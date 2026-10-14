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

    private fun transfer(hash: String) = Transfer(book.id, "device", "book.epub", hash, 1)

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
    fun verifiedRevisionIsOnKindle() {
        assertEquals(
            "On Kindle",
            bookSignals(book, true, transfer("current"), null, null, false).device,
        )
    }

    @Test
    fun modifiedRevisionNeedsUpdateInsteadOfClaimingSync() {
        assertEquals(
            "Needs update",
            bookSignals(book, true, transfer("old"), null, null, false).device,
        )
    }

    @Test
    fun unpairedAndMissingFilesStayDistinct() {
        assertEquals("Phone only", bookSignals(book, false, null, null, null, false).device)
        assertEquals("Not sent", bookSignals(book, true, null, null, null, false).device)
    }

    @Test
    fun readerCompletionIsLabelledAndDoesNotOverwritePhone() {
        val s = bookSignals(book, true, null, reading("complete"), null, false)
        assertEquals("Finished · Kindle", s.reading)
        assertEquals("Unread", book.status)
        assertEquals(0.5, s.progress!!, 0.0)
    }

    @Test
    fun linkNeverClaimsPublishedCloudProgress() {
        assertEquals("Hardcover matched", bookSignals(book, true, null, null, null, true).hardcover)
    }

    @Test
    fun queuedAndSendingHaveDifferentBadges() {
        val task = SyncTask("t", "job", 0, book.id, "Title", "source", "current", "dest", 10)
        assertEquals("Queued", bookSignals(book, true, null, null, task, false).device)
        assertEquals(
            "Sending",
            bookSignals(book, true, null, null, task.copy(state = "RUNNING"), false).device,
        )
        assertEquals(
            "Transfer paused",
            bookSignals(book, true, null, null, task.copy(state = "PAUSED"), false).device,
        )
    }
}
