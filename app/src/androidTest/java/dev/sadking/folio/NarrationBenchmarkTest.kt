package dev.sadking.folio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dev.sadking.folio.reader.KokoroModel
import dev.sadking.folio.reader.NarrationBenchmark
import dev.sadking.folio.reader.NarrationPriors
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the on-device benchmark with the real model: load, a sweep at 2 and 4 threads, the
 * diagnostic render and a 2-minute sustained run. Only with
 * `-Pandroid.testInstrumentationRunnerArguments.narrationBench=true` and a natural voice installed.
 * On an emulator the numbers are informative only; the test checks invariants, never speed.
 */
class NarrationBenchmarkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun onlyWhenAsked() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("narrationBench") == "true")
        assumeTrue(KokoroModel.installed(context).isNotEmpty())
    }

    @Test
    fun eachSampleIsDeliveredOnceWhetherStreamedOrReturned() {
        val variant = KokoroModel.installed(context).first()
        val dir = KokoroModel.dir(context, variant)
        val tts =
            OfflineTts(
                null,
                OfflineTtsConfig(
                    model =
                        OfflineTtsModelConfig(
                            kokoro =
                                OfflineTtsKokoroModelConfig(
                                    model = File(dir, variant.modelFile).path,
                                    voices = File(dir, "voices.bin").path,
                                    tokens = File(dir, "tokens.txt").path,
                                    dataDir = File(dir, "espeak-ng-data").path,
                                    lexicon = File(dir, "lexicon-us-en.txt").path,
                                    lang = "en-us",
                                ),
                            numThreads = 2,
                        ),
                    silenceScale = 0.4f,
                ),
            )
        try {
            var streamed = 0
            var pieces = 0
            val returned =
                tts.generateWithConfigAndCallback(
                        "It is a truth universally acknowledged. However little known the " +
                            "feelings of such a man may be, this truth is well fixed.",
                        GenerationConfig(silenceScale = 0.4f, speed = 1f, sid = 3),
                    ) {
                        streamed += it.size
                        pieces++
                        1
                    }
                    .samples
            assertTrue(pieces >= 2)
            assertEquals(returned.size, streamed)
        } finally {
            tts.release()
        }
    }

    @Test
    fun theBenchmarkFinishesAndSavesWhatTheStartGateNeeds() {
        NarrationBenchmark.start(
            context,
            NarrationBenchmark.Plan(
                sustainedMinutes = 2,
                threads = listOf(2, 4),
                tryProviders = false,
            ),
        )
        val end = System.currentTimeMillis() + 45 * 60_000L
        while (System.currentTimeMillis() < end) {
            val state = NarrationBenchmark.state.value
            if (state is NarrationBenchmark.State.Done || state is NarrationBenchmark.State.Failed)
                break
            Thread.sleep(1_000)
        }
        val done = NarrationBenchmark.state.value
        assertTrue("finished: $done", done is NarrationBenchmark.State.Done)
        done as NarrationBenchmark.State.Done
        JSONObject(done.report.toJson())
        val best = done.report.best
        assertNotNull(best)
        best!!
        assertEquals(2 * KokoroModel.installed(context).size, done.report.configs.size)
        val variant = KokoroModel.Variant.entries.first { it.name.lowercase() == best.variant }
        assertEquals(best.threads, NarrationPriors.bestThreads(context, variant))
        for (name in listOf("rows.csv", "report.json", "whole.wav", "separate.wav")) assertTrue(
            name,
            File(done.exportDir, name).isFile,
        )
        for (gap in
            listOfNotNull(done.report.wholeGapP50Ms, done.report.separateGapP50Ms)) assertTrue(
            "sentence gap $gap ms",
            gap in 100..600,
        )
    }
}
