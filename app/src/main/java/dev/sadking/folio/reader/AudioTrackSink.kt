package dev.sadking.folio.reader

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import dev.sadking.folio.reader.narration.HeardEvent
import dev.sadking.folio.reader.narration.NarrationTuning
import dev.sadking.folio.reader.narration.PcmQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** Where narration audio goes. Commands are queued; the sink acts on them on its own thread. */
internal interface AudioSink {
    fun send(command: SinkCommand)

    /** Frames heard since the last flush. */
    val headFrame: Long

    /** Underruns counted by the output so far. */
    val underruns: Int
}

internal sealed interface SinkCommand {
    /** Start or continue writing and playing. */
    data object Play : SinkCommand

    /** Stop playing but keep what is buffered: [Resume] continues mid-sentence. */
    data object Pause : SinkCommand

    data object Resume : SinkCommand

    /**
     * Drop what is buffered (written but not yet heard returns to the queue), then, when [rewindTo]
     * is set, rewind the queue so that utterance is written next.
     */
    data class Flush(val rewindTo: Long?, val gen: Long) : SinkCommand

    data object Release : SinkCommand
}

/**
 * How far ahead of Readium's request the writer may go, in the queue's index space (the engine's
 * piece ids). Set on Main, read by the writer.
 */
internal class WriteLimits {
    @Volatile var gen = 0L
    /** Where the requested utterance starts: replayed from here after an output restart. */
    @Volatile var requestStart = -1L
    @Volatile var maxUtterance = -1L
    @Volatile var requestEnd = -1L
    @Volatile var maxFramesPastRequestEnd = 0L
    /** No more audio will come: running dry is the end of the book, not a stall. */
    @Volatile var planEnded = false
}

/** What the sink reports, from its thread; the engine moves each report to Main. */
internal interface SinkEvents {
    /** [flushes] counts the [SinkCommand.Flush]es applied before these events. */
    fun onHeard(flushes: Int, events: List<HeardEvent>)

    /** Nothing left to play and the next unit is not ready: the sink paused at the boundary. */
    fun onStarved(gen: Long)

    /** Everything written was heard and the plan has ended. */
    fun onDrained(gen: Long)

    /** The first audio of this sink reached the speaker. */
    fun onFirstAudio()

    /** The queue could not rewind to the requested utterance after a flush. */
    fun onRewindFailed(gen: Long, utteranceIndex: Long)

    fun onError(message: String)
}

/** The audio output the writer drives; [AudioTrack] on a phone, a fake in JVM tests. */
internal interface PcmOutput {
    fun play()

    fun pause()

    fun flush()

    /** Non-blocking: returns frames taken (0 when full) or a negative error code. */
    fun write(pcm: ShortArray, offset: Int, count: Int): Int

    /** Raw playback head: frames played, as the output counts them (wraps at 2^32). */
    val rawHead: Long

    val underruns: Int

    fun release()
}

/** One [AudioTrack] in streaming mode, 16-bit mono: never stopped or flushed at a boundary. */
internal class AudioTrackOutput(sampleRate: Int, tuning: NarrationTuning) : PcmOutput {
    private val track =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes((sampleRate * tuning.trackBufferSeconds).toInt() * 2)
            .build()
            .apply {
                startThresholdInFrames =
                    (sampleRate * tuning.trackStartThresholdSeconds).toInt().coerceAtLeast(1)
            }

    override fun play() = track.play()

    override fun pause() = track.pause()

    override fun flush() = track.flush()

    override fun write(pcm: ShortArray, offset: Int, count: Int): Int =
        track.write(pcm, offset, count, AudioTrack.WRITE_NON_BLOCKING)

    override val rawHead: Long
        get() = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    override val underruns: Int
        get() = track.underrunCount

    override fun release() = track.release()
}

/**
 * The writer ("kokoro-audio"): feeds ready audio from [queue] into one output, in order, without
 * stopping between units, and reports which utterances have been heard from the playback head. It
 * is the only thread that touches the output, so a stop can never race a write. It never waits for
 * synthesis: when nothing is ready it pauses at the unit boundary and says so.
 */
internal class AudioTrackSink(
    private val queue: PcmQueue,
    private val sampleRate: Int,
    private val tuning: NarrationTuning,
    private val limits: WriteLimits,
    private val events: SinkEvents,
    private val output: () -> PcmOutput = { AudioTrackOutput(sampleRate, tuning) },
) : AudioSink {
    private val commands = ConcurrentLinkedQueue<SinkCommand>()
    private val thread = Thread(::run, "kokoro-audio").apply { isDaemon = true }

    @Volatile
    override var headFrame: Long = 0
        private set

    @Volatile
    override var underruns: Int = 0
        private set

    init {
        thread.start()
    }

    override fun send(command: SinkCommand) {
        commands.add(command)
        LockSupport.unpark(thread)
    }

    private lateinit var out: PcmOutput
    private var playing = false
    private var stalled = false
    private var drained = false
    private var heardAny = false
    private var recreated = false
    private var flushes = 0
    private var lastRaw = 0L
    private var underrunBase = 0
    private val zeros = ShortArray(sampleRate / 10)
    private val starvationFrames = (tuning.starvationSeconds * sampleRate).toLong()

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        try {
            out = output()
        } catch (failure: Throwable) {
            events.onError(failure.message ?: "Audio output failed")
            return
        }
        while (true) {
            while (true) {
                val command = commands.poll() ?: break
                if (!apply(command)) return
            }
            if (playing && !stalled) {
                write()
                readHead()
                checkDry()
            }
            val tick = if (playing) tuning.writerTickMs else IDLE_TICK_MS
            if (commands.isEmpty()) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(tick))
        }
    }

    /** False when the sink is released. */
    private fun apply(command: SinkCommand): Boolean {
        when (command) {
            SinkCommand.Play,
            SinkCommand.Resume -> {
                playing = true
                stalled = false
                drained = false
                out.play()
            }
            SinkCommand.Pause -> {
                if (playing) out.pause()
                readHead()
                playing = false
            }
            is SinkCommand.Flush -> {
                flushes++
                out.pause()
                out.flush()
                playing = false
                stalled = false
                drained = false
                resetHead()
                queue.onFlush()
                command.rewindTo?.let { index ->
                    if (!queue.rewindTo(index)) events.onRewindFailed(command.gen, index)
                }
            }
            SinkCommand.Release -> {
                out.release()
                return false
            }
        }
        return true
    }

    /** Writes until the output is full or nothing more may be written yet. */
    private fun write() {
        while (true) {
            val item =
                queue.nextToWrite(
                    limits.maxUtterance,
                    limits.requestEnd,
                    limits.maxFramesPastRequestEnd,
                ) ?: return
            val written =
                if (item.count > 0) out.write(item.pcm, item.offset, item.count)
                else if (item.gapRemaining > 0)
                    out.write(zeros, 0, minOf(zeros.size, item.gapRemaining))
                else 0
            if (written < 0) {
                recover(written)
                return
            }
            if (written == 0) return
            queue.markWritten(item.seq, written)
        }
    }

    /** A dead output (audio server restart) is rebuilt once; a second failure is reported. */
    private fun recover(code: Int) {
        if (code == AudioTrack.ERROR_DEAD_OBJECT && !recreated) {
            recreated = true
            try {
                out.release()
            } catch (_: Throwable) {}
            try {
                out = output()
                resetHead()
                queue.onFlush()
                if (limits.requestStart >= 0) queue.rewindTo(limits.requestStart)
                if (playing) out.play()
                return
            } catch (_: Throwable) {}
        }
        playing = false
        events.onError("Audio output failed ($code)")
    }

    private fun resetHead() {
        headFrame = 0
        lastRaw = out.rawHead
        underrunBase = out.underruns
    }

    private fun readHead() {
        val raw = out.rawHead
        val delta =
            when {
                raw >= lastRaw -> raw - lastRaw
                // Wrapped past 2^32 frames (about 50 hours at 24 kHz).
                lastRaw > WRAP_ZONE && raw < 0x10000000L -> raw + 0x100000000L - lastRaw
                // The output reset its count after a flush: count from zero.
                else -> raw
            }
        lastRaw = raw
        if (delta > 0) {
            headFrame += delta
            if (!heardAny) {
                heardAny = true
                events.onFirstAudio()
            }
        }
        underruns = out.underruns - underrunBase
        val heard = queue.onHead(headFrame)
        if (heard.isNotEmpty()) events.onHeard(flushes, heard)
    }

    /**
     * When the output is about to run dry: at the end of the book, report it once everything was
     * heard; when the next unit is not made yet, pause here so the gap falls between units and the
     * engine decides when to go on. When audio is ready but held back for Readium, let it play out.
     */
    private fun checkDry() {
        val ahead = queue.writtenFrames - headFrame
        if (ahead >= starvationFrames) return
        if (limits.planEnded) {
            if (ahead <= 0 && !drained && queue.snapshot(headFrame).readyUnits == 0) {
                drained = true
                events.onDrained(limits.gen)
            }
            return
        }
        if (queue.snapshot(headFrame).readyUnits > 0) return
        stalled = true
        out.pause()
        events.onStarved(limits.gen)
    }

    private companion object {
        const val IDLE_TICK_MS = 250L
        const val WRAP_ZONE = 0xF0000000L
    }
}
