package dev.sadking.folio.reader.narration

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A unit not yet ready, in plan order. [inFlightElapsedSeconds] is set for the one being made. */
internal data class PendingUnit(val seq: Long, val chars: Int, val inFlightElapsedSeconds: Double?)

internal data class GateInput(
    /** Ready + cached-ahead + written-unheard listening seconds, contiguous from the head. */
    val reserveSeconds: Double,
    /** At least one whole unit is ready to write. */
    val unitReady: Boolean,
    /** Units not yet ready, plan order; the first may be in flight. */
    val pending: List<PendingUnit>,
    val speed: Double,
    val thermal: Boolean,
    /**
     * Seconds left of the 20 s budget, or null when the budget does not apply (chapter
     * preparation).
     */
    val budgetRemainingSeconds: Double?,
    /** True when the horizon ends at the end of the chapter (chapter-preparation mode). */
    val chapterHorizon: Boolean,
)

internal sealed interface GateDecision {
    data object Start : GateDecision

    data class Wait(val etaSeconds: Double) : GateDecision

    data class TooSlow(
        val effectiveRatio: Double,
        /** 20 / (r − 1): seconds of listening a 20 s reserve sustains. Infinite when r ≤ 1. */
        val sustainSeconds: Double,
        /** Seconds to prepare the rest of the horizon. */
        val prepareSeconds: Double,
    ) : GateDecision
}

/**
 * Decides when playback can start without stopping later (section 7 of
 * docs/READ_ALOUD_PIPELINE.md). For each pending unit j:
 * ```
 * ready_j  = R + Σ_{i=2..j} μ_i + z·cv·√(Σ_{i≤j} μ_i²) + G     when unit j will be ready
 * needed_j = B + Σ_{i<j} d_i                                   when playback reaches it
 * eta      = max(0, max_j (ready_j − needed_j))
 * ```
 *
 * Start when one whole unit is ready and eta is 0. There is no fixed wait: on a fast phone the gate
 * opens as soon as the first unit is ready.
 *
 * TooSlow needs evidence: at least one unit synthesised this session, or a prior resting on
 * priorCountForTooSlow units. An unmeasured prior alone never says TooSlow; its wait is capped at
 * the budget left. Chapter preparation (chapterHorizon) never says TooSlow.
 */
internal class StartGate(
    private val model: ThroughputModel,
    private val tuning: NarrationTuning = NarrationTuning.DEFAULT,
) {
    fun evaluate(input: GateInput, priorCount: Int): GateDecision {
        val units = horizon(input)
        val cv = model.cv
        var readySum = 0.0
        var squareSum = 0.0
        var needed = input.reserveSeconds
        var eta = 0.0
        var firstReady = 0.0
        for ((i, unit) in units.withIndex()) {
            val mu = model.predictSynthSeconds(unit.chars, input.speed, input.thermal)
            val remaining = unit.inFlightElapsedSeconds?.let { max(0.0, mu - it) } ?: mu
            readySum += remaining
            squareSum += mu * mu
            val ready = readySum + tuning.gateZ * cv * sqrt(squareSum) + tuning.gateGuardSeconds
            eta = max(eta, ready - needed)
            needed += model.predictAudioSeconds(unit.chars, input.speed)
            if (i == 0) firstReady = remaining
        }
        if (units.isEmpty()) {
            return if (input.unitReady || input.reserveSeconds > 0.0) GateDecision.Start
            else GateDecision.Wait(0.0)
        }
        val ratio = model.effectiveRatio(units.map { it.chars }, input.speed, input.thermal)
        val evidence = model.observations >= 1 || priorCount >= tuning.priorCountForTooSlow
        val budget = input.budgetRemainingSeconds
        if (!input.chapterHorizon) {
            val tooSlow =
                (evidence && ratio >= tuning.tooSlowRatio) ||
                    (budget != null && eta > budget && (evidence || budget <= 0.0))
            if (tooSlow) {
                val sustain =
                    if (ratio > 1.0) tuning.startBudgetSeconds / (ratio - 1.0)
                    else Double.POSITIVE_INFINITY
                return GateDecision.TooSlow(ratio, sustain, readySum)
            }
        }
        if (input.unitReady && eta <= 0.0) return GateDecision.Start
        var wait = if (input.unitReady) eta else max(eta, firstReady)
        if (!evidence && budget != null) wait = min(wait, max(0.0, budget))
        return GateDecision.Wait(wait)
    }

    private fun horizon(input: GateInput): List<PendingUnit> {
        val out = ArrayList<PendingUnit>()
        var seconds = 0.0
        for (unit in input.pending) {
            if (out.size >= tuning.horizonUnits || seconds >= tuning.horizonSeconds) break
            out += unit
            seconds += model.predictAudioSeconds(unit.chars, input.speed)
        }
        return out
    }
}
