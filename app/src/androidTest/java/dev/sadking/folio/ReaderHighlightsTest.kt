package dev.sadking.folio

import android.content.Intent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.BookAnnotation
import dev.sadking.folio.data.PhoneReading
import dev.sadking.folio.reader.HIGHLIGHT_NOTE_DIALOG_TAG
import dev.sadking.folio.reader.PHONE_ANNOTATION_DEVICE
import dev.sadking.folio.reader.ReaderActivity
import dev.sadking.folio.reader.SELECTION_HIGHLIGHT
import dev.sadking.folio.reader.SELECTION_NOTE
import dev.sadking.folio.reader.annotationId
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * Real WebView: highlights from the e-reader are drawn where their KOReader pointers point, one
 * that cannot be placed is kept, and a passage highlighted on the phone is stored with pointers
 * that the reader places again (the round trip KOReader relies on).
 */
class ReaderHighlightsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun drawsKindleHighlightsAndRoundTripsAPhoneHighlight() {
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
            fun chapter(title: String) =
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body><h1>$title</h1>""" +
                    (1..20).joinToString("") { "<p>$title paragraph $it with enough text.</p>" } +
                    "</body></html>"
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            entry(
                "OPS/book.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Highlight Fixture</dc:title><dc:creator>QA Author</dc:creator><dc:identifier id="uid">urn:test:$id</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/><itemref idref="notes" linear="no"/><itemref idref="two"/></spine></package>""",
            )
            entry(
                "OPS/nav.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">Chapter one</a></li><li><a href="two.xhtml">Chapter two</a></li></ol></nav></body></html>""",
            )
            entry("OPS/one.xhtml", chapter("Chapter one"))
            entry("OPS/notes.xhtml", chapter("Notes"))
            entry("OPS/two.xhtml", chapter("Chapter two"))
        }
        val hash = dev.sadking.folio.core.Epub.sha256(file)
        val dao = app.database.library()
        // Chapter two is DocFragment[3]: the notes page counts in KOReader's numbering.
        // <p>[1] is "Chapter two paragraph 1 ..." (the <h1> is not a <p>).
        val kindle =
            BookAnnotation(
                "kindle-$id",
                id,
                "link:kindle",
                "paragraph",
                "",
                "Chapter two",
                "2026-10-16 08:00:00",
                "/body/DocFragment[3]/body/p[1]/text().12",
                "/body/DocFragment[3]/body/p[1]/text().21",
                "highlight",
            )
        val lost =
            kindle.copy(
                id = "lost-$id",
                position = "/body/DocFragment[3]/body/p[999]/text().0",
                endPosition = "/body/DocFragment[3]/body/p[999]/text().4",
            )
        runBlocking {
            dao.insert(
                Book(
                    id,
                    "Highlight Fixture",
                    "[\"QA Author\"]",
                    originalPath = file.path,
                    currentPath = file.path,
                    originalHash = hash,
                    currentHash = hash,
                )
            )
            dao.annotation(kindle)
            dao.annotation(lost)
            dao.savePhoneReading(
                PhoneReading(
                    id,
                    hash,
                    """{"href":"OPS/two.xhtml","type":"application/xhtml+xml","locations":{"progression":0.0}}""",
                    "",
                    null,
                    System.currentTimeMillis(),
                )
            )
        }
        val intent =
            Intent(app, ReaderActivity::class.java)
                .putExtra("book_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                fun page(script: String): String? {
                    var navigator: EpubNavigatorFragment? = null
                    scenario.onActivity {
                        navigator =
                            it.supportFragmentManager.findFragmentByTag("epub")
                                as? EpubNavigatorFragment
                    }
                    val nav = navigator ?: return null
                    return runBlocking {
                        withContext(Dispatchers.Main) { nav.evaluateJavascript(script) }
                    }
                }
                /** Decoration ids and their text in the page on screen. */
                fun drawn(): Map<String, String> {
                    val raw =
                        page(
                            "JSON.stringify(readium.getDecorations('highlights').items" +
                                ".map(i => [i.decoration.id, i.range.toString()]))"
                        ) ?: return emptyMap()
                    val text = JSONTokener(raw).nextValue() as? String ?: return emptyMap()
                    val items = JSONArray(text)
                    return (0 until items.length()).associate {
                        items.getJSONArray(it).getString(0) to items.getJSONArray(it).getString(1)
                    }
                }

                // 1. The e-reader's highlight is drawn on its words; the lost one is not drawn.
                compose.waitUntil(30000) {
                    runCatching { kindle.id in drawn() }.getOrDefault(false)
                }
                assertEquals("paragraph", drawn()[kindle.id])
                assertTrue(lost.id !in drawn())

                // 2. Select "enough" in paragraph 2 and tap Highlight.
                page(
                    """(function(){var t=document.getElementsByTagName('p')[1].firstChild;""" +
                        """var i=t.data.indexOf('enough');var r=document.createRange();""" +
                        """r.setStart(t,i);r.setEnd(t,i+6);var s=getSelection();""" +
                        """s.removeAllRanges();s.addRange(r);return true;})()"""
                )
                scenario.onActivity { it.selectionAction(SELECTION_HIGHLIGHT) }
                fun phoneRows() =
                    runBlocking { dao.allAnnotations() }
                        .filter { it.bookId == id && it.deviceId == PHONE_ANNOTATION_DEVICE }
                compose.waitUntil(30000) { phoneRows().isNotEmpty() }
                val made = phoneRows().single()
                assertEquals("enough", made.text)
                assertEquals("highlight", made.kind)
                assertTrue(
                    made.position,
                    made.position.startsWith("/body/DocFragment[3]/body/p[2]/"),
                )
                assertTrue(
                    made.endPosition,
                    made.endPosition.startsWith("/body/DocFragment[3]/body/p[2]/"),
                )
                assertTrue(
                    made.date,
                    Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$").matches(made.date),
                )
                assertEquals(annotationId("phone", made.date, made.position), made.id)
                assertEquals("Chapter two", made.chapter)

                // 3. Its pointers place it again on the same words.
                compose.waitUntil(30000) { runCatching { made.id in drawn() }.getOrDefault(false) }
                assertEquals("enough", drawn()[made.id])

                // 4. Add note: the dialog asks for the note, Save stores a "note" row.
                page(
                    """(function(){var t=document.getElementsByTagName('p')[2].firstChild;""" +
                        """var i=t.data.indexOf('text');var r=document.createRange();""" +
                        """r.setStart(t,i);r.setEnd(t,i+4);var s=getSelection();""" +
                        """s.removeAllRanges();s.addRange(r);return true;})()"""
                )
                scenario.onActivity { it.selectionAction(SELECTION_NOTE) }
                compose.waitUntil(30000) {
                    compose
                        .onAllNodesWithTag(HIGHLIGHT_NOTE_DIALOG_TAG)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNode(hasSetTextAction()).performTextInput("Mine")
                compose.onNodeWithText("Save").performClick()
                compose.waitUntil(30000) { phoneRows().any { it.kind == "note" } }
                val note = phoneRows().single { it.kind == "note" }
                assertEquals("text", note.text)
                assertEquals("Mine", note.note)
                assertTrue(
                    note.position,
                    note.position.startsWith("/body/DocFragment[3]/body/p[3]/"),
                )
                compose.waitUntil(30000) { runCatching { note.id in drawn() }.getOrDefault(false) }

                // Both e-reader rows are still stored.
                val stored = runBlocking { dao.allAnnotations() }.map { it.id }
                assertTrue(kindle.id in stored && lost.id in stored)
            }
        } finally {
            runBlocking {
                dao.deleteLocalAnnotations(id)
                dao.deleteLocalBook(id)
            }
            dir.deleteRecursively()
        }
    }
}
