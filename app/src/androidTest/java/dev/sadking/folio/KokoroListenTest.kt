package dev.sadking.folio

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.Book
import dev.sadking.folio.reader.KokoroModel
import dev.sadking.folio.reader.ListenState
import dev.sadking.folio.reader.NaturalVoice
import dev.sadking.folio.reader.ReaderActivity
import dev.sadking.folio.reader.ReaderViewModel
import dev.sadking.folio.reader.narration.NarrationStatus
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Listen with the natural voice, end to end on a device: the real Kokoro model (downloaded here,
 * about 132 MB, as the app does), sherpa-onnx's native code, the audio output, Readium's player and
 * the media service. It passes once Kokoro has made audio for the book and narration is preparing,
 * playing or asking about speed, with the reader still open. A crash ends the whole test process,
 * so that fails the run. Skipped only when the model cannot be downloaded.
 */
class KokoroListenTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<FolioApplication>()
    private val settings = app.getSharedPreferences("reader", 0)
    private val cache = File(app.filesDir, "narration/cache")

    @Test
    fun listeningWithTheNaturalVoiceMakesAudioAndKeepsTheReaderOpen() {
        val installedBefore = KokoroModel.ready(app, KokoroModel.Variant.INT8)
        if (!installedBefore) download()
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
                        "Narration Fixture",
                        "[\"QA Author\"]",
                        originalPath = file.path,
                        currentPath = file.path,
                        originalHash = hash,
                        currentHash = hash,
                    )
                )
        }
        cache.listFiles()?.forEach { it.delete() }
        settings
            .edit()
            .putString("listenEngine", "natural")
            .putInt("kokoroSpeaker", 3)
            .putFloat("listenSpeed", 1.25f)
            .remove("narrationVariant")
            .commit()
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

                var seen = "nothing yet"
                /** Throws on a closed reader or a failed voice; true once Kokoro made audio. */
                fun narrationGoing(): Boolean {
                    check(scenario.state != Lifecycle.State.DESTROYED) {
                        "The reader closed while the natural voice started. Last: $seen"
                    }
                    var finishing = false
                    var natural = false
                    var state = ListenState.OFF
                    var narration: NarrationStatus = NarrationStatus.Idle
                    var problem: Any? = null
                    scenario.onActivity { activity ->
                        val listen = ViewModelProvider(activity)[ReaderViewModel::class.java].listen
                        finishing = activity.isFinishing
                        natural = listen.natural
                        state = listen.state
                        narration = listen.narration
                        problem = listen.naturalProblem
                    }
                    seen = "state=$state natural=$natural narration=$narration problem=$problem"
                    check(!finishing) { "The reader is closing. Last: $seen" }
                    check(problem == null) { "The natural voice failed: $seen" }
                    check(state == ListenState.OFF || state == ListenState.STARTING || natural) {
                        "The phone's voice is reading instead: $seen"
                    }
                    val audioMade = cache.listFiles()?.any { it.name.endsWith(".pcm") } == true
                    val going =
                        when (narration) {
                            is NarrationStatus.Playing,
                            is NarrationStatus.Preparing,
                            is NarrationStatus.PreparingChapter,
                            is NarrationStatus.Paused,
                            is NarrationStatus.TooSlow -> true
                            else -> false
                        }
                    return natural && audioMade && going
                }
                // Generous: the model loads and the first sentences are made on an emulator CPU.
                compose.waitUntil(300_000) { narrationGoing() }
                QaScreenshots.capture("reader-listen-natural")
                // It keeps going, with the reader open, for a few more seconds.
                val until = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < until) {
                    narrationGoing()
                    Thread.sleep(250)
                }
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[ReaderViewModel::class.java].listen.stop()
                }
                compose.waitUntil(10_000) {
                    var off = false
                    scenario.onActivity { activity ->
                        off =
                            ViewModelProvider(activity)[ReaderViewModel::class.java].listen.state ==
                                ListenState.OFF
                    }
                    off
                }
            }
        } finally {
            settings
                .edit()
                .remove("listenEngine")
                .remove("kokoroSpeaker")
                .remove("listenSpeed")
                .commit()
            runBlocking { app.database.library().deleteLocalBook(id) }
            dir.deleteRecursively()
            // Other reader tests expect the phone's voice and the download offer.
            if (!installedBefore) KokoroModel.remove(app, KokoroModel.Variant.INT8)
        }
    }

    /** The app's own download, verify and unpack. Skips the test only for a failed download. */
    private fun download() {
        KokoroModel.install(app, KokoroModel.Variant.INT8)
        val state = KokoroModel.state(app, KokoroModel.Variant.INT8)
        val end = System.currentTimeMillis() + 20 * 60_000L
        while (System.currentTimeMillis() < end) {
            val now = state.value
            if (now == NaturalVoice.Ready || now == NaturalVoice.Failed) break
            Thread.sleep(1_000)
        }
        assumeTrue(
            "The natural voice could not be downloaded here: ${state.value}",
            state.value == NaturalVoice.Ready,
        )
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
                """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Narration Fixture</dc:title><dc:creator>QA Author</dc:creator><dc:identifier id="uid">urn:test:$id</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="chapter"/></spine></package>""",
            )
            entry(
                "nav.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">Morning</a></li></ol></nav></body></html>""",
            )
            entry(
                "chapter.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Morning</title></head><body><h1>Morning</h1>""" +
                    (1..40).joinToString("") {
                        "<p>Mr. Hale walked to the river at dawn. The water was quiet, and the light was soft. " +
                            "\"Good morning,\" said the boatman. Paragraph $it ends here.</p>"
                    } +
                    "</body></html>",
            )
        }
    }
}
