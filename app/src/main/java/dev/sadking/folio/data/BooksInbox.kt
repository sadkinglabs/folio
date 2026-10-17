package dev.sadking.folio.data

import android.content.res.Resources
import dev.sadking.folio.R
import dev.sadking.folio.core.BookPaths
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A file Librillo doesn't take: not an EPUB, PDF, CBZ or CBR. */
class NotABook(message: String) : IllegalArgumentException(message)

/** A book file whose details can't be read: damaged or protected. The message is for the user. */
class UnreadableBook(cause: Exception) : IllegalArgumentException(importFailure(cause), cause)

/** A file larger than Librillo takes. */
class TooLargeBook : IllegalArgumentException("File too large")

/** Why an Inbox file went to `Inbox/Not imported`. */
enum class InboxReason {
    ALREADY_IN_LIBRARY,
    NOT_A_BOOK,
    UNREADABLE,
    TOO_LARGE,
}

/**
 * One Inbox file and what happened to it. [name] is the name it had in the Inbox, [file] where it
 * is now, [bookId] the book it became or already was, [reason] why it was not imported.
 */
data class InboxEntry(
    val name: String,
    val file: File,
    val bookId: String?,
    val reason: InboxReason?,
)

/** What one pass over the Inbox did. */
data class InboxResult(
    val added: List<InboxEntry> = emptyList(),
    val notImported: List<InboxEntry> = emptyList(),
    /** Files still being written. They wait for the next pass. */
    val waiting: Int = 0,
    /** Files that couldn't be filed this time, such as after a storage error. They stay put. */
    val retry: Int = 0,
    /** True when at least one of the [retry] files failed because the phone is out of space. */
    val storageFull: Boolean = false,
) {
    /** This pass followed by [next], a later pass over what was left. */
    operator fun plus(next: InboxResult) =
        InboxResult(
            added + next.added,
            notImported + next.notImported,
            next.waiting,
            next.retry,
            next.storageFull,
        )
}

/**
 * `Books/Inbox`, the drop folder (M9 step 2). Each pass files every book directly in it whose size
 * has settled: a readable book is moved (renamed, never copied) to `Author/Title.<format>` and
 * added; a file whose content is already in the library, or that Librillo can't take, goes to
 * `Inbox/Not imported` with the reason. Nothing in the Inbox is ever deleted. Keep one instance: it
 * remembers file sizes between passes.
 */
class BooksInbox(
    private val repo: LibraryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Size of each file and when it was first seen at that size, by path. */
    private val sizes = ConcurrentHashMap<String, Pair<Long, Long>>()

    val folder: File
        get() = File(repo.booksRoot(), FOLDER)

    suspend fun run(): InboxResult =
        withContext(Dispatchers.IO) {
            if (!repo.folderReady()) return@withContext InboxResult()
            val files =
                folder
                    .listFiles()
                    .orEmpty()
                    .filter { it.isFile && !it.name.startsWith('.') }
                    .sortedBy { it.name }
            val added = mutableListOf<InboxEntry>()
            val notImported = mutableListOf<InboxEntry>()
            var waiting = 0
            var retry = 0
            var storageFull = false
            for (file in files) {
                currentCoroutineContext().ensureActive()
                if (!settled(file)) {
                    waiting++
                    continue
                }
                try {
                    val entry = fileOne(file)
                    if (entry.reason == null) {
                        added += entry
                    } else {
                        notImported += entry
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    retry++
                    if (isStorageFull(e)) storageFull = true
                } finally {
                    sizes.remove(file.path)
                }
            }
            InboxResult(added, notImported, waiting, retry, storageFull)
        }

    /**
     * Files one settled Inbox file: a new book (reason null), or a file set aside in `Not imported`
     * with its reason. Throws for a storage error; the file then stays where it is.
     */
    private suspend fun fileOne(file: File): InboxEntry {
        val filed =
            try {
                repo.fileFromInbox(file)
            } catch (_: NotABook) {
                return setAside(file, InboxReason.NOT_A_BOOK, null)
            } catch (_: UnreadableBook) {
                return setAside(file, InboxReason.UNREADABLE, null)
            } catch (_: TooLargeBook) {
                return setAside(file, InboxReason.TOO_LARGE, null)
            }
        val (book, isNew) = filed
        return if (isNew) InboxEntry(file.name, File(book.currentPath), book.id, null)
        else setAside(file, InboxReason.ALREADY_IN_LIBRARY, book.id)
    }

    /**
     * True when [file] hasn't changed for [SETTLE_MS]: its modified time is that old, or its size
     * has been the same since a pass at least that long ago.
     */
    private fun settled(file: File): Boolean {
        val now = clock()
        if (now - file.lastModified() >= SETTLE_MS) return true
        val size = file.length()
        val since = sizes[file.path]?.takeIf { it.first == size }?.second
        if (since == null) {
            sizes[file.path] = size to now
            return false
        }
        return now - since >= SETTLE_MS
    }

    /** Moves [file] to `Inbox/Not imported`, under a free name; never over another file. */
    private fun setAside(file: File, reason: InboxReason, bookId: String?): InboxEntry {
        val dir = File(folder, NOT_IMPORTED).apply { mkdirs() }
        val dest = freeName(dir, file.name)
        Files.move(file.toPath(), dest.toPath())
        return InboxEntry(file.name, dest, bookId, reason)
    }

    companion object {
        const val FOLDER = "Inbox"
        const val NOT_IMPORTED = "Not imported"
        const val SETTLE_MS = 5_000L

        /** [name] in [dir], or `name (2).ext` and so on when that is taken, ignoring case. */
        internal fun freeName(dir: File, name: String): File {
            val taken = dir.list().orEmpty().map(BookPaths::key).toSet()
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val stem = name.substring(0, dot)
            val ext = name.substring(dot)
            var candidate = name
            var n = 1
            while (BookPaths.key(candidate) in taken) {
                n++
                candidate = "$stem ($n)$ext"
            }
            return File(dir, candidate)
        }
    }
}

/** Why an Inbox file was not imported, in plain words. */
fun InboxReason.text(res: Resources): String =
    res.getString(
        when (this) {
            InboxReason.ALREADY_IN_LIBRARY -> R.string.inbox_m9_reason_duplicate
            InboxReason.NOT_A_BOOK -> R.string.import_unsupported_format
            InboxReason.UNREADABLE -> R.string.inbox_m9_reason_unreadable
            InboxReason.TOO_LARGE -> R.string.inbox_m9_reason_too_large
        }
    )

/**
 * One plain message for a pass ("Added 2 books from the Inbox. 1 was already in your library."), or
 * null when nothing was filed or set aside.
 */
fun InboxResult.message(res: Resources): String? {
    val already = notImported.count { it.reason == InboxReason.ALREADY_IN_LIBRARY }
    val failed = notImported.size - already
    val parts = buildList {
        if (added.isNotEmpty())
            add(res.getQuantityString(R.plurals.msg_inbox_m9_added, added.size, added.size))
        if (already > 0)
            add(
                res.getQuantityString(
                    if (added.isEmpty()) R.plurals.msg_inbox_m9_already_alone
                    else R.plurals.msg_inbox_m9_already,
                    already,
                    already,
                )
            )
        if (failed > 0)
            add(res.getQuantityString(R.plurals.msg_inbox_m9_not_imported, failed, failed))
        if (retry > 0) add(res.getQuantityString(R.plurals.msg_inbox_m9_retry, retry, retry))
        if (storageFull) add(res.getString(R.string.data6_storage_full))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}
