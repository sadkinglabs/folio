package dev.sadking.folio.data

import dev.sadking.folio.core.Metadata
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class LibraryBasicsTest {
    @Test
    fun manualCatalogueMetadataSurvivesPortableRepresentation() {
        val id = UUID.randomUUID().toString()
        val metadata =
            Metadata(
                "Offline book",
                listOf("Author"),
                description = "Notes",
                publisher = "Publisher",
                language = "es",
                identifiers = listOf("ISBN:123"),
                series = "Series",
                seriesIndex = "2",
                tags = listOf("History"),
            )
        val wish =
            WishBook(
                id,
                "manual:$id",
                metadata.title,
                encode(metadata.authors),
                details = metadataJson(metadata).toString(),
                status = "Reading",
            )
        val restored = parseWish(wishJson(wish))
        assertEquals(metadata, restored.displayBook().metadata())
        assertEquals("Reading", restored.status)
        assertEquals(wish.catalogKey, restored.catalogKey)
    }

    @Test
    fun legacyCatalogueHasMetadataAndStatusDefaults() {
        val id = UUID.randomUUID().toString()
        val json = wishJson(WishBook(id, "hardcover:1", "Legacy", "[]"))
        json.remove("details")
        json.remove("status")
        val wish = parseWish(json)
        assertEquals("Legacy", wish.displayBook().metadata().title)
        assertEquals("", wish.status)
        assertEquals("Want to Read", wish.displayBook().status)
    }

    @Test
    fun explicitPhoneStatusDoesNotRewriteCloudObservation() {
        val wish = WishBook("id", "hardcover:1", "Title", "[]", status = "Paused")
        val cloud = HardcoverObservation(1, 1, 3, 42, 1, 1000)
        assertEquals("Paused · phone", catalogueSignals(wish, cloud).reading)
        assertEquals(3, cloud.status)
    }

    @Test
    fun importFailuresDescribeRecoveryWithoutRawIoDetails() {
        assertTrue(importFailure(SecurityException("private URI")).contains("Choose"))
        assertTrue(importFailure(java.util.zip.ZipException("internal bytes")).contains("ZIP"))
        assertFalse(importFailure(java.io.IOException("sensitive path")).contains("sensitive"))
    }

    @Test
    fun catalogueEditingAndStatusDoNotImplyEpubOwnership() {
        val actions = availableBookActions(false)
        assertTrue(BookAction.EDIT in actions && BookAction.STATUS in actions)
        assertFalse(BookAction.EXPORT_EPUB in actions || BookAction.SEND in actions)
    }

    @Test
    fun epubActionsIncludeSharingAndAttachmentCorrection() {
        val actions = availableBookActions(true)
        assertTrue(
            actions.containsAll(
                listOf(
                    BookAction.EXPORT_EPUB,
                    BookAction.SHARE_EPUB,
                    BookAction.OPEN_READER,
                    BookAction.EXTERNAL_READER,
                    BookAction.DETACH,
                )
            )
        )
    }

    @Test
    fun explicitWantToReadOverridesObservedCloudFinishedWithoutChangingIt() {
        val wish = WishBook("id", "hardcover:1", "Title", "[]", status = "Want to Read")
        val cloud = HardcoverObservation(1, 1, 3, 42, 1, 1000)
        assertEquals("Want to Read · phone", catalogueSignals(wish, cloud).reading)
        assertEquals(3, cloud.status)
    }
}
