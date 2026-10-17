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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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

internal const val REMOVED_SHEET_TAG = "removed-sheet"

/** One row per removed book: the EPUB entry stands for its group when the group has one. */
internal fun removedGroups(entries: List<RemovedEntry>): List<RemovedEntry> =
    entries
        .groupBy { it.groupId }
        .values
        .map { values -> values.firstOrNull { it.kind == "EPUB" } ?: values.first() }

/**
 * Recently removed: rows with a cover, the title, Restore and a trash icon. Deleting for good is a
 * separate, confirmed step; nothing here is destructive on its own.
 */
@Composable
fun RemovedBooksSheet(
    entries: List<RemovedEntry>,
    busy: Boolean,
    onRestore: (String) -> Unit,
    onPurge: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var purge by remember { mutableStateOf<RemovedEntry?>(null) }
    FolioSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        RemovedBooksContent(entries, busy, onRestore, onDelete = { purge = it })
    }
    purge?.let { entry ->
        ConfirmSheet(
            covers = listOf(coverStub(entry.title)),
            title = stringResource(R.string.library_removed_delete_title),
            body = stringResource(R.string.library_removed_delete_body),
            confirmLabel = stringResource(R.string.library_removed_delete),
            dismissLabel = stringResource(R.string.library_removed_keep),
            destructive = true,
            busy = busy,
            onConfirm = {
                onPurge(entry.groupId)
                purge = null
            },
            onDismiss = { purge = null },
        )
    }
}

@Composable
internal fun RemovedBooksContent(
    entries: List<RemovedEntry>,
    busy: Boolean,
    onRestore: (String) -> Unit,
    onDelete: (RemovedEntry) -> Unit,
) {
    val groups = removedGroups(entries)
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 650.dp).testTag(REMOVED_SHEET_TAG),
        contentPadding =
            PaddingValues(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp),
    ) {
        item {
            Text(
                stringResource(R.string.library_removed_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = FolioSpace.small).semantics { heading() },
            )
        }
        if (groups.isEmpty())
            item {
                EmptyState(
                    stringResource(R.string.library_removed_empty),
                    stringResource(R.string.library_removed_empty_body),
                    Icons.Outlined.DeleteOutline,
                    illustration = FolioIllustration.Books,
                )
            }
        items(groups, key = { it.groupId }) { entry ->
            ResultRow(
                entry.title,
                "",
                detail =
                    when (entry.kind) {
                        "EPUB" -> stringResource(R.string.library_removed_kind_epub)
                        "Catalogue" -> stringResource(R.string.library_removed_kind_catalogue)
                        else -> entry.kind
                    },
            ) {
                FilledTonalButton(onClick = { onRestore(entry.groupId) }, enabled = !busy) {
                    Text(stringResource(R.string.library_removed_restore))
                }
                IconButton(onClick = { onDelete(entry) }, enabled = !busy) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        stringResource(R.string.library_removed_delete_title),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
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
    FolioSheet(
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
                modifier = Modifier.semantics { heading() },
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
    FolioSheet(
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
                modifier = Modifier.semantics { heading() },
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

internal const val IMPORT_RESULTS_TAG = "import-results"

/**
 * What happened to each chosen file: a check or an error icon, the file name, one line, and Open or
 * Retry on the row. Several failures get one "Retry all" at the end.
 */
@Composable
fun ImportResultsSheet(
    results: List<ImportOutcome>,
    busy: Boolean,
    onRetry: (List<Uri>) -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    FolioSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        ImportResultsContent(results, busy, onRetry, onOpen)
    }
}

@Composable
internal fun ImportResultsContent(
    results: List<ImportOutcome>,
    busy: Boolean,
    onRetry: (List<Uri>) -> Unit,
    onOpen: (String) -> Unit,
) {
    val failed = results.filter { it.error != null }
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 650.dp).testTag(IMPORT_RESULTS_TAG),
        contentPadding =
            PaddingValues(start = FolioSpace.item, end = FolioSpace.item, bottom = 32.dp),
    ) {
        item {
            Text(
                stringResource(R.string.library_import_results_title),
                style = MaterialTheme.typography.titleLarge,
                modifier =
                    Modifier.padding(horizontal = FolioSpace.small, vertical = 4.dp).semantics {
                        heading()
                    },
            )
        }
        items(results, key = { it.uri }) { result ->
            val error = result.error
            ListItem(
                leadingContent = {
                    if (error != null)
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            stringResource(R.string.library_import_failed),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    else
                        Icon(
                            Icons.Outlined.CheckCircle,
                            stringResource(R.string.library_import_done),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                },
                headlineContent = {
                    Text(
                        result.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        error ?: result.result,
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (error != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    if (error != null)
                        TextButton(
                            onClick = { onRetry(listOf(Uri.parse(result.uri))) },
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.library_import_retry_one))
                        }
                    else
                        result.bookId?.let {
                            TextButton(onClick = { onOpen(it) }, enabled = !busy) {
                                Text(stringResource(R.string.library_import_open))
                            }
                        }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (failed.size > 1)
            item {
                FilledTonalButton(
                    onClick = { onRetry(failed.map { Uri.parse(it.uri) }) },
                    enabled = !busy,
                    modifier = Modifier.padding(horizontal = FolioSpace.small, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.library_import_retry_failed))
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
