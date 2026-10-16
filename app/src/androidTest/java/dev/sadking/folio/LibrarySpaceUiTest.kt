package dev.sadking.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.data.*
import dev.sadking.folio.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A chip in the Filter sheet, not the quick filter with the same label on the page. */
internal fun inFilterSheet(label: String) =
    hasText(label) and hasAnyAncestor(hasTestTag(LIBRARY_FILTER_SHEET_TAG))

class LibrarySpaceUiTest {
    @get:Rule val compose = createComposeRule()
    private val book =
        Book(
            "book",
            "Library star",
            "[\"Ada Quill\"]",
            tags = "[\"History\"]",
            originalPath = "file.epub",
            currentPath = "file.epub",
            originalHash = "a",
            currentHash = "a",
        )

    @Test
    fun defaultBrowseKeepsSearchAndFiltersOffTheBookSurface() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                LibraryScreen(listOf(book), emptySet(), onOpen = {}, onSelect = {})
            }
        }
        // Search is an icon until asked for and the Add routes stay out of the way. The only chips
        // are the quick filters in the Sort and Filter row.
        compose.onNodeWithContentDescription("Search library").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search").assertIsDisplayed()
        compose.onNodeWithTag(LIBRARY_QUICK_FILTERS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Type it in").assertDoesNotExist()
        compose.onNodeWithText("Import your Hardcover library").assertDoesNotExist()
        compose.onNodeWithContentDescription("Filter").assertIsDisplayed()
        compose.onNodeWithTag(LIBRARY_TAB_WANTED_TAG).assertIsDisplayed()
        compose
            .onNode(hasContentDescription("Library star, Ada Quill") and hasClickAction())
            .assertIsDisplayed()
    }

    @Test
    fun filterHasTagChipsAndResetRestoresBooks() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                LibraryScreen(listOf(book), emptySet(), onOpen = {}, onSelect = {})
            }
        }
        compose.onNodeWithContentDescription("Filter").performClick()
        compose.onNodeWithText("Status").assertIsDisplayed()
        compose.onNodeWithText("Sort").assertDoesNotExist()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Show 1 book").assertIsDisplayed()
        compose.onNode(inFilterSheet("Reading")).performClick()
        compose.onNodeWithText("Show 0 books").assertIsDisplayed()
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Show 1 book").performClick()
        compose.onNodeWithContentDescription("Filter").assertIsDisplayed()
        compose
            .onNode(hasContentDescription("Library star, Ada Quill") and hasClickAction())
            .assertIsDisplayed()
    }

    @Test
    fun addSheetOffersEveryEntryRouteAsATile() {
        var selected: AddBookAction? = null
        compose.setContent {
            FolioTheme(dynamic = false) {
                AddBooksSheet("account", onAction = { selected = it }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Import book").assertIsDisplayed()
        compose.onNodeWithText("Find online").assertIsDisplayed()
        compose.onNodeWithText("Scan cover").assertIsDisplayed()
        compose.onNodeWithText("Type it in").assertIsDisplayed()
        compose.onNodeWithText("@account").assertIsDisplayed()
        compose.onNodeWithText("Import your Hardcover library").performClick()
        compose.runOnIdle { assertEquals(AddBookAction.HARDCOVER, selected) }
    }
}
