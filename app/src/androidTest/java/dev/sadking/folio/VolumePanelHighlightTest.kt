package dev.sadking.folio

import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.sadking.folio.data.Book
import dev.sadking.folio.reader.ListenState
import dev.sadking.folio.reader.ReaderActivity
import dev.sadking.folio.reader.ReaderViewModel
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.readium.r2.shared.publication.Locator

/**
 * The owner's bug: while the system volume panel was out, the voice went on but the highlight on
 * the page stayed on one sentence, flickering forward for a frame, until the panel closed. With the
 * phone's voice, volume up and down every 0.7 s keeps the panel out (and the reader's window
 * without focus) for about 6 s. The sentence the reader draws must keep up with the one being read.
 * A timeline and screenshots taken while the panel is out go to files/qa, which CI publishes.
 */
class VolumePanelHighlightTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<FolioApplication>()
    private val settings = app.getSharedPreferences("reader", 0)

    @Test
    fun theHighlightKeepsUpWithTheVoiceWhileTheVolumePanelIsOut() {
        val id = UUID.randomUUID().toString()
        val dir = File(app.repository.root, id).apply { mkdirs() }
        val file = File(dir, "original.epub")
        writeFixture(file, id)
        val hash = dev.sadking.folio.core.Epub.sha256(file)
        runBlocking {
            app.database
                .library()
                .insert(
                    Book(
                        id,
                        "Volume Fixture",
                        "[\"QA Author\"]",
                        originalPath = file.path,
                        currentPath = file.path,
                        originalHash = hash,
                        currentHash = hash,
                    )
                )
        }
        settings.edit().putString("listenEngine", "phone").commit()
        val intent =
            Intent(app, ReaderActivity::class.java)
                .putExtra("book_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                compose.waitUntil(60_000) {
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
                compose.waitUntil(30_000) {
                    runBlocking { app.database.library().phoneReading(id, hash) } != null
                }
                fun listenShown() =
                    compose
                        .onAllNodesWithContentDescription("Listen")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                var taps = 0
                while (!listenShown() && taps < 3) {
                    QaScreenshots.tapScreenCentre()
                    taps++
                    runCatching { compose.waitUntil(5_000) { listenShown() } }
                }
                check(listenShown()) { "No Listen button after $taps taps. ${Diagnostics.state()}" }
                compose.onNodeWithContentDescription("Listen").performClick()

                fun key(locator: Locator?) = locator?.let {
                    it.href.toString() + it.locations.toJSON().toString()
                }
                data class Sample(val state: ListenState, val spoken: String?, val drawn: String?)
                fun sample(): Sample {
                    var result = Sample(ListenState.OFF, null, null)
                    scenario.onActivity { activity ->
                        val listen = ViewModelProvider(activity)[ReaderViewModel::class.java].listen
                        result =
                            Sample(listen.state, key(listen.utterance), key(activity.appliedListen))
                    }
                    return result
                }
                // An emulator without a voice cannot show the bug.
                runCatching { compose.waitUntil(60_000) { sample().state == ListenState.PLAYING } }
                assumeTrue("No voice played here", sample().state == ListenState.PLAYING)
                // Two sentences heard first, so the page has settled into following the voice.
                val first = sample().spoken
                compose.waitUntil(30_000) { sample().spoken != first }

                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val timeline = StringBuilder()
                val start = System.currentTimeMillis()
                val spokenSeen = mutableSetOf<String?>()
                var behindSince: Long? = null
                var worstBehindMs = 0L
                var nextKeyAt = 0L
                var press = 0
                var shots = 0
                while (System.currentTimeMillis() - start < 8_000) {
                    val now = System.currentTimeMillis() - start
                    if (now >= nextKeyAt && now < 6_000) {
                        instrumentation.sendKeyDownUpSync(
                            if (press % 2 == 0) KeyEvent.KEYCODE_VOLUME_UP
                            else KeyEvent.KEYCODE_VOLUME_DOWN
                        )
                        press++
                        nextKeyAt = now + 700
                    }
                    val s = sample()
                    spokenSeen += s.spoken
                    if (s.spoken != null && s.spoken != s.drawn) {
                        val since = behindSince ?: now.also { behindSince = it }
                        worstBehindMs = maxOf(worstBehindMs, now - since)
                    } else behindSince = null
                    timeline.append(
                        "${now}ms ${s.state} spoken=${s.spoken?.hashCode()} drawn=${s.drawn?.hashCode()}\n"
                    )
                    // What the screen really shows, panel and page, at about 1.5, 3.5 and 5.5 s.
                    if (shots < 3 && now >= 1_500 + shots * 2_000L) {
                        screenshot("volume-panel-${shots + 1}")
                        shots++
                    }
                    Thread.sleep(150)
                }
                screenshot("volume-panel-after")
                val report =
                    "worst highlight lag ${worstBehindMs}ms, sentences read ${spokenSeen.size}\n$timeline"
                android.util.Log.i("VolumePanelHighlightTest", report)
                runCatching {
                    File(app.filesDir, "qa")
                        .apply { mkdirs() }
                        .resolve("volume-panel.txt")
                        .writeText(report)
                }
                check(spokenSeen.size >= 2) { "Reading did not move on during the test.\n$report" }
                check(worstBehindMs <= 2_500) {
                    "The highlight fell ${worstBehindMs}ms behind the voice with the volume panel out.\n$report"
                }
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[ReaderViewModel::class.java].listen.stop()
                }
            }
        } finally {
            settings.edit().remove("listenEngine").commit()
            runBlocking { app.database.library().deleteLocalBook(id) }
            dir.deleteRecursively()
        }
    }

    /** The whole screen as it is now, without waiting for the app to go idle. */
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        val folder = File(app.filesDir, "qa").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    private fun writeFixture(file: File, id: String) {
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
                """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Volume Fixture</dc:title><dc:creator>QA Author</dc:creator><dc:identifier id="uid">urn:test:$id</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="chapter"/></spine></package>""",
            )
            entry(
                "nav.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">Chapter one</a></li></ol></nav></body></html>""",
            )
            // Short sentences, so several are read while the panel is out.
            entry(
                "chapter.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter one</title></head><body><h1>Chapter one</h1>""" +
                    (1..60).joinToString("") {
                        "<p>Sentence $it starts here. The orchard was quiet.</p>"
                    } +
                    "</body></html>",
            )
        }
    }
}
