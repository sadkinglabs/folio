package dev.sadking.folio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.HardcoverReading
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class HardcoverDatesTest {
    @get:Rule val compose = createComposeRule()

    private val book =
        Book(
            "a",
            "The Orchard",
            "[\"Alex Rowan\"]",
            originalPath = "",
            currentPath = "",
            originalHash = "",
            currentHash = "",
        )

    @Test
    fun finishDialogSendsTheReadItWasOpenedFor() {
        val reading = HardcoverReading(1, 2, 7, 96, 10, startedOn = "2026-09-01")
        var finished: HardcoverReading? = null
        compose.setContent {
            FolioTheme(dynamic = false) {
                var day by remember { mutableStateOf(LocalDate.of(2026, 10, 2)) }
                FinishDialog(
                    book,
                    reading,
                    day,
                    busy = false,
                    onDate = { day = it },
                    onFinish = { finished = it },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Finished", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(HARDCOVER_DATE_TAG).performClick()
        // Material's date picker opens over the dialog.
        compose.onAllNodes(isDialog()).assertCountEquals(2)
        compose.onAllNodesWithText("Cancel").onLast().performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText("Mark").performClick()
        compose.runOnIdle { assertEquals(reading, finished) }
    }
}
