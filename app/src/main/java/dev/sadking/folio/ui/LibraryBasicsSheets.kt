@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.sadking.folio.R
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*

@Composable
fun CatalogueEditorDialog(
    book: Book,
    busy: Boolean,
    error: String?,
    onSave: (Metadata, Uri?) -> Unit,
    onDismiss: () -> Unit,
) {
    var dirty by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    fun close() {
        if (!busy) {
            if (dirty) discard = true else onDismiss()
        }
    }
    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BackHandler { close() }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (book.title.isBlank())
                                stringResource(R.string.library_editor_add_title)
                            else stringResource(R.string.library_editor_edit_title)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { close() }, enabled = !busy) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                stringResource(R.string.library_back),
                            )
                        }
                    },
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (error != null)
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                MetadataEditor(book, busy, onDirtyChange = { dirty = it }, save = onSave)
            }
        }
    }
    if (discard)
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text(stringResource(R.string.library_discard_title)) },
            text = { Text(stringResource(R.string.library_discard_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        discard = false
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.library_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { discard = false }) {
                    Text(stringResource(R.string.library_keep_editing))
                }
            },
        )
}

@Composable
fun RemovedBooksSheet(
    entries: List<RemovedEntry>,
    busy: Boolean,
    onRestore: (String) -> Unit,
    onPurge: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var purge by remember { mutableStateOf<RemovedEntry?>(null) }
    val groups =
        entries
            .groupBy { it.groupId }
            .values
            .map { values -> values.firstOrNull { it.kind == "EPUB" } ?: values.first() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 650.dp),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.library_removed_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(stringResource(R.string.library_removed_body))
            }
            if (groups.isEmpty()) item { Text(stringResource(R.string.library_removed_empty)) }
            items(groups, key = { it.groupId }) { entry ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (entry.kind) {
                                "EPUB" -> stringResource(R.string.library_removed_kind_epub)
                                "Catalogue" ->
                                    stringResource(R.string.library_removed_kind_catalogue)
                                else -> entry.kind
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row {
                            TextButton(onClick = { onRestore(entry.groupId) }, enabled = !busy) {
                                Text(stringResource(R.string.library_removed_restore))
                            }
                            TextButton(
                                onClick = { purge = entry },
                                enabled = !busy,
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                            ) {
                                Text(stringResource(R.string.library_removed_delete))
                            }
                        }
                    }
                }
            }
        }
    }
    purge?.let { entry ->
        AlertDialog(
            onDismissRequest = { purge = null },
            title = {
                Text(stringResource(R.string.library_removed_delete_title, entry.title))
            },
            text = { Text(stringResource(R.string.library_removed_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onPurge(entry.groupId)
                        purge = null
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.library_removed_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { purge = null }) {
                    Text(stringResource(R.string.library_removed_keep))
                }
            },
        )
    }
}

/**
 * "Add to list" for the selected books: one checkbox per manual list, checked when every selected
 * book is already on it. Toggling applies at once; the sheet stays open for the next list.
 */
@Composable
fun BatchListsSheet(
    count: Int,
    shelves: List<Shelf>,
    checked: Set<String>,
    busy: Boolean,
    onList: (String, Boolean) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.library_batch_add_to_list),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                pluralStringResource(R.plurals.library_batch_count, count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(FolioSpace.small))
            val manual = shelves.filter { it.ruleField == "Manual" }
            if (manual.isEmpty())
                TextButton(onClick = onCreate) {
                    Text(stringResource(R.string.shell_create_list))
                }
            manual.forEach { shelf ->
                val on = shelf.id in checked
                Row(
                    Modifier.fillMaxWidth()
                        .toggleableCompat(on, !busy) { onList(shelf.id, it) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Checkbox(on, null, enabled = !busy)
                    Spacer(Modifier.width(FolioSpace.small))
                    Text(shelf.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** "Status" for the selected books: one radio row per status, applied on tap. */
@Composable
fun BatchStatusSheet(count: Int, busy: Boolean, onStatus: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.library_batch_status),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                pluralStringResource(R.plurals.library_batch_count, count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(FolioSpace.small))
            phoneStatuses.forEach { status ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(
                            selected = false,
                            enabled = !busy,
                            role = androidx.compose.ui.semantics.Role.RadioButton,
                        ) {
                            onStatus(status)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    RadioButton(false, null, enabled = !busy)
                    Spacer(Modifier.width(FolioSpace.small))
                    Text(libraryStatusLabel(status), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(FolioSpace.small))
            Text(
                stringResource(R.string.library_batch_body),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ImportResultsSheet(
    results: List<ImportOutcome>,
    busy: Boolean,
    onRetry: (List<Uri>) -> Unit,
    onChooseAgain: () -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 650.dp),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.library_import_results_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                TextButton(onClick = onChooseAgain, enabled = !busy) {
                    Text(stringResource(R.string.library_import_choose_again))
                }
                if (results.any { it.error != null })
                    TextButton(
                        onClick = {
                            onRetry(results.filter { it.error != null }.map { Uri.parse(it.uri) })
                        },
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.library_import_retry_failed))
                    }
            }
            items(results, key = { it.uri }) { result ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(result.name, style = MaterialTheme.typography.titleMedium)
                        Text(result.result)
                        result.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                            TextButton(
                                onClick = { onRetry(listOf(Uri.parse(result.uri))) },
                                enabled = !busy,
                            ) {
                                Text(stringResource(R.string.library_import_retry_one))
                            }
                        }
                        result.bookId?.let {
                            TextButton(onClick = { onOpen(it) }, enabled = !busy) {
                                Text(stringResource(R.string.library_import_open))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Display text for a stored list rule; the stored rule name never changes. */
@Composable
internal fun libraryRuleLabel(rule: String): String =
    when (rule) {
        "Manual" -> stringResource(R.string.library_rule_manual)
        "Status" -> stringResource(R.string.library_rule_status)
        "Language" -> stringResource(R.string.library_rule_language)
        "Series" -> stringResource(R.string.library_rule_series)
        "Tag" -> stringResource(R.string.library_rule_tag)
        "Missing on Kindle" -> stringResource(R.string.library_rule_not_on_device)
        else -> rule
    }
