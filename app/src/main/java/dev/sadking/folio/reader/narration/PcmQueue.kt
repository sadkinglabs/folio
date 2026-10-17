package dev.sadking.folio.reader.narration

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

/** Where one utterance lies in a unit's speech: frames [startFrame, endFrame). */
internal data class Span(
    val utteranceIndex: Long,
    val key: String,
    val startFrame: Int,
    val endFrame: Int,
)

/** A synthesised (or cached) unit, ready to be written to the track. */
internal class ReadyUnit(
    val seq: Long,
    val gen: Long,
    val firstUtterance: Long,
    val lastUtterance: Long,
    /** Edge-trimmed speech, no gap. */
    val pcm: ShortArray,
    /** Zeros to append after pcm. */
    val gapFrames: Int,
    /** Within pcm; last span's endFrame == pcm.size. */
    val spans: List<Span>,
    val cacheKey: String,
    val cacheHit: Boolean,
    /**
     * [SynthesisUnit.continues]: the last span's utterance goes on in the next unit, so its Done
     * waits for that unit (and the next unit's first span gets no second Started).
     */
    val continues: Boolean = false,
) {
    val frames: Int
        get() = pcm.size + gapFrames
}

/**
 * One write: [pcm] from [offset] for [count] frames; when pcm is exhausted, [gapRemaining] zeros
 * follow. Report every frame written, zeros included, with [PcmQueue.markWritten].
 */
internal data class WriteItem(
    val seq: Long,
    val pcm: ShortArray,
    val offset: Int,
    val count: Int,
    val gapRemaining: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is WriteItem &&
            seq == other.seq &&
            pcm === other.pcm &&
            offset == other.offset &&
            count == other.count &&
            gapRemaining == other.gapRemaining

    override fun hashCode(): Int =
        (((seq.hashCode() * 31 + System.identityHashCode(pcm)) * 31 + offset) * 31 + count) * 31 +
            gapRemaining
}

/** An utterance began to be heard, or was heard to its end, at the playback head. */
internal sealed interface HeardEvent {
    val gen: Long
    val utteranceIndex: Long

    data class Started(override val gen: Long, override val utteranceIndex: Long) : HeardEvent

    data class Done(override val gen: Long, override val utteranceIndex: Long) : HeardEvent
}

internal data class QueueSnapshot(
    /** Units with frames still to write. */
    val readyUnits: Int,
    /** Their unwritten listening seconds, contiguous or not. */
    val readySeconds: Double,
    val writtenUnheardSeconds: Double,
    /** As [PcmQueue.reserveSeconds]. */
    val reserveSeconds: Double,
    val recentUnits: Int,
    /** The unit written next, when it is ready. */
    val nextReadySeq: Long?,
)

/**
 * The bounded hand-off between synthesis and playback. Thread-safe: the writer thread, the
 * synthesis side (through Main) and Main all call it, under one lock. Frames are in track
 * coordinates since the last flush.
 *
 * Units are written strictly in seq order. A unit that arrives after a later one has started to be
 * written is refused. Heard units move to a small ring kept for skip-previous.
 */
internal class PcmQueue(
    val sampleRate: Int,
    private val tuning: NarrationTuning = NarrationTuning.DEFAULT,
    private val listeningSecondsPerFrame: Double = 1.0 / sampleRate,
) {
    private class Entry(val unit: ReadyUnit) {
        /** Frames before which writing starts (a rewind into the middle of the unit). */
        var startOffset = 0
        /** Events that count as emitted when writing starts at [startOffset]. */
        var startEvents = 0
        /** Frames of [ReadyUnit.frames] written, counting the skipped [startOffset]. */
        var written = 0
        /** Some of it has been written since the last flush. */
        var started = false
        /**
         * Track frame of the unit's frame 0, valid while [started]. Virtual (it can be negative)
         * when writing began at [startOffset] > 0.
         */
        var trackStart = 0L
        /** Events emitted: 2k = Started of span k, 2k + 1 = Done of span k. */
        var events = 0

        val fullyWritten: Boolean
            get() = written >= unit.frames

        fun reset() {
            written = startOffset
            started = false
            events = startEvents
        }

        fun rewindFrom(frame: Int, events: Int) {
            startOffset = frame
            startEvents = events
            reset()
        }
    }

    private val lock = ReentrantLock()
    private var gen: Long? = null
    /** Not yet heard, ascending seq: written-unheard first, then ready. */
    private val entries = ArrayList<Entry>()
    /** Heard, oldest first. */
    private val ring = ArrayDeque<Entry>()
    /** Seq written next; null = the first entry. */
    private var cursor: Long? = null
    private var written = 0L
    private var lastStarted: Long? = null

    val writtenFrames: Long
        get() = lock.withLock { written }

    /** False when gen is stale or seq is out of plan order (duplicates). */
    fun offer(unit: ReadyUnit, currentGen: Long): Boolean = lock.withLock {
        if (unit.gen != currentGen) return false
        val known = gen
        if (known != null && currentGen < known) return false
        if (known != currentGen) dropOtherGens(currentGen)
        if (entries.any { it.unit.seq == unit.seq } || ring.any { it.unit.seq == unit.seq }) {
            return false
        }
        val c = cursor
        if (c != null && unit.seq < c) return false
        if (entries.any { it.unit.seq > unit.seq && it.started }) return false
        if (ring.any { it.unit.seq > unit.seq }) return false
        val at = entries.indexOfFirst { it.unit.seq > unit.seq }
        if (at < 0) entries += Entry(unit) else entries.add(at, Entry(unit))
        true
    }

    /**
     * Next contiguous piece to write, or null. Honour the speculation limits: nothing beyond
     * utterance [maxUtteranceIndex] and nothing whose start is more than [maxFramesPastRequestEnd]
     * past the end of [requestEndUtterance]. The limits apply when a unit starts; a unit already
     * being written is always finished.
     */
    fun nextToWrite(
        maxUtteranceIndex: Long,
        requestEndUtterance: Long,
        maxFramesPastRequestEnd: Long,
    ): WriteItem? = lock.withLock {
        val e = cursorEntry() ?: return null
        if (e.fullyWritten) return null
        if (!e.started) {
            if (firstUtteranceToWrite(e) > maxUtteranceIndex) return null
            val end = requestEndFrame(requestEndUtterance)
            if (end == null) {
                if (!requestIsBehindQueue(requestEndUtterance, e)) return null
            } else if (written - end > maxFramesPastRequestEnd) {
                return null
            }
        }
        val pcm = e.unit.pcm
        val offset = min(e.written, pcm.size)
        WriteItem(
            seq = e.unit.seq,
            pcm = pcm,
            offset = offset,
            count = pcm.size - offset,
            gapRemaining = e.unit.gapFrames - max(0, e.written - pcm.size),
        )
    }

    /**
     * [frames] of [seq] were written (partial writes allowed); the unit's write offset advances.
     */
    fun markWritten(seq: Long, frames: Int) {
        lock.withLock {
            if (frames <= 0) return
            val e = cursorEntry() ?: return
            if (e.unit.seq != seq || e.fullyWritten) return
            if (!e.started) {
                e.trackStart = written - e.written
                e.started = true
            }
            val n = min(frames, e.unit.frames - e.written)
            e.written += n
            written += n
            cursor = if (e.fullyWritten) seq + 1 else seq
        }
    }

    /**
     * Heard events for utterances whose end (or start) frame ≤ [headFrame], in order, each at most
     * once.
     */
    fun onHead(headFrame: Long): List<HeardEvent> = lock.withLock {
        val head = min(headFrame, written)
        val out = ArrayList<HeardEvent>()
        while (entries.isNotEmpty()) {
            val e = entries[0]
            if (!e.started) break
            emitEvents(e, head, out)
            val heard =
                e.events >= 2 * e.unit.spans.size &&
                    e.fullyWritten &&
                    head >= e.trackStart + e.unit.frames
            if (!heard) break
            entries.removeAt(0)
            ring.addLast(e)
            trimRing()
        }
        out
    }

    /**
     * Track flushed: written-unheard units return to ready with their PCM; frame counters reset.
     */
    fun onFlush() {
        lock.withLock {
            entries.forEach { it.reset() }
            ring.forEach { it.started = false }
            written = 0
            cursor = null
            lastStarted = null
        }
    }

    /**
     * Rewind so [utteranceIndex] is written next (from ready, written-unheard or the recent ring).
     * False if it is no longer held. Caller flushes the track first. Units before it move to the
     * ring; writing starts at the utterance's first frame, even inside a merged unit.
     */
    fun rewindTo(utteranceIndex: Long): Boolean = lock.withLock {
        val inRing = ring.indexOfFirst { spanOf(it, utteranceIndex) != null }
        if (inRing >= 0) {
            val back = ring.toList().subList(inRing, ring.size)
            repeat(back.size) { ring.removeLast() }
            back.forEach { it.rewindFrom(0, 0) }
            entries.addAll(0, back)
        }
        val at = entries.indexOfFirst { spanOf(it, utteranceIndex) != null }
        if (at < 0) return false
        repeat(at) {
            val skipped = entries.removeAt(0)
            skipped.started = false
            ring.addLast(skipped)
        }
        trimRing()
        val target = entries[0]
        val k = spanOf(target, utteranceIndex)!!
        target.rewindFrom(target.unit.spans[k].startFrame, 2 * k)
        for (i in 1 until entries.size) entries[i].rewindFrom(0, 0)
        cursor = target.unit.seq
        lastStarted = null
        true
    }

    /**
     * Drops ready and written-unheard units of any other gen; the recent ring keeps only matching
     * gen. Gens only grow: an older [currentGen] than one already seen is ignored.
     */
    fun invalidate(currentGen: Long) {
        lock.withLock {
            val known = gen
            if (known == null || currentGen > known) dropOtherGens(currentGen)
        }
    }

    /**
     * Listening seconds the head can play without new synthesis: written-unheard frames plus the
     * ready frames contiguous from the write cursor.
     */
    fun reserveSeconds(headFrame: Long): Double = lock.withLock {
        reserveFrames(headFrame) * listeningSecondsPerFrame
    }

    fun isReady(seq: Long): Boolean = lock.withLock { entries.any { it.unit.seq == seq } }

    fun hasUtterance(utteranceIndex: Long): Boolean = lock.withLock {
        entries.any { spanOf(it, utteranceIndex) != null } ||
            ring.any { spanOf(it, utteranceIndex) != null }
    }

    fun snapshot(headFrame: Long): QueueSnapshot = lock.withLock {
        val pending = entries.filter { !it.fullyWritten }
        val next = cursorEntry()?.takeIf { !it.fullyWritten }
        QueueSnapshot(
            readyUnits = pending.size,
            readySeconds =
                pending.sumOf { (it.unit.frames - it.written).toLong() } * listeningSecondsPerFrame,
            writtenUnheardSeconds = max(0L, written - headFrame) * listeningSecondsPerFrame,
            reserveSeconds = reserveFrames(headFrame) * listeningSecondsPerFrame,
            recentUnits = ring.size,
            nextReadySeq = next?.unit?.seq,
        )
    }

    private fun reserveFrames(headFrame: Long): Long {
        var frames = max(0L, written - headFrame)
        val c = cursorEntry() ?: return frames
        var at = entries.indexOf(c)
        var seq = c.unit.seq
        frames += c.unit.frames - c.written
        while (++at < entries.size && entries[at].unit.seq == seq + 1) {
            frames += entries[at].unit.frames
            seq++
        }
        return frames
    }

    private fun cursorEntry(): Entry? {
        val c = cursor ?: return entries.firstOrNull()
        return entries.firstOrNull { it.unit.seq == c }
    }

    private fun firstUtteranceToWrite(e: Entry): Long =
        e.unit.spans.getOrNull(e.startEvents / 2)?.utteranceIndex ?: e.unit.firstUtterance

    /** Track frame where [utterance] ends, when the unit holding its end has been written. */
    private fun requestEndFrame(utterance: Long): Long? {
        var found: Pair<Entry, Span>? = null
        for (e in ring) e.unit.spans
            .lastOrNull { it.utteranceIndex == utterance }
            ?.let { found = e to it }
        for (e in entries) {
            e.unit.spans.lastOrNull { it.utteranceIndex == utterance }?.let { found = e to it }
        }
        val (e, span) = found ?: return null
        return if (e.started) e.trackStart + span.endFrame else null
    }

    /**
     * With no written end frame for the request: writing may start when the unit is not past the
     * request, or when the request is older than anything held (nothing to measure from).
     */
    private fun requestIsBehindQueue(request: Long, e: Entry): Boolean {
        if (firstUtteranceToWrite(e) <= request) return true
        val known =
            entries.any { spanOf(it, request) != null } || ring.any { spanOf(it, request) != null }
        val lowest = (ring.firstOrNull() ?: entries.firstOrNull())?.unit?.firstUtterance
        return !known && lowest != null && request < lowest
    }

    private fun emitEvents(e: Entry, head: Long, out: MutableList<HeardEvent>) {
        val spans = e.unit.spans
        while (e.events < 2 * spans.size) {
            val k = e.events / 2
            val span = spans[k]
            val start = e.trackStart + span.startFrame
            val end = e.trackStart + span.endFrame
            if (e.events % 2 == 0) {
                if (head <= start && head < end) return
                if (lastStarted != span.utteranceIndex) {
                    out += HeardEvent.Started(e.unit.gen, span.utteranceIndex)
                    lastStarted = span.utteranceIndex
                }
            } else {
                if (head < end) return
                val goesOn = k == spans.lastIndex && e.unit.continues
                if (!goesOn) out += HeardEvent.Done(e.unit.gen, span.utteranceIndex)
            }
            e.events++
        }
    }

    private fun spanOf(e: Entry, utterance: Long): Int? =
        e.unit.spans.indexOfFirst { it.utteranceIndex == utterance }.takeIf { it >= 0 }

    private fun trimRing() {
        var seconds = ring.sumOf { it.unit.frames.toLong() } * listeningSecondsPerFrame
        while (
            ring.size > tuning.recentRingUnits ||
                (ring.size > 1 && seconds > tuning.recentRingSeconds)
        ) {
            seconds -= ring.removeFirst().unit.frames * listeningSecondsPerFrame
        }
    }

    /** A new gen starts a new plan: every held unit belonged to the old one. */
    private fun dropOtherGens(currentGen: Long) {
        if (gen == currentGen) return
        gen = currentGen
        entries.removeAll { it.unit.gen != currentGen }
        ring.removeAll { it.unit.gen != currentGen }
        cursor = null
        lastStarted = null
    }
}
