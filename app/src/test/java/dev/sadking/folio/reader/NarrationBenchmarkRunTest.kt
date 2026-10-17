package dev.sadking.folio.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The benchmark's steps, files and saved results with a fake model that answers at once. Says
 * nothing about speed: only the device run measures that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NarrationBenchmarkRunTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val loaded = mutableListOf<KokoroRuntime.Config>()

    @Before
    fun installAFakeVoice() {
        File(KokoroModel.dir(app), ".complete").apply {
            parentFile!!.mkdirs()
            writeText("test")
        }
        NarrationBenchmark.loader = { _, config ->
            synchronized(loaded) { loaded += config }
            FakeVoice(24_000)
        }
        NarrationBenchmark.sweepAudioSeconds = 8.0
        NarrationBenchmark.providerAudioSeconds = 2.0
    }

    @After fun stop() = NarrationBenchmark.cancel()

    private fun awaitEnd(): NarrationBenchmark.State {
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            val state = NarrationBenchmark.state.value
            if (state is NarrationBenchmark.State.Done || state is NarrationBenchmark.State.Failed)
                return state
            Thread.sleep(10)
        }
        error("the benchmark did not finish: ${NarrationBenchmark.state.value}")
    }

    @Test
    fun aRunSweepsRendersSavesAndExports() {
        NarrationBenchmark.start(
            app,
            NarrationBenchmark.Plan(
                sustainedMinutes = 0,
                threads = listOf(2, 4),
                tryProviders = true,
            ),
        )
        val state = awaitEnd()
        assertTrue("$state", state is NarrationBenchmark.State.Done)
        state as NarrationBenchmark.State.Done
        val report = state.report
        assertEquals(listOf("int8"), report.loadMs.keys.toList())
        // Two CPU rows and one per provider.
        assertEquals(listOf("cpu", "cpu", "nnapi", "xnnpack"), report.configs.map { it.provider })
        assertNotNull(report.best)
        assertEquals("cpu", report.best!!.provider)
        assertEquals(
            report.best!!.threads,
            NarrationPriors.bestThreads(app, KokoroModel.Variant.INT8),
        )
        val prior = NarrationPriors.load(app, KokoroModel.Variant.INT8, report.best!!.threads)
        assertTrue(prior.n > 0)
        assertNotNull("a fast model starts", report.predictedStartSeconds)
        for (name in listOf("rows.csv", "report.json", "whole.wav", "separate.wav")) assertTrue(
            name,
            File(state.exportDir, name).isFile,
        )
        assertTrue(File(state.exportDir, "rows.csv").readLines().size > 4)
        // Each configuration loaded its own model; nothing ran with read-aloud's.
        assertTrue(loaded.any { it.threads == 2 } && loaded.any { it.threads == 4 })
    }

    @Test
    fun thePassageBecomesUnitsLikeABook() {
        val passage =
            app.resources
                .openRawResource(dev.sadking.folio.R.raw.narration_passage)
                .bufferedReader()
                .use {
                    it.readText()
                }
        val units = NarrationBenchmark.passageUnits(passage)
        assertTrue(units.size > 20)
        assertTrue(units.all { it.chars <= KokoroRuntime.tuning.hardSplitChars })
        assertTrue(units.none { it.speechText.contains("Mr.") })
    }

    @Test
    fun cancellingReturnsToIdle() {
        NarrationBenchmark.sweepAudioSeconds = 10_000.0
        NarrationBenchmark.start(
            app,
            NarrationBenchmark.Plan(sustainedMinutes = 0, threads = listOf(2)),
        )
        Thread.sleep(200)
        NarrationBenchmark.cancel()
        assertEquals(NarrationBenchmark.State.Idle, NarrationBenchmark.state.value)
    }
}
