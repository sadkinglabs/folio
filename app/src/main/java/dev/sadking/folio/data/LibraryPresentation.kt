package dev.sadking.folio.data

import dev.sadking.folio.core.LibraryRules

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
    tasks: List<SyncTask>,
    observations: List<HardcoverObservation>,
    accountId: Int?,
    deviceId: String,
    paired: Boolean,
    jobState: String?,
): LibraryPresentation {
    val entries = unifiedLibrary(books, wishes)
    val fileIds = books.map { it.id }.toSet()
    val linked = wishes.filter { it.linkedBookId != null }.associateBy { it.linkedBookId }
    val cloud = observations.filter { it.accountId == accountId }.associateBy { it.hardcoverId }
    val deviceTransfers = transfers.filter { it.deviceId == deviceId }.associateBy { it.bookId }
    val latest =
        snapshots
            .filter { it.deviceId == deviceId }
            .groupBy { it.bookId }
            .mapValues { (_, values) -> values.maxBy { it.fetchedAt } }
    val pending = tasks.associate { task ->
        task.bookId to
            if (jobState in listOf("FAILED", "PAUSED")) task.copy(state = "PAUSED") else task
    }
    val signals =
        books.associate { book ->
            val wish = linked[book.id]
            book.id to
                linkedReadingSignals(
                    bookSignals(
                        book,
                        paired,
                        deviceTransfers[book.id],
                        latest[book.id],
                        pending[book.id],
                        wish?.hardcoverId != null,
                    ),
                    wish,
                    cloud[wish?.hardcoverId],
                )
        } +
            wishes
                .filter { it.linkedBookId !in fileIds }
                .associate { it.id to catalogueSignals(it, cloud[it.hardcoverId]) }
    val verified =
        books.filter { deviceTransfers[it.id]?.hash == it.currentHash }.map { it.id }.toSet()
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
                        book.id in verified,
                    )
            }
    }
    return LibraryPresentation(entries, fileIds, signals, scoped)
}
