package dev.sadking.folio.sync

import dev.sadking.folio.data.Book
import dev.sadking.folio.data.WishBook
import dev.sadking.folio.data.encode
import org.junit.Assert.*
import org.junit.Test

/** What a book page's Hardcover tile says, and which items matching picks up. */
class HardcoverItemStateTest {
    private fun book(id: String, title: String = "Dune", addedAt: Long = 1) =
        Book(
            id,
            title,
            encode(listOf("Frank Herbert")),
            identifiers = encode(listOf("urn:isbn:9780441013593")),
            originalPath = "/nowhere/$id.epub",
            currentPath = "/nowhere/$id.epub",
            originalHash = id.padEnd(64, '0'),
            currentHash = id.padEnd(64, '0'),
            addedAt = addedAt,
        )

    private fun wish(
        id: String,
        hardcoverId: Int? = null,
        linkedBookId: String? = null,
        title: String = "Dune",
        addedAt: Long = 1,
    ) =
        WishBook(
            id,
            "manual:$id",
            title,
            encode(listOf("Frank Herbert")),
            hardcoverId = hardcoverId,
            linkedBookId = linkedBookId,
            addedAt = addedAt,
        )

    private fun record(itemId: String, state: MatchRecordState, key: String) =
        MatchRecord(itemId, state, emptyList(), key, 1)

    private fun state(
        itemId: String,
        books: List<Book>,
        wishes: List<WishBook> = emptyList(),
        records: Map<String, MatchRecord> = emptyMap(),
        connected: Boolean = true,
        enabled: Boolean = true,
    ) = hardcoverItemState(itemId, books, wishes, records, connected, enabled)

    @Test
    fun aLinkedBookAndItsEntryAreMatched() {
        val books = listOf(book("b"))
        val wishes = listOf(wish("w", hardcoverId = 9, linkedBookId = "b"))
        assertEquals(HardcoverItemState.MATCHED, state("b", books, wishes))
        assertEquals(HardcoverItemState.MATCHED, state("w", books, wishes))
        assertEquals(HardcoverItemState.MATCHED, state("w", emptyList(), listOf(wish("w", 9))))
    }

    @Test
    fun noRecordIsMatchingWhileSyncIsOnAndOffOtherwise() {
        val books = listOf(book("b"))
        assertEquals(HardcoverItemState.MATCHING, state("b", books))
        assertEquals(HardcoverItemState.OFF, state("b", books, connected = false))
        assertEquals(HardcoverItemState.OFF, state("b", books, enabled = false))
    }

    @Test
    fun recordsGiveCheckNotOnHardcoverAndNotFound() {
        val books = listOf(book("b"))
        val key = bookQuery("b", books[0]).key()
        assertEquals(
            HardcoverItemState.CHECK,
            state("b", books, records = mapOf("b" to record("b", MatchRecordState.UNSURE, key))),
        )
        assertEquals(
            HardcoverItemState.NOT_ON_HARDCOVER,
            state(
                "b",
                books,
                records = mapOf("b" to record("b", MatchRecordState.NOT_ON_HARDCOVER, "old")),
            ),
        )
        assertEquals(
            HardcoverItemState.NOT_FOUND,
            state("b", books, records = mapOf("b" to record("b", MatchRecordState.NOT_FOUND, key))),
        )
    }

    @Test
    fun notFoundIsTriedAgainAfterAnEdit() {
        val records = mapOf("b" to record("b", MatchRecordState.NOT_FOUND, "before the edit"))
        assertEquals(HardcoverItemState.MATCHING, state("b", listOf(book("b")), records = records))
        assertEquals(
            HardcoverItemState.OFF,
            state("b", listOf(book("b")), records = records, enabled = false),
        )
    }

    @Test
    fun aBookWithAnUnlinkedEntryUsesTheEntrysRecord() {
        val books = listOf(book("b"))
        val wishes = listOf(wish("w", linkedBookId = "b"))
        val records = mapOf("w" to record("w", MatchRecordState.UNSURE, "k"))
        assertEquals(HardcoverItemState.CHECK, state("b", books, wishes, records))
        assertEquals(HardcoverItemState.CHECK, state("w", books, wishes, records))
    }

    @Test
    fun theWholeLibraryAtOnceAgreesWithOneAtATime() {
        val books = listOf(book("b1"), book("b2"), book("b3"))
        val wishes = listOf(wish("w1", 9, "b1"), wish("w2", linkedBookId = "b2"), wish("w3"))
        val records = mapOf("w3" to record("w3", MatchRecordState.NOT_ON_HARDCOVER, ""))
        val all = hardcoverItemStates(books, wishes, records, connected = true, enabled = true)
        for (id in listOf("b1", "b2", "b3", "w1", "w2", "w3")) assertEquals(
            id,
            state(id, books, wishes, records),
            all[id],
        )
    }

    @Test
    fun matchItemsAreUnlinkedEntriesAndLooseBooksNewestFirst() {
        val books =
            listOf(
                book("linked", addedAt = 5),
                book("withEntry", addedAt = 6),
                book("loose", addedAt = 9),
            )
        val wishes =
            listOf(
                wish("w-linked", 9, "linked"),
                wish("w-file", linkedBookId = "withEntry", addedAt = 3),
                wish("w-plain", title = "Emma", addedAt = 7),
            )
        val items = matchItems(books, wishes, removed = emptySet(), records = emptyMap())
        assertEquals(listOf("loose", "w-plain", "w-file"), items.map { it.itemId })
        // An entry with a file is matched on its book's details.
        assertEquals(listOf("9780441013593"), items.last().isbns)
    }

    @Test
    fun removedRuledOutAndAlreadyCheckedItemsAreSkipped() {
        val books = listOf(book("b1"), book("b2"), book("b3"), book("b4"))
        val key = { id: String -> bookQuery(id, books.first { it.id == id }).key() }
        val records =
            mapOf(
                "b2" to record("b2", MatchRecordState.NOT_ON_HARDCOVER, "anything"),
                "b3" to record("b3", MatchRecordState.NOT_FOUND, key("b3")),
                "b4" to record("b4", MatchRecordState.UNSURE, "before the edit"),
            )
        val items = matchItems(books, emptyList(), removed = setOf("b1"), records = records)
        assertEquals(listOf("b4"), items.map { it.itemId })
    }

    @Test
    fun checksListOnlyUnsureItemsStillUnlinked() {
        val books = listOf(book("b1"), book("b2"))
        val candidate = MatchCandidateView(9, "Dune", listOf("Frank Herbert"), 1965, null)
        val records =
            mapOf(
                "b1" to MatchRecord("b1", MatchRecordState.UNSURE, listOf(candidate), "k", 1),
                "b2" to MatchRecord("b2", MatchRecordState.UNSURE, listOf(candidate), "k", 1),
                "gone" to MatchRecord("gone", MatchRecordState.UNSURE, listOf(candidate), "k", 1),
            )
        val checks = matchChecks(books, listOf(wish("w", 9, "b2")), records)
        assertEquals(listOf("b1"), checks.map { it.itemId })
        assertEquals(listOf(candidate), checks.single().candidates)
        assertEquals("Dune", checks.single().title)
    }
}
