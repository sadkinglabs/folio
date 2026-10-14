package dev.sadking.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.data.Book
import dev.sadking.folio.ui.BookDetails
import dev.sadking.folio.ui.FolioTheme
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
                BookDetails(
                    book,
                    emptyList(),
                    emptySet(),
                    onDevice = false,
                    onShelf = { _, _ -> },
                    onStatus = {},
                    onSync = {},
                    busy = false,
                    deviceBusy = true,
                )
            }
        }
        compose.onNodeWithText("Read").assertIsEnabled()
        compose.onNodeWithText("Update progress").assertIsEnabled()
        compose.onNodeWithText("Send to device").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Refresh from Kindle").assertIsNotEnabled()
    }
}
