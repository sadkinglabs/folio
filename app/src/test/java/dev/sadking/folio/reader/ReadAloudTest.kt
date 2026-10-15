package dev.sadking.folio.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudTest {
    @Test
    fun speedButtonStepsThroughEverySpeedAndWraps() {
        var speed = 1.0
        val seen =
            (1..LISTEN_SPEEDS.size).map {
                speed = nextListenSpeed(speed)
                speed
            }
        assertEquals(listOf(1.25, 1.5, 2.0, 0.75, 1.0), seen)
    }

    @Test
    fun unknownSavedSpeedRestartsAtTheFirst() {
        assertEquals(1.0, nextListenSpeed(3.0), 0.0)
    }

    @Test
    fun labelsDropTrailingZeros() {
        assertEquals("1×", listenSpeedLabel(1.0))
        assertEquals("1.25×", listenSpeedLabel(1.25))
        assertEquals("0.75×", listenSpeedLabel(0.75))
    }
}
