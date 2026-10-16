package dev.sadking.folio.core

import java.io.File
import java.io.RandomAccessFile

/** The text fields of a PDF's Info dictionary that Librillo uses. Blank when absent. */
data class PdfInfo(val title: String = "", val author: String = "")

/**
 * Title and author from a PDF's Info dictionary, read with a small parser: the trailer names the
 * Info object, and the file is scanned for that object. Encrypted files and files that keep the
 * Info dictionary inside a compressed object stream fall back to the file name. Nothing is
 * rendered.
 */
object Pdf {
    private const val EDGE = 1024 * 1024
    private const val CHUNK = 1024 * 1024
    private const val OVERLAP = 64
    private const val OBJECT_MAX = 64 * 1024
    private val infoRef = Regex("""/Info\s*(\d{1,10})\s+(\d{1,5})\s+R""")
    private val inlineInfo = Regex("""/Info\s*<<""")
    private val encrypt = Regex("""/Encrypt\b""")
    private val junkEndings =
        listOf(".doc", ".docx", ".pdf", ".indd", ".tex", ".dvi", ".rtf", ".odt", ".ps", ".qxd")

    /** Metadata for import: the Info title and authors when usable, else the file name. */
    fun metadata(file: File, fileName: String?): Metadata {
        val info = runCatching { info(file) }.getOrNull()
        val title = info?.title?.takeUnless { junk(it) }?.take(500) ?: FileTitle.from(fileName)
        val authors =
            info
                ?.author
                .orEmpty()
                .split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(20)
                .map { it.take(200) }
        return Metadata(title = title, authors = authors)
    }

    /** The Info dictionary's title and author, or null when they can't be read. */
    fun info(file: File): PdfInfo? =
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val size = minOf(length, EDGE.toLong()).toInt()
            // The trailer is at the end; linearized files also have one near the start.
            for (start in listOf(length - size, 0L).distinct()) {
                val text = latin1(raf, start, size)
                if (encrypt.containsMatchIn(text)) return null
                infoRef.findAll(text).lastOrNull()?.let { m ->
                    val body =
                        objectBody(raf, length, m.groupValues[1].toLong(), m.groupValues[2].toInt())
                            ?: return null
                    return parseInfoDictionary(body)
                }
                inlineInfo.findAll(text).lastOrNull()?.let { m ->
                    return parseInfoDictionary(text.substring(m.range.last - 1))
                }
            }
            null
        }

    private fun junk(title: String): Boolean =
        title.isBlank() ||
            title.startsWith("Microsoft Word - ", ignoreCase = true) ||
            junkEndings.any { title.endsWith(it, ignoreCase = true) }

    /** The text after the last `N G obj` header in the file, up to `endobj`. */
    private fun objectBody(raf: RandomAccessFile, length: Long, number: Long, gen: Int): String? {
        val header = Regex("""(?<![0-9])$number\s+$gen\s+obj(?![A-Za-z])""")
        var found = -1L
        var pos = 0L
        while (pos < length) {
            val size = minOf(CHUNK.toLong(), length - pos).toInt()
            val text = latin1(raf, pos, size)
            // A match at a chunk's first byte may be the end of a longer number; the chunk
            // before saw it whole.
            header
                .findAll(text)
                .lastOrNull { pos == 0L || it.range.first > 0 }
                ?.let { found = pos + it.range.last + 1 }
            if (pos + size >= length) break
            pos += size - OVERLAP
        }
        if (found < 0) return null
        val body = latin1(raf, found, minOf(OBJECT_MAX.toLong(), length - found).toInt())
        val end = body.indexOf("endobj")
        return if (end >= 0) body.substring(0, end) else body
    }

    private fun latin1(raf: RandomAccessFile, start: Long, size: Int): String {
        val bytes = ByteArray(size)
        raf.seek(start)
        raf.readFully(bytes)
        return String(bytes, Charsets.ISO_8859_1)
    }

    /**
     * Reads `/Title` and `/Author` from the first dictionary in [text] (bytes as ISO-8859-1
     * characters). Nested dictionaries and arrays are skipped.
     */
    fun parseInfoDictionary(text: String): PdfInfo {
        var i = text.indexOf("<<")
        if (i < 0) return PdfInfo()
        i += 2
        val values = mutableMapOf<String, String>()
        var depth = 1
        var arrays = 0
        var key: String? = null
        fun value(raw: String) {
            if (depth == 1 && arrays == 0 && key != null) values[key!!] = decodeText(raw)
            if (arrays == 0) key = null
        }
        while (i < text.length && depth > 0) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '%' -> {
                    while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
                }
                c == '(' -> {
                    val (raw, end) = literal(text, i)
                    value(raw)
                    i = end
                }
                c == '<' && next == '<' -> {
                    depth++
                    i += 2
                }
                c == '>' && next == '>' -> {
                    depth--
                    if (depth == 1 && arrays == 0) key = null
                    i += 2
                }
                c == '<' -> {
                    val end = text.indexOf('>', i).let { if (it < 0) text.length else it }
                    value(hex(text.substring(i + 1, end)))
                    i = end + 1
                }
                c == '[' -> {
                    arrays++
                    i++
                }
                c == ']' -> {
                    arrays = maxOf(0, arrays - 1)
                    if (arrays == 0 && depth == 1) key = null
                    i++
                }
                c == '/' -> {
                    var end = i + 1
                    while (end < text.length && !delimiter(text[end])) end++
                    val name = text.substring(i + 1, end)
                    if (depth == 1 && arrays == 0) key = if (key == null) name else null
                    i = end
                }
                delimiter(c) -> i++
                else -> {
                    // A number, a reference's "R", true, false or null: a value that isn't text.
                    while (i < text.length && !delimiter(text[i])) i++
                    if (depth == 1 && arrays == 0) key = null
                }
            }
        }
        return PdfInfo(values["Title"].orEmpty(), values["Author"].orEmpty())
    }

    private fun delimiter(c: Char) = c.isWhitespace() || c in "()<>[]{}/%" || c == '\u0000'

    /** A literal string starting at [start] (the `(`), and the index after its `)`. */
    internal fun literal(text: String, start: Int): Pair<String, Int> {
        val out = StringBuilder()
        var i = start + 1
        var depth = 1
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') {
                val n = text.getOrNull(i + 1) ?: return out.toString() to text.length
                if (n in '0'..'7') {
                    var j = i + 1
                    var v = 0
                    while (j < text.length && j <= i + 3 && text[j] in '0'..'7') {
                        v = v * 8 + (text[j] - '0')
                        j++
                    }
                    out.append((v and 0xFF).toChar())
                    i = j
                    continue
                }
                when (n) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    // A backslash at the end of a line joins the lines.
                    '\r' -> if (text.getOrNull(i + 2) == '\n') i++
                    '\n' -> {}
                    else -> out.append(n)
                }
                i += 2
                continue
            }
            when (c) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return out.toString() to i + 1
                }
            }
            if (c == '\r') {
                out.append('\n')
                if (text.getOrNull(i + 1) == '\n') i++
            } else out.append(c)
            i++
        }
        return out.toString() to i
    }

    /** A hex string's bytes as ISO-8859-1 characters; a missing last digit counts as 0. */
    internal fun hex(digits: String): String {
        val clean = digits.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        val even = if (clean.length % 2 == 1) clean + "0" else clean
        return even.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
    }

    /** PDF text string bytes (as ISO-8859-1 characters) to text: UTF-16, UTF-8 or PDFDoc. */
    internal fun decodeText(raw: String): String {
        val bytes = ByteArray(raw.length) { raw[it].code.toByte() }
        fun starts(vararg b: Int) =
            bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
        val text =
            when {
                starts(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                starts(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                starts(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
                else ->
                    raw.map { c ->
                            when (c.code) {
                                in 0x80..0x9E -> pdfDoc80[c.code - 0x80]
                                0xA0 -> Char(0x20AC)
                                else -> c
                            }
                        }
                        .joinToString("")
            }
        return text.replace(Regex("[\\x00-\\x1F\\x7F\\s]+"), " ").trim()
    }

    /** PDFDocEncoding 0x80 to 0x9E, where it differs from ISO-8859-1. */
    private val pdfDoc80 =
        intArrayOf(
                0x2022,
                0x2020,
                0x2021,
                0x2026,
                0x2014,
                0x2013,
                0x0192,
                0x2044,
                0x2039,
                0x203A,
                0x2212,
                0x2030,
                0x201E,
                0x201C,
                0x201D,
                0x2018,
                0x2019,
                0x201A,
                0x2122,
                0xFB01,
                0xFB02,
                0x0141,
                0x0152,
                0x0160,
                0x0178,
                0x017D,
                0x0131,
                0x0142,
                0x0153,
                0x0161,
                0x017E,
            )
            .map { Char(it) }
}
