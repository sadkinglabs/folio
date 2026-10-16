package dev.sadking.folio.link

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Sync automatically" over the old on/off key. The password is stored by hand: the Android
 * Keystore that encrypts it does not exist on the JVM, and only its presence matters here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LinkSettingsTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(LinkSettings.PREFERENCES, Context.MODE_PRIVATE)

    private fun setUp() = prefs.edit().putString(LinkSettings.KEY_PASSWORD, "sealed").commit()

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun syncAutomaticallyIsOnByDefaultButLinkWaitsToBeSetUp() {
        val settings = LinkSettings(context)
        assertTrue(settings.autoSync)
        assertFalse(settings.isSetUp)
        assertFalse(settings.enabled)
    }

    @Test
    fun onceSetUpLinkRunsByItself() {
        setUp()
        val settings = LinkSettings(context)
        assertTrue(settings.isSetUp)
        assertTrue(settings.enabled)
    }

    @Test
    fun anUpdateKeepsTheOwnersChoice() {
        // The old switch, turned off before this version.
        prefs.edit().putBoolean("enabled", false).commit()
        setUp()
        val settings = LinkSettings(context)
        assertFalse(settings.autoSync)
        assertFalse(settings.enabled)
    }

    @Test
    fun turningItOffWritesTheOldKey() {
        setUp()
        val settings = LinkSettings(context)
        settings.enabled = false
        assertFalse(prefs.getBoolean("enabled", true))
        assertFalse(settings.enabled)
        settings.enabled = true
        assertTrue(prefs.getBoolean("enabled", false))
        assertTrue(settings.enabled)
    }

    @Test
    fun turningItOnBeforeSetUpDoesNotRunLink() {
        val settings = LinkSettings(context)
        settings.enabled = true
        assertTrue(settings.autoSync)
        assertFalse(settings.enabled)
        assertEquals(LinkSettings.DEFAULT_PORT, settings.port)
    }
}
