package dev.sadking.folio.reader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.data.BookAnnotation
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
        )

    @Test
    fun aTappedNoteIsShownReadOnlyWithCopyAndNoDelete() {
        var copied = 0
        compose.setContent {
            FolioTheme(dynamic = false) { HighlightSheet(fromKindle, onCopy = { copied++ }) }
        }
        compose.onNodeWithText("The orchard had been planted.").assertIsDisplayed()
        compose.onNodeWithText("Same as the vineyard.").assertIsDisplayed()
        compose.onNodeWithText("Your note").assertIsDisplayed()
        compose.onNodeWithText("From your e-reader · 2026-10-16 08:41:12").assertIsDisplayed()
        compose.onNodeWithText("Chapter two").assertIsDisplayed()
        // Read-only: no field to edit, nothing to delete.
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose
            .onAllNodesWithText("Delete", substring = true, ignoreCase = true)
            .assertCountEquals(0)
        compose.onNodeWithText("Copy").performClick()
        assertEquals(1, copied)
    }

    @Test
    fun aPhoneHighlightSaysWhereItWasMade() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                HighlightSheet(
                    fromKindle.copy(
                        deviceId = PHONE_ANNOTATION_DEVICE,
                        note = "",
                        kind = "highlight",
                    ),
                    onCopy = {},
                )
            }
        }
        compose.onNodeWithText("Made on this phone · 2026-10-16 08:41:12").assertIsDisplayed()
        compose.onAllNodesWithText("Your note").assertCountEquals(0)
    }
}
