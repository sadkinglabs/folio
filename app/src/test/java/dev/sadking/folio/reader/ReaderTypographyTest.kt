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
}
