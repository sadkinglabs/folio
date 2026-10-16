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
/** What a send did, in one line: queued, already there, and what needs review. */
internal fun sendSummary(
    resources: android.content.res.Resources,
    queued: Int,
    present: Int,
    differ: Int,
): String {
    if (queued == 0 && differ == 0) return resources.getString(R.string.send_nothing_missing)
    return listOfNotNull(
            resources.getQuantityString(R.plurals.send_queued, queued, queued).takeIf {
                queued > 0
            },
            resources.getQuantityString(R.plurals.send_present, present, present).takeIf {
                present > 0
            },
            resources.getQuantityString(R.plurals.send_differ, differ, differ).takeIf {
                differ > 0
            },
        )
        .joinToString(" · ")
}

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
        run(app.getString(R.string.msg_device_connecting_folder)) {
            pairedFolder.select(uri)
            phoneFolder.value = pairedFolder.label
            val added = pairedFolder.refresh()
            app.resources.getQuantityString(R.plurals.msg_device_folder_connected, added, added)
        }

    fun forgetPhoneFolder() =
        run(app.getString(R.string.msg_device_disconnecting_folder)) {
            pairedFolder.forget()
            phoneFolder.value = ""
            app.getString(R.string.msg_device_folder_disconnected)
        }

    fun refreshPhoneFolder() =
        run(app.getString(R.string.msg_device_refreshing_folder)) {
            val added = pairedFolder.refresh()
            app.resources.getQuantityString(R.plurals.msg_device_folder_refreshed, added, added)
        }

    val folderReport = MutableStateFlow<List<String>>(emptyList())

    fun installCompanion() =
        runOnDevice(app.getString(R.string.msg_device_installing_companion)) {
            BridgeInstaller(app).install()
        }

    fun clearFolderReport() {
        folderReport.value = emptyList()
    }

    fun syncPairedFolder() =
        runOnDevice(app.getString(R.string.msg_device_comparing_folders)) {
            try {
                pairedFolder.reconcile()
            } finally {
                folderReport.value = pairedFolder.lastReport
            }
        }

    val activeSyncTasks =
        dao.activeTasks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** KOReader document digests by revision hash, so each file is sampled once. */
    private val digests = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** E-readers that reached the phone through Folio Link, newest first. Read-only. */
    val linkEreaders: StateFlow<List<EreaderSeen>> =
        combine(dao.kosyncFlow(), dao.transferFlow(), dao.books()) { reports, transfers, books ->
                ereadersSeen(reports, transfers, books, app.link.settings.phoneDeviceId) { book ->
                    digests[book.currentHash]
                        ?: File(book.currentPath)
                            .takeIf { book.currentPath.isNotBlank() && it.isFile }
                            ?.let {
                                runCatching { dev.sadking.folio.reader.koreaderDigest(it) }
                                    .getOrNull()
                            }
                            ?.also { digests[book.currentHash] = it }
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Books waiting for Folio Link e-readers, oldest first. Read-only. */
    val linkQueue: StateFlow<List<QueuedBook>> =
        combine(app.linkQueue.queued, dao.books(), dao.transferFlow()) { ids, books, transfers ->
                queuedBooks(ids, books, transfers)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Takes a book out of the Folio Link queue. Nothing on any e-reader changes. */
    fun removeFromLinkQueue(id: String) = app.linkQueue.remove(id)

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
        dev.sadking.folio.safeError(e, app.resources, device.value.authentication)

    fun probeHost(host: String, port: Int) {
        val generation = probeGeneration.incrementAndGet()
        observedHost.value = null
        probeStatus.value = Operation(true, app.getString(R.string.msg_pairing_reading_fingerprint))
        runOnDevice(app.getString(R.string.msg_pairing_reading_ssh_fingerprint)) {
            try {
                val observed = HostFingerprint.read(host.trim(), port)
                if (probeGeneration.get() == generation) {
                    observedHost.value = observed
                    probeStatus.value =
                        Operation(message = app.getString(R.string.msg_pairing_fingerprint_confirm))
                }
                app.getString(R.string.msg_pairing_fingerprint_read)
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
        runOnDevice(
            app.getString(
                if (create == null) R.string.msg_pairing_reading_folders
                else R.string.msg_pairing_creating_folder
            )
        ) {
            folderStatus.value =
                Operation(
                    busy = true,
                    message = app.getString(R.string.msg_pairing_reading_folders),
                )
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
                                    app.getString(R.string.msg_pairing_password_first)
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
                                    } ?: error(app.getString(R.string.msg_pairing_key_unreadable))
                                DeviceCredential(password, bytes)
                            } else {
                                check(
                                    saved.fingerprint == config.fingerprint &&
                                        saved.username == config.username &&
                                        saved.authentication == config.authentication
                                ) {
                                    app.getString(R.string.msg_pairing_key_first)
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
                app.getString(
                    if (create == null) R.string.msg_pairing_folders_loaded
                    else R.string.msg_pairing_folder_created
                )
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
        runOnDevice(app.getString(R.string.msg_pairing_saving)) {
            val keyBytes =
                keyUri
                    ?.takeIf { config.authentication == "Key" }
                    ?.let { uri ->
                        app.contentResolver.openInputStream(uri)?.use { input ->
                            java.io
                                .ByteArrayOutputStream()
                                .also { Epub.copyBounded(input, it, 65536) }
                                .toByteArray()
                        } ?: error(app.getString(R.string.msg_pairing_key_unreadable))
                    }
            app.deviceSettings.save(config, password, keyBytes)
            device.value = config
            observedHost.value = null
            plan.value = emptyList()
            inventory.value = emptyList()
            inventoryCheckedAt.value = null
            withContext(Dispatchers.Main) { onSaved() }
            app.getString(R.string.msg_pairing_saved)
        }

    fun forgetDevice() =
        runOnDevice(app.getString(R.string.msg_pairing_removing)) {
            app.deviceSettings.forget()
            device.value = DeviceConfig()
            plan.value = emptyList()
            inventory.value = emptyList()
            inventoryCheckedAt.value = null
            app.getString(R.string.msg_pairing_removed)
        }

    private fun connect() = SftpDevice(device.value, app.deviceSettings.credential())

    fun preview(selected: Set<String>? = null) =
        runOnDevice(app.getString(R.string.msg_device_comparing)) { prepare(selected) }

    private suspend fun prepare(selected: Set<String>?): String {
        require(selected == null || selected.isNotEmpty()) {
            app.getString(R.string.msg_device_nothing_to_compare)
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
        val found = inventory.value.size
        return app.resources.getQuantityString(R.plurals.msg_device_compared, found, found)
    }

    fun sync() = runOnDevice(app.getString(R.string.msg_device_sending)) { performSync() }

    fun syncMissing() =
        runOnDevice(app.getString(R.string.send_comparing)) {
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
            val differ = plan.value.count { it.action == Action.CONFLICT }
            if (plan.value.any { it.action == Action.UPLOAD }) performSync()
            else if (differ == 0) app.getString(R.string.msg_device_nothing_to_send)
            else
                app.resources.getQuantityString(
                    R.plurals.msg_device_nothing_to_send_differ,
                    differ,
                    differ,
                )
        }

    /** With Folio Link on, sending queues books for Link e-readers instead of copying over SSH. */
    val sendsThroughLink: Boolean
        get() = app.link.settings.enabled

    private suspend fun queueForLink(ids: Set<String>): String {
        require(ids.isNotEmpty()) { app.getString(R.string.msg_device_book_unavailable) }
        // Sending again means: get it again. The plugin still finds a copy that is there and
        // keeps it; only a book deleted from the e-reader is downloaded once more.
        ids.forEach { app.database.library().forgetLinkDeliveries(it) }
        app.linkQueue.add(ids)
        return app.resources.getQuantityString(R.plurals.link_queued, ids.size, ids.size)
    }

    fun sendBook(book: Book) =
        if (sendsThroughLink)
            run(app.getString(R.string.link_queueing)) { queueForLink(setOf(book.id)) }
        else
            runOnDevice(app.getString(R.string.msg_device_sending_book, book.title)) {
                check(device.value.host.isNotBlank()) { app.getString(R.string.send_pair_first) }
                prepare(setOf(book.id))
                val item =
                    plan.value.singleOrNull()
                        ?: error(app.getString(R.string.msg_device_book_unavailable))
                when (item.action) {
                    Action.UPLOAD -> performSync()
                    Action.PRESENT -> app.getString(R.string.msg_device_book_present)
                    Action.CONFLICT -> error(app.getString(R.string.msg_device_book_differs))
                }
            }

    private suspend fun performSync(): String {
        check(planDevice == device.value.id) {
            app.getString(R.string.msg_device_connection_changed)
        }
        val items = plan.value.filter { it.action == Action.UPLOAD }
        val id = coordinator.enqueue(items, device.value.id)
        startJob(id)
        plan.value = plan.value.filter { it.action != Action.UPLOAD }
        return app.resources.getQuantityString(R.plurals.msg_device_queued, items.size, items.size)
    }

    private suspend fun startJob(id: String) {
        try {
            withContext(Dispatchers.Main) { dev.sadking.folio.sync.SyncService.start(app, id) }
        } catch (e: Exception) {
            coordinator.startFailed(id)
            error(app.getString(R.string.msg_device_start_failed))
        }
    }

    fun resumeSync(id: String) =
        runOnDevice(app.getString(R.string.msg_device_resuming)) {
            coordinator.prepareResume(id)
            startJob(id)
            app.getString(R.string.msg_device_resumed)
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
        run(app.getString(R.string.msg_device_saving_sync_setting)) {
            dao.updateShelf(shelf.copy(syncEnabled = enabled))
            app.getString(R.string.msg_device_sync_setting_saved)
        }

    /** Compares the chosen books with the e-reader and queues the ones it is missing. */
    fun sendBooks(ids: Set<String>) =
        if (sendsThroughLink) run(app.getString(R.string.link_queueing)) { queueForLink(ids) }
        else runOnDevice(app.getString(R.string.send_comparing)) { send(ids) }

    fun sendShelf(shelf: Shelf) =
        if (sendsThroughLink)
            run(app.getString(R.string.link_queueing)) {
                queueForLink(
                    collectionBooks(shelf, dao.allBooks(), dao.allMemberships()) { false }
                        .map { it.id }
                        .toSet()
                )
            }
        else
            runOnDevice(app.getString(R.string.send_comparing)) {
                val verified = dao.transfers(device.value.id).associateBy { it.bookId }
                val ids =
                    collectionBooks(shelf, dao.allBooks(), dao.allMemberships()) {
                            verified[it.id]?.hash == it.currentHash
                        }
                        .map { it.id }
                        .toSet()
                send(ids)
            }

    /**
     * Uploads only what is missing. Books that differ on the e-reader are never overwritten; they
     * stay in the comparison on the Device tab for review.
     */
    private suspend fun send(ids: Set<String>): String {
        check(device.value.host.isNotBlank()) { app.getString(R.string.send_pair_first) }
        prepare(ids)
        val items = plan.value
        val queued = items.count { it.action == Action.UPLOAD }
        if (queued > 0) performSync()
        return sendSummary(
            app.resources,
            queued,
            items.count { it.action == Action.PRESENT },
            items.count { it.action == Action.CONFLICT },
        )
    }

    fun refreshReading() = refreshReadingFor(null)

    fun refreshBookReading(bookId: String) = refreshReadingFor(setOf(bookId))

    private fun refreshReadingFor(selected: Set<String>?) =
        runOnDevice(app.getString(R.string.msg_device_getting_reading)) {
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
            fun plural(id: Int, n: Int) = app.resources.getQuantityString(id, n, n)
            val summary =
                listOfNotNull(
                        plural(R.plurals.msg_device_reading_updated, imported),
                        plural(R.plurals.msg_device_reading_none, missing).takeIf { missing > 0 },
                        plural(R.plurals.msg_device_reading_differ, conflicts).takeIf {
                            conflicts > 0
                        },
                        plural(R.plurals.msg_device_reading_unreadable, failed).takeIf {
                            failed > 0
                        },
                    )
                    .joinToString(" · ")
            app.getString(R.string.msg_device_reading_kept, summary)
        }

    fun sendCollections() =
        runOnDevice(app.getString(R.string.msg_device_sending_collections)) {
            val shelves = dao.allShelves().filter { it.syncEnabled }
            require(shelves.isNotEmpty()) {
                app.getString(R.string.msg_device_collections_none)
            }
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
                                ?: error(app.getString(R.string.msg_device_collections_send_books))
                        require(
                            record.hash == b.currentHash &&
                                remote.exists(record.remotePath) &&
                                remote.read(record.remotePath).use { Epub.hash(it) } ==
                                    b.currentHash
                        ) {
                            app.getString(R.string.msg_device_collections_changed)
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
            app.getString(R.string.msg_device_collections_sent)
        }

    fun refreshStatistics() =
        runOnDevice(app.getString(R.string.msg_device_getting_statistics)) {
            val config = device.value
            connect().use { remote ->
                require(remote.exists(".folio-statistics.json")) {
                    app.getString(R.string.msg_device_statistics_export_first)
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
                require(first.contentEquals(bytes())) {
                    app.getString(R.string.msg_device_statistics_changed)
                }
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
            app.getString(R.string.msg_device_statistics_saved)
        }
}

data class FolderListing(
    val config: DeviceConfig,
    val relative: String,
    val directories: List<DeviceDirectory>,
)
