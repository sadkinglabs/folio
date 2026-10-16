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
class LibraryFolderStateTest {
    @get:Rule val compose = createComposeRule()

    private fun book(id: String, title: String) =
        Book(
            id,
            title,
            "[\"Alex Rowan\"]",
            originalPath = "$id.epub",
            currentPath = "$id.epub",
            originalHash = id,
            currentHash = id,
        )

    private val books = listOf(book("a", "The Orchard"), book("b", "Salt and Iron"))

    @Before
    fun clearPreferences() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences(LibraryView.PREFERENCES, 0)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun aBookWhoseFileIsGoneSaysFileMissing() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                LibraryScreen(
                    books,
                    emptySet(),
                    onOpen = {},
                    onSelect = {},
                    missingFiles = setOf("a"),
                )
            }
        }
        val missing =
            hasContentDescription("The Orchard, Alex Rowan") and hasStateDescription("File missing")
        compose.waitUntil(5_000) { compose.onAllNodes(missing).fetchSemanticsNodes().isNotEmpty() }
        compose
            .onNode(
                hasContentDescription("Salt and Iron, Alex Rowan") and
                    hasStateDescription("File missing")
            )
            .assertDoesNotExist()
    }

    @Test
    fun pullingDownTheLibraryAsksForARefresh() {
        var refreshes = 0
        compose.setContent {
            FolioTheme(dynamic = false) {
                LibraryScreen(
                    books,
                    emptySet(),
                    onOpen = {},
                    onSelect = {},
                    missingFiles = emptySet(),
                    onRefresh = { refreshes++ },
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(LIBRARY_GRID_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(LIBRARY_GRID_TAG).performTouchInput {
            swipeDown(startY = top + 1f, endY = bottom, durationMillis = 400)
        }
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test
    fun aListPageDoesNotRefresh() {
        var refreshes = 0
        compose.setContent {
            FolioTheme(dynamic = false) {
                LibraryScreen(
                    books,
                    emptySet(),
                    onOpen = {},
                    onSelect = {},
                    filterName = "Slow Sundays",
                    missingFiles = emptySet(),
                    onRefresh = { refreshes++ },
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(LIBRARY_GRID_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(LIBRARY_GRID_TAG).performTouchInput {
            swipeDown(startY = top + 1f, endY = bottom, durationMillis = 400)
        }
        compose.runOnIdle { assertEquals(0, refreshes) }
    }
}
