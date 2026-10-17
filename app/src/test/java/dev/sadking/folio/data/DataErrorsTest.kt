package dev.sadking.folio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jcraft.jsch.SftpException
import dev.sadking.folio.core.Epub
import dev.sadking.folio.core.Metadata
import dev.sadking.folio.failureText
import dev.sadking.folio.safeError
import java.io.File
import java.io.IOException
import java.nio.file.FileSystemException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Typed errors (M6): failures that were silent or in code-only English now read plainly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataErrorsTest {
    private lateinit var context: Context
    private lateinit var base: File
    private lateinit var books: File
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository

    private val outOfSpace = "Your phone is out of space. Free some space and try again."

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        base = File(context.cacheDir, "errors-${UUID.randomUUID()}").apply { mkdirs() }
        books = File(base, "Books").apply { mkdirs() }
        val isolated =
            object : ContextWrapper(context) {
                override fun getFilesDir() = File(base, "private").apply { mkdirs() }
            }
        db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repo = LibraryRepository(isolated, db) { books }
        repo.folderAccess = { true }
    }

    @After
    fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    private fun epub(file: File, title: String): File {
        file.parentFile!!.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            mapOf(
                    "mimetype" to "application/epub+zip",
                    "META-INF/container.xml" to
                        """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                    "book.opf" to
                        """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title><dc:creator>Ann Lee</dc:creator><dc:identifier id="uid">urn:test:$title</dc:identifier></metadata><manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""",
                    "c.xhtml" to
                        "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>$title</body></html>",
                )
                .forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
        }
        return file
    }

    private suspend fun shelved(title: String, format: String = "epub"): Book {
        val file = epub(File(books, "Ann Lee/$title.$format"), title)
        val hash = Epub.sha256(file)
        return Book(
                UUID.randomUUID().toString(),
                title,
                encode(listOf("Ann Lee")),
                originalPath = file.absolutePath,
                currentPath = file.absolutePath,
                originalHash = hash,
                currentHash = hash,
                format = format,
            )
            .also { repo.dao.insert(it) }
    }

    @Test
    fun noSpaceLeftIsRecognisedHoweverItIsWrapped() {
        // As Android's streams and java.nio report it.
        assertTrue(isStorageFull(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(isStorageFull(FileSystemException("a.epub", null, "No space left on device")))
        assertTrue(isStorageFull(RuntimeException(IOException("x", IOException("ENOSPC")))))
        assertTrue(isStorageFull(StorageFull("plain")))
        assertFalse(isStorageFull(IOException("Permission denied")))
        assertFalse(isStorageFull(IllegalStateException()))
    }

    @Test
    fun outOfSpaceReadsPlainlyInEveryMessage() {
        val res = context.resources
        val enospc = IOException("write failed: ENOSPC (No space left on device)")
        assertEquals(outOfSpace, safeError(enospc, res))
        assertEquals(outOfSpace, importFailure(enospc, res))
        assertEquals(outOfSpace, importFailure(StorageFull(outOfSpace, enospc)))
        // A full disk on the e-reader is not the phone's: it stays a file transfer problem.
        val remote = SftpException(4, "No space left on device")
        assertEquals(res.failureText().fileTransfer, safeError(remote, res))
        // Other failures read as before.
        assertEquals(
            "File is missing or unavailable. Download it locally and choose it again.",
            importFailure(java.io.FileNotFoundException("x"), res),
        )
    }

    @Test
    fun theRepositoryTurnsNoSpaceLeftIntoStorageFull() {
        val e =
            assertThrows(StorageFull::class.java) {
                repo.spaceChecked {
                    throw IOException("write failed: ENOSPC (No space left on device)")
                }
            }
        assertEquals(outOfSpace, e.message)
        assertTrue(e.cause is IOException)
        // Anything else goes through unchanged.
        val other = IOException("Permission denied")
        assertSame(
            other,
            assertThrows(IOException::class.java) { repo.spaceChecked { throw other } },
        )
    }

    @Test
    fun editingAFileChangedOutsideLibrilloIsRefusedAndWritesNothing() = runBlocking {
        val book = shelved("Home")
        File(book.currentPath).appendText("changed by another app")
        val before = books.walk().map { it.relativeTo(books).path }.toSet()

        val e =
            runCatching { repo.edit(book.id, Metadata("Away", listOf("Ann Lee")), null) }
                .exceptionOrNull()

        assertTrue(e is FileChangedOutside)
        assertTrue(e is IllegalStateException)
        assertEquals(
            "This book's file changed outside Librillo. Open the Books folder to check it.",
            e!!.message,
        )
        assertEquals(before, books.walk().map { it.relativeTo(books).path }.toSet())
        assertEquals(book, repo.dao.book(book.id))
    }

    @Test
    fun aPdfChangedOutsideLibrilloIsRefusedToo() = runBlocking {
        val book = shelved("Scan", format = "pdf")
        File(book.currentPath).appendText("changed")
        val e =
            runCatching { repo.edit(book.id, Metadata("Other", listOf("Ann Lee")), null) }
                .exceptionOrNull()
        assertTrue(e is FileChangedOutside)
        assertEquals(book, repo.dao.book(book.id))
    }

    @Test
    fun attachingExplainsWhatIsGoneInPlainWords() = runBlocking {
        val book = shelved("Home")
        val missingWish = runCatching { repo.attach("no-such-entry", book.id) }.exceptionOrNull()!!
        assertEquals("This Want to Read entry is no longer in your library.", missingWish.message)
        val wish = WishBook("w1", "manual:w1", "Home", encode(listOf("Ann Lee")))
        repo.dao.insertWish(wish)
        val missingBook = runCatching { repo.attach(wish.id, "no-such-book") }.exceptionOrNull()!!
        assertEquals("This book is no longer in your library", missingBook.message)
        repo.dao.markRemoved(RemovedEntry(book.id, book.id, book.title, "EPUB", 1L))
        val removed = runCatching { repo.attach(wish.id, book.id) }.exceptionOrNull()!!
        assertTrue(removed is IllegalArgumentException)
        assertEquals("Restore these books from Recently removed first.", removed.message)
    }

    @Test
    fun theFolderScanSaysWhyAFileWasNotAdded() = runBlocking {
        File(books, "Odd/notes.epub").apply {
            parentFile!!.mkdirs()
            writeText("not a book at all")
        }
        // Looks like an EPUB from its first bytes, but has no package to read.
        File(books, "Odd/broken.epub").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("mimetype"))
                zip.write("application/epub+zip".toByteArray())
                zip.closeEntry()
            }
        }
        val scan =
            BooksFolderScan(
                repo,
                File(base, "private/cache.json"),
                context.getSharedPreferences("errors-${UUID.randomUUID()}", Context.MODE_PRIVATE),
                now = { System.currentTimeMillis() + 60_000 },
            )

        val result = scan.run()

        assertEquals(listOf("Odd/broken.epub", "Odd/notes.epub"), result.notAdded)
        assertEquals(
            mapOf(
                "Odd/broken.epub" to InboxReason.UNREADABLE,
                "Odd/notes.epub" to InboxReason.NOT_A_BOOK,
            ),
            result.notAddedReasons,
        )
        // The reasons are kept with the last result, and both files stay where they are.
        assertEquals(result.notAddedReasons, scan.lastResult()!!.notAddedReasons)
        assertTrue(File(books, "Odd/broken.epub").isFile)
        assertTrue(File(books, "Odd/notes.epub").isFile)
    }

    @Test
    fun contentAlreadyInTheLibraryIsNotAddedAgainAndSaysWhy() = runBlocking {
        val kept = shelved("Home")
        val copy = File(kept.currentPath).copyTo(File(books, "Copies/Home copy.epub"))
        val (book, reason) = repo.lock.withLock { repo.placeInFolder(copy, kept.currentHash) }
        assertNull(book)
        assertEquals(InboxReason.ALREADY_IN_LIBRARY, reason)
        assertTrue(copy.isFile)
        assertEquals(1, repo.dao.allStoredBooks().size)
    }

    @Test
    fun aBookWhoseFileWasReplacedByAnotherKindIsChangedNotMissing() = runBlocking {
        val book = shelved("Home")
        // Another app wrote a PDF over the EPUB, keeping its name.
        File(book.currentPath)
            .writeBytes("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n".toByteArray())
        val scan =
            BooksFolderScan(
                repo,
                File(base, "private/cache.json"),
                context.getSharedPreferences("errors-${UUID.randomUUID()}", Context.MODE_PRIVATE),
                now = { System.currentTimeMillis() + 60_000 },
            )

        val result = scan.run()

        assertEquals(listOf("Home"), result.notAccepted)
        assertTrue(result.missing.isEmpty())
        assertFalse(book.id in scan.missing())
        assertEquals(listOf("Home"), scan.lastResult()!!.notAccepted)
        assertEquals(book, repo.dao.book(book.id))
    }

    @Test
    fun aFullPhoneIsSaidOnceAfterTheInboxPass() {
        val res = context.resources
        assertEquals(
            "1 file in the Inbox couldn't be filed. Librillo will try again next time. " +
                outOfSpace,
            InboxResult(retry = 1, storageFull = true).message(res),
        )
        assertEquals(
            InboxResult(retry = 2, storageFull = true),
            InboxResult(retry = 1) + InboxResult(retry = 2, storageFull = true),
        )
    }
}
