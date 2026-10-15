package dev.sadking.folio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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

    fun encode(): String =
        when (this) {
            is Book -> "book:$id"
            is Record -> "record:$id"
            is Edit -> "edit:$id"
            is List -> "list:$id"
            is Search -> "search:$query"
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
    var tab by mutableIntStateOf(tab.coerceIn(0, TABS - 1))
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

    /** Shows a tab with nothing above it. */
    fun root(tab: Int) {
        stack.clear()
        this.tab = tab.coerceIn(0, TABS - 1)
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
            }
        }
    }

    companion object {
        /**
         * Four tabs in the bar; the centre item between Lists and Device is an action, not a tab.
         */
        const val TABS = 4
        const val LIBRARY = 0
        const val LISTS = 1
        const val DEVICE = 2
        const val SETTINGS = 3

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
