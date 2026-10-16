package dev.sadking.folio.data

import androidx.room.withTransaction
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

/** What one run of [BooksFolderMove] did. [failed] holds the titles of books that stayed put. */
data class MoveResult(val moved: Int, val failed: List<String>)

/**
 * Moves books from Librillo's private storage into the Books folder (M8 slice 2): the current EPUB
 * to `Author/Title.epub`, the original download, when it differs, to
 * `.librillo/originals/<id>.epub`. Each file is copied under a hidden name, flushed to disk and
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
            books.forEachIndexed { index, book ->
                currentCoroutineContext().ensureActive()
                progress(index, books.size)
                try {
                    // One book at a time under the library lock, so edits and imports wait.
                    if (repo.lock.withLock { moveOne(book.id) }) moved++
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    failed += book.title
                }
            }
            progress(books.size, books.size)
            MoveResult(moved, failed)
        }

    /** False when the book no longer needs moving. */
    private suspend fun moveOne(id: String): Boolean {
        val book = dao.book(id) ?: return false
        val current = File(book.currentPath)
        if (!repo.isPrivate(current) || dao.removedEntry(id) != null) return false
        val books = repo.booksRoot().apply { mkdirs() }
        check(books.isDirectory) { "Books folder unavailable" }
        require(current.isFile && Epub.sha256(current) == book.currentHash) {
            "File changed or missing"
        }
        // An identical file from an interrupted run sits at the plain path; reuse it.
        val plain = File(books, BookPaths.path(book.metadata().authors, book.title))
        val path =
            if (plain.isFile && Epub.sha256(plain) == book.currentHash) plain.relativeTo(books).path
            else repo.freePath(books, book.metadata().authors, book.title, mine = null)
        val target = place(current, book.currentHash, File(books, path))
        val sameFile = book.originalPath == book.currentPath
        val original =
            if (sameFile) target
            else
                place(
                    File(book.originalPath),
                    book.originalHash,
                    File(books, ".librillo/originals/${book.id}.epub"),
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

    /**
     * Copies [source] to [dest] under a hidden name, flushes it, checks it against [hash] and then
     * gives it its name. An identical file already at [dest] is kept as it is.
     */
    private fun place(source: File, hash: String, dest: File): File {
        require(source.isFile && Epub.sha256(source) == hash) { "File changed or missing" }
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
