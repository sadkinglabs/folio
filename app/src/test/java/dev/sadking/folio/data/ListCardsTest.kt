package dev.sadking.folio.data

import org.junit.Assert.*
import org.junit.Test

class ListCardsTest {
    private fun book(id: String, status: String = "Unread", cover: String? = null) =
        Book(
            id,
            "Title $id",
            "[]",
            originalPath = "",
            currentPath = "",
            originalHash = "",
            currentHash = "",
            status = status,
            coverPath = cover,
        )

    private fun library(entries: List<Book>, shelfEntries: Map<String, List<Book>> = emptyMap()) =
        LibraryPresentation(entries, entries.map { it.id }.toSet(), emptyMap(), shelfEntries)

    @Test
    fun systemListsComeFirstThenUserListsInOrder() {
        val wanted = book("w", "Want to Read")
        val done = book("f", "Finished")
        val shelves = listOf(Shelf("s1", "Slow Sundays"), Shelf("s2", "Essays", "Tag", "Essays"))
        val cards =
            listCards(shelves, library(listOf(wanted, done), mapOf("s1" to listOf(wanted, done))))
        assertEquals(
            listOf("reading:WANT_TO_READ", "reading:READING", "reading:FINISHED", "s1", "s2"),
            cards.map { it.id },
        )
        assertEquals(listOf(1, 0, 1, 2, 0), cards.map { it.count })
        assertEquals("Finished", cards[2].name)
        assertNull(cards[0].shelf)
        assertEquals(ReadingList.WANT_TO_READ, cards[0].system)
        assertNull(cards[3].rule)
        assertEquals("Tag" to "Essays", cards[4].rule)
    }

    @Test
    fun systemListIdsRoundTripAndShelfIdsDoNot() {
        for (list in ReadingList.entries) assertEquals(list, systemListOf(systemListId(list)))
        assertNull(systemListOf("3f2a-shelf"))
        assertNull(systemListOf("reading:NOPE"))
        assertNull(systemListOf("reading:"))
    }

    @Test
    fun mosaicPrefersCoversAndKeepsOrder() {
        val books =
            listOf(
                book("a"),
                book("b", cover = "/b.jpg"),
                book("c"),
                book("d", cover = "/d.jpg"),
                book("e"),
                book("f"),
            )
        assertEquals(listOf("b", "d", "a", "c"), mosaicCovers(books).map { it.id })
        assertEquals(listOf("b", "d"), mosaicCovers(books, 2).map { it.id })
        assertTrue(mosaicCovers(emptyList()).isEmpty())
    }

    @Test
    fun aMissingListResolvesToNull() {
        val lib = library(emptyList())
        assertNull(listCard("gone", emptyList(), lib))
        assertEquals("Finished", listCard("reading:FINISHED", emptyList(), lib)!!.name)
        assertEquals(
            "Essays",
            listCard("s", listOf(Shelf("s", "Essays")), lib)!!.name,
        )
    }
}
