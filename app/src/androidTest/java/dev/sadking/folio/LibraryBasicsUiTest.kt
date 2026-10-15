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
        compose.setContent {
            FolioTheme(dynamic = false) {
                RemovedBooksSheet(
                    listOf(RemovedEntry("id", "group", "Removed book", "EPUB", 1000)),
                    false,
                    { restored = it },
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithText("Restore").performClick()
        compose.runOnIdle { assertEquals("group", restored) }
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
                    {},
                )
            }
        }
        compose.onNodeWithText("Broken.epub").assertIsDisplayed()
        compose.onNodeWithText("Not an EPUB").assertIsDisplayed()
        compose.onNodeWithText("Retry this file").performScrollTo().performClick()
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
                    onCreate = { _, _, _, _ -> },
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
        compose.onNodeWithText("Include in sends").performScrollTo().performClick()
        compose.onNodeWithText("Save list").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("New name", saved!!.name)
            assertEquals("Manual", saved!!.ruleField)
            assertTrue(saved!!.syncEnabled)
            assertFalse(deleted)
        }
        // Delete is an icon in the title row; it only asks, the confirm lives outside the sheet.
        compose.onNodeWithContentDescription("Delete list").performClick()
        compose.runOnIdle { assertTrue(deleted) }
    }
}
