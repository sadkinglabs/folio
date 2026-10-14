package dev.sadking.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.data.*
import dev.sadking.folio.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookActionsUiTest {
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
    fun gridLongPressOpensContextRatherThanStartingSelection() {
        var context = ""
        var selection = ""
        compose.setContent {
            FolioTheme(dynamic = false) {
                LibraryScreen(
                    listOf(book),
                    emptySet(),
                    onOpen = {},
                    onSelect = { selection = it },
                    onContext = { context = it.id },
                )
            }
        }
        compose.onNode(hasText(book.title) and hasClickAction()).performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(book.id, context)
            assertEquals("", selection)
        }
    }

    @Test
    fun listLongPressUsesSameContextFlow() {
        var context = ""
        compose.setContent {
            FolioTheme(dynamic = false) {
                LibraryScreen(
                    listOf(book),
                    emptySet(),
                    onOpen = {},
                    onSelect = {},
                    onContext = { context = it.id },
                )
            }
        }
        compose.onNodeWithContentDescription("Show list").performClick()
        compose.onNode(hasText(book.title) and hasClickAction()).performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(book.id, context) }
    }

    @Test
    fun catalogueMenuOffersAttachmentAndRemovalWithoutSend() {
        var chosen: BookAction? = null
        compose.setContent {
            FolioTheme(dynamic = false) {
                BookActionsSheet(
                    book,
                    false,
                    false,
                    false,
                    onAction = { chosen = it },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Send to device").assertDoesNotExist()
        compose.onNodeWithText("Attach EPUB from phone").assertHasClickAction()
        compose.onNodeWithText("Remove from library").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(BookAction.REMOVE, chosen) }
    }

    @Test
    fun removalRequiresConfirmationAndDescribesLocalScope() {
        var confirms = 0
        compose.setContent {
            FolioTheme(dynamic = false) {
                RemoveBooksDialog(listOf(book), 1, false, null, { confirms++ }, {})
            }
        }
        compose.runOnIdle { assertEquals(0, confirms) }
        compose.onNodeWithText("Kindle and Hardcover", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Move to Recently removed").performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
    }
}
