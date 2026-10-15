package dev.sadking.folio.data

import org.junit.Assert.*
import org.junit.Test

class LibraryTabsTest {
    private fun book(
        id: String,
        status: String = "Unread",
        addedAt: Long = 1,
        epub: Boolean = true,
    ) =
        Book(
            id,
            "Title $id",
            "[]",
            originalPath = if (epub) "$id.epub" else "",
            currentPath = if (epub) "$id.epub" else "",
            originalHash = if (epub) "hash-$id" else "",
            currentHash = if (epub) "hash-$id" else "",
            addedAt = addedAt,
            status = status,
        )

    private fun signals(reading: String, progress: Double? = null, fetchedAt: Long? = null) =
        BookSignals("Phone only", reading, progress, null, readingFetchedAt = fetchedAt)

    @Test
    fun wantedBooksAreNewestFirstWhateverTheEntryOrder() {
        val tabs =
            libraryTabs(
                listOf(
                    book("old", "Want to Read", addedAt = 10, epub = false),
                    book("new", "Want to Read", addedAt = 30, epub = false),
                    book("mid", "Want to Read", addedAt = 20, epub = false),
                ),
                emptyMap(),
                emptySet(),
                null,
            )
        assertEquals(listOf("new", "mid", "old"), tabs.wanted.map { it.id })
        assertTrue(tabs.library.isEmpty())
    }

    @Test
    fun anOwnedWantedBookSitsInBothTabsAndNothingIsDropped() {
        val entries =
            listOf(
                book("owned-wanted", "Want to Read"),
                book("record-wanted", "Want to Read", epub = false),
                book("record-finished", "Finished", epub = false),
                book("owned", "Unread"),
            )
        val tabs = libraryTabs(entries, emptyMap(), setOf("owned-wanted", "owned"), null)
        assertEquals(listOf("owned-wanted", "record-finished", "owned"), tabs.library.map { it.id })
        assertEquals(listOf("owned-wanted", "record-wanted"), tabs.wanted.map { it.id })
        assertEquals(
            entries.map { it.id }.toSet(),
            (tabs.library + tabs.wanted).map { it.id }.toSet(),
        )
    }

    @Test
    fun theShelfHoldsBooksInProgressWithTheCurrentOneFirst() {
        val entries =
            listOf(
                book("a", "Reading"),
                book("b", "Unread"),
                book("c", "Reading"),
                book("d", "Reading", epub = false),
            )
        val tabs = libraryTabs(entries, emptyMap(), setOf("a", "b", "c"), currentId = "c")
        assertEquals(listOf("c", "a", "d"), tabs.reading.map { it.id })
        // The grid keeps every book, so a filter never has to know about the shelf.
        assertEquals(listOf("a", "b", "c", "d"), tabs.library.map { it.id })
    }

    @Test
    fun theEreaderStatusOutranksTheStoredOne() {
        val entries = listOf(book("a", "Unread"))
        val tabs =
            libraryTabs(entries, mapOf("a" to signals("Reading · Kindle", 0.4)), setOf("a"), null)
        assertEquals(listOf("a"), tabs.reading.map { it.id })
        assertEquals("Reading", readingStatusOf(entries[0], signals("Reading · Kindle")))
        assertEquals("Unread", readingStatusOf(entries[0], null))
    }

    private fun phone(percent: Double?, at: Long) =
        PhoneReading("a", "hash-a", "{}", "/body/p[1]", percent, at)

    @Test
    fun progressComesFromWhicheverDeviceReadLast() {
        assertNull(readingProgress(null, null))
        assertEquals(0.3, readingProgress(phone(0.3, 10), null)!!, 0.0)
        assertEquals(0.5, readingProgress(null, signals("Reading", 0.5, 10))!!, 0.0)
        assertEquals(0.5, readingProgress(phone(0.3, 10), signals("Reading", 0.5, 20))!!, 0.0)
        assertEquals(0.3, readingProgress(phone(0.3, 30), signals("Reading", 0.5, 20))!!, 0.0)
        // An e-reader report without a time is older than any phone position.
        assertEquals(0.3, readingProgress(phone(0.3, 30), signals("Reading", 0.5))!!, 0.0)
        // A phone position outside 0..1 is ignored rather than drawn.
        assertEquals(0.5, readingProgress(phone(1.5, 30), signals("Reading", 0.5, 20))!!, 0.0)
    }

    @Test
    fun latestPhonePositionPerBook() {
        val latest = latestPhoneReading(listOf(phone(0.1, 10), phone(0.2, 20), phone(0.15, 15)))
        assertEquals(0.2, latest.getValue("a").percent!!, 0.0)
    }

    @Test
    fun activeFiltersCountEachKindOnce() {
        assertEquals(0, activeLibraryFilters("All", "All books", ""))
        assertEquals(1, activeLibraryFilters("Reading", "All books", ""))
        assertEquals(3, activeLibraryFilters("Reading", "With EPUB", "History"))
        assertEquals(0, activeLibraryFilters("All", "All books", "   "))
    }
}
