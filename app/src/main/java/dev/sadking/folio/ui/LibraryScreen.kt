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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import dev.sadking.folio.*
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
    var grid by rememberSaveable { mutableStateOf(true) }
    var sort by rememberSaveable { mutableStateOf("Recent") }
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
            placeholder = { Text("Search library") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.isNotEmpty())
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, "Clear search")
                        }
                    TextButton(
                        onClick = {
                            keyboard?.hide()
                            showRefine = true
                        }
                    ) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (activeFilters == 0) "Refine" else "Refine · $activeFilters")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier =
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics {
                    contentDescription = "Search library"
                },
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(filterName ?: "Library", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (results == null) "Loading books…"
                    else if (filtered.size == books.size) {
                        if (books.size == 1) "1 book" else "${books.size} books"
                    } else "${filtered.size} of ${books.size} books",
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
                    modifier = Modifier.semantics { contentDescription = "Show grid" },
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
                    modifier = Modifier.semantics { contentDescription = "Show list" },
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(20.dp))
                }
            }
            if (onFolderSync != null)
                IconButton(onClick = onFolderSync, enabled = !busy) {
                    Icon(Icons.Outlined.Sync, "Sync paired folders")
                }
            if (onSettings != null)
                IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
        }
        if (selection.isNotEmpty())
            Text(
                "Tap books to change the selection · Kindle actions use EPUBs only",
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        if (results == null)
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        else if (filtered.isEmpty())
            EmptyState(
                if (books.isEmpty() && filterName == null) "Make room for your next read"
                else "No matching books",
                if (books.isEmpty() && filterName == null)
                    "Add an EPUB, enter a book, scan a cover or bring in your Hardcover library."
                else "Try another search or reading filter.",
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
                    if (books.isEmpty() && filterName == null) "Add books" else "Clear filters",
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
                        columns = GridCells.Adaptive(140.dp),
                        contentPadding =
                            PaddingValues(start = 20.dp, end = 20.dp, bottom = 88.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
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
                                    if (selection.isEmpty() && onContext != null) "Book actions"
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
                                                        "Book actions"
                                                    else "Change selection"
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
                                            book.status,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                }
                                if (book.id in selection)
                                    Icon(Icons.Outlined.CheckCircle, "Selected")
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
