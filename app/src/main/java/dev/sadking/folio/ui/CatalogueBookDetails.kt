@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.DiscoveryViewModel
import dev.sadking.folio.R
import dev.sadking.folio.acquire.BrowserLaunch
import dev.sadking.folio.data.*
import dev.sadking.folio.sync.HardcoverItemState

internal const val ATTACH_SHEET_TAG = "attach-sheet"
internal const val ATTACH_PICK_FILE_TAG = "attach-pick-file"

/**
 * A record (a book without an EPUB) on the same page as any other book. Its primary action, "Get
 * this book", opens the in-app browser on a search for it; Attach takes a file from the phone or an
 * EPUB already in Folio, with a confirm sheet that shows both covers. The record keeps its details,
 * lists and Hardcover match throughout. [hardcoverState] is what the Hardcover tile says; while
 * [hardcoverSync] is on, a matched record reaches Hardcover by itself, so there is no "Add to
 * Hardcover" button.
 */
@Composable
fun CatalogueBookDetails(
    model: DiscoveryViewModel,
    wish: WishBook,
    books: List<Book>,
    lists: List<String>,
    contentPadding: PaddingValues = PaddingValues(),
    onEdit: () -> Unit,
    onHardcover: () -> Unit,
    onRemove: () -> Unit,
    onLists: () -> Unit,
    onStatus: (String) -> Unit,
    hardcoverState: HardcoverItemState? = null,
    hardcoverSync: Boolean = false,
    onCheckMatch: () -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    val observations by model.observations.collectAsStateWithLifecycle()
    val accountId by model.accountId.collectAsStateWithLifecycle()
    val observed = observations.firstOrNull {
        it.accountId == accountId && it.hardcoverId == wish.hardcoverId
    }
    val snackbar = rememberOperationSnackbar(model.notices)
    val context = LocalContext.current
    val noBrowser = stringResource(R.string.discovery_no_browser_search)
    var attaching by rememberSaveable { mutableStateOf(false) }
    var candidate by remember { mutableStateOf<Book?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) model.importForAttachment(uri) { candidate = it }
        }
    val display = wish.displayBook()
    Box {
        BookDetails(
            book = display,
            hasEpub = false,
            lists = lists,
            signals = catalogueSignals(wish, observed),
            hardcoverLinked = wish.hardcoverId != null,
            hardcoverPage = observed?.pages,
            hardcoverState = hardcoverState,
            busy = state.busy,
            contentPadding = contentPadding,
            onRead = {
                // "Get this book" (M5): the in-app browser searches the first source for this
                // entry, and the book it gets is linked to the entry.
                try {
                    context.startActivity(
                        BrowserLaunch.find(context, wish.title, decode(wish.authors), wish.id)
                    )
                } catch (_: android.content.ActivityNotFoundException) {
                    model.report(noBrowser)
                }
            },
            onAttach = { attaching = true },
            onEdit = onEdit,
            onHardcover = onHardcover,
            onCheckMatch = onCheckMatch,
            onRemove = onRemove,
            onLists = onLists,
            onStatus = onStatus,
            extra = {
                if (
                    !hardcoverSync &&
                        wish.hardcoverId != null &&
                        !wish.hardcoverSynced &&
                        wish.status in listOf("", "Want to Read") &&
                        (observed == null || observed.status == 1)
                )
                    FilledTonalButton(
                        onClick = { model.publish(wish) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.discovery_add_to_hardcover_want_to_read))
                    }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            },
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (attaching)
        AttachSheet(
            books = books,
            suggested = { BookDiscovery.suggests(wish, it) },
            busy = state.busy,
            onPickFile = {
                attaching = false
                picker.launch(BOOK_FILE_TYPES)
            },
            onPick = {
                attaching = false
                candidate = it
            },
            onDismiss = { attaching = false },
        )
    candidate?.let { book ->
        ConfirmSheet(
            covers = listOf(book, display),
            title = stringResource(R.string.rd_attach_confirm_title, wish.title),
            body =
                stringResource(
                    R.string.rd_attach_confirm_body,
                    book.title,
                    book.authorLabel.ifBlank { stringResource(R.string.shell_unknown_author) },
                ),
            confirmLabel = stringResource(R.string.rd_attach),
            dismissLabel = stringResource(R.string.rd_keep_separate),
            onConfirm = {
                model.attach(wish, book)
                candidate = null
            },
            onDismiss = { candidate = null },
            busy = state.busy,
        )
    }
}

/**
 * Where the EPUB comes from: a file on the phone, or a book already in Folio. Books the record
 * probably matches come first and are marked.
 */
@Composable
internal fun AttachSheet(
    books: List<Book>,
    suggested: (Book) -> Boolean,
    busy: Boolean,
    onPickFile: () -> Unit,
    onPick: (Book) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown =
        remember(books, query) {
            books
                .filter {
                    query.isBlank() || (it.title + " " + it.authorLabel).contains(query, true)
                }
                .sortedByDescending { suggested(it) }
        }
    FolioSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().testTag(ATTACH_SHEET_TAG),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            item {
                Text(
                    stringResource(R.string.rd_attach_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier =
                        Modifier.padding(horizontal = FolioSpace.section).semantics {
                            heading()
                        },
                )
            }
            item {
                Surface(
                    Modifier.padding(horizontal = FolioSpace.section)
                        .fillMaxWidth()
                        .clickable(enabled = !busy, role = Role.Button, onClick = onPickFile)
                        .testTag(ATTACH_PICK_FILE_TAG),
                    shape = FolioSpace.panelShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        Modifier.padding(FolioSpace.item),
                        horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.FolderOpen, null)
                        Text(
                            stringResource(R.string.rd_attach_pick_file),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            if (books.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.rd_attach_in_folio).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier.padding(
                                start = FolioSpace.section,
                                end = FolioSpace.section,
                                top = FolioSpace.small,
                            ),
                    )
                }
                if (books.size > 6)
                    item {
                        OutlinedTextField(
                            query,
                            { query = it },
                            label = { Text(stringResource(R.string.discovery_search_epubs)) },
                            singleLine = true,
                            modifier =
                                Modifier.fillMaxWidth().padding(horizontal = FolioSpace.section),
                        )
                    }
                items(shown, key = { it.id }) { book ->
                    ListItem(
                        leadingContent = { CoverTile(book, Modifier.width(40.dp), lifted = false) },
                        headlineContent = {
                            Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(book.authorLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent =
                            if (suggested(book)) {
                                {
                                    SuggestionChip(
                                        onClick = { onPick(book) },
                                        enabled = !busy,
                                        label = { Text(stringResource(R.string.rd_suggested)) },
                                    )
                                }
                            } else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier =
                            Modifier.clickable(enabled = !busy, role = Role.Button) { onPick(book) }
                                .padding(horizontal = FolioSpace.small),
                    )
                }
            }
        }
    }
}
