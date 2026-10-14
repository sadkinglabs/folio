package dev.sadking.folio.sync

import dev.sadking.folio.data.HardcoverReading
import org.junit.Assert.assertEquals
import org.junit.Test

class HardcoverOutboxTest {
    private val reading =
        HardcoverReading(userBookId = 10, status = 2, readId = 20, pages = 100, editionId = 30)

    @Test
    fun movesAnOpenReadForwardOnItsOwnEdition() {
        assertEquals(OutboxStep.Send(150), outboxStep(0.5, reading, editionPages = 300))
    }

    @Test
    fun neverGoesBackwardsOrRepeats() {
        assertEquals(OutboxStep.AlreadyThere, outboxStep(0.2, reading, editionPages = 300))
        assertEquals(OutboxStep.AlreadyThere, outboxStep(1.0 / 3, reading, editionPages = 300))
    }

    @Test
    fun leavesAnythingUnusualForReview() {
        assertEquals(
            OutboxStep.NeedsReview(OutboxStep.Reason.NOT_LINKED),
            outboxStep(0.5, HardcoverReading(null, null, null, null, null), 300),
        )
        // Want to Read, or Currently Reading without an open read: do not start one.
        assertEquals(
            OutboxStep.NeedsReview(OutboxStep.Reason.NOT_READING),
            outboxStep(0.5, reading.copy(status = 1), 300),
        )
        assertEquals(
            OutboxStep.NeedsReview(OutboxStep.Reason.NOT_READING),
            outboxStep(0.5, reading.copy(readId = null), 300),
        )
        assertEquals(
            OutboxStep.NeedsReview(OutboxStep.Reason.NO_EDITION),
            outboxStep(0.5, reading, editionPages = null),
        )
        assertEquals(
            OutboxStep.NeedsReview(OutboxStep.Reason.NO_EDITION),
            outboxStep(0.5, reading.copy(editionId = null), 300),
        )
    }

    @Test
    fun aFirstPageCountIsSentWhenHardcoverHasNone() {
        assertEquals(OutboxStep.Send(30), outboxStep(0.1, reading.copy(pages = null), 300))
    }
}
