package dev.sadking.folio.data

import org.junit.Assert.*
import org.junit.Test

class BookSignalsTest {
    private val book =
        Book(
            "id",
            "Title",
            "[]",
            originalPath = "",
            currentPath = "",
            originalHash = "original",
            currentHash = "current",
        )

    private fun reading(status: String) =
        ReadingSnapshot(
            book.id,
            "device",
            0.5,
            "anchor",
            status,
            0,
            1,
            "sidecar",
            "sidecar-hash",
            "original",
        )

    @Test
    fun readerCompletionIsLabelledAndDoesNotOverwritePhone() {
        val s = bookSignals(book, reading("complete"), false)
        assertEquals("Finished · Kindle", s.reading)
        assertEquals("Unread", book.status)
        assertEquals(0.5, s.progress!!, 0.0)
    }

    @Test
    fun linkNeverClaimsPublishedCloudProgress() {
        assertEquals("Hardcover matched", bookSignals(book, null, true).hardcover)
    }

    @Test
    fun aRecordedFileThatIsGoneIsMissingButNoPathIsNot() {
        val placed = book.copy(currentPath = "/storage/emulated/0/Books/A/T.epub")
        assertTrue(bookFileMissing(placed) { false })
        assertFalse(bookFileMissing(placed) { true })
        assertFalse(bookFileMissing(book) { false })
    }
}
