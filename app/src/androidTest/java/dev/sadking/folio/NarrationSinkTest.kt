package dev.sadking.folio

import dev.sadking.folio.reader.AudioTrackSink
import dev.sadking.folio.reader.SinkCommand
import dev.sadking.folio.reader.SinkEvents
import dev.sadking.folio.reader.WriteLimits
import dev.sadking.folio.reader.narration.HeardEvent
import dev.sadking.folio.reader.narration.NarrationTuning
import dev.sadking.folio.reader.narration.PcmQueue
import dev.sadking.folio.reader.narration.ReadyUnit
import dev.sadking.folio.reader.narration.Span
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The real audio path: tone units through [AudioTrackSink] and one streaming AudioTrack. They must
 * be heard in order, and the track must not underrun between units. Run on an emulator, this says
 * nothing about a phone's timing; it checks the order and continuity of the output path. Skipped
 * when the playback head never moves (an emulator started with -noaudio).
 */
class NarrationSinkTest {
    private val rate = 24_000

    private fun tone(seq: Long, seconds: Double, gapSeconds: Double): ReadyUnit {
        val frames = (seconds * rate).toInt()
        val pcm = ShortArray(frames) { (sin(2 * PI * 440 * it / rate) * 6_000).toInt().toShort() }
        return ReadyUnit(
            seq = seq,
            gen = 1,
            firstUtterance = seq,
            lastUtterance = seq,
            pcm = pcm,
            gapFrames = (gapSeconds * rate).toInt(),
            spans = listOf(Span(seq, "u$seq", 0, frames)),
            cacheKey = "tone$seq",
            cacheHit = false,
        )
    }

    @Test
    fun toneUnitsAreHeardInOrderWithoutUnderrunsBetweenThem() {
        val queue = PcmQueue(rate, NarrationTuning.DEFAULT)
        for (i in 0 until 6) queue.offer(tone(i.toLong(), 0.4, 0.15), 1)
        val limits =
            WriteLimits().apply {
                gen = 1
                requestStart = 0
                requestEnd = 0
                maxUtterance = 5
                maxFramesPastRequestEnd = 60L * rate
                planEnded = true
            }
        val done = CopyOnWriteArrayList<Long>()
        val underrunsAtDone = CopyOnWriteArrayList<Int>()
        lateinit var sink: AudioTrackSink
        val drained = java.util.concurrent.atomic.AtomicBoolean(false)
        val events =
            object : SinkEvents {
                override fun onHeard(flushes: Int, events: List<HeardEvent>) {
                    for (event in events.filterIsInstance<HeardEvent.Done>()) {
                        done += event.utteranceIndex
                        underrunsAtDone += sink.underruns
                    }
                }

                override fun onStarved(gen: Long) = Unit

                override fun onDrained(gen: Long) {
                    drained.set(true)
                }

                override fun onFirstAudio() = Unit

                override fun onRewindFailed(gen: Long, utteranceIndex: Long) = Unit

                override fun onError(message: String) = Unit
            }
        sink = AudioTrackSink(queue, rate, NarrationTuning.DEFAULT, limits, events)
        try {
            sink.send(SinkCommand.Play)
            val end = System.currentTimeMillis() + 15_000
            while (!drained.get() && System.currentTimeMillis() < end) Thread.sleep(20)
            assumeTrue("the playback head never moved (no audio device)", sink.headFrame > 0)
            assertEquals((0L until 6L).toList(), done.toList())
            assertEquals(
                "no underrun between the first and the last unit",
                underrunsAtDone.first(),
                underrunsAtDone.last(),
            )
        } finally {
            sink.send(SinkCommand.Release)
        }
    }
}
