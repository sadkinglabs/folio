@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R

internal const val LIBRARY_FILTER_SHEET_TAG = "library-filter-sheet"

/**
 * The Filter sheet: three chip rows (Status, Files, Tag) and a footer with Reset and "Show N
 * books". Filters last for the session. Sorting lives in the toolbar, not here.
 */
@Composable
fun LibraryFilterSheet(
    status: String,
    files: String,
    tag: String,
    tags: List<String>,
    count: Int,
    showStatus: Boolean,
    onStatus: (String) -> Unit,
    onFiles: (String) -> Unit,
    onTag: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.testTag(LIBRARY_FILTER_SHEET_TAG),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp)) {
            Column(
                Modifier.weight(1f, false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = FolioSpace.section),
                verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
            ) {
                Text(
                    stringResource(R.string.library_filter),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                // The stored values are filter keys; only their labels are translated.
                if (showStatus)
                    FilterChoices(
                        stringResource(R.string.library_filter_status),
                        listOf("All", "Unread", "Reading", "Finished", "Paused", "Abandoned").map {
                            it to libraryStatusLabel(it)
                        },
                        status,
                        onStatus,
                    )
                FilterChoices(
                    stringResource(R.string.library_filter_files),
                    listOf(
                        "All books" to stringResource(R.string.library_status_all),
                        "With EPUB" to stringResource(R.string.library_files_with_epub),
                        "Needs EPUB" to stringResource(R.string.library_files_needs_epub),
                    ),
                    files,
                    onFiles,
                )
                if (tags.isNotEmpty())
                    FilterChoices(
                        stringResource(R.string.library_filter_tag),
                        listOf("" to stringResource(R.string.library_filter_any_tag)) +
                            tags.take(30).map { it to it },
                        tags.firstOrNull { it.equals(tag, true) } ?: "",
                        onTag,
                    )
            }
            Row(
                Modifier.fillMaxWidth().padding(FolioSpace.section),
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
            ) {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.library_filter_reset))
                }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(pluralStringResource(R.plurals.library_filter_show, count, count))
                }
            }
        }
    }
}

@Composable
private fun FilterChoices(
    title: String,
    values: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        values.forEach { (value, label) ->
            FilterChip(value == selected, { onSelect(value) }, label = { Text(label) })
        }
    }
}

/** Display text for a stored reading status; unknown values are shown as stored. */
@Composable
internal fun libraryStatusLabel(status: String): String =
    when (status) {
        "All" -> stringResource(R.string.library_status_all)
        "Want to Read" -> stringResource(R.string.library_status_want_to_read)
        "Unread" -> stringResource(R.string.library_status_unread)
        "Reading" -> stringResource(R.string.library_status_reading)
        "Finished" -> stringResource(R.string.library_status_finished)
        "Paused" -> stringResource(R.string.library_status_paused)
        "Abandoned" -> stringResource(R.string.library_status_abandoned)
        else -> status
    }
