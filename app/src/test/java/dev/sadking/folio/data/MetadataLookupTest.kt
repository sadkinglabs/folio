package dev.sadking.folio.data

import org.junit.Assert.*
import org.junit.Test

/** Independently constructed API fixtures; does not require an external service. */
class MetadataLookupTest {
    @Test
    fun workSearchResolvesEditionSpecificMetadata() {
        val requests = mutableListOf<String>()
        val service = MetadataLookup { url, _ ->
            requests += url
            when {
                url.contains("search.json") ->
                    """{"docs":[{"key":"/works/OL1W","title":"Work title","author_name":["Author"],"cover_i":12,"editions":{"docs":[{"key":"/books/OL2M","title":"Edition title"}]}}]}"""
                url.contains("/works/") -> """{"description":{"value":"Work description"}}"""
                else ->
                    """{"title":"Edition title","publishers":["Publisher"],"languages":[{"key":"/languages/spa"}],"isbn_13":["9780000000000"],"covers":[14]}"""
            }.toByteArray()
        }
        val candidate = service.search("Title & author").single()
        val result = service.proposal(candidate)
        assertEquals("Edition title", result.metadata.title)
        assertEquals("Work description", result.metadata.description)
        assertEquals("Publisher", result.metadata.publisher)
        assertEquals("spa", result.metadata.language)
        assertEquals(listOf("9780000000000"), result.metadata.identifiers)
        assertEquals(14L, result.coverId)
        assertTrue(requests[0].contains("Title+%26+author"))
        assertEquals(3, requests.size)
    }

    @Test
    fun unavailableEditionStillOffersWorkFields() {
        val service = MetadataLookup { url, _ ->
            (if (url.contains("search.json"))
                    """{"docs":[{"key":"/works/OL1W","title":"Title","author_name":["Author"]}]}"""
                else """{"description":"Description"}""")
                .toByteArray()
        }
        val candidate = service.search("Title").single()
        assertNull(candidate.editionKey)
        assertNull(candidate.coverId)
        val result = service.proposal(candidate)
        assertEquals("Title", result.metadata.title)
        assertEquals("Description", result.metadata.description)
        assertTrue(result.metadata.identifiers.isEmpty())
    }

    @Test
    fun untrustedWorkPathRejectedBeforeRequest() {
        var requests = 0
        val service = MetadataLookup { _, _ ->
            requests++
            byteArrayOf()
        }
        assertThrows(Exception::class.java) {
            service.proposal(MetadataCandidate("/../private", "Title", emptyList(), null, null))
        }
        assertEquals(0, requests)
    }

    @Test
    fun coverFetchUsesDedicatedHttpsHostAndBound() {
        var request = ""
        var bound = 0L
        val service = MetadataLookup { url, limit ->
            request = url
            bound = limit
            byteArrayOf(1, 2, 3)
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), service.cover(12))
        assertEquals("https://covers.openlibrary.org/b/id/12-L.jpg?default=false", request)
        assertEquals(10L * 1024 * 1024, bound)
        assertThrows(Exception::class.java) { service.cover(-1) }
    }

    @Test
    fun oversizeResponseRejected() {
        val service = MetadataLookup { _, _ -> ByteArray(2 * 1024 * 1024 + 1) }
        assertThrows(Exception::class.java) { service.search("Title") }
    }

    @Test
    fun malformedResponseDoesNotProduceAnEdition() {
        val service = MetadataLookup { _, _ ->
            "{\"docs\":[{\"title\":\"Missing identity\"}]}".toByteArray()
        }
        assertThrows(Exception::class.java) { service.search("Title") }
    }

    @Test
    fun expectedCoverRedirectAllowedAndUnsafeDestinationsRejected() {
        val original = java.net.URI("https://covers.openlibrary.org/b/id/12-L.jpg")
        val expected = java.net.URI("https://archive.org/download/covers/cover.jpg")
        assertEquals(expected, providerRedirect(original, expected))
        assertEquals(
            "ia800001.us.archive.org",
            providerRedirect(original, java.net.URI("https://ia800001.us.archive.org/cover.jpg"))
                .host,
        )
        listOf(
                "http://archive.org/cover.jpg",
                "https://archive.org.evil.example/cover.jpg",
                "https://localhost/cover.jpg",
                "https://archive.org:8443/cover.jpg",
                "https://user@archive.org/cover.jpg",
            )
            .forEach { url ->
                assertThrows(Exception::class.java) {
                    providerRedirect(original, java.net.URI(url))
                }
            }
        assertThrows(Exception::class.java) {
            providerRedirect(java.net.URI("https://openlibrary.org/works/OL1W.json"), expected)
        }
    }
}
