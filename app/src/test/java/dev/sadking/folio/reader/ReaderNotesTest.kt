package dev.sadking.folio.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.core.NotePlace
import dev.sadking.folio.data.BookAnnotation
import dev.sadking.folio.data.HighlightColour
import dev.sadking.folio.data.PhoneBookmark
import dev.sadking.folio.ui.FolioTheme
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Highlights & notes (M11 C) on the JVM with Robolectric, not on a device: the rows, their order,
 * filter and search, the jump callback, the row menu, and delete with Undo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ReaderNotesTest {
    @get:Rule val compose = createComposeRule()

    private fun row(
        id: String,
        text: String,
        fragment: Int,
        note: String = "",
        kind: String = if (note.isEmpty()) "highlight" else "note",
        color: String = "",
        paragraph: Int = 1,
        deletedAt: Long = 0,
    ) =
        BookAnnotation(
            id,
            "b1",
            "link:kindle",
            text,
            note,
            "Chapter $fragment",
            "2026-10-16 08:41:12",
            "/body/DocFragment[$fragment]/body/p[$paragraph]/text().0",
            "/body/DocFragment[$fragment]/body/p[$paragraph]/text().9",
            kind,
            color = color,
            drawer = "lighten",
            deletedAt = deletedAt,
        )

    private val highlights =
        listOf(
            row("late", "The café by the river.", 4, color = "green"),
            row(
                "noted",
                "Ada pruned the old trees.",
                2,
                note = "Like the vineyard",
                paragraph = 10,
            ),
            row("early", "In March the pruning began.", 2, color = "yellow", paragraph = 9),
            row("mark", "", 3, note = "Come back", kind = "bookmark"),
        )

    private val phone =
        placed(
            PhoneBookmark(
                "phone",
                "b1",
                "rev",
                """{"href":"/OEBPS/three.xhtml","type":"application/xhtml+xml",""" +
                    """"locations":{"progression":0.0,"totalProgression":0.5}}""",
                "Where Ada starts",
                0,
            )
        )

    private val spine = listOf("OEBPS/cover.xhtml", "OEBPS/one.xhtml", "OEBPS/three.xhtml")

    private val items
        get() = noteItems(highlights, listOf(phone), spine, ZoneOffset.UTC)

    @Test
    fun rowsAreInReadingOrderWithBookmarksInTheirChapter() {
        // The phone bookmark is in the third spine item, DocFragment[3], with the Kindle's.
        assertEquals(listOf("early", "noted", "phone", "mark", "late"), items.map { it.id })
        assertEquals(NotePlace(3, progression = 0.0), items[2].place)
        assertEquals("Chapter 3", items.first { it.id == "mark" }.passage)
        // Deleted highlights never show.
        val withDeleted = highlights + row("gone", "Gone", 2, deletedAt = 1)
        assertFalse(noteItems(withDeleted, emptyList(), spine).any { it.id == "gone" })
    }

    @Test
    fun filtersPickHighlightsNotesOrBookmarks() {
        fun ids(filter: NotesFilter) = filterNotes(items, filter, "").map { it.id }
        assertEquals(5, ids(NotesFilter.ALL).size)
        assertEquals(listOf("early", "noted", "late"), ids(NotesFilter.HIGHLIGHTS))
        // A note is any row with a note: the Kindle's bookmark with one counts.
        assertEquals(listOf("noted", "mark"), ids(NotesFilter.NOTES))
        assertEquals(listOf("phone", "mark"), ids(NotesFilter.BOOKMARKS))
    }

    @Test
    fun searchLooksInPassagesAndNotesWithoutCaseOrAccents() {
        fun ids(query: String, filter: NotesFilter = NotesFilter.ALL) =
            filterNotes(items, filter, query).map { it.id }
        assertEquals(listOf("late"), ids("CAFE"))
        assertEquals(listOf("late"), ids("café"))
        assertEquals(listOf("noted"), ids("vineyard"))
        // Every word must match, in any order.
        assertEquals(listOf("noted"), ids("trees ada"))
        assertEquals(listOf("early", "noted"), ids("prun"))
        assertEquals(listOf("phone"), ids("starts", NotesFilter.BOOKMARKS))
        assertEquals(emptyList<String>(), ids("starts", NotesFilter.HIGHLIGHTS))
        assertEquals(5, ids("   ").size)
    }

    @Test
    fun aDayIsShownInThePhonesLanguage() {
        assertEquals("16/10/2026", noteDay("2026-10-16 08:41:12"))
        assertEquals("sometime", noteDay("sometime"))
    }

    @Test
    fun rowsShowPassageNoteChapterAndDayAndJump() {
        val opened = mutableListOf<String>()
        compose.setContent {
            FolioTheme(dynamic = false) {
                ReaderNotesList(items, NotesActions(onOpen = { opened += it.id }), ExportActions())
            }
        }
        compose.onNodeWithText("Ada pruned the old trees.").assertIsDisplayed()
        compose.onNodeWithText("Like the vineyard").assertIsDisplayed()
        compose.onAllNodesWithText("Chapter 2 · 16/10/2026").assertCountEquals(2)
        compose.onNodeWithText("All · 5").assertIsSelected()
        compose.onNodeWithContentDescription("Green", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("In March the pruning began.").performClick()
        compose.onNodeWithText("Where Ada starts").performClick()
        assertEquals(listOf("early", "phone"), opened)
    }

    @Test
    fun chipsAndSearchNarrowTheList() {
        compose.setContent {
            FolioTheme(dynamic = false) { ReaderNotesList(items, NotesActions(), ExportActions()) }
        }
        compose.onNodeWithText("Bookmarks · 2").performClick()
        compose.onNodeWithText("Bookmarks · 2").assertIsSelected()
        compose.onAllNodesWithText("In March the pruning began.").assertCountEquals(0)
        compose.onNodeWithText("Where Ada starts").assertIsDisplayed()
        compose.onNodeWithText("Highlights · 3").performClick()
        compose.onNodeWithTag(READER_NOTES_SEARCH_TAG).performTextInput("river")
        compose.onNodeWithText("The café by the river.").assertIsDisplayed()
        compose.onAllNodesWithText("In March the pruning began.").assertCountEquals(0)
        compose.onNodeWithTag(READER_NOTES_SEARCH_TAG).performTextReplacement("nothing like it")
        compose.onNodeWithText("Nothing matches your search.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("In March the pruning began.").assertIsDisplayed()
    }

    @Test
    fun anEmptyBookSaysHowToAddAndExportIsOff() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                ReaderNotesList(emptyList(), NotesActions(), ExportActions())
            }
        }
        compose.onNodeWithText("Nothing here yet", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Export").assertIsNotEnabled()
    }

    @Test
    fun theRowMenuRecoloursAndAsksForANote() {
        val colours = mutableListOf<Pair<String, HighlightColour>>()
        val noted = mutableListOf<String>()
        val renamed = mutableListOf<String>()
        compose.setContent {
            FolioTheme(dynamic = false) {
                ReaderNotesList(
                    items,
                    NotesActions(
                        onColour = { row, colour -> colours += row.id to colour },
                        onNote = { noted += it.id },
                        onRename = { renamed += it.id },
                    ),
                    ExportActions(),
                )
            }
        }
        val menus = compose.onAllNodesWithContentDescription("Options")
        menus[0].performClick()
        compose.onNode(hasContentDescription("Yellow") and isSelectable()).assertIsSelected()
        compose.onNodeWithContentDescription("Pink").performClick()
        menus[1].performClick()
        compose.onNodeWithText("Edit note").performClick()
        // The phone bookmark: Rename, and no colours or Copy.
        menus[2].performClick()
        compose.onAllNodesWithText("Copy").assertCountEquals(0)
        compose.onNodeWithText("Rename").performClick()
        assertEquals(listOf("early" to HighlightColour.PINK), colours)
        assertEquals(listOf("noted"), noted)
        assertEquals(listOf("phone"), renamed)
    }

    @Test
    fun deleteOffersUndoAndUndoBringsTheRowBack() {
        var rows by mutableStateOf(highlights)
        var deleted by mutableStateOf<BookAnnotation?>(null)
        compose.setContent {
            val host = remember { SnackbarHostState() }
            FolioTheme(dynamic = false) {
                Box {
                    ReaderNotesList(
                        noteItems(rows, emptyList(), spine),
                        NotesActions(
                            onDelete = { item ->
                                // As the view model does: the row goes, and Undo is offered.
                                deleted = item.annotation
                                rows = rows.filter { it.id != item.id }
                            }
                        ),
                        ExportActions(),
                    )
                    SnackbarHost(host)
                }
                UndoOffer(
                    deleted,
                    "Highlight deleted",
                    "Undo",
                    { host },
                    SnackbarDuration.Indefinite,
                    onUndo = {
                        rows = rows + it
                        deleted = null
                    },
                    onSettled = { deleted = null },
                )
            }
        }
        compose.onAllNodesWithContentDescription("Options")[0].performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onAllNodesWithText("In March the pruning began.").assertCountEquals(0)
        compose.onNodeWithText("Highlight deleted").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("In March the pruning began.").assertIsDisplayed()
        assertNull(deleted)
    }

    @Test
    fun exportMenuSharesSavesAndChangesTheFolder() {
        val done = mutableListOf<String>()
        var folder by mutableStateOf<String?>(null)
        compose.setContent {
            FolioTheme(dynamic = false) {
                ReaderNotesList(
                    items,
                    NotesActions(),
                    ExportActions(
                        folder = folder,
                        onShare = { done += "share" },
                        onSave = { done += "save" },
                        onChooseFolder = { done += "choose" },
                    ),
                )
            }
        }
        compose.onNodeWithContentDescription("Export").performClick()
        compose.onAllNodesWithText("Choose another folder…").assertCountEquals(0)
        compose.onNodeWithText("Save to a folder…").performClick()
        folder = "Obsidian"
        compose.onNodeWithContentDescription("Export").performClick()
        compose.onNodeWithText("Save to Obsidian").performClick()
        compose.onNodeWithContentDescription("Export").performClick()
        compose.onNodeWithText("Choose another folder…").performClick()
        compose.onNodeWithContentDescription("Export").performClick()
        compose.onNodeWithText("Share as Markdown").performClick()
        assertEquals(listOf("save", "save", "choose", "share"), done)
    }

    @Test
    fun theContentsSheetOpensNotesInItsSecondTab() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                ReaderContentsSheet(listOf(ContentsEntry("Chapter one", 0)), 0, items, {})
            }
        }
        compose.onNodeWithText("Chapter one").assertIsDisplayed()
        compose.onNodeWithText("Highlights & notes").performClick()
        compose.onNodeWithTag(READER_NOTES_TAG).assertIsDisplayed()
        compose.onNodeWithText("Where Ada starts").assertIsDisplayed()
    }
}
