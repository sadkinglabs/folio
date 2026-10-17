package dev.sadking.folio.reader.narration

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** A unit's speech after edge trimming, with how many frames each edge lost (for metrics). */
internal data class EdgeTrim(
    val pcm: ShortArray,
    val leadTrimmedFrames: Int,
    val trailTrimmedFrames: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is EdgeTrim &&
            pcm.contentEquals(other.pcm) &&
            leadTrimmedFrames == other.leadTrimmedFrames &&
            trailTrimmedFrames == other.trailTrimmedFrames

    override fun hashCode(): Int =
        (pcm.contentHashCode() * 31 + leadTrimmedFrames) * 31 + trailTrimmedFrames
}

/**
 * Silence at the edges of a unit, and the pause inserted after it.
 *
 * The pause at a unit boundary is: what is kept of this unit's trailing silence (at most
 * edgeKeepSeconds) + [gapFrames] of zeros + what is kept of the next unit's leading silence (at
 * most edgeKeepSeconds). [gapFrames] therefore subtracts the two kept edges, so the whole boundary
 * pause comes to the target for its kind ÷ speed (section 9 of docs/READ_ALOUD_PIPELINE.md).
 */
internal object EdgeSilence {
    /**
     * Trims leading/trailing runs with |x| ≤ threshold (in Short units: threshold × 32767) to ≤
     * keepFrames each. Never trims inside speech. A unit that is silent throughout keeps
     * keepFrames.
     */
    fun trim(pcm: ShortArray, threshold: Double, keepFrames: Int): EdgeTrim {
        val limit = limit(threshold)
        val keep = keepFrames.coerceAtLeast(0)
        val n = pcm.size
        val lead = leadingRun(pcm, limit)
        if (lead == n) {
            val kept = minOf(n, keep)
            return EdgeTrim(pcm.copyOfRange(0, kept), n - kept, 0)
        }
        val trail = trailingRun(pcm, limit)
        val cutLead = max(0, lead - keep)
        val cutTrail = max(0, trail - keep)
        if (cutLead == 0 && cutTrail == 0) return EdgeTrim(pcm, 0, 0)
        return EdgeTrim(pcm.copyOfRange(cutLead, n - cutTrail), cutLead, cutTrail)
    }

    /** Frames of zeros to append for [gap] at [speed]. */
    fun gapFrames(gap: GapKind, speed: Double, sampleRate: Int, tuning: NarrationTuning): Int {
        val target =
            when (gap) {
                GapKind.FRAGMENT -> tuning.gapFragmentSeconds
                GapKind.CLAUSE -> tuning.gapClauseSeconds
                GapKind.SENTENCE -> tuning.gapSentenceSeconds
                GapKind.PARAGRAPH -> tuning.gapParagraphSeconds
            }
        val seconds = target / speed.coerceAtLeast(0.1) - 2 * tuning.edgeKeepSeconds
        return if (seconds <= 0.0) 0 else (seconds * sampleRate).roundToInt()
    }

    /**
     * Leading and trailing silence in frames, for metrics (threshold 0.01 like the host analysis).
     * A silent unit reports its whole length for both.
     */
    fun measureEdges(pcm: ShortArray, threshold: Double = 0.01): Pair<Int, Int> {
        val limit = limit(threshold)
        val lead = leadingRun(pcm, limit)
        if (lead == pcm.size) return lead to lead
        return lead to trailingRun(pcm, limit)
    }

    private fun limit(threshold: Double): Int = (threshold * Short.MAX_VALUE).roundToInt()

    private fun leadingRun(pcm: ShortArray, limit: Int): Int {
        var i = 0
        while (i < pcm.size && abs(pcm[i].toInt()) <= limit) i++
        return i
    }

    private fun trailingRun(pcm: ShortArray, limit: Int): Int {
        var i = pcm.size
        while (i > 0 && abs(pcm[i - 1].toInt()) <= limit) i--
        return pcm.size - i
    }
}
