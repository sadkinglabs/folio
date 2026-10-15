package dev.sadking.folio.ui

import dev.sadking.folio.core.Metadata
import dev.sadking.folio.data.*
import org.junit.Assert.*
import org.junit.Test

/** The plain functions behind the lookup, Hardcover and removed sheets. */
class SheetLogicTest {
    private val current =
        Metadata(
            "The Orchard",
            listOf("Alex Rowan"),
            description = "",
            publisher = "Old Press",
            language = "en",
            series = "Seasons",
            seriesIndex = "1",
            tags = listOf("keep"),
        )
    private val proposed =
        Metadata(
            "The Orchard: A Year",
            listOf("Alex Rowan", "Sam Reed"),
            description = "A revised description.",
            publisher = "",
            language = "en",
            identifiers = listOf("isbn:1"),
            series = "Other",
            tags = listOf("drop"),
        )

    @Test
    fun lookupFieldsListOnlyTheFieldsWithASuggestion() {
        val fields = lookupFields(current, proposed)
        assertEquals(
            listOf("Title", "Authors", "Description", "Language", "Identifiers"),
            fields.map { it.key },
        )
        val title = fields.first()
        assertEquals("The Orchard", title.current)
        assertEquals("The Orchard: A Year", title.suggested)
        assertEquals("", fields.first { it.key == "Description" }.current)
    }

    @Test
    fun applyLookupTakesOnlySelectedFieldsAndNeverSeriesOrTags() {
        val merged = applyLookup(current, proposed, setOf("Title", "Description"))
        assertEquals("The Orchard: A Year", merged.title)
        assertEquals("A revised description.", merged.description)
        assertEquals(listOf("Alex Rowan"), merged.authors)
        assertEquals("Old Press", merged.publisher)
        assertEquals("Seasons", merged.series)
        assertEquals(listOf("keep"), merged.tags)
        assertEquals(current, applyLookup(current, proposed, emptySet()))
    }

    @Test
    fun removedGroupsShowOneRowPerBookPreferringItsEpub() {
        val entries =
            listOf(
                RemovedEntry("a", "g1", "Record", "Catalogue", 1),
                RemovedEntry("b", "g1", "Book", "EPUB", 2),
                RemovedEntry("c", "g2", "Only record", "Catalogue", 3),
            )
        val groups = removedGroups(entries)
        assertEquals(listOf("b", "c"), groups.map { it.id })
    }

    @Test
    fun percentInputDropsTrailingZerosAndKeepsOneDecimal() {
        assertEquals("37", percentInput(37.0))
        assertEquals("37.5", percentInput(37.5))
        assertEquals("41.3", percentInput(41.3333))
        assertEquals("100", percentInput(100.0))
        assertEquals("0", percentInput(0.0))
    }

    @Test
    fun importableUnlessFolioHoldsTheBookTwice() {
        val row = HardcoverLibraryBook(1, HardcoverMatch(9, "T", emptyList(), "t"), 2, null, null)
        fun wish(id: String) = WishBook(id, "hardcover:9", "T", "[]", hardcoverId = 9)
        assertTrue(importable(row, emptyList()))
        assertTrue(importable(row, listOf(wish("one"))))
        assertFalse(importable(row, listOf(wish("one"), wish("two"))))
    }

    @Test
    fun quickFilterChipTurnsOnThenBackOff() {
        assertEquals("Reading", quickToggle("All", "Reading", "All"))
        assertEquals("Unread", quickToggle("Reading", "Unread", "All"))
        assertEquals("All", quickToggle("Reading", "Reading", "All"))
        assertEquals("All books", quickToggle("Needs EPUB", "Needs EPUB", "All books"))
    }
}
