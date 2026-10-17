package dev.sadking.folio.acquire

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.sadking.folio.ui.FolioTheme
import dev.sadking.folio.ui.GetOnlineRow

private val sampleSources =
    BookSources.BUILT_IN.mapIndexed { i, s -> if (i == 2) s.copy(hidden = true) else s } +
        BookSource(
            id = "user-sample",
            name = "Village library",
            startUrl = "https://books.example.org/",
            searchUrl = "https://books.example.org/search?q={query}",
        )

/** A stand-in for a web page: the WebView doesn't draw in previews. */
@Composable
private fun SamplePage(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Pride and Prejudice", style = MaterialTheme.typography.headlineSmall)
            Text("Jane Austen", style = MaterialTheme.typography.titleMedium)
            Text(
                "Download for ereaders: compatible epub, advanced epub, azw3, kepub.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Frame(download: DownloadState) {
    FolioTheme(dynamic = false) {
        Surface {
            BrowserFrame(
                source = BookSources.BUILT_IN[0],
                host = "standardebooks.org",
                loading = 100,
                others = BookSources.BUILT_IN.drop(1),
                searching = true,
                download = download,
                snackbar = remember { SnackbarHostState() },
                onBack = {},
                onClose = {},
                onOther = {},
                onStop = {},
                onDone = {},
                onKeepBrowsing = {},
                page = { SamplePage(it) },
            )
        }
    }
}

@Preview(name = "Book sources", widthDp = 412, heightDp = 892)
@Composable
internal fun AcquireSourcesPreview() {
    FolioTheme(dynamic = false) {
        Surface { SourcesScreen(sampleSources, {}, { _, _ -> }, {}, {}, {}, {}) }
    }
}

@Preview(name = "Site editor", widthDp = 412, heightDp = 892)
@Composable
internal fun AcquireSourceEditorPreview() {
    FolioTheme(dynamic = false) { Surface { SourceEditor(sampleSources.last(), {}, {}) } }
}

@Preview(name = "Get a book", widthDp = 412, heightDp = 892)
@Composable
internal fun AcquireFindPreview() {
    FolioTheme(dynamic = false) {
        Surface {
            FindStartScreen(
                query = "Pride and Prejudice Jane Austen",
                onQuery = {},
                sources = sampleSources.filter { !it.hidden },
                onOpen = {},
                onManage = {},
                onClose = {},
            )
        }
    }
}

@Preview(name = "Browser · downloading", widthDp = 412, heightDp = 892)
@Composable
internal fun AcquireDownloadingPreview() =
    Frame(
        DownloadState.Running(
            "jane-austen_pride-and-prejudice.epub",
            1_450_000,
            3_200_000,
        )
    )

@Preview(name = "Browser · saved", widthDp = 412, heightDp = 892)
@Composable
internal fun AcquireSavedPreview() =
    Frame(
        DownloadState.Finished(
            AcquireOutcome.Saved("b1", "Pride and Prejudice", attached = true),
            ereader = true,
        )
    )

@Preview(name = "Browser · saved, no e-reader", widthDp = 412, heightDp = 892)
@Composable
internal fun AcquireSavedNoReaderPreview() =
    Frame(
        DownloadState.Finished(
            AcquireOutcome.Saved("b1", "Pride and Prejudice", attached = false),
            ereader = false,
        )
    )

@Preview(name = "Browser · protected", widthDp = 412, heightDp = 892)
@Composable
internal fun AcquireProtectedPreview() =
    Frame(DownloadState.Finished(AcquireOutcome.Protected, ereader = true))

@Preview(name = "Add sheet · Get a book online", widthDp = 412, heightDp = 160)
@Composable
internal fun AcquireAddRowPreview() {
    FolioTheme(dynamic = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) { GetOnlineRow {} }
    }
}
