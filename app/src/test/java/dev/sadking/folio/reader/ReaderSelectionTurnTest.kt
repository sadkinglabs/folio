package dev.sadking.folio.reader

import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_UP
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Selecting across a page turn, on the JVM: which second-finger touches turn the page under a
 * selection, and what the page gets of them.
 */
class ReaderSelectionTurnTest {
    private val width = 1080f
    private val slop = 24f

    private fun fromPage(value: Any?): String = JSONObject.quote(value.toString())

    /** One finger at a point, by id. */
    private data class Finger(val id: Int, val x: Float, val y: Float)

    private class Gesture(
        val taps: SelectionTaps = SelectionTaps(24f, 300),
        var rtl: Boolean = false,
        var selecting: Boolean = true,
    ) {
        fun touch(action: Int, time: Long, vararg fingers: Finger, index: Int = 0) =
            taps.onTouch(
                action,
                index,
                fingers.map { it.id }.toIntArray(),
                fingers.map { it.x }.toFloatArray(),
                fingers.map { it.y }.toFloatArray(),
                time,
                1080f,
                rtl,
                selecting,
            )
    }

    private val held = Finger(0, 300f, 900f)
    private val right = Finger(1, 900f, 1200f)
    private val left = Finger(1, 150f, 1200f)

    /** A held finger, and a second finger [tap] down at [down] and up at [up]; the up's touch. */
    private fun Gesture.tapWhileHeld(
        tap: Finger,
        down: Long = 100,
        up: Long = 180,
        lift: Finger = tap,
    ): SelectionTouch {
        assertEquals(SelectionTouch(PageTouch.Whole), touch(ACTION_DOWN, 0, held))
        assertEquals(
            SelectionTouch(PageTouch.Withheld),
            touch(ACTION_POINTER_DOWN, down, held, tap, index = 1),
        )
        return touch(ACTION_POINTER_UP, up, held, lift, index = 1)
    }

    @Test
    fun readsThePagesAnswer() {
        val raw =
            fromPage(JSONObject().put("selected", true).put("earlier", false).put("later", true))
        val page = parseSelectionPage(raw)
        assertEquals(SelectionPage(selected = true, earlier = false, later = true), page)
        assertTrue(page!!.canTurn(forward = true))
        assertFalse(page.canTurn(forward = false))
        // Nothing selected: no turn, wherever the chapter goes on.
        assertFalse(SelectionPage(false, true, true).canTurn(true))
        assertFalse(SelectionPage(false, true, true).canTurn(false))
        // A broken answer, or not one.
        assertNull(parseSelectionPage("null"))
        assertNull(parseSelectionPage(fromPage("null")))
        assertNull(parseSelectionPage("not json"))
        assertNull(parseSelectionPage(fromPage(JSONObject().put("later", true))))
    }

    @Test
    fun theHalfTheBookReadsTowardsGoesForward() {
        assertEquals(1, selectionTapTurn(900f, width, rtl = false))
        assertEquals(1, selectionTapTurn(540f, width, rtl = false))
        assertEquals(-1, selectionTapTurn(539f, width, rtl = false))
        assertEquals(-1, selectionTapTurn(900f, width, rtl = true))
        assertEquals(1, selectionTapTurn(100f, width, rtl = true))
    }

    @Test
    fun aQuickSecondFingerTapTurnsForwardAndNeverReachesThePage() {
        val gesture = Gesture()
        assertEquals(SelectionTouch(PageTouch.Withheld, turn = 1), gesture.tapWhileHeld(right))
        // The held finger goes on dragging on the new page: the page gets it as it came.
        assertEquals(
            SelectionTouch(PageTouch.Whole),
            gesture.touch(ACTION_MOVE, 260, held.copy(x = 320f)),
        )
        assertEquals(SelectionTouch(PageTouch.Whole), gesture.touch(ACTION_UP, 400, held))
    }

    @Test
    fun theOtherHalfGoesBackAndRightToLeftBooksSwapThem() {
        assertEquals(-1, Gesture().tapWhileHeld(left).turn)
        assertEquals(-1, Gesture(rtl = true).tapWhileHeld(right).turn)
        assertEquals(1, Gesture(rtl = true).tapWhileHeld(left).turn)
    }

    @Test
    fun whileBothAreDownThePageSeesOnlyTheHeldFinger() {
        val gesture = Gesture()
        gesture.touch(ACTION_DOWN, 0, held)
        gesture.touch(ACTION_POINTER_DOWN, 100, held, right, index = 1)
        // A move of both: the page gets one with the held finger alone, as a move.
        assertEquals(
            SelectionTouch(PageTouch.Only(ACTION_MOVE, 0, listOf(0))),
            gesture.touch(ACTION_MOVE, 120, held.copy(x = 310f), right),
        )
        // The tapping finger may come first in the event; the held one is still picked out.
        assertEquals(
            SelectionTouch(PageTouch.Only(ACTION_MOVE, 0, listOf(0))),
            gesture.touch(ACTION_MOVE, 140, right, held.copy(x = 312f)),
        )
        assertEquals(1, gesture.touch(ACTION_POINTER_UP, 180, right, held, index = 0).turn)
    }

    @Test
    fun severalTapsTurnSeveralPages() {
        val gesture = Gesture()
        assertEquals(1, gesture.tapWhileHeld(right).turn)
        val again = Finger(2, 880f, 1100f)
        assertEquals(
            SelectionTouch(PageTouch.Withheld),
            gesture.touch(ACTION_POINTER_DOWN, 400, held, again, index = 1),
        )
        assertEquals(1, gesture.touch(ACTION_POINTER_UP, 460, held, again, index = 1).turn)
        val back = Finger(1, 100f, 1100f)
        gesture.touch(ACTION_POINTER_DOWN, 700, held, back, index = 1)
        assertEquals(-1, gesture.touch(ACTION_POINTER_UP, 790, held, back, index = 1).turn)
    }

    @Test
    fun aLongSecondTouchIsNotATurn() {
        assertEquals(
            SelectionTouch(PageTouch.Withheld),
            Gesture().tapWhileHeld(right, down = 100, up = 401),
        )
        // Held past the time with moves in between: still none.
        val gesture = Gesture()
        gesture.touch(ACTION_DOWN, 0, held)
        gesture.touch(ACTION_POINTER_DOWN, 100, held, right, index = 1)
        gesture.touch(ACTION_MOVE, 450, held, right)
        assertEquals(0, gesture.touch(ACTION_POINTER_UP, 460, held, right, index = 1).turn)
        // Exactly the limit still counts.
        assertEquals(1, Gesture().tapWhileHeld(right, down = 100, up = 400).turn)
    }

    @Test
    fun aSecondFingerThatMovesIsNotATurn() {
        // Moved past the slop and back: a pinch that came back is still a pinch.
        val gesture = Gesture()
        gesture.touch(ACTION_DOWN, 0, held)
        gesture.touch(ACTION_POINTER_DOWN, 100, held, right, index = 1)
        assertEquals(
            SelectionTouch(PageTouch.Only(ACTION_MOVE, 0, listOf(0))),
            gesture.touch(ACTION_MOVE, 120, held, right.copy(x = right.x - 60f)),
        )
        assertEquals(
            SelectionTouch(PageTouch.Withheld),
            gesture.touch(ACTION_POINTER_UP, 160, held, right, index = 1),
        )
        // Lifted away from where it landed.
        assertEquals(0, Gesture().tapWhileHeld(right, lift = right.copy(y = right.y + 40f)).turn)
        // Within the slop is a tap: a finger never lands still.
        assertEquals(1, Gesture().tapWhileHeld(right, lift = right.copy(x = right.x + 10f)).turn)
    }

    @Test
    fun twoFingersTappingAtOnceTurnNothing() {
        val gesture = Gesture()
        val other = Finger(2, 700f, 1300f)
        gesture.touch(ACTION_DOWN, 0, held)
        gesture.touch(ACTION_POINTER_DOWN, 100, held, right, index = 1)
        assertEquals(
            SelectionTouch(PageTouch.Withheld),
            gesture.touch(ACTION_POINTER_DOWN, 110, held, right, other, index = 2),
        )
        assertEquals(0, gesture.touch(ACTION_POINTER_UP, 150, held, right, other, index = 1).turn)
        assertEquals(0, gesture.touch(ACTION_POINTER_UP, 160, held, other, index = 1).turn)
    }

    @Test
    fun withNothingSelectedEveryFingerIsThePagesAsBefore() {
        val gesture = Gesture(selecting = false)
        assertEquals(SelectionTouch(PageTouch.Whole), gesture.touch(ACTION_DOWN, 0, held))
        assertEquals(
            SelectionTouch(PageTouch.Whole),
            gesture.touch(ACTION_POINTER_DOWN, 100, held, right, index = 1),
        )
        assertEquals(SelectionTouch(PageTouch.Whole), gesture.touch(ACTION_MOVE, 120, held, right))
        assertEquals(
            SelectionTouch(PageTouch.Whole),
            gesture.touch(ACTION_POINTER_UP, 180, held, right, index = 1),
        )
        assertEquals(SelectionTouch(PageTouch.Whole), gesture.touch(ACTION_UP, 200, held))
        // A single tap, selected or not, is the page's: its tap zones keep working.
        val selected = Gesture()
        assertEquals(SelectionTouch(PageTouch.Whole), selected.touch(ACTION_DOWN, 0, right))
        assertEquals(SelectionTouch(PageTouch.Whole), selected.touch(ACTION_UP, 80, right))
    }

    @Test
    fun aPageFingerAddedBesideAKeptOneKeepsItsOwnPlaceInTheEvent() {
        // Selecting when the second finger lands, not when the third does.
        val gesture = Gesture()
        val third = Finger(2, 500f, 500f)
        gesture.touch(ACTION_DOWN, 0, held)
        gesture.touch(ACTION_POINTER_DOWN, 100, held, right, index = 1)
        gesture.selecting = false
        assertEquals(
            SelectionTouch(PageTouch.Only(ACTION_POINTER_DOWN, 1, listOf(0, 2))),
            gesture.touch(ACTION_POINTER_DOWN, 120, held, right, third, index = 2),
        )
        assertEquals(
            SelectionTouch(PageTouch.Only(ACTION_MOVE, 0, listOf(0, 2))),
            gesture.touch(ACTION_MOVE, 130, held, right, third),
        )
        assertEquals(
            SelectionTouch(PageTouch.Only(ACTION_POINTER_UP, 1, listOf(0, 2))),
            gesture.touch(ACTION_POINTER_UP, 140, held, right, third, index = 2),
        )
        // The kept finger was a tap, but two fingers came down with it: no turn.
        assertEquals(0, gesture.touch(ACTION_POINTER_UP, 150, held, right, index = 1).turn)
    }

    @Test
    fun theHeldFingerLiftingFirstEndsThePagesTouchThere() {
        val gesture = Gesture()
        gesture.touch(ACTION_DOWN, 0, held)
        gesture.touch(ACTION_POINTER_DOWN, 100, held, right, index = 1)
        // The page sees its only finger lift: a plain up, never a pointer up for a finger it
        // never saw go down.
        assertEquals(
            SelectionTouch(PageTouch.Only(ACTION_UP, 0, listOf(0))),
            gesture.touch(ACTION_POINTER_UP, 150, held, right, index = 0),
        )
        // Nothing more for the page; the tap still counts, as text is still selected.
        assertEquals(SelectionTouch(PageTouch.Withheld), gesture.touch(ACTION_MOVE, 160, right))
        assertEquals(SelectionTouch(PageTouch.Withheld, 1), gesture.touch(ACTION_UP, 190, right))
        // A finger landing after the page's touch ended is kept from it as well.
        val again = Gesture()
        again.touch(ACTION_DOWN, 0, held)
        again.touch(ACTION_POINTER_DOWN, 100, held, right, index = 1)
        again.touch(ACTION_POINTER_UP, 150, held, right, index = 0)
        val late = Finger(3, 200f, 200f)
        assertEquals(
            SelectionTouch(PageTouch.Withheld),
            again.touch(ACTION_POINTER_DOWN, 170, right, late, index = 1),
        )
        assertEquals(
            SelectionTouch(PageTouch.Withheld),
            again.touch(ACTION_POINTER_UP, 200, right, late, index = 1),
        )
        assertEquals(SelectionTouch(PageTouch.Withheld), again.touch(ACTION_UP, 210, right))
    }

    @Test
    fun aCancelReachesThePageWithItsOwnFingersOnly() {
        val gesture = Gesture()
        gesture.touch(ACTION_DOWN, 0, held)
        gesture.touch(ACTION_POINTER_DOWN, 100, held, right, index = 1)
        assertEquals(
            SelectionTouch(PageTouch.Only(ACTION_CANCEL, 0, listOf(0))),
            gesture.touch(ACTION_CANCEL, 120, held, right),
        )
        // The next touch starts afresh.
        assertEquals(SelectionTouch(PageTouch.Whole), gesture.touch(ACTION_DOWN, 500, held))
        assertEquals(SelectionTouch(PageTouch.Whole), gesture.touch(ACTION_UP, 560, held))
    }

    @Test
    fun aPassageAcrossTwoPagesOfAChapterIsOneHighlight() {
        // The pointers of a selection that runs from one page onto the next: the same chapter.
        val raw =
            fromPage(
                JSONObject()
                    .put("pos0", "/body/DocFragment[3]/body/p[4]/text().12")
                    .put("pos1", "/body/DocFragment[3]/body/p[9]/text().20")
                    .put("text", "the end of one page and the start of the next")
            )
        // DocFragment[3] is the chapter with KOReader index 2.
        val passage = parseSelection(raw, 2, 5)
        assertNotNull(passage)
        val row = phoneAnnotation("b1", passage!!, "", "Chapter two", "2026-10-17 10:00:00")
        assertEquals(listOf(2), placeableHighlights(listOf(row), 5).map { it.chapter })
    }
}
