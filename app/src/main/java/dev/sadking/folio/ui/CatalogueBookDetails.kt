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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.DiscoveryViewModel
import dev.sadking.folio.R
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
    val noBrowser = stringResource(R.string.discovery_no_browser_search)
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
                    stringResource(R.string.discovery_add_epub),
                    {
                        picker.launch(arrayOf("application/epub+zip", "application/octet-stream"))
                    },
                    onProgress,
                    !state.busy,
                )
                Spacer(Modifier.height(FolioSpace.gap))
                BookStateSummary(catalogueSignals(wish, observed))
                observed?.let { value ->
                    val checked = observationDate(value.fetchedAt)
                    Text(
                        value.pages?.let {
                            stringResource(R.string.discovery_hardcover_checked_page, checked, it)
                        } ?: stringResource(R.string.discovery_hardcover_checked, checked),
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
                        Text(stringResource(R.string.discovery_add_to_hardcover_want_to_read))
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
                            model.report(noBrowser)
                        }
                    }
                ) {
                    Text(stringResource(R.string.discovery_search_annas_archive))
                }
                Text(
                    stringResource(R.string.discovery_search_annas_archive_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (books.isNotEmpty())
                    TextButton(onClick = { attaching = !attaching }) {
                        Text(stringResource(R.string.discovery_choose_existing_epub))
                    }
            }
            if (attaching) {
                item {
                    OutlinedTextField(
                        query,
                        { query = it },
                        label = { Text(stringResource(R.string.discovery_search_epubs)) },
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
                            stringResource(
                                if (BookDiscovery.suggests(wish, book))
                                    R.string.discovery_book_line_suggested
                                else R.string.discovery_book_line,
                                book.title,
                                book.authorLabel,
                            )
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
                    ExpandableSection(
                        stringResource(R.string.discovery_description),
                        stringResource(R.string.discovery_about),
                    ) {
                        Text(metadata.description, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (metadata.series.isNotBlank())
                    DetailLine(
                        stringResource(R.string.discovery_series),
                        "${metadata.series} ${metadata.seriesIndex}",
                    )
                if (metadata.publisher.isNotBlank())
                    DetailLine(stringResource(R.string.discovery_publisher), metadata.publisher)
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
                        Text(stringResource(R.string.discovery_remove))
                    }
                Spacer(Modifier.height(32.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
    }
    candidate?.let { book ->
        AlertDialog(
            onDismissRequest = { candidate = null },
            title = { Text(stringResource(R.string.discovery_attach_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.discovery_attach_body,
                        book.title,
                        book.authorLabel,
                        wish.title,
                    )
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
                    Text(stringResource(R.string.discovery_attach_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { candidate = null }) {
                    Text(stringResource(R.string.discovery_cancel))
                }
            },
        )
    }
}
