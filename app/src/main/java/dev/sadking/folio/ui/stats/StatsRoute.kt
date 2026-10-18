@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui.stats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import dev.sadking.folio.ui.FolioSheet
import dev.sadking.folio.ui.FolioSpace
import dev.sadking.folio.ui.LocalFolioWindow
import dev.sadking.folio.ui.rememberHaptics
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Stats tab with its state: the view model's load, the range, the shown month, the chosen day
 * and the Goals sheet. [onYear] opens a year in review (true: straight to its share image).
 */
@Composable
internal fun StatsRoute(
    model: StatsViewModel,
    onOpenBook: (String) -> Unit,
    onYear: (year: Int, share: Boolean) -> Unit,
    onLibrary: () -> Unit,
) {
    val load by model.load.collectAsStateWithLifecycle()
    StatsRouteContent(
        load,
        onSaveGoals = model::saveGoals,
        onRetry = model::retry,
        onOpenBook = onOpenBook,
        onYear = onYear,
        onLibrary = onLibrary,
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
) {
    val text = rememberStatsText()
    var rangeName by rememberSaveable { mutableStateOf(StatsRange.MONTH.name) }
    // "2026-10"; null is the current month, so the tab follows the calendar past a month's end.
    var monthText by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTrend by rememberSaveable { mutableStateOf<Int?>(null) }
    var dayText by rememberSaveable { mutableStateOf<String?>(null) }
    var showGoals by rememberSaveable { mutableStateOf(false) }
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
            },
            onTrend = { index ->
                selectedTrend = index
                val snap = snapshot
                if (index != null && snap != null) {
                    val stats = snap.stats
                    when (range) {
                        StatsRange.WEEK,
                        StatsRange.MONTH ->
                            StatsUi.columnDate(stats, range, index)
                                ?.takeIf { !it.isAfter(stats.today) }
                                ?.let(::showDay)
                        StatsRange.YEAR ->
                            YearMonth.of(stats.today.year, index + 1)
                                .takeIf { it <= YearMonth.from(stats.today) }
                                ?.let(::showMonth)
                        StatsRange.ALL ->
                            onYear(StatsUi.rangeDates(stats, range).start.year + index, false)
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
    val twoPane = LocalFolioWindow.current.twoPane
    // Two panes show the day under the calendar, while the calendar shows its month.
    val dayDetails =
        if (snap != null && dayDate != null)
            remember(snap, dayDate, text) { StatsUi.day(snap, dayDate, text) }
        else null
    val selectedDay = dayDetails.takeIf {
        twoPane && dayDate != null && YearMonth.from(dayDate) == shownMonth
    }

    Box(Modifier.fillMaxSize().testTag(STATS_TAG)) {
        StatsTab(state, range, actions, selectedTrend, selectedDay)
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
