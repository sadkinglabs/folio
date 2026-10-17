package dev.sadking.folio.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.reader.koreaderDigest
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Word counts and KOReader digests for the statistics (a6), over an in-memory Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StatsIndexerTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var db: LibraryDatabase
    private var clock = 1_760_000_000_000L

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun close() {
        db.close()
    }

    private fun indexer() = StatsIndexer(db.library(), db.stats()) { clock }

    private fun epub(name: String, body: String): File {
        val file = folder.newFile(name)
        val entries =
            linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to
                    """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>""",
                "OPS/package.opf" to
                    """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:$name</dc:identifier><dc:title>Words</dc:title></metadata><manifest><item id="c0" href="c0.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c0"/></spine></package>""",
                "OPS/c0.xhtml" to
                    """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter</title></head><body>$body</body></html>""",
            )
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (entry, text) ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun pdf(name: String): File =
        folder.newFile(name).apply { writeBytes(ByteArray(5_000) { (it * 31).toByte() }) }

    private fun book(
        id: String,
        file: File,
        hash: String,
        format: String = "epub",
        originalFile: File = file,
        originalHash: String = hash,
    ) =
        Book(
            id = id,
            title = "Title $id",
            authors = "",
            originalPath = originalFile.path,
            currentPath = file.path,
            originalHash = originalHash,
            currentHash = hash,
            format = format,
        )

    private fun addBook(book: Book) = runBlocking { db.library().insert(book) }

    private fun words(id: String) = runBlocking {
        db.stats().allWords().singleOrNull { it.bookId == id }
    }

    private fun digests(id: String) = runBlocking { db.stats().digestsOf(id) }

    @Test
    fun anEpubGetsItsWordsAndOneDigest() = runBlocking {
        val file = epub("one.epub", "<p>One two three.</p>")
        addBook(book("b1", file, "rev-1"))

        assertEquals(1, indexer().run())

        val row = words("b1")!!
        assertEquals(3L, row.words)
        assertEquals("rev-1", row.revisionHash)
        assertEquals(clock, row.countedAt)
        val digest = digests("b1").single()
        assertEquals(koreaderDigest(file), digest.digest)
        assertEquals("rev-1", digest.revisionHash)
    }

    @Test
    fun aSecondRunDoesNothing() = runBlocking {
        addBook(book("b1", epub("one.epub", "<p>One two.</p>"), "rev-1"))
        indexer().run()

        clock += 60_000
        assertEquals(0, indexer().run())
        assertEquals(clock - 60_000, words("b1")!!.countedAt)
    }

    @Test
    fun aNewCurrentFileIsRecounted() = runBlocking {
        val first = epub("first.epub", "<p>One two.</p>")
        addBook(book("b1", first, "rev-1"))
        indexer().run()

        val second = epub("second.epub", "<p>One two three four.</p>")
        db.library()
            .update(book("b1", second, "rev-2", originalFile = first, originalHash = "rev-1"))

        assertEquals(1, indexer().run())
        val row = words("b1")!!
        assertEquals(4L, row.words)
        assertEquals("rev-2", row.revisionHash)
        // The digest of the new revision is added; the old one stays.
        assertEquals(
            setOf("rev-1", "rev-2"),
            digests("b1").map { it.revisionHash }.toSet(),
        )
    }

    @Test
    fun aPdfGetsADigestButNoWords() = runBlocking {
        val file = pdf("paper.pdf")
        addBook(book("p1", file, "pdf-rev", format = "pdf"))

        assertEquals(1, indexer().run())

        assertNull(words("p1"))
        assertEquals(koreaderDigest(file), digests("p1").single().digest)
    }

    @Test
    fun aMissingFileIsSkippedAndTheRestOfTheRunStillHappens() = runBlocking {
        val gone = File(folder.root, "gone.epub")
        addBook(book("missing", gone, "rev-gone"))
        addBook(book("good", epub("good.epub", "<p>One two.</p>"), "rev-good"))

        assertEquals(1, indexer().run())

        assertNull(words("missing"))
        assertTrue(digests("missing").isEmpty())
        assertEquals(2L, words("good")!!.words)
    }

    @Test
    fun anUnreadableEpubIsCountedOnceAndNotRetried() = runBlocking {
        val file = folder.newFile("broken.epub").apply { writeText("not a zip") }
        addBook(book("b1", file, "rev-1"))

        assertEquals(1, indexer().run())
        assertNull(words("b1")!!.words)

        assertEquals(0, indexer().run())
    }
}
