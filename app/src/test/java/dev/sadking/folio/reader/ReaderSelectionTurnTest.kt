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
 * Selecting across a page turn, on the JVM: which of the other hand's touches turn the page under a
 * selection, and what the page gets of them. The other hand comes either as a second finger in the
 * held finger's touch, or as a touch of its own (the held finger is on a selection handle, a window
 * of its own).
 */
class ReaderSelectionTurnTest {
    private fun fromPage(value: Any?): String = JSONObject.quote(value.toString())

    /** One finger at a point, by id. */
    private data class Finger(val id: Int, val x: Float, val y: Float)

    /**
     * A page 1080 pixels wide with Readium's edge zones: a tap at x 756 or more goes forward, at
     * 324 or less back. Slop 24, slides from 100 pixels, taps up to 300 ms.
     *
     * [selecting] is whether text is selected when a further finger lands. [selectedAtDown] is
     * whether it is when a touch starts: false when the held finger's own long press makes the
     * selection, true when the selection was there before this touch (the held finger is on a
     * handle, in a window of its own).
     */
    private class Gesture(
        var rules: TurnRules = TurnRules(left = 0f, width = 1080f),
        var selecting: Boolean = true,
        var selectedAtDown: Boolean = false,
    ) {
        val taps = SelectionTaps(slop = 24f, swipe = 100f, tapMs = 300)

        constructor(rtl: Boolean) : this(TurnRules(left = 0f, width = 1080f, rtl = rtl))

        fun touch(action: Int, time: Long, vararg fingers: Finger, index: Int = 0) =
            taps.onTouch(
                action,
                index,
                fingers.map { it.id }.toIntArray(),
                fingers.map { it.x }.toFloatArray(),
                fingers.map { it.y }.toFloatArray(),
                time,
                rules,
                if (action == ACTION_DOWN) selectedAtDown else selecting,
            )
    }

    private val held = Finger(0, 300f, 900f)
    private val right = Finger(1, 900f, 1200f)
    private val left = Finger(1, 150f, 1200f)
    private val middle = Finger(1, 540f, 1200f)

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

    /** A held finger, and a second finger sliding from [from] to [to]; the second's lift. */
    private fun Gesture.slideWhileHeld(from: Finger, to: Finger, steps: Int = 4): SelectionTouch {
        touch(ACTION_DOWN, 0, held)
        touch(ACTION_POINTER_DOWN, 100, held, from, index = 1)
        for (step in 1..steps) {
            val x = from.x + (to.x - from.x) * step / steps
            val y = from.y + (to.y - from.y) * step / steps
            assertEquals(
                SelectionTouch(PageTouch.Only(ACTION_MOVE, 0, listOf(0))),
                touch(ACTION_MOVE, 100L + step * 20, held, Finger(from.id, x, y)),
            )
        }
        return touch(ACTION_POINTER_UP, 100L + steps * 20 + 10, held, to, index = 1)
    }

    /** A touch of its own, [tap], down at [down] and up at [up] while text is selected. */
    private fun Gesture.otherHandTap(
        tap: Finger,
        down: Long = 1_000,
        up: Long = 1_080,
        lift: Finger = tap,
    ): SelectionTouch {
        selectedAtDown = true
        val first = touch(ACTION_DOWN, down, tap)
        assertEquals(SelectionTouch(PageTouch.Held), first)
        assertEquals("other hand: held back", first.note)
        assertTrue(taps.holding)
        return touch(ACTION_UP, up, lift)
    }

    /** A touch of its own sliding from [from] to [to] in [steps] moves, then lifting. */
    private fun Gesture.otherHandSlide(from: Finger, to: Finger, steps: Int = 4): SelectionTouch {
        selectedAtDown = true
        assertEquals(SelectionTouch(PageTouch.Held), touch(ACTION_DOWN, 1_000, from))
        for (step in 1..steps) {
            val x = from.x + (to.x - from.x) * step / steps
            val y = from.y + (to.y - from.y) * step / steps
            assertEquals(
                SelectionTouch(PageTouch.Held),
                touch(ACTION_MOVE, 1_000L + step * 20, Finger(from.id, x, y)),
            )
        }
        return touch(ACTION_UP, 1_000L + steps * 20 + 10, to)
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

    // A second finger in the held finger's touch.

    @Test
    fun aQuickSecondFingerTapTurnsForwardAndNeverReachesThePage() {
        val gesture = Gesture()
        val lifted = gesture.tapWhileHeld(right)
        assertEquals(SelectionTouch(PageTouch.Withheld, turn = 1), lifted)
        assertEquals("second-finger tap: next page", lifted.note)
        // The held finger goes on dragging on the new page: the page gets it as it came.
        assertEquals(
            SelectionTouch(PageTouch.Whole),
            gesture.touch(ACTION_MOVE, 260, held.copy(x = 320f)),
        )
        assertEquals(SelectionTouch(PageTouch.Whole), gesture.touch(ACTION_UP, 400, held))
    }

    @Test
    fun aSecondFingerOnTheOtherSideGoesBackAndRightToLeftBooksSwapThem() {
        assertEquals(-1, Gesture().tapWhileHeld(left).turn)
        assertEquals(-1, Gesture(rtl = true).tapWhileHeld(right).turn)
        assertEquals(1, Gesture(rtl = true).tapWhileHeld(left).turn)
    }

    @Test
    fun aSecondFingerFollowsTheOwnersTapZones() {
        // In the middle the page's tap would show the controls: the second finger turns nothing,
        // and the page still never sees it.
        val middleTap = Gesture().tapWhileHeld(middle)
        assertEquals(SelectionTouch(PageTouch.Withheld), middleTap)
        assertEquals("second finger: no turn, tap in the middle", middleTap.note)
        // x 700 is in the middle with edge zones, and on the right half with halves.
        val inner = Finger(1, 700f, 1200f)
        assertEquals(0, Gesture().tapWhileHeld(inner).turn)
        val halves = Gesture(TurnRules(0f, 1080f, zones = TapZones.HALVES))
        assertEquals(1, halves.tapWhileHeld(inner).turn)
        // Left-handed swaps the sides; a right-to-left book swaps them back.
        assertEquals(-1, Gesture(TurnRules(0f, 1080f, leftHanded = true)).tapWhileHeld(right).turn)
        assertEquals(
            1,
            Gesture(TurnRules(0f, 1080f, leftHanded = true, rtl = true)).tapWhileHeld(right).turn,
        )
        // Zones are measured on the page, wherever it sits in the window: x 1100 is near the
        // right edge of a page that starts at 200.
        val shifted = Gesture(TurnRules(left = 200f, width = 1080f))
        assertEquals(0, shifted.tapWhileHeld(Finger(1, 900f, 1200f)).turn)
        assertEquals(1, Gesture(TurnRules(200f, 1080f)).tapWhileHeld(Finger(1, 1100f, 1200f)).turn)
    }

    @Test
    fun aSecondFingerSlidingSidewaysTurnsTheWayThePageWouldFollow() {
        // Leftwards brings the next page in a left-to-right book.
        val leftwards = Gesture().slideWhileHeld(middle, middle.copy(x = middle.x - 200f))
        assertEquals(SelectionTouch(PageTouch.Withheld, turn = 1), leftwards)
        assertEquals("second-finger slide: next page", leftwards.note)
        assertEquals(-1, Gesture().slideWhileHeld(middle, middle.copy(x = middle.x + 200f)).turn)
        // A right-to-left book reads the other way.
        assertEquals(
            -1,
            Gesture(rtl = true).slideWhileHeld(middle, middle.copy(x = middle.x - 200f)).turn,
        )
        assertEquals(
            1,
            Gesture(rtl = true).slideWhileHeld(middle, middle.copy(x = middle.x + 200f)).turn,
        )
        // Too short, or more up and down than sideways: no turn.
        assertEquals(0, Gesture().slideWhileHeld(middle, middle.copy(x = middle.x - 60f)).turn)
        assertEquals(
            0,
            Gesture().slideWhileHeld(middle, Finger(1, middle.x - 150f, middle.y + 200f)).turn,
        )
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
    fun aSecondFingerThatMovesAndComesBackIsNotATurn() {
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
        // Lifted away from where it landed, up or down.
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
        // A single tap with nothing selected is the page's: its tap zones keep working.
        assertEquals(SelectionTouch(PageTouch.Whole), gesture.touch(ACTION_DOWN, 300, right))
        assertFalse(gesture.taps.holding)
        assertEquals(SelectionTouch(PageTouch.Whole), gesture.touch(ACTION_UP, 380, right))
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

    // A touch of its own: the held finger is on a selection handle, in its own window.

    @Test
    fun anOtherHandTapIsHeldBackAndTurnsForward() {
        val gesture = Gesture()
        val lifted = gesture.otherHandTap(right)
        assertEquals(SelectionTouch(PageTouch.Withheld, turn = 1), lifted)
        assertEquals("other-hand tap: next page", lifted.note)
        assertFalse(gesture.taps.holding)
        // Each tap turns one more page.
        assertEquals(1, gesture.otherHandTap(right, down = 1_500, up = 1_560).turn)
        assertEquals(-1, gesture.otherHandTap(left, down = 2_000, up = 2_090).turn)
    }

    @Test
    fun anOtherHandTapFollowsTheTapZonesAndRightToLeft() {
        val back = Gesture().otherHandTap(left)
        assertEquals(SelectionTouch(PageTouch.Withheld, turn = -1), back)
        assertEquals("other-hand tap: previous page", back.note)
        assertEquals(-1, Gesture(rtl = true).otherHandTap(right).turn)
        assertEquals(1, Gesture(rtl = true).otherHandTap(left).turn)
        assertEquals(-1, Gesture(TurnRules(0f, 1080f, leftHanded = true)).otherHandTap(right).turn)
        val inner = Finger(0, 700f, 1200f)
        assertEquals(1, Gesture(TurnRules(0f, 1080f, TapZones.HALVES)).otherHandTap(inner).turn)
        // Exactly the limit is still a tap; a little drift within the slop too.
        assertEquals(1, Gesture().otherHandTap(right, down = 1_000, up = 1_300).turn)
        assertEquals(1, Gesture().otherHandTap(right, lift = right.copy(x = right.x - 15f)).turn)
    }

    @Test
    fun anOtherHandTapInTheMiddleIsReplayedSoItStillClearsTheSelection() {
        val gesture = Gesture()
        val lifted = gesture.otherHandTap(middle)
        assertEquals(SelectionTouch(PageTouch.Replay), lifted)
        assertEquals("held back, replayed: tap in the middle", lifted.note)
        assertFalse(gesture.taps.holding)
        // The touch is over: the next one is classified afresh.
        gesture.selectedAtDown = false
        assertEquals(SelectionTouch(PageTouch.Whole), gesture.touch(ACTION_DOWN, 2_000, middle))
    }

    @Test
    fun anOtherHandSlideTurnsTheWayThePageWouldFollow() {
        val leftwards = Gesture().otherHandSlide(middle, middle.copy(x = middle.x - 250f))
        assertEquals(SelectionTouch(PageTouch.Withheld, turn = 1), leftwards)
        assertEquals("other-hand slide: next page", leftwards.note)
        val rightwards = Gesture().otherHandSlide(middle, middle.copy(x = middle.x + 250f))
        assertEquals(-1, rightwards.turn)
        assertEquals("other-hand slide: previous page", rightwards.note)
        // In a right-to-left book the page follows the other way.
        assertEquals(
            -1,
            Gesture(rtl = true).otherHandSlide(middle, middle.copy(x = middle.x - 250f)).turn,
        )
        assertEquals(
            1,
            Gesture(rtl = true).otherHandSlide(middle, middle.copy(x = middle.x + 250f)).turn,
        )
        // A slide starting in a tap zone is still a slide: it goes the slide's way.
        assertEquals(-1, Gesture().otherHandSlide(right, right.copy(x = 1_070f, y = 1_230f)).turn)
        // A slow slide is still a slide.
        val slow = Gesture(selectedAtDown = true)
        slow.touch(ACTION_DOWN, 1_000, middle)
        assertEquals(
            SelectionTouch(PageTouch.Held),
            slow.touch(ACTION_MOVE, 1_100, middle.copy(x = middle.x - 40f)),
        )
        assertNull(slow.taps.expire(1_400))
        assertEquals(
            SelectionTouch(PageTouch.Held),
            slow.touch(ACTION_MOVE, 1_600, middle.copy(x = middle.x - 200f)),
        )
        assertEquals(1, slow.touch(ACTION_UP, 1_700, middle.copy(x = middle.x - 200f)).turn)
    }

    @Test
    fun anOtherHandSlideTooShortOrTurningUpIsReplayed() {
        // Sideways past the slop but short of a slide: the page gets all of it at the lift.
        val short = Gesture().otherHandSlide(middle, middle.copy(x = middle.x - 60f))
        assertEquals(SelectionTouch(PageTouch.Replay), short)
        assertEquals("held back, replayed: neither tap nor slide", short.note)
        // Sideways at first, then mostly down by the lift.
        val bent = Gesture(selectedAtDown = true)
        bent.touch(ACTION_DOWN, 1_000, middle)
        assertEquals(
            SelectionTouch(PageTouch.Held),
            bent.touch(ACTION_MOVE, 1_020, middle.copy(x = middle.x - 40f)),
        )
        assertEquals(
            SelectionTouch(PageTouch.Replay),
            bent.touch(ACTION_UP, 1_100, Finger(0, middle.x - 120f, middle.y + 300f)),
        )
    }

    @Test
    fun anOtherHandDragUpOrDownIsReplayedAtOnceAndThenPassesThrough() {
        val gesture = Gesture(selectedAtDown = true)
        gesture.touch(ACTION_DOWN, 1_000, middle)
        assertEquals(
            SelectionTouch(PageTouch.Held),
            gesture.touch(ACTION_MOVE, 1_010, middle.copy(y = middle.y + 10f)),
        )
        val replayed = gesture.touch(ACTION_MOVE, 1_030, middle.copy(y = middle.y + 60f))
        assertEquals(SelectionTouch(PageTouch.Replay), replayed)
        assertEquals("held back, replayed: drag up or down", replayed.note)
        assertEquals(
            SelectionTouch(PageTouch.Whole),
            gesture.touch(ACTION_MOVE, 1_050, middle.copy(x = middle.x - 300f)),
        )
        assertEquals(
            SelectionTouch(PageTouch.Whole),
            gesture.touch(ACTION_UP, 1_070, middle.copy(x = middle.x - 300f)),
        )
    }

    @Test
    fun anOtherHandLongTouchIsReplayedSoItCanStillSelect() {
        // Still and silent: the timer gives it to the page once the tap time is over.
        val still = Gesture(selectedAtDown = true)
        still.touch(ACTION_DOWN, 1_000, right)
        assertNull(still.taps.expire(1_250))
        val expired = still.taps.expire(1_300)
        assertEquals(SelectionTouch(PageTouch.Replay), expired)
        assertEquals("held back, replayed: long touch", expired!!.note)
        assertNull(still.taps.expire(1_400))
        assertEquals(SelectionTouch(PageTouch.Whole), still.touch(ACTION_MOVE, 1_500, right))
        assertEquals(SelectionTouch(PageTouch.Whole), still.touch(ACTION_UP, 1_900, right))
        // A move in place after the tap time does the same.
        val resting = Gesture(selectedAtDown = true)
        resting.touch(ACTION_DOWN, 1_000, right)
        assertEquals(SelectionTouch(PageTouch.Held), resting.touch(ACTION_MOVE, 1_200, right))
        assertEquals(SelectionTouch(PageTouch.Replay), resting.touch(ACTION_MOVE, 1_350, right))
        // Lifted late before either: replayed with its lift, no turn.
        val late = Gesture().otherHandTap(right, down = 1_000, up = 1_301)
        assertEquals(SelectionTouch(PageTouch.Replay), late)
        assertEquals("held back, replayed: long touch", late.note)
    }

    @Test
    fun aSecondFingerJoiningAHeldTouchReplaysItAll() {
        val gesture = Gesture(selectedAtDown = true)
        gesture.touch(ACTION_DOWN, 1_000, middle)
        val joined =
            gesture.touch(
                ACTION_POINTER_DOWN,
                1_050,
                middle,
                Finger(1, 200f, 400f),
                index = 1,
            )
        assertEquals(SelectionTouch(PageTouch.Replay), joined)
        assertEquals("held back, replayed: a second finger joined", joined.note)
        // A pinch now, the page's as it comes.
        assertEquals(
            SelectionTouch(PageTouch.Whole),
            gesture.touch(ACTION_MOVE, 1_080, middle, Finger(1, 150f, 350f)),
        )
        assertEquals(
            SelectionTouch(PageTouch.Whole),
            gesture.touch(ACTION_POINTER_UP, 1_100, middle, Finger(1, 150f, 350f), index = 1),
        )
        assertEquals(SelectionTouch(PageTouch.Whole), gesture.touch(ACTION_UP, 1_120, middle))
    }

    @Test
    fun aHeldTouchThatIsCancelledNeverReachesThePage() {
        val gesture = Gesture(selectedAtDown = true)
        gesture.touch(ACTION_DOWN, 1_000, right)
        val cancelled = gesture.touch(ACTION_CANCEL, 1_040, right)
        assertEquals(SelectionTouch(PageTouch.Withheld), cancelled)
        assertEquals("held back, cancelled", cancelled.note)
        assertFalse(gesture.taps.holding)
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
