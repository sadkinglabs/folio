package dev.sadking.folio

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.core.dictionary.DictionaryPackage
import dev.sadking.folio.data.Book
import dev.sadking.folio.reader.ReaderActivity
import dev.sadking.folio.reader.ReaderViewModel
import dev.sadking.folio.reader.SELECTION_DEFINE
import dev.sadking.folio.reader.SELECTION_MENU
import dev.sadking.folio.reader.dictionary.DEFINE_SHEET_TAG
import dev.sadking.folio.reader.dictionary.DictionaryModel
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * Real WebView: Define is in the selection menu, and with a small dictionary in place it defines an
 * inflected word ("dogs" → dog) without any network, and the sheet comes back after a rotation. The
 * real download is not used here; the dictionary files are written in WNDB format.
 */
class ReaderDefineTest {
    @get:Rule val compose = createEmptyComposeRule()

    /** A two-entry WNDB dictionary, with real byte offsets, marked complete. */
    private fun writeDictionary(dir: File) {
        dir.mkdirs()
        val licence = "  1 This software and database is being provided to you, the LICENSEE.  \n"
        val data = StringBuilder(licence)
        val offset = data.toString().toByteArray().size
        data.append(
            "%08d 05 n 02 dog 0 domestic_dog 0 000 | a member of the genus Canis; \"the dog barked all night\"  \n"
                .format(offset)
        )
        File(dir, "data.noun").writeText(data.toString())
        File(dir, "index.noun").writeText(licence + "dog n 1 0 1 0 %08d  \n".format(offset))
        for (pos in listOf("verb", "adj", "adv")) {
            File(dir, "data.$pos").writeText(licence)
            File(dir, "index.$pos").writeText(licence)
        }
        for (pos in listOf("noun", "verb", "adj", "adv")) File(dir, "$pos.exc").writeText("")
        File(dir, ".complete").writeText(DictionaryPackage.SHA256)
    }

    @Test
    fun defineIsInTheMenuAndDefinesAnInflectedWordOffline() {
        assertTrue(SELECTION_MENU.any { it.first == SELECTION_DEFINE })
        val app = ApplicationProvider.getApplicationContext<FolioApplication>()
        DictionaryModel.remove(app)
        writeDictionary(DictionaryModel.dir(app))
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
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            entry(
                "OPS/book.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Define Fixture</dc:title><dc:identifier id="uid">urn:test:$id</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/></spine></package>""",
            )
            entry(
                "OPS/nav.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">One</a></li></ol></nav></body></html>""",
            )
            entry(
                "OPS/one.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head><body><p>The dogs barked at the gate all night.</p></body></html>""",
            )
        }
        val hash = dev.sadking.folio.core.Epub.sha256(file)
        val dao = app.database.library()
        runBlocking {
            dao.insert(
                Book(
                    id,
                    "Define Fixture",
                    "[]",
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
                fun navigator(): EpubNavigatorFragment? {
                    var nav: EpubNavigatorFragment? = null
                    scenario.onActivity {
                        nav =
                            it.supportFragmentManager.findFragmentByTag("epub")
                                as? EpubNavigatorFragment
                    }
                    return nav
                }
                fun sheetShown() =
                    compose.onAllNodesWithTag(DEFINE_SHEET_TAG).fetchSemanticsNodes().isNotEmpty()
                compose.waitUntil(30_000) { navigator() != null }
                // Select "dogs", as a long press would, then Define.
                compose.waitUntil(30_000) {
                    val nav = navigator() ?: return@waitUntil false
                    runBlocking {
                        withContext(Dispatchers.Main) {
                            nav.evaluateJavascript(
                                """(function(){var p=document.getElementsByTagName('p')[0];""" +
                                    """if(!p)return false;var t=p.firstChild;var i=t.data.indexOf('dogs');""" +
                                    """var r=document.createRange();r.setStart(t,i);r.setEnd(t,i+4);""" +
                                    """var s=getSelection();s.removeAllRanges();s.addRange(r);return true;})()"""
                            )
                        }
                    } == "true"
                }
                scenario.onActivity { it.selectionAction(SELECTION_DEFINE) }
                compose.waitUntil(30_000) { sheetShown() }
                compose.waitUntil(30_000) {
                    compose
                        .onAllNodesWithTag(DEFINE_SHEET_TAG)
                        .fetchSemanticsNodes()
                        .isNotEmpty() &&
                        runCatching {
                                compose.onNodeWithText("a member of the genus Canis").assertExists()
                            }
                            .isSuccess
                }
                compose.onNodeWithText("dog").assertExists()
                compose.onNodeWithText("“the dog barked all night”").assertExists()
                compose.onNodeWithText("Also: domestic dog").assertExists()
                var asked: String? = null
                scenario.onActivity {
                    asked = ViewModelProvider(it)[ReaderViewModel::class.java].defineText
                }
                assertEquals("dogs", asked)

                // A rotation keeps the sheet open.
                scenario.recreate()
                compose.waitUntil(30_000) { sheetShown() }
                compose.waitUntil(30_000) {
                    runCatching {
                            compose.onNodeWithText("a member of the genus Canis").assertExists()
                        }
                        .isSuccess
                }
            }
        } finally {
            DictionaryModel.remove(app)
            runBlocking { dao.deleteLocalBook(id) }
            dir.deleteRecursively()
        }
    }
}
