package dev.sadking.folio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class BooksFolderUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun askingForAccessThenShowingItIsAllowed() {
        var asked = 0
        compose.setContent {
            FolioTheme(dynamic = false) {
                var access by remember { mutableStateOf(false) }
                BooksFolderContent(
                    "Phone storage/Books",
                    access,
                    onAllow = {
                        asked++
                        access = true
                    },
                    onChange = {},
                )
            }
        }
        compose.onNodeWithText("Phone storage/Books").assertIsDisplayed()
        compose.onNodeWithTag(BOOKS_FOLDER_ALLOW_TAG).performClick()
        compose.runOnIdle { assertEquals(1, asked) }
        compose.onNodeWithTag(BOOKS_FOLDER_ALLOW_TAG).assertDoesNotExist()
        compose.onNodeWithText("Access allowed").assertIsDisplayed()
    }

    @Test
    fun aFolderOutsidePhoneStorageIsExplained() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                BooksFolderContent("Phone storage/Books", true, {}, {}, refused = true)
            }
        }
        compose.onNodeWithText("not an SD card", substring = true).assertIsDisplayed()
    }

    @Test
    fun theMoveButtonShowsOnlyWithAccessAndBooksToMove() {
        var moves = 0
        var state by mutableStateOf(Triple(false, 3, false))
        compose.setContent {
            FolioTheme(dynamic = false) {
                BooksFolderContent(
                    "Phone storage/Books",
                    hasAccess = state.first,
                    onAllow = {},
                    onChange = {},
                    pending = state.second,
                    busy = state.third,
                    onMove = { moves++ },
                )
            }
        }
        compose.onNodeWithTag(BOOKS_FOLDER_MOVE_TAG).assertDoesNotExist()
        state = Triple(true, 3, false)
        compose.onNodeWithText("Move 3 books here").performClick()
        compose.runOnIdle { assertEquals(1, moves) }
        state = Triple(true, 3, true)
        compose.onNodeWithTag(BOOKS_FOLDER_MOVE_TAG).assertIsNotEnabled()
        state = Triple(true, 0, false)
        compose.onNodeWithTag(BOOKS_FOLDER_MOVE_TAG).assertDoesNotExist()
    }
}
