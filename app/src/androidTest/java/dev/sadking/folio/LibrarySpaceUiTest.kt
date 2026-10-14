package dev.sadking.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.data.*
import dev.sadking.folio.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LibrarySpaceUiTest {
    @get:Rule val compose = createComposeRule()
    private val book =
        Book(
            "book",
            "Library star",
            "[]",
            tags = "[\"History\"]",
            originalPath = "file.epub",
            currentPath = "file.epub",
            originalHash = "a",
            currentHash = "a",
        )

    @Test
    fun defaultBrowseAnchorsSearchAndKeepsFiltersOffTheBookSurface() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                LibraryScreen(listOf(book), emptySet(), onOpen = {}, onSelect = {})
            }
        }
        compose.onNodeWithContentDescription("Search library").assertIsDisplayed()
        compose.onNodeWithText("Needs EPUB").assertDoesNotExist()
        compose.onNodeWithText("Add book manually").assertDoesNotExist()
        compose.onNodeWithText("Import Hardcover library").assertDoesNotExist()
        compose.onNodeWithText("Refine").assertIsDisplayed()
        compose.onNode(hasText(book.title) and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun refineContainsTagAndSortingAndResetRestoresBooks() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                LibraryScreen(listOf(book), emptySet(), onOpen = {}, onSelect = {})
            }
        }
        compose.onNodeWithText("Refine").performClick()
        compose.onNodeWithText("Search library").assertIsDisplayed()
        compose.onNodeWithText("Exact tag").performScrollTo().performTextInput("Missing")
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Show 1 book").performClick()
        compose.onNodeWithContentDescription("Search library").assertIsDisplayed()
        compose.onNode(hasText(book.title) and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun addMenuRetainsAllEntryRoutes() {
        var selected: AddBookAction? = null
        compose.setContent {
            FolioTheme(dynamic = false) {
                AddBooksSheet("account", onAction = { selected = it }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Import EPUBs").assertIsDisplayed()
        compose.onNodeWithText("Add book manually").assertIsDisplayed()
        compose.onNodeWithText("Scan or find a book").assertIsDisplayed()
        compose.onNodeWithText("Look up on Hardcover").assertIsDisplayed()
        compose.onNodeWithText("Import Hardcover library").performClick()
        compose.runOnIdle { assertEquals(AddBookAction.HARDCOVER, selected) }
    }
}
