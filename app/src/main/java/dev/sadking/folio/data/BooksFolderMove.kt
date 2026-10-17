package dev.sadking.folio.data

import androidx.room.withTransaction
import dev.sadking.folio.R
import dev.sadking.folio.core.BookPaths
import dev.sadking.folio.core.Epub
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What one run of [BooksFolderMove] did. [failed] holds the titles of books that stayed put, and
 * [reasons] why each stayed, in plain words, by title.
 */
data class MoveResult(
    val moved: Int,
    val failed: List<String>,
    val reasons: Map<String, String> = emptyMap(),
)

/**
 * Moves books from Librillo's private storage into the Books folder (M8 slice 2): the current file
 * to `Author/Title.<format>`, the original download, when it differs, to
 * `.librillo/originals/<id>.<format>`. Each file is copied under a hidden name, flushed to disk and
 * checked against the book's hash before it gets its name; the book's paths change in the database
 * only then, and the private copy is removed last. A book that fails stays in private storage and
 * keeps working. Running it again moves what is left, and reuses an identical file an interrupted
 * run already placed.
 */
class BooksFolderMove(private val repo: LibraryRepository) {
    private val dao = repo.dao

    /** Books whose file is already in the Books folder. */
    suspend fun inFolder(): Int =
        withContext(Dispatchers.IO) {
            dao.allBooks().count {
                it.currentPath.isNotBlank() && repo.inBooksFolder(File(it.currentPath))
            }
        }

    /** Books still in private storage, not counting Recently removed. */
    suspend fun pending(): Int = withContext(Dispatchers.IO) { candidates().size }

    private suspend fun candidates(): List<Book> =
        dao.allBooks().filter {
            it.currentPath.isNotBlank() &&
                repo.isPrivate(File(it.currentPath)) &&
                dao.removedEntry(it.id) == null
        }

    suspend fun run(progress: (done: Int, total: Int) -> Unit = { _, _ -> }): MoveResult =
        withContext(Dispatchers.IO) {
            val books = candidates()
            var moved = 0
            val failed = mutableListOf<String>()
            val reasons = linkedMapOf<String, String>()
            books.forEachIndexed { index, book ->
                currentCoroutineContext().ensureActive()
                progress(index, books.size)
                try {
                    // One book at a time under the library lock, so edits and imports wait.
                    if (repo.lock.withLock { moveOne(book.id) }) moved++
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    failed += book.title
                    reasons[book.title] = reason(e)
                }
            }
            progress(books.size, books.size)
            MoveResult(moved, failed, reasons)
        }

    /** Why a book stayed in private storage, in plain words. */
    private fun reason(e: Exception): String =
        repo.context.getString(
            when {
                isStorageFull(e) -> R.string.data6_move_reason_space
                e is FileChangedOutside -> R.string.data6_move_reason_changed
                else -> R.string.data6_move_reason_failed
            }
        )

    /** False when the book no longer needs moving. */
    private suspend fun moveOne(id: String): Boolean {
        val book = dao.book(id) ?: return false
        val current = File(book.currentPath)
        if (!repo.isPrivate(current) || dao.removedEntry(id) != null) return false
        val books = repo.booksRoot().apply { mkdirs() }
        check(books.isDirectory) { "Books folder unavailable" }
        if (!current.isFile || Epub.sha256(current) != book.currentHash) throw changed()
        // An identical file from an interrupted run sits at the plain path; reuse it.
        val authors = book.metadata().authors
        val plain = File(books, BookPaths.path(authors, book.title, extension = book.format))
        val path =
            if (plain.isFile && Epub.sha256(plain) == book.currentHash) plain.relativeTo(books).path
            else repo.freePath(books, authors, book.title, mine = null, extension = book.format)
        val target = place(current, book.currentHash, File(books, path))
        val sameFile = book.originalPath == book.currentPath
        val original =
            if (sameFile) target
            else
                place(
                    File(book.originalPath),
                    book.originalHash,
                    File(books, ".librillo/originals/${book.id}.${book.format}"),
                )
        repo.database.withTransaction {
            dao.update(
                book.copy(currentPath = target.absolutePath, originalPath = original.absolutePath)
            )
        }
        current.delete()
        if (!sameFile && repo.isPrivate(File(book.originalPath))) File(book.originalPath).delete()
        return true
    }

    private fun changed() =
        FileChangedOutside(repo.context.getString(R.string.data6_file_changed_outside))

    /**
     * Copies [source] to [dest] under a hidden name, flushes it, checks it against [hash] and then
     * gives it its name. An identical file already at [dest] is kept as it is.
     */
    private fun place(source: File, hash: String, dest: File): File {
        if (!source.isFile || Epub.sha256(source) != hash) throw changed()
        if (dest.isFile && Epub.sha256(dest) == hash) return dest
        require(!dest.exists()) { "Destination taken" }
        dest.parentFile!!.mkdirs()
        val part = File(dest.parentFile, ".${dest.name}.part")
        try {
            source.inputStream().use { input ->
                FileOutputStream(part).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(Epub.sha256(part) == hash) { "Copy did not match" }
            check(part.renameTo(dest)) { "Cannot name the copy" }
        } finally {
            part.delete()
        }
        return dest
    }
}
