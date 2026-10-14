package dev.sadking.folio

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Saves a full-screen capture to the app's files/qa folder, which CI copies out after a run. */
object QaScreenshots {
    fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Let sheet and bar animations finish; idle sync does not wait for Compose animations.
        Thread.sleep(800)
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        val folder = File(instrumentation.targetContext.filesDir, "qa").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    /** A real touch, delivered like a finger: the reader's page is a WebView, not Compose. */
    fun tapScreenCentre() {
        val metrics =
            InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand("input tap ${metrics.widthPixels / 2} ${metrics.heightPixels / 2}")
            .close()
        Thread.sleep(1500)
    }
}
