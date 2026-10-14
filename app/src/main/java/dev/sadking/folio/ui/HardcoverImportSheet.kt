@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.DiscoveryViewModel
import dev.sadking.folio.R
import dev.sadking.folio.data.*

@Composable
fun HardcoverImportSheet(
    model: DiscoveryViewModel,
    books: List<Book>,
    wishes: List<WishBook>,
    onDismiss: () -> Unit,
) {
    val rows by model.profile.collectAsStateWithLifecycle()
    val more by model.profileMore.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = rememberOperationSnackbar(model.notices)
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var targets by remember { mutableStateOf(mapOf<Int, String>()) }
    var matching by remember { mutableStateOf<HardcoverLibraryBook?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Box {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 720.dp),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.hardcover_import_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(stringResource(R.string.hardcover_import_intro))
                    Row {
                        TextButton(
                            onClick = {
                                selected =
                                    rows
                                        .filter { row ->
                                            wishes.count { it.hardcoverId == row.book.id } <= 1
                                        }
                                        .map { it.userBookId }
                                        .toSet()
                            },
                            enabled = !state.busy,
                        ) {
                            Text(stringResource(R.string.hardcover_import_select_loaded))
                        }
                        TextButton(
                            onClick = {
                                selected = emptySet()
                                targets = emptyMap()
                                model.loadProfile()
                            },
                            enabled = !state.busy,
                        ) {
                            Text(stringResource(R.string.hardcover_import_refresh))
                        }
                    }
                    OutlinedTextField(
                        query,
                        { query = it },
                        label = { Text(stringResource(R.string.hardcover_import_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.failed) Text(state.message, color = MaterialTheme.colorScheme.error)
                }
                items(
                    rows.filter {
                        (it.book.title + " " + it.book.authors.joinToString()).contains(query, true)
                    },
                    key = { it.userBookId },
                ) { row ->
                    val existing = wishes.filter { it.hardcoverId == row.book.id }
                    val target = books.firstOrNull {
                        it.id == (targets[row.userBookId] ?: existing.singleOrNull()?.linkedBookId)
                    }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row {
                                Checkbox(
                                    row.userBookId in selected,
                                    {
                                        selected =
                                            if (it) selected + row.userBookId
                                            else selected - row.userBookId
                                    },
                                    enabled = !state.busy && existing.size <= 1,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        row.book.title,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(row.book.authors.joinToString(", "))
                                    Text(
                                        row.pages?.let {
                                            pluralStringResource(
                                                R.plurals.hardcover_import_status_pages,
                                                it,
                                                it,
                                                hardcoverStatus(row.status),
                                            )
                                        }
                                            ?: stringResource(
                                                R.string.hardcover_import_status,
                                                hardcoverStatus(row.status),
                                            ),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                            Text(
                                when {
                                    existing.size > 1 ->
                                        stringResource(R.string.hardcover_import_duplicate)
                                    target != null ->
                                        stringResource(R.string.hardcover_import_epub, target.title)
                                    existing.isNotEmpty() ->
                                        stringResource(R.string.hardcover_import_update_existing)
                                    else -> stringResource(R.string.hardcover_import_no_epub)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (
                                existing.size <= 1 &&
                                    existing.singleOrNull()?.linkedBookId == null &&
                                    books.isNotEmpty()
                            )
                                TextButton(onClick = { matching = row }, enabled = !state.busy) {
                                    Text(stringResource(R.string.hardcover_import_match_epub))
                                }
                            if (targets[row.userBookId] != null)
                                TextButton(onClick = { targets = targets - row.userBookId }) {
                                    Text(
                                        stringResource(R.string.hardcover_import_keep_without_epub)
                                    )
                                }
                        }
                    }
                }
                item {
                    if (more)
                        TextButton(onClick = { model.loadProfile(false) }, enabled = !state.busy) {
                            Text(stringResource(R.string.hardcover_import_load_more))
                        }
                    Text(
                        stringResource(R.string.hardcover_import_counts, rows.size, selected.size),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Button(
                        onClick = { confirm = true },
                        enabled = !state.busy && selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.hardcover_import_review))
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
            SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
        }
    }
    matching?.let { row ->
        var matchQuery by remember(row.userBookId) { mutableStateOf(row.book.title) }
        ModalBottomSheet(onDismissRequest = { matching = null }) {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 520.dp),
                contentPadding = PaddingValues(24.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.hardcover_import_match_title, row.book.title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(stringResource(R.string.hardcover_import_match_intro))
                    OutlinedTextField(
                        matchQuery,
                        { matchQuery = it },
                        label = { Text(stringResource(R.string.hardcover_import_match_search)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                items(
                    books
                        .filter { b ->
                            wishes.none { it.linkedBookId == b.id } &&
                                targets.none { (id, book) -> id != row.userBookId && book == b.id }
                        }
                        .filter { (it.title + " " + it.authorLabel).contains(matchQuery, true) },
                    key = { it.id },
                ) { book ->
                    TextButton(
                        onClick = {
                            targets = targets + (row.userBookId to book.id)
                            selected = selected + row.userBookId
                            matching = null
                        }
                    ) {
                        Text(
                            stringResource(
                                R.string.hardcover_import_match_option,
                                book.title,
                                book.authorLabel,
                            )
                        )
                    }
                }
            }
        }
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = {
                Text(
                    pluralStringResource(
                        R.plurals.hardcover_import_confirm_title,
                        selected.size,
                        selected.size,
                    )
                )
            },
            text = {
                val attached = targets.count { it.key in selected }
                Text(
                    pluralStringResource(
                        R.plurals.hardcover_import_confirm_body,
                        attached,
                        attached,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        model.importProfile(selected, targets, onDismiss)
                    },
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.hardcover_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(R.string.hardcover_import_back))
                }
            },
        )
}
