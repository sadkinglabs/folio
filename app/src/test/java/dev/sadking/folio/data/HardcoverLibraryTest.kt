package dev.sadking.folio.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HardcoverLibraryTest {
    private fun response(rows: String) = JSONObject("{\"data\":{\"user_books\":$rows}}")

    private fun row(id: Int, title: String = "Dune") =
        """{"id":$id,"status_id":2,"edition_id":10,"book":{"id":1,"title":"$title","slug":"dune","cached_contributors":[{"author":{"name":"Frank Herbert"}}]},"user_book_reads":[{"progress_pages":42,"edition_id":10}]}"""

    @Test
    fun paginationIsAccountScopedAndReadOnly() {
        val client = HardcoverClient { _, body ->
            assertTrue(body.getString("query").startsWith("query("))
            assertTrue(body.getString("query").contains("id:{_gt:"))
            assertEquals(8, body.getJSONObject("variables").getInt("user"))
            assertEquals(100, body.getJSONObject("variables").getInt("after"))
            response("[${row(101)}]")
        }
        val value = client.libraryPage("token", 8, 100).single()
        assertEquals(101, value.userBookId)
        assertEquals(listOf("Frank Herbert"), value.book.authors)
        assertEquals(42, value.pages)
    }

    @Test
    fun emptyPageEndsPagination() {
        assertTrue(HardcoverClient { _, _ -> response("[]") }.libraryPage("token", 1, 0).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonAdvancingCursor() {
        HardcoverClient { _, _ -> response("[${row(99)}]") }.libraryPage("token", 1, 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfOrderRows() {
        HardcoverClient { _, _ -> response("[${row(102)},${row(101)}]") }
            .libraryPage("token", 1, 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankTitles() {
        HardcoverClient { _, _ -> response("[${row(101, "")}]") }.libraryPage("token", 1, 0)
    }

    @Test
    fun missingReadAndEditionAreNullable() {
        val obj =
            JSONObject(row(101))
                .put("edition_id", JSONObject.NULL)
                .put("user_book_reads", org.json.JSONArray())
        val value =
            HardcoverClient { _, _ -> response("[$obj]") }.libraryPage("token", 1, 0).single()
        assertNull(value.pages)
        assertNull(value.editionId)
    }

    @Test
    fun pagesRetainReadEditionRatherThanBookDefault() {
        val obj = JSONObject(row(101))
        obj.getJSONArray("user_book_reads").getJSONObject(0).put("edition_id", 20)
        val value =
            HardcoverClient { _, _ -> response("[$obj]") }.libraryPage("token", 1, 0).single()
        assertEquals(20, value.editionId)
    }
}
