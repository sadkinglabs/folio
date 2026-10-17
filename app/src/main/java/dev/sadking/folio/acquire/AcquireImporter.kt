package dev.sadking.folio.acquire

import android.content.res.Resources
import android.net.Uri
import dev.sadking.folio.R
import dev.sadking.folio.core.BookFormats
import dev.sadking.folio.core.BookProtection
import dev.sadking.folio.core.Comic
import dev.sadking.folio.core.Protection
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.BooksInbox
import dev.sadking.folio.data.LibraryRepository
import dev.sadking.folio.data.NotABook
import dev.sadking.folio.data.TooLargeBook
import dev.sadking.folio.data.UnreadableBook
import dev.sadking.folio.data.isStorageFull
import dev.sadking.folio.userMessage
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What happened to a book got from the browser. */
sealed interface AcquireOutcome {
    /**
     * A new book. [attached] when it was linked to the Want to Read entry it was got for;
     * [attachProblem] says, in the repository's plain words, why linking failed.
     */
    data class Saved(
        val bookId: String,
        val title: String,
        val attached: Boolean,
        val attachProblem: String? = null,
    ) : AcquireOutcome

    /** The same file was already in the library; the download was dropped. */
    data class Already(
        val bookId: String,
        val title: String,
        val attached: Boolean,
        val attachProblem: String? = null,
    ) : AcquireOutcome

    data object Protected : AcquireOutcome

    data object NotABook : AcquireOutcome

    data object TooLarge : AcquireOutcome

    /** The phone ran out of space while the book was written. */
    data object NoSpace : AcquireOutcome

    data object Failed : AcquireOutcome
}

/** [AcquireOutcome.NoSpace] for an out-of-space error, otherwise [AcquireOutcome.Failed]. */
internal fun failure(e: Throwable): AcquireOutcome =
    if (isStorageFull(e)) AcquireOutcome.NoSpace else AcquireOutcome.Failed

/** The outcome's first line, in plain words. */
internal fun AcquireOutcome.headline(res: Resources): String =
    res.getString(
        when (this) {
            is AcquireOutcome.Saved -> R.string.acq_saved
            is AcquireOutcome.Already -> R.string.acq_already
            AcquireOutcome.Protected -> R.string.acq_protected
            AcquireOutcome.NotABook -> R.string.acq_not_a_book
            AcquireOutcome.TooLarge -> R.string.acq_too_large
            AcquireOutcome.NoSpace -> R.string.data6_storage_full
            AcquireOutcome.Failed -> R.string.acq_failed
        }
    )

/**
 * The lines under the headline: when the e-reader is set up, that a new book reaches it at the next
 * sync; why linking to the Want to Read entry failed, when it did.
 */
internal fun AcquireOutcome.details(res: Resources, ereader: Boolean): List<String> =
    when (this) {
        is AcquireOutcome.Saved ->
            listOfNotNull(res.getString(R.string.acq_next_sync).takeIf { ereader }, attachProblem)
        is AcquireOutcome.Already -> listOfNotNull(attachProblem)
        else -> emptyList()
    }

/** True for an outcome that put nothing in the library. */
internal val AcquireOutcome.failed: Boolean
    get() = this !is AcquireOutcome.Saved && this !is AcquireOutcome.Already

/**
 * Where a download is written while it arrives. [toFolder]: a hidden file in `Books/Inbox`, on the
 * volume the book will live on, so filing it is a rename; otherwise a folder of its own in the
 * cache, removed afterwards.
 */
internal data class Staged(val file: File, val toFolder: Boolean, val folder: File?)

/**
 * Takes a finished download into the library through the normal import: never over another file,
 * duplicates recognised, protected files refused before anything is imported, and the book linked
 * to the Want to Read entry it was got for.
 */
internal class AcquireImporter(
    private val repo: LibraryRepository,
    private val cacheDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A new place to write a download to. */
    fun stage(): Staged =
        if (repo.folderReady()) {
            val inbox = File(repo.booksRoot(), BooksInbox.FOLDER).apply { mkdirs() }
            // Hidden: the Inbox pass and the folder scan skip dot files.
            Staged(File(inbox, ".download-${UUID.randomUUID()}.part"), toFolder = true, null)
        } else {
            val folder = File(cacheDir, "downloads/${UUID.randomUUID()}").apply { mkdirs() }
            Staged(File(folder, "download.part"), toFolder = false, folder)
        }

    /** Drops a download that won't be imported. */
    fun discard(staged: Staged) {
        staged.file.delete()
        staged.folder?.deleteRecursively()
    }

    /**
     * Imports the complete download in [staged] under [name] (a [DownloadSniffer.fileName]). With
     * [wishId], the book is then linked to that Want to Read entry.
     */
    suspend fun finish(staged: Staged, name: String, wishId: String?): AcquireOutcome =
        withContext(Dispatchers.IO) {
            try {
                val file = staged.file
                val header = file.inputStream().use { it.readNBytes(BookFormats.HEADER_BYTES) }
                if (BookProtection.isAcsm(header, name, null)) return@withContext dropped(staged)
                val format =
                    BookFormats.detect(header, name)
                        ?: Comic.zipKind(file)
                        ?: return@withContext dropped(staged, AcquireOutcome.NotABook)
                if (BookProtection.check(file, format) != Protection.NONE)
                    return@withContext dropped(staged)
                val named = DownloadSniffer.withExtension(name, format)
                val (book, isNew) =
                    if (staged.toFolder) fileIntoInbox(file, named)
                    else importPrivately(staged, named)
                attach(book, isNew, wishId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: TooLargeBook) {
                discard(staged)
                AcquireOutcome.TooLarge
            } catch (_: NotABook) {
                discard(staged)
                AcquireOutcome.NotABook
            } catch (_: UnreadableBook) {
                discard(staged)
                AcquireOutcome.NotABook
            } catch (e: Exception) {
                // A storage error. A file already renamed into the Inbox stays there, and the
                // next Inbox pass files it; anything else is dropped.
                staged.file.delete()
                staged.folder?.deleteRecursively()
                failure(e)
            }
        }

    private fun dropped(
        staged: Staged,
        outcome: AcquireOutcome = AcquireOutcome.Protected,
    ): AcquireOutcome {
        discard(staged)
        return outcome
    }

    /**
     * Renames the download into the Inbox under a free name and files it at once. The modified time
     * is set to now, so an Inbox pass that runs in between treats it as still being written. A copy
     * of a book already kept is deleted: it is byte for byte the same.
     */
    private suspend fun fileIntoInbox(file: File, name: String): Pair<Book, Boolean> {
        file.setLastModified(clock())
        val inbox = File(repo.booksRoot(), BooksInbox.FOLDER)
        val dest = moveNoClobber(file, inbox, name)
        val filed =
            try {
                repo.fileFromInbox(dest)
            } catch (e: NotABook) {
                dest.delete()
                throw e
            } catch (e: UnreadableBook) {
                dest.delete()
                throw e
            } catch (e: TooLargeBook) {
                dest.delete()
                throw e
            }
        val (book, isNew) = filed
        if (!isNew && dest.exists() && !sameFile(dest, File(book.currentPath))) dest.delete()
        return filed
    }

    /** Without the Books folder: the normal import from a file URI, then the cache is cleared. */
    private suspend fun importPrivately(staged: Staged, name: String): Pair<Book, Boolean> {
        val folder = staged.folder ?: throw IOException("No staging folder")
        try {
            val named = File(folder, name)
            Files.move(staged.file.toPath(), named.toPath())
            // The name in the URI keeps the format hint and the title fallback.
            return repo.import(Uri.fromFile(named))
        } finally {
            folder.deleteRecursively()
        }
    }

    /** Moves [file] into [dir] as [name], or `name (2)` and so on; never over another file. */
    private fun moveNoClobber(file: File, dir: File, name: String): File {
        repeat(5) {
            val dest = BooksInbox.freeName(dir, name)
            try {
                Files.move(file.toPath(), dest.toPath())
                return dest
            } catch (_: FileAlreadyExistsException) {
                // Another file took the name in between; pick the next one.
            }
        }
        throw IOException("No free name in the Inbox")
    }

    private fun sameFile(a: File, b: File): Boolean =
        runCatching { a.canonicalPath == b.canonicalPath }.getOrDefault(false)

    private suspend fun attach(book: Book, isNew: Boolean, wishId: String?): AcquireOutcome {
        var attached = false
        var problem: String? = null
        if (wishId != null) {
            val wish = repo.dao.wish(wishId)
            if (wish?.linkedBookId == book.id) attached = true
            else
                try {
                    repo.attach(wishId, book.id)
                    attached = true
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    // The book is kept either way; only the link to the entry failed.
                    problem =
                        if (e is IllegalArgumentException || e is IllegalStateException)
                            userMessage(e)
                        else null
                }
        }
        return if (isNew) AcquireOutcome.Saved(book.id, book.title, attached, problem)
        else AcquireOutcome.Already(book.id, book.title, attached, problem)
    }
}
