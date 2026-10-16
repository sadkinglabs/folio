package dev.sadking.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.data.Book
import dev.sadking.folio.ui.*
import org.junit.Rule
import org.junit.Test

/** A running library operation blocks changes only; the book stays readable. There is no Send. */
class BookDetailsBusyUiTest {
    @get:Rule val compose = createComposeRule()
    private val book =
        Book(
            "book",
            "Library book",
            "[]",
            originalPath = "original",
            currentPath = "original",
            originalHash = "a",
            currentHash = "a",
        )

    @Test
    fun idlePageOffersReadingAndProgressWithoutSendOrSshRefresh() {
        compose.setContent {
            FolioTheme(dynamic = false) { BookDetails(book, busy = false, fileMissing = false) }
        }
        compose.onNodeWithTag(BOOK_PRIMARY_TAG).assertTextEquals("Read").assertIsEnabled()
        compose.onNodeWithTag(BOOK_ACTION_EDIT_TAG).assertIsEnabled()
        compose.onNodeWithTag(BOOK_ACTION_HARDCOVER_TAG).assertIsEnabled()
        // Sync puts every book on the e-reader: no Send, and no fetching over SSH.
        compose.onNodeWithText("Send").assertDoesNotExist()
        compose.onNodeWithTag(BOOK_ACTION_PROGRESS_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(PROGRESS_SHEET_TAG).assertIsDisplayed()
        // The book page behind the sheet says "Not opened" too: check the sheet's own line.
        compose
            .onNode(hasText("Not opened") and hasAnyAncestor(hasTestTag(PROGRESS_SHEET_TAG)))
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Refresh from e-reader").assertDoesNotExist()
    }

    @Test
    fun libraryOperationBlocksChangesButNeverReading() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                BookDetails(book, busy = true, fileMissing = false)
            }
        }
        compose.onNodeWithTag(BOOK_PRIMARY_TAG).assertIsEnabled()
        compose.onNodeWithTag(BOOK_ACTION_EDIT_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(BOOK_ACTION_REMOVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(BOOK_ACTION_SHARE_TAG).assertIsNotEnabled()
    }
}
