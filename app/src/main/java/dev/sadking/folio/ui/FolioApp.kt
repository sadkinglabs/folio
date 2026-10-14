@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package dev.sadking.folio.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.*
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
import dev.sadking.folio.device.*
import kotlinx.coroutines.flow.collectLatest

private data class Destination(val title: String, val icon: ImageVector)

private val destinations =
    listOf(
        Destination("Library", Icons.Outlined.AutoStories),
        Destination("Lists", Icons.Outlined.CollectionsBookmark),
        Destination("Device", Icons.Outlined.TabletMac),
        Destination("Reading", Icons.Outlined.MenuBook),
    )

@Composable
fun FolioApp(model: LibraryViewModel) {
    val discovery: DiscoveryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var showProfileImport by rememberSaveable { mutableStateOf(false) }
    val nav = rememberFolioNav()
    val catalogueId = nav.recordId
    var catalogueProgress by rememberSaveable { mutableStateOf(false) }
    var catalogueEditorId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingShelfId by rememberSaveable { mutableStateOf<String?>(null) }
    var showRemoved by rememberSaveable { mutableStateOf(false) }
    var showBatchManage by rememberSaveable { mutableStateOf(false) }
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
        val phoneFolder by model.phoneFolder.collectAsStateWithLifecycle()
        val folderReport by model.folderReport.collectAsStateWithLifecycle()
        val removed by model.removed.collectAsStateWithLifecycle()
        val importResults by model.importResults.collectAsStateWithLifecycle()
        val showImportResults by model.showImportResults.collectAsStateWithLifecycle()
        val managementError by model.managementError.collectAsStateWithLifecycle()
        val shelves by model.shelves.collectAsStateWithLifecycle()
        val memberships by model.memberships.collectAsStateWithLifecycle()
        val foregroundOp by model.operation.collectAsStateWithLifecycle()
        val discoveryState by discovery.state.collectAsStateWithLifecycle()
        val activeTasks by model.activeSyncTasks.collectAsStateWithLifecycle()
        val syncJobs by model.syncJobs.collectAsStateWithLifecycle()
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

        val plan by model.plan.collectAsStateWithLifecycle()
        val history by model.history.collectAsStateWithLifecycle()
        val inventory by model.inventory.collectAsStateWithLifecycle()
        val inventoryCheckedAt by model.inventoryCheckedAt.collectAsStateWithLifecycle()
        val device by model.device.collectAsStateWithLifecycle()
        val observedHost by model.observedHost.collectAsStateWithLifecycle()
        val probeStatus by model.probeStatus.collectAsStateWithLifecycle()
        val folderStatus by model.folderStatus.collectAsStateWithLifecycle()
        val folderListing by model.folderListing.collectAsStateWithLifecycle()
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
        val signals = library.signals
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
                title = { Text("KOReader companion") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Enter KOReader’s existing plugins folder. Folio installs its companion and keeps a backup of any previous version. Restart KOReader afterwards."
                        )
                        OutlinedTextField(
                            companionPath,
                            { companionPath = it },
                            label = { Text("Kindle plugins folder") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            installCompanion = false
                            model.installCompanion(companionPath.trim())
                        }
                    ) {
                        Text("Install")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { installCompanion = false }) { Text("Cancel") }
                },
            )
        val phoneFolderPicker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) model.choosePhoneFolder(uri)
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
                books.firstOrNull { it.id == id }?.let(model::sendBook)
            } else if (action == "missing") model.syncMissing()
            else if (action == "preview") model.sync()
        }
        val notificationPermission =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                performRequestedSync()
            }
        fun requestSync(action: String, bookId: String? = null) {
            pendingSendId = bookId
            pendingSync = action
            if (
                android.os.Build.VERSION.SDK_INT >= 33 &&
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
        // Settings handles its own back as a dialog window; this covers book screens and selection.
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
        CompositionLocalProvider(LocalRouteHeading provides (book == null && catalogue == null)) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    if (book != null || catalogue != null)
                        TopAppBar(
                            title = { Text(if (editing) "Edit book" else "Book details") },
                            navigationIcon = {
                                IconButton(
                                    onClick = { leaveBook() },
                                    enabled = !editing || !op.busy,
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
                                        },
                                        enabled = !op.busy,
                                    ) {
                                        Icon(Icons.Outlined.Edit, "Edit metadata")
                                    }
                                if (!editing)
                                    IconButton(
                                        onClick = { actionsId = book?.id ?: catalogue?.id }
                                    ) {
                                        Icon(Icons.Outlined.MoreVert, "Book actions")
                                    }
                            },
                        )
                    else if (tab != 0)
                        TopAppBar(
                            title = { Text(destinations.getOrNull(tab)?.title ?: "Library") },
                            actions = {
                                IconButton(onClick = { nav.open(Route.Settings) }) {
                                    Icon(Icons.Outlined.Settings, "Settings")
                                }
                            },
                        )
                },
                bottomBar = {
                    if (book == null && catalogue == null)
                        NavigationBar {
                            destinations.forEachIndexed { i, d ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = {
                                        nav.root(i)
                                        selection = emptySet()
                                    },
                                    icon = {
                                        BadgedBox(
                                            badge = {
                                                if (i == 2 && syncJob?.active == true) Badge()
                                            }
                                        ) {
                                            Icon(d.icon, null)
                                        }
                                    },
                                    label = { Text(d.title) },
                                )
                            }
                        }
                },
                floatingActionButton = {
                    if (
                        book == null &&
                            catalogue == null &&
                            tab == 0 &&
                            selection.isEmpty() &&
                            !op.busy
                    )
                        FloatingActionButton(onClick = { showAddBooks = true }) {
                            Icon(Icons.Outlined.Add, "Add books")
                        }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (foregroundOp.busy) StatusSurface(foregroundOp)
                    if (book != null) {
                        if (editing)
                            MetadataEditor(book, op.busy, onDirtyChange = { editorDirty = it }) {
                                metadata,
                                cover ->
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
                                    dev.sadking.folio.reader.ReaderActivity.open(context, book.id)
                                },
                                phoneReading =
                                    phoneReading.firstOrNull {
                                        it.bookId == book.id && it.revisionHash == book.currentHash
                                    },
                                onShelf = { id, checked -> model.membership(book.id, id, checked) },
                                onStatus = { model.status(book, it) },
                                onSync = {
                                    if (device.host.isBlank()) {
                                        pairingSendId = book.id
                                        showConnection = true
                                    } else if (syncJob?.state in listOf("FAILED", "PAUSED")) {
                                        nav.root(2)
                                    } else requestSync("book", book.id)
                                },
                                signals = signals[book.id],
                                onRemove = { removalIds = setOf(book.id) },
                                onCollections = { listEntryId = book.id },
                                onRefreshReading = {
                                    if (device.host.isBlank()) showConnection = true
                                    else model.refreshBookReading(book.id)
                                },
                                onReading = { nav.root(3) },
                                busy = op.busy,
                                deviceBusy = deviceBusy,
                                reading =
                                    reading
                                        .filter { it.bookId == book.id && it.deviceId == device.id }
                                        .maxByOrNull { it.fetchedAt },
                                annotations = annotations.filter { it.bookId == book.id },
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
                        stateHolder.SaveableStateProvider("tab-$tab") {
                            when (tab) {
                                0,
                                1 ->
                                    LibraryArea(
                                        lists = tab == 1,
                                        library = library,
                                        shelves = shelves,
                                        selection = selection,
                                        busy = op.busy,
                                        onSelection = { selection = it },
                                        onOpen = { entry -> openEntry(entry.id) },
                                        onContext = { actionsId = it.id },
                                        onAdd = { showAddBooks = true },
                                        onCreate = { name, field, value ->
                                            model.createShelf(name, field, value)
                                        },
                                        onManageShelf = {
                                            model.managementError.value = null
                                            editingShelfId = it.id
                                        },
                                        onPolicy = model::shelfPolicy,
                                        onSendShelf = {
                                            nav.root(2)
                                            model.previewShelf(it)
                                        },
                                        onSend = {
                                            nav.root(2)
                                            model.preview(it)
                                            selection = emptySet()
                                        },
                                        onRemove = { removalIds = it },
                                        onBatch = { showBatchManage = true },
                                        onAssign = { id ->
                                            model.manageBatch(selection, shelf = id) {
                                                selection = emptySet()
                                            }
                                        },
                                        onDiscover = { showDiscovery = true },
                                        onSettings = { nav.open(Route.Settings) },
                                        onFolderSync = {
                                            if (phoneFolder.isBlank()) nav.open(Route.Settings)
                                            else if (device.host.isBlank()) showConnection = true
                                            else model.syncPairedFolder()
                                        },
                                        requestedList = if (tab == 1) requestedList else null,
                                        onRequestHandled = { requestedList = null },
                                    )
                                2 ->
                                    KindleScreen(
                                        device,
                                        inventory,
                                        plan,
                                        history,
                                        deviceBusy,
                                        inventoryCheckedAt = inventoryCheckedAt,
                                        localBookCount = books.size,
                                        onLibrary = { nav.root(0) },
                                        onConfigure = { showConnection = true },
                                        onPreview = { model.preview() },
                                        onSync = { requestSync("preview") },
                                        onSyncMissing = { requestSync("missing") },
                                        onCollections = model::sendCollections,
                                        syncJob = syncJob,
                                        onCancelJob = model::cancelSync,
                                        onResumeJob = model::resumeSync,
                                        bookTitles = books.associate { it.id to it.title },
                                        remoteBooks =
                                            transfers
                                                .filter { it.deviceId == device.id }
                                                .mapNotNull { t ->
                                                    books
                                                        .firstOrNull { it.id == t.bookId }
                                                        ?.let { t.remotePath to it }
                                                }
                                                .toMap(),
                                        onOpenBook = { nav.open(Route.Book(it.id)) },
                                        syncScope =
                                            if (shelves.any { it.syncEnabled })
                                                "Sync includes: ${shelves.filter { it.syncEnabled }.joinToString { it.name }}. No remote books are removed."
                                            else
                                                "Sync includes all phone books. Select collection sync preferences to limit it.",
                                    )
                                3 ->
                                    ReadingScreen(
                                        library.entries,
                                        reading,
                                        annotations,
                                        deviceBusy,
                                        model::refreshReading,
                                        onOpen = { entry -> openEntry(entry.id) },
                                        statistics =
                                            statistics.firstOrNull { it.deviceId == device.id },
                                        onStatistics = model::refreshStatistics,
                                        paired = device.host.isNotBlank(),
                                        onConnect = { showConnection = true },
                                        onLibrary = { nav.root(0) },
                                        signals = library.signals,
                                        onHardcover = { tracked ->
                                            openEntry(tracked.id, progress = true)
                                            if (tracked.id in library.fileIds) showHardcover = true
                                            discovery.openReading(tracked)
                                        },
                                    )
                            }
                        }
                }
            }
        }
        if (nav.top == Route.Settings)
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { nav.back() },
                properties =
                    androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                CompositionLocalProvider(LocalRouteHeading provides true) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text("Settings") },
                                navigationIcon = {
                                    IconButton(onClick = { nav.back() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                    }
                                },
                            )
                        },
                        snackbarHost = { SnackbarHost(snackbar) },
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) {
                            SettingsScreen(
                                appearance,
                                dynamic,
                                books.size,
                                onTheme = {
                                    appearance = it
                                    prefs.edit().putString("theme", it).apply()
                                },
                                onDynamic = {
                                    dynamic = it
                                    prefs.edit().putBoolean("dynamic", it).apply()
                                },
                                onConnection = { showConnection = true },
                                onForget = model::forgetDevice,
                                paired = device.host.isNotBlank(),
                                onRemoved = { showRemoved = true },
                                removedCount = removed.map { it.groupId }.distinct().size,
                                recordCount = library.entries.size,
                                phoneFolder = phoneFolder,
                                onPhoneFolder = { phoneFolderPicker.launch(null) },
                                onRefreshFolder = model::refreshPhoneFolder,
                                onForgetFolder = model::forgetPhoneFolder,
                                onInstallCompanion = { installCompanion = true },
                                onImports = { model.showImportResults.value = true },
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
                                        arrayOf("application/zip", "application/octet-stream")
                                    )
                                },
                            )
                        }
                    }
                }
            }
        if (confirmDiscard)
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text("Discard unsaved changes?") },
                text = {
                    Text("Your book has not been changed. Keep editing or discard this draft.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDiscard = false
                            editorDirty = false
                            if (nav.editing) nav.back()
                        }
                    ) {
                        Text("Discard")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
                },
            )
        if (showAddBooks)
            AddBooksSheet(
                hardcoverUsername,
                onDismiss = { showAddBooks = false },
                onAction = { action ->
                    showAddBooks = false
                    when (action) {
                        AddBookAction.EPUB ->
                            picker.launch(
                                arrayOf("application/epub+zip", "application/octet-stream")
                            )
                        AddBookAction.MANUAL -> {
                            model.managementError.value = null
                            catalogueEditorId = "new"
                        }
                        AddBookAction.DISCOVER -> {
                            showDiscovery = true
                        }
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
        if (showBatchManage)
            BatchManageSheet(
                selection.size,
                shelves,
                op.busy,
                onList = { shelf, add ->
                    model.manageBatch(selection, shelf, add) {
                        selection = emptySet()
                        showBatchManage = false
                    }
                },
                onStatus = { value ->
                    model.manageBatch(selection, status = value) {
                        selection = emptySet()
                        showBatchManage = false
                    }
                },
                onDismiss = { showBatchManage = false },
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
                title = { Text("Detach catalogue entry?") },
                text = {
                    Text(
                        "Keeps this EPUB and its Kindle path, notes and progress. Its catalogue book returns as a separate entry with its metadata, Hardcover match and lists, ready for the correct EPUB."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { model.detach(detachBook) { detachBookId = null } },
                        enabled = !op.busy,
                    ) {
                        Text("Detach")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { detachBookId = null }) { Text("Keep attachment") }
                },
            )
        val actionBook = catalogueBooks.firstOrNull { it.id == actionsId }
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
                            nav.root(0)
                            selection = selection + actionBook.id
                        }
                        BookAction.SEND -> {
                            if (device.host.isBlank()) {
                                pairingSendId = actionBook.id
                                showConnection = true
                            } else if (syncJob?.state in listOf("FAILED", "PAUSED")) {
                                nav.root(2)
                            } else requestSync("book", actionBook.id)
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
                                discovery.report("Install a browser to search for this book")
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
            ModalBottomSheet(onDismissRequest = model::clearFolderReport) {
                LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp)) {
                    item {
                        Text(
                            "Sync needs your review",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            "Both copies were kept. No reading data was overwritten.",
                            Modifier.padding(vertical = 12.dp),
                        )
                    }
                    items(folderReport.size) { index ->
                        Text(folderReport[index], Modifier.padding(vertical = 12.dp))
                        HorizontalDivider()
                    }
                    item { TextButton(onClick = model::clearFolderReport) { Text("Done") } }
                }
            }
        val listBook = catalogueBooks.firstOrNull { it.id == listEntryId }
        if (listBook != null)
            ModalBottomSheet(onDismissRequest = { listEntryId = null }) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)
                ) {
                    Text(
                        "Lists · ${listBook.title}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    val manual = shelves.filter { it.ruleField == "Manual" }
                    if (manual.isEmpty())
                        TextButton(
                            onClick = {
                                listEntryId = null
                                nav.root(1)
                            }
                        ) {
                            Text("Create a list")
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
                    Text("Phone reading status", style = MaterialTheme.typography.headlineSmall)
                    Text("Kindle observations and Hardcover are updated separately.")
                    phoneStatuses.forEach { value ->
                        TextButton(
                            onClick = {
                                model.status(statusBook, value)
                                statusEntryId = null
                            },
                            enabled = !op.busy,
                        ) {
                            Text(value + if (value == statusBook.status) " · Current" else "")
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
                title = { Text("Attach this edition?") },
                text = {
                    Text(
                        "${attachmentBook.title}\n${attachmentBook.authorLabel}\n\nAttach to ${attachmentWish.title}? Lists and the Hardcover match are retained. Cancelling keeps the imported EPUB separate."
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
                        Text("Confirm attachment")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            attachWishId = null
                            attachReviewId = null
                        }
                    ) {
                        Text("Cancel")
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
                onRefreshDevice = { model.refreshBookReading(book.id) },
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
                    nav.root(1)
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
                title = { Text("Restore this backup?") },
                text = {
                    Text(
                        "The entire archive is checked before restore. Missing books are added, existing books are retained and collection memberships are merged. SSH pairing is excluded."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            restoreUri?.let { model.restoreBackup(android.net.Uri.parse(it)) }
                            restoreUri = null
                        },
                        enabled = !op.busy,
                    ) {
                        Text("Restore")
                    }
                },
                dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Cancel") } },
            )
        if (showConnection)
            ModalBottomSheet(
                onDismissRequest = {
                    showConnection = false
                    model.clearHostProbe()
                    model.clearFolders()
                    pairingSendId = null
                }
            ) {
                Box {
                    ConnectionForm(
                        device,
                        deviceBusy,
                        save = { config, password, key ->
                            model.saveDevice(config, password, key) {
                                showConnection = false
                                model.clearHostProbe()
                                model.clearFolders()
                                pairingSendId?.let { id ->
                                    pairingSendId = null
                                    requestSync("book", id)
                                }
                            }
                        },
                        observed = observedHost,
                        probeStatus = probeStatus,
                        onProbe = model::probeHost,
                        onClearProbe = model::clearHostProbe,
                        folderListing = folderListing,
                        folderStatus = folderStatus,
                        onBrowse = model::browseFolders,
                        onClearFolders = model::clearFolders,
                    )
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                }
            }
    }
}
