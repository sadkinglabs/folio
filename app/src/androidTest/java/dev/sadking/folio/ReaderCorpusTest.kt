package dev.sadking.folio

import android.content.Intent
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real WebView corpus: unusual but legitimate EPUBs must open and save a place, and broken ones
 * must show an error instead of crashing. Generated fixtures only; no copyrighted content.
 */
class ReaderCorpusTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app
        get() = ApplicationProvider.getApplicationContext<FolioApplication>()

    private fun xhtml(body: String, attributes: String = "") =
        """<html xmlns="http://www.w3.org/1999/xhtml"$attributes><head><title>T</title></head><body>$body</body></html>"""

    /** Builds and registers a book; chapters are (href, content) in spine order. */
    private fun book(
        chapters: List<Pair<String, String>>,
        extra: Map<String, ByteArray> = emptyMap(),
        manifestExtra: String = "",
    ): Pair<String, String> {
        val id = UUID.randomUUID().toString()
        val dir = File(app.repository.root, id).apply { mkdirs() }
        val file = File(dir, "original.epub")
        val manifest = chapters.mapIndexed { i, (href, _) ->
            """<item id="c$i" href="$href" media-type="application/xhtml+xml"/>"""
        }
        val spine = chapters.indices.joinToString("") { """<itemref idref="c$it"/>""" }
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip".toByteArray())
            entry(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
                    .toByteArray(),
            )
            entry(
                "book.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Corpus</dc:title><dc:creator>QA</dc:creator><dc:identifier id="uid">urn:test:$id</dc:identifier><dc:language>en</dc:language></metadata><manifest>${manifest.joinToString("")}$manifestExtra</manifest><spine>$spine</spine></package>"""
                    .toByteArray(),
            )
            chapters.forEach { (href, content) -> entry(href, content.toByteArray()) }
            extra.forEach { (name, bytes) -> entry(name, bytes) }
        }
        val hash = dev.sadking.folio.core.Epub.sha256(file)
        runBlocking {
            app.database
                .library()
                .insert(
                    Book(
                        id,
                        "Corpus",
                        "[\"QA\"]",
                        originalPath = file.path,
                        currentPath = file.path,
                        originalHash = hash,
                        currentHash = hash,
                    )
                )
        }
        return id to hash
    }

    private fun startAt(id: String, hash: String, href: String, progression: Double) = runBlocking {
        app.database
            .library()
            .savePhoneReading(
                PhoneReading(
                    id,
                    hash,
                    """{"href":"$href","type":"application/xhtml+xml","locations":{"progression":$progression}}""",
                    "",
                    null,
                    System.currentTimeMillis(),
                )
            )
    }

    private fun saved(id: String, hash: String) = runBlocking {
        app.database.library().phoneReading(id, hash)
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

    private fun mounted(scenario: ActivityScenario<ReaderActivity>): Boolean {
        var attached = false
        scenario.onActivity {
            attached =
                it.supportFragmentManager.findFragmentByTag("epub")?.view?.isAttachedToWindow ==
                    true
        }
        return attached
    }

    @Test
    fun deepPageOfAVeryLongChapterStillGetsAKoreaderPointer() {
        // The old capture gave up after 8,000 text nodes from the chapter start.
        val paragraphs = (1..12000).joinToString("") { "<p>Line $it of a very long chapter.</p>" }
        val (id, hash) = book(listOf("long.xhtml" to xhtml(paragraphs)))
        startAt(id, hash, "long.xhtml", 0.9)
        read(id) {
            compose.waitUntil(60000) { saved(id, hash)?.xpointer?.isNotEmpty() == true }
            val pointer = saved(id, hash)!!.xpointer
            val paragraph = Regex("/p\\[(\\d+)]").find(pointer)?.groupValues?.get(1)?.toInt() ?: 0
            assertTrue(pointer, paragraph > 8000)
        }
    }

    /** Opens [chapters] at [href] and waits until the reader has saved a place there. */
    private fun opensAndSaves(
        chapters: List<Pair<String, String>>,
        href: String,
        extra: Map<String, ByteArray> = emptyMap(),
        manifestExtra: String = "",
    ) {
        val (id, hash) = book(chapters, extra, manifestExtra)
        startAt(id, hash, href, 0.5)
        val before = saved(id, hash)!!.updatedAt
        read(id) { scenario ->
            compose.waitUntil(30000) { mounted(scenario) }
            compose.waitUntil(30000) {
                saved(id, hash)!!.let { it.updatedAt > before && it.locator.contains(href) }
            }
        }
    }

    @Test
    fun rightToLeftChapterOpensAndSavesAPlace() =
        opensAndSaves(
            listOf(
                "rtl.xhtml" to
                    xhtml(
                        (1..60).joinToString("") {
                            "<p>هذا سطر عربي رقم $it في فصل من اليمين إلى اليسار.</p>"
                        },
                        """ dir="rtl" xml:lang="ar" lang="ar"""",
                    )
            ),
            "rtl.xhtml",
        )

    @Test
    fun imageOnlyChapterOpensAndSavesAPlace() {
        val image =
            android.graphics.Bitmap.createBitmap(600, 900, android.graphics.Bitmap.Config.ARGB_8888)
        val png =
            java.io
                .ByteArrayOutputStream()
                .also { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
        image.recycle()
        opensAndSaves(
            listOf("image.xhtml" to xhtml("""<div><img src="plate.png" alt="Plate"/></div>""")),
            "image.xhtml",
            extra = mapOf("plate.png" to png),
            manifestExtra = """<item id="plate" href="plate.png" media-type="image/png"/>""",
        )
    }

    @Test
    fun malformedChapterDoesNotCrashTheReader() =
        // Not well-formed XML: the WebView may show a parse error, but the reader must stay up.
        opensAndSaves(
            listOf(
                "sloppy.xhtml" to
                    """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Unclosed <b>bold<p>Next</body></html>"""
            ),
            "sloppy.xhtml",
        )

    @Test
    fun bookWithoutReadableChaptersShowsAnErrorInsteadOfCrashing() {
        val (id, _) = book(emptyList())
        read(id) {
            compose.waitUntil(30000) {
                compose
                    .onAllNodesWithText("Couldn’t open this book")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }
    }
}
