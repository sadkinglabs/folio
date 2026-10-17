package dev.sadking.folio.reader.narration

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One unit of the on-device benchmark. Benchmark units are made at 1× speed. */
internal data class BenchmarkRow(
    val phase: String,
    val variant: String,
    val threads: Int,
    val provider: String,
    val fellBack: Boolean,
    val seq: Int,
    val chars: Int,
    val synthMs: Long,
    val audioMs: Long,
    val edgeLeadMs: Int,
    val edgeTrailMs: Int,
    val thermalStatus: Int,
    val thermalHeadroom: Float?,
    val tMs: Long,
) {
    val rtf: Double
        get() = synthMs.toDouble() / audioMs
}

/** One (variant, threads, provider) configuration, summarised. */
internal data class ConfigResult(
    val variant: String,
    val threads: Int,
    val provider: String,
    val fellBack: Boolean,
    val rtfMean: Double,
    val rtfP95: Double,
    val cv: Double,
    val firstUnitMs: Long,
    val overheadA: Double,
    val perCharB1: Double,
)

internal data class BenchmarkReport(
    val loadMs: Map<String, Long>,
    val configs: List<ConfigResult>,
    val best: ConfigResult?,
    /** Whole render vs separate units: boundary gap p50/p95 in ms, lead/trail ms. */
    val wholeGapP50Ms: Int?,
    val wholeGapP95Ms: Int?,
    val separateGapP50Ms: Int?,
    val separateGapP95Ms: Int?,
    val sustainedMinutes: Int?,
    val sustainedRtfFirst: Double?,
    val sustainedRtfLast: Double?,
    val thermalMax: Int?,
    /** Predicted start wait from the gate with the best config's prior and a 3 s first unit. */
    val predictedStartSeconds: Double?,
    val tooSlow: Boolean,
) {
    fun toJson(): String =
        NarrationJson.obj(
            "load_ms" to
                NarrationJson.Raw(
                    NarrationJson.obj(
                        *loadMs.entries
                            .sortedBy { it.key }
                            .map { it.key to it.value }
                            .toTypedArray()
                    )
                ),
            "configs" to NarrationJson.Raw(NarrationJson.array(configs.map { configJson(it) })),
            "best" to best?.let { NarrationJson.Raw(configJson(it)) },
            "whole_gap_p50_ms" to wholeGapP50Ms,
            "whole_gap_p95_ms" to wholeGapP95Ms,
            "separate_gap_p50_ms" to separateGapP50Ms,
            "separate_gap_p95_ms" to separateGapP95Ms,
            "sustained_minutes" to sustainedMinutes,
            "sustained_rtf_first" to sustainedRtfFirst,
            "sustained_rtf_last" to sustainedRtfLast,
            "thermal_max" to thermalMax,
            "predicted_start_s" to predictedStartSeconds,
            "too_slow" to tooSlow,
        )

    private fun configJson(c: ConfigResult): String =
        NarrationJson.obj(
            "variant" to c.variant,
            "threads" to c.threads,
            "provider" to c.provider,
            "fell_back" to c.fellBack,
            "rtf_mean" to c.rtfMean,
            "rtf_p95" to c.rtfP95,
            "cv" to c.cv,
            "first_unit_ms" to c.firstUnitMs,
            "overhead_a" to c.overheadA,
            "per_char_b1" to c.perCharB1,
        )
}

internal object BenchmarkStats {
    private const val MIN_GAP_SECONDS = 0.06
    private const val CV_FLOOR = 0.05
    private const val MAX_A = 2.0

    /**
     * Mean and p95 of per-unit RTF, the line fit `synth s ≈ a + b1 · chars` (benchmark units are at
     * 1×) and cv, the spread of real synthesis time around that line. Rows must share one
     * configuration; rows with no audio are left out.
     */
    fun summarize(rows: List<BenchmarkRow>): ConfigResult {
        val usable = rows.filter { it.audioMs > 0 }
        require(usable.isNotEmpty()) { "no rows with audio" }
        val head = usable.first()
        val rtfs = usable.map { it.rtf }
        val (a, b1) = fit(usable)
        return ConfigResult(
            variant = head.variant,
            threads = head.threads,
            provider = head.provider,
            fellBack = usable.any { it.fellBack },
            rtfMean = rtfs.average(),
            rtfP95 = NarrationMetrics.p95(rtfs) ?: rtfs.average(),
            cv = spread(usable, a, b1),
            firstUnitMs = usable.minBy { it.seq }.synthMs,
            overheadA = a,
            perCharB1 = b1,
        )
    }

    /** The prior the engine starts from for this configuration. */
    fun prior(rows: List<BenchmarkRow>, nowMs: Long): ThroughputPrior {
        val usable = rows.filter { it.audioMs > 0 && it.chars > 0 }
        require(usable.isNotEmpty()) { "no rows with audio" }
        val (a, b1) = fit(usable)
        val alpha1 = usable.sumOf { it.audioMs } / 1000.0 / usable.sumOf { it.chars }
        return ThroughputPrior(
            a = a,
            b1 = b1,
            alpha1 = alpha1,
            cv = max(CV_FLOOR, spread(usable, a, b1)),
            n = usable.size,
            updatedAtMs = nowMs,
        )
    }

    fun csvHeader(): String =
        "phase,variant,threads,provider,fell_back,seq,chars,synth_ms,audio_ms,rtf,edge_lead_ms," +
            "edge_trail_ms,thermal_status,thermal_headroom,t_ms"

    fun csvRow(r: BenchmarkRow): String =
        listOf(
                r.phase,
                r.variant,
                r.threads,
                r.provider,
                if (r.fellBack) 1 else 0,
                r.seq,
                r.chars,
                r.synthMs,
                r.audioMs,
                NarrationJson.number(if (r.audioMs > 0) r.rtf else null, 4),
                r.edgeLeadMs,
                r.edgeTrailMs,
                r.thermalStatus,
                NarrationJson.number(r.thermalHeadroom?.toDouble(), 3),
                r.tMs,
            )
            .joinToString(",")

    /**
     * Silence gaps for the diagnostic render, in milliseconds: every run of |x| ≤ threshold lasting
     * at least 60 ms inside [segments] laid end to end, leaving out the silence before the first
     * sound and after the last. Pass the whole render as one segment, or the separate units in
     * order; the two lists compare sentence pauses with and without unit boundaries.
     */
    fun gaps(segments: List<ShortArray>, sampleRate: Int, threshold: Double = 0.01): List<Int> {
        val limit = (threshold * Short.MAX_VALUE).roundToInt()
        val minRun = (MIN_GAP_SECONDS * sampleRate).roundToInt().coerceAtLeast(1)
        val out = ArrayList<Int>()
        var heardSound = false
        var run = 0
        for (segment in segments) {
            for (x in segment) {
                if (abs(x.toInt()) <= limit) {
                    run++
                } else {
                    if (heardSound && run >= minRun) out += (run * 1000.0 / sampleRate).roundToInt()
                    heardSound = true
                    run = 0
                }
            }
        }
        return out
    }

    /** Least squares of synthesis seconds on characters, a clamped to [0, 2], b1 > 0. */
    private fun fit(rows: List<BenchmarkRow>): Pair<Double, Double> {
        val n = rows.size.toDouble()
        val xs = rows.map { it.chars.toDouble() }
        val ys = rows.map { it.synthMs / 1000.0 }
        val sx = xs.sum()
        val sy = ys.sum()
        val sxx = xs.sumOf { it * it }
        val sxy = xs.indices.sumOf { xs[it] * ys[it] }
        val det = n * sxx - sx * sx
        if (sx <= 0.0) return 0.0 to 1e-5
        var a: Double
        var b: Double
        if (det <= 1e-9 * n * sxx) {
            a = 0.0
            b = sy / sx
        } else {
            b = (n * sxy - sx * sy) / det
            a = (sy - b * sx) / n
            if (a < 0.0 || a > MAX_A) {
                a = a.coerceIn(0.0, MAX_A)
                b = (sxy - a * sx) / sxx
            }
        }
        return a to max(b, 1e-5)
    }

    /** Standard deviation of real ÷ predicted − 1. */
    private fun spread(rows: List<BenchmarkRow>, a: Double, b1: Double): Double {
        val errors = rows.mapNotNull { r ->
            val predicted = a + b1 * r.chars
            if (predicted > 0.0) r.synthMs / 1000.0 / predicted - 1.0 else null
        }
        if (errors.size < 2) return 0.0
        val mean = errors.average()
        return sqrt(errors.sumOf { (it - mean) * (it - mean) } / errors.size)
    }
}
