@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui.stats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.StatsLoad
import dev.sadking.folio.StatsViewModel
import dev.sadking.folio.core.stats.GoalSettings
import dev.sadking.folio.data.StatsSnapshot
import dev.sadking.folio.ui.FolioMotion
import dev.sadking.folio.ui.FolioSheet
import dev.sadking.folio.ui.FolioSpace
import dev.sadking.folio.ui.LocalFolioWindow
import dev.sadking.folio.ui.rememberHaptics
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Stats tab with its state: the view model's load, the range, the shown month, the chosen day
 * and the Goals sheet. [onYear] opens a year in review (true: straight to its share image). [toTop]
 * counts taps on the Stats tab while it is already shown: each one takes the dashboard back to the
 * top.
 */
@Composable
internal fun StatsRoute(
    model: StatsViewModel,
    onOpenBook: (String) -> Unit,
    onYear: (year: Int, share: Boolean) -> Unit,
    onLibrary: () -> Unit,
    toTop: Int = 0,
) {
    val load by model.load.collectAsStateWithLifecycle()
    StatsRouteContent(
        load,
        onSaveGoals = model::saveGoals,
        onRetry = model::retry,
        onOpenBook = onOpenBook,
        onYear = onYear,
        onLibrary = onLibrary,
        toTop = toTop,
    )
}

/**
 * [StatsRoute] without a view model. The range, month, chosen trend column, chosen day and the
 * Goals sheet survive a rotation. The mapping runs off the main thread; the last screen stays until
 * the next is ready.
 */
@Composable
internal fun StatsRouteContent(
    load: StatsLoad,
    onSaveGoals: (GoalSettings) -> Unit,
    onRetry: () -> Unit,
    onOpenBook: (String) -> Unit,
    onYear: (year: Int, share: Boolean) -> Unit,
    onLibrary: () -> Unit,
    toTop: Int = 0,
    listState: LazyListState = rememberLazyListState(),
) {
    val text = rememberStatsText()
    var rangeName by rememberSaveable { mutableStateOf(StatsRange.MONTH.name) }
    // "2026-10"; null is the current month, so the tab follows the calendar past a month's end.
    var monthText by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTrend by rememberSaveable { mutableStateOf<Int?>(null) }
    var dayText by rememberSaveable { mutableStateOf<String?>(null) }
    var showGoals by rememberSaveable { mutableStateOf(false) }
    // A Year column picked a month: the dashboard goes to the calendar once it shows that month.
    var calendarMonth by remember { mutableStateOf<YearMonth?>(null) }
    val range = StatsRange.valueOf(rangeName)
    val retry by rememberUpdatedState(onRetry)

    // The last snapshot, for the sheets and the taps: it stays through a reload.
    val snapshot by
        produceState<StatsSnapshot?>(null, load) {
            if (load is StatsLoad.Ready) value = load.snapshot
        }
    val today = snapshot?.stats?.today
    val thisMonth = today?.let { YearMonth.from(it) }
    val shownMonth = monthText?.let { YearMonth.parse(it) } ?: thisMonth

    val state by
        produceState<StatsState>(StatsState.Loading, load, range, monthText, text) {
            when (load) {
                is StatsLoad.Ready ->
                    value =
                        withContext(Dispatchers.Default) {
                            val stats = load.snapshot.stats
                            val month =
                                monthText?.let { YearMonth.parse(it) }
                                    ?: YearMonth.from(stats.today)
                            StatsUi.state(load.snapshot, range, month, text) { retry() }
                        }
                StatsLoad.Failed -> value = StatsState.Failed { retry() }
                StatsLoad.Loading -> Unit
            }
        }

    val twoPane = LocalFolioWindow.current.twoPane
    // Tapping the tab again: back to the top, animated unless the system removes animations.
    val reduced = FolioMotion.reduced
    var tops by remember { mutableIntStateOf(toTop) }
    LaunchedEffect(toTop) {
        if (toTop != tops) {
            tops = toTop
            if (reduced) listState.scrollToItem(0) else listState.animateScrollToItem(0)
        }
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state, calendarMonth) {
        val month = calendarMonth ?: return@LaunchedEffect
        if ((state as? StatsState.Ready)?.month?.yearMonth == month) {
            calendarMonth = null
            // Two panes keep the calendar in the left pane, so there is nothing to scroll to.
            // Clearing the request ends this effect, so the scroll runs in the screen's scope.
            if (!twoPane)
                scope.launch {
                    val index = statsCalendarIndex(showTitle = true)
                    if (reduced) listState.scrollToItem(index)
                    else listState.animateScrollToItem(index)
                }
        }
    }

    fun showMonth(month: YearMonth) {
        monthText = month.takeIf { it != thisMonth }?.toString()
    }

    fun showDay(date: LocalDate) {
        showMonth(YearMonth.from(date))
        dayText = date.toString()
    }

    val actions =
        StatsActions(
            onRange = {
                rangeName = it.name
                selectedTrend = null
                calendarMonth = null
            },
            onTrend = { index ->
                selectedTrend = index
                val snap = snapshot
                // The columns belong to the chart on screen: right after a range change it can
                // still be the old range's chart until the new one is ready.
                val shown = (state as? StatsState.Ready)?.overview?.range
                if (index != null && snap != null && shown != null) {
                    val stats = snap.stats
                    when (shown) {
                        StatsRange.WEEK,
                        StatsRange.MONTH ->
                            StatsUi.columnDate(stats, shown, index)
                                ?.takeIf { !it.isAfter(stats.today) }
                                ?.let(::showDay)
                        StatsRange.YEAR ->
                            (index + 1)
                                .takeIf { it in 1..12 }
                                ?.let { YearMonth.of(stats.today.year, it) }
                                ?.takeIf { it <= YearMonth.from(stats.today) }
                                ?.let {
                                    showMonth(it)
                                    calendarMonth = it
                                }
                        StatsRange.ALL ->
                            onYear(StatsUi.rangeDates(stats, shown).start.year + index, false)
                    }
                }
            },
            onPreviousMonth = { shownMonth?.let { showMonth(it.minusMonths(1)) } },
            onNextMonth = {
                shownMonth?.let { month ->
                    if (thisMonth != null && month < thisMonth) showMonth(month.plusMonths(1))
                }
            },
            onDay = { day -> shownMonth?.let { showDay(it.atDay(day.day)) } },
            onBook = { onOpenBook(it.id) },
            onYear = { onYear(it, false) },
            onShareYear = { onYear(it, true) },
            onGoals = { showGoals = true },
            onLibrary = onLibrary,
        )

    val snap = snapshot
    val dayDate = dayText?.let { LocalDate.parse(it) }
    // Two panes show the day under the calendar, while the calendar shows its month.
    val dayDetails =
        if (snap != null && dayDate != null)
            remember(snap, dayDate, text) { StatsUi.day(snap, dayDate, text) }
        else null
    val selectedDay = dayDetails.takeIf {
        twoPane && dayDate != null && YearMonth.from(dayDate) == shownMonth
    }

    Box(Modifier.fillMaxSize().testTag(STATS_TAG)) {
        StatsTab(state, range, actions, selectedTrend, selectedDay, listState)
    }

    if (!twoPane && dayDetails != null)
        FolioSheet(
            onDismissRequest = { dayText = null },
            shape = FolioSpace.sheetShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            DayDetailsContent(
                dayDetails,
                onBook = {
                    dayText = null
                    onOpenBook(it.id)
                },
            )
        }

    if (showGoals && snap != null) {
        val haptics = rememberHaptics()
        var draft by remember { mutableStateOf(StatsUi.goals(snap)) }
        FolioSheet(
            onDismissRequest = { showGoals = false },
            shape = FolioSpace.sheetShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            GoalsSheetContent(
                draft,
                onGoals = {
                    haptics.tick()
                    draft = it
                },
                onSave = {
                    haptics.confirm()
                    onSaveGoals(draft.toSettings())
                    showGoals = false
                },
                onOff = {
                    onSaveGoals(GoalSettings())
                    showGoals = false
                },
            )
        }
    }
}
