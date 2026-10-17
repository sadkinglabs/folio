@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.sadking.folio.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.ui.FolioSpace
import kotlin.math.roundToInt

/*
 * The Stats charts, drawn with Compose Canvas and no chart library. Marks follow one rule set:
 * bars at most 24 dp thick with a 4 dp rounded data end and a square baseline, 2 dp lines, 8 dp
 * dots with a 2 dp surface ring, a 2 dp surface gap between touching fills, hairline solid
 * gridlines one step off the surface, labels in text colours never in a series colour.
 */

/**
 * The book palette for the calendar, the day strip and the per-book timeline: eight fixed hues in a
 * fixed order, stepped once for light and once for dark surfaces. Validated with the dataviz
 * palette checks against the app's fallback surfaces (#F7F6F0 and #121410): every adjacent pair
 * clears colour-blind separation (worst 9.1 light, 8.4 dark) and normal-vision separation (19.6
 * light, 19.3 dark). Four light slots sit under 3:1 on the surface, so a legend with the book's
 * title always accompanies the bars. A book keeps its slot for good; the slot is never reassigned
 * when the month's book count changes.
 */
internal object StatsPalette {
    private val light =
        listOf(
            Color(0xFF2A78D6),
            Color(0xFFEB6834),
            Color(0xFF1BAF7A),
            Color(0xFFEDA100),
            Color(0xFFE87BA4),
            Color(0xFF008300),
            Color(0xFF6250D6),
            Color(0xFFE34948),
        )
    private val dark =
        listOf(
            Color(0xFF3987E5),
            Color(0xFFD95926),
            Color(0xFF199E70),
            Color(0xFFC98500),
            Color(0xFFD55181),
            Color(0xFF008300),
            Color(0xFF9085E9),
            Color(0xFFE66767),
        )

    const val SLOTS = 8

    @Composable
    fun book(slot: Int): Color {
        val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        return (if (isDark) dark else light)[Math.floorMod(slot, SLOTS)]
    }

    /** Reading: the theme's primary. */
    @Composable fun reading(): Color = MaterialTheme.colorScheme.primary

    /**
     * Listening: a lighter step of the same hue, primary at half strength over the surface, so the
     * two read as one activity in two kinds and pass the ordinal (one hue, two steps) checks in
     * both themes (2.2:1 light, 3.7:1 dark on the fallback palette).
     */
    @Composable
    fun listening(): Color =
        MaterialTheme.colorScheme.primary
            .copy(alpha = 0.5f)
            .compositeOver(MaterialTheme.colorScheme.surface)

    /** Gridlines and baselines: a hairline one step off the surface. */
    @Composable fun grid(): Color = MaterialTheme.colorScheme.outlineVariant

    /** The fold for a 4th and later book in one calendar day. */
    @Composable fun other(): Color = MaterialTheme.colorScheme.outline
}

/** The chart's text sizes in px, at the current density and font scale. */
private val barMax = 24.dp
private val cornerRadius = 4.dp
private val gap = 2.dp
private val axisBand = 18.dp

/** "4 h 12 min", "4 h", "48 min". Seconds under a minute read as 1 min, never "0 min". */
@Composable
internal fun durationText(seconds: Long): String {
    val minutes = ((seconds + 30) / 60).coerceAtLeast(if (seconds > 0) 1 else 0)
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> stringResource(R.string.stats_hours_minutes, h, m)
        h > 0 -> stringResource(R.string.stats_hours, h)
        else -> stringResource(R.string.stats_minutes, m)
    }
}

/** "1 h 50", "48 min": a tile's detail line, where "1 h 50 min" would not fit. */
@Composable
internal fun compactDurationText(seconds: Long): String {
    val minutes = (seconds + 30) / 60
    return if (minutes >= 60) "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"
    else stringResource(R.string.stats_minutes, minutes)
}

/** "4 h", "48 m": for axis ticks and bubbles where a word would not fit. */
private fun shortDuration(seconds: Long): String {
    val minutes = (seconds + 30) / 60
    return if (minutes >= 60) "${minutes / 60} h" else "$minutes m"
}

/** The legend under a two-series chart: a dot per series, text in text colours. */
@Composable
internal fun ChartLegend(entries: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(FolioSpace.item)) {
        entries.forEach { (label, color) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A bar from [baseline] up to [top], rounded only at its data end when [rounded]. */
private fun DrawScope.dataBar(
    color: Color,
    left: Float,
    top: Float,
    width: Float,
    baseline: Float,
    radius: Float,
    rounded: Boolean,
    hollow: Boolean = false,
    stroke: Float = 0f,
) {
    val height = (baseline - top).coerceAtLeast(0f)
    if (height <= 0f) return
    val path =
        Path().apply {
            addRoundRect(
                RoundRect(
                    left,
                    top,
                    left + width,
                    baseline,
                    topLeftCornerRadius = if (rounded) CornerRadius(radius) else CornerRadius.Zero,
                    topRightCornerRadius = if (rounded) CornerRadius(radius) else CornerRadius.Zero,
                    bottomLeftCornerRadius = CornerRadius.Zero,
                    bottomRightCornerRadius = CornerRadius.Zero,
                )
            )
        }
    if (hollow) drawPath(path, color, style = Stroke(stroke)) else drawPath(path, color)
}

/** A small label on an inverse surface above a mark: the chart's tooltip. */
private fun DrawScope.bubble(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    anchorX: Float,
    bottomY: Float,
    surface: Color,
    ink: Color,
    padding: Float,
    radius: Float,
) {
    val layout = measurer.measure(text, style)
    val w = layout.size.width + padding * 2
    val h = layout.size.height + padding
    val left = (anchorX - w / 2).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
    val top = (bottomY - h).coerceAtLeast(0f)
    drawRoundRect(surface, Offset(left, top), Size(w, h), CornerRadius(radius))
    drawText(layout, ink, Offset(left + padding, top + padding / 2))
}

/**
 * The trend over the range: one column per day, month or year, reading from the baseline and
 * listening stacked above it behind a 2 dp surface gap. A hairline at the top carries the scale's
 * maximum as a short label; the baseline is a hairline too. Tap or drag picks a column and shows
 * its value in a bubble; the Stats screen opens the Day sheet on a tap in Week and Month.
 */
@Composable
internal fun TrendChart(
    columns: List<TrendColumn>,
    modifier: Modifier = Modifier,
    selected: Int? = null,
    onSelect: (Int?) -> Unit = {},
    plotHeight: Dp = 96.dp,
) {
    val reading = StatsPalette.reading()
    val listening = StatsPalette.listening()
    val grid = StatsPalette.grid()
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val bubbleSurface = MaterialTheme.colorScheme.inverseSurface
    val bubbleInk = MaterialTheme.colorScheme.inverseOnSurface
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall
    val bubbleStyle = MaterialTheme.typography.labelMedium
    val max = columns.maxOfOrNull { it.totalSeconds }?.coerceAtLeast(60L) ?: 60L
    val a11y =
        stringResource(
            R.string.stats_trend_a11y,
            durationText(columns.sumOf { it.readingSeconds }),
            durationText(columns.sumOf { it.listeningSeconds }),
            columns.maxByOrNull { it.totalSeconds }?.label ?: "",
        )
    Canvas(
        modifier
            .fillMaxWidth()
            .height(plotHeight + axisBand + 20.dp)
            .semantics { contentDescription = a11y }
            .pointerInput(columns) {
                val slot = size.width.toFloat() / columns.size.coerceAtLeast(1)
                detectTapGestures { pos ->
                    onSelect((pos.x / slot).toInt().coerceIn(0, columns.lastIndex))
                }
            }
            .pointerInput(columns) {
                val slot = size.width.toFloat() / columns.size.coerceAtLeast(1)
                detectHorizontalDragGestures { change, _ ->
                    onSelect((change.position.x / slot).toInt().coerceIn(0, columns.lastIndex))
                }
            }
    ) {
        if (columns.isEmpty()) return@Canvas
        val bubbleRoom = 20.dp.toPx()
        val plotTop = bubbleRoom
        val baseline = plotTop + plotHeight.toPx()
        // The scale's maximum sits in a gutter on the left, so the first column never covers it.
        val maxLabel = measurer.measure(shortDuration(max), axisStyle)
        val gutter = maxLabel.size.width + 8.dp.toPx()
        val slot = (size.width - gutter) / columns.size
        val barW = minOf(barMax.toPx(), slot * 0.62f)
        val radius = cornerRadius.toPx()
        val gapPx = gap.toPx()
        drawLine(grid, Offset(0f, plotTop), Offset(size.width, plotTop), 1f)
        drawLine(grid, Offset(0f, baseline), Offset(size.width, baseline), 1f)
        drawText(maxLabel, ink, Offset(0f, plotTop + 2.dp.toPx()))
        val scale = (baseline - plotTop) / max
        columns.forEachIndexed { i, c ->
            val left = gutter + slot * i + (slot - barW) / 2
            val readingH = c.readingSeconds * scale
            val listeningH = c.listeningSeconds * scale
            val readingTop = baseline - readingH
            val hasListening = listeningH >= 1f
            dataBar(reading, left, readingTop, barW, baseline, radius, rounded = !hasListening)
            if (hasListening) {
                val top = readingTop - gapPx - listeningH
                dataBar(listening, left, top, barW, readingTop - gapPx, radius, rounded = true)
            }
            if (c.labelled) {
                val layout = measurer.measure(c.label, axisStyle)
                drawText(
                    layout,
                    ink,
                    Offset(
                        gutter + slot * i + (slot - layout.size.width) / 2,
                        baseline + 4.dp.toPx(),
                    ),
                )
            }
        }
        selected
            ?.takeIf { it in columns.indices }
            ?.let { i ->
                val c = columns[i]
                val top =
                    baseline - c.totalSeconds * scale - (if (c.listeningSeconds > 0) gapPx else 0f)
                bubble(
                    measurer,
                    "${c.label} · ${shortDuration(c.totalSeconds)}",
                    bubbleStyle,
                    gutter + slot * i + slot / 2,
                    top - 4.dp.toPx(),
                    bubbleSurface,
                    bubbleInk,
                    6.dp.toPx(),
                    8.dp.toPx(),
                )
            }
    }
}

/**
 * Minutes per hour of the day: 24 thin bars in one hue, the busiest hour labelled above its bar.
 * Four axis labels, left-aligned under midnight, 6 am, noon and 6 pm.
 */
@Composable
internal fun HourChart(
    byHour: List<Long>,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 72.dp,
) {
    val reading = StatsPalette.reading()
    val grid = StatsPalette.grid()
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val strongInk = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall
    val peakIndex = byHour.indices.maxByOrNull { byHour[it] } ?: 0
    val a11y = stringResource(R.string.stats_hours_a11y, hourLabel(peakIndex))
    Canvas(
        modifier.fillMaxWidth().height(plotHeight + axisBand + 18.dp).semantics {
            contentDescription = a11y
        }
    ) {
        val top = 18.dp.toPx()
        val baseline = top + plotHeight.toPx()
        val slot = size.width / 24
        val barW = minOf(barMax.toPx(), slot - gap.toPx())
        val max = (byHour.maxOrNull() ?: 0L).coerceAtLeast(60L)
        val scale = (baseline - top) / max
        drawLine(grid, Offset(0f, baseline), Offset(size.width, baseline), 1f)
        byHour.forEachIndexed { i, s ->
            val left = slot * i + (slot - barW) / 2
            dataBar(reading, left, baseline - s * scale, barW, baseline, cornerRadius.toPx(), true)
        }
        if ((byHour.getOrNull(peakIndex) ?: 0L) > 0L) {
            val layout = measurer.measure(hourLabel(peakIndex), axisStyle)
            val x =
                (slot * peakIndex + slot / 2 - layout.size.width / 2).coerceIn(
                    0f,
                    size.width - layout.size.width,
                )
            drawText(
                layout,
                strongInk,
                Offset(x, baseline - byHour[peakIndex] * scale - layout.size.height - 2.dp.toPx()),
            )
        }
        hourAxisLabels(slot * 6, measurer, axisStyle).forEach { (hour, label) ->
            drawText(
                measurer,
                label,
                Offset(slot * hour, baseline + 4.dp.toPx()),
                axisStyle.copy(color = ink),
            )
        }
    }
}

/**
 * The labels under a 24-hour axis: four when six hours span at least 64 dp, else midnight and noon
 * only, so "Midnight" never runs into "6 am" in a narrow pane.
 */
private fun DrawScope.hourAxisLabels(
    sixHoursPx: Float,
    measurer: TextMeasurer,
    style: TextStyle,
): List<Pair<Int, String>> =
    if (measurer.measure("Midnight", style).size.width + 8.dp.toPx() <= sixHoursPx)
        listOf(0 to "Midnight", 6 to "6 am", 12 to "Noon", 18 to "6 pm")
    else listOf(0 to "Midnight", 12 to "Noon")

/** "10 pm", "Noon", "Midnight": the hour a chart points at, in plain words. */
internal fun hourLabel(hour: Int): String =
    when {
        hour == 0 -> "Midnight"
        hour == 12 -> "Noon"
        hour < 12 -> "$hour am"
        else -> "${hour - 12} pm"
    }

/** Minutes per weekday, Monday first: seven bars, the busiest labelled with its value. */
@Composable
internal fun WeekdayChart(
    byWeekday: List<Long>,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 72.dp,
) {
    val reading = StatsPalette.reading()
    val grid = StatsPalette.grid()
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val strongInk = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall
    val names = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    val letters = listOf("M", "T", "W", "T", "F", "S", "S")
    val peak = byWeekday.indices.maxByOrNull { byWeekday[it] } ?: 0
    val a11y = stringResource(R.string.stats_weekdays_a11y, names[peak])
    Canvas(
        modifier.fillMaxWidth().height(plotHeight + axisBand + 18.dp).semantics {
            contentDescription = a11y
        }
    ) {
        val top = 18.dp.toPx()
        val baseline = top + plotHeight.toPx()
        val slot = size.width / 7
        val barW = minOf(barMax.toPx(), slot * 0.5f)
        val max = (byWeekday.maxOrNull() ?: 0L).coerceAtLeast(60L)
        val scale = (baseline - top) / max
        drawLine(grid, Offset(0f, baseline), Offset(size.width, baseline), 1f)
        byWeekday.forEachIndexed { i, s ->
            val left = slot * i + (slot - barW) / 2
            dataBar(reading, left, baseline - s * scale, barW, baseline, cornerRadius.toPx(), true)
            val layout = measurer.measure(letters[i], axisStyle)
            drawText(
                layout,
                ink,
                Offset(slot * i + (slot - layout.size.width) / 2, baseline + 4.dp.toPx()),
            )
        }
        if ((byWeekday.getOrNull(peak) ?: 0L) > 0L) {
            val layout = measurer.measure(shortDuration(byWeekday[peak]), axisStyle)
            drawText(
                layout,
                strongInk,
                Offset(
                    slot * peak + (slot - layout.size.width) / 2,
                    baseline - byWeekday[peak] * scale - layout.size.height - 2.dp.toPx(),
                ),
            )
        }
    }
}

/**
 * The month grid, KOReader style: seven columns, a row per week, and in each day up to three bars,
 * one per book in the book's colour, as long as that book's time against the month's longest
 * one-book day. A fourth book and later fold into one outline-coloured bar. Listening bars are
 * hollow. Today's number sits on a primary disc. The whole cell is the tap target.
 */
@Composable
internal fun CalendarGrid(
    month: CalendarMonth,
    onDay: (CalendarDay) -> Unit,
    modifier: Modifier = Modifier,
    selectedDay: Int? = null,
) {
    val large = LocalDensity.current.fontScale > 1.3f
    val cellHeight = if (large) 76.dp else 54.dp
    val weekdays = listOf("M", "T", "W", "T", "F", "S", "S")
    val slotColors = (0 until StatsPalette.SLOTS).map { StatsPalette.book(it) }
    val other = StatsPalette.other()
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val ink = MaterialTheme.colorScheme.onSurface
    val mutedInk = MaterialTheme.colorScheme.onSurfaceVariant
    val todayDisc = MaterialTheme.colorScheme.primary
    val onToday = MaterialTheme.colorScheme.onPrimary
    val selectedFill = MaterialTheme.colorScheme.secondaryContainer
    val measurer = rememberTextMeasurer()
    val numberStyle = MaterialTheme.typography.labelMedium
    val rows = (month.firstWeekday + month.days.size + 6) / 7
    val max = month.maxBarSeconds.coerceAtLeast(1L)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            weekdays.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedInk,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        repeat(rows) { row ->
            Row(Modifier.fillMaxWidth().height(cellHeight)) {
                repeat(7) { col ->
                    val index = row * 7 + col - month.firstWeekday
                    val day = month.days.getOrNull(index)
                    if (day == null) {
                        Box(Modifier.weight(1f))
                    } else {
                        val future = month.today != null && day.day > month.today
                        val description =
                            if (day.bars.isEmpty())
                                stringResource(
                                    R.string.stats_calendar_day_none_a11y,
                                    day.day.toString(),
                                )
                            else
                                stringResource(
                                    R.string.stats_calendar_day_a11y,
                                    day.day.toString(),
                                    durationText(day.totalSeconds),
                                    day.bars.size,
                                )
                        Canvas(
                            Modifier.weight(1f)
                                .fillMaxHeight()
                                .padding(1.dp)
                                .clip(FolioSpace.tileShape)
                                .then(
                                    if (selectedDay == day.day) Modifier.background(selectedFill)
                                    else Modifier
                                )
                                .clickable(role = Role.Button) { onDay(day) }
                                .semantics { contentDescription = description }
                        ) {
                            val pad = 4.dp.toPx()
                            val number = measurer.measure(day.day.toString(), numberStyle)
                            // Below the number, whatever the text size, so bars never cross it.
                            val barsTop = pad + number.size.height + 4.dp.toPx()
                            if (month.today == day.day) {
                                val r = maxOf(10.dp.toPx(), number.size.height / 2 + 2.dp.toPx())
                                drawCircle(todayDisc, r, Offset(pad + r, pad + r))
                                drawText(
                                    number,
                                    onToday,
                                    Offset(
                                        pad + r - number.size.width / 2,
                                        pad + r - number.size.height / 2,
                                    ),
                                )
                            } else {
                                val color =
                                    when {
                                        future -> mutedInk.copy(alpha = 0.38f)
                                        day.bars.isEmpty() -> mutedInk
                                        else -> ink
                                    }
                                drawText(number, color, Offset(pad + 2.dp.toPx(), pad))
                            }
                            val barH = 5.dp.toPx()
                            val gapPx = gap.toPx()
                            val maxW = size.width - pad * 2
                            val shown = day.bars.sortedByDescending { it.seconds }.take(3)
                            val rest = day.bars.sortedByDescending { it.seconds }.drop(3)
                            shown.forEachIndexed { i, bar ->
                                val w =
                                    (bar.seconds.toFloat() / max * maxW)
                                        .coerceAtLeast(6.dp.toPx())
                                        .coerceAtMost(maxW)
                                val top = barsTop + i * (barH + gapPx)
                                val color = slotColors[Math.floorMod(bar.slot, StatsPalette.SLOTS)]
                                val rect =
                                    RoundRect(pad, top, pad + w, top + barH, CornerRadius(barH / 2))
                                if (bar.listening)
                                    drawPath(
                                        Path().apply { addRoundRect(rect) },
                                        color,
                                        style = Stroke(1.dp.toPx()),
                                    )
                                else drawPath(Path().apply { addRoundRect(rect) }, color)
                            }
                            if (rest.isNotEmpty() && shown.size == 3) {
                                // The fold replaces the third bar's row only when it would hide
                                // more
                                // than one book; otherwise the third book keeps its colour.
                                val total = rest.sumOf { it.seconds }
                                val w =
                                    (total.toFloat() / max * maxW)
                                        .coerceAtLeast(6.dp.toPx())
                                        .coerceAtMost(maxW)
                                val top = barsTop + 3 * (barH + gapPx)
                                if (top + barH <= size.height - 2.dp.toPx())
                                    drawRoundRect(
                                        other,
                                        Offset(pad, top),
                                        Size(w, barH),
                                        CornerRadius(barH / 2),
                                    )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The month's books under the grid: a dot in the book's colour and its title, up to six. */
@Composable
internal fun CalendarLegend(
    books: List<CalendarBook>,
    onBook: (CalendarBook) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = books.sortedByDescending { it.seconds }.take(6)
    val more = books.size - shown.size
    androidx.compose.foundation.layout.FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FolioSpace.item),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        shown.forEach { entry ->
            Row(
                Modifier.clip(FolioSpace.tileShape)
                    .clickable(role = Role.Button) { onBook(entry) }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(StatsPalette.book(entry.slot)))
                Text(
                    entry.book.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
            }
        }
        if (more > 0)
            Text(
                stringResource(R.string.stats_legend_more, more),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
    }
}

/**
 * One day's sessions along 24 hours: a track with a block per session in the book's colour,
 * listening blocks hollow. Labels under midnight, 6 am, noon and 6 pm.
 */
@Composable
internal fun DayStrip(sessions: List<SessionRow>, modifier: Modifier = Modifier) {
    val slotColors = (0 until StatsPalette.SLOTS).map { StatsPalette.book(it) }
    // A tint, not a container step: the strip sits on a sheet or a panel of any tier.
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall
    val a11y = stringResource(R.string.stats_day_sessions_a11y)
    Canvas(
        modifier.fillMaxWidth().height(20.dp + axisBand).semantics { contentDescription = a11y }
    ) {
        val h = 20.dp.toPx()
        val r = CornerRadius(4.dp.toPx())
        drawRoundRect(track, Offset.Zero, Size(size.width, h), r)
        val perHour = size.width / 24f
        sessions.forEach { s ->
            val left = s.startHour * perHour
            val w = (s.seconds / 3600f * perHour).coerceAtLeast(6.dp.toPx())
            val color = slotColors[Math.floorMod(s.slot, StatsPalette.SLOTS)]
            val rect = RoundRect(left, 0f, (left + w).coerceAtMost(size.width), h, r)
            if (s.listening)
                drawPath(Path().apply { addRoundRect(rect) }, color, style = Stroke(1.5.dp.toPx()))
            else drawPath(Path().apply { addRoundRect(rect) }, color)
        }
        hourAxisLabels(perHour * 6, measurer, axisStyle).forEach { (hour, label) ->
            drawText(
                measurer,
                label,
                Offset(perHour * hour, h + 4.dp.toPx()),
                axisStyle.copy(color = ink),
            )
        }
    }
}

/**
 * A book's progress over its days: a 2 dp line through session points, a 10% wash under it, 8 dp
 * dots with a 2 dp surface ring (hollow for listening), a hairline at 100% and at the baseline, and
 * the last value labelled at the line's end.
 */
@Composable
internal fun BookTimeline(
    points: List<TimelinePoint>,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 72.dp,
    startLabel: String = "",
    endLabel: String = "",
    dots: Boolean = true,
) {
    val line = StatsPalette.reading()
    val grid = StatsPalette.grid()
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val strongInk = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall
    val last = points.lastOrNull()
    val a11y =
        stringResource(
            R.string.stats_book_timeline_a11y,
            ((points.firstOrNull()?.progress ?: 0f) * 100).roundToInt(),
            ((last?.progress ?: 0f) * 100).roundToInt(),
            (last?.day ?: 0f).roundToInt().coerceAtLeast(1),
            points.size,
        )
    Canvas(
        modifier.fillMaxWidth().height(plotHeight + axisBand + 8.dp).semantics {
            contentDescription = a11y
        }
    ) {
        val top = 8.dp.toPx()
        val baseline = top + plotHeight.toPx()
        val endLabelLayout =
            measurer.measure(last?.let { "${(it.progress * 100).roundToInt()}%" } ?: "", axisStyle)
        val right = size.width - endLabelLayout.size.width - 6.dp.toPx()
        drawLine(grid, Offset(0f, top), Offset(size.width, top), 1f)
        drawLine(grid, Offset(0f, baseline), Offset(size.width, baseline), 1f)
        if (points.isEmpty()) return@Canvas
        val maxDay = points.maxOf { it.day }.coerceAtLeast(1f)
        fun x(p: TimelinePoint) = p.day / maxDay * right
        fun y(p: TimelinePoint) = baseline - p.progress.coerceIn(0f, 1f) * (baseline - top)
        val path = Path()
        val area = Path()
        points.forEachIndexed { i, p ->
            if (i == 0) {
                path.moveTo(x(p), y(p))
                area.moveTo(x(p), baseline)
                area.lineTo(x(p), y(p))
            } else {
                path.lineTo(x(p), y(p))
                area.lineTo(x(p), y(p))
            }
        }
        area.lineTo(x(points.last()), baseline)
        area.close()
        drawPath(area, line.copy(alpha = 0.10f))
        drawPath(
            path,
            line,
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        if (dots)
            points.forEach { p ->
                val c = Offset(x(p), y(p))
                drawCircle(surface, 6.dp.toPx(), c)
                if (p.listening)
                    drawCircle(line, 4.dp.toPx() - 1.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                else drawCircle(line, 4.dp.toPx(), c)
            }
        last?.let {
            drawText(
                endLabelLayout,
                strongInk,
                Offset(
                    size.width - endLabelLayout.size.width,
                    y(it) - endLabelLayout.size.height / 2,
                ),
            )
        }
        if (startLabel.isNotEmpty())
            drawText(
                measurer,
                startLabel,
                Offset(0f, baseline + 4.dp.toPx()),
                axisStyle.copy(color = ink),
            )
        if (endLabel.isNotEmpty()) {
            val layout = measurer.measure(endLabel, axisStyle)
            drawText(layout, ink, Offset(right - layout.size.width, baseline + 4.dp.toPx()))
        }
    }
}

/** Books finished per month, January first: twelve columns, the busiest labelled with its count. */
@Composable
internal fun MonthBars(
    byMonth: List<Int>,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 64.dp,
    color: Color = StatsPalette.reading(),
    ink: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    strongInk: Color = MaterialTheme.colorScheme.onSurface,
) {
    val grid = StatsPalette.grid()
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall
    val letters = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")
    val names =
        listOf(
            "January",
            "February",
            "March",
            "April",
            "May",
            "June",
            "July",
            "August",
            "September",
            "October",
            "November",
            "December",
        )
    val peak = byMonth.indices.maxByOrNull { byMonth[it] } ?: 0
    val a11y = stringResource(R.string.stats_year_by_month_a11y, byMonth.sum(), names[peak])
    Canvas(
        modifier.fillMaxWidth().height(plotHeight + axisBand + 18.dp).semantics {
            contentDescription = a11y
        }
    ) {
        val top = 18.dp.toPx()
        val baseline = top + plotHeight.toPx()
        val slot = size.width / 12
        val barW = minOf(barMax.toPx(), slot * 0.56f)
        val max = (byMonth.maxOrNull() ?: 0).coerceAtLeast(1)
        val scale = (baseline - top) / max
        drawLine(grid, Offset(0f, baseline), Offset(size.width, baseline), 1f)
        byMonth.forEachIndexed { i, n ->
            val left = slot * i + (slot - barW) / 2
            dataBar(color, left, baseline - n * scale, barW, baseline, cornerRadius.toPx(), true)
            val layout = measurer.measure(letters[i], axisStyle)
            drawText(
                layout,
                ink,
                Offset(slot * i + (slot - layout.size.width) / 2, baseline + 4.dp.toPx()),
            )
        }
        if ((byMonth.getOrNull(peak) ?: 0) > 0) {
            val layout = measurer.measure(byMonth[peak].toString(), axisStyle)
            drawText(
                layout,
                strongInk,
                Offset(
                    slot * peak + (slot - layout.size.width) / 2,
                    baseline - byMonth[peak] * scale - layout.size.height - 2.dp.toPx(),
                ),
            )
        }
    }
}

/**
 * A Material shape as a tonal badge: the polygon from [MaterialShapes] filled with [color], with
 * [content] centred on it. Milestones and the streak use it.
 */
@Composable
internal fun ShapeBadge(
    polygon: androidx.graphics.shapes.RoundedPolygon,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(modifier.size(size), shape = polygon.toShape(), color = color) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}
