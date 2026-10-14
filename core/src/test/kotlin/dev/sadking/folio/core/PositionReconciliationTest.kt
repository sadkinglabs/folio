package dev.sadking.folio.core

import org.junit.Assert.*
import org.junit.Test

class PositionReconciliationTest {
    @Test
    fun firstPairingOfTwoDifferentPositionsRequiresReview() {
        assertEquals(PositionAction.CONFLICT, PositionReconciliation.plan("phone", "kindle", null))
    }

    @Test
    fun oneChangedSideIsForwardedIncludingBackwardReading() {
        val base = PositionBaseline("old", "old")
        assertEquals(
            PositionAction.TO_KINDLE,
            PositionReconciliation.plan("earlier-passage", "old", base),
        )
        assertEquals(PositionAction.TO_PHONE, PositionReconciliation.plan("old", "new", base))
    }

    @Test
    fun twoChangedSidesAreNeverResolvedByPercentageOrFetchTime() {
        assertEquals(
            PositionAction.CONFLICT,
            PositionReconciliation.plan("one", "two", PositionBaseline("old", "old")),
        )
    }

    @Test
    fun noSavedPositionAndRepeatedSyncAreSafe() {
        assertEquals(PositionAction.TO_PHONE, PositionReconciliation.plan("", "one", null))
        assertEquals(PositionAction.TO_KINDLE, PositionReconciliation.plan("one", "", null))
        assertEquals(PositionAction.UNCHANGED, PositionReconciliation.plan("one", "one", null))
        assertEquals(
            PositionAction.UNCHANGED,
            PositionReconciliation.plan("one", "two", PositionBaseline("one", "two")),
        )
    }
}
