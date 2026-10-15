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

    fun removeLocal(ids: Set<String>, onRemoved: () -> Unit = {}) =
        run(msg(R.string.msg_library_removing)) {
            val (count, cleaned) = repo.removeLocal(ids)
            withContext(Dispatchers.Main) { onRemoved() }
            withLeftovers(
                app.resources.getQuantityString(R.plurals.msg_library_removed, count, count),
                cleaned,
            )
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

    fun exportEpub(book: Book, uri: Uri) =
        run(msg(R.string.msg_library_exporting_epub)) {
            repo.lock.withLock {
                val current = dao.book(book.id) ?: error(msg(R.string.msg_book_gone))
                require(dao.removedEntry(book.id) == null) {
                    msg(R.string.msg_library_restore_first)
                }
                val file = File(current.currentPath)
                require(file.canonicalFile.parentFile == File(repo.root, book.id).canonicalFile) {
                    msg(R.string.msg_library_file_misplaced)
                }
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
                    require(
                        file.canonicalFile.parentFile == File(repo.root, book.id).canonicalFile
                    ) {
                        msg(R.string.msg_library_file_misplaced)
                    }
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

    fun createShelf(name: String, field: String = "Manual", value: String = "") =
        run(msg(R.string.msg_library_creating_list)) {
            require(name.isNotBlank()) { msg(R.string.msg_library_list_name) }
            require(name.length <= 120 && field in LibraryRules.fields)
            require(field in listOf("Manual", "Missing on Kindle") || value.isNotBlank()) {
                msg(R.string.msg_library_list_rule)
            }
            dao.insertShelf(Shelf(UUID.randomUUID().toString(), name.trim(), field, value.trim()))
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
            try {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { Epub.copyBounded(input, it, BackupArchive.MAX_TOTAL) }
                } ?: error(msg(R.string.msg_library_backup_unreadable))
                LibraryBackup(repo).restore(temp)
            } finally {
                temp.delete()
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
}
