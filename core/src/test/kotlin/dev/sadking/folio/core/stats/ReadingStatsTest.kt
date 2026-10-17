package dev.sadking.folio.core.stats

import dev.sadking.folio.core.stats.SessionSource.KINDLE
import dev.sadking.folio.core.stats.SessionSource.LISTEN
import dev.sadking.folio.core.stats.SessionSource.PHONE
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReadingStatsTest {
    /** Has daylight saving time: 2026-03-29 loses an hour, 2026-10-25 gains one. */
    private val zone = ZoneId.of("Europe/Madrid")
    private var n = 0

    private fun at(date: String, time: String) =
        LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()

    private fun session(
        book: String,
        date: String,
        time: String,
        minutes: Int,
        source: SessionSource = KINDLE,
        device: String = if (source == KINDLE) "ereader:k" else "phone:me",
        read: Double? = null,
        start: Double? = null,
        end: Double? = null,
        id: String = "s${n++}",
        seconds: Long = minutes * 60L,
    ) = ReadingSession(id, source, device, book, null, at(date, time), seconds, start, end, read)

    private fun stats(
        sessions: List<ReadingSession>,
        books: List<StatsBook> = emptyList(),
        finishes: List<BookFinish> = emptyList(),
        today: String = "2026-10-17",
    ) = ReadingStats(sessions, books, finishes, zone, LocalDate.parse(today))

    private fun date(value: String) = LocalDate.parse(value)

    private fun finish(book: String, day: String, source: FinishSource = FinishSource.LIBRARY) =
        BookFinish(book, date(day), source)

    // ---- Days, midnight and daylight saving ----

    @Test
    fun aSessionAcrossMidnightCountsOnBothDays() {
        val s = stats(listOf(session("a", "2026-03-10", "23:30", 60)))
        val march = s.month(YearMonth.of(2026, 3))
        assertEquals(31, march.days.size)
        assertEquals(1800, march.days[9].readingSeconds)
        assertEquals(1800, march.days[10].readingSeconds)
        assertEquals(3600, march.readingSeconds)
        assertEquals(2, march.readingDays)
        val before = s.day(date("2026-03-10")).sessions.single()
        assertEquals(at("2026-03-10", "23:30"), before.startMs)
        assertEquals(at("2026-03-11", "00:00"), before.endMs)
        assertEquals(1800, before.seconds)
        val after = s.day(date("2026-03-11")).sessions.single()
        assertEquals(at("2026-03-11", "00:00"), after.startMs)
        assertEquals(1800, after.seconds)
    }

    @Test
    fun aSessionAcrossTheEndOfAMonthCountsInBothMonths() {
        val s = stats(listOf(session("a", "2026-01-31", "23:00", 120)))
        assertEquals(3600, s.month(YearMonth.of(2026, 1)).readingSeconds)
        assertEquals(3600, s.month(YearMonth.of(2026, 2)).readingSeconds)
        assertEquals(3600, s.month(YearMonth.of(2026, 2)).days[0].readingSeconds)
    }

    @Test
    fun theHourLostToDaylightSavingIsSkipped() {
        // 01:30 plus an hour of real time is 03:30: the clock jumps from 02:00 to 03:00.
        val s = stats(listOf(session("a", "2026-03-29", "01:30", 60)))
        val day = date("2026-03-29")
        assertEquals(3600, s.day(day).readingSeconds)
        val hours = s.habits(DateRange(day, day)).hours
        assertEquals(1800, hours[1])
        assertEquals(0, hours[2])
        assertEquals(1800, hours[3])
    }

    @Test
    fun theHourGainedFromDaylightSavingIsCountedOnce() {
        // 02:30 summer time plus an hour of real time is 02:30 winter time.
        val s = stats(listOf(session("a", "2026-10-25", "02:30", 60)))
        val day = date("2026-10-25")
        assertEquals(3600, s.day(day).readingSeconds)
        assertEquals(3600, s.habits(DateRange(day, day)).hours[2])
    }

    // ---- Streaks ----

    @Test
    fun streaksRunAcrossDaylightSavingDays() {
        val sessions =
            listOf("2026-03-28", "2026-03-29", "2026-03-30").map { session("a", it, "10:00", 30) }
        val streak = Streak(date("2026-03-28"), date("2026-03-30"))
        assertEquals(streak, stats(sessions, today = "2026-03-30").overview().currentStreak)
        assertEquals(3, streak.days)
        // Not read yet today: the streak is still alive.
        assertEquals(streak, stats(sessions, today = "2026-03-31").overview().currentStreak)
        assertNull(stats(sessions, today = "2026-04-01").overview().currentStreak)
        assertEquals(streak, stats(sessions, today = "2026-04-01").overview().longestStreak)
        val autumn =
            listOf("2026-10-24", "2026-10-25", "2026-10-26").map { session("a", it, "23:30", 20) }
        assertEquals(
            Streak(date("2026-10-24"), date("2026-10-26")),
            stats(autumn, today = "2026-10-26").overview().currentStreak,
        )
    }

    @Test
    fun aReadingDayNeedsAMinuteAndTiesGoToTheEarliest() {
        val s =
            stats(
                listOf(
                    session("a", "2026-03-01", "10:00", 30),
                    session("a", "2026-03-02", "10:00", 0, seconds = 30),
                    session("a", "2026-03-03", "10:00", 30),
                ),
                today = "2026-03-03",
            )
        val march1 = date("2026-03-01")
        assertEquals(Streak(march1, march1), s.overview().longestStreak)
        assertEquals(2, s.month(YearMonth.of(2026, 3)).readingDays)
        assertEquals(30, s.month(YearMonth.of(2026, 3)).days[1].readingSeconds)
    }

    // ---- Repeats and overlap ----

    @Test
    fun theSameSessionSentTwiceCountsOnceAtItsLongest() {
        val s =
            stats(
                listOf(
                    session("a", "2026-03-01", "10:00", 10, id = "k1"),
                    session("a", "2026-03-01", "10:00", 20, id = "k1"),
                )
            )
        assertEquals(1200, s.overview().totalSeconds)
        assertEquals(1, s.book("a")!!.sessions)
    }

    @Test
    fun timeReadOnTwoDevicesAtOnceCountsOnce() {
        val inside =
            stats(
                listOf(
                    session("a", "2026-03-01", "10:00", 30),
                    session("b", "2026-03-01", "10:10", 10, source = PHONE),
                )
            )
        assertEquals(1800, inside.overview().totalSeconds)
        assertEquals(listOf("a"), inside.day(date("2026-03-01")).sessions.map { it.session.book })
        val partly =
            stats(
                listOf(
                    session("b", "2026-03-01", "09:50", 15, source = PHONE),
                    session("a", "2026-03-01", "10:00", 30),
                )
            )
        assertEquals(2400, partly.overview().totalSeconds)
        assertEquals(1500, partly.book("a")!!.readingSeconds)
        assertEquals(900, partly.book("b")!!.readingSeconds)
    }

    @Test
    fun oneDevicesOwnSessionsAreNeverCut() {
        val s =
            stats(
                listOf(
                    session("a", "2026-03-01", "10:00", 3),
                    session("b", "2026-03-01", "10:02", 2),
                )
            )
        assertEquals(300, s.overview().totalSeconds)
    }

    @Test
    fun oneEreaderKnownByTwoIdsCountsOnce() {
        val s =
            stats(
                listOf(
                    session("a", "2026-03-01", "10:00", 30, device = "ereader:old"),
                    session("a", "2026-03-01", "10:00", 30, device = "ereader:new"),
                )
            )
        assertEquals(1800, s.overview().totalSeconds)
    }

    @Test
    fun listeningIsCountedOnItsOwn() {
        val s =
            stats(
                listOf(
                    session("a", "2026-10-17", "10:00", 30),
                    session("a", "2026-10-17", "10:00", 20, source = LISTEN),
                )
            )
        val o = s.overview()
        assertEquals(1800, o.totalSeconds)
        assertEquals(1800, o.todaySeconds)
        assertEquals(1200, o.listeningTotalSeconds)
        assertEquals(1200, o.listeningTodaySeconds)
        val day = s.month(YearMonth.of(2026, 10)).days[16]
        assertEquals(1800, day.readingSeconds)
        assertEquals(1200, day.listeningSeconds)
        assertEquals(listOf(DayBook("a", 0, 1800, 1200)), day.books)
        assertEquals(1, s.book("a")!!.sessions)
        assertEquals(1, s.book("a")!!.listeningSessions)
    }

    @Test
    fun brokenSessionsAreIgnored() {
        val s =
            stats(
                listOf(
                    session("a", "2026-03-01", "10:00", 0),
                    session("a", "2026-03-01", "10:00", 0, seconds = MAX_SESSION_SECONDS + 1),
                    ReadingSession(
                        "x",
                        KINDLE,
                        "ereader:k",
                        null,
                        null,
                        at("2026-03-01", "10:00"),
                        60,
                    ),
                )
            )
        assertTrue(s.isEmpty)
    }

    @Test
    fun anUnmatchedDocumentIsItsOwnBook() {
        val doc = "0123456789abcdef0123456789abcdef"
        val s =
            stats(
                listOf(
                    ReadingSession(
                        "x",
                        KINDLE,
                        "ereader:k",
                        null,
                        doc,
                        at("2026-03-01", "10:00"),
                        60,
                    )
                )
            )
        assertEquals(60, s.book("doc:$doc")!!.readingSeconds)
    }

    // ---- Speed ----

    private val words = listOf(StatsBook("a", words = 100_000))

    @Test
    fun wordsPerMinuteLeaveOutShortJumpyAndUnknownSessions() {
        val s =
            stats(
                listOf(
                    session("a", "2026-03-01", "10:00", 30, read = 0.09),
                    session("a", "2026-03-02", "10:00", 20, source = PHONE, read = 0.04),
                    // Too short.
                    session("a", "2026-03-03", "10:00", 2, read = 0.01),
                    // 5,000 words a minute: a jump, not reading.
                    session("a", "2026-03-04", "10:00", 10, read = 0.5),
                    // Nothing known about how much was read.
                    session("a", "2026-03-05", "10:00", 30),
                ),
                words,
            )
        val speed = s.speed()
        assertEquals(Wpm(300, 9000, 1800, 1), speed.kindle)
        assertEquals(Wpm(200, 4000, 1200, 1), speed.phone)
        assertEquals(Wpm(260, 13000, 3000, 2), speed.reading)
        assertNull(speed.listening)
        assertEquals(speed.reading, s.overview().speed)
        assertEquals(speed.reading, s.book("a")!!.speed)
    }

    @Test
    fun underTenMinutesOfSpeedIsNoSpeed() {
        val s = stats(listOf(session("a", "2026-03-01", "10:00", 5, read = 0.01)), words)
        assertNull(s.speed().reading)
    }

    @Test
    fun listeningHasItsOwnSpeed() {
        val s =
            stats(
                listOf(session("a", "2026-03-01", "10:00", 20, source = LISTEN, read = 0.03)),
                words,
            )
        assertEquals(150, s.speed().listening!!.wordsPerMinute)
        assertNull(s.speed().reading)
    }

    // ---- Per book ----

    @Test
    fun timeLeftUsesTheBooksWordsAndPace() {
        val s =
            stats(
                listOf(session("a", "2026-03-01", "10:00", 30, read = 0.09, end = 0.5)),
                listOf(StatsBook("a", words = 100_000, progress = 0.5)),
            )
        // 50,000 words left at 300 a minute.
        assertEquals(10_000L, s.book("a")!!.timeLeftSeconds)
    }

    @Test
    fun withoutWordsTimeLeftUsesTimePerShareOfTheBook() {
        val s =
            stats(
                listOf(
                    session("c", "2026-03-01", "10:00", 0, seconds = 600, read = 0.06),
                    session("c", "2026-03-02", "10:00", 0, seconds = 400, read = 0.04, end = 0.4),
                )
            )
        // 1,000 seconds for a tenth of the book; 60% left.
        assertEquals(6000L, s.book("c")!!.timeLeftSeconds)
    }

    @Test
    fun aFinishedBookHasNoTimeLeft() {
        val s =
            stats(
                listOf(session("a", "2026-03-01", "10:00", 30, read = 0.09, end = 0.5)),
                listOf(StatsBook("a", words = 100_000, progress = 0.5)),
                listOf(finish("a", "2026-03-02")),
            )
        assertNull(s.book("a")!!.timeLeftSeconds)
    }

    @Test
    fun daysToFinishCountFromTheFirstSessionOfThatRead() {
        val sessions =
            listOf(
                session("d", "2026-01-01", "10:00", 30, start = 0.0),
                session("d", "2026-01-05", "10:00", 30, start = 0.5),
                session("d", "2026-06-20", "10:00", 30, start = 0.0),
                session("e", "2026-02-01", "10:00", 30, start = 0.4),
            )
        val s =
            stats(
                sessions,
                finishes =
                    listOf(
                        finish("d", "2026-01-10"),
                        finish("d", "2026-06-30"),
                        finish("e", "2026-02-03"),
                    ),
            )
        val d = s.book("d")!!
        assertEquals(listOf(date("2026-01-10"), date("2026-06-30")), d.finishes)
        assertEquals(11, d.daysToFinish)
        assertEquals(3, d.sessions)
        assertEquals(3, d.readingDays)
        assertEquals(date("2026-01-01"), d.firstDay)
        assertEquals(date("2026-06-20"), d.lastDay)
        // Begun before it was tracked: never shown as fast.
        assertNull(s.book("e")!!.daysToFinish)
        assertEquals(FinishRecord("d", date("2026-01-10"), 10), s.records().fastestFinish)
    }

    @Test
    fun theTimelineHasOneRowPerDayWithTheDaysLastPosition() {
        val s =
            stats(
                listOf(
                    session("a", "2026-03-01", "10:00", 10, end = 0.1),
                    session("a", "2026-03-01", "20:00", 10, end = 0.2),
                    session("a", "2026-03-03", "10:00", 5, source = LISTEN, end = 0.25),
                )
            )
        assertEquals(
            listOf(
                BookDay(date("2026-03-01"), 1200, 0, 0.2),
                BookDay(date("2026-03-03"), 0, 300, 0.25),
            ),
            s.book("a")!!.timeline,
        )
    }

    @Test
    fun booksAreListedByLatestActivity() {
        val s =
            stats(
                listOf(
                    session("old", "2026-01-01", "10:00", 10),
                    session("new", "2026-03-01", "10:00", 10),
                ),
                finishes = listOf(finish("hardcover-only", "2026-02-01", FinishSource.HARDCOVER)),
            )
        assertEquals(listOf("new", "hardcover-only", "old"), s.books().map { it.book })
        assertNull(s.book("nothing"))
    }

    // ---- Finishes ----

    @Test
    fun theSameFinishFromBothSourcesCountsOnce() {
        val merged =
            ReadingStats.mergeFinishes(
                listOf(
                    finish("x", "2026-01-12", FinishSource.HARDCOVER),
                    finish("x", "2026-01-10"),
                    finish("x", "2026-05-01", FinishSource.HARDCOVER),
                )
            )
        assertEquals(
            listOf(finish("x", "2026-01-10"), finish("x", "2026-05-01", FinishSource.HARDCOVER)),
            merged,
        )
    }

    @Test
    fun aStatusTimeFromHardcoverIsNotASecondFinish() {
        val s =
            stats(
                listOf(session("x", "2026-03-01", "10:00", 30)),
                finishes =
                    listOf(
                        finish("x", "2019-05-01", FinishSource.HARDCOVER),
                        // The status came down from Hardcover with the time it changed there.
                        finish("x", "2023-02-01"),
                        // A re-read in Librillo.
                        finish("x", "2026-03-10"),
                        // A book Hardcover has no dated read of: its finish is believed.
                        finish("y", "2023-02-01"),
                    ),
            )
        assertEquals(
            listOf(
                finish("x", "2019-05-01", FinishSource.HARDCOVER),
                finish("y", "2023-02-01"),
                finish("x", "2026-03-10"),
            ),
            s.finishes,
        )
    }

    // ---- Calendar colours ----

    @Test
    fun eachBookKeepsOneColourForTheMonthAndTheNinthIsOther() {
        val sessions =
            (1..9).map { session("b$it", "2026-03-%02d".format(it), "10:00", 10) } +
                session("b1", "2026-03-20", "09:00", 10) +
                session("b2", "2026-03-20", "08:00", 10)
        val march = stats(sessions).month(YearMonth.of(2026, 3))
        assertEquals((0..7).toList() + OTHER_SLOT, march.books.map { it.slot })
        assertEquals((1..9).map { "b$it" }, march.books.map { it.book })
        // b2 read first that day, but the cell keeps the month's order.
        assertEquals(listOf("b1", "b2"), march.days[19].books.map { it.book })
        assertEquals(listOf(0, 1), march.days[19].books.map { it.slot })
        assertEquals(0, stats(sessions).day(date("2026-03-20")).sessions[1].slot)
    }

    @Test
    fun finishedBooksAreMarkedOnTheirDay() {
        val s = stats(emptyList(), finishes = listOf(finish("a", "2026-03-05")))
        assertEquals(listOf("a"), s.month(YearMonth.of(2026, 3)).days[4].finished)
        assertEquals(listOf("a"), s.day(date("2026-03-05")).finished)
    }

    // ---- Habits ----

    @Test
    fun habitsCountWeekdaysSessionLengthsAndHours() {
        val s =
            stats(
                listOf(
                    session("a", "2026-03-02", "08:00", 0, seconds = 30),
                    session("a", "2026-03-02", "09:00", 4),
                    session("a", "2026-03-03", "09:00", 10),
                    session("a", "2026-03-04", "21:00", 20),
                    session("a", "2026-03-05", "21:00", 45),
                    session("a", "2026-03-06", "21:00", 90),
                )
            )
        val h = s.habits(DateRange(date("2026-03-02"), date("2026-03-15")))
        assertEquals(14, h.range.days)
        assertTrue(h.weekdays.all { it.occurrences == 2 })
        assertEquals(DayOfWeek.MONDAY, h.weekdays[0].day)
        assertEquals(270, h.weekdays[0].seconds)
        assertEquals(135, h.weekdays[0].averageSeconds)
        assertEquals(5, h.sessions.count)
        assertEquals(listOf(1, 1, 1, 1, 1), h.sessions.buckets)
        assertEquals(1200, h.sessions.medianSeconds)
        assertEquals(2028, h.sessions.meanSeconds)
        assertEquals(5400L, h.sessions.longest!!.durationSec)
        assertEquals(30, h.hours[8])
        assertEquals(840, h.hours[9])
        assertEquals(1200 + 2700 + 3600, h.hours[21])
        assertEquals(1800, h.hours[22])
        assertEquals(5, h.readingDays)
    }

    @Test
    fun weeksAndMonthsEndWithTheCurrentOne() {
        val s =
            stats(
                listOf(
                    session("a", "2026-10-12", "10:00", 10),
                    session("a", "2026-10-05", "10:00", 20),
                    session("a", "2026-09-30", "10:00", 5, source = LISTEN),
                ),
                finishes = listOf(finish("a", "2026-10-13")),
            )
        val weeks = s.weeks(3)
        assertEquals(
            listOf(date("2026-09-28"), date("2026-10-05"), date("2026-10-12")),
            weeks.map { it.start },
        )
        assertEquals(listOf(0L, 1200L, 600L), weeks.map { it.readingSeconds })
        assertEquals(listOf(300L, 0L, 0L), weeks.map { it.listeningSeconds })
        assertEquals(listOf(0, 0, 1), weeks.map { it.booksFinished })
        val months = s.months(2)
        assertEquals(listOf(date("2026-09-01"), date("2026-10-01")), months.map { it.start })
        assertEquals(date("2026-10-31"), months[1].endInclusive)
        assertEquals(1800, months[1].readingSeconds)
    }

    // ---- Milestones and records ----

    @Test
    fun milestonesAreDatedWhenReached() {
        val s =
            stats(
                (1..7).map { session("a", "2026-02-%02d".format(it), "10:00", 10) },
                finishes = listOf(finish("a", "2026-02-07")),
                today = "2026-02-07",
            )
        val m = s.milestones()
        assertEquals(
            listOf(
                Milestone(MilestoneKind.FIRST_READ, 1, date("2026-02-01"), "a"),
                Milestone(MilestoneKind.HOURS, 1, date("2026-02-06"), null),
                Milestone(MilestoneKind.BOOKS, 1, date("2026-02-07"), "a"),
                Milestone(MilestoneKind.STREAK, 7, date("2026-02-07"), null),
            ),
            m.reached,
        )
        assertEquals(
            listOf(
                UpcomingMilestone(MilestoneKind.HOURS, 10, 1),
                UpcomingMilestone(MilestoneKind.BOOKS, 10, 1),
                UpcomingMilestone(MilestoneKind.STREAK, 30, 7),
            ),
            m.next,
        )
    }

    @Test
    fun recordsKeepTheBestAndTheEarliestOnATie() {
        val s =
            stats(
                listOf(
                    session("a", "2025-03-01", "10:00", 40),
                    session("a", "2025-03-01", "20:00", 40),
                    session("b", "2025-04-01", "10:00", 90),
                    session("b", "2025-05-01", "10:00", 90),
                ),
                listOf(StatsBook("a", words = 50_000), StatsBook("b", words = 120_000)),
                listOf(
                    finish("a", "2023-06-01"),
                    finish("b", "2025-05-01"),
                    finish("a", "2025-06-01"),
                ),
            )
        val r = s.records()
        assertEquals(DayRecord(date("2025-04-01"), 5400), r.mostInADay)
        assertEquals(date("2025-04-01"), r.longestSession!!.date)
        assertEquals(FinishRecord("b", date("2025-05-01"), 120_000), r.longestBookFinished)
        assertEquals(YearCount(2025, 2), r.mostBooksInAYear)
        assertEquals(MonthCount(YearMonth.of(2023, 6), 1), r.mostBooksInAMonth)
        assertEquals(
            listOf(YearCount(2023, 1), YearCount(2024, 0), YearCount(2025, 2), YearCount(2026, 0)),
            r.booksPerYear,
        )
    }

    // ---- Year in review ----

    @Test
    fun aYearInReview() {
        val s =
            stats(
                listOf(
                    session("a", "2025-03-03", "10:00", 60),
                    session("b", "2025-03-04", "10:00", 30),
                    session("a", "2025-07-01", "21:00", 45),
                    session("a", "2024-12-01", "10:00", 10),
                ),
                finishes = listOf(finish("a", "2025-07-01")),
            )
        val y = s.yearInReview(2025)
        assertFalse(y.isEmpty)
        assertEquals(8100, y.readingSeconds)
        assertEquals(3, y.readingDays)
        assertEquals(5400, y.months[2])
        assertEquals(2700, y.months[6])
        assertEquals(listOf(BookTime("a", 6300), BookTime("b", 1800)), y.topBooks)
        assertEquals(YearMonth.of(2025, 3), y.busiestMonth)
        assertEquals(DayOfWeek.TUESDAY, y.busiestWeekday)
        assertEquals(10, y.peakHour)
        assertEquals(3600L, y.longestSession!!.session.durationSec)
        assertEquals(DayRecord(date("2025-03-03"), 3600), y.mostInADay)
        assertEquals(Streak(date("2025-03-03"), date("2025-03-04")), y.longestStreak)
        assertEquals(listOf(finish("a", "2025-07-01")), y.booksFinished)
        assertEquals(600, y.previousYearReadingSeconds)
        assertEquals(
            listOf(MilestoneKind.HOURS to 1, MilestoneKind.BOOKS to 1),
            y.milestones.map { it.kind to it.threshold },
        )
        assertTrue(s.yearInReview(2023).isEmpty)
    }

    @Test
    fun aStreakAcrossNewYearIsCutAtTheYearsEdge() {
        val s =
            stats(
                listOf("2024-12-30", "2024-12-31", "2025-01-01").map {
                    session("a", it, "10:00", 10)
                }
            )
        assertEquals(
            Streak(date("2025-01-01"), date("2025-01-01")),
            s.yearInReview(2025).longestStreak,
        )
        assertEquals(3, s.records().longestStreak!!.days)
    }

    // ---- Goals ----

    @Test
    fun goalsAreOffUntilSet() {
        assertEquals(GoalProgress(null, null), stats(emptyList()).goals(GoalSettings()))
        assertFalse(GoalSettings().enabled)
    }

    @Test
    fun theYearlyBooksGoalComparesWithASteadyPace() {
        val s =
            stats(
                emptyList(),
                finishes =
                    listOf(
                        finish("a", "2025-12-30"),
                        finish("b", "2026-02-01"),
                        finish("c", "2026-09-01"),
                    ),
            )
        val goal = s.goals(GoalSettings(booksPerYear = 24)).books!!
        assertEquals(2026, goal.year)
        assertEquals(2, goal.finished)
        assertEquals(24.0 * 290 / 365, goal.expectedByToday, 1e-9)
        assertFalse(goal.met)
        assertTrue(goal.ahead < 0)
        assertTrue(s.goals(GoalSettings(booksPerYear = 2)).books!!.met)
    }

    @Test
    fun theDailyGoalCountsReadingOnly() {
        val base =
            listOf(
                session("a", "2026-10-15", "10:00", 25),
                session("a", "2026-10-16", "10:00", 20),
                session("a", "2026-10-17", "10:00", 10),
                session("a", "2026-10-17", "12:00", 30, source = LISTEN),
            )
        val goal = stats(base).goals(GoalSettings(minutesPerDay = 20)).daily!!
        assertFalse(goal.metToday)
        assertEquals(600, goal.todaySeconds)
        assertEquals(Streak(date("2026-10-15"), date("2026-10-16")), goal.streak)
        assertEquals(7, goal.recent.size)
        assertEquals(date("2026-10-11"), goal.recent.first().date)
        assertEquals(
            listOf(false, false, false, false, true, true, false),
            goal.recent.map { it.met },
        )
        // Monday 12 to Saturday 17.
        assertEquals(2, goal.daysMetThisWeek)
        assertEquals(2, goal.daysMetThisMonth)
        val later =
            stats(base + session("a", "2026-10-17", "20:00", 15))
                .goals(GoalSettings(minutesPerDay = 20))
                .daily!!
        assertTrue(later.metToday)
        assertEquals(Streak(date("2026-10-15"), date("2026-10-17")), later.streak)
        assertNull(stats(emptyList()).goals(GoalSettings(minutesPerDay = 20)).daily!!.streak)
    }

    // ---- Nothing yet ----

    @Test
    fun withNothingEveryAnswerIsEmpty() {
        val s = stats(emptyList())
        assertTrue(s.isEmpty)
        val o = s.overview()
        assertEquals(0, o.totalSeconds)
        assertNull(o.currentStreak)
        assertNull(o.longestStreak)
        assertNull(o.speed)
        assertNull(o.firstDay)
        assertNull(o.lastActivityMs)
        val october = s.month(YearMonth.of(2026, 10))
        assertEquals(31, october.days.size)
        assertTrue(october.days.all { it.readingSeconds == 0L && it.books.isEmpty() })
        assertTrue(october.books.isEmpty())
        assertTrue(s.day(date("2026-10-17")).sessions.isEmpty())
        assertNull(s.book("a"))
        assertTrue(s.books().isEmpty())
        assertTrue(s.yearInReview(2026).isEmpty)
        assertTrue(s.milestones().reached.isEmpty())
        assertEquals(UpcomingMilestone(MilestoneKind.HOURS, 1, 0), s.milestones().next.first())
        val r = s.records()
        assertNull(r.longestStreak)
        assertNull(r.mostInADay)
        assertTrue(r.booksPerYear.isEmpty())
        val h = s.habits(DateRange.last(30, date("2026-10-17")))
        assertEquals(30, h.range.days)
        assertEquals(24, h.hours.size)
        assertEquals(0, h.sessions.count)
        assertEquals(12, s.weeks().size)
        assertEquals(12, s.months().size)
    }
}
