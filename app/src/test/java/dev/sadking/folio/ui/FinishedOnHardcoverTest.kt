package dev.sadking.folio.ui

import dev.sadking.folio.data.Book
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

    @Test
    fun onlyFinishedBooksMatchedToHardcoverAreSent() {
        val books =
            listOf(
                book("a", "Finished"),
                book("b", "Finished"),
                book("c", "Reading"),
                book("d", "Finished"),
            )
        val wishes =
            listOf(
                WishBook(
                    id = "w1",
                    catalogKey = "k1",
                    title = "A",
                    authors = "[]",
                    linkedBookId = "a",
                    hardcoverId = 1,
                ),
                WishBook(
                    id = "w2",
                    catalogKey = "k2",
                    title = "B",
                    authors = "[]",
                    linkedBookId = "b",
                    hardcoverId = null,
                ),
                WishBook(
                    id = "w3",
                    catalogKey = "k3",
                    title = "C",
                    authors = "[]",
                    linkedBookId = "c",
                    hardcoverId = 3,
                ),
            )
        assertEquals(listOf("a"), finishedOnHardcover(books, wishes))
    }
}
