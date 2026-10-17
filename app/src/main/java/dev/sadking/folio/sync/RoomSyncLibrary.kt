package dev.sadking.folio.sync

import androidx.room.withTransaction
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.core.BookFormats
import dev.sadking.folio.core.Comic
import dev.sadking.folio.core.Epub
import dev.sadking.folio.core.stats.KoreaderSessions
import dev.sadking.folio.core.sync.Baseline
import dev.sadking.folio.core.sync.PhoneAction
import dev.sadking.folio.core.sync.PhoneBook
import dev.sadking.folio.core.sync.Question
import dev.sadking.folio.core.sync.SyncAnnotation
import dev.sadking.folio.core.sync.SyncPending
import dev.sadking.folio.data.DeviceKeys
import dev.sadking.folio.data.DeviceStatistics
import dev.sadking.folio.data.LINK_DEVICE_PREFIX
import dev.sadking.folio.data.LibraryRepository
import dev.sadking.folio.data.Membership
import dev.sadking.folio.data.NotABook
import dev.sadking.folio.data.Shelf
import dev.sadking.folio.data.StatsBookNameRow
import dev.sadking.folio.data.SyncBaseline
import dev.sadking.folio.data.UnreadableBook
import dev.sadking.folio.data.WishMembership
import dev.sadking.folio.data.decode
import dev.sadking.folio.data.encode
import dev.sadking.folio.data.keepSessions
import dev.sadking.folio.data.validateStatistics
import dev.sadking.folio.link.ReadingStatistics
import dev.sadking.folio.link.Refusal
import dev.sadking.folio.link.RefusedFile
import dev.sadking.folio.link.SyncLibrary
import dev.sadking.folio.link.SyncRefusal
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone's side of sync protocol v2: Librillo's library in Room and the Books folder, as the
 * Link server's [dev.sadking.folio.link.SyncRoutes] sees it. docs/SYNC_PROTOCOL.md has the rules.
 * The server calls it from its own threads, one sync step at a time, so every call blocks.
 *
 * Nothing here deletes a book file or an annotation. A book removed by sync goes to Recently
 * removed; an annotation deleted on the e-reader is deleted softly (restorable for 30 days); a new
 * file from the e-reader is a new book or a new revision, beside the old file.
 */
class RoomSyncLibrary
internal constructor(
    private val repo: LibraryRepository,
    /** Questions, answers and last sync per e-reader. */
    val state: SyncState,
    /** Where uploads wait when the Books folder can't be used. */
    private val cacheDir: File,
    /**
     * Told which books' status sync may have changed, so Hardcover gets them like a change made on
     * the phone ([HardcoverSync.statusChanged]): a Kindle finish goes to Hardcover on its own.
     */
    private val statusChanged: suspend (Collection<String>) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : SyncLibrary {
    constructor(
        app: FolioApplication
    ) : this(
        app.repository,
        SyncState.of(app),
        app.cacheDir,
        // Looked up when a status changes, not when the Link server starts.
        { ids -> HardcoverSync.get(app).statusChanged(ids) },
    )

    private val dao = repo.dao
    private val baselineDao = repo.database.syncBaselines()
    private val stats = repo.database.stats()

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
     * without restoring it from Recently removed: only a Restore action does that. A file that is
     * too large or not a book Librillo can read is refused ([SyncRefusal]) and not sent again.
     */
    override fun receive(ereaderId: String, kindlePath: String, file: File): String? = attempt {
        io {
            repo.lock.withLock {
                if (!file.isFile) return@withLock null
                if (file.length() > Epub.MAX_FILE) throw SyncRefusal(Refusal.TOO_LARGE)
                dao.duplicate(Epub.sha256(file))?.id
                    ?: try {
                        repo.addLocked(file, fileName(kindlePath), repo.folderReady()).first.id
                    } catch (e: NotABook) {
                        throw SyncRefusal(Refusal.NOT_A_BOOK)
                    } catch (e: UnreadableBook) {
                        throw SyncRefusal(Refusal.NOT_A_BOOK)
                    }
            }
        }
    }

    /**
     * Takes the upload as the book's new revision; the old file is kept (see reviseLocked). A file
     * that is too large, not a book, or another kind of file than the book is refused.
     */
    override fun revise(
        ereaderId: String,
        bookId: String,
        kindlePath: String,
        file: File,
    ): String? = attempt {
        io {
            repo.lock.withLock {
                val book = dao.book(bookId) ?: return@withLock null
                if (!file.isFile) return@withLock null
                if (file.length() > Epub.MAX_FILE) throw SyncRefusal(Refusal.TOO_LARGE)
                when (formatOf(file, fileName(kindlePath))) {
                    null -> throw SyncRefusal(Refusal.NOT_A_BOOK)
                    book.format -> Unit
                    else -> throw SyncRefusal(Refusal.OTHER_FORMAT)
                }
                repo.reviseLocked(bookId, file, fileName(kindlePath))?.id
            }
        }
    }

    /** Each action on its own: one that fails is skipped, and the next sync tries it again. */
    override fun apply(
        ereaderId: String,
        actions: List<PhoneAction>,
        received: Map<String, String>,
    ) {
        for (action in actions) attempt { io { applyOne(ereaderId, action, received) } }
        // Books whose status sync set go to Hardcover too (M10).
        val statuses = actions.mapNotNull {
            when (it) {
                is PhoneAction.SetStatus -> it.bookId
                is PhoneAction.Import -> if (it.status != null) received[it.path] else null
                else -> null
            }
        }
        if (statuses.isNotEmpty()) attempt { io { statusChanged(statuses.distinct()) } }
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
            is PhoneAction.EditAnnotations ->
                editAnnotations(ereaderId, action.bookId, action.annotations)
            is PhoneAction.DeleteAnnotations -> deleteAnnotations(action.bookId, action.ids)
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
     * The e-reader's note, colour and style for annotations both sides have. One the owner deleted
     * here comes back (it was edited on the e-reader, and an edit beats a delete); one missing here
     * is added.
     */
    private suspend fun editAnnotations(
        ereaderId: String,
        bookId: String,
        annotations: List<SyncAnnotation>,
    ) {
        if (annotations.isEmpty()) return
        repo.database.withTransaction {
            if (dao.book(bookId) == null) return@withTransaction
            annotations
                .distinctBy { it.id }
                .forEach { a ->
                    val changed =
                        dao.syncEditAnnotation(
                            a.id,
                            bookId,
                            a.text,
                            a.note,
                            a.kind,
                            a.color,
                            a.drawer,
                            a.updated,
                        )
                    if (changed == 0) dao.annotation(SyncAnnotations.toRow(a, bookId, ereaderId))
                }
        }
    }

    /** Deleted on the e-reader: deleted here too, softly, so the owner can restore it. */
    private suspend fun deleteAnnotations(bookId: String, ids: List<String>) {
        repo.database.withTransaction {
            ids.distinct().forEach { id ->
                if (dao.annotationById(id)?.bookId == bookId) dao.softDeleteAnnotation(id, now())
            }
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
        // The owner tapped Sync since this e-reader's last sync.
        if (state.syncRequested(ereaderId)) return true
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

    override fun refused(ereaderId: String): List<RefusedFile> = state.get(ereaderId).refused

    override fun saveRefused(ereaderId: String, refused: List<RefusedFile>) =
        state.saveRefused(ereaderId, refused)

    override fun started(ereaderId: String, deviceName: String, at: Long) =
        state.started(ereaderId, deviceName, at)

    override fun synced(ereaderId: String, deviceName: String, at: Long) =
        state.synced(ereaderId, deviceName, at, books = baselineDao.forEreader(ereaderId).size)

    /**
     * KOReader's reading time from [ereaderId], kept as `link:<id>` in `device_statistics` so the
     * device roster names it. It replaces what that e-reader sent before: KOReader's own database
     * is the record, and the phone keeps its latest totals. Checked as a backup's are
     * ([validateStatistics]); anything that fails is refused and nothing is kept.
     */
    override fun saveStatistics(ereaderId: String, statistics: ReadingStatistics) {
        val value =
            DeviceStatistics(
                deviceId = LINK_DEVICE_PREFIX + ereaderId,
                totalSeconds = statistics.seconds,
                exportedAt = statistics.exportedAt,
                fetchedAt = now(),
                days =
                    JSONArray(
                            statistics.days.map {
                                JSONObject().put("date", it.date).put("seconds", it.seconds)
                            }
                        )
                        .toString(),
            )
        try {
            validateStatistics(value)
        } catch (e: RuntimeException) {
            // A malformed date or number fails as a parse error; to the routes it is all refused.
            throw IllegalArgumentException("Invalid reading statistics", e)
        }
        io { dao.statistics(value) }
    }

    /**
     * The latest `start + seconds` (epoch seconds) of the KOReader sessions kept from [ereaderId],
     * as the plugin's watermark. 0 when there are none; another e-reader's sessions don't count.
     */
    override fun sessionsUntil(ereaderId: String): Long = io {
        stats.kindleUntil(DeviceKeys.ereader(ereaderId)) ?: 0L
    }

    /**
     * Keeps [sessions] from [ereaderId] as Kindle rows: the book stays as KOReader's file digest
     * (`document`) with no `bookId` until it is matched. A session only grows: a copy no longer
     * than the stored one is not written (see [keepSessions]). Returns how many rows were written;
     * nothing is deleted.
     */
    override fun saveSessions(
        ereaderId: String,
        sessions: List<KoreaderSessions.WireSession>,
    ): Int = io {
        stats.keepSessions(
            sessions.map {
                KoreaderSessions.toSession(ereaderId, DeviceKeys.ereader(ereaderId), it)
            },
            now(),
        )
    }

    /** The digests of KOReader files the phone can match to a library book (A1). */
    override fun knownDigests(): List<String> = io { stats.knownDigests() }

    /**
     * Keeps the names [ereaderId] sent for books the phone can't match (A1), by digest, as `digest
     * -> (title, authors)`. Each name is written now, so a newer name for a digest replaces the
     * stored one.
     */
    override fun saveBookNames(ereaderId: String, names: Map<String, Pair<String, String>>) {
        if (names.isEmpty()) return
        val at = now()
        io {
            stats.upsertBookNames(
                names.map { (digest, name) ->
                    StatsBookNameRow(
                        digest,
                        title = name.first,
                        authors = name.second,
                        updatedAt = at,
                    )
                }
            )
        }
    }

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

    /**
     * Runs [block]; a failure leaves this step undone, for the next sync to try again. A
     * [SyncRefusal] is not a failure: it goes on to the routes, which remember the file.
     */
    private inline fun <T> attempt(block: () -> T?): T? =
        try {
            block()
        } catch (e: SyncRefusal) {
            throw e
        } catch (e: Exception) {
            null
        }

    /** The book format of [file], as an import reads it, or null when it isn't a book. */
    private fun formatOf(file: File, name: String): String? {
        val header = file.inputStream().use { it.readNBytes(BookFormats.HEADER_BYTES) }
        return BookFormats.detect(header, name) ?: Comic.zipKind(file)
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
                // Unreadable means unknown: only ever a reason not to delete.
                annotationStates =
                    annotationStates?.let { text ->
                        runCatching {
                                val json = org.json.JSONObject(text)
                                json.keys().asSequence().associateWith { json.getString(it) }
                            }
                            .getOrNull()
                    },
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
                annotationStates =
                    annotationStates?.let { states ->
                        org.json.JSONObject(states.toSortedMap() as Map<*, *>).toString()
                    },
            )
    }
}
