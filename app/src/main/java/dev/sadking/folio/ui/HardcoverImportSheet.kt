@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.DiscoveryViewModel
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
                    Text("Import Hardcover library", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Choose books to save on this phone. Confirmed matches are retained. EPUB suggestions need your review. Nothing is written to Hardcover or Kindle."
                    )
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
                            Text("Select loaded books")
                        }
                        TextButton(
                            onClick = {
                                selected = emptySet()
                                targets = emptyMap()
                                model.loadProfile()
                            },
                            enabled = !state.busy,
                        ) {
                            Text("Refresh")
                        }
                    }
                    OutlinedTextField(
                        query,
                        { query = it },
                        label = { Text("Search loaded books") },
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
                                        "${hardcoverStatus(row.status)} · Hardcover" +
                                            (row.pages?.let { " · $it pages" } ?: ""),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                            Text(
                                when {
                                    existing.size > 1 ->
                                        "Multiple local matches: resolve before import"
                                    target != null -> "EPUB: ${target.title}"
                                    existing.isNotEmpty() -> "Update existing catalogue entry"
                                    else -> "Save without EPUB"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (
                                existing.size <= 1 &&
                                    existing.singleOrNull()?.linkedBookId == null &&
                                    books.isNotEmpty()
                            )
                                TextButton(onClick = { matching = row }, enabled = !state.busy) {
                                    Text("Review an EPUB match")
                                }
                            if (targets[row.userBookId] != null)
                                TextButton(onClick = { targets = targets - row.userBookId }) {
                                    Text("Keep without EPUB")
                                }
                        }
                    }
                }
                item {
                    if (more)
                        TextButton(onClick = { model.loadProfile(false) }, enabled = !state.busy) {
                            Text("Load next 50 books")
                        }
                    Text(
                        "${rows.size} loaded · ${selected.size} selected",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Button(
                        onClick = { confirm = true },
                        enabled = !state.busy && selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Review import")
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
                    Text("Match ${row.book.title}", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Choose the correct edition. This links the file; it does not replace metadata or reading data."
                    )
                    OutlinedTextField(
                        matchQuery,
                        { matchQuery = it },
                        label = { Text("Search phone EPUBs") },
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
                        Text("Confirm ${book.title} · ${book.authorLabel}")
                    }
                }
            }
        }
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Save ${selected.size} books?") },
            text = {
                Text(
                    "${targets.count { it.key in selected }} reviewed EPUB attachments. Other books keep their existing attachment or are saved without a file. Status and pages remain labelled Hardcover observations. Phone metadata and Kindle activity stay intact."
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
                    Text("Import to phone")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Back to review") }
            },
        )
}
