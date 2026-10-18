package dev.sadking.folio.ui.stats

import dev.sadking.folio.R
import dev.sadking.folio.core.stats.DateRange
import dev.sadking.folio.core.stats.DaySession
import dev.sadking.folio.core.stats.FinishSource
import dev.sadking.folio.core.stats.GoalSettings
import dev.sadking.folio.core.stats.MilestoneKind as CoreMilestoneKind
import dev.sadking.folio.core.stats.ReadingSession
import dev.sadking.folio.core.stats.ReadingStats
import dev.sadking.folio.core.stats.SessionSource
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.StatsSnapshot
import dev.sadking.folio.data.encode
import dev.sadking.folio.ui.FolioDates
import dev.sadking.folio.ui.coverStub
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Turns one [StatsSnapshot] into the Stats screens' own models (StatsModels.kt). Plain functions of
 * their inputs: today is `snapshot.stats.today`, the zone `snapshot.stats.zone`, and words come
 * from a [StatsText]. Every number is the core's ([ReadingStats]); what lives here is the screens'
 * own rules: the range's dates, the trend, which milestones show, and the name of an unknown key.
 * docs/STATS_DESIGN.md (U2) has them.
 */
internal object StatsUi {
    private const val HARDCOVER_PREFIX = "hc:"

    /** The milestones that show, besides the first book (owner, 2026-10-18). */
    private val BOOK_BADGES = setOf(10, 25, 50, 100)
    private val HOUR_BADGES = setOf(100, 500, 1000)

    /** A streak shows as a record from two days: one reading day is not a streak. */
    private const val MIN_STREAK_RECORD = 2
    private const val RECENT_SESSIONS = 10
    private const val DAY_MS = 86_400_000f

    // ---- Ranges ----

    /** The dates a range covers: the whole week, month or year, or everything so far. */
    fun rangeDates(stats: ReadingStats, range: StatsRange): DateRange {
        val today = stats.today
        return when (range) {
            StatsRange.WEEK -> {
                val start = today.with(TemporalAdjusters.previousOrSame(stats.firstDayOfWeek))
                DateRange(start, start.plusDays(6))
            }
            StatsRange.MONTH -> DateRange.of(YearMonth.from(today))
            StatsRange.YEAR -> DateRange.of(today.year)
            StatsRange.ALL -> DateRange(stats.overview().firstDay ?: today, today)
        }
    }

    /**
     * The same number of days as have gone by in the range, one period back. Null for ALL: there is
     * nothing before it.
     */
    fun previousDates(stats: ReadingStats, range: StatsRange): DateRange? {
        val today = stats.today
        return when (range) {
            StatsRange.WEEK ->
                DateRange(rangeDates(stats, range).start.minusDays(7), today.minusDays(7))
            StatsRange.MONTH -> {
                val previous = YearMonth.from(today).minusMonths(1)
                val last = minOf(today.dayOfMonth, previous.lengthOfMonth())
                DateRange(previous.atDay(1), previous.atDay(last))
            }
            StatsRange.YEAR -> DateRange(LocalDate.of(today.year - 1, 1, 1), today.minusYears(1))
            StatsRange.ALL -> null
        }
    }

    /** The day a trend column stands for: WEEK and MONTH only, else null. */
    fun columnDate(stats: ReadingStats, range: StatsRange, index: Int): LocalDate? {
        val dates = rangeDates(stats, range)
        return when (range) {
            StatsRange.WEEK,
            StatsRange.MONTH ->
                dates.start.plusDays(index.toLong()).takeIf { index in 0 until dates.days }
            StatsRange.YEAR,
            StatsRange.ALL -> null
        }
    }

    // ---- The tab ----

    /**
     * What the Stats tab shows. Empty when no session was ever counted (Hardcover years still
     * show); otherwise everything at once. A mapping that throws gives Failed, so a bad row never
     * crashes the tab.
     */
    fun state(
        snapshot: StatsSnapshot,
        range: StatsRange,
        month: YearMonth,
        text: StatsText,
        retry: () -> Unit = {},
    ): StatsState =
        try {
            val stats = snapshot.stats
            if (stats.overview().firstDay == null) {
                StatsState.Empty(years(snapshot))
            } else {
                StatsState.Ready(
                    overview = overview(snapshot, range, text),
                    month = calendar(snapshot, month, text),
                    habits = habits(snapshot, range, text),
                    books = rangeBooks(snapshot, range, text),
                    milestones = milestones(snapshot, text),
                    years = years(snapshot),
                    goals = goals(snapshot),
                    syncPending = snapshot.waitingForEreader,
                    phoneOnly = !snapshot.waitingForEreader && !snapshot.hasEreaderSessions,
                )
            }
        } catch (_: RuntimeException) {
            StatsState.Failed(retry)
        }

    // ---- Hero ----

    fun overview(snapshot: StatsSnapshot, range: StatsRange, text: StatsText): StatsOverview {
        val stats = snapshot.stats
        val dates = rangeDates(stats, range)
        val habits = stats.habits(dates)
        return StatsOverview(
            range = range,
            readingSeconds = habits.readingSeconds,
            listeningSeconds = habits.listeningSeconds,
            daysRead = habits.readingDays,
            daysInRange = dates.days,
            sessions = habits.sessions.count,
            wordsPerMinute = habits.speed?.wordsPerMinute,
            trendPercent = trend(stats, range, habits.readingSeconds + habits.listeningSeconds),
            previousRangeName = previousRangeName(stats, range, text),
            columns = columns(stats, range, dates, text),
        )
    }

    /** The change against the previous range, in percent; null against nothing. */
    private fun trend(stats: ReadingStats, range: StatsRange, now: Long): Int? {
        val previous = previousDates(stats, range) ?: return null
        val before = stats.habits(previous).let { it.readingSeconds + it.listeningSeconds }
        if (before == 0L) return null
        return ((now - before) * 100.0 / before).roundToInt()
    }

    private fun previousRangeName(stats: ReadingStats, range: StatsRange, text: StatsText): String =
        when (range) {
            StatsRange.WEEK -> text.string(R.string.stats_last_week)
            StatsRange.MONTH ->
                YearMonth.from(stats.today)
                    .minusMonths(1)
                    .month
                    .getDisplayName(TextStyle.FULL_STANDALONE, text.locale)
            StatsRange.YEAR -> (stats.today.year - 1).toString()
            StatsRange.ALL -> ""
        }

    private fun columns(
        stats: ReadingStats,
        range: StatsRange,
        dates: DateRange,
        text: StatsText,
    ): List<TrendColumn> =
        when (range) {
            StatsRange.WEEK ->
                (0 until dates.days).map { i ->
                    val date = dates.start.plusDays(i.toLong())
                    val day = stats.day(date)
                    TrendColumn(
                        date.dayOfWeek.getDisplayName(TextStyle.NARROW_STANDALONE, text.locale),
                        day.readingSeconds,
                        day.listeningSeconds,
                    )
                }
            StatsRange.MONTH ->
                stats.month(YearMonth.from(stats.today)).days.map { day ->
                    val number = day.date.dayOfMonth
                    TrendColumn(
                        number.toString(),
                        day.readingSeconds,
                        day.listeningSeconds,
                        labelled = number % 7 == 1,
                    )
                }
            StatsRange.YEAR ->
                (1..12).map { m ->
                    val month = stats.month(YearMonth.of(stats.today.year, m))
                    TrendColumn(
                        Month.of(m).getDisplayName(TextStyle.NARROW_STANDALONE, text.locale),
                        month.readingSeconds,
                        month.listeningSeconds,
                    )
                }
            StatsRange.ALL ->
                (dates.start.year..dates.endInclusive.year).map { year ->
                    val review = stats.yearInReview(year)
                    TrendColumn(
                        year.toString(),
                        review.readingSeconds,
                        review.listeningSeconds,
                    )
                }
        }

    // ---- Calendar and day ----

    fun calendar(snapshot: StatsSnapshot, month: YearMonth, text: StatsText): CalendarMonth {
        val stats = snapshot.stats
        val core = stats.month(month)
        return CalendarMonth(
            title = month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", text.locale)),
            firstWeekday = month.atDay(1).dayOfWeek.value - 1,
            days =
                core.days.map { day ->
                    CalendarDay(
                        day.date.dayOfMonth,
                        day.books.map { b ->
                            DayBar(
                                bookId = b.book,
                                slot = b.slot,
                                seconds = b.readingSeconds + b.listeningSeconds,
                                listening = b.readingSeconds == 0L,
                            )
                        },
                    )
                },
            books =
                core.books.map { b ->
                    CalendarBook(
                        label(snapshot, b.book, text),
                        b.slot,
                        b.readingSeconds + b.listeningSeconds,
                    )
                },
            today = stats.today.dayOfMonth.takeIf { month == YearMonth.from(stats.today) },
        )
    }

    fun day(snapshot: StatsSnapshot, date: LocalDate, text: StatsText): DayDetails {
        val stats = snapshot.stats
        val core = stats.day(date)
        return DayDetails(
            day = date.dayOfMonth,
            title =
                if (date == stats.today) text.string(R.string.stats_calendar_today)
                else date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", text.locale)),
            totalSeconds = core.readingSeconds + core.listeningSeconds,
            sessions = core.sessions.map { sessionRow(snapshot, it, text) },
        )
    }

    private fun sessionRow(
        snapshot: StatsSnapshot,
        part: DaySession,
        text: StatsText,
    ): SessionRow {
        val start = Instant.ofEpochMilli(part.startMs).atZone(snapshot.stats.zone)
        return SessionRow(
            book = label(snapshot, part.session.book, text),
            slot = part.slot,
            startHour = start.hour + start.minute / 60f,
            seconds = part.seconds,
            pages = part.session.pages,
            source = source(snapshot, part.session, text),
            listening = part.session.source == SessionSource.LISTEN,
        )
    }

    /** Where a session was recorded, in words: Listening, Phone, or the e-reader's own name. */
    fun source(snapshot: StatsSnapshot, session: ReadingSession, text: StatsText): String =
        when {
            session.source == SessionSource.LISTEN -> text.string(R.string.stats_source_listening)
            session.deviceKey.startsWith("phone:") -> text.string(R.string.stats_source_phone)
            else ->
                snapshot.deviceNames[session.deviceKey]
                    ?: text.string(R.string.stats_source_ereader)
        }

    /**
     * The book a key stands for, for a cover and a title. A library book is itself; any other key
     * gets a stub with the label's name, else "A book on your e-reader" or, for a Hardcover key, "A
     * book from Hardcover". The stub's id is always the stats [key], so a tap can open it.
     */
    fun label(snapshot: StatsSnapshot, key: String, text: StatsText): Book {
        val label = snapshot.labels[key]
        label?.book?.let {
            return it
        }
        val title =
            label?.title?.takeIf { it.isNotBlank() }
                ?: text.string(
                    if (key.startsWith(HARDCOVER_PREFIX)) R.string.stats_unknown_hardcover_book
                    else R.string.stats_unknown_book
                )
        val authors = label?.authors.orEmpty()
        return coverStub(
                title,
                if (authors.isBlank()) "[]" else encode(listOf(authors)),
                label?.coverPath,
            )
            .copy(id = key)
    }

    // ---- Habits and books ----

    fun habits(snapshot: StatsSnapshot, range: StatsRange, text: StatsText): Habits {
        val stats = snapshot.stats
        val overview = stats.overview()
        val core = stats.habits(rangeDates(stats, range))
        val total = core.readingSeconds + core.listeningSeconds
        return Habits(
            streakDays = overview.currentStreak?.days ?: 0,
            longestStreakDays = overview.longestStreak?.days ?: 0,
            longestStreakWhen =
                overview.longestStreak
                    ?.end
                    ?.format(DateTimeFormatter.ofPattern("LLLL yyyy", text.locale))
                    .orEmpty(),
            byHour = core.hours,
            byWeekday = core.weekdays.map { it.seconds },
            typicalSessionSeconds = core.sessions.medianSeconds,
            longestSessionSeconds = core.sessions.longest?.durationSec ?: 0,
            trendPercent = trend(stats, range, total),
            previousRangeName = previousRangeName(stats, range, text),
            listeningShare = if (total == 0L) 0 else (core.listeningSeconds * 100 / total).toInt(),
            listeningSeconds = core.listeningSeconds,
        )
    }

    /** The books with time in the range, longest first. */
    fun rangeBooks(snapshot: StatsSnapshot, range: StatsRange, text: StatsText): List<RangeBook> {
        val stats = snapshot.stats
        val dates = rangeDates(stats, range)
        return stats
            .books()
            .map { book ->
                book.book to
                    book.timeline
                        .filter { it.date in dates }
                        .sumOf { it.readingSeconds + it.listeningSeconds }
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { (key, seconds) -> RangeBook(label(snapshot, key, text), seconds) }
    }

    // ---- Milestones and years ----

    /** The badges, newest first. What shows and what doesn't is the owner's call (2026-10-18). */
    fun milestones(snapshot: StatsSnapshot, text: StatsText): List<Milestone> {
        val stats = snapshot.stats
        val shown = mutableListOf<Pair<LocalDate, Milestone>>()
        fun add(date: LocalDate, kind: MilestoneKind, title: String, detail: String) {
            shown += date to Milestone(kind, title, detail, FolioDates.day(date))
        }
        stats.milestones().reached.forEach { m ->
            when {
                m.kind == CoreMilestoneKind.BOOKS && m.threshold == 1 ->
                    add(
                        m.date,
                        MilestoneKind.FIRST_FINISH,
                        text.string(R.string.stats_milestone_first_finish),
                        m.book?.let { label(snapshot, it, text).title }.orEmpty(),
                    )
                m.kind == CoreMilestoneKind.BOOKS && m.threshold in BOOK_BADGES ->
                    add(
                        m.date,
                        MilestoneKind.BOOKS,
                        text.string(R.string.stats_milestone_books, m.threshold),
                        text.string(R.string.stats_eyebrow_all),
                    )
                m.kind == CoreMilestoneKind.HOURS && m.threshold in HOUR_BADGES ->
                    add(
                        m.date,
                        MilestoneKind.HOURS,
                        text.string(R.string.stats_milestone_hours, m.threshold),
                        text.string(R.string.stats_milestone_reading_time),
                    )
                m.kind == CoreMilestoneKind.STREAK ->
                    add(
                        m.date,
                        MilestoneKind.STREAK,
                        text.plural(R.plurals.stats_streak_days, m.threshold, m.threshold),
                        text.string(R.string.stats_milestone_streak_reached),
                    )
            }
        }
        val records = stats.records()
        records.longestStreak
            ?.takeIf { it.days >= MIN_STREAK_RECORD }
            ?.let {
                add(
                    it.end,
                    MilestoneKind.STREAK,
                    text.string(R.string.stats_milestone_streak),
                    text.string(R.string.stats_share_days, it.days),
                )
            }
        records.fastestFinish?.let {
            add(
                it.date,
                MilestoneKind.FASTEST,
                text.string(R.string.stats_milestone_fastest),
                text.string(
                    R.string.stats_milestone_fastest_detail,
                    label(snapshot, it.book, text).title,
                    it.value.toInt(),
                ),
            )
        }
        records.mostInADay?.let {
            add(
                it.date,
                MilestoneKind.BIGGEST_DAY,
                text.string(R.string.stats_milestone_biggest_day),
                text.duration(it.seconds),
            )
        }
        stats
            .books()
            .mapNotNull { book ->
                book.timeline.firstOrNull { it.listeningSeconds > 0 }?.let { book.book to it.date }
            }
            .minWithOrNull(compareBy({ it.second }, { it.first }))
            ?.let { (key, date) ->
                add(
                    date,
                    MilestoneKind.FIRST_LISTEN,
                    text.string(R.string.stats_milestone_first_listen),
                    label(snapshot, key, text).title,
                )
            }
        return shown.sortedByDescending { it.first }.map { it.second }
    }

    /** One row per year with any reading, listening or finish, newest first. */
    fun years(snapshot: StatsSnapshot): List<YearSummary> {
        val stats = snapshot.stats
        val years = sortedSetOf(reverseOrder<Int>())
        stats.records().booksPerYear.forEach { years += it.year }
        stats.overview().firstDay?.let { first -> years += first.year..stats.today.year }
        return years.mapNotNull { year ->
            val review = stats.yearInReview(year)
            if (review.isEmpty) return@mapNotNull null
            val seconds = review.readingSeconds + review.listeningSeconds
            YearSummary(
                year = year,
                books = review.booksFinished.size,
                seconds = seconds,
                longestStreak = review.longestStreak?.days ?: 0,
                fromHardcover =
                    seconds == 0L &&
                        review.booksFinished.all { it.source == FinishSource.HARDCOVER },
            )
        }
    }

    // ---- Goals ----

    fun goals(snapshot: StatsSnapshot): Goals {
        val stats = snapshot.stats
        val progress = stats.goals(snapshot.goals)
        val overview = stats.overview()
        return Goals(
            booksPerYear = snapshot.goals.booksPerYear,
            minutesPerDay = snapshot.goals.minutesPerDay,
            booksSoFar = progress.books?.finished ?: overview.booksFinishedThisYear,
            booksOnPace = progress.books?.let { floor(it.expectedByToday).toInt() } ?: 0,
            minutesToday = ((progress.daily?.todaySeconds ?: overview.todaySeconds) / 60).toInt(),
        )
    }

    // ---- One book ----

    /** A book's page of numbers; null when it has no reading or listening to show. */
    fun book(snapshot: StatsSnapshot, key: String, text: StatsText): BookStats? {
        val stats = snapshot.stats
        val core = stats.book(key) ?: return null
        if (core.readingSeconds + core.listeningSeconds == 0L) return null
        val firstDay = core.firstDay ?: return null
        val lastDay = core.lastDay ?: return null
        val parts =
            core.timeline.flatMap { day ->
                stats.day(day.date).sessions.filter { it.session.book == key }
            }
        val shortDay = DateTimeFormatter.ofPattern("d MMM", text.locale)
        val finish = core.finishes.lastOrNull()?.takeIf { it >= lastDay }
        val firstMidnight = firstDay.atStartOfDay(stats.zone).toInstant().toEpochMilli()
        val byDevice =
            parts
                .groupBy { source(snapshot, it.session, text) }
                .map { (name, list) -> name to list.sumOf { it.seconds } }
                .sortedByDescending { it.second }
        return BookStats(
            book = label(snapshot, key, text),
            readingSeconds = core.readingSeconds,
            listeningSeconds = core.listeningSeconds,
            sessions = core.sessions + core.listeningSessions,
            startedText = firstDay.format(shortDay),
            finishedText = finish?.format(shortDay),
            days =
                if (finish != null) core.daysToFinish
                else (ChronoUnit.DAYS.between(firstDay, stats.today) + 1).toInt(),
            wordsPerMinute = core.speed?.wordsPerMinute,
            averageWordsPerMinute = stats.speed().reading?.wordsPerMinute,
            secondsLeft = core.timeLeftSeconds,
            progress =
                core.timeline.mapNotNull { it.progress }.lastOrNull()?.toFloat()?.coerceIn(0f, 1f)
                    ?: 0f,
            timeline =
                parts.mapNotNull { part ->
                    val progress = part.session.progressEnd ?: return@mapNotNull null
                    TimelinePoint(
                        day = (part.endMs - firstMidnight) / DAY_MS,
                        progress = progress.toFloat().coerceIn(0f, 1f),
                        listening = part.session.source == SessionSource.LISTEN,
                    )
                },
            byDevice = byDevice,
            recent =
                parts.takeLast(RECENT_SESSIONS).asReversed().map { sessionRow(snapshot, it, text) },
        )
    }

    // ---- Year in review ----

    fun yearReview(snapshot: StatsSnapshot, year: Int, text: StatsText): YearReview {
        val stats = snapshot.stats
        val core = stats.yearInReview(year)
        val shortDay = DateTimeFormatter.ofPattern("d MMM", text.locale)
        val fastest = stats.records().fastestFinish?.takeIf { it.date.year == year }
        val records =
            listOfNotNull(
                core.longestSession?.let {
                    text.string(R.string.stats_record_longest_session) to
                        text.duration(it.session.durationSec)
                },
                core.mostInADay?.let {
                    text.string(R.string.stats_milestone_biggest_day) to text.duration(it.seconds)
                },
                core.longestStreak
                    ?.takeIf { it.days >= MIN_STREAK_RECORD }
                    ?.let {
                        text.string(R.string.stats_share_streak) to
                            text.string(R.string.stats_share_days, it.days)
                    },
                fastest?.let {
                    text.string(R.string.stats_milestone_fastest) to
                        text.string(
                            R.string.stats_joined,
                            label(snapshot, it.book, text).title,
                            text.string(R.string.stats_share_days, it.value.toInt()),
                        )
                },
            )
        val firsts =
            core.milestones.mapNotNull { m ->
                val date = m.date.format(shortDay)
                when {
                    m.kind == CoreMilestoneKind.BOOKS && m.threshold == 1 ->
                        text.string(R.string.stats_milestone_first_finish) to
                            text.string(
                                R.string.stats_joined,
                                m.book?.let { label(snapshot, it, text).title }.orEmpty(),
                                date,
                            )
                    m.kind == CoreMilestoneKind.BOOKS && m.threshold in BOOK_BADGES ->
                        text.string(R.string.stats_milestone_books, m.threshold) to date
                    m.kind == CoreMilestoneKind.HOURS && m.threshold in HOUR_BADGES ->
                        text.string(R.string.stats_first_hours, m.threshold) to date
                    else -> null
                }
            }
        return YearReview(
            year = year,
            books = core.booksFinished.size,
            readingSeconds = core.readingSeconds,
            listeningSeconds = core.listeningSeconds,
            topBooks =
                core.topBooks.map { RangeBook(label(snapshot, it.book, text), it.readingSeconds) },
            byMonth =
                (1..12).map { month -> core.booksFinished.count { it.date.monthValue == month } },
            longestStreak = core.longestStreak?.days ?: 0,
            favouriteHour = core.peakHour?.let(::hourLabel) ?: text.string(R.string.stats_no_value),
            wordsPerMinute = core.speed?.wordsPerMinute,
            records = records,
            firsts = firsts,
        )
    }
}

/** The goals as the core stores them. */
internal fun Goals.toSettings() = GoalSettings(booksPerYear, minutesPerDay)
