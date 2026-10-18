package dev.sadking.folio.reader

import android.view.MotionEvent
import kotlin.math.hypot
import org.json.JSONObject

/**
 * Selecting across a page turn (M11). In a paged book, while one finger holds a selection (a
 * selection handle, or the long press still dragging) or text is selected, a quick tap with a
 * second finger turns the page: on the half of the screen the book reads towards it goes forward,
 * on the other half back (so in a right-to-left book the left half goes forward). Several taps turn
 * several pages. The held finger keeps dragging, so the selection grows on the new page.
 *
 * The second finger never reaches the page. [SelectionTaps] takes it out of every touch event
 * before the page sees it, so the page follows the held finger alone: it sees neither a pinch nor a
 * new touch that would end the selection's drag.
 *
 * A chapter is one document laid out in columns, so the next page is only a scroll away and the
 * selection survives it. The selection's pointers, and so the highlight, cover both pages. At the
 * end of a chapter (or its start, going back) the page does not turn: the next chapter is another
 * document, the selection cannot reach into it, and turning would lose it. A short buzz says so. A
 * highlight there ends at the chapter.
 *
 * Scrolling books need none of this: the page scrolls under the handle by itself.
 */

/** A second finger that lifts within this long of touching down is a tap. */
internal const val SELECTION_TAP_MS = 300L

/** How often the page is asked whether text is selected, while a finger is down or menu open. */
internal const val SELECTION_POLL_MS = 120L

/** How long a turn under a selection may take to show before the next tap is handled. */
internal const val SELECTION_TURN_SETTLE_MS = 500L

/**
 * What the page says (FolioSelection.state in highlights.js): whether text is [selected], and
 * whether this chapter has a page before ([earlier]) and after ([later]) the one on screen, in
 * reading order.
 */
internal data class SelectionPage(val selected: Boolean, val earlier: Boolean, val later: Boolean) {
    /** Whether the page may turn under the selection, [forward] or back, within the chapter. */
    fun canTurn(forward: Boolean): Boolean = selected && if (forward) later else earlier
}

/** The page's answer, or null when it is not one. */
internal fun parseSelectionPage(raw: String?): SelectionPage? {
    val value = bridgeValue(raw) as? JSONObject ?: return null
    if (!value.has("selected")) return null
    return SelectionPage(
        value.optBoolean("selected"),
        value.optBoolean("earlier"),
        value.optBoolean("later"),
    )
}

/**
 * The way a tap at [x] on a screen [width] wide turns the page: 1 forward, on the half the book
 * reads towards (the right, or the left when [rtl]), else -1.
 */
internal fun selectionTapTurn(x: Float, width: Float, rtl: Boolean): Int {
    val right = x >= width / 2f
    return if (right != rtl) 1 else -1
}

/** What the page gets for one touch event. */
internal sealed interface PageTouch {
    /** The event as it came. */
    data object Whole : PageTouch

    /** Nothing: the event was only about fingers the page never saw. */
    data object Withheld : PageTouch

    /**
     * A copy with only the pointers [ids], in that order, as [action] (masked). For a pointer down
     * or up, [actionIndex] is that pointer's index in [ids].
     */
    data class Only(val action: Int, val actionIndex: Int, val ids: List<Int>) : PageTouch
}

/** What to do with one touch event: what the page gets, and a page [turn] (1, -1, or 0 none). */
internal data class SelectionTouch(val page: PageTouch, val turn: Int = 0)

/**
 * Follows the fingers on the reader's window, one gesture at a time, and finds second-finger taps
 * while text is selected. The first finger always belongs to the page. A further finger that
 * touches down while text is being selected is the reader's: the page never sees it, from its touch
 * down to its lift. It turns the page when it lifts within [tapMs] of touching down, having moved
 * less than [slop] pixels, with no other further finger touching down meanwhile; a pinch, a long
 * touch or two fingers at once turn nothing. A further finger while nothing is selected is the
 * page's, as before. Once the page's own fingers have all lifted, the page has seen its touch end,
 * and every finger still down or landing after is kept from it until the last one lifts.
 *
 * Pure: it sees each event as its masked action, the action's pointer index, the pointer ids and
 * positions in the event's order, and the time.
 */
internal class SelectionTaps(private val slop: Float, private val tapMs: Long = SELECTION_TAP_MS) {
    private class Tap(val downAt: Long, val x: Float, val y: Float, var spoiled: Boolean)

    /** The fingers the page sees, in the order they touched down. */
    private val page = LinkedHashSet<Int>()
    /** The fingers kept from the page, by id. */
    private val taps = HashMap<Int, Tap>()
    /** True once the page has seen this gesture end, while kept fingers are still down. */
    private var pageEnded = false

    /**
     * One event: its masked [action], the [actionIndex] of the pointer going down or up, the
     * pointer [ids] and their [xs] and [ys] in the event's order, at [time]. [width] and [rtl] say
     * which half of the screen goes forward; [selecting] is whether text is being selected, checked
     * when a further finger touches down.
     */
    fun onTouch(
        action: Int,
        actionIndex: Int,
        ids: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        time: Long,
        width: Float,
        rtl: Boolean,
        selecting: Boolean,
    ): SelectionTouch {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                ids.firstOrNull()?.let(page::add)
                return SelectionTouch(PageTouch.Whole)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val id = ids.getOrNull(actionIndex) ?: return SelectionTouch(PageTouch.Withheld)
                if (pageEnded || selecting) {
                    // Two kept fingers at once are neither a tap: a pinch or a fumble.
                    val crowded = taps.isNotEmpty()
                    taps.values.forEach { it.spoiled = true }
                    taps[id] = Tap(time, xs[actionIndex], ys[actionIndex], crowded || !selecting)
                    return SelectionTouch(PageTouch.Withheld)
                }
                // A further finger for the page: whatever this is, it is not a tap.
                taps.values.forEach { it.spoiled = true }
                page += id
                return SelectionTouch(forPage(action, id, ids))
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in ids.indices) {
                    val tap = taps[ids[index]] ?: continue
                    if (moved(tap, xs[index], ys[index]) || time - tap.downAt > tapMs)
                        tap.spoiled = true
                }
                return SelectionTouch(forPage(action, -1, ids))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = ids.getOrNull(actionIndex) ?: return SelectionTouch(PageTouch.Withheld)
                val tap = taps.remove(id)
                if (tap != null)
                    return SelectionTouch(
                        PageTouch.Withheld,
                        turn(tap, xs[actionIndex], ys[actionIndex], time, width, rtl),
                    )
                if (id !in page) return SelectionTouch(PageTouch.Withheld)
                if (page.size == 1 && !pageEnded) {
                    // The page's last finger lifts while a kept one is still down: the page's
                    // gesture ends here.
                    page.clear()
                    pageEnded = true
                    return SelectionTouch(PageTouch.Only(MotionEvent.ACTION_UP, 0, listOf(id)))
                }
                val forPage = forPage(action, id, ids)
                page -= id
                return SelectionTouch(forPage)
            }
            MotionEvent.ACTION_UP -> {
                val id = ids.firstOrNull()
                val tap = id?.let(taps::remove)
                val ended = pageEnded
                reset()
                return when {
                    tap != null ->
                        SelectionTouch(
                            PageTouch.Withheld,
                            turn(tap, xs[0], ys[0], time, width, rtl),
                        )
                    ended -> SelectionTouch(PageTouch.Withheld)
                    else -> SelectionTouch(PageTouch.Whole)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                val forPage = if (pageEnded) PageTouch.Withheld else forPage(action, -1, ids)
                reset()
                return SelectionTouch(forPage)
            }
            else -> return SelectionTouch(PageTouch.Whole)
        }
    }

    /** The event for the page: whole when it has only the page's fingers, else trimmed to them. */
    private fun forPage(action: Int, id: Int, ids: IntArray): PageTouch {
        if (pageEnded) return PageTouch.Withheld
        val kept = ids.filter { it in page }
        if (kept.isEmpty()) return PageTouch.Withheld
        if (kept.size == ids.size) return PageTouch.Whole
        return PageTouch.Only(action, kept.indexOf(id).coerceAtLeast(0), kept)
    }

    private fun moved(tap: Tap, x: Float, y: Float) = hypot(x - tap.x, y - tap.y) > slop

    private fun turn(tap: Tap, x: Float, y: Float, time: Long, width: Float, rtl: Boolean): Int =
        if (tap.spoiled || moved(tap, x, y) || time - tap.downAt > tapMs) 0
        else selectionTapTurn(tap.x, width, rtl)

    private fun reset() {
        page.clear()
        taps.clear()
        pageEnded = false
    }
}
