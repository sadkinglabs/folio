package dev.sadking.folio.core.stats

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.TreeMap
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Every number on the Stats tab and the book page's card, from the stored sessions, books and
 * finishes. Pure: no clock, no Android; [today] and [zone] are given. Build one per change of data
 * and ask it what the screen needs. docs/STATS_DESIGN.md (b) has the rules.
 *
 * Sessions are cleaned first: invalid ones dropped, repeats of one id kept once (the longest), and
 * time two reading sessions share counted once ([countSessions]). Listening is counted on its own
 * and never added to reading.
 */
class ReadingStats(
    sessions: List<ReadingSession>,
    books: List<StatsBook> = emptyList(),
    finishes: List<BookFinish> = emptyList(),
    val zone: ZoneId,
    val today: LocalDate,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
) {
    private val counted: List<Counted> = countSessions(sessions)
    private val bookInfo: Map<String, StatsBook> = books.associateBy { it.key }

    /** Counted time cut at local midnights, in time order. */
    private val parts: List<Part> = counted.flatMap { c ->
        splitDays(c.fromMs, c.toMs).map { (d, a, b) -> Part(c, d, a, b) }
    }
    private val partsByDate: Map<LocalDate, List<Part>> = parts.groupBy { it.date }
    private val partsByBook: Map<String, List<Part>> = parts.groupBy { it.book }
    private val countedByBook: Map<String, List<Counted>> = counted.groupBy { it.session.book }

    private val finishList: List<BookFinish> =
        mergeFinishes(
            trustedFinishes(finishes) { book, from, to ->
                partsByBook[book].orEmpty().any { it.date >= from && it.date <= to }
            }
        )

    private val days: TreeMap<LocalDate, DayTotals> =
        TreeMap<LocalDate, DayTotals>().also { map ->
            parts.forEach { p ->
                val day = map.getOrPut(p.date) { DayTotals() }
                if (p.counted.session.source.isReading) day.readingMs += p.ms
                else day.listeningMs += p.ms
            }
        }

    /** Every reading day's run of consecutive days, oldest first. */
    private val streaks: List<Streak> =
        runs(days.filter { it.value.readingMs >= MIN_READING_DAY_MS }.keys.toList())

    /** Live sessions: valid, unique, not entirely inside another. */
    private val live: List<ReadingSession> = counted.map { it.session }

    /** True when there is nothing at all to show. */
    val isEmpty: Boolean
        get() = counted.isEmpty() && finishList.isEmpty()

    /** The finishes after merging the same book's finishes from both sources, oldest first. */
    val finishes: List<BookFinish>
        get() = finishList

    fun overview(): Overview {
        val weekStart = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        return Overview(
            todaySeconds = readingSeconds(DateRange(today, today)),
            weekSeconds = readingSeconds(DateRange(weekStart, today)),
            monthSeconds = readingSeconds(DateRange(today.withDayOfMonth(1), today)),
            yearSeconds = readingSeconds(DateRange(today.withDayOfYear(1), today)),
            totalSeconds = days.values.sumOf { it.readingMs } / 1000,
            listeningTodaySeconds = (days[today]?.listeningMs ?: 0) / 1000,
            listeningTotalSeconds = days.values.sumOf { it.listeningMs } / 1000,
            currentStreak = currentStreak(),
            longestStreak = longest(streaks),
            booksFinishedThisYear = finishList.count { it.date.year == today.year },
            speed = wpm(live.filter { it.source.isReading }),
            firstDay = days.firstEntry()?.key,
            lastActivityMs = live.maxOfOrNull { it.endMs },
        )
    }

    /** The calendar for [month]: every day, each with its books in a stable colour order. */
    fun month(month: YearMonth): CalendarMonth {
        val range = DateRange.of(month)
        val slots = slots(month)
        val list =
            (0 until range.days).map { i ->
                val date = range.start.plusDays(i.toLong())
                val totals = days[date]
                CalendarDay(
                    date,
                    (totals?.readingMs ?: 0) / 1000,
                    (totals?.listeningMs ?: 0) / 1000,
                    dayBooks(partsByDate[date].orEmpty(), slots),
                    finishedOn(date),
                )
            }
        val monthParts = list.flatMap { partsByDate[it.date].orEmpty() }
        return CalendarMonth(
            month,
            list,
            dayBooks(monthParts, slots).map {
                MonthBook(it.book, it.slot, it.readingSeconds, it.listeningSeconds)
            },
            list.sumOf { it.readingSeconds },
            list.sumOf { it.listeningSeconds },
            list.count { (days[it.date]?.readingMs ?: 0) >= MIN_READING_DAY_MS },
        )
    }

    /** A tapped day: its books with their month's colours, and its sessions in time order. */
    fun day(date: LocalDate): DayDetail {
        val slots = slots(YearMonth.from(date))
        val list = partsByDate[date].orEmpty()
        return DayDetail(
            date,
            (days[date]?.readingMs ?: 0) / 1000,
            (days[date]?.listeningMs ?: 0) / 1000,
            dayBooks(list, slots),
            list.sortedWith(compareBy({ it.fromMs }, { it.counted.session.id })).map {
                DaySession(
                    it.counted.session,
                    it.fromMs,
                    it.toMs,
                    it.ms / 1000,
                    slots.getValue(it.counted.session.book),
                )
            },
            finishedOn(date),
        )
    }

    /** Habits over [range]: when, how long, how fast. */
    fun habits(range: DateRange): Habits {
        val reading = parts.filter { it.date in range && it.counted.session.source.isReading }
        val readingMs = reading.sumOf { it.ms }
        val readingDays =
            days.subMap(range.start, true, range.endInclusive, true).values.count {
                it.readingMs >= MIN_READING_DAY_MS
            }
        val hours = LongArray(24)
        reading.forEach { p -> splitHours(p.fromMs, p.toMs).forEach { (h, ms) -> hours[h] += ms } }
        val weekdayMs = LongArray(7)
        reading.forEach { weekdayMs[it.date.dayOfWeek.value - 1] += it.ms }
        val occurrences = IntArray(7)
        (0 until range.days).forEach {
            occurrences[range.start.plusDays(it.toLong()).dayOfWeek.value - 1]++
        }
        val sessionsInRange = counted.filter {
            it.session.source.isReading && localDate(it.fromMs) in range
        }
        return Habits(
            range = range,
            readingSeconds = readingMs / 1000,
            listeningSeconds =
                parts
                    .filter { it.date in range && !it.counted.session.source.isReading }
                    .sumOf { it.ms } / 1000,
            readingDays = readingDays,
            averagePerReadingDay = if (readingDays == 0) 0 else readingMs / 1000 / readingDays,
            hours = hours.map { it / 1000 },
            weekdays =
                DayOfWeek.entries.map {
                    WeekdayTotal(it, weekdayMs[it.value - 1] / 1000, occurrences[it.value - 1])
                },
            sessions = sessionLengths(sessionsInRange),
            speed = wpm(sessionsInRange.map { it.session }),
        )
    }

    /** Speeds over everything, or over the sessions that start in [range]. */
    fun speed(range: DateRange? = null): Speed {
        val list = live.filter { range == null || localDate(it.startMs) in range }
        return Speed(
            reading = wpm(list.filter { it.source.isReading }),
            kindle = wpm(list.filter { it.source == SessionSource.KINDLE }),
            phone = wpm(list.filter { it.source == SessionSource.PHONE }),
            listening = wpm(list.filter { it.source == SessionSource.LISTEN }),
        )
    }

    /** One book; null when it has no session and no finish. */
    fun book(key: String): BookStats? {
        val mine = partsByBook[key].orEmpty()
        val bookFinishes = finishList.filter { it.book == key }.map { it.date }
        if (mine.isEmpty() && bookFinishes.isEmpty()) return null
        val reading = mine.filter { it.counted.session.source.isReading }
        val sessions = countedByBook[key].orEmpty()
        val readingSessions = sessions.filter { it.session.source.isReading }.map { it.session }
        val readingDates =
            reading
                .groupBy { it.date }
                .filterValues { list -> list.sumOf { it.ms } >= MIN_READING_DAY_MS }
                .keys
        val timeline =
            mine
                .groupBy { it.date }
                .toSortedMap()
                .map { (date, list) ->
                    val (r, l) = list.partition { it.counted.session.source.isReading }
                    BookDay(
                        date,
                        r.sumOf { it.ms } / 1000,
                        l.sumOf { it.ms } / 1000,
                        list.maxByOrNull { it.toMs }?.counted?.session?.progressEnd,
                    )
                }
        val bookSpeed = wpm(readingSessions)
        return BookStats(
            book = key,
            readingSeconds = reading.sumOf { it.ms } / 1000,
            listeningSeconds = (mine - reading.toSet()).sumOf { it.ms } / 1000,
            sessions = readingSessions.size,
            listeningSessions = sessions.size - readingSessions.size,
            firstDay = mine.minOfOrNull { it.date },
            lastDay = mine.maxOfOrNull { it.date },
            readingDays = readingDates.size,
            finishes = bookFinishes,
            daysToFinish = bookFinishes.lastOrNull()?.let { daysToFinish(key, it) },
            speed = bookSpeed,
            timeLeftSeconds = timeLeft(key, readingSessions, bookSpeed, bookFinishes.lastOrNull()),
            timeline = timeline,
        )
    }

    /** Every book with a session or a finish, most recent activity first. */
    fun books(): List<BookStats> =
        (parts.map { it.counted.session.book } + finishList.map { it.book })
            .distinct()
            .mapNotNull { book(it) }
            .sortedWith(
                compareByDescending<BookStats> {
                        maxOf(
                            it.lastDay ?: LocalDate.MIN,
                            it.finishes.lastOrNull() ?: LocalDate.MIN,
                        )
                    }
                    .thenBy { it.book }
            )

    /** The last [count] weeks (starting on [firstDayOfWeek]), oldest first, this week last. */
    fun weeks(count: Int = 12): List<PeriodTotal> {
        val start = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        return (count - 1 downTo 0).map { i ->
            val from = start.minusWeeks(i.toLong())
            period(DateRange(from, from.plusDays(6)))
        }
    }

    /** The last [count] months, oldest first, this month last. */
    fun months(count: Int = 12): List<PeriodTotal> {
        val month = YearMonth.from(today)
        return (count - 1 downTo 0).map { period(DateRange.of(month.minusMonths(it.toLong()))) }
    }

    /** Milestones reached, in date order, and the next one of each kind. */
    fun milestones(): Milestones {
        val reached = mutableListOf<Milestone>()
        val firstReading = parts.firstOrNull {
            it.counted.session.source.isReading &&
                (days[it.date]?.readingMs ?: 0) >= MIN_READING_DAY_MS
        }
        if (firstReading != null)
            reached += Milestone(MilestoneKind.FIRST_READ, 1, firstReading.date, firstReading.book)
        var total = 0L
        val hours = MILESTONE_THRESHOLDS.getValue(MilestoneKind.HOURS)
        days.forEach { (date, day) ->
            val before = total
            total += day.readingMs
            hours
                .filter { before < it * HOUR_MS && total >= it * HOUR_MS }
                .forEach { reached += Milestone(MilestoneKind.HOURS, it, date, null) }
        }
        MILESTONE_THRESHOLDS.getValue(MilestoneKind.BOOKS)
            .filter { it <= finishList.size }
            .forEach {
                val finish = finishList[it - 1]
                reached += Milestone(MilestoneKind.BOOKS, it, finish.date, finish.book)
            }
        MILESTONE_THRESHOLDS.getValue(MilestoneKind.STREAK).forEach { n ->
            streaks
                .filter { it.days >= n }
                .minOfOrNull { it.start.plusDays(n - 1L) }
                ?.let { reached += Milestone(MilestoneKind.STREAK, n, it, null) }
        }
        val current =
            mapOf(
                MilestoneKind.HOURS to total / HOUR_MS,
                MilestoneKind.BOOKS to finishList.size.toLong(),
                MilestoneKind.STREAK to (currentStreak()?.days ?: 0).toLong(),
            )
        val next = current.mapNotNull { (kind, value) ->
            MILESTONE_THRESHOLDS.getValue(kind)
                .firstOrNull { threshold ->
                    reached.none { it.kind == kind && it.threshold == threshold }
                }
                ?.let { UpcomingMilestone(kind, it, value) }
        }
        return Milestones(
            reached.sortedWith(compareBy({ it.date }, { it.kind.ordinal }, { it.threshold })),
            next,
        )
    }

    fun records(): Records {
        val byYear = finishList.groupingBy { it.date.year }.eachCount()
        val byMonth = finishList.groupingBy { YearMonth.from(it.date) }.eachCount()
        val firstYear = finishList.minOfOrNull { it.date.year }
        return Records(
            longestStreak = longest(streaks),
            mostInADay = mostInADay(null),
            longestSession = longestSession(null),
            longestBookFinished =
                finishList
                    .mapNotNull { f ->
                        bookInfo[f.book]?.words?.let { FinishRecord(f.book, f.date, it) }
                    }
                    .maxWithOrNull(
                        compareBy<FinishRecord> { it.value }.thenByDescending { it.date }
                    ),
            fastestFinish =
                finishList
                    .mapNotNull { f ->
                        daysToFinish(f.book, f.date)?.let {
                            FinishRecord(f.book, f.date, it.toLong())
                        }
                    }
                    .minWithOrNull(compareBy<FinishRecord> { it.value }.thenBy { it.date }),
            mostBooksInAYear =
                byYear.entries
                    .maxWithOrNull(
                        compareBy<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key }
                    )
                    ?.let { YearCount(it.key, it.value) },
            mostBooksInAMonth =
                byMonth.entries
                    .maxWithOrNull(
                        compareBy<Map.Entry<YearMonth, Int>> { it.value }
                            .thenByDescending { it.key }
                    )
                    ?.let { MonthCount(it.key, it.value) },
            booksPerYear =
                if (firstYear == null) emptyList()
                else
                    (firstYear..maxOf(today.year, byYear.keys.max())).map {
                        YearCount(it, byYear[it] ?: 0)
                    },
        )
    }

    fun yearInReview(year: Int): YearInReview {
        val range = DateRange.of(year)
        val inYear = parts.filter { it.date.year == year }
        val (reading, listening) = inYear.partition { it.counted.session.source.isReading }
        val months = LongArray(12)
        reading.forEach { months[it.date.monthValue - 1] += it.ms }
        val habits = habits(range)
        val yearSessions = live.filter { it.source.isReading && localDate(it.startMs).year == year }
        val reachedMilestones = milestones().reached.filter { it.date.year == year }
        return YearInReview(
            year = year,
            readingSeconds = reading.sumOf { it.ms } / 1000,
            listeningSeconds = listening.sumOf { it.ms } / 1000,
            readingDays = habits.readingDays,
            booksFinished = finishList.filter { it.date.year == year },
            topBooks =
                reading
                    .groupBy { it.book }
                    .map { (book, list) -> BookTime(book, list.sumOf { it.ms } / 1000) }
                    .sortedWith(
                        compareByDescending<BookTime> { it.readingSeconds }.thenBy { it.book }
                    )
                    .take(5),
            months = months.map { it / 1000 },
            longestStreak =
                longest(
                    streaks.mapNotNull {
                        val start = maxOf(it.start, range.start)
                        val end = minOf(it.end, range.endInclusive)
                        if (start <= end) Streak(start, end) else null
                    }
                ),
            mostInADay = mostInADay(range),
            longestSession = longestSession(range),
            busiestMonth =
                months.indices
                    .filter { months[it] > 0 }
                    .maxByOrNull { months[it] }
                    ?.let { YearMonth.of(year, it + 1) },
            busiestWeekday =
                habits.weekdays.filter { it.seconds > 0 }.maxByOrNull { it.seconds }?.day,
            peakHour =
                habits.hours.indices
                    .filter { habits.hours[it] > 0 }
                    .maxByOrNull { habits.hours[it] },
            speed = wpm(yearSessions),
            wordsRead =
                yearSessions
                    .sumOf { s ->
                        val words = sessionWords(s) ?: return@sumOf 0.0
                        if (words / (s.durationSec / 60.0) > MAX_WPM) 0.0 else words
                    }
                    .roundToLong(),
            milestones = reachedMilestones,
            previousYearReadingSeconds = readingSeconds(DateRange.of(year - 1)),
        )
    }

    /** Goal progress for [settings]; both parts null when the goals are off. */
    fun goals(settings: GoalSettings): GoalProgress {
        val books =
            settings.booksPerYear
                ?.takeIf { it > 0 }
                ?.let { target ->
                    BooksGoal(
                        today.year,
                        target,
                        finishList.count { it.date.year == today.year },
                        target.toDouble() * today.dayOfYear / today.lengthOfYear(),
                    )
                }
        val daily =
            settings.minutesPerDay
                ?.takeIf { it > 0 }
                ?.let { minutes ->
                    val targetMs = minutes * 60_000L
                    fun met(date: LocalDate) = (days[date]?.readingMs ?: 0) >= targetMs
                    val weekStart = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
                    val end = if (met(today)) today else today.minusDays(1)
                    var start = end
                    while (met(start)) start = start.minusDays(1)
                    start = start.plusDays(1)
                    DailyGoal(
                        targetMinutes = minutes,
                        todaySeconds = (days[today]?.readingMs ?: 0) / 1000,
                        recent =
                            (6 downTo 0).map {
                                val date = today.minusDays(it.toLong())
                                GoalDay(date, (days[date]?.readingMs ?: 0) / 1000, met(date))
                            },
                        daysMetThisWeek = DateRange(weekStart, today).dates().count(::met),
                        daysMetThisMonth =
                            DateRange(today.withDayOfMonth(1), today).dates().count(::met),
                        streak = if (start <= end) Streak(start, end) else null,
                    )
                }
        return GoalProgress(books, daily)
    }

    // ---- Internals ----

    private class Counted(val session: ReadingSession, val fromMs: Long, val toMs: Long) {
        val ms: Long
            get() = toMs - fromMs
    }

    private class Part(
        val counted: Counted,
        val date: LocalDate,
        val fromMs: Long,
        val toMs: Long,
    ) {
        val ms: Long
            get() = toMs - fromMs

        val book: String
            get() = counted.session.book
    }

    private class DayTotals {
        var readingMs = 0L
        var listeningMs = 0L
    }

    private fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    private fun splitDays(from: Long, to: Long): List<Triple<LocalDate, Long, Long>> {
        val out = mutableListOf<Triple<LocalDate, Long, Long>>()
        var a = from
        while (a < to) {
            val date = localDate(a)
            val b = minOf(to, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            out += Triple(date, a, b)
            a = b
        }
        return out
    }

    private fun splitHours(from: Long, to: Long): List<Pair<Int, Long>> {
        val out = mutableListOf<Pair<Int, Long>>()
        var a = from
        while (a < to) {
            val at = Instant.ofEpochMilli(a).atZone(zone)
            val next = at.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val b = minOf(to, next)
            out += at.hour to (b - a)
            a = b
        }
        return out
    }

    private fun readingSeconds(range: DateRange): Long =
        days.subMap(range.start, true, range.endInclusive, true).values.sumOf { it.readingMs } /
            1000

    private fun period(range: DateRange): PeriodTotal {
        val list = days.subMap(range.start, true, range.endInclusive, true).values
        return PeriodTotal(
            range.start,
            range.endInclusive,
            list.sumOf { it.readingMs } / 1000,
            list.sumOf { it.listeningMs } / 1000,
            finishList.count { it.date in range },
        )
    }

    private fun currentStreak(): Streak? =
        streaks.lastOrNull()?.takeIf { it.end == today || it.end == today.minusDays(1) }

    private fun finishedOn(date: LocalDate): List<String> =
        finishList.filter { it.date == date }.map { it.book }.distinct()

    /**
     * Each book's colour in [month]: in the order of its first counted moment that month (reading
     * or listening), ties by key. The first [BOOK_SLOTS] get 0..7, the rest [OTHER_SLOT].
     */
    private fun slots(month: YearMonth): Map<String, Int> =
        parts
            .filter { YearMonth.from(it.date) == month }
            .groupBy { it.book }
            .map { (book, list) -> book to list.minOf { it.fromMs } }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .mapIndexed { i, (book, _) -> book to if (i < BOOK_SLOTS) i else OTHER_SLOT }
            .toMap()

    private fun dayBooks(list: List<Part>, slots: Map<String, Int>): List<DayBook> =
        list
            .groupBy { it.book }
            .map { (book, ps) ->
                val (r, l) = ps.partition { it.counted.session.source.isReading }
                DayBook(
                    book,
                    slots.getValue(book),
                    r.sumOf { it.ms } / 1000,
                    l.sumOf { it.ms } / 1000,
                )
            }
            // The month's order: slot order, and for the books sharing the Other slot, the order
            // they came in.
            .sortedBy { slots.keys.indexOf(it.book) }

    private fun mostInADay(range: DateRange?): DayRecord? =
        days.entries
            .filter { (range == null || it.key in range) && it.value.readingMs > 0 }
            .maxWithOrNull(
                compareBy<Map.Entry<LocalDate, DayTotals>> { it.value.readingMs }
                    .thenByDescending { it.key }
            )
            ?.let { DayRecord(it.key, it.value.readingMs / 1000) }

    private fun longestSession(range: DateRange?): SessionRecord? =
        live
            .filter { it.source.isReading && (range == null || localDate(it.startMs) in range) }
            .maxWithOrNull(
                compareBy<ReadingSession> { it.durationSec }.thenByDescending { it.startMs }
            )
            ?.let { SessionRecord(it, localDate(it.startMs)) }

    private fun sessionLengths(list: List<Counted>): SessionLengths {
        val kept = list.filter { it.ms >= GLANCE_MS }
        val seconds = kept.map { it.ms / 1000 }.sorted()
        val edges = SESSION_BUCKETS_MINUTES.map { it * 60L }
        val buckets = IntArray(edges.size + 1)
        seconds.forEach { s -> buckets[edges.count { s >= it }]++ }
        return SessionLengths(
            count = kept.size,
            medianSeconds =
                when {
                    seconds.isEmpty() -> 0
                    seconds.size % 2 == 1 -> seconds[seconds.size / 2]
                    else -> (seconds[seconds.size / 2 - 1] + seconds[seconds.size / 2]) / 2
                },
            meanSeconds = if (seconds.isEmpty()) 0 else seconds.sum() / seconds.size,
            longest =
                kept
                    .maxWithOrNull(compareBy<Counted> { it.ms }.thenByDescending { it.fromMs })
                    ?.session,
            buckets = buckets.toList(),
        )
    }

    private fun sessionWords(s: ReadingSession): Double? {
        val words = bookInfo[s.book]?.words?.takeIf { it > 0 } ?: return null
        val read = s.progressRead?.takeIf { it > 0 } ?: return null
        return read.coerceAtMost(1.0) * words
    }

    /**
     * Words per minute over [list]: total words over total minutes of the sessions that qualify (at
     * least [SPEED_MIN_SESSION_SECONDS], words and progress read known, a pace between [MIN_WPM]
     * and [MAX_WPM]). Null under [SPEED_MIN_TOTAL_SECONDS] of qualifying time.
     */
    private fun wpm(list: List<ReadingSession>): Wpm? {
        var words = 0.0
        var seconds = 0L
        var n = 0
        list.forEach { s ->
            if (s.durationSec < SPEED_MIN_SESSION_SECONDS) return@forEach
            val w = sessionWords(s) ?: return@forEach
            val pace = w / (s.durationSec / 60.0)
            if (pace < MIN_WPM || pace > MAX_WPM) return@forEach
            words += w
            seconds += s.durationSec
            n++
        }
        if (seconds < SPEED_MIN_TOTAL_SECONDS) return null
        return Wpm((words / (seconds / 60.0)).roundToInt(), words.roundToLong(), seconds, n)
    }

    /**
     * Days to finish [book] on [finish]: from the first day with a session after the finish before
     * it (or ever) up to [finish], both counted. Null when no session falls in that window, or when
     * the first one didn't start near the beginning ([STARTED_AT_MOST]), so a book begun before
     * tracking never looks fast.
     */
    private fun daysToFinish(book: String, finish: LocalDate): Int? {
        val previous =
            finishList.filter { it.book == book && it.date < finish }.maxOfOrNull { it.date }
        val window =
            partsByBook[book].orEmpty().filter {
                it.date <= finish && (previous == null || it.date > previous)
            }
        val first = window.minByOrNull { it.fromMs } ?: return null
        val start = first.counted.session.progressStart ?: return null
        if (start > STARTED_AT_MOST) return null
        return (finish.toEpochDay() - first.date.toEpochDay() + 1).toInt()
    }

    /**
     * Seconds left in [book] at the owner's pace. Null when it was finished after its last session,
     * its position is unknown or at the end, or no pace is known. The pace is the book's own speed,
     * else the owner's reading speed, else this book's seconds per share of the book read (from at
     * least [MIN_SHARE_FOR_PACE] of it).
     */
    private fun timeLeft(
        book: String,
        sessions: List<ReadingSession>,
        bookSpeed: Wpm?,
        lastFinish: LocalDate?,
    ): Long? {
        val last = sessions.maxByOrNull { it.endMs }
        if (lastFinish != null && (last == null || lastFinish >= localDate(last.endMs))) return null
        val progress = bookInfo[book]?.progress ?: last?.progressEnd ?: return null
        if (progress >= FINISHED_AT) return null
        val remaining = 1 - progress.coerceIn(0.0, 1.0)
        val words = bookInfo[book]?.words?.takeIf { it > 0 }
        val pace = bookSpeed ?: wpm(live.filter { it.source.isReading })
        if (words != null && pace != null)
            return (remaining * words / pace.wordsPerMinute * 60).roundToLong()
        val measured = sessions.filter { (it.progressRead ?: 0.0) > 0 }
        val share = measured.sumOf { it.progressRead!! }
        if (share < MIN_SHARE_FOR_PACE) return null
        return (remaining * measured.sumOf { it.durationSec } / share).roundToLong()
    }

    companion object {
        /** A day counts as a reading day (calendar count, streaks) from one minute of reading. */
        const val MIN_READING_DAY_MS = 60_000L

        /** Sessions under a minute are glances: left out of session lengths. */
        const val GLANCE_MS = 60_000L

        const val SPEED_MIN_SESSION_SECONDS = 180L
        const val SPEED_MIN_TOTAL_SECONDS = 600L
        const val MIN_WPM = 30
        const val MAX_WPM = 1200

        /** The same book finished twice within this many days is one finish (two sources). */
        const val SAME_FINISH_DAYS = 7L

        /**
         * A finish in Librillo next to Hardcover's history needs a session this many days before.
         */
        const val EVIDENCE_DAYS = 60L

        /** Days to finish needs the first session to start in the first 5% of the book. */
        const val STARTED_AT_MOST = 0.05

        /** From here on, a book counts as read to the end: no time left. */
        const val FINISHED_AT = 0.995

        const val MIN_SHARE_FOR_PACE = 0.02

        private const val HOUR_MS = 3_600_000L

        /**
         * The finishes to believe. A book's status time is not always when it was finished: a
         * status that came down from Hardcover carries the time it changed there. So for a book
         * Hardcover has a dated finish for, a [FinishSource.LIBRARY] finish counts only when it is
         * within [SAME_FINISH_DAYS] of a Hardcover one (the same finish) or when the book has a
         * session in the [EVIDENCE_DAYS] up to it (read in Librillo or on the e-reader).
         * [readBetween] says whether [book] has a session from one date to another, both included.
         */
        fun trustedFinishes(
            finishes: List<BookFinish>,
            readBetween: (book: String, from: LocalDate, to: LocalDate) -> Boolean,
        ): List<BookFinish> {
            val hardcover =
                finishes.filter { it.source == FinishSource.HARDCOVER }.groupBy { it.book }
            return finishes.filter { f ->
                val dated = hardcover[f.book]
                f.source == FinishSource.HARDCOVER ||
                    dated == null ||
                    dated.any {
                        kotlin.math.abs(it.date.toEpochDay() - f.date.toEpochDay()) <=
                            SAME_FINISH_DAYS
                    } ||
                    readBetween(f.book, f.date.minusDays(EVIDENCE_DAYS), f.date)
            }
        }

        /** One finish per book per [SAME_FINISH_DAYS], the earliest kept; sorted by date, book. */
        fun mergeFinishes(finishes: List<BookFinish>): List<BookFinish> =
            finishes
                .groupBy { it.book }
                .flatMap { (_, list) ->
                    val kept = mutableListOf<BookFinish>()
                    list.sortedWith(compareBy({ it.date }, { it.source.ordinal })).forEach { f ->
                        val last = kept.lastOrNull()
                        if (
                            last == null ||
                                f.date.toEpochDay() - last.date.toEpochDay() > SAME_FINISH_DAYS
                        )
                            kept += f
                    }
                    kept
                }
                .sortedWith(compareBy({ it.date }, { it.book }))

        /** Runs of consecutive dates in sorted [dates]. */
        internal fun runs(dates: List<LocalDate>): List<Streak> {
            val out = mutableListOf<Streak>()
            dates.forEach { d ->
                val last = out.lastOrNull()
                if (last != null && last.end.plusDays(1) == d)
                    out[out.size - 1] = last.copy(end = d)
                else out += Streak(d, d)
            }
            return out
        }

        internal fun longest(streaks: List<Streak>): Streak? =
            streaks.maxWithOrNull(compareBy<Streak> { it.days }.thenByDescending { it.start })

        /**
         * The sessions that count, each with the stretch of time it adds: invalid ones dropped, one
         * per id (the longest), then per kind (reading, listening) in start order, the start of a
         * session that another device's earlier session still covers is cut off, so time read on
         * two devices at once counts once. One device's own sessions are never cut: it reads one
         * book at a time, and its sessions' counted time never overlaps. A session left with no
         * time of its own is dropped.
         */
        private fun countSessions(sessions: List<ReadingSession>): List<Counted> {
            val unique =
                sessions
                    .filter { it.valid }
                    .groupBy { it.id }
                    .values
                    .map { list -> list.maxWith(compareBy({ it.durationSec }, { it.startMs })) }
            return listOf(true, false)
                .flatMap { reading ->
                    val coveredBy = HashMap<String, Long>()
                    unique
                        .filter { it.source.isReading == reading }
                        .sortedWith(compareBy({ it.startMs }, { it.source.ordinal }, { it.id }))
                        .mapNotNull { s ->
                            val others =
                                coveredBy.filterKeys { it != s.deviceKey }.values.maxOrNull()
                            val from = maxOf(s.startMs, others ?: Long.MIN_VALUE)
                            val to = s.endMs
                            coveredBy[s.deviceKey] = maxOf(coveredBy[s.deviceKey] ?: to, to)
                            if (to > from) Counted(s, from, to) else null
                        }
                }
                .sortedWith(compareBy({ it.fromMs }, { it.session.id }))
        }
    }
}

private fun DateRange.dates(): List<LocalDate> = (0 until days).map { start.plusDays(it.toLong()) }
