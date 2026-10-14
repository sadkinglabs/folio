package dev.sadking.folio

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sadking.folio.device.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSettingsTest {
    @Test
    fun passwordFreeModeDiscardsCredentialsAndNeedsNoSecret() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val isolated =
            object : ContextWrapper(context) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("$name-$suffix", mode)
            }
        val prefs = isolated.getSharedPreferences("device", 0)
        val settings = DeviceSettings(isolated)
        val config =
            DeviceConfig(
                "192.168.1.2",
                fingerprint = "SHA256:" + "A".repeat(43),
                authentication = "Password",
            )
        try {
            settings.save(config, "fixture-password")
            assertEquals("fixture-password", settings.credential().secret)
            assertNotEquals("fixture-password", prefs.getString("secret", null))
            settings.save(
                config.copy(authentication = "KOReader password-free"),
                "ignored-password",
                byteArrayOf(1, 2, 3),
            )
            assertEquals("", settings.credential().secret)
            assertNull(settings.credential().privateKey)
            assertFalse(prefs.contains("secret"))
            assertFalse(prefs.contains("private"))
            settings.forget()
            assertEquals("", settings.load().host)
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
