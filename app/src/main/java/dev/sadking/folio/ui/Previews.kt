package dev.sadking.folio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.sadking.folio.Operation
import dev.sadking.folio.SetupProgress
import dev.sadking.folio.core.sync.Question
import dev.sadking.folio.data.*
import dev.sadking.folio.device.DeviceConfig
import dev.sadking.folio.device.ObservedHostKey
import dev.sadking.folio.link.LinkLog
import dev.sadking.folio.link.Refusal
import dev.sadking.folio.link.RefusedFile
import dev.sadking.folio.sync.EreaderSync
import dev.sadking.folio.sync.FinishPrompt

private val sampleBooks =
    listOf(
        Book(
            "sample1",
            "The Orchard",
            "[\"Alex Rowan\"]",
            description =
                "A quiet journey through the seasons, told through the people who care for one small orchard.",
            series = "Seasons",
            seriesIndex = "1",
            publisher = "Example Press",
            language = "en",
            originalPath = "",
            currentPath = "",
            originalHash = "",
            currentHash = "",
            status = "Reading",
        ),
        Book(
            "sample2",
            "Letters from the Coast",
            "[\"Morgan Wells\"]",
            originalPath = "",
            currentPath = "",
            originalHash = "",
            currentHash = "",
        ),
        Book(
            "sample3",
            "A Field Guide to Elsewhere",
            "[\"Sam Reed\"]",
            originalPath = "",
            currentPath = "",
            originalHash = "",
            currentHash = "",
            status = "Finished",
        ),
    )

@Preview(name = "Library · light", showBackground = true, widthDp = 412, heightDp = 892)
@Preview(
    name = "Library · dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun LibraryPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            val wish =
                WishBook(
                    "catalogue1",
                    "hardcover:1",
                    "The Left Hand of Darkness",
                    "[\"Ursula K. Le Guin\"]",
                    hardcoverId = 1,
                )
            // Enough books in progress for the Reading carousel to scroll.
            val reading =
                listOf("The Long Winter", "Salt and Iron", "Night Trains").mapIndexed { i, title ->
                    sampleBooks[1].copy(id = "reading$i", title = title, status = "Reading")
                }
            val books = sampleBooks + reading
            LibraryScreen(
                unifiedLibrary(books, listOf(wish)),
                emptySet(),
                onOpen = {},
                onSelect = {},
                fileIds = books.map { it.id }.toSet(),
                signals = mapOf(wish.id to catalogueSignals(wish, null)),
                progress =
                    mapOf(sampleBooks.first().id to 0.37) +
                        reading.mapIndexed { i, book -> book.id to 0.2 * (i + 1) },
                currentId = sampleBooks.first().id,
                missingFiles = setOf("sample2"),
                syncLine = {
                    SyncStatusLine(
                        SyncLineState(
                            SyncLine.Synced("Kindle Paperwhite", PREVIEW_NOW - 2 * 60_000),
                            1,
                        ),
                        PREVIEW_NOW,
                        onSync = {},
                        onSetUp = {},
                        onDecisions = {},
                    )
                },
            )
        }
    }
}

@Preview(name = "Details · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Details · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun DetailsPreview() {
    FolioTheme(dynamic = false) {
        Surface {
            BookDetails(
                sampleBooks[0],
                lists = listOf("Slow Sundays"),
                signals = BookSignals("Reading · phone", 0.41, null),
            )
        }
    }
}

@Preview(name = "Details · file missing", widthDp = 412, heightDp = 892)
@Composable
internal fun DetailsFileMissingPreview() {
    FolioTheme(dynamic = false) {
        Surface {
            BookDetails(
                sampleBooks[1],
                signals = BookSignals("Reading · phone", 0.41, null),
                fileMissing = true,
            )
        }
    }
}

@Preview(name = "Details · changed outside", widthDp = 412, heightDp = 892)
@Composable
internal fun DetailsChangedPreview() {
    FolioTheme(dynamic = false) {
        Surface {
            BookDetails(sampleBooks[2], fileMissing = false, changedOutside = true)
        }
    }
}

@Preview(name = "Editor · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Editor · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun EditorPreview() {
    FolioTheme(dynamic = false) { Surface { MetadataEditor(sampleBooks[0], false) { _, _ -> } } }
}

/** A fuller shelf for the Lists tab: enough books for a four-cover fan and a few statuses. */
private val listSampleBooks =
    sampleBooks +
        listOf(
            Book(
                "sample4",
                "Winter Light",
                "[\"Ida Berg\"]",
                originalPath = "",
                currentPath = "",
                originalHash = "",
                currentHash = "",
                status = "Finished",
            ),
            Book(
                "sample5",
                "Small Hours",
                "[\"Theo Marsh\"]",
                originalPath = "",
                currentPath = "",
                originalHash = "",
                currentHash = "",
                status = "Finished",
            ),
            Book(
                "sample6",
                "The Long Table",
                "[\"June Okafor\"]",
                originalPath = "",
                currentPath = "",
                originalHash = "",
                currentHash = "",
                status = "Finished",
            ),
        )

private val listSampleShelves =
    listOf(
        Shelf("slow", "Slow Sundays", syncEnabled = true),
        Shelf("spanish", "In Spanish", "Language", "es"),
        Shelf("discover", "To discover"),
    )

private fun listSampleLibrary() =
    LibraryPresentation(
        listSampleBooks,
        listSampleBooks.map { it.id }.toSet(),
        emptyMap(),
        mapOf(
            "slow" to listOf(listSampleBooks[0], listSampleBooks[1], listSampleBooks[2]),
            "spanish" to listOf(listSampleBooks[4]),
        ),
    )

@Preview(name = "Lists · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Lists · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun ListsPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            ListsScreen(
                listCards(listSampleShelves, listSampleLibrary()),
                busy = false,
                onOpen = {},
                onNew = {},
                onScan = {},
            )
        }
    }
}

/** The Lists tab before any lists of your own: the three reading lists and the New list tile. */
@Composable
internal fun ListsFirstRunPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            ListsScreen(
                listCards(emptyList(), listSampleLibrary()),
                busy = false,
                onOpen = {},
                onNew = {},
                onScan = {},
            )
        }
    }
}

@Preview(name = "List detail · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "List detail · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun ListDetailPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            ListDetailScreen(
                listCard("slow", listSampleShelves, listSampleLibrary())!!,
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
        }
    }
}

@Preview(name = "List editor · light", widthDp = 412, heightDp = 892)
@Composable
internal fun ListEditorPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            ListEditorSheet(
                listSampleShelves[1],
                takenNames = listSampleShelves.map { it.name },
                busy = false,
                error = null,
                onCreate = { _, _, _ -> },
                onSave = {},
                onDelete = {},
                onDismiss = {},
            )
        }
    }
}

/** A fixed clock for previews and screenshots, so "2 min ago" never drifts. */
internal const val PREVIEW_NOW = 1791750000000L

private val sampleQuestions =
    listOf(
        Question(
            "q-1a2b3c4d5e6f7a8b",
            "sample1",
            "Librillo/Alex Rowan/The Orchard.epub",
            "status",
            "Reading",
            "Finished",
            "The Orchard: Kindle says Finished, phone says Reading. Which is right?",
            listOf("Finished", "Reading"),
        ),
        Question(
            "q-2b3c4d5e6f7a8b9c",
            "sample2",
            "Librillo/Morgan Wells/Letters from the Coast.epub",
            "file",
            "",
            "",
            "Letters from the Coast was changed on both the phone and the Kindle. Which copy should both keep?",
            listOf("Kindle copy", "Phone copy"),
        ),
    )

private val sampleKindle =
    EreaderSync(
        "kindle-7f3a",
        "Kindle Paperwhite",
        lastSync = PREVIEW_NOW - 2 * 60_000,
        questions = sampleQuestions,
        answers = mapOf("q-2b3c4d5e6f7a8b9c" to "Kindle copy"),
        refused =
            listOf(
                RefusedFile("a".repeat(64), "Downloads/Scan 2024.pdf", Refusal.NOT_A_BOOK, 0),
                RefusedFile("b".repeat(64), "Comics/Big Atlas.cbz", Refusal.TOO_LARGE, 0),
            ),
        books = 412,
    )

private val sampleActivity =
    listOf(
        LinkLog.Entry(
            PREVIEW_NOW - 2 * 60_000,
            LinkLog.Kind.REQUEST,
            "/folio/v2/finish",
            200,
            "Kindle Paperwhite",
        ),
        LinkLog.Entry(
            PREVIEW_NOW - 3 * 60_000,
            LinkLog.Kind.REQUEST,
            "/folio/v2/sync",
            200,
            "Kindle Paperwhite",
        ),
        LinkLog.Entry(
            PREVIEW_NOW - 4 * 60_000,
            LinkLog.Kind.REQUEST,
            "/syncs/progress",
            200,
            "192.168.1.17",
        ),
        LinkLog.Entry(PREVIEW_NOW - 50 * 60_000, LinkLog.Kind.SCREEN_ON),
        LinkLog.Entry(PREVIEW_NOW - 5 * 3_600_000, LinkLog.Kind.STARTED, port = 8765),
    )

@Preview(name = "Device · light", widthDp = 412, heightDp = 1400)
@Preview(
    name = "Device · dark",
    widthDp = 412,
    heightDp = 1400,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun KindlePreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            DeviceScreen(
                ereaders = listOf(sampleKindle),
                line =
                    SyncLineState(SyncLine.Synced("Kindle Paperwhite", sampleKindle.lastSync), 1),
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
}

/** Set up from this phone, never synced, automatic sync off and no Wi-Fi. */
@Preview(name = "Device waiting · light", widthDp = 412, heightDp = 1100)
@Preview(
    name = "Device waiting · dark",
    widthDp = 412,
    heightDp = 1100,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun DeviceOffPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            DeviceScreen(
                ereaders = emptyList(),
                line = SyncLineState(SyncLine.NeverSynced(null)),
                requestedAt = 0,
                now = PREVIEW_NOW,
                autoSync = false,
                onAutoSync = {},
                keepAwake = false,
                onKeepAwake = {},
                noWifi = true,
                onOpenWifi = {},
                onSync = {},
                onSetUp = {},
                onDecisions = {},
                activity = emptyList(),
                onClearActivity = {},
                onSignIn = {},
                onSavePlugin = {},
            )
        }
    }
}

@Preview(name = "Device first run · light", widthDp = 412, heightDp = 1000)
@Composable
internal fun DeviceFirstRunPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            DeviceScreen(
                ereaders = emptyList(),
                line = SyncLineState(SyncLine.NotSetUp),
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
                activity = listOf(LinkLog.Entry(PREVIEW_NOW, LinkLog.Kind.STARTED, port = 8765)),
                onClearActivity = {},
                onSignIn = {},
                onSavePlugin = {},
            )
        }
    }
}

/** Every sentence the Library's sync line can say, top to bottom. */
@Preview(name = "Sync line · light", widthDp = 412, heightDp = 600)
@Preview(
    name = "Sync line · dark",
    widthDp = 412,
    heightDp = 600,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun SyncLinePreview() {
    // Inspection mode, as in Android Studio: the "Syncing" ring stands still, so the screenshot
    // test can settle.
    CompositionLocalProvider(androidx.compose.ui.platform.LocalInspectionMode provides true) {
        SyncLineRows()
    }
}

@Composable
private fun SyncLineRows() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listOf(
                        SyncLineState(SyncLine.NotSetUp),
                        SyncLineState(SyncLine.NeverSynced(null)),
                        SyncLineState(SyncLine.Syncing("Kindle Paperwhite")),
                        SyncLineState(SyncLine.Requested("Kindle Paperwhite")),
                        SyncLineState(
                            SyncLine.Synced("Kindle Paperwhite", PREVIEW_NOW - 2 * 60_000)
                        ),
                        SyncLineState(SyncLine.Synced(null, PREVIEW_NOW - 26 * 3_600_000), 2),
                    )
                    .forEach { state ->
                        SyncStatusLine(
                            state,
                            PREVIEW_NOW,
                            onSync = {},
                            onSetUp = {},
                            onDecisions = {},
                        )
                    }
            }
        }
    }
}

@Preview(name = "Decisions · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Decisions · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun DecisionsPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DecisionsContent(
                decisions(listOf(sampleKindle)),
                listOf(FinishPrompt("sample3", "A Field Guide to Elsewhere")),
                onAnswer = { _, _ -> },
                onMarkRead = {},
                onNotNow = {},
            )
        }
    }
}

@Preview(name = "Setup · light", widthDp = 412, heightDp = 1200)
@Preview(
    name = "Setup · dark",
    widthDp = 412,
    heightDp = 1200,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun SetupPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            PluginSetupContent(
                reinstall = false,
                savedHost = "192.168.1.30",
                savedPort = 2222,
                savedFingerprint = "",
                folder = "Librillo",
                observed =
                    ObservedHostKey(
                        "192.168.1.30",
                        2222,
                        "ssh-ed25519",
                        "SHA256:Xq3r0pXyZ1c2V4bN5m6L7k8J9h0G1f2D3s4A5q6W7e8",
                    ),
                probe = Operation(),
                progress = SetupProgress(),
                onFind = { _, _ -> },
                onInstall = { _, _, _, _ -> },
                onDone = {},
            )
        }
    }
}

@Preview(name = "Setup done · light", widthDp = 412, heightDp = 700)
@Composable
internal fun SetupDonePreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            PluginSetupContent(
                reinstall = true,
                savedHost = "192.168.1.30",
                savedPort = 2222,
                savedFingerprint = "",
                folder = "Librillo",
                observed = null,
                probe = Operation(),
                progress = SetupProgress(SetupProgress.Stage.DONE),
                onFind = { _, _ -> },
                onInstall = { _, _, _, _ -> },
                onDone = {},
            )
        }
    }
}

@Preview(name = "Pairing · light", widthDp = 412, heightDp = 1100)
@Preview(
    name = "Pairing · dark",
    widthDp = 412,
    heightDp = 1100,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun PairingPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            ConnectionForm(
                DeviceConfig(
                    host = "192.168.1.23",
                    fingerprint = "SHA256:kE4z1b3Q9mXc7Vp2Rt8Hn5Wq0Yd6Lf1Gs3Jh9Ua2Bo",
                    authentication = "KOReader password-free",
                ),
                busy = false,
                probeStatus =
                    Operation(message = "Fingerprint read; no login or file access was attempted"),
                onProbe = { _, _ -> },
                onBrowse = { _, _, _, _, _ -> },
                onForget = {},
            ) { _, _, _ ->
            }
        }
    }
}

@Preview(name = "Pairing first run · light", widthDp = 412, heightDp = 1100)
@Composable
internal fun PairingFirstRunPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            ConnectionForm(DeviceConfig(), busy = false, onProbe = { _, _ -> }) { _, _, _ -> }
        }
    }
}

private val sampleStatistics =
    DeviceStatistics(
        "device",
        totalSeconds = 12 * 3600 + 4 * 60,
        exportedAt = 1791750000L,
        fetchedAt = 1791750100L,
        days =
            """[{"date":"2026-10-14","seconds":2700},{"date":"2026-10-13","seconds":1500},{"date":"2026-10-12","seconds":4200}]""",
    )

@Preview(name = "Settings · light", widthDp = 412, heightDp = 1500)
@Preview(
    name = "Settings · dark",
    widthDp = 412,
    heightDp = 1500,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun SettingsPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            SettingsScreen(
                appearance = "System",
                dynamic = true,
                onTheme = {},
                onDynamic = {},
                reading = ReadingDefaults(),
                statistics = sampleStatistics,
                hardcoverAutomatic = false,
                hardcoverUsername = "alex",
                count = 9,
                recordCount = 12,
                removedCount = 3,
                sshHost = "192.168.1.23",
                phoneFolder = "Internal storage/Books",
            )
        }
    }
}

@Preview(name = "Settings first run · light", widthDp = 412, heightDp = 1500)
@Composable
internal fun SettingsFirstRunPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            SettingsScreen(appearance = "Light", dynamic = false, onTheme = {}, onDynamic = {})
        }
    }
}

@Preview(name = "Text defaults · light", widthDp = 412, heightDp = 700)
@Preview(
    name = "Text defaults · dark",
    widthDp = 412,
    heightDp = 700,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun ReadingDefaultsPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ReadingDefaultsContent(ReadingDefaults(), onReading = {})
        }
    }
}

@Preview(name = "Reading time · light", widthDp = 412, heightDp = 500)
@Composable
internal fun ReadingTimePreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ReadingTimeContent(sampleStatistics)
        }
    }
}

@Preview(name = "Backup · light", widthDp = 412, heightDp = 320)
@Preview(
    name = "Backup · dark",
    widthDp = 412,
    heightDp = 320,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun BackupPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            BackupContent(onExport = {}, onRestore = {}, busy = false)
        }
    }
}

@Preview(name = "Books folder · light", widthDp = 412, heightDp = 800)
@Composable
internal fun BooksFolderPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            BooksFolderContent(
                "Phone storage/Books",
                hasAccess = true,
                onAllow = {},
                onChange = {},
                pending = 12,
                inbox = "Books/Inbox",
                lastScanAt = System.currentTimeMillis() - 5 * 60_000L,
                lastScanMessage = "Added 2 books from the Inbox. 1 was already in your library.",
            )
        }
    }
}

@Preview(name = "Phone folder · light", widthDp = 412, heightDp = 360)
@Composable
internal fun PhoneFolderPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            PhoneFolderContent(
                "Internal storage/Books",
                onChoose = {},
                onRefresh = {},
                onForget = {},
                busy = false,
            )
        }
    }
}

@Preview(name = "Hardcover account · light", widthDp = 412, heightDp = 420)
@Preview(
    name = "Hardcover account · dark",
    widthDp = 412,
    heightDp = 420,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun HardcoverAccountPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            HardcoverAccountContent(
                username = "",
                busy = false,
                error = null,
                onGetToken = {},
                onConnect = {},
                onDisconnect = {},
            )
        }
    }
}

@Preview(name = "Bottom bar · light", widthDp = 412, heightDp = 360)
@Preview(
    name = "Bottom bar · dark",
    widthDp = 412,
    heightDp = 360,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun BarPreview() {
    FolioTheme(dynamic = false) {
        Surface {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FolioBar(
                    tab = 0,
                    onTab = {},
                    current = CurrentBook(sampleBooks[0], 0.37, 1791750000000),
                    onContinue = {},
                    onOptions = {},
                    deviceBadge = false,
                    hazeState = null,
                )
                FolioBar(
                    tab = 2,
                    onTab = {},
                    current = null,
                    onContinue = {},
                    onOptions = {},
                    deviceBadge = true,
                    hazeState = null,
                )
                SelectionActionBar(
                    enabled = true,
                    onLists = {},
                    onStatus = {},
                    onRemove = {},
                )
            }
        }
    }
}

@Preview(name = "Metadata lookup · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Metadata lookup · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun LookupPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            MetadataLookupContent(
                sampleBooks[0],
                emptyList(),
                MetadataProposal(
                    sampleBooks[0]
                        .metadata()
                        .copy(
                            title = "The Orchard: A Year",
                            publisher = "Example Editions",
                            description = "A revised description from the selected edition.",
                            identifiers = listOf("isbn:9780000000001"),
                        ),
                    12345L,
                ),
                false,
                {},
                {},
                { _, _ -> },
            )
        }
    }
}

@Preview(name = "Metadata lookup results · light", widthDp = 412, heightDp = 600)
@Composable
internal fun LookupResultsPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            MetadataLookupContent(
                sampleBooks[0],
                listOf(
                    MetadataCandidate("/works/OL1W", "The Orchard", listOf("Alex Rowan"), 1L, null),
                    MetadataCandidate(
                        "/works/OL2W",
                        "The Orchard Keeper",
                        listOf("Alex Rowan"),
                        2L,
                        "/books/OL2M",
                    ),
                ),
                null,
                false,
                {},
                {},
                { _, _ -> },
            )
        }
    }
}

private val hardcoverEditions =
    listOf(
        HardcoverEdition(10, "The Orchard", 320, "Paperback · 2019"),
        HardcoverEdition(11, "The Orchard", 288, "Hardcover · 2017"),
    )

@Preview(name = "Hardcover · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Hardcover · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun HardcoverReadingPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            HardcoverReadingContent(
                book = sampleBooks[0].copy(currentPath = "orchard.epub", currentHash = "h1"),
                username = "alex",
                busy = false,
                error = null,
                reading = HardcoverReading(1, 2, 7, 96, 10),
                editions = hardcoverEditions,
                matches = emptyList(),
                snapshot =
                    ReadingSnapshot(
                        "sample1",
                        "kindle",
                        0.41,
                        "",
                        "reading",
                        0,
                        1791750000000,
                        "",
                        "",
                        "h1",
                    ),
                phonePercent = 0.37,
                reports = emptyList(),
                onConnect = {},
                onRefresh = {},
                onSearch = {},
                onLink = {},
                onMarkReading = {},
                onSend = { _, _, _ -> },
            )
        }
    }
}

@Preview(name = "Hardcover link · light", widthDp = 412, heightDp = 600)
@Composable
internal fun HardcoverLinkPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            HardcoverReadingContent(
                book = sampleBooks[0],
                username = "alex",
                busy = false,
                error = null,
                reading = null,
                editions = emptyList(),
                matches =
                    listOf(
                        HardcoverMatch(1, "The Orchard", listOf("Alex Rowan"), "the-orchard"),
                        HardcoverMatch(2, "Orchard Days", listOf("A. Rowan"), "orchard-days"),
                    ),
                snapshot = null,
                phonePercent = null,
                reports = emptyList(),
                onConnect = {},
                onRefresh = {},
                onSearch = {},
                onLink = {},
                onMarkReading = {},
                onSend = { _, _, _ -> },
            )
        }
    }
}

@Preview(name = "Hardcover not connected · light", widthDp = 412, heightDp = 400)
@Composable
internal fun HardcoverConnectPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            HardcoverReadingContent(
                book = sampleBooks[0],
                username = "",
                busy = false,
                error = null,
                reading = null,
                editions = emptyList(),
                matches = emptyList(),
                snapshot = null,
                phonePercent = null,
                reports = emptyList(),
                onConnect = {},
                onRefresh = {},
                onSearch = {},
                onLink = {},
                onMarkReading = {},
                onSend = { _, _, _ -> },
            )
        }
    }
}

private val hardcoverLibrary =
    listOf(
        HardcoverLibraryBook(
            1,
            HardcoverMatch(1, "The Orchard", listOf("Alex Rowan"), "the-orchard"),
            2,
            96,
            10,
        ),
        HardcoverLibraryBook(
            2,
            HardcoverMatch(2, "Letters from the Coast", listOf("Morgan Wells"), "letters"),
            3,
            null,
            null,
        ),
        HardcoverLibraryBook(
            3,
            HardcoverMatch(3, "The Lighthouse at the End of the World", listOf("Jules Verne"), "l"),
            1,
            null,
            null,
        ),
    )

@Preview(name = "Hardcover import · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Hardcover import · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun HardcoverImportPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            HardcoverImportContent(
                rows = hardcoverLibrary,
                more = false,
                busy = false,
                error = null,
                books = sampleBooks,
                wishes = emptyList(),
                selected = setOf(1, 3),
                targets = mapOf(1 to sampleBooks[0].id),
                query = "",
                onQuery = {},
                onToggle = { _, _ -> },
                onSelectAll = {},
                onRefresh = {},
                onMatch = {},
                onUnmatch = {},
                onLoadMore = {},
                onImport = {},
            )
        }
    }
}

@Preview(name = "Hardcover search · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Hardcover search · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun HardcoverSearchPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            HardcoverBookSearchContent(
                query = "The Orchard",
                onQuery = {},
                busy = false,
                error = null,
                results = hardcoverLibrary.map { it.book },
                proposal = sampleBooks[0],
                onSearch = {},
                onSelect = {},
                onAdd = {},
                onSave = {},
            )
        }
    }
}

@Preview(name = "Scan a cover · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Scan a cover · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun DiscoveryPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            DiscoveryContent(
                query = "THE ORCHARD\nAlex Rowan",
                onQuery = {},
                candidates =
                    listOf(
                        MetadataCandidate(
                            "/works/OL1W",
                            "The Orchard",
                            listOf("Alex Rowan"),
                            1L,
                            null,
                        ),
                        MetadataCandidate(
                            "/works/OL2W",
                            "The Orchard Keeper",
                            listOf("Cormac McCarthy"),
                            2L,
                            null,
                        ),
                    ),
                busy = false,
                error = null,
                hardcover = true,
                onBack = {},
                onCamera = {},
                onPhoto = {},
                onFind = {},
                onHardcover = {},
                onChoose = {},
            )
        }
    }
}

@Preview(name = "Import results · light", widthDp = 412, heightDp = 480)
@Preview(
    name = "Import results · dark",
    widthDp = 412,
    heightDp = 480,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun ImportResultsPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ImportResultsContent(
                listOf(
                    ImportOutcome("content://1", "The Orchard.epub", "Imported", "sample1", null),
                    ImportOutcome(
                        "content://2",
                        "Letters.epub",
                        "Already in your library, or restored",
                        "sample2",
                        null,
                    ),
                    ImportOutcome(
                        "content://3",
                        "Broken.epub",
                        "Not imported",
                        null,
                        "Not a readable EPUB ZIP archive. Download another copy.",
                    ),
                    ImportOutcome(
                        "content://4",
                        "Gone.epub",
                        "Not imported",
                        null,
                        "File is missing or unavailable.",
                    ),
                ),
                busy = false,
                onRetry = {},
                onOpen = {},
            )
        }
    }
}

@Preview(name = "Recently removed · light", widthDp = 412, heightDp = 400)
@Preview(
    name = "Recently removed · dark",
    widthDp = 412,
    heightDp = 400,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun RemovedPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            RemovedBooksContent(
                listOf(
                    RemovedEntry("r1", "g1", "Letters from the Coast", "EPUB", 1791750000000),
                    RemovedEntry("r2", "g2", "Winter Light", "Catalogue", 1791740000000),
                ),
                busy = false,
                onRestore = {},
                onDelete = {},
            )
        }
    }
}

@Preview(name = "Recently removed empty · light", widthDp = 412, heightDp = 480)
@Composable
internal fun RemovedEmptyPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            RemovedBooksContent(emptyList(), busy = false, onRestore = {}, onDelete = {})
        }
    }
}
