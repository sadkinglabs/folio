package dev.sadking.folio

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry

/**
 * What the device was doing when a UI test timed out: the window that had focus (a system dialog on
 * top swallows taps and Back) and the app's last operation (a failed save shows here).
 */
object Diagnostics {
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .executeShellCommand(command)
            )
            .use { it.readBytes().decodeToString() }

    fun state(): String {
        val app = ApplicationProvider.getApplicationContext<FolioApplication>()
        val operation = app.operations.state.value
        val windows = runCatching { shell("dumpsys window windows") }.getOrDefault("")
        // A shell that answers nothing shows here, rather than as an empty focus.
        val focus =
            if (windows.isBlank()) "(shell gave no output)"
            else
                windows
                    .lines()
                    .filter { "mCurrentFocus" in it || "mFocusedApp" in it }
                    .joinToString("\n") { it.trim() }
        return "Operation: busy=${operation.busy} failed=${operation.failed} " +
            "message='${operation.message}'\n" +
            focus
    }
}
