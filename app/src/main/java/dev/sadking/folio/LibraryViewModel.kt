package dev.sadking.folio

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
import dev.sadking.folio.reader.koreaderDigest
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock

/** The phone library: books, lists, editing, metadata lookup and backups. */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FolioApplication
    private val repo = app.repository
    private val dao = repo.dao
    private val runner = app.operations
    /** Shared with [DeviceViewModel]: one status line and one queue for both. */
    val operation = runner.state
    val notices = runner.notices
    val removed =
        dao.removed().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val importResults = MutableStateFlow<List<ImportOutcome>>(emptyList())
    val showImportResults = MutableStateFlow(false)
    /** True while [refreshFolder] runs, for the Library's pull-to-refresh indicator. */
    val folderRefreshing = MutableStateFlow(false)
    /** Kept across passes: it remembers which Inbox files are still being written. */
    private val inbox by lazy { BooksInbox(repo) }
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

    /** KOReader document digests by revision hash, so each file is sampled once. */
    private val digests = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The book behind the bar's centre item: last read anywhere, phone or e-reader through Folio
     * Link. Computed off the main thread whenever books, positions or link reports change.
     */
    val currentBook: StateFlow<CurrentBook?> =
        combine(books, phoneReading, dao.kosyncFlow()) { books, phone, reports ->
                dev.sadking.folio.data.currentBook(
                    books,
                    phone,
                    reports,
                    app.link.settings.phoneDeviceId,
                ) { book ->
                    digests[book.currentHash]
                        ?: File(book.currentPath)
                            .takeIf { book.currentPath.isNotBlank() && it.isFile }
                            ?.let { runCatching { koreaderDigest(it) }.getOrNull() }
                            ?.also { digests[book.currentHash] = it }
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val lookupResults = MutableStateFlow<List<MetadataCandidate>>(emptyList())
    val lookupCover = MutableStateFlow<String?>(null)
    val lookupProposal = MutableStateFlow<MetadataProposal?>(null)
    private val lookup = MetadataLookup()

    /** Library work. A transfer job never blocks it: queued transfers re-check revisions. */
    private fun run(label: String, work: suspend () -> String) =
        runner.launch(viewModelScope, label, device = false, work)

    private fun safeError(e: Exception) = dev.sadking.folio.safeError(e, app.resources)

    private fun msg(id: Int, vararg args: Any) =
        if (args.isEmpty()) app.getString(id) else app.getString(id, *args)

    /** [message], followed by a note when some private files could not be deleted. */
    private fun withLeftovers(message: String, cleaned: Boolean) =
        if (cleaned) message else message + " " + msg(R.string.msg_library_leftover_files)

    /** Removes books to Recently removed; the message offers Undo, which brings them back. */
    fun removeLocal(ids: Set<String>, onRemoved: () -> Unit = {}) =
        runner.launchUndoable(viewModelScope, msg(R.string.msg_library_removing), device = false) {
            val (count, cleaned, groups) = repo.removeLocal(ids)
            withContext(Dispatchers.Main) { onRemoved() }
            withLeftovers(
                app.resources.getQuantityString(R.plurals.msg_library_removed, count, count),
                cleaned,
            ) to { undoRemoval(groups) }
        }

    private fun undoRemoval(groups: List<String>) =
        run(msg(R.string.msg_library_restoring)) {
            groups.forEach { repo.restoreRemoved(it) }
            msg(R.string.msg_library_restored)
        }

    fun restoreRemoved(group: String) =
        run(msg(R.string.msg_library_restoring)) {
            repo.restoreRemoved(group)
            msg(R.string.msg_library_restored)
        }

    fun purgeRemoved(group: String) =
        run(msg(R.string.msg_library_purging)) {
            val cleaned = repo.purgeRemoved(group)
            withLeftovers(msg(R.string.msg_library_purged), cleaned)
        }

    fun saveCatalogue(id: String?, metadata: Metadata, cover: Uri?, onSaved: () -> Unit) =
        run(msg(R.string.msg_library_saving_book)) {
            managementError.value = null
            try {
                repo.saveCatalogue(id, metadata, cover)
            } catch (e: Exception) {
                managementError.value = safeError(e)
                throw e
            }
            withContext(Dispatchers.Main) { onSaved() }
            msg(R.string.msg_library_book_saved)
        }

    fun manageBatch(
        ids: Set<String>,
        shelf: String? = null,
        add: Boolean = true,
        status: String? = null,
        onDone: () -> Unit = {},
    ) =
        run(msg(R.string.msg_library_updating_selected)) {
            repo.manageBatch(ids, shelf, add, status)
            withContext(Dispatchers.Main) { onDone() }
            msg(R.string.msg_library_selected_updated)
        }

    fun detach(book: Book, done: () -> Unit) =
        run(msg(R.string.msg_library_detaching)) {
            repo.detach(book.id)
            withContext(Dispatchers.Main) { done() }
            msg(R.string.msg_library_detached)
        }

    fun saveShelf(shelf: Shelf, onDone: () -> Unit) =
        run(msg(R.string.msg_library_saving_list)) {
            managementError.value = null
            try {
                require(shelf.name.trim().isNotBlank() && shelf.name.trim().length <= 120) {
                    msg(R.string.msg_library_list_name)
                }
                require(
                    shelf.ruleField in LibraryRules.fields &&
                        (shelf.ruleField in listOf("Manual", "Missing on Kindle") ||
                            shelf.ruleValue.isNotBlank())
                ) {
                    msg(R.string.msg_library_list_rule)
                }
                require(
                    dao.allShelves().none {
                        it.id != shelf.id && it.name.equals(shelf.name.trim(), true)
                    }
                ) {
                    msg(R.string.msg_library_list_exists)
                }
                dao.updateShelf(
                    shelf.copy(name = shelf.name.trim(), ruleValue = shelf.ruleValue.trim())
                )
            } catch (e: Exception) {
                managementError.value = safeError(e)
                throw e
            }
            withContext(Dispatchers.Main) { onDone() }
            msg(R.string.msg_library_list_saved)
        }

    fun deleteShelf(shelf: Shelf, done: () -> Unit) =
        run(msg(R.string.msg_library_removing_list)) {
            dao.deleteShelf(shelf.id)
            withContext(Dispatchers.Main) { done() }
            msg(R.string.msg_library_list_removed)
        }

    fun import(uris: List<Uri>) =
        run(msg(R.string.msg_library_importing)) {
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
                            ?.take(200) ?: msg(R.string.msg_library_import_unnamed)
                    try {
                        val (book, added) = repo.import(uri)
                        ImportOutcome(
                            uri.toString(),
                            name,
                            msg(
                                if (added) R.string.msg_library_import_added
                                else R.string.msg_library_import_existing
                            ),
                            book.id,
                            null,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ImportOutcome(
                            uri.toString(),
                            name,
                            msg(R.string.msg_library_import_failed),
                            null,
                            importFailure(e),
                        )
                    }
                }
            importResults.value = results
            showImportResults.value = true
            msg(
                R.string.msg_library_imported,
                results.count { it.error == null },
                results.count { it.error != null },
            )
        }

    /**
     * Moves every book still in private storage into the Books folder (M8). Each file is checked
     * before its private copy goes; a book that fails stays where it is and keeps working.
     */
    fun moveToBooksFolder() =
        run(msg(R.string.msg_books_folder_moving)) {
            require(dev.sadking.folio.data.BooksFolder(app).hasAccess) {
                msg(R.string.msg_books_folder_no_access)
            }
            moveMessage(dev.sadking.folio.data.BooksFolderMove(repo).run())
        }

    private fun moveMessage(result: MoveResult): String {
        val moved =
            app.resources.getQuantityString(
                R.plurals.msg_books_folder_moved,
                result.moved,
                result.moved,
            )
        return if (result.failed.isEmpty()) moved
        else
            moved +
                " " +
                app.resources.getQuantityString(
                    R.plurals.msg_books_folder_stayed,
                    result.failed.size,
                    result.failed.size,
                    result.failed.joinToString(", "),
                )
    }

    /**
     * Brings the library up to date with the Books folder (M9 step 2): moves books still in private
     * storage (once per app start), files what is in the Inbox, then scans the folder. Runs when
     * Librillo comes to the foreground and on pull-to-refresh, at most once every 30 seconds unless
     * [force] (a pull) or All files access was just turned on. Quiet unless something changed.
     */
    fun refreshFolder(force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            // One refresh at a time, across activities; a second request while one runs is dropped.
            if (!folderRun.compareAndSet(false, true)) return@launch
            try {
                val ready = repo.folderReady()
                val now = android.os.SystemClock.elapsedRealtime()
                val accessJustGranted = ready && !lastReady
                lastReady = ready
                if (!ready) return@launch
                if (!force && !accessJustGranted && now - lastRefresh < REFRESH_INTERVAL_MS)
                    return@launch
                lastRefresh = now
                folderRefreshing.value = true
                val lines = mutableListOf<String>()
                if (!autoMoved) {
                    autoMoved = true
                    val move = BooksFolderMove(repo)
                    if (move.pending() > 0) {
                        val result = move.run()
                        if (result.moved > 0 || result.failed.isNotEmpty())
                            lines += moveMessage(result)
                    }
                }
                var filed = inbox.run()
                if (filed.waiting > 0) {
                    // A file still being written gets one more look once it has had time to settle.
                    delay(INBOX_SETTLE_MS)
                    filed += inbox.run()
                }
                filed.message(app.resources)?.let { lines += it }
                if (filed.added.isNotEmpty() || filed.notImported.isNotEmpty())
                    importResults.value = inboxOutcomes(filed)
                val scan = app.folderScan
                val before = scan.lastResult()
                val missingBefore = scan.missing()
                val found = scan.run()
                // Only news goes in the message: a book that was already missing, or a file that
                // already couldn't be read, is not reported again on every check.
                val newlyMissing = (scan.missing() - missingBefore).size
                val newlyNotAdded = (found.notAdded - before?.notAdded.orEmpty().toSet()).size
                lines += scanLines(found, newlyNotAdded)
                val missing = found.missing.size
                val news =
                    lines +
                        listOfNotNull(
                            newlyMissing
                                .takeIf { it > 0 }
                                ?.let { plural(R.plurals.msg_scan_m9_missing, it) }
                        )
                if (news.isNotEmpty())
                    runner.notices.emit(Operation(message = news.joinToString(" ")))
                if (found.finishedAt > 0)
                    // Settings shows this check's news, and how many books still miss their file.
                    FolderScanNotes.recordScan(
                        FolderScanNotes.preferences(app),
                        found.finishedAt,
                        (lines +
                                listOfNotNull(
                                    missing
                                        .takeIf { it > 0 }
                                        ?.let { plural(R.plurals.msg_scan_m9_missing, it) }
                                ))
                            .joinToString(" "),
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runner.notices.emit(
                    Operation(message = msg(R.string.msg_folder_m9_refresh_failed), failed = true)
                )
            } finally {
                folderRefreshing.value = false
                folderRun.set(false)
            }
        }
    }

    private fun plural(id: Int, count: Int) = app.resources.getQuantityString(id, count, count)

    /** The scan's news in plain lines: books added, followed and changed, and unreadable files. */
    private fun scanLines(found: ScanResult, newlyNotAdded: Int) =
        listOfNotNull(
            found.added.size.takeIf { it > 0 }?.let { plural(R.plurals.msg_scan_m9_added, it) },
            found.relinked.size
                .takeIf { it > 0 }
                ?.let { plural(R.plurals.msg_scan_m9_relinked, it) },
            found.revised.size.takeIf { it > 0 }?.let { plural(R.plurals.msg_scan_m9_revised, it) },
            newlyNotAdded.takeIf { it > 0 }?.let { plural(R.plurals.msg_scan_m9_not_added, it) },
        )

    /** Inbox results for Settings → Imports, with the reason for each file not imported. */
    private fun inboxOutcomes(result: InboxResult): List<ImportOutcome> =
        (result.added + result.notImported).map { entry ->
            ImportOutcome(
                Uri.fromFile(entry.file).toString(),
                entry.name.take(200),
                msg(
                    when (entry.reason) {
                        null -> R.string.msg_library_import_added
                        InboxReason.ALREADY_IN_LIBRARY -> R.string.msg_library_import_existing
                        else -> R.string.msg_library_import_failed
                    }
                ),
                entry.bookId,
                entry.reason?.takeIf { it != InboxReason.ALREADY_IN_LIBRARY }?.text(app.resources),
            )
        }

    fun exportEpub(book: Book, uri: Uri) =
        run(msg(R.string.msg_library_exporting_epub)) {
            repo.lock.withLock {
                val current = dao.book(book.id) ?: error(msg(R.string.msg_book_gone))
                require(dao.removedEntry(book.id) == null) {
                    msg(R.string.msg_library_restore_first)
                }
                val file = File(current.currentPath)
                require(repo.holds(book.id, file)) { msg(R.string.msg_library_file_misplaced) }
                app.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: error(msg(R.string.msg_write_failed))
            }
            msg(R.string.msg_library_epub_exported)
        }

    fun openEpub(book: Book, share: Boolean) =
        run(
            msg(
                if (share) R.string.msg_library_preparing_share
                else R.string.msg_library_preparing_open
            )
        ) {
            val ready =
                repo.lock.withLock {
                    val current = dao.book(book.id) ?: error(msg(R.string.msg_book_gone))
                    require(dao.removedEntry(book.id) == null) {
                        msg(R.string.msg_library_restore_first)
                    }
                    val file = File(current.currentPath)
                    require(repo.holds(book.id, file)) { msg(R.string.msg_library_file_misplaced) }
                    val folder = File(app.cacheDir, "epub-share").apply { mkdirs() }
                    File(folder, "${book.id}.${current.format}").also {
                        file.copyTo(it, overwrite = true)
                    } to bookMimeType(current.format)
                }
            val uri =
                androidx.core.content.FileProvider.getUriForFile(
                    app,
                    "${app.packageName}.photos",
                    ready.first,
                )
            withContext(Dispatchers.Main) {
                val intent =
                    if (share)
                        android.content
                            .Intent(android.content.Intent.ACTION_SEND)
                            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            .setType(ready.second)
                    else
                        android.content
                            .Intent(android.content.Intent.ACTION_VIEW)
                            .setDataAndType(uri, ready.second)
                intent.clipData = android.content.ClipData.newUri(app.contentResolver, "Book", uri)
                intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    val chooser =
                        android.content.Intent.createChooser(
                                intent,
                                msg(
                                    if (share) R.string.msg_library_share_title
                                    else R.string.msg_library_open_title
                                ),
                            )
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    chooser.putExtra(
                        android.content.Intent.EXTRA_EXCLUDE_COMPONENTS,
                        arrayOf(android.content.ComponentName(app, MainActivity::class.java)),
                    )
                    app.startActivity(chooser)
                } catch (_: android.content.ActivityNotFoundException) {
                    error(msg(R.string.msg_library_no_app))
                }
            }
            msg(R.string.msg_library_choose_app)
        }

    fun edit(book: Book, metadata: Metadata, cover: Uri?, done: () -> Unit) =
        run(msg(R.string.msg_library_saving_changes)) {
            repo.edit(book.id, metadata, cover)
            withContext(Dispatchers.Main) { done() }
            msg(R.string.msg_library_changes_saved)
        }

    fun status(book: Book, status: String) =
        run(msg(R.string.msg_library_updating_status)) {
            repo.manageBatch(setOf(book.id), status = status)
            msg(R.string.msg_library_status_updated)
        }

    fun createShelf(
        name: String,
        field: String = "Manual",
        value: String = "",
        syncEnabled: Boolean = false,
    ) =
        run(msg(R.string.msg_library_creating_list)) {
            require(name.isNotBlank()) { msg(R.string.msg_library_list_name) }
            require(name.length <= 120 && field in LibraryRules.fields)
            require(field in listOf("Manual", "Missing on Kindle") || value.isNotBlank()) {
                msg(R.string.msg_library_list_rule)
            }
            dao.insertShelf(
                Shelf(UUID.randomUUID().toString(), name.trim(), field, value.trim(), syncEnabled)
            )
            msg(R.string.msg_library_list_created)
        }

    fun membership(book: String, shelf: String, selected: Boolean) =
        run(msg(R.string.msg_library_updating_lists)) {
            repo.manageBatch(setOf(book), shelfId = shelf, add = selected)
            msg(R.string.msg_library_lists_updated)
        }

    fun exportBackup(uri: Uri) =
        run(msg(R.string.msg_library_backing_up)) {
            val temp = File(app.cacheDir, "backup-${UUID.randomUUID()}.zip")
            try {
                LibraryBackup(repo).export(temp)
                app.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    temp.inputStream().use { it.copyTo(out) }
                } ?: error(msg(R.string.msg_write_failed))
                msg(R.string.msg_library_backup_saved)
            } finally {
                temp.delete()
            }
        }

    fun restoreBackup(uri: Uri) =
        run(msg(R.string.msg_library_restoring_backup)) {
            val temp = File(app.cacheDir, "restore-${UUID.randomUUID()}.zip")
            val restored =
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use {
                            Epub.copyBounded(input, it, BackupArchive.MAX_TOTAL)
                        }
                    } ?: error(msg(R.string.msg_library_backup_unreadable))
                    LibraryBackup(repo).restore(temp)
                } finally {
                    temp.delete()
                }
            // Restored books land in private storage; with folder access they go on to the folder.
            if (!repo.folderReady()) restored
            else {
                val move = BooksFolderMove(repo).run()
                if (move.moved == 0 && move.failed.isEmpty()) restored
                else restored + " " + moveMessage(move)
            }
        }

    fun searchMetadata(query: String) =
        run(msg(R.string.msg_library_searching_open_library)) {
            lookupProposal.value = null
            lookupResults.value = emptyList()
            lookupResults.value = lookup.search(query)
            val found = lookupResults.value.size
            app.resources.getQuantityString(R.plurals.msg_library_metadata_matches, found, found)
        }

    fun selectMetadata(candidate: MetadataCandidate) =
        run(msg(R.string.msg_library_loading_edition)) {
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
            msg(R.string.msg_library_choose_fields)
        }

    fun clearLookup() {
        lookupResults.value = emptyList()
        lookupProposal.value = null
        lookupCover.value?.let { File(it).delete() }
        lookupCover.value = null
    }

    fun applyMetadata(book: Book, metadata: Metadata, coverId: Long?, done: () -> Unit) =
        run(msg(R.string.msg_library_applying_details)) {
            val file = coverId?.let { id ->
                File(app.cacheDir, "lookup-${UUID.randomUUID()}.jpg").also {
                    it.writeBytes(lookup.cover(id))
                }
            }
            try {
                repo.edit(book.id, metadata, file?.let(Uri::fromFile))
                withContext(Dispatchers.Main) { done() }
                msg(R.string.msg_library_details_saved)
            } finally {
                file?.delete()
            }
        }

    fun exportHighlights(book: Book, uri: Uri) =
        run(msg(R.string.msg_library_exporting_highlights)) {
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
                ?: error(msg(R.string.msg_write_failed))
            msg(R.string.msg_library_highlights_exported)
        }

    private companion object {
        const val REFRESH_INTERVAL_MS = 30_000L
        const val INBOX_SETTLE_MS = 6_000L
        /** One folder refresh at a time, across activities. */
        val folderRun = AtomicBoolean(false)
        @Volatile var lastRefresh = -REFRESH_INTERVAL_MS
        @Volatile var lastReady = false
        /** The automatic move runs once per app start, the first time access is on. */
        @Volatile var autoMoved = false
    }
}
