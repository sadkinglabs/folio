package dev.sadking.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sadking.folio.data.*
import dev.sadking.folio.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryUiTest {
    @get:Rule val compose = createComposeRule()

    private fun book(id: String, title: String, author: String, status: String = "Unread") =
        Book(
            id,
            title,
            "[\"$author\"]",
            originalPath = "$id.epub",
            currentPath = "$id.epub",
            originalHash = id,
            currentHash = id,
            addedAt = id.hashCode().toLong(),
            status = status,
        )

    @Test
    fun emptyLibraryHasImportAction() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                LibraryScreen(emptyList(), emptySet(), onOpen = {}, onSelect = {})
            }
        }
        compose.onNodeWithText("Your shelf is empty").assertIsDisplayed()
        compose.onNodeWithText("Add books").assertHasClickAction()
    }

    @Test
    fun coversCarryTitleAuthorAndProgressForTalkBack() {
        val reading = book("r", "The Orchard", "Alex Rowan", status = "Reading")
        val unread = book("u", "Quiet Rooms", "Sam Hale")
        var opened = ""
        compose.setContent {
            FolioTheme(dynamic = false) {
                LibraryScreen(
                    listOf(unread, reading),
                    emptySet(),
                    onOpen = { opened = it.id },
                    onSelect = {},
                    progress = mapOf("r" to 0.37),
                    currentId = "r",
                )
            }
        }
        // The book in progress sits on the Reading shelf and in the grid, once each.
        compose.onNodeWithTag(LIBRARY_SHELF_TAG).assertIsDisplayed()
        compose
            .onAllNodes(hasContentDescription("The Orchard, Alex Rowan, 37%") and hasClickAction())
            .assertCountEquals(2)
        compose
            .onNode(hasContentDescription("Quiet Rooms, Sam Hale") and hasClickAction())
            .performClick()
        compose.runOnIdle { assertEquals("u", opened) }
    }

    @Test
    fun wantToReadTabIsNewestFirstAndFramesBooksWithoutAFile() {
        val older = book("a", "Older wish", "Ann Hollis", status = "Want to Read")
        val newer =
            book("b", "Newer wish", "Bo Lind", status = "Want to Read").copy(addedAt = 9_000_000)
        val owned = book("c", "Owned", "Cy Park")
        compose.setContent {
            FolioTheme(dynamic = false) {
                LibraryScreen(
                    listOf(older, owned, newer),
                    emptySet(),
                    onOpen = {},
                    onSelect = {},
                    fileIds = setOf("c"),
                )
            }
        }
        compose.onNode(hasContentDescription("Owned, Cy Park")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Older wish, Ann Hollis")).assertDoesNotExist()
        compose.onNodeWithTag(LIBRARY_TAB_WANTED_TAG).performClick()
        compose.onNode(hasContentDescription("Owned, Cy Park")).assertDoesNotExist()
        val wanted =
            compose
                .onAllNodes(hasContentDescription("wish", substring = true) and hasClickAction())
                .fetchSemanticsNodes()
        assertEquals(2, wanted.size)
        assertTrue(
            wanted[0]
                .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
                .first()
                .startsWith("Newer wish")
        )
        compose
            .onNode(hasContentDescription("Older wish, Ann Hollis"))
            .assertIsDisplayed()
            .assert(hasStateDescription("No EPUB yet"))
    }

    @Test
    fun longPressSelectsAndTheHeaderCounts() {
        var selected = setOf<String>()
        compose.setContent {
            FolioTheme(dynamic = false) {
                LibraryScreen(
                    listOf(book("a", "First", "Ann"), book("b", "Second", "Bo")),
                    selected,
                    onOpen = {},
                    onSelect = { selected = if (it in selected) selected - it else selected + it },
                    onClearSelection = { selected = emptySet() },
                )
            }
        }
        compose.onNode(hasContentDescription("First, Ann") and hasClickAction()).performTouchInput {
            longClick()
        }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNode(hasContentDescription("First, Ann")).assertIsSelected()
        compose.onNode(hasContentDescription("Second, Bo") and hasClickAction()).performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear selection").performClick()
        compose.onNodeWithText("1 selected").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add books").assertIsDisplayed()
        compose.runOnIdle { assertTrue(selected.isEmpty()) }
    }
}
