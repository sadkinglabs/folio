package dev.sadking.folio.core.dictionary

/**
 * WordNet's morphy: the base forms of an inflected word ("running" → "run", "geese" → "goose"). The
 * word itself is tried first, then the exception list, then the detachment rules; only forms the
 * index holds are kept, in that order, each once.
 */
object Morphy {
    private val NOUN_RULES =
        listOf(
            "s" to "",
            "ses" to "s",
            "xes" to "x",
            "zes" to "z",
            "ches" to "ch",
            "shes" to "sh",
            "men" to "man",
            "ies" to "y",
        )
    private val VERB_RULES =
        listOf(
            "s" to "",
            "ies" to "y",
            "es" to "e",
            "es" to "",
            "ed" to "e",
            "ed" to "",
            "ing" to "e",
            "ing" to "",
        )
    private val ADJECTIVE_RULES = listOf("er" to "", "est" to "", "er" to "e", "est" to "e")

    private fun rules(pos: PartOfSpeech) =
        when (pos) {
            PartOfSpeech.NOUN -> NOUN_RULES
            PartOfSpeech.VERB -> VERB_RULES
            PartOfSpeech.ADJECTIVE -> ADJECTIVE_RULES
            PartOfSpeech.ADVERB -> emptyList()
        }

    fun lemmas(
        word: String,
        pos: PartOfSpeech,
        exceptions: (PartOfSpeech, String) -> List<String>,
        exists: (PartOfSpeech, String) -> Boolean,
    ): List<String> {
        if (word.isEmpty()) return emptyList()
        val candidates = LinkedHashSet<String>()
        candidates += word
        candidates += exceptions(pos, word)
        for ((suffix, ending) in rules(pos)) {
            if (word.endsWith(suffix)) candidates += word.dropLast(suffix.length) + ending
        }
        return candidates.filter { it.isNotEmpty() && exists(pos, it) }
    }
}
