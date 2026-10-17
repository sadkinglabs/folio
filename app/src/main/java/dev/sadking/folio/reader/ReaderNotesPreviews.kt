package dev.sadking.folio.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.sadking.folio.data.BookAnnotation
import dev.sadking.folio.data.PhoneBookmark
import dev.sadking.folio.ui.FolioTheme
import java.time.ZoneOffset

private fun sampleAnnotation(
    id: String,
    text: String,
    chapter: String,
    fragment: Int,
    note: String = "",
    color: String = "",
    drawer: String = "lighten",
    kind: String = if (note.isEmpty()) "highlight" else "note",
    device: String = "link:kindle",
) =
    BookAnnotation(
        id = id,
        bookId = "sample1",
        deviceId = device,
        text = text,
        note = note,
        chapter = chapter,
        date = "2026-10-1$fragment 08:41:12",
        position = "/body/DocFragment[$fragment]/body/p[2]/text().0",
        endPosition = "/body/DocFragment[$fragment]/body/p[2]/text().40",
        kind = kind,
        color = color,
        drawer = drawer,
    )

/** A book's highlights, notes and bookmarks, from the phone and the Kindle, for previews. */
internal val sampleNotes: List<NoteItem>
    get() =
        noteItems(
            listOf(
                sampleAnnotation(
                    "h1",
                    "In March the pruning began. Ada worked from the east end, where the frost " +
                        "lingered longest, and the old trees took the whole of the first week.",
                    "Chapter one",
                    2,
                    color = "yellow",
                    device = PHONE_ANNOTATION_DEVICE,
                ),
                sampleAnnotation(
                    "h2",
                    "The orchard had been planted by her grandfather.",
                    "Chapter two",
                    3,
                    note = "Same order as the vineyard chapter.",
                    color = "green",
                ),
                sampleAnnotation(
                    "h3",
                    "Nobody spoke of the winter after that.",
                    "Chapter two",
                    3,
                    drawer = "underscore",
                ),
                sampleAnnotation(
                    "k1",
                    "",
                    "Chapter three",
                    4,
                    note = "Come back to the letter.",
                    kind = "bookmark",
                    drawer = "",
                ),
            ),
            listOf(
                placed(
                    PhoneBookmark(
                        "b1",
                        "sample1",
                        "",
                        "{}",
                        "Where Ada starts pruning",
                        1_792_000_000_000,
                    )
                )
            ),
            emptyList(),
            ZoneOffset.UTC,
        )

/** Highlights & notes: every kind of row, in reading order. */
@Preview(name = "Reader notes · light", widthDp = 412, heightDp = 760)
@Composable
internal fun ReaderNotesPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ReaderContentsSheet(
                emptyList(),
                -1,
                sampleNotes,
                {},
                export = ExportActions(folder = "Obsidian"),
                initialTab = 1,
            )
        }
    }
}

/** Highlights & notes filtered to Bookmarks: the phone's and the Kindle's. */
@Preview(name = "Reader notes bookmarks · light", widthDp = 412, heightDp = 480)
@Composable
internal fun ReaderNotesBookmarksPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ReaderContentsSheet(
                emptyList(),
                -1,
                sampleNotes,
                {},
                initialTab = 1,
                initialFilter = NotesFilter.BOOKMARKS,
            )
        }
    }
}

/** Highlights & notes before the owner marks anything. */
@Preview(name = "Reader notes empty · light", widthDp = 412, heightDp = 360)
@Composable
internal fun ReaderNotesEmptyPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ReaderContentsSheet(emptyList(), -1, emptyList(), {}, initialTab = 1)
        }
    }
}
