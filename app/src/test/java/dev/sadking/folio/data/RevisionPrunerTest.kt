package dev.sadking.folio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.core.Epub
import dev.sadking.folio.core.Metadata
import java.io.File
import java.nio.file.Files
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

/** Revision pruning (M6): old revisions go, a few stay, and nothing else is ever touched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RevisionPrunerTest {
    private lateinit var context: Context
    private lateinit var base: File
    private lateinit var books: File
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        base = File(context.cacheDir, "prune-${UUID.randomUUID()}").apply { mkdirs() }
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

    private fun epub(file: File, title: String, author: String = "Ann Lee"): File {
        file.parentFile!!.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            mapOf(
                    "mimetype" to "application/epub+zip",
                    "META-INF/container.xml" to
                        """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                    "book.opf" to
                        """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title><dc:creator>$author</dc:creator><dc:identifier id="uid">urn:test:$title</dc:identifier></metadata><manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""",
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

    private fun file(path: File, text: String = path.name, modified: Long? = null): File =
        path.apply {
            parentFile!!.mkdirs()
            writeText(text)
            if (modified != null) setLastModified(modified)
        }

    private fun previous(id: String, millis: Long) =
        file(File(books, ".librillo/previous/$id-$millis.epub"))

    private fun privateFile(id: String, name: String, modified: Long) =
        file(File(repo.root, "$id/$name"), modified = modified)

    private fun uuid() = UUID.randomUUID().toString()

    /**
     * A book in the Books folder whose original was kept in `.librillo/originals` when it was first
     * edited, as editing leaves it.
     */
    private suspend fun shelved(title: String, id: String = uuid(), cover: String? = null): Book {
        val current = epub(File(books, "Ann Lee/$title.epub"), title)
        val original = epub(File(books, ".librillo/originals/$id.epub"), "$title (first)")
        return Book(
                id,
                title,
                encode(listOf("Ann Lee")),
                originalPath = original.absolutePath,
                currentPath = current.absolutePath,
                originalHash = Epub.sha256(original),
                currentHash = Epub.sha256(current),
                coverPath = cover,
            )
            .also { repo.dao.insert(it) }
    }

    private suspend fun prune(id: String, keep: Int = 3) =
        repo.lock.withLock { RevisionPruner(repo, keep).pruneLocked(id) }

    private fun names(dir: File) = dir.list().orEmpty().sorted()

    @Test
    fun keepsTheOriginalTheCurrentFileAndTheThreeNewestPreviousRevisions() = runBlocking {
        val id = uuid()
        val folder = File(repo.root, id)
        val heldCover = privateFile(id, "cover-${uuid()}", modified = 1_000)
        val book = shelved("Home", id, cover = heldCover.absolutePath)
        (1..5).forEach { previous(id, it * 1_000L) }
        val coverOriginal = privateFile(id, "cover-original", modified = 1_000)
        val privateOriginal = privateFile(id, "original.epub", modified = 1_000)
        // Edits made in private storage before the book moved to the Books folder: older than the
        // folder's, so the three newest kept are the folder's.
        val revisions = (1..2).map { privateFile(id, "${uuid()}.epub", modified = it * 100L) }
        val covers = (1..5).map { privateFile(id, "cover-${uuid()}", modified = it * 10_000L) }
        val doomed =
            listOf(1_000L, 2_000L).map { File(books, ".librillo/previous/$id-$it.epub") } +
                revisions +
                covers.take(2)
        val bytes = doomed.sumOf { it.length() }

        val result = prune(id)

        assertEquals(
            listOf("$id-3000.epub", "$id-4000.epub", "$id-5000.epub"),
            names(File(books, ".librillo/previous")),
        )
        assertTrue(File(book.currentPath).isFile)
        assertTrue(File(book.originalPath).isFile)
        assertTrue(coverOriginal.isFile)
        assertTrue(privateOriginal.isFile)
        // The cover a row points at stays, however old.
        assertTrue(heldCover.isFile)
        assertEquals(listOf(false, false), revisions.map { it.isFile })
        assertEquals(listOf(false, false, true, true, true), covers.map { it.isFile })
        assertEquals(6, result.removed)
        assertEquals(0, result.failed)
        assertEquals(bytes, result.bytes)
        assertEquals(0, result.skipped)
        assertEquals(book, repo.dao.book(id))
        assertTrue(folder.isDirectory)
    }

    @Test
    fun anotherBookWhoseIdStartsTheSameIsNeverTouched() = runBlocking {
        val id = uuid()
        val other = "$id-1"
        shelved("Home", id)
        shelved("Away", other)
        (1..4).forEach { previous(id, it * 1_000L) }
        val others = (1..4).map { previous(other, it * 1_000L) }

        assertEquals(1, prune(id).removed)

        assertFalse(File(books, ".librillo/previous/$id-1000.epub").exists())
        assertTrue(others.all { it.isFile })
    }

    @Test
    fun aBookInRecentlyRemovedIsSkipped() = runBlocking {
        val book = shelved("Home")
        val old = (1..5).map { previous(book.id, it * 1_000L) }
        repo.dao.markRemoved(RemovedEntry(book.id, book.id, book.title, "EPUB", 1L))

        assertEquals(PruneResult(skipped = 1), prune(book.id))
        assertTrue(old.all { it.isFile })
    }

    @Test
    fun aBookWhoseCurrentFileIsMissingIsSkipped() = runBlocking {
        val book = shelved("Home")
        val old = (1..5).map { previous(book.id, it * 1_000L) }
        File(book.currentPath).delete()

        assertEquals(PruneResult(skipped = 1), prune(book.id))
        assertTrue(old.all { it.isFile })
    }

    @Test
    fun aBookWhoseCurrentFileChangedIsSkipped() = runBlocking {
        val book = shelved("Home")
        val old = (1..5).map { previous(book.id, it * 1_000L) }
        File(book.currentPath).appendText("changed outside Librillo")

        assertEquals(PruneResult(skipped = 1), prune(book.id))
        assertTrue(old.all { it.isFile })
    }

    @Test
    fun aBookWhoseOriginalIsMissingIsSkipped() = runBlocking {
        val book = shelved("Home")
        val old = (1..5).map { previous(book.id, it * 1_000L) }
        File(book.originalPath).delete()

        assertEquals(PruneResult(skipped = 1), prune(book.id))
        assertTrue(old.all { it.isFile })
    }

    @Test
    fun aRevisionAnyRowPointsAtIsKept() = runBlocking {
        val book = shelved("Home")
        val oldest = previous(book.id, 1_000L)
        (2..5).forEach { previous(book.id, it * 1_000L) }
        // Another record (as a restore could leave it) still uses the oldest file.
        repo.dao.insert(
            book.copy(
                id = uuid(),
                currentPath = oldest.absolutePath,
                originalPath = oldest.absolutePath,
                originalHash = "0".repeat(64),
                currentHash = "0".repeat(64),
            )
        )

        assertEquals(1, prune(book.id).removed)

        assertTrue(oldest.isFile)
        assertFalse(File(books, ".librillo/previous/${book.id}-2000.epub").exists())
    }

    @Test
    fun filesOutsideTheTwoFoldersAreNeverDeletedEvenWhenNamedLikeRevisions() = runBlocking {
        val book = shelved("Home")
        (1..5).forEach { previous(book.id, it * 1_000L) }
        val lookalikes =
            listOf(
                file(File(books, "Ann Lee/${book.id}-1.epub")),
                file(File(books, ".librillo/originals/${book.id}-1.epub")),
                file(File(books, ".librillo/previous/nested/${book.id}-1.epub")),
                file(File(books, "${book.id}-1.epub")),
                file(File(repo.root, "${uuid()}/${uuid()}.epub"), modified = 1L),
                file(File(repo.root, "${uuid()}.epub"), modified = 1L),
            )
        // A link named like a revision: the link may go, never what it points at. Here it stays.
        val target = file(File(base, "elsewhere/keep.epub"))
        val link = File(books, ".librillo/previous/${book.id}-500.epub")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        assertEquals(2, prune(book.id).removed)

        assertTrue(lookalikes.all { it.isFile })
        assertTrue(target.isFile)
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun readingDataAnnotationsAndBookmarksAreUnchanged() = runBlocking {
        val book = shelved("Home")
        (1..6).forEach { previous(book.id, it * 1_000L) }
        // Positions and bookmarks made on the old revisions stay: they are reading data.
        listOf("old-hash-1", "old-hash-2", book.currentHash).forEachIndexed { i, hash ->
            repo.dao.savePhoneReading(
                PhoneReading(book.id, hash, "{}", "", 0.1 * (i + 1), i.toLong())
            )
            repo.dao.addPhoneBookmark(PhoneBookmark("b$i", book.id, hash, "{}", "Mark $i", 1L))
        }
        repo.dao.annotation(
            BookAnnotation(
                "n1",
                book.id,
                "phone",
                "a passage",
                "a note",
                "",
                "2026-10-17",
                "",
                "",
                "",
            )
        )
        val reading = repo.dao.allPhoneReading()
        val bookmarks = repo.dao.allPhoneBookmarks()
        val notes = repo.dao.everyAnnotation()

        assertEquals(3, prune(book.id).removed)

        assertEquals(reading, repo.dao.allPhoneReading())
        assertEquals(bookmarks, repo.dao.allPhoneBookmarks())
        assertEquals(notes, repo.dao.everyAnnotation())
        assertEquals(book, repo.dao.book(book.id))
    }

    @Test
    fun editingABookInTheBooksFolderKeepsAtMostThreePreviousVersions() = runBlocking {
        val book = shelved("Home")
        (1..3).forEach { previous(book.id, it * 1_000L) }

        repo.edit(book.id, Metadata("Home Again", listOf("Ann Lee")), null)

        // The edit set aside today's file; with it there would be four, so the oldest went.
        val kept = names(File(books, ".librillo/previous"))
        assertEquals(3, kept.size)
        assertFalse("${book.id}-1000.epub" in kept)
        assertTrue("${book.id}-2000.epub" in kept)
        val edited = repo.dao.book(book.id)!!
        assertEquals(Epub.sha256(File(edited.currentPath)), edited.currentHash)
        assertTrue(File(edited.originalPath).isFile)
    }

    @Test
    fun editingABookInPrivateStorageKeepsTheOriginalAndAtMostThreeEarlierEdits() = runBlocking {
        repo.folderAccess = { false }
        val source = epub(File(base, "Download/home.epub"), "Home")
        val (book, _) = repo.import(android.net.Uri.fromFile(source))
        repo.dao.annotation(
            BookAnnotation("n1", book.id, "phone", "a passage", "", "", "2026-10-17", "", "", "")
        )
        repeat(6) { repo.edit(book.id, Metadata("Home $it", listOf("Ann Lee")), null) }

        val edited = repo.dao.book(book.id)!!
        val folder = File(repo.root, book.id)
        val epubs = names(folder).filter { it.endsWith(".epub") }
        // original.epub, the current file and three earlier edits.
        assertEquals(5, epubs.size)
        assertTrue("original.epub" in epubs)
        assertTrue(File(edited.currentPath).isFile)
        assertEquals("Home 5", edited.title)
        assertEquals(1, repo.dao.everyAnnotation().size)
    }

    @Test
    fun pruneAllGoesThroughEveryBookAndCountsWhatItSkipped() = runBlocking {
        val first = shelved("Home")
        val second = shelved("Away")
        val removed = shelved("Gone")
        listOf(first, second, removed).forEach { b ->
            (1..4).forEach { previous(b.id, it * 1_000L) }
        }
        repo.dao.markRemoved(RemovedEntry(removed.id, removed.id, removed.title, "EPUB", 1L))

        val result = RevisionPruner(repo).pruneAll()

        assertEquals(2, result.removed)
        assertEquals(1, result.skipped)
        assertTrue(File(books, ".librillo/previous/${removed.id}-1000.epub").isFile)
    }

    @Test
    fun theDailyPassRunsOnceADay() {
        val day = RevisionPruner.DAY_MS
        val start = 10 * day
        assertTrue(RevisionPruner.dailyPassDue(context, start))
        assertFalse(RevisionPruner.dailyPassDue(context, start + day - 1))
        assertTrue(RevisionPruner.dailyPassDue(context, start + day))
        // A clock that went back does not hold the pass off.
        assertTrue(RevisionPruner.dailyPassDue(context, start))
    }
}
