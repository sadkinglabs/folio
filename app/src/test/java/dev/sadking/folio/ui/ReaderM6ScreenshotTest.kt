package dev.sadking.folio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.sadking.folio.reader.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The reader's M6 sheets and bar, rendered like [ScreenshotTest]: light, dark and 200% text, to
 * `app/build/screenshots`. Runs only with `-Pscreenshots`. Its own copy of the helper, so the two
 * test files can change apart.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ReaderM6ScreenshotTest {
    @Before
    fun onlyWhenAsked() {
        assumeTrue(System.getProperty("roborazzi.test.record") == "true")
    }

    /** [qualifiers] sets another window, such as a tablet's. */
    private fun shoot(name: String, qualifiers: String? = null, content: @Composable () -> Unit) {
        if (qualifiers != null) RuntimeEnvironment.setQualifiers(qualifiers)
        captureRoboImage("build/screenshots/$name-light.png") { content() }
        RuntimeEnvironment.setQualifiers("+night")
        captureRoboImage("build/screenshots/$name-dark.png") { content() }
        RuntimeEnvironment.setQualifiers("+notnight")
        captureRoboImage("build/screenshots/$name-font200.png") {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                content()
            }
        }
    }

    @Test fun search() = shoot("reader-search") { ReaderSearchPreview() }

    @Test fun searchEmpty() = shoot("reader-search-empty") { ReaderSearchEmptyPreview() }

    @Test fun timeLeft() = shoot("reader-time-left") { ReaderTimeLeftPreview() }

    @Test
    fun textTwoPages() =
        shoot("reader-text-two-pages", "w840dp-h900dp-xhdpi") { ReaderTextTwoPagesPreview() }

    @Test fun define() = shoot("reader-define") { DefineSheetPreview() }

    @Test fun defineDownload() = shoot("reader-define-download") { DefineDownloadPreview() }

    @Test
    fun defineDownloading() = shoot("reader-define-downloading") { DefineDownloadingPreview() }

    @Test fun defineNotFound() = shoot("reader-define-not-found") { DefineNotFoundPreview() }

    @Test fun controls() = shoot("reader-controls-m6") { ReaderControlsPreview() }
}
