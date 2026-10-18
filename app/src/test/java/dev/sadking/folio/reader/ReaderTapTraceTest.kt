package dev.sadking.folio.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** The reader's tap trace, on the JVM: stamped steps, oldest first, and a fixed size. */
class ReaderTapTraceTest {
    @Test
    fun keepsTheLatestStepsStampedOldestFirst() {
        var now = 100L
        val trace = TapTrace(capacity = 3, clock = { now++ })
        listOf("touch down", "touch up", "page tap", "highlight tapped").forEach(trace::add)
        assertEquals(listOf("101 touch up", "102 page tap", "103 highlight tapped"), trace.steps())
    }

    @Test
    fun startsEmpty() {
        assertEquals(emptyList<String>(), TapTrace(clock = { 0L }).steps())
    }
}
