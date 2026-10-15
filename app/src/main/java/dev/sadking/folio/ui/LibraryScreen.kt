@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package dev.sadking.folio.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.sadking.folio.R
import dev.sadking.folio.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val LIBRARY_GRID_TAG = "library-grid"
internal const val LIBRARY_LIST_TAG = "library-list"
internal const val LIBRARY_SHELF_TAG = "reading-shelf"
internal const val LIBRARY_TAB_LIBRARY_TAG = "library-tab-library"
internal const val LIBRARY_TAB_WANTED_TAG = "library-tab-wanted"
internal const val LIBRARY_FILTER_TAG = "library-filter"
internal const val LIBRARY_SEARCH_TAG = "library-search"

/**
 * The Library screen: a serif title with the count, Add and Search; two tabs, Library and Want to
 * read; on the Library tab a Reading shelf above a captionless cover grid. With [filterName] set it
 * shows one list's books instead (no tabs, no shelf). The grid scrolls under the glass bar and pads
 * its end by [LocalContentBottomPadding].
 */
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
    progress: Map<String, Double> = emptyMap(),
    currentId: String? = null,
    onContext: ((Book) -> Unit)? = null,
    onClearSelection: () -> Unit = {},
    onFind: (() -> Unit)? = null,
    onScan: (() -> Unit)? = null,
    requestedTab: LibraryTab? = null,
    onRequestHandled: () -> Unit = {},
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val scoped = filterName != null
    var tabName by rememberSaveable { mutableStateOf(LibraryTab.LIBRARY.name) }
    val tab = LibraryTab.valueOf(tabName)
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tabName = requestedTab.name
            onRequestHandled()
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    // Layout and sort are remembered between launches. Search and filters last for the session,
    // so a forgotten filter never hides books on the next start.
    val view = rememberLibraryView()
    var grid by view.grid
    var sort by view.sort
    var status by rememberSaveable { mutableStateOf("All") }
    var files by rememberSaveable { mutableStateOf("All books") }
    var tag by rememberSaveable { mutableStateOf("") }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    val tabs =
        remember(books, signals, fileIds, currentId) {
            libraryTabs(books, signals, fileIds, currentId)
        }
    val source =
        when {
            scoped -> books
            tab == LibraryTab.LIBRARY -> tabs.library
            else -> tabs.wanted
        }
    // Want to read is always newest first; its status is the tab itself.
    val wanted = !scoped && tab == LibraryTab.WANT_TO_READ
    val effectiveStatus = if (wanted) "All" else status
    val effectiveSort = if (wanted) "Recent" else sort
    val index by
        produceState<List<SearchBook>?>(null, source) {
            value = withContext(Dispatchers.Default) { indexLibrary(source) }
        }
    val results by
        produceState<List<Book>?>(
            null,
            index,
            query,
            effectiveSort,
            effectiveStatus,
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
                        searchLibrary(
                            current,
                            query,
                            effectiveStatus,
                            files,
                            tag,
                            effectiveSort,
                            signals,
                            fileIds,
                        )
                    }
        }
    val filtered = results.orEmpty()
    val activeFilters = activeLibraryFilters(effectiveStatus, files, tag)
    val shelf =
        if (!scoped && tab == LibraryTab.LIBRARY && query.isBlank() && activeFilters == 0)
            tabs.reading
        else emptyList()
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val motionScope = rememberCoroutineScope()
    val bottomPadding = LocalContentBottomPadding.current + FolioSpace.item
    val columns = GridCells.Adaptive(104.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.6f))
    fun clearFilters() {
        query = ""
        status = "All"
        files = "All books"
        tag = ""
        onClearFilter()
    }

    Column(modifier.fillMaxWidth()) {
        if (selection.isNotEmpty()) SelectionHeader(selection.size, onClearSelection)
        else
            LibraryHeader(
                filterName ?: stringResource(R.string.library_title),
                count = if (results == null) null else source.size,
                searching = searching || query.isNotEmpty(),
                onSearch = {
                    if (searching || query.isNotEmpty()) {
                        query = ""
                        searching = false
                        keyboard?.hide()
                    } else searching = true
                },
                onAdd = if (scoped) null else onImport,
                busy = busy,
            )
        if (!scoped)
            PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent) {
                Tab(
                    selected = tab == LibraryTab.LIBRARY,
                    onClick = { tabName = LibraryTab.LIBRARY.name },
                    text = { Text(stringResource(R.string.library_tab_library), maxLines = 1) },
                    modifier = Modifier.testTag(LIBRARY_TAB_LIBRARY_TAG),
                )
                Tab(
                    selected = tab == LibraryTab.WANT_TO_READ,
                    onClick = { tabName = LibraryTab.WANT_TO_READ.name },
                    text = { Text(stringResource(R.string.library_tab_wanted), maxLines = 1) },
                    modifier = Modifier.testTag(LIBRARY_TAB_WANTED_TAG),
                )
            }
        AnimatedVisibility(searching || query.isNotEmpty()) {
            SearchField(query, onQuery = { query = it }, onClear = { query = "" })
        }
        LibraryToolbar(
            sort = if (wanted) null else sort,
            onSort = { sort = it },
            grid = grid,
            onGrid = { showGrid ->
                if (showGrid != grid) {
                    grid = showGrid
                    motionScope.launch {
                        val target = maxOf(0, filtered.size - 1)
                        if (showGrid)
                            gridState.scrollToItem(
                                listState.firstVisibleItemIndex.coerceAtMost(target)
                            )
                        else
                            listState.scrollToItem(
                                gridState.firstVisibleItemIndex.coerceAtMost(target)
                            )
                    }
                }
            },
            activeFilters = activeFilters,
            onFilter = {
                keyboard?.hide()
                showFilter = true
            },
        )
        if (results == null) LoadingGrid(columns, Modifier.weight(1f))
        else if (filtered.isEmpty())
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    source.isNotEmpty() || scoped ->
                        EmptyState(
                            stringResource(R.string.library_no_match_title),
                            stringResource(R.string.library_no_match_body),
                            Icons.Outlined.FilterList,
                            { clearFilters() },
                            actionEnabled = !busy,
                            actionLabel = stringResource(R.string.library_no_match_action),
                        )
                    wanted ->
                        EmptyState(
                            stringResource(R.string.library_wanted_empty_title),
                            stringResource(R.string.library_wanted_empty_body),
                            Icons.Outlined.BookmarkBorder,
                            onFind ?: onImport,
                            actionEnabled = !busy,
                            actionLabel = stringResource(R.string.library_wanted_empty_find),
                            illustration = FolioIllustration.Bookmark,
                            secondaryAction = onScan,
                            secondaryLabel = stringResource(R.string.library_wanted_empty_scan),
                        )
                    else ->
                        EmptyState(
                            stringResource(R.string.library_empty_title),
                            stringResource(R.string.library_empty_body),
                            Icons.Outlined.AutoStories,
                            onImport,
                            actionEnabled = !busy,
                            actionLabel = stringResource(R.string.library_empty_action),
                            illustration = FolioIllustration.Books,
                        )
                }
            }
        else
            AnimatedContent(
                grid,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    fadeIn(FolioMotion.standardSpec()) togetherWith
                        fadeOut(FolioMotion.standardSpec())
                },
                label = "Library layout",
            ) { showGrid ->
                val tile: @Composable (Book, Modifier, CoverVariant, String?) -> Unit =
                    { book, tileModifier, variant, label ->
                        BookCoverCell(
                            book,
                            tileModifier,
                            selected = book.id in selection,
                            variant = variant,
                            signals = signals[book.id],
                            progress = progress[book.id],
                            noEpub = book.id !in fileIds,
                            label = label,
                            onClick = {
                                if (selection.isEmpty()) onOpen(book) else onSelect(book.id)
                            },
                            onSelect = { onSelect(book.id) },
                            onContext = onContext?.let { { it(book) } },
                        )
                    }
                if (showGrid)
                    LazyVerticalGrid(
                        state = gridState,
                        columns = columns,
                        contentPadding =
                            PaddingValues(
                                start = FolioSpace.item,
                                end = FolioSpace.item,
                                top = FolioSpace.small,
                                bottom = bottomPadding,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
                        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
                        modifier = Modifier.fillMaxSize().testTag(LIBRARY_GRID_TAG),
                    ) {
                        if (shelf.isNotEmpty())
                            item(key = "shelf", span = { GridItemSpan(maxLineSpan) }) {
                                ReadingShelf(shelf, progress, tile)
                            }
                        items(filtered, key = { it.id }) { book ->
                            tile(
                                book,
                                Modifier.fillMaxWidth(),
                                if (book.id in fileIds) CoverVariant.Grid else CoverVariant.Wanted,
                                null,
                            )
                        }
                    }
                else
                    LazyColumn(
                        Modifier.fillMaxSize().testTag(LIBRARY_LIST_TAG),
                        state = listState,
                        contentPadding = PaddingValues(bottom = bottomPadding),
                    ) {
                        if (shelf.isNotEmpty())
                            item(key = "shelf") {
                                ReadingShelf(
                                    shelf,
                                    progress,
                                    tile,
                                    Modifier.padding(horizontal = FolioSpace.item),
                                )
                            }
                        items(filtered, key = { it.id }) { book ->
                            BookRow(
                                book,
                                selected = book.id in selection,
                                signals = signals[book.id],
                                progress = progress[book.id],
                                noEpub = book.id !in fileIds,
                                onClick = {
                                    if (selection.isEmpty()) onOpen(book) else onSelect(book.id)
                                },
                                onSelect = { onSelect(book.id) },
                                onContext = onContext?.let { { it(book) } },
                            )
                        }
                    }
            }
    }
    if (showFilter)
        LibraryFilterSheet(
            status = status,
            files = files,
            tag = tag,
            tags =
                remember(index) { index.orEmpty().flatMap { it.displayTags }.distinct().sorted() },
            count = filtered.size,
            showStatus = !wanted,
            onStatus = { status = it },
            onFiles = { files = it },
            onTag = { tag = it },
            onReset = {
                status = "All"
                files = "All books"
                tag = ""
            },
            onDismiss = { showFilter = false },
        )
}

@Composable
private fun LibraryHeader(
    title: String,
    count: Int?,
    searching: Boolean,
    onSearch: () -> Unit,
    onAdd: (() -> Unit)?,
    busy: Boolean,
) {
    val large = LocalDensity.current.fontScale > 1.5f
    Row(
        Modifier.fillMaxWidth().padding(start = FolioSpace.page, end = FolioSpace.gap, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style =
                if (large) MaterialTheme.typography.headlineSmall
                else MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (count != null) {
            Spacer(Modifier.width(FolioSpace.small))
            val label = pluralStringResource(R.plurals.library_book_count, count, count)
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.semantics { contentDescription = label },
            ) {
                Text(
                    count.toString(),
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSearch, modifier = Modifier.testTag(LIBRARY_SEARCH_TAG)) {
            Icon(
                if (searching) Icons.Outlined.SearchOff else Icons.Outlined.Search,
                stringResource(
                    if (searching) R.string.library_close_search else R.string.library_search
                ),
            )
        }
        if (onAdd != null)
            FilledTonalIconButton(onClick = onAdd, enabled = !busy) {
                Icon(Icons.Outlined.Add, stringResource(R.string.shell_add_books))
            }
    }
}

/** "3 selected" with Close; replaces the title row while books are selected. */
@Composable
private fun SelectionHeader(count: Int, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = FolioSpace.small, end = FolioSpace.page, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear) {
            Icon(Icons.Outlined.Close, stringResource(R.string.shell_clear_selection))
        }
        Text(
            stringResource(R.string.shell_selected_count, count),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onClear: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val hint = stringResource(R.string.library_search_hint)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(hint) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty())
                IconButton(onClick = onClear) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.library_clear_search))
                }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = FolioSpace.item, vertical = FolioSpace.small)
                .focusRequester(focus)
                .semantics { contentDescription = hint },
    )
}

/** One line: sort menu (Library tab only), grid or list, and Filter with a count dot. */
@Composable
private fun LibraryToolbar(
    sort: String?,
    onSort: (String) -> Unit,
    grid: Boolean,
    onGrid: (Boolean) -> Unit,
    activeFilters: Int,
    onFilter: () -> Unit,
) {
    val showGridLabel = stringResource(R.string.library_show_grid)
    val showListLabel = stringResource(R.string.library_show_list)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = FolioSpace.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sort != null) SortMenu(sort, onSort)
        else
            Text(
                stringResource(R.string.library_sort_newest),
                Modifier.padding(horizontal = FolioSpace.gap),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        Spacer(Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow(Modifier.height(36.dp)) {
            SegmentedButton(
                selected = grid,
                onClick = { onGrid(true) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = {},
                modifier = Modifier.semantics { contentDescription = showGridLabel },
            ) {
                Icon(Icons.Outlined.GridView, null, Modifier.size(18.dp))
            }
            SegmentedButton(
                selected = !grid,
                onClick = { onGrid(false) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = {},
                modifier = Modifier.semantics { contentDescription = showListLabel },
            ) {
                Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(18.dp))
            }
        }
        IconButton(onClick = onFilter, modifier = Modifier.testTag(LIBRARY_FILTER_TAG)) {
            BadgedBox(
                badge = {
                    if (activeFilters > 0)
                        Badge { Text(activeFilters.toString(), Modifier.clearAndSetSemantics {}) }
                }
            ) {
                Icon(
                    Icons.Outlined.Tune,
                    if (activeFilters == 0) stringResource(R.string.library_filter)
                    else stringResource(R.string.library_filter_count, activeFilters),
                )
            }
        }
    }
}

@Composable
private fun SortMenu(sort: String, onSort: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options =
        listOf(
            "Recent" to stringResource(R.string.library_sort_recent),
            "Title" to stringResource(R.string.library_sort_title),
            "Author" to stringResource(R.string.library_sort_author),
        )
    val current = options.firstOrNull { it.first == sort }?.second ?: sort
    val sortLabel = stringResource(R.string.library_sort_by, current)
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.semantics { contentDescription = sortLabel },
        ) {
            Icon(Icons.Outlined.SwapVert, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(current)
            Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = {
                        if (value == sort) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                    },
                    onClick = {
                        onSort(value)
                        open = false
                    },
                )
            }
        }
    }
}

/** Six blank covers while the index builds. The library never blocks on it. */
@Composable
private fun LoadingGrid(columns: GridCells, modifier: Modifier) {
    val loading = stringResource(R.string.library_loading)
    LazyVerticalGrid(
        columns = columns,
        userScrollEnabled = false,
        contentPadding = PaddingValues(horizontal = FolioSpace.item, vertical = FolioSpace.small),
        horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = loading },
    ) {
        items(6) {
            Box(
                Modifier.aspectRatio(FolioSpace.COVER_RATIO)
                    .clip(FolioSpace.coverShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            )
        }
    }
}

/** The "Reading" shelf: a row of 112 dp covers with their progress, the current book first. */
@Composable
private fun ReadingShelf(
    books: List<Book>,
    progress: Map<String, Double>,
    tile: @Composable (Book, Modifier, CoverVariant, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
        Text(
            stringResource(R.string.library_shelf_reading).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = FolioSpace.small),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
            contentPadding = PaddingValues(bottom = FolioSpace.small),
            modifier = Modifier.testTag(LIBRARY_SHELF_TAG),
        ) {
            items(books, key = { "shelf-${it.id}" }) { book ->
                val percent = progress[book.id]?.let { (it * 100).toInt() }
                tile(
                    book,
                    Modifier.width(112.dp),
                    CoverVariant.Shelf,
                    percent?.let { stringResource(R.string.rd_percent, it) },
                )
            }
        }
    }
}

/** One badge at most: finished, on the e-reader, sending, or a problem. No EPUB is the frame. */
@Composable
private fun coverBadge(signals: BookSignals?): Pair<ImageVector, String>? {
    val reading = signals?.reading ?: return null
    val device = signals.device
    return when {
        reading.startsWith("Finished") -> Icons.Outlined.DoneAll to reading
        device == "On Kindle" ->
            Icons.Outlined.TabletAndroid to stringResource(R.string.shell_device_on_ereader)
        device == "Sending" || device == "Queued" ->
            Icons.Outlined.Sync to stringResource(R.string.shell_device_sending_badge)
        device in listOf("Transfer failed", "Transfer paused", "Needs update") ->
            Icons.Outlined.ErrorOutline to displayDeviceState(signals)
        else -> null
    }
}

/** What TalkBack reads for a cover: title, author and, for a book in progress, its progress. */
@Composable
private fun coverDescription(book: Book, signals: BookSignals?, progress: Double?): String {
    val author = book.authorLabel.ifBlank { stringResource(R.string.shell_unknown_author) }
    val percent = progress?.takeIf { readingStatusOf(book, signals) == "Reading" }
    return if (percent == null) stringResource(R.string.rd_tile_book, book.title, author)
    else stringResource(R.string.rd_tile_book_progress, book.title, author, (percent * 100).toInt())
}

/**
 * A cover in the grid or on the shelf. Tap opens; long-press selects with a haptic tick. The
 * cover's own semantics are replaced by one description, so TalkBack reads "Title, Author, 37%"
 * once.
 */
@Composable
private fun BookCoverCell(
    book: Book,
    modifier: Modifier,
    selected: Boolean,
    variant: CoverVariant,
    signals: BookSignals?,
    progress: Double?,
    noEpub: Boolean,
    label: String?,
    onClick: () -> Unit,
    onSelect: () -> Unit,
    onContext: (() -> Unit)?,
) {
    val haptics = LocalHapticFeedback.current
    val description = coverDescription(book, signals, progress)
    val noEpubState = stringResource(R.string.shell_device_no_epub)
    val selectLabel =
        stringResource(if (selected) R.string.shell_deselect_book else R.string.shell_select_book)
    val actionsLabel = stringResource(R.string.library_book_actions)
    val inset by animateDpAsState(if (selected) 2.dp else 0.dp, label = "Selection inset")
    val reading = readingStatusOf(book, signals) == "Reading"
    Column(
        modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSelect()
                },
            )
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                this.selected = selected
                if (noEpub) stateDescription = noEpubState
                customActions =
                    listOfNotNull(
                        CustomAccessibilityAction(selectLabel) {
                            onSelect()
                            true
                        },
                        onContext?.let {
                            CustomAccessibilityAction(actionsLabel) {
                                it()
                                true
                            }
                        },
                    )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(inset)) {
            CoverTile(
                book,
                Modifier.fillMaxWidth()
                    .then(
                        if (selected)
                            Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.primary,
                                FolioSpace.coverShape,
                            )
                        else Modifier
                    ),
                variant = variant,
                progress = if (reading) progress?.toFloat() else null,
                badge = coverBadge(signals),
            )
            // The plain overload: inside this Column, the ColumnScope one would be picked.
            androidx.compose.animation.AnimatedVisibility(
                selected,
                Modifier.align(Alignment.TopEnd).padding(6.dp),
                enter = scaleIn(FolioMotion.standardSpec()) + fadeIn(FolioMotion.standardSpec()),
                exit = scaleOut(FolioMotion.standardSpec()) + fadeOut(FolioMotion.standardSpec()),
            ) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Outlined.Check, null, Modifier.padding(4.dp).size(16.dp))
                }
            }
        }
        if (label != null)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

/** The list layout's row: a small cover, title, author and a one-line state. */
@Composable
private fun BookRow(
    book: Book,
    selected: Boolean,
    signals: BookSignals?,
    progress: Double?,
    noEpub: Boolean,
    onClick: () -> Unit,
    onSelect: () -> Unit,
    onContext: (() -> Unit)?,
) {
    val haptics = LocalHapticFeedback.current
    val selectLabel =
        stringResource(if (selected) R.string.shell_deselect_book else R.string.shell_select_book)
    val actionsLabel = stringResource(R.string.library_book_actions)
    val reading = readingStatusOf(book, signals) == "Reading"
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSelect()
                },
            )
            .semantics(mergeDescendants = true) {
                this.selected = selected
                customActions =
                    listOfNotNull(
                        CustomAccessibilityAction(selectLabel) {
                            onSelect()
                            true
                        },
                        onContext?.let {
                            CustomAccessibilityAction(actionsLabel) {
                                it()
                                true
                            }
                        },
                    )
            }
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .padding(horizontal = FolioSpace.page, vertical = FolioSpace.gap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        CoverTile(
            book,
            Modifier.width(56.dp),
            variant = if (noEpub) CoverVariant.Wanted else CoverVariant.Grid,
            progress = if (reading) progress?.toFloat() else null,
            badge = coverBadge(signals),
            lifted = false,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                book.authorLabel.ifBlank { stringResource(R.string.shell_unknown_author) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                        libraryStatusLabel(readingStatusOf(book, signals)),
                        progress
                            ?.takeIf { reading }
                            ?.let { stringResource(R.string.rd_percent, (it * 100).toInt()) },
                        if (noEpub) stringResource(R.string.shell_device_no_epub) else null,
                    )
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected)
            Icon(
                Icons.Outlined.CheckCircle,
                stringResource(R.string.library_selected),
                tint = MaterialTheme.colorScheme.primary,
            )
    }
}
