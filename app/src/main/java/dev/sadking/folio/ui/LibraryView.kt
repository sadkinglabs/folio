package dev.sadking.folio.ui

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext

/** Library layout and sort order, kept in preferences so they survive restarts. */
internal class LibraryView(private val prefs: SharedPreferences) {
    val grid = mutableStateOf(prefs.getBoolean(GRID, true))
    val sort = mutableStateOf(prefs.getString(SORT, "Recent") ?: "Recent")

    fun save() = prefs.edit().putBoolean(GRID, grid.value).putString(SORT, sort.value).apply()

    companion object {
        const val PREFERENCES = "library-view"
        private const val GRID = "grid"
        private const val SORT = "sort"
    }
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
