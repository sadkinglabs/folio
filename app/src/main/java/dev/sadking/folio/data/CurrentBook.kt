package dev.sadking.folio.data

/**
 * The book behind the centre item of the bottom bar: the one read most recently anywhere, on the
 * phone or on an e-reader through Folio Link. [readAt] is in milliseconds; [percent] is the
 * position from whichever device read it last, when that device reported one.
 */
data class CurrentBook(val book: Book, val percent: Double?, val readAt: Long)

/**
 * Picks the current book. Books marked Finished or Abandoned on the phone are skipped, so a book
 * closed on purpose does not stay in the bar. A phone position counts whatever revision it was
 * saved on, so editing a book's details does not drop it from the bar; a Folio Link report counts
 * when its KOReader document digest matches the book's file, which [digestOf] supplies (null when
 * the file cannot be read). Reports from [phoneDeviceId] are the phone's own echoes of its position
 * and are ignored. Null when nothing has been read yet.
 */
fun currentBook(
    books: List<Book>,
    phoneReading: List<PhoneReading>,
    reports: List<KosyncReport>,
    phoneDeviceId: String?,
    digestOf: (Book) -> String?,
): CurrentBook? {
    val candidates = books.filter { it.status !in listOf("Finished", "Abandoned") }
    if (candidates.isEmpty()) return null
    val phoneByBook = phoneReading.groupBy { it.bookId }
    val deviceReports =
        reports
            .filter { phoneDeviceId == null || it.deviceId != phoneDeviceId }
            .groupBy { it.document }
    return candidates
        .mapNotNull { book ->
            val phone = phoneByBook[book.id]?.maxByOrNull { it.updatedAt }
            // Digests cost a file read each; only books that could match a report pay for one.
            val report =
                if (deviceReports.isEmpty()) null
                else
                    digestOf(book)
                        ?.let(deviceReports::get)
                        ?.maxWithOrNull(
                            compareBy<KosyncReport>({ it.timestamp }, { it.receivedAt })
                        )
            val reportAt = report?.timestamp?.times(1000)
            if (phone != null && (reportAt == null || phone.updatedAt >= reportAt))
                CurrentBook(book, phone.percent, phone.updatedAt)
            else if (report != null && reportAt != null)
                CurrentBook(book, report.percentage, reportAt)
            else null
        }
        .maxWithOrNull(compareBy<CurrentBook>({ it.readAt }, { it.book.addedAt }))
}
