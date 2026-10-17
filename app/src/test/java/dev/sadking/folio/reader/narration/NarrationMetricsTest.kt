package dev.sadking.folio.reader.narration

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationMetricsTest {
    private fun record(
        tMs: Long,
        seq: Long,
        synthMs: Long,
        audioMs: Long,
        cacheHit: Boolean = false,
        thermal: Int = 0,
    ) =
        UnitRecord(
            tMs,
            1,
            seq,
            1,
            150,
            cacheHit,
            5,
            synthMs,
            audioMs,
            40,
            60,
            320,
            30_000,
            thermal,
            null,
        )

    private val fixed = SessionSummaryFixed(1800, 2400, 900, 1500, 1700, "int8", 4, 1.0, 0.4f, 0, 2)

    @Test
    fun p95IsNearestRank() {
        assertNull(NarrationMetrics.p95(emptyList()))
        assertEquals(5.0, NarrationMetrics.p95(listOf(5.0))!!, 0.0)
        assertEquals(19.0, NarrationMetrics.p95((1..20).map { it.toDouble() })!!, 0.0)
        assertEquals(95.0, NarrationMetrics.p95((100 downTo 1).map { it.toDouble() })!!, 0.0)
    }

    @Test
    fun csvRowsHaveAsManyColumnsAsTheirHeaders() {
        val unitColumns = NarrationMetrics.unitsCsvHeader().split(",").size
        assertEquals(16, unitColumns)
        assertEquals(
            unitColumns,
            NarrationMetrics.unitsCsvRow(record(0, 0, 100, 0)).split(",").size,
        )
        assertEquals(
            unitColumns,
            NarrationMetrics.unitsCsvRow(record(0, 0, 100, 200).copy(thermalHeadroom = 0.4f))
                .split(",")
                .size,
        )
        assertTrue(NarrationMetrics.unitsCsvRow(record(0, 0, 100, 200)).contains(",0.5000,"))
        val boundaryColumns = NarrationMetrics.boundariesCsvHeader().split(",").size
        assertEquals(7, boundaryColumns)
        assertEquals(
            boundaryColumns,
            NarrationMetrics.boundariesCsvRow(BoundaryRecord(1, 2, 3, null, true, 0, 0))
                .split(",")
                .size,
        )
        assertEquals(
            "1,2,3,40,0,15,1",
            NarrationMetrics.boundariesCsvRow(BoundaryRecord(1, 2, 3, 40, false, 15, 1)),
        )
    }

    @Test
    fun threeStallsInsideFiveMinutesAreTooMany() {
        val metrics = NarrationMetrics()
        metrics.stall(0, 500)
        metrics.stall(100_000, 500)
        assertFalse(metrics.stallsExceeded(100_000))
        metrics.stall(200_000, 500)
        assertTrue(metrics.stallsExceeded(200_000))
        assertFalse(metrics.stallsExceeded(301_000))
    }

    @Test
    fun summaryComparesTheFirstAndLastFiveMinutes() {
        val metrics = NarrationMetrics()
        for (i in 0..60) {
            val t = i * 10_000L
            val rtf = if (t < 300_000) 0.5 else 1.0
            metrics.unit(record(t, i.toLong(), (4000 * rtf).toLong(), 4000))
        }
        metrics.unit(record(605_000, 99, 1, 4000, cacheHit = true))
        val summary = metrics.summary(600_000, fixed)
        assertEquals(0.5, summary.rtfFirst5Min!!, 1e-9)
        assertEquals(1.0, summary.rtfLast5Min!!, 1e-9)
        assertEquals((30 * 0.5 + 31 * 1.0) / 61, summary.rtfMean!!, 1e-9)
        assertEquals(1.0, summary.rtfP95!!, 1e-9)
        assertEquals(1800L, summary.tapToFirstAudioMs)
        assertEquals("int8", summary.variant)
    }

    @Test
    fun reserveScreenAndListeningTime() {
        val metrics = NarrationMetrics()
        metrics.reserve(0, 5.0, screenOn = true)
        metrics.unit(record(500, 0, 2000, 4000))
        metrics.reserve(1000, 15.0, screenOn = false)
        metrics.unit(record(1500, 1, 3000, 4000, thermal = 2))
        metrics.reserve(2000, 10.0, screenOn = false)
        metrics.reserve(20_000, 12.0, screenOn = false)
        metrics.stall(2500, 700)
        metrics.boundary(BoundaryRecord(3000, 0, 1, 80, false, 700, 2))
        val summary = metrics.summary(20_000, fixed)
        assertEquals("the 18 s pause is left out", 2.0, summary.listenSeconds, 1e-9)
        assertEquals(5.0, summary.reserveMinS!!, 0.0)
        assertEquals(15.0, summary.reserveMaxS!!, 0.0)
        assertEquals(10.5, summary.reserveMeanS!!, 1e-9)
        assertEquals(0.5, summary.screenOnRtfMean!!, 1e-9)
        assertEquals(0.75, summary.screenOffRtfMean!!, 1e-9)
        assertEquals(2, summary.thermalMax)
        assertEquals(1, summary.stalls)
        assertEquals(700L, summary.stallMsTotal)
        assertEquals(2, summary.underrunsTotal)
    }

    @Test
    fun ringsKeepTheLast200() {
        val metrics = NarrationMetrics()
        repeat(250) {
            metrics.unit(record(it.toLong(), it.toLong(), 100, 200))
            metrics.boundary(BoundaryRecord(it.toLong(), it.toLong(), it + 1L, null, false, 0, 0))
        }
        assertEquals(200, metrics.units.size)
        assertEquals(50L, metrics.units.first().seq)
        assertEquals(200, metrics.boundaries.size)
    }

    @Test
    fun summaryJsonParsesWithTheDocumentedKeys() {
        val empty =
            NarrationMetrics().summary(0, fixed.copy(tapToFirstAudioMs = null, variant = "fp\"32"))
        val json = JSONObject(empty.toJson())
        assertTrue(json.isNull("tap_to_first_audio_ms"))
        assertTrue(json.isNull("rtf_mean"))
        assertEquals("fp\"32", json.getString("variant"))
        assertEquals(2400L, json.getLong("model_load_ms"))
        assertEquals(0.4, json.getDouble("silence_scale"), 1e-6)
        val keys =
            setOf(
                "tap_to_first_audio_ms",
                "model_load_ms",
                "first_unit_synth_ms",
                "gate_predicted_wait_ms",
                "gate_actual_wait_ms",
                "listen_s",
                "stalls",
                "stall_ms_total",
                "underruns_total",
                "rtf_mean",
                "rtf_p95",
                "rtf_first_5min",
                "rtf_last_5min",
                "reserve_min_s",
                "reserve_mean_s",
                "reserve_max_s",
                "thermal_max",
                "screen_off_rtf_mean",
                "screen_on_rtf_mean",
                "variant",
                "threads",
                "speed",
                "silence_scale",
                "align_miss",
                "readium_late",
            )
        assertEquals(keys, json.keys().asSequence().toSet())
    }
}
