package dev.sadking.folio.core.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Where a session was recorded. docs/STATS_DESIGN.md (a) has what each one fills. */
enum class SessionSource {
    /** KOReader on an e-reader, derived from its page statistics ([KoreaderSessions]). */
    KINDLE,

    /** Librillo's reader on the phone ([SessionTracker]). */
    PHONE,

    /** Read-aloud on the phone ([SessionTracker]). Never added to reading time. */
    LISTEN;

    val isReading: Boolean
        get() = this != LISTEN
}

/**
 * One stretch of reading or listening. The record the data pipeline stores and the screens read.
 * - [id]: stable across repeated syncs; the same id is the same session (an upsert, never a copy).
 * - [deviceKey]: a `DeviceKeys` key (`ereader:<id>`, `phone:<id>`).
 * - [bookId]: the library book id when known; otherwise [document], KOReader's partial MD5 (32
 *   lowercase hex). At least one is set.
 * - [startMs]: epoch milliseconds. [durationSec]: seconds that count, idle already removed.
 * - [progressStart], [progressEnd]: where in the book it started and ended, 0..1, when known.
 * - [progressRead]: how much of the book was actually read in it, 0..1, jumps left out. Words read
 *   are `progressRead × book words`.
 * - [pages]: distinct pages seen (KOReader only).
 */
data class ReadingSession(
    val id: String,
    val source: SessionSource,
    val deviceKey: String,
    val bookId: String?,
    val document: String?,
    val startMs: Long,
    val durationSec: Long,
    val progressStart: Double? = null,
    val progressEnd: Double? = null,
    val progressRead: Double? = null,
    val pages: Int? = null,
) {
    /** The key the statistics group this session under: [bookId], else `doc:` + [document]. */
    val book: String
        get() = bookId ?: (DOCUMENT_PREFIX + document.orEmpty())

    val endMs: Long
        get() = startMs + durationSec * 1000

    /** Usable at all: a book, a positive duration of at most a day, a start after 1970. */
    internal val valid: Boolean
        get() =
            (!bookId.isNullOrBlank() || !document.isNullOrBlank()) &&
                startMs > 0 &&
                durationSec in 1..MAX_SESSION_SECONDS
}

/** The book key prefix for a KOReader document no library book matches yet. */
const val DOCUMENT_PREFIX = "doc:"

/** The longest session believed: a day. Longer ones are ignored as broken. */
const val MAX_SESSION_SECONDS = 86_400L

/**
 * What the statistics know about one book besides its sessions. [key] is the same key as
 * [ReadingSession.book]: a library book id, a catalogue entry id, or `doc:<md5>`. [words] is the
 * book's word count from its EPUB ([EpubWords]), null when unknown. [progress] is where the owner
 * is now (0..1), null when unknown; time left uses it.
 */
data class StatsBook(val key: String, val words: Long? = null, val progress: Double? = null)

enum class FinishSource {
    /** The book's status became Finished in Librillo (or from an e-reader), on that day. */
    LIBRARY,

    /** A Hardcover read with a finished date. */
    HARDCOVER,
}

/** A book finished on [date] (local). The same book can be finished more than once (re-reads). */
data class BookFinish(val book: String, val date: LocalDate, val source: FinishSource)

/** The owner's goals. Null is off. Both off until the owner signs up. */
data class GoalSettings(val booksPerYear: Int? = null, val minutesPerDay: Int? = null) {
    val enabled: Boolean
        get() = booksPerYear != null || minutesPerDay != null
}

/** Days [start] to [endInclusive], both included. */
data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    val days: Int
        get() = (endInclusive.toEpochDay() - start.toEpochDay() + 1).toInt().coerceAtLeast(0)

    operator fun contains(date: LocalDate) = date >= start && date <= endInclusive

    companion object {
        fun of(year: Int) = DateRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))

        fun of(month: YearMonth) = DateRange(month.atDay(1), month.atEndOfMonth())

        /** The [days] days ending with [end]. */
        fun last(days: Int, end: LocalDate) = DateRange(end.minusDays(days - 1L), end)
    }
}

/** Consecutive reading days, [start] to [end] included. */
data class Streak(val start: LocalDate, val end: LocalDate) {
    val days: Int
        get() = (end.toEpochDay() - start.toEpochDay() + 1).toInt()
}

/** The headline numbers at the top of the Stats tab. Reading is Kindle plus phone. */
data class Overview(
    val todaySeconds: Long,
    val weekSeconds: Long,
    val monthSeconds: Long,
    val yearSeconds: Long,
    val totalSeconds: Long,
    val listeningTodaySeconds: Long,
    val listeningTotalSeconds: Long,
    /** The streak that includes today or yesterday; null when neither was a reading day. */
    val currentStreak: Streak?,
    val longestStreak: Streak?,
    val booksFinishedThisYear: Int,
    val speed: Wpm?,
    /** The first day with reading or listening; null when there is none. */
    val firstDay: LocalDate?,
    /** When the newest session ended (epoch ms); null when there is none. */
    val lastActivityMs: Long?,
)

/** The book colour slot for books past the eighth in one month: the "Other" colour. */
const val OTHER_SLOT = 8

/** How many books in one month get a colour of their own. */
const val BOOK_SLOTS = 8

/** One book on one day: its colour [slot] in that month, and the seconds read and listened. */
data class DayBook(
    val book: String,
    val slot: Int,
    val readingSeconds: Long,
    val listeningSeconds: Long,
)

/**
 * One calendar cell. [books] are in slot order, the same order every day of the month. [finished]
 * are the books finished that day.
 */
data class CalendarDay(
    val date: LocalDate,
    val readingSeconds: Long,
    val listeningSeconds: Long,
    val books: List<DayBook>,
    val finished: List<String>,
)

/** A book in a month, with its colour [slot] (0..7, or [OTHER_SLOT]). */
data class MonthBook(
    val book: String,
    val slot: Int,
    val readingSeconds: Long,
    val listeningSeconds: Long,
)

/** A month of the calendar: [days] has every day of the month, empty ones included. */
data class CalendarMonth(
    val month: YearMonth,
    val days: List<CalendarDay>,
    val books: List<MonthBook>,
    val readingSeconds: Long,
    val listeningSeconds: Long,
    val readingDays: Int,
)

/**
 * The part of one session that falls on one day: [startMs] to [endMs] of counted time, [seconds] of
 * it. A session across midnight shows on both days.
 */
data class DaySession(
    val session: ReadingSession,
    val startMs: Long,
    val endMs: Long,
    val seconds: Long,
    val slot: Int,
)

/**
 * A tapped day: its books (same slots as its month), its sessions in time order, and the books
 * finished that day.
 */
data class DayDetail(
    val date: LocalDate,
    val readingSeconds: Long,
    val listeningSeconds: Long,
    val books: List<DayBook>,
    val sessions: List<DaySession>,
    val finished: List<String>,
)

/** Reading on one weekday over a range: [seconds] in all, over [occurrences] such days. */
data class WeekdayTotal(val day: DayOfWeek, val seconds: Long, val occurrences: Int) {
    val averageSeconds: Long
        get() = if (occurrences == 0) 0 else seconds / occurrences
}

/**
 * Session lengths, glances under a minute left out. [buckets] count sessions under 5 minutes, 5 to
 * 15, 15 to 30, 30 to 60, and an hour or more ([SESSION_BUCKETS_MINUTES]).
 */
data class SessionLengths(
    val count: Int,
    val medianSeconds: Long,
    val meanSeconds: Long,
    val longest: ReadingSession?,
    val buckets: List<Int>,
)

/** Bucket edges in minutes for [SessionLengths.buckets]. */
val SESSION_BUCKETS_MINUTES = listOf(5, 15, 30, 60)

/** Reading habits over a range. Reading only, except the listening total. */
data class Habits(
    val range: DateRange,
    val readingSeconds: Long,
    val listeningSeconds: Long,
    val readingDays: Int,
    /** Reading seconds over reading days; 0 when none. */
    val averagePerReadingDay: Long,
    /** 24 values: seconds read in each local hour of the day, 0 to 23. */
    val hours: List<Long>,
    /** Monday to Sunday. */
    val weekdays: List<WeekdayTotal>,
    val sessions: SessionLengths,
    val speed: Wpm?,
)

/** A reading speed from [sessions] sessions: [words] in [seconds]. */
data class Wpm(val wordsPerMinute: Int, val words: Long, val seconds: Long, val sessions: Int)

/** Speeds by source. [reading] is Kindle and phone together. */
data class Speed(val reading: Wpm?, val kindle: Wpm?, val phone: Wpm?, val listening: Wpm?)

/** One day of one book: seconds, and where the book stood at the end of the day when known. */
data class BookDay(
    val date: LocalDate,
    val readingSeconds: Long,
    val listeningSeconds: Long,
    val progress: Double?,
)

/** Everything about one book. */
data class BookStats(
    val book: String,
    val readingSeconds: Long,
    val listeningSeconds: Long,
    val sessions: Int,
    val listeningSessions: Int,
    val firstDay: LocalDate?,
    val lastDay: LocalDate?,
    val readingDays: Int,
    /** Every finish, oldest first. */
    val finishes: List<LocalDate>,
    /** Days from the first reading day to the latest finish; null unless fully tracked. */
    val daysToFinish: Int?,
    val speed: Wpm?,
    /** Seconds left at the owner's pace; null when finished or unknown. */
    val timeLeftSeconds: Long?,
    val timeline: List<BookDay>,
)

/** Reading per period. [start] to [endInclusive] is a week or a month. */
data class PeriodTotal(
    val start: LocalDate,
    val endInclusive: LocalDate,
    val readingSeconds: Long,
    val listeningSeconds: Long,
    val booksFinished: Int,
)

enum class MilestoneKind {
    /** The first day with reading. [Milestone.threshold] is 1. */
    FIRST_READ,

    /** Total reading reached [Milestone.threshold] hours. */
    HOURS,

    /** [Milestone.threshold] books finished (dated finishes). */
    BOOKS,

    /** A streak reached [Milestone.threshold] days. */
    STREAK,
}

/** The thresholds each kind of milestone has, in order. */
val MILESTONE_THRESHOLDS: Map<MilestoneKind, List<Int>> =
    mapOf(
        MilestoneKind.FIRST_READ to listOf(1),
        MilestoneKind.HOURS to listOf(1, 10, 50, 100, 250, 500, 1000),
        MilestoneKind.BOOKS to listOf(1, 10, 25, 50, 100, 250, 500),
        MilestoneKind.STREAK to listOf(7, 30, 100, 365),
    )

/** A milestone reached on [date]. [book] is the book that reached it, when one did. */
data class Milestone(
    val kind: MilestoneKind,
    val threshold: Int,
    val date: LocalDate,
    val book: String?,
)

/** The next milestone of a kind: [current] of [threshold] (hours, books or streak days). */
data class UpcomingMilestone(val kind: MilestoneKind, val threshold: Int, val current: Long)

data class Milestones(val reached: List<Milestone>, val next: List<UpcomingMilestone>)

data class DayRecord(val date: LocalDate, val seconds: Long)

data class SessionRecord(val session: ReadingSession, val date: LocalDate)

/** A finished book: [value] is its words, or its days to finish. */
data class FinishRecord(val book: String, val date: LocalDate, val value: Long)

data class YearCount(val year: Int, val count: Int)

data class MonthCount(val month: YearMonth, val count: Int)

/** Personal bests. Ties go to the earliest. */
data class Records(
    val longestStreak: Streak?,
    val mostInADay: DayRecord?,
    val longestSession: SessionRecord?,
    val longestBookFinished: FinishRecord?,
    val fastestFinish: FinishRecord?,
    val mostBooksInAYear: YearCount?,
    val mostBooksInAMonth: MonthCount?,
    /** Every year from the first finish to this year, zeros included. */
    val booksPerYear: List<YearCount>,
)

/** A book and its reading seconds. */
data class BookTime(val book: String, val readingSeconds: Long)

/** A year in review. [isEmpty] when the year has no reading, listening or finish. */
data class YearInReview(
    val year: Int,
    val readingSeconds: Long,
    val listeningSeconds: Long,
    val readingDays: Int,
    val booksFinished: List<BookFinish>,
    /** The five books read longest, longest first. */
    val topBooks: List<BookTime>,
    /** Twelve values, January to December. */
    val months: List<Long>,
    val longestStreak: Streak?,
    val mostInADay: DayRecord?,
    val longestSession: SessionRecord?,
    val busiestMonth: YearMonth?,
    val busiestWeekday: DayOfWeek?,
    val peakHour: Int?,
    val speed: Wpm?,
    val wordsRead: Long,
    val milestones: List<Milestone>,
    val previousYearReadingSeconds: Long,
) {
    val isEmpty: Boolean
        get() = readingSeconds == 0L && listeningSeconds == 0L && booksFinished.isEmpty()
}

/** Progress on the yearly books goal for [year]. */
data class BooksGoal(
    val year: Int,
    val target: Int,
    val finished: Int,
    /** Books a steady pace would have finished by today. */
    val expectedByToday: Double,
) {
    val met: Boolean
        get() = finished >= target

    /** Books ahead of a steady pace (negative: behind). */
    val ahead: Double
        get() = finished - expectedByToday
}

/** One day against the daily goal. */
data class GoalDay(val date: LocalDate, val seconds: Long, val met: Boolean)

/** Progress on the daily minutes goal. Reading only. */
data class DailyGoal(
    val targetMinutes: Int,
    val todaySeconds: Long,
    /** The last 7 days, oldest first, today last. */
    val recent: List<GoalDay>,
    val daysMetThisWeek: Int,
    val daysMetThisMonth: Int,
    /** Consecutive days met, ending today or yesterday. */
    val streak: Streak?,
) {
    val metToday: Boolean
        get() = todaySeconds >= targetMinutes * 60L
}

/** Goal progress; each part null while that goal is off. */
data class GoalProgress(val books: BooksGoal?, val daily: DailyGoal?)
