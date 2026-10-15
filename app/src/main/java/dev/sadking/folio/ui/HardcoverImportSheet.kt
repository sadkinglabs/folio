@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package dev.sadking.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.DiscoveryViewModel
import dev.sadking.folio.R
import dev.sadking.folio.data.*

internal const val HARDCOVER_IMPORT_TAG = "hardcover-import"
internal const val HARDCOVER_IMPORT_BUTTON_TAG = "hardcover-import-button"

/**
 * Import from the Hardcover library: a full-height sheet with a search, one row per book with its
 * status and a checkbox, and a footer that imports the selection. More rows load as the list
 * reaches its end.
 */
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Box(Modifier.fillMaxHeight()) {
            HardcoverImportContent(
                rows = rows,
                more = more,
                busy = state.busy,
                error = state.message.takeIf { state.failed },
                books = books,
                wishes = wishes,
                selected = selected,
                targets = targets,
                query = query,
                onQuery = { query = it },
                onToggle = { id, on -> selected = if (on) selected + id else selected - id },
                onSelectAll = { on ->
                    selected =
                        if (on) rows.filter { importable(it, wishes) }.map { it.userBookId }.toSet()
                        else emptySet()
                },
                onRefresh = {
                    selected = emptySet()
                    targets = emptyMap()
                    model.loadProfile()
                },
                onMatch = { matching = it },
                onUnmatch = { targets = targets - it.userBookId },
                onLoadMore = { model.loadProfile(false) },
                onImport = { confirm = true },
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
    matching?.let { row ->
        MatchEpubSheet(
            row,
            books.filter { b ->
                wishes.none { it.linkedBookId == b.id } &&
                    targets.none { (id, book) -> id != row.userBookId && book == b.id }
            },
            onChoose = { book ->
                targets = targets + (row.userBookId to book.id)
                selected = selected + row.userBookId
                matching = null
            },
            onDismiss = { matching = null },
        )
    }
    if (confirm) {
        val attached = targets.count { it.key in selected }
        ConfirmSheet(
            covers = emptyList(),
            title =
                pluralStringResource(
                    R.plurals.hardcover_import_confirm_title,
                    selected.size,
                    selected.size,
                ),
            body =
                listOfNotNull(
                        attached
                            .takeIf { it > 0 }
                            ?.let {
                                pluralStringResource(R.plurals.hardcover_import_with_epub, it, it)
                            },
                        stringResource(R.string.hardcover_import_confirm_body),
                    )
                    .joinToString(" "),
            confirmLabel = stringResource(R.string.hardcover_import_confirm),
            dismissLabel = stringResource(R.string.shell_cancel),
            busy = state.busy,
            onConfirm = {
                confirm = false
                model.importProfile(selected, targets, onDismiss)
            },
            onDismiss = { confirm = false },
        )
    }
}

/** A Hardcover book can be imported unless Folio already holds it twice. */
internal fun importable(row: HardcoverLibraryBook, wishes: List<WishBook>): Boolean =
    wishes.count { it.hardcoverId == row.book.id } <= 1

@Composable
internal fun HardcoverImportContent(
    rows: List<HardcoverLibraryBook>,
    more: Boolean,
    busy: Boolean,
    error: String?,
    books: List<Book>,
    wishes: List<WishBook>,
    selected: Set<Int>,
    targets: Map<Int, String>,
    query: String,
    onQuery: (String) -> Unit,
    onToggle: (Int, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onMatch: (HardcoverLibraryBook) -> Unit,
    onUnmatch: (HardcoverLibraryBook) -> Unit,
    onLoadMore: () -> Unit,
    onImport: () -> Unit,
) {
    val shown = rows.filter {
        (it.book.title + " " + it.book.authors.joinToString()).contains(query, true)
    }
    val importable = rows.filter { importable(it, wishes) }
    // At large text the status moves into the row's detail line, so the title keeps its room.
    val large = LocalDensity.current.fontScale > 1.5f
    Column(Modifier.fillMaxWidth().fillMaxHeight().testTag(HARDCOVER_IMPORT_TAG)) {
        Column(
            Modifier.padding(horizontal = FolioSpace.section),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        ) {
            SheetTitle(stringResource(R.string.hardcover_import_title)) {
                IconButton(onClick = onRefresh, enabled = !busy) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.hardcover_import_refresh))
                }
            }
            OutlinedTextField(
                query,
                onQuery,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text(stringResource(R.string.hardcover_import_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error != null)
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = FolioSpace.section, vertical = 4.dp),
        ) {
            items(shown, key = { it.userBookId }) { row ->
                val existing = wishes.filter { it.hardcoverId == row.book.id }
                val target = books.firstOrNull {
                    it.id == (targets[row.userBookId] ?: existing.singleOrNull()?.linkedBookId)
                }
                val canImport = existing.size <= 1
                val canMatch =
                    canImport && existing.singleOrNull()?.linkedBookId == null && books.isNotEmpty()
                val on = row.userBookId in selected
                ResultRow(
                    row.book.title,
                    row.book.authors.joinToString(", "),
                    Modifier.toggleableCompat(on, !busy && canImport) {
                        onToggle(row.userBookId, it)
                    },
                    detail =
                        listOfNotNull(
                                if (large) hardcoverStatus(row.status) else null,
                                row.pages?.let {
                                    pluralStringResource(R.plurals.hardcover_pages, it, it)
                                },
                                when {
                                    existing.size > 1 ->
                                        stringResource(R.string.hardcover_import_duplicate)
                                    target != null ->
                                        stringResource(R.string.hardcover_import_epub, target.title)
                                    existing.isNotEmpty() ->
                                        stringResource(R.string.hardcover_import_update_existing)
                                    else -> null
                                },
                            )
                            .joinToString(" · "),
                ) {
                    if (!large)
                        StatusPill(hardcoverStatus(row.status), Modifier.widthIn(max = 112.dp))
                    if (targets[row.userBookId] != null)
                        IconButton(onClick = { onUnmatch(row) }, enabled = !busy) {
                            Icon(
                                Icons.Outlined.LinkOff,
                                stringResource(R.string.hardcover_import_keep_without_epub),
                            )
                        }
                    else if (canMatch)
                        IconButton(onClick = { onMatch(row) }, enabled = !busy) {
                            Icon(
                                Icons.Outlined.Link,
                                stringResource(R.string.hardcover_import_match_epub),
                            )
                        }
                    Checkbox(on, null, enabled = !busy && canImport)
                }
            }
            if (more)
                item {
                    // Reaching the end loads the next page; a failed load waits for Refresh.
                    LaunchedEffect(busy, error) { if (!busy && error == null) onLoadMore() }
                    Box(Modifier.fillMaxWidth().padding(FolioSpace.item), Alignment.Center) {
                        LoadingIndicator(Modifier.size(32.dp))
                    }
                }
        }
        Row(
            Modifier.fillMaxWidth()
                .padding(
                    start = FolioSpace.gap,
                    end = FolioSpace.section,
                    bottom = 24.dp,
                    top = 4.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val all = importable.isNotEmpty() && importable.all { it.userBookId in selected }
            Row(
                Modifier.toggleableCompat(all, !busy && importable.isNotEmpty(), onSelectAll)
                    .padding(end = FolioSpace.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(all, null, enabled = !busy && importable.isNotEmpty())
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.hardcover_import_all),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onImport,
                enabled = !busy && selected.isNotEmpty(),
                modifier = Modifier.testTag(HARDCOVER_IMPORT_BUTTON_TAG),
            ) {
                Text(
                    if (selected.isEmpty()) stringResource(R.string.hardcover_import_button_empty)
                    else stringResource(R.string.hardcover_import_button, selected.size)
                )
            }
        }
    }
}

/** The nested picker: which EPUB on this phone is this Hardcover book. */
@Composable
private fun MatchEpubSheet(
    row: HardcoverLibraryBook,
    candidates: List<Book>,
    onChoose: (Book) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(row.userBookId) { mutableStateOf(row.book.title) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 520.dp),
            contentPadding =
                PaddingValues(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.hardcover_import_match_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    row.book.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    query,
                    { query = it },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    placeholder = { Text(stringResource(R.string.hardcover_import_match_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = FolioSpace.gap),
                )
            }
            items(
                candidates.filter { (it.title + " " + it.authorLabel).contains(query, true) },
                key = { it.id },
            ) { book ->
                ResultRow(
                    book.title,
                    book.authorLabel,
                    coverPath = book.coverPath,
                    onClick = { onChoose(book) },
                ) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
