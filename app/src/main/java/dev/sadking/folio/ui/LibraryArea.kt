package dev.sadking.folio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.data.*

/**
 * Library and list browsing share one selection and one book browser. The selection's actions live
 * in the shell's bottom bar, which swaps to them while books are selected.
 */
@Composable
internal fun LibraryArea(
    lists: Boolean,
    rootRequest: Int = 0,
    library: LibraryPresentation,
    shelves: List<Shelf>,
    selection: Set<String>,
    busy: Boolean,
    onSelection: (Set<String>) -> Unit,
    onOpen: (Book) -> Unit,
    onContext: (Book) -> Unit,
    onAdd: () -> Unit,
    onCreate: (String, String, String) -> Unit,
    onManageShelf: (Shelf) -> Unit,
    onPolicy: (Shelf, Boolean) -> Unit,
    onSendShelf: (Shelf) -> Unit,
    onDiscover: () -> Unit,
    onFind: () -> Unit,
    progress: Map<String, Double> = emptyMap(),
    currentId: String? = null,
    requestedList: String? = null,
    onRequestHandled: () -> Unit = {},
) {
    var scope by rememberSaveable { mutableStateOf<String?>(null) }
    // A tab tap shows the lists overview; coming back from a book page keeps the open list.
    var handledRoot by rememberSaveable { mutableIntStateOf(rootRequest) }
    LaunchedEffect(rootRequest) {
        if (handledRoot != rootRequest) {
            handledRoot = rootRequest
            if (requestedList == null) scope = null
        }
    }
    val holder = rememberSaveableStateHolder()
    val pinned = ReadingList.entries.firstOrNull { scope == "reading:${it.name}" }
    val shelf = shelves.firstOrNull { it.id == scope }
    LaunchedEffect(requestedList, lists) {
        if (lists && requestedList != null) {
            scope = "reading:$requestedList"
            onRequestHandled()
        }
    }
    LaunchedEffect(scope, shelves) {
        if (scope != null && pinned == null && shelf == null) scope = null
    }
    BackHandler(lists && scope != null && selection.isEmpty()) { scope = null }
    val bottom = LocalContentBottomPadding.current
    if (lists && scope == null)
        Box(Modifier.fillMaxSize().padding(bottom = bottom)) {
            CollectionsScreen(
                shelves,
                emptyList(),
                {},
                { scope = it },
                busy,
                counts = library.shelfEntries.mapValues { it.value.size },
                onSmartCreate = onCreate,
                onSync = onSendShelf,
                onPolicy = onPolicy,
                onDiscover = onDiscover,
                onReadingList = { scope = "reading:${it.name}" },
                readingCounts =
                    ReadingList.entries.associateWith { list ->
                        library.entries.count { list.contains(it, library.signals[it.id]) }
                    },
                onManage = onManageShelf,
                previews = library.shelfEntries,
            )
        }
    else
        Column(Modifier.fillMaxSize()) {
            if (lists)
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { scope = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.shell_all_lists))
                    }
                }
            val visible =
                if (!lists) library.entries
                else if (pinned != null)
                    library.entries.filter { pinned.contains(it, library.signals[it.id]) }
                else library.shelfEntries[scope].orEmpty()
            holder.SaveableStateProvider(if (lists) scope ?: "lists" else "library") {
                LibraryScreen(
                    visible,
                    selection,
                    Modifier.weight(1f),
                    onOpen,
                    onSelect = {
                        onSelection(if (it in selection) selection - it else selection + it)
                    },
                    filterName = if (lists) pinned?.title ?: shelf?.name else null,
                    onClearFilter = { scope = null },
                    onImport = onAdd,
                    busy = busy,
                    signals = library.signals,
                    fileIds = library.fileIds,
                    progress = progress,
                    currentId = currentId,
                    onContext = onContext,
                    onClearSelection = { onSelection(emptySet()) },
                    onFind = onFind,
                    onScan = onDiscover,
                    requestedTab =
                        if (!lists && requestedList == ReadingList.WANT_TO_READ.name)
                            LibraryTab.WANT_TO_READ
                        else null,
                    onRequestHandled = onRequestHandled,
                )
            }
        }
}
