package dev.sadking.folio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.data.*
import dev.sadking.folio.ui.*
import org.junit.Rule
import org.junit.Test

class UnifiedLibraryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun needsEpubFilterShowsCatalogueBookAndOpensAttachmentFlow() {
        val book =
            Book(
                "file",
                "Owned book",
                "[\"Ann Hollis\"]",
                originalPath = "book.epub",
                currentPath = "book.epub",
                originalHash = "a",
                currentHash = "a",
            )
        // A record tracked as Reading lives on the Library tab, framed as needing its EPUB.
        val wish =
            WishBook("wish", "hardcover:1", "Future read", "[\"Bo Lind\"]", status = "Reading")
        var opened = ""
        compose.setContent {
            FolioTheme(dynamic = false) {
                LibraryScreen(
                    unifiedLibrary(listOf(book), listOf(wish)),
                    emptySet(),
                    onOpen = { opened = it.id },
                    onSelect = {},
                    fileIds = setOf(book.id),
                    signals = mapOf(wish.id to catalogueSignals(wish, null)),
                )
            }
        }
        compose.onNodeWithContentDescription("Filter").performClick()
        compose.onNodeWithText("Needs EPUB").performClick()
        compose.onNodeWithText("Show 1 book").performClick()
        compose.onNode(hasContentDescription("Owned book", substring = true)).assertDoesNotExist()
        // The record is on the Reading shelf and in the grid; either opens it.
        compose
            .onAllNodes(hasContentDescription("Future read, Bo Lind") and hasClickAction())
            .onFirst()
            .assertIsDisplayed()
            .assert(hasStateDescription("No EPUB yet"))
            .performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(wish.id, opened) }
    }
}
