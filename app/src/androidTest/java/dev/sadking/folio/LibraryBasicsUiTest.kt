package dev.sadking.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.data.*
import dev.sadking.folio.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LibraryBasicsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun recentlyRemovedCanRestoreWithoutDeleting() {
        var restored = ""
        var purged = ""
        compose.setContent {
            FolioTheme(dynamic = false) {
                RemovedBooksSheet(
                    listOf(RemovedEntry("id", "group", "Removed book", "EPUB", 1000)),
                    false,
                    { restored = it },
                    { purged = it },
                    {},
                )
            }
        }
        compose.onNodeWithText("Removed book").assertIsDisplayed()
        compose.onNodeWithText("Restore").performClick()
        compose.runOnIdle {
            assertEquals("group", restored)
            assertEquals("", purged)
        }
        // The trash icon only asks; deleting for good is the confirm sheet's red button.
        compose.onNodeWithContentDescription("Delete for good?").performClick()
        compose
            .onNodeWithText("Files and notes on this phone are deleted.", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithTag(CONFIRM_SHEET_DISMISS_TAG).performClick()
        compose.runOnIdle { assertEquals("", purged) }
        compose.onNodeWithContentDescription("Delete for good?").performClick()
        compose.onNodeWithTag(CONFIRM_SHEET_CONFIRM_TAG).performClick()
        compose.runOnIdle { assertEquals("group", purged) }
    }

    @Test
    fun importFailureShowsFilenameReasonAndRetry() {
        var retry = false
        compose.setContent {
            FolioTheme(dynamic = false) {
                ImportResultsSheet(
                    listOf(
                        ImportOutcome(
                            "content://fixture/broken",
                            "Broken.epub",
                            "Failed",
                            null,
                            "Not an EPUB",
                        )
                    ),
                    false,
                    { retry = true },
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithText("Broken.epub").assertIsDisplayed()
        compose.onNodeWithText("Not an EPUB").assertIsDisplayed()
        compose.onNodeWithContentDescription("Failed").assertIsDisplayed()
        compose.onNodeWithText("Choose files again").assertDoesNotExist()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(retry) }
    }

    @Test
    fun listEditorUsesSaveAndSeparateDeletionConfirmation() {
        var saved: Shelf? = null
        var deleted = false
        compose.setContent {
            FolioTheme(dynamic = false) {
                ListEditorSheet(
                    Shelf("id", "Old name"),
                    takenNames = listOf("Old name", "Taken"),
                    busy = false,
                    error = null,
                    onCreate = { _, _, _ -> },
                    onSave = { saved = it },
                    onDelete = { deleted = true },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("List name").performTextClearance()
        compose.onNodeWithText("List name").performTextInput("Taken")
        compose.onNodeWithText("A list with this name already exists").assertIsDisplayed()
        compose.onNodeWithText("List name").performTextClearance()
        compose.onNodeWithText("List name").performTextInput("New name")
        // Lists sync with KOReader collections by themselves: no "Include in sends" switch.
        compose.onNodeWithText("Include in sends").assertDoesNotExist()
        compose.onNodeWithText("Save list").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("New name", saved!!.name)
            assertEquals("Manual", saved!!.ruleField)
            assertFalse(saved!!.syncEnabled)
            assertFalse(deleted)
        }
        // Delete is an icon in the title row; it only asks, the confirm lives outside the sheet.
        compose.onNodeWithContentDescription("Delete list").performClick()
        compose.runOnIdle { assertTrue(deleted) }
    }
}
