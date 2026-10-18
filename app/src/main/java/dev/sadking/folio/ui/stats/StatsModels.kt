package dev.sadking.folio.ui.stats

import dev.sadking.folio.data.Book

/*
 * The Stats screens' own models. `StatsUi` (StatsUi.kt) fills them from a `StatsSnapshot`; previews
 * fill them by hand.
 */

/** The range the hero and the habits scope to. The calendar pages by month on its own. */
internal enum class StatsRange {
    WEEK,
    MONTH,
    YEAR,
    ALL,
}

/** One column of the trend chart: a day, a month or a year, with reading and listening apart. */
internal data class TrendColumn(
    val label: String,
    val readingSeconds: Long,
    val listeningSeconds: Long,
    /** The axis label is drawn under this column (every column in a week; every 7th in a month). */
    val labelled: Boolean = true,
) {
    val totalSeconds: Long
        get() = readingSeconds + listeningSeconds
}

/** The hero: the range's total and its three tiles. */
internal data class StatsOverview(
    val range: StatsRange,
    val readingSeconds: Long,
    val listeningSeconds: Long,
    val daysRead: Int,
    val daysInRange: Int,
    val sessions: Int,
    /** Null when no pace is known yet. */
    val wordsPerMinute: Int?,
    /** Change against the previous range, in percent; null when there is nothing to compare. */
    val trendPercent: Int?,
    val previousRangeName: String,
    val columns: List<TrendColumn>,
) {
    val totalSeconds: Long
        get() = readingSeconds + listeningSeconds
}

/** One book's bar in a calendar day: its palette slot and how long it was read. */
internal data class DayBar(
    val bookId: String,
    val slot: Int,
    val seconds: Long,
    val listening: Boolean = false,
)

internal data class CalendarDay(val day: Int, val bars: List<DayBar>) {
    val totalSeconds: Long
        get() = bars.sumOf { it.seconds }
}

/** A book named in the calendar's legend. [slot] is stable for the book across months. */
internal data class CalendarBook(val book: Book, val slot: Int, val seconds: Long)

internal data class CalendarMonth(
    val title: String,
    /** Monday = 0 … Sunday = 6: the weekday the 1st falls on. */
    val firstWeekday: Int,
    val days: List<CalendarDay>,
    val books: List<CalendarBook>,
    /** 1-based day of month, or null when the month is not the current one. */
    val today: Int?,
) {
    /** The longest one-book day of the month: a full-width bar. */
    val maxBarSeconds: Long
        get() = days.maxOfOrNull { d -> d.bars.maxOfOrNull { it.seconds } ?: 0L } ?: 0L
}

/** One session on the Day sheet's strip and in the per-book list. */
internal data class SessionRow(
    val book: Book,
    val slot: Int,
    val startHour: Float,
    val seconds: Long,
    /** Null when no page count is known. */
    val pages: Int?,
    val source: String,
    val listening: Boolean = false,
)

internal data class DayDetails(
    /** 1-based day of the shown month, for the grid's selected cell. */
    val day: Int,
    val title: String,
    val totalSeconds: Long,
    val sessions: List<SessionRow>,
)

internal data class Habits(
    val streakDays: Int,
    val longestStreakDays: Int,
    val longestStreakWhen: String,
    /** Seconds per hour of the day, 24 entries. */
    val byHour: List<Long>,
    /** Seconds per weekday, Monday first, 7 entries. */
    val byWeekday: List<Long>,
    val typicalSessionSeconds: Long,
    val longestSessionSeconds: Long,
    val trendPercent: Int?,
    val previousRangeName: String,
    val listeningShare: Int,
    val listeningSeconds: Long,
)

/** A book read in the range, with its time, for the Books carousel. */
internal data class RangeBook(val book: Book, val seconds: Long)

internal enum class MilestoneKind {
    FIRST_FINISH,
    BOOKS,
    HOURS,
    STREAK,
    FASTEST,
    BIGGEST_DAY,
    FIRST_LISTEN,
}

internal data class Milestone(
    val kind: MilestoneKind,
    val title: String,
    val detail: String,
    val whenText: String,
)

internal data class YearSummary(
    val year: Int,
    val books: Int,
    val seconds: Long,
    val longestStreak: Int,
    val fromHardcover: Boolean,
)

/** A progress point on the per-book timeline: days since the first session, and how far. */
internal data class TimelinePoint(val day: Float, val progress: Float, val listening: Boolean)

internal data class BookStats(
    val book: Book,
    val readingSeconds: Long,
    val listeningSeconds: Long,
    val sessions: Int,
    val startedText: String,
    val finishedText: String?,
    /** Null when the number of days is not known. */
    val days: Int?,
    /** Null when no pace is known yet. */
    val wordsPerMinute: Int?,
    /** Null when there is no average to show. */
    val averageWordsPerMinute: Int?,
    val secondsLeft: Long?,
    val progress: Float,
    val timeline: List<TimelinePoint>,
    val byDevice: List<Pair<String, Long>>,
    val recent: List<SessionRow>,
) {
    val totalSeconds: Long
        get() = readingSeconds + listeningSeconds
}

internal data class Goals(
    val booksPerYear: Int?,
    val minutesPerDay: Int?,
    val booksSoFar: Int,
    /** Books the pace says you should have finished by today. */
    val booksOnPace: Int,
    val minutesToday: Int,
) {
    val on: Boolean
        get() = booksPerYear != null || minutesPerDay != null
}

internal data class YearReview(
    val year: Int,
    val books: Int,
    val readingSeconds: Long,
    val listeningSeconds: Long,
    val topBooks: List<RangeBook>,
    /** Books finished per month, January first. */
    val byMonth: List<Int>,
    val longestStreak: Int,
    val favouriteHour: String,
    /** Null when no pace is known yet. */
    val wordsPerMinute: Int?,
    val records: List<Pair<String, String>>,
    val firsts: List<Pair<String, String>>,
)

/** What the Stats tab shows: everything at once, or one of its states. */
internal sealed interface StatsState {
    data object Loading : StatsState

    /** No reading on the phone or e-reader. Hardcover years, if any, still show. */
    data class Empty(val years: List<YearSummary> = emptyList()) : StatsState

    data class Failed(val retry: () -> Unit) : StatsState

    data class Ready(
        val overview: StatsOverview,
        val month: CalendarMonth,
        val habits: Habits,
        val books: List<RangeBook>,
        val milestones: List<Milestone>,
        val years: List<YearSummary>,
        val goals: Goals,
        /** An e-reader is paired but has not sent its history yet. */
        val syncPending: Boolean = false,
        /** Only phone reading so far: the Kindle has sent nothing. */
        val phoneOnly: Boolean = false,
    ) : StatsState
}
