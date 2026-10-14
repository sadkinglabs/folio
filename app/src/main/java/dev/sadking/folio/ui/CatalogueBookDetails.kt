@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.DiscoveryViewModel
import dev.sadking.folio.data.*

@Composable
fun CatalogueBookDetails(
    model: DiscoveryViewModel,
    wish: WishBook,
    books: List<Book>,
    shelves: List<Shelf>,
    onProgress: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onActions: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onLists: () -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    val memberships by model.wishMemberships.collectAsStateWithLifecycle()
    val observations by model.observations.collectAsStateWithLifecycle()
    val accountId by model.accountId.collectAsStateWithLifecycle()
    val observed = observations.firstOrNull {
        it.accountId == accountId && it.hardcoverId == wish.hardcoverId
    }
    val snackbar = rememberOperationSnackbar(model.notices)
    val context = LocalContext.current
    var attaching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<Book?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) model.importForAttachment(uri) { candidate = it }
        }
    Box {
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(FolioSpace.page),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                BookHero(wish.displayBook())
            }
            item {
                BookPrimaryActions(
                    "Add EPUB",
                    {
                        picker.launch(arrayOf("application/epub+zip", "application/octet-stream"))
                    },
                    onProgress,
                    !state.busy,
                )
                Spacer(Modifier.height(FolioSpace.gap))
                BookStateSummary(catalogueSignals(wish, observed))
                observed?.let { value ->
                    Text(
                        "Hardcover snapshot · ${java.time.Instant.ofEpochMilli(value.fetchedAt)}" +
                            (value.pages?.let { " · $it recorded pages" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (
                    wish.hardcoverId != null &&
                        !wish.hardcoverSynced &&
                        wish.status in listOf("", "Want to Read") &&
                        (observed == null || observed.status == 1)
                )
                    OutlinedButton(
                        onClick = { model.publish(wish) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Add to Hardcover Want to Read")
                    }
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(
                                        BookDiscovery.archiveSearch(
                                            wish.title,
                                            decode(wish.authors),
                                        )
                                    ),
                                )
                            )
                        } catch (_: android.content.ActivityNotFoundException) {
                            model.report("Install a browser to search for this book")
                        }
                    }
                ) {
                    Text("Search Anna’s Archive ↗")
                }
                Text(
                    "Opens a title and author query in your browser. Return here to attach an EPUB.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (books.isNotEmpty())
                    TextButton(onClick = { attaching = !attaching }) {
                        Text("Choose an EPUB already in Folio")
                    }
            }
            if (attaching) {
                item {
                    OutlinedTextField(
                        query,
                        { query = it },
                        label = { Text("Search imported EPUBs") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                items(
                    books
                        .filter { (it.title + " " + it.authorLabel).contains(query, true) }
                        .sortedByDescending { BookDiscovery.suggests(wish, it) },
                    key = { it.id },
                ) { book ->
                    TextButton(onClick = { candidate = book }, enabled = !state.busy) {
                        Text(
                            "${book.title} · ${book.authorLabel}" +
                                if (BookDiscovery.suggests(wish, book)) " · Suggested" else ""
                        )
                    }
                }
            }
            item {
                BookListsSummary(
                    shelves
                        .filter { shelf ->
                            memberships.any { it.wishId == wish.id && it.shelfId == shelf.id }
                        }
                        .map { it.name },
                    onLists,
                    !state.busy,
                )
            }
            item {
                val metadata = wish.displayBook()
                if (metadata.description.isNotBlank()) {
                    ExpandableSection("Description", "About this book") {
                        Text(metadata.description, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (metadata.series.isNotBlank())
                    DetailLine("Series", "${metadata.series} ${metadata.seriesIndex}")
                if (metadata.publisher.isNotBlank()) DetailLine("Publisher", metadata.publisher)
            }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                if (onRemove != null)
                    TextButton(
                        onClick = onRemove,
                        enabled = !state.busy,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                    ) {
                        Text("Remove from library")
                    }
                Spacer(Modifier.height(32.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
    }
    candidate?.let { book ->
        AlertDialog(
            onDismissRequest = { candidate = null },
            title = { Text("Attach this edition?") },
            text = {
                Text(
                    "${book.title}\n${book.authorLabel}\n\nConfirm this EPUB belongs to ${wish.title}. Files, annotations and reading history are retained. Cancelling keeps the EPUB as a separate library entry."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.attach(wish, book)
                        candidate = null
                    },
                    enabled = !state.busy,
                ) {
                    Text("Confirm attachment")
                }
            },
            dismissButton = { TextButton(onClick = { candidate = null }) { Text("Cancel") } },
        )
    }
}
