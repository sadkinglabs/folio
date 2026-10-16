package dev.sadking.folio.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

data class ParsedComic(val metadata: Metadata, val cover: ByteArray?, val coverType: String?)

/**
 * A CBZ comic: a ZIP of page images, with an optional `ComicInfo.xml`. Pages are read in natural
 * order (2 before 10). Nothing in the archive is extracted to disk.
 */
object Comic {
    private const val MAX_ENTRIES = 20000
    private const val MAX_XML = 1L * 1024 * 1024
    private const val MAX_COVER = 10L * 1024 * 1024
    private val imageTypes =
        mapOf(
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "webp" to "image/webp",
            "gif" to "image/gif",
            "avif" to "image/avif",
        )
    /** Cover types the rest of the app already accepts. */
    private val coverTypes = setOf("image/jpeg", "image/png", "image/webp", "image/gif")

    /** True for a ZIP entry that is a page: an image, not hidden, not macOS clutter. */
    fun isPage(name: String): Boolean =
        !name.endsWith('/') &&
            name.split('/').none { it.startsWith('.') || it == "__MACOSX" } &&
            name.substringAfterLast('.', "").lowercase() in imageTypes

    /** The page entries of an archive, in reading order. */
    fun pages(names: List<String>): List<String> = names.filter(::isPage).sortedWith(naturalOrder)

    /**
     * For a ZIP that [BookFormats.detect] couldn't place from its first bytes: "epub", "cbz", or
     * null when it is neither or not a readable ZIP.
     */
    fun zipKind(file: File): String? =
        try {
            ZipFile(file).use { z ->
                val mimetype =
                    z.getEntry("mimetype")?.let { e ->
                        z.getInputStream(e).use { String(it.readNBytes(100), Charsets.US_ASCII) }
                    }
                when {
                    mimetype?.trim() == "application/epub+zip" ||
                        z.getEntry("META-INF/container.xml") != null -> BookFormats.EPUB
                    z.entries().asSequence().take(MAX_ENTRIES).any { isPage(it.name) } ->
                        BookFormats.CBZ
                    else -> null
                }
            }
        } catch (_: IOException) {
            null
        }

    /** Title, writers and series from `ComicInfo.xml` when present, else the file name. */
    fun parse(file: File, fileName: String?): ParsedComic =
        ZipFile(file).use { z ->
            val entries = z.entries().toList()
            require(entries.size <= MAX_ENTRIES) { "This comic has too many files" }
            val pages = pages(entries.filter { !it.isDirectory }.map { it.name })
            require(pages.isNotEmpty()) { "This comic has no pages" }
            val info =
                entries
                    .filter {
                        !it.isDirectory &&
                            it.name.substringAfterLast('/').equals("ComicInfo.xml", true)
                    }
                    .minByOrNull { it.name.count { c -> c == '/' } }
                    ?.let { runCatching { comicInfo(read(z, it, MAX_XML)) }.getOrNull() }
            val fallback = FileTitle.from(fileName)
            val metadata =
                info?.let { it.copy(title = it.title.ifBlank { fallback }) } ?: Metadata(fallback)
            val first = z.getEntry(pages.first())
            val type = imageTypes[first.name.substringAfterLast('.').lowercase()]
            val cover =
                if (type in coverTypes) runCatching { read(z, first, MAX_COVER) }.getOrNull()
                else null
            ParsedComic(metadata, cover, type.takeIf { cover != null })
        }

    /**
     * The fields Librillo keeps from a `ComicInfo.xml`, or null when it can't be read safely. The
     * title is blank when the file has neither Title nor Series.
     */
    fun comicInfo(xml: ByteArray): Metadata? {
        val doc = document(xml) ?: return null
        val root = doc.documentElement ?: return null
        if (!root.tagName.equals("ComicInfo", true)) return null
        fun field(name: String): String {
            val nodes = root.childNodes
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                if (n is org.w3c.dom.Element && n.tagName == name)
                    return n.textContent.replace(Regex("""\s+"""), " ").trim()
            }
            return ""
        }
        fun list(vararg names: String): List<String> =
            names
                .flatMap { field(it).split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        val series = field("Series")
        val number = field("Number")
        val title =
            field("Title").ifBlank {
                if (series.isBlank()) "" else if (number.isBlank()) series else "$series #$number"
            }
        val index = number.takeIf {
            it.toDoubleOrNull()?.let { n -> n >= 0 && n.isFinite() } == true
        }
        return Metadata(
            title = title.take(500),
            authors = list("Writer").take(20).map { it.take(200) },
            description = field("Summary").take(10000),
            publisher = field("Publisher").take(500),
            language = field("LanguageISO").take(20),
            identifiers = listOf(field("GTIN")).filter { it.isNotBlank() },
            series = series.take(500),
            seriesIndex = if (series.isBlank()) "" else index.orEmpty(),
            tags = list("Genre", "Tags").take(50),
        )
    }

    /** Natural order: digit runs compare as numbers, the rest ignoring case. */
    val naturalOrder: Comparator<String> = Comparator { a, b ->
        val x = chunks(a)
        val y = chunks(b)
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c =
                if (p[0] in '0'..'9' && q[0] in '0'..'9') {
                    val pt = p.trimStart('0')
                    val qt = q.trimStart('0')
                    if (pt.length != qt.length) pt.length - qt.length else pt.compareTo(qt)
                } else p.compareTo(q, ignoreCase = true)
            if (c != 0) return@Comparator c
        }
        if (x.size != y.size) x.size - y.size else a.compareTo(b)
    }

    private val chunk = Regex("""[0-9]+|[^0-9]+""")

    private fun chunks(s: String) = chunk.findAll(s).map { it.value }.toList()

    private fun read(z: ZipFile, e: ZipEntry, max: Long): ByteArray =
        ByteArrayOutputStream()
            .also { out -> z.getInputStream(e).use { Epub.copyBounded(it, out, max) } }
            .toByteArray()

    /** Parses XML with no DTDs and no external references, like [Epub]'s package parser. */
    private fun document(bytes: ByteArray): Document? {
        val charset =
            when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    Charsets.UTF_16LE
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }
        val text = String(bytes, charset).removePrefix(Char(0xFEFF).toString())
        if (text.contains("<!DOCTYPE", true) || text.contains("<!ENTITY", true)) return null
        return runCatching {
                val f = DocumentBuilderFactory.newInstance()
                runCatching {
                    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                f.isExpandEntityReferences = false
                val builder = f.newDocumentBuilder()
                builder.setEntityResolver { _, _ ->
                    throw org.xml.sax.SAXException("External XML references are blocked")
                }
                builder.setErrorHandler(null)
                builder.parse(org.xml.sax.InputSource(StringReader(text)))
            }
            .getOrNull()
    }
}
