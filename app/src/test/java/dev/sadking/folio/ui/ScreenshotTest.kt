package dev.sadking.folio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the design previews to PNG on the JVM, in light, dark and at 200% text, so a screen can
 * be checked in seconds instead of a full emulator run. Runs only with `-Pscreenshots`; images go
 * to `app/build/screenshots`. Robolectric's renderer is close to a phone's but not identical: blur
 * and some shadows are approximations, so the emulator stays the reference.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ScreenshotTest {
    @Before
    fun onlyWhenAsked() {
        assumeTrue(System.getProperty("roborazzi.test.record") == "true")
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
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

    @Test fun library() = shoot("library") { LibraryPreview() }

    @Test fun details() = shoot("details") { DetailsPreview() }

    @Test fun editor() = shoot("editor") { EditorPreview() }

    @Test fun collections() = shoot("collections") { CollectionsPreview() }

    @Test fun device() = shoot("device") { KindlePreview() }

    @Test fun pairing() = shoot("pairing") { PairingPreview() }

    @Test fun bar() = shoot("bar") { BarPreview() }

    @Test fun lookup() = shoot("lookup") { LookupPreview() }
}
