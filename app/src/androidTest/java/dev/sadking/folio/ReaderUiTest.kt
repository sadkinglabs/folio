package dev.sadking.folio

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.*
import dev.sadking.folio.reader.ReaderActivity
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native UI/Room test. Requires a real Android WebView runtime; not substituted by JVM mocks. */
class ReaderUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun readerOpensBookmarksAndPersistsItsPlace() {
        val app = ApplicationProvider.getApplicationContext<FolioApplication>()
        val id = UUID.randomUUID().toString()
        val dir = File(app.repository.root, id).apply { mkdirs() }
        val file = File(dir, "original.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            entry(
                "book.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Reader Fixture</dc:title><dc:creator>QA Author</dc:creator><dc:identifier id="uid">urn:test:$id</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="chapter"/></spine></package>""",
            )
            entry(
                "nav.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">A quiet beginning</a></li></ol></nav></body></html>""",
            )
            entry(
                "chapter.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>A quiet beginning</title></head><body><h1>A quiet beginning</h1>""" +
                    (1..100).joinToString("") {
                        "<p>A library starts with a book and a place to return. Paragraph $it includes Unicode 🌍 and native reading controls.</p>"
                    } +
                    "</body></html>",
            )
        }
        val hash = dev.sadking.folio.core.Epub.sha256(file)
        val book =
            Book(
                id,
                "Reader Fixture",
                "[\"QA Author\"]",
                originalPath = file.path,
                currentPath = file.path,
                originalHash = hash,
                currentHash = hash,
            )
        runBlocking { app.database.library().insert(book) }
        val intent =
            Intent(app, ReaderActivity::class.java)
                .putExtra("book_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                compose.waitUntil(30000) {
                    compose.onAllNodesWithText("QA Author").fetchSemanticsNodes().isNotEmpty()
                }
                // A title alone can appear before the fragment mounts; wait for its real view.
                compose.waitUntil(30000) {
                    var attached = false
                    scenario.onActivity { activity ->
                        attached =
                            activity.supportFragmentManager
                                .findFragmentByTag("epub")
                                ?.view
                                ?.isAttachedToWindow == true
                    }
                    attached
                }
                // Controls first: no sheet has been opened yet, so taps reach the page.
                QaScreenshots.capture("reader-controls")
                fun controlsShown() =
                    compose
                        .onAllNodesWithContentDescription("Reading appearance")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                QaScreenshots.tapScreenCentre()
                compose.waitUntil(5000) { !controlsShown() }
                QaScreenshots.capture("reader-immersive")
                QaScreenshots.tapScreenCentre()
                compose.waitUntil(5000) { controlsShown() }
                compose.onNodeWithContentDescription("Reading appearance").performClick()
                compose.waitUntil(5000) {
                    compose
                        .onAllNodesWithText("Only for this book")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                QaScreenshots.capture("reader-appearance")
                // Close through the scrim's own action: a tap at its centre would land on the tall
                // sheet.
                compose
                    .onNodeWithContentDescription("Close sheet")
                    .performSemanticsAction(SemanticsActions.OnClick)
                compose.waitUntil(5000) {
                    compose.onAllNodesWithText("Only for this book").fetchSemanticsNodes().isEmpty()
                }
                compose.onNodeWithContentDescription("Bookmark this page").performClick()
                compose.waitUntil(10000) {
                    runBlocking {
                        app.database.library().phoneBookmarks(id, hash).first().isNotEmpty()
                    }
                }
                compose.onNodeWithContentDescription("Contents and bookmarks").performClick()
                // The chapter title also shows in the bottom bar; check the contents entry itself.
                compose.onNode(hasText("A quiet beginning") and hasClickAction()).assertIsSelected()
                compose.onNodeWithText("Bookmarks · 1").performClick()
                compose.onNodeWithContentDescription("Delete bookmark").assertExists()
            }
            val saved = runBlocking { app.database.library().phoneReading(id, hash) }
            assertNotNull(saved)
            assertTrue(saved!!.locator.contains("chapter.xhtml"))
        } finally {
            runBlocking { app.database.library().deleteLocalBook(id) }
            dir.deleteRecursively()
        }
    }
}
