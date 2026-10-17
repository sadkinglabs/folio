package dev.sadking.folio.data

import org.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HardcoverClientTest {
    private var now = 1_000_000L

    private fun data(text: String) = JSONObject().put("data", JSONObject(text))

    @Before
    fun setUp() {
        HardcoverClient.resetPacing()
        HardcoverClient.clock = { now }
    }

    @After
    fun tearDown() {
        HardcoverClient.clock = System::currentTimeMillis
        HardcoverClient.resetPacing()
    }

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

    @Test
    fun searchCarriesYearAndCover() {
        var calls = 0
        val client = HardcoverClient { _, body ->
            calls++
            if (calls == 1) data("""{"search":{"ids":[9],"error":null}}""")
            else {
                val q = body.getString("query")
                assertTrue(q.contains("release_year") && q.contains("cached_image"))
                data(
                    """{"books":[{"id":9,"title":"Dune","slug":"dune","release_year":1965,"cached_contributors":[{"author":{"name":"Frank Herbert"}}],"cached_image":{"url":"https://assets.hardcover.app/dune.jpg"}}]}"""
                )
            }
        }
        val match = client.search("token", "Dune Frank Herbert").single()
        assertEquals(1965, match.releaseYear)
        assertEquals("https://assets.hardcover.app/dune.jpg", match.coverUrl)
    }

    @Test
    fun missingYearAndCoverAreNull() {
        var calls = 0
        val client = HardcoverClient { _, _ ->
            calls++
            if (calls == 1) data("""{"search":{"ids":[9],"error":null}}""")
            else
                data(
                    """{"books":[{"id":9,"title":"Dune","slug":"dune","release_year":null,"cached_contributors":[],"cached_image":null}]}"""
                )
        }
        val match = client.search("token", "Dune").single()
        assertNull(match.releaseYear)
        assertNull(match.coverUrl)
    }

    @Test
    fun editionsByIsbnAsksForEveryFormAndUsesTheCanonicalBook() {
        val client = HardcoverClient { _, body ->
            val q = body.getString("query")
            assertTrue(
                q.startsWith("query(") && q.contains("isbn_13:{_in:") && !q.contains("mutation")
            )
            assertEquals(
                """["9780441013593","0441013597"]""",
                body.getJSONObject("variables").getJSONArray("isbns").toString(),
            )
            data(
                """{"editions":[
                  {"id":1,"isbn_13":"9780441013593","isbn_10":"0441013597","book":{"id":5,"title":"Dune","slug":"dune","release_year":1965,"canonical_id":9,"cached_contributors":[{"name":"Frank Herbert"}],"cached_image":null}},
                  {"id":2,"isbn_13":null,"isbn_10":"0441013597","book":{"id":9,"title":"Dune","slug":"dune","release_year":1965,"canonical_id":null,"cached_contributors":[],"cached_image":null}},
                  {"id":3,"isbn_13":"9780441013593","isbn_10":null,"book":{"id":7,"title":"","slug":"","canonical_id":null,"cached_contributors":[]}}
                ]}"""
            )
        }
        val hits = client.editionsByIsbn("token", listOf("9780441013593", "0441013597"))
        assertEquals(listOf(9, 9), hits.map { it.book.id })
        assertEquals("9780441013593", hits[0].isbn13)
        assertNull(hits[1].isbn13)
        assertEquals("0441013597", hits[1].isbn10)
        assertEquals(listOf("Frank Herbert"), hits[0].book.authors)
    }

    @Test
    fun editionsByIsbnRejectsBadRequestsBeforeSending() {
        var calls = 0
        val client = HardcoverClient { _, _ ->
            calls++
            data("""{"editions":[]}""")
        }
        assertTrue(runCatching { client.editionsByIsbn("token", emptyList()) }.isFailure)
        assertTrue(
            runCatching { client.editionsByIsbn("token", List(101) { "978044101359$it".take(13) }) }
                .isFailure
        )
        assertTrue(runCatching { client.editionsByIsbn("token", listOf("\"} }")) }.isFailure)
        assertEquals(0, calls)
    }

    @Test
    fun setStatusAddsWithPrivacyOrUpdatesTheEntry() {
        val sent = mutableListOf<JSONObject>()
        val client = HardcoverClient { _, body ->
            sent += body
            if (body.getString("query").contains("insert_user_book"))
                data("""{"insert_user_book":{"id":70,"error":null}}""")
            else data("""{"update_user_book":{"id":71,"error":null}}""")
        }
        assertEquals(70, client.setStatus("token", 44, 1, userBookId = null, privacy = 3))
        val added = sent[0].getJSONObject("variables").getJSONObject("object")
        assertEquals(44, added.getInt("book_id"))
        assertEquals(1, added.getInt("status_id"))
        assertEquals(3, added.getInt("privacy_setting_id"))
        assertEquals(71, client.setStatus("token", 44, 5, userBookId = 71, privacy = 3))
        val updated = sent[1].getJSONObject("variables")
        assertEquals(71, updated.getInt("id"))
        assertEquals("""{"status_id":5}""", updated.getJSONObject("object").toString())
    }

    @Test
    fun setStatusNeverSendsReadOrUnknownStatuses() {
        var calls = 0
        val client = HardcoverClient { _, _ ->
            calls++
            data("{}")
        }
        for (status in listOf(0, 3, 6)) assertTrue(
            runCatching { client.setStatus("token", 44, status, null, 1) }.isFailure
        )
        assertEquals(0, calls)
    }

    @Test
    fun setStatusThatHardcoverDoesNotConfirmFails() {
        val client = HardcoverClient { _, _ ->
            data("""{"update_user_book":{"id":null,"error":"failed"}}""")
        }
        assertTrue(runCatching { client.setStatus("token", 44, 2, 71, 1) }.isFailure)
    }

    @Test
    fun slowDownCoolsEveryClientUntilRetryAfter() {
        var calls = 0
        val busy = HardcoverClient { _, _ ->
            calls++
            throw HardcoverHttpException(429, "Hardcover is busy; retry later", 30_000)
        }
        val error =
            runCatching { busy.account("token") }.exceptionOrNull() as HardcoverHttpException
        assertEquals(429, error.code)
        assertEquals(30_000L, error.retryAfterMs)
        // Another client in the same process waits too, without calling Hardcover.
        var other = 0
        val client = HardcoverClient { _, _ ->
            other++
            data("""{"me":[{"id":7,"username":"reader","account_privacy_setting_id":3}]}""")
        }
        now += 10_000
        val waiting =
            runCatching { client.account("token") }.exceptionOrNull() as HardcoverHttpException
        assertEquals(429, waiting.code)
        assertEquals(20_000L, waiting.retryAfterMs)
        assertEquals(0, other)
        // Two seconds or less left: the request goes ahead.
        now += 18_000
        assertEquals(7, client.account("token").id)
        assertEquals(1, other)
        assertEquals(1, calls)
    }

    @Test
    fun slowDownWithoutRetryAfterWaitsAMinute() {
        val client = HardcoverClient { _, _ -> throw HardcoverHttpException(429, "busy") }
        val error =
            runCatching { client.account("token") }.exceptionOrNull() as HardcoverHttpException
        assertEquals(HardcoverClient.DEFAULT_RETRY_AFTER_MS, error.retryAfterMs)
        now += 57_000
        assertTrue(runCatching { client.account("token") }.isFailure)
    }

    @Test
    fun otherFailuresDoNotCoolTheGate() {
        var fail = true
        val client = HardcoverClient { _, _ ->
            if (fail)
                throw HardcoverHttpException(
                    503,
                    "Hardcover request failed; check internet and retry",
                )
            data("""{"me":[{"id":7,"username":"reader","account_privacy_setting_id":3}]}""")
        }
        assertTrue(runCatching { client.account("token") }.isFailure)
        fail = false
        assertEquals(7, client.account("token").id)
    }

    @Test
    fun retryAfterReadsWholeSeconds() {
        assertEquals(120_000L, HardcoverClient.retryAfterMs("120"))
        assertEquals(1_000L, HardcoverClient.retryAfterMs("0"))
        assertNull(HardcoverClient.retryAfterMs(null))
        assertNull(HardcoverClient.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(3_600_000L, HardcoverClient.retryAfterMs("999999"))
    }

    private fun libraryRow(finished: String) =
        """{"id":101,"status_id":3,"edition_id":null,"updated_at":null,"book":{"id":1,"title":"Dune","slug":"dune","release_year":1965,"cached_contributors":[],"cached_image":null},"user_book_reads":[],"finished":[$finished]}"""

    private fun libraryPage(finished: String) =
        HardcoverClient { _, _ ->
                data("""{"user_books":[${libraryRow(finished)}]}""")
            }
            .libraryPage("token", 7, 0)
            .single()
            .reads

    @Test
    fun aPageWithTwoFinishedReadsParsesBoth() {
        val reads =
            libraryPage(
                """{"id":5,"started_at":"2026-01-02","finished_at":"2026-02-03"},""" +
                    """{"id":6,"started_at":null,"finished_at":"2026-09-30"}"""
            )
        assertEquals(
            listOf(
                HardcoverRead(
                    5,
                    java.time.LocalDate.of(2026, 1, 2),
                    java.time.LocalDate.of(2026, 2, 3),
                ),
                HardcoverRead(6, null, java.time.LocalDate.of(2026, 9, 30)),
            ),
            reads,
        )
    }

    @Test
    fun aReadWithABadDateIsDropped() {
        val reads =
            libraryPage(
                """{"id":5,"started_at":null,"finished_at":"soon"},""" +
                    """{"id":6,"started_at":"2026-13-01","finished_at":"2026-02-03"},""" +
                    """{"id":7,"started_at":null,"finished_at":"2026-02-04"}"""
            )
        assertEquals(listOf(7), reads.map { it.id })
    }
}
