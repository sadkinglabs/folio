package dev.sadking.folio.acquire

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.core.Epub
import dev.sadking.folio.data.BooksInbox
import dev.sadking.folio.data.InboxResult
import dev.sadking.folio.data.LibraryDatabase
import dev.sadking.folio.data.LibraryRepository
import dev.sadking.folio.data.WishBook
import dev.sadking.folio.data.encode
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A finished browser download going into the library through the normal import (M5): into the Books
 * folder by way of the Inbox, or into private storage; never over a file; duplicates and protected
 * files dropped; linked to its Want to Read entry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AcquireImportTest {
    private lateinit var base: File
    private lateinit var books: File
    private lateinit var inbox: File
    private lateinit var cache: File
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository
    private lateinit var importer: AcquireImporter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = File(context.cacheDir, "acquire-${UUID.randomUUID()}").apply { mkdirs() }
        books = File(base, "Books")
        inbox = File(books, BooksInbox.FOLDER)
        cache = File(base, "cache")
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
        importer = AcquireImporter(repo, cache)
    }

    @After
    fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    private fun epub(
        file: File,
        title: String = "Home",
        author: String = "Ann Lee",
        extra: Map<String, String> = emptyMap(),
    ) {
        file.parentFile!!.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            (mapOf(
                    "mimetype" to "application/epub+zip",
                    "META-INF/container.xml" to
                        """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                    "book.opf" to
                        """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title><dc:creator>$author</dc:creator><dc:identifier id="uid">urn:test:$title</dc:identifier></metadata><manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""",
                    "c.xhtml" to
                        "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>$title</body></html>",
                ) + extra)
                .forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
        }
    }

    /** A staged download holding an EPUB, as the downloader leaves it. */
    private fun downloaded(title: String = "Home", extra: Map<String, String> = emptyMap()) =
        importer.stage().also { epub(it.file, title, extra = extra) }

    private fun inboxFiles() = inbox.listFiles().orEmpty().filter { it.isFile }

    @Test
    fun withTheFolderTheBookLandsInAuthorTitleAndTheInboxIsLeftEmpty() = runBlocking {
        val staged = downloaded()
        assertTrue(staged.toFolder)
        assertEquals(inbox.canonicalFile, staged.file.parentFile!!.canonicalFile)
        assertTrue(staged.file.name.startsWith(".download-"))
        val hash = Epub.sha256(staged.file)
        val outcome = importer.finish(staged, "download.epub", null)
        val book = repo.dao.allBooks().single()
        assertEquals(AcquireOutcome.Saved(book.id, "Home", attached = false), outcome)
        val target = File(books, "Ann Lee/Home.epub")
        assertEquals(target.absolutePath, book.currentPath)
        assertEquals(hash, Epub.sha256(target))
        assertTrue(inboxFiles().isEmpty())
    }

    @Test
    fun aDownloadStillArrivingIsInvisibleToTheInboxPass() = runBlocking {
        val staged = downloaded()
        val later = { System.currentTimeMillis() + 60_000 }
        assertEquals(InboxResult(), BooksInbox(repo, later).run())
        assertTrue(staged.file.exists())
        assertTrue(repo.dao.allBooks().isEmpty())
    }

    @Test
    fun aDuplicateIsDroppedAndTheBookAlreadyKeptIsUntouched() = runBlocking {
        importer.finish(downloaded(), "first.epub", null)
        val kept = repo.dao.allBooks().single()
        val keptFile = File(kept.currentPath)
        val keptBytes = keptFile.readBytes()
        val again = importer.stage().also { keptFile.copyTo(it.file) }
        val outcome = importer.finish(again, "second.epub", null)
        assertEquals(AcquireOutcome.Already(kept.id, "Home", attached = false), outcome)
        assertEquals(listOf(kept), repo.dao.allBooks())
        assertArrayEquals(keptBytes, keptFile.readBytes())
        assertTrue(inboxFiles().isEmpty())
        assertFalse(File(inbox, BooksInbox.NOT_IMPORTED).exists())
    }

    @Test
    fun notABookLeavesNothingBehind() = runBlocking {
        val staged = importer.stage().also { it.file.writeText("<html>Sign in</html>") }
        assertEquals(AcquireOutcome.NotABook, importer.finish(staged, "book.epub", null))
        assertTrue(inbox.listFiles().orEmpty().isEmpty())
        assertTrue(repo.dao.allBooks().isEmpty())
    }

    @Test
    fun aProtectedBookIsDeletedAndNeverImported() = runBlocking {
        val adobe = downloaded(extra = mapOf("META-INF/rights.xml" to "<rights/>"))
        assertEquals(AcquireOutcome.Protected, importer.finish(adobe, "loan.epub", null))
        val acsm =
            importer.stage().also {
                it.file.writeText(
                    """<fulfillmentToken xmlns="http://ns.adobe.com/adept"><distributor/></fulfillmentToken>"""
                )
            }
        assertEquals(AcquireOutcome.Protected, importer.finish(acsm, "URLLink.acsm", null))
        assertTrue(inbox.listFiles().orEmpty().isEmpty())
        assertTrue(repo.dao.allBooks().isEmpty())
    }

    @Test
    fun withoutTheFolderTheBookGoesToPrivateStorage() = runBlocking {
        repo.folderAccess = { false }
        val staged = downloaded()
        assertFalse(staged.toFolder)
        assertTrue(staged.file.canonicalPath.startsWith(File(cache, "downloads").canonicalPath))
        val outcome = importer.finish(staged, "home.epub", null)
        val book = repo.dao.allBooks().single()
        assertEquals(AcquireOutcome.Saved(book.id, "Home", attached = false), outcome)
        assertTrue(repo.isPrivate(File(book.currentPath)))
        assertTrue(File(book.currentPath).isFile)
        assertFalse(staged.folder!!.exists())
        assertFalse(books.exists())
    }

    @Test
    fun withoutTheFolderNotABookClearsTheCache() = runBlocking {
        repo.folderAccess = { false }
        val staged = importer.stage().also { it.file.writeText("plain text") }
        assertEquals(AcquireOutcome.NotABook, importer.finish(staged, "a.epub", null))
        assertFalse(staged.folder!!.exists())
        assertTrue(repo.dao.allBooks().isEmpty())
    }

    @Test
    fun aWishIdLinksTheBookToItsEntry() = runBlocking {
        db.library().insertWish(WishBook("wish-1", "k1", "Home", encode(listOf("Ann Lee"))))
        val outcome = importer.finish(downloaded(), "home.epub", "wish-1")
        val book = repo.dao.allBooks().single()
        assertEquals(AcquireOutcome.Saved(book.id, "Home", attached = true), outcome)
        assertEquals(book.id, repo.dao.wish("wish-1")!!.linkedBookId)
        // Getting the same book again for the same entry is still linked, not an error.
        val again = importer.stage().also { File(book.currentPath).copyTo(it.file) }
        assertEquals(
            AcquireOutcome.Already(book.id, "Home", attached = true),
            importer.finish(again, "home.epub", "wish-1"),
        )
    }

    @Test
    fun aFailedLinkKeepsTheBookAndSaysWhy() = runBlocking {
        val outcome = importer.finish(downloaded(), "home.epub", "missing-entry")
        val saved = outcome as AcquireOutcome.Saved
        assertFalse(saved.attached)
        assertEquals("This Want to Read entry is no longer in your library.", saved.attachProblem)
        assertEquals(1, repo.dao.allBooks().size)
    }

    @Test
    fun aFileAlreadyAtTheNameIsNeverReplaced() = runBlocking {
        inbox.mkdirs()
        val other = File(inbox, "home.epub").apply { writeText("someone else's file") }
        val outcome = importer.finish(downloaded(), "home.epub", null)
        assertTrue(outcome is AcquireOutcome.Saved)
        assertEquals("someone else's file", other.readText())
        // A different book with the same title and author takes a free name beside the first.
        val first = repo.dao.allBooks().single()
        val firstBytes = File(first.currentPath).readBytes()
        val different = importer.stage().also { epub(it.file, extra = mapOf("x.txt" to "2")) }
        assertTrue(importer.finish(different, "home.epub", null) is AcquireOutcome.Saved)
        assertArrayEquals(firstBytes, File(first.currentPath).readBytes())
        assertEquals(2, repo.dao.allBooks().map { it.currentPath }.distinct().size)
        assertEquals(listOf(other), inboxFiles())
    }

    @Test
    fun aFullPhoneIsSaidPlainlyNotAsAConnectionProblem() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        val full = java.io.IOException("write failed: ENOSPC (No space left on device)")
        assertEquals(AcquireOutcome.NoSpace, failure(full))
        assertEquals(AcquireOutcome.NoSpace, failure(RuntimeException("copy", full)))
        assertEquals(AcquireOutcome.Failed, failure(java.io.IOException("HTTP 500")))
        assertEquals(
            "Your phone is out of space. Free some space and try again.",
            AcquireOutcome.NoSpace.headline(res),
        )
        assertTrue(AcquireOutcome.NoSpace.failed)
    }
}
