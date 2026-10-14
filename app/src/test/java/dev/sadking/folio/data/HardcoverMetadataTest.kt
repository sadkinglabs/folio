package dev.sadking.folio.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HardcoverMetadataTest {
    private fun response() =
        JSONObject(
            """{"data":{"books_by_pk":{"id":1,"title":"A Book","description":"Description","cached_contributors":[{"author":{"name":"Author"}}],"cached_image":{"url":"https://assets.hardcover.app/cover.jpg"},"cached_tags":["History",{"tag":"Literature"}],"featured_book_series":{"position":2,"series":{"name":"Series"}},"default_ebook_edition":{"isbn_13":"1234567890123","isbn_10":null,"language":{"code2":"en"},"publisher":{"name":"Publisher"}},"default_physical_edition":null}}}"""
        )

    @Test
    fun fetchesCanonicalMetadataWithoutWritingRemoteAccount() {
        val client = HardcoverClient { _, body ->
            assertTrue(body.getString("query").startsWith("query("))
            assertEquals(1, body.getJSONObject("variables").getInt("id"))
            response()
        }
        val data = client.bookMetadata("token", 1)
        assertEquals("Description", data.metadata.description)
        assertEquals(listOf("Author"), data.metadata.authors)
        assertEquals("Publisher", data.metadata.publisher)
        assertEquals("en", data.metadata.language)
        assertEquals("Series", data.metadata.series)
        assertEquals("2", data.metadata.seriesIndex)
        assertEquals(listOf("History", "Literature"), data.metadata.tags)
        assertEquals(listOf("1234567890123"), data.metadata.identifiers)
    }

    @Test
    fun missingDefaultsAndImageRemainOptional() {
        val response = response()
        val book = response.getJSONObject("data").getJSONObject("books_by_pk")
        book
            .put("default_ebook_edition", JSONObject.NULL)
            .put("cached_image", JSONObject.NULL)
            .put("featured_book_series", JSONObject.NULL)
        val data = HardcoverClient { _, _ -> response }.bookMetadata("token", 1)
        assertTrue(data.metadata.identifiers.isEmpty())
        assertEquals("", data.metadata.series)
        assertNull(data.coverUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsReturnedBookIdentityMismatch() {
        HardcoverClient { _, _ -> response() }.bookMetadata("token", 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankTitle() {
        val response = response()
        response.getJSONObject("data").getJSONObject("books_by_pk").put("title", "")
        HardcoverClient { _, _ -> response }.bookMetadata("token", 1)
    }

    @Test
    fun publicCoverAllowsBoundedTrustedOriginWithoutAccountToken() {
        val cover = HardcoverCover { byteArrayOf(1, 2, 3) }
        assertEquals(3, cover.load("https://assets.hardcover.app/image.jpg").size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPrivateOrArbitraryCoverOrigin() {
        HardcoverCover { byteArrayOf(1) }.load("https://127.0.0.1/image")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCoverCredentialsAndUnusualPorts() {
        HardcoverCover { byteArrayOf(1) }.load("https://secret@assets.hardcover.app:8443/image")
    }
}
