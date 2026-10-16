package dev.sadking.folio.core

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class BookFormatsTest {
    private val epubMime = "application/epub+zip".toByteArray()
    private val container = "<container/>".toByteArray()

    private fun zip(
        vararg entries: Pair<String, ByteArray>,
        storeMimetype: Boolean = true,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                val e = ZipEntry(name)
                if (name == "mimetype" && storeMimetype) {
                    e.method = ZipEntry.STORED
                    e.size = bytes.size.toLong()
                    e.compressedSize = bytes.size.toLong()
                    e.crc = CRC32().apply { update(bytes) }.value
                }
                z.putNextEntry(e)
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun file(bytes: ByteArray, name: String) =
        Files.createTempDirectory("formats").resolve(name).toFile().apply {
            writeBytes(bytes)
            deleteOnExit()
        }

    @Test
    fun pdfByItsHeader() {
        val pdf = "%PDF-1.7\n%âã\n1 0 obj".toByteArray(Charsets.ISO_8859_1)
        assertEquals("pdf", BookFormats.detect(pdf, "book.pdf"))
        assertEquals("pdf", BookFormats.detect(pdf, null))
        assertEquals("pdf", BookFormats.detect(pdf, "misnamed.epub"))
    }

    @Test
    fun pdfHeaderMustStartInTheFirstKilobyte() {
        val magic = "%PDF-1.4".toByteArray()
        assertEquals("pdf", BookFormats.detect(ByteArray(500) { ' '.code.toByte() } + magic, null))
        assertNull(BookFormats.detect(ByteArray(2000) + magic, "a.pdf"))
    }

    @Test
    fun epubByItsMimetypeWhateverItsName() {
        val epub =
            zip(
                "mimetype" to epubMime,
                "META-INF/container.xml" to container,
                "OPS/cover.jpg" to byteArrayOf(1, 2),
            )
        for (name in listOf(null, "a.epub", "a.cbz", "a.zip")) {
            assertEquals("epub", BookFormats.detect(epub, name))
        }
        val deflated =
            zip("mimetype" to epubMime, "OPS/a.xhtml" to byteArrayOf(1), storeMimetype = false)
        assertEquals("epub", BookFormats.detect(deflated, null))
    }

    @Test
    fun epubWithMimetypeOutOfPlaceIsKnownByItsContainer() {
        val epub = zip("OPS/a.xhtml" to byteArrayOf(1), "META-INF/container.xml" to container)
        assertEquals("epub", BookFormats.detect(epub, "a.cbz"))
    }

    @Test
    fun cbzByItsImages() {
        val comic = zip("001.jpg" to byteArrayOf(1), "002.PNG" to byteArrayOf(2))
        for (name in listOf(null, "a.zip", "a.cbz", "a.epub")) {
            assertEquals("cbz", BookFormats.detect(comic, name))
        }
        val withFolder =
            zip("Issue 1/" to ByteArray(0), "Issue 1/ComicInfo.xml" to "<ComicInfo/>".toByteArray())
        assertEquals("cbz", BookFormats.detect(withFolder, null))
    }

    @Test
    fun aZipWithNeitherFallsBackToItsName() {
        val plain = zip("readme.txt" to "hi".toByteArray())
        assertEquals("cbz", BookFormats.detect(plain, "a.cbz"))
        assertEquals("epub", BookFormats.detect(plain, "dir.d/A.EPUB"))
        assertNull(BookFormats.detect(plain, "a.zip"))
        assertNull(BookFormats.detect(plain, null))
    }

    @Test
    fun rarIsCbr() {
        val rar4 = "Rar!\u001A\u0007\u0000rest".toByteArray(Charsets.ISO_8859_1)
        val rar5 = "Rar!\u001A\u0007\u0001\u0000rest".toByteArray(Charsets.ISO_8859_1)
        assertEquals("cbr", BookFormats.detect(rar4, "a.cbr"))
        assertEquals("cbr", BookFormats.detect(rar5, "a.cbz"))
        assertEquals("cbr", BookFormats.detect(rar5, null))
    }

    @Test
    fun unsupportedFilesAreNullWhateverTheirName() {
        val mobi = ByteArray(80).also { "BOOKMOBI".toByteArray().copyInto(it, 60) }
        assertNull(BookFormats.detect(mobi, "a.mobi"))
        assertNull(BookFormats.detect("Once upon a time".toByteArray(), "a.pdf"))
        val fb2 = "<?xml version=\"1.0\"?><FictionBook>".toByteArray()
        assertNull(BookFormats.detect(fb2, "a.fb2"))
        assertNull(BookFormats.detect(ByteArray(0), "a.epub"))
        assertNull(BookFormats.detect(byteArrayOf(0x50, 0x4B, 0x05, 0x06) + ByteArray(18), "a.cbz"))
    }

    @Test
    fun aHeaderCutInsideABigFirstEntryStillDecides() {
        val image = Random(1).nextBytes(200_000)
        val misordered = zip("OPS/cover.jpg" to image, "META-INF/container.xml" to container)
        val header = misordered.copyOf(BookFormats.HEADER_BYTES)
        // Only the image is visible in the first 64 KiB.
        assertEquals("cbz", BookFormats.detect(header, null))
        // The whole file's directory shows it is an EPUB.
        assertEquals("epub", BookFormats.detect(file(misordered, "book.zip")))
    }

    @Test
    fun fileOverloadMatchesHeaderRules() {
        assertEquals("pdf", BookFormats.detect(file("%PDF-1.7\n".toByteArray(), "x.bin")))
        assertEquals("cbz", BookFormats.detect(file(zip("1.webp" to byteArrayOf(1)), "x.zip")))
        // A damaged ZIP: only the name can break the tie.
        val damaged = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(40) { 7 }
        assertEquals("cbz", BookFormats.detect(file(damaged, "a.cbz")))
        assertNull(BookFormats.detect(file(damaged, "a.zip")))
    }
}
