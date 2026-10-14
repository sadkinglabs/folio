@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
                    title = { Text(if (book.title.isBlank()) "Add book offline" else "Edit book") },
                    navigationIcon = {
                        IconButton(onClick = { close() }, enabled = !busy) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
            title = { Text("Discard unsaved changes?") },
            text = { Text("The saved book has not been changed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        discard = false
                        onDismiss()
                    }
                ) {
                    Text("Discard")
                }
            },
            dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } },
        )
}

@Composable
fun ShelfEditorSheet(
    shelf: Shelf,
    busy: Boolean,
    error: String?,
    onSave: (Shelf) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(shelf.id) { mutableStateOf(shelf.name) }
    var field by rememberSaveable(shelf.id) { mutableStateOf(shelf.ruleField) }
    var value by rememberSaveable(shelf.id) { mutableStateOf(shelf.ruleValue) }
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Edit list", style = MaterialTheme.typography.headlineSmall)
            EditorField("List name", name, { name = it }, enabled = !busy, error = name.isBlank())
            Text("Rule", style = MaterialTheme.typography.titleMedium)
            LibraryRules.fields.forEach { rule ->
                Row {
                    RadioButton(field == rule, { field = rule }, enabled = !busy)
                    Text(rule, Modifier.padding(top = 12.dp))
                }
            }
            if (field !in listOf("Manual", "Missing on Kindle"))
                EditorField("Rule value", value, { value = it }, enabled = !busy)
            if (shelf.ruleField != field)
                Text(
                    "Manual memberships are retained when switching rules. Switching a smart list to Manual uses its stored manual memberships, not a copy of the smart results.",
                    style = MaterialTheme.typography.bodySmall,
                )
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            Button(
                onClick = { onSave(shelf.copy(name = name, ruleField = field, ruleValue = value)) },
                enabled =
                    !busy &&
                        name.isNotBlank() &&
                        (field in listOf("Manual", "Missing on Kindle") || value.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save list")
            }
            TextButton(
                onClick = { confirmDelete = true },
                enabled = !busy,
                colors =
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Delete list")
            }
        }
    }
    if (confirmDelete)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${shelf.name}?") },
            text = {
                Text(
                    "Removes this list and its memberships. Books, files, reading data, Kindle collections and Hardcover entries are retained."
                )
            },
            confirmButton = {
                TextButton(onClick = onDelete, enabled = !busy) { Text("Delete list") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep list") }
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
                Text("Recently removed", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Files, notes and associations stay here until you delete them permanently. Restoring an EPUB also restores its attached catalogue entry."
                )
            }
            if (groups.isEmpty()) item { Text("No removed books") }
            items(groups, key = { it.groupId }) { entry ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium)
                        Text(entry.kind, style = MaterialTheme.typography.labelMedium)
                        Row {
                            TextButton(onClick = { onRestore(entry.groupId) }, enabled = !busy) {
                                Text("Restore")
                            }
                            TextButton(
                                onClick = { purge = entry },
                                enabled = !busy,
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                            ) {
                                Text("Delete permanently")
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
            title = { Text("Delete ${entry.title} permanently?") },
            text = {
                Text(
                    "Deletes the app-owned files and cached reading data on this phone. This cannot be undone. Downloads, Kindle and Hardcover remain untouched."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onPurge(entry.groupId)
                        purge = null
                    },
                    enabled = !busy,
                ) {
                    Text("Delete permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { purge = null }) { Text("Keep removed book") }
            },
        )
    }
}

@Composable
fun BatchManageSheet(
    count: Int,
    shelves: List<Shelf>,
    busy: Boolean,
    onList: (String, Boolean) -> Unit,
    onStatus: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text("Manage $count selected books", style = MaterialTheme.typography.headlineSmall)
            Text(
                "List and status changes apply on this phone. They do not publish Hardcover progress."
            )
            shelves
                .filter { it.ruleField == "Manual" }
                .forEach { shelf ->
                    Text(shelf.name, style = MaterialTheme.typography.titleMedium)
                    Row {
                        TextButton(onClick = { onList(shelf.id, true) }, enabled = !busy) {
                            Text("Add to list")
                        }
                        TextButton(onClick = { onList(shelf.id, false) }, enabled = !busy) {
                            Text("Remove from list")
                        }
                    }
                }
            Text("Phone reading status", style = MaterialTheme.typography.titleMedium)
            phoneStatuses.forEach { status ->
                TextButton(onClick = { onStatus(status) }, enabled = !busy) { Text(status) }
            }
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
                Text("Import results", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onChooseAgain, enabled = !busy) { Text("Choose files again") }
                if (results.any { it.error != null })
                    TextButton(
                        onClick = {
                            onRetry(results.filter { it.error != null }.map { Uri.parse(it.uri) })
                        },
                        enabled = !busy,
                    ) {
                        Text("Retry failed files")
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
                                Text("Retry this file")
                            }
                        }
                        result.bookId?.let {
                            TextButton(onClick = { onOpen(it) }, enabled = !busy) {
                                Text("Open book")
                            }
                        }
                    }
                }
            }
        }
    }
}
