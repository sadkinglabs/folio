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
    fun phoneCurrentlyReadingAppearsWithoutKindleSnapshot() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                ReadingScreen(listOf(book), emptyList(), emptyList(), false, {}, {}, paired = false)
            }
        }
        compose.onNodeWithText("The Orchard").assertIsDisplayed()
        compose.onNodeWithText("Phone · Reading").assertIsDisplayed()
        compose.onNodeWithText("Connect your e-reader").assertHasClickAction()
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
