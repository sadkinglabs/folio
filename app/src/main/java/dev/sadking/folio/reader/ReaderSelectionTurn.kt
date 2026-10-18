package dev.sadking.folio.reader

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot
import org.json.JSONObject

/**
 * Selecting across a page turn (M11). In a paged book, while text is selected, the other hand turns
 * the page with a quick tap in the page-turn tap zones, or with a slide sideways. The finger
 * holding the selection keeps dragging, so the selection grows on the new page.
 *
 * The other hand's touch reaches the reader in one of two ways:
 * - As a second finger in the held finger's own touch. This is how it comes while the long press
 *   that made the selection is still dragging: that finger is on the page itself.
 * - As a touch of its own. This is how it comes once the held finger is on a selection handle. The
 *   WebView draws each handle in a separate popup window that splits touches, so a finger on the
 *   page while another holds a handle starts a new touch on the reader's window. Handed to the
 *   page, that new touch ends the handle's drag (Chromium's TouchHandle ends a drag on any touch
 *   down away from it) and its tap clears the selection.
 *
 * Either way, [SelectionTaps] keeps the touch from the page until it knows what it is. A tap or a
 * slide that turns never reaches the page. Anything else is given to the page, as far as it can be:
 * a touch of its own reaches it late but whole, so a tap in the middle still clears the selection
 * and a long press still selects; a second finger is kept from it, as it was before, since adding
 * it to the held finger's drag would make a pinch and end the drag.
 *
 * A chapter is one document laid out in columns, so the next page is only a scroll away and the
 * selection survives it. The selection's pointers, and so the highlight, cover both pages. At the
 * end of a chapter (or its start, going back) the page does not turn: the next chapter is another
 * document, the selection cannot reach into it, and turning would lose it. A short buzz says so. A
 * highlight there ends at the chapter.
 *
 * Scrolling books need none of this: the page scrolls under the handle by itself.
 */

/** A touch that lifts within this long of touching down is a tap. */
internal const val SELECTION_TAP_MS = 300L

/**
 * How far a touch must slide sideways to turn the page, in dp. Readium leaves page swipes to the
 * WebView's own scrolling, so the reader has no threshold of its own to reuse.
 */
internal const val SELECTION_SWIPE_DP = 40f

/** How often the page is asked whether text is selected; see ReaderActivity.watchSelection. */
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
 * Where the page is and how it turns, for a touch at a window x: the page view's [left] edge and
 * [width] in window pixels, the owner's tap [zones] and [leftHanded] swap, and whether the book
 * reads right to left ([rtl]).
 */
internal data class TurnRules(
    val left: Float,
    val width: Float,
    val zones: TapZones = TapZones.EDGES,
    val leftHanded: Boolean = false,
    val rtl: Boolean = false,
)

/** What the page gets for one touch event. */
internal sealed interface PageTouch {
    /** The event as it came. */
    data object Whole : PageTouch

    /** Nothing: the event was only about fingers the page never saw, or a turn. */
    data object Withheld : PageTouch

    /** Nothing yet: keep a copy, as the page may still get it later (see [Replay]). */
    data object Held : PageTouch

    /** Every event held so far, in order, then this one (if there is one), as they came. */
    data object Replay : PageTouch

    /**
     * A copy with only the pointers [ids], in that order, as [action] (masked). For a pointer down
     * or up, [actionIndex] is that pointer's index in [ids].
     */
    data class Only(val action: Int, val actionIndex: Int, val ids: List<Int>) : PageTouch
}

/**
 * What to do with one touch event: what the page gets, and a page [turn] (1 forward, -1 back, or 0
 * none). [note] says what was decided, for the tap trace; it is not part of equality.
 */
internal data class SelectionTouch(val page: PageTouch, val turn: Int = 0) {
    var note: String? = null
        private set

    fun noted(text: String) = apply { note = text }
}

/**
 * Follows the touches on the reader's window, one gesture at a time, and finds the other hand's
 * taps and slides while text is selected (see the top of this file).
 *
 * A touch of its own that starts while text is selected is held: the page gets nothing of it yet.
 * It turns the page when it lifts within [tapMs], having moved no more than [slop] pixels, in a
 * page-turn tap zone; or when it lifts having slid at least [swipe] pixels, more sideways than up
 * or down. It is replayed to the page, whole, when it is a tap in the middle, when it stays down
 * longer than [tapMs] without moving ([expire] says when, as a still finger may send nothing), when
 * it first moves more up or down than sideways, when a second finger joins it, or when it lifts as
 * neither a tap nor a slide.
 *
 * A further finger that touches down while text is selected is the reader's: the page never sees
 * it, from its touch down to its lift. It turns the page as a touch of its own would, unless
 * another further finger touched down meanwhile (a pinch or a fumble). A further finger while
 * nothing is selected is the page's, as before. Once the page's own fingers have all lifted, the
 * page has seen its touch end, and every finger still down or landing after is kept from it until
 * the last one lifts.
 *
 * Pure: it sees each event as its masked action, the action's pointer index, the pointer ids and
 * positions in the event's order, and the time.
 */
internal class SelectionTaps(
    private val slop: Float,
    private val swipe: Float,
    private val tapMs: Long = SELECTION_TAP_MS,
) {
    private class Tap(val downAt: Long, val x: Float, val y: Float, var spoiled: Boolean) {
        /** Moved past the slop at some point: no longer a tap. */
        var moved = false
    }

    /** The fingers the page sees, in the order they touched down. */
    private val page = LinkedHashSet<Int>()
    /** The further fingers kept from the page, by id. */
    private val taps = HashMap<Int, Tap>()
    /** True once the page has seen this gesture end, while kept fingers are still down. */
    private var pageEnded = false
    /** A touch of its own, held back from the page while it may still turn. */
    private var held: Tap? = null
    /** True once a held touch was replayed: the rest of it goes to the page as it comes. */
    private var passing = false

    /** Whether a touch of its own is being held back now. */
    val holding: Boolean
        get() = held != null

    /**
     * One event: its masked [action], the [actionIndex] of the pointer going down or up, the
     * pointer [ids] and their [xs] and [ys] in the event's order, at [time]. [rules] say how a tap
     * or slide turns; [selecting] is whether text is selected, checked when a finger touches down.
     */
    fun onTouch(
        action: Int,
        actionIndex: Int,
        ids: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        time: Long,
        rules: TurnRules,
        selecting: Boolean,
    ): SelectionTouch {
        if (action == MotionEvent.ACTION_DOWN) {
            reset()
            if (selecting && ids.isNotEmpty()) {
                held = Tap(time, xs[0], ys[0], spoiled = false)
                return SelectionTouch(PageTouch.Held).noted("other hand: held back")
            }
            ids.firstOrNull()?.let(page::add)
            return SelectionTouch(PageTouch.Whole)
        }
        held?.let {
            return onHeld(it, action, ids, xs, ys, time, rules)
        }
        if (passing) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) reset()
            return SelectionTouch(PageTouch.Whole)
        }
        when (action) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                val id = ids.getOrNull(actionIndex) ?: return SelectionTouch(PageTouch.Withheld)
                if (pageEnded || selecting) {
                    // Two kept fingers at once are neither a tap nor a slide: a pinch or a fumble.
                    val crowded = taps.isNotEmpty()
                    taps.values.forEach { it.spoiled = true }
                    taps[id] = Tap(time, xs[actionIndex], ys[actionIndex], crowded || !selecting)
                    return SelectionTouch(PageTouch.Withheld).noted("second finger: held back")
                }
                // A further finger for the page: whatever this is, it does not turn.
                taps.values.forEach { it.spoiled = true }
                page += id
                return SelectionTouch(forPage(action, id, ids))
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in ids.indices) {
                    val tap = taps[ids[index]] ?: continue
                    if (beyondSlop(tap, xs[index], ys[index])) tap.moved = true
                }
                return SelectionTouch(forPage(action, -1, ids))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = ids.getOrNull(actionIndex) ?: return SelectionTouch(PageTouch.Withheld)
                val tap = taps.remove(id)
                if (tap != null)
                    return secondLifted(tap, xs[actionIndex], ys[actionIndex], time, rules)
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
                    tap != null -> secondLifted(tap, xs[0], ys[0], time, rules)
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

    /**
     * Called once [tapMs] have passed since a held touch came down, at [time]: a touch still down
     * that has not moved is a long touch, so the page gets it now ([PageTouch.Replay], with no
     * event of its own) and the rest of it as it comes. Null when nothing changes.
     */
    fun expire(time: Long): SelectionTouch? {
        val tap = held ?: return null
        if (tap.moved || time - tap.downAt < tapMs) return null
        return replay("long touch")
    }

    /** One event of a touch of its own that is held back. */
    private fun onHeld(
        tap: Tap,
        action: Int,
        ids: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        time: Long,
        rules: TurnRules,
    ): SelectionTouch {
        val x = xs.firstOrNull() ?: tap.x
        val y = ys.firstOrNull() ?: tap.y
        return when (action) {
            MotionEvent.ACTION_MOVE ->
                when {
                    !tap.moved && beyondSlop(tap, x, y) -> {
                        tap.moved = true
                        if (abs(y - tap.y) >= abs(x - tap.x)) replay("drag up or down")
                        else SelectionTouch(PageTouch.Held)
                    }
                    !tap.moved && time - tap.downAt > tapMs -> replay("long touch")
                    else -> SelectionTouch(PageTouch.Held)
                }
            MotionEvent.ACTION_UP -> {
                val (turn, how) = verdict(tap, x, y, time, rules)
                if (turn != 0) {
                    reset()
                    SelectionTouch(PageTouch.Withheld, turn).noted("other-hand $how")
                } else {
                    val touch = replay(how)
                    reset()
                    touch
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                reset()
                SelectionTouch(PageTouch.Withheld).noted("held back, cancelled")
            }
            MotionEvent.ACTION_POINTER_DOWN -> replay("a second finger joined")
            else -> SelectionTouch(PageTouch.Held)
        }
    }

    /** Gives the held touch to the page, and the rest of it as it comes. */
    private fun replay(why: String): SelectionTouch {
        held = null
        passing = true
        return SelectionTouch(PageTouch.Replay).noted("held back, replayed: $why")
    }

    /** A kept further finger lifts at ([x], [y]) at [time]: the page never sees it. */
    private fun secondLifted(
        tap: Tap,
        x: Float,
        y: Float,
        time: Long,
        rules: TurnRules,
    ): SelectionTouch {
        if (tap.spoiled)
            return SelectionTouch(PageTouch.Withheld).noted("second finger: no turn, not alone")
        val (turn, how) = verdict(tap, x, y, time, rules)
        return SelectionTouch(PageTouch.Withheld, turn)
            .noted(if (turn != 0) "second-finger $how" else "second finger: no turn, $how")
    }

    /**
     * Whether [tap], lifting at ([x], [y]) at [time], turns the page: 1 forward, -1 back, or 0; and
     * what it was, in words.
     */
    private fun verdict(
        tap: Tap,
        x: Float,
        y: Float,
        time: Long,
        rules: TurnRules,
    ): Pair<Int, String> {
        if (!tap.moved && !beyondSlop(tap, x, y)) {
            if (time - tap.downAt > tapMs) return 0 to "long touch"
            val action =
                tapAction(tap.x - rules.left, rules.width, rules.zones, rules.leftHanded, rules.rtl)
            return when (action) {
                TapAction.NEXT -> 1 to "tap: next page"
                TapAction.PREVIOUS -> -1 to "tap: previous page"
                TapAction.CONTROLS -> 0 to "tap in the middle"
            }
        }
        val dx = x - tap.x
        val dy = y - tap.y
        if (abs(dx) >= swipe && abs(dx) > abs(dy)) {
            // The page follows the finger: sliding towards where the lines start brings the next
            // page, so leftwards in a left-to-right book and rightwards in a right-to-left one.
            val forward = (dx < 0) != rules.rtl
            return if (forward) 1 to "slide: next page" else -1 to "slide: previous page"
        }
        return 0 to "neither tap nor slide"
    }

    /** The event for the page: whole when it has only the page's fingers, else trimmed to them. */
    private fun forPage(action: Int, id: Int, ids: IntArray): PageTouch {
        if (pageEnded) return PageTouch.Withheld
        val kept = ids.filter { it in page }
        if (kept.isEmpty()) return PageTouch.Withheld
        if (kept.size == ids.size) return PageTouch.Whole
        return PageTouch.Only(action, kept.indexOf(id).coerceAtLeast(0), kept)
    }

    private fun beyondSlop(tap: Tap, x: Float, y: Float) = hypot(x - tap.x, y - tap.y) > slop

    private fun reset() {
        page.clear()
        taps.clear()
        pageEnded = false
        held = null
        passing = false
    }
}
