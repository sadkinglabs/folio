package dev.sadking.folio.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTypographyTest {
    private val global = ReaderTypography(fontSize = 120.0, serif = false, lineHeight = 1.3)

    @Test
    fun overrideRoundTrips() {
        val book =
            ReaderTypography(
                fontSize = 150.0,
                serif = true,
                scroll = true,
                lineHeight = 1.8,
                margins = 0.5,
                publisherStyles = true,
            )
        assertEquals(book, ReaderTypography.fromJson(book.toJson(), global))
    }

    @Test
    fun missingFieldsFallBackToGlobalSettings() {
        assertEquals(
            global.copy(fontSize = 90.0),
            ReaderTypography.fromJson("""{"fontSize":90}""", global),
        )
    }

    @Test
    fun malformedOrOutOfRangeOverridesAreSafe() {
        assertEquals(global, ReaderTypography.fromJson("not json", global))
        val clamped = ReaderTypography.fromJson("""{"fontSize":900,"lineHeight":0.2}""", global)
        assertEquals(200.0, clamped.fontSize, 0.0)
        assertEquals(1.0, clamped.lineHeight, 0.0)
    }

    @Test
    fun readiumGetsAMultiplierNotAPercentage() {
        assertEquals(1.1, readiumFontScale(110.0), 1e-9)
        assertEquals(0.75, readiumFontScale(10.0), 1e-9)
        assertEquals(2.0, readiumFontScale(900.0), 1e-9)
    }

    @Test
    fun stepsMoveTenPercentAndStopAtTheEnds() {
        assertEquals(120.0, steppedTextSize(110.0, up = true), 0.0)
        assertEquals(100.0, steppedTextSize(110.0, up = false), 0.0)
        assertEquals(MIN_TEXT, steppedTextSize(MIN_TEXT, up = false), 0.0)
        assertEquals(MAX_TEXT, steppedTextSize(MAX_TEXT, up = true), 0.0)
        // Old slider values land back on the steps.
        assertEquals(120.0, steppedTextSize(113.0, up = true), 0.0)
    }
}
