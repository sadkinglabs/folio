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
import androidx.compose.ui.unit.dp

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
                Text("Refine library", style = MaterialTheme.typography.headlineSmall)
                if (scope != null)
                    Row {
                        Text("List: $scope", Modifier.weight(1f))
                        TextButton(onClick = onClearScope) { Text("Clear list") }
                    }
                RefineChoices(
                    "Reading status",
                    listOf(
                        "All",
                        "Want to Read",
                        "Unread",
                        "Reading",
                        "Finished",
                        "Paused",
                        "Abandoned",
                    ),
                    status,
                    onStatus,
                )
                RefineChoices(
                    "Files",
                    listOf("All books", "With EPUB", "Needs EPUB"),
                    files,
                    onFiles,
                )
                RefineChoices("Sort", listOf("Recent", "Title", "Author"), sort, onSort)
                Text("Tags", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    tag,
                    onTag,
                    label = { Text("Exact tag") },
                    placeholder = { Text("Any tag") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (tags.isNotEmpty())
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(tag.isBlank(), { onTag("") }, label = { Text("Any tag") })
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
                OutlinedButton(onClick = onReset) { Text("Reset") }
                Button(
                    onClick = {
                        keyboard?.hide()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (count == 1) "Show 1 book" else "Show $count books")
                }
            }
        }
    }
}

@Composable
private fun RefineChoices(
    title: String,
    values: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { value ->
            FilterChip(value == selected, { onSelect(value) }, label = { Text(value) })
        }
    }
}
