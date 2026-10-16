package dev.sadking.folio.sync

import dev.sadking.folio.core.sync.SyncAnnotation
import dev.sadking.folio.data.BookAnnotation
import dev.sadking.folio.data.LINK_DEVICE_PREFIX

/**
 * Annotations between Room ([BookAnnotation]) and sync ([SyncAnnotation]). The id is the same on
 * both. Rows sync adds carry the device id `link:<e-reader id>`.
 *
 * Rows from before sync came from KOReader over SSH, with ids of their own. The e-reader reports
 * the same highlight under its sync id. Such an older row is the same annotation as a synced row
 * with the same time and start, so it is left out of what sync sees once that synced row exists: it
 * is kept in Room, never deleted, and never sent twice.
 */
object SyncAnnotations {
    private val KINDS = setOf("highlight", "note", "bookmark")

    fun toSync(row: BookAnnotation): SyncAnnotation =
        SyncAnnotation(
            id = row.id,
            text = row.text,
            note = row.note,
            chapter = row.chapter,
            datetime = row.date,
            pos0 = row.position,
            pos1 = row.endPosition,
            kind = row.kind.takeIf { it in KINDS } ?: "highlight",
        )

    fun toRow(annotation: SyncAnnotation, bookId: String, ereaderId: String): BookAnnotation =
        BookAnnotation(
            id = annotation.id,
            bookId = bookId,
            deviceId = LINK_DEVICE_PREFIX + ereaderId,
            text = annotation.text,
            note = annotation.note,
            chapter = annotation.chapter,
            date = annotation.datetime,
            position = annotation.pos0,
            endPosition = annotation.pos1,
            kind = annotation.kind,
        )

    /**
     * One book's annotations as sync sees them, in id order. Older rows with the same time and
     * start as another row count once: not at all when a synced row has them, else the first by id.
     * Rows without a time are never merged.
     */
    fun forSync(rows: List<BookAnnotation>): List<SyncAnnotation> = visible(rows).map(::toSync)

    /** The rows of one book that [forSync] keeps, in id order. The screens can show the same. */
    fun visible(rows: List<BookAnnotation>): List<BookAnnotation> {
        val (synced, older) = rows.sortedBy { it.id }.partition { it.isSynced() }
        val syncedKeys = synced.mapNotNull(::key).toSet()
        val kept =
            older
                .filter { row -> key(row)?.let { it !in syncedKeys } ?: true }
                .groupBy { key(it) ?: "id:" + it.id }
                .values
                .map { it.first() }
        return (synced + kept).sortedBy { it.id }
    }

    private fun BookAnnotation.isSynced() = deviceId.startsWith(LINK_DEVICE_PREFIX)

    /** Same time, same start: the same annotation. Null without a time. */
    private fun key(row: BookAnnotation): String? =
        row.date.trim().takeIf { it.isNotEmpty() }?.let { it + "\n" + row.position.trim() }
}
