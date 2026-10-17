package dev.sadking.folio.reader

import org.json.JSONObject

/**
 * Selecting across a page turn (M11). In a paged book, dragging a selection handle to the last word
 * on the page and holding it there for a moment turns to the next page, and the selection keeps
 * growing from there; the first word and the previous page work the same way. A chapter is one
 * document laid out in columns, so the next page is only a scroll away and the selection survives
 * it. The selection's pointers, and so the highlight, cover both pages.
 *
 * At the end of a chapter the page does not turn: the next chapter is another document, the
 * selection cannot reach into it, and turning would lose it. A highlight there ends at the chapter.
 * Scrolling books need none of this: the page scrolls under the handle by itself.
 */

/** How long the moving end must rest on the page's first or last word before the page turns. */
internal const val SELECTION_TURN_DWELL_MS = 500L

/** How often the page is asked about the selection while text is selected. */
internal const val SELECTION_POLL_MS = 120L

/**
 * What the page says about the selection (FolioSelection.edge in highlights.js): [edge] 1 when its
 * moving end holds the last character on the page and a further page exists, -1 for the first
 * character and an earlier page, else 0; its [length]; and a [key] that changes whenever the moving
 * end or the page does.
 */
internal data class SelectionEdge(val edge: Int, val length: Int, val key: String)

/** The page's answer, or null when nothing is selected or the answer is not one. */
internal fun parseSelectionEdge(raw: String?): SelectionEdge? {
    val value = bridgeValue(raw) as? JSONObject ?: return null
    val key = value.optString("key").takeIf { it.isNotEmpty() } ?: return null
    return SelectionEdge(
        value.optInt("edge").coerceIn(-1, 1),
        value.optInt("length").coerceAtLeast(0),
        key,
    )
}

/**
 * Decides when to turn the page under a selection, from the page's answers over time. It turns when
 * the moving end has rested on the page's first or last word for [dwellMs], and only once the
 * selection has grown past its first size: a word long-pressed at the bottom of a page, to look it
 * up, does not turn the page. One turn per resting place.
 */
internal class SelectionPageTurner(private val dwellMs: Long = SELECTION_TURN_DWELL_MS) {
    private var shortest = Int.MAX_VALUE
    private var key: String? = null
    private var since = 0L
    private var turnedAt: String? = null

    /** The direction to turn after [sample] at [now] (milliseconds): 1, -1, or 0 to stay. */
    fun next(sample: SelectionEdge?, now: Long): Int {
        if (sample == null) {
            reset()
            return 0
        }
        shortest = minOf(shortest, sample.length)
        if (sample.key != key) {
            key = sample.key
            since = now
        }
        if (sample.edge == 0 || sample.length <= shortest || sample.key == turnedAt) return 0
        if (now - since < dwellMs) return 0
        turnedAt = sample.key
        return sample.edge
    }

    fun reset() {
        shortest = Int.MAX_VALUE
        key = null
        since = 0L
        turnedAt = null
    }
}
