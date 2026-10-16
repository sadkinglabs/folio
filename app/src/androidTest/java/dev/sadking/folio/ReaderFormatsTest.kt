package dev.sadking.folio

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.readium.r2.navigator.OverflowableNavigator

/**
 * PDF and comic books open in the phone reader, save a Readium place and page through it. Other
 * formats explain that they open on the e-reader. Generated fixtures only; no copyrighted content.
 */
class ReaderFormatsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app
        get() = ApplicationProvider.getApplicationContext<FolioApplication>()

    /** Registers a book whose file [write] creates; returns its id and file hash. */
    private fun book(format: String, write: (File) -> Unit): Pair<String, String> {
        val id = UUID.randomUUID().toString()
        val dir = File(app.repository.root, id).apply { mkdirs() }
        val file = File(dir, "original.$format")
        write(file)
        val hash = dev.sadking.folio.core.Epub.sha256(file)
        runBlocking {
            app.database
                .library()
                .insert(
                    Book(
                        id,
                        "Fixture",
                        "[\"QA\"]",
                        originalPath = file.path,
                        currentPath = file.path,
                        originalHash = hash,
                        currentHash = hash,
                        format = format,
                    )
                )
        }
        return id to hash
    }

    private fun writePdf(file: File) {
        val document = PdfDocument()
        val paint = Paint().apply { textSize = 24f }
        for (n in 1..3) {
            val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, n).create())
            page.canvas.drawText("Page $n", 40f, 60f, paint)
            document.finishPage(page)
        }
        file.outputStream().use { document.writeTo(it) }
        document.close()
    }

    private fun writeCbz(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for (n in 1..3) {
                zip.putNextEntry(ZipEntry("00$n.png"))
                val picture = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
                picture.eraseColor(Color.rgb(40 * n, 90, 160))
                picture.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("ComicInfo.xml"))
            zip.write("<ComicInfo/>".toByteArray())
            zip.closeEntry()
        }
    }

    private fun saved(id: String, hash: String) = runBlocking {
        app.database.library().phoneReading(id, hash)
    }

    private fun pageOf(reading: PhoneReading?): Int =
        reading?.let { JSONObject(it.locator).optJSONObject("locations")?.optInt("position") } ?: 0

    /** True once the reader's fragment with [tag] is on screen. */
    private fun shown(scenario: ActivityScenario<ReaderActivity>, tag: String): Boolean {
        var attached = false
        scenario.onActivity {
            attached =
                it.supportFragmentManager.findFragmentByTag(tag)?.view?.isAttachedToWindow == true
        }
        return attached
    }

    private fun turnPage(scenario: ActivityScenario<ReaderActivity>, tag: String) {
        scenario.onActivity {
            (it.supportFragmentManager.findFragmentByTag(tag) as OverflowableNavigator).goForward(
                false
            )
        }
    }

    private fun <T> read(id: String, block: (ActivityScenario<ReaderActivity>) -> T): T {
        val intent =
            Intent(app, ReaderActivity::class.java)
                .putExtra("book_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            return ActivityScenario.launch<ReaderActivity>(intent).use(block)
        } finally {
            runBlocking { app.database.library().deleteLocalBook(id) }
            File(app.repository.root, id).deleteRecursively()
        }
    }

    @Test
    fun pdfOpensAndSavesAReadiumPlace() {
        val (id, hash) = book("pdf", ::writePdf)
        read(id) { scenario ->
            compose.waitUntil(30000) { shown(scenario, "pdf") }
            compose.waitUntil(30000) { saved(id, hash) != null }
            val place = saved(id, hash)!!
            assertEquals("", place.xpointer)
            // The place is stored as JSON, where "/" may be written as "\\/": read the field.
            assertEquals(
                place.locator,
                "application/pdf",
                org.json.JSONObject(place.locator).getString("type"),
            )
            turnPage(scenario, "pdf")
            compose.waitUntil(30000) { pageOf(saved(id, hash)) >= 2 }
        }
    }

    @Test
    fun comicOpensAndSavesItsPage() {
        val (id, hash) = book("cbz", ::writeCbz)
        read(id) { scenario ->
            compose.waitUntil(30000) { shown(scenario, "comic") }
            compose.waitUntil(30000) { saved(id, hash) != null }
            assertTrue(saved(id, hash)!!.locator.contains("001.png"))
            turnPage(scenario, "comic")
            compose.waitUntil(30000) { saved(id, hash)!!.locator.contains("002.png") }
        }
    }

    @Test
    fun unknownFormatExplainsItOpensOnTheEreader() {
        val (id, _) = book("mobi") { it.writeBytes(byteArrayOf(1, 2, 3)) }
        read(id) {
            val text = app.getString(R.string.reader_m9_format_unsupported)
            compose.waitUntil(30000) {
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }
}
