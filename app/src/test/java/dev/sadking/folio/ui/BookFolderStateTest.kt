package dev.sadking.folio.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.FolderScanNotes
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class BookFolderStateTest {
    @get:Rule val compose = createComposeRule()

    private val prefs
        get() = FolderScanNotes.preferences(ApplicationProvider.getApplicationContext<Context>())

    private fun book(format: String = "epub", path: String = "/none") =
        Book(
            "b",
            "Title",
            "[\"A\"]",
            originalPath = path,
            currentPath = path,
            originalHash = "h",
            currentHash = "h",
            format = format,
        )

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun aMissingFileSaysSoAndHoldsShare() {
        compose.setContent {
            FolioTheme(dynamic = false) { BookDetails(book(), fileMissing = true) }
        }
        compose.onNodeWithTag(BOOK_FILE_MISSING_TAG).assertExists()
        compose.onNodeWithText("File missing").assertExists()
        compose.onNodeWithTag(BOOK_ACTION_SHARE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(BOOK_ACTION_EDIT_TAG).assertIsEnabled()
    }

    @Test
    fun theDiskDecidesWhenNothingIsPassed() {
        compose.setContent {
            FolioTheme(dynamic = false) { BookDetails(book(path = "/no/such/book.epub")) }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(BOOK_FILE_MISSING_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theChangedNoteShowsUntilTheBookIsOpened() {
        prefs.edit().putStringSet(FolderScanNotes.CHANGED_IDS, setOf("b", "other")).commit()
        var reads = 0
        compose.setContent {
            FolioTheme(dynamic = false) {
                BookDetails(book("pdf"), fileMissing = false, onRead = { reads++ })
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(BOOK_CHANGED_OUTSIDE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("This file was changed outside Librillo").assertExists()
        compose.onNodeWithTag(BOOK_PRIMARY_TAG).performClick()
        compose.runOnIdle { assertEquals(1, reads) }
        assertEquals(setOf("other"), FolderScanNotes.changedIds(prefs))
        compose.onNodeWithTag(BOOK_CHANGED_OUTSIDE_TAG).assertDoesNotExist()
    }
}
