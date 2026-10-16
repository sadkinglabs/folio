package dev.sadking.folio.ui

import dev.sadking.folio.data.Book
import dev.sadking.folio.data.ReadingSnapshot
import dev.sadking.folio.data.WishBook
import org.junit.Assert.assertEquals
import org.junit.Test

class FinishedOnHardcoverTest {
    private fun book(id: String, status: String) =
        Book(
            id,
            id,
            "[]",
            originalPath = "",
            currentPath = "",
            originalHash = id,
            currentHash = id,
            status = status,
        )

    private fun wish(id: String, linked: String?, hardcover: Int?, status: String = "") =
        WishBook(
            id = id,
            catalogKey = "k$id",
            title = id,
            authors = "[]",
            linkedBookId = linked,
            hardcoverId = hardcover,
            status = status,
        )

    @Test
    fun everyKindOfFinishedBookMatchedToHardcoverIsSent() {
        val books =
            listOf(book("phone", "Finished"), book("kindle", "Unread"), book("c", "Reading"))
        val reading =
            listOf(ReadingSnapshot("kindle", "kindle-1", 1.0, "", "complete", 0, 0L, "", "", ""))
        val wishes =
            listOf(
                wish("w1", "phone", 1),
                wish("w2", "kindle", 2),
                wish("w3", "c", 3),
                wish("catalogue", null, 4, status = "Finished"),
            )
        val result = finishedOnHardcover(books, wishes, reading)
        assertEquals(listOf("phone", "kindle", "catalogue"), result.ids)
        assertEquals(0, result.unmatched)
    }

    @Test
    fun unmatchedAndAlreadyReadBooksAreCountedNotSent() {
        val books = listOf(book("a", "Finished"), book("b", "Finished"), book("c", "Finished"))
        val wishes = listOf(wish("w1", "a", 1), wish("w2", "b", 2))
        val result = finishedOnHardcover(books, wishes, readOnHardcover = setOf(2))
        assertEquals(listOf("a"), result.ids)
        assertEquals(1, result.unmatched)
        assertEquals(1, result.alreadyRead)
    }
}
