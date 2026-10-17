package dev.sadking.folio.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

/**
 * The app's one search bar: Material's docked search bar, as the Library first used it. Every
 * search field in the app is this one, so they look and behave alike. [modifier] places the bar;
 * [fieldModifier] goes on the text field itself (focus, test tags). [onSheet]: on a sheet, whose
 * colour is the bar's own, the bar takes the next tone up so its shape still shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolioSearchBar(
    query: String,
    onQuery: (String) -> Unit,
    hint: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    onSearch: (String) -> Unit = {},
    onClear: () -> Unit = { onQuery("") },
    onSheet: Boolean = false,
) {
    DockedSearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQuery,
                onSearch = onSearch,
                expanded = false,
                onExpandedChange = {},
                placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty())
                        IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, clearLabel) }
                },
                modifier = fieldModifier.semantics { contentDescription = hint },
            )
        },
        expanded = false,
        onExpandedChange = {},
        modifier = modifier,
        colors =
            if (onSheet)
                SearchBarDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            else SearchBarDefaults.colors(),
    ) {}
}
