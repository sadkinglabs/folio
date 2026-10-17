package dev.sadking.folio.reader.narration

import kotlin.math.max
import kotlin.math.sqrt

/**
 * What one (variant, threads) pair is known to do: synthesis seconds ≈ [a] + [b1] · chars / speed;
 * [alpha1] audio seconds per character at 1×; [cv] the spread of real against predicted; [n] how
 * many units it rests on (0 = an estimate nobody measured).
 */
internal data class ThroughputPrior(
    val a: Double,
    val b1: Double,
    val alpha1: Double,
    val cv: Double,
    val n: Int,
    val updatedAtMs: Long,
) {
    companion object {
        /** Host measurement of the shipped int8 model (RTF 1.5). Not a phone figure. */
        val INT8_DEFAULT = ThroughputPrior(0.40, 0.0765, 0.051, 0.25, 0, 0)
        /** Pixel estimate for fp32 (RTF 0.7); the host measured 0.43. */
        val FP32_DEFAULT = ThroughputPrior(0.15, 0.0357, 0.051, 0.25, 0, 0)
    }
}

/**
 * Learns how fast this phone makes speech, from units it actually synthesised. Fits `synth ≈ a + b1
 * · chars / speed` by exponentially weighted least squares (λ 0.9), with `a` in [0, 2] and `b1` >
 * 0, so the per-call overhead of short units is predicted as well as long ones. The prior counts as
 * 3 observations and fades as real ones arrive. Main-confined.
 *
 * A prior with n > 0 was measured in an earlier session or the benchmark; its cv is widened ×1.5
 * until this session has 5 observations of its own.
 */
internal class ThroughputModel(
    prior: ThroughputPrior,
    private val tuning: NarrationTuning = NarrationTuning.DEFAULT,
) {
    private val prior = prior
    private val priorCv = if (prior.n > 0) prior.cv * PRIOR_CV_WIDENING else prior.cv
    private var s0 = 0.0
    private var sx = 0.0
    private var sy = 0.0
    private var sxx = 0.0
    private var sxy = 0.0
    private var weightSum = 0.0
    private var errorSquare: Double? = null
    private var a = prior.a
    private var b1 = prior.b1
    private var alpha1 = prior.alpha1

    /** Units measured this session at full weight; the warm-up is not counted. */
    var observations: Int = 0
        private set

    val cv: Double
        get() {
            val e = errorSquare
            if (observations < CV_OBSERVATIONS || e == null) return priorCv
            return max(CV_FLOOR, sqrt(e))
        }

    /** One synthesised unit (never a cache hit). [weight] 0.5 for the warm-up observation. */
    fun observe(
        chars: Int,
        speed: Double,
        synthSeconds: Double,
        audioSeconds: Double,
        weight: Double = 1.0,
    ) {
        if (chars <= 0 || speed <= 0.0 || weight <= 0.0) return
        if (!synthSeconds.isFinite() || synthSeconds < 0.0) return
        if (!audioSeconds.isFinite() || audioSeconds <= 0.0) return
        val x = chars / speed
        val predicted = a + b1 * x
        if (predicted > 0.0) {
            val e = synthSeconds / predicted - 1.0
            val rate = (1.0 - LAMBDA) * weight
            errorSquare = errorSquare?.let { it + rate * (e * e - it) } ?: (e * e)
        }
        val decay = Math.pow(LAMBDA, weight)
        s0 = s0 * decay + weight
        sx = sx * decay + weight * x
        sy = sy * decay + weight * synthSeconds
        sxx = sxx * decay + weight * x * x
        sxy = sxy * decay + weight * x * synthSeconds
        val alphaRate = max((1.0 - LAMBDA) * weight, weight / (weightSum + weight + 1.0))
        alpha1 += alphaRate * (audioSeconds * speed / chars - alpha1)
        weightSum += weight
        // A priming request (the warm-up) moves the fit but is not a measured unit: alone it is
        // not evidence enough to call the phone too slow.
        if (weight >= 1.0) observations++
        refit()
    }

    /** Includes the thermal or normal inflation δ. */
    fun predictSynthSeconds(chars: Int, speed: Double, thermal: Boolean): Double =
        (a + b1 * chars / speed) * (1.0 + delta(thermal))

    fun predictAudioSeconds(chars: Int, speed: Double): Double = alpha1 * chars / speed

    /**
     * Σ predicted synth ÷ Σ predicted audio over [unitChars] (chars per unit), inflated by δ. Over
     * 1 means slower than listening. With no units, a typical 150-character unit stands in.
     */
    fun effectiveRatio(unitChars: List<Int>, speed: Double, thermal: Boolean): Double {
        val units = unitChars.filter { it > 0 }.ifEmpty { listOf(TYPICAL_UNIT_CHARS) }
        val synth = units.sumOf { predictSynthSeconds(it, speed, thermal) }
        val audio = units.sumOf { predictAudioSeconds(it, speed) }
        return synth / audio
    }

    fun toPrior(nowMs: Long): ThroughputPrior =
        ThroughputPrior(
            a = a,
            b1 = b1,
            alpha1 = alpha1,
            cv = if (observations < CV_OBSERVATIONS) prior.cv else cv,
            n = prior.n + observations,
            updatedAtMs = nowMs,
        )

    private fun delta(thermal: Boolean) =
        if (thermal) tuning.gateDeltaThermal else tuning.gateDeltaNormal

    private fun refit() {
        val pw = max(0.0, PRIOR_WEIGHT - weightSum) / 2
        var n = s0
        var x = sx
        var y = sy
        var xx = sxx
        var xy = sxy
        for (px in PRIOR_POINTS) {
            val py = prior.a + prior.b1 * px
            n += pw
            x += pw * px
            y += pw * py
            xx += pw * px * px
            xy += pw * px * py
        }
        if (n <= 0.0 || x <= 0.0) return
        val det = n * xx - x * x
        var fitA: Double
        var fitB: Double
        if (det <= 1e-9 * n * xx) {
            fitA = prior.a.coerceIn(0.0, MAX_A)
            fitB = (y - fitA * n) / x
        } else {
            fitB = (n * xy - x * y) / det
            fitA = (y - fitB * x) / n
            if (fitA < 0.0 || fitA > MAX_A) {
                fitA = fitA.coerceIn(0.0, MAX_A)
                fitB = (xy - fitA * x) / xx
            }
        }
        if (!fitA.isFinite() || !fitB.isFinite()) return
        a = fitA
        b1 = max(fitB, MIN_B1)
    }

    private companion object {
        const val LAMBDA = 0.9
        const val PRIOR_WEIGHT = 3.0
        const val PRIOR_CV_WIDENING = 1.5
        const val CV_OBSERVATIONS = 5
        const val CV_FLOOR = 0.05
        const val MAX_A = 2.0
        const val MIN_B1 = 1e-5
        const val TYPICAL_UNIT_CHARS = 150
        /** Characters at 1× where the prior is pinned: a short and a long unit. */
        val PRIOR_POINTS = doubleArrayOf(40.0, 250.0)
    }
}
