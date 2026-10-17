package dev.sadking.folio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.sadking.folio.acquire.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The in-app browser's screens (M5) rendered to PNG in light, dark and at 200% text, plus the start
 * page and browser at tablet width. Runs only with `-Pscreenshots`; images go to
 * `app/build/screenshots`. Its own copy of [ScreenshotTest]'s helper, so the two files have one
 * owner each.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class AcquireScreenshotTest {
    @Before
    fun onlyWhenAsked() {
        assumeTrue(System.getProperty("roborazzi.test.record") == "true")
    }

    private fun shoot(name: String, tablet: Boolean = false, content: @Composable () -> Unit) {
        if (tablet) RuntimeEnvironment.setQualifiers("w840dp-h1280dp-xhdpi")
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

    @Test fun sources() = shoot("acquire-sources") { AcquireSourcesPreview() }

    @Test fun sourceEditor() = shoot("acquire-source-editor") { AcquireSourceEditorPreview() }

    @Test fun find() = shoot("acquire-find") { AcquireFindPreview() }

    @Test fun downloading() = shoot("acquire-downloading") { AcquireDownloadingPreview() }

    @Test fun saved() = shoot("acquire-saved") { AcquireSavedPreview() }

    @Test fun savedNoReader() = shoot("acquire-saved-no-reader") { AcquireSavedNoReaderPreview() }

    @Test fun protectedBook() = shoot("acquire-protected") { AcquireProtectedPreview() }

    @Test fun addRow() = shoot("acquire-add-row") { AcquireAddRowPreview() }

    @Test fun findTablet() = shoot("acquire-find-tablet", tablet = true) { AcquireFindPreview() }

    @Test fun savedTablet() = shoot("acquire-saved-tablet", tablet = true) { AcquireSavedPreview() }
}
