package dev.sadking.folio.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.FolioApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Librillo coming to the foreground starts Link only when it is set up and syncs by itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FolioLinkStartTest {
    private val app: FolioApplication
        get() = ApplicationProvider.getApplicationContext()

    private val prefs
        get() = app.getSharedPreferences(LinkSettings.PREFERENCES, Context.MODE_PRIVATE)

    private fun nextStarted() = shadowOf(app).nextStartedService

    @Before
    fun clear() {
        prefs.edit().clear().commit()
        while (true) nextStarted() ?: break
    }

    @Test
    fun linkDoesNotStartBeforeItIsSetUp() {
        app.link.onAppForeground()
        assertNull(nextStarted())
    }

    @Test
    fun linkStartsWhenLibrilloOpens() {
        prefs.edit().putString(LinkSettings.KEY_PASSWORD, "sealed").commit()
        app.link.onAppForeground()
        assertEquals(FolioLinkService::class.java.name, nextStarted()?.component?.className)
    }

    @Test
    fun linkStaysOffWhenTheOwnerTurnedItOff() {
        prefs
            .edit()
            .putString(LinkSettings.KEY_PASSWORD, "sealed")
            .putBoolean(LinkSettings.KEY_AUTO_SYNC, false)
            .commit()
        app.link.onAppForeground()
        assertNull(nextStarted())
    }

    @Test
    fun linkDoesNotStartFromTheBackground() {
        prefs.edit().putString(LinkSettings.KEY_PASSWORD, "sealed").commit()
        app.link.onAppBackground()
        app.link.startIfAllowed()
        assertNull(nextStarted())
    }
}
