package dev.sadking.folio

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.sadking.folio.data.*
import dev.sadking.folio.ui.*
import java.io.File
import java.util.zip.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The real app at 200% system font size: main actions stay reachable, and CI keeps captures. */
class LargeFontUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app
        get() = ApplicationProvider.getApplicationContext<FolioApplication>()

    private val automation
        get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
            it.readBytes()
        }
    }

    private fun epub(title: String, author: String): File {
        val file = File(app.cacheDir, "large-font-${title.hashCode()}.epub")
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
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Large font fixture.</p></body></html>",
            )
        }
        return file
    }

    @Before
    fun seedAndEnlarge() = runBlocking {
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
        app.repository.import(Uri.fromFile(epub("The Count of Monte Cristo", "Alexandre Dumas")))
        shell("settings put system font_scale 2.0")
        Thread.sleep(1000)
    }

    @After
    fun restoreFontSize() {
        shell("settings put system font_scale 1.0")
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(30000, condition)
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(
                "Timed out waiting for $what. ${Diagnostics.state()}\nScreen:\n" +
                    compose.onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE),
                timeout,
            )
        }
    }

    @Test
    fun mainScreensWorkAtDoubleFontSize() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Covers carry no caption; TalkBack and the tests read "Title, Author" instead.
            val middlemarch =
                hasContentDescription("Middlemarch, George Eliot") and hasClickAction()
            waitFor("the library") {
                compose.onAllNodes(middlemarch).fetchSemanticsNodes().isNotEmpty()
            }
            QaScreenshots.capture("library-font200")

            compose.onNode(middlemarch).performClick()
            val send = hasTestTag(BOOK_ACTION_SEND_TAG)
            waitFor("the book page") { compose.onAllNodes(send).fetchSemanticsNodes().isNotEmpty() }
            // The primary actions must be reachable, scrolling if the large text pushes them down.
            compose.onNodeWithTag(BOOK_PRIMARY_TAG).performScrollTo().assertIsDisplayed()
            compose.onNode(send).performScrollTo().assertIsDisplayed()
            QaScreenshots.capture("book-font200")

            // Back straight to the activity: a system Back can land on another window on top.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor("the library again") {
                compose.onAllNodes(send).fetchSemanticsNodes().isEmpty()
            }
            compose.onNode(hasText("Device") and hasClickAction()).performClick()
            compose.waitForIdle()
            // The Link card and its switch stay on screen at 200%.
            compose.onNodeWithTag(DEVICE_LINK_CARD_TAG).assertIsDisplayed()
            compose.onNodeWithTag(DEVICE_LINK_SWITCH_TAG).assertIsDisplayed()
            QaScreenshots.capture("device-font200")
            compose.onNode(hasText("Settings") and hasClickAction()).performClick()
            compose.waitForIdle()
            // Theme segments drop under their row at 200%; the wallpaper switch stays reachable.
            compose.onNodeWithTag(SETTINGS_THEME_TAG).assertIsDisplayed()
            compose.onNodeWithTag(SETTINGS_WALLPAPER_TAG).performScrollTo().assertIsDisplayed()
            QaScreenshots.capture("settings-font200")
        }
    }
}
