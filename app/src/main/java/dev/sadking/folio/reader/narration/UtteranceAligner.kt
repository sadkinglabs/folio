package dev.sadking.folio.reader.narration

/** What a `speak(text)` from Readium refers to. */
internal sealed interface Match {
    /** A planned utterance ahead of the last request. */
    data class Planned(val index: Long) : Match

    /** The utterance that `stop()` interrupted: resume it. */
    data object Held : Match

    /** A recently heard utterance (skip previous). */
    data class Recent(val index: Long) : Match

    data object Miss : Match
}

internal object UtteranceAligner {
    /**
     * Matches [key] (already normalizeKey'd) against, in order: the held utterance, the plan at
     * lastRequested+1..+[window], then the recent indices, most recent first.
     *
     * The held utterance is tried first, not after the window as section 5.6 lists it: when the
     * next utterance has the same text (a repeated "No."), resuming after a pause is far more
     * likely than skipping forward, and resuming must not drop the rest of the sentence.
     *
     * [plan] must be in ascending index order; held and recent indices are looked up in it.
     */
    fun match(
        key: String,
        plan: List<PlannedUtterance>,
        lastRequested: Long,
        heldIndex: Long?,
        recentIndices: List<Long>,
        window: Int,
    ): Match {
        if (heldIndex != null && find(plan, heldIndex)?.key == key) return Match.Held
        for (step in 1..window) {
            val index = lastRequested + step
            if (find(plan, index)?.key == key) return Match.Planned(index)
        }
        for (index in recentIndices.sortedDescending()) {
            if (find(plan, index)?.key == key) return Match.Recent(index)
        }
        return Match.Miss
    }

    private fun find(plan: List<PlannedUtterance>, index: Long): PlannedUtterance? {
        if (plan.isEmpty()) return null
        val guess = index - plan[0].index
        if (guess >= 0 && guess < plan.size && plan[guess.toInt()].index == index) {
            return plan[guess.toInt()]
        }
        val at = plan.binarySearchBy(index) { it.index }
        return if (at >= 0) plan[at] else null
    }
}
