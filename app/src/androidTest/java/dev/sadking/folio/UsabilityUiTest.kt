package dev.sadking.folio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
class UsabilityUiTest {
    @get:Rule val compose = createComposeRule()
    private val book =
        Book(
            "example",
            "The Orchard",
            "[\"Alex Rowan\"]",
            originalPath = "",
            currentPath = "",
            originalHash = "",
            currentHash = "",
            status = "Reading",
        )

    @Test
    fun bookHasOnePrimaryActionAndFiveIconActions() {
        var read = false
        var shared = false
        var hardcover = false
        compose.setContent {
            FolioTheme(dynamic = false) {
                BookDetails(
                    book,
                    onRead = { read = true },
                    onShare = { shared = true },
                    onHardcover = { hardcover = true },
                    fileMissing = false,
                )
            }
        }
        // The title shows twice: in the hero and on the generated cover.
        compose.onAllNodesWithText("The Orchard").onFirst().assertIsDisplayed()
        compose.onNodeWithTag(BOOK_PRIMARY_TAG).assertTextEquals("Read").performClick()
        compose.onNodeWithText("Send").assertDoesNotExist()
        compose.onNodeWithTag(BOOK_ACTION_SHARE_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithTag(BOOK_ACTION_HARDCOVER_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithTag(BOOK_ACTION_REMOVE_TAG).assertHasClickAction()
        compose.runOnIdle {
            assertTrue(read)
            assertTrue(shared)
            assertTrue(hardcover)
        }
    }

    @Test
    fun librarySearchSurvivesOpeningAndReturningFromDetails() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                val holder = rememberSaveableStateHolder()
                var details by remember { mutableStateOf(false) }
                Column {
                    if (details) Button(onClick = { details = false }) { Text("Return to library") }
                    else
                        holder.SaveableStateProvider("library") {
                            LibraryScreen(
                                listOf(book),
                                emptySet(),
                                onOpen = { details = true },
                                onSelect = {},
                            )
                        }
                }
            }
        }
        // Search opens from its icon; the field keeps the query while a book is open.
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithContentDescription("Search library").performTextInput("Orchard")
        compose
            .onAllNodes(hasContentDescription("The Orchard, Alex Rowan") and hasClickAction())
            .onFirst()
            .performClick()
        compose.onNodeWithText("Return to library").performClick()
        compose.onNodeWithContentDescription("Search library").assertTextContains("Orchard")
    }

    @Test
    fun metadataDraftReportsChangesAndKeepsSaveVisible() {
        var dirty = false
        compose.setContent {
            FolioTheme(dynamic = false) {
                MetadataEditor(book, false, onDirtyChange = { dirty = it }) { _, _ -> }
            }
        }
        compose.onNodeWithText("Save changes").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Title").performTextReplacement("A revised title")
        compose.runOnIdle { assertTrue(dirty) }
        compose.onNodeWithText("Save changes").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun nowReadingSheetShowsThePhonePositionWithoutAnEreader() {
        var continued = false
        var action: NowReadingAction? = null
        compose.setContent {
            FolioTheme(dynamic = false) {
                NowReadingSheet(
                    CurrentBook(book, 0.37, 1L),
                    recent = emptyList(),
                    busy = false,
                    onContinue = { continued = true },
                    onAction = { action = it },
                    onPick = {},
                    onLibrary = {},
                    onDismiss = {},
                )
            }
        }
        // The title shows twice: as the sheet's heading and on the generated cover.
        compose.onAllNodesWithText("The Orchard").onFirst().assertIsDisplayed()
        compose.onNodeWithText("37%").assertIsDisplayed()
        compose.onNodeWithText("Continue · 37%").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Send").assertDoesNotExist()
        compose.onNodeWithContentDescription("Edit").assertHasClickAction()
        compose.onNodeWithText("Open book page").performClick()
        compose.runOnIdle {
            assertTrue(continued)
            assertEquals(NowReadingAction.OPEN, action)
        }
    }

    @Test
    fun nowReadingSheetWithoutABookOffersRecentReadingAndTheLibrary() {
        var picked: Book? = null
        var library = false
        compose.setContent {
            FolioTheme(dynamic = false) {
                NowReadingSheet(
                    null,
                    recent = listOf(book),
                    busy = false,
                    onContinue = {},
                    onAction = {},
                    onPick = { picked = it },
                    onLibrary = { library = true },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Pick a book to read").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertDoesNotExist()
        compose.onNode(hasText("The Orchard") and hasClickAction()).performClick()
        compose.onNodeWithText("Go to Library").performClick()
        compose.runOnIdle {
            assertEquals(book, picked)
            assertTrue(library)
        }
    }

    @Test
    fun centreTileTapsContinueAndLongPressOpensOptions() {
        var continued = false
        var options = 0
        compose.setContent {
            FolioTheme(dynamic = false) {
                FolioBar(
                    tab = 0,
                    onTab = {},
                    current = CurrentBook(book, 0.37, 1L),
                    onContinue = { continued = true },
                    onOptions = { options++ },
                    deviceBadge = false,
                    hazeState = null,
                )
            }
        }
        val tile = compose.onNodeWithContentDescription("Continue reading The Orchard, 37%")
        tile.performClick()
        tile.performTouchInput { longClick() }
        compose.runOnIdle {
            assertTrue(continued)
            assertEquals(1, options)
        }
        compose.onNodeWithText("Settings").assertHasClickAction()
        compose.onNodeWithText("Reading").assertDoesNotExist()
    }

    @Test
    fun collectionCreationIsASeparateFocusedFlow() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                var creating by remember { mutableStateOf(false) }
                ListsScreen(
                    listCards(
                        emptyList(),
                        LibraryPresentation(emptyList(), emptySet(), emptyMap(), emptyMap()),
                    ),
                    busy = false,
                    onOpen = {},
                    onNew = { creating = true },
                )
                if (creating)
                    ListEditorSheet(
                        null,
                        emptyList(),
                        busy = false,
                        error = null,
                        onCreate = { _, _, _ -> },
                        onSave = {},
                        onDelete = null,
                        onDismiss = { creating = false },
                    )
            }
        }
        compose.onNodeWithText("List name").assertDoesNotExist()
        compose.onNodeWithTag(LISTS_NEW_TAG).performClick()
        compose.onNodeWithText("List name").assertIsDisplayed()
        compose.onNodeWithText("Create list").assertIsNotEnabled()
    }
}
