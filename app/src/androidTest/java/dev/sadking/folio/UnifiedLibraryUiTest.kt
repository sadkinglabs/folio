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
                "[]",
                originalPath = "book.epub",
                currentPath = "book.epub",
                originalHash = "a",
                currentHash = "a",
            )
        val wish = WishBook("wish", "hardcover:1", "Future read", "[]")
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
        compose.onNodeWithText("Refine").performClick()
        compose.onNodeWithText("Needs EPUB").performClick()
        compose.onNodeWithText("Show 1 book").performClick()
        compose.onNodeWithText("Owned book").assertDoesNotExist()
        compose
            .onNode(hasText("Future read") and hasClickAction())
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(wish.id, opened) }
    }
}
