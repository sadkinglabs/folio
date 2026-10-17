package dev.sadking.folio

import android.content.Intent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.sadking.folio.reader.ReaderActivity
import dev.sadking.folio.reader.ReaderComfort
import dev.sadking.folio.reader.ReaderDefaults
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * The reader's brightness and keep-screen-on reach its own window. The reader opens on a book that
 * does not exist, so no page is needed: its screen still applies the window settings.
 */
class ReaderComfortWindowTest {
    private val app = ApplicationProvider.getApplicationContext<FolioApplication>()
    private val prefs = app.getSharedPreferences(ReaderDefaults.PREFERENCES, 0)
    private val saved = ReaderComfort.load(prefs)

    @After
    fun restore() {
        ReaderComfort.save(prefs, saved)
    }

    private fun launch(comfort: ReaderComfort, check: (WindowManager.LayoutParams) -> Unit) {
        ReaderComfort.save(prefs, comfort)
        val intent =
            Intent(app, ReaderActivity::class.java)
                .putExtra("book_id", UUID.randomUUID().toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var attributes: WindowManager.LayoutParams? = null
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                scenario.onActivity {
                    attributes =
                        WindowManager.LayoutParams().apply { copyFrom(it.window.attributes) }
                }
                if (runCatching { check(attributes!!) }.isSuccess) return@use
                Thread.sleep(100)
            }
            check(attributes!!)
        }
    }

    @Test
    fun theReadersBrightnessAndScreenOnApplyToItsWindow() {
        launch(ReaderComfort(autoBrightness = false, brightness = 0.3f, keepScreenOn = true)) {
            assertEquals(0.3f, it.screenBrightness, 0.001f)
            assertTrue(it.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        }
    }

    @Test
    fun autoAndOffLeaveThePhonesOwn() {
        launch(ReaderComfort(autoBrightness = true, brightness = 0.3f, keepScreenOn = false)) {
            assertEquals(
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
                it.screenBrightness,
                0.001f,
            )
            assertEquals(0, it.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
