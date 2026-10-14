package dev.sadking.folio.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.sadking.folio.data.*
import dev.sadking.folio.device.DeviceConfig

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
private fun LibraryPreview() {
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
private fun DetailsPreview() {
    FolioTheme(dynamic = false) {
        Surface {
            BookDetails(
                sampleBooks[0],
                listOf(Shelf("s1", "Slow Sundays")),
                setOf("s1"),
                true,
                { _, _ -> },
                {},
                {},
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
private fun EditorPreview() {
    FolioTheme(dynamic = false) { Surface { MetadataEditor(sampleBooks[0], false) { _, _ -> } } }
}

@Preview(name = "Collections · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Collections · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun CollectionsPreview() {
    FolioTheme(dynamic = false) {
        Surface {
            CollectionsScreen(
                listOf(Shelf("1", "Slow Sundays"), Shelf("2", "To discover")),
                listOf(Membership("sample1", "1")),
                {},
                {},
                false,
            )
        }
    }
}

@Preview(name = "Kindle · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Kindle · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun KindlePreview() {
    FolioTheme(dynamic = false) {
        Surface {
            KindleScreen(
                DeviceConfig(host = "192.168.1.42"),
                emptyList(),
                emptyList(),
                emptyList(),
                false,
                {},
                {},
                {},
                {},
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
private fun PairingPreview() {
    FolioTheme(dynamic = false) { Surface { ConnectionForm(DeviceConfig(), false) { _, _, _ -> } } }
}

@Preview(name = "Reading · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Reading · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ReadingPreview() {
    FolioTheme(dynamic = false) {
        Surface {
            ReadingScreen(
                sampleBooks,
                listOf(
                    ReadingSnapshot(
                        "sample1",
                        "device",
                        0.42,
                        "/body/p[7]",
                        "reading",
                        4,
                        1791750000000,
                        "book.sdr/metadata.epub.lua",
                        "sidecar-hash",
                        "document-hash",
                    )
                ),
                listOf(
                    BookAnnotation(
                        "sample-note",
                        "sample1",
                        "device",
                        "The season had changed.",
                        "A lovely image.",
                        "Autumn",
                        "2026-10-11",
                        "x",
                        "y",
                        "highlight",
                    )
                ),
                false,
                {},
                {},
                DeviceStatistics(
                    "device",
                    86400,
                    1791750000,
                    1791750000000,
                    "[{\"date\":\"2026-10-11\",\"seconds\":1800},{\"date\":\"2026-10-10\",\"seconds\":2400}]",
                ),
            )
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
private fun LookupPreview() {
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
