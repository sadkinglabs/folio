package dev.sadking.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.data.*
import dev.sadking.folio.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadingListsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun wantToReadIsPinnedAlongsideUserLists() {
        var opened: ReadingList? = null
        compose.setContent {
            FolioTheme(dynamic = false) {
                CollectionsScreen(
                    emptyList(),
                    emptyList(),
                    {},
                    {},
                    false,
                    onReadingList = { opened = it },
                    readingCounts = mapOf(ReadingList.WANT_TO_READ to 3),
                )
            }
        }
        compose.onNodeWithText("Pinned").assertIsDisplayed()
        compose.onNodeWithText("Want to Read").performClick()
        compose.runOnIdle { assertEquals(ReadingList.WANT_TO_READ, opened) }
        compose.onNodeWithText("All books").assertDoesNotExist()
    }

    @Test
    fun syncControlsAreInTransferSheet() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                CollectionsScreen(
                    listOf(Shelf("shelf", "Summer")),
                    emptyList(),
                    {},
                    {},
                    false,
                    counts = mapOf("shelf" to 2),
                )
            }
        }
        compose.onNodeWithText("Preview transfer").assertDoesNotExist()
        compose.onNodeWithText("Transfer options").performScrollTo().performClick()
        compose.onNodeWithText("Preview transfer").assertIsDisplayed()
        compose.onNodeWithText("Include in one-tap sync").assertIsDisplayed()
    }
}
