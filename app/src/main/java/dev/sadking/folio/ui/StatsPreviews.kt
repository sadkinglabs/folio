@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.BookSignals
import dev.sadking.folio.data.CurrentBook
import dev.sadking.folio.ui.stats.*

/*
 * M12 Stats mockups: the Stats tab and its sheets with fake data, as the visual reference for the
 * implementation. Nothing here is reachable from the app. The data types are the placeholders in
 * ui/stats/StatsModels.kt until the statistics core lands.
 */

private val statsBooks: List<Book> =
    sampleBooks +
        listOf(
            coverStub("Salt and Iron", "[\"Ines Marlow\"]"),
            coverStub("Night Trains", "[\"Tom Aldous\"]"),
            coverStub("The Long Winter", "[\"Hanna Vik\"]"),
        )

private const val MIN = 60L
private const val HOUR = 3600L

/** Minutes read per book per day in October 2026, days 1 to 17; later days are empty. */
private val octoberDays: List<CalendarDay> =
    (1..31).map { day ->
        val bars =
            when (day) {
                1 -> listOf(DayBar("sample1", 0, 48 * MIN))
                2 -> listOf(DayBar("sample1", 0, 35 * MIN), DayBar("sample2", 1, 20 * MIN))
                3 -> listOf(DayBar("sample1", 0, 72 * MIN))
                4 ->
                    listOf(
                        DayBar("sample1", 0, 110 * MIN),
                        DayBar("sample3", 2, 25 * MIN, listening = true),
                    )
                5 -> emptyList()
                6 -> listOf(DayBar("sample2", 1, 30 * MIN))
                7 ->
                    listOf(
                        DayBar("sample1", 0, 48 * MIN),
                        DayBar("sample2", 1, 20 * MIN),
                        DayBar("sample3", 2, 16 * MIN, listening = true),
                    )
                8 -> listOf(DayBar("sample1", 0, 40 * MIN))
                9 ->
                    listOf(
                        DayBar("sample1", 0, 25 * MIN),
                        DayBar("cover:Salt and Iron", 3, 45 * MIN),
                    )
                10 -> listOf(DayBar("cover:Salt and Iron", 3, 95 * MIN))
                11 ->
                    listOf(
                        DayBar("cover:Salt and Iron", 3, 120 * MIN),
                        DayBar("sample3", 2, 30 * MIN, listening = true),
                    )
                12 -> emptyList()
                13 -> listOf(DayBar("cover:Salt and Iron", 3, 22 * MIN))
                14 ->
                    listOf(
                        DayBar("cover:Night Trains", 4, 55 * MIN),
                        DayBar("sample1", 0, 18 * MIN),
                    )
                15 ->
                    listOf(
                        DayBar("cover:Night Trains", 4, 64 * MIN),
                        DayBar("sample2", 1, 12 * MIN),
                        DayBar("sample3", 2, 20 * MIN, listening = true),
                        DayBar("cover:The Long Winter", 5, 15 * MIN),
                    )
                16 -> listOf(DayBar("cover:Night Trains", 4, 38 * MIN))
                17 ->
                    listOf(
                        DayBar("cover:Night Trains", 4, 26 * MIN),
                        DayBar("sample1", 0, 30 * MIN),
                    )
                else -> emptyList()
            }
        CalendarDay(day, bars)
    }

private val octoberBooks: List<CalendarBook> =
    listOf(
        CalendarBook(statsBooks[0], 0, 426 * MIN),
        CalendarBook(statsBooks[1], 1, 82 * MIN),
        CalendarBook(statsBooks[2], 2, 91 * MIN),
        CalendarBook(statsBooks[3], 3, 282 * MIN),
        CalendarBook(statsBooks[4], 4, 183 * MIN),
        CalendarBook(statsBooks[5], 5, 15 * MIN),
    )

/** October 2026 starts on a Thursday; the preview's today is the 17th. */
internal val sampleMonth =
    CalendarMonth(
        "October 2026",
        firstWeekday = 3,
        days = octoberDays,
        books = octoberBooks,
        today = 17,
    )

private val monthColumns: List<TrendColumn> = octoberDays.map { d ->
    TrendColumn(
        d.day.toString(),
        d.bars.filter { !it.listening }.sumOf { it.seconds },
        d.bars.filter { it.listening }.sumOf { it.seconds },
        labelled = d.day == 1 || d.day % 7 == 1,
    )
}

/** The week of 11 to 17 October, labelled by weekday initial. */
private val weekColumns: List<TrendColumn> =
    monthColumns.subList(10, 17).mapIndexed { i, c ->
        c.copy(label = listOf("S", "M", "T", "W", "T", "F", "S")[i], labelled = true)
    }

internal val sampleOverview =
    StatsOverview(
        range = StatsRange.MONTH,
        readingSeconds =
            octoberDays.sumOf { d -> d.bars.filter { !it.listening }.sumOf { it.seconds } },
        listeningSeconds =
            octoberDays.sumOf { d -> d.bars.filter { it.listening }.sumOf { it.seconds } },
        daysRead = octoberDays.count { it.bars.isNotEmpty() },
        daysInRange = 31,
        sessions = 42,
        wordsPerMinute = 248,
        trendPercent = 12,
        previousRangeName = "September",
        columns = monthColumns,
    )

private val byHour: List<Long> =
    listOf(2, 0, 0, 0, 0, 3, 18, 42, 25, 6, 4, 8, 22, 10, 4, 3, 6, 12, 20, 38, 64, 110, 140, 48)
        .map {
            it * MIN
        }

internal val sampleHabits =
    Habits(
        streakDays = 6,
        longestStreakDays = 23,
        longestStreakWhen = "March 2026",
        byHour = byHour,
        byWeekday = listOf(72, 95, 60, 110, 48, 140, 210).map { it * MIN },
        typicalSessionSeconds = 24 * MIN,
        longestSessionSeconds = 110 * MIN,
        trendPercent = 12,
        previousRangeName = "September",
        listeningShare = 12,
        listeningSeconds = 91 * MIN,
    )

internal val sampleRangeBooks = octoberBooks.map { RangeBook(it.book, it.seconds) }

internal val sampleMilestones =
    listOf(
        Milestone(MilestoneKind.STREAK, "Longest streak", "23 days", "March 2026"),
        Milestone(MilestoneKind.HOURS, "100 hours", "Reading time", "12 August 2026"),
        Milestone(MilestoneKind.BIGGEST_DAY, "Biggest day", "4 h 10 min", "11 October 2026"),
        Milestone(MilestoneKind.BOOKS, "10 books", "This year", "3 July 2026"),
        Milestone(
            MilestoneKind.FASTEST,
            "Fastest finish",
            "Salt and Iron in 3 days",
            "13 October 2026",
        ),
        Milestone(
            MilestoneKind.FIRST_LISTEN,
            "First listen",
            "A Field Guide to Elsewhere",
            "4 October 2026",
        ),
        Milestone(
            MilestoneKind.FIRST_FINISH,
            "First book finished",
            "The Orchard",
            "9 February 2026",
        ),
    )

internal val sampleYears =
    listOf(
        YearSummary(2026, 17, 142 * HOUR, 23, fromHardcover = false),
        YearSummary(2025, 31, 0, 0, fromHardcover = true),
        YearSummary(2024, 24, 0, 0, fromHardcover = true),
    )

internal val goalsOff = Goals(null, null, 17, 19, 14)
internal val goalsOn =
    Goals(
        booksPerYear = 24,
        minutesPerDay = 30,
        booksSoFar = 17,
        booksOnPace = 19,
        minutesToday = 14,
    )

internal val sampleStats =
    StatsState.Ready(
        overview = sampleOverview,
        month = sampleMonth,
        habits = sampleHabits,
        books = sampleRangeBooks,
        milestones = sampleMilestones,
        years = sampleYears,
        goals = goalsOff,
    )

internal val sampleDay =
    DayDetails(
        day = 7,
        title = "Tuesday 7 October",
        totalSeconds = 84 * MIN,
        sessions =
            listOf(
                SessionRow(statsBooks[1], 1, 7.5f, 20 * MIN, 12, "Phone"),
                SessionRow(statsBooks[2], 2, 13.0f, 16 * MIN, 6, "Listening", listening = true),
                SessionRow(statsBooks[0], 0, 22.2f, 48 * MIN, 22, "Kindle"),
            ),
    )

internal val sampleBookStats =
    BookStats(
        book = statsBooks[0],
        readingSeconds = 4 * HOUR + 12 * MIN,
        listeningSeconds = 25 * MIN,
        sessions = 11,
        startedText = "3 Oct",
        finishedText = null,
        days = 9,
        wordsPerMinute = 248,
        averageWordsPerMinute = 240,
        secondsLeft = 2 * HOUR + 30 * MIN,
        progress = 0.64f,
        timeline =
            listOf(
                TimelinePoint(0f, 0.04f, false),
                TimelinePoint(0.4f, 0.09f, false),
                TimelinePoint(1.2f, 0.17f, false),
                TimelinePoint(2.1f, 0.22f, true),
                TimelinePoint(3.0f, 0.34f, false),
                TimelinePoint(3.6f, 0.38f, false),
                TimelinePoint(5.2f, 0.46f, false),
                TimelinePoint(6.1f, 0.52f, true),
                TimelinePoint(7.0f, 0.58f, false),
                TimelinePoint(8.3f, 0.61f, false),
                TimelinePoint(9.0f, 0.64f, false),
            ),
        byDevice =
            listOf(
                "Kindle" to 3 * HOUR + 5 * MIN,
                "Phone" to 1 * HOUR + 7 * MIN,
                "Listening" to 25 * MIN,
            ),
        recent =
            listOf(
                SessionRow(statsBooks[0], 0, 22f, 48 * MIN, 22, "Kindle"),
                SessionRow(statsBooks[0], 0, 7f, 18 * MIN, 8, "Phone"),
                SessionRow(statsBooks[0], 0, 21f, 72 * MIN, 31, "Kindle"),
                SessionRow(statsBooks[0], 0, 13f, 25 * MIN, 0, "Listening", listening = true),
                SessionRow(statsBooks[0], 0, 22f, 35 * MIN, 15, "Kindle"),
            ),
    )

internal val sampleYearReview =
    YearReview(
        year = 2026,
        books = 17,
        readingSeconds = 130 * HOUR,
        listeningSeconds = 12 * HOUR,
        topBooks =
            listOf(
                RangeBook(statsBooks[0], 14 * HOUR),
                RangeBook(statsBooks[3], 11 * HOUR),
                RangeBook(statsBooks[4], 9 * HOUR),
            ),
        byMonth = listOf(1, 2, 1, 2, 1, 2, 1, 2, 1, 3, 1, 0),
        longestStreak = 23,
        favouriteHour = "10 pm",
        wordsPerMinute = 248,
        records =
            listOf(
                "Longest session" to "2 h 40 min",
                "Biggest day" to "4 h 10 min",
                "Longest streak" to "23 days",
                "Fastest finish" to "Salt and Iron · 3 days",
            ),
        firsts =
            listOf(
                "First book finished" to "The Orchard · 9 Feb",
                "First listen" to "A Field Guide to Elsewhere · 4 Oct",
                "First 100 hours" to "12 Aug",
            ),
    )

private val previewActions = StatsActions()

@Preview(name = "Stats · light", showBackground = true, widthDp = 412, heightDp = 2000)
@Preview(
    name = "Stats · dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 2000,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun StatsPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            StatsTab(sampleStats, StatsRange.MONTH, previewActions)
        }
    }
}

/** Goals on, and a column of the trend picked, so the bubble shows. */
@Preview(name = "Stats · goals", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
internal fun StatsGoalsPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            StatsTab(
                sampleStats.copy(goals = goalsOn),
                StatsRange.MONTH,
                previewActions,
                selectedTrend = 10,
            )
        }
    }
}

@Preview(name = "Stats · phone only", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
internal fun StatsPhoneOnlyPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            StatsTab(
                sampleStats.copy(
                    overview =
                        sampleOverview.copy(
                            range = StatsRange.WEEK,
                            columns = weekColumns,
                            readingSeconds = weekColumns.sumOf { it.readingSeconds },
                            listeningSeconds = weekColumns.sumOf { it.listeningSeconds },
                            daysRead = weekColumns.count { it.totalSeconds > 0 },
                            daysInRange = 7,
                            sessions = 13,
                            trendPercent = null,
                        ),
                    phoneOnly = true,
                ),
                StatsRange.WEEK,
                previewActions,
            )
        }
    }
}

@Preview(name = "Stats · empty", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
internal fun StatsEmptyPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            StatsTab(StatsState.Empty, StatsRange.MONTH, previewActions)
        }
    }
}

@Preview(name = "Stats · loading", showBackground = true, widthDp = 412, heightDp = 600)
@Composable
internal fun StatsLoadingPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            StatsTab(StatsState.Loading, StatsRange.MONTH, previewActions)
        }
    }
}

@Preview(name = "Stats · day sheet", widthDp = 412, heightDp = 520)
@Composable
internal fun StatsDayPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(top = FolioSpace.item)) {
                DayDetailsContent(sampleDay, onBook = {})
            }
        }
    }
}

@Preview(name = "Stats · goals sheet", widthDp = 412, heightDp = 560)
@Composable
internal fun StatsGoalsSheetPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(top = FolioSpace.item)) {
                GoalsSheetContent(goalsOn, onGoals = {}, onSave = {}, onOff = {})
            }
        }
    }
}

/** The book page with the stats card under the status strip, where the design places it. */
@Preview(name = "Book · stats card", showBackground = true, widthDp = 412, heightDp = 1400)
@Composable
internal fun DetailsStatsPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            BookDetails(
                sampleBooks[0],
                lists = listOf("Slow Sundays"),
                signals = BookSignals("Reading · phone", 0.64, null),
                fileMissing = false,
                changedOutside = false,
                extra = { BookStatsCard(sampleBookStats, onClick = {}) },
            )
        }
    }
}

@Preview(name = "Book · stats sheet", widthDp = 412, heightDp = 1100)
@Composable
internal fun BookStatsSheetPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(top = FolioSpace.item)) { BookStatsContent(sampleBookStats) }
        }
    }
}

@Preview(name = "Year in review", showBackground = true, widthDp = 412, heightDp = 1500)
@Composable
internal fun YearReviewPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            YearReviewScreen(sampleYearReview, onBack = {}, onShare = {})
        }
    }
}

/** The share image at its design size: 360 × 640 dp, which renders at density 3 as 1080 × 1920. */
@Preview(name = "Year share card", widthDp = 360, heightDp = 640)
@Composable
internal fun YearShareCardPreview() {
    FolioTheme(dynamic = false) { YearShareCard(sampleYearReview) }
}

/**
 * The Stats tab in the tablet frame: the calendar and the chosen day on the left, the dashboard on
 * the right. The rail shows no selected tab, as the Stats item is not in it yet.
 */
@Preview(name = "Stats · tablet", widthDp = 840, heightDp = 1280)
@Preview(name = "Stats · tablet landscape", widthDp = 1280, heightDp = 800)
@Preview(name = "Stats · foldable", widthDp = 700, heightDp = 840)
@Composable
internal fun TabletStatsPreview() = TabletStats(hinge = false)

@Preview(name = "Stats · foldable, half open", widthDp = 700, heightDp = 840)
@Composable
internal fun FoldStatsPreview() = TabletStats(hinge = true)

@Composable
private fun TabletStats(hinge: Boolean) {
    val measured = rememberFolioWindow()
    val window =
        if (hinge) folioWindow(measured.widthDp, measured.heightDp, measured.widthDp / 2)
        else measured
    CompositionLocalProvider(LocalFolioWindow provides window) {
        FolioTheme(dynamic = false) {
            Surface(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    if (window.layout != FolioLayout.BAR)
                        FolioRail(
                            tab = -1,
                            onTab = {},
                            current = CurrentBook(sampleBooks[0], 0.37, PREVIEW_NOW),
                            onContinue = {},
                            onOptions = {},
                            deviceBadge = false,
                        )
                    Box(Modifier.weight(1f)) {
                        StatsTab(
                            sampleStats,
                            StatsRange.MONTH,
                            previewActions,
                            selectedDay = sampleDay,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The phone bar with the Stats tab added: six equal slots, the Now Reading tile over the third. A
 * mock of the proposed layout, not the real [FolioBar], for the owner to judge the tile's position;
 * see STATS_UI.md for the alternative.
 */
@Preview(name = "Bar with Stats", widthDp = 412, heightDp = 160)
@Composable
internal fun StatsBarPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val slot = maxWidth / 6
                    GlassSurface(
                        null,
                        Modifier.padding(top = 10.dp).fillMaxWidth(),
                        shape = FolioSpace.sheetShape,
                    ) {
                        NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                            val items =
                                listOf(
                                    Triple(
                                        R.string.shell_tab_library,
                                        Icons.Outlined.AutoStories,
                                        Icons.Filled.AutoStories,
                                    ),
                                    Triple(
                                        R.string.shell_tab_lists,
                                        Icons.Outlined.CollectionsBookmark,
                                        Icons.Filled.CollectionsBookmark,
                                    ),
                                    Triple(
                                        R.string.stats_tab,
                                        Icons.Outlined.Insights,
                                        Icons.Filled.Insights,
                                    ),
                                    Triple(
                                        R.string.shell_tab_device,
                                        Icons.Outlined.Devices,
                                        Icons.Filled.Devices,
                                    ),
                                    Triple(
                                        R.string.shell_settings,
                                        Icons.Outlined.Tune,
                                        Icons.Filled.Tune,
                                    ),
                                )
                            items.forEachIndexed { i, (title, icon, selectedIcon) ->
                                if (i == 2) Spacer(Modifier.weight(1f))
                                NavigationBarItem(
                                    selected = i == 2,
                                    onClick = {},
                                    icon = { Icon(if (i == 2) selectedIcon else icon, null) },
                                    label = {
                                        Text(
                                            stringResource(title),
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    NowReadingTile(
                        CurrentBook(sampleBooks[0], 0.37, PREVIEW_NOW),
                        onClick = {},
                        onOptions = {},
                        modifier =
                            Modifier.align(Alignment.TopStart)
                                .offset(x = slot * 2.5f - nowReadingTileSize / 2),
                    )
                }
            }
        }
    }
}
