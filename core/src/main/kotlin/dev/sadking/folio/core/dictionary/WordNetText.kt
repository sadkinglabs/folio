package dev.sadking.folio.core.dictionary

import java.util.Locale

/** Turns selected text into the form WordNet's index uses. */
object WordNetText {
    const val MAX_WORDS = 4
    const val MAX_CHARS = 60

    /** Apostrophes as WordNet writes them (straight), from the curly and modifier forms. */
    private val APOSTROPHES = Regex("[’‘ʼ`´]")
    private val SPACES = Regex("\\s+")
    /** Letters, marks, digits, and the joiners WordNet lemmas use: ' - . / */
    private val TOKEN = Regex("[\\p{L}\\p{M}\\p{N}'.\\-/]+")
    private val LETTER = Regex("\\p{L}")

    /**
     * Trims punctuation and quotes (straight and curly) around the selection, drops a possessive
     * 's, lower-cases, and joins words with `_`. Null if it isn't a word or a short phrase (at most
     * [MAX_WORDS] words and [MAX_CHARS] characters).
     */
    fun normalise(selection: String): String? {
        var text = selection.replace(APOSTROPHES, "'").replace(SPACES, " ").trim()
        if (text.length > MAX_CHARS) return null
        text = text.trim { !it.isLetterOrDigit() }
        // A possessive: "Dog's" → "Dog", "dogs'" → "dogs". Only after trimming, so a closing
        // quote does not hide it.
        text =
            when {
                text.endsWith("'s") || text.endsWith("'S") -> text.dropLast(2)
                text.endsWith("'") -> text.dropLast(1)
                else -> text
            }
        text = text.trim { !it.isLetterOrDigit() }
        if (text.isEmpty()) return null
        val words = text.split(' ')
        if (words.size > MAX_WORDS) return null
        if (words.any { !TOKEN.matches(it) }) return null
        if (!LETTER.containsMatchIn(text)) return null
        return words.joinToString("_").lowercase(Locale.ROOT)
    }
}
