package dev.sadking.folio.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import dev.sadking.folio.LibraryViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The frame follows the room the app is drawn in, not the phone's screen: an 840 dp window forced
 * onto a phone gets two panes. Scaling makes it measure 839.99 dp, which must still count as 840.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class ForcedSizeLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun anExpandedWindowOnAPhoneScreenGetsTwoPanes() {
        val model = ViewModelProvider(compose.activity)[LibraryViewModel::class.java]
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(840.dp, 1280.dp))
            ) {
                FolioApp(model)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(FOLIO_RAIL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FOLIO_BAR_TAG).assertDoesNotExist()
        compose.onNodeWithTag(EMPTY_PANE_TAG).assertIsDisplayed()
    }
}
