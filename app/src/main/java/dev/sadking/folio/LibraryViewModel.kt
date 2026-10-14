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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class Operation(
    val busy: Boolean = false,
    val message: String = "",
    val progress: Float? = null,
    val failed: Boolean = false,
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FolioApplication
    private val repo = app.repository
    private val dao = repo.dao
    private val coordinator = app.syncCoordinator
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
        run("Installing KOReader companion…") { BridgeInstaller(app).install(folder) }

    fun clearFolderReport() {
        folderReport.value = emptyList()
    }

    fun syncPairedFolder() =
        run("Comparing paired folders…") {
            try {
                pairedFolder.reconcile()
            } finally {
                folderReport.value = pairedFolder.lastReport
            }
        }

    val activeSyncTasks =
        dao.activeTasks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val syncJobs = dao.jobs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val removed =
        dao.removed().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val importResults = MutableStateFlow<List<ImportOutcome>>(emptyList())
    val showImportResults = MutableStateFlow(false)
    val managementError = MutableStateFlow<String?>(null)
    val books =
        dao.books().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val shelves =
        dao.shelves().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val memberships =
        dao.memberships().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history =
        dao.history().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val transfers =
        dao.transferFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val phoneReading =
        dao.phoneReadingFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val reading =
        dao.reading().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val annotations =
        dao.annotations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val statistics =
        dao.statistics().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val lookupResults = MutableStateFlow<List<MetadataCandidate>>(emptyList())
    val lookupCover = MutableStateFlow<String?>(null)
    val lookupProposal = MutableStateFlow<MetadataProposal?>(null)
    private val lookup = MetadataLookup()
    val operation = MutableStateFlow(Operation())
    val notices = MutableSharedFlow<Operation>(extraBufferCapacity = 8)
    val observedHost = MutableStateFlow<ObservedHostKey?>(null)
    val probeStatus = MutableStateFlow(Operation())
    private val probeGeneration = java.util.concurrent.atomic.AtomicLong()
    val device = MutableStateFlow(app.deviceSettings.load())
    val plan = MutableStateFlow<List<SyncItem>>(emptyList())
    val inventory = MutableStateFlow<List<RemoteEntry>>(emptyList())
    val inventoryCheckedAt = MutableStateFlow<Long?>(null)
    private var planDevice: String? = null
    private val operations = Mutex()

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
    }

    private fun run(label: String, work: suspend () -> String) {
        viewModelScope.launch {
            operations.withLock {
                operation.value = Operation(true, label)
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            check(syncJobs.value.none { it.active }) {
                                "Finish or cancel the active sync before changing the library or pairing"
                            }
                            work()
                        }
                    operation.value = Operation(message = result)
                    notices.emit(operation.value)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    operation.value = Operation(message = safeError(e), failed = true)
                    notices.emit(operation.value)
                }
            }
        }
    }

    fun removeLocal(ids: Set<String>, onRemoved: () -> Unit = {}) =
        run("Removing from phone library…") {
            val (count, cleaned) = repo.removeLocal(ids)
            plan.value = emptyList()
            withContext(Dispatchers.Main) { onRemoved() }
            "$count moved to Recently removed; restore from Settings. Kindle and Hardcover retained" +
                if (!cleaned) ". Some private files could not be cleaned up" else ""
        }

    fun restoreRemoved(group: String) =
        run("Restoring book…") {
            repo.restoreRemoved(group)
            "Book and its cached reading data restored"
        }

    fun purgeRemoved(group: String) =
        run("Deleting private book copies…") {
            val cleaned = repo.purgeRemoved(group)
            "Removed permanently from phone; Kindle and Hardcover retained" +
                if (!cleaned) ". Some orphan private files remain" else ""
        }

    fun saveCatalogue(id: String?, metadata: Metadata, cover: Uri?, onSaved: () -> Unit) =
        run("Saving book…") {
            managementError.value = null
            try {
                repo.saveCatalogue(id, metadata, cover)
            } catch (e: Exception) {
                managementError.value = safeError(e)
                throw e
            }
            withContext(Dispatchers.Main) { onSaved() }
            "Book metadata saved on phone"
        }

    fun manageBatch(
        ids: Set<String>,
        shelf: String? = null,
        add: Boolean = true,
        status: String? = null,
        onDone: () -> Unit = {},
    ) =
        run("Updating selected books…") {
            repo.manageBatch(ids, shelf, add, status)
            withContext(Dispatchers.Main) { onDone() }
            "Selected books updated on phone"
        }

    fun detach(book: Book, done: () -> Unit) =
        run("Detaching catalogue entry…") {
            repo.detach(book.id)
            withContext(Dispatchers.Main) { done() }
            "Attachment cleared. EPUB, lists and reading data retained; attach the correct edition from the catalogue entry"
        }

    fun saveShelf(shelf: Shelf, onDone: () -> Unit) =
        run("Saving list…") {
            managementError.value = null
            try {
                require(shelf.name.trim().isNotBlank() && shelf.name.trim().length <= 120) {
                    "Enter a list name up to 120 characters"
                }
                require(
                    shelf.ruleField in LibraryRules.fields &&
                        (shelf.ruleField in listOf("Manual", "Missing on Kindle") ||
                            shelf.ruleValue.isNotBlank())
                ) {
                    "Choose a rule and enter its value"
                }
                require(
                    dao.allShelves().none {
                        it.id != shelf.id && it.name.equals(shelf.name.trim(), true)
                    }
                ) {
                    "A list with this name already exists"
                }
                dao.updateShelf(
                    shelf.copy(name = shelf.name.trim(), ruleValue = shelf.ruleValue.trim())
                )
            } catch (e: Exception) {
                managementError.value = safeError(e)
                throw e
            }
            withContext(Dispatchers.Main) { onDone() }
            "List saved"
        }

    fun deleteShelf(shelf: Shelf, done: () -> Unit) =
        run("Removing list…") {
            dao.deleteShelf(shelf.id)
            withContext(Dispatchers.Main) { done() }
            "List removed; books and Kindle collections retained"
        }

    fun import(uris: List<Uri>) =
        run("Importing books…") {
            val results =
                uris.distinct().map { uri ->
                    runCatching {
                        app.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    val name =
                        runCatching {
                                app.contentResolver
                                    .query(
                                        uri,
                                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                                        null,
                                        null,
                                        null,
                                    )
                                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                            }
                            .getOrNull()
                            ?.take(200) ?: "Selected EPUB"
                    try {
                        val (book, added) = repo.import(uri)
                        ImportOutcome(
                            uri.toString(),
                            name,
                            if (added) "Imported" else "Already in library / restored",
                            book.id,
                            null,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ImportOutcome(uri.toString(), name, "Failed", null, importFailure(e))
                    }
                }
            importResults.value = results
            showImportResults.value = true
            plan.value = emptyList()
            "${results.count { it.error == null }} available · ${results.count { it.error != null }} failed. Review import results"
        }

    fun exportEpub(book: Book, uri: Uri) =
        run("Exporting EPUB…") {
            repo.lock.withLock {
                val current = dao.book(book.id) ?: error("Book no longer exists")
                require(dao.removedEntry(book.id) == null) { "Restore this book first" }
                val file = File(current.currentPath)
                require(file.canonicalFile.parentFile == File(repo.root, book.id).canonicalFile) {
                    "Invalid book location"
                }
                app.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: error("Cannot write selected destination")
            }
            "Current EPUB revision exported"
        }

    fun openEpub(book: Book, share: Boolean) =
        run(if (share) "Preparing EPUB share…" else "Preparing reader…") {
            val ready =
                repo.lock.withLock {
                    val current = dao.book(book.id) ?: error("Book no longer exists")
                    require(dao.removedEntry(book.id) == null) { "Restore this book first" }
                    val file = File(current.currentPath)
                    require(file.canonicalFile.parentFile == File(repo.root, book.id).canonicalFile)
                    val folder = File(app.cacheDir, "epub-share").apply { mkdirs() }
                    File(folder, "${book.id}.epub").also { file.copyTo(it, overwrite = true) }
                }
            val uri =
                androidx.core.content.FileProvider.getUriForFile(
                    app,
                    "${app.packageName}.photos",
                    ready,
                )
            withContext(Dispatchers.Main) {
                val intent =
                    if (share)
                        android.content
                            .Intent(android.content.Intent.ACTION_SEND)
                            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            .setType("application/epub+zip")
                    else
                        android.content
                            .Intent(android.content.Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/epub+zip")
                intent.clipData = android.content.ClipData.newUri(app.contentResolver, "EPUB", uri)
                intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    val chooser =
                        android.content.Intent.createChooser(
                                intent,
                                if (share) "Share EPUB" else "Open EPUB",
                            )
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    chooser.putExtra(
                        android.content.Intent.EXTRA_EXCLUDE_COMPONENTS,
                        arrayOf(android.content.ComponentName(app, MainActivity::class.java)),
                    )
                    app.startActivity(chooser)
                } catch (_: android.content.ActivityNotFoundException) {
                    error("Install an EPUB reader or sharing app, or export the file instead")
                }
            }
            "EPUB handed to Android"
        }

    fun edit(book: Book, metadata: Metadata, cover: Uri?, done: () -> Unit) =
        run("Saving metadata…") {
            repo.edit(book.id, metadata, cover)
            plan.value = emptyList()
            withContext(Dispatchers.Main) { done() }
            "Metadata saved in a new EPUB revision"
        }

    fun status(book: Book, status: String) =
        run("Updating reading status…") {
            repo.manageBatch(setOf(book.id), status = status)
            "Reading status updated on phone"
        }

    fun createShelf(name: String, field: String = "Manual", value: String = "") =
        run("Creating collection…") {
            require(name.isNotBlank())
            require(name.length <= 120 && field in LibraryRules.fields)
            require(field in listOf("Manual", "Missing on Kindle") || value.isNotBlank()) {
                "Enter a shelf rule value"
            }
            dao.insertShelf(Shelf(UUID.randomUUID().toString(), name.trim(), field, value.trim()))
            "Collection created"
        }

    fun membership(book: String, shelf: String, selected: Boolean) =
        run("Updating collection…") {
            repo.manageBatch(setOf(book), shelfId = shelf, add = selected)
            "Collection updated"
        }

    fun probeHost(host: String, port: Int) {
        val generation = probeGeneration.incrementAndGet()
        observedHost.value = null
        probeStatus.value = Operation(true, "Reading Kindle fingerprint…")
        run("Reading Kindle SSH fingerprint…") {
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
        run(if (create == null) "Reading Kindle folders…" else "Creating Kindle folder…") {
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
        run("Saving Kindle connection…") {
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
        run("Removing pairing…") {
            app.deviceSettings.forget()
            device.value = DeviceConfig()
            plan.value = emptyList()
            inventory.value = emptyList()
            inventoryCheckedAt.value = null
            "Pairing removed"
        }

    private fun connect() = SftpDevice(device.value, app.deviceSettings.credential())

    fun preview(selected: Set<String>? = null) =
        run("Connecting and comparing books…") { prepare(selected) }

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
            val removed =
                dao.removed()
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
            val importResults = MutableStateFlow<List<ImportOutcome>>(emptyList())
            val showImportResults = MutableStateFlow(false)
            val managementError = MutableStateFlow<String?>(null)
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

    fun sync() = run("Synchronising…") { performSync() }

    fun syncMissing() =
        run("Checking Kindle for missing books…") {
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
                            allBooks
                                .filter { b ->
                                    if (shelf.ruleField == "Manual")
                                        allMemberships.any {
                                            it.bookId == b.id && it.shelfId == shelf.id
                                        }
                                    else
                                        LibraryRules.matches(
                                            shelf.ruleField,
                                            shelf.ruleValue,
                                            b.metadata(),
                                            b.status,
                                            b.id in presentIds,
                                        )
                                }
                                .map { it.id }
                        }
                        .toSet()
            plan.value = SyncPlanner.scope(plan.value, selected)
            if (plan.value.any { it.action == Action.UPLOAD }) performSync()
            else
                "Nothing to upload · ${plan.value.count { it.action == Action.CONFLICT }} conflicts left unchanged"
        }

    fun sendBook(book: Book) =
        run("Sending ${book.title} to Kindle…") {
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
        run("Resuming queued sync…") {
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

    fun shelfBookIds(shelf: Shelf): Set<String> =
        books.value
            .filter { b ->
                if (shelf.ruleField == "Manual")
                    memberships.value.any { it.bookId == b.id && it.shelfId == shelf.id }
                else
                    LibraryRules.matches(
                        shelf.ruleField,
                        shelf.ruleValue,
                        b.metadata(),
                        b.status,
                        transfers.value.any {
                            it.bookId == b.id &&
                                it.deviceId == device.value.id &&
                                it.hash == b.currentHash
                        },
                    )
            }
            .map { it.id }
            .toSet()

    fun shelfPolicy(shelf: Shelf, enabled: Boolean) =
        run("Saving sync policy…") {
            dao.updateShelf(shelf.copy(syncEnabled = enabled))
            "Collection sync preference saved"
        }

    fun previewShelf(shelf: Shelf) = preview(shelfBookIds(shelf))

    fun exportBackup(uri: Uri) =
        run("Creating verified backup…") {
            val temp = File(app.cacheDir, "backup-${UUID.randomUUID()}.zip")
            try {
                LibraryBackup(repo).export(temp)
                app.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    temp.inputStream().use { it.copyTo(out) }
                } ?: error("Cannot write backup")
                "Backup saved · originals, revisions, collections and imported reading data included"
            } finally {
                temp.delete()
            }
        }

    fun restoreBackup(uri: Uri) =
        run("Validating and restoring backup…") {
            val temp = File(app.cacheDir, "restore-${UUID.randomUUID()}.zip")
            try {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { Epub.copyBounded(input, it, BackupArchive.MAX_TOTAL) }
                } ?: error("Cannot open backup")
                LibraryBackup(repo).restore(temp).also { plan.value = emptyList() }
            } finally {
                temp.delete()
            }
        }

    fun searchMetadata(query: String) =
        run("Searching Open Library…") {
            lookupProposal.value = null
            lookupResults.value = emptyList()
            lookupResults.value = lookup.search(query)
            "${lookupResults.value.size} metadata matches · choose an edition to review"
        }

    fun selectMetadata(candidate: MetadataCandidate) =
        run("Loading edition metadata…") {
            lookupProposal.value = null
            lookupCover.value?.let { File(it).delete() }
            lookupCover.value = null
            val proposal = lookup.proposal(candidate)
            proposal.coverId?.let { id ->
                runCatching {
                    val file = File(app.cacheDir, "cover-preview-${UUID.randomUUID()}.jpg")
                    file.writeBytes(lookup.cover(id))
                    lookupCover.value = file.absolutePath
                }
            }
            lookupProposal.value = proposal
            "Select the fields you want to replace"
        }

    fun clearLookup() {
        lookupResults.value = emptyList()
        lookupProposal.value = null
        lookupCover.value?.let { File(it).delete() }
        lookupCover.value = null
    }

    fun applyMetadata(book: Book, metadata: Metadata, coverId: Long?, done: () -> Unit) =
        run("Applying selected metadata…") {
            val file = coverId?.let { id ->
                File(app.cacheDir, "lookup-${UUID.randomUUID()}.jpg").also {
                    it.writeBytes(lookup.cover(id))
                }
            }
            try {
                repo.edit(book.id, metadata, file?.let(Uri::fromFile))
                plan.value = emptyList()
                withContext(Dispatchers.Main) { done() }
                "Selected fields saved in a new EPUB revision"
            } finally {
                file?.delete()
            }
        }

    fun refreshReading() = refreshReadingFor(null)

    fun refreshBookReading(bookId: String) = refreshReadingFor(setOf(bookId))

    private fun refreshReadingFor(selected: Set<String>?) =
        run("Retrieving KOReader reading data…") {
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

    fun exportHighlights(book: Book, uri: Uri) =
        run("Exporting highlights…") {
            val values =
                dao.allAnnotations()
                    .filter { it.bookId == book.id }
                    .map {
                        ReaderAnnotation(
                            it.id,
                            it.text,
                            it.note,
                            it.chapter,
                            it.date,
                            it.position,
                            it.endPosition,
                            it.kind,
                        )
                    }
            val text = KoReaderData.markdown(book.title, book.authorLabel, values)
            app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                ?: error("Cannot write Markdown file")
            "Highlights exported as Markdown"
        }

    fun sendCollections() =
        run("Preparing KOReader collection additions…") {
            val shelves = dao.allShelves().filter { it.syncEnabled }
            require(shelves.isNotEmpty()) { "Select Include in Sync on at least one collection" }
            val local = dao.allBooks()
            val members = dao.allMemberships()
            val known = dao.transfers(device.value.id).associateBy { it.bookId }
            val config = device.value
            val requests = mutableListOf<org.json.JSONObject>()
            connect().use { remote ->
                shelves.forEach { shelf ->
                    val selected = local.filter { b ->
                        if (shelf.ruleField == "Manual")
                            members.any { it.bookId == b.id && it.shelfId == shelf.id }
                        else
                            LibraryRules.matches(
                                shelf.ruleField,
                                shelf.ruleValue,
                                b.metadata(),
                                b.status,
                                known[b.id]?.hash == b.currentHash,
                            )
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
        run("Retrieving exported reading statistics…") {
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

    private fun safeError(e: Exception): String =
        when (e) {
            is com.jcraft.jsch.JSchException ->
                when {
                    e.message.orEmpty().contains("HostKey", ignoreCase = true) ->
                        "SSH host key changed or was rejected. Check the IP shown by KOReader, then read the fingerprint in setup and explicitly review any replacement."
                    e.message.orEmpty().contains("Auth fail", ignoreCase = true) ->
                        if (device.value.authentication == "KOReader password-free")
                            "KOReader rejected password-free access. Stop SSH, disable key-only login, enable Login without password and restart SSH. Check the username is root."
                        else
                            "SSH authentication was rejected. Check the username, authorized public key and key passphrase or password."
                    e.message.orEmpty().contains("Algorithm negotiation", ignoreCase = true) ->
                        "The SSH server's algorithms are unsupported. Update its configuration; obsolete algorithms are not automatically enabled."
                    else ->
                        "SSH connection failed. Check Wi-Fi, the Kindle's address, SSH port and whether its server is running."
                }
            is com.jcraft.jsch.SftpException ->
                "SFTP operation failed. Check folder permissions, storage and connection; preview again before retrying."
            is IllegalArgumentException,
            is IllegalStateException -> e.message?.take(200) ?: "Operation could not be completed"
            else ->
                "Operation failed. Check the file, available storage and connection, then try again."
        }
}

data class FolderListing(
    val config: DeviceConfig,
    val relative: String,
    val directories: List<DeviceDirectory>,
)
