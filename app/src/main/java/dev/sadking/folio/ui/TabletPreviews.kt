package dev.sadking.folio.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.data.*
import dev.sadking.folio.sync.HardcoverSyncStatus

/*
 * Tablet and foldable previews: the app's frame (rail and panes) around real screens, at the sizes
 * the screenshot test renders: 840 x 1280 dp (tablet, portrait), 1280 x 800 dp (tablet, landscape)
 * and 700 x 840 dp (an unfolded phone). The window comes from the configuration, as in the app;
 * [hinge] puts a fold down the middle (book posture) to show the panes split at the hinge.
 */

/** The window to draw in: the configuration's, with a fold down the middle when [hinge]. */
@Composable
private fun previewWindow(hinge: Boolean): FolioWindow {
    val window = rememberFolioWindow()
    return if (hinge) folioWindow(window.widthDp, window.heightDp, window.widthDp / 2) else window
}

/**
 * The app's frame as FolioApp draws it from 600 dp: the rail on [tab], then [Panes]. [twoPane] is
 * false for tabs that lay themselves out (Device, Settings).
 */
@Composable
private fun PreviewFrame(
    tab: Int,
    hinge: Boolean,
    twoPane: Boolean = true,
    wideMaxWidth: Int? = WIDE_PANE_MAX_WIDTH_DP,
    detail: (@Composable () -> Unit)? = null,
    primary: @Composable () -> Unit,
) {
    val window = previewWindow(hinge)
    CompositionLocalProvider(LocalFolioWindow provides window) {
        FolioTheme(dynamic = false) {
            Surface(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    if (window.layout != FolioLayout.BAR)
                        FolioRail(
                            tab = tab,
                            onTab = {},
                            current = CurrentBook(sampleBooks[0], 0.37, PREVIEW_NOW),
                            onContinue = {},
                            onOptions = {},
                            deviceBadge = tab == FolioNav.DEVICE,
                        )
                    Panes(
                        twoPane = twoPane && window.twoPane,
                        maxWidth =
                            when (window.layout) {
                                FolioLayout.BAR -> null
                                FolioLayout.RAIL -> PANE_MAX_WIDTH_DP.dp
                                FolioLayout.TWO_PANE -> wideMaxWidth?.dp
                            },
                        primary = primary,
                        detail = detail,
                        empty = {
                            EmptyPane(
                                stringResource(
                                    if (tab == FolioNav.LISTS) R.string.ad6_choose_list
                                    else R.string.ad6_choose_book
                                )
                            )
                        },
                    )
                }
            }
        }
    }
}

/** The Library with a book open beside it; on a phone-sized rail, the book alone. */
@Preview(name = "Library · tablet", widthDp = 840, heightDp = 1280)
@Preview(name = "Library · tablet landscape", widthDp = 1280, heightDp = 800)
@Preview(name = "Library · foldable", widthDp = 700, heightDp = 840)
@Composable
internal fun TabletLibraryPreview() = TabletLibrary(hinge = false)

/** The same with the screen half folded like a book: the panes split at the hinge. */
@Preview(name = "Library · foldable, half open", widthDp = 700, heightDp = 840)
@Composable
internal fun FoldLibraryPreview() = TabletLibrary(hinge = true)

@Composable
private fun TabletLibrary(hinge: Boolean) {
    val books = sampleBooks
    PreviewFrame(
        FolioNav.LIBRARY,
        hinge,
        detail = {
            DetailPane({ BookTopBar(null, onBack = {}, title = books[0].title) }) {
                BookDetails(
                    books[0],
                    lists = listOf("Slow Sundays"),
                    signals = BookSignals("Reading · phone", 0.41, null),
                )
            }
        },
    ) {
        LibraryScreen(
            books,
            emptySet(),
            onOpen = {},
            onSelect = {},
            progress = mapOf(books[0].id to 0.37),
            currentId = books[0].id,
            missingFiles = emptySet(),
        )
    }
}

/** The Library with nothing chosen: the right pane says what it is for. */
@Preview(name = "Library empty pane · tablet", widthDp = 840, heightDp = 1280)
@Composable
internal fun TabletLibraryEmptyPreview() {
    PreviewFrame(FolioNav.LIBRARY, hinge = false) {
        LibraryScreen(
            sampleBooks,
            emptySet(),
            onOpen = {},
            onSelect = {},
            missingFiles = emptySet(),
        )
    }
}

/** Lists on the left, one list's books on the right. */
@Preview(name = "Lists · tablet", widthDp = 840, heightDp = 1280)
@Preview(name = "Lists · tablet landscape", widthDp = 1280, heightDp = 800)
@Preview(name = "Lists · foldable", widthDp = 700, heightDp = 840)
@Composable
internal fun TabletListsPreview() = TabletLists(hinge = false)

/** The same with the screen half folded like a book: the panes split at the hinge. */
@Preview(name = "Lists · foldable, half open", widthDp = 700, heightDp = 840)
@Composable
internal fun FoldListsPreview() = TabletLists(hinge = true)

@Composable
private fun TabletLists(hinge: Boolean) {
    val library = listSampleLibrary()
    PreviewFrame(
        FolioNav.LISTS,
        hinge,
        detail = {
            ListDetailScreen(
                listCard("slow", listSampleShelves, library)!!,
                emptySet(),
                busy = false,
                onBack = {},
                onOpen = {},
                onSelect = {},
                onClearSelection = {},
                onAddFromLibrary = {},
                onEdit = {},
                progress = mapOf(sampleBooks.first().id to 0.37),
                currentId = sampleBooks.first().id,
            )
        },
    ) {
        ListsScreen(
            listCards(listSampleShelves, library),
            busy = false,
            onOpen = {},
            onNew = {},
            onScan = {},
        )
    }
}

/** Settings' groups on the left, Text (the reader's defaults) open on the right. */
@Preview(name = "Settings · tablet", widthDp = 840, heightDp = 1280)
@Preview(name = "Settings · tablet landscape", widthDp = 1280, heightDp = 800)
@Preview(name = "Settings · foldable", widthDp = 700, heightDp = 840)
@Composable
internal fun TabletSettingsPreview() = TabletSettings(hinge = false)

/** The same with the screen half folded like a book: the panes split at the hinge. */
@Preview(name = "Settings · foldable, half open", widthDp = 700, heightDp = 840)
@Composable
internal fun FoldSettingsPreview() = TabletSettings(hinge = true)

/** Settings on a tablet with Licences open beside the groups. */
@Preview(name = "Settings licences · tablet", widthDp = 840, heightDp = 1280)
@Composable
internal fun TabletLicencesPreview() = TabletSettings(hinge = false, SettingsPane.LICENCES.name)

@Composable
private fun TabletSettings(hinge: Boolean, pane: String = SettingsPane.TEXT.name) {
    PreviewFrame(FolioNav.SETTINGS, hinge, twoPane = false, wideMaxWidth = null) {
        SettingsScreen(
            appearance = "System",
            dynamic = true,
            onTheme = {},
            onDynamic = {},
            reading = ReadingDefaults(),
            statistics = sampleStatistics,
            hardcoverStatus =
                HardcoverSyncStatus(
                    connected = true,
                    lastSyncAt = PREVIEW_NOW - 5 * 60_000,
                    matched = 312,
                    toCheck = 4,
                ),
            now = PREVIEW_NOW,
            hardcoverUsername = "alex",
            count = 9,
            recordCount = 12,
            removedCount = 3,
            sshHost = "192.168.1.23",
            phoneFolder = "Internal storage/Books",
            initialPane = pane,
        )
    }
}

/** The Device tab: one pane, at most 720 dp wide. */
@Preview(name = "Device · tablet", widthDp = 840, heightDp = 1280)
@Preview(name = "Device · tablet landscape", widthDp = 1280, heightDp = 800)
@Preview(name = "Device · foldable", widthDp = 700, heightDp = 840)
@Composable
internal fun TabletDevicePreview() = TabletDevice(hinge = false)

/** The same with the screen half folded like a book: the panes split at the hinge. */
@Preview(name = "Device · foldable, half open", widthDp = 700, heightDp = 840)
@Composable
internal fun FoldDevicePreview() = TabletDevice(hinge = true)

@Composable
private fun TabletDevice(hinge: Boolean) {
    PreviewFrame(FolioNav.DEVICE, hinge, twoPane = false) {
        DeviceScreen(
            ereaders = listOf(sampleKindle),
            line = SyncLineState(SyncLine.Synced("Kindle Paperwhite", sampleKindle.lastSync), 1),
            requestedAt = 0,
            now = PREVIEW_NOW,
            autoSync = true,
            onAutoSync = {},
            keepAwake = false,
            onKeepAwake = {},
            noWifi = false,
            onOpenWifi = {},
            onSync = {},
            onSetUp = {},
            onDecisions = {},
            activity = sampleActivity,
            onClearActivity = {},
            onSignIn = {},
            onSavePlugin = {},
        )
    }
}

/** Settings → About → Licences, as its sheet shows it on a phone. */
@Preview(name = "Licences · light", widthDp = 412, heightDp = 1400)
@Preview(
    name = "Licences · dark",
    widthDp = 412,
    heightDp = 1400,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun LicencesPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Column { LicencesContent() }
        }
    }
}
