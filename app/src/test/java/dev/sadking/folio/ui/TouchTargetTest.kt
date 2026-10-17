package dev.sadking.folio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.reader.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Material's touch target rule on every screen preview: anything tappable must offer at least 48 x
 * 48 dp to a finger, whatever its drawn size. Fails with the list of offenders.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class TouchTargetTest {
    @get:Rule val compose = createComposeRule()

    private val screens: List<Pair<String, @Composable () -> Unit>> =
        listOf(
            "BackupPreview" to { BackupPreview() },
            "BarPreview" to { BarPreview() },
            "BooksFolderPreview" to { BooksFolderPreview() },
            "DetailsPreview" to { DetailsPreview() },
            "DetailsFileMissingPreview" to { DetailsFileMissingPreview() },
            "DetailsChangedPreview" to { DetailsChangedPreview() },
            "DecisionsPreview" to { DecisionsPreview() },
            "DeviceFirstRunPreview" to { DeviceFirstRunPreview() },
            "DeviceOffPreview" to { DeviceOffPreview() },
            "DiscoveryPreview" to { DiscoveryPreview() },
            "EditorPreview" to { EditorPreview() },
            "HardcoverAccountPreview" to { HardcoverAccountPreview() },
            "HardcoverConnectPreview" to { HardcoverConnectPreview() },
            "HardcoverImportPreview" to { HardcoverImportPreview() },
            "HardcoverLinkPreview" to { HardcoverLinkPreview() },
            "HardcoverReadingPreview" to { HardcoverReadingPreview() },
            "HardcoverSearchPreview" to { HardcoverSearchPreview() },
            "ImportResultsPreview" to { ImportResultsPreview() },
            "KindlePreview" to { KindlePreview() },
            "LibraryPreview" to { LibraryPreview() },
            "ListDetailPreview" to { ListDetailPreview() },
            "ListEditorPreview" to { ListEditorPreview() },
            "ListsFirstRunPreview" to { ListsFirstRunPreview() },
            "ListsPreview" to { ListsPreview() },
            "LookupPreview" to { LookupPreview() },
            "LookupResultsPreview" to { LookupResultsPreview() },
            "PairingFirstRunPreview" to { PairingFirstRunPreview() },
            "PairingPreview" to { PairingPreview() },
            "PhoneFolderPreview" to { PhoneFolderPreview() },
            "ReaderContentsPreview" to { ReaderContentsPreview() },
            "ReaderControlsPreview" to { ReaderControlsPreview() },
            "ReaderDevicesEmptyPreview" to { ReaderDevicesEmptyPreview() },
            "ReaderDevicesPreview" to { ReaderDevicesPreview() },
            "ReaderFailedPreview" to { ReaderFailedPreview() },
            "ReaderLinkOfferPreview" to { ReaderLinkOfferPreview() },
            "ReaderListenOfferPreview" to { ReaderListenOfferPreview() },
            "ReaderListenOptionsPreview" to { ReaderListenOptionsPreview() },
            "ReaderListenPreview" to { ReaderListenPreview() },
            "ReaderTextPreview" to { ReaderTextPreview() },
            "ReadingDefaultsPreview" to { ReadingDefaultsPreview() },
            "ReadingTimePreview" to { ReadingTimePreview() },
            "RemovedEmptyPreview" to { RemovedEmptyPreview() },
            "RemovedPreview" to { RemovedPreview() },
            "SettingsFirstRunPreview" to { SettingsFirstRunPreview() },
            "SettingsPreview" to { SettingsPreview() },
            "SetupDonePreview" to { SetupDonePreview() },
            "SetupPreview" to { SetupPreview() },
            "SyncLinePreview" to { SyncLinePreview() },
            "ReaderListenNaturalPreview" to { ReaderListenNaturalPreview() },
            "LicencesPreview" to { LicencesPreview() },
        )

    /** The tablet frame: the rail and both panes. */
    private val tabletScreens: List<Pair<String, @Composable () -> Unit>> =
        listOf(
            "TabletLibraryPreview" to { TabletLibraryPreview() },
            "TabletLibraryEmptyPreview" to { TabletLibraryEmptyPreview() },
            "TabletListsPreview" to { TabletListsPreview() },
            "TabletSettingsPreview" to { TabletSettingsPreview() },
            "TabletLicencesPreview" to { TabletLicencesPreview() },
            "TabletDevicePreview" to { TabletDevicePreview() },
        )

    /** A foldable, flat and half open like a book. */
    private val foldableScreens: List<Pair<String, @Composable () -> Unit>> =
        listOf(
            "TabletLibraryPreview" to { TabletLibraryPreview() },
            "FoldLibraryPreview" to { FoldLibraryPreview() },
            "FoldListsPreview" to { FoldListsPreview() },
            "FoldSettingsPreview" to { FoldSettingsPreview() },
            "FoldDevicePreview" to { FoldDevicePreview() },
        )

    @Test fun everythingTappableIsAtLeast48dp() = check(screens)

    @Test
    @Config(qualifiers = "w840dp-h1280dp-xhdpi")
    fun everythingTappableIsAtLeast48dpOnATablet() = check(tabletScreens)

    @Test
    @Config(qualifiers = "w700dp-h840dp-xhdpi")
    fun everythingTappableIsAtLeast48dpOnAFoldable() = check(foldableScreens)

    private fun check(screens: List<Pair<String, @Composable () -> Unit>>) {
        var current by androidx.compose.runtime.mutableStateOf(screens.first().second)
        compose.setContent { current() }
        val small = mutableListOf<String>()
        for ((name, screen) in screens) {
            current = screen
            compose.waitForIdle()
            val density = compose.density.density
            val nodes =
                compose.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
            nodes.forEach { node ->
                val bounds = node.touchBoundsInRoot
                val w = bounds.width / density
                val h = bounds.height / density
                // Off-screen or zero-size nodes are not visible targets.
                if (w < 1f || h < 1f) return@forEach
                if (w < 47.5f || h < 47.5f) {
                    val label =
                        node.config
                            .getOrNull(SemanticsProperties.ContentDescription)
                            ?.joinToString()
                            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                            ?: node.config.getOrNull(SemanticsProperties.TestTag)
                            ?: "?"
                    small += "$name: '$label' ${w.toInt()} x ${h.toInt()} dp"
                }
            }
        }
        assertTrue(small.joinToString("\n"), small.isEmpty())
    }
}
