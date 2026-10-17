package dev.sadking.folio.reader.narration

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputModelTest {
    private val chars = listOf(30, 60, 90, 120, 160, 200, 240, 280)

    /** Feeds [count] exact observations of synth = a + b1 · chars / speed. */
    private fun ThroughputModel.feed(
        a: Double,
        b1: Double,
        speed: Double = 1.0,
        count: Int = 40,
        alpha1: Double = 0.05,
    ) {
        repeat(count) {
            val c = chars[it % chars.size]
            observe(c, speed, a + b1 * c / speed, alpha1 * c / speed)
        }
    }

    private fun near(expected: Double, actual: Double, fraction: Double) =
        assertTrue(
            "expected $expected ± ${fraction * 100}%, was $actual",
            abs(actual - expected) <= abs(expected) * fraction,
        )

    @Test
    fun recoversOverheadAndPerCharacterCost() {
        val model = ThroughputModel(ThroughputPrior.INT8_DEFAULT)
        model.feed(a = 0.3, b1 = 0.06)
        val fitted = model.toPrior(0)
        near(0.3, fitted.a, 0.05)
        near(0.06, fitted.b1, 0.05)
        near(0.05, fitted.alpha1, 0.05)
        assertEquals(40, model.observations)
    }

    @Test
    fun observationsAtOneAndAHalfPredictNormalSpeed() {
        val model = ThroughputModel(ThroughputPrior.INT8_DEFAULT)
        model.feed(a = 0.3, b1 = 0.06, speed = 1.5)
        near((0.3 + 0.06 * 150) * 1.05, model.predictSynthSeconds(150, 1.0, thermal = false), 0.05)
        near(0.05 * 150, model.predictAudioSeconds(150, 1.0), 0.05)
        near(0.05 * 150 / 1.5, model.predictAudioSeconds(150, 1.5), 0.05)
    }

    @Test
    fun withNoObservationsThePriorIsUsedAsIs() {
        val model = ThroughputModel(ThroughputPrior.INT8_DEFAULT)
        assertEquals((0.40 + 0.0765 * 100) * 1.05, model.predictSynthSeconds(100, 1.0, false), 1e-9)
        assertEquals(0.051 * 100, model.predictAudioSeconds(100, 1.0), 1e-9)
        assertEquals(0.25, model.cv, 1e-12)
        assertEquals(0, model.observations)
    }

    @Test
    fun thePriorFadesOverThreeObservations() {
        val prior = ThroughputPrior.INT8_DEFAULT
        val model = ThroughputModel(prior)
        val truth = { c: Int -> 0.1 + 0.03 * c }
        val priorAt150 = model.predictSynthSeconds(150, 1.0, false)
        model.observe(150, 1.0, truth(150), 7.5)
        val afterOne = model.predictSynthSeconds(150, 1.0, false)
        assertTrue("moves toward the observation", afterOne < priorAt150)
        assertTrue("but not all the way", afterOne > truth(150) * 1.05 + 0.01)
        model.observe(60, 1.0, truth(60), 3.0)
        model.observe(250, 1.0, truth(250), 12.5)
        near(truth(150) * 1.05, model.predictSynthSeconds(150, 1.0, false), 0.02)
    }

    @Test
    fun cvIsThePriorsUntilFiveObservationsThenMeasuredWithAFloor() {
        val model = ThroughputModel(ThroughputPrior.INT8_DEFAULT)
        model.feed(a = 0.3, b1 = 0.06, count = 4)
        assertEquals(0.25, model.cv, 1e-12)
        model.feed(a = 0.3, b1 = 0.06, count = 80)
        assertEquals(0.05, model.cv, 1e-12)

        val noisy = ThroughputModel(ThroughputPrior.INT8_DEFAULT)
        repeat(200) {
            val c = chars[it % chars.size]
            val factor = if (it % 2 == 0) 1.2 else 0.8
            noisy.observe(c, 1.0, (0.3 + 0.06 * c) * factor, 0.05 * c)
        }
        assertTrue("cv ${noisy.cv}", noisy.cv in 0.12..0.3)
    }

    @Test
    fun aMeasuredPriorStartsWithAWiderSpread() {
        val measured = ThroughputPrior(0.2, 0.03, 0.05, 0.2, n = 30, updatedAtMs = 1)
        assertEquals(0.3, ThroughputModel(measured).cv, 1e-12)
    }

    @Test
    fun thermalInflationIsLargerThanNormal() {
        val model = ThroughputModel(ThroughputPrior.FP32_DEFAULT)
        val normal = model.predictSynthSeconds(150, 1.0, thermal = false)
        val warm = model.predictSynthSeconds(150, 1.0, thermal = true)
        assertEquals(1.15 / 1.05, warm / normal, 1e-9)
    }

    @Test
    fun theWarmUpCountsHalf() {
        val full = ThroughputModel(ThroughputPrior.INT8_DEFAULT)
        val half = ThroughputModel(ThroughputPrior.INT8_DEFAULT)
        full.observe(10, 1.0, 0.3, 0.5, weight = 1.0)
        half.observe(10, 1.0, 0.3, 0.5, weight = 0.5)
        val prior =
            ThroughputModel(ThroughputPrior.INT8_DEFAULT).predictSynthSeconds(10, 1.0, false)
        val movedFull = prior - full.predictSynthSeconds(10, 1.0, false)
        val movedHalf = prior - half.predictSynthSeconds(10, 1.0, false)
        assertTrue("half $movedHalf, full $movedFull", movedHalf in 0.0..movedFull * 0.99)
    }

    @Test
    fun theWarmUpIsNotAMeasuredUnit() {
        val model = ThroughputModel(ThroughputPrior.INT8_DEFAULT)
        model.observe(6, 1.0, 0.9, 0.4, weight = 0.5)
        assertEquals(0, model.observations)
        assertEquals("the saved count does not grow", 0, model.toPrior(1).n)
        model.observe(80, 1.0, 6.5, 4.0)
        assertEquals(1, model.observations)
    }

    @Test
    fun effectiveRatioIsSynthOverAudio() {
        val model = ThroughputModel(ThroughputPrior.INT8_DEFAULT)
        val expected = (0.40 + 0.0765 * 150) * 1.05 / (0.051 * 150)
        assertEquals(expected, model.effectiveRatio(listOf(150), 1.0, false), 1e-9)
        assertEquals(expected, model.effectiveRatio(emptyList(), 1.0, false), 1e-9)
        assertTrue(
            model.effectiveRatio(listOf(20), 1.0, false) >
                model.effectiveRatio(listOf(300), 1.0, false)
        )
    }

    @Test
    fun toPriorAddsObservationsToTheCount() {
        val model = ThroughputModel(ThroughputPrior.INT8_DEFAULT.copy(n = 5))
        model.feed(0.3, 0.06, count = 3)
        val saved = model.toPrior(1234)
        assertEquals(8, saved.n)
        assertEquals(1234L, saved.updatedAtMs)
        assertEquals("cv stays the prior's until 5 observations", 0.25, saved.cv, 1e-12)
    }

    @Test
    fun nonsenseObservationsAreIgnored() {
        val model = ThroughputModel(ThroughputPrior.INT8_DEFAULT)
        model.observe(0, 1.0, 1.0, 1.0)
        model.observe(100, 1.0, Double.NaN, 5.0)
        model.observe(100, 1.0, 1.0, 0.0)
        model.observe(100, 0.0, 1.0, 5.0)
        assertEquals(0, model.observations)
    }
}
