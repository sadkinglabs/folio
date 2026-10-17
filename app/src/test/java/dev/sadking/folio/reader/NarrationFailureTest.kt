@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.sadking.folio.reader

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.reader.narration.NarrationStatus
import dev.sadking.folio.reader.narration.ThroughputPrior
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.navigator.media.tts.TtsEngine
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Failures nobody planned for end narration with an error that Readium hears, never the app: an
 * exception on Main (in the engine or a listener), on the audio thread or on the synthesis thread.
 * Robolectric rethrows an exception from a posted task, so an unguarded one fails these tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class NarrationFailureTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val texts =
        (0 until 12).map { "Sentence $it walks along the quiet river bank at dawn." }
    private val voice = FakeVoice(RATE)
    private var engine: KokoroEngine? = null
    private var fakeSink: FakeAudioSink? = null
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        KokoroRuntime.loader = { _, _ -> voice }
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, failure -> uncaught += failure }
    }

    @After
    fun tearDown() {
        engine?.close()
        settle()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        assertEquals("no thread died", emptyList<Throwable>(), uncaught.toList())
    }

    private fun handle() = runBlocking {
        KokoroRuntime.acquire(
            app,
            KokoroRuntime.Config(
                KokoroModel.Variant.INT8,
                threads = nextConfig++,
                silenceScale = 0.4f,
            ),
        )
    }

    private fun start(
        listener: TtsEngine.Listener<KokoroError>,
        sink: (FakeAudioSink) -> AudioSink = { it },
    ): KokoroEngine {
        val created =
            KokoroEngine(
                handle(),
                { queue, limits, events ->
                    sink(FakeAudioSink(queue, limits, events, RATE).also { fakeSink = it })
                },
                FakePlanSource(listOf(texts.chunked(3))),
                null,
                KokoroPreferences(),
                NarrationEnv(prior = FAST, tappedAtMs = SystemClock.elapsedRealtime()),
            )
        engine = created
        created.setListener(listener)
        settle()
        return created
    }

    private fun settle() {
        var quiet = 0
        val end = System.currentTimeMillis() + 5_000
        while (quiet < 3) {
            shadowOf(Looper.getMainLooper()).idle()
            if (KokoroRuntime.pendingTasks == 0) quiet++
            else {
                quiet = 0
                Thread.sleep(1)
            }
            if (System.currentTimeMillis() > end) fail("synthesis did not settle")
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun anOutputThatThrowsOnMainEndsNarrationWithAnError() {
        val readium = Listener()
        val created =
            start(readium) { fake ->
                object : AudioSink by fake {
                    override fun send(command: SinkCommand) {
                        if (command == SinkCommand.Play) error("track gone")
                        fake.send(command)
                    }
                }
            }
        created.speak(TtsEngine.RequestId("r0"), texts[0], null)
        settle()
        assertEquals(listOf("error:r0"), readium.events.toList())
        assertTrue(created.status.value is NarrationStatus.Failed)
        // Readium asks again: the same error, at once, still without a crash.
        created.speak(TtsEngine.RequestId("r1"), texts[0], null)
        settle()
        assertEquals(listOf("error:r0", "error:r1"), readium.events.toList())
    }

    @Test
    fun aListenerThatThrowsEndsNarrationNotTheApp() {
        val readium =
            object : Listener() {
                override fun onStart(requestId: TtsEngine.RequestId) = error("listener broke")
            }
        val created = start(readium)
        created.speak(TtsEngine.RequestId("r0"), texts[0], null)
        // Play until the voice reaches the sentence and the engine tells the listener.
        var steps = 0
        while (created.status.value !is NarrationStatus.Failed && steps++ < 300) {
            settle()
            fakeSink?.step(10)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        }
        assertTrue("status=${created.status.value}", created.status.value is NarrationStatus.Failed)
        assertEquals(listOf("error:r0"), readium.events.toList())
    }

    @Test
    fun aModelThatThrowsWhenFreedLeavesTheSynthesisThreadRunning() {
        val broken =
            object : LoadedVoice by voice {
                override fun release() = throw IllegalStateException("native free failed")
            }
        KokoroRuntime.loader = { _, _ -> broken }
        handle().release()
        KokoroRuntime.releaseIdle()
        settle()
        val ran = CountDownLatch(1)
        handle().also { it.submit(0, 0, 0, { true }) { ran.countDown() } }.release()
        assertTrue("a later job still runs", ran.await(5, TimeUnit.SECONDS))
        settle()
    }

    private open class Listener : TtsEngine.Listener<KokoroError> {
        val events = CopyOnWriteArrayList<String>()

        override fun onStart(requestId: TtsEngine.RequestId) = Unit

        override fun onRange(requestId: TtsEngine.RequestId, range: IntRange) = Unit

        override fun onInterrupted(requestId: TtsEngine.RequestId) = Unit

        override fun onFlushed(requestId: TtsEngine.RequestId) = Unit

        override fun onDone(requestId: TtsEngine.RequestId) {
            events += "done:${requestId.value}"
        }

        override fun onError(requestId: TtsEngine.RequestId, error: KokoroError) {
            events += "error:${requestId.value}"
        }
    }

    private companion object {
        const val RATE = 8_000
        var nextConfig = 500

        /** Synthesis so fast the gate opens as soon as the first unit is ready. */
        val FAST = ThroughputPrior(0.01, 0.0001, 0.051, 0.1, 0, 0)
    }
}
