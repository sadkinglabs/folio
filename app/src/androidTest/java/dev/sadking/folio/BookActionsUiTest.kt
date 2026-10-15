package dev.sadking.folio

import androidx.compose.ui.semantics.SemanticsActions
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

    // The library sorts and indexes off the main thread, which Compose tests do not wait for.
    private fun cover(): SemanticsNodeInteraction {
        val matcher = hasContentDescription(book.title, substring = true) and hasClickAction()
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodes(matcher).onFirst()
    }

    @Test
    fun gridLongPressSelectsAndTheAccessibilityActionOpensQuickActions() {
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
        cover().performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(book.id, selection)
            assertEquals("", context)
        }
        val actions =
            cover().fetchSemanticsNode().config[SemanticsActions.CustomActions].first {
                it.label == "Book actions"
            }
        compose.runOnIdle { actions.action() }
        compose.runOnIdle { assertEquals(book.id, context) }
    }

    @Test
    fun listLongPressUsesTheSameSelectionFlow() {
        var selection = ""
        compose.setContent {
            FolioTheme(dynamic = false) {
                LibraryScreen(listOf(book), emptySet(), onOpen = {}, onSelect = { selection = it })
            }
        }
        compose.onNodeWithContentDescription("Show list").performClick()
        cover().performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(book.id, selection) }
    }

    @Test
    fun recordQuickActionsOfferAttachAndRemovalWithoutSend() {
        var chosen: BookAction? = null
        compose.setContent {
            FolioTheme(dynamic = false) {
                BookActionsSheet(book, false, false, onAction = { chosen = it }, onDismiss = {})
            }
        }
        compose.onNodeWithTag(bookActionTag(BookAction.SEND)).assertDoesNotExist()
        compose.onNodeWithTag(bookActionTag(BookAction.ATTACH)).assertHasClickAction()
        compose.onNodeWithTag(bookActionTag(BookAction.REMOVE)).performClick()
        compose.runOnIdle { assertEquals(BookAction.REMOVE, chosen) }
        compose.onNodeWithTag(bookActionTag(BookAction.OPEN)).performClick()
        compose.runOnIdle { assertEquals(BookAction.OPEN, chosen) }
    }

    @Test
    fun epubQuickActionsOfferSendAndReadAndStayOpenWhileBusyOnlyForSelecting() {
        var chosen: BookAction? = null
        compose.setContent {
            FolioTheme(dynamic = false) {
                BookActionsSheet(book, true, true, onAction = { chosen = it }, onDismiss = {})
            }
        }
        compose.onNodeWithTag(bookActionTag(BookAction.SEND)).assertIsNotEnabled()
        compose.onNodeWithTag(bookActionTag(BookAction.OPEN_READER)).assertIsNotEnabled()
        compose.onNodeWithTag(bookActionTag(BookAction.SELECT)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(BookAction.SELECT, chosen) }
    }

    @Test
    fun removalRequiresConfirmationAndSaysWhatIsKept() {
        var confirms = 0
        compose.setContent {
            FolioTheme(dynamic = false) {
                RemoveBooksSheet(listOf(book), false, null, { confirms++ }, {})
            }
        }
        compose.runOnIdle { assertEquals(0, confirms) }
        compose.onNodeWithText("Remove Library book?").assertIsDisplayed()
        compose
            .onNodeWithText("Files, notes and progress are kept", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithTag(CONFIRM_SHEET_CONFIRM_TAG).assertTextEquals("Remove").performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
    }

    @Test
    fun bookPageActionsAndOverflow() {
        var hardcover = false
        var send = false
        var lists = false
        compose.setContent {
            FolioTheme(dynamic = false) {
                BookDetails(
                    book,
                    lists = listOf("Slow Sundays"),
                    onSend = { send = true },
                    onHardcover = { hardcover = true },
                    onLists = { lists = true },
                )
            }
        }
        compose.onNodeWithTag(BOOK_ACTION_SEND_TAG).performClick()
        compose.onNodeWithTag(BOOK_ACTION_HARDCOVER_TAG).performClick()
        compose.onNodeWithText("Slow Sundays").assertIsDisplayed()
        compose.onNodeWithTag(BOOK_LISTS_ADD_TAG).performClick()
        compose.runOnIdle {
            assertTrue(send)
            assertTrue(hardcover)
            assertTrue(lists)
        }
        // The status strip opens the Progress sheet, where the status on this phone changes.
        compose
            .onNodeWithTag(STATUS_TILE_PHONE_TAG)
            .assertTextContains("Not started")
            .performClick()
        compose.onNodeWithTag(PROGRESS_SHEET_TAG).assertIsDisplayed()
        compose.onNodeWithText("Status on this phone", ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun recordPageFindsAnEpubAndNeverSends() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                BookDetails(
                    book.copy(
                        originalPath = "",
                        currentPath = "",
                        originalHash = "",
                        currentHash = "",
                    ),
                    hasEpub = false,
                )
            }
        }
        compose.onNodeWithTag(BOOK_PRIMARY_TAG).assertTextEquals("Find EPUB")
        compose.onNodeWithTag(BOOK_ACTION_SEND_TAG).assertDoesNotExist()
        compose.onNodeWithTag(BOOK_ACTION_SHARE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(BOOK_ACTION_ATTACH_TAG).assertHasClickAction()
        compose.onNodeWithTag(STATUS_TILE_PHONE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(STATUS_TILE_HARDCOVER_TAG).assertIsDisplayed()
    }
}
