package dev.sadking.folio.data

/** Presentation derives from independent observations; it never rewrites a reading source. */
data class BookSignals(
    val device: String,
    val reading: String,
    val progress: Double?,
    val hardcover: String?,
    val deviceVerifiedAt: Long? = null,
    val readingFetchedAt: Long? = null,
)

fun bookSignals(
    book: Book,
    paired: Boolean,
    transfer: Transfer?,
    reading: ReadingSnapshot?,
    task: SyncTask?,
    hardcoverLinked: Boolean,
): BookSignals {
    val device =
        when {
            task?.state == "PAUSED" -> "Transfer paused"
            task?.state == "FAILED" -> "Transfer failed"
            task?.state == "RUNNING" -> "Sending"
            task?.state == "QUEUED" -> "Queued"
            transfer != null && transfer.hash != book.currentHash -> "Needs update"
            transfer != null -> "On Kindle"
            paired -> "Not sent"
            else -> "Phone only"
        }
    val status =
        when {
            reading?.status == "complete" -> "Finished · Kindle"
            reading?.status == "reading" -> "Reading · Kindle"
            book.status != "Unread" -> "${book.status} · phone"
            else -> "Unread"
        }
    return BookSignals(
        device,
        status,
        reading?.percent?.takeIf { it.isFinite() && it in 0.0..1.0 },
        if (hardcoverLinked) "Hardcover matched" else null,
        transfer?.completedAt,
        reading?.fetchedAt,
    )
}
