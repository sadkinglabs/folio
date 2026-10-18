@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.sadking.folio.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import dev.sadking.folio.R
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.decode
import dev.sadking.folio.ui.CoverTile
import dev.sadking.folio.ui.CoverVariant
import dev.sadking.folio.ui.EmptyState
import dev.sadking.folio.ui.FolioIllustration
import dev.sadking.folio.ui.FolioSpace
import dev.sadking.folio.ui.LocalContentBottomPadding
import dev.sadking.folio.ui.LocalFolioWindow
import dev.sadking.folio.ui.Panes
import dev.sadking.folio.ui.ResultRow
import dev.sadking.folio.ui.SectionHeading
import dev.sadking.folio.ui.SheetHeading
import dev.sadking.folio.ui.StatusStrip
import dev.sadking.folio.ui.StatusTile
import dev.sadking.folio.ui.Tooltipped
import dev.sadking.folio.ui.cappedTextStyle
import dev.sadking.folio.ui.lift
import kotlin.math.abs

/*
 * The Stats tab and its sheets, as the M12 design mockups. Data comes in as the placeholder types
 * of StatsModels.kt; callbacks go out. Nothing here is wired into the app's navigation yet.
 */

internal const val STATS_TAG = "stats"
internal const val STATS_HERO_TAG = "stats-hero"
internal const val STATS_CALENDAR_TAG = "stats-calendar"
internal const val STATS_RANGE_TAG = "stats-range"

/** What the Stats screen can be asked to do. One object, so the long parameter list stays short. */
internal class StatsActions(
    val onRange: (StatsRange) -> Unit = {},
    val onTrend: (Int?) -> Unit = {},
    val onPreviousMonth: () -> Unit = {},
    val onNextMonth: () -> Unit = {},
    val onDay: (CalendarDay) -> Unit = {},
    val onBook: (Book) -> Unit = {},
    val onYear: (Int) -> Unit = {},
    val onShareYear: (Int) -> Unit = {},
    val onGoals: () -> Unit = {},
    val onLibrary: () -> Unit = {},
)

/**
 * The Stats tab in the app's frame. One scroll on a phone and a rail; from 840 dp (or across a
 * fold) the calendar keeps the left pane with the chosen day under it, and the dashboard takes the
 * right.
 */
@Composable
internal fun StatsTab(
    state: StatsState,
    range: StatsRange,
    actions: StatsActions,
    selectedTrend: Int? = null,
    selectedDay: DayDetails? = null,
) {
    val window = LocalFolioWindow.current
    val ready = state as? StatsState.Ready
    val twoPane = window.twoPane && ready != null
    Panes(
        twoPane = twoPane,
        maxWidth = null,
        primary = {
            if (twoPane && ready != null)
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            top = 12.dp,
                            bottom = LocalContentBottomPadding.current + FolioSpace.section,
                        ),
                    verticalArrangement = Arrangement.spacedBy(FolioSpace.section),
                ) {
                    StatsTitle(actions.onGoals)
                    Column(Modifier.padding(horizontal = FolioSpace.page)) {
                        CalendarCard(ready.month, selectedDay, actions)
                    }
                    if (selectedDay != null)
                        Column(Modifier.padding(horizontal = FolioSpace.page)) {
                            Surface(
                                Modifier.fillMaxWidth(),
                                shape = FolioSpace.panelShape,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Column(Modifier.padding(vertical = FolioSpace.item)) {
                                    DayDetailsContent(selectedDay, actions.onBook)
                                }
                            }
                        }
                }
            else StatsScreen(state, range, actions, selectedTrend, calendarInline = true)
        },
        detail =
            if (twoPane)
                ({
                    StatsScreen(
                        state,
                        range,
                        actions,
                        selectedTrend,
                        calendarInline = false,
                        showTitle = false,
                    )
                })
            else null,
        empty = {},
    )
}

/** The screen title row: "Stats" in serif, and the Goals icon. */
@Composable
private fun StatsTitle(onGoals: () -> Unit) {
    val large = LocalDensity.current.fontScale > 1.5f
    Row(
        Modifier.fillMaxWidth().padding(start = FolioSpace.page, end = FolioSpace.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.stats_title),
            style =
                if (large) MaterialTheme.typography.headlineSmall
                else MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        val goals = stringResource(R.string.stats_goals)
        Tooltipped(goals) {
            IconButton(onClick = onGoals) { Icon(Icons.Outlined.Flag, goals) }
        }
    }
}

/**
 * The dashboard: title, range, hero, then the calendar (when [calendarInline]), habits, books,
 * milestones, years, the goal offer and a line on the sources. Scrolls under the glass bar.
 */
@Composable
internal fun StatsScreen(
    state: StatsState,
    range: StatsRange,
    actions: StatsActions,
    selectedTrend: Int? = null,
    calendarInline: Boolean = true,
    showTitle: Boolean = true,
) {
    val page = Modifier.padding(horizontal = FolioSpace.page)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                top = 12.dp,
                bottom = LocalContentBottomPadding.current + FolioSpace.section,
            ),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.section),
    ) {
        if (showTitle) item("title") { StatsTitle(actions.onGoals) }
        when (state) {
            StatsState.Loading ->
                item("loading") {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 96.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
                    ) {
                        LoadingIndicator()
                        Text(
                            stringResource(R.string.stats_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            is StatsState.Empty -> {
                item("empty") {
                    EmptyState(
                        title = stringResource(R.string.stats_empty_title),
                        message = stringResource(R.string.stats_empty_body),
                        icon = Icons.Outlined.AutoStories,
                        illustration = FolioIllustration.Books,
                        action = actions.onLibrary,
                        actionLabel = stringResource(R.string.stats_empty_action),
                    )
                }
                if (state.years.isNotEmpty()) yearsItem(state.years, actions)
                item("goals") { Box(page) { GoalsOffer(actions.onGoals) } }
            }
            is StatsState.Failed ->
                item("failed") {
                    EmptyState(
                        title = stringResource(R.string.stats_error),
                        message = "",
                        icon = Icons.Outlined.ErrorOutline,
                        action = state.retry,
                        actionLabel = stringResource(R.string.stats_retry),
                    )
                }
            is StatsState.Ready -> {
                item("range") { Box(page) { RangeGroup(range, actions.onRange) } }
                item("hero") {
                    Box(page) {
                        HeroPanel(
                            state.overview,
                            state.goals,
                            state.syncPending,
                            state.phoneOnly,
                            selectedTrend,
                            actions.onTrend,
                        )
                    }
                }
                if (calendarInline)
                    item("calendar") {
                        Column(page, verticalArrangement = Arrangement.spacedBy(FolioSpace.gap)) {
                            SectionHeading(stringResource(R.string.stats_calendar))
                            CalendarCard(state.month, null, actions)
                        }
                    }
                item("habits") {
                    Column(page, verticalArrangement = Arrangement.spacedBy(FolioSpace.gap)) {
                        SectionHeading(stringResource(R.string.stats_habits))
                        HabitsCard(state.habits)
                    }
                }
                item("books") { BooksRow(state.books, actions.onBook) }
                item("milestones") { MilestonesRow(state.milestones) }
                yearsItem(state.years, actions)
                if (!state.goals.on) item("goals") { Box(page) { GoalsOffer(actions.onGoals) } }
                item("sources") {
                    Text(
                        stringResource(R.string.stats_sources_line),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = page,
                    )
                }
            }
        }
    }
}

/**
 * The years list: a heading, then a row per year. The dashboard and the Empty state both show it.
 */
private fun LazyListScope.yearsItem(years: List<YearSummary>, actions: StatsActions) {
    item("years") {
        Column(
            Modifier.padding(horizontal = FolioSpace.page),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        ) {
            SectionHeading(stringResource(R.string.stats_years))
            years.forEach { YearRow(it, actions.onYear, actions.onShareYear) }
        }
    }
}

/** Week · Month · Year · All as one connected group of toggle buttons; exactly one is on. */
@Composable
internal fun RangeGroup(
    range: StatsRange,
    onRange: (StatsRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels =
        listOf(
            StatsRange.WEEK to stringResource(R.string.stats_range_week),
            StatsRange.MONTH to stringResource(R.string.stats_range_month),
            StatsRange.YEAR to stringResource(R.string.stats_range_year),
            StatsRange.ALL to stringResource(R.string.stats_range_all),
        )
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        labels.forEachIndexed { i, (value, label) ->
            ToggleButton(
                checked = range == value,
                onCheckedChange = { if (it) onRange(value) },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                shapes =
                    when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        labels.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                // Narrow padding and a capped label, so four words fit a phone at 200% text.
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            ) {
                Text(
                    label,
                    style = cappedTextStyle(MaterialTheme.typography.labelLarge, 1.3f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun eyebrow(range: StatsRange): String =
    stringResource(
        when (range) {
            StatsRange.WEEK -> R.string.stats_eyebrow_week
            StatsRange.MONTH -> R.string.stats_eyebrow_month
            StatsRange.YEAR -> R.string.stats_eyebrow_year
            StatsRange.ALL -> R.string.stats_eyebrow_all
        }
    )

/** An eyebrow in small caps, as the settings groups use. */
@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * The hero: the range's name, its total as the one big number, a line of facts, the trend chart
 * with its legend, three tiles, the goals when they are on, and a note while a source is missing.
 */
@Composable
internal fun HeroPanel(
    overview: StatsOverview,
    goals: Goals,
    syncPending: Boolean,
    phoneOnly: Boolean,
    selectedTrend: Int?,
    onTrend: (Int?) -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(FolioSpace.item),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Eyebrow(eyebrow(overview.range))
                Text(
                    durationText(overview.totalSeconds),
                    style = cappedTextStyle(MaterialTheme.typography.displayMediumEmphasized, 1.3f),
                    maxLines = 1,
                )
                Text(
                    stringResource(
                        R.string.stats_joined,
                        stringResource(
                            R.string.stats_days_of,
                            overview.daysRead,
                            overview.daysInRange,
                        ),
                        pluralStringResource(
                            R.plurals.stats_sessions_count,
                            overview.sessions,
                            overview.sessions,
                        ),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val trend =
                when {
                    overview.trendPercent == null -> null
                    overview.trendPercent > 0 ->
                        stringResource(
                            R.string.stats_trend_up,
                            overview.trendPercent,
                            overview.previousRangeName,
                        )
                    overview.trendPercent < 0 ->
                        stringResource(
                            R.string.stats_trend_down,
                            abs(overview.trendPercent),
                            overview.previousRangeName,
                        )
                    else -> stringResource(R.string.stats_trend_same, overview.previousRangeName)
                }
            TrendChart(overview.columns, selected = selectedTrend, onSelect = onTrend)
            ChartLegend(
                listOf(
                    stringResource(R.string.stats_legend_reading) to StatsPalette.reading(),
                    stringResource(R.string.stats_legend_listening) to StatsPalette.listening(),
                )
            )
            if (trend != null)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        if ((overview.trendPercent ?: 0) < 0) Icons.Outlined.TrendingDown
                        else Icons.Outlined.TrendingUp,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(trend, style = MaterialTheme.typography.labelLarge)
                }
            val perSession =
                if (overview.sessions > 0) overview.readingSeconds / overview.sessions else 0L
            StatusStrip(
                tiles =
                    listOf(
                        StatusTile(
                            Icons.Outlined.CalendarToday,
                            stringResource(R.string.stats_tile_days),
                            overview.daysRead.toString(),
                            stringResource(R.string.stats_tile_days_detail, overview.daysInRange),
                            "stats-tile-days",
                        ),
                        StatusTile(
                            Icons.Outlined.Timer,
                            stringResource(R.string.stats_tile_sessions),
                            overview.sessions.toString(),
                            stringResource(
                                R.string.stats_tile_sessions_detail,
                                durationText(perSession),
                            ),
                            "stats-tile-sessions",
                        ),
                        StatusTile(
                            Icons.Outlined.Speed,
                            stringResource(R.string.stats_tile_pace),
                            paceText(overview.wordsPerMinute),
                            stringResource(
                                if (phoneOnly) R.string.stats_tile_pace_phone
                                else R.string.stats_tile_pace_detail
                            ),
                            "stats-tile-pace",
                        ),
                    ),
                onClick = {},
            )
            if (goals.on) GoalsProgress(goals)
            if (syncPending || phoneOnly)
                Text(
                    stringResource(
                        if (syncPending) R.string.stats_sync_pending else R.string.stats_phone_only
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

/** Today's minutes as a wavy ring; the year's books as a wavy bar. Only when a goal is set. */
@Composable
internal fun GoalsProgress(goals: Goals, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FolioSpace.gap)) {
        goals.minutesPerDay?.let { target ->
            val fraction = (goals.minutesToday.toFloat() / target).coerceIn(0f, 1f)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
            ) {
                CircularWavyProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(44.dp),
                )
                Column {
                    Text(
                        stringResource(
                            R.string.stats_goal_minutes_progress,
                            goals.minutesToday,
                            target,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (goals.minutesToday >= target)
                            stringResource(R.string.stats_goal_done_today)
                        else
                            stringResource(
                                R.string.stats_goal_left_today,
                                target - goals.minutesToday,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        goals.booksPerYear?.let { target ->
            val fraction = (goals.booksSoFar.toFloat() / target).coerceIn(0f, 1f)
            val gapToPace = goals.booksSoFar - goals.booksOnPace
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            R.string.stats_goal_books_progress,
                            goals.booksSoFar,
                            target,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        when {
                            gapToPace == 0 -> stringResource(R.string.stats_goal_on_pace)
                            gapToPace > 0 ->
                                pluralStringResource(
                                    R.plurals.stats_goal_ahead,
                                    gapToPace,
                                    gapToPace,
                                )
                            else ->
                                pluralStringResource(
                                    R.plurals.stats_goal_behind,
                                    -gapToPace,
                                    -gapToPace,
                                )
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearWavyProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The month: its name between two chevrons, the grid, and the books as a legend. */
@Composable
internal fun CalendarCard(month: CalendarMonth, selected: DayDetails?, actions: StatsActions) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(FolioSpace.gap),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val previous = stringResource(R.string.stats_previous_month)
                val next = stringResource(R.string.stats_next_month)
                Tooltipped(previous) {
                    IconButton(onClick = actions.onPreviousMonth) {
                        Icon(Icons.Outlined.ChevronLeft, previous)
                    }
                }
                Text(
                    month.title,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Tooltipped(next) {
                    IconButton(onClick = actions.onNextMonth, enabled = month.today == null) {
                        Icon(Icons.Outlined.ChevronRight, next)
                    }
                }
            }
            CalendarGrid(month, actions.onDay, selectedDay = selected?.day)
            if (month.books.isEmpty())
                Text(
                    stringResource(R.string.stats_calendar_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            else
                CalendarLegend(
                    month.books,
                    { actions.onBook(it.book) },
                    Modifier.padding(horizontal = 4.dp),
                )
        }
    }
}

/**
 * Habits: the streak on a nine-sided cookie badge, when and on which days you read, and three tiles
 * for the typical session, the trend and the share of listening.
 */
@Composable
internal fun HabitsCard(habits: Habits) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(FolioSpace.item),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.item),
            ) {
                ShapeBadge(
                    MaterialShapes.Cookie9Sided,
                    64.dp,
                    MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        habits.streakDays.toString(),
                        style =
                            cappedTextStyle(MaterialTheme.typography.headlineSmallEmphasized, 1.3f)
                                .copy(fontFamily = FontFamily.Default),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (habits.streakDays > 0)
                        Text(
                            pluralStringResource(
                                R.plurals.stats_streak_days,
                                habits.streakDays,
                                habits.streakDays,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    else
                        Text(
                            stringResource(R.string.stats_streak_none),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    Text(
                        if (habits.longestStreakDays > 1)
                            stringResource(
                                R.string.stats_streak_longest,
                                habits.longestStreakDays,
                                habits.longestStreakWhen,
                            )
                        else stringResource(R.string.stats_streak_none_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.stats_when_you_read),
                    style = MaterialTheme.typography.titleSmall,
                )
                HourChart(habits.byHour)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.stats_which_days),
                    style = MaterialTheme.typography.titleSmall,
                )
                WeekdayChart(habits.byWeekday)
            }
            val trend =
                when {
                    habits.trendPercent == null -> "—"
                    habits.trendPercent > 0 -> "+${habits.trendPercent}%"
                    else -> "${habits.trendPercent}%"
                }
            StatusStrip(
                tiles =
                    listOf(
                        StatusTile(
                            Icons.Outlined.Timer,
                            stringResource(R.string.stats_tile_session),
                            durationText(habits.typicalSessionSeconds),
                            stringResource(
                                R.string.stats_tile_session_detail,
                                compactDurationText(habits.longestSessionSeconds),
                            ),
                            "stats-tile-session",
                        ),
                        StatusTile(
                            Icons.Outlined.TrendingUp,
                            stringResource(R.string.stats_tile_trend),
                            trend,
                            habits.previousRangeName,
                            "stats-tile-trend",
                        ),
                        StatusTile(
                            Icons.Outlined.Headphones,
                            stringResource(R.string.stats_tile_listening),
                            durationText(habits.listeningSeconds),
                            stringResource(R.string.stats_share_of_time, habits.listeningShare),
                            "stats-tile-listening",
                        ),
                    ),
                onClick = {},
            )
        }
    }
}

/** The books read in the range as a row of covers with their time, longest first. */
@Composable
internal fun BooksRow(books: List<RangeBook>, onBook: (Book) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpace.gap)) {
        Box(Modifier.padding(horizontal = FolioSpace.page)) {
            SectionHeading(
                stringResource(R.string.stats_books),
                stringResource(R.string.stats_books_line),
            )
        }
        if (books.isEmpty())
            Text(
                stringResource(R.string.stats_books_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = FolioSpace.page),
            )
        else {
            val sorted = books.sortedByDescending { it.seconds }
            HorizontalUncontainedCarousel(
                state = rememberCarouselState { sorted.size },
                itemWidth = 104.dp,
                itemSpacing = FolioSpace.gap,
                contentPadding = PaddingValues(horizontal = FolioSpace.page),
            ) { i ->
                val entry = sorted[i]
                val time = durationText(entry.seconds)
                val description = stringResource(R.string.stats_book_a11y, entry.book.title, time)
                // Padding inside the clip, so the rounded corner never cuts the label's first
                // glyph.
                Column(
                    Modifier.width(104.dp)
                        .clip(FolioSpace.tileShape)
                        .clickable(role = Role.Button) { onBook(entry.book) }
                        .semantics(mergeDescendants = true) { contentDescription = description }
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CoverTile(entry.book, Modifier.fillMaxWidth(), variant = CoverVariant.Shelf)
                    Text(time, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

/** The badge for each kind of milestone: a Material shape and an icon. */
private fun milestoneShape(kind: MilestoneKind): RoundedPolygon =
    when (kind) {
        MilestoneKind.FIRST_FINISH -> MaterialShapes.Flower
        MilestoneKind.BOOKS -> MaterialShapes.Cookie9Sided
        MilestoneKind.HOURS -> MaterialShapes.Sunny
        MilestoneKind.STREAK -> MaterialShapes.SoftBurst
        MilestoneKind.FASTEST -> MaterialShapes.Gem
        MilestoneKind.BIGGEST_DAY -> MaterialShapes.VerySunny
        MilestoneKind.FIRST_LISTEN -> MaterialShapes.Clover4Leaf
    }

private fun milestoneIcon(kind: MilestoneKind): ImageVector =
    when (kind) {
        MilestoneKind.FIRST_FINISH -> Icons.Outlined.AutoStories
        MilestoneKind.BOOKS -> Icons.Outlined.LibraryBooks
        MilestoneKind.HOURS -> Icons.Outlined.Schedule
        MilestoneKind.STREAK -> Icons.Outlined.LocalFireDepartment
        MilestoneKind.FASTEST -> Icons.Outlined.Bolt
        MilestoneKind.BIGGEST_DAY -> Icons.Outlined.WbSunny
        MilestoneKind.FIRST_LISTEN -> Icons.Outlined.Headphones
    }

/** Milestones as a multi-browse carousel of badge cards, newest first. */
@Composable
internal fun MilestonesRow(milestones: List<Milestone>) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpace.gap)) {
        Box(Modifier.padding(horizontal = FolioSpace.page)) {
            SectionHeading(stringResource(R.string.stats_milestones))
        }
        if (milestones.isEmpty())
            Text(
                stringResource(R.string.stats_milestones_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = FolioSpace.page),
            )
        else
            HorizontalMultiBrowseCarousel(
                state = rememberCarouselState { milestones.size },
                preferredItemWidth = 156.dp,
                itemSpacing = FolioSpace.small,
                contentPadding = PaddingValues(horizontal = FolioSpace.page),
            ) { i ->
                val m = milestones[i]
                Surface(
                    Modifier.height(176.dp).maskClip(FolioSpace.panelShape),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(
                        Modifier.padding(FolioSpace.item).semantics(mergeDescendants = true) {},
                        verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
                    ) {
                        ShapeBadge(
                            milestoneShape(m.kind),
                            56.dp,
                            MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Icon(
                                milestoneIcon(m.kind),
                                null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                        // A small item in the multi-browse layout shows its badge only: the text
                        // fades out below 120 dp, instead of being cut mid-word by the mask.
                        val minTextWidth = with(LocalDensity.current) { 120.dp.toPx() }
                        Column(
                            Modifier.graphicsLayer {
                                alpha = if (carouselItemDrawInfo.size < minTextWidth) 0f else 1f
                            },
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                m.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                m.detail,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                m.whenText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
    }
}

/** One year: the number in serif, its facts, and Share. Tap opens the year in review. */
@Composable
internal fun YearRow(year: YearSummary, onYear: (Int) -> Unit, onShare: (Int) -> Unit) {
    val open = stringResource(R.string.stats_open_year, year.year)
    Surface(
        Modifier.fillMaxWidth().clip(FolioSpace.panelShape).clickable(
            role = Role.Button,
            onClickLabel = open,
        ) {
            onYear(year.year)
        },
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(
                start = FolioSpace.item,
                end = FolioSpace.small,
                top = FolioSpace.small,
                bottom = FolioSpace.small,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.item),
        ) {
            Text(
                year.year.toString(),
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (year.fromHardcover)
                        stringResource(R.string.stats_year_hardcover, year.books)
                    else
                        stringResource(
                            R.string.stats_year_line,
                            year.books,
                            durationText(year.seconds),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!year.fromHardcover && year.longestStreak > 1)
                    Text(
                        stringResource(
                            R.string.stats_streak_longest,
                            year.longestStreak,
                            year.year.toString(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            val share = stringResource(R.string.stats_share)
            Tooltipped(share) {
                FilledTonalIconButton(onClick = { onShare(year.year) }) {
                    Icon(Icons.Outlined.Share, share)
                }
            }
        }
    }
}

/** The goal offer while no goal is set: one panel, one button. */
@Composable
internal fun GoalsOffer(onGoals: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(FolioSpace.item),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.item),
        ) {
            Icon(Icons.Outlined.Flag, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.stats_goal_set_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.stats_goal_set_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onGoals) { Text(stringResource(R.string.stats_goal_set)) }
        }
    }
}

// ----- Sheets -----

/** The Day sheet: the day's total, its sessions through the day, and a row per book. */
@Composable
internal fun DayDetailsContent(day: DayDetails, onBook: (Book) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = FolioSpace.item),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        SheetHeading(
            day.title,
            stringResource(
                R.string.stats_joined,
                durationText(day.totalSeconds),
                pluralStringResource(
                    R.plurals.stats_sessions_count,
                    day.sessions.size,
                    day.sessions.size,
                ),
            ),
        )
        DayStrip(day.sessions, Modifier.padding(horizontal = FolioSpace.section))
        Column(Modifier.padding(horizontal = FolioSpace.section)) {
            day.sessions
                .groupBy { it.book.id }
                .values
                .sortedByDescending { group -> group.sumOf { it.seconds } }
                .forEach { group ->
                    val first = group.first()
                    val sources = group.map { it.source }.distinct().joinToString(" · ")
                    val duration = durationText(group.sumOf { it.seconds })
                    val pages = group.sumOf { it.pages ?: 0 }
                    ResultRow(
                        first.book.title,
                        decode(first.book.authors).joinToString(", "),
                        detail =
                            if (pages > 0)
                                stringResource(
                                    R.string.stats_joined,
                                    duration,
                                    stringResource(
                                        R.string.stats_joined,
                                        sources,
                                        stringResource(R.string.stats_day_pages, pages),
                                    ),
                                )
                            else stringResource(R.string.stats_joined, duration, sources),
                        onClick = { onBook(first.book) },
                        trailing = {
                            Box(
                                Modifier.size(12.dp)
                                    .clip(CircleShape)
                                    .background(StatsPalette.book(first.slot))
                            )
                        },
                    )
                }
        }
    }
}

/** The Goals sheet: a switch and a stepper per goal, Save, and a way to turn goals off. */
@Composable
internal fun GoalsSheetContent(
    goals: Goals,
    onGoals: (Goals) -> Unit,
    onSave: () -> Unit,
    onOff: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = FolioSpace.section),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        SheetHeading(
            stringResource(R.string.stats_goals),
            stringResource(R.string.stats_goals_body),
        )
        GoalRow(
            stringResource(R.string.stats_goal_books_year),
            goals.booksPerYear,
            default = 12,
            step = 1,
            min = 1,
        ) {
            onGoals(goals.copy(booksPerYear = it))
        }
        GoalRow(
            stringResource(R.string.stats_goal_minutes_day),
            goals.minutesPerDay,
            default = 20,
            step = 5,
            min = 5,
        ) {
            onGoals(goals.copy(minutesPerDay = it))
        }
        Column(
            Modifier.padding(horizontal = FolioSpace.section),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.stats_save))
            }
            if (goals.on)
                TextButton(onClick = onOff, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.stats_goals_off))
                }
        }
    }
}

/** One goal: its switch, and while on, a stepper with Fewer and More. */
@Composable
private fun GoalRow(
    title: String,
    value: Int?,
    default: Int,
    step: Int,
    min: Int,
    onValue: (Int?) -> Unit,
) {
    Column(
        Modifier.padding(horizontal = FolioSpace.section),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = value != null,
                onCheckedChange = { onValue(if (it) default else null) },
            )
        }
        if (value != null)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.item),
            ) {
                val fewer = stringResource(R.string.stats_fewer)
                val more = stringResource(R.string.stats_more)
                FilledTonalIconButton(
                    onClick = { onValue((value - step).coerceAtLeast(min)) },
                    enabled = value > min,
                ) {
                    Icon(Icons.Outlined.Remove, fewer)
                }
                Text(
                    value.toString(),
                    style =
                        MaterialTheme.typography.headlineSmallEmphasized.copy(
                            fontFamily = FontFamily.Default
                        ),
                    modifier = Modifier.width(72.dp),
                    textAlign = TextAlign.Center,
                )
                FilledTonalIconButton(onClick = { onValue(value + step) }) {
                    Icon(Icons.Outlined.Add, more)
                }
            }
    }
}

// ----- Per book -----

/** A value over its label, three to a row, inside the book's stats card. */
@Composable
private fun MiniStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Eyebrow(label)
    }
}

/** "248 wpm", or the dash when there is no pace yet. */
@Composable
private fun paceText(wordsPerMinute: Int?): String =
    wordsPerMinute?.let { stringResource(R.string.stats_wpm, it) }
        ?: stringResource(R.string.stats_no_value)

/**
 * The line on a book's progress: the days it took, the time left at your pace, or the days so far.
 * The card and the sheet heading both show it.
 */
@Composable
private fun bookProgressLine(stats: BookStats): String {
    val finished = stats.finishedText
    val days = stats.days
    val secondsLeft = stats.secondsLeft
    return when {
        finished != null && days != null -> stringResource(R.string.stats_book_finished_in, days)
        finished != null -> stringResource(R.string.stats_book_finished, finished)
        secondsLeft != null ->
            stringResource(R.string.stats_book_time_left, durationText(secondsLeft))
        else -> stringResource(R.string.stats_book_days_so_far, days ?: 0)
    }
}

/**
 * The card on the book page: three numbers, the progress timeline, and the time left at your pace
 * or the days it took. The whole card opens the book's stats sheet.
 */
@Composable
internal fun BookStatsCard(stats: BookStats, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description =
        stringResource(
            R.string.stats_book_card_a11y,
            durationText(stats.totalSeconds),
            stats.sessions,
            paceText(stats.wordsPerMinute),
        )
    val stacked = LocalDensity.current.fontScale > 1.3f
    Surface(
        modifier
            .fillMaxWidth()
            .clip(FolioSpace.panelShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(FolioSpace.item),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(stringResource(R.string.stats_book_stats), Modifier.weight(1f))
                Icon(
                    Icons.Outlined.ChevronRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val stats3 =
                listOf(
                    durationText(stats.totalSeconds) to
                        stringResource(R.string.stats_book_tile_time),
                    stats.sessions.toString() to stringResource(R.string.stats_book_tile_sessions),
                    paceText(stats.wordsPerMinute) to stringResource(R.string.stats_book_tile_pace),
                )
            if (stacked)
                Column(verticalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
                    stats3.forEach { (v, l) -> MiniStat(v, l) }
                }
            else
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
                    stats3.forEach { (v, l) -> MiniStat(v, l, Modifier.weight(1f)) }
                }
            BookTimeline(
                stats.timeline,
                plotHeight = 56.dp,
                startLabel = stats.startedText,
                endLabel = stats.finishedText ?: stringResource(R.string.stats_calendar_today),
            )
            Text(
                bookProgressLine(stats),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A label on the left and a value on the right, in a sheet. */
@Composable
private fun FactLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The book's stats sheet: the tiles, a taller timeline, where and when, and the latest sessions.
 */
@Composable
internal fun BookStatsContent(stats: BookStats) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = FolioSpace.section),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        SheetHeading(
            stats.book.title,
            stringResource(
                R.string.stats_joined,
                durationText(stats.totalSeconds),
                bookProgressLine(stats),
            ),
        )
        val side = Modifier.padding(horizontal = FolioSpace.section)
        StatusStrip(
            tiles =
                listOf(
                    StatusTile(
                        Icons.Outlined.Timer,
                        stringResource(R.string.stats_book_tile_time),
                        durationText(stats.readingSeconds),
                        null,
                        "book-stats-time",
                    ),
                    StatusTile(
                        Icons.Outlined.CalendarToday,
                        stringResource(R.string.stats_book_tile_sessions),
                        stats.sessions.toString(),
                        stringResource(R.string.stats_book_started, stats.startedText),
                        "book-stats-sessions",
                    ),
                    StatusTile(
                        Icons.Outlined.Speed,
                        stringResource(R.string.stats_book_tile_pace),
                        paceText(stats.wordsPerMinute),
                        stats.averageWordsPerMinute?.let {
                            stringResource(R.string.stats_book_pace_detail, it)
                        },
                        "book-stats-pace",
                    ),
                ),
            onClick = {},
            modifier = side,
        )
        Column(side, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.stats_book_timeline),
                style = MaterialTheme.typography.titleSmall,
            )
            BookTimeline(
                stats.timeline,
                plotHeight = 128.dp,
                startLabel = stats.startedText,
                endLabel = stats.finishedText ?: stringResource(R.string.stats_calendar_today),
            )
        }
        Column(side) {
            stats.secondsLeft?.let {
                FactLine(stringResource(R.string.stats_book_time_left, durationText(it)), "")
            }
            Text(
                stringResource(R.string.stats_book_by_device),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = FolioSpace.small),
            )
            stats.byDevice.forEach { (name, seconds) -> FactLine(name, durationText(seconds)) }
        }
        Column(side) {
            Text(
                stringResource(R.string.stats_book_recent_sessions),
                style = MaterialTheme.typography.titleSmall,
            )
            if (stats.recent.isEmpty())
                Text(
                    stringResource(R.string.stats_book_no_sessions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            stats.recent.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        val pages = s.pages
                        Text(
                            if (pages != null && pages > 0)
                                stringResource(
                                    R.string.stats_book_session_row,
                                    s.source,
                                    durationText(s.seconds),
                                    pages,
                                )
                            else
                                stringResource(
                                    R.string.stats_joined,
                                    s.source,
                                    durationText(s.seconds),
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        hourLabel(s.startHour.toInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ----- Year in review -----

/** The year in review: the share card at the top, then the year's sections as panels. */
@Composable
internal fun YearReviewScreen(
    review: YearReview,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onBook: (Book) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.stats_year_review_title, review.year)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.shell_back))
                }
            },
            actions = {
                val share = stringResource(R.string.stats_share)
                Tooltipped(share) {
                    IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, share) }
                }
            },
        )
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FolioSpace.page)
                .padding(bottom = LocalContentBottomPadding.current + FolioSpace.section),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
        ) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Lifted, like a cover: it is the object that gets shared.
                ScaledShareCard(
                    review,
                    (maxWidth * 0.72f).coerceAtMost(300.dp),
                    Modifier.align(Alignment.Center).then(lift(FolioSpace.panelShape)),
                )
            }
            Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Share, null)
                Spacer(Modifier.width(FolioSpace.small))
                Text(stringResource(R.string.stats_share_image))
            }
            ReviewPanel(stringResource(R.string.stats_year_top_books)) {
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap)) {
                    review.topBooks.take(3).forEach { entry ->
                        Column(
                            Modifier.weight(1f)
                                .clip(FolioSpace.tileShape)
                                .clickable(role = Role.Button) { onBook(entry.book) }
                                .padding(4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CoverTile(entry.book, Modifier.fillMaxWidth())
                            Text(
                                durationText(entry.seconds),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            ReviewPanel(stringResource(R.string.stats_year_by_month)) { MonthBars(review.byMonth) }
            ReviewPanel(stringResource(R.string.stats_year_records)) {
                review.records.forEach { (label, value) -> FactLine(label, value) }
            }
            ReviewPanel(stringResource(R.string.stats_year_firsts)) {
                review.firsts.forEach { (label, value) -> FactLine(label, value) }
            }
        }
    }
}

@Composable
private fun ReviewPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(FolioSpace.item),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

/** The card's design size: 360 × 640 dp, which at density 3 is the 1080 × 1920 image. */
internal val shareCardWidth = 360.dp
internal val shareCardHeight = 640.dp

/**
 * [YearShareCard] drawn [width] wide: the density is scaled so the card's 360 dp fill exactly that
 * width. The same wrapper, with density 3, renders the share image.
 */
@Composable
internal fun ScaledShareCard(review: YearReview, width: Dp, modifier: Modifier = Modifier) {
    val base = LocalDensity.current
    val factor = width / shareCardWidth
    Box(
        modifier
            .width(width)
            .aspectRatio(shareCardWidth / shareCardHeight)
            .clip(FolioSpace.panelShape)
    ) {
        CompositionLocalProvider(LocalDensity provides Density(base.density * factor, 1f)) {
            YearShareCard(review)
        }
    }
}

/**
 * The shareable year in review, 360 × 640 dp: the theme's surface with a soft primary glow, the
 * year in serif, the books and hours, the top three covers fanned, four facts, the books by month
 * and the Librillo wordmark. Rendered with the current colour scheme, so it matches the phone.
 */
@Composable
internal fun YearShareCard(review: YearReview, modifier: Modifier = Modifier) {
    val glow = MaterialTheme.colorScheme.primaryContainer
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier.size(shareCardWidth, shareCardHeight).background(surface)) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(glow.copy(alpha = 0.85f), glow.copy(alpha = 0f)),
                    center = Offset(size.width * 0.2f, size.height * 0.12f),
                    radius = size.width * 0.9f,
                ),
                radius = size.width * 0.9f,
                center = Offset(size.width * 0.2f, size.height * 0.12f),
            )
        }
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow(stringResource(R.string.stats_share_eyebrow))
                Text(
                    review.year.toString(),
                    style =
                        MaterialTheme.typography.displayLarge.copy(fontFamily = FontFamily.Serif),
                )
                Text(
                    stringResource(
                        R.string.stats_year_hero_line,
                        review.books,
                        ((review.readingSeconds + review.listeningSeconds) / 3600).toInt(),
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
                val top = review.topBooks.take(3)
                val angles = listOf(-10f, 0f, 10f)
                val offsets = listOf((-72).dp, 0.dp, 72.dp)
                val lifts = listOf(12.dp, 0.dp, 12.dp)
                // The middle cover is drawn last, so it sits in front.
                listOf(0, 2, 1).forEach { i ->
                    top.getOrNull(i)?.let { entry ->
                        CoverTile(
                            entry.book,
                            Modifier.width(104.dp)
                                .offset(x = offsets[i], y = lifts[i])
                                .rotate(angles[i]),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.item)) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
                ) {
                    MiniStat(
                        stringResource(R.string.stats_share_days, review.longestStreak),
                        stringResource(R.string.stats_share_streak),
                    )
                    MiniStat(
                        paceText(review.wordsPerMinute),
                        stringResource(R.string.stats_share_pace),
                    )
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
                ) {
                    MiniStat(review.favouriteHour, stringResource(R.string.stats_share_hour))
                    MiniStat(
                        durationText(review.listeningSeconds),
                        stringResource(R.string.stats_share_listening),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow(stringResource(R.string.stats_year_by_month))
                MonthBars(review.byMonth, plotHeight = 56.dp)
            }
            Text(
                stringResource(R.string.stats_share_footer),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
