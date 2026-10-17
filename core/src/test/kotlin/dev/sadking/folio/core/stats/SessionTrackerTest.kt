package dev.sadking.folio.core.stats

import org.junit.Assert.*
import org.junit.Test

class SessionTrackerTest {
    private var next = 0

    private fun tracker(source: SessionSource = SessionSource.PHONE) =
        SessionTracker(source, "phone:me", { "s${next++}" })

    private fun s(seconds: Int) = seconds * 1000L

    @Test
    fun pagesCountTheirTimeAndTheReadingDone() {
        val t = tracker()
        assertNull(t.start("book", 0, 0.10))
        t.mark(s(30), 0.11)
        t.mark(s(60), 0.12)
        val end = t.stop(s(90))!!
        assertEquals("s0", end.id)
        assertEquals(SessionSource.PHONE, end.source)
        assertEquals("book", end.bookId)
        assertEquals(0L, end.startMs)
        assertEquals(90, end.durationSec)
        assertEquals(0.10, end.progressStart!!, 0.0)
        assertEquals(0.12, end.progressEnd!!, 0.0)
        assertEquals(0.02, end.progressRead!!, 1e-9)
    }

    @Test
    fun aPageCountsTwoMinutesAtMost() {
        val t = tracker()
        t.start("book", 0, 0.1)
        // Six minutes on one page: 2 count, and 4 is not yet a gap of more than 5.
        val same = t.mark(s(360), 0.11)!!
        assertEquals("s0", same.id)
        assertEquals(120, same.durationSec)
    }

    @Test
    fun aPageLeftAloneEndsTheSession() {
        val t = tracker()
        t.start("book", 0, 0.1)
        t.mark(s(30), 0.11)
        // Ten minutes later: the first session ends with 2 minutes on that page.
        val ended = t.mark(s(630), 0.12)!!
        assertEquals("s0", ended.id)
        assertEquals(150, ended.durationSec)
        val fresh = t.mark(s(660), 0.13)!!
        assertEquals("s1", fresh.id)
        assertEquals(s(630), fresh.startMs)
        assertEquals(30, fresh.durationSec)
        assertEquals(0.12, fresh.progressStart!!, 0.0)
    }

    @Test
    fun jumpsBackwardMovesAndBigStepsAreNotReading() {
        val t = tracker()
        t.start("book", 0, 0.10)
        t.mark(s(30), 0.30, jump = true)
        t.mark(s(60), 0.29)
        t.mark(s(90), 0.40)
        val end = t.mark(s(120), 0.41)!!
        assertEquals(0.01, end.progressRead!!, 1e-9)
        assertEquals(0.41, end.progressEnd!!, 0.0)
    }

    @Test
    fun noForwardStepLeavesProgressReadUnknown() {
        val t = tracker()
        t.start("book", 0, 0.5)
        assertNull(t.stop(s(30))!!.progressRead)
    }

    @Test
    fun comingBackWithinFiveMinutesContinuesTheSession() {
        val t = tracker()
        t.start("book", 0, 0.1)
        t.stop(s(60))
        assertNull(t.start("book", s(60 + 240), 0.1))
        val end = t.stop(s(60 + 240 + 30))!!
        assertEquals("s0", end.id)
        assertEquals(90, end.durationSec)
    }

    @Test
    fun comingBackLaterStartsANewSession() {
        val t = tracker()
        t.start("book", 0, 0.1)
        t.stop(s(60))
        assertNull(t.start("book", s(60 + 301), 0.1))
        assertEquals("s1", t.stop(s(60 + 301 + 30))!!.id)
    }

    @Test
    fun anotherBookEndsTheRunningSession() {
        val t = tracker()
        t.start("one", 0, 0.1)
        val ended = t.start("two", s(40), 0.5)!!
        assertEquals("one", ended.bookId)
        assertEquals(40, ended.durationSec)
        assertEquals("two", t.stop(s(70))!!.bookId)
    }

    @Test
    fun underASecondIsNothingToSave() {
        val t = tracker()
        t.start("book", 0, 0.1)
        assertNull(t.stop(500))
        assertNull(t.mark(s(10), 0.2))
    }

    @Test
    fun listeningCountsAllPlayingTime() {
        val t = tracker(SessionSource.LISTEN)
        t.start("book", 0, 0.2)
        t.mark(s(400), 0.21)
        val end = t.stop(s(1000))!!
        assertEquals(SessionSource.LISTEN, end.source)
        assertEquals(1000, end.durationSec)
        assertEquals("s0", end.id)
        assertEquals(0.01, end.progressRead!!, 1e-9)
    }
}
