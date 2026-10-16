package dev.sadking.folio.data

/** Presentation only. Catalogue entries never become transfer inputs. */
fun WishBook.displayBook(): Book =
    Book(
            id,
            title,
            authors,
            originalPath = "",
            currentPath = "",
            coverPath = coverPath,
            originalHash = "",
            currentHash = "",
            addedAt = addedAt,
            status = status.ifBlank { "Want to Read" },
        )
        .let { book ->
            if (details == "{}") book
            else
                book.withMetadata(
                    parseMetadata(org.json.JSONObject(details))
                        .copy(title = title, authors = decode(authors))
                )
        }

fun unifiedLibrary(books: List<Book>, wishes: List<WishBook>): List<Book> {
    val owned = books.map { it.id }.toSet()
    val visibleBooks = books.map { book ->
        book.copy(
            coverPath =
                book.coverPath ?: wishes.firstOrNull { it.linkedBookId == book.id }?.coverPath
        )
    }
    return (visibleBooks + wishes.filter { it.linkedBookId !in owned }.map { it.displayBook() })
        .sortedByDescending { it.addedAt }
}

fun hardcoverStatus(status: Int): String =
    when (status) {
        1 -> "Want to Read"
        2 -> "Reading"
        3 -> "Finished"
        4 -> "Paused"
        5 -> "Abandoned"
        else -> "Status $status"
    }

fun catalogueSignals(wish: WishBook, observation: HardcoverObservation?): BookSignals =
    BookSignals(
        if (wish.status.isNotBlank()) "${wish.status} · phone"
        else observation?.let { "${hardcoverStatus(it.status)} · Hardcover" } ?: "Want to Read",
        null,
        if (wish.hardcoverId != null) "Hardcover matched" else null,
    )

fun requireAttachment(wish: WishBook, book: Book, wishes: List<WishBook>) {
    require(wish.linkedBookId == null || wish.linkedBookId == book.id) {
        "This entry already has an EPUB; its attachment was retained"
    }
    require(wishes.none { it.id != wish.id && it.linkedBookId == book.id }) {
        "This EPUB already belongs to another catalogue entry; open that book to review its match"
    }
}

data class LocalRemovalPlan(
    val bookIds: Set<String>,
    val wishIds: Set<String>,
    val visibleCount: Int,
)

fun localRemovalPlan(
    ids: Set<String>,
    books: List<Book>,
    wishes: List<WishBook>,
): LocalRemovalPlan {
    val owned = books.map { it.id }.toSet()
    val booksToRemove =
        ids.filter { it in owned }.toSet() +
            wishes
                .filter { it.id in ids && it.linkedBookId in owned }
                .mapNotNull { it.linkedBookId }
    val wishesToRemove =
        wishes.filter { it.id in ids || it.linkedBookId in booksToRemove }.map { it.id }.toSet()
    val unowned = wishes.count { it.id in wishesToRemove && it.linkedBookId !in booksToRemove }
    return LocalRemovalPlan(booksToRemove, wishesToRemove, booksToRemove.size + unowned)
}

enum class BookAction {
    OPEN,
    SELECT,
    PROGRESS,
    EDIT,
    LISTS,
    STATUS,
    EXPORT_NOTES,
    ATTACH,
    SEARCH,
    REMOVE,
    DETACH,
    EXPORT_EPUB,
    SHARE_EPUB,
    OPEN_READER,
    EXTERNAL_READER,
}

fun availableBookActions(hasEpub: Boolean, hasAnnotations: Boolean = false): List<BookAction> =
    if (hasEpub)
        listOf(
            BookAction.OPEN,
            BookAction.OPEN_READER,
            BookAction.SELECT,
            BookAction.PROGRESS,
            BookAction.EDIT,
            BookAction.LISTS,
            BookAction.STATUS,
            BookAction.DETACH,
            BookAction.EXPORT_EPUB,
            BookAction.SHARE_EPUB,
            BookAction.EXTERNAL_READER,
        ) +
            (if (hasAnnotations) listOf(BookAction.EXPORT_NOTES) else emptyList()) +
            BookAction.REMOVE
    else
        listOf(
            BookAction.OPEN,
            BookAction.SELECT,
            BookAction.ATTACH,
            BookAction.EDIT,
            BookAction.STATUS,
            BookAction.SEARCH,
            BookAction.PROGRESS,
            BookAction.LISTS,
            BookAction.REMOVE,
        )

val phoneStatuses = listOf("Want to Read", "Unread", "Reading", "Finished", "Paused", "Abandoned")
