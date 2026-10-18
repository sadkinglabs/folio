package dev.sadking.folio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** A screen that sits above the selected tab. Sheets and dialogs are overlays, not routes. */
sealed interface Route {
    /** A book with an EPUB. */
    data class Book(val id: String) : Route

    /** A book record without an EPUB (catalogue entry). */
    data class Record(val id: String) : Route

    /** The metadata editor for a book with an EPUB. */
    data class Edit(val id: String) : Route

    /** One list's books. */
    data class List(val id: String) : Route

    /** A search source (web view) for a query. */
    data class Search(val query: String) : Route

    /** Scan a cover: camera or photo, recognised text, matches. */
    data object Discover : Route

    /** A year in review, opened from the Stats tab. */
    data class Year(val year: Int) : Route

    fun encode(): String =
        when (this) {
            is Book -> "book:$id"
            is Record -> "record:$id"
            is Edit -> "edit:$id"
            is List -> "list:$id"
            is Search -> "search:$query"
            Discover -> "discover"
            is Year -> "year:$year"
        }

    companion object {
        fun decode(value: String): Route? {
            val id = value.substringAfter(':', "")
            return when (value.substringBefore(':')) {
                "book" -> Book(id).takeIf { id.isNotEmpty() }
                "record" -> Record(id).takeIf { id.isNotEmpty() }
                "edit" -> Edit(id).takeIf { id.isNotEmpty() }
                "list" -> List(id).takeIf { id.isNotEmpty() }
                "search" -> Search(id)
                "discover" -> Discover
                "year" -> id.toIntOrNull()?.takeIf { it in 1900..9999 }?.let(::Year)
                // "settings" was a route before Settings became a tab; it is dropped on restore.
                else -> null
            }
        }
    }
}

/**
 * The app's back stack: a selected tab plus the screens opened above it. Back pops one screen;
 * choosing a tab clears the screens. Survives rotation and process death via [rememberFolioNav].
 */
class FolioNav(tab: Int = 0, routes: List<Route> = emptyList()) {
    var tab by mutableIntStateOf(if (tab == LISTS) LIBRARY else tab.coerceIn(0, TABS - 1))
        private set

    /**
     * The Library should show its Lists top tab: set when [LISTS] is asked for or restored, cleared
     * by [listsShown] once the Library has switched. Not saved; a saved 1 sets it again.
     */
    var listsRequested by mutableStateOf(tab == LISTS)
        private set

    val stack = mutableStateListOf<Route>().apply { addAll(routes) }

    val top: Route?
        get() = stack.lastOrNull()

    /** The book shown on screen, including while its editor is open. */
    val bookId: String?
        get() =
            when (val route = top) {
                is Route.Book -> route.id
                is Route.Edit -> route.id
                else -> null
            }

    val recordId: String?
        get() = (top as? Route.Record)?.id

    /** The list whose detail is on screen. */
    val listId: String?
        get() = (top as? Route.List)?.id

    val editing: Boolean
        get() = top is Route.Edit

    /** The scan-a-cover screen is on top. */
    val discovering: Boolean
        get() = top is Route.Discover

    /** The year in review on screen: it fills the window beside the rail, never a detail pane. */
    val yearShown: Int?
        get() = (top as? Route.Year)?.year

    /**
     * With two panes, the screen in the right pane: the top screen when it is a book, a record, an
     * editor or a list. The scan-a-cover screen fills the window instead.
     */
    val detail: Route?
        get() =
            when (val route = top) {
                is Route.Book,
                is Route.Record,
                is Route.Edit,
                is Route.List -> route
                else -> null
            }

    /**
     * With two panes, the list kept in the left pane while one of its books is open on the right:
     * the nearest list under a book, record or editor on top. Back pops the book as on a phone.
     */
    val listBeside: String?
        get() =
            when (detail) {
                is Route.Book,
                is Route.Record,
                is Route.Edit ->
                    (stack.dropLast(1).lastOrNull { it is Route.List } as? Route.List)?.id
                else -> null
            }

    /** Opens [route]; opening the screen already on top does nothing. */
    fun open(route: Route) {
        if (top != route) stack.add(route)
    }

    fun back(): Boolean = stack.removeLastOrNull() != null

    /** Swaps the top screen, e.g. a record that just gained its EPUB becomes that book. */
    fun replaceTop(route: Route) {
        stack.removeLastOrNull()
        open(route)
    }

    /**
     * Drops every screen above [base], or every screen when [base] is null or not open. With two
     * panes, a book chosen in the left pane takes the right pane's place instead of piling up.
     */
    fun clearAbove(base: Route?) {
        val keep = if (base == null) 0 else stack.lastIndexOf(base) + 1
        while (stack.size > keep) stack.removeAt(stack.lastIndex)
    }

    /** Shows a tab with nothing above it. [LISTS] shows the Library, on its Lists top tab. */
    fun root(tab: Int) {
        stack.clear()
        listsRequested = tab == LISTS
        this.tab = if (tab == LISTS) LIBRARY else tab.coerceIn(0, TABS - 1)
    }

    /** The Library has switched to its Lists tab. */
    fun listsShown() {
        listsRequested = false
    }

    /** Drops every screen for books or lists that no longer exist. */
    fun forget(ids: Set<String>) {
        stack.removeAll {
            when (it) {
                is Route.Book -> it.id in ids
                is Route.Record -> it.id in ids
                is Route.Edit -> it.id in ids
                is Route.List -> it.id in ids
                is Route.Search -> false
                Route.Discover -> false
                is Route.Year -> false
            }
        }
    }

    companion object {
        /**
         * Bar order: Library, Stats · tile · Device, Settings. The indices are saved, so they never
         * change; 1 ([LISTS]) is the Library's Lists top tab. The centre tile is an action, not a
         * tab.
         */
        const val TABS = 5
        const val LIBRARY = 0

        /**
         * Not a tab of its own since M12: the Library's Lists top tab. A saved or requested 1 shows
         * the Library with Lists.
         */
        const val LISTS = 1
        const val DEVICE = 2
        const val SETTINGS = 3
        const val STATS = 4

        val saver: Saver<FolioNav, List<String>> =
            Saver(
                save = { listOf(it.tab.toString()) + it.stack.map(Route::encode) },
                restore = { saved ->
                    FolioNav(
                        saved.firstOrNull()?.toIntOrNull() ?: 0,
                        saved.drop(1).mapNotNull(Route::decode),
                    )
                },
            )
    }
}

@Composable
fun rememberFolioNav(): FolioNav = rememberSaveable(saver = FolioNav.saver) { FolioNav() }
