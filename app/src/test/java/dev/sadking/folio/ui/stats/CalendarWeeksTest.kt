package dev.sadking.folio.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarWeeksTest {
    private fun month(firstWeekday: Int, vararg days: List<DayBar>) =
        CalendarMonth(
            "October 2026",
            firstWeekday,
            days.mapIndexed { i, bars -> CalendarDay(i + 1, bars) },
            emptyList(),
            null,
        )

    private fun read(book: String, slot: Int = 0, listening: Boolean = false) =
        listOf(DayBar(book, slot, 600, listening))

    @Test
    fun consecutiveDaysAreOneRun() {
        val weeks =
            calendarWeeks(month(0, read("a"), read("a"), read("a"), emptyList(), read("a")), 3)
        val runs = weeks[0].runs
        assertEquals(listOf(0 to 2, 4 to 4), runs.map { it.start to it.end })
        assertEquals(listOf(false, false), runs.map { it.continuesAfter })
    }

    @Test
    fun aRunAcrossTheWeekGoesOnSquareInTheSameLane() {
        // The 1st is a Saturday: days 1 to 3 cross from the first row into the second.
        val weeks = calendarWeeks(month(5, read("b", 1), read("a") + read("b", 1), read("a")), 3)
        val endOfWeek = weeks[0].runs.single { it.bookId == "a" }
        val nextWeek = weeks[1].runs.single { it.bookId == "a" }
        assertEquals(true, endOfWeek.continuesAfter)
        assertEquals(true, nextWeek.continuesBefore)
        assertEquals(endOfWeek.lane, nextWeek.lane)
    }

    @Test
    fun aBookOnlyListenedToIsHollowOnThoseDays() {
        val weeks = calendarWeeks(month(0, read("a"), read("a", listening = true), read("a")), 3)
        assertEquals(listOf(false, true, false), weeks[0].runs.single().listeningOnly)
    }

    @Test
    fun booksBeyondTheLanesAreCountedPerDay() {
        val day = read("a") + read("b", 1) + read("c", 2) + read("d", 3)
        val weeks = calendarWeeks(month(0, day, read("a")), 3)
        assertEquals(3, weeks[0].runs.size)
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 0), weeks[0].hidden)
        // The longer run is placed first.
        assertEquals("a", weeks[0].runs.single { it.lane == 0 }.bookId)
    }
}
