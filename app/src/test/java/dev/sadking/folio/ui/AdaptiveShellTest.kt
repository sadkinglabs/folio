package dev.sadking.folio.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import dev.sadking.folio.LibraryViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The whole app shell on the JVM with an empty library: a phone keeps the bottom bar and no rail; a
 * tablet gets the rail, no bar, and the empty pane beside the Library.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AdaptiveShellTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun launch() {
        val model = ViewModelProvider(compose.activity)[LibraryViewModel::class.java]
        compose.setContent { FolioApp(model) }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w412dp-h892dp-xxhdpi")
    fun aPhoneKeepsTheBottomBar() {
        launch()
        compose.onNodeWithTag(FOLIO_BAR_TAG).assertExists()
        compose.onNodeWithTag(FOLIO_RAIL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(EMPTY_PANE_TAG).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w700dp-h840dp-xhdpi")
    fun aMediumWindowGetsTheRailAndOnePane() {
        launch()
        compose.onNodeWithTag(FOLIO_RAIL_TAG).assertExists()
        compose.onNodeWithTag(FOLIO_BAR_TAG).assertDoesNotExist()
        compose.onNodeWithTag(EMPTY_PANE_TAG).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp-xhdpi")
    fun aTabletGetsTheRailAndAnEmptyPaneBesideTheLibrary() {
        launch()
        compose.onNodeWithTag(FOLIO_RAIL_TAG).assertExists()
        compose.onNodeWithTag(FOLIO_BAR_TAG).assertDoesNotExist()
        compose.onNodeWithTag(EMPTY_PANE_TAG).assertExists()
    }
}
