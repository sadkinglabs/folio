package dev.sadking.folio.sync

import androidx.room.withTransaction
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.core.Epub
import dev.sadking.folio.core.sync.Baseline
import dev.sadking.folio.core.sync.PhoneAction
import dev.sadking.folio.core.sync.PhoneBook
import dev.sadking.folio.core.sync.Question
import dev.sadking.folio.core.sync.SyncAnnotation
import dev.sadking.folio.core.sync.SyncPending
import dev.sadking.folio.data.LibraryRepository
import dev.sadking.folio.data.Membership
import dev.sadking.folio.data.Shelf
import dev.sadking.folio.data.SyncBaseline
import dev.sadking.folio.data.WishMembership
import dev.sadking.folio.data.decode
import dev.sadking.folio.data.encode
import dev.sadking.folio.link.SyncLibrary
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock

/**
 * The phone's side of sync protocol v2: Librillo's library in Room and the Books folder, as the
 * Link server's [dev.sadking.folio.link.SyncRoutes] sees it. docs/SYNC_PROTOCOL.md has the rules.
 * The server calls it from its own threads, one sync step at a time, so every call blocks.
 *
 * Nothing here deletes a book file or an annotation. A book removed by sync goes to Recently
 * removed; a new file from the e-reader is a new book or a new revision, beside the old file.
 */
class RoomSyncLibrary
internal constructor(
    private val repo: LibraryRepository,
    /** Questions, answers and last sync per e-reader. */
    val state: SyncState,
    /** Where uploads wait when the Books folder can't be used. */
    private val cacheDir: File,
    private val now: () -> Long = System::currentTimeMillis,
) : SyncLibrary {
    constructor(app: FolioApplication) : this(app.repository, SyncState.of(app), app.cacheDir)

    private val dao = repo.dao
    private val baselineDao = repo.database.syncBaselines()

    override fun phoneBooks(): List<PhoneBook> = io {
        val removed = dao.allRemoved().map { it.id }.toSet()
        val manual =
            dao.allShelves().filter { it.ruleField == MANUAL }.associate { it.id to it.name }
        val lists =
            dao.everyMembership()
                .filter { it.shelfId in manual }
                .groupBy({ it.bookId }, { manual.getValue(it.shelfId) })
        val notes = dao.everyAnnotation().groupBy { it.bookId }
        val canRead = repo.folderAccess()
        dao.allStoredBooks()
            .sortedBy { it.id }
            .map { book ->
                val file = File(book.currentPath)
                val present = book.currentPath.isNotBlank() && file.isFile
                PhoneBook(
                    id = book.id,
                    sha256 = book.currentHash,
                    format = book.format,
                    title = book.title,
                    authors = runCatching { decode(book.authors) }.getOrDefault(emptyList()),
                    status = book.status,
                    statusAt = book.statusAt,
                    removed = book.id in removed,
                    // Without folder access the folder can't be seen; that is not a missing file.
                    hasFile =
                        present ||
                            (!canRead && book.currentPath.isNotBlank() && repo.inBooksFolder(file)),
                    annotations = SyncAnnotations.forSync(notes[book.id].orEmpty()),
                    lists = lists[book.id].orEmpty().distinct().sorted(),
                    size = if (present) file.length() else 0,
                )
            }
    }

    override fun baselines(ereaderId: String): List<Baseline> =
        baselineDao.forEreader(ereaderId).map { it.toBaseline() }.sortedBy { it.bookId }

    override fun commit(ereaderId: String, baselines: List<Baseline>) {
        val at = now()
        repo.database.runInTransaction {
            baselineDao.deleteForEreader(ereaderId)
            baselineDao.upsertAll(baselines.map { it.toRow(ereaderId, at) })
        }
    }

    override fun bookFile(bookId: String): File? = io {
        val book = dao.book(bookId) ?: return@io null
        File(book.currentPath).takeIf {
            book.currentPath.isNotBlank() && it.isFile && repo.holds(bookId, it)
        }
    }

    /**
     * Adds the upload as a new book the way an import does: its format read from its bytes, filed
     * at `Author/Title.ext` in the Books folder when it can be used, else in private storage, next
     * to existing files and never over one. Content the library already has returns that book,
     * without restoring it from Recently removed: only a Restore action does that.
     */
    override fun receive(ereaderId: String, kindlePath: String, file: File): String? = attempt {
        io {
            repo.lock.withLock {
                if (!file.isFile || file.length() > Epub.MAX_FILE) return@withLock null
                dao.duplicate(Epub.sha256(file))?.id
                    ?: repo.addLocked(file, fileName(kindlePath), repo.folderReady()).first.id
            }
        }
    }

    /** Takes the upload as the book's new revision; the old file is kept (see reviseLocked). */
    override fun revise(
        ereaderId: String,
        bookId: String,
        kindlePath: String,
        file: File,
    ): String? = attempt {
        io { repo.lock.withLock { repo.reviseLocked(bookId, file, fileName(kindlePath))?.id } }
    }

    /** Each action on its own: one that fails is skipped, and the next sync tries it again. */
    override fun apply(
        ereaderId: String,
        actions: List<PhoneAction>,
        received: Map<String, String>,
    ) {
        for (action in actions) attempt { io { applyOne(ereaderId, action, received) } }
    }

    private suspend fun applyOne(
        ereaderId: String,
        action: PhoneAction,
        received: Map<String, String>,
    ) {
        when (action) {
            is PhoneAction.Import -> {
                val bookId = received[action.path] ?: return
                action.status?.let { setStatus(bookId, it, action.statusAt ?: now()) }
                addAnnotations(ereaderId, bookId, action.annotations)
                // The book may have been in the library already: its lists are kept.
                if (action.lists.isNotEmpty()) setLists(bookId, action.lists, keepOthers = true)
            }
            is PhoneAction.Restore ->
                dao.removedEntry(action.bookId)?.let { repo.restoreRemoved(it.groupId) }
            // The file was taken in revise(), before the actions.
            is PhoneAction.Revise -> Unit
            is PhoneAction.Remove -> repo.removeLocal(setOf(action.bookId))
            is PhoneAction.SetStatus -> setStatus(action.bookId, action.status, action.at)
            is PhoneAction.AddAnnotations ->
                addAnnotations(ereaderId, action.bookId, action.annotations)
            is PhoneAction.SetLists -> setLists(action.bookId, action.lists, keepOthers = false)
        }
    }

    private suspend fun setStatus(bookId: String, status: String, at: Long) {
        repo.manageBatch(setOf(bookId), status = status, statusAt = at)
    }

    /** Adds the annotations the book doesn't have yet, by id. Never changes or deletes one. */
    private suspend fun addAnnotations(
        ereaderId: String,
        bookId: String,
        annotations: List<SyncAnnotation>,
    ) {
        if (annotations.isEmpty()) return
        repo.database.withTransaction {
            if (dao.book(bookId) == null) return@withTransaction
            annotations
                .distinctBy { it.id }
                .forEach { dao.annotation(SyncAnnotations.toRow(it, bookId, ereaderId)) }
        }
    }

    /**
     * Makes the book's manual lists exactly [names], making lists that don't exist yet; with
     * [keepOthers], only adds. Smart lists are rules, not memberships, so they are left alone; a
     * name a smart list already has can't become a manual list and is skipped. A Want to Read entry
     * attached to the book follows, as it does when lists are changed by hand.
     */
    private suspend fun setLists(bookId: String, names: List<String>, keepOthers: Boolean) {
        repo.lock.withLock {
            repo.database.withTransaction {
                if (dao.book(bookId) == null) return@withTransaction
                val wanted = names.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                val taken = dao.allShelves().map { it.name }.toSet()
                wanted
                    .filter { it !in taken }
                    .forEach { dao.insertShelf(Shelf(UUID.randomUUID().toString(), it)) }
                val manual = dao.allShelves().filter { it.ruleField == MANUAL }
                val target = manual.filter { it.name in wanted }.map { it.id }.toSet()
                val manualIds = manual.map { it.id }.toSet()
                val current =
                    dao.membershipsOf(bookId).map { it.shelfId }.filter { it in manualIds }.toSet()
                val wishes = dao.allWishes().filter { it.linkedBookId == bookId }
                (target - current).forEach { dao.addMembership(Membership(bookId, it)) }
                target.forEach { shelf ->
                    wishes.forEach { dao.addWishMembership(WishMembership(it.id, shelf)) }
                }
                if (!keepOthers)
                    (current - target).forEach { shelf ->
                        dao.removeMembership(bookId, shelf)
                        wishes.forEach { dao.removeWishMembership(it.id, shelf) }
                    }
            }
        }
    }

    override fun pending(ereaderId: String): Boolean {
        val ereader = state.get(ereaderId)
        // An answer given on the phone is used at the next sync.
        if (ereader.answers.isNotEmpty()) return true
        return SyncPending.pending(
            phoneBooks(),
            baselines(ereaderId),
            waiting = ereader.waiting.mapNotNull { it.bookId }.toSet(),
        )
    }

    override fun phoneQuestions(ereaderId: String): List<Question> = state.get(ereaderId).waiting

    override fun saveQuestions(ereaderId: String, questions: List<Question>) =
        state.saveQuestions(ereaderId, questions)

    override fun phoneAnswers(ereaderId: String): Map<String, String> = state.get(ereaderId).answers

    override fun synced(ereaderId: String, deviceName: String, at: Long) =
        state.synced(ereaderId, deviceName, at)

    /**
     * On the Books folder's volume when it can be used, so filing an upload is a rename; else in
     * the app's cache. Uploads left over from a sync that never finished are cleared after a day.
     */
    override fun uploadDir(): File {
        val dir =
            if (repo.folderReady()) File(repo.booksRoot(), UPLOADS)
            else File(cacheDir, "sync-uploads")
        dir.mkdirs()
        val stale = now() - STALE_UPLOAD_MS
        dir.listFiles()
            .orEmpty()
            .filter {
                it.isFile &&
                    it.name.startsWith("librillo-") &&
                    it.name.endsWith(".upload") &&
                    it.lastModified() < stale
            }
            .forEach { it.delete() }
        return dir
    }

    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    /** Runs [block]; a failure leaves this step undone, for the next sync to try again. */
    private inline fun <T> attempt(block: () -> T?): T? =
        try {
            block()
        } catch (e: Exception) {
            null
        }

    companion object {
        const val MANUAL = "Manual"
        /** In the Books folder; the folder scan skips hidden folders. */
        const val UPLOADS = ".librillo/uploads"
        const val STALE_UPLOAD_MS = 24 * 60 * 60 * 1000L

        fun fileName(kindlePath: String) = kindlePath.substringAfterLast('/')

        fun SyncBaseline.toBaseline() =
            Baseline(
                bookId = bookId,
                path = path,
                sha256 = hash,
                status = status,
                statusAt = statusAt,
                annotationIds =
                    runCatching { decode(annotationIds) }.getOrDefault(emptyList()).toSet(),
                collections = runCatching { decode(collections) }.getOrDefault(emptyList()).toSet(),
            )

        fun Baseline.toRow(ereaderId: String, at: Long) =
            SyncBaseline(
                bookId = bookId,
                ereaderId = ereaderId,
                path = path,
                hash = sha256,
                status = status,
                statusAt = statusAt,
                annotationIds = encode(annotationIds.sorted()),
                collections = encode(collections.sorted()),
                syncedAt = at,
            )
    }
}
