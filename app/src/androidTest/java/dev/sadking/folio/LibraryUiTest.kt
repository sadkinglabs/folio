package dev.sadking.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sadking.folio.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun emptyLibraryHasImportAction() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                LibraryScreen(emptyList(), emptySet(), onOpen = {}, onSelect = {})
            }
        }
        compose.onNodeWithText("Make room for your next read").assertIsDisplayed()
        compose.onNodeWithText("Add books").assertHasClickAction()
    }
}
