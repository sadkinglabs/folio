package dev.sadking.folio.data

import org.json.*
import org.junit.Assert.*
import org.junit.Test

class HardcoverClientTest {
    private fun data(text: String) = JSONObject().put("data", JSONObject(text))

    @Test
    fun accountIsVerifiedBeforeAnyWrite() {
        val client = HardcoverClient { token, body ->
            assertEquals("personal-token", token)
            assertTrue(body.getString("query").contains("me"))
            data("""{"me":[{"id":7,"username":"reader","account_privacy_setting_id":3}]}""")
        }
        assertEquals(HardcoverAccount(7, "reader", 3), client.account("personal-token"))
    }

    @Test
    fun searchHydratesOnlyReturnedIdsInSearchOrder() {
        var calls = 0
        val client = HardcoverClient { _, body ->
            calls++
            if (calls == 1) {
                assertEquals("Title Author", body.getJSONObject("variables").getString("text"))
                data("""{"search":{"ids":[9,3,9],"error":null}}""")
            } else {
                assertEquals(
                    "[9,3]",
                    body.getJSONObject("variables").getJSONArray("ids").toString(),
                )
                data(
                    """{"books":[{"id":3,"title":"Other edition","slug":"other","cached_contributors":[{"author":{"name":"Writer"}}]},{"id":9,"title":"Book","slug":"book","cached_contributors":[{"name":"Author"}]}]}"""
                )
            }
        }
        val matches = client.search("token", "Title Author")
        assertEquals(listOf(9, 3), matches.map { it.id })
        assertEquals(listOf("Author"), matches[0].authors)
        assertEquals(2, calls)
    }

    @Test
    fun emptySearchDoesNotHydrate() {
        var calls = 0
        val client = HardcoverClient { _, _ ->
            calls++
            data("""{"search":{"ids":[],"error":null}}""")
        }
        assertTrue(client.search("token", "Missing").isEmpty())
        assertEquals(1, calls)
    }

    @Test
    fun wantToReadUsesAccountPrivacyAndConfirmedBookId() {
        var calls = 0
        val client = HardcoverClient { _, body ->
            calls++
            when (calls) {
                1 ->
                    data("""{"me":[{"id":7,"username":"reader","account_privacy_setting_id":3}]}""")
                2 -> {
                    assertEquals(44, body.getJSONObject("variables").getInt("book"))
                    assertEquals(7, body.getJSONObject("variables").getInt("user"))
                    data("""{"user_books":[]} """)
                }
                else -> {
                    val o = body.getJSONObject("variables").getJSONObject("object")
                    assertEquals(44, o.getInt("book_id"))
                    assertEquals(1, o.getInt("status_id"))
                    assertEquals(3, o.getInt("privacy_setting_id"))
                    data("""{"insert_user_book":{"id":99,"error":null}}""")
                }
            }
        }
        assertTrue(client.wantToRead("token", 44).startsWith("Added"))
        assertEquals(3, calls)
    }

    @Test
    fun existingFinishedBookNeverGetsResetByRetry() {
        var writes = 0
        val client = HardcoverClient { _, body ->
            val q = body.getString("query")
            if (q.contains("mutation")) {
                writes++
                error("Must not write")
            }
            if (q.contains("me {"))
                data("""{"me":[{"id":7,"username":"reader","account_privacy_setting_id":1}]}""")
            else data("""{"user_books":[{"id":99,"status_id":3}]}""")
        }
        repeat(2) { assertTrue(client.wantToRead("token", 44).startsWith("Already")) }
        assertEquals(0, writes)
    }

    @Test
    fun graphqlErrorDoesNotExposeReturnedSecrets() {
        val client = HardcoverClient { _, _ ->
            JSONObject("""{"errors":[{"message":"private-token private-book"}],"data":null}""")
        }
        val error = runCatching { client.account("token") }.exceptionOrNull()!!
        assertFalse(error.message!!.contains("private-token"))
        assertFalse(error.message!!.contains("private-book"))
    }

    @Test
    fun unsuccessfulMutationIsNotReportedAsAdded() {
        val client = HardcoverClient { _, body ->
            val q = body.getString("query")
            when {
                q.contains("me {") ->
                    data("""{"me":[{"id":7,"username":"reader","account_privacy_setting_id":1}]}""")
                q.contains("mutation") ->
                    data("""{"insert_user_book":{"id":null,"error":"failure"}}""")
                else -> data("""{"user_books":[]} """)
            }
        }
        assertTrue(runCatching { client.wantToRead("token", 44) }.isFailure)
    }

    @Test
    fun tokenHeaderInjectionRejectedBeforeRequest() {
        var calls = 0
        val client = HardcoverClient { _, _ ->
            calls++
            data("{}")
        }
        assertTrue(runCatching { client.account("token\r\nHeader: secret") }.isFailure)
        assertEquals(0, calls)
    }

    @Test
    fun searchRejectsUnrequestedHydratedBook() {
        var calls = 0
        val client = HardcoverClient { _, _ ->
            calls++
            if (calls == 1) data("""{"search":{"ids":[1]}}""")
            else
                data(
                    """{"books":[{"id":2,"title":"Wrong","slug":"wrong","cached_contributors":[]}]}"""
                )
        }
        assertTrue(runCatching { client.search("token", "Title") }.isFailure)
    }
}
