package dev.sadking.folio.reader.narration

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where each utterance of a unit starts and ends in its audio, so highlighting follows what is
 * heard. A one-utterance unit (the common case) is exact. For a merged unit the boundaries are
 * estimates: each part's share of the speech characters, snapped to the quietest 20 ms inside ±0.4
 * s. Expect a fraction of a second of highlight error inside merged fragments.
 */
internal object UtteranceSpans {
    private const val WINDOW_SECONDS = 0.02
    private const val SEARCH_SECONDS = 0.4

    /**
     * Frame boundaries for each part of [unit] over [pcm] (the speech before the gap). Returns
     * parts.size ranges, in order, covering [0, pcm.size) with no gap or overlap. A range is empty
     * only when there are fewer frames than parts.
     */
    fun split(unit: SynthesisUnit, pcm: ShortArray, sampleRate: Int): List<IntRange> {
        val n = pcm.size
        val parts = unit.parts
        if (parts.size == 1) return listOf(0 until n)
        val weights = parts.map { part ->
            val slice = part.utterance.text.substring(part.charStart, part.charEnd)
            SpeechText.forSynthesis(slice).length.coerceAtLeast(1).toDouble()
        }
        val total = weights.sum()
        val prefix = LongArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + abs(pcm[i].toInt())
        val window = (WINDOW_SECONDS * sampleRate).roundToInt().coerceAtLeast(1)
        val search = (SEARCH_SECONDS * sampleRate).roundToInt()

        val bounds = IntArray(parts.size + 1)
        bounds[parts.size] = n
        var share = 0.0
        for (k in 1 until parts.size) {
            share += weights[k - 1]
            val estimate = (n * share / total).roundToInt()
            // Leave at least one frame for each part still to come, when there are frames enough.
            val lo = (bounds[k - 1] + 1).coerceAtMost(n)
            val hi = (n - (parts.size - k)).coerceAtLeast(lo)
            bounds[k] =
                quietest(prefix, estimate - search, estimate + search, window, lo, hi)
                    ?: estimate.coerceIn(lo, hi)
        }
        return List(parts.size) { bounds[it] until bounds[it + 1] }
    }

    /**
     * Centre of the quietest 20 ms window whose centre lies in [from, to] ∩ [lo, hi]. When several
     * windows are equally quiet (a pause longer than 20 ms), the middle of that quiet stretch, and
     * of the stretches the one nearest the estimate.
     */
    private fun quietest(
        prefix: LongArray,
        from: Int,
        to: Int,
        window: Int,
        lo: Int,
        hi: Int,
    ): Int? {
        val n = prefix.size - 1
        val half = window / 2
        val first = maxOf(from, lo, half)
        val last = minOf(to, hi, n - (window - half))
        if (first > last) return null
        fun energy(centre: Int) = prefix[centre - half + window] - prefix[centre - half]
        var least = Long.MAX_VALUE
        for (centre in first..last) least = minOf(least, energy(centre))
        val estimate = (from + to) / 2
        var best = -1
        var centre = first
        while (centre <= last) {
            if (energy(centre) != least) {
                centre++
                continue
            }
            val runStart = centre
            while (centre + 1 <= last && energy(centre + 1) == least) centre++
            val middle = (runStart + centre) / 2
            if (best < 0 || abs(middle - estimate) < abs(best - estimate)) best = middle
            centre++
        }
        return best
    }
}
