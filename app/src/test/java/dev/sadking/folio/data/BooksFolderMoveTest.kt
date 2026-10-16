package dev.sadking.folio.data

import android.content.Context
import android.content.ContextWrapper
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BooksFolderMoveTest {
    private lateinit var base: File
    private lateinit var books: File
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = File(context.cacheDir, "move-${UUID.randomUUID()}").apply { mkdirs() }
        books = File(base, "Books")
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

    /** A book as import leaves it: one private original.epub, current and original the same. */
    private suspend fun imported(title: String, author: String, uid: String = title): Book {
        val id = UUID.randomUUID().toString()
        val file = File(repo.root, "$id/original.epub").also { epub(it, title, author, uid) }
        val hash = Epub.sha256(file)
        return Book(
                id,
                title,
                encode(listOf(author)),
                originalPath = file.absolutePath,
                currentPath = file.absolutePath,
                originalHash = hash,
                currentHash = hash,
            )
            .also { repo.dao.insert(it) }
    }

    @Test
    fun aBookMovesToAuthorTitleAndItsPrivateCopyGoes() = runBlocking {
        val book = imported("The Dispossessed", "Ursula K. Le Guin")
        val move = BooksFolderMove(repo)
        assertEquals(1, move.pending())
        assertEquals(MoveResult(1, emptyList()), move.run())
        val moved = repo.dao.book(book.id)!!
        val file = File(books, "Ursula K. Le Guin/The Dispossessed.epub")
        assertEquals(file.absolutePath, moved.currentPath)
        assertEquals(moved.currentPath, moved.originalPath)
        assertEquals(book.currentHash, Epub.sha256(file))
        assertFalse(File(book.currentPath).exists())
        assertEquals(0, move.pending())
        // No temporary files are left behind.
        assertTrue(books.walk().none { it.name.endsWith(".part") })
    }

    @Test
    fun anEditedBookKeepsItsOriginalInLibrillosFolder() = runBlocking {
        val book = imported("Original Title", "Ann Lee")
        val edited = repo.edit(book.id, Metadata("New Title", listOf("Ann Lee")), null)
        assertTrue(repo.isPrivate(File(edited.currentPath)))
        BooksFolderMove(repo).run()
        val moved = repo.dao.book(book.id)!!
        assertEquals(File(books, "Ann Lee/New Title.epub").absolutePath, moved.currentPath)
        assertEquals(
            File(books, ".librillo/originals/${book.id}.epub").absolutePath,
            moved.originalPath,
        )
        assertEquals(book.originalHash, Epub.sha256(File(moved.originalPath)))
        assertEquals(edited.currentHash, Epub.sha256(File(moved.currentPath)))
    }

    @Test
    fun aChangedFileStaysWhereItIsAndTheRestMove() = runBlocking {
        val broken = imported("Broken", "Ann Lee")
        File(broken.currentPath).appendText("changed")
        val fine = imported("Fine", "Ann Lee")
        val result = BooksFolderMove(repo).run()
        assertEquals(MoveResult(1, listOf("Broken")), result)
        assertEquals(broken.currentPath, repo.dao.book(broken.id)!!.currentPath)
        assertTrue(File(broken.currentPath).isFile)
        assertTrue(repo.inBooksFolder(File(repo.dao.book(fine.id)!!.currentPath)))
    }

    @Test
    fun twoBooksWithTheSameNameGetNumbersAndAnInterruptedCopyIsReused() = runBlocking {
        val first = imported("Home", "Ann Lee")
        // An earlier run placed this book's file and stopped before saving its new path.
        File(books, "Ann Lee/Home.epub")
            .also { it.parentFile!!.mkdirs() }
            .writeBytes(File(first.currentPath).readBytes())
        val second = imported("Home", "Ann Lee", uid = "another edition")
        BooksFolderMove(repo).run()
        assertEquals(
            File(books, "Ann Lee/Home.epub").absolutePath,
            repo.dao.book(first.id)!!.currentPath,
        )
        assertEquals(
            File(books, "Ann Lee/Home (2).epub").absolutePath,
            repo.dao.book(second.id)!!.currentPath,
        )
    }

    @Test
    fun recentlyRemovedBooksStayPrivate() = runBlocking {
        val book = imported("Gone", "Ann Lee")
        repo.removeLocal(setOf(book.id))
        assertEquals(0, BooksFolderMove(repo).pending())
        BooksFolderMove(repo).run()
        assertTrue(repo.isPrivate(File(repo.dao.book(book.id)!!.currentPath)))
    }

    @Test
    fun editingAMovedBookRenamesItAndKeepsTheOldFile() = runBlocking {
        val book = imported("Draft", "Ann Lee")
        BooksFolderMove(repo).run()
        val edited = repo.edit(book.id, Metadata("Final", listOf("Bo Lind")), null)
        assertEquals(File(books, "Bo Lind/Final.epub").absolutePath, edited.currentPath)
        // The unedited file was also the original, so it is kept as the original.
        assertEquals(
            File(books, ".librillo/originals/${book.id}.epub").absolutePath,
            edited.originalPath,
        )
        assertEquals(book.originalHash, Epub.sha256(File(edited.originalPath)))
        assertFalse(File(books, "Ann Lee").exists())
        // A second edit keeps the replaced file as a previous version.
        val again =
            repo.edit(book.id, Metadata("Final", listOf("Bo Lind"), description = "x"), null)
        assertEquals(edited.currentPath, again.currentPath)
        assertEquals(1, File(books, ".librillo/previous").listFiles()!!.size)
    }

    @Test
    fun anEditIsRefusedWhenTheFileChangedOutsideLibrillo() = runBlocking {
        val book = imported("Draft", "Ann Lee")
        BooksFolderMove(repo).run()
        val path = repo.dao.book(book.id)!!.currentPath
        File(path).appendText("changed")
        assertTrue(
            runCatching { repo.edit(book.id, Metadata("Other", listOf("Ann Lee")), null) }.isFailure
        )
        assertTrue(File(path).isFile)
        assertEquals(path, repo.dao.book(book.id)!!.currentPath)
    }

    @Test
    fun purgingDeletesTheFolderFileAndItsEmptyAuthorFolder() = runBlocking {
        val book = imported("Gone", "Ann Lee")
        BooksFolderMove(repo).run()
        val path = repo.dao.book(book.id)!!.currentPath
        val groups = repo.removeLocal(setOf(book.id)).third
        // Removing alone keeps the file, so Undo works.
        assertTrue(File(path).isFile)
        assertTrue(repo.purgeRemoved(groups.single()))
        assertFalse(File(path).exists())
        assertFalse(File(books, "Ann Lee").exists())
    }

    @Test
    fun onlyLibraryFilesAreAccepted() {
        val id = UUID.randomUUID().toString()
        assertTrue(repo.holds(id, File(repo.root, "$id/original.epub")))
        assertTrue(repo.holds(id, File(books, "Ann Lee/Home.epub")))
        assertFalse(repo.holds(id, File(repo.root, "other/original.epub")))
        assertFalse(repo.holds(id, File(base, "elsewhere.epub")))
        assertFalse(repo.holds(id, File(books, "../escape.epub")))
    }

    @Test
    fun aBackupOfMovedBooksRestoresIntoAnEmptyLibrary() = runBlocking {
        val plain = imported("Plain", "Ann Lee")
        val edited = imported("Before", "Bo Lind")
        repo.edit(edited.id, Metadata("After", listOf("Bo Lind")), null)
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
            for (book in listOf(repo.dao.book(plain.id)!!, repo.dao.book(edited.id)!!)) {
                val copy = restored.getValue(book.id)
                assertEquals(book.currentHash, Epub.sha256(File(copy.currentPath)))
                assertEquals(book.originalHash, Epub.sha256(File(copy.originalPath)))
            }
        } finally {
            otherDb.close()
        }
    }
}
