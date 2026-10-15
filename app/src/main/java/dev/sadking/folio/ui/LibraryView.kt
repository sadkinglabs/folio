package dev.sadking.folio.ui

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext

/** Library layout, sort order and recent searches, kept in preferences across restarts. */
internal class LibraryView(private val prefs: SharedPreferences) {
    val grid = mutableStateOf(prefs.getBoolean(GRID, true))
    val sort = mutableStateOf(prefs.getString(SORT, "Recent") ?: "Recent")
    val recent =
        mutableStateOf(
            prefs.getString(RECENT, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        )

    fun save() = prefs.edit().putBoolean(GRID, grid.value).putString(SORT, sort.value).apply()

    /** Keeps [query] as the newest recent search. */
    fun searched(query: String) {
        recent.value = withRecent(recent.value, query)
        prefs.edit().putString(RECENT, recent.value.joinToString("\n")).apply()
    }

    fun clearRecent() {
        recent.value = emptyList()
        prefs.edit().remove(RECENT).apply()
    }

    companion object {
        const val PREFERENCES = "library-view"
        private const val GRID = "grid"
        private const val SORT = "sort"
        private const val RECENT = "recent-searches"
    }
}

/** [query] first, without a duplicate that differs only in case, at most [max] searches. */
internal fun withRecent(recent: List<String>, query: String, max: Int = 5): List<String> {
    val clean = query.trim().replace('\n', ' ').take(100)
    if (clean.isEmpty()) return recent
    return (listOf(clean) + recent.filterNot { it.equals(clean, ignoreCase = true) }).take(max)
}

@Composable
internal fun rememberLibraryView(): LibraryView {
    val context = LocalContext.current
    val view = remember {
        LibraryView(context.getSharedPreferences(LibraryView.PREFERENCES, 0))
    }
    LaunchedEffect(view) {
        snapshotFlow { view.grid.value to view.sort.value }.collect { view.save() }
    }
    return view
}
