package dev.sadking.folio.reader.narration

/**
 * Comparison key for matching Readium's utterance text with the plan: trim + collapse whitespace.
 * Same as reader.normalize.
 */
internal fun normalizeKey(text: String): String = text.trim().replace(WHITESPACE, " ")

private val WHITESPACE = Regex("\\s+")

/**
 * Only what Kokoro hears. Matching, highlighting and alignment use the original text. Any change to
 * the output must bump [VERSION], which is part of the audio cache key.
 */
internal object SpeechText {
    const val VERSION: Int = 1

    /** Soft hyphen, zero-width space/non-joiner/joiner, zero-width no-break space. */
    private val INVISIBLE = Regex("[\\x{AD}\\x{200B}-\\x{200D}\\x{FEFF}]")

    /** The full stop also ends the text: only end of text or a closing mark follows. */
    private const val ENDS_TEXT = "(?=\\s*$|\\s*[\"”’)\\]])"

    /** "etc." also ends the sentence before a capital ("e.g. Paris" is a name, so not there). */
    private const val ENDS = "(?=\\s*$|\\s*[\"”’)\\]]|\\s+[A-Z“\"])"

    private val EXPANSIONS: List<Pair<Regex, String>> =
        listOf(
            Regex("\\bMr\\.") to "Mister",
            Regex("\\bMrs\\.") to "Missus",
            Regex("\\bMs\\.") to "Miz",
            Regex("\\bDr\\.(?=\\s+[A-Z])") to "Doctor",
            Regex("\\bProf\\.") to "Professor",
            Regex("\\be\\.g\\.$ENDS_TEXT") to "for example.",
            Regex("\\bE\\.g\\.") to "For example",
            Regex("\\be\\.g\\.") to "for example",
            Regex("\\bi\\.e\\.$ENDS_TEXT") to "that is.",
            Regex("\\bI\\.e\\.") to "That is",
            Regex("\\bi\\.e\\.") to "that is",
            Regex("\\bvs\\.") to "versus",
            Regex("\\betc\\.$ENDS") to "et cetera.",
            Regex("\\betc\\.") to "et cetera",
        )

    /**
     * What Kokoro hears: expansions, invisible characters removed, whitespace collapsed,
     * punctuation kept. An abbreviation whose full stop also ends the sentence keeps it ("etc." at
     * the end becomes "et cetera."). "St." is left alone (Saint or Street).
     */
    fun forSynthesis(text: String): String {
        var out = normalizeKey(text.replace(INVISIBLE, ""))
        for ((pattern, replacement) in EXPANSIONS) out = pattern.replace(out, replacement)
        return out
    }
}
