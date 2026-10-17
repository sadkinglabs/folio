package dev.sadking.folio.reader.narration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterMarksTest {
    @Test
    fun stopsAtSixtyAndResumesBelowThirty() {
        val marks = WaterMarks()
        assertTrue(marks.shouldSynthesize(0.0, false))
        assertTrue(marks.shouldSynthesize(59.9, false))
        assertFalse(marks.shouldSynthesize(60.0, false))
        assertFalse(marks.shouldSynthesize(45.0, false))
        assertFalse(marks.shouldSynthesize(30.0, false))
        assertTrue(marks.shouldSynthesize(29.9, false))
        assertTrue(marks.shouldSynthesize(50.0, false))
        assertFalse(marks.shouldSynthesize(61.0, false))
    }

    @Test
    fun strainedUsesTheHigherMark() {
        val marks = WaterMarks()
        assertTrue(marks.shouldSynthesize(60.0, true))
        assertTrue(marks.shouldSynthesize(119.0, true))
        assertFalse(marks.shouldSynthesize(120.0, true))
        assertFalse(marks.shouldSynthesize(31.0, true))
        assertTrue(marks.shouldSynthesize(29.0, true))
    }

    @Test
    fun marksComeFromTheTuning() {
        val marks = WaterMarks(NarrationTuning(lowWaterSeconds = 5.0, highWaterSeconds = 10.0))
        assertFalse(marks.shouldSynthesize(10.0, false))
        assertFalse(marks.shouldSynthesize(6.0, false))
        assertTrue(marks.shouldSynthesize(4.0, false))
    }
}
