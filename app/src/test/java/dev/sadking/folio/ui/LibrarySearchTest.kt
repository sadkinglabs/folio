package dev.sadking.folio.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.Book
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class LibrarySearchTest {
    @get:Rule val compose = createComposeRule()

    private val book =
        Book(
            "a",
            "The Orchard",
            "[\"Alex Rowan\"]",
            originalPath = "a.epub",
            currentPath = "a.epub",
            originalHash = "a",
            currentHash = "a",
        )

    @Before
    fun clearPreferences() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences(LibraryView.PREFERENCES, 0)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun recentSearchesKeepTheNewestFirstWithoutRepeats() {
        assertEquals(listOf("orchard"), withRecent(emptyList(), "  orchard "))
        assertEquals(listOf("Orchard", "rowan"), withRecent(listOf("rowan", "orchard"), "Orchard"))
        assertEquals(listOf("a"), withRecent(listOf("a"), "   "))
        assertEquals(5, withRecent(listOf("1", "2", "3", "4", "5"), "6").size)
    }

    @Test
    fun aSearchWithNoMatchOffersHardcoverAndOpeningAMatchKeepsTheSearch() {
        var hardcover = ""
        compose.setContent {
            FolioTheme(dynamic = false) {
                LibraryScreen(
                    listOf(book),
                    emptySet(),
                    onOpen = {},
                    onSelect = {},
                    onSearchHardcover = { hardcover = it },
                )
            }
        }
        compose.onNodeWithTag(LIBRARY_SEARCH_TAG).performClick()
        val field = hasSetTextAction() and hasAnyAncestor(hasTestTag(LIBRARY_SEARCH_BAR_TAG))
        compose.onNode(field).performTextInput("Dune")
        compose.onNodeWithText("Search Hardcover for “Dune”").performClick()
        compose.runOnIdle { assertEquals("Dune", hardcover) }

        compose.onNode(field).performTextReplacement("orch")
        compose
            .onNode(hasContentDescription("The Orchard, Alex Rowan") and hasClickAction())
            .performClick()
        compose.onNode(field).performTextReplacement("")
        compose.onNodeWithText("Clear recent").assertIsDisplayed()
        compose.onNodeWithText("orch").assertIsDisplayed()
    }
}
