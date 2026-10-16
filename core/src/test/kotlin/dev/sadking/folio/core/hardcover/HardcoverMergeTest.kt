package dev.sadking.folio.core.hardcover

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardcoverMergeTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 16)
    private val now = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private val id = 42
    private val item = "book-1"
    private val title = "Leviathan Wakes"

    private fun plan(
        word: String,
        statusAt: Long,
        remote: Int?,
        updatedAt: Long? = null,
        baseline: HardcoverBaseline? = null,
        userBookId: Int = 7,
        zone: ZoneId = utc,
    ): MergeStep? =
        HardcoverMerge.plan(
            MergeInput(
                id,
                LocalSide(item, title, word, statusAt),
                remote?.let { RemoteSide(userBookId, it, updatedAt) },
                baseline,
            ),
            zone,
            today,
            now,
        )

    private fun up(status: Int, statusAt: Long, userBookId: Int? = null, on: LocalDate? = null) =
        MergeStep.Up(id, item, status, userBookId, on, statusAt)

    private fun down(status: Int, word: String, at: Long) =
        MergeStep.Down(id, item, status, word, at)

    private fun ask(hardcover: Int, word: String) = MergeStep.Ask(id, item, title, hardcover, word)

    // Unknown Hardcover statuses.

    @Test
    fun unknownHardcoverStatusIsLeftAloneBothWays() {
        assertNull(plan("Reading", now, remote = 6))
        assertNull(plan("Reading", now, remote = 0))
        assertNull(plan("Reading", now, remote = 9, baseline = HardcoverBaseline(1, 0)))
        assertNull(plan("Unread", 0, remote = -1, baseline = HardcoverBaseline(2, 0)))
    }

    // First time: no baseline.

    @Test
    fun firstTimeNothingOnEitherSideDoesNothing() {
        assertNull(plan("Unread", 0, remote = null))
        assertNull(plan("", 0, remote = null))
    }

    @Test
    fun firstTimeUnreadTakesHardcover() {
        assertEquals(down(2, "Reading", now - hour), plan("Unread", now, 2, updatedAt = now - hour))
        assertEquals(down(5, "Abandoned", now), plan("", 0, 5, updatedAt = null))
    }

    @Test
    fun firstTimeAbsentOnHardcoverGoesUpAsANewBook() {
        assertEquals(up(1, now - hour), plan("Want to Read", now - hour, remote = null))
        assertEquals(up(2, 0), plan("Reading", 0, remote = null))
        assertEquals(up(4, 5), plan("Paused", 5, remote = null))
        assertEquals(up(5, 5), plan("Abandoned", 5, remote = null))
    }

    @Test
    fun firstTimeFinishedAbsentOnHardcoverHasNoDate() {
        assertEquals(up(3, now - hour, on = null), plan("Finished", now - hour, remote = null))
    }

    @Test
    fun firstTimeSameStatusAgrees() {
        assertEquals(
            MergeStep.Agree(id, 3, now - hour),
            plan("Finished", now - hour, 3, updatedAt = now),
        )
        assertEquals(MergeStep.Agree(id, 1, 0), plan("Want to Read", 0, 1, updatedAt = null))
    }

    @Test
    fun firstTimeDifferentStatusesNewerLibrilloWins() {
        assertEquals(
            up(3, now, userBookId = 7, on = today),
            plan("Finished", now, 2, updatedAt = now - hour),
        )
        assertEquals(
            up(1, now, userBookId = 7),
            plan("Want to Read", now, 5, updatedAt = now - hour),
        )
    }

    @Test
    fun firstTimeDifferentStatusesNewerHardcoverWins() {
        assertEquals(down(3, "Finished", now), plan("Reading", now - hour, 3, updatedAt = now))
    }

    @Test
    fun firstTimeDifferentStatusesWithinAMinuteAsks() {
        assertEquals(ask(3, "Reading"), plan("Reading", now, 3, updatedAt = now - 60_000))
        assertEquals(ask(3, "Reading"), plan("Reading", now - 30_000, 3, updatedAt = now))
    }

    @Test
    fun firstTimeOneMinuteAndOneMillisecondApartIsNotTheSameMoment() {
        assertEquals(down(3, "Finished", now), plan("Reading", now - 60_001, 3, updatedAt = now))
    }

    @Test
    fun firstTimeWithAnUnknownTimeTheStatusFurtherAlongWins() {
        // Paused here, Want to Read on Hardcover: Paused goes up.
        assertEquals(up(4, 0, userBookId = 7), plan("Paused", 0, 1, updatedAt = now))
        assertEquals(up(4, now, userBookId = 7), plan("Paused", now, 1, updatedAt = null))
        // Finished on the Kindle, still Currently Reading on Hardcover: Read goes up, ending today.
        assertEquals(up(3, 0, userBookId = 7, on = today), plan("Finished", 0, 2, updatedAt = now))
        // Want to Read here, Read on Hardcover: Read comes down.
        assertEquals(down(3, "Finished", now), plan("Want to Read", 0, 3, updatedAt = now))
        // Paused against Did Not Finish: neither is further along, so it asks.
        assertEquals(ask(5, "Paused"), plan("Paused", 0, 5, updatedAt = now))
    }

    @Test
    fun firstTimeReadingAgainstReadOnHardcoverAsksInCaseOfAReread() {
        assertEquals(ask(3, "Reading"), plan("Reading", 0, 3, updatedAt = now))
        assertEquals(ask(3, "Paused"), plan("Paused", 0, 3, updatedAt = null))
    }

    // With a baseline.

    @Test
    fun nothingChangedDoesNothing() {
        assertNull(plan("Reading", now, 2, baseline = HardcoverBaseline(2, now - hour)))
        assertNull(plan("Unread", now, 2, baseline = HardcoverBaseline(2, 0)))
    }

    @Test
    fun librilloChangedGoesUpForEveryStatus() {
        val baseline = HardcoverBaseline(1, 0)
        assertEquals(up(2, now, 7), plan("Reading", now, 1, baseline = baseline))
        assertEquals(up(3, now, 7), plan("Finished", now, 1, baseline = baseline))
        assertEquals(up(4, now, 7), plan("Paused", now, 1, baseline = baseline))
        assertEquals(up(5, now, 7), plan("Abandoned", now, 1, baseline = baseline))
        assertEquals(
            up(1, now, 7),
            plan("Want to Read", now, 2, baseline = HardcoverBaseline(2, 0)),
        )
    }

    @Test
    fun librilloChangedButHardcoverLostTheBookAsks() {
        assertEquals(
            ask(0, "Finished"),
            plan("Finished", now, remote = null, baseline = HardcoverBaseline(2, 0)),
        )
    }

    @Test
    fun unreadNeverGoesUp() {
        assertNull(plan("Unread", now, 3, baseline = HardcoverBaseline(3, 0)))
        assertNull(plan("", now, 1, baseline = HardcoverBaseline(1, 0)))
    }

    @Test
    fun hardcoverChangeComesDownOverUnread() {
        assertEquals(
            down(3, "Finished", now - hour),
            plan("Unread", now, 3, updatedAt = now - hour, baseline = HardcoverBaseline(2, 0)),
        )
    }

    @Test
    fun hardcoverChangedComesDown() {
        assertEquals(
            down(3, "Finished", now - hour),
            plan("Reading", 5, 3, updatedAt = now - hour, baseline = HardcoverBaseline(2, 5)),
        )
    }

    @Test
    fun hardcoverChangeWithoutATimeUsesNow() {
        assertEquals(
            down(4, "Paused", now),
            plan("Reading", 5, 4, updatedAt = null, baseline = HardcoverBaseline(2, 5)),
        )
    }

    @Test
    fun removedFromHardcoverIsGoneAndLibrilloIsKept() {
        assertEquals(
            MergeStep.Gone(id, 5),
            plan("Reading", 5, remote = null, baseline = HardcoverBaseline(2, 5)),
        )
        assertEquals(
            MergeStep.Gone(id, 0),
            plan("Unread", 0, remote = null, baseline = HardcoverBaseline(1, 0)),
        )
    }

    @Test
    fun goneIsNotPutBackUntilLibrilloChangesAgain() {
        val tombstone = HardcoverBaseline(0, now - hour)
        assertNull(plan("Reading", now - hour, remote = null, baseline = tombstone))
        assertNull(plan("Reading", now - 2 * hour, remote = null, baseline = tombstone))
        assertEquals(up(2, now), plan("Reading", now, remote = null, baseline = tombstone))
        assertNull(plan("Unread", now, remote = null, baseline = tombstone))
    }

    @Test
    fun goneAnsweredWithLibrilloIsPutBack() {
        // answer(useHardcover = false) for a gone book stores baseline (0, 0).
        assertEquals(
            up(4, now),
            plan("Paused", now, remote = null, baseline = HardcoverBaseline(0, 0)),
        )
    }

    @Test
    fun addedBackOnHardcoverAfterGoneComesDown() {
        assertEquals(
            down(1, "Want to Read", now),
            plan("Reading", 5, 1, updatedAt = now, baseline = HardcoverBaseline(0, 5)),
        )
    }

    @Test
    fun bothChangedToTheSameStatusAgrees() {
        assertEquals(
            MergeStep.Agree(id, 3, now),
            plan("Finished", now, 3, baseline = HardcoverBaseline(2, 0)),
        )
    }

    @Test
    fun bothChangedDifferentlyAsks() {
        assertEquals(
            ask(3, "Paused"),
            plan("Paused", now, 3, updatedAt = now, baseline = HardcoverBaseline(2, 0)),
        )
    }

    @Test
    fun bothChangedAfterGoneAsks() {
        assertEquals(
            ask(1, "Paused"),
            plan("Paused", now, 1, baseline = HardcoverBaseline(0, now - hour)),
        )
    }

    // The finish day.

    @Test
    fun finishedFromCurrentlyReadingCarriesTheStatusDay() {
        val at = Instant.parse("2026-10-14T09:00:00Z").toEpochMilli()
        assertEquals(
            up(3, at, 7, on = LocalDate.of(2026, 10, 14)),
            plan("Finished", at, 2, baseline = HardcoverBaseline(2, 0)),
        )
    }

    @Test
    fun finishedFromPausedCarriesTheStatusDay() {
        val at = Instant.parse("2026-10-12T09:00:00Z").toEpochMilli()
        assertEquals(
            up(3, at, 7, on = LocalDate.of(2026, 10, 12)),
            plan("Finished", at, 4, baseline = HardcoverBaseline(4, 0)),
        )
    }

    @Test
    fun finishedFromWantToReadOrNotStartedHasNoDate() {
        assertEquals(
            up(3, now, 7, on = null),
            plan("Finished", now, 1, baseline = HardcoverBaseline(1, 0)),
        )
        assertEquals(
            up(3, now, 7, on = null),
            plan("Finished", now, 5, baseline = HardcoverBaseline(5, 0)),
        )
    }

    @Test
    fun finishedWithAnUnknownTimeEndsToday() {
        assertEquals(
            up(3, 0, 7, on = today),
            plan("Finished", 0, 2, baseline = HardcoverBaseline(2, 0)),
        )
    }

    @Test
    fun finishDayIsNeverAfterToday() {
        val tomorrow = Instant.parse("2026-10-18T09:00:00Z").toEpochMilli()
        assertEquals(
            up(3, tomorrow, 7, on = today),
            plan("Finished", tomorrow, 2, baseline = HardcoverBaseline(2, 0)),
        )
    }

    @Test
    fun finishDayUsesTheLocalTimeZone() {
        // 23:30 UTC on 15 October is already 16 October in Auckland (UTC+13).
        val at = Instant.parse("2026-10-15T23:30:00Z").toEpochMilli()
        val baseline = HardcoverBaseline(2, 0)
        assertEquals(
            up(3, at, 7, on = LocalDate.of(2026, 10, 15)),
            plan("Finished", at, 2, baseline = baseline, zone = utc),
        )
        assertEquals(
            up(3, at, 7, on = LocalDate.of(2026, 10, 16)),
            plan("Finished", at, 2, baseline = baseline, zone = ZoneId.of("Pacific/Auckland")),
        )
        // 03:00 UTC on 16 October is still 15 October in Los Angeles.
        val early = Instant.parse("2026-10-16T03:00:00Z").toEpochMilli()
        assertEquals(
            up(3, early, 7, on = LocalDate.of(2026, 10, 15)),
            plan(
                "Finished",
                early,
                2,
                baseline = baseline,
                zone = ZoneId.of("America/Los_Angeles"),
            ),
        )
    }

    @Test
    fun onlyFinishedCarriesADay() {
        assertEquals(
            up(5, now, 7, on = null),
            plan("Abandoned", now, 2, baseline = HardcoverBaseline(2, 0)),
        )
    }
}
