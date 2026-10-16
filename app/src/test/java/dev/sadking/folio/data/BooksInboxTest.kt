package dev.sadking.folio.data

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.core.Epub
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
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

/** The Books/Inbox drop folder and adding books straight into the Books folder (M9 step 2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BooksInboxTest {
    private lateinit var base: File
    private lateinit var books: File
    private lateinit var inboxDir: File
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository
    /** A clock that says every file has settled. */
    private val later = { System.currentTimeMillis() + 60_000 }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = File(context.cacheDir, "inbox-${UUID.randomUUID()}").apply { mkdirs() }
        books = File(base, "Books")
        inboxDir = File(books, "Inbox")
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

    private fun epub(file: File, title: String, author: String, uid: String = title) {
        file.parentFile!!.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            mapOf(
                    "mimetype" to "application/epub+zip",
                    "META-INF/container.xml" to
                        """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                    "book.opf" to
                        """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title><dc:creator>$author</dc:creator><dc:identifier id="uid">urn:test:$uid</dc:identifier></metadata><manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""",
                    "c.xhtml" to
                        "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>$title</body></html>",
                )
                .forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
        }
    }

    /** A ZIP with a mimetype and nothing else: it looks like an EPUB but has no book in it. */
    private fun brokenEpub(file: File) {
        file.parentFile!!.mkdirs()
        val bytes = "application/epub+zip".toByteArray()
        ZipOutputStream(file.outputStream()).use { zip ->
            val entry =
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(bytes) }.value
                }
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
    }

    private fun dropped(
        name: String,
        title: String = "Home",
        author: String = "Ann Lee",
        uid: String = title,
    ) = File(inboxDir, name).also { epub(it, title, author, uid) }

    @Test
    fun aReadableBookIsMovedIntoAuthorTitleAndAdded() = runBlocking {
        val file = dropped("download (1).epub")
        val hash = Epub.sha256(file)
        val result = BooksInbox(repo, later).run()
        assertEquals(1, result.added.size)
        assertFalse(file.exists())
        val target = File(books, "Ann Lee/Home.epub")
        assertEquals(hash, Epub.sha256(target))
        val book = repo.dao.allBooks().single()
        assertEquals(target.absolutePath, book.currentPath)
        assertEquals(target.absolutePath, book.originalPath)
        assertEquals(hash, book.currentHash)
        assertEquals("epub", book.format)
        assertEquals(book.id, result.added.single().bookId)
    }

    @Test
    fun aBookAlreadyInTheLibraryGoesToNotImportedAndIsKept() = runBlocking {
        val first = dropped("a.epub")
        val bytes = first.readBytes()
        BooksInbox(repo, later).run()
        val book = repo.dao.allBooks().single()
        File(inboxDir, "a.epub").writeBytes(bytes)
        val result = BooksInbox(repo, later).run()
        val entry = result.notImported.single()
        assertEquals(InboxReason.ALREADY_IN_LIBRARY, entry.reason)
        val kept = File(inboxDir, "Not imported/a.epub")
        assertEquals(kept.absolutePath, entry.file.absolutePath)
        assertArrayEquals(bytes, kept.readBytes())
        assertFalse(File(inboxDir, "a.epub").exists())
        assertEquals(1, repo.dao.allBooks().size)
        assertEquals(book.id, entry.bookId)
    }

    @Test
    fun filesLibrilloCannotTakeGoToNotImportedWithTheirReasonAndNothingIsOverwritten() =
        runBlocking {
            inboxDir.mkdirs()
            File(inboxDir, "Not imported").mkdirs()
            File(inboxDir, "notes.txt").writeText("not a book")
            brokenEpub(File(inboxDir, "broken.epub"))
            File(inboxDir, "Not imported/notes.txt").writeText("earlier")
            val result = BooksInbox(repo, later).run()
            assertEquals(
                InboxReason.NOT_A_BOOK,
                result.notImported.single { it.name == "notes.txt" }.reason,
            )
            assertEquals(
                InboxReason.UNREADABLE,
                result.notImported.single { it.name == "broken.epub" }.reason,
            )
            assertEquals("earlier", File(inboxDir, "Not imported/notes.txt").readText())
            assertEquals("not a book", File(inboxDir, "Not imported/notes (2).txt").readText())
            assertTrue(File(inboxDir, "Not imported/broken.epub").exists())
            assertTrue(repo.dao.allBooks().isEmpty())
            assertTrue(inboxDir.listFiles()!!.none { it.isFile })
        }

    @Test
    fun aFileStillBeingWrittenWaitsForTheNextPass() = runBlocking {
        val file = dropped("new.epub")
        val inbox = BooksInbox(repo)
        val first = inbox.run()
        assertEquals(1, first.waiting)
        assertTrue(file.exists())
        assertTrue(repo.dao.allBooks().isEmpty())
        file.setLastModified(System.currentTimeMillis() - 10_000)
        val second = inbox.run()
        assertEquals(0, second.waiting)
        assertEquals(1, second.added.size)
    }

    @Test
    fun aFileWhoseSizeHoldsForFiveSecondsIsFiledEvenWithAFutureDate() = runBlocking {
        var now = System.currentTimeMillis()
        val file = dropped("odd.epub")
        file.setLastModified(now + 3_600_000)
        val inbox = BooksInbox(repo) { now }
        assertEquals(1, inbox.run().waiting)
        now += 6_000
        assertEquals(1, inbox.run().added.size)
    }

    @Test
    fun hiddenFilesAndSubfoldersAreLeftAlone() = runBlocking {
        inboxDir.mkdirs()
        val hidden =
            File(inboxDir, ".pending-1700000000-x.epub").also { epub(it, "Hidden", "Ann Lee") }
        val nested = File(inboxDir, "sub/y.epub").also { epub(it, "Nested", "Ann Lee") }
        assertEquals(InboxResult(), BooksInbox(repo, later).run())
        assertTrue(hidden.exists())
        assertTrue(nested.exists())
        assertTrue(repo.dao.allBooks().isEmpty())
    }

    @Test
    fun withoutAccessTheInboxIsNotTouched() = runBlocking {
        repo.folderAccess = { false }
        val file = dropped("a.epub")
        assertEquals(InboxResult(), BooksInbox(repo, later).run())
        assertTrue(file.exists())
    }

    @Test
    fun aFileThatCannotBeFiledStaysInTheInbox() = runBlocking {
        // A plain file where the author's folder should go, so the book can't be placed.
        File(books, "Ann Lee").apply {
            parentFile!!.mkdirs()
            writeText("in the way")
        }
        val file = dropped("a.epub")
        val hash = Epub.sha256(file)
        val result = BooksInbox(repo, later).run()
        assertEquals(1, result.retry)
        assertTrue(file.exists())
        assertEquals(hash, Epub.sha256(file))
        assertTrue(repo.dao.allBooks().isEmpty())
        assertTrue(repo.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun importWritesStraightIntoTheBooksFolderAndLeavesTheSource() = runBlocking {
        val source = File(base, "Download/home.epub").also { epub(it, "Home", "Ann Lee") }
        val (book, added) = repo.import(Uri.fromFile(source))
        assertTrue(added)
        val target = File(books, "Ann Lee/Home.epub").absolutePath
        assertEquals(target, book.currentPath)
        assertEquals(target, book.originalPath)
        assertTrue(source.exists())
        assertEquals(Epub.sha256(source), book.currentHash)
        assertTrue(File(books, ".librillo").walk().none { it.isFile })
        assertTrue(inboxDir.isDirectory)

        val second =
            File(base, "Download/home-2.epub").also {
                epub(it, "Home", "Ann Lee", uid = "another edition")
            }
        val (other, otherAdded) = repo.import(Uri.fromFile(second))
        assertTrue(otherAdded)
        assertEquals(File(books, "Ann Lee/Home (2).epub").absolutePath, other.currentPath)
    }

    @Test
    fun withoutAccessImportStaysPrivate() = runBlocking {
        repo.folderAccess = { false }
        val source = File(base, "Download/home.epub").also { epub(it, "Home", "Ann Lee") }
        val (book, added) = repo.import(Uri.fromFile(source))
        assertTrue(added)
        assertTrue(repo.isPrivate(File(book.currentPath)))
        assertFalse(books.exists())
    }

    @Test
    fun oneMessageForThePass() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        fun entry(r: InboxReason?) = InboxEntry("x", File("x"), null, r)
        assertEquals(
            "Added 2 books from the Inbox. 1 was already in your library.",
            InboxResult(
                    added = listOf(entry(null), entry(null)),
                    notImported = listOf(entry(InboxReason.ALREADY_IN_LIBRARY)),
                )
                .message(res),
        )
        assertEquals(
            "1 file in the Inbox was already in your library. " +
                "1 file couldn't be added. It's in Inbox/Not imported.",
            InboxResult(
                    notImported =
                        listOf(entry(InboxReason.ALREADY_IN_LIBRARY), entry(InboxReason.UNREADABLE))
                )
                .message(res),
        )
        assertNull(InboxResult(waiting = 1).message(res))
    }
}
