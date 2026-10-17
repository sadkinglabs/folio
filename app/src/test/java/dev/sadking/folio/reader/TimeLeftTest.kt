package dev.sadking.folio.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TimeLeftTest {
    private val resources = RuntimeEnvironment.getApplication().resources

    @Test
    fun minutesRoundUpAndCountTheCurrentPosition() {
        // Positions 101..340 at 1.2 a minute: 240 / 1.2 = 200 minutes.
        assertEquals(TimeLeft(null, 200), timeLeft(101, 340, null, 1.2))
        // 11 positions to the chapter's end at 2 a minute: 5.5, rounded up.
        assertEquals(TimeLeft(6, 120), timeLeft(101, 340, 111, 2.0))
    }

    @Test
    fun theLastPageStillShowsAMinute() {
        assertEquals(TimeLeft(1, 1), timeLeft(340, 340, 340, 1.2))
        // Out-of-range input is held to the book.
        assertEquals(TimeLeft(1, 1), timeLeft(400, 340, 340, 1.2))
        assertEquals(TimeLeft(null, 1), timeLeft(1, 1, null, 50.0))
        // A broken speed falls back to one position a minute.
        assertEquals(TimeLeft(null, 10), timeLeft(1, 10, null, 0.0))
    }

    @Test
    fun theChapterEndsWhereTheNextStarts() {
        // Chapters start at positions 1, 11 and 31 (0-based 0, 10, 30) of 50.
        val starts = listOf(0, 10, 30)
        assertEquals(10, chapterEndAt(1, starts, 50))
        assertEquals(10, chapterEndAt(10, starts, 50))
        assertEquals(30, chapterEndAt(11, starts, 50))
        assertEquals(50, chapterEndAt(31, starts, 50))
        // An empty chapter (two items starting at the same place) is skipped.
        assertEquals(30, chapterEndAt(11, listOf(0, 10, 10, 30), 50))
        assertNull(chapterEndAt(5, emptyList(), 50))
    }

    @Test
    fun durationsReadPlainly() {
        assertEquals("1 min", durationText(0, resources))
        assertEquals("1 min", durationText(1, resources))
        assertEquals("59 min", durationText(59, resources))
        assertEquals("1 h", durationText(60, resources))
        assertEquals("4 h 10 min", durationText(250, resources))
    }

    @Test
    fun theLineNamesTheChapterAndTheBook() {
        assertEquals(
            "12 min left in chapter · 4 h 10 min in book",
            timeLeftText(TimeLeft(12, 250), resources),
        )
        // PDFs and comics: the book part only.
        assertEquals("2 h left in book", timeLeftText(TimeLeft(null, 120), resources))
    }
}
