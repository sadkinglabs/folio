@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.sadking.folio.reader

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.reader.narration.PlannedUtterance
import dev.sadking.folio.reader.narration.normalizeKey
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.navigator.media.common.MediaNavigator
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The planner must list exactly the utterances Readium's player will ask for, or every request
 * misses the plan. A small book with italics, a link, dialogue, abbreviations, a scene break and
 * two chapters is read by the real `TtsNavigator` (with an engine that finishes each utterance at
 * once) and walked by [UtterancePlanner]; the two sequences must be identical.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UtterancePlannerTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun thePlanIsWhatReadiumAsksFor() {
        val publication = open(book())
        val spoken = readAloud(publication).spoken.map(::normalizeKey)
        val planned = plan(publication, null).map { it.key }
        assertTrue("the book has content", spoken.size > 10)
        assertEquals(spoken, planned)
    }

    @Test
    fun aWalkFromALocatorFindsThatUtteranceInItsParagraph() {
        val publication = open(book())
        val heard = readAloud(publication)
        val spoken = heard.spoken.map(::normalizeKey)
        for ((text, locator) in heard.locations.drop(3).filterIndexed { i, _ -> i % 4 == 0 }) {
            val walked = plan(publication, locator).map { it.key }
            val at = walked.indexOf(normalizeKey(text))
            assertTrue("found within its paragraph: $at", at in 0..8)
            assertEquals(spoken.drop(spoken.indexOf(normalizeKey(text))), walked.drop(at))
        }
    }

    @Test
    fun onlyTextWithALetterOrDigitIsSpokenAndAbbreviationsStayWhole() {
        val planned = plan(open(book()), null)
        assertTrue(planned.all { u -> u.text.any { it.isLetterOrDigit() } })
        assertTrue(planned.none { it.text.trim() == "Mr." })
        assertTrue(planned.any { "Mr. Bennet" in it.text })
        // A change of language splits a sentence into utterances (Readium 3.4 keeps italics and
        // links in one segment); only the last piece ends the sentence.
        val foreign = planned.indexOfFirst { it.text.trim() == "au revoir" }
        assertTrue(planned.joinToString("|") { it.text }, foreign > 0)
        assertEquals(false, planned[foreign - 1].endsSentence)
        assertEquals(false, planned[foreign].endsSentence)
        assertEquals(true, planned[foreign + 1].endsSentence)
        assertEquals(planned[foreign].elementOrdinal, planned[foreign + 1].elementOrdinal)
        assertTrue(planned.any { it.text.startsWith("She had read Pride and its sequel") })
        assertTrue(planned.map { it.href }.distinct().size == 2)
        assertTrue(planned.last().endsElement)
    }

    private fun plan(publication: Publication, from: Locator?): List<PlannedUtterance> = runOnMain {
        val walker = UtterancePlanner(publication).walk(from)
        buildList {
            while (true) {
                val batch = walker.next(7)
                if (batch.isEmpty()) break
                addAll(batch)
            }
        }
    }

    /** Runs [block] on Main while the test pumps the main looper. */
    private fun <T> runOnMain(block: suspend () -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var result: Result<T>? = null
        scope.launch { result = runCatching { block() } }
        val end = System.currentTimeMillis() + 20_000
        while (result == null) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("timed out")
            Thread.sleep(2)
        }
        scope.cancel()
        return result!!.getOrThrow()
    }

    private class Heard(val spoken: List<String>, val locations: List<Pair<String, Locator>>)

    /** Every utterance Readium's player speaks, start to end, and the locations it showed. */
    private fun readAloud(publication: Publication): Heard {
        val spoken = mutableListOf<Pair<String, Locator>>()
        val provider = InstantProvider()
        val navigator = runOnMain {
            TtsNavigatorFactory(app, publication, provider, kokoroSentenceTokenizer)!!
                .createNavigator(
                    object : TtsNavigator.Listener {
                        override fun onStopRequested() = Unit
                    }
                )
                .getOrElse { error(it.message) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            navigator.location.collect { location ->
                if (spoken.lastOrNull()?.second != location.utteranceLocator)
                    spoken += location.utterance to location.utteranceLocator
            }
        }
        navigator.play()
        val end = System.currentTimeMillis() + 30_000
        while (navigator.playback.value.state !is MediaNavigator.State.Ended) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("Readium did not finish: $spoken")
            Thread.sleep(1)
        }
        shadowOf(Looper.getMainLooper()).idle()
        scope.cancel()
        navigator.close()
        return Heard(provider.engine.spoken, spoken)
    }

    private fun open(file: File): Publication = runOnMain {
        val http = DefaultHttpClient()
        val retriever = AssetRetriever(app.contentResolver, http)
        val asset =
            retriever.retrieve(file.toUrl(isDirectory = false)).getOrElse { error(it.message) }
        PublicationOpener(DefaultPublicationParser(app, http, retriever, pdfFactory = null))
            .open(asset, allowUserInteraction = false)
            .getOrElse { error(it.message) }
    }

    private fun book(): File {
        val chapter1 =
            """
            <h1>Chapter One</h1>
            <p>It is a truth universally acknowledged, that a single man in possession of a good
            fortune, must be in want of a wife.</p>
            <p>"My dear Mr. Bennet," said his lady to him one day, "have you heard that Netherfield
            Park is let at last?"</p>
            <p>Mr. Bennet replied that he had not.</p>
            <p>She had read <i>Pride</i> and <a href="ch2.xhtml">its sequel</a> twice, e.g. on
            Sundays.</p>
            <p>* * *</p>
            <p>She said <span lang="fr" xml:lang="fr">au revoir</span> and left.</p>
            <p>"No," said she. "Yes." He lived at No. 5, near St. Mary's.</p>
            """
        val chapter2 =
            """
            <h1>Chapter Two</h1>
            <p>It was the best of times, it was the worst of times; it was the age of wisdom.</p>
            <p>J. Austen wrote this — or did she?</p>
            <p>The end.</p>
            """
        val file = Files.createTempFile("planner", ".epub").toFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                }
            )
            zip.write(mimetype)
            fun add(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.trimIndent().toByteArray())
            }
            add(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf"
                    media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """,
            )
            add(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">planner-test</dc:identifier>
                    <dc:title>Planner</dc:title>
                    <dc:language>en</dc:language>
                    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
                </package>
                """,
            )
            add(
                "OEBPS/nav.xhtml",
                xhtml(
                    "<nav epub:type=\"toc\"><ol><li><a href=\"ch1.xhtml\">One</a></li></ol></nav>"
                ),
            )
            add("OEBPS/ch1.xhtml", xhtml(chapter1))
            add("OEBPS/ch2.xhtml", xhtml(chapter2))
        }
        return file
    }

    private fun xhtml(body: String) =
        """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en">
<head><title>t</title></head><body>${body.trimIndent()}</body></html>"""
}

/** An engine that finishes every utterance at once, on Main, as a real engine reports. */
private class InstantEngine :
    TtsEngine<KokoroSettings, KokoroPreferences, KokoroError, KokoroVoice> {
    private val main = Handler(Looper.getMainLooper())
    private var listener: TtsEngine.Listener<KokoroError>? = null
    val spoken = mutableListOf<String>()
    override val settings = MutableStateFlow(KokoroSettings(Language("en-US"), 1.0, 3))
    override val voices: Set<KokoroVoice> = emptySet()

    override fun submitPreferences(preferences: KokoroPreferences) = Unit

    override fun setListener(listener: TtsEngine.Listener<KokoroError>?) {
        this.listener = listener
    }

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        spoken += text
        main.post {
            listener?.onStart(requestId)
            listener?.onDone(requestId)
        }
    }

    override fun stop() = Unit

    override fun close() = Unit
}

private class InstantProvider :
    TtsEngineProvider<
        KokoroSettings,
        KokoroPreferences,
        KokoroPreferencesEditor,
        KokoroError,
        KokoroVoice,
    > {
    val engine = InstantEngine()

    override suspend fun createEngine(
        publication: Publication,
        initialPreferences: KokoroPreferences,
    ): Try<InstantEngine, Error> = Try.success(engine)

    override fun createPreferencesEditor(
        publication: Publication,
        initialPreferences: KokoroPreferences,
    ) = KokoroPreferencesEditor(initialPreferences)

    override fun createEmptyPreferences() = KokoroPreferences()

    override fun getPlaybackParameters(settings: KokoroSettings) = PlaybackParameters(1f)

    override fun updatePlaybackParameters(
        previous: KokoroPreferences,
        playbackParameters: PlaybackParameters,
    ) = previous

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun mapEngineError(error: KokoroError) =
        PlaybackException(error.message, null, PlaybackException.ERROR_CODE_UNSPECIFIED)
}
