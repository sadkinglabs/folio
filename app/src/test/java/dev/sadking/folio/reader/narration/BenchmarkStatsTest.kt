package dev.sadking.folio.reader.narration

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkStatsTest {
    private val chars = listOf(40, 80, 120, 160, 200, 240, 280, 60, 100, 140)

    private fun rows(
        overheadMs: Long = 300,
        perCharMs: Long = 60,
        jitter: (Int) -> Double = { 1.0 },
    ) = chars.mapIndexed { i, c ->
        BenchmarkRow(
            "sweep",
            "int8",
            4,
            "cpu",
            false,
            seq = i + 1,
            chars = c,
            synthMs = ((overheadMs + perCharMs * c) * jitter(i)).toLong(),
            audioMs = 51L * c,
            edgeLeadMs = 50,
            edgeTrailMs = 90,
            thermalStatus = 0,
            thermalHeadroom = null,
            tMs = i * 1000L,
        )
    }

    @Test
    fun summarizeFitsOverheadAndPerCharacterCost() {
        val result = BenchmarkStats.summarize(rows())
        assertEquals(0.3, result.overheadA, 1e-6)
        assertEquals(0.06, result.perCharB1, 1e-6)
        assertEquals(0.0, result.cv, 1e-6)
        val rtfs = rows().map { it.rtf }
        assertEquals(rtfs.average(), result.rtfMean, 1e-12)
        assertEquals(NarrationMetrics.p95(rtfs)!!, result.rtfP95, 1e-12)
        assertEquals(300L + 60 * 40, result.firstUnitMs)
        assertEquals("int8", result.variant)
        assertEquals(4, result.threads)
    }

    @Test
    fun cvMeasuresTheSpreadAroundTheFit() {
        val noisy = BenchmarkStats.summarize(rows(jitter = { if (it % 2 == 0) 1.1 else 0.9 }))
        assertTrue("cv ${noisy.cv}", noisy.cv in 0.07..0.13)
    }

    @Test
    fun priorCarriesTheFitAudioPerCharacterAndCount() {
        val prior = BenchmarkStats.prior(rows(), nowMs = 42)
        assertEquals(0.3, prior.a, 1e-6)
        assertEquals(0.06, prior.b1, 1e-6)
        assertEquals(0.051, prior.alpha1, 1e-9)
        assertEquals("floored", 0.05, prior.cv, 0.0)
        assertEquals(chars.size, prior.n)
        assertEquals(42L, prior.updatedAtMs)
    }

    @Test
    fun gapsAreInteriorPausesInMilliseconds() {
        val rate = 1000
        fun tone(ms: Int) = ShortArray(ms) { 5000 }
        fun silence(ms: Int) = ShortArray(ms)
        val whole =
            silence(80) +
                tone(500) +
                silence(100) +
                tone(500) +
                silence(30) +
                tone(200) +
                silence(300)
        assertEquals(
            "lead, trail and the 30 ms dip are left out",
            listOf(100),
            BenchmarkStats.gaps(listOf(whole), rate),
        )
        val separate =
            listOf(
                silence(60) + tone(500) + silence(50),
                silence(60) + tone(500) + silence(70),
                tone(10),
            )
        assertEquals(listOf(110, 70), BenchmarkStats.gaps(separate, rate))
        assertEquals(emptyList<Int>(), BenchmarkStats.gaps(emptyList(), rate))
    }

    @Test
    fun csvRowsMatchTheHeader() {
        val header = BenchmarkStats.csvHeader().split(",").size
        assertEquals(15, header)
        rows().forEach { assertEquals(header, BenchmarkStats.csvRow(it).split(",").size) }
    }

    @Test
    fun reportJsonParses() {
        val config = BenchmarkStats.summarize(rows())
        val report =
            BenchmarkReport(
                loadMs = mapOf("int8" to 2300L, "fp32" to 3100L),
                configs = listOf(config),
                best = null,
                wholeGapP50Ms = 160,
                wholeGapP95Ms = 400,
                separateGapP50Ms = 165,
                separateGapP95Ms = null,
                sustainedMinutes = 5,
                sustainedRtfFirst = 0.6,
                sustainedRtfLast = Double.NaN,
                thermalMax = 1,
                predictedStartSeconds = 2.5,
                tooSlow = false,
            )
        val json = JSONObject(report.toJson())
        assertEquals(2300L, json.getJSONObject("load_ms").getLong("int8"))
        assertEquals(1, json.getJSONArray("configs").length())
        assertEquals("int8", json.getJSONArray("configs").getJSONObject(0).getString("variant"))
        assertTrue(json.isNull("best"))
        assertTrue(json.isNull("separate_gap_p95_ms"))
        assertTrue("NaN is written as null", json.isNull("sustained_rtf_last"))
        assertEquals(false, json.getBoolean("too_slow"))
        val withBest = JSONObject(report.copy(best = config).toJson())
        assertEquals(4, withBest.getJSONObject("best").getInt("threads"))
    }
}
