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
    fun bookHasTwoImmediatePrimaryActions() {
        var send = false
        var progress = false
        compose.setContent {
            FolioTheme(dynamic = false) {
                BookDetails(
                    book,
                    emptyList(),
                    emptySet(),
                    false,
                    { _, _ -> },
                    {},
                    { send = true },
                    onHardcover = { progress = true },
                )
            }
        }
        compose.onNodeWithText("Send to device").assertIsDisplayed().performClick()
        compose.onNodeWithText("Update progress").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(send)
            assertTrue(progress)
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
        compose.onNodeWithContentDescription("Search library").performTextInput("Orchard")
        compose.onNodeWithText("The Orchard").performClick()
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
        compose.onNodeWithContentDescription("Send").assertHasClickAction()
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
                    transferActive = false,
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
                CollectionsScreen(emptyList(), emptyList(), {}, {}, false)
            }
        }
        compose.onNodeWithText("List name").assertDoesNotExist()
        compose.onNodeWithText("Create list").performClick()
        compose.onNodeWithText("List name").assertIsDisplayed()
    }
}
