package dev.sadking.folio.core

import org.junit.Assert.*
import org.junit.Test

class LibraryRulesTest {
    private val m =
        Metadata(
            "Title",
            listOf("Author"),
            language = "es",
            series = "Saga",
            seriesIndex = "2.5",
            tags = listOf("History"),
        )

    @Test
    fun exactCaseInsensitiveRules() {
        assertTrue(LibraryRules.matches("Tag", "history", m, "Unread", false))
        assertFalse(LibraryRules.matches("Tag", "hist", m, "Unread", false))
        assertTrue(LibraryRules.matches("Status", "unread", m, "Unread", false))
        assertTrue(LibraryRules.matches("Language", "ES", m, "Unread", false))
        assertTrue(LibraryRules.matches("Series", "saga", m, "Unread", false))
    }

    @Test
    fun missingDeviceRuleAndUnsupportedField() {
        assertTrue(LibraryRules.matches("Missing on Kindle", "", m, "Unread", false))
        assertFalse(LibraryRules.matches("Missing on Kindle", "", m, "Unread", true))
        assertFalse(LibraryRules.matches("anything", "", m, "Unread", false))
    }

    @Test
    fun numericSeriesOrderAndInvalidValuesLast() {
        assertEquals(
            listOf("1", "2.5", "10", "unknown"),
            listOf("10", "unknown", "2.5", "1").sortedBy(LibraryRules::seriesNumber),
        )
        assertEquals(Double.MAX_VALUE, LibraryRules.seriesNumber("NaN"), 0.0)
    }
}
