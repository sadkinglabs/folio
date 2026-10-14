package dev.sadking.folio

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
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
    val lookupResults = MutableStateFlow<List<MetadataCandidate>>(emptyList())
    val lookupCover = MutableStateFlow<String?>(null)
    val lookupProposal = MutableStateFlow<MetadataProposal?>(null)
    private val lookup = MetadataLookup()

    /** Library work. A transfer job never blocks it: queued transfers re-check revisions. */
    private fun run(label: String, work: suspend () -> String) =
        runner.launch(viewModelScope, label, device = false, work)

    private fun safeError(e: Exception) = dev.sadking.folio.safeError(e)

    fun removeLocal(ids: Set<String>, onRemoved: () -> Unit = {}) =
        run("Removing from phone library…") {
            val (count, cleaned) = repo.removeLocal(ids)
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
            withContext(Dispatchers.Main) { done() }
            "Changes saved. Your original EPUB is kept"
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
            "Lists updated"
        }

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
                LibraryBackup(repo).restore(temp)
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
                withContext(Dispatchers.Main) { done() }
                "Selected details saved. Your original EPUB is kept"
            } finally {
                file?.delete()
            }
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
}
