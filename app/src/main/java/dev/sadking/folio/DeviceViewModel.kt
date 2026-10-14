package dev.sadking.folio

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
import dev.sadking.folio.device.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * The e-reader and the paired phone folder: pairing, comparison, transfers and the reading data
 * brought back from KOReader.
 */
class DeviceViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FolioApplication
    private val repo = app.repository
    private val dao = repo.dao
    private val coordinator = app.syncCoordinator
    private val runner = app.operations
    private val notices = runner.notices
    private val pairedFolder = PairedFolder(app)
    val phoneFolder = MutableStateFlow(pairedFolder.label)

    fun choosePhoneFolder(uri: Uri) =
        run("Connecting phone folder…") {
            pairedFolder.select(uri)
            phoneFolder.value = pairedFolder.label
            val added = pairedFolder.refresh()
            "Phone folder connected · $added EPUBs imported. Tap Sync in the library to compare both folders."
        }

    fun forgetPhoneFolder() =
        run("Disconnecting phone folder…") {
            pairedFolder.forget()
            phoneFolder.value = ""
            "Phone folder disconnected. Library and Kindle books retained."
        }

    fun refreshPhoneFolder() =
        run("Refreshing phone folder…") {
            "${pairedFolder.refresh()} new EPUBs imported from the phone folder"
        }

    val folderReport = MutableStateFlow<List<String>>(emptyList())

    fun installCompanion(folder: String) =
        runOnDevice("Installing KOReader companion…") { BridgeInstaller(app).install(folder) }

    fun clearFolderReport() {
        folderReport.value = emptyList()
    }

    fun syncPairedFolder() =
        runOnDevice("Comparing paired folders…") {
            try {
                pairedFolder.reconcile()
            } finally {
                folderReport.value = pairedFolder.lastReport
            }
        }

    val activeSyncTasks =
        dao.activeTasks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val syncJobs = dao.jobs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val observedHost = MutableStateFlow<ObservedHostKey?>(null)
    val probeStatus = MutableStateFlow(Operation())
    private val probeGeneration = java.util.concurrent.atomic.AtomicLong()
    val device = MutableStateFlow(app.deviceSettings.load())
    val plan = MutableStateFlow<List<SyncItem>>(emptyList())
    val inventory = MutableStateFlow<List<RemoteEntry>>(emptyList())
    val inventoryCheckedAt = MutableStateFlow<Long?>(null)
    private var planDevice: String? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            coordinator.awaitRecovery()
            val config = device.value
            app.database.withTransaction {
                val current = dao.transfers(config.id).associateBy { it.bookId }
                dao.transfers(config.legacyId).forEach { old ->
                    if (current[old.bookId]?.completedAt?.let { it >= old.completedAt } != true)
                        dao.transfer(old.copy(deviceId = config.id))
                }
                dao.deleteLegacyTransfers(config.legacyId)
                dao.rekeyJournal(config.legacyId, config.id)
            }
        }
        // A comparison describes the revisions it was made from. Any change to the library (an
        // edit, an import, a restore, a removal) makes it stale, wherever the change came from.
        viewModelScope.launch {
            var last: Set<Pair<String, String>>? = null
            dao.books()
                .map { books -> books.map { it.id to it.currentHash }.toSet() }
                .distinctUntilChanged()
                .collect {
                    if (last != null && last != it) {
                        plan.value = emptyList()
                        planDevice = null
                    }
                    last = it
                }
        }
    }

    /** Library work started from the device screens. */
    private fun run(label: String, work: suspend () -> String) =
        runner.launch(viewModelScope, label, device = false, work)

    /** Work that talks to the device or changes pairing; it waits for the transfer job. */
    private fun runOnDevice(label: String, work: suspend () -> String) =
        runner.launch(viewModelScope, label, device = true, work)

    private fun safeError(e: Exception) =
        dev.sadking.folio.safeError(e, device.value.authentication)

    fun probeHost(host: String, port: Int) {
        val generation = probeGeneration.incrementAndGet()
        observedHost.value = null
        probeStatus.value = Operation(true, "Reading Kindle fingerprint…")
        runOnDevice("Reading Kindle SSH fingerprint…") {
            try {
                val observed = HostFingerprint.read(host.trim(), port)
                if (probeGeneration.get() == generation) {
                    observedHost.value = observed
                    probeStatus.value =
                        Operation(
                            message =
                                "Fingerprint read. Confirm the Kindle address before trusting it"
                        )
                }
                "Fingerprint read; no login or file access was attempted"
            } catch (e: Exception) {
                if (probeGeneration.get() == generation)
                    probeStatus.value = Operation(message = safeError(e), failed = true)
                throw e
            }
        }
    }

    fun clearHostProbe() {
        probeGeneration.incrementAndGet()
        observedHost.value = null
        probeStatus.value = Operation()
    }

    val folderStatus = MutableStateFlow(Operation())
    val folderListing = MutableStateFlow<FolderListing?>(null)
    private val folderGeneration = java.util.concurrent.atomic.AtomicLong()

    fun clearFolders() {
        folderGeneration.incrementAndGet()
        folderListing.value = null
        folderStatus.value = Operation()
    }

    fun browseFolders(
        config: DeviceConfig,
        password: String,
        key: Uri?,
        relative: String = "",
        create: String? = null,
    ) {
        val generation = folderGeneration.incrementAndGet()
        runOnDevice(if (create == null) "Reading Kindle folders…" else "Creating Kindle folder…") {
            folderStatus.value = Operation(busy = true, message = "Reading Kindle folders…")
            try {
                val saved = app.deviceSettings.load()
                val credential =
                    when (config.authentication) {
                        "KOReader password-free" -> DeviceCredential("")
                        "Password" ->
                            if (password.isNotEmpty()) DeviceCredential(password)
                            else {
                                check(
                                    saved.fingerprint == config.fingerprint &&
                                        saved.username == config.username &&
                                        saved.authentication == config.authentication
                                ) {
                                    "Enter the SSH password first"
                                }
                                app.deviceSettings.credential()
                            }
                        else ->
                            if (key != null) {
                                val bytes =
                                    app.contentResolver.openInputStream(key)?.use { input ->
                                        java.io
                                            .ByteArrayOutputStream()
                                            .also { Epub.copyBounded(input, it, 65536) }
                                            .toByteArray()
                                    } ?: error("Cannot open private key")
                                DeviceCredential(password, bytes)
                            } else {
                                check(
                                    saved.fingerprint == config.fingerprint &&
                                        saved.username == config.username &&
                                        saved.authentication == config.authentication
                                ) {
                                    "Select your SSH private key first"
                                }
                                app.deviceSettings.credential().let {
                                    if (password.isNotEmpty()) it.copy(secret = password) else it
                                }
                            }
                    }
                SftpDevice(config.copy(directory = "/mnt/us"), credential).use { remote ->
                    if (create != null) remote.createDirectory(relative, create)
                    val dirs = remote.directories(relative)
                    if (folderGeneration.get() == generation)
                        folderListing.value = FolderListing(config, relative, dirs)
                }
                if (folderGeneration.get() == generation) folderStatus.value = Operation()
                if (create == null) "Kindle folders loaded" else "Folder created on Kindle"
            } catch (e: Exception) {
                if (folderGeneration.get() == generation)
                    folderStatus.value = Operation(message = safeError(e), failed = true)
                throw e
            }
        }
    }

    fun saveDevice(
        config: DeviceConfig,
        password: String,
        keyUri: Uri? = null,
        onSaved: () -> Unit = {},
    ) =
        runOnDevice("Saving Kindle connection…") {
            val keyBytes =
                keyUri
                    ?.takeIf { config.authentication == "Key" }
                    ?.let { uri ->
                        app.contentResolver.openInputStream(uri)?.use { input ->
                            java.io
                                .ByteArrayOutputStream()
                                .also { Epub.copyBounded(input, it, 65536) }
                                .toByteArray()
                        } ?: error("Cannot open private key")
                    }
            app.deviceSettings.save(config, password, keyBytes)
            device.value = config
            observedHost.value = null
            plan.value = emptyList()
            inventory.value = emptyList()
            inventoryCheckedAt.value = null
            withContext(Dispatchers.Main) { onSaved() }
            "Pairing saved. Review sync to verify access."
        }

    fun forgetDevice() =
        runOnDevice("Removing pairing…") {
            app.deviceSettings.forget()
            device.value = DeviceConfig()
            plan.value = emptyList()
            inventory.value = emptyList()
            inventoryCheckedAt.value = null
            "Pairing removed"
        }

    private fun connect() = SftpDevice(device.value, app.deviceSettings.credential())

    fun preview(selected: Set<String>? = null) =
        runOnDevice("Connecting and comparing books…") { prepare(selected) }

    private suspend fun prepare(selected: Set<String>?): String {
        require(selected == null || selected.isNotEmpty()) {
            "This collection has no books to compare"
        }
        plan.value = emptyList()
        planDevice = null
        val config = device.value
        connect().use { remote ->
            inventory.value = remote.inventory()
            inventoryCheckedAt.value = System.currentTimeMillis()
            val existing = dao.transfers(config.id).associateBy { it.bookId }
            val books =
                dao.allBooks()
                    .filter { selected == null || it.id in selected }
                    .map { b ->
                        SyncBook(
                            b.id,
                            b.title,
                            File(b.currentPath),
                            b.currentHash,
                            existing[b.id]?.hash,
                            existing[b.id]?.remotePath,
                        )
                    }
            plan.value = SyncPlanner.plan(books, remote)
            planDevice = config.id
            // Presence is only recorded after an actual hash comparison, never inferred from
            // filenames.
            plan.value
                .filter { it.action == Action.PRESENT }
                .forEach {
                    dao.transfer(
                        Transfer(
                            it.book.id,
                            config.id,
                            it.path,
                            it.book.hash,
                            System.currentTimeMillis(),
                        )
                    )
                }
        }
        return "Comparison ready · ${inventory.value.size} EPUBs in Kindle folder"
    }

    fun sync() = runOnDevice("Synchronising…") { performSync() }

    fun syncMissing() =
        runOnDevice("Checking Kindle for missing books…") {
            val enabled = dao.allShelves().filter { it.syncEnabled }
            prepare(null)
            val presentIds =
                plan.value.filter { it.action == Action.PRESENT }.map { it.book.id }.toSet()
            val allBooks = dao.allBooks()
            val allMemberships = dao.allMemberships()
            val selected =
                if (enabled.isEmpty()) null
                else
                    enabled
                        .flatMap { shelf ->
                            collectionBooks(shelf, allBooks, allMemberships) { it.id in presentIds }
                                .map { it.id }
                        }
                        .toSet()
            plan.value = SyncPlanner.scope(plan.value, selected)
            if (plan.value.any { it.action == Action.UPLOAD }) performSync()
            else
                "Nothing to upload · ${plan.value.count { it.action == Action.CONFLICT }} conflicts left unchanged"
        }

    fun sendBook(book: Book) =
        runOnDevice("Sending ${book.title} to Kindle…") {
            check(device.value.host.isNotBlank()) { "Pair your Kindle first" }
            prepare(setOf(book.id))
            val item = plan.value.singleOrNull() ?: error("Book is no longer available")
            when (item.action) {
                Action.UPLOAD -> performSync()
                Action.PRESENT -> "This EPUB is already verified on Kindle"
                Action.CONFLICT ->
                    error(
                        "The Kindle has another revision. Review the conflict in Kindle; existing books and annotations are retained."
                    )
            }
        }

    private suspend fun performSync(): String {
        check(planDevice == device.value.id) { "Pairing changed; preview again" }
        val items = plan.value.filter { it.action == Action.UPLOAD }
        val id = coordinator.enqueue(items, device.value.id)
        startJob(id)
        plan.value = plan.value.filter { it.action != Action.UPLOAD }
        return "${items.size} books queued. Follow transfer progress in Kindle or the notification"
    }

    private suspend fun startJob(id: String) {
        try {
            withContext(Dispatchers.Main) { dev.sadking.folio.sync.SyncService.start(app, id) }
        } catch (e: Exception) {
            coordinator.startFailed(id)
            error("Sync saved but Android could not start it. Tap Resume in Kindle")
        }
    }

    fun resumeSync(id: String) =
        runOnDevice("Resuming queued sync…") {
            coordinator.prepareResume(id)
            startJob(id)
            "Sync resumed; completed files are verified without duplicate uploads"
        }

    fun cancelSync(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                coordinator.cancel(id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                notices.emit(Operation(message = safeError(e), failed = true))
            }
        }
    }

    /** Books a collection selects; [onDevice] says whether a book counts as on the e-reader. */
    private fun collectionBooks(
        shelf: Shelf,
        books: List<Book>,
        memberships: List<Membership>,
        onDevice: (Book) -> Boolean,
    ): List<Book> = books.filter { b ->
        if (shelf.ruleField == "Manual")
            memberships.any { it.bookId == b.id && it.shelfId == shelf.id }
        else
            LibraryRules.matches(
                shelf.ruleField,
                shelf.ruleValue,
                b.metadata(),
                b.status,
                onDevice(b),
            )
    }

    fun shelfPolicy(shelf: Shelf, enabled: Boolean) =
        run("Saving sync policy…") {
            dao.updateShelf(shelf.copy(syncEnabled = enabled))
            "Collection sync preference saved"
        }

    fun previewShelf(shelf: Shelf) =
        runOnDevice("Connecting and comparing books…") {
            val verified = dao.transfers(device.value.id).associateBy { it.bookId }
            val ids =
                collectionBooks(shelf, dao.allBooks(), dao.allMemberships()) {
                        verified[it.id]?.hash == it.currentHash
                    }
                    .map { it.id }
                    .toSet()
            prepare(ids)
        }

    fun refreshReading() = refreshReadingFor(null)

    fun refreshBookReading(bookId: String) = refreshReadingFor(setOf(bookId))

    private fun refreshReadingFor(selected: Set<String>?) =
        runOnDevice("Retrieving KOReader reading data…") {
            var imported = 0
            var missing = 0
            var failed = 0
            var conflicts = 0
            val config = device.value
            val local = dao.allBooks().associateBy { it.id }
            connect().use { remote ->
                for (transfer in
                    dao.transfers(config.id).filter { selected == null || it.bookId in selected }) {
                    val book = local[transfer.bookId] ?: continue
                    try {
                        if (
                            !remote.exists(transfer.remotePath) ||
                                remote.read(transfer.remotePath).use { Epub.hash(it) } !=
                                    transfer.hash
                        ) {
                            conflicts++
                            continue
                        }
                        val data = ReadingRetriever.read(remote, transfer.remotePath)
                        if (data == null) {
                            missing++
                            continue
                        }
                        val (value, hash) = data
                        app.database.withTransaction {
                            dao.reading(
                                ReadingSnapshot(
                                    book.id,
                                    config.id,
                                    value.percent,
                                    value.position,
                                    value.status,
                                    value.rating,
                                    System.currentTimeMillis(),
                                    ReadingRetriever.sidecar(transfer.remotePath),
                                    hash,
                                    transfer.hash,
                                )
                            )
                            value.annotations.forEach { a ->
                                val id =
                                    KoReaderData.digest(
                                        (book.id + "\u0000" + config.id + "\u0000" + a.key)
                                            .toByteArray()
                                    )
                                dao.annotation(
                                    BookAnnotation(
                                        id,
                                        book.id,
                                        config.id,
                                        a.text,
                                        a.note,
                                        a.chapter,
                                        a.date,
                                        a.position,
                                        a.endPosition,
                                        a.kind,
                                    )
                                )
                            }
                        }
                        imported++
                    } catch (e: Exception) {
                        failed++
                    }
                }
            }
            "$imported reading snapshots refreshed · $missing adjacent sidecars absent · $conflicts file conflicts · $failed unsupported or changed snapshots. Existing annotations retained."
        }

    fun sendCollections() =
        runOnDevice("Preparing KOReader collection additions…") {
            val shelves = dao.allShelves().filter { it.syncEnabled }
            require(shelves.isNotEmpty()) { "Select Include in Sync on at least one collection" }
            val local = dao.allBooks()
            val members = dao.allMemberships()
            val known = dao.transfers(device.value.id).associateBy { it.bookId }
            val config = device.value
            val requests = mutableListOf<org.json.JSONObject>()
            connect().use { remote ->
                shelves.forEach { shelf ->
                    val selected =
                        collectionBooks(shelf, local, members) {
                            known[it.id]?.hash == it.currentHash
                        }
                    val paths = selected.map { b ->
                        val record =
                            known[b.id]
                                ?: error(
                                    "Transfer or verify every collection book before sending its membership"
                                )
                        require(
                            record.hash == b.currentHash &&
                                remote.exists(record.remotePath) &&
                                remote.read(record.remotePath).use { Epub.hash(it) } ==
                                    b.currentHash
                        ) {
                            "Collection contains a missing or changed EPUB; preview its transfer first"
                        }
                        "${config.directory.trimEnd('/')}/${record.remotePath}"
                    }
                    requests +=
                        org.json
                            .JSONObject()
                            .put("name", shelf.name)
                            .put("paths", org.json.JSONArray(paths))
                }
                val json =
                    org.json
                        .JSONObject()
                        .put("format", "folio-collections")
                        .put("version", 1)
                        .put("createdAt", System.currentTimeMillis())
                        .put("shelves", org.json.JSONArray(requests))
                        .toString()
                val source = File(app.cacheDir, "collections-${UUID.randomUUID()}.json")
                val temporary = ".folio-${UUID.randomUUID()}.part"
                val destination = ".folio-collections-${UUID.randomUUID()}.json"
                try {
                    source.writeText(json)
                    require(source.length() <= 4L * 1024 * 1024)
                    remote.upload(temporary, source) { _, _ -> }
                    require(remote.read(temporary).use { Epub.hash(it) } == Epub.sha256(source))
                    remote.publish(temporary, destination)
                    require(remote.read(destination).use { Epub.hash(it) } == Epub.sha256(source))
                } finally {
                    source.delete()
                    runCatching { remote.removeTemporary(temporary) }
                }
            }
            "Collection request sent. In KOReader's Folio bridge menu, review and apply additions. Existing memberships are retained."
        }

    fun refreshStatistics() =
        runOnDevice("Retrieving exported reading statistics…") {
            val config = device.value
            connect().use { remote ->
                require(remote.exists(".folio-statistics.json")) {
                    "In KOReader's Folio bridge menu, export reading statistics first"
                }
                fun bytes() =
                    java.io
                        .ByteArrayOutputStream()
                        .also { out ->
                            remote.read(".folio-statistics.json").use {
                                Epub.copyBounded(it, out, 4L * 1024 * 1024)
                            }
                        }
                        .toByteArray()
                val first = bytes()
                require(first.contentEquals(bytes())) { "Statistics export changed; retry" }
                val o = org.json.JSONObject(first.toString(Charsets.UTF_8))
                require(o.getString("format") == "folio-statistics" && o.getInt("version") == 1)
                val value =
                    DeviceStatistics(
                        config.id,
                        o.getLong("seconds"),
                        o.getLong("exportedAt"),
                        System.currentTimeMillis(),
                        o.getJSONArray("days").toString(),
                    )
                validateStatistics(value)
                dao.statistics(value)
            }
            "Reading statistics saved offline"
        }
}

data class FolderListing(
    val config: DeviceConfig,
    val relative: String,
    val directories: List<DeviceDirectory>,
)
