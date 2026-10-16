package dev.sadking.folio.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.sadking.folio.core.BookFormats
import dev.sadking.folio.core.Comic
import dev.sadking.folio.core.FileTitle
import dev.sadking.folio.core.Metadata
import dev.sadking.folio.core.ParsedComic
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry

/** The MIME type to hand other apps for a book file in [format]. */
fun bookMimeType(format: String): String =
    when (format) {
        BookFormats.PDF -> "application/pdf"
        BookFormats.CBZ -> "application/vnd.comicbook+zip"
        BookFormats.CBR -> "application/vnd.comicbook-rar"
        else -> "application/epub+zip"
    }

/**
 * A PDF's first page as a JPEG cover, drawn by Android's own PDF renderer. Null when the page can't
 * be drawn (a password, a damaged file, or no renderer, as in JVM tests).
 */
internal object PdfCover {
    private const val WIDTH = 600
    private const val MAX_HEIGHT = 1200

    fun render(file: File): ByteArray? =
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (renderer.pageCount == 0) return null
                    renderer.openPage(0).use { page ->
                        if (page.width <= 0 || page.height <= 0) return null
                        val height =
                            (WIDTH.toLong() * page.height / page.width)
                                .toInt()
                                .coerceIn(1, MAX_HEIGHT)
                        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                            ByteArrayOutputStream()
                                .also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                                .toByteArray()
                                .takeIf { it.isNotEmpty() }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        }
}

/**
 * Reads a RAR comic (CBR) with libarchive: ComicInfo.xml for its details and the first page as the
 * cover. Only reads; nothing is written to disk. Null when the archive can't be read, so import
 * keeps the file with its name as the title.
 */
internal object RarComic {
    private const val BLOCK = 64 * 1024
    private const val MAX_ENTRIES = 20000
    private const val MAX_INFO = 1024 * 1024
    private const val MAX_COVER = 10 * 1024 * 1024
    private val coverTypes = setOf("jpg", "jpeg", "png", "webp", "gif")

    fun parse(file: File, fileName: String?): ParsedComic? =
        try {
            val names = mutableListOf<String>()
            var info: ByteArray? = null
            var infoDepth = Int.MAX_VALUE
            entries(file) { name, archive ->
                names += name
                val depth = name.count { it == '/' }
                if (
                    name.substringAfterLast('/').equals("ComicInfo.xml", true) && depth < infoDepth
                ) {
                    readEntry(archive, MAX_INFO)?.let {
                        info = it
                        infoDepth = depth
                    }
                }
                names.size < MAX_ENTRIES
            }
            val first = Comic.pages(names).firstOrNull()
            var cover: ByteArray? = null
            if (first != null && first.substringAfterLast('.').lowercase() in coverTypes)
                entries(file) { name, archive ->
                    if (name == first) cover = readEntry(archive, MAX_COVER)
                    name != first
                }
            val fallback = FileTitle.from(fileName)
            val metadata =
                info
                    ?.let { Comic.comicInfo(it) }
                    ?.let { it.copy(title = it.title.ifBlank { fallback }) } ?: Metadata(fallback)
            val type =
                first?.substringAfterLast('.')?.lowercase()?.let {
                    if (it == "jpg") "image/jpeg" else "image/$it"
                }
            ParsedComic(metadata, cover, type.takeIf { cover != null })
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            // libarchive's native library is missing, as in JVM tests.
            null
        }

    /** Calls [visit] for each readable file in the archive until it returns false. */
    private fun entries(file: File, visit: (name: String, archive: Long) -> Boolean) {
        val archive = Archive.readNew()
        try {
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readOpenFileName(archive, file.path.toByteArray(), BLOCK.toLong())
            while (true) {
                val entry = Archive.readNextHeader(archive)
                if (entry == 0L) break
                if (
                    ArchiveEntry.filetype(entry) != ArchiveEntry.AE_IFREG ||
                        ArchiveEntry.isEncrypted(entry)
                )
                    continue
                val name =
                    (ArchiveEntry.pathnameUtf8(entry)
                            ?: ArchiveEntry.pathname(entry)?.let { String(it) })
                        ?.replace('\\', '/')
                        ?.trimStart('/') ?: continue
                if (!visit(name, archive)) break
            }
        } finally {
            Archive.readFree(archive)
        }
    }

    /** The current entry's bytes, or null when it is bigger than [max]. */
    private fun readEntry(archive: Long, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val bytes = ByteArray(BLOCK)
        while (true) {
            val buffer = ByteBuffer.wrap(bytes)
            Archive.readData(archive, buffer)
            if (buffer.position() == 0) break
            if (out.size() + buffer.position() > max) return null
            out.write(bytes, 0, buffer.position())
        }
        return out.toByteArray()
    }
}
