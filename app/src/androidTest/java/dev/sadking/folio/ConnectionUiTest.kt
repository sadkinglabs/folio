package dev.sadking.folio

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sadking.folio.device.*
import dev.sadking.folio.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionUiTest {
    @get:Rule val compose = createComposeRule()
    private val pin = "SHA256:" + "A".repeat(43)

    @Test
    fun fingerprintTrustEnablesPasswordFreeSaveWithoutASecret() {
        var observed by
            mutableStateOf<ObservedHostKey?>(
                ObservedHostKey("192.168.1.2", 2222, "ecdsa-sha2-nistp256", pin)
            )
        var saved: DeviceConfig? = null
        val initial = DeviceConfig(host = "192.168.1.2", authentication = "KOReader password-free")
        compose.setContent {
            FolioTheme(dynamic = false) {
                ConnectionForm(
                    initial,
                    false,
                    observed = observed,
                    onClearProbe = { observed = null },
                ) { config, secret, key ->
                    assertEquals("", secret)
                    assertNull(key)
                    saved = config
                }
            }
        }
        compose.onNode(hasText("Trust this Kindle") and hasClickAction()).performClick()
        compose.onNodeWithText("Save pairing").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(pin, saved!!.fingerprint)
            assertEquals("KOReader password-free", saved!!.authentication)
            assertEquals("/mnt/us/documents", saved!!.directory)
        }
    }

    @Test
    fun observationForAnotherAddressCannotBeTrusted() {
        val observed = ObservedHostKey("192.168.1.2", 2222, "ecdsa-sha2-nistp256", pin)
        compose.setContent {
            FolioTheme(dynamic = false) {
                ConnectionForm(DeviceConfig(host = "192.168.1.3"), false, observed = observed) {
                    _,
                    _,
                    _ ->
                    error("Must not save")
                }
            }
        }
        compose.onNode(hasText("Trust this Kindle") and hasClickAction()).assertIsNotEnabled()
        compose
            .onNodeWithText("The address changed. Cancel and read its fingerprint again.")
            .assertIsDisplayed()
    }
}
