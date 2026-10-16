package dev.sadking.folio.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Which kind of book a file is: "epub", "pdf", "cbz" or "cbr", or null for anything Librillo
 * doesn't keep (MOBI, AZW/KFX, FB2, TXT). The bytes decide. The file name's extension only breaks a
 * tie, when a ZIP's entries show neither an EPUB nor images. The format is also the file's
 * extension in the Books folder (see [BookPaths.path]).
 */
object BookFormats {
    const val EPUB = "epub"
    const val PDF = "pdf"
    const val CBZ = "cbz"
    const val CBR = "cbr"
    val ALL = listOf(EPUB, PDF, CBZ, CBR)

    /** How many leading bytes to pass to [detect]: enough for a ZIP's first entries. */
    const val HEADER_BYTES = 64 * 1024

    /** PDF readers accept the header anywhere in the first kilobyte. */
    private const val PDF_WINDOW = 1024
    private const val MAX_ENTRIES = 10_000
    private const val MIMETYPE_BYTES = 100
    private const val EPUB_MIMETYPE = "application/epub+zip"
    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val RAR = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)
    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "jxl")

    private class Entry(val name: String, val content: ByteArray?)

    /** [header] is the file's first bytes, up to [HEADER_BYTES]. [fileName] may be null. */
    fun detect(header: ByteArray, fileName: String?): String? =
        when {
            header.startsWith(ZIP) -> zip(entries(header), extension(fileName))
            header.startsWith(RAR) -> CBR
            header.startsWithin(PDF_MAGIC, PDF_WINDOW) -> PDF
            else -> null
        }

    /** Like [detect], but a ZIP is judged by its whole directory, not only its first entries. */
    fun detect(file: File): String? {
        val header = file.inputStream().use { it.readNBytes(HEADER_BYTES) }
        if (!header.startsWith(ZIP)) return detect(header, file.name)
        val listed =
            try {
                ZipFile(file).use { z ->
                    val all = z.entries()
                    val found = mutableListOf<Entry>()
                    while (all.hasMoreElements() && found.size < MAX_ENTRIES) {
                        val e = all.nextElement()
                        val content =
                            if (e.name == "mimetype") {
                                z.getInputStream(e).use { it.readNBytes(MIMETYPE_BYTES) }
                            } else {
                                null
                            }
                        found += Entry(e.name, content)
                    }
                    found
                }
            } catch (e: IOException) {
                // A damaged ZIP: judge by what the header shows.
                entries(header)
            } catch (e: IllegalArgumentException) {
                entries(header)
            }
        return zip(listed, extension(file.name))
    }

    private fun zip(entries: List<Entry>, extension: String?): String? {
        val epub = entries.any { e ->
            e.name == "META-INF/container.xml" ||
                (e.name == "mimetype" &&
                    e.content?.toString(Charsets.US_ASCII)?.trim() == EPUB_MIMETYPE)
        }
        val comic = entries.any { e ->
            val leaf = e.name.substringAfterLast('/')
            leaf.equals("ComicInfo.xml", ignoreCase = true) ||
                leaf.substringAfterLast('.', "").lowercase() in IMAGES
        }
        return when {
            epub -> EPUB
            comic -> CBZ
            extension == EPUB || extension == CBZ -> extension
            else -> null
        }
    }

    /** The entries readable from a ZIP's first bytes. A header cut mid-entry ends the list. */
    private fun entries(header: ByteArray): List<Entry> {
        val found = mutableListOf<Entry>()
        try {
            ZipInputStream(ByteArrayInputStream(header)).use { z ->
                while (found.size < MAX_ENTRIES) {
                    val e = z.nextEntry ?: break
                    val content = if (e.name == "mimetype") z.readNBytes(MIMETYPE_BYTES) else null
                    found += Entry(e.name, content)
                }
            }
        } catch (e: IOException) {
            // The header was cut inside an entry; keep what was read.
        } catch (e: IllegalArgumentException) {
            // A name that isn't valid UTF-8; keep what was read.
        }
        return found
    }

    private fun extension(fileName: String?): String? =
        fileName?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase()?.takeIf {
            it.isNotEmpty()
        }

    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /** Whether [needle] starts somewhere in the first [window] bytes. */
    private fun ByteArray.startsWithin(needle: ByteArray, window: Int): Boolean =
        (0..minOf(size - needle.size, window - 1)).any { i ->
            needle.indices.all { this[i + it] == needle[it] }
        }
}
