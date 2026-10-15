package dev.sadking.folio.reader

import kotlin.math.roundToInt

/**
 * The bottom bar's progress: how far through the book, as a 0..1 fraction, and the "Page 12 of 340
 * · 4%" label. [page] is null until Readium's positions are known, in which case the label shows
 * the percentage alone.
 */
data class PageLabel(val page: Int?, val total: Int, val percent: Int)

/** The slider's position for a Readium total progression; unknown progression sits at the start. */
fun progressFraction(totalProgression: Double?): Float =
    (totalProgression ?: 0.0).coerceIn(0.0, 1.0).toFloat()

/** The label for the current page: a page number only when positions are known. */
fun pageLabel(totalProgression: Double?, position: Int?, total: Int): PageLabel =
    PageLabel(
        page = position?.takeIf { total > 0 },
        total = total,
        percent = ((totalProgression ?: 0.0) * 100).toInt().coerceIn(0, 100),
    )

/** The label while scrubbing: the page the slider would open at [fraction]. */
fun scrubLabel(fraction: Float, total: Int): PageLabel =
    PageLabel(
        page = if (total > 0) positionIndexAt(fraction, total) + 1 else null,
        total = total,
        percent = (fraction.coerceIn(0f, 1f) * 100).roundToInt(),
    )

/** Index into the positions list for a slider fraction; the last page at the far end. */
fun positionIndexAt(fraction: Float, total: Int): Int =
    (fraction.coerceIn(0f, 1f) * total).toInt().coerceIn(0, (total - 1).coerceAtLeast(0))

/**
 * Where each chapter after the first starts, as slider fractions, from the number of positions in
 * each reading-order item. Empty items make no tick; two chapters starting at the same place make
 * one.
 */
fun chapterTicks(positionsPerChapter: List<Int>): List<Float> {
    val total = positionsPerChapter.sum()
    if (total <= 0) return emptyList()
    var start = 0
    val ticks = mutableListOf<Float>()
    positionsPerChapter.forEach { count ->
        if (start > 0 && count > 0) {
            val tick = start.toFloat() / total
            if (ticks.lastOrNull() != tick) ticks += tick
        }
        start += count
    }
    return ticks
}

/**
 * Drops ticks closer than [minGap] to the one kept before them, so a book of 300 short chapters
 * does not turn the track into a dotted line. The first tick is always kept.
 */
fun thinTicks(ticks: List<Float>, minGap: Float): List<Float> {
    val kept = mutableListOf<Float>()
    ticks.sorted().forEach { tick ->
        if (kept.isEmpty() || tick - kept.last() >= minGap) kept += tick
    }
    return kept
}

/** The chapter title to show while scrubbing: the last chapter starting at or before [fraction]. */
fun chapterTitleAt(fraction: Float, chapterStarts: List<Float>, titles: List<String?>): String? {
    val index = chapterStarts.indexOfLast { it <= fraction.coerceIn(0f, 1f) }
    return titles.getOrNull(index)?.takeIf { it.isNotBlank() }
}
