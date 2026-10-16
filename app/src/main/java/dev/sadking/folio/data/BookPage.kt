package dev.sadking.folio.data

/** What the book page's one primary button does. */
enum class BookPrimary {
    /** Open the reader at the saved place. */
    CONTINUE,
    /** Open the reader from the start. */
    READ,
    /** A record without an EPUB: search for one. */
    FIND_EPUB,
}

fun bookPrimary(hasEpub: Boolean, phone: PhoneReading?): BookPrimary =
    when {
        !hasEpub -> BookPrimary.FIND_EPUB
        phone?.percent?.takeIf { it.isFinite() && it > 0.0 } != null -> BookPrimary.CONTINUE
        else -> BookPrimary.READ
    }

/** The icon actions under a book, in order. A record loses Share and gains Attach. */
enum class BookPageAction {
    ATTACH,
    EDIT,
    PROGRESS,
    HARDCOVER,
    SHARE,
    REMOVE,
}

fun bookPageActions(hasEpub: Boolean): List<BookPageAction> =
    if (hasEpub)
        listOf(
            BookPageAction.EDIT,
            BookPageAction.PROGRESS,
            BookPageAction.HARDCOVER,
            BookPageAction.SHARE,
            BookPageAction.REMOVE,
        )
    else
        listOf(
            BookPageAction.ATTACH,
            BookPageAction.EDIT,
            BookPageAction.PROGRESS,
            BookPageAction.HARDCOVER,
            BookPageAction.REMOVE,
        )

/** The book page's overflow menu. Every item needs the EPUB, so a record has no menu. */
enum class BookOverflow {
    EXTERNAL_READER,
    SAVE_COPY,
    DETACH,
    EXPORT_NOTES,
}

fun bookOverflow(hasEpub: Boolean, attached: Boolean, hasNotes: Boolean): List<BookOverflow> =
    if (!hasEpub) emptyList()
    else
        listOfNotNull(
            BookOverflow.EXTERNAL_READER,
            BookOverflow.SAVE_COPY,
            BookOverflow.DETACH.takeIf { attached },
            BookOverflow.EXPORT_NOTES.takeIf { hasNotes },
        )

/**
 * The quick actions a long-press on a library cover offers, without opening the page: five icons
 * for a book, six for a record, then a row to the page itself.
 */
fun quickBookActions(hasEpub: Boolean): List<BookAction> =
    if (hasEpub)
        listOf(
            BookAction.OPEN_READER,
            BookAction.LISTS,
            BookAction.STATUS,
            BookAction.SELECT,
            BookAction.REMOVE,
        )
    else
        listOf(
            BookAction.SEARCH,
            BookAction.ATTACH,
            BookAction.LISTS,
            BookAction.STATUS,
            BookAction.SELECT,
            BookAction.REMOVE,
        )

/** Where a position came from. */
enum class ProgressSource {
    PHONE,
    EREADER,
    HARDCOVER,
}

/**
 * One source's position for the status strip and the Progress sheet. [percent] is 0..1; [page] is
 * Hardcover's page; [at] is when the source was last seen, in milliseconds. [device] names the
 * e-reader when it is known.
 */
data class ProgressEntry(
    val source: ProgressSource,
    val percent: Double? = null,
    val page: Int? = null,
    val at: Long? = null,
    val device: String? = null,
)

/**
 * Every position known for a book, phone first, e-readers newest first, Hardcover last. A record
 * has no phone position. The SSH snapshot and Folio Link reports are kept apart: each e-reader that
 * reported gets its own row, and nothing is merged or rewritten.
 */
fun progressEntries(
    hasEpub: Boolean,
    phone: PhoneReading?,
    device: ReadingSnapshot?,
    reports: List<KosyncReport>,
    hardcoverLinked: Boolean,
    hardcoverPage: Int? = null,
): List<ProgressEntry> {
    val own =
        phone
            ?.takeIf { hasEpub }
            ?.let {
                ProgressEntry(
                    ProgressSource.PHONE,
                    it.percent?.takeIf { p -> p.isFinite() && p in 0.0..1.0 },
                    at = it.updatedAt,
                )
            }
    val ereaders =
        listOfNotNull(
            device?.let {
                ProgressEntry(
                    ProgressSource.EREADER,
                    it.percent?.takeIf { p -> p.isFinite() && p in 0.0..1.0 },
                    at = it.fetchedAt,
                )
            }
        ) +
            reports.map {
                ProgressEntry(
                    ProgressSource.EREADER,
                    it.percentage.takeIf { p -> p.isFinite() && p in 0.0..1.0 },
                    at = it.timestamp * 1000,
                    device = it.device,
                )
            }
    val hardcover =
        if (hardcoverLinked) ProgressEntry(ProgressSource.HARDCOVER, page = hardcoverPage) else null
    return listOfNotNull(own) +
        ereaders.sortedByDescending { it.at ?: 0L } +
        listOfNotNull(hardcover)
}

/** The e-reader that is furthest ahead of the phone, by more than half a percent; else null. */
fun aheadOfPhone(entries: List<ProgressEntry>): ProgressEntry? {
    val own = entries.firstOrNull { it.source == ProgressSource.PHONE }?.percent ?: 0.0
    return entries
        .filter { it.source == ProgressSource.EREADER && it.percent != null }
        .filter { it.percent!! > own + 0.005 }
        .maxByOrNull { it.percent!! }
}

/**
 * The average of opaque pixels, as ARGB with full alpha, for the glow behind a cover. A 16-pixel
 * downsample is enough: the glow is soft and only needs the cover's general tone. Transparent
 * pixels are skipped; with none left, the result is mid grey.
 */
fun averageArgb(pixels: IntArray): Int {
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0
    for (p in pixels) {
        if ((p ushr 24) < 128) continue
        r += (p shr 16) and 0xFF
        g += (p shr 8) and 0xFF
        b += p and 0xFF
        n++
    }
    if (n == 0) return 0xFF808080.toInt()
    return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
}
