package dev.sadking.folio.data

import dev.sadking.folio.core.LibraryRules
import java.io.File

data class LibraryPresentation(
    val entries: List<Book>,
    val fileIds: Set<String>,
    val signals: Map<String, BookSignals>,
    val shelfEntries: Map<String, List<Book>>,
)

/**
 * One presentation projection for browsing, lists and activity. Does not persist merged source
 * state.
 */
fun presentLibrary(
    books: List<Book>,
    wishes: List<WishBook>,
    shelves: List<Shelf>,
    members: List<Membership>,
    wishMembers: List<WishMembership>,
    transfers: List<Transfer>,
    snapshots: List<ReadingSnapshot>,
    observations: List<HardcoverObservation>,
    accountId: Int?,
    deviceId: String,
    baselines: List<SyncBaseline> = emptyList(),
): LibraryPresentation {
    val entries = unifiedLibrary(books, wishes)
    val fileIds = books.map { it.id }.toSet()
    val linked = wishes.filter { it.linkedBookId != null }.associateBy { it.linkedBookId }
    val cloud = observations.filter { it.accountId == accountId }.associateBy { it.hardcoverId }
    // A book is on the e-reader when the last sync left its current file there (the baseline),
    // or, from before sync, when it went there over SSH or the first plugin confirmed it.
    val currentHash = books.associate { it.id to it.currentHash }
    val onEreader =
        (transfers
                .filter { it.deviceId == deviceId || it.deviceId.startsWith(LINK_DEVICE_PREFIX) }
                .filter { it.hash == currentHash[it.bookId] }
                .map { it.bookId } +
                baselines.filter { it.hash == currentHash[it.bookId] }.map { it.bookId })
            .toSet()
    val latest =
        snapshots
            .filter { it.deviceId == deviceId }
            .groupBy { it.bookId }
            .mapValues { (_, values) -> values.maxBy { it.fetchedAt } }
    val signals =
        books.associate { book ->
            val wish = linked[book.id]
            book.id to
                linkedReadingSignals(
                    bookSignals(book, latest[book.id], wish?.hardcoverId != null),
                    wish,
                    cloud[wish?.hardcoverId],
                )
        } +
            wishes
                .filter { it.linkedBookId !in fileIds }
                .associate { it.id to catalogueSignals(it, cloud[it.hardcoverId]) }
    val wishById = wishes.associateBy { it.id }
    val membership =
        members.groupBy { it.shelfId }.mapValues { it.value.map { row -> row.bookId }.toSet() }
    val catalogueMembership =
        wishMembers.groupBy { it.shelfId }.mapValues { it.value.map { row -> row.wishId }.toSet() }
    val scoped = shelves.associate { shelf ->
        shelf.id to
            entries.filter { book ->
                if (shelf.ruleField == "Manual")
                    book.id in membership[shelf.id].orEmpty() ||
                        book.id in catalogueMembership[shelf.id].orEmpty()
                else if (book.id !in fileIds && shelf.ruleField == "Missing on Kindle") false
                else
                    LibraryRules.matches(
                        shelf.ruleField,
                        shelf.ruleValue,
                        book.metadata(),
                        if (book.id in fileIds) book.status
                        else
                            wishById[book.id]?.status?.takeIf { it.isNotBlank() }
                                ?: cloud[wishById[book.id]?.hardcoverId]?.let {
                                    hardcoverStatus(it.status)
                                }
                                ?: "Want to Read",
                        book.id in onEreader,
                    )
            }
    }
    return LibraryPresentation(entries, fileIds, signals, scoped)
}

/**
 * Ids of books whose recorded file is gone (see [bookFileMissing]). Reads the disk: call it off the
 * main thread, never from [presentLibrary].
 */
fun missingFileIds(
    books: List<Book>,
    isFile: (String) -> Boolean = { File(it).isFile },
): Set<String> = books.filter { bookFileMissing(it, isFile) }.map { it.id }.toSet()
