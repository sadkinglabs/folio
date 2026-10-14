package dev.sadking.folio

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.PhoneReading
import dev.sadking.folio.reader.ReaderActivity
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real WebView: KOReader numbers every spine item, including `linear="no"` ones, so a chapter after
 * a non-linear notes page must be captured as DocFragment[3], and the place must survive
 * recreation.
 */
class ReaderSpineTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun capturesFullSpineIndexAndKeepsPlaceAcrossRecreation() {
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
                    (1..40).joinToString("") {
                        "<p>$title paragraph $it with enough text to wrap.</p>"
                    } +
                    "</body></html>"
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            entry(
                "OPS/book.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Spine Fixture</dc:title><dc:creator>QA Author</dc:creator><dc:identifier id="uid">urn:test:$id</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/><itemref idref="notes" linear="no"/><itemref idref="two"/></spine></package>""",
            )
            entry("OPS/one.xhtml", chapter("Chapter one"))
            entry("OPS/notes.xhtml", chapter("Notes"))
            entry("OPS/two.xhtml", chapter("Chapter two"))
        }
        val hash = dev.sadking.folio.core.Epub.sha256(file)
        runBlocking {
            app.database
                .library()
                .insert(
                    Book(
                        id,
                        "Spine Fixture",
                        "[\"QA Author\"]",
                        originalPath = file.path,
                        currentPath = file.path,
                        originalHash = hash,
                        currentHash = hash,
                    )
                )
            // Start in chapter two, as if the reader had been closed there.
            app.database
                .library()
                .savePhoneReading(
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
        fun saved() = runBlocking { app.database.library().phoneReading(id, hash) }
        try {
            ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                compose.waitUntil(30000) { saved()?.xpointer?.isNotEmpty() == true }
                val pointer = saved()!!.xpointer
                assertTrue(pointer, pointer.startsWith("/body/DocFragment[3]/"))

                scenario.recreate()
                // A fresh save after recreation must still be in chapter two.
                val before = saved()!!.updatedAt
                compose.waitUntil(30000) { saved()!!.updatedAt > before }
                val after = saved()!!
                assertTrue(after.locator, after.locator.contains("two.xhtml"))
            }
        } finally {
            runBlocking { app.database.library().deleteLocalBook(id) }
            dir.deleteRecursively()
        }
    }
}
