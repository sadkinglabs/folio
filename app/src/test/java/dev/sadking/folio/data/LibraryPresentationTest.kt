package dev.sadking.folio.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryPresentationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun onlyBooksWhoseRecordedFileIsGoneAreMissing() {
        val present = folder.newFile("present.epub")
        val books =
            listOf(
                book("present").copy(currentPath = present.path),
                book("gone").copy(currentPath = File(folder.root, "gone.epub").path),
                book("record").copy(currentPath = ""),
            )
        assertEquals(setOf("gone"), missingFileIds(books))
    }

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
        val signals = mapOf(b.id to BookSignals("Finished · Kindle", 1.0, null))
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
                7,
                "device",
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
                listOf(HardcoverObservation(8, 1, 3, 100, 1, 1000)),
                7,
                "device",
            )
        assertEquals("Want to Read", p.signals.getValue(b.id).reading)
        assertNull(p.signals.getValue(b.id).progress)
    }

    @Test
    fun theNotOnTheEreaderRuleFollowsTheLastSyncAndOlderConfirmations() {
        val b = book()
        val shelf = Shelf("shelf", "Missing", "Missing on Kindle", "")
        fun missing(transfers: List<Transfer>, baselines: List<SyncBaseline> = emptyList()) =
            presentLibrary(
                    listOf(b),
                    emptyList(),
                    listOf(shelf),
                    emptyList(),
                    emptyList(),
                    transfers,
                    emptyList(),
                    emptyList(),
                    7,
                    "ssh-device",
                    baselines,
                )
                .shelfEntries
                .getValue(shelf.id)
                .map { it.id }
        fun baseline(hash: String) =
            SyncBaseline(b.id, "kindle", "Librillo/A/T.epub", hash, "Reading", 0, syncedAt = 1)
        // Nothing known: the book is not on the e-reader.
        assertEquals(listOf(b.id), missing(emptyList()))
        // The last sync left the current file there.
        assertEquals(emptyList<String>(), missing(emptyList(), listOf(baseline(b.currentHash))))
        // The e-reader has an older file of it: not the current book.
        assertEquals(listOf(b.id), missing(emptyList(), listOf(baseline("old"))))
        // Confirmed by the first plugin or sent over SSH before sync existed.
        assertEquals(
            emptyList<String>(),
            missing(listOf(Transfer(b.id, "link:kindle", "/b.epub", b.currentHash, 5))),
        )
        assertEquals(
            emptyList<String>(),
            missing(listOf(Transfer(b.id, "ssh-device", "/b.epub", b.currentHash, 5))),
        )
        assertEquals(
            listOf(b.id),
            missing(listOf(Transfer(b.id, "other", "/b.epub", b.currentHash, 5))),
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
                7,
                "device",
            )
        assertTrue(p.shelfEntries.getValue(shelf.id).isEmpty())
        assertTrue(p.fileIds.isEmpty())
    }

    @Test
    fun signalsKeepTheReadingTimeAndTheHardcoverMatch() {
        val signals = bookSignals(book(), null, true)
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
