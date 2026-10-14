package dev.sadking.folio.data

import org.json.*
import org.junit.Assert.*
import org.junit.Test

class HardcoverReadingTest {
    private class AccountFixture {
        var status = 3
        var readId: Int? = 21
        var pages: Int? = 15
        var edition: Int? = 8
        var parentEdition: Int? = 8
        var exists = true
        var reads = 1
        val writes = mutableListOf<JSONObject>()
        val client = HardcoverClient { _, body ->
            val q = body.getString("query")
            val v = body.getJSONObject("variables")
            val data =
                when {
                    q.contains("me {") ->
                        JSONObject(
                            """{"me":[{"id":7,"username":"reader","account_privacy_setting_id":3}]}"""
                        )
                    q.contains("editions_by_pk") ->
                        JSONObject("""{"editions_by_pk":{"id":8,"book_id":44,"pages":300}}""")
                    q.contains("user_books(") -> {
                        assertEquals(7, v.getInt("user"))
                        val sessions = JSONArray()
                        if (readId != null)
                            repeat(reads) {
                                sessions.put(
                                    JSONObject()
                                        .put("id", readId)
                                        .put("progress_pages", pages ?: JSONObject.NULL)
                                        .put("edition_id", edition ?: JSONObject.NULL)
                                )
                            }
                        JSONObject()
                            .put(
                                "user_books",
                                JSONArray().apply {
                                    if (exists)
                                        put(
                                            JSONObject()
                                                .put("id", 99)
                                                .put("status_id", status)
                                                .put("edition_id", parentEdition ?: JSONObject.NULL)
                                                .put("user_book_reads", sessions)
                                        )
                                },
                            )
                    }
                    q.contains("mutation") -> {
                        writes.add(JSONObject(body.toString()))
                        val key =
                            when {
                                q.contains("insert_user_book_read") -> {
                                    readId = 22
                                    pages = v.getJSONObject("value").getInt("progress_pages")
                                    edition = v.getJSONObject("value").getInt("edition_id")
                                    "insert_user_book_read"
                                }
                                q.contains("update_user_book_read") -> {
                                    assertEquals(readId, v.getInt("id"))
                                    pages = v.getJSONObject("value").getInt("progress_pages")
                                    edition = v.getJSONObject("value").getInt("edition_id")
                                    "update_user_book_read"
                                }
                                q.contains("insert_user_book(") -> {
                                    exists = true
                                    val o = v.getJSONObject("object")
                                    assertEquals(3, o.getInt("privacy_setting_id"))
                                    status = o.getInt("status_id")
                                    "insert_user_book"
                                }
                                else -> {
                                    val o = v.getJSONObject("object")
                                    if (o.has("status_id")) status = o.getInt("status_id")
                                    if (o.has("edition_id")) parentEdition = o.getInt("edition_id")
                                    "update_user_book"
                                }
                            }
                        JSONObject()
                            .put(
                                key,
                                JSONObject()
                                    .put("id", if (key.endsWith("_read")) readId else 99)
                                    .put("error", JSONObject.NULL),
                            )
                    }
                    else -> error("Unexpected fixture query")
                }
            JSONObject().put("data", data)
        }
    }

    @Test
    fun statusUpdateTouchesOnlyStatus() {
        val f = AccountFixture()
        f.client.currentlyReading("token", 44)
        assertEquals(2, f.status)
        assertEquals(15, f.pages)
        assertEquals(21, f.readId)
        assertEquals(
            "{\"status_id\":2}",
            f.writes.single().getJSONObject("variables").getJSONObject("object").toString(),
        )
        f.client.currentlyReading("token", 44)
        assertEquals(1, f.writes.size)
    }

    @Test
    fun progressUpdatesActiveReadAndRetryDoesNotWriteAgain() {
        val f = AccountFixture()
        val expected = f.client.reading("token", 44)
        f.client.progress("token", 44, 8, 75, expected, false, false)
        assertEquals(75, f.pages)
        assertEquals(21, f.readId)
        assertEquals(2, f.status)
        val read =
            f.writes
                .first { it.getString("query").contains("update_user_book_read") }
                .getJSONObject("variables")
                .getJSONObject("value")
        assertEquals(setOf("progress_pages", "edition_id"), read.keys().asSequence().toSet())
        val count = f.writes.size
        assertTrue(
            f.client
                .progress("token", 44, 8, 75, expected, false, false)
                .startsWith("Progress already")
        )
        assertEquals(count, f.writes.size)
    }

    @Test
    fun startingNewReadRequiresConfirmationAndKeepsCompletedHistory() {
        val f = AccountFixture()
        f.readId = null
        f.pages = null
        val expected = f.client.reading("token", 44)
        assertTrue(
            runCatching { f.client.progress("token", 44, 8, 30, expected, false, false) }.isFailure
        )
        assertTrue(f.writes.isEmpty())
        f.client.progress("token", 44, 8, 30, expected, true, false)
        val write =
            f.writes
                .first { it.getString("query").contains("insert_user_book_read") }
                .getJSONObject("variables")
                .getJSONObject("value")
        assertTrue(write.has("started_at"))
        assertFalse(write.has("finished_at"))
        assertEquals(22, f.readId)
    }

    @Test
    fun remoteConflictFailsBeforeAnyMutation() {
        val f = AccountFixture()
        val expected = f.client.reading("token", 44)
        f.pages = 16
        assertTrue(
            runCatching { f.client.progress("token", 44, 8, 40, expected, false, false) }.isFailure
        )
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun decreasesRequireExplicitConsent() {
        val f = AccountFixture()
        val expected = f.client.reading("token", 44)
        assertTrue(
            runCatching { f.client.progress("token", 44, 8, 5, expected, false, false) }.isFailure
        )
        assertTrue(f.writes.isEmpty())
        f.client.progress("token", 44, 8, 5, expected, false, true)
        assertEquals(5, f.pages)
    }

    @Test
    fun wrongBookAndOutOfRangeProgressFailBeforeWriting() {
        val f = AccountFixture()
        val expected = f.client.reading("token", 44)
        assertTrue(
            runCatching { f.client.progress("token", 45, 8, 10, expected, false, false) }.isFailure
        )
        assertTrue(
            runCatching { f.client.progress("token", 44, 8, 301, expected, false, false) }.isFailure
        )
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun multipleUnfinishedReadsAreAmbiguous() {
        val f = AccountFixture()
        f.reads = 2
        assertTrue(runCatching { f.client.currentlyReading("token", 44) }.isFailure)
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun newLibraryEntryUsesAccountPrivacy() {
        val f = AccountFixture()
        f.exists = false
        f.readId = null
        f.pages = null
        f.client.currentlyReading("token", 44)
        assertEquals(2, f.status)
        assertEquals(1, f.writes.size)
    }

    @Test
    fun koreaderFractionConvertsBeforeEditionEstimate() {
        assertEquals(50.0, koreaderPercent(0.5), 0.0)
        assertEquals(150, estimatedHardcoverPage(koreaderPercent(0.5), 300))
        assertEquals(300, estimatedHardcoverPage(koreaderPercent(1.0), 300))
        assertTrue(runCatching { koreaderPercent(50.0) }.isFailure)
    }

    @Test
    fun percentageIsExplicitEditionEstimate() {
        assertEquals(75, estimatedHardcoverPage(25.0, 300))
        assertEquals(0, estimatedHardcoverPage(0.0, 300))
        assertEquals(300, estimatedHardcoverPage(100.0, 300))
        assertTrue(runCatching { estimatedHardcoverPage(Double.NaN, 300) }.isFailure)
        assertTrue(runCatching { estimatedHardcoverPage(10.0, 0) }.isFailure)
    }
}
