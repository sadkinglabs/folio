@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
)

package dev.sadking.folio.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.sadking.folio.*
import dev.sadking.folio.R
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
import dev.sadking.folio.device.*
import kotlinx.coroutines.flow.collectLatest

@Composable
fun FolioApp(model: LibraryViewModel) {
    val discovery: DiscoveryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val devices: DeviceViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var showProfileImport by rememberSaveable { mutableStateOf(false) }
    val nav = rememberFolioNav()
    val catalogueId = nav.recordId
    var catalogueProgress by rememberSaveable { mutableStateOf(false) }
    var catalogueEditorId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingShelfId by rememberSaveable { mutableStateOf<String?>(null) }
    var showRemoved by rememberSaveable { mutableStateOf(false) }
    // "lists" or "status": which sheet the selection bar opened.
    var batchSheet by rememberSaveable { mutableStateOf<String?>(null) }
    var detachBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var epubExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionsId by rememberSaveable { mutableStateOf<String?>(null) }
    var listEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    var statusEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    var attachWishId by rememberSaveable { mutableStateOf<String?>(null) }
    var attachReviewId by rememberSaveable { mutableStateOf<String?>(null) }
    var removalIds by remember { mutableStateOf(setOf<String>()) }
    var removalAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(removalIds) { removalAttempted = false }
    var showAddBooks by rememberSaveable { mutableStateOf(false) }
    var titleLookupQuery by rememberSaveable { mutableStateOf("") }
    var showTitleLookup by rememberSaveable { mutableStateOf(false) }
    var resumeHardcoverSearch by rememberSaveable { mutableStateOf(false) }
    var resumeHardcoverImport by rememberSaveable { mutableStateOf(false) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var showHardcover by rememberSaveable { mutableStateOf(false) }
    var showDiscovery by rememberSaveable { mutableStateOf(false) }
    var showNowReading by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("appearance", 0) }
    var appearance by rememberSaveable {
        mutableStateOf(prefs.getString("theme", "System").orEmpty())
    }
    var dynamic by rememberSaveable { mutableStateOf(prefs.getBoolean("dynamic", true)) }
    val dark =
        when (appearance) {
            "Dark" -> true
            "Light" -> false
            else -> isSystemInDarkTheme()
        }
    FolioTheme(dark, dynamic) {
        val books by model.books.collectAsStateWithLifecycle()
        val phoneReading by model.phoneReading.collectAsStateWithLifecycle()
        val phoneFolder by devices.phoneFolder.collectAsStateWithLifecycle()
        val folderReport by devices.folderReport.collectAsStateWithLifecycle()
        val removed by model.removed.collectAsStateWithLifecycle()
        val importResults by model.importResults.collectAsStateWithLifecycle()
        val showImportResults by model.showImportResults.collectAsStateWithLifecycle()
        val managementError by model.managementError.collectAsStateWithLifecycle()
        val shelves by model.shelves.collectAsStateWithLifecycle()
        val memberships by model.memberships.collectAsStateWithLifecycle()
        val foregroundOp by model.operation.collectAsStateWithLifecycle()
        val discoveryState by discovery.state.collectAsStateWithLifecycle()
        val activeTasks by devices.activeSyncTasks.collectAsStateWithLifecycle()
        val syncJobs by devices.syncJobs.collectAsStateWithLifecycle()
        val syncJob = syncJobs.firstOrNull()
        // A running operation disables only controls that would start another one. A transfer
        // runs in its own service: it blocks device actions, never reading or library changes.
        val op = foregroundOp
        val deviceBusy = foregroundOp.busy || syncJobs.any { it.active }
        val snackbar = rememberOperationSnackbar(model.notices)
        val stateHolder = rememberSaveableStateHolder()
        val hardcoverUsername by discovery.username.collectAsStateWithLifecycle()
        val wishes by discovery.wishes.collectAsStateWithLifecycle()
        val wishMemberships by discovery.wishMemberships.collectAsStateWithLifecycle()
        val observations by discovery.observations.collectAsStateWithLifecycle()
        val accountId by discovery.accountId.collectAsStateWithLifecycle()
        val catalogue = wishes.firstOrNull { it.id == catalogueId }
        LaunchedEffect(
            discovery,
            showDiscovery,
            showHardcover,
            showProfileImport,
            showTitleLookup,
            showAccount,
            catalogueId,
        ) {
            if (
                !showDiscovery &&
                    !showHardcover &&
                    !showProfileImport &&
                    !showTitleLookup &&
                    !showAccount &&
                    catalogueId == null
            )
                discovery.notices.collectLatest { notice ->
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(
                        notice.message,
                        withDismissAction = true,
                        duration =
                            if (notice.failed) SnackbarDuration.Long else SnackbarDuration.Short,
                    )
                }
        }

        val plan by devices.plan.collectAsStateWithLifecycle()
        val history by model.history.collectAsStateWithLifecycle()
        val inventory by devices.inventory.collectAsStateWithLifecycle()
        val inventoryCheckedAt by devices.inventoryCheckedAt.collectAsStateWithLifecycle()
        val device by devices.device.collectAsStateWithLifecycle()
        val observedHost by devices.observedHost.collectAsStateWithLifecycle()
        val probeStatus by devices.probeStatus.collectAsStateWithLifecycle()
        val folderStatus by devices.folderStatus.collectAsStateWithLifecycle()
        val folderListing by devices.folderListing.collectAsStateWithLifecycle()
        val transfers by model.transfers.collectAsStateWithLifecycle()
        val allReading by model.reading.collectAsStateWithLifecycle()
        val reading =
            remember(allReading, device.id) { allReading.filter { it.deviceId == device.id } }
        val library =
            remember(
                books,
                wishes,
                shelves,
                memberships,
                wishMemberships,
                transfers,
                reading,
                activeTasks,
                observations,
                accountId,
                device.id,
                device.host,
                syncJob?.state,
            ) {
                presentLibrary(
                    books,
                    wishes,
                    shelves,
                    memberships,
                    wishMemberships,
                    transfers,
                    reading,
                    activeTasks,
                    observations,
                    accountId,
                    device.id,
                    device.host.isNotBlank(),
                    syncJob?.state,
                )
            }
        val catalogueBooks = library.entries
        // The progress drawn on covers: the newer of the phone's own place and the e-reader's.
        val progressById =
            remember(phoneReading, library) {
                val latest = latestPhoneReading(phoneReading)
                library.entries
                    .mapNotNull { entry ->
                        readingProgress(latest[entry.id], library.signals[entry.id])?.let {
                            entry.id to it
                        }
                    }
                    .toMap()
            }
        val signals = library.signals
        val latestRead by model.currentBook.collectAsStateWithLifecycle()
        // A book attached to a record may keep its cover on the record.
        val currentBook =
            remember(latestRead, wishes) {
                latestRead?.let { current ->
                    val b = current.book
                    current.copy(
                        book =
                            b.copy(
                                coverPath =
                                    b.coverPath
                                        ?: wishes.firstOrNull { it.linkedBookId == b.id }?.coverPath
                            )
                    )
                }
            }
        val recentReading =
            remember(books, signals) {
                books
                    .filter { ReadingList.READING.contains(it, signals[it.id]) }
                    .sortedByDescending { it.addedAt }
                    .take(3)
            }
        val statistics by model.statistics.collectAsStateWithLifecycle()
        val annotations by model.annotations.collectAsStateWithLifecycle()
        val lookupResults by model.lookupResults.collectAsStateWithLifecycle()
        val lookupProposal by model.lookupProposal.collectAsStateWithLifecycle()
        val lookupCover by model.lookupCover.collectAsStateWithLifecycle()
        var showLookup by rememberSaveable { mutableStateOf(false) }
        var restoreUri by rememberSaveable { mutableStateOf<String?>(null) }
        var exportBookId by rememberSaveable { mutableStateOf<String?>(null) }
        val backupPicker =
            rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/zip")
            ) {
                it?.let(model::exportBackup)
            }
        val restorePicker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
                restoreUri = it?.toString()
            }
        val markdownPicker =
            rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("text/markdown")
            ) { uri ->
                if (uri != null)
                    books
                        .firstOrNull { it.id == exportBookId }
                        ?.let { model.exportHighlights(it, uri) }
            }
        var installCompanion by rememberSaveable { mutableStateOf(false) }
        var companionPath by rememberSaveable { mutableStateOf("/mnt/us/koreader/plugins") }
        if (installCompanion)
            AlertDialog(
                onDismissRequest = { installCompanion = false },
                title = { Text(stringResource(R.string.shell_companion_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.shell_companion_body))
                        OutlinedTextField(
                            companionPath,
                            { companionPath = it },
                            label = { Text(stringResource(R.string.shell_companion_folder)) },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            installCompanion = false
                            devices.installCompanion(companionPath.trim())
                        }
                    ) {
                        Text(stringResource(R.string.shell_companion_install))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { installCompanion = false }) {
                        Text(stringResource(R.string.shell_cancel))
                    }
                },
            )
        val phoneFolderPicker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) devices.choosePhoneFolder(uri)
            }
        var pendingSendId by rememberSaveable { mutableStateOf<String?>(null) }
        var pairingSendId by rememberSaveable { mutableStateOf<String?>(null) }
        var pendingSync by rememberSaveable { mutableStateOf<String?>(null) }
        val notificationPrefs = remember { context.getSharedPreferences("sync-ui", 0) }
        fun performRequestedSync() {
            val action = pendingSync
            pendingSync = null
            if (action == "book") {
                val id = pendingSendId
                pendingSendId = null
                books.firstOrNull { it.id == id }?.let(devices::sendBook)
            } else if (action == "books") {
                val ids = pendingSendId.orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
                pendingSendId = null
                if (ids.isNotEmpty()) devices.sendBooks(ids)
            } else if (action == "shelf") {
                val id = pendingSendId
                pendingSendId = null
                shelves.firstOrNull { it.id == id }?.let(devices::sendShelf)
            } else if (action == "missing") devices.syncMissing()
            else if (action == "preview") devices.sync()
        }
        val notificationPermission =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                performRequestedSync()
            }
        /** [target] is a book id, comma-joined book ids or a list id, depending on [action]. */
        fun requestSync(action: String, target: String? = null) {
            pendingSendId = target
            pendingSync = action
            if (
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    !notificationPrefs.getBoolean("requested", false)
            ) {
                notificationPrefs.edit().putBoolean("requested", true).apply()
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else performRequestedSync()
        }
        val tab = nav.tab
        val density = androidx.compose.ui.platform.LocalDensity.current
        val detailId = nav.bookId
        var editorDirty by rememberSaveable { mutableStateOf(false) }
        var confirmDiscard by rememberSaveable { mutableStateOf(false) }
        val editing = nav.editing
        // Multi-select survives rotation and process recreation.
        var selection by
            rememberSaveable(
                stateSaver =
                    androidx.compose.runtime.saveable.Saver<Set<String>, ArrayList<String>>(
                        save = { ArrayList(it) },
                        restore = { it.toSet() },
                    )
            ) {
                mutableStateOf(setOf<String>())
            }
        var requestedList by rememberSaveable { mutableStateOf<String?>(null) }
        var showConnection by rememberSaveable { mutableStateOf(false) }
        /** Sends from where you are; only pairing or a stuck transfer takes you elsewhere. */
        fun sendOrPair(send: () -> Unit) {
            when {
                devices.sendsThroughLink -> send()
                device.host.isBlank() -> showConnection = true
                syncJob?.state in listOf("FAILED", "PAUSED") -> nav.root(FolioNav.DEVICE)
                else -> send()
            }
        }
        /** Sends one book, pairing first when needed and surfacing a stuck transfer. */
        fun actionsSend(id: String) {
            if (devices.sendsThroughLink) requestSync("book", id)
            else if (device.host.isBlank()) {
                pairingSendId = id
                showConnection = true
            } else if (syncJob?.state in listOf("FAILED", "PAUSED")) {
                nav.root(FolioNav.DEVICE)
            } else requestSync("book", id)
        }
        val picker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
                model.import(it)
            }
        val epubExportPicker =
            rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/epub+zip")
            ) { uri ->
                if (uri != null)
                    books.firstOrNull { it.id == epubExportId }?.let { model.exportEpub(it, uri) }
            }
        val attachmentPicker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) {
                    attachWishId = null
                    attachReviewId = null
                } else discovery.importForAttachment(uri) { attachReviewId = it.id }
            }
        LaunchedEffect(catalogue?.linkedBookId, books) {
            catalogue?.linkedBookId?.let { id ->
                if (books.any { it.id == id }) {
                    nav.replaceTop(Route.Book(id))
                    catalogueProgress = false
                }
            }
        }
        val book =
            books
                .firstOrNull { it.id == detailId }
                ?.let { b ->
                    b.copy(
                        coverPath =
                            b.coverPath ?: wishes.firstOrNull { it.linkedBookId == b.id }?.coverPath
                    )
                }
        val nextBook =
            book
                ?.takeIf { it.series.isNotBlank() }
                ?.let { current ->
                    books
                        .filter {
                            it.id != current.id &&
                                it.series.equals(current.series, true) &&
                                it.status !in listOf("Finished", "Abandoned") &&
                                reading
                                    .filter { r -> r.bookId == it.id }
                                    .maxByOrNull { r -> r.fetchedAt }
                                    ?.status != "complete" &&
                                LibraryRules.seriesNumber(it.seriesIndex) >
                                    LibraryRules.seriesNumber(current.seriesIndex)
                        }
                        .minByOrNull { LibraryRules.seriesNumber(it.seriesIndex) }
                }
        fun leaveBook() {
            if (editing && editorDirty) confirmDiscard = true
            else {
                if (catalogueId != null) catalogueProgress = false
                nav.back()
            }
        }
        // Back pops a book screen, then clears a selection; a root tab leaves the app.
        BackHandler(nav.stack.isNotEmpty() || selection.isNotEmpty()) {
            if (editing && op.busy) return@BackHandler
            if (nav.stack.isNotEmpty()) leaveBook() else selection = emptySet()
        }
        // Opens a book screen for a library entry, with or without an EPUB.
        fun openEntry(id: String, progress: Boolean = false) {
            if (books.any { it.id == id }) nav.open(Route.Book(id))
            else {
                nav.open(Route.Record(id))
                catalogueProgress = progress
            }
        }
        // The Add sheet's routes; the Want to read tab's "Find a book" uses the lookup one.
        fun addBook(action: AddBookAction) {
            when (action) {
                AddBookAction.EPUB ->
                    picker.launch(arrayOf("application/epub+zip", "application/octet-stream"))
                AddBookAction.MANUAL -> {
                    model.managementError.value = null
                    catalogueEditorId = "new"
                }
                AddBookAction.DISCOVER -> showDiscovery = true
                AddBookAction.TITLE -> {
                    titleLookupQuery = ""
                    discovery.clearTitleLookup()
                    if (hardcoverUsername.isBlank()) {
                        resumeHardcoverSearch = true
                        showAccount = true
                    } else showTitleLookup = true
                }
                AddBookAction.HARDCOVER ->
                    if (hardcoverUsername.isBlank()) {
                        resumeHardcoverImport = true
                        showAccount = true
                    } else {
                        showProfileImport = true
                        discovery.loadProfile()
                    }
            }
        }
        SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    containerColor = MaterialTheme.colorScheme.surface,
                    topBar = {
                        if (book != null || catalogue != null)
                            TopAppBar(
                                title = {
                                    Text(
                                        stringResource(
                                            if (editing) R.string.shell_edit_book
                                            else R.string.shell_book_details
                                        )
                                    )
                                },
                                navigationIcon = {
                                    IconButton(
                                        onClick = { leaveBook() },
                                        enabled = !editing || !op.busy,
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            stringResource(R.string.shell_back),
                                        )
                                    }
                                },
                                actions = {
                                    if (!editing)
                                        IconButton(
                                            onClick = {
                                                if (book != null) {
                                                    editorDirty = false
                                                    nav.open(Route.Edit(book.id))
                                                } else catalogueEditorId = catalogue?.id
                                            }
                                        ) {
                                            Icon(
                                                Icons.Outlined.Edit,
                                                stringResource(R.string.shell_edit_metadata),
                                            )
                                        }
                                    if (!editing)
                                        IconButton(
                                            onClick = { actionsId = book?.id ?: catalogue?.id }
                                        ) {
                                            Icon(
                                                Icons.Outlined.MoreVert,
                                                stringResource(R.string.shell_book_actions),
                                            )
                                        }
                                },
                            )
                    },
                    bottomBar = {
                        // The bar swaps to the selection's actions with a vertical slide, at the
                        // same height, so the content under it does not move.
                        if (book == null && catalogue == null)
                            AnimatedContent(
                                selection.isNotEmpty(),
                                transitionSpec = {
                                    (fadeIn(FolioMotion.standardSpec()) +
                                            slideInVertically(FolioMotion.standardSpec()) {
                                                it / 2
                                            })
                                        .togetherWith(
                                            fadeOut(FolioMotion.standardSpec()) +
                                                slideOutVertically(FolioMotion.standardSpec()) {
                                                    it / 2
                                                }
                                        )
                                },
                                label = "Bar",
                            ) { selecting ->
                                if (selecting)
                                    SelectionActionBar(
                                        enabled = !op.busy,
                                        sendEnabled = selection.any { it in library.fileIds },
                                        onSend = {
                                            sendOrPair {
                                                requestSync(
                                                    "books",
                                                    selection
                                                        .intersect(library.fileIds)
                                                        .joinToString(","),
                                                )
                                                selection = emptySet()
                                            }
                                        },
                                        onLists = { batchSheet = "lists" },
                                        onStatus = { batchSheet = "status" },
                                        onRemove = { removalIds = selection },
                                        hazeState = hazeState,
                                    )
                                else
                                    FolioBar(
                                        tab = tab,
                                        onTab = { i ->
                                            nav.root(i)
                                            selection = emptySet()
                                        },
                                        current = currentBook,
                                        onContinue = {
                                            currentBook?.let {
                                                dev.sadking.folio.reader.ReaderActivity.open(
                                                    context,
                                                    it.book.id,
                                                )
                                            }
                                        },
                                        onOptions = { showNowReading = true },
                                        transferActive = syncJob?.active == true,
                                        hazeState = hazeState,
                                    )
                            }
                    },
                ) { padding ->
                    // The whole content is the glass bar's backdrop. Tabs scroll under the bar
                    // and pad their own content by the bar's height; a book screen, which has no
                    // bar, keeps the window inset as ordinary padding.
                    val bottom = padding.calculateBottomPadding()
                    Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                        CompositionLocalProvider(LocalContentBottomPadding provides bottom) {
                            Column(
                                Modifier.fillMaxSize()
                                    .padding(top = padding.calculateTopPadding())
                                    .then(
                                        if (book != null || catalogue != null)
                                            Modifier.padding(bottom = bottom)
                                        else Modifier
                                    )
                            ) {
                                if (foregroundOp.busy) StatusSurface(foregroundOp)
                                // The device tab shows the full transfer; everywhere else gets a
                                // compact
                                // banner.
                                if (
                                    syncJob?.active == true &&
                                        !(tab == FolioNav.DEVICE && nav.stack.isEmpty())
                                )
                                    TransferBanner(
                                        syncJob,
                                        onOpen = { nav.root(FolioNav.DEVICE) },
                                        onCancel = { devices.cancelSync(syncJob.id) },
                                    )
                                if (book != null) {
                                    if (editing)
                                        MetadataEditor(
                                            book,
                                            op.busy,
                                            onDirtyChange = { editorDirty = it },
                                        ) { metadata, cover ->
                                            model.edit(book, metadata, cover) {
                                                editorDirty = false
                                                if (nav.editing) nav.back()
                                            }
                                        }
                                    else
                                        BookDetails(
                                            book,
                                            shelves.filter { it.ruleField == "Manual" },
                                            memberships
                                                .filter { it.bookId == book.id }
                                                .map { it.shelfId }
                                                .toSet(),
                                            transfers.any {
                                                it.bookId == book.id &&
                                                    it.deviceId == device.id &&
                                                    it.hash == book.currentHash
                                            },
                                            onRead = {
                                                dev.sadking.folio.reader.ReaderActivity.open(
                                                    context,
                                                    book.id,
                                                )
                                            },
                                            phoneReading =
                                                phoneReading.firstOrNull {
                                                    it.bookId == book.id &&
                                                        it.revisionHash == book.currentHash
                                                },
                                            onShelf = { id, checked ->
                                                model.membership(book.id, id, checked)
                                            },
                                            onStatus = { model.status(book, it) },
                                            onSync = {
                                                if (devices.sendsThroughLink)
                                                    requestSync("book", book.id)
                                                else if (device.host.isBlank()) {
                                                    pairingSendId = book.id
                                                    showConnection = true
                                                } else if (
                                                    syncJob?.state in listOf("FAILED", "PAUSED")
                                                ) {
                                                    nav.root(FolioNav.DEVICE)
                                                } else requestSync("book", book.id)
                                            },
                                            signals = signals[book.id],
                                            onRemove = { removalIds = setOf(book.id) },
                                            onCollections = { listEntryId = book.id },
                                            onRefreshReading = {
                                                if (device.host.isBlank()) showConnection = true
                                                else devices.refreshBookReading(book.id)
                                            },
                                            onReading = { nav.root(FolioNav.LIBRARY) },
                                            busy = op.busy,
                                            deviceBusy = deviceBusy,
                                            reading =
                                                reading
                                                    .filter {
                                                        it.bookId == book.id &&
                                                            it.deviceId == device.id
                                                    }
                                                    .maxByOrNull { it.fetchedAt },
                                            annotations =
                                                annotations.filter { it.bookId == book.id },
                                            onLookup = {
                                                model.clearLookup()
                                                showLookup = true
                                            },
                                            onExport = {
                                                exportBookId = book.id
                                                markdownPicker.launch("Folio-${book.id}.md")
                                            },
                                            nextInSeries = nextBook,
                                            nextOnDevice =
                                                nextBook?.let { next ->
                                                    transfers.any {
                                                        it.bookId == next.id &&
                                                            it.deviceId == device.id &&
                                                            it.hash == next.currentHash
                                                    }
                                                } == true,
                                            onNext = { nav.open(Route.Book(it.id)) },
                                            onHardcover = {
                                                showHardcover = true
                                                discovery.openReading(book)
                                            },
                                        )
                                } else if (catalogue != null) {
                                    CatalogueBookDetails(
                                        discovery,
                                        catalogue,
                                        books,
                                        shelves,
                                        onProgress = {
                                            catalogueProgress = true
                                            discovery.openReading(catalogue.displayBook())
                                        },
                                        onRemove = { removalIds = setOf(catalogue.id) },
                                        onActions = {
                                            actionsId = catalogue.id
                                        },
                                        onDismiss = { leaveBook() },
                                        onLists = { listEntryId = catalogue.id },
                                    )
                                } else
                                    AnimatedContent(
                                        tab,
                                        modifier = Modifier.fillMaxSize(),
                                        transitionSpec = {
                                            val offset = with(density) { 8.dp.roundToPx() }
                                            (fadeIn(FolioMotion.standardSpec()) +
                                                    slideInVertically(FolioMotion.standardSpec()) {
                                                        offset
                                                    })
                                                .togetherWith(
                                                    fadeOut(FolioMotion.standardSpec()) +
                                                        slideOutVertically(
                                                            FolioMotion.standardSpec()
                                                        ) {
                                                            -offset
                                                        }
                                                )
                                        },
                                        label = "Tab",
                                    ) { shownTab ->
                                        stateHolder.SaveableStateProvider("tab-$shownTab") {
                                            // Library and Lists scroll under the bar; Device and
                                            // Settings keep the bar's height as padding until they
                                            // move over.
                                            Box(
                                                Modifier.fillMaxSize()
                                                    .then(
                                                        if (
                                                            shownTab == FolioNav.DEVICE ||
                                                                shownTab == FolioNav.SETTINGS
                                                        )
                                                            Modifier.padding(bottom = bottom)
                                                        else Modifier
                                                    )
                                            ) {
                                                when (shownTab) {
                                                    FolioNav.LIBRARY,
                                                    FolioNav.LISTS ->
                                                        LibraryArea(
                                                            lists = shownTab == FolioNav.LISTS,
                                                            library = library,
                                                            shelves = shelves,
                                                            selection = selection,
                                                            busy = op.busy,
                                                            onSelection = { selection = it },
                                                            onOpen = { entry ->
                                                                openEntry(entry.id)
                                                            },
                                                            onContext = { actionsId = it.id },
                                                            onAdd = { showAddBooks = true },
                                                            onCreate = { name, field, value ->
                                                                model.createShelf(
                                                                    name,
                                                                    field,
                                                                    value,
                                                                )
                                                            },
                                                            onManageShelf = {
                                                                model.managementError.value = null
                                                                editingShelfId = it.id
                                                            },
                                                            onPolicy = devices::shelfPolicy,
                                                            onSendShelf = { shelf ->
                                                                sendOrPair {
                                                                    requestSync("shelf", shelf.id)
                                                                }
                                                            },
                                                            onDiscover = { showDiscovery = true },
                                                            onFind = {
                                                                addBook(AddBookAction.TITLE)
                                                            },
                                                            progress = progressById,
                                                            currentId = currentBook?.book?.id,
                                                            requestedList =
                                                                if (shownTab == FolioNav.LIBRARY)
                                                                    requestedList
                                                                else null,
                                                            onRequestHandled = {
                                                                requestedList = null
                                                            },
                                                        )
                                                    FolioNav.DEVICE ->
                                                        KindleScreen(
                                                            device,
                                                            inventory,
                                                            plan,
                                                            history,
                                                            deviceBusy,
                                                            inventoryCheckedAt = inventoryCheckedAt,
                                                            localBookCount = books.size,
                                                            onLibrary = {
                                                                nav.root(FolioNav.LIBRARY)
                                                            },
                                                            onConfigure = { showConnection = true },
                                                            onPreview = { devices.preview() },
                                                            onSync = { requestSync("preview") },
                                                            onSyncMissing = {
                                                                requestSync("missing")
                                                            },
                                                            onCollections =
                                                                devices::sendCollections,
                                                            syncJob = syncJob,
                                                            onCancelJob = devices::cancelSync,
                                                            onResumeJob = devices::resumeSync,
                                                            bookTitles =
                                                                books.associate {
                                                                    it.id to it.title
                                                                },
                                                            remoteBooks =
                                                                transfers
                                                                    .filter {
                                                                        it.deviceId == device.id
                                                                    }
                                                                    .mapNotNull { t ->
                                                                        books
                                                                            .firstOrNull {
                                                                                it.id == t.bookId
                                                                            }
                                                                            ?.let {
                                                                                t.remotePath to it
                                                                            }
                                                                    }
                                                                    .toMap(),
                                                            onOpenBook = {
                                                                nav.open(Route.Book(it.id))
                                                            },
                                                            syncScope =
                                                                if (shelves.any { it.syncEnabled })
                                                                    stringResource(
                                                                        R.string
                                                                            .shell_sync_scope_lists,
                                                                        shelves
                                                                            .filter {
                                                                                it.syncEnabled
                                                                            }
                                                                            .joinToString {
                                                                                it.name
                                                                            },
                                                                    )
                                                                else
                                                                    stringResource(
                                                                        R.string
                                                                            .shell_sync_scope_all
                                                                    ),
                                                        )
                                                    FolioNav.SETTINGS ->
                                                        SettingsScreen(
                                                            appearance,
                                                            dynamic,
                                                            books.size,
                                                            onTheme = {
                                                                appearance = it
                                                                prefs
                                                                    .edit()
                                                                    .putString("theme", it)
                                                                    .apply()
                                                            },
                                                            onDynamic = {
                                                                dynamic = it
                                                                prefs
                                                                    .edit()
                                                                    .putBoolean("dynamic", it)
                                                                    .apply()
                                                            },
                                                            onConnection = {
                                                                showConnection = true
                                                            },
                                                            onForget = devices::forgetDevice,
                                                            paired = device.host.isNotBlank(),
                                                            onRemoved = { showRemoved = true },
                                                            removedCount =
                                                                removed
                                                                    .map { it.groupId }
                                                                    .distinct()
                                                                    .size,
                                                            recordCount = library.entries.size,
                                                            phoneFolder = phoneFolder,
                                                            onPhoneFolder = {
                                                                phoneFolderPicker.launch(null)
                                                            },
                                                            onRefreshFolder =
                                                                devices::refreshPhoneFolder,
                                                            onForgetFolder =
                                                                devices::forgetPhoneFolder,
                                                            onInstallCompanion = {
                                                                installCompanion = true
                                                            },
                                                            onImports = {
                                                                model.showImportResults.value = true
                                                            },
                                                            hardcoverUsername = hardcoverUsername,
                                                            onHardcover = { showAccount = true },
                                                            busy = deviceBusy,
                                                            onBackup = {
                                                                backupPicker.launch(
                                                                    "Folio-backup-${java.time.LocalDate.now()}.zip"
                                                                )
                                                            },
                                                            onRestore = {
                                                                restorePicker.launch(
                                                                    arrayOf(
                                                                        "application/zip",
                                                                        "application/octet-stream",
                                                                    )
                                                                )
                                                            },
                                                            statistics =
                                                                statistics.firstOrNull {
                                                                    it.deviceId == device.id
                                                                },
                                                            onStatistics =
                                                                devices::refreshStatistics,
                                                            onRefreshReading =
                                                                devices::refreshReading,
                                                        )
                                                }
                                            }
                                        }
                                    }
                            }
                        }
                    }
                }
            }
        }
        if (showNowReading)
            NowReadingSheet(
                currentBook,
                recentReading,
                busy = op.busy || discoveryState.busy,
                onContinue = {
                    showNowReading = false
                    currentBook?.let {
                        dev.sadking.folio.reader.ReaderActivity.open(context, it.book.id)
                    }
                },
                onAction = { action ->
                    showNowReading = false
                    val id = currentBook?.book?.id ?: return@NowReadingSheet
                    when (action) {
                        NowReadingAction.SEND -> actionsSend(id)
                        NowReadingAction.EDIT -> {
                            nav.open(Route.Book(id))
                            editorDirty = false
                            nav.open(Route.Edit(id))
                        }
                        NowReadingAction.PROGRESS -> statusEntryId = id
                        NowReadingAction.HARDCOVER -> {
                            openEntry(id, progress = true)
                            showHardcover = true
                            currentBook?.let { discovery.openReading(it.book) }
                        }
                        NowReadingAction.SHARE -> currentBook?.let { model.openEpub(it.book, true) }
                        NowReadingAction.REMOVE -> removalIds = setOf(id)
                        NowReadingAction.OPEN -> openEntry(id)
                    }
                },
                onPick = { picked ->
                    showNowReading = false
                    dev.sadking.folio.reader.ReaderActivity.open(context, picked.id)
                },
                onLibrary = {
                    showNowReading = false
                    nav.root(FolioNav.LIBRARY)
                },
                onDismiss = { showNowReading = false },
            )
        if (confirmDiscard)
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text(stringResource(R.string.shell_discard_title)) },
                text = {
                    Text(stringResource(R.string.shell_discard_body))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDiscard = false
                            editorDirty = false
                            if (nav.editing) nav.back()
                        }
                    ) {
                        Text(stringResource(R.string.shell_discard))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false }) {
                        Text(stringResource(R.string.shell_keep_editing))
                    }
                },
            )
        if (showAddBooks)
            AddBooksSheet(
                hardcoverUsername,
                onDismiss = { showAddBooks = false },
                onAction = { action ->
                    showAddBooks = false
                    addBook(action)
                },
            )
        if (showTitleLookup)
            HardcoverBookSearchSheet(
                discovery,
                initialQuery = titleLookupQuery,
                onDismiss = {
                    showTitleLookup = false
                    discovery.clearTitleLookup()
                },
                onSaved = { wish ->
                    showTitleLookup = false
                    discovery.clearTitleLookup()
                    if (wish.linkedBookId != null) nav.open(Route.Book(wish.linkedBookId))
                    else openEntry(wish.id)
                },
            )
        val editingShelf = shelves.firstOrNull { it.id == editingShelfId }
        if (editingShelf != null)
            ShelfEditorSheet(
                editingShelf,
                op.busy,
                managementError,
                onSave = { model.saveShelf(it) { editingShelfId = null } },
                onDelete = {
                    model.deleteShelf(editingShelf) {
                        editingShelfId = null
                    }
                },
                onDismiss = { if (!op.busy) editingShelfId = null },
            )
        if (catalogueEditorId == "new" || wishes.any { it.id == catalogueEditorId }) {
            val value = wishes.firstOrNull { it.id == catalogueEditorId }
            val display =
                value?.displayBook()
                    ?: Book(
                        "manual-draft",
                        "",
                        "[]",
                        originalPath = "",
                        currentPath = "",
                        originalHash = "",
                        currentHash = "",
                    )
            CatalogueEditorDialog(
                display,
                op.busy,
                managementError,
                onSave = { metadata, cover ->
                    model.saveCatalogue(value?.id, metadata, cover) { catalogueEditorId = null }
                },
                onDismiss = { catalogueEditorId = null },
            )
        }
        if (showRemoved)
            RemovedBooksSheet(removed, op.busy, model::restoreRemoved, model::purgeRemoved) {
                showRemoved = false
            }
        if (batchSheet == "lists")
            BatchListsSheet(
                selection.size,
                shelves,
                checked =
                    shelves
                        .filter { shelf ->
                            shelf.ruleField == "Manual" &&
                                selection.all { id ->
                                    memberships.any {
                                        it.bookId == id && it.shelfId == shelf.id
                                    } ||
                                        wishMemberships.any {
                                            it.wishId == id && it.shelfId == shelf.id
                                        }
                                }
                        }
                        .map { it.id }
                        .toSet(),
                busy = op.busy,
                onList = { shelf, add -> model.manageBatch(selection, shelf, add) },
                onCreate = {
                    batchSheet = null
                    selection = emptySet()
                    nav.root(FolioNav.LISTS)
                },
                onDismiss = { batchSheet = null },
            )
        if (batchSheet == "status")
            BatchStatusSheet(
                selection.size,
                op.busy,
                onStatus = { value ->
                    model.manageBatch(selection, status = value) {
                        selection = emptySet()
                        batchSheet = null
                    }
                },
                onDismiss = { batchSheet = null },
            )
        if (showImportResults)
            ImportResultsSheet(
                importResults,
                op.busy,
                model::import,
                onChooseAgain = {
                    picker.launch(arrayOf("application/epub+zip", "application/octet-stream"))
                },
                onOpen = { id ->
                    model.showImportResults.value = false
                    nav.open(Route.Book(id))
                },
                onDismiss = { model.showImportResults.value = false },
            )
        val detachBook = books.firstOrNull { it.id == detachBookId }
        if (detachBook != null)
            AlertDialog(
                onDismissRequest = { detachBookId = null },
                title = { Text(stringResource(R.string.shell_detach_title)) },
                text = {
                    Text(stringResource(R.string.shell_detach_body))
                },
                confirmButton = {
                    TextButton(
                        onClick = { model.detach(detachBook) { detachBookId = null } },
                        enabled = !op.busy,
                    ) {
                        Text(stringResource(R.string.shell_detach))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { detachBookId = null }) {
                        Text(stringResource(R.string.shell_keep_attached))
                    }
                },
            )
        val actionBook = catalogueBooks.firstOrNull { it.id == actionsId }
        val noBrowser = stringResource(R.string.shell_no_browser)
        if (actionBook != null)
            BookActionsSheet(
                actionBook,
                books.any { it.id == actionBook.id },
                annotations.any { it.bookId == actionBook.id },
                op.busy || discoveryState.busy,
                attached = wishes.any { it.linkedBookId == actionBook.id },
                onDismiss = { actionsId = null },
                onAction = { action ->
                    actionsId = null
                    when (action) {
                        BookAction.OPEN -> openEntry(actionBook.id)
                        BookAction.SELECT -> {
                            nav.root(FolioNav.LIBRARY)
                            selection = selection + actionBook.id
                        }
                        BookAction.SEND -> actionsSend(actionBook.id)
                        BookAction.PROGRESS -> {
                            openEntry(actionBook.id, progress = true)
                            if (books.any { it.id == actionBook.id }) showHardcover = true
                            discovery.openReading(actionBook)
                        }
                        BookAction.EDIT -> {
                            if (books.any { it.id == actionBook.id }) {
                                nav.open(Route.Book(actionBook.id))
                                editorDirty = false
                                nav.open(Route.Edit(actionBook.id))
                            } else {
                                model.managementError.value = null
                                catalogueEditorId = actionBook.id
                            }
                        }
                        BookAction.LISTS -> listEntryId = actionBook.id
                        BookAction.STATUS -> statusEntryId = actionBook.id
                        BookAction.EXPORT_NOTES -> {
                            exportBookId = actionBook.id
                            markdownPicker.launch("Folio-${actionBook.id}.md")
                        }
                        BookAction.ATTACH -> {
                            attachWishId = actionBook.id
                            attachmentPicker.launch(
                                arrayOf("application/epub+zip", "application/octet-stream")
                            )
                        }
                        BookAction.SEARCH -> {
                            try {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(
                                            BookDiscovery.archiveSearch(
                                                actionBook.title,
                                                decode(actionBook.authors),
                                            )
                                        ),
                                    )
                                )
                            } catch (_: android.content.ActivityNotFoundException) {
                                discovery.report(noBrowser)
                            }
                        }
                        BookAction.REMOVE -> removalIds = setOf(actionBook.id)
                        BookAction.DETACH -> detachBookId = actionBook.id
                        BookAction.EXPORT_EPUB -> {
                            epubExportId = actionBook.id
                            epubExportPicker.launch("${actionBook.id}.epub")
                        }
                        BookAction.SHARE_EPUB -> model.openEpub(actionBook, true)
                        BookAction.EXTERNAL_READER -> model.openEpub(actionBook, false)
                        BookAction.OPEN_READER ->
                            dev.sadking.folio.reader.ReaderActivity.open(context, actionBook.id)
                    }
                },
            )
        if (folderReport.isNotEmpty())
            ModalBottomSheet(onDismissRequest = devices::clearFolderReport) {
                LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp)) {
                    item {
                        Text(
                            stringResource(R.string.shell_folder_review_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            stringResource(R.string.shell_folder_review_body),
                            Modifier.padding(vertical = 12.dp),
                        )
                    }
                    items(folderReport.size) { index ->
                        Text(folderReport[index], Modifier.padding(vertical = 12.dp))
                        HorizontalDivider()
                    }
                    item {
                        TextButton(onClick = devices::clearFolderReport) {
                            Text(stringResource(R.string.shell_done))
                        }
                    }
                }
            }
        val listBook = catalogueBooks.firstOrNull { it.id == listEntryId }
        if (listBook != null)
            ModalBottomSheet(onDismissRequest = { listEntryId = null }) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)
                ) {
                    Text(
                        stringResource(R.string.shell_lists_for_book, listBook.title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    val manual = shelves.filter { it.ruleField == "Manual" }
                    if (manual.isEmpty())
                        TextButton(
                            onClick = {
                                listEntryId = null
                                nav.root(FolioNav.LISTS)
                            }
                        ) {
                            Text(stringResource(R.string.shell_create_list))
                        }
                    manual.forEach { shelf ->
                        val owned = books.any { it.id == listBook.id }
                        val checked =
                            if (owned)
                                memberships.any {
                                    it.bookId == listBook.id && it.shelfId == shelf.id
                                }
                            else
                                wishMemberships.any {
                                    it.wishId == listBook.id && it.shelfId == shelf.id
                                }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked,
                                { selected ->
                                    if (owned) model.membership(listBook.id, shelf.id, selected)
                                    else
                                        wishes
                                            .firstOrNull { it.id == listBook.id }
                                            ?.let { discovery.membership(it, shelf, selected) }
                                },
                                enabled = !op.busy && !discoveryState.busy,
                            )
                            Text(shelf.name)
                        }
                    }
                }
            }
        val statusBook = catalogueBooks.firstOrNull { it.id == statusEntryId }
        if (statusBook != null)
            ModalBottomSheet(onDismissRequest = { statusEntryId = null }) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text(
                        stringResource(R.string.shell_status_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(stringResource(R.string.shell_status_body))
                    phoneStatuses.forEach { value ->
                        TextButton(
                            onClick = {
                                model.status(statusBook, value)
                                statusEntryId = null
                            },
                            enabled = !op.busy,
                        ) {
                            Text(
                                if (value == statusBook.status)
                                    stringResource(R.string.shell_status_current, value)
                                else value
                            )
                        }
                    }
                }
            }
        val attachmentWish = wishes.firstOrNull { it.id == attachWishId }
        val attachmentBook = books.firstOrNull { it.id == attachReviewId }
        if (attachmentWish != null && attachmentBook != null)
            AlertDialog(
                onDismissRequest = {
                    attachWishId = null
                    attachReviewId = null
                },
                title = { Text(stringResource(R.string.shell_attach_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.shell_attach_body,
                            attachmentBook.title,
                            attachmentBook.authorLabel,
                            attachmentWish.title,
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            discovery.attach(attachmentWish, attachmentBook)
                            attachWishId = null
                            attachReviewId = null
                        },
                        enabled = !discoveryState.busy,
                    ) {
                        Text(stringResource(R.string.shell_attach_confirm))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            attachWishId = null
                            attachReviewId = null
                        }
                    ) {
                        Text(stringResource(R.string.shell_cancel))
                    }
                },
            )
        if (removalIds.isNotEmpty()) {
            val targets = catalogueBooks.filter { it.id in removalIds }
            RemoveBooksDialog(
                targets,
                annotations.count { it.bookId in removalIds },
                op.busy || discoveryState.busy,
                if (removalAttempted && foregroundOp.failed) foregroundOp.message else null,
                onDismiss = { if (!op.busy) removalIds = emptySet() },
                onConfirm = {
                    removalAttempted = true
                    val ids = removalIds
                    model.removeLocal(ids) {
                        if (catalogueId in ids) catalogueProgress = false
                        nav.forget(ids)
                        selection = selection - ids
                        removalIds = emptySet()
                    }
                },
            )
        }
        if (showProfileImport)
            HardcoverImportSheet(discovery, books, wishes) { showProfileImport = false }
        if (catalogue != null && catalogueProgress)
            HardcoverReadingSheet(discovery, catalogue.displayBook(), null) {
                catalogueProgress = false
                discovery.clearReading()
            }
        if (showAccount)
            HardcoverAccountDialog(
                discovery,
                onConnected = {
                    if (resumeHardcoverSearch) {
                        resumeHardcoverSearch = false
                        showTitleLookup = true
                    } else if (resumeHardcoverImport) {
                        resumeHardcoverImport = false
                        showProfileImport = true
                        discovery.loadProfile()
                    }
                },
            ) {
                showAccount = false
                resumeHardcoverImport = false
                resumeHardcoverSearch = false
            }
        if (showHardcover && book != null)
            HardcoverReadingSheet(
                discovery,
                book,
                reading.filter { it.bookId == book.id }.maxByOrNull { it.fetchedAt },
                onRefreshDevice = { devices.refreshBookReading(book.id) },
                devicePaired = device.host.isNotBlank(),
                deviceOperation = foregroundOp,
                phoneReading =
                    phoneReading.firstOrNull {
                        it.bookId == book.id && it.revisionHash == book.currentHash
                    },
            ) {
                showHardcover = false
                discovery.clearReading()
            }
        if (showDiscovery)
            DiscoveryScreen(
                discovery,
                onDismiss = { showDiscovery = false },
                onHardcoverLookup = { text ->
                    showDiscovery = false
                    discovery.clearTitleLookup()
                    titleLookupQuery = text
                    showTitleLookup = true
                },
                onSaved = {
                    showDiscovery = false
                    nav.root(FolioNav.LIBRARY)
                    requestedList = ReadingList.WANT_TO_READ.name
                },
            )
        if (showLookup && book != null)
            MetadataLookupSheet(
                book,
                lookupResults,
                lookupProposal,
                op.busy,
                model::searchMetadata,
                model::selectMetadata,
                onApply = { metadata, cover ->
                    model.applyMetadata(book, metadata, cover) { showLookup = false }
                },
                onDismiss = {
                    showLookup = false
                    model.clearLookup()
                },
                coverPreview = lookupCover,
            )
        if (restoreUri != null)
            AlertDialog(
                onDismissRequest = { restoreUri = null },
                title = { Text(stringResource(R.string.shell_restore_title)) },
                text = {
                    Text(stringResource(R.string.shell_restore_body))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            restoreUri?.let { model.restoreBackup(android.net.Uri.parse(it)) }
                            restoreUri = null
                        },
                        enabled = !op.busy,
                    ) {
                        Text(stringResource(R.string.shell_restore))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { restoreUri = null }) {
                        Text(stringResource(R.string.shell_cancel))
                    }
                },
            )
        if (showConnection)
            ModalBottomSheet(
                onDismissRequest = {
                    showConnection = false
                    devices.clearHostProbe()
                    devices.clearFolders()
                    pairingSendId = null
                }
            ) {
                Box {
                    ConnectionForm(
                        device,
                        deviceBusy,
                        save = { config, password, key ->
                            devices.saveDevice(config, password, key) {
                                showConnection = false
                                devices.clearHostProbe()
                                devices.clearFolders()
                                pairingSendId?.let { id ->
                                    pairingSendId = null
                                    requestSync("book", id)
                                }
                            }
                        },
                        observed = observedHost,
                        probeStatus = probeStatus,
                        onProbe = devices::probeHost,
                        onClearProbe = devices::clearHostProbe,
                        folderListing = folderListing,
                        folderStatus = folderStatus,
                        onBrowse = devices::browseFolders,
                        onClearFolders = devices::clearFolders,
                    )
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                }
            }
    }
}
