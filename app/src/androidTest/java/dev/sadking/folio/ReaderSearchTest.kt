package dev.sadking.folio

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.Book
import dev.sadking.folio.reader.READER_SEARCH_SHEET_TAG
import dev.sadking.folio.reader.ReaderActivity
import dev.sadking.folio.reader.ReaderPanel
import dev.sadking.folio.reader.ReaderViewModel
import dev.sadking.folio.reader.SearchStatus
import dev.sadking.folio.reader.readerSearchHitTag
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * Real WebView and Readium search: a word in chapter three is found from chapter one, a tap on the
 * match moves the page there and draws the search highlight, and both survive a rotation.
 */
class ReaderSearchTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun fixture(file: File, id: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            fun chapter(title: String, extra: String = "") =
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body><h1>$title</h1>""" +
                    (1..30).joinToString("") {
                        "<p>$title paragraph $it with enough plain text.</p>"
                    } +
                    extra +
                    "</body></html>"
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            entry(
                "OPS/book.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Search Fixture</dc:title><dc:creator>QA Author</dc:creator><dc:identifier id="uid">urn:test:$id</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/><item id="three" href="three.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/><itemref idref="two"/><itemref idref="three"/></spine></package>""",
            )
            entry(
                "OPS/nav.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">Chapter one</a></li><li><a href="two.xhtml">Chapter two</a></li><li><a href="three.xhtml">Chapter three</a></li></ol></nav></body></html>""",
            )
            entry("OPS/one.xhtml", chapter("Chapter one"))
            entry("OPS/two.xhtml", chapter("Chapter two"))
            entry(
                "OPS/three.xhtml",
                chapter("Chapter three", "<p>At the gate stood an old Wälnut tree.</p>"),
            )
        }
    }

    @Test
    fun findsAWordTapGoesThereAndTheHighlightSurvivesRotation() {
        val app = ApplicationProvider.getApplicationContext<FolioApplication>()
        val id = UUID.randomUUID().toString()
        val dir = File(app.repository.root, id).apply { mkdirs() }
        val file = File(dir, "original.epub")
        fixture(file, id)
        val hash = dev.sadking.folio.core.Epub.sha256(file)
        val dao = app.database.library()
        runBlocking {
            dao.insert(
                Book(
                    id,
                    "Search Fixture",
                    "[\"QA Author\"]",
                    originalPath = file.path,
                    currentPath = file.path,
                    originalHash = hash,
                    currentHash = hash,
                )
            )
        }
        val intent =
            Intent(app, ReaderActivity::class.java)
                .putExtra("book_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                fun model(): ReaderViewModel {
                    var found: ReaderViewModel? = null
                    scenario.onActivity {
                        found = ViewModelProvider(it)[ReaderViewModel::class.java]
                    }
                    return found!!
                }
                fun navigator(): EpubNavigatorFragment? {
                    var nav: EpubNavigatorFragment? = null
                    scenario.onActivity {
                        nav =
                            it.supportFragmentManager.findFragmentByTag("epub")
                                as? EpubNavigatorFragment
                    }
                    return nav
                }
                fun searchDecorations(): Int {
                    val nav = navigator() ?: return 0
                    val raw =
                        runBlocking {
                            withContext(Dispatchers.Main) {
                                nav.evaluateJavascript(
                                    "JSON.stringify(readium.getDecorations('search').items.length)"
                                )
                            }
                        } ?: return 0
                    return (JSONTokener(raw).nextValue() as? String)?.toIntOrNull() ?: 0
                }
                compose.waitUntil(30_000) { navigator() != null }

                // Search from chapter one; accents and case do not matter.
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderViewModel::class.java]
                    model.panel = ReaderPanel.SEARCH
                    model.editSearch("walnut")
                    model.search("walnut")
                }
                compose.waitUntil(30_000) { model().searchResults.status == SearchStatus.DONE }
                val hits = model().searchResults.hits
                assertEquals(1, hits.size)
                assertEquals("Chapter three", hits.single().chapter)
                assertEquals("Wälnut", hits.single().match)

                // A tap on the match closes the sheet, moves the page and highlights the word.
                compose.onNodeWithTag(readerSearchHitTag(0)).performClick()
                compose.waitUntil(30_000) {
                    navigator()?.currentLocator?.value?.href?.toString()?.endsWith("three.xhtml") ==
                        true
                }
                compose.waitUntil(30_000) {
                    runCatching { searchDecorations() == 1 }.getOrDefault(false)
                }
                var applied = 0
                scenario.onActivity { applied = it.appliedSearch.size }
                assertEquals(1, applied)

                // Rotation rebuilds the activity: the place, the highlight and the query come back.
                scenario.recreate()
                compose.waitUntil(30_000) { navigator() != null }
                compose.waitUntil(30_000) {
                    navigator()?.currentLocator?.value?.href?.toString()?.endsWith("three.xhtml") ==
                        true
                }
                compose.waitUntil(30_000) {
                    runCatching { searchDecorations() == 1 }.getOrDefault(false)
                }
                assertEquals("walnut", model().searchText)
                // Reopening the search shows the same results; closing it removes the highlight.
                scenario.onActivity {
                    ViewModelProvider(it)[ReaderViewModel::class.java].panel = ReaderPanel.SEARCH
                }
                compose.waitUntil(30_000) {
                    compose
                        .onAllNodesWithTag(READER_SEARCH_SHEET_TAG)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                scenario.recreate()
                compose.waitUntil(30_000) {
                    compose
                        .onAllNodesWithTag(READER_SEARCH_SHEET_TAG)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                assertEquals(1, model().searchResults.hits.size)
                // Back to the focused window, the sheet, delivered and handled before going on: a
                // system Back through accessibility can be swallowed on a busy emulator.
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitUntil(30_000) { model().shownHit == null }
                compose.waitUntil(30_000) {
                    runCatching { searchDecorations() == 0 }.getOrDefault(false)
                }
                assertTrue(model().panel == null)
            }
        } finally {
            runBlocking { dao.deleteLocalBook(id) }
            dir.deleteRecursively()
        }
    }
}
