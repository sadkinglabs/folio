@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package dev.sadking.folio.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import dev.sadking.folio.*
import dev.sadking.folio.R
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
import dev.sadking.folio.device.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    books: List<Book>,
    selection: Set<String>,
    modifier: Modifier = Modifier,
    onOpen: (Book) -> Unit,
    onSelect: (String) -> Unit,
    filterName: String? = null,
    onClearFilter: () -> Unit = {},
    onImport: () -> Unit = {},
    busy: Boolean = false,
    signals: Map<String, BookSignals> = emptyMap(),
    fileIds: Set<String> = books.map { it.id }.toSet(),
    onContext: ((Book) -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    onFolderSync: (() -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    // Layout and sort are remembered between launches. Search and filters last for the session,
    // so a forgotten filter never hides books on the next start.
    val view = rememberLibraryView()
    var grid by view.grid
    var sort by view.sort
    var status by rememberSaveable { mutableStateOf("All") }
    var files by rememberSaveable { mutableStateOf("All books") }
    var showRefine by rememberSaveable { mutableStateOf(false) }
    var tag by rememberSaveable { mutableStateOf("") }
    val index by
        produceState<List<SearchBook>?>(null, books) {
            value = withContext(Dispatchers.Default) { indexLibrary(books) }
        }
    val results by
        produceState<List<Book>?>(
            null,
            index,
            query,
            sort,
            status,
            signals,
            files,
            fileIds,
            tag,
        ) {
            val current = index
            value =
                if (current == null) null
                else
                    withContext(Dispatchers.Default) {
                        searchLibrary(current, query, status, files, tag, sort, signals, fileIds)
                    }
        }
    val filtered = results.orEmpty()
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val motionScope = rememberCoroutineScope()
    val searchHint = stringResource(R.string.library_search_hint)
    val bookActionsLabel = stringResource(R.string.library_book_actions)
    val changeSelectionLabel = stringResource(R.string.library_change_selection)
    val showGridLabel = stringResource(R.string.library_show_grid)
    val showListLabel = stringResource(R.string.library_show_list)
    Column(modifier.fillMaxWidth()) {
        val activeFilters =
            listOf(
                    status != "All",
                    files != "All books",
                    tag.isNotBlank(),
                    filterName != null,
                )
                .count { it }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(searchHint) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.isNotEmpty())
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Outlined.Close,
                                stringResource(R.string.library_clear_search),
                            )
                        }
                    TextButton(
                        onClick = {
                            keyboard?.hide()
                            showRefine = true
                        }
                    ) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (activeFilters == 0) stringResource(R.string.library_refine)
                            else stringResource(R.string.library_refine_count, activeFilters)
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier =
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics {
                    contentDescription = searchHint
                },
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    filterName ?: stringResource(R.string.library_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (results == null) stringResource(R.string.library_loading)
                    else if (filtered.size == books.size)
                        pluralStringResource(R.plurals.library_book_count, books.size, books.size)
                    else
                        pluralStringResource(
                            R.plurals.library_book_count_filtered,
                            books.size,
                            filtered.size,
                            books.size,
                        ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = grid,
                    onClick = {
                        if (!grid) {
                            grid = true
                            motionScope.launch {
                                gridState.scrollToItem(
                                    listState.firstVisibleItemIndex.coerceAtMost(
                                        maxOf(0, filtered.size - 1)
                                    )
                                )
                            }
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = {},
                    modifier = Modifier.semantics { contentDescription = showGridLabel },
                ) {
                    Icon(Icons.Outlined.GridView, null, Modifier.size(20.dp))
                }
                SegmentedButton(
                    selected = !grid,
                    onClick = {
                        if (grid) {
                            grid = false
                            motionScope.launch {
                                listState.scrollToItem(
                                    gridState.firstVisibleItemIndex.coerceAtMost(
                                        maxOf(0, filtered.size - 1)
                                    )
                                )
                            }
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = {},
                    modifier = Modifier.semantics { contentDescription = showListLabel },
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(20.dp))
                }
            }
            if (onFolderSync != null)
                IconButton(onClick = onFolderSync, enabled = !busy) {
                    Icon(Icons.Outlined.Sync, stringResource(R.string.library_sync_folders))
                }
            if (onSettings != null)
                IconButton(onClick = onSettings) {
                    Icon(Icons.Outlined.Settings, stringResource(R.string.library_settings))
                }
        }
        if (selection.isNotEmpty())
            Text(
                stringResource(R.string.library_selection_hint),
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        if (results == null)
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        else if (filtered.isEmpty())
            EmptyState(
                if (books.isEmpty() && filterName == null)
                    stringResource(R.string.library_empty_title)
                else stringResource(R.string.library_no_match_title),
                if (books.isEmpty() && filterName == null)
                    stringResource(R.string.library_empty_body)
                else stringResource(R.string.library_no_match_body),
                Icons.Outlined.AutoStories,
                if (books.isEmpty() && filterName == null) onImport
                else
                    ({
                        query = ""
                        status = "All"
                        files = "All books"
                        tag = ""
                        onClearFilter()
                    }),
                actionEnabled = !busy,
                actionLabel =
                    if (books.isEmpty() && filterName == null)
                        stringResource(R.string.library_empty_action)
                    else stringResource(R.string.library_no_match_action),
            )
        else
            AnimatedContent(
                grid,
                modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
                label = "Library layout",
            ) { showGrid ->
                if (showGrid)
                    LazyVerticalGrid(
                        state = gridState,
                        // Three covers across a typical phone; more on wider screens.
                        // Wider tiles for larger text, so titles wrap between words, not inside
                        // them.
                        columns =
                            GridCells.Adaptive(
                                104.dp *
                                    androidx.compose.ui.platform.LocalDensity.current.fontScale
                                        .coerceIn(1f, 1.6f)
                            ),
                        contentPadding =
                            PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filtered, key = { it.id }) { book ->
                            BookTile(
                                book,
                                book.id in selection,
                                onClick = {
                                    if (selection.isEmpty()) onOpen(book) else onSelect(book.id)
                                },
                                onLong = {
                                    if (selection.isEmpty() && onContext != null) onContext(book)
                                    else onSelect(book.id)
                                },
                                signals = signals[book.id],
                                selectable = true,
                                longActionLabel =
                                    if (selection.isEmpty() && onContext != null) bookActionsLabel
                                    else null,
                            )
                        }
                    }
                else
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        items(filtered, key = { it.id }) { book ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (selection.isEmpty()) onOpen(book)
                                            else onSelect(book.id)
                                        },
                                        onLongClick = {
                                            if (selection.isEmpty() && onContext != null)
                                                onContext(book)
                                            else onSelect(book.id)
                                        },
                                    )
                                    .semantics {
                                        selected = book.id in selection
                                        customActions =
                                            listOf(
                                                CustomAccessibilityAction(
                                                    if (selection.isEmpty() && onContext != null)
                                                        bookActionsLabel
                                                    else changeSelectionLabel
                                                ) {
                                                    if (selection.isEmpty() && onContext != null)
                                                        onContext(book)
                                                    else onSelect(book.id)
                                                    true
                                                }
                                            )
                                    }
                                    .background(
                                        if (book.id in selection)
                                            MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                LibraryCover(
                                    book,
                                    signals[book.id],
                                    Modifier.width(72.dp).aspectRatio(0.68f),
                                    compact = true,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        book.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 2,
                                        overflow =
                                            androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        book.authorLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (signals[book.id] != null)
                                        BookBadges(signals.getValue(book.id))
                                    else
                                        Text(
                                            libraryStatusLabel(book.status),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                }
                                if (book.id in selection)
                                    Icon(
                                        Icons.Outlined.CheckCircle,
                                        stringResource(R.string.library_selected),
                                    )
                            }
                        }
                    }
            }
    }
    if (showRefine)
        LibraryRefineSheet(
            status,
            files,
            sort,
            tag,
            remember(index) { index.orEmpty().flatMap { it.displayTags }.distinct().sorted() },
            filterName,
            filtered.size,
            onStatus = { status = it },
            onFiles = { files = it },
            onSort = { sort = it },
            onTag = { tag = it },
            onClearScope = onClearFilter,
            onReset = {
                query = ""
                status = "All"
                files = "All books"
                sort = "Recent"
                tag = ""
                onClearFilter()
            },
            onDismiss = {
                keyboard?.hide()
                showRefine = false
            },
        )
}
