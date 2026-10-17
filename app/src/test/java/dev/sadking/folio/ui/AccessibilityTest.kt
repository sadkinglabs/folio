package dev.sadking.folio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TalkBack's view of every screen adaptive owns, at phone, tablet and foldable sizes: anything
 * tappable has a name and a role, and each screen's title is a heading. Fails with the offenders.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AccessibilityTest {
    @get:Rule val compose = createComposeRule()

    private fun text(id: Int) =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    /** Screens a phone shows, with the titles that must read as headings. */
    private val phone: List<Triple<String, @Composable () -> Unit, List<Int>>> =
        listOf(
            Triple("BackupPreview", { BackupPreview() }, listOf(R.string.settings_backup)),
            Triple("BarPreview", { BarPreview() }, emptyList()),
            Triple(
                "BooksFolderPreview",
                { BooksFolderPreview() },
                listOf(R.string.settings_books_folder),
            ),
            Triple("DetailsPreview", { DetailsPreview() }, emptyList()),
            Triple("DetailsFileMissingPreview", { DetailsFileMissingPreview() }, emptyList()),
            Triple("DetailsChangedPreview", { DetailsChangedPreview() }, emptyList()),
            Triple(
                "DecisionsPreview",
                { DecisionsPreview() },
                listOf(R.string.m9sync_decisions_title),
            ),
            Triple(
                "DeviceFirstRunPreview",
                { DeviceFirstRunPreview() },
                listOf(R.string.device_title),
            ),
            Triple("DeviceOffPreview", { DeviceOffPreview() }, listOf(R.string.device_title)),
            Triple("DiscoveryPreview", { DiscoveryPreview() }, listOf(R.string.discovery_title)),
            Triple("EditorPreview", { EditorPreview() }, emptyList()),
            Triple("HardcoverAccountPreview", { HardcoverAccountPreview() }, emptyList()),
            Triple("HardcoverConnectPreview", { HardcoverConnectPreview() }, emptyList()),
            Triple("HardcoverImportPreview", { HardcoverImportPreview() }, emptyList()),
            Triple("HardcoverLinkPreview", { HardcoverLinkPreview() }, emptyList()),
            Triple("HardcoverReadingPreview", { HardcoverReadingPreview() }, emptyList()),
            Triple("HardcoverSearchPreview", { HardcoverSearchPreview() }, emptyList()),
            Triple(
                "ImportResultsPreview",
                { ImportResultsPreview() },
                listOf(R.string.library_import_results_title),
            ),
            Triple("KindlePreview", { KindlePreview() }, listOf(R.string.device_title)),
            Triple("LibraryPreview", { LibraryPreview() }, listOf(R.string.library_title)),
            Triple("LicencesPreview", { LicencesPreview() }, listOf(R.string.ad6_licences)),
            Triple("ListDetailPreview", { ListDetailPreview() }, emptyList()),
            Triple("ListEditorPreview", { ListEditorPreview() }, emptyList()),
            Triple(
                "ListsFirstRunPreview",
                { ListsFirstRunPreview() },
                listOf(R.string.lists_title),
            ),
            Triple("ListsPreview", { ListsPreview() }, listOf(R.string.lists_title)),
            Triple("LookupPreview", { LookupPreview() }, emptyList()),
            Triple("LookupResultsPreview", { LookupResultsPreview() }, emptyList()),
            Triple("PairingFirstRunPreview", { PairingFirstRunPreview() }, emptyList()),
            Triple("PairingPreview", { PairingPreview() }, emptyList()),
            Triple(
                "PhoneFolderPreview",
                { PhoneFolderPreview() },
                listOf(R.string.settings_folder),
            ),
            Triple(
                "ReadingTimePreview",
                { ReadingTimePreview() },
                listOf(R.string.reading_time_title),
            ),
            Triple(
                "RemovedEmptyPreview",
                { RemovedEmptyPreview() },
                listOf(R.string.library_removed_title),
            ),
            Triple("RemovedPreview", { RemovedPreview() }, listOf(R.string.library_removed_title)),
            Triple(
                "SettingsFirstRunPreview",
                { SettingsFirstRunPreview() },
                listOf(R.string.settings_title),
            ),
            Triple(
                "SettingsPreview",
                { SettingsPreview() },
                listOf(
                    R.string.settings_title,
                    R.string.settings_appearance,
                    R.string.settings_library,
                    R.string.settings_about,
                ),
            ),
            Triple("SetupDonePreview", { SetupDonePreview() }, emptyList()),
            Triple("SetupPreview", { SetupPreview() }, emptyList()),
            Triple("SyncLinePreview", { SyncLinePreview() }, emptyList()),
        )

    /** The tablet frame: rail and panes. Titles are those of both panes. */
    private val tablet: List<Triple<String, @Composable () -> Unit, List<Int>>> =
        listOf(
            Triple(
                "TabletLibraryPreview",
                { TabletLibraryPreview() },
                listOf(R.string.library_title),
            ),
            Triple(
                "TabletLibraryEmptyPreview",
                { TabletLibraryEmptyPreview() },
                listOf(R.string.library_title),
            ),
            Triple("TabletListsPreview", { TabletListsPreview() }, listOf(R.string.lists_title)),
            Triple(
                "TabletSettingsPreview",
                { TabletSettingsPreview() },
                listOf(R.string.settings_title),
            ),
            Triple(
                "TabletLicencesPreview",
                { TabletLicencesPreview() },
                listOf(R.string.settings_title, R.string.ad6_licences),
            ),
            Triple("TabletDevicePreview", { TabletDevicePreview() }, listOf(R.string.device_title)),
        )

    /** A foldable, flat (rail, one pane) and half open like a book (two panes at the hinge). */
    private val foldable: List<Triple<String, @Composable () -> Unit, List<Int>>> =
        // Flat, a medium window shows one pane: the open book or list covers the Library and Lists.
        tablet
            .filter { it.first != "TabletLicencesPreview" }
            .map {
                if (it.first == "TabletLibraryPreview" || it.first == "TabletListsPreview")
                    it.copy(third = emptyList())
                else it
            } +
            listOf(
                Triple(
                    "FoldLibraryPreview",
                    { FoldLibraryPreview() },
                    listOf(R.string.library_title),
                ),
                Triple("FoldListsPreview", { FoldListsPreview() }, listOf(R.string.lists_title)),
                Triple(
                    "FoldSettingsPreview",
                    { FoldSettingsPreview() },
                    listOf(R.string.settings_title),
                ),
                Triple("FoldDevicePreview", { FoldDevicePreview() }, listOf(R.string.device_title)),
            )

    /** The descriptions Material gives a bottom sheet's scrim and drag handle. */
    private val materialSheetParts = setOf("Close sheet", "Drag handle")

    /** A node TalkBack can name: a description, its own or merged text, or a text field. */
    private fun named(config: androidx.compose.ui.semantics.SemanticsConfiguration): Boolean =
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {
            it.isNotBlank()
        } ||
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.isNotBlank() } ||
            config.getOrNull(SemanticsProperties.EditableText) != null

    private fun check(screens: List<Triple<String, @Composable () -> Unit, List<Int>>>) {
        var current by mutableStateOf(screens.first().second)
        compose.setContent { current() }
        val problems = mutableListOf<String>()
        for ((name, screen, titles) in screens) {
            current = screen
            compose.waitForIdle()
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
                val config = node.config
                val label =
                    config.getOrNull(SemanticsProperties.TestTag)
                        ?: config.getOrNull(SemanticsProperties.Text)?.joinToString()
                        ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                        ?: "?"
                if (!named(config)) problems += "$name: a tappable node has no name ($label)"
                // Text fields take text, not a role; a bottom sheet's scrim and drag handle are
                // Material's own, named by it. Everything else says what it is.
                val field = config.getOrNull(SemanticsActions.SetText) != null
                val sheetPart =
                    config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {
                        it in materialSheetParts
                    }
                if (!field && !sheetPart && config.getOrNull(SemanticsProperties.Role) == null)
                    problems += "$name: '$label' has no role"
            }
            titles.forEach { id ->
                val title = text(id)
                val found =
                    compose
                        .onAllNodes(isHeading() and hasText(title, ignoreCase = true))
                        .fetchSemanticsNodes()
                if (found.isEmpty()) problems += "$name: '$title' is not a heading"
            }
        }
        assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    @Test @Config(qualifiers = "w412dp-h892dp-xxhdpi") fun phoneScreens() = check(phone)

    @Test @Config(qualifiers = "w840dp-h1280dp-xhdpi") fun tabletScreens() = check(tablet)

    @Test @Config(qualifiers = "w700dp-h840dp-xhdpi") fun foldableScreens() = check(foldable)

    /** The colours tablets add: a chosen settings row and the rail. Text stays at 4.5:1. */
    @Test
    fun newGroundsKeepTextReadable() {
        val schemes = mutableListOf<androidx.compose.material3.ColorScheme>()
        compose.setContent {
            FolioTheme(dark = false, dynamic = false) { schemes += MaterialTheme.colorScheme }
            FolioTheme(dark = true, dynamic = false) { schemes += MaterialTheme.colorScheme }
        }
        compose.waitForIdle()
        fun ratio(a: Color, b: Color): Double {
            val x = a.compositeOver(b).luminance() + 0.05
            val y = b.luminance() + 0.05
            return maxOf(x, y) / minOf(x, y)
        }
        val failures = mutableListOf<String>()
        schemes.forEachIndexed { i, c ->
            val pairs =
                mapOf(
                    "onSurface on secondaryContainer" to ratio(c.onSurface, c.secondaryContainer),
                    "onSurfaceVariant on secondaryContainer" to
                        ratio(c.onSurfaceVariant, c.secondaryContainer),
                    "onSurfaceVariant on surfaceContainer (rail)" to
                        ratio(c.onSurfaceVariant, c.surfaceContainer),
                    "onSecondaryContainer on secondaryContainer (rail)" to
                        ratio(c.onSecondaryContainer, c.secondaryContainer),
                )
            pairs.forEach { (pair, value) ->
                if (value < 4.5) failures += "${if (i == 0) "light" else "dark"} $pair: $value"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
