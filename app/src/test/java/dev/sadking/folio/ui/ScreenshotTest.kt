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

    /** [tall] renders a longer screen in one image, so a scrolling tab shows every group. */
    private fun shoot(name: String, tall: Boolean = false, content: @Composable () -> Unit) {
        if (tall) RuntimeEnvironment.setQualifiers("w412dp-h2000dp-xxhdpi")
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

    @Test fun lists() = shoot("lists") { ListsPreview() }

    @Test fun listDetail() = shoot("list-detail") { ListDetailPreview() }

    @Test fun listEditor() = shoot("list-editor") { ListEditorPreview() }

    @Test fun device() = shoot("device") { KindlePreview() }

    @Test fun deviceOff() = shoot("device-off") { DeviceOffPreview() }

    @Test fun deviceFirstRun() = shoot("device-first-run") { DeviceFirstRunPreview() }

    @Test fun pairing() = shoot("pairing", tall = true) { PairingPreview() }

    @Test
    fun pairingFirstRun() = shoot("pairing-first-run", tall = true) { PairingFirstRunPreview() }

    @Test fun settings() = shoot("settings", tall = true) { SettingsPreview() }

    @Test
    fun settingsFirstRun() = shoot("settings-first-run", tall = true) { SettingsFirstRunPreview() }

    @Test fun readingDefaults() = shoot("settings-text") { ReadingDefaultsPreview() }

    @Test fun readingTime() = shoot("settings-reading-time") { ReadingTimePreview() }

    @Test fun backup() = shoot("settings-backup") { BackupPreview() }

    @Test fun phoneFolder() = shoot("settings-folder") { PhoneFolderPreview() }

    @Test fun hardcoverAccount() = shoot("hardcover-account") { HardcoverAccountPreview() }

    @Test fun bar() = shoot("bar") { BarPreview() }

    @Test fun lookup() = shoot("lookup") { LookupPreview() }

    @Test fun lookupResults() = shoot("lookup-results") { LookupResultsPreview() }

    @Test fun hardcover() = shoot("hardcover") { HardcoverReadingPreview() }

    @Test fun hardcoverLink() = shoot("hardcover-link") { HardcoverLinkPreview() }

    @Test fun hardcoverConnect() = shoot("hardcover-connect") { HardcoverConnectPreview() }

    @Test fun hardcoverImport() = shoot("hardcover-import") { HardcoverImportPreview() }

    @Test fun hardcoverSearch() = shoot("hardcover-search") { HardcoverSearchPreview() }

    @Test fun discovery() = shoot("discovery") { DiscoveryPreview() }

    @Test fun importResults() = shoot("import-results") { ImportResultsPreview() }

    @Test fun removed() = shoot("removed") { RemovedPreview() }

    @Test fun removedEmpty() = shoot("removed-empty") { RemovedEmptyPreview() }

    @Test fun readerControls() = shoot("reader-controls") { ReaderControlsPreview() }

    @Test fun readerText() = shoot("reader-text") { ReaderTextPreview() }

    @Test fun readerContents() = shoot("reader-contents") { ReaderContentsPreview() }

    @Test fun readerDevices() = shoot("reader-devices") { ReaderDevicesPreview() }

    @Test fun readerDevicesEmpty() = shoot("reader-devices-empty") { ReaderDevicesEmptyPreview() }

    @Test fun readerLinkOffer() = shoot("reader-link-offer") { ReaderLinkOfferPreview() }

    @Test fun readerListen() = shoot("reader-listen") { ReaderListenPreview() }

    @Test
    fun readerListenOptions() = shoot("reader-listen-options") { ReaderListenOptionsPreview() }

    @Test fun readerFailed() = shoot("reader-failed") { ReaderFailedPreview() }
}
