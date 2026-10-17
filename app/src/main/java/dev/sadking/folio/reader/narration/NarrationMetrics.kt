package dev.sadking.folio.reader.narration

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** One unit as made: lengths and timings only, never text. */
internal data class UnitRecord(
    val tMs: Long,
    val gen: Long,
    val seq: Long,
    val utterances: Int,
    val chars: Int,
    val cacheHit: Boolean,
    val queueWaitMs: Long,
    val synthMs: Long,
    val audioMs: Long,
    val edgeLeadMs: Int,
    val edgeTrailMs: Int,
    val gapMs: Int,
    val reserveMsAfter: Long,
    val thermalStatus: Int,
    val thermalHeadroom: Float?,
) {
    val rtf: Double
        get() = synthMs.toDouble() / audioMs
}

/** One boundary between units as heard. */
internal data class BoundaryRecord(
    val tMs: Long,
    val fromSeq: Long,
    val toSeq: Long,
    val readiumRoundtripMs: Long?,
    val heardAhead: Boolean,
    val stallMs: Long,
    val underrunsDelta: Int,
)

internal data class SessionSummary(
    val tapToFirstAudioMs: Long?,
    val modelLoadMs: Long,
    val firstUnitSynthMs: Long?,
    val gatePredictedWaitMs: Long?,
    val gateActualWaitMs: Long?,
    val listenSeconds: Double,
    val stalls: Int,
    val stallMsTotal: Long,
    val underrunsTotal: Int,
    val rtfMean: Double?,
    val rtfP95: Double?,
    val rtfFirst5Min: Double?,
    val rtfLast5Min: Double?,
    val reserveMinS: Double?,
    val reserveMeanS: Double?,
    val reserveMaxS: Double?,
    val thermalMax: Int,
    val screenOffRtfMean: Double?,
    val screenOnRtfMean: Double?,
    val variant: String,
    val threads: Int,
    val speed: Double,
    val silenceScale: Float,
    val alignMiss: Int,
    val readiumLate: Int,
) {
    /** summary.json, keys as section 12 of docs/READ_ALOUD_PIPELINE.md. */
    fun toJson(): String =
        NarrationJson.obj(
            "tap_to_first_audio_ms" to tapToFirstAudioMs,
            "model_load_ms" to modelLoadMs,
            "first_unit_synth_ms" to firstUnitSynthMs,
            "gate_predicted_wait_ms" to gatePredictedWaitMs,
            "gate_actual_wait_ms" to gateActualWaitMs,
            "listen_s" to listenSeconds,
            "stalls" to stalls,
            "stall_ms_total" to stallMsTotal,
            "underruns_total" to underrunsTotal,
            "rtf_mean" to rtfMean,
            "rtf_p95" to rtfP95,
            "rtf_first_5min" to rtfFirst5Min,
            "rtf_last_5min" to rtfLast5Min,
            "reserve_min_s" to reserveMinS,
            "reserve_mean_s" to reserveMeanS,
            "reserve_max_s" to reserveMaxS,
            "thermal_max" to thermalMax,
            "screen_off_rtf_mean" to screenOffRtfMean,
            "screen_on_rtf_mean" to screenOnRtfMean,
            "variant" to variant,
            "threads" to threads,
            "speed" to speed,
            "silence_scale" to silenceScale,
            "align_miss" to alignMiss,
            "readium_late" to readiumLate,
        )
}

/** The parts of a summary that the engine knows and the metrics do not. */
internal data class SessionSummaryFixed(
    val tapToFirstAudioMs: Long?,
    val modelLoadMs: Long,
    val firstUnitSynthMs: Long?,
    val gatePredictedWaitMs: Long?,
    val gateActualWaitMs: Long?,
    val variant: String,
    val threads: Int,
    val speed: Double,
    val silenceScale: Float,
    val alignMiss: Int,
    val readiumLate: Int,
)

/**
 * Ring of records plus the math. Main-confined. Real-time ratios count synthesised units only;
 * cache hits cost no synthesis. Running totals cover the whole session; the rings keep the last
 * metricsRing records for the CSV files and the p95.
 */
internal class NarrationMetrics(private val tuning: NarrationTuning = NarrationTuning.DEFAULT) {
    private val unitRing = ArrayDeque<UnitRecord>()
    private val boundaryRing = ArrayDeque<BoundaryRecord>()
    private val stallTimes = ArrayDeque<Long>()
    private var stallCount = 0
    private var stallMsTotal = 0L
    private var underruns = 0
    private var thermalMax = 0
    private val all = Mean()
    private val firstWindow = Mean()
    private val screenOnMean = Mean()
    private val screenOffMean = Mean()
    private var firstUnitMs: Long? = null
    private var screenOn = true
    private var reserveMin: Double? = null
    private var reserveMax: Double? = null
    private val reserveMean = Mean()
    private var lastReserveMs: Long? = null
    private var listenMs = 0L

    val units: List<UnitRecord>
        get() = unitRing.toList()

    val boundaries: List<BoundaryRecord>
        get() = boundaryRing.toList()

    fun unit(record: UnitRecord) {
        unitRing.addLast(record)
        while (unitRing.size > tuning.metricsRing) unitRing.removeFirst()
        thermalMax = max(thermalMax, record.thermalStatus)
        if (record.cacheHit || record.audioMs <= 0) return
        val rtf = record.rtf
        all.add(rtf)
        val first = firstUnitMs ?: record.tMs.also { firstUnitMs = it }
        if (record.tMs - first < FIVE_MINUTES_MS) firstWindow.add(rtf)
        (if (screenOn) screenOnMean else screenOffMean).add(rtf)
    }

    fun boundary(record: BoundaryRecord) {
        boundaryRing.addLast(record)
        while (boundaryRing.size > tuning.metricsRing) boundaryRing.removeFirst()
        underruns += record.underrunsDelta
    }

    /**
     * A reserve sample. Call it while playing, every few seconds at most: listening time is the sum
     * of intervals between samples, leaving out intervals over [LISTEN_GAP_MS] (paused).
     */
    fun reserve(tMs: Long, seconds: Double, screenOn: Boolean) {
        this.screenOn = screenOn
        reserveMin = min(reserveMin ?: seconds, seconds)
        reserveMax = max(reserveMax ?: seconds, seconds)
        reserveMean.add(seconds)
        lastReserveMs?.let { last ->
            val dt = tMs - last
            if (dt in 1..LISTEN_GAP_MS) listenMs += dt
        }
        lastReserveMs = tMs
    }

    fun stall(tMs: Long, ms: Long) {
        stallCount++
        stallMsTotal += ms
        stallTimes.addLast(tMs)
        val window = (tuning.stallWindowSeconds * 1000).toLong()
        while (stallTimes.isNotEmpty() && stallTimes.first() <= tMs - window) {
            stallTimes.removeFirst()
        }
    }

    /** True when stallsForTooSlow stalls fell inside stallWindowSeconds. */
    fun stallsExceeded(nowMs: Long): Boolean {
        val window = (tuning.stallWindowSeconds * 1000).toLong()
        return stallTimes.count { it > nowMs - window && it <= nowMs } >= tuning.stallsForTooSlow
    }

    fun summary(nowMs: Long, fixed: SessionSummaryFixed): SessionSummary {
        val synthesised = unitRing.filter { !it.cacheHit && it.audioMs > 0 }
        val last = synthesised.filter { it.tMs > nowMs - FIVE_MINUTES_MS }.map { it.rtf }
        return SessionSummary(
            tapToFirstAudioMs = fixed.tapToFirstAudioMs,
            modelLoadMs = fixed.modelLoadMs,
            firstUnitSynthMs = fixed.firstUnitSynthMs,
            gatePredictedWaitMs = fixed.gatePredictedWaitMs,
            gateActualWaitMs = fixed.gateActualWaitMs,
            listenSeconds = listenMs / 1000.0,
            stalls = stallCount,
            stallMsTotal = stallMsTotal,
            underrunsTotal = underruns,
            rtfMean = all.value,
            rtfP95 = p95(synthesised.map { it.rtf }),
            rtfFirst5Min = firstWindow.value,
            rtfLast5Min = if (last.isEmpty()) null else last.average(),
            reserveMinS = reserveMin,
            reserveMeanS = reserveMean.value,
            reserveMaxS = reserveMax,
            thermalMax = thermalMax,
            screenOffRtfMean = screenOffMean.value,
            screenOnRtfMean = screenOnMean.value,
            variant = fixed.variant,
            threads = fixed.threads,
            speed = fixed.speed,
            silenceScale = fixed.silenceScale,
            alignMiss = fixed.alignMiss,
            readiumLate = fixed.readiumLate,
        )
    }

    private class Mean {
        private var sum = 0.0
        private var count = 0

        fun add(x: Double) {
            sum += x
            count++
        }

        val value: Double?
            get() = if (count == 0) null else sum / count
    }

    companion object {
        private const val FIVE_MINUTES_MS = 300_000L
        private const val LISTEN_GAP_MS = 5_000L

        fun unitsCsvHeader(): String =
            "t_ms,gen,seq,utterances,chars,cache_hit,queue_wait_ms,synth_ms,audio_ms,rtf," +
                "edge_lead_ms,edge_trail_ms,gap_ms,reserve_ms_after,thermal_status,thermal_headroom"

        fun unitsCsvRow(r: UnitRecord): String =
            listOf(
                    r.tMs,
                    r.gen,
                    r.seq,
                    r.utterances,
                    r.chars,
                    if (r.cacheHit) 1 else 0,
                    r.queueWaitMs,
                    r.synthMs,
                    r.audioMs,
                    NarrationJson.number(if (r.audioMs > 0) r.rtf else null, 4),
                    r.edgeLeadMs,
                    r.edgeTrailMs,
                    r.gapMs,
                    r.reserveMsAfter,
                    r.thermalStatus,
                    NarrationJson.number(r.thermalHeadroom?.toDouble(), 3),
                )
                .joinToString(",")

        fun boundariesCsvHeader(): String =
            "t_ms,from_seq,to_seq,readium_roundtrip_ms,heard_ahead,stall_ms,underruns_delta"

        fun boundariesCsvRow(r: BoundaryRecord): String =
            listOf(
                    r.tMs,
                    r.fromSeq,
                    r.toSeq,
                    r.readiumRoundtripMs ?: "",
                    if (r.heardAhead) 1 else 0,
                    r.stallMs,
                    r.underrunsDelta,
                )
                .joinToString(",")

        /** Nearest-rank 95th percentile; null when empty. */
        fun p95(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val rank = ceil(0.95 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}

/** Just enough JSON and number formatting for the measurement files; Locale.ROOT throughout. */
internal object NarrationJson {
    /** Already-encoded JSON, written as is by [value]. */
    class Raw(val json: String)

    /** "" for null or non-finite, else [decimals] places. */
    fun number(value: Double?, decimals: Int): String =
        if (value == null || !value.isFinite()) ""
        else String.format(Locale.ROOT, "%.${decimals}f", value)

    fun obj(vararg fields: Pair<String, Any?>): String =
        fields.joinToString(",", "{", "}") { (k, v) -> "${string(k)}:${value(v)}" }

    fun array(values: List<String>): String = values.joinToString(",", "[", "]")

    fun value(v: Any?): String =
        when (v) {
            null -> "null"
            is Boolean -> v.toString()
            is Int,
            is Long -> v.toString()
            is Float -> if (v.isFinite()) v.toString() else "null"
            is Double -> if (v.isFinite()) v.toString() else "null"
            is String -> string(v)
            is Raw -> v.json
            else -> string(v.toString())
        }

    fun string(s: String): String {
        val out = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }
}
