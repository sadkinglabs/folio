package dev.sadking.folio.reader

import android.content.SharedPreferences
import android.content.res.Resources
import androidx.core.content.edit
import dev.sadking.folio.R
import kotlin.math.ceil

/**
 * The owner's reading speed on this phone, per format, measured from page turns while reading.
 *
 * A sample is a forward step of 1 to 3 positions that settles 3 seconds to 3 minutes after the
 * previous settled page. Backward moves, jumps (slider, contents, search, links) and longer gaps
 * start a new run without a sample, so idle time never counts. The same position settling again
 * keeps the clock running: a phone page can be smaller than one Readium position. Samples are
 * averaged with an exponentially weighted mean (weight 0.1; a plain mean for the first ten, so the
 * first sample does not weigh for ever). Until 20 samples, the defaults stand in.
 */
class ReadingSpeed(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var runKind: ReaderKind? = null
    private var runPosition = 0
    private var runAt = 0L

    /** A settled page: [position] is the Readium position (1-based). */
    fun observe(kind: ReaderKind, position: Int) {
        val now = clock()
        val continuing = runKind == kind
        if (continuing && position == runPosition) return
        val step = position - runPosition
        val elapsed = now - runAt
        runKind = kind
        runPosition = position
        runAt = now
        if (!continuing) return
        if (step in 1..MAX_STEP && elapsed in MIN_GAP_MS..MAX_GAP_MS)
            record(kind, step * 60_000.0 / elapsed)
    }

    /** A jump, a pause or leaving the reader: the next page starts a new run. */
    fun interrupt() {
        runKind = null
    }

    fun positionsPerMinute(kind: ReaderKind): Double =
        if (measured(kind))
            prefs.getFloat(speedKey(kind), 0f).toDouble().takeIf { it > 0 } ?: default(kind)
        else default(kind)

    fun measured(kind: ReaderKind): Boolean = prefs.getInt(samplesKey(kind), 0) >= MIN_SAMPLES

    private fun record(kind: ReaderKind, sample: Double) {
        val count = prefs.getInt(samplesKey(kind), 0) + 1
        val previous = prefs.getFloat(speedKey(kind), sample.toFloat()).toDouble()
        val weight = maxOf(WEIGHT, 1.0 / count)
        val value = previous + weight * (sample - previous)
        prefs.edit {
            putFloat(speedKey(kind), value.toFloat())
            putInt(samplesKey(kind), count)
        }
    }

    companion object {
        const val MAX_STEP = 3
        const val MIN_GAP_MS = 3_000L
        const val MAX_GAP_MS = 3 * 60_000L
        const val WEIGHT = 0.1
        const val MIN_SAMPLES = 20

        internal fun speedKey(kind: ReaderKind) = "speed_${kind.name.lowercase()}"

        internal fun samplesKey(kind: ReaderKind) = "speed_samples_${kind.name.lowercase()}"

        /**
         * Until measured: one Readium EPUB position is about 1,024 characters, so 1.2 a minute is
         * an ordinary reading pace; a PDF page a minute; four comic pages a minute.
         */
        fun default(kind: ReaderKind): Double =
            when (kind) {
                ReaderKind.EPUB -> 1.2
                ReaderKind.PDF -> 1.0
                ReaderKind.COMIC -> 4.0
            }
    }
}

/** Minutes left in the chapter (null when unknown) and in the book; each at least 1. */
data class TimeLeft(val chapterMinutes: Int?, val bookMinutes: Int)

/**
 * Time left from [position] (1-based) of [total], counting the current position, at [perMinute]
 * positions a minute. [chapterEnd] is the chapter's last position (1-based), or null.
 */
fun timeLeft(position: Int, total: Int, chapterEnd: Int?, perMinute: Double): TimeLeft {
    val rate = perMinute.takeIf { it > 0 && it.isFinite() } ?: 1.0
    val last = total.coerceAtLeast(1)
    val current = position.coerceIn(1, last)
    // The small allowance keeps 240 / 1.2 at 200 minutes despite floating-point rounding.
    fun minutes(positions: Int) =
        ceil(positions.coerceAtLeast(1) / rate - 1e-9).toInt().coerceAtLeast(1)
    return TimeLeft(
        chapterEnd?.let { minutes(it.coerceIn(current, last) - current + 1) },
        minutes(last - current + 1),
    )
}

/**
 * The last position (1-based) of the chapter holding [position], from where each reading-order item
 * starts ([chapterFirst], 0-based indexes into the positions); null when they are unknown.
 */
fun chapterEndAt(position: Int, chapterFirst: List<Int>, total: Int): Int? {
    if (chapterFirst.isEmpty() || total <= 0) return null
    val index = position - 1
    return chapterFirst.firstOrNull { it > index }?.coerceAtMost(total) ?: total
}

/** "12 min", "1 h", "4 h 10 min". */
internal fun durationText(minutes: Int, resources: Resources): String {
    val value = minutes.coerceAtLeast(1)
    return when {
        value < 60 -> resources.getString(R.string.rd6_minutes, value)
        value % 60 == 0 -> resources.getString(R.string.rd6_hours, value / 60)
        else -> resources.getString(R.string.rd6_hours_minutes, value / 60, value % 60)
    }
}

/** "12 min left in chapter · 4 h 10 min in book", or the book part alone. */
internal fun timeLeftText(left: TimeLeft, resources: Resources): String {
    val book = durationText(left.bookMinutes, resources)
    return left.chapterMinutes?.let {
        resources.getString(R.string.rd6_time_left_both, durationText(it, resources), book)
    } ?: resources.getString(R.string.rd6_time_left_book, book)
}
