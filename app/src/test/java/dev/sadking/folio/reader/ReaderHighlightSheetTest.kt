package dev.sadking.folio.reader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.data.BookAnnotation
import dev.sadking.folio.data.HighlightColour
import dev.sadking.folio.ui.FolioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The highlight sheet on the JVM (Robolectric), not on a device. The note dialog is covered by
 * ReaderHighlightsTest on the emulator: a text field in a dialog never settles under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ReaderHighlightSheetTest {
    @get:Rule val compose = createComposeRule()

    private val fromKindle =
        BookAnnotation(
            "a",
            "b1",
            "link:kindle",
            "The orchard had been planted.",
            "Same as the vineyard.",
            "Chapter two",
            "2026-10-16 08:41:12",
            "/body/DocFragment[3]/body/p[2]/text().0",
            "/body/DocFragment[3]/body/p[2]/text().29",
            "note",
            color = "green",
            drawer = "lighten",
        )

    @Test
    fun aTappedNoteOffersColourEditNoteCopyAndDelete() {
        var copied = 0
        var deleted = 0
        var noted = 0
        val picked = mutableListOf<HighlightColour>()
        compose.setContent {
            FolioTheme(dynamic = false) {
                HighlightSheet(
                    fromKindle,
                    HighlightSheetActions(
                        onCopy = { copied++ },
                        onColour = { picked += it },
                        onNote = { noted++ },
                        onDelete = { deleted++ },
                    ),
                )
            }
        }
        compose.onNodeWithText("The orchard had been planted.").assertIsDisplayed()
        compose.onNodeWithText("Same as the vineyard.").assertIsDisplayed()
        compose.onNodeWithText("Your note").assertIsDisplayed()
        compose.onNodeWithText("From your e-reader · 2026-10-16 08:41:12").assertIsDisplayed()
        compose.onNodeWithText("Chapter two").assertIsDisplayed()
        // The note is edited in its own dialog, never in the sheet.
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        // Its colour is shown as chosen; the others can be picked.
        compose.onNodeWithContentDescription("Green").assertIsSelected()
        compose.onNodeWithContentDescription("Yellow").assertIsNotSelected()
        compose.onNodeWithContentDescription("Pink").performClick()
        compose.onNodeWithContentDescription("Underline").performClick()
        assertEquals(listOf(HighlightColour.PINK, HighlightColour.UNDERLINE), picked)
        compose.onNodeWithText("Edit note").performClick()
        compose.onNodeWithText("Copy").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf(1, 1, 1), listOf(noted, copied, deleted))
    }

    @Test
    fun aPhoneHighlightSaysWhereItWasMadeAndOffersAddNote() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                HighlightSheet(
                    fromKindle.copy(
                        deviceId = PHONE_ANNOTATION_DEVICE,
                        note = "",
                        kind = "highlight",
                        color = "",
                        drawer = "underscore",
                    ),
                    HighlightSheetActions(),
                )
            }
        }
        compose.onNodeWithText("Made on this phone · 2026-10-16 08:41:12").assertIsDisplayed()
        compose.onAllNodesWithText("Your note").assertCountEquals(0)
        compose.onNodeWithText("Add note").assertIsDisplayed()
        compose.onNodeWithContentDescription("Underline").assertIsSelected()
    }

    @Test
    fun aBookmarkHasNoColour() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                HighlightSheet(
                    fromKindle.copy(kind = "bookmark", drawer = "", color = ""),
                    HighlightSheetActions(),
                )
            }
        }
        compose.onAllNodesWithContentDescription("Yellow").assertCountEquals(0)
        compose.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun theColourSheetHighlightsInThePickedColour() {
        val picked = mutableListOf<HighlightColour>()
        compose.setContent {
            FolioTheme(dynamic = false) {
                HighlightColourSheet("The orchard had been planted.") { picked += it }
            }
        }
        compose.onNodeWithText("Highlight colour").assertIsDisplayed()
        compose.onNodeWithContentDescription("Blue").performClick()
        assertEquals(listOf(HighlightColour.BLUE), picked)
    }
}
