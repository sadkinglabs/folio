package dev.sadking.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.data.Book
import dev.sadking.folio.ui.*
import org.junit.Rule
import org.junit.Test

/** A transfer in progress blocks device actions only; the book stays readable and editable. */
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
    fun transferInProgressKeepsReadingAndProgressAvailable() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                BookDetails(book, busy = false, deviceBusy = true, fileMissing = false)
            }
        }
        compose.onNodeWithTag(BOOK_PRIMARY_TAG).assertTextEquals("Read").assertIsEnabled()
        compose.onNodeWithTag(BOOK_ACTION_EDIT_TAG).assertIsEnabled()
        compose.onNodeWithTag(BOOK_ACTION_HARDCOVER_TAG).assertIsEnabled()
        compose.onNodeWithTag(BOOK_ACTION_SEND_TAG).assertIsNotEnabled()
        // The e-reader refresh lives in the Progress sheet and waits for the transfer too.
        compose.onNodeWithTag(BOOK_ACTION_PROGRESS_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(PROGRESS_SHEET_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription("Refresh from e-reader").assertIsNotEnabled()
    }

    @Test
    fun libraryOperationBlocksChangesButNeverReading() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                BookDetails(book, busy = true, deviceBusy = true, fileMissing = false)
            }
        }
        compose.onNodeWithTag(BOOK_PRIMARY_TAG).assertIsEnabled()
        compose.onNodeWithTag(BOOK_ACTION_EDIT_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(BOOK_ACTION_REMOVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(BOOK_ACTION_SHARE_TAG).assertIsNotEnabled()
    }
}
