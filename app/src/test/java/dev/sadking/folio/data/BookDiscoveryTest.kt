package dev.sadking.folio.data

import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BookDiscoveryTest {
    private fun wish() =
        WishBook(
            UUID.randomUUID().toString(),
            "/works/OL1W",
            "Café Book",
            encode(listOf("José Writer")),
            hardcoverId = 44,
            hardcoverTitle = "Café Book",
            hardcoverSynced = true,
        )

    private fun book(title: String, author: String) =
        Book(
            "book",
            title,
            encode(listOf(author)),
            originalPath = "file",
            currentPath = "file",
            originalHash = "a",
            currentHash = "a",
        )

    @Test
    fun ocrHintIsBoundedAndEditableNotAnIdentity() {
        assertEquals(
            "BOOK TITLE AUTHOR NAME",
            BookDiscovery.query("\nBOOK TITLE\n!\nAUTHOR NAME\n"),
        )
        assertTrue(BookDiscovery.query("a".repeat(1000)).length <= 300)
        assertEquals("", BookDiscovery.query("!\n?"))
    }

    @Test
    fun matchingSuggestionRequiresTitleAndAuthor() {
        assertTrue(BookDiscovery.suggests(wish(), book("Cafe Book", "Jose Writer")))
        assertFalse(BookDiscovery.suggests(wish(), book("Cafe Book", "Other Writer")))
        assertFalse(BookDiscovery.suggests(wish(), book("Other Book", "Jose Writer")))
        assertFalse(
            BookDiscovery.suggests(wish().copy(authors = "[]"), book("Cafe Book", "Jose Writer"))
        )
    }

    @Test
    fun backupRoundTripRetainsLinkButResetsPublicationFlag() {
        val value = wish()
        val restored = parseWish(wishJson(value))
        assertEquals(value.id, restored.id)
        assertEquals(value.hardcoverId, restored.hardcoverId)
        assertFalse(restored.hardcoverSynced)
        assertNull(restored.coverPath)
    }

    @Test
    fun malformedWishlistBackupRejected() {
        val json = wishJson(wish()).put("id", "../../escape")
        assertTrue(runCatching { parseWish(json) }.isFailure)
        assertTrue(runCatching { parseWish(wishJson(wish()).put("hardcoverId", -1)) }.isFailure)
    }

    @Test
    fun backupContainsNoAccountCredentials() {
        val json = wishJson(wish()).toString()
        assertFalse(json.contains("token"))
        assertFalse(json.contains("username"))
        assertTrue(JSONObject(json).has("catalogKey"))
    }

    @Test
    fun hardcoverCatalogueEntriesSurviveBackup() {
        val value = wish().copy(catalogKey = "hardcover:44")
        assertEquals("hardcover:44", parseWish(wishJson(value)).catalogKey)
        assertTrue(
            runCatching { parseWish(wishJson(value).put("catalogKey", "hardcover:0")) }.isFailure
        )
    }
}
