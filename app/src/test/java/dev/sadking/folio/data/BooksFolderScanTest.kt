package dev.sadking.folio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.core.Epub
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Books folder scan: files moved, changed, gone and new (M9 step 2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BooksFolderScanTest {
    private lateinit var base: File
    private lateinit var books: File
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository
    private lateinit var scan: BooksFolderScan
    private lateinit var notes: android.content.SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = File(context.cacheDir, "scan-${UUID.randomUUID()}").apply { mkdirs() }
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
        notes = context.getSharedPreferences("scan-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        // Files the test writes are seconds old at most; a clock a minute ahead sees them settled.
        scan =
            BooksFolderScan(
                repo,
                File(base, "private/books-folder-cache.json"),
                notes,
                now = { System.currentTimeMillis() + 60_000 },
            )
    }

    @After
    fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    private fun epub(file: File, title: String, author: String, uid: String = title): File {
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
        return file
    }

    private fun pdf(file: File, title: String): File = file.apply {
        parentFile!!.mkdirs()
        writeBytes(
            ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n5 0 obj\n" +
                    "<< /Title ($title) /Author (Ann Lee) >>\nendobj\n" +
                    "trailer\n<< /Root 1 0 R /Info 5 0 R >>\n%%EOF\n")
                .toByteArray(Charsets.ISO_8859_1)
        )
    }

    /** A book whose only copy is at [path] in the Books folder, as the move leaves it. */
    private suspend fun shelved(
        path: String,
        title: String,
        author: String = "Ann Lee",
        format: String = "epub",
    ): Book {
        val file = File(books, path)
        if (format == "pdf") pdf(file, title) else epub(file, title, author)
        val hash = Epub.sha256(file)
        return Book(
                UUID.randomUUID().toString(),
                title,
                encode(listOf(author)),
                originalPath = file.absolutePath,
                currentPath = file.absolutePath,
                originalHash = hash,
                currentHash = hash,
                format = format,
            )
            .also { repo.dao.insert(it) }
    }

    private fun position(book: Book, percent: Double) =
        PhoneReading(
            bookId = book.id,
            revisionHash = book.currentHash,
            locator = "{}",
            xpointer = "",
            percent = percent,
            updatedAt = 1L,
        )

    @Test
    fun aFileRenamedByHandIsFollowedWithItsProgressNotesAndLists() = runBlocking {
        val book = shelved("Ann Lee/Home.epub", "Home")
        repo.dao.savePhoneReading(position(book, 0.4))
        repo.dao.annotation(
            BookAnnotation(
                id = "n1",
                bookId = book.id,
                deviceId = "phone",
                text = "a passage",
                note = "a note",
                chapter = "",
                date = "2026-10-16",
                position = "",
                endPosition = "",
                kind = "highlight",
            )
        )
        repo.dao.insertShelf(Shelf("s1", "Favourites"))
        repo.dao.addMembership(Membership(book.id, "s1"))
        val renamed = File(books, "Elsewhere/Home, signed.epub").also { it.parentFile!!.mkdirs() }
        assertTrue(File(book.currentPath).renameTo(renamed))

        val result = scan.run()

        assertEquals(listOf("Home"), result.relinked)
        assertTrue(result.missing.isEmpty() && result.added.isEmpty())
        val moved = repo.dao.book(book.id)!!
        assertEquals(renamed.absolutePath, moved.currentPath)
        assertEquals(renamed.absolutePath, moved.originalPath)
        assertEquals(book.currentHash, moved.currentHash)
        assertEquals(0.4, repo.dao.phoneReading(book.id, book.currentHash)!!.percent!!, 0.0)
        assertEquals(1, repo.dao.allAnnotations().size)
        assertEquals(listOf(Membership(book.id, "s1")), repo.dao.allMemberships())
    }

    @Test
    fun aFileChangedElsewhereIsANewRevisionAndSaysSoUntilOpened() = runBlocking {
        val book = shelved("Ann Lee/Home.epub", "Home")
        repo.dao.savePhoneReading(position(book, 0.5))
        val file = epub(File(book.currentPath), "Home", "Ann Lee", uid = "fixed in Calibre")
        val hash = Epub.sha256(file)

        val result = scan.run()

        assertEquals(listOf("Home"), result.revised)
        assertTrue(result.added.isEmpty())
        val revised = repo.dao.book(book.id)!!
        assertEquals(book.currentPath, revised.currentPath)
        assertEquals(hash, revised.currentHash)
        // The original was this same file, so the original is the new content too.
        assertEquals(hash, revised.originalHash)
        // The old position is kept, and the reader finds it under the new revision.
        assertNotNull(repo.dao.phoneReading(book.id, book.currentHash))
        assertEquals(0.5, repo.dao.phoneReading(book.id, hash)!!.percent!!, 0.0)
        assertEquals(setOf(book.id), scan.changedOutside())
        scan.opened(book.id)
        assertEquals(emptySet<String>(), scan.changedOutside())
        assertEquals(ScanResult(), scan.run().copy(finishedAt = 0))
    }

    @Test
    fun aDeletedFileLeavesTheBookMissingAndItsReturnLinksItBack() = runBlocking {
        val book = shelved("Ann Lee/Home.epub", "Home")
        repo.dao.savePhoneReading(position(book, 0.3))
        val bytes = File(book.currentPath).readBytes()
        assertTrue(File(book.currentPath).delete())

        val gone = scan.run()

        assertEquals(listOf("Home"), gone.missing)
        assertEquals(book, repo.dao.book(book.id))
        assertNotNull(repo.dao.phoneReading(book.id, book.currentHash))
        assertEquals(setOf(book.id), scan.missing())

        val found =
            File(books, "Found/Home.epub").apply {
                parentFile!!.mkdirs()
                writeBytes(bytes)
            }
        val back = scan.run()

        assertEquals(listOf("Home"), back.relinked)
        assertEquals(found.absolutePath, repo.dao.book(book.id)!!.currentPath)
        assertEquals(emptySet<String>(), scan.missing())
        assertEquals(back, scan.lastResult())
    }

    @Test
    fun aNewFileIsAddedWhereItIsAndOnlyOnce() = runBlocking {
        val file =
            epub(
                File(books, "my downloads/the-left-hand.epub"),
                "The Left Hand of Darkness",
                "Ursula K. Le Guin",
            )
        // The same content under a name that sorts later is not added twice.
        File(books, "my downloads/zz copy.epub").writeBytes(file.readBytes())
        val fieldNotes = pdf(File(books, "Ann Lee/Field Notes.pdf"), "Field Notes")

        val result = scan.run()

        assertEquals(setOf("The Left Hand of Darkness", "Field Notes"), result.added.toSet())
        val added = repo.dao.allBooks().associateBy { it.title }
        assertEquals(2, added.size)
        val novel = added.getValue("The Left Hand of Darkness")
        assertEquals(file.absolutePath, novel.currentPath)
        assertEquals(file.absolutePath, novel.originalPath)
        assertEquals("epub", novel.format)
        assertEquals("pdf", added.getValue("Field Notes").format)
        assertTrue(file.isFile && fieldNotes.isFile)
        assertTrue(scan.run().added.isEmpty())
    }

    @Test
    fun aFileThatIsNotABookIsLeftWhereItIs() = runBlocking {
        val junk =
            File(books, "Ann Lee/Notes.epub").apply {
                parentFile!!.mkdirs()
                writeText("not a book")
            }

        val result = scan.run()

        assertEquals(listOf("Ann Lee/Notes.epub"), result.notAdded)
        assertTrue(result.added.isEmpty())
        assertTrue(junk.isFile)
        assertTrue(repo.dao.allBooks().isEmpty())
    }

    @Test
    fun inboxLibrillosOwnHiddenAndSdrFoldersAreNotScanned() = runBlocking {
        epub(File(books, "Inbox/Dropped.epub"), "Dropped", "Ann Lee")
        epub(File(books, ".librillo/removed/Kept.epub"), "Kept", "Ann Lee")
        epub(File(books, "Ann Lee/Home.sdr/Inside.epub"), "Inside", "Ann Lee")
        epub(File(books, "Ann Lee/.Hidden.epub"), "Hidden", "Ann Lee")
        epub(File(books, "Ann Lee/.Home.epub.part"), "Part", "Ann Lee")

        val result = scan.run()

        assertTrue(result.added.isEmpty() && result.notAdded.isEmpty())
        assertTrue(repo.dao.allBooks().isEmpty())
    }

    @Test
    fun aRecentlyRemovedBooksFileIsLeftAloneUntilItIsRestored() = runBlocking {
        val book = shelved("Ann Lee/Home.epub", "Home")
        val (_, _, groups) = repo.removeLocal(setOf(book.id))
        val renamed = File(books, "Ann Lee/Home again.epub")
        assertTrue(File(book.currentPath).renameTo(renamed))

        assertEquals(ScanResult(), scan.run().copy(finishedAt = 0))
        assertEquals(book.currentPath, repo.dao.book(book.id)!!.currentPath)

        groups.forEach { repo.restoreRemoved(it) }
        assertEquals(listOf("Home"), scan.run().relinked)
        assertEquals(renamed.absolutePath, repo.dao.book(book.id)!!.currentPath)
    }

    @Test
    fun aBookStillInPrivateStorageIsLeftAloneAndItsCopyIsNotAdded() = runBlocking {
        val id = UUID.randomUUID().toString()
        val file = epub(File(repo.root, "$id/original.epub"), "Home", "Ann Lee")
        val hash = Epub.sha256(file)
        val book =
            Book(
                    id,
                    "Home",
                    encode(listOf("Ann Lee")),
                    originalPath = file.absolutePath,
                    currentPath = file.absolutePath,
                    originalHash = hash,
                    currentHash = hash,
                )
                .also { repo.dao.insert(it) }
        File(books, "Ann Lee/Home.epub").apply {
            parentFile!!.mkdirs()
            writeBytes(file.readBytes())
        }

        assertEquals(ScanResult(), scan.run().copy(finishedAt = 0))
        assertEquals(book, repo.dao.book(id))
    }

    @Test
    fun anUnchangedFileIsNotHashedAgain() = runBlocking {
        val book = shelved("Ann Lee/Field Notes.pdf", "Field Notes A", format = "pdf")
        scan.run()
        val file = File(book.currentPath)
        val time = file.lastModified()
        val other = pdf(File(base, "other.pdf"), "Field Notes B").readBytes()
        assertEquals(file.length(), other.size.toLong())
        file.writeBytes(other)
        assertTrue(file.setLastModified(time))

        // Same size and time: the cached hash is trusted, so the change isn't seen.
        assertTrue(scan.run().revised.isEmpty())

        assertTrue(file.setLastModified(time + 10_000))
        assertEquals(listOf("Field Notes A"), scan.run().revised)
        assertEquals(Epub.sha256(file), repo.dao.book(book.id)!!.currentHash)
    }

    @Test
    fun aDamagedCacheIsRebuilt() = runBlocking {
        val cache =
            File(base, "private/books-folder-cache.json").apply {
                parentFile!!.mkdirs()
                writeText("{not json")
            }
        val book = shelved("Ann Lee/Home.epub", "Home")
        assertTrue(File(book.currentPath).delete())

        assertEquals(listOf("Home"), scan.run().missing)
        assertTrue(JSONObject(cache.readText()).has("files"))
    }

    @Test
    fun withoutFolderAccessNothingIsConcluded() = runBlocking {
        val book = shelved("Ann Lee/Home.epub", "Home")
        assertTrue(File(book.currentPath).delete())
        val blind =
            BooksFolderScan(
                repo,
                File(base, "private/other-cache.json"),
                notes,
                canRead = { false },
            )

        assertEquals(ScanResult(), blind.run())
        assertEquals(emptySet<String>(), blind.missing())
        assertFalse(File(base, "private/other-cache.json").exists())
    }
}
