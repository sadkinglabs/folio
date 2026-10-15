package dev.sadking.folio

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [36])
class SendSummaryTest {
    private val resources
        get() = RuntimeEnvironment.getApplication().resources

    @Test
    fun saysWhatWasQueuedSkippedAndLeftForReview() {
        assertEquals(
            "3 books are on their way to your e-reader · 1 was already there · " +
                "1 is different on the e-reader, review it on the Device tab",
            sendSummary(resources, queued = 3, present = 1, differ = 1),
        )
        assertEquals(
            "1 book is on its way to your e-reader",
            sendSummary(resources, queued = 1, present = 0, differ = 0),
        )
    }

    @Test
    fun nothingMissingIsOneSentence() {
        assertEquals(
            "Your e-reader already has all of these",
            sendSummary(resources, queued = 0, present = 4, differ = 0),
        )
    }

    @Test
    fun onlyDifferencesStillPointToReview() {
        assertEquals(
            "2 were already there · 2 are different on the e-reader, review them on the Device tab",
            sendSummary(resources, queued = 0, present = 2, differ = 2),
        )
    }
}
