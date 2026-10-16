package dev.sadking.folio.sync

import dev.sadking.folio.core.sync.SyncAnnotation
import dev.sadking.folio.data.BookAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test

/** Annotations between Room and sync, and older KOReader rows that sync has since matched. */
class SyncAnnotationsTest {
    private fun row(
        id: String,
        device: String = "kindle-ssh",
        date: String = "2026-01-01 10:00:00",
        pos: String = "/body/p[1]",
        kind: String = "highlight",
    ) = BookAnnotation(id, "b1", device, "text", "note", "One", date, pos, "/body/p[2]", kind)

    @Test
    fun aRowAndASyncAnnotationAreTheSame() {
        val sync = SyncAnnotations.toSync(row("a", kind = "note"))
        assertEquals(
            SyncAnnotation(
                "a",
                "text",
                "note",
                "One",
                "2026-01-01 10:00:00",
                "/body/p[1]",
                "/body/p[2]",
                "note",
            ),
            sync,
        )
        val back = SyncAnnotations.toRow(sync, "b1", "kindle-7f")
        assertEquals("link:kindle-7f", back.deviceId)
        assertEquals(row("a", device = "link:kindle-7f", kind = "note"), back)
    }

    @Test
    fun anUnknownKindIsAHighlight() {
        assertEquals("highlight", SyncAnnotations.toSync(row("a", kind = "underline")).kind)
    }

    @Test
    fun anOlderRowMatchedByASyncedOneIsLeftOut() {
        val older = row("old")
        val synced = row("new", device = "link:kindle")
        assertEquals(listOf("new"), SyncAnnotations.forSync(listOf(older, synced)).map { it.id })
    }

    @Test
    fun olderRowsOfOneAnnotationCountOnce() {
        // A changed note was kept as another row by the KOReader import.
        val rows = listOf(row("z"), row("m"), row("other", pos = "/body/p[9]"))
        assertEquals(listOf("m", "other"), SyncAnnotations.forSync(rows).map { it.id })
    }

    @Test
    fun rowsWithoutATimeAreNeverMerged() {
        val rows =
            listOf(row("a", date = ""), row("b", date = ""), row("c", device = "link:k", date = ""))
        assertEquals(listOf("a", "b", "c"), SyncAnnotations.forSync(rows).map { it.id })
    }

    @Test
    fun syncedRowsFromTwoEreadersAreBothKept() {
        val rows = listOf(row("k1", device = "link:kindle"), row("k2", device = "link:kobo"))
        assertEquals(listOf("k1", "k2"), SyncAnnotations.forSync(rows).map { it.id })
    }
}
