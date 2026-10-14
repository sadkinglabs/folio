package dev.sadking.folio.data

import org.junit.Assert.*
import org.junit.Test

class ReadingListsTest {
    private fun book(status: String) =
        Book(
            "id",
            "Title",
            "[]",
            originalPath = "",
            currentPath = "",
            originalHash = "",
            currentHash = "",
            status = status,
        )

    @Test
    fun phoneBookCanBelongToWantToRead() {
        assertTrue(ReadingList.WANT_TO_READ.contains(book("Want to Read"), null))
        assertFalse(ReadingList.WANT_TO_READ.contains(book("Unread"), null))
    }

    @Test
    fun observedReadingStateMatchesLibraryFilters() {
        val signals = BookSignals("On Kindle", "Finished · Kindle", 1.0, null)
        assertTrue(ReadingList.FINISHED.contains(book("Want to Read"), signals))
        assertFalse(ReadingList.WANT_TO_READ.contains(book("Want to Read"), signals))
    }

    @Test
    fun catalogueDefaultAppearsInPinnedList() {
        val wish = WishBook("wish", "key", "Title", "[]")
        assertTrue(
            ReadingList.WANT_TO_READ.contains(wish.displayBook(), catalogueSignals(wish, null))
        )
    }

    @Test
    fun linkedCatalogueDoesNotDuplicatePinnedCount() {
        val owned = book("Want to Read")
        val wish = WishBook("wish", "key", "Title", "[]", linkedBookId = owned.id)
        assertEquals(
            1,
            unifiedLibrary(listOf(owned), listOf(wish)).count {
                ReadingList.WANT_TO_READ.contains(it, null)
            },
        )
    }

    @Test
    fun attachedFileRetainsWantToReadIntentUntilReading() {
        val wish = WishBook("wish", "key", "Title", "[]")
        val unread = BookSignals("On Kindle", "Unread", null, null)
        assertTrue(
            ReadingList.WANT_TO_READ.contains(
                book("Unread"),
                linkedReadingSignals(unread, wish, null),
            )
        )
        val finished = unread.copy(reading = "Finished · Kindle")
        assertEquals(finished, linkedReadingSignals(finished, wish, null))
    }
}
