package dev.sadking.folio

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.KosyncReport
import dev.sadking.folio.reader.ReaderActivity
import dev.sadking.folio.reader.koreaderDigest
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Real WebView and Room: a position an e-reader reported through Folio Link is offered when the
 * book opens on the phone, and the phone's own place is published back for KOReader. The e-reader
 * report is inserted directly; no KOReader is involved.
 */
class ReaderLinkTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app
        get() = ApplicationProvider.getApplicationContext<FolioApplication>()

    private val id = UUID.randomUUID().toString()
    private lateinit var file: File
    private lateinit var hash: String
    private lateinit var document: String

    @Before
    fun book() {
        val dir = File(app.repository.root, id).apply { mkdirs() }
        file = File(dir, "original.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            fun chapter(title: String) =
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body><h1>$title</h1>""" +
                    (1..60).joinToString("") {
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
                """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Link Fixture</dc:title><dc:creator>QA Author</dc:creator><dc:identifier id="uid">urn:test:$id</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""",
            )
            entry("OPS/one.xhtml", chapter("Chapter one"))
            entry("OPS/two.xhtml", chapter("Chapter two"))
        }
        hash = dev.sadking.folio.core.Epub.sha256(file)
        document = koreaderDigest(file)
        runBlocking {
            app.database
                .library()
                .insert(
                    Book(
                        id,
                        "Link Fixture",
                        "[\"QA Author\"]",
                        originalPath = file.path,
                        currentPath = file.path,
                        originalHash = hash,
                        currentHash = hash,
                    )
                )
        }
        // A Kobo read on into chapter two, a minute from now so it is newer than anything here.
        val now = System.currentTimeMillis() + 60_000
        app.database
            .library()
            .saveKosyncReport(
                KosyncReport(
                    document,
                    "kobo-1",
                    "Kobo",
                    "/body/DocFragment[2]/body/p[20]/text().0",
                    0.75,
                    now / 1000,
                    now,
                )
            )
    }

    @After
    fun cleanUp() {
        runBlocking { app.database.library().deleteLocalBook(id) }
        file.parentFile?.deleteRecursively()
        app.getSharedPreferences("link-offers", 0).edit().clear().commit()
    }

    private fun open() =
        ActivityScenario.launch<ReaderActivity>(
            Intent(app, ReaderActivity::class.java)
                .putExtra("book_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

    private fun saved() = runBlocking { app.database.library().phoneReading(id, hash) }

    @Test
    fun continuingTakesThePhoneToTheEreadersPassageAndPublishesIt() {
        open().use {
            compose.waitUntil(30_000) {
                compose
                    .onAllNodesWithText("Continue from Kobo at 75%?")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Continue").performClick()
            compose.waitUntil(30_000) {
                saved()?.xpointer?.startsWith("/body/DocFragment[2]/") == true
            }
            assertTrue(saved()!!.locator, saved()!!.locator.contains("two.xhtml"))
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithText("Continue from Kobo at 75%?")
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
            // The phone's place is now what Folio Link hands to KOReader for this book.
            val reports = runBlocking { app.database.library().kosyncReports(document) }
            val phone = reports.single { it.deviceId == app.link.settings.phoneDeviceId }
            assertTrue(phone.progress, phone.progress.startsWith("/body/DocFragment[2]/"))
        }
    }

    @Test
    fun stayingKeepsThePlaceAndTheSameReportIsNotOfferedAgain() {
        open().use {
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText("Stay").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Stay").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Stay").fetchSemanticsNodes().isEmpty()
            }
        }
        open().use {
            compose.waitUntil(30_000) { saved() != null }
            assertTrue(
                compose
                    .onAllNodesWithText("Continue from Kobo at 75%?")
                    .fetchSemanticsNodes()
                    .isEmpty()
            )
            assertFalse(saved()!!.locator.contains("two.xhtml"))
        }
    }
}
