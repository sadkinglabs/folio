package dev.sadking.folio

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.*
import dev.sadking.folio.ui.*
import java.io.File
import java.util.zip.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The real app at tablet sizes on the emulator: the window size is forced with Compose's
 * configuration override, rotation is a size change, and recreation is the state restorer's
 * save-and-restore. A book opened beside the Library stays open through a rotation and a
 * recreation; on a phone-sized window it covers the Library, and Back returns to one pane.
 */
class AdaptiveUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app
        get() = ApplicationProvider.getApplicationContext<FolioApplication>()

    private fun epub(title: String, author: String): File {
        val file = File(app.cacheDir, "adaptive-${title.hashCode()}.epub")
        ZipOutputStream(file.outputStream()).use { z ->
            fun entry(name: String, text: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""",
            )
            entry(
                "book.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title><dc:creator>$author</dc:creator><dc:identifier id="uid">urn:folio:fixture:${title.hashCode()}</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>""",
            )
            entry(
                "chapter.xhtml",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Adaptive fixture.</p></body></html>",
            )
        }
        return file
    }

    @Before
    fun seed() = runBlocking {
        require(android.os.Build.HARDWARE in listOf("ranchu", "goldfish")) {
            "Fixtures run only on an emulator"
        }
        require(
            app.database.library().allBooks().all {
                decode(it.identifiers).any { id -> id.startsWith("urn:folio:fixture:") }
            } && app.database.library().allWishes().all { it.details.contains("FOLIO-QA") }
        ) {
            "Refusing to replace non-fixture library data"
        }
        app.database.clearAllTables()
        app.getSharedPreferences("library-view", 0).edit().clear().commit()
        app.repository.import(Uri.fromFile(epub("Middlemarch", "George Eliot")))
        app.repository.import(Uri.fromFile(epub("Persuasion", "Jane Austen")))
    }

    private fun waitFor(what: String, matcher: SemanticsMatcher) {
        try {
            compose.waitUntil(30000) {
                compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(
                "Timed out waiting for $what. ${Diagnostics.state()}\nScreen:\n" +
                    compose.onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE),
                timeout,
            )
        }
    }

    private fun gone(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()

    @Test
    fun aBookBesideTheLibraryKeepsItsPlaceThroughRotationRecreationAndFolding() {
        var size by mutableStateOf(DpSize(840.dp, 1280.dp))
        val restorer = StateRestorationTester(compose)
        val model = ViewModelProvider(compose.activity)[LibraryViewModel::class.java]
        restorer.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                FolioApp(model)
            }
        }
        val middlemarch = hasContentDescription("Middlemarch, George Eliot") and hasClickAction()
        waitFor("the library", middlemarch)
        // A tablet: the rail instead of the bar, and an empty pane beside the Library.
        compose.onNodeWithTag(FOLIO_RAIL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FOLIO_BAR_TAG).assertDoesNotExist()
        compose.onNodeWithTag(EMPTY_PANE_TAG).assertIsDisplayed()

        compose.onAllNodes(middlemarch).onFirst().performClick()
        waitFor("the book beside the Library", hasTestTag(BOOK_PRIMARY_TAG))
        compose.onNodeWithTag(DETAIL_PANE_TAG).assertIsDisplayed()
        compose.onAllNodes(middlemarch).onFirst().assertIsDisplayed()

        // Choosing another book replaces the one on the right.
        compose
            .onAllNodes(hasContentDescription("Persuasion, Jane Austen") and hasClickAction())
            .onFirst()
            .performClick()
        waitFor("Persuasion on the right", hasText("Persuasion") and isHeading())

        // Rotation to landscape: still two panes, the same book.
        size = DpSize(1280.dp, 800.dp)
        compose.waitForIdle()
        compose.onNodeWithTag(DETAIL_PANE_TAG).assertIsDisplayed()
        compose.onAllNodes(hasText("Persuasion") and isHeading()).onFirst().assertIsDisplayed()

        // The activity rebuilt (rotation without configChanges): the book is still chosen.
        restorer.emulateSavedInstanceStateRestore()
        waitFor("the book after recreation", hasTestTag(BOOK_PRIMARY_TAG))
        compose.onNodeWithTag(DETAIL_PANE_TAG).assertIsDisplayed()

        // Folded to a phone: the book covers the Library, as on a phone, with no bar.
        size = DpSize(412.dp, 892.dp)
        compose.waitForIdle()
        compose.onNodeWithTag(FOLIO_RAIL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(BOOK_PRIMARY_TAG).assertIsDisplayed()
        compose.waitUntil(5000) { gone(middlemarch) }

        // Back returns to the Library, one pane, with the bar.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor("the Library again", middlemarch)
        compose.onNodeWithTag(FOLIO_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(BOOK_PRIMARY_TAG).assertDoesNotExist()
    }

    @Test
    fun settingsShowTheChosenGroupBesideTheRowsOnATablet() {
        val model = ViewModelProvider(compose.activity)[LibraryViewModel::class.java]
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(1280.dp, 800.dp))
            ) {
                FolioApp(model)
            }
        }
        waitFor("the rail", hasTestTag(FOLIO_RAIL_TAG))
        compose.onNode(hasText("Settings") and hasClickAction()).performClick()
        compose.onNodeWithTag(SETTINGS_LICENCES_TAG).performScrollTo().performClick()
        // Beside the rows, not in a sheet: the rows stay on screen.
        compose.onNodeWithTag(SETTINGS_PANE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(LICENCES_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SETTINGS_LICENCES_TAG).assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Open English WordNet", substring = true).assertExists()
    }
}
