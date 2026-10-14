@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R

@Composable
fun LibraryRefineSheet(
    status: String,
    files: String,
    sort: String,
    tag: String,
    tags: List<String>,
    scope: String?,
    count: Int,
    onStatus: (String) -> Unit,
    onFiles: (String) -> Unit,
    onSort: (String) -> Unit,
    onTag: (String) -> Unit,
    onClearScope: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).imePadding()) {
            Column(
                Modifier.weight(1f, false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.library_refine_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (scope != null)
                    Row {
                        Text(
                            stringResource(R.string.library_refine_list, scope),
                            Modifier.weight(1f),
                        )
                        TextButton(onClick = onClearScope) {
                            Text(stringResource(R.string.library_refine_clear_list))
                        }
                    }
                // The stored values are filter keys; only their labels are translated.
                RefineChoices(
                    stringResource(R.string.library_refine_status),
                    listOf(
                            "All",
                            "Want to Read",
                            "Unread",
                            "Reading",
                            "Finished",
                            "Paused",
                            "Abandoned",
                        )
                        .map { it to libraryStatusLabel(it) },
                    status,
                    onStatus,
                )
                RefineChoices(
                    stringResource(R.string.library_refine_files),
                    listOf(
                        "All books" to stringResource(R.string.library_files_all),
                        "With EPUB" to stringResource(R.string.library_files_with_epub),
                        "Needs EPUB" to stringResource(R.string.library_files_needs_epub),
                    ),
                    files,
                    onFiles,
                )
                RefineChoices(
                    stringResource(R.string.library_refine_sort),
                    listOf(
                        "Recent" to stringResource(R.string.library_sort_recent),
                        "Title" to stringResource(R.string.library_sort_title),
                        "Author" to stringResource(R.string.library_sort_author),
                    ),
                    sort,
                    onSort,
                )
                Text(
                    stringResource(R.string.library_refine_tags),
                    style = MaterialTheme.typography.titleMedium,
                )
                val anyTag = stringResource(R.string.library_refine_any_tag)
                OutlinedTextField(
                    tag,
                    onTag,
                    label = { Text(stringResource(R.string.library_refine_exact_tag)) },
                    placeholder = { Text(anyTag) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (tags.isNotEmpty())
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(tag.isBlank(), { onTag("") }, label = { Text(anyTag) })
                        tags
                            .filter { tag.isBlank() || it.contains(tag, true) }
                            .take(20)
                            .forEach { value ->
                                FilterChip(
                                    value.equals(tag, true),
                                    { onTag(if (value.equals(tag, true)) "" else value) },
                                    label = { Text(value) },
                                )
                            }
                    }
            }
            Row(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onReset) {
                    Text(stringResource(R.string.library_refine_reset))
                }
                Button(
                    onClick = {
                        keyboard?.hide()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(pluralStringResource(R.plurals.library_refine_show, count, count))
                }
            }
        }
    }
}

@Composable
private fun RefineChoices(
    title: String,
    values: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
