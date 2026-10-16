package dev.sadking.folio.core

import java.text.Normalizer

/**
 * Where a book's file goes in the Books folder: `Author/Title.<format>`. The same path is used on
 * the phone and on the e-reader, so names must be safe on FAT and exFAT, which e-readers use, and
 * on the phone's ext4/F2FS.
 */
object BookPaths {
    const val UNKNOWN_AUTHOR = "Unknown author"
    const val UNTITLED = "Untitled"

    /** Longest name part in characters; FAT allows 255, and some e-readers less. */
    const val MAX_CHARS = 120

    /** Longest name part in UTF-8 bytes, under ext4's 255-byte limit with room for " (99)". */
    const val MAX_BYTES = 240

    private val forbidden = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")
    private val reserved =
        setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

    /**
     * The path for a book: the first author's folder and the title as the file name. [taken] says
     * whether a path is already used by another book; the comparison is case-insensitive because
     * FAT ignores case. A clash gets " (2)", " (3)" and so on. [extension] is the book's format
     * ("epub", "pdf", "cbz", "cbr"). Pass it by name, since [taken] stays last for trailing
     * lambdas.
     */
    fun path(
        authors: List<String>,
        title: String,
        extension: String = "epub",
        taken: (String) -> Boolean = { false },
    ): String {
        val ext = extension.removePrefix(".").lowercase()
        require(ext.isNotEmpty() && ext.all { it in 'a'..'z' || it in '0'..'9' }) {
            "Not a file extension: $extension"
        }
        val folder = part(authors.firstOrNull { it.isNotBlank() }.orEmpty(), UNKNOWN_AUTHOR)
        val name = part(title, UNTITLED, reserveForSuffix = ext.length + 1)
        var candidate = "$folder/$name.$ext"
        var n = 2
        while (taken(candidate)) {
            candidate = "$folder/$name ($n).$ext"
            n++
        }
        return candidate
    }

    /** The key two paths share when FAT would treat them as the same file. */
    fun key(path: String): String = Normalizer.normalize(path, Normalizer.Form.NFC).lowercase()

    /** One folder or file name, cleaned of everything FAT, exFAT or ext4 would refuse. */
    fun part(text: String, fallback: String, reserveForSuffix: Int = 0): String {
        val cleaned =
            Normalizer.normalize(text, Normalizer.Form.NFC)
                .replace(forbidden, " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
                // Hidden files on the e-reader, and trailing dots FAT drops.
                .trimStart('.', ' ')
                .trimEnd('.', ' ')
        val short = shorten(cleaned, MAX_CHARS - reserveForSuffix, MAX_BYTES - reserveForSuffix)
        val safe = short.ifEmpty { fallback }
        return if (safe.uppercase() in reserved) "$safe _" else safe
    }

    /** Cuts on a code point boundary and at a word break when one is near the end. */
    private fun shorten(text: String, maxChars: Int, maxBytes: Int): String {
        if (text.length <= maxChars && text.toByteArray().size <= maxBytes) return text
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            val b = s.toByteArray().size
            if (out.length + s.length > maxChars || bytes + b > maxBytes) break
            out.append(s)
            bytes += b
            i += s.length
        }
        val space = out.lastIndexOf(" ")
        val cut = if (space >= out.length * 3 / 4) out.substring(0, space) else out.toString()
        return cut.trimEnd('.', ' ')
    }
}
