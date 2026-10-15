package dev.sadking.folio.data

import org.junit.Assert.*
import org.junit.Test

class CurrentBookTest {
    private fun book(id: String, status: String = "Reading", addedAt: Long = 1) =
        Book(
            id,
            "Title $id",
            "[]",
            originalPath = "",
            currentPath = "",
            originalHash = "orig-$id",
            currentHash = "hash-$id",
            addedAt = addedAt,
            status = status,
        )

    private fun phone(id: String, at: Long, percent: Double? = 0.4, revision: String = "hash-$id") =
        PhoneReading(id, revision, "{}", "/body/DocFragment[1]/body/p[1]", percent, at)

    private fun report(document: String, device: String, seconds: Long, percent: Double = 0.6) =
        KosyncReport(document, device, "Kindle", "/body/p[2]", percent, seconds, seconds * 1000)

    private val digests = mapOf("a" to "doc-a", "b" to "doc-b")

    @Test
    fun nothingReadMeansNoCurrentBook() {
        assertNull(currentBook(listOf(book("a")), emptyList(), emptyList(), "phone") { null })
    }

    @Test
    fun latestPhonePositionWins() {
        val current =
            currentBook(
                listOf(book("a"), book("b")),
                listOf(phone("a", 10_000), phone("b", 20_000, 0.7)),
                emptyList(),
                "phone",
            ) {
                fail("No reports, so no digest should be computed")
                null
            }
        assertEquals("b", current!!.book.id)
        assertEquals(0.7, current.percent!!, 0.0)
        assertEquals(20_000L, current.readAt)
    }

    @Test
    fun aNewerEreaderReportThroughFolioLinkWins() {
        val current =
            currentBook(
                listOf(book("a"), book("b")),
                listOf(phone("a", 10_000)),
                listOf(report("doc-b", "kindle", seconds = 30, percent = 0.55)),
                "phone",
            ) {
                digests[it.id]
            }
        assertEquals("b", current!!.book.id)
        assertEquals(0.55, current.percent!!, 0.0)
        assertEquals(30_000L, current.readAt)
    }

    @Test
    fun thePhonesOwnEchoedReportsDoNotCount() {
        val current =
            currentBook(
                listOf(book("a"), book("b")),
                listOf(phone("a", 10_000)),
                listOf(report("doc-b", "phone", seconds = 30)),
                "phone",
            ) {
                digests[it.id]
            }
        assertEquals("a", current!!.book.id)
    }

    @Test
    fun finishedAndAbandonedBooksAreSkippedAndEditsKeepThePosition() {
        val current =
            currentBook(
                listOf(book("a", status = "Finished"), book("b", status = "Abandoned"), book("c")),
                listOf(
                    phone("a", 30_000),
                    phone("b", 20_000),
                    phone("c", 10_000, 0.2, revision = "older-revision"),
                ),
                emptyList(),
                "phone",
            ) {
                null
            }
        assertEquals("c", current!!.book.id)
        assertEquals(0.2, current.percent!!, 0.0)
    }

    @Test
    fun tiesGoToTheBookAddedLast() {
        val current =
            currentBook(
                listOf(book("a", addedAt = 1), book("b", addedAt = 2)),
                listOf(phone("a", 10_000), phone("b", 10_000)),
                emptyList(),
                null,
            ) {
                null
            }
        assertEquals("b", current!!.book.id)
    }
}
