package dev.sadking.folio.data

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.core.Epub
import dev.sadking.folio.core.Metadata
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

/** Import of PDF, CBZ and CBR files, and edits and moves that keep their format (M9 step 1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImportFormatsTest {
    private lateinit var base: File
    private lateinit var books: File
    private lateinit var incoming: File
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = File(context.cacheDir, "formats-${UUID.randomUUID()}").apply { mkdirs() }
        books = File(base, "Books")
        incoming = File(base, "incoming").apply { mkdirs() }
        val isolated =
            object : ContextWrapper(context) {
                override fun getFilesDir() = File(base, "private").apply { mkdirs() }
            }
        db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repo = LibraryRepository(isolated, db) { books }
    }

    @After
    fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    private fun pdf(name: String, info: String = "<< /Title (Field Notes) /Author (Ann Lee) >>") =
        File(incoming, name).apply {
            writeBytes(
                ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n5 0 obj\n$info\nendobj\n" +
                        "trailer\n<< /Root 1 0 R /Info 5 0 R >>\n%%EOF\n")
                    .toByteArray(Charsets.ISO_8859_1)
            )
        }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>) =
        File(incoming, name).apply {
            ZipOutputStream(outputStream()).use { z ->
                entries.forEach { (entry, bytes) ->
                    z.putNextEntry(ZipEntry(entry))
                    z.write(bytes)
                    z.closeEntry()
                }
            }
        }

    private suspend fun import(file: File) = repo.import(Uri.fromFile(file))

    @Test
    fun aPdfKeepsItsFormatAndInfoTitle() = runBlocking {
        val (book, added) = import(pdf("scan_0001.pdf"))
        assertTrue(added)
        assertEquals("pdf", book.format)
        assertEquals("Field Notes", book.title)
        assertEquals(listOf("Ann Lee"), decode(book.authors))
        assertTrue(book.currentPath.endsWith("/original.pdf"))
        assertEquals(Epub.sha256(File(book.currentPath)), book.currentHash)
        assertEquals("pdf", repo.dao.book(book.id)!!.format)
    }

    @Test
    fun theSamePdfTwiceIsOneBook() = runBlocking {
        val first = import(pdf("a.pdf")).first
        val (again, added) = import(pdf("copy.pdf"))
        assertFalse(added)
        assertEquals(first.id, again.id)
    }

    @Test
    fun aCbzGetsComicInfoAndItsFirstPageAsCover() = runBlocking {
        val (book, _) =
            import(
                zip(
                    "Saga 03.cbz",
                    "p10.jpg" to byteArrayOf(10),
                    "p2.jpg" to byteArrayOf(2),
                    "ComicInfo.xml" to
                        "<ComicInfo><Series>Saga</Series><Number>3</Number><Writer>Brian K. Vaughan</Writer></ComicInfo>"
                            .toByteArray(),
                )
            )
        assertEquals("cbz", book.format)
        assertEquals("Saga #3", book.title)
        assertEquals("Saga", book.series)
        assertEquals("3", book.seriesIndex)
        assertArrayEquals(byteArrayOf(2), File(book.coverPath!!).readBytes())
        assertTrue(book.currentPath.endsWith("/original.cbz"))
    }

    @Test
    fun aCbrIsKeptWithItsFileNameAsTitle() = runBlocking {
        val file =
            File(incoming, "Saga_01.cbr").apply {
                writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00) + ByteArray(64))
            }
        val (book, _) = import(file)
        assertEquals("cbr", book.format)
        assertEquals("Saga 01", book.title)
        assertNull(book.coverPath)
        assertTrue(book.currentPath.endsWith("/original.cbr"))
    }

    @Test
    fun anEpubIsStillAnEpub() = runBlocking {
        val (book, _) =
            import(
                zip(
                    "book.zip",
                    "mimetype" to "application/epub+zip".toByteArray(),
                    "META-INF/container.xml" to
                        """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>"""
                            .toByteArray(),
                    "book.opf" to
                        """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Plain</dc:title><dc:identifier id="uid">x</dc:identifier></metadata><manifest/><spine/></package>"""
                            .toByteArray(),
                )
            )
        assertEquals("epub", book.format)
        assertEquals("Plain", book.title)
        assertTrue(book.currentPath.endsWith("/original.epub"))
    }

    @Test
    fun otherFilesAreRefusedAndLeaveNothingBehind() = runBlocking {
        val text = File(incoming, "notes.pdf").apply { writeText("not a book") }
        val result = runCatching { import(text) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(repo.dao.allBooks().isEmpty())
        assertTrue(repo.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun editingAPdfChangesTheRecordNotTheFile() = runBlocking {
        val (book, _) = import(pdf("a.pdf"))
        val bytes = File(book.currentPath).readBytes()
        val edited = repo.edit(book.id, Metadata("Better Title", listOf("Bo Lind")), null)
        assertEquals("Better Title", edited.title)
        assertEquals(book.currentPath, edited.currentPath)
        assertEquals(book.currentHash, edited.currentHash)
        assertArrayEquals(bytes, File(edited.currentPath).readBytes())
        assertEquals("Better Title", repo.dao.book(book.id)!!.title)
    }

    @Test
    fun aPdfMovesAsAPdfAndAnEditRenamesIt() = runBlocking {
        val (book, _) = import(pdf("a.pdf"))
        BooksFolderMove(repo).run()
        val moved = repo.dao.book(book.id)!!
        assertEquals(File(books, "Ann Lee/Field Notes.pdf").absolutePath, moved.currentPath)
        assertEquals(moved.currentPath, moved.originalPath)
        val edited = repo.edit(book.id, Metadata("Field Notes", listOf("Bo Lind")), null)
        assertEquals(File(books, "Bo Lind/Field Notes.pdf").absolutePath, edited.currentPath)
        assertEquals(edited.currentPath, edited.originalPath)
        assertEquals(book.currentHash, Epub.sha256(File(edited.currentPath)))
        assertFalse(File(books, "Ann Lee").exists())
        // Nothing is kept aside: the file itself never changed.
        assertFalse(File(books, ".librillo").exists())
    }

    @Test
    fun aPdfAndAnEpubWithTheSameTitleSitSideBySide() = runBlocking {
        File(books, "Ann Lee").mkdirs()
        File(books, "Ann Lee/Field Notes.epub").writeText("another book")
        val (book, _) = import(pdf("a.pdf"))
        BooksFolderMove(repo).run()
        assertEquals(
            File(books, "Ann Lee/Field Notes.pdf").absolutePath,
            repo.dao.book(book.id)!!.currentPath,
        )
    }

    @Test
    fun anEmptyTitleIsRefusedForAPdf() = runBlocking {
        val (book, _) = import(pdf("a.pdf"))
        assertTrue(runCatching { repo.edit(book.id, Metadata(" "), null) }.isFailure)
        assertTrue(
            runCatching { repo.edit(book.id, Metadata("T", seriesIndex = "two"), null) }.isFailure
        )
        assertEquals("Field Notes", repo.dao.book(book.id)!!.title)
    }

    @Test
    fun aBackupKeepsEachBooksFormat() = runBlocking {
        val pdf = import(pdf("a.pdf")).first
        val comic =
            import(zip("Saga 03.cbz", "p1.jpg" to byteArrayOf(1), "p2.jpg" to byteArrayOf(2))).first
        BooksFolderMove(repo).run()
        val archive = File(base, "backup.zip")
        LibraryBackup(repo).export(archive)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val other = File(base, "other").apply { mkdirs() }
        val otherDb =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        try {
            val fresh =
                LibraryRepository(
                    object : ContextWrapper(context) {
                        override fun getFilesDir() = other
                    },
                    otherDb,
                ) {
                    File(other, "Books")
                }
            LibraryBackup(fresh).restore(archive)
            val restored = fresh.dao.allBooks().associateBy { it.id }
            for (book in listOf(pdf, comic)) {
                val copy = restored.getValue(book.id)
                assertEquals(book.format, copy.format)
                assertEquals(book.currentHash, Epub.sha256(File(copy.currentPath)))
            }
        } finally {
            otherDb.close()
        }
    }
}
