package dev.sadking.folio.core.stats

import dev.sadking.folio.core.Epub
import java.io.File
import java.util.zip.ZipFile

/**
 * A book's word count from its own EPUB text, so speeds on the Kindle and the phone compare fairly.
 * Counted once per file revision. Never logs or keeps the text.
 */
object EpubWords {
    /** The most text read from one spine item. */
    const val MAX_ITEM_BYTES = 32L * 1024 * 1024

    /**
     * Words in every spine item of [file], or null when it can't be read (not an EPUB, protected,
     * damaged).
     */
    fun count(file: File): Long? =
        runCatching {
                val spine = Epub.spine(file)
                ZipFile(file).use { zip ->
                    spine.sumOf { path ->
                        val entry = zip.getEntry(path) ?: return@sumOf 0L
                        if (entry.size > MAX_ITEM_BYTES) return@sumOf 0L
                        val bytes =
                            zip.getInputStream(entry).use { input ->
                                val out = java.io.ByteArrayOutputStream()
                                Epub.copyBounded(input, out, MAX_ITEM_BYTES)
                                out.toByteArray()
                            }
                        count(markupText(String(bytes, Charsets.UTF_8)))
                    }
                }
            }
            .getOrNull()

    /**
     * The text of an XHTML document: tags removed, `head`, `script` and `style` left out, character
     * references replaced by a space or the letter they stand for.
     */
    fun markupText(markup: String): String {
        val out = StringBuilder(markup.length / 2)
        var i = 0
        var skipUntil: String? = null
        while (i < markup.length) {
            val c = markup[i]
            if (c == '<') {
                val close = markup.indexOf('>', i)
                if (close < 0) break
                val tag = markup.substring(i + 1, close).trim().lowercase()
                val name =
                    tag.removePrefix("/")
                        .takeWhile { it.isLetterOrDigit() || it == ':' }
                        .substringAfter(':')
                if (skipUntil != null) {
                    if (tag.startsWith("/") && name == skipUntil) skipUntil = null
                } else if (!tag.startsWith("/") && !tag.endsWith("/") && name in SKIPPED) {
                    skipUntil = name
                }
                // A tag separates words: "<p>one</p><p>two</p>" is two words.
                out.append(' ')
                i = close + 1
                continue
            }
            if (skipUntil != null) {
                i++
                continue
            }
            if (c == '&') {
                val end = markup.indexOf(';', i)
                if (end in (i + 1)..(i + 10)) {
                    out.append(entity(markup.substring(i + 1, end)))
                    i = end + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private val SKIPPED = setOf("head", "script", "style")

    private val ACCENTS = listOf("acute", "grave", "uml", "circ", "tilde", "cedil", "ring", "slash")

    private fun entity(name: String): String {
        val code =
            when {
                name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)
                name.startsWith("#") -> name.drop(1).toIntOrNull()
                else -> null
            }
        return when {
            code != null && Character.isValidCodePoint(code) -> String(Character.toChars(code))
            // Named letters (é, ñ, ...) keep a word whole; the rest (&amp;, &nbsp;, ...) split.
            ACCENTS.any { name.length == it.length + 1 && name.endsWith(it) } -> "a"
            name == "rsquo" || name == "apos" -> "'"
            else -> " "
        }
    }

    /**
     * Words in [text]: runs of letters and digits, joined by an apostrophe or hyphen between two of
     * them ("don't", "well-known" are one). Each Chinese or Japanese character counts as a word.
     */
    fun count(text: CharSequence): Long {
        var words = 0L
        var inWord = false
        var i = 0
        while (i < text.length) {
            val code = Character.codePointAt(text, i)
            val size = Character.charCount(code)
            when {
                ideographic(code) -> {
                    words++
                    inWord = false
                }
                Character.isLetterOrDigit(code) ||
                    Character.getType(code) == Character.NON_SPACING_MARK.toInt() -> {
                    if (!inWord) words++
                    inWord = true
                }
                inWord &&
                    (code == '\''.code || code == '’'.code || code == '-'.code) &&
                    i + size < text.length &&
                    Character.isLetterOrDigit(Character.codePointAt(text, i + size)) -> Unit
                else -> inWord = false
            }
            i += size
        }
        return words
    }

    private fun ideographic(code: Int): Boolean =
        Character.isIdeographic(code) ||
            Character.UnicodeScript.of(code).let {
                it == Character.UnicodeScript.HIRAGANA || it == Character.UnicodeScript.KATAKANA
            }
}
