package dev.sadking.folio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.sadking.folio.data.*
import dev.sadking.folio.device.DeviceConfig
import dev.sadking.folio.link.LinkLog

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
            LibraryScreen(
                unifiedLibrary(sampleBooks, listOf(wish)),
                emptySet(),
                onOpen = {},
                onSelect = {},
                fileIds = sampleBooks.map { it.id }.toSet(),
                signals = mapOf(wish.id to catalogueSignals(wish, null)),
                progress = mapOf(sampleBooks.first().id to 0.37),
                currentId = sampleBooks.first().id,
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
                signals = BookSignals("On Kindle", "Reading · phone", 0.41, null),
            )
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
                onSend = {},
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
                onCreate = { _, _, _, _ -> },
                onSave = {},
                onDelete = {},
                onDismiss = {},
            )
        }
    }
}

@Preview(name = "Device · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Device · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun KindlePreview() {
    val now = System.currentTimeMillis()
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            DeviceScreen(
                status = LinkStatus.ON,
                addresses = listOf("192.168.1.42:8765"),
                onLink = {},
                onCopy = {},
                onSignIn = {},
                ereaders =
                    listOf(
                        EreaderSeen(
                            "kindle-pw5",
                            "Kindle",
                            now - 3 * 60_000,
                            EreaderPosition(sampleBooks[0], 0.41),
                        ),
                        EreaderSeen("kobo-clara", "", now - 2 * 86_400_000, null),
                    ),
                queue = listOf(QueuedBook(sampleBooks[1], false), QueuedBook(sampleBooks[2], true)),
                onOpenBook = {},
                onRemoveQueued = {},
                activity =
                    listOf(
                        LinkLog.Entry(
                            now - 3 * 60_000,
                            LinkLog.Kind.REQUEST,
                            "/syncs/progress",
                            200,
                            "192.168.1.17",
                        ),
                        LinkLog.Entry(
                            now - 4 * 60_000,
                            LinkLog.Kind.REQUEST,
                            "/users/auth",
                            200,
                            "192.168.1.17",
                        ),
                        LinkLog.Entry(now - 50 * 60_000, LinkLog.Kind.SCREEN_ON),
                        LinkLog.Entry(now - 3 * 3_600_000, LinkLog.Kind.SCREEN_OFF),
                        LinkLog.Entry(now - 5 * 3_600_000, LinkLog.Kind.STARTED, port = 8765),
                    ),
                onClearActivity = {},
                onSavePlugin = {},
            )
        }
    }
}

@Preview(name = "Device off · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Device off · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun DeviceOffPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            DeviceScreen(
                status = LinkStatus.OFF,
                addresses = emptyList(),
                onLink = {},
                onCopy = {},
                onSignIn = {},
                ereaders = emptyList(),
                queue = emptyList(),
                onOpenBook = {},
                onRemoveQueued = {},
                activity = emptyList(),
                onClearActivity = {},
                onSavePlugin = {},
                sshHost = "192.168.1.42",
                syncJob =
                    SyncJob(
                        "job",
                        "ssh",
                        "PAUSED",
                        totalBooks = 3,
                        totalBytes = 6_000_000,
                        completedBooks = 1,
                        bytesSent = 2_000_000,
                        currentTitle = sampleBooks[1].title,
                    ),
            )
        }
    }
}

@Preview(name = "Device first run · light", widthDp = 412, heightDp = 892)
@Composable
internal fun DeviceFirstRunPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            DeviceScreen(
                status = LinkStatus.ON,
                addresses = listOf("192.168.1.42:8765"),
                onLink = {},
                onCopy = {},
                onSignIn = {},
                ereaders = emptyList(),
                queue = emptyList(),
                onOpenBook = {},
                onRemoveQueued = {},
                activity =
                    listOf(
                        LinkLog.Entry(
                            System.currentTimeMillis(),
                            LinkLog.Kind.STARTED,
                            port = 8765,
                        )
                    ),
                onClearActivity = {},
                onSavePlugin = {},
            )
        }
    }
}

@Preview(name = "Pairing · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Pairing · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun PairingPreview() {
    FolioTheme(dynamic = false) { Surface { ConnectionForm(DeviceConfig(), false) { _, _, _ -> } } }
}

@Preview(name = "Bottom bar · light", widthDp = 412, heightDp = 240)
@Preview(
    name = "Bottom bar · dark",
    widthDp = 412,
    heightDp = 240,
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
                    transferActive = false,
                    hazeState = null,
                )
                FolioBar(
                    tab = 2,
                    onTab = {},
                    current = null,
                    onContinue = {},
                    onOptions = {},
                    transferActive = true,
                    hazeState = null,
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
        Surface {
            MetadataLookupSheet(
                sampleBooks[0],
                emptyList(),
                MetadataProposal(
                    sampleBooks[0]
                        .metadata()
                        .copy(
                            publisher = "Example Editions",
                            description = "A revised description from the selected edition.",
                        ),
                    null,
                ),
                false,
                {},
                {},
                { _, _ -> },
                {},
            )
        }
    }
}
