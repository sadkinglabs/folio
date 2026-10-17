package dev.sadking.folio.reader

import android.media.AudioTrack
import dev.sadking.folio.reader.narration.HeardEvent
import dev.sadking.folio.reader.narration.NarrationTuning
import dev.sadking.folio.reader.narration.PcmQueue
import dev.sadking.folio.reader.narration.ReadyUnit
import dev.sadking.folio.reader.narration.Span
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real writer thread of [AudioTrackSink] against an output that plays in real time: units
 * follow each other with one play() and no pause or flush between them, nothing is written twice,
 * and a missing unit pauses at the boundary instead of underrunning.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioTrackSinkTest {
    private val rate = 8_000
    private val tuning = NarrationTuning.DEFAULT
    private val queue = PcmQueue(rate, tuning)
    private val limits = WriteLimits().apply { gen = 1 }
    private val output = RealTimeOutput(rate)
    private val events = Recorder()
    private val outputs = mutableListOf<PcmOutput>()
    private lateinit var sink: AudioTrackSink

    @After
    fun release() {
        if (::sink.isInitialized) sink.send(SinkCommand.Release)
    }

    private fun start() {
        sink = AudioTrackSink(queue, rate, tuning, limits, events) { output.also { outputs += it } }
    }

    /** A unit of [seconds] holding pieces [first]..[first + pieces - 1], each a constant level. */
    private fun unit(
        seq: Long,
        first: Long,
        pieces: Int,
        seconds: Double,
        gap: Int = 0,
    ): ReadyUnit {
        val frames = (seconds * rate).toInt()
        val per = frames / pieces
        val pcm = ShortArray(frames) { (1_000 + (first + it / per)).toShort() }
        val spans =
            (0 until pieces).map { k ->
                Span(
                    first + k,
                    "k${first + k}",
                    k * per,
                    if (k == pieces - 1) frames else (k + 1) * per,
                )
            }
        return ReadyUnit(seq, 1, first, first + pieces - 1, pcm, gap, spans, "key$seq", false)
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("timed out: ${events.heard} ${output.calls}")
            Thread.sleep(5)
        }
    }

    @Test
    fun unitsPlayBackToBackWithoutStoppingAndInOrder() {
        for (i in 0 until 4) queue.offer(unit(i.toLong(), i * 2L, 2, 0.15, gap = 400), 1)
        limits.requestEnd = 1
        limits.maxUtterance = 7
        limits.maxFramesPastRequestEnd = 10L * rate
        limits.planEnded = true
        start()
        sink.send(SinkCommand.Play)
        waitFor { events.drained }
        val done = events.heard.filterIsInstance<HeardEvent.Done>().map { it.utteranceIndex }
        assertEquals((0L..7L).toList(), done)
        assertEquals(listOf("play"), output.calls.filter { it != "write" })
        assertEquals(
            "every frame written once",
            4 * (0.15 * rate).toInt() + 4 * 400,
            output.written,
        )
        assertEquals(0, events.starved)
        assertEquals(0, sink.underruns)
    }

    @Test
    fun aMissingUnitPausesAtTheBoundaryAndPlayResumes() {
        queue.offer(unit(0, 0, 1, 0.2), 1)
        limits.requestEnd = 0
        limits.maxUtterance = 5
        limits.maxFramesPastRequestEnd = 10L * rate
        start()
        sink.send(SinkCommand.Play)
        waitFor { events.starved == 1 }
        assertTrue(output.calls.last() == "pause")
        queue.offer(unit(1, 1, 1, 0.2), 1)
        sink.send(SinkCommand.Play)
        limits.planEnded = true
        waitFor { events.drained }
        assertEquals(
            listOf(0L, 1L),
            events.heard.filterIsInstance<HeardEvent.Done>().map { it.utteranceIndex },
        )
    }

    @Test
    fun aFlushRewindsToTheRequestedPieceAndCountsItself() {
        for (i in 0 until 3) queue.offer(unit(i.toLong(), i.toLong(), 1, 0.3), 1)
        limits.requestEnd = 0
        limits.maxUtterance = 2
        limits.maxFramesPastRequestEnd = 10L * rate
        start()
        sink.send(SinkCommand.Play)
        waitFor { events.heard.any { it is HeardEvent.Started && it.utteranceIndex == 0L } }
        sink.send(SinkCommand.Pause)
        sink.send(SinkCommand.Flush(rewindTo = 2, gen = 1))
        limits.requestEnd = 2
        limits.planEnded = true
        sink.send(SinkCommand.Play)
        waitFor { events.drained }
        assertTrue(output.calls.contains("flush"))
        assertEquals(1, events.flushesSeen.last())
        assertTrue(events.heard.none { it is HeardEvent.Done && it.utteranceIndex == 1L })
    }

    @Test
    fun aDeadOutputIsRebuiltOnceAndReplaysTheRequest() {
        queue.offer(unit(0, 0, 1, 0.3), 1)
        limits.requestStart = 0
        limits.requestEnd = 0
        limits.maxUtterance = 0
        limits.maxFramesPastRequestEnd = 0
        limits.planEnded = true
        output.failNextWrite = AudioTrack.ERROR_DEAD_OBJECT
        start()
        sink.send(SinkCommand.Play)
        waitFor { events.drained }
        assertEquals(2, outputs.size)
        assertEquals(emptyList<String>(), events.errors)
    }

    private class Recorder : SinkEvents {
        val heard = CopyOnWriteArrayList<HeardEvent>()
        val flushesSeen = CopyOnWriteArrayList<Int>()
        val errors = CopyOnWriteArrayList<String>()
        @Volatile var starved = 0
        @Volatile var drained = false

        override fun onHeard(flushes: Int, events: List<HeardEvent>) {
            flushesSeen += flushes
            heard += events
        }

        override fun onStarved(gen: Long) {
            starved++
        }

        override fun onDrained(gen: Long) {
            drained = true
        }

        override fun onFirstAudio() = Unit

        override fun onRewindFailed(gen: Long, utteranceIndex: Long) {
            errors += "rewind $utteranceIndex"
        }

        override fun onError(message: String) {
            errors += message
        }
    }
}

/** Plays at [rate] frames per second of wall time from a 0.5 s buffer, like an AudioTrack. */
internal class RealTimeOutput(private val rate: Int) : PcmOutput {
    val calls = CopyOnWriteArrayList<String>()
    @Volatile var written = 0
    @Volatile var failNextWrite = 0
    private val capacity = rate / 2
    private var buffered = 0L
    private var played = 0L
    private var since = 0L
    private var playing = false
    private var dry = 0

    @Synchronized
    private fun advance() {
        val now = System.nanoTime()
        if (playing) {
            val frames = (now - since) * rate / 1_000_000_000
            val step = minOf(frames, buffered)
            if (frames > buffered && buffered >= 0 && frames > 0 && played > 0 && buffered == 0L)
                dry++
            played += step
            buffered -= step
            if (frames > 0) since = now
        } else since = now
    }

    @Synchronized
    override fun play() {
        advance()
        calls += "play"
        playing = true
        since = System.nanoTime()
    }

    @Synchronized
    override fun pause() {
        advance()
        calls += "pause"
        playing = false
    }

    @Synchronized
    override fun flush() {
        calls += "flush"
        buffered = 0
        played = 0
    }

    @Synchronized
    override fun write(pcm: ShortArray, offset: Int, count: Int): Int {
        if (failNextWrite != 0) {
            val code = failNextWrite
            failNextWrite = 0
            return code
        }
        advance()
        if (calls.lastOrNull() != "write") calls += "write"
        val n = minOf(count.toLong(), capacity - buffered).toInt()
        buffered += n
        written += n
        return n
    }

    override val rawHead: Long
        @Synchronized
        get() {
            advance()
            return played
        }

    override val underruns: Int
        get() = 0

    override fun release() = Unit
}
