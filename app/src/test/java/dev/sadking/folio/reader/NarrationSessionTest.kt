@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.sadking.folio.reader

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.reader.narration.NarrationStatus
import dev.sadking.folio.reader.narration.NarrationTuning
import dev.sadking.folio.reader.narration.PcmQueue
import dev.sadking.folio.reader.narration.PlannedUtterance
import dev.sadking.folio.reader.narration.ThroughputPrior
import dev.sadking.folio.reader.narration.normalizeKey
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.navigator.media.tts.TtsEngine
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * The engine end to end on the JVM: the real [KokoroRuntime] thread with a fake model, a fake
 * output whose playback head moves with Robolectric's clock, and a fake Readium that asks for the
 * next sentence when told the last one is done. Nothing here plays sound or runs Kokoro.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class NarrationSessionTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val texts = (0 until 40).map { sentence(it) }
    private val plan = FakePlanSource(texts.chunked(3).chunked(4))
    private lateinit var voice: FakeVoice
    private lateinit var sink: FakeAudioSink
    private lateinit var engine: KokoroEngine
    private lateinit var readium: FakeReadium
    private val wake = FakeWakeLock()
    private var screenOn = true

    @Before
    fun setUp() {
        voice = FakeVoice(RATE)
        KokoroRuntime.loader = { _, _ -> voice }
    }

    @After
    fun tearDown() {
        voice.hold?.countDown()
        voice.permits?.release(1_000)
        if (::engine.isInitialized) engine.close()
        if (::readium.isInitialized) assertEquals(emptyList<String>(), readium.offMain)
        settle()
    }

    private fun start(
        prior: ThroughputPrior = FAST,
        speak: Boolean = true,
        warmup: SynthesisObservation? = null,
    ) {
        val handle = runBlocking {
            KokoroRuntime.acquire(
                app,
                KokoroRuntime.Config(
                    KokoroModel.Variant.INT8,
                    threads = nextConfig++,
                    silenceScale = 0.4f,
                ),
            )
        }
        engine =
            KokoroEngine(
                handle,
                { queue, limits, events ->
                    FakeAudioSink(queue, limits, events, RATE).also { sink = it }
                },
                plan,
                null,
                KokoroPreferences(),
                NarrationEnv(
                    prior = prior,
                    wakeLock = wake,
                    screenOn = { screenOn },
                    tappedAtMs = SystemClock.elapsedRealtime(),
                    warmup = warmup,
                ),
            )
        readium = FakeReadium(engine, texts)
        engine.setListener(readium)
        settle()
        if (speak) {
            readium.speakCurrent()
            settle()
        }
    }

    /** Lets the synthesis thread and Main run until both are idle. */
    private fun settle() {
        var quiet = 0
        val end = System.currentTimeMillis() + 5_000
        while (quiet < 3) {
            shadowOf(Looper.getMainLooper()).idle()
            // A fake model held on purpose counts as settled: nothing else can run meanwhile.
            if (KokoroRuntime.pendingTasks == 0 || voice.stuck) quiet++
            else {
                quiet = 0
                Thread.sleep(1)
            }
            if (System.currentTimeMillis() > end) fail("synthesis did not settle")
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Plays [ms] of virtual time in 10 ms steps, letting synthesis keep up between steps. */
    private fun play(ms: Long) {
        var t = 0L
        while (t < ms) {
            settle()
            sink.step(STEP_MS)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(STEP_MS.toLong()))
            t += STEP_MS
        }
        settle()
    }

    private fun playUntil(limitMs: Long, done: () -> Boolean) {
        var t = 0L
        while (!done()) {
            if (t > limitMs)
                fail(
                    "not reached within $limitMs ms of playback: ${readium.events.takeLast(8)} " +
                        "status=${engine.status.value} sink=${sink.debug()}"
                )
            play(100)
            t += 100
        }
    }

    private fun audioMs(text: String, speed: Double = 1.0) =
        (voice.frames(text, speed) * 1000L / RATE)

    @Test
    fun doneComesOnlyWhenTheHeadPassesTheSentenceEnd() {
        start()
        play(200)
        assertTrue("started", "start:0" in readium.events)
        assertFalse("done:0" in readium.events)
        play(audioMs(texts[0]) - 400)
        assertFalse("not done before its audio was heard", "done:0" in readium.events)
        play(600)
        assertTrue("done once heard", "done:0" in readium.events)
        assertTrue(sink.headFrame >= voice.frames(texts[0], 1.0) - RATE / 10)
    }

    @Test
    fun sentencesFollowWithoutUnderrunsWhenSynthesisKeepsUp() {
        start()
        playUntil(120_000) { readium.current >= 20 }
        assertEquals(0, sink.underruns)
        assertEquals(0, sink.stalls)
        val done =
            readium.events
                .filter { it.startsWith("done:") }
                .map { it.removePrefix("done:").toInt() }
        assertEquals((0 until done.size).toList(), done)
        // Crossing from the first chapter into the second needed no wait either.
        assertTrue(plan.hrefOf(texts[20]) != plan.hrefOf(texts[0]))
    }

    @Test
    fun aLateRequestIsAnsweredAtOnceWhenItsSentenceWasHeard() {
        start()
        readium.roundTripMs = audioMs(texts[1]) + 1_000
        playUntil(60_000) { readium.current >= 2 }
        val asked = readium.timeline.first { it.second == "speak:1" }.first
        val done = readium.timeline.first { it.second == "done:1" }.first
        assertTrue("answered within a step, took ${done - asked} ms", done - asked <= STEP_MS)
    }

    @Test
    fun stopThenTheSameSentenceResumesWithoutFlushOrNewSynthesis() {
        start()
        play(1_000)
        val head = sink.headFrame
        engine.stop()
        settle()
        assertTrue("interrupted:0" in readium.events)
        assertEquals(SinkCommand.Pause, sink.commands.last())
        val flushes = sink.flushCount
        readium.speakCurrent()
        settle()
        play(200)
        assertEquals(flushes, sink.flushCount)
        assertEquals(1, voice.calls.count { it.first == texts[0] })
        assertTrue("continues from where it paused", sink.headFrame > head)
        playUntil(10_000) { "done:0" in readium.events }
    }

    @Test
    fun stopThenTheNextSentenceFlushesAndPlaysAudioAlreadyMade() {
        start()
        play(1_000)
        engine.stop()
        settle()
        readium.current = 1
        readium.speakCurrent()
        settle()
        assertTrue(sink.commands.any { it is SinkCommand.Flush && it.rewindTo != null })
        playUntil(10_000) { "done:1" in readium.events }
        assertFalse("done:0" in readium.events)
        assertEquals(1, voice.calls.count { it.first == texts[1] })
    }

    @Test
    fun stopThenThePreviousSentenceComesFromRecentAudio() {
        start()
        playUntil(30_000) { readium.current >= 2 }
        play(500)
        engine.stop()
        settle()
        readium.current = 1
        readium.events.clear()
        readium.speakCurrent()
        settle()
        assertTrue(sink.commands.any { it is SinkCommand.Flush && it.rewindTo != null })
        playUntil(10_000) { "done:1" in readium.events }
        assertEquals(1, voice.calls.count { it.first == texts[1] })
    }

    @Test
    fun aSpeedChangeNeverPlaysOldAudioAndStartsTheNewAtOnce() {
        start()
        playUntil(30_000) { readium.current >= 1 }
        val current = readium.current
        voice.hold = CountDownLatch(1)
        play(50)
        // Readium's order: preferences, then stop, then speak the same sentence again.
        engine.submitPreferences(KokoroPreferences(speed = 1.5))
        engine.stop()
        sink.writtenValues.clear()
        voice.hold!!.countDown()
        settle()
        assertTrue(
            "the current sentence is on its way at the new speed before Readium asks",
            voice.calls.any { it.first == texts[current] && it.second == 1.5f },
        )
        readium.speakCurrent()
        settle()
        playUntil(20_000) { "done:$current" in readium.events }
        assertEquals(setOf(FakeVoice.level(1.5f)), sink.writtenValues)
    }

    @Test
    fun aVoiceChangeNeverPlaysTheOldVoice() {
        start()
        playUntil(30_000) { readium.current >= 1 }
        val current = readium.current
        voice.hold = CountDownLatch(1)
        play(50)
        engine.submitPreferences(KokoroPreferences(speaker = 2))
        engine.stop()
        sink.writtenValues.clear()
        voice.hold!!.countDown()
        settle()
        readium.speakCurrent()
        settle()
        playUntil(20_000) { "done:$current" in readium.events }
        assertEquals(setOf(FakeVoice.level(1f, sid = 2)), sink.writtenValues)
    }

    @Test
    fun aResultThatArrivesAfterCloseNeverReachesReadiumOrTheOutput() {
        start()
        playUntil(30_000) { readium.current >= 1 }
        voice.hold = CountDownLatch(1)
        // A new generation starts synthesising the current sentence, and is held there.
        engine.submitPreferences(KokoroPreferences(speed = 1.25))
        val end = System.currentTimeMillis() + 5_000
        while (!voice.stuck) {
            if (System.currentTimeMillis() > end) fail("synthesis never started")
            Thread.sleep(1)
        }
        val calls = voice.calls.size
        engine.close()
        val events = readium.events.toList()
        val commands = sink.commands.size
        voice.hold!!.countDown()
        settle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        settle()
        assertTrue("the held request did finish", voice.calls.size > calls)
        assertEquals("no listener call after close", events, readium.events.toList())
        assertEquals("nothing sent to the output after release", commands, sink.commands.size)
        assertEquals(SinkCommand.Release, sink.commands.last())
        assertTrue(engine.status.value is NarrationStatus.Idle)
    }

    @Test
    fun aRequestOutsideThePlanIsMadeNextAndPlayed() {
        voice.hold = CountDownLatch(1)
        start()
        val elsewhere = "A sentence from somewhere else in the book, far from here."
        // Readium stops the current request before it asks for another.
        engine.stop()
        readium.speak(elsewhere)
        voice.hold!!.countDown()
        settle()
        val made = voice.calls.map { it.first }
        assertEquals(elsewhere, made[made.indexOf(texts[0]) + 1])
        playUntil(20_000) { readium.events.any { it == "done:x" } }
    }

    @Test
    fun theRuntimeRunsUrgentJobsFirstThenInPlanOrder() {
        start(speak = false)
        val gate = CountDownLatch(1)
        val order = CopyOnWriteArrayList<Long>()
        KokoroRuntime.submit(1, 1, 0, { true }) { gate.await(2, TimeUnit.SECONDS) }
        KokoroRuntime.submit(1, 5, 0, { true }) { order += 5 }
        KokoroRuntime.submit(1, 3, 0, { true }) { order += 3 }
        KokoroRuntime.submit(0, 9, 0, { true }) { order += 9 }
        KokoroRuntime.submit(0, 2, 0, { false }) { order += 2 }
        gate.countDown()
        settle()
        assertEquals(listOf(9L, 3L, 5L), order.toList())
    }

    @Test
    fun anEmptyResultIsHeardAsSilenceButThreeInARowStopReading() {
        voice.empty = { it == texts[1] }
        start()
        playUntil(30_000) { readium.current >= 3 }
        assertTrue("done:1" in readium.events)
        assertTrue(readium.events.none { it.startsWith("error") })
        voice.empty = { true }
        engine.submitPreferences(KokoroPreferences(speaker = 2))
        engine.stop()
        readium.speakCurrent()
        playUntil(10_000) { readium.events.any { it.startsWith("error") } }
        assertTrue(engine.status.value is NarrationStatus.Failed)
    }

    @Test
    fun closingWhileSynthesizingFreesTheModelAfterwardOnItsThread() {
        start()
        voice.hold = CountDownLatch(1)
        play(50)
        engine.submitPreferences(KokoroPreferences(speed = 1.25))
        Thread.sleep(50)
        engine.close()
        KokoroRuntime.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        Thread.sleep(100)
        assertEquals("not freed while generating", 0, voice.releases.size)
        voice.hold!!.countDown()
        settle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
        settle()
        assertEquals(listOf("kokoro-synth"), voice.releases.toList())
    }

    @Test
    fun preparingShowsOnlyWhileWaitingAndHoldsTheWakeLock() {
        voice.hold = CountDownLatch(1)
        start()
        assertTrue(engine.status.value is NarrationStatus.Preparing)
        assertTrue(wake.held)
        voice.hold!!.countDown()
        settle()
        play(100)
        assertTrue(engine.status.value is NarrationStatus.Playing)
        play(2_000)
        assertTrue(engine.status.value is NarrationStatus.Playing)
        engine.stop()
        settle()
        assertFalse("released once paused", wake.held)
    }

    @Test
    fun threeStallsInFiveMinutesAskWhatToDo() {
        voice.permits = Semaphore(1)
        start()
        repeat(3) { stall ->
            playUntil(60_000) { sink.stalls > stall }
            play(500)
            if (stall < 2) {
                assertTrue(
                    "stall $stall: status=${engine.status.value} ${sink.debug()}",
                    engine.status.value is NarrationStatus.Preparing,
                )
                voice.permits!!.release()
                settle()
                play(100)
            }
        }
        assertTrue(
            "status=${engine.status.value} stalls=${sink.stalls}",
            engine.status.value is NarrationStatus.TooSlow,
        )
    }

    @Test
    fun aTrustedSlowMeasurementAsksOnScreenAndReadsWithBreaksOffScreen() {
        // Held, so the gate decides on the saved measurement alone.
        voice.hold = CountDownLatch(1)
        start(prior = SLOW)
        assertTrue(engine.status.value is NarrationStatus.TooSlow)
        engine.close()
        screenOn = false
        start(prior = SLOW)
        assertFalse(engine.status.value is NarrationStatus.TooSlow)
        voice.hold!!.countDown()
        playUntil(60_000) { "done:0" in readium.events }
    }

    @Test
    fun aGuessAndAWarmUpNeverAskWhatToDo() {
        // Nothing real is measured yet: the unmeasured standard prior (RTF 1.5) and a short,
        // slow-looking warm-up must not open the "too slow" question.
        voice.hold = CountDownLatch(1)
        start(
            prior = ThroughputPrior.INT8_DEFAULT,
            warmup = SynthesisObservation(chars = 6, synthSeconds = 0.9, audioSeconds = 0.4),
        )
        assertTrue(
            "status=${engine.status.value}",
            engine.status.value is NarrationStatus.Preparing,
        )
    }

    @Test
    fun theEndOfTheBookCompletesTheLastRequest() {
        start()
        playUntil(400_000) {
            readium.current == texts.lastIndex && "done:${texts.lastIndex}" in readium.events
        }
    }

    private companion object {
        const val RATE = 8_000
        const val STEP_MS = 10
        var nextConfig = 100

        /** Synthesis so fast the gate opens as soon as the first unit is ready. */
        val FAST = ThroughputPrior(0.01, 0.0001, 0.051, 0.1, 0, 0)

        /** Twice slower than real time, measured many times: never keeps up. */
        val SLOW = ThroughputPrior(0.4, 0.102, 0.051, 0.1, 50, 0)

        fun sentence(i: Int) =
            "Sentence $i walks slowly along the quiet river bank before the light of dawn."
    }
}

/** A model that makes a flat tone, its level set by the speed, its length by the text. */
internal class FakeVoice(override val sampleRate: Int) : LoadedVoice {
    val calls = CopyOnWriteArrayList<Pair<String, Float>>()
    val releases = CopyOnWriteArrayList<String>()
    @Volatile var hold: CountDownLatch? = null
    @Volatile var permits: Semaphore? = null
    @Volatile var empty: (String) -> Boolean = { false }
    @Volatile var waiting = false

    /** Waiting on a hold that is still closed: nothing else can run until the test opens it. */
    val stuck: Boolean
        get() = waiting && ((hold?.count ?: 0L) > 0L || permits?.availablePermits() == 0)

    fun frames(text: String, speed: Double) = (text.length * 0.051 * sampleRate / speed).toInt()

    override fun synthesize(
        speechText: String,
        sid: Int,
        speed: Float,
        silenceScale: Float,
        keepGoing: () -> Boolean,
    ): FloatArray {
        waiting = true
        try {
            hold?.await(5, TimeUnit.SECONDS)
            permits?.tryAcquire(60, TimeUnit.SECONDS)
        } finally {
            waiting = false
        }
        calls += speechText to speed
        if (empty(speechText)) return FloatArray(0)
        return FloatArray(frames(speechText, speed.toDouble())) { sample(speed, sid) }
    }

    override fun release() {
        releases += Thread.currentThread().name
    }

    companion object {
        const val LEVEL_BASE = 0.2f

        /** The tone's level: set by the speed and the voice, so old audio is told apart. */
        fun sample(speed: Float, sid: Int): Float = LEVEL_BASE + 0.1f * speed + 0.01f * sid

        /** The 16-bit sample the tone becomes at [speed] and [sid], away from its fades. */
        fun level(speed: Float, sid: Int = KOKORO_DEFAULT_SPEAKER): Short =
            (sample(speed, sid) * 32767f).toInt().toShort()
    }
}

/**
 * The output, simulated: it writes what the queue allows into a 0.5 s buffer, plays as many frames
 * as each step lasts and reports what was heard, like [AudioTrackSink] but on the test thread.
 */
internal class FakeAudioSink(
    private val queue: PcmQueue,
    private val limits: WriteLimits,
    private val events: SinkEvents,
    private val rate: Int,
) : AudioSink {
    val commands = CopyOnWriteArrayList<SinkCommand>()
    val writtenValues = HashSet<Short>()
    var flushCount = 0
    var stalls = 0
    private var playing = false
    private var stalled = false
    private var drained = false
    private val buffer = rate / 2
    private val starvation = (NarrationTuning.DEFAULT.starvationSeconds * rate).toLong()

    override var headFrame = 0L
        private set

    override var underruns = 0
        private set

    override fun send(command: SinkCommand) {
        commands += command
        when (command) {
            SinkCommand.Play,
            SinkCommand.Resume -> {
                playing = true
                stalled = false
                drained = false
            }
            SinkCommand.Pause -> playing = false
            is SinkCommand.Flush -> {
                playing = false
                stalled = false
                flushCount++
                headFrame = 0
                queue.onFlush()
                command.rewindTo?.let {
                    if (!queue.rewindTo(it)) events.onRewindFailed(command.gen, it)
                }
            }
            SinkCommand.Release -> playing = false
        }
    }

    private fun fill() {
        while (queue.writtenFrames - headFrame < buffer) {
            val item =
                queue.nextToWrite(
                    limits.maxUtterance,
                    limits.requestEnd,
                    limits.maxFramesPastRequestEnd,
                ) ?: return
            val room = (buffer - (queue.writtenFrames - headFrame)).toInt()
            val count =
                if (item.count > 0) minOf(item.count, room) else minOf(item.gapRemaining, room)
            if (count <= 0) return
            // The middle of a long write is past the 5 ms fades: the tone's own level.
            if (item.count > 0 && count >= 300) writtenValues += item.pcm[item.offset + count / 2]
            queue.markWritten(item.seq, count)
        }
    }

    fun step(ms: Int) {
        if (!playing || stalled) return
        fill()
        val want = ms.toLong() * rate / 1000
        val available = queue.writtenFrames - headFrame
        val dry = queue.snapshot(headFrame).readyUnits == 0
        if (available < want + starvation && dry && !limits.planEnded) {
            // Like the real writer: pause at the boundary when the next unit is not made yet.
            headFrame += maxOf(0L, available - starvation)
            report()
            stalled = true
            stalls++
            events.onStarved(limits.gen)
            return
        }
        if (available < want && !limits.planEnded) underruns++
        headFrame += minOf(available, want)
        report()
        fill()
        if (limits.planEnded && queue.writtenFrames - headFrame <= 0 && !drained) {
            drained = true
            events.onDrained(limits.gen)
        }
    }

    fun debug() =
        "playing=$playing stalled=$stalled head=$headFrame written=${queue.writtenFrames} " +
            "snapshot=${queue.snapshot(headFrame)} planEnded=${limits.planEnded} " +
            "limits=${limits.requestEnd}/${limits.maxUtterance} commands=${commands.takeLast(4)}"

    private fun report() {
        val heard = queue.onHead(headFrame)
        if (heard.isNotEmpty()) events.onHeard(flushCount, heard)
    }
}

/** Readium's player, reduced: speak, wait for done, ask for the next one. Main thread only. */
internal class FakeReadium(private val engine: KokoroEngine, private val texts: List<String>) :
    TtsEngine.Listener<KokoroError> {
    val events = CopyOnWriteArrayList<String>()
    val timeline = CopyOnWriteArrayList<Pair<Long, String>>()
    val offMain = CopyOnWriteArrayList<String>()
    var current = 0
    var roundTripMs = 0L
    private var id: TtsEngine.RequestId? = null
    private var asked = 0
    private var spokenIndex = 0
    private val main = Handler(Looper.getMainLooper())

    fun speakCurrent() = speak(texts[current], current)

    fun speak(text: String, index: Int = -1) {
        val request = TtsEngine.RequestId("r${asked++}")
        id = request
        spokenIndex = index
        note("speak:${label()}")
        engine.speak(request, text, null)
    }

    private fun label() = if (spokenIndex < 0) "x" else spokenIndex.toString()

    private fun note(event: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) offMain += event
        events += event
        timeline += SystemClock.elapsedRealtime() to event
    }

    override fun onStart(requestId: TtsEngine.RequestId) {
        if (requestId == id) note("start:${label()}")
    }

    override fun onRange(requestId: TtsEngine.RequestId, range: IntRange) = Unit

    override fun onInterrupted(requestId: TtsEngine.RequestId) {
        if (requestId == id) note("interrupted:${label()}")
    }

    override fun onFlushed(requestId: TtsEngine.RequestId) = Unit

    override fun onDone(requestId: TtsEngine.RequestId) {
        if (requestId != id) {
            note("stale-done")
            return
        }
        note("done:${label()}")
        if (spokenIndex < 0 || current + 1 >= texts.size) return
        current++
        val next = Runnable { speakCurrent() }
        if (roundTripMs == 0L) main.post(next) else main.postDelayed(next, roundTripMs)
    }

    override fun onError(requestId: TtsEngine.RequestId, error: KokoroError) {
        note("error:${label()}")
    }
}

/** Chapters of paragraphs of sentences, walked like [UtterancePlanner] walks a book. */
internal class FakePlanSource(chapters: List<List<List<String>>>) : PlanSource {
    private val all: List<PlannedUtterance> = buildList {
        var ordinal = 0L
        chapters.forEachIndexed { c, paragraphs ->
            for (paragraph in paragraphs) {
                paragraph.forEachIndexed { i, text ->
                    add(
                        PlannedUtterance(
                            index = size.toLong(),
                            key = normalizeKey(text),
                            text = text,
                            href = "chapter$c.xhtml",
                            elementOrdinal = ordinal,
                            endsSentence = endsSentence(text),
                            endsElement = i == paragraph.lastIndex,
                        )
                    )
                }
                ordinal++
            }
        }
    }

    fun hrefOf(text: String) = all.first { it.text == text }.href

    override fun walk(from: Locator?): PlanWalker {
        val start =
            if (from == null) 0
            else all.indexOfFirst { it.text == from.text.highlight }.coerceAtLeast(0)
        return object : PlanWalker {
            private var at = start

            override suspend fun next(max: Int): List<PlannedUtterance> =
                all.subList(at, minOf(all.size, at + max)).also { at += it.size }
        }
    }
}

internal class FakeWakeLock : NarrationWakeLock {
    var held = false

    override fun hold(timeoutMs: Long) {
        held = true
    }

    override fun release() {
        held = false
    }
}
