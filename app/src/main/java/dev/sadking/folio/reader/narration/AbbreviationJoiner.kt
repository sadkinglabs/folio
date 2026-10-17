package dev.sadking.folio.reader.narration

/**
 * Undoes sentence breaks that the ICU sentence tokenizer makes after abbreviations, so "Mr." is
 * never an utterance of its own. Used by Readium's tokenizer and by the planner alike, so their
 * utterances match by construction.
 */
internal object AbbreviationJoiner {
    private val TITLE = Regex("\\b(Mr|Mrs|Ms|Dr|Prof|St|Sr|Jr|Mt|vs|etc)\\.$")
    private val LATIN = Regex("\\b([eE]\\.g|[iI]\\.e)\\.$")
    private val INITIAL = Regex("\\b[A-Z]\\.$")
    private val NUMBER = Regex("\\bNo\\.$")

    /**
     * Merges a range into the next when it ends with a known abbreviation (section 5.1). Ranges
     * must be ascending.
     */
    fun join(text: String, ranges: List<IntRange>): List<IntRange> {
        if (ranges.size < 2) return ranges
        val out = ArrayList<IntRange>(ranges.size)
        var current = ranges[0]
        for (i in 1 until ranges.size) {
            val next = ranges[i]
            if (endsWithAbbreviation(text, current, next)) {
                current = current.first..next.last
            } else {
                out += current
                current = next
            }
        }
        out += current
        return out
    }

    private fun endsWithAbbreviation(text: String, range: IntRange, next: IntRange): Boolean {
        val piece = slice(text, range).trimEnd()
        if (TITLE.containsMatchIn(piece)) return true
        if (LATIN.containsMatchIn(piece)) return true
        if (INITIAL.containsMatchIn(piece)) return true
        if (NUMBER.containsMatchIn(piece)) {
            val following = slice(text, next).trimStart()
            return following.firstOrNull()?.isDigit() == true
        }
        return false
    }

    private fun slice(text: String, range: IntRange): String {
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        return text.substring(start, end)
    }
}
