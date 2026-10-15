package dev.sadking.folio.data

/** How many covers a list card's mosaic shows at most. */
const val MOSAIC_COVERS = 4

/**
 * One card on the Lists tab, and the data behind its detail route. System lists (Want to Read,
 * Currently Reading, Finished) are views of reading status; user lists are stored [Shelf]s.
 */
data class ListCard(
    /** The route id: [systemListId] for a system list, the shelf id otherwise. */
    val id: String,
    val name: String,
    /** The list's books, in library order. */
    val books: List<Book>,
    val system: ReadingList? = null,
    val shelf: Shelf? = null,
) {
    val count: Int
        get() = books.size

    /** A stored rule other than Manual, as "field" to "value". */
    val rule: Pair<String, String>?
        get() = shelf?.takeIf { it.ruleField != "Manual" }?.let { it.ruleField to it.ruleValue }

    val syncEnabled: Boolean
        get() = shelf?.syncEnabled == true

    /** The covers for the mosaic: books with artwork first, so a list with one cover shows it. */
    val covers: List<Book>
        get() = mosaicCovers(books)
}

/** The route id of a system list; a shelf id is never shaped like this. */
fun systemListId(list: ReadingList): String = "reading:${list.name}"

/** The system list a route id names, or null for a shelf id. */
fun systemListOf(id: String): ReadingList? =
    id.removePrefix("reading:")
        .takeIf { it != id }
        ?.let { name -> ReadingList.entries.firstOrNull { it.name == name } }

/** Up to [limit] books for a mosaic: those with artwork first, then the rest, order kept. */
fun mosaicCovers(books: List<Book>, limit: Int = MOSAIC_COVERS): List<Book> =
    (books.filter { it.coverPath != null } + books.filter { it.coverPath == null }).take(limit)

/** The order of the system lists on the Lists tab: what is being read first. */
val LIST_TAB_ORDER = listOf(ReadingList.READING, ReadingList.FINISHED, ReadingList.WANT_TO_READ)

/** The cards of the Lists tab: the three system lists first, then the user's lists in order. */
fun listCards(shelves: List<Shelf>, library: LibraryPresentation): List<ListCard> =
    LIST_TAB_ORDER.map { list ->
        ListCard(
            systemListId(list),
            list.title,
            library.entries.filter { list.contains(it, library.signals[it.id]) },
            system = list,
        )
    } +
        shelves.map { shelf ->
            ListCard(shelf.id, shelf.name, library.shelfEntries[shelf.id].orEmpty(), shelf = shelf)
        }

/** The card a route id names, or null when that list no longer exists. */
fun listCard(id: String, shelves: List<Shelf>, library: LibraryPresentation): ListCard? =
    listCards(shelves, library).firstOrNull { it.id == id }
