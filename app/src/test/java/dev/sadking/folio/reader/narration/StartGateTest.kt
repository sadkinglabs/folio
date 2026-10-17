package dev.sadking.folio.reader.narration

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartGateTest {
    private val alpha = 0.051
    /** About 3 s of audio at 1×. */
    private val firstChars = 59
    /** About 4 s of audio at 1×. */
    private val unitChars = 78

    /** A prior with no overhead and real-time ratio [r]. */
    private fun prior(r: Double, cv: Double, n: Int = 0) =
        ThroughputPrior(0.0, r * alpha, alpha, cv, n, 0)

    private fun pending(count: Int, firstSeq: Long = 1, elapsed: Double? = null) =
        List(count) { PendingUnit(firstSeq + it, unitChars, if (it == 0) elapsed else null) }

    /**
     * Synthesis runs unit after unit at the true speed (the prior without δ); the gate is asked
     * every 50 ms. Returns the first decision that is not Wait, with its time from the tap, and the
     * Wait seen when the first unit became ready.
     */
    private fun simulate(
        r: Double,
        cv: Double,
        units: Int = 200,
    ): Triple<Double, GateDecision, GateDecision?> {
        val model = ThroughputModel(prior(r, cv))
        val gate = StartGate(model)
        val chars = listOf(firstChars) + List(units - 1) { unitChars }
        val synth = chars.map { r * alpha * it }
        var t = 0.0
        var firstReadyDecision: GateDecision? = null
        while (t < 40.0) {
            var done = 0
            var finished = 0.0
            while (done < chars.size && finished + synth[done] <= t + 1e-9) finished +=
                synth[done++]
            val reserve = chars.take(done).sumOf { alpha * it }
            val rest =
                chars.drop(done).mapIndexed { i, c ->
                    PendingUnit(done + i.toLong(), c, if (i == 0) t - finished else null)
                }
            val decision =
                gate.evaluate(GateInput(reserve, done > 0, rest, 1.0, false, 20.0 - t, false), 0)
            if (done > 0 && firstReadyDecision == null) firstReadyDecision = decision
            if (decision !is GateDecision.Wait) return Triple(t, decision, firstReadyDecision)
            t += 0.05
        }
        error("never decided")
    }

    @Test
    fun aFastPhoneStartsRightAfterTheFirstUnit() {
        val (t, decision, _) = simulate(r = 0.4, cv = 0.07)
        assertEquals(GateDecision.Start, decision)
        val firstUnit = 0.4 * alpha * firstChars
        assertTrue(
            "started at $t, first unit ready at $firstUnit",
            t >= firstUnit && t <= firstUnit + 0.1,
        )
    }

    @Test
    fun ratioPointSevenStartsByAboutFourSeconds() {
        val (t, decision, _) = simulate(r = 0.7, cv = 0.25)
        assertEquals(GateDecision.Start, decision)
        assertTrue("started at $t", t in 3.0..4.5)
    }

    @Test
    fun ratioPointNineWaitsSomeSecondsThenStartsWithinTheBudget() {
        val (t, decision, atFirst) = simulate(r = 0.9, cv = 0.25)
        val wait = atFirst as GateDecision.Wait
        assertTrue("eta ${wait.etaSeconds}", wait.etaSeconds in 4.0..10.0)
        assertEquals(GateDecision.Start, decision)
        assertTrue("started at $t", t < 20.0)
    }

    @Test
    fun oneSlowObservationMeansTooSlowBeforeTheBudgetRunsOut() {
        val model = ThroughputModel(ThroughputPrior.INT8_DEFAULT)
        val audio = alpha * unitChars
        model.observe(unitChars, 1.0, 1.2 * audio, audio)
        val decision =
            StartGate(model)
                .evaluate(GateInput(audio, true, pending(50), 1.0, false, 17.0, false), 0)
        val slow = decision as GateDecision.TooSlow
        assertTrue(slow.effectiveRatio >= 0.97)
        assertEquals(20.0 / (slow.effectiveRatio - 1.0), slow.sustainSeconds, 1e-9)
        assertTrue(slow.prepareSeconds > 0.0)
    }

    @Test
    fun theDefaultPriorAloneNeverSaysTooSlow() {
        val gate = StartGate(ThroughputModel(ThroughputPrior.INT8_DEFAULT))
        val decision =
            gate.evaluate(GateInput(0.0, false, pending(100), 1.0, false, 19.0, false), 0)
        val wait = decision as GateDecision.Wait
        assertTrue("capped at the budget left: ${wait.etaSeconds}", wait.etaSeconds <= 19.0)
    }

    @Test
    fun theWarmUpAloneIsNotEvidence() {
        // "Ready." made by the warm-up: short, so its per-call overhead looks slow.
        val model = ThroughputModel(ThroughputPrior.INT8_DEFAULT)
        model.observe(6, 1.0, 0.9, 0.4, weight = 0.5)
        val gate = StartGate(model)
        val decision =
            gate.evaluate(GateInput(0.0, false, pending(100), 1.0, false, 19.0, false), 0)
        assertTrue(
            "a guess plus a warm-up must not say too slow: $decision",
            decision is GateDecision.Wait,
        )
        // One real unit at the same slow pace is evidence.
        model.observe(unitChars, 1.0, 1.5 * alpha * unitChars + 0.4, alpha * unitChars)
        assertTrue(
            gate.evaluate(GateInput(0.0, false, pending(100), 1.0, false, 19.0, false), 0)
                is GateDecision.TooSlow
        )
    }

    @Test
    fun aPriorRestingOnTwentyUnitsCanSayTooSlow() {
        val gate = StartGate(ThroughputModel(ThroughputPrior.INT8_DEFAULT.copy(n = 25)))
        val input = GateInput(0.0, false, pending(100), 1.0, false, 19.0, false)
        assertTrue(gate.evaluate(input, priorCount = 25) is GateDecision.TooSlow)
        assertTrue(gate.evaluate(input, priorCount = 19) is GateDecision.Wait)
    }

    @Test
    fun exceedingTheBudgetIsTooSlow() {
        val gate = StartGate(ThroughputModel(prior(0.9, 0.25, n = 25)))
        val decision =
            gate.evaluate(GateInput(0.0, false, pending(100), 1.0, false, 1.0, false), 25)
        val slow = decision as GateDecision.TooSlow
        assertTrue("ratio ${slow.effectiveRatio} is under 0.97", slow.effectiveRatio < 0.97)
        assertEquals(Double.POSITIVE_INFINITY, slow.sustainSeconds, 0.0)

        val unmeasured = StartGate(ThroughputModel(ThroughputPrior.INT8_DEFAULT))
        val spent =
            unmeasured.evaluate(GateInput(0.0, false, pending(10), 1.0, false, 0.0, false), 0)
        assertTrue("the budget is spent", spent is GateDecision.TooSlow)
    }

    @Test
    fun chapterPreparationWaitsInsteadOfSayingTooSlow() {
        val model = ThroughputModel(prior(1.3, 0.1))
        model.observe(unitChars, 1.0, 1.3 * alpha * unitChars, alpha * unitChars)
        val units = pending(30)
        val decision =
            StartGate(model).evaluate(GateInput(0.0, true, units, 1.0, false, null, true), 0)
        val wait = decision as GateDecision.Wait
        val prepare = units.sumOf { model.predictSynthSeconds(it.chars, 1.0, false) }
        assertTrue(
            "eta ${wait.etaSeconds} of $prepare",
            wait.etaSeconds > 0.0 && wait.etaSeconds < prepare + 1.0,
        )
    }

    @Test
    fun etaIsNeverNegativeAndNeverGrowsWithMoreReserve() {
        val gate = StartGate(ThroughputModel(prior(0.9, 0.25)))
        var last = Double.MAX_VALUE
        for (reserve in 0..120) {
            val decision =
                gate.evaluate(
                    GateInput(reserve.toDouble(), true, pending(100), 1.0, false, 20.0, false),
                    0,
                )
            val eta = (decision as? GateDecision.Wait)?.etaSeconds ?: 0.0
            assertTrue(eta >= 0.0)
            assertTrue("reserve $reserve: $eta after $last", eta <= last + 1e-9)
            last = eta
        }
        assertEquals(0.0, last, 0.0)
        val notReady =
            gate.evaluate(GateInput(500.0, false, pending(5), 1.0, false, 20.0, false), 0)
        assertTrue((notReady as GateDecision.Wait).etaSeconds > 0.0)
    }

    @Test
    fun theInFlightUnitCountsOnlyWhatIsLeft() {
        val gate = StartGate(ThroughputModel(prior(0.7, 0.25)))
        val fresh =
            gate.evaluate(GateInput(3.0, true, pending(50), 1.0, false, 20.0, false), 0)
                as GateDecision.Wait
        val later =
            gate.evaluate(
                GateInput(3.0, true, pending(50, elapsed = 1.0), 1.0, false, 20.0, false),
                0,
            ) as GateDecision.Wait
        assertEquals(fresh.etaSeconds - 1.0, later.etaSeconds, 1e-9)
    }

    @Test
    fun nothingPendingStartsWhenSomethingIsReady() {
        val gate = StartGate(ThroughputModel(ThroughputPrior.INT8_DEFAULT))
        assertEquals(
            GateDecision.Start,
            gate.evaluate(GateInput(2.0, true, emptyList(), 1.0, false, 20.0, false), 0),
        )
        assertEquals(
            GateDecision.Wait(0.0),
            gate.evaluate(GateInput(0.0, false, emptyList(), 1.0, false, 20.0, false), 0),
        )
    }

    @Test
    fun theHorizonIsBounded() {
        val gate = StartGate(ThroughputModel(prior(1.2, 0.1)), NarrationTuning(horizonUnits = 10))
        val ten =
            gate.evaluate(GateInput(0.0, true, pending(10), 1.0, false, null, true), 0)
                as GateDecision.Wait
        val many =
            gate.evaluate(GateInput(0.0, true, pending(1000), 1.0, false, null, true), 0)
                as GateDecision.Wait
        assertEquals(ten.etaSeconds, many.etaSeconds, 1e-9)
        assertTrue(abs(ten.etaSeconds) > 0.0)
    }
}
