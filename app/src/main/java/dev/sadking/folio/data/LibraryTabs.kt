package dev.sadking.folio.data

/** The tabs at the top of the Library screen. */
enum class LibraryTab {
    /** Books you own: every entry with an EPUB, plus records you are reading or have read. */
    LIBRARY,
    /** Books you intend to get, with or without an EPUB, newest first. */
    WANT_TO_READ,
    /** The reading lists and your own lists. It shows no books itself. */
    LISTS,
}

/** The Library screen's contents, split once from one list of entries. */
data class LibraryTabs(
    /** Books in progress for the "Reading" shelf, the current book first. */
    val reading: List<Book>,
    /** The Library tab's grid, in the order the entries came. */
    val library: List<Book>,
    /** The Want to read tab's grid, newest first. */
    val wanted: List<Book>,
)

/** The reading status the user sees: the e-reader's or the phone's report, else the stored one. */
fun readingStatusOf(book: Book, signals: BookSignals?): String =
    signals?.reading?.substringBefore(" ·") ?: book.status

/**
 * Splits the unified library into the two tabs and the Reading shelf. An entry is never dropped: a
 * Want to Read book with an EPUB sits in both tabs, because it is owned and wanted. Records without
 * an EPUB stay out of the Library tab unless they are being read or have been read, so a book
 * tracked without a file keeps a home.
 */
fun libraryTabs(
    entries: List<Book>,
    signals: Map<String, BookSignals>,
    fileIds: Set<String>,
    currentId: String?,
): LibraryTabs {
    val status = entries.associate { it.id to readingStatusOf(it, signals[it.id]) }
    val wanted =
        entries
            .filter { status.getValue(it.id) == "Want to Read" }
            .sortedByDescending { it.addedAt }
    val library = entries.filter { it.id in fileIds || status.getValue(it.id) != "Want to Read" }
    val reading = library.filter { status.getValue(it.id) == "Reading" }
    val ordered = reading.filter { it.id == currentId } + reading.filter { it.id != currentId }
    return LibraryTabs(ordered, library, wanted)
}

/**
 * The progress to show on a cover: the newer of the phone's own position and the e-reader's report,
 * when either exists. Neither source is rewritten; this only chooses which one to display.
 */
fun readingProgress(phone: PhoneReading?, signals: BookSignals?): Double? {
    val device = signals?.progress
    val own = phone?.percent?.takeIf { it.isFinite() && it in 0.0..1.0 }
    return when {
        own == null -> device
        device == null -> own
        (signals.readingFetchedAt ?: 0L) > phone!!.updatedAt -> device
        else -> own
    }
}

/** The latest phone position per book, for [readingProgress]. */
fun latestPhoneReading(phoneReading: List<PhoneReading>): Map<String, PhoneReading> =
    phoneReading.groupBy { it.bookId }.mapValues { (_, rows) -> rows.maxBy { it.updatedAt } }

/** How many filters are active, for the dot on the Filter icon. The search text is not a filter. */
fun activeLibraryFilters(status: String, files: String, tag: String): Int =
    listOf(status != "All", files != "All books", tag.isNotBlank()).count { it }
