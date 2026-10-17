package dev.sadking.folio.reader

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.sadking.folio.data.PhoneBookmark
import dev.sadking.folio.ui.FolioTheme

/** Previews of M11's reading comfort and bookmarks, over the same stand-in page as the reader's. */
private val sampleComfort =
    ReaderComfort(
        autoBrightness = false,
        brightness = 0.35f,
        warmth = 0.4f,
        tapZones = TapZones.HALVES,
        leftHanded = true,
    )

/** The Text sheet with its Screen and Turning pages groups under the text settings. */
@Preview(name = "Reader text and comfort · light", widthDp = 412, heightDp = 1800)
@Composable
internal fun ReaderTextComfortPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ReaderTextSheet(
                ReaderTextState(fontSize = 120.0, pageTheme = "Paper"),
                ReaderTextActions({}, {}, {}, {}, {}, {}, {}, {}),
                more = { ReaderComfortSection(sampleComfort, scrolling = false, onChange = {}) },
            )
        }
    }
}

/** The comfort groups alone, as a scrolling book shows them: tap choices dimmed. */
@Preview(name = "Reader comfort · scrolling", widthDp = 412, heightDp = 900)
@Composable
internal fun ReaderComfortScrollingPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ReaderComfortSection(ReaderComfort(), scrolling = true, onChange = {})
        }
    }
}

/**
 * A bookmarked page at half warmth with the controls showing: the ribbon on the page, the top bar's
 * ribbon filled, and the bars themselves untinted.
 */
@Preview(name = "Reader bookmarked, warm · light", widthDp = 412, heightDp = 892)
@Composable
internal fun ReaderBookmarkedWarmPreview() {
    val dark = isSystemInDarkTheme()
    FolioTheme(dynamic = false) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(top = 24.dp)) {
                ReaderPagePlaceholder(dark)
                BookmarkRibbon(dark, Modifier.align(Alignment.TopEnd).padding(end = 20.dp))
            }
            WarmthOverlay(0.5f, Modifier.matchParentSize())
            ReaderTopBar(
                null,
                "The Orchard",
                enabled = true,
                backEnabled = true,
                insets = WindowInsets(top = 24.dp),
                onBack = {},
                onPanel = {},
                modifier = Modifier.align(Alignment.TopCenter),
                onListen = {},
                bookmarked = true,
                onBookmark = {},
            )
        }
    }
}

/** A bookmarked Paper page at full warmth, controls hidden. */
@Preview(name = "Reader bookmarked, warm · paper", widthDp = 412, heightDp = 892)
@Composable
internal fun ReaderBookmarkedPaperPreview() {
    FolioTheme(dynamic = false) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(top = 24.dp)) {
                ReaderPagePlaceholder(dark = false, paper = true)
                BookmarkRibbon(false, Modifier.align(Alignment.TopEnd).padding(end = 20.dp))
            }
            WarmthOverlay(1f, Modifier.matchParentSize())
        }
    }
}

private val renamedBookmarks =
    listOf(
        PhoneBookmark("b1", "sample1", "", "{}", "Where Ada starts pruning", 0),
        PhoneBookmark("b2", "sample1", "", "{}", "Chapter three · 31%", 0),
    )

/** The Bookmarks tab, each row with Rename and Delete. */
@Preview(name = "Reader bookmarks · light", widthDp = 412, heightDp = 420)
@Composable
internal fun ReaderBookmarksPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ReaderContentsSheet(
                emptyList(),
                -1,
                renamedBookmarks,
                {},
                {},
                {},
                initialTab = 1,
            )
        }
    }
}
