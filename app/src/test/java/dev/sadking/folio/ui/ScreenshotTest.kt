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
    private companion object {
        const val TABLET = "w840dp-h1280dp-xhdpi"
        const val TABLET_LANDSCAPE = "w1280dp-h800dp-xhdpi"
        const val FOLDABLE = "w700dp-h840dp-xhdpi"
    }

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

    /**
     * The frame at a tablet or foldable size, [qualifiers] being one of the three below: light,
     * dark and 200% text, like [shoot].
     */
    private fun shootAt(name: String, qualifiers: String, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        shoot(name, content = content)
    }

    @Test fun library() = shoot("library") { LibraryPreview() }

    @Test fun licences() = shoot("settings-licences", tall = true) { LicencesPreview() }

    @Test fun tabletLibrary() = shootAt("tablet-library", TABLET) { TabletLibraryPreview() }

    @Test
    fun tabletLibraryLandscape() =
        shootAt("tablet-library-landscape", TABLET_LANDSCAPE) { TabletLibraryPreview() }

    @Test
    fun tabletLibraryEmpty() =
        shootAt("tablet-library-empty", TABLET) { TabletLibraryEmptyPreview() }

    @Test fun foldableLibrary() = shootAt("foldable-library", FOLDABLE) { FoldLibraryPreview() }

    @Test
    fun foldableFlatLibrary() =
        shootAt("foldable-flat-library", FOLDABLE) { TabletLibraryPreview() }

    @Test fun tabletLists() = shootAt("tablet-lists", TABLET) { TabletListsPreview() }

    @Test
    fun tabletListsLandscape() =
        shootAt("tablet-lists-landscape", TABLET_LANDSCAPE) { TabletListsPreview() }

    @Test fun foldableLists() = shootAt("foldable-lists", FOLDABLE) { FoldListsPreview() }

    @Test fun tabletSettings() = shootAt("tablet-settings", TABLET) { TabletSettingsPreview() }

    @Test
    fun tabletSettingsLandscape() =
        shootAt("tablet-settings-landscape", TABLET_LANDSCAPE) { TabletSettingsPreview() }

    @Test
    fun tabletSettingsLicences() =
        shootAt("tablet-settings-licences", TABLET) { TabletLicencesPreview() }

    @Test fun foldableSettings() = shootAt("foldable-settings", FOLDABLE) { FoldSettingsPreview() }

    @Test fun tabletDevice() = shootAt("tablet-device", TABLET) { TabletDevicePreview() }

    @Test
    fun tabletDeviceLandscape() =
        shootAt("tablet-device-landscape", TABLET_LANDSCAPE) { TabletDevicePreview() }

    @Test fun foldableDevice() = shootAt("foldable-device", FOLDABLE) { FoldDevicePreview() }

    @Test fun details() = shoot("details") { DetailsPreview() }

    @Test fun detailsFileMissing() = shoot("details-file-missing") { DetailsFileMissingPreview() }

    @Test fun detailsChanged() = shoot("details-changed") { DetailsChangedPreview() }

    @Test fun editor() = shoot("editor") { EditorPreview() }

    @Test fun lists() = shoot("lists") { ListsPreview() }

    @Test fun listsFirstRun() = shoot("lists-first-run") { ListsFirstRunPreview() }

    @Test fun listDetail() = shoot("list-detail") { ListDetailPreview() }

    @Test fun listEditor() = shoot("list-editor") { ListEditorPreview() }

    @Test fun device() = shoot("device", tall = true) { KindlePreview() }

    @Test fun deviceOff() = shoot("device-off", tall = true) { DeviceOffPreview() }

    @Test fun deviceFirstRun() = shoot("device-first-run") { DeviceFirstRunPreview() }

    @Test fun devices() = shoot("devices", tall = true) { DevicesPreview() }

    @Test fun deviceSheet() = shoot("device-sheet", tall = true) { DeviceSheetPreview() }

    @Test
    fun deviceSheetOld() = shoot("device-sheet-old", tall = true) { OldPositionSheetPreview() }

    @Test fun progressDevices() = shoot("progress-devices") { ProgressDevicesPreview() }

    @Test fun progressMerged() = shoot("progress-merged") { ProgressMergedPreview() }

    @Test fun decisionsHeld() = shoot("decisions-held", tall = true) { DecisionsHeldPreview() }

    @Test fun hardcoverMove() = shoot("hardcover-move", tall = true) { HardcoverMovePreview() }

    @Test fun syncLine() = shoot("sync-line") { SyncLinePreview() }

    @Test fun decisions() = shoot("decisions", tall = true) { DecisionsPreview() }

    @Test
    fun decisionsHardcover() =
        shoot("decisions-hardcover", tall = true) { DecisionsHardcoverPreview() }

    @Test fun hardcoverTile() = shoot("hardcover-tile", tall = true) { HardcoverTilePreview() }

    @Test fun setup() = shoot("setup", tall = true) { SetupPreview() }

    @Test fun setupDone() = shoot("setup-done") { SetupDonePreview() }

    @Test fun pairing() = shoot("pairing", tall = true) { PairingPreview() }

    @Test
    fun pairingFirstRun() = shoot("pairing-first-run", tall = true) { PairingFirstRunPreview() }

    @Test fun settings() = shoot("settings", tall = true) { SettingsPreview() }

    @Test
    fun settingsFirstRun() = shoot("settings-first-run", tall = true) { SettingsFirstRunPreview() }

    @Test
    fun settingsHardcover() =
        shoot("settings-hardcover", tall = true) {
            SettingsHardcoverPreview()
        }

    @Test fun readingDefaults() = shoot("settings-text") { ReadingDefaultsPreview() }

    @Test fun readingTime() = shoot("settings-reading-time") { ReadingTimePreview() }

    @Test fun backup() = shoot("settings-backup") { BackupPreview() }

    @Test fun phoneFolder() = shoot("settings-folder") { PhoneFolderPreview() }

    @Test fun booksFolder() = shoot("settings-books-folder") { BooksFolderPreview() }

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

    @Test
    fun readerTextComfort() =
        shoot("reader-text-comfort", tall = true) { ReaderTextComfortPreview() }

    @Test
    fun readerComfortScrolling() =
        shoot("reader-comfort-scrolling") { ReaderComfortScrollingPreview() }

    @Test fun readerEdgeLevel() = shoot("reader-edge-level") { ReaderEdgeLevelPreview() }

    @Test
    fun readerBookmarkedWarm() = shoot("reader-bookmarked-warm") { ReaderBookmarkedWarmPreview() }

    @Test
    fun readerBookmarkedPaper() =
        shoot("reader-bookmarked-paper") { ReaderBookmarkedPaperPreview() }

    @Test fun readerBookmarks() = shoot("reader-bookmarks") { ReaderNotesBookmarksPreview() }

    @Test fun readerNotes() = shoot("reader-notes") { ReaderNotesPreview() }

    @Test fun readerNotesEmpty() = shoot("reader-notes-empty") { ReaderNotesEmptyPreview() }

    @Test fun readerHighlight() = shoot("reader-highlight") { ReaderHighlightPreview() }

    @Test
    fun detailsDeletedHighlights() =
        shoot("details-deleted-highlights", tall = true) { DetailsDeletedHighlightsPreview() }

    @Test
    fun readerHighlightColour() =
        shoot("reader-highlight-colour") { ReaderHighlightColourPreview() }

    @Test fun readerDevices() = shoot("reader-devices") { ReaderDevicesPreview() }

    @Test fun readerDevicesEmpty() = shoot("reader-devices-empty") { ReaderDevicesEmptyPreview() }

    @Test fun readerLinkOffer() = shoot("reader-link-offer") { ReaderLinkOfferPreview() }

    @Test fun readerListen() = shoot("reader-listen") { ReaderListenPreview() }

    @Test
    fun readerListenOptions() = shoot("reader-listen-options") { ReaderListenOptionsPreview() }

    @Test
    fun readerListenNatural() =
        shoot("reader-listen-natural", tall = true) { ReaderListenNaturalPreview() }

    @Test fun readerListenOffer() = shoot("reader-listen-offer") { ReaderListenOfferPreview() }

    @Test fun listenPreparing() = shoot("listen-preparing") { ReaderListenPreparingPreview() }

    @Test
    fun listenPreparingChapter() =
        shoot("listen-preparing-chapter") { ReaderListenChapterPreview() }

    @Test fun listenTooSlow() = shoot("listen-too-slow") { ReaderListenTooSlowPreview() }

    @Test
    fun listenBenchmarkRunning() =
        shoot("listen-benchmark-running") { ReaderListenBenchmarkRunningPreview() }

    @Test
    fun listenBenchmarkResult() =
        shoot("listen-benchmark-result", tall = true) { ReaderListenBenchmarkResultPreview() }

    @Test fun readerFailed() = shoot("reader-failed") { ReaderFailedPreview() }
}
