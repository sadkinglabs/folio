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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.unit.DpSize
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.sadking.folio.*
import dev.sadking.folio.R
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
import dev.sadking.folio.device.*
import dev.sadking.folio.reader.ExportActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Composable
fun FolioApp(model: LibraryViewModel) {
    // The space Librillo is given decides phone, rail or two panes: the window on a phone or
    // tablet, one side in split screen, and the forced size in tests (the configuration's screen
    // size does not follow any of those).
    BoxWithConstraints(Modifier.fillMaxSize()) { FolioAppBody(model, DpSize(maxWidth, maxHeight)) }
}

@Composable
private fun FolioAppBody(model: LibraryViewModel, space: DpSize) {
    val discovery: DiscoveryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val devices: DeviceViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var showProfileImport by rememberSaveable { mutableStateOf(false) }
    val nav = rememberFolioNav()
    val catalogueId = nav.recordId
    var catalogueProgress by rememberSaveable { mutableStateOf(false) }
    var catalogueEditorId by rememberSaveable { mutableStateOf<String?>(null) }
    // The list editor: "new" for a list being created, else the shelf id being edited.
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
    // The status and navigation bar icons follow Librillo's own theme, not the phone's, so they
    // stay readable when the two differ (a light Librillo on a dark phone, or the other way).
    val window = remember(context) { context.findActivity()?.window }
    if (window != null)
        SideEffect {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    // The window's size class and a fold's hinge decide between the bar, the rail and two panes.
    val frame = rememberFolioWindow(space)
    FolioTheme(dark, dynamic) {
        val books by model.books.collectAsStateWithLifecycle()
        val phoneReading by model.phoneReading.collectAsStateWithLifecycle()
        val phoneFolder by devices.phoneFolder.collectAsStateWithLifecycle()
        val removed by model.removed.collectAsStateWithLifecycle()
        val importResults by model.importResults.collectAsStateWithLifecycle()
        val showImportResults by model.showImportResults.collectAsStateWithLifecycle()
        val managementError by model.managementError.collectAsStateWithLifecycle()
        val shelves by model.shelves.collectAsStateWithLifecycle()
        val memberships by model.memberships.collectAsStateWithLifecycle()
        val foregroundOp by model.operation.collectAsStateWithLifecycle()
        val folderRefreshing by model.folderRefreshing.collectAsStateWithLifecycle()
        val discoveryState by discovery.state.collectAsStateWithLifecycle()
        // A running operation disables only controls that would start another one.
        val op = foregroundOp
        val snackbar = rememberOperationSnackbar(model.notices)
        // Messages show in the sheet on top while one is open, not under it (FolioSheet).
        val appMessages = remember(snackbar) { AppMessages(snackbar) }
        val deviceDirectory by devices.directory.collectAsStateWithLifecycle()
        CompositionLocalProvider(
            LocalAppMessages provides appMessages,
            LocalDevices provides deviceDirectory,
        ) {
            val stateHolder = rememberSaveableStateHolder()
            val hardcoverUsername by discovery.username.collectAsStateWithLifecycle()
            val wishes by discovery.wishes.collectAsStateWithLifecycle()
            val wishMemberships by discovery.wishMemberships.collectAsStateWithLifecycle()
            val observations by discovery.observations.collectAsStateWithLifecycle()
            val accountId by discovery.accountId.collectAsStateWithLifecycle()
            val catalogue = wishes.firstOrNull { it.id == catalogueId }
            LaunchedEffect(
                discovery,
                showHardcover,
                showProfileImport,
                showTitleLookup,
                showAccount,
                catalogueId,
            ) {
                // The scan screen is a route under this scaffold, so its notices show here too.
                if (
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
                                if (notice.failed) SnackbarDuration.Long
                                else SnackbarDuration.Short,
                        )
                    }
            }

            val ereaders by devices.ereaders.collectAsStateWithLifecycle()
            val ereaderCards by devices.ereaderCards.collectAsStateWithLifecycle()
            val syncRequestedAt by devices.syncRequestedAt.collectAsStateWithLifecycle()
            val hardcoverStatus by devices.hardcoverStatus.collectAsStateWithLifecycle()
            val hardcoverChecks by devices.hardcoverChecks.collectAsStateWithLifecycle()
            val hardcoverQuestions by devices.hardcoverQuestions.collectAsStateWithLifecycle()
            val hardcoverItemStates by devices.hardcoverItemStates.collectAsStateWithLifecycle()
            val pluginInstalledAt by devices.pluginInstalledAt.collectAsStateWithLifecycle()
            val libraryFolder by devices.libraryFolder.collectAsStateWithLifecycle()
            val setupProgress by devices.setup.collectAsStateWithLifecycle()
            // The sync line's clock: "2 min ago" moves on while the screen is open.
            val now by
                produceState(System.currentTimeMillis()) {
                    while (true) {
                        kotlinx.coroutines.delay(30_000)
                        value = System.currentTimeMillis()
                    }
                }
            val hardcoverWaiting = hardcoverChecks.size + hardcoverQuestions.size
            val syncLineState =
                remember(ereaders, syncRequestedAt, pluginInstalledAt, hardcoverWaiting, now) {
                    syncLine(
                        ereaders,
                        syncRequestedAt,
                        pluginInstalledAt > 0,
                        hardcoverWaiting,
                        now,
                    )
                }
            var showDecisions by rememberSaveable { mutableStateOf(false) }
            val matchOffer by devices.matchOffer.collectAsStateWithLifecycle()
            var showSetup by rememberSaveable { mutableStateOf(false) }
            val device by devices.device.collectAsStateWithLifecycle()
            val observedHost by devices.observedHost.collectAsStateWithLifecycle()
            val probeStatus by devices.probeStatus.collectAsStateWithLifecycle()
            val folderStatus by devices.folderStatus.collectAsStateWithLifecycle()
            val folderListing by devices.folderListing.collectAsStateWithLifecycle()
            val transfers by model.transfers.collectAsStateWithLifecycle()
            val baselines by model.baselines.collectAsStateWithLifecycle()
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
                    baselines,
                    reading,
                    observations,
                    accountId,
                    device.id,
                ) {
                    presentLibrary(
                        books,
                        wishes,
                        shelves,
                        memberships,
                        wishMemberships,
                        transfers,
                        reading,
                        observations,
                        accountId,
                        device.id,
                        baselines,
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
                                            ?: wishes
                                                .firstOrNull { it.linkedBookId == b.id }
                                                ?.coverPath
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
            // Librillo closed unexpectedly last time: offer the report once.
            val crashNotice = stringResource(R.string.crash_notice)
            val crashShare = stringResource(R.string.crash_share)
            LaunchedEffect(Unit) {
                CrashReports.unseen.first { it }
                val result =
                    snackbar.showSnackbar(
                        crashNotice,
                        actionLabel = crashShare,
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                CrashReports.markSeen(context)
                if (result == SnackbarResult.ActionPerformed) {
                    val report =
                        withContext(Dispatchers.IO) {
                            runCatching { CrashReports.report(context) }.getOrNull()
                        }
                    if (report != null) shareCrashReport(context, report)
                }
            }
            // A pick or an answer Hardcover sync refused says why.
            LaunchedEffect(devices) {
                devices.hardcoverNotices.collect { message ->
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(
                        message,
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                }
            }
            val statistics by model.statistics.collectAsStateWithLifecycle()
            val annotations by model.annotations.collectAsStateWithLifecycle()
            val deletedAnnotations by model.deletedAnnotations.collectAsStateWithLifecycle()
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
            val notesFolder by model.notesFolder.collectAsState()
            LifecycleResumeEffect(Unit) {
                model.refreshNotesFolder()
                onPauseOrDispose {}
            }
            val notesFolderPicker =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree
                    ->
                    val book = books.firstOrNull { it.id == exportBookId }
                    if (tree != null && book != null) model.chooseNotesFolder(tree, book)
                }
            val phoneFolderPicker =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri
                    ->
                    if (uri != null) devices.choosePhoneFolder(uri)
                }
            val notificationPrefs = remember { context.getSharedPreferences("sync-ui", 0) }
            val haptics = rememberHaptics()
            // The phone shows a notification while it listens for the e-reader; ask once, at the
            // first Sync.
            val notificationPermission =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            fun syncNow() {
                haptics.confirm()
                if (
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                        !notificationPrefs.getBoolean("requested", false)
                ) {
                    notificationPrefs.edit().putBoolean("requested", true).apply()
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                devices.syncNow()
            }
            fun openSetup() {
                devices.resetSetup()
                showSetup = true
            }
            val tab = nav.tab
            val density = androidx.compose.ui.platform.LocalDensity.current
            val reducedMotion = FolioMotion.reduced
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
            // A list's "Add from Library": the Library picks books for it until added or cancelled.
            var pickingListId by rememberSaveable { mutableStateOf<String?>(null) }
            val pickingShelf = pickingListId?.let { id -> shelves.firstOrNull { it.id == id } }
            // A list deleted while picking for it ends the picking; an empty `shelves` is still
            // loading.
            LaunchedEffect(pickingListId, pickingShelf == null, shelves.isEmpty()) {
                if (pickingListId != null && pickingShelf == null && shelves.isNotEmpty())
                    pickingListId = null
            }
            var deletingShelfId by rememberSaveable { mutableStateOf<String?>(null) }
            val cards = remember(shelves, library) { listCards(shelves, library) }
            val listId = nav.listId
            val openList = listId?.let { id -> cards.firstOrNull { it.id == id } }
            // A list deleted or restored away from under its screen closes that screen.
            LaunchedEffect(listId, openList == null) {
                if (listId != null && openList == null) nav.forget(setOf(listId))
            }
            var showConnection by rememberSaveable { mutableStateOf(false) }
            val picker =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
                    model.import(it)
                }
            val epubExportPicker =
                rememberLauncherForActivityResult(SaveBookCopy) { uri ->
                    if (uri != null)
                        books
                            .firstOrNull { it.id == epubExportId }
                            ?.let { model.exportEpub(it, uri) }
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
                                b.coverPath
                                    ?: wishes.firstOrNull { it.linkedBookId == b.id }?.coverPath
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
            // Back pops a book screen, then clears a selection, then stops picking for a list; a
            // root
            // tab leaves the app.
            BackHandler(nav.stack.isNotEmpty() || selection.isNotEmpty() || pickingListId != null) {
                if (editing && op.busy) return@BackHandler
                if (nav.stack.isNotEmpty()) leaveBook()
                else if (selection.isNotEmpty()) selection = emptySet() else pickingListId = null
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
                    AddBookAction.EPUB -> picker.launch(BOOK_FILE_TYPES)
                    AddBookAction.MANUAL -> {
                        model.managementError.value = null
                        catalogueEditorId = "new"
                    }
                    AddBookAction.DISCOVER -> nav.open(Route.Discover)
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
            val noBrowser = stringResource(R.string.shell_no_browser)
            val layout = frame.layout
            val compact = layout == FolioLayout.BAR
            val detailOpen = book != null || catalogue != null
            // The Library and Lists show a list beside the chosen item; Device and Settings lay
            // themselves out. The scan screen always fills the window.
            val twoPane =
                frame.twoPane &&
                    (tab == FolioNav.LIBRARY || tab == FolioNav.LISTS) &&
                    !nav.discovering
            // A list stays on the left while one of its books is open on the right.
            val listBeside =
                if (twoPane && detailOpen)
                    nav.listBeside?.let { id -> cards.firstOrNull { it.id == id } }
                else null
            // From a list pane beside a detail, a book takes the detail's place instead of piling
            // up.
            fun openFromList(id: String, base: Route?) {
                if (twoPane) {
                    if (editing && op.busy) return
                    if (editing && editorDirty) {
                        confirmDiscard = true
                        return
                    }
                    nav.clearAbove(base)
                }
                openEntry(id)
            }
            // The book page's top bar: across the window on a phone, inside its pane on a tablet.
            val bookBar: @Composable (dev.chrisbanes.haze.HazeState?) -> Unit = { haze ->
                if (editing && book != null)
                    TopAppBar(
                        title = { Text(stringResource(R.string.shell_edit_book)) },
                        navigationIcon = {
                            IconButton(onClick = { leaveBook() }, enabled = !op.busy) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    stringResource(R.string.shell_back),
                                )
                            }
                        },
                    )
                else if (book != null || catalogue != null)
                    BookTopBar(
                        haze,
                        onBack = { leaveBook() },
                        title = (book ?: catalogue?.displayBook())?.title.orEmpty(),
                        overflow =
                            if (book == null) emptyList()
                            else
                                bookOverflow(
                                        hasEpub = true,
                                        attached = wishes.any { it.linkedBookId == book.id },
                                        hasNotes = annotations.any { it.bookId == book.id },
                                    )
                                    .map { item ->
                                        when (item) {
                                            BookOverflow.EXTERNAL_READER ->
                                                OverflowItem(
                                                    Icons.Outlined.OpenInNew,
                                                    stringResource(R.string.shell_action_external),
                                                    enabled = !op.busy,
                                                ) {
                                                    model.openEpub(book, false)
                                                }
                                            BookOverflow.SAVE_COPY ->
                                                OverflowItem(
                                                    Icons.Outlined.FileDownload,
                                                    stringResource(
                                                        R.string.shell_action_export_epub
                                                    ),
                                                    enabled = !op.busy,
                                                ) {
                                                    epubExportId = book.id
                                                    epubExportPicker.launch(
                                                        "${book.id}.${book.format}"
                                                    )
                                                }
                                            BookOverflow.DETACH ->
                                                OverflowItem(
                                                    Icons.Outlined.LinkOff,
                                                    stringResource(R.string.shell_action_detach),
                                                    enabled = !op.busy,
                                                ) {
                                                    detachBookId = book.id
                                                }
                                            BookOverflow.EXPORT_NOTES ->
                                                OverflowItem(
                                                    Icons.Outlined.EditNote,
                                                    stringResource(
                                                        R.string.shell_action_export_notes
                                                    ),
                                                    enabled = !op.busy,
                                                ) {
                                                    exportBookId = book.id
                                                    markdownPicker.launch(notesFileName(book))
                                                }
                                        }
                                    },
                    )
            }
            // The selection's actions, in the tab bar's place on a phone and under the panes on a
            // tablet.
            val selectionBar: @Composable () -> Unit = {
                if (pickingShelf != null)
                    AddToListBar(
                        count = selection.size,
                        listName = pickingShelf.name,
                        enabled = !op.busy,
                        onAdd = {
                            val shelf = pickingShelf
                            model.manageBatch(selection, shelf.id, add = true) {
                                selection = emptySet()
                                pickingListId = null
                                nav.root(FolioNav.LISTS)
                                nav.open(Route.List(shelf.id))
                            }
                        },
                        onCancel = {
                            selection = emptySet()
                            pickingListId = null
                        },
                        hazeState = hazeState,
                    )
                else
                    SelectionActionBar(
                        enabled = !op.busy,
                        onLists = { batchSheet = "lists" },
                        onStatus = { batchSheet = "status" },
                        onRemove = { removalIds = selection },
                    )
            }
            fun chooseTab(i: Int) {
                nav.root(i)
                selection = emptySet()
                pickingListId = null
            }
            fun continueReading() {
                currentBook?.let {
                    dev.sadking.folio.reader.ReaderActivity.open(context, it.book.id)
                }
            }
            // The book, record or editor; on a phone it scrolls under the window's top bar.
            val bookContent: @Composable (PaddingValues) -> Unit = { pagePadding ->
                if (book != null) {
                    if (editing)
                        Box(
                            Modifier.padding(
                                bottom = if (compact) 0.dp else pagePadding.calculateBottomPadding()
                            )
                        ) {
                            MetadataEditor(book, op.busy, onDirtyChange = { editorDirty = it }) {
                                metadata,
                                cover ->
                                model.edit(book, metadata, cover) {
                                    editorDirty = false
                                    if (nav.editing) nav.back()
                                }
                            }
                        }
                    else
                        BookDetails(
                            book = book,
                            hasEpub = true,
                            lists =
                                shelves
                                    .filter { shelf ->
                                        memberships.any {
                                            it.bookId == book.id && it.shelfId == shelf.id
                                        }
                                    }
                                    .map { it.name },
                            signals = signals[book.id],
                            phoneReading =
                                phoneReading.firstOrNull {
                                    it.bookId == book.id && it.revisionHash == book.currentHash
                                },
                            reading =
                                reading
                                    .filter { it.bookId == book.id && it.deviceId == device.id }
                                    .maxByOrNull { it.fetchedAt },
                            // Every old SSH position, whichever saved connection read it.
                            snapshots = allReading.filter { it.bookId == book.id },
                            hardcoverLinked =
                                wishes.any { it.linkedBookId == book.id && it.hardcoverId != null },
                            hardcoverState = hardcoverItemStates[book.id],
                            onCheckMatch = { showDecisions = true },
                            hardcoverPage =
                                wishes
                                    .firstOrNull {
                                        it.linkedBookId == book.id && it.hardcoverId != null
                                    }
                                    ?.let { wish ->
                                        observations
                                            .firstOrNull {
                                                it.accountId == accountId &&
                                                    it.hardcoverId == wish.hardcoverId
                                            }
                                            ?.pages
                                    },
                            annotations = annotations.filter { it.bookId == book.id },
                            deletedAnnotations =
                                deletedAnnotations.filter {
                                    it.bookId == book.id && it.text.isNotBlank()
                                },
                            onRestoreAnnotation = model::restoreAnnotation,
                            nextInSeries = nextBook,
                            busy = op.busy,
                            contentPadding = pagePadding,
                            onRead = {
                                dev.sadking.folio.reader.ReaderActivity.open(context, book.id)
                            },
                            onReadFrom = { start ->
                                dev.sadking.folio.reader.ReaderActivity.open(
                                    context,
                                    book.id,
                                    start,
                                )
                            },
                            onEdit = {
                                editorDirty = false
                                nav.open(Route.Edit(book.id))
                            },
                            onHardcover = {
                                showHardcover = true
                                discovery.openReading(book)
                            },
                            onShare = { model.openEpub(book, true) },
                            onRemove = { removalIds = setOf(book.id) },
                            onLists = { listEntryId = book.id },
                            onStatus = { model.status(book, it) },
                            onLookup = {
                                model.clearLookup()
                                showLookup = true
                            },
                            notesExport =
                                ExportActions(
                                    folder = notesFolder,
                                    onShare = { model.shareNotes(book) },
                                    onSave = {
                                        if (notesFolder == null) {
                                            exportBookId = book.id
                                            notesFolderPicker.launch(null)
                                        } else model.saveNotes(book)
                                    },
                                    onChooseFolder = {
                                        exportBookId = book.id
                                        notesFolderPicker.launch(model.notesExport.folder())
                                    },
                                ),
                            onNext = { nav.open(Route.Book(it.id)) },
                        )
                } else if (catalogue != null) {
                    CatalogueBookDetails(
                        discovery,
                        catalogue,
                        books,
                        lists =
                            shelves
                                .filter { shelf ->
                                    wishMemberships.any {
                                        it.wishId == catalogue.id && it.shelfId == shelf.id
                                    }
                                }
                                .map { it.name },
                        contentPadding = pagePadding,
                        onEdit = { catalogueEditorId = catalogue.id },
                        onHardcover = {
                            catalogueProgress = true
                            discovery.openReading(catalogue.displayBook())
                        },
                        onRemove = { removalIds = setOf(catalogue.id) },
                        onLists = { listEntryId = catalogue.id },
                        onStatus = { model.status(catalogue.displayBook(), it) },
                        hardcoverState = hardcoverItemStates[catalogue.id],
                        hardcoverSync = hardcoverStatus.connected && hardcoverStatus.enabled,
                        onCheckMatch = { showDecisions = true },
                    )
                }
            }
            // One list's books. [onOpenBook] differs by pane: on the right a book opens above the
            // list; on the left it takes the right pane's place.
            val listPage: @Composable (ListCard, () -> Unit, (String) -> Unit) -> Unit =
                { card, onBack, onOpenBook ->
                    ListDetailScreen(
                        card,
                        selection,
                        busy = op.busy,
                        onBack = onBack,
                        onOpen = { onOpenBook(it.id) },
                        onSelect = { id ->
                            selection = if (id in selection) selection - id else selection + id
                        },
                        onClearSelection = { selection = emptySet() },
                        onAddFromLibrary = {
                            selection = emptySet()
                            pickingListId = card.id
                            nav.root(FolioNav.LIBRARY)
                        },
                        onEdit = {
                            model.managementError.value = null
                            editingShelfId = it.id
                        },
                        signals = library.signals,
                        fileIds = library.fileIds,
                        progress = progressById,
                        currentId = currentBook?.book?.id,
                        onContext = { actionsId = it.id },
                    )
                }
            // The four tabs, each keeping its own saved state while another is shown.
            val tabs: @Composable (Dp) -> Unit = { bottom ->
                AnimatedContent(
                    tab,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        val offset = if (reducedMotion) 0 else with(density) { 8.dp.roundToPx() }
                        (fadeIn(FolioMotion.standardSpec()) +
                                slideInVertically(FolioMotion.standardSpec()) { offset })
                            .togetherWith(
                                fadeOut(FolioMotion.standardSpec()) +
                                    slideOutVertically(FolioMotion.standardSpec()) { -offset }
                            )
                    },
                    label = "Tab",
                ) { shownTab ->
                    stateHolder.SaveableStateProvider("tab-$shownTab") {
                        // Library and Lists scroll under the bar; Device and Settings keep the
                        // bar's
                        // height as padding until they move over.
                        Box(
                            Modifier.fillMaxSize()
                                .then(
                                    if (
                                        shownTab == FolioNav.DEVICE || shownTab == FolioNav.SETTINGS
                                    )
                                        Modifier.padding(bottom = bottom)
                                    else Modifier
                                )
                        ) {
                            when (shownTab) {
                                FolioNav.LIBRARY ->
                                    LibraryScreen(
                                        library.entries,
                                        selection,
                                        onOpen = { openFromList(it.id, null) },
                                        onSelect = { id ->
                                            selection =
                                                if (id in selection) selection - id
                                                else selection + id
                                        },
                                        onImport = { showAddBooks = true },
                                        busy = op.busy,
                                        signals = library.signals,
                                        fileIds = library.fileIds,
                                        progress = progressById,
                                        currentId = currentBook?.book?.id,
                                        onContext = { actionsId = it.id },
                                        onClearSelection = { selection = emptySet() },
                                        onFind = { addBook(AddBookAction.TITLE) },
                                        onSearchHardcover = { words ->
                                            addBook(AddBookAction.TITLE)
                                            titleLookupQuery = words
                                        },
                                        onScan = { nav.open(Route.Discover) },
                                        requestedTab =
                                            if (requestedList == ReadingList.WANT_TO_READ.name)
                                                LibraryTab.WANT_TO_READ
                                            else null,
                                        onRequestHandled = { requestedList = null },
                                        pickingFor = pickingShelf?.name,
                                        onCancelPicking = {
                                            selection = emptySet()
                                            pickingListId = null
                                        },
                                        onRefresh = { model.refreshFolder(force = true) },
                                        refreshing = folderRefreshing,
                                    )
                                FolioNav.LISTS ->
                                    ListsScreen(
                                        cards,
                                        busy = op.busy,
                                        onOpen = {
                                            // Beside the lists, a list replaces the one shown.
                                            if (twoPane) nav.clearAbove(null)
                                            nav.open(Route.List(it))
                                        },
                                        onNew = {
                                            model.managementError.value = null
                                            editingShelfId = "new"
                                        },
                                        onEdit = {
                                            model.managementError.value = null
                                            editingShelfId = it.id
                                        },
                                        onDelete = { deletingShelfId = it.id },
                                        onScan = { nav.open(Route.Discover) },
                                    )
                                FolioNav.DEVICE ->
                                    DeviceTab(
                                        link =
                                            (context.applicationContext as FolioApplication).link,
                                        ereaders = ereaderCards,
                                        devices = deviceDirectory,
                                        onRename = devices::renameDevice,
                                        onMerge = devices::mergeDevice,
                                        onSeparate = devices::separateDevice,
                                        onForget = devices::forgetEreader,
                                        line = syncLineState,
                                        requestedAt = syncRequestedAt,
                                        now = now,
                                        onSync = ::syncNow,
                                        onSetUp = ::openSetup,
                                        onDecisions = { showDecisions = true },
                                    )
                                FolioNav.SETTINGS ->
                                    SettingsTab(
                                        appearance = appearance,
                                        dynamic = dynamic,
                                        onTheme = {
                                            appearance = it
                                            prefs.edit().putString("theme", it).apply()
                                        },
                                        onDynamic = {
                                            dynamic = it
                                            prefs.edit().putBoolean("dynamic", it).apply()
                                        },
                                        statistics = statistics,
                                        hardcoverUsername = hardcoverUsername,
                                        onHardcover = { showAccount = true },
                                        onImportHardcover = {
                                            if (hardcoverUsername.isBlank()) {
                                                resumeHardcoverImport = true
                                                showAccount = true
                                            } else {
                                                showProfileImport = true
                                                discovery.loadProfile()
                                            }
                                        },
                                        hardcoverStatus = hardcoverStatus,
                                        onHardcoverSync = devices::setHardcoverSync,
                                        onHardcoverSyncNow = devices::syncHardcoverNow,
                                        onDecisions = { showDecisions = true },
                                        now = now,
                                        count = books.size,
                                        recordCount = library.entries.size,
                                        removedCount = removed.map { it.groupId }.distinct().size,
                                        onRemoved = { showRemoved = true },
                                        onBackup = {
                                            backupPicker.launch(
                                                "Librillo-backup-${java.time.LocalDate.now()}.zip"
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
                                        onImports = { model.showImportResults.value = true },
                                        sshHost = device.host,
                                        onConnection = { showConnection = true },
                                        phoneFolder = phoneFolder,
                                        onPhoneFolder = { phoneFolderPicker.launch(null) },
                                        onRefreshFolder = devices::refreshPhoneFolder,
                                        onForgetFolder = devices::forgetPhoneFolder,
                                        busy = op.busy,
                                        onMoveBooks = model::moveToBooksFolder,
                                        onBookSources = {
                                            try {
                                                context.startActivity(
                                                    dev.sadking.folio.acquire.BrowserLaunch.sources(
                                                        context
                                                    )
                                                )
                                            } catch (_: android.content.ActivityNotFoundException) {
                                                discovery.report(noBrowser)
                                            }
                                        },
                                    )
                            }
                        }
                    }
                }
            }
            CompositionLocalProvider(LocalFolioWindow provides frame) {
                SharedTransitionLayout {
                    CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                        Scaffold(
                            // While a sheet is open, its own top shows the message instead.
                            snackbarHost = { if (!appMessages.inSheet) SnackbarHost(snackbar) },
                            containerColor = MaterialTheme.colorScheme.surface,
                            // On a tablet the book page's bar sits in its pane.
                            topBar = { if (compact) bookBar(hazeState) },
                            bottomBar = {
                                // The bar swaps to the selection's actions with a vertical slide,
                                // at
                                // the
                                // same height, so the content under it does not move. The scan
                                // screen
                                // has no bar: it is a full-screen step from the Add sheet. On a
                                // tablet
                                // the rail holds the tabs and only the selection's actions show
                                // here.
                                if (!compact) {
                                    if (selection.isNotEmpty() && !nav.discovering) selectionBar()
                                } else if (book == null && catalogue == null && !nav.discovering)
                                    AnimatedContent(
                                        selection.isNotEmpty(),
                                        transitionSpec = {
                                            if (reducedMotion)
                                                fadeIn(FolioMotion.standardSpec())
                                                    .togetherWith(
                                                        fadeOut(FolioMotion.standardSpec())
                                                    )
                                            else
                                                (fadeIn(FolioMotion.standardSpec()) +
                                                        slideInVertically(
                                                            FolioMotion.standardSpec()
                                                        ) {
                                                            it / 2
                                                        })
                                                    .togetherWith(
                                                        fadeOut(FolioMotion.standardSpec()) +
                                                            slideOutVertically(
                                                                FolioMotion.standardSpec()
                                                            ) {
                                                                it / 2
                                                            }
                                                    )
                                        },
                                        label = "Bar",
                                    ) { selecting ->
                                        if (selecting) selectionBar()
                                        else
                                            FolioBar(
                                                tab = tab,
                                                onTab = ::chooseTab,
                                                current = currentBook,
                                                onContinue = ::continueReading,
                                                onOptions = { showNowReading = true },
                                                deviceBadge = syncLineState.decisions > 0,
                                                hazeState = hazeState,
                                            )
                                    }
                            },
                        ) { padding ->
                            // The whole content is the glass bar's backdrop. Tabs scroll under the
                            // bar
                            // and pad their own content by the bar's height; a book screen, which
                            // has
                            // no
                            // bar, keeps the window inset as ordinary padding.
                            val bottom = padding.calculateBottomPadding()
                            val top = padding.calculateTopPadding()
                            // A book page scrolls under its glass top bar and pads its content
                            // instead.
                            val bookPage = detailOpen && !editing
                            val banners = foregroundOp.busy
                            val direction = LocalLayoutDirection.current
                            // On a tablet the frame keeps clear of side system bars and pads the
                            // top
                            // itself, so bars inside the panes do not pad for the status bar again.
                            val sides =
                                PaddingValues(
                                    start = padding.calculateStartPadding(direction),
                                    end = padding.calculateEndPadding(direction),
                                )
                            Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                                CompositionLocalProvider(
                                    LocalContentBottomPadding provides bottom
                                ) {
                                    Row(
                                        Modifier.fillMaxSize()
                                            .then(
                                                if (compact) Modifier
                                                else
                                                    Modifier.padding(sides)
                                                        .consumeWindowInsets(sides)
                                            )
                                    ) {
                                        if (!compact && !nav.discovering)
                                            FolioRail(
                                                tab = tab,
                                                onTab = ::chooseTab,
                                                current = currentBook,
                                                onContinue = ::continueReading,
                                                onOptions = { showNowReading = true },
                                                deviceBadge = syncLineState.decisions > 0,
                                            )
                                        Column(
                                            Modifier.weight(1f)
                                                .fillMaxHeight()
                                                .then(
                                                    if (compact)
                                                        Modifier.padding(
                                                            top = if (bookPage) 0.dp else top
                                                        )
                                                    else
                                                        Modifier.padding(top = top)
                                                            .consumeWindowInsets(
                                                                WindowInsets.statusBars
                                                            )
                                                )
                                                .then(
                                                    if (editing && compact)
                                                        Modifier.padding(bottom = bottom)
                                                    else Modifier
                                                )
                                        ) {
                                            if (banners)
                                                Column(
                                                    Modifier.padding(
                                                        top = if (compact && bookPage) top else 0.dp
                                                    )
                                                ) {
                                                    StatusSurface(foregroundOp)
                                                }
                                            val pagePadding =
                                                if (compact)
                                                    PaddingValues(
                                                        top = if (banners) 0.dp else top,
                                                        bottom = bottom,
                                                    )
                                                else PaddingValues(bottom = bottom)
                                            val openListPage = openList
                                            Panes(
                                                twoPane = twoPane,
                                                maxWidth =
                                                    when {
                                                        compact || nav.discovering -> null
                                                        layout == FolioLayout.RAIL ->
                                                            PANE_MAX_WIDTH_DP.dp
                                                        // Settings shows its groups beside the
                                                        // chosen
                                                        // one.
                                                        tab == FolioNav.SETTINGS &&
                                                            !detailOpen &&
                                                            openListPage == null -> null
                                                        else -> WIDE_PANE_MAX_WIDTH_DP.dp
                                                    },
                                                primary = {
                                                    if (listBeside != null)
                                                        listPage(
                                                            listBeside,
                                                            { nav.forget(setOf(listBeside.id)) },
                                                            {
                                                                openFromList(
                                                                    it,
                                                                    Route.List(listBeside.id),
                                                                )
                                                            },
                                                        )
                                                    else tabs(bottom)
                                                },
                                                detail =
                                                    when {
                                                        detailOpen -> {
                                                            {
                                                                DetailPane(
                                                                    if (compact) null
                                                                    else ({ bookBar(null) })
                                                                ) {
                                                                    bookContent(pagePadding)
                                                                }
                                                            }
                                                        }
                                                        nav.discovering -> {
                                                            {
                                                                DiscoveryScreen(
                                                                    discovery,
                                                                    onBack = { nav.back() },
                                                                    onHardcoverLookup = { text ->
                                                                        nav.back()
                                                                        discovery.clearTitleLookup()
                                                                        titleLookupQuery = text
                                                                        showTitleLookup = true
                                                                    },
                                                                    onSaved = {
                                                                        nav.root(FolioNav.LIBRARY)
                                                                        requestedList =
                                                                            ReadingList.WANT_TO_READ
                                                                                .name
                                                                    },
                                                                    contentPadding = pagePadding,
                                                                )
                                                            }
                                                        }
                                                        openListPage != null -> {
                                                            {
                                                                listPage(
                                                                    openListPage,
                                                                    { nav.back() },
                                                                    { openEntry(it) },
                                                                )
                                                            }
                                                        }
                                                        else -> null
                                                    },
                                                empty = {
                                                    EmptyPane(
                                                        stringResource(
                                                            if (tab == FolioNav.LISTS)
                                                                R.string.ad6_choose_list
                                                            else R.string.ad6_choose_book
                                                        )
                                                    )
                                                },
                                                modifier = Modifier.weight(1f),
                                            )
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
                            NowReadingAction.SHARE ->
                                currentBook?.let { model.openEpub(it.book, true) }
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
            if (editingShelf != null || editingShelfId == "new")
                ListEditorSheet(
                    editingShelf,
                    takenNames = shelves.map { it.name },
                    busy = op.busy,
                    error = managementError,
                    onCreate = { name, field, value ->
                        // Lists reach the e-reader as KOReader collections by sync, not by sending.
                        model.createShelf(name, field, value, false)
                        editingShelfId = null
                    },
                    onSave = { model.saveShelf(it) { editingShelfId = null } },
                    onDelete =
                        editingShelf?.let { shelf ->
                            {
                                editingShelfId = null
                                deletingShelfId = shelf.id
                            }
                        },
                    onDismiss = { if (!op.busy) editingShelfId = null },
                )
            val deletingShelf = shelves.firstOrNull { it.id == deletingShelfId }
            if (deletingShelf != null)
                ConfirmSheet(
                    covers = library.shelfEntries[deletingShelf.id].orEmpty(),
                    title = stringResource(R.string.library_list_delete_title, deletingShelf.name),
                    body = stringResource(R.string.library_list_delete_body),
                    confirmLabel = stringResource(R.string.library_list_delete),
                    dismissLabel = stringResource(R.string.library_list_keep),
                    onConfirm = {
                        model.deleteShelf(deletingShelf) {
                            deletingShelfId = null
                            nav.forget(setOf(deletingShelf.id))
                        }
                    },
                    onDismiss = { deletingShelfId = null },
                    destructive = true,
                    busy = op.busy,
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
                        model.managementError.value = null
                        editingShelfId = "new"
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
                    onOpen = { id ->
                        model.showImportResults.value = false
                        nav.open(Route.Book(id))
                    },
                    onDismiss = { model.showImportResults.value = false },
                )
            val detachBook = books.firstOrNull { it.id == detachBookId }
            if (detachBook != null)
                ConfirmSheet(
                    covers = listOf(detachBook),
                    title = stringResource(R.string.shell_detach_title),
                    body = stringResource(R.string.shell_detach_body),
                    confirmLabel = stringResource(R.string.shell_detach),
                    dismissLabel = stringResource(R.string.shell_keep_attached),
                    onConfirm = { model.detach(detachBook) { detachBookId = null } },
                    onDismiss = { detachBookId = null },
                    destructive = true,
                    busy = op.busy,
                )
            val actionBook = catalogueBooks.firstOrNull { it.id == actionsId }
            if (actionBook != null)
                BookActionsSheet(
                    actionBook,
                    books.any { it.id == actionBook.id },
                    op.busy || discoveryState.busy,
                    onDismiss = { actionsId = null },
                    onAction = { action ->
                        actionsId = null
                        when (action) {
                            BookAction.OPEN -> openEntry(actionBook.id)
                            BookAction.SELECT -> {
                                nav.root(FolioNav.LIBRARY)
                                selection = selection + actionBook.id
                            }
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
                                markdownPicker.launch(notesFileName(actionBook))
                            }
                            BookAction.ATTACH -> {
                                attachWishId = actionBook.id
                                attachmentPicker.launch(BOOK_FILE_TYPES)
                            }
                            BookAction.SEARCH -> {
                                // Librillo's own browser (M5): it searches the book sources for
                                // this
                                // title. Only a record without a file (a Want to Read entry) gets
                                // the
                                // book attached.
                                try {
                                    context.startActivity(
                                        dev.sadking.folio.acquire.BrowserLaunch.find(
                                            context,
                                            actionBook.title,
                                            decode(actionBook.authors),
                                            actionBook.id.takeIf { id ->
                                                books.none { it.id == id }
                                            },
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
                                epubExportPicker.launch("${actionBook.id}.${actionBook.format}")
                            }
                            BookAction.SHARE_EPUB -> model.openEpub(actionBook, true)
                            BookAction.EXTERNAL_READER -> model.openEpub(actionBook, false)
                            BookAction.OPEN_READER ->
                                dev.sadking.folio.reader.ReaderActivity.open(context, actionBook.id)
                        }
                    },
                )
            val listBook = catalogueBooks.firstOrNull { it.id == listEntryId }
            if (listBook != null)
                FolioSheet(onDismissRequest = { listEntryId = null }) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)
                    ) {
                        Text(
                            stringResource(R.string.shell_lists_for_book, listBook.title),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        val manual = shelves.filter { it.ruleField == "Manual" }
                        if (manual.isEmpty())
                            TextButton(
                                onClick = {
                                    listEntryId = null
                                    nav.root(FolioNav.LISTS)
                                    model.managementError.value = null
                                    editingShelfId = "new"
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
                FolioSheet(onDismissRequest = { statusEntryId = null }) {
                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                        Text(
                            stringResource(R.string.shell_status_title),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() },
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
                ConfirmSheet(
                    covers = listOf(attachmentBook, attachmentWish.displayBook()),
                    title = stringResource(R.string.rd_attach_confirm_title, attachmentWish.title),
                    body =
                        stringResource(
                            R.string.rd_attach_confirm_body,
                            attachmentBook.title,
                            attachmentBook.authorLabel.ifBlank {
                                stringResource(R.string.shell_unknown_author)
                            },
                        ),
                    confirmLabel = stringResource(R.string.rd_attach),
                    dismissLabel = stringResource(R.string.rd_keep_separate),
                    onConfirm = {
                        discovery.attach(attachmentWish, attachmentBook)
                        attachWishId = null
                        attachReviewId = null
                    },
                    onDismiss = {
                        attachWishId = null
                        attachReviewId = null
                    },
                    busy = discoveryState.busy,
                )
            if (removalIds.isNotEmpty()) {
                val targets = catalogueBooks.filter { it.id in removalIds }
                RemoveBooksSheet(
                    targets,
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
                HardcoverAccountSheet(
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
                    phoneReading =
                        phoneReading.firstOrNull {
                            it.bookId == book.id && it.revisionHash == book.currentHash
                        },
                ) {
                    showHardcover = false
                    discovery.clearReading()
                }
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
            if (showDecisions)
                DecisionsSheet(
                    decisions(ereaders),
                    hardcoverChecks,
                    hardcoverQuestions,
                    onAnswer = { decision, option ->
                        haptics.confirm()
                        devices.answer(decision.ereaderId, decision.question.id, option)
                    },
                    onPick = { check, candidate ->
                        haptics.confirm()
                        devices.pickMatch(check, candidate)
                    },
                    onNotOnHardcover = devices::notOnHardcover,
                    // The book's own Hardcover sheet, which searches by its title and author.
                    onSearch = { check ->
                        showDecisions = false
                        // A catalogue entry with a file is searched as its book.
                        val bookId =
                            books.firstOrNull { it.id == check.itemId }?.id
                                ?: wishes.firstOrNull { it.id == check.itemId }?.linkedBookId
                        val match = books.firstOrNull { it.id == bookId }
                        if (match != null) {
                            nav.open(Route.Book(match.id))
                            showHardcover = true
                            discovery.openReading(match)
                        } else
                            wishes
                                .firstOrNull { it.id == check.itemId }
                                ?.let { entry ->
                                    openEntry(entry.id, progress = true)
                                    discovery.openReading(entry.displayBook())
                                }
                    },
                    onHardcoverAnswer = { question, useHardcover ->
                        haptics.confirm()
                        devices.answerHardcover(question, useHardcover)
                    },
                    onAnswerAll = { useHardcover ->
                        haptics.confirm()
                        devices.answerAllHardcover(useHardcover)
                    },
                    onDismiss = {
                        showDecisions = false
                        devices.keepMatch()
                    },
                    offer = matchOffer,
                    onMove = { offer ->
                        haptics.confirm()
                        devices.moveMatch(offer)
                    },
                    onKeep = devices::keepMatch,
                )
            if (showSetup)
                PluginSetupSheet(
                    reinstall = pluginInstalledAt > 0 || ereaders.isNotEmpty(),
                    savedHost = device.host,
                    savedPort = device.port,
                    savedFingerprint = device.fingerprint,
                    folder = libraryFolder,
                    observed = observedHost,
                    probe = probeStatus,
                    progress = setupProgress,
                    onFind = devices::probeHost,
                    onInstall = devices::setUpEreader,
                    onDismiss = {
                        if (
                            setupProgress.stage != dev.sadking.folio.SetupProgress.Stage.INSTALLING
                        ) {
                            showSetup = false
                            devices.resetSetup()
                        }
                    },
                )
            if (showConnection)
                FolioSheet(
                    onDismissRequest = {
                        showConnection = false
                        devices.clearHostProbe()
                        devices.clearFolders()
                    }
                ) {
                    Box {
                        ConnectionForm(
                            device,
                            op.busy,
                            save = { config, password, key ->
                                devices.saveDevice(config, password, key) {
                                    showConnection = false
                                    devices.clearHostProbe()
                                    devices.clearFolders()
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
                            onForget = {
                                showConnection = false
                                devices.clearHostProbe()
                                devices.clearFolders()
                                devices.forgetDevice()
                            },
                        )
                    }
                }
        }
    }
}

/** Saves a copy of a book file; the type follows the name's extension (EPUB, PDF, CBZ or CBR). */
private object SaveBookCopy : ActivityResultContracts.CreateDocument("application/epub+zip") {
    override fun createIntent(context: android.content.Context, input: String) =
        super.createIntent(context, input)
            .setType(dev.sadking.folio.data.bookMimeType(input.substringAfterLast('.')))
}
