package dev.sadking.folio.data

import org.junit.Assert.*
import org.junit.Test

class UnifiedLibraryTest {
    private fun book(id: String = "file") =
        Book(
            id,
            "Dune",
            "[\"Frank Herbert\"]",
            originalPath = "original.epub",
            currentPath = "revision.epub",
            originalHash = "a",
            currentHash = "b",
            addedAt = 10,
        )

    private fun wish(id: String = "catalogue", linked: String? = null) =
        WishBook(
            id,
            "hardcover:1",
            "Dune",
            "[\"Frank Herbert\"]",
            hardcoverId = 1,
            linkedBookId = linked,
            addedAt = 20,
        )

    @Test
    fun collectionContainsFilesAndUnattachedBooks() {
        val entries = unifiedLibrary(listOf(book()), listOf(wish()))
        assertEquals(listOf("catalogue", "file"), entries.map { it.id })
        assertEquals("", entries.first().currentPath)
        assertEquals("Want to Read", entries.first().status)
    }

    @Test
    fun attachmentDoesNotDuplicateOwnedEdition() {
        assertEquals(listOf(book()), unifiedLibrary(listOf(book()), listOf(wish(linked = "file"))))
    }

    @Test
    fun missingFileLeavesCatalogueVisible() {
        assertEquals(
            "catalogue",
            unifiedLibrary(emptyList(), listOf(wish(linked = "gone"))).single().id,
        )
    }

    @Test
    fun titleDoesNotMergeEditions() {
        assertEquals(3, unifiedLibrary(listOf(book(), book("other")), listOf(wish())).size)
    }

    @Test
    fun noFileCannotClaimKindleProgress() {
        val value = catalogueSignals(wish(), HardcoverObservation(1, 1, 2, 42, 10, 1000))
        assertEquals("No EPUB", value.device)
        assertEquals("Reading · Hardcover", value.reading)
        assertNull(value.progress)
    }

    @Test
    fun attachmentRetryIsIdempotent() {
        requireAttachment(wish(linked = "file"), book(), listOf(wish(linked = "file")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotReplaceAttachment() {
        requireAttachment(wish(linked = "other"), book(), listOf(wish(linked = "other")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotClaimOtherEntriesFile() {
        requireAttachment(wish(), book(), listOf(wish(), wish("other", "file")))
    }

    @Test
    fun catalogueArtworkRemainsVisibleIfEpubHasNoCover() {
        val owned = book()
        val wish = wish(linked = owned.id).copy(coverPath = "saved-cover.jpg")
        assertEquals(wish.coverPath, unifiedLibrary(listOf(owned), listOf(wish)).single().coverPath)
        assertNull(owned.coverPath)
    }
}
