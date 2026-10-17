package dev.sadking.folio.reader.narration

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays synthesis times measured on the host (x86, sherpa-onnx 1.13.8, one row per sentence of
 * the benchmark passage) through the real [StartGate], [ThroughputModel] and [WaterMarks]:
 * synthesis one unit at a time, playback consuming the audio in real time, the gate asked every 10
 * ms while waiting. It shows what the pipeline does with real Kokoro timings; it says nothing about
 * the phone, whose speed is unknown until the on-device benchmark runs.
 */
class HostReplayTest {
    private class Chunk(val chars: Int, val synth: Double, val audio: Double)

    private class Replay(
        val startSeconds: Double?,
        val tooSlowSeconds: Double?,
        val stalls: Int,
        val minReserve: Double,
        val maxReserve: Double,
        val listened: Double,
    )

    private fun chunks(variant: String): List<Chunk> =
        javaClass
            .getResourceAsStream("/narration/host_sentence_chunks.csv")!!
            .bufferedReader()
            .readLines()
            .filter { !it.startsWith("#") && !it.startsWith("variant") && it.isNotBlank() }
            .map { it.split(',') }
            .filter { it[0] == variant }
            .map { Chunk(it[1].toInt(), it[2].toDouble() / 1000, it[3].toDouble() / 1000) }

    /** [repeat] passes of the passage back to back, as a longer listen. */
    private fun replay(
        variant: String,
        prior: ThroughputPrior,
        repeat: Int = 1,
        tuning: NarrationTuning = NarrationTuning.DEFAULT,
    ): Replay {
        val units = List(repeat) { chunks(variant) }.flatten()
        val model = ThroughputModel(prior, tuning)
        val gate = StartGate(model, tuning)
        val marks = WaterMarks(tuning)
        val step = 0.01
        var t = 0.0
        var made = 0 // units finished
        var inFlightSince: Double? = 0.0 // unit [made] started at
        var available = 0.0 // seconds of audio made, contiguous
        var head = 0.0 // seconds of audio heard
        var playing = false
        var waitSince = 0.0
        var start: Double? = null
        var stalls = 0
        var minReserve = Double.MAX_VALUE
        var maxReserve = 0.0
        val total = units.sumOf { it.audio }
        while (head < total - 1e-6) {
            if (t > 4 * total + 60) error("replay did not finish")
            // Synthesis.
            val since = inFlightSince
            if (since != null && made < units.size && t - since >= units[made].synth - 1e-9) {
                val unit = units[made]
                model.observe(unit.chars, 1.0, unit.synth, unit.audio)
                available += unit.audio
                made++
                inFlightSince = null
            }
            val reserve = available - head
            if (
                inFlightSince == null &&
                    made < units.size &&
                    (!playing || marks.shouldSynthesize(reserve, strained = false))
            )
                inFlightSince = t
            // The gate, while waiting.
            if (!playing) {
                val pending =
                    units.drop(made).mapIndexed { i, unit ->
                        PendingUnit(
                            (made + i).toLong(),
                            unit.chars,
                            if (i == 0) inFlightSince?.let { t - it } else null,
                        )
                    }
                val decision =
                    gate.evaluate(
                        GateInput(
                            reserveSeconds = reserve,
                            unitReady = reserve > 0.25 || made == units.size,
                            pending = pending,
                            speed = 1.0,
                            thermal = false,
                            budgetRemainingSeconds = tuning.startBudgetSeconds - (t - waitSince),
                            chapterHorizon = false,
                        ),
                        priorCount = prior.n,
                    )
                when (decision) {
                    GateDecision.Start -> {
                        playing = true
                        if (start == null) start = t
                    }
                    is GateDecision.TooSlow ->
                        return Replay(start, t, stalls, minReserve, maxReserve, head)
                    is GateDecision.Wait -> Unit
                }
            }
            // Playback.
            if (playing) {
                minReserve = minOf(minReserve, reserve)
                maxReserve = maxOf(maxReserve, reserve)
                if (reserve < step - 1e-9 && made < units.size) {
                    stalls++
                    playing = false
                    waitSince = t
                } else head = minOf(available, head + step)
            }
            t += step
        }
        return Replay(start, null, stalls, minReserve, maxReserve, head)
    }

    /**
     * The earliest start that, in hindsight, would never have stalled with these exact timings:
     * every unit made before playback reaches it. No gate can know this in advance.
     */
    private fun hindsightStart(variant: String): Double {
        var made = 0.0
        var before = 0.0
        var start = 0.0
        for (unit in chunks(variant)) {
            made += unit.synth
            start = maxOf(start, made - before)
            before += unit.audio
        }
        return start
    }

    private fun report(name: String, r: Replay) =
        println(
            String.format(
                Locale.ROOT,
                "host replay %s: start %s s (hindsight %.2f s), tooSlow %s s, stalls %d, " +
                    "reserve %.1f..%.1f s, listened %.0f s",
                name,
                r.startSeconds?.let { String.format(Locale.ROOT, "%.2f", it) },
                hindsightStart(name.substringBefore(' ')),
                r.tooSlowSeconds?.let { String.format(Locale.ROOT, "%.2f", it) },
                r.stalls,
                if (r.minReserve == Double.MAX_VALUE) 0.0 else r.minReserve,
                r.maxReserve,
                r.listened,
            )
        )

    @Test
    fun hostFp32StartsAfterTheFirstSentenceAndNeverStalls() {
        val chunks = chunks("fp32")
        assertEquals(57, chunks.size)
        val r = replay("fp32", ThroughputPrior.FP32_DEFAULT, repeat = 3)
        report("fp32 x3", r)
        val start = r.startSeconds!!
        assertTrue("starts once the first sentence is made, at $start s", start >= chunks[0].synth)
        assertTrue("and well inside the budget: $start s", start < 8.0)
        assertEquals(null, r.tooSlowSeconds)
        assertEquals(0, r.stalls)
        assertTrue("the reserve grows to the high water mark: ${r.maxReserve}", r.maxReserve > 55.0)
    }

    @Test
    fun hostFp32WithItsMeasurementSavedStartsSooner() {
        // A later session: the speed test or an earlier listen saved the fit of these timings.
        val measured = ThroughputPrior(0.18, 0.0203, 0.0524, 0.186, 57, 0)
        val first = replay("fp32", ThroughputPrior.FP32_DEFAULT)
        val later = replay("fp32", measured, repeat = 3)
        report("fp32 measured prior x3", later)
        assertTrue(
            "${later.startSeconds} < ${first.startSeconds}",
            later.startSeconds!! < first.startSeconds!!,
        )
        assertEquals(0, later.stalls)
    }

    @Test
    fun hostInt8IsCalledTooSlowAfterItsFirstSentence() {
        val chunks = chunks("int8")
        val r = replay("int8", ThroughputPrior.INT8_DEFAULT)
        report("int8", r)
        val at = r.tooSlowSeconds!!
        assertEquals("never starts a pipeline that would stall", null, r.startSeconds)
        assertTrue("decided on a measured unit: $at s", at >= chunks[0].synth)
        assertTrue("and within the 20 s budget: $at s", at < 20.0)
    }

    @Test
    fun hostInt8WithAMeasuredPriorIsCalledTooSlowAtOnce() {
        // What a second session sees once the benchmark or a first session saved the measurement.
        val measured = ThroughputPrior(0.4, 0.072, 0.051, 0.2, 57, 0)
        val r = replay("int8", measured)
        report("int8 measured prior", r)
        assertTrue("at once: ${r.tooSlowSeconds}", r.tooSlowSeconds!! < 0.1)
    }
}
