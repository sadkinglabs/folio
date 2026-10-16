package dev.sadking.folio.data

import org.junit.Assert.*
import org.junit.Test

class LibraryPresentationTest {
    private fun book(
        id: String = "file",
        title: String = "Pride and Prejudice",
        status: String = "Unread",
    ) =
        Book(
            id,
            title,
            "[\"Jane Austen\"]",
            tags = "[\"Fiction\",\"Classics\"]",
            originalPath = "original",
            currentPath = "current",
            originalHash = "a",
            currentHash = "b",
            status = status,
        )

    @Test
    fun trimmedSearchCoversAuthorAndExactTags() {
        val books = listOf(book())
        assertEquals(
            books,
            searchLibrary(
                indexLibrary(books),
                "  JANE AUSTEN  ",
                "All",
                "All books",
                "classics",
                "Title",
                emptyMap(),
                setOf("file"),
            ),
        )
        assertTrue(
            searchLibrary(
                    indexLibrary(books),
                    "",
                    "All",
                    "All books",
                    "classic",
                    "Title",
                    emptyMap(),
                    setOf("file"),
                )
                .isEmpty()
        )
    }

    @Test
    fun fileFilteringKeepsCatalogueEntriesDistinct() {
        val records = listOf(book(), book("record", "Meditations", "Want to Read"))
        assertEquals(
            listOf("record"),
            searchLibrary(
                    indexLibrary(records),
                    "",
                    "All",
                    "Needs EPUB",
                    "",
                    "Recent",
                    emptyMap(),
                    setOf("file"),
                )
                .map { it.id },
        )
    }

    @Test
    fun filteringUsesObservedStatusWithoutChangingPhoneData() {
        val b = book()
        val signals = mapOf(b.id to BookSignals("On Kindle", "Finished · Kindle", 1.0, null))
        assertEquals(
            listOf(b),
            searchLibrary(
                indexLibrary(listOf(b)),
                "",
                "Finished",
                "All books",
                "",
                "Title",
                signals,
                setOf(b.id),
            ),
        )
        assertEquals("Unread", b.status)
    }

    @Test
    fun projectionDeduplicatesAttachmentsAndRetainsManualLists() {
        val b = book()
        val wish =
            WishBook(
                "record",
                "hardcover:1",
                "Catalogue",
                "[]",
                hardcoverId = 1,
                linkedBookId = b.id,
            )
        val p =
            presentLibrary(
                listOf(b),
                listOf(wish),
                listOf(Shelf("list", "Next")),
                listOf(Membership(b.id, "list")),
                listOf(WishMembership(wish.id, "list")),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                7,
                "device",
                true,
                null,
            )
        assertEquals(1, p.entries.size)
        assertEquals(listOf(b.id), p.shelfEntries.getValue("list").map { it.id })
        assertTrue(ReadingList.WANT_TO_READ.contains(b, p.signals[b.id]))
    }

    @Test
    fun otherAccountsAndDevicesNeverSupplyPresenceOrProgress() {
        val b = book()
        val wish =
            WishBook("record", "hardcover:1", "Title", "[]", hardcoverId = 1, linkedBookId = b.id)
        val p =
            presentLibrary(
                listOf(b),
                listOf(wish),
                emptyList(),
                emptyList(),
                emptyList(),
                listOf(Transfer(b.id, "other", "book.epub", b.currentHash, 500)),
                listOf(
                    ReadingSnapshot(
                        b.id,
                        "other",
                        0.99,
                        "other-anchor",
                        "complete",
                        0,
                        1000,
                        "other.sdr",
                        "source-hash",
                        b.currentHash,
                    )
                ),
                emptyList(),
                listOf(HardcoverObservation(8, 1, 3, 100, 1, 1000)),
                7,
                "device",
                true,
                null,
            )
        assertEquals("Not sent", p.signals.getValue(b.id).device)
        assertEquals("Want to Read", p.signals.getValue(b.id).reading)
    }

    @Test
    fun aBookTheLibrilloPluginConfirmedIsOnTheEreader() {
        val b = book()
        fun device(transfers: List<Transfer>, paired: Boolean = false) =
            presentLibrary(
                    listOf(b),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    transfers,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    7,
                    "ssh-device",
                    paired,
                    null,
                )
                .signals
                .getValue(b.id)
                .device
        // Sent through the plugin only, with no SSH pairing at all.
        assertEquals(
            "On Kindle",
            device(listOf(Transfer(b.id, "link:kindle", "/b.epub", b.currentHash, 5))),
        )
        // An old SSH copy does not hide that the plugin has the current file.
        assertEquals(
            "On Kindle",
            device(
                listOf(
                    Transfer(b.id, "ssh-device", "/old.epub", "old", 9),
                    Transfer(b.id, "link:kindle", "/b.epub", b.currentHash, 5),
                ),
                paired = true,
            ),
        )
        // Only an older file anywhere: it needs updating.
        assertEquals(
            "Needs update",
            device(listOf(Transfer(b.id, "link:kindle", "/b.epub", "old", 5))),
        )
    }

    @Test
    fun catalogueRecordsDoNotBecomeTransferRuleInputs() {
        val wish = WishBook("record", "key", "Title", "[]")
        val shelf = Shelf("shelf", "Missing", "Missing on Kindle", "")
        val p =
            presentLibrary(
                emptyList(),
                listOf(wish),
                listOf(shelf),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                7,
                "device",
                true,
                null,
            )
        assertTrue(p.shelfEntries.getValue(shelf.id).isEmpty())
        assertTrue(p.fileIds.isEmpty())
    }

    @Test
    fun verifiedLabelsHaveHistoricalObservationTime() {
        val b = book()
        val signals =
            bookSignals(
                b,
                true,
                Transfer(b.id, "device", "book.epub", b.currentHash, 500),
                null,
                null,
                true,
            )
        assertEquals(500L, signals.deviceVerifiedAt)
        assertNull(signals.readingFetchedAt)
        assertEquals("Hardcover matched", signals.hardcover)
    }

    @Test
    fun largeSearchIndexRetainsStableIdentityAndSortsDeterministically() {
        val books = (0 until 5000).map { book("id-$it", "Book ${it.toString().padStart(5, '0')}") }
        val result =
            searchLibrary(
                indexLibrary(books),
                "book 04999",
                "All",
                "All books",
                "",
                "Title",
                emptyMap(),
                books.map { it.id }.toSet(),
            )
        assertEquals("id-4999", result.single().id)
    }
}
