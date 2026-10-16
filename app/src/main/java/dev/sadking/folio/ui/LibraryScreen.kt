@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package dev.sadking.folio.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.sadking.folio.R
import dev.sadking.folio.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val LIBRARY_GRID_TAG = "library-grid"
internal const val LIBRARY_LIST_TAG = "library-list"
internal const val LIBRARY_SHELF_TAG = "reading-shelf"
internal const val LIBRARY_TAB_LIBRARY_TAG = "library-tab-library"
internal const val LIBRARY_TAB_WANTED_TAG = "library-tab-wanted"
internal const val LIBRARY_FILTER_TAG = "library-filter"
internal const val LIBRARY_VIEW_TAG = "library-view"
internal const val LIBRARY_SEARCH_TAG = "library-search"
internal const val LIBRARY_REFRESH_TAG = "library-refresh"
/** The pull indicator stays at least this long, so a quick check still reads as one. */
private const val REFRESH_MIN_MILLIS = 600L

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
    /** Looks a search with no match up on Hardcover. */
    onSearchHardcover: ((String) -> Unit)? = null,
    onScan: (() -> Unit)? = null,
    requestedTab: LibraryTab? = null,
    onRequestHandled: () -> Unit = {},
    /** The list being filled from here: taps select instead of opening, under a banner. */
    pickingFor: String? = null,
    onCancelPicking: () -> Unit = {},
    /** Pulling down on the Library: Librillo checks the Inbox and the Books folder again. */
    onRefresh: () -> Unit = {},
    /** True while that check runs; the pull indicator stays until it ends. */
    refreshing: Boolean = false,
    /** Books whose file is gone. Null checks the disk off the main thread; previews pass a set. */
    missingFiles: Set<String>? = null,
    /** Replaces the title row (a list detail draws its own banner above the grid). */
    // A custom page header (a list's page) gets the view toggle to place among its actions.
    header: (@Composable (viewToggle: @Composable () -> Unit) -> Unit)? = null,
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
    // A book's file can vanish in the Files app without any change in the library: check the disk
    // again on return, on pull-down, and whenever the books change.
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose {}
    }
    val inspection = LocalInspectionMode.current
    val checkedMissing by
        produceState(emptySet<String>(), books, fileIds, checks, missingFiles) {
            value =
                if (missingFiles != null || inspection) emptySet()
                else
                    withContext(Dispatchers.IO) {
                        missingFileIds(books.filter { it.id in fileIds })
                    }
        }
    val missing = missingFiles ?: checkedMissing
    var pulled by remember { mutableStateOf(false) }
    val busyNow by rememberUpdatedState(busy || refreshing)
    LaunchedEffect(pulled) {
        if (pulled) {
            delay(REFRESH_MIN_MILLIS)
            snapshotFlow { busyNow }.first { !it }
            pulled = false
        }
    }
    val columns = GridCells.Adaptive(104.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.6f))
    fun clearFilters() {
        query = ""
        status = "All"
        files = "All books"
        tag = ""
        onClearFilter()
    }

    fun switchGrid(showGrid: Boolean) {
        if (showGrid == grid) return
        grid = showGrid
        motionScope.launch {
            val target = maxOf(0, filtered.size - 1)
            if (showGrid)
                gridState.scrollToItem(listState.firstVisibleItemIndex.coerceAtMost(target))
            else listState.scrollToItem(gridState.firstVisibleItemIndex.coerceAtMost(target))
        }
    }
    // Grid or list changes how the whole page shows, so it sits with the page's actions, not with
    // Sort and Filter, which change what is shown.
    val viewToggle: @Composable () -> Unit = { ViewToggle(grid, ::switchGrid) }
    // Opening a book from a search keeps the search for next time.
    fun open(book: Book) {
        if (query.isNotBlank()) view.searched(query)
        onOpen(book)
    }

    Column(modifier.fillMaxWidth()) {
        if (selection.isNotEmpty()) SelectionHeader(selection.size, onClearSelection)
        else if (header != null) header(viewToggle)
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
                viewToggle = viewToggle,
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
        if (pickingFor != null) PickingBanner(pickingFor, onCancelPicking)
        AnimatedVisibility(searching || query.isNotEmpty()) {
            SearchField(
                query,
                onQuery = { query = it },
                onClear = { query = "" },
                recent = view.recent.value,
                onSearched = view::searched,
                onClearRecent = view::clearRecent,
            )
        }
        LibraryToolbar(
            sort = if (wanted) null else sort,
            onSort = { sort = it },
            activeFilters = activeFilters,
            onFilter = {
                keyboard?.hide()
                showFilter = true
            },
        ) {
            QuickFilters(
                status = if (wanted) null else status,
                files = files,
                onStatus = { status = quickToggle(status, it, "All") },
                onFiles = { files = quickToggle(files, it, "All books") },
            )
        }
        PullToRefreshBox(
            isRefreshing = pulled,
            onRefresh = {
                pulled = true
                checks++
                onRefresh()
            },
            modifier = Modifier.weight(1f).fillMaxWidth().testTag(LIBRARY_REFRESH_TAG),
            enabled = !scoped && selection.isEmpty() && pickingFor == null,
        ) {
            if (results == null) LoadingGrid(columns, Modifier.fillMaxSize())
            else if (filtered.isEmpty())
                // Scrollable, so pulling down works on an empty library too.
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when {
                        source.isNotEmpty() || scoped ->
                            EmptyState(
                                stringResource(R.string.library_no_match_title),
                                stringResource(R.string.library_no_match_body),
                                Icons.Outlined.FilterList,
                                { clearFilters() },
                                actionEnabled = !busy,
                                actionLabel = stringResource(R.string.library_no_match_action),
                                // Not in the library: look the words up on Hardcover instead.
                                secondaryAction =
                                    onSearchHardcover
                                        ?.takeIf { query.isNotBlank() }
                                        ?.let { { it(query.trim()) } },
                                secondaryLabel =
                                    stringResource(R.string.library_search_hardcover, query.trim()),
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
                    modifier = Modifier.fillMaxSize(),
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
                                fileMissing = book.id in missing,
                                label = label,
                                onClick = {
                                    if (selection.isEmpty() && pickingFor == null) open(book)
                                    else onSelect(book.id)
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
                                    if (book.id in fileIds && book.id !in missing) CoverVariant.Grid
                                    else CoverVariant.Wanted,
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
                                    fileMissing = book.id in missing,
                                    onClick = {
                                        if (selection.isEmpty()) open(book) else onSelect(book.id)
                                    },
                                    onSelect = { onSelect(book.id) },
                                    onContext = onContext?.let { { it(book) } },
                                )
                            }
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
    viewToggle: @Composable () -> Unit,
) {
    val large = LocalDensity.current.fontScale > 1.5f
    Row(
        Modifier.fillMaxWidth().padding(start = FolioSpace.page, end = FolioSpace.gap, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Title and count take all the spare width, so the title only shortens when it must.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
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
        }
        val searchLabel =
            stringResource(
                if (searching) R.string.library_close_search else R.string.library_search
            )
        Tooltipped(searchLabel) {
            IconButton(onClick = onSearch, modifier = Modifier.testTag(LIBRARY_SEARCH_TAG)) {
                Icon(
                    if (searching) Icons.Outlined.SearchOff else Icons.Outlined.Search,
                    searchLabel,
                )
            }
        }
        viewToggle()
        if (onAdd != null) {
            val add = stringResource(R.string.shell_add_books)
            Tooltipped(add) {
                FilledTonalIconButton(onClick = onAdd, enabled = !busy) {
                    Icon(Icons.Outlined.Add, add)
                }
            }
        }
    }
}

/** "3 selected" with Close; replaces the title row while books are selected. */
internal const val LIBRARY_PICKING_BANNER_TAG = "library-picking-banner"

/** "Choose books for Slow Sundays": a tonal strip with a Cancel icon, shown while picking. */
@Composable
private fun PickingBanner(listName: String, onCancel: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth()
            .padding(horizontal = FolioSpace.item, vertical = FolioSpace.small)
            .testTag(LIBRARY_PICKING_BANNER_TAG),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            Modifier.padding(start = FolioSpace.item, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.rd_picking_for_list, listName),
                Modifier.weight(1f).padding(vertical = FolioSpace.small),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Outlined.Close,
                    stringResource(R.string.shell_cancel),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

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

internal const val LIBRARY_SEARCH_BAR_TAG = "library-search-bar"

/**
 * Material's search bar. The library below filters as you type; while the field is empty, recent
 * searches show under it as chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    recent: List<String>,
    onSearched: (String) -> Unit,
    onClearRecent: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val hint = stringResource(R.string.library_search_hint)
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column {
        DockedSearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = query,
                    onQueryChange = onQuery,
                    onSearch = {
                        onSearched(it)
                        keyboard?.hide()
                    },
                    expanded = false,
                    onExpandedChange = {},
                    placeholder = { Text(hint) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty())
                            IconButton(onClick = onClear) {
                                Icon(
                                    Icons.Outlined.Close,
                                    stringResource(R.string.library_clear_search),
                                )
                            }
                    },
                    modifier =
                        Modifier.focusRequester(focus)
                            .onFocusChanged { focused = it.isFocused }
                            .semantics { contentDescription = hint },
                )
            },
            expanded = false,
            onExpandedChange = {},
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = FolioSpace.item, vertical = FolioSpace.small)
                    .testTag(LIBRARY_SEARCH_BAR_TAG),
        ) {}
        // Recent searches as chips while the field is empty: a short row, so the books stay in
        // view.
        AnimatedVisibility(focused && query.isBlank() && recent.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = FolioSpace.item),
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                recent.forEach { past ->
                    SuggestionChip(
                        onClick = { onQuery(past) },
                        label = { Text(past, maxLines = 1) },
                        icon = {
                            Icon(
                                Icons.Outlined.History,
                                null,
                                Modifier.size(SuggestionChipDefaults.IconSize),
                            )
                        },
                    )
                }
                TextButton(onClick = onClearRecent) {
                    Text(stringResource(R.string.library_clear_recent))
                }
            }
        }
    }
}

internal const val LIBRARY_QUICK_FILTERS_TAG = "library-quick-filters"

/** A quick filter chip turns its value on, or back to [none] when it is already on. */
internal fun quickToggle(current: String, value: String, none: String): String =
    if (current == value) none else value

/**
 * One-tap filters for the common cases, the same ones the Filter sheet sets: three reading statuses
 * (not on Want to read, where [status] is null) and books that still need an EPUB.
 */
@Composable
private fun QuickFilters(
    status: String?,
    files: String,
    onStatus: (String) -> Unit,
    onFiles: (String) -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState())
            .padding(horizontal = FolioSpace.small)
            .testTag(LIBRARY_QUICK_FILTERS_TAG),
        horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        if (status != null)
            listOf("Reading", "Unread", "Finished").forEach { value ->
                QuickChip(status == value, libraryStatusLabel(value)) { onStatus(value) }
            }
        QuickChip(files == "Needs EPUB", stringResource(R.string.library_files_needs_epub)) {
            onFiles("Needs EPUB")
        }
    }
}

@Composable
private fun QuickChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected,
        onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon =
            if (selected) {
                { Icon(Icons.Outlined.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
            } else null,
    )
}

/**
 * The content controls: sort menu (Library tab only), the [quick] filter chips, which scroll in the
 * space between, and Filter with a count dot.
 */
@Composable
private fun LibraryToolbar(
    sort: String?,
    onSort: (String) -> Unit,
    activeFilters: Int,
    onFilter: () -> Unit,
    quick: @Composable () -> Unit,
) {
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
        // The chips fade out at the end, so a cut chip reads as "scroll for more".
        Box(
            Modifier.weight(1f)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.horizontalGradient(0.85f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                }
        ) {
            quick()
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

/** One icon showing the layout it switches to, as Files and Gmail do. */
@Composable
private fun ViewToggle(grid: Boolean, onGrid: (Boolean) -> Unit) {
    val label = stringResource(if (grid) R.string.library_show_list else R.string.library_show_grid)
    Tooltipped(label) {
        IconButton(onClick = { onGrid(!grid) }, modifier = Modifier.testTag(LIBRARY_VIEW_TAG)) {
            Icon(if (grid) Icons.AutoMirrored.Filled.List else Icons.Outlined.GridView, label)
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
@OptIn(ExperimentalMaterial3Api::class)
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
        // Material's carousel: covers keep their size and the one leaving the edge narrows under
        // a mask, so the row reads as more to scroll. Covers keep their captions below.
        HorizontalUncontainedCarousel(
            state = rememberCarouselState { books.size },
            itemWidth = 112.dp,
            itemSpacing = FolioSpace.gap,
            contentPadding = PaddingValues(bottom = FolioSpace.small),
            modifier = Modifier.fillMaxWidth().testTag(LIBRARY_SHELF_TAG),
        ) { i ->
            val book = books[i]
            val percent = progress[book.id]?.let { (it * 100).toInt() }
            tile(
                book,
                Modifier.width(112.dp).maskClip(RectangleShape),
                CoverVariant.Shelf,
                percent?.let { stringResource(R.string.rd_percent, it) },
            )
        }
    }
}

/**
 * One badge at most: file missing, finished, on the e-reader, sending, or a problem. No EPUB is the
 * frame.
 */
@Composable
private fun coverBadge(
    signals: BookSignals?,
    fileMissing: Boolean = false,
): Pair<ImageVector, String>? {
    if (fileMissing) return Icons.Outlined.SearchOff to stringResource(R.string.m9ui_file_missing)
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
    fileMissing: Boolean = false,
    label: String?,
    onClick: () -> Unit,
    onSelect: () -> Unit,
    onContext: (() -> Unit)?,
) {
    val haptics = rememberHaptics()
    val description = coverDescription(book, signals, progress)
    val noEpubState = stringResource(R.string.shell_device_no_epub)
    val missingState = stringResource(R.string.m9ui_file_missing)
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
                    haptics.longPress()
                    onSelect()
                },
            )
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                this.selected = selected
                if (noEpub) stateDescription = noEpubState
                else if (fileMissing) stateDescription = missingState
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
                badge = coverBadge(signals, fileMissing),
            )
            // The plain overload: inside this Column, the ColumnScope one would be picked.
            val reduced = FolioMotion.reduced
            androidx.compose.animation.AnimatedVisibility(
                selected,
                Modifier.align(Alignment.TopEnd).padding(6.dp),
                enter =
                    if (reduced) fadeIn(FolioMotion.standardSpec())
                    else scaleIn(FolioMotion.standardSpec()) + fadeIn(FolioMotion.standardSpec()),
                exit =
                    if (reduced) fadeOut(FolioMotion.standardSpec())
                    else scaleOut(FolioMotion.standardSpec()) + fadeOut(FolioMotion.standardSpec()),
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
    fileMissing: Boolean = false,
    onClick: () -> Unit,
    onSelect: () -> Unit,
    onContext: (() -> Unit)?,
) {
    val haptics = rememberHaptics()
    val selectLabel =
        stringResource(if (selected) R.string.shell_deselect_book else R.string.shell_select_book)
    val actionsLabel = stringResource(R.string.library_book_actions)
    val reading = readingStatusOf(book, signals) == "Reading"
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.longPress()
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
            variant = if (noEpub || fileMissing) CoverVariant.Wanted else CoverVariant.Grid,
            progress = if (reading) progress?.toFloat() else null,
            badge = coverBadge(signals, fileMissing),
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
                        if (fileMissing) stringResource(R.string.m9ui_file_missing) else null,
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
