package dev.sadking.folio

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.data.*
import dev.sadking.folio.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadingListsUiTest {
    @get:Rule val compose = createComposeRule()

    private fun book(id: String, status: String) =
        Book(
            id,
            "Title $id",
            "[]",
            originalPath = "",
            currentPath = "",
            originalHash = "",
            currentHash = "",
            status = status,
        )

    private fun library(books: List<Book>, shelfEntries: Map<String, List<Book>> = emptyMap()) =
        LibraryPresentation(books, books.map { it.id }.toSet(), emptyMap(), shelfEntries)

    @Test
    fun wantToReadIsASystemListCardAboveUserLists() {
        var opened: String? = null
        val wanted = listOf(book("a", "Want to Read"), book("b", "Want to Read"))
        compose.setContent {
            FolioTheme(dynamic = false) {
                ListsScreen(
                    listCards(listOf(Shelf("shelf", "Summer")), library(wanted)),
                    busy = false,
                    onOpen = { opened = it },
                    onNew = {},
                )
            }
        }
        compose.onNodeWithText("Pinned").assertDoesNotExist()
        compose.onNodeWithText("Transfer options").assertDoesNotExist()
        compose
            .onNodeWithTag(listCardTag(systemListId(ReadingList.WANT_TO_READ)))
            .assertIsDisplayed()
            .assert(hasText("2 books"))
        compose.onNodeWithText("Finished").assertIsDisplayed()
        compose.onNodeWithText("Summer").assertIsDisplayed()
        compose.onNodeWithText("Want to Read").performClick()
        compose.runOnIdle { assertEquals(systemListId(ReadingList.WANT_TO_READ), opened) }
    }

    @Test
    fun theLongPressMenuEditsAndDeletesWithoutSending() {
        var edited: Shelf? = null
        val shelf = Shelf("shelf", "Summer")
        compose.setContent {
            FolioTheme(dynamic = false) {
                ListsScreen(
                    listCards(
                        listOf(shelf),
                        library(
                            listOf(book("a", "Unread")),
                            mapOf(shelf.id to listOf(book("a", "Unread"))),
                        ),
                    ),
                    busy = false,
                    onOpen = {},
                    onNew = {},
                    onEdit = { edited = it },
                )
            }
        }
        // Lists reach the e-reader as KOReader collections by sync; nothing is sent by hand.
        compose.onNodeWithTag(listCardTag("shelf")).performTouchInput { longClick() }
        compose.onNodeWithText("Delete list").assertIsDisplayed()
        compose.onNodeWithText("Send to e-reader").assertDoesNotExist()
        compose.onNodeWithText("Include in sends").assertDoesNotExist()
        compose.onNodeWithText("Edit").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(shelf, edited) }
    }

    @Test
    fun systemListsHaveNoMenu() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                ListsScreen(listCards(emptyList(), library(emptyList())), false, {}, {})
            }
        }
        compose.onNodeWithTag(listCardTag(systemListId(ReadingList.FINISHED))).performTouchInput {
            longClick()
        }
        compose.onNodeWithText("Delete list").assertDoesNotExist()
        compose.onNodeWithTag(LISTS_NEW_CARD_TAG).assertIsDisplayed()
    }

    @Test
    fun listDetailShowsItsBooksAndEmptyState() {
        var opened: Book? = null
        val books = listOf(book("a", "Unread"), book("b", "Reading"))
        val shelves = listOf(Shelf("full", "Summer"), Shelf("bare", "Winter", "Tag", "snow"))
        val lib = library(books, mapOf("full" to books))
        compose.setContent {
            FolioTheme(dynamic = false) {
                var id by
                    androidx.compose.runtime.remember {
                        mutableStateOf("full")
                    }
                ListDetailScreen(
                    listCard(id, shelves, lib)!!,
                    emptySet(),
                    busy = false,
                    onBack = { id = "bare" },
                    onOpen = { opened = it },
                    onSelect = {},
                    onClearSelection = {},
                    onAddFromLibrary = {},
                )
            }
        }
        compose.onNodeWithTag(LIST_DETAIL_TAG).assertIsDisplayed()
        compose.onNodeWithText("2 books").assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(hasContentDescription("Title b", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNode(hasContentDescription("Title b", substring = true)).performClick()
        compose.runOnIdle { assertEquals("b", opened!!.id) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("No books yet").assertIsDisplayed()
        compose.onNodeWithText("Tag: snow").assertIsDisplayed()
    }
}
