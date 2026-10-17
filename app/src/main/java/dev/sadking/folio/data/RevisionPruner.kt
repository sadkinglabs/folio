package dev.sadking.folio.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.sadking.folio.core.Epub
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What one pruning pass did: files [removed] and their [bytes], books [skipped] because a previous
 * revision might be their only good copy, and files that could not be removed ([failed]).
 */
data class PruneResult(
    val removed: Int = 0,
    val bytes: Long = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
) {
    operator fun plus(other: PruneResult) =
        PruneResult(
            removed + other.removed,
            bytes + other.bytes,
            skipped + other.skipped,
            failed + other.failed,
        )
}

/**
 * Keeps each book's original, its current file and the [keepPrevious] newest previous revisions;
 * removes older previous revisions and superseded covers. Never touches database rows.
 *
 * A previous revision is, and nothing else is:
 * `<Books>/.librillo/previous/<bookId>-<millis>.<format>` (the exact pattern, so one id is never
 * taken for another that starts the same), and in the book's private folder
 * `filesDir/library/<bookId>/` a file named `<uuid>.<format>`. The newest are counted across both
 * places: one in the Books folder by the time in its name (when it was set aside), one in private
 * storage by when it was written. A superseded cover is a `cover-<uuid>` file in the private folder
 * that no row points at; the [keepPrevious] newest of those stay too.
 *
 * Never removed: any path a `books` row holds (original, current or cover, for every book,
 * including those in Recently removed) or a Want to Read entry's cover; anything in
 * `.librillo/originals`; `original.<format>`; `cover-original`; anything outside those two folders;
 * links. A whole book is left alone when it is in Recently removed, when its original or current
 * file is missing, or when its current file is not the content the library recorded: then an older
 * revision may be the only good copy left.
 */
class RevisionPruner(private val repo: LibraryRepository, private val keepPrevious: Int = 3) {
    init {
        require(keepPrevious >= 0)
    }

    private val dao = repo.dao

    /** One book. Call with `repo.lock` held. Errors are counted in [PruneResult.failed]. */
    internal suspend fun pruneLocked(bookId: String): PruneResult =
        withContext(Dispatchers.IO) {
            val book = dao.book(bookId) ?: return@withContext PruneResult(skipped = 1)
            if (dao.removedEntry(bookId) != null) return@withContext PruneResult(skipped = 1)
            val current = File(book.currentPath)
            if (book.currentPath.isBlank() || !current.isFile)
                return@withContext PruneResult(skipped = 1)
            if (book.originalPath.isNotBlank() && !File(book.originalPath).isFile)
                return@withContext PruneResult(skipped = 1)
            val held = heldPaths()
            fun free(file: File) = canonical(file)?.let { it !in held } == true
            val revisions = (inFolder(book) + inPrivate(book)).filter { free(it.file) }
            val covers = covers(book).filter { free(it.file) }
            val doomed = surplus(revisions) + surplus(covers)
            if (doomed.isEmpty()) return@withContext PruneResult()
            // Hashed only when something would go: most books have nothing to prune.
            val intact =
                try {
                    Epub.sha256(current) == book.currentHash
                } catch (_: Exception) {
                    false
                }
            if (!intact) return@withContext PruneResult(skipped = 1)
            var result = PruneResult()
            for (old in doomed) {
                val size = old.file.length()
                result +=
                    try {
                        if (Files.deleteIfExists(old.file.toPath())) PruneResult(1, size)
                        else PruneResult()
                    } catch (_: Exception) {
                        PruneResult(failed = 1)
                    }
            }
            result
        }

    /** Every book, one at a time under the lock, so imports and edits wait at most for one. */
    suspend fun pruneAll(): PruneResult =
        withContext(Dispatchers.IO) {
            var result = PruneResult()
            for (book in dao.allStoredBooks()) {
                currentCoroutineContext().ensureActive()
                result +=
                    try {
                        repo.lock.withLock { pruneLocked(book.id) }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        PruneResult(failed = 1)
                    }
            }
            result
        }

    private data class Candidate(val file: File, val time: Long)

    /** Everything beyond the [keepPrevious] newest; the newest first, ties by name. */
    private fun surplus(candidates: List<Candidate>): List<Candidate> =
        candidates
            .sortedWith(
                compareByDescending<Candidate> { it.time }.thenByDescending { it.file.name }
            )
            .drop(keepPrevious)

    /** Every path a row points at, canonical: books (all of them) and Want to Read covers. */
    private suspend fun heldPaths(): Set<String> {
        val paths =
            dao.allStoredBooks().flatMap {
                listOfNotNull(it.originalPath, it.currentPath, it.coverPath)
            } + dao.allWishes().mapNotNull { it.coverPath }
        return paths.filter { it.isNotBlank() }.mapNotNull { canonical(File(it)) }.toSet()
    }

    /** `.librillo/previous/<id>-<millis>.<format>`, timed by the moment it was set aside. */
    private fun inFolder(book: Book): List<Candidate> {
        val dir = File(repo.booksRoot(), PREVIOUS)
        val pattern = Regex(Regex.escape(book.id) + "-(\\d{1,19})\\." + Regex.escape(book.format))
        return files(dir).mapNotNull { file ->
            pattern.matchEntire(file.name)?.let { m ->
                m.groupValues[1].toLongOrNull()?.let { Candidate(file, it) }
            }
        }
    }

    /** `<uuid>.<format>` in the book's private folder, timed by when it was written. */
    private fun inPrivate(book: Book): List<Candidate> {
        val pattern = Regex(UUID_PATTERN + "\\." + Regex.escape(book.format))
        return files(File(repo.root, book.id))
            .filter { pattern.matches(it.name) }
            .map { Candidate(it, it.lastModified()) }
    }

    /** `cover-<uuid>` in the book's private folder. */
    private fun covers(book: Book): List<Candidate> {
        val pattern = Regex("cover-$UUID_PATTERN")
        return files(File(repo.root, book.id))
            .filter { pattern.matches(it.name) }
            .map { Candidate(it, it.lastModified()) }
    }

    /** Plain files directly in [dir]: no folders, no links, and [dir] itself not a link. */
    private fun files(dir: File): List<File> {
        if (!Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val real = canonical(dir) ?: return emptyList()
        return dir.listFiles().orEmpty().filter { file ->
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                canonical(file.parentFile!!) == real
        }
    }

    private fun canonical(file: File): String? = runCatching { file.canonicalPath }.getOrNull()

    companion object {
        /** In the Books folder: where earlier versions of a book's file are kept. */
        const val PREVIOUS = ".librillo/previous"
        private const val UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        private const val PREFERENCES = "revision-pruning"
        private const val LAST_RUN = "last_run_at"
        const val DAY_MS = 24 * 60 * 60 * 1000L

        private fun preferences(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

        /**
         * True, and the time recorded, when the daily pass is due: never run, run a day or more
         * ago, or the clock has gone back since.
         */
        fun dailyPassDue(context: Context, now: Long = System.currentTimeMillis()): Boolean {
            val prefs = preferences(context)
            val last = prefs.getLong(LAST_RUN, 0L)
            if (last in 1..now && now - last < DAY_MS) return false
            prefs.edit { putLong(LAST_RUN, now) }
            return true
        }
    }
}
