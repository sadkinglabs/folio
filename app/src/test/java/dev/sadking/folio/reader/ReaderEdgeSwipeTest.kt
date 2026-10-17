package dev.sadking.folio.reader

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test

/** Edge slides for warmth and brightness, on the JVM: where they start, when they are claimed. */
class ReaderEdgeSwipeTest {
    private val width = 1080f
    private val height = 2400f
    private val strip = 84f
    private val slop = 24f

    private fun tracker() = EdgeSwipeTracker(strip, slop, claimWithinMs = 400)

    private fun EdgeSwipeTracker.touch(
        action: Int,
        x: Float,
        y: Float,
        time: Long = 0,
        pointers: Int = 1,
        enabled: Boolean = true,
    ) = onTouch(action, x, y, width, time, pointers, enabled)

    @Test
    fun onlyTheStripsAlongTheSidesStartASlide() {
        assertEquals(EdgeSide.LEFT, edgeSide(0f, width, strip))
        assertEquals(EdgeSide.LEFT, edgeSide(strip, width, strip))
        assertNull(edgeSide(strip + 1, width, strip))
        assertNull(edgeSide(width / 2, width, strip))
        assertNull(edgeSide(width - strip - 1, width, strip))
        assertEquals(EdgeSide.RIGHT, edgeSide(width - strip, width, strip))
        assertEquals(EdgeSide.RIGHT, edgeSide(width, width, strip))
        // No page, or strips that would meet in the middle: nothing.
        assertNull(edgeSide(0f, 0f, strip))
        assertNull(edgeSide(0f, 100f, 60f))
    }

    @Test
    fun aSlideUpTheRightEdgeIsClaimedAndFollowed() {
        val edge = tracker()
        assertEquals(EdgeStep.PASS, edge.touch(MotionEvent.ACTION_DOWN, width - 10, 1200f))
        // Still within the slop: the page keeps it for now.
        assertEquals(EdgeStep.PASS, edge.touch(MotionEvent.ACTION_MOVE, width - 12, 1190f, 30))
        assertEquals(EdgeStep.CLAIM, edge.touch(MotionEvent.ACTION_MOVE, width - 14, 1160f, 60))
        assertEquals(EdgeSide.RIGHT, edge.side)
        assertEquals(0f, edge.travel, 0f)
        // Up is positive, measured from where the slide was claimed.
        assertEquals(EdgeStep.DRAG, edge.touch(MotionEvent.ACTION_MOVE, width - 14, 960f, 90))
        assertEquals(200f, edge.travel, 0f)
        assertEquals(EdgeStep.DRAG, edge.touch(MotionEvent.ACTION_MOVE, width - 14, 1260f, 120))
        assertEquals(-100f, edge.travel, 0f)
        assertEquals(EdgeStep.END, edge.touch(MotionEvent.ACTION_UP, width - 14, 1260f, 150))
        // The next touch starts afresh.
        assertEquals(EdgeStep.PASS, edge.touch(MotionEvent.ACTION_DOWN, width / 2, 1200f, 400))
        assertEquals(EdgeStep.PASS, edge.touch(MotionEvent.ACTION_MOVE, width / 2, 900f, 430))
    }

    @Test
    fun theLeftEdgeIsWarmth() {
        val edge = tracker()
        edge.touch(MotionEvent.ACTION_DOWN, 10f, 1200f)
        assertEquals(EdgeStep.CLAIM, edge.touch(MotionEvent.ACTION_MOVE, 12f, 1240f, 40))
        assertEquals(EdgeSide.LEFT, edge.side)
    }

    @Test
    fun tapsSidewaysSwipesAndLongPressesStayThePages() {
        val edge = tracker()
        // A tap at the edge turns the page as before.
        assertEquals(EdgeStep.PASS, edge.touch(MotionEvent.ACTION_DOWN, 10f, 1200f))
        assertEquals(EdgeStep.PASS, edge.touch(MotionEvent.ACTION_UP, 10f, 1200f, 80))
        // A sideways swipe (a page turn, or the system's Back) is never claimed, even if it
        // later drifts up or down.
        edge.touch(MotionEvent.ACTION_DOWN, 10f, 1200f)
        assertEquals(EdgeStep.PASS, edge.touch(MotionEvent.ACTION_MOVE, 60f, 1205f, 30))
        assertEquals(EdgeStep.PASS, edge.touch(MotionEvent.ACTION_MOVE, 70f, 1000f, 60))
        assertEquals(EdgeStep.PASS, edge.touch(MotionEvent.ACTION_UP, 70f, 1000f, 90))
        // A diagonal move is not clearly vertical.
        edge.touch(MotionEvent.ACTION_DOWN, width - 10, 1200f)
        assertEquals(EdgeStep.PASS, edge.touch(MotionEvent.ACTION_MOVE, width - 30, 1170f, 30))
        assertEquals(EdgeStep.PASS, edge.touch(MotionEvent.ACTION_MOVE, width - 30, 1000f, 60))
        // A long press at the edge selects text: once held, a later slide is the selection's.
        edge.touch(MotionEvent.ACTION_DOWN, 10f, 1200f)
        assertEquals(EdgeStep.PASS, edge.touch(MotionEvent.ACTION_MOVE, 11f, 1201f, 500))
        assertEquals(EdgeStep.PASS, edge.touch(MotionEvent.ACTION_MOVE, 11f, 900f, 600))
    }

    @Test
    fun theMiddleTwoFingersAndTurningItOffAreLeftAlone() {
        val edge = tracker()
        edge.touch(MotionEvent.ACTION_DOWN, width / 2, 1200f)
        assertEquals(EdgeStep.PASS, edge.touch(MotionEvent.ACTION_MOVE, width / 2, 900f, 30))
        edge.touch(MotionEvent.ACTION_DOWN, 10f, 1200f, enabled = false)
        assertEquals(EdgeStep.PASS, edge.touch(MotionEvent.ACTION_MOVE, 10f, 900f, 30))
        edge.touch(MotionEvent.ACTION_DOWN, 10f, 1200f)
        edge.touch(MotionEvent.ACTION_POINTER_DOWN, 10f, 1200f, 10, pointers = 2)
        assertEquals(
            EdgeStep.PASS,
            edge.touch(MotionEvent.ACTION_MOVE, 10f, 900f, 30, pointers = 2),
        )
    }

    @Test
    fun theSystemTakingTheTouchEndsTheSlide() {
        val edge = tracker()
        edge.touch(MotionEvent.ACTION_DOWN, 10f, 1200f)
        edge.touch(MotionEvent.ACTION_MOVE, 10f, 1100f, 30)
        assertEquals(EdgeStep.END, edge.touch(MotionEvent.ACTION_CANCEL, 10f, 1100f, 60))
    }

    @Test
    fun upIsMoreAndLevelsStayInRange() {
        // 60% of the page's height covers the whole range.
        val span = height * EDGE_SWIPE_SPAN
        assertEquals(0.75f, edgeLevel(0.5f, span / 4, height), 0.0001f)
        assertEquals(0.25f, edgeLevel(0.5f, -span / 4, height), 0.0001f)
        assertEquals(1f, edgeLevel(0.5f, span, height), 0f)
        assertEquals(0f, edgeLevel(0.5f, -span, height), 0f)
        assertEquals(0.1f, edgeLevel(0.5f, -span, height, min = 0.1f), 0f)
        // No page height: nothing moves.
        assertEquals(0.5f, edgeLevel(0.5f, 500f, 0f), 0f)
    }

    @Test
    fun theLeftSetsWarmthAndTheRightBrightnessLeavingAuto() {
        val start =
            ReaderComfort(
                autoBrightness = true,
                brightness = 0.4f,
                warmth = 0.2f,
                leftHanded = true,
            )
        val span = height * EDGE_SWIPE_SPAN
        val warmer = edgeSwiped(start, EdgeSide.LEFT, span / 2, height)
        assertEquals(0.7f, warmer.warmth, 0.0001f)
        // Brightness is untouched, still the phone's own.
        assertTrue(warmer.autoBrightness)
        assertEquals(0.4f, warmer.brightness, 0f)
        assertTrue(warmer.leftHanded)

        val brighter = edgeSwiped(start, EdgeSide.RIGHT, span / 10, height)
        assertFalse(brighter.autoBrightness)
        assertEquals(0.5f, brighter.brightness, 0.0001f)
        assertEquals(0.2f, brighter.warmth, 0f)
        // Down to the bottom: never darker than the reader allows.
        val darkest = edgeSwiped(start, EdgeSide.RIGHT, -span * 2, height)
        assertEquals(ReaderComfort.MIN_BRIGHTNESS, darkest.brightness, 0f)
        val coolest = edgeSwiped(start, EdgeSide.LEFT, -span * 2, height)
        assertEquals(0f, coolest.warmth, 0f)
    }
}
