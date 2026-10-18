package dev.sadking.folio.ui.stats

import dev.sadking.folio.core.stats.BookFinish
import dev.sadking.folio.core.stats.DateRange
import dev.sadking.folio.core.stats.FinishSource
import dev.sadking.folio.core.stats.GoalSettings
import dev.sadking.folio.core.stats.OTHER_SLOT
import dev.sadking.folio.core.stats.ReadingSession
import dev.sadking.folio.core.stats.ReadingStats
import dev.sadking.folio.core.stats.SessionSource
import dev.sadking.folio.core.stats.StatsBook
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.StatsLabel
import dev.sadking.folio.data.StatsSnapshot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Stats mapper on the JVM (Robolectric for the string resources only): a real [ReadingStats]
 * built from fake sessions, the snapshot around it, and what [StatsUi] makes of it. Zone
 * Europe/Madrid; today is Saturday 17 October 2026.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StatsUiTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val today = LocalDate.of(2026, 10, 17)
    private val text = StatsText(RuntimeEnvironment.getApplication().resources)

    private fun session(
        id: String,
        book: String,
        date: LocalDate,
        minutes: Long,
        hour: Int = 20,
        source: SessionSource = SessionSource.KINDLE,
        device: String = "ereader:k",
        progressStart: Double? = null,
        progressEnd: Double? = null,
        progressRead: Double? = null,
        pages: Int? = null,
    ) =
        ReadingSession(
            id = id,
            source = source,
            deviceKey = device,
            bookId = book,
            document = null,
            startMs = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(),
            durationSec = minutes * 60,
            progressStart = progressStart,
            progressEnd = progressEnd,
            progressRead = progressRead,
            pages = pages,
        )

    private fun snapshot(
        sessions: List<ReadingSession>,
        finishes: List<BookFinish> = emptyList(),
        books: List<StatsBook> = emptyList(),
        labels: Map<String, StatsLabel> = emptyMap(),
        deviceNames: Map<String, String> = emptyMap(),
        goals: GoalSettings = GoalSettings(),
        waitingForEreader: Boolean = false,
        hasEreaderSessions: Boolean = sessions.any { it.source == SessionSource.KINDLE },
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    ) =
        StatsSnapshot(
            stats =
                ReadingStats(
                    sessions,
                    books,
                    finishes,
                    zone = zone,
                    today = today,
                    firstDayOfWeek = firstDayOfWeek,
                ),
            labels = labels,
            deviceNames = deviceNames,
            goals = goals,
            waitingForEreader = waitingForEreader,
            hasEreaderSessions = hasEreaderSessions,
        )

    private fun libraryBook(id: String, title: String) =
        Book(
            id,
            title,
            "[\"Ines Marlow\"]",
            originalPath = "",
            currentPath = "",
            originalHash = "",
            currentHash = "",
        )

    private fun label(id: String, title: String) =
        libraryBook(id, title).let { StatsLabel(title, "Ines Marlow", it, null) }

    private fun oct(day: Int) = LocalDate.of(2026, 10, day)

    private fun sep(day: Int) = LocalDate.of(2026, 9, day)

    // ---- Ranges and the hero ----

    @Test
    fun monthTotalsDaysAndColumnLabels() {
        val snap =
            snapshot(
                listOf(
                    session("1", "a", oct(1), 40),
                    session("2", "a", oct(2), 25, source = SessionSource.PHONE, device = "phone:p"),
                    session(
                        "3",
                        "b",
                        oct(3),
                        30,
                        source = SessionSource.LISTEN,
                        device = "phone:p",
                    ),
                )
            )
        val overview = StatsUi.overview(snap, StatsRange.MONTH, text)
        assertEquals(65 * 60L, overview.readingSeconds)
        assertEquals(30 * 60L, overview.listeningSeconds)
        assertEquals(2, overview.daysRead)
        assertEquals(31, overview.daysInRange)
        assertEquals(2, overview.sessions)
        assertEquals(31, overview.columns.size)
        assertEquals((1..31).map { it.toString() }, overview.columns.map { it.label })
        assertEquals(
            listOf(1, 8, 15, 22, 29),
            overview.columns.mapIndexedNotNull { i, c -> (i + 1).takeIf { c.labelled } },
        )
        assertEquals(40 * 60L, overview.columns[0].readingSeconds)
        assertEquals(30 * 60L, overview.columns[2].listeningSeconds)
    }

    @Test
    fun weekStartsOnTheFirstDayOfTheWeek() {
        val sessions = listOf(session("1", "a", oct(11), 30), session("2", "a", oct(12), 20))
        val monday = snapshot(sessions)
        assertEquals(
            DateRange(oct(12), oct(18)),
            StatsUi.rangeDates(monday.stats, StatsRange.WEEK),
        )
        val mondayWeek = StatsUi.overview(monday, StatsRange.WEEK, text)
        assertEquals(20 * 60L, mondayWeek.readingSeconds)
        assertEquals(7, mondayWeek.columns.size)
        assertEquals("M", mondayWeek.columns.first().label)

        val sunday = snapshot(sessions, firstDayOfWeek = DayOfWeek.SUNDAY)
        assertEquals(
            DateRange(oct(11), oct(17)),
            StatsUi.rangeDates(sunday.stats, StatsRange.WEEK),
        )
        val sundayWeek = StatsUi.overview(sunday, StatsRange.WEEK, text)
        assertEquals(50 * 60L, sundayWeek.readingSeconds)
        assertEquals("S", sundayWeek.columns.first().label)
    }

    @Test
    fun yearAndAllRanges() {
        val snap =
            snapshot(
                listOf(
                    session("1", "a", LocalDate.of(2024, 5, 3), 60),
                    session("2", "a", LocalDate.of(2026, 2, 3), 30),
                    session("3", "a", oct(1), 45),
                )
            )
        assertEquals(DateRange.of(2026), StatsUi.rangeDates(snap.stats, StatsRange.YEAR))
        assertEquals(
            DateRange(LocalDate.of(2024, 5, 3), today),
            StatsUi.rangeDates(snap.stats, StatsRange.ALL),
        )
        val year = StatsUi.overview(snap, StatsRange.YEAR, text)
        assertEquals(75 * 60L, year.readingSeconds)
        assertEquals(12, year.columns.size)
        assertEquals(30 * 60L, year.columns[1].readingSeconds)
        val all = StatsUi.overview(snap, StatsRange.ALL, text)
        assertEquals(listOf("2024", "2025", "2026"), all.columns.map { it.label })
        assertEquals(listOf(60 * 60L, 0L, 75 * 60L), all.columns.map { it.readingSeconds })
        assertNull(all.trendPercent)
    }

    @Test
    fun theTrendComparesTheSameElapsedDays() {
        val snap =
            snapshot(
                listOf(
                    session("1", "a", sep(10), 60),
                    // After the 17th: not part of the same elapsed days.
                    session("2", "a", sep(20), 500),
                    session("3", "a", oct(10), 90),
                )
            )
        val month = StatsUi.overview(snap, StatsRange.MONTH, text)
        assertEquals(50, month.trendPercent)
        assertEquals("September", month.previousRangeName)
        assertEquals(
            DateRange(sep(1), sep(17)),
            StatsUi.previousDates(snap.stats, StatsRange.MONTH),
        )
        assertEquals(50, StatsUi.habits(snap, StatsRange.MONTH, text).trendPercent)
    }

    @Test
    fun theTrendIsNullAgainstAnEmptyPeriodAndForAll() {
        val snap = snapshot(listOf(session("1", "a", oct(14), 90)))
        assertNull(StatsUi.overview(snap, StatsRange.MONTH, text).trendPercent)
        assertNull(StatsUi.overview(snap, StatsRange.WEEK, text).trendPercent)
        assertNull(StatsUi.overview(snap, StatsRange.YEAR, text).trendPercent)
        assertNull(StatsUi.previousDates(snap.stats, StatsRange.ALL))
    }

    @Test
    fun previousDatesAndNamesPerRange() {
        val snap = snapshot(listOf(session("1", "a", oct(10), 90)))
        assertEquals(
            DateRange(oct(5), oct(10)),
            StatsUi.previousDates(snap.stats, StatsRange.WEEK),
        )
        assertEquals(
            DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 10, 17)),
            StatsUi.previousDates(snap.stats, StatsRange.YEAR),
        )
        assertEquals("last week", StatsUi.overview(snap, StatsRange.WEEK, text).previousRangeName)
        assertEquals("2025", StatsUi.overview(snap, StatsRange.YEAR, text).previousRangeName)
    }

    @Test
    fun aFallIsANegativePercent() {
        val snap = snapshot(listOf(session("1", "a", sep(10), 60), session("2", "a", oct(10), 30)))
        assertEquals(-50, StatsUi.overview(snap, StatsRange.MONTH, text).trendPercent)
    }

    @Test
    fun columnDateForWeekAndMonthOnly() {
        val stats = snapshot(emptyList()).stats
        assertEquals(oct(12), StatsUi.columnDate(stats, StatsRange.WEEK, 0))
        assertEquals(oct(18), StatsUi.columnDate(stats, StatsRange.WEEK, 6))
        assertNull(StatsUi.columnDate(stats, StatsRange.WEEK, 7))
        assertEquals(oct(1), StatsUi.columnDate(stats, StatsRange.MONTH, 0))
        assertEquals(oct(31), StatsUi.columnDate(stats, StatsRange.MONTH, 30))
        assertNull(StatsUi.columnDate(stats, StatsRange.MONTH, 31))
        assertNull(StatsUi.columnDate(stats, StatsRange.YEAR, 3))
        assertNull(StatsUi.columnDate(stats, StatsRange.ALL, 0))
    }

    // ---- Calendar and day ----

    @Test
    fun theCalendarKeepsTheCoresSlots() {
        // Nine books, one on each of the first nine days: the ninth takes the Other slot.
        val sessions = (1..9).map { session("s$it", "book$it", oct(it), 30) }
        val snap = snapshot(sessions)
        val calendar = StatsUi.calendar(snap, YearMonth.of(2026, 10), text)
        assertEquals("October 2026", calendar.title)
        assertEquals(3, calendar.firstWeekday)
        assertEquals(31, calendar.days.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, OTHER_SLOT), calendar.books.map { it.slot })
        assertEquals(OTHER_SLOT, calendar.days[8].bars.single().slot)
        assertEquals(1, calendar.days[1].bars.single().slot)
        assertEquals(30 * 60L, calendar.books[0].seconds)
        assertEquals("book1", calendar.books[0].book.id)
    }

    @Test
    fun aListeningOnlyBarIsHollowAndSumsBothKindsOfSeconds() {
        val snap =
            snapshot(
                listOf(
                    session("1", "a", oct(1), 30, 8),
                    session("2", "a", oct(1), 15, 12, SessionSource.LISTEN, "phone:p"),
                    session("3", "b", oct(2), 20, 8, SessionSource.LISTEN, "phone:p"),
                )
            )
        val calendar = StatsUi.calendar(snap, YearMonth.of(2026, 10), text)
        val first = calendar.days[0].bars.single()
        assertEquals(45 * 60L, first.seconds)
        assertFalse(first.listening)
        assertTrue(calendar.days[1].bars.single().listening)
    }

    @Test
    fun todayIsSetOnlyInTheCurrentMonth() {
        val snap = snapshot(listOf(session("1", "a", sep(3), 30)))
        assertEquals(17, StatsUi.calendar(snap, YearMonth.of(2026, 10), text).today)
        assertNull(StatsUi.calendar(snap, YearMonth.of(2026, 9), text).today)
        assertEquals("September 2026", StatsUi.calendar(snap, YearMonth.of(2026, 9), text).title)
        assertEquals(1, StatsUi.calendar(snap, YearMonth.of(2026, 9), text).firstWeekday)
    }

    @Test
    fun aDayListsItsSessionsWithHourSourceAndPages() {
        val snap =
            snapshot(
                listOf(
                    session("1", "a", oct(7), 48, hour = 22, pages = 22),
                    session("2", "b", oct(7), 20, 7, SessionSource.PHONE, "phone:p"),
                    session("3", "c", oct(7), 16, 13, SessionSource.LISTEN, "phone:p"),
                ),
                deviceNames = mapOf("ereader:k" to "Kindle"),
            )
        val day = StatsUi.day(snap, oct(7), text)
        assertEquals(7, day.day)
        assertEquals("Wednesday 7 October", day.title)
        assertEquals(84 * 60L, day.totalSeconds)
        assertEquals(listOf("b", "c", "a"), day.sessions.map { it.book.id })
        assertEquals(listOf("Phone", "Listening", "Kindle"), day.sessions.map { it.source })
        assertEquals(listOf(7f, 13f, 22f), day.sessions.map { it.startHour })
        assertEquals(listOf(null, null, 22), day.sessions.map { it.pages })
        assertEquals(listOf(false, true, false), day.sessions.map { it.listening })
        assertEquals("Today", StatsUi.day(snap, today, text).title)
    }

    @Test
    fun aSessionStartingPartWayThroughAnHourHasAFractionalStart() {
        val s = session("1", "a", oct(7), 10, hour = 9)
        val shifted = s.copy(startMs = s.startMs + 30 * 60_000)
        val day = StatsUi.day(snapshot(listOf(shifted)), oct(7), text)
        assertEquals(9.5f, day.sessions.single().startHour)
    }

    // ---- Labels and sources ----

    @Test
    fun labels() {
        val book = libraryBook("a", "The Orchard")
        val snap =
            snapshot(
                emptyList(),
                labels =
                    mapOf(
                        "a" to StatsLabel("The Orchard", "Ines Marlow", book, null),
                        "doc:abc" to StatsLabel("Night Trains", "Tom Aldous", null, "/c.jpg"),
                    ),
            )
        assertEquals(book, StatsUi.label(snap, "a", text))

        val doc = StatsUi.label(snap, "doc:abc", text)
        assertEquals("doc:abc", doc.id)
        assertEquals("Night Trains", doc.title)
        assertEquals("""["Tom Aldous"]""", doc.authors)
        assertEquals("/c.jpg", doc.coverPath)

        val unknownDoc = StatsUi.label(snap, "doc:zzz", text)
        assertEquals("doc:zzz", unknownDoc.id)
        assertEquals("A book on your e-reader", unknownDoc.title)
        assertEquals("[]", unknownDoc.authors)

        val unknownHardcover = StatsUi.label(snap, "hc:42", text)
        assertEquals("hc:42", unknownHardcover.id)
        assertEquals("A book from Hardcover", unknownHardcover.title)
    }

    @Test
    fun sourcesAreNamedByDevice() {
        val kindle = session("1", "a", oct(1), 10)
        val unnamed = session("2", "a", oct(1), 10, device = "ereader:other")
        val phone = session("3", "a", oct(1), 10, source = SessionSource.PHONE, device = "phone:p")
        val listen =
            session("4", "a", oct(1), 10, source = SessionSource.LISTEN, device = "phone:p")
        val snap =
            snapshot(
                listOf(kindle, unnamed, phone, listen),
                deviceNames = mapOf("ereader:k" to "Kindle Paperwhite"),
            )
        assertEquals("Kindle Paperwhite", StatsUi.source(snap, kindle, text))
        assertEquals("E-reader", StatsUi.source(snap, unnamed, text))
        assertEquals("Phone", StatsUi.source(snap, phone, text))
        assertEquals("Listening", StatsUi.source(snap, listen, text))
    }

    // ---- Habits and the Books row ----

    @Test
    fun habitsStreakListeningShareAndSessions() {
        val snap =
            snapshot(
                listOf(
                    session("1", "a", oct(16), 30, hour = 21),
                    session("2", "a", oct(17), 90, hour = 21),
                    session("3", "b", oct(17), 40, 8, SessionSource.LISTEN, "phone:p"),
                    session("4", "a", oct(1), 30, hour = 6),
                )
            )
        val habits = StatsUi.habits(snap, StatsRange.MONTH, text)
        assertEquals(2, habits.streakDays)
        assertEquals(2, habits.longestStreakDays)
        assertEquals("October 2026", habits.longestStreakWhen)
        assertEquals(24, habits.byHour.size)
        assertEquals(90 * 60L, habits.byHour[21])
        assertEquals(30 * 60L, habits.byHour[22])
        assertEquals(7, habits.byWeekday.size)
        // 17 October 2026 is a Saturday: index 5.
        assertEquals(90 * 60L, habits.byWeekday[5])
        assertEquals(30 * 60L, habits.typicalSessionSeconds)
        assertEquals(90 * 60L, habits.longestSessionSeconds)
        assertEquals(40 * 60L, habits.listeningSeconds)
        // 40 of 190 minutes.
        assertEquals(21, habits.listeningShare)
    }

    @Test
    fun habitsListeningShareIsZeroWithNothing() {
        val snap = snapshot(listOf(session("1", "a", sep(1), 30)))
        val habits = StatsUi.habits(snap, StatsRange.WEEK, text)
        assertEquals(0, habits.listeningShare)
        assertEquals(0L, habits.typicalSessionSeconds)
        assertEquals(0L, habits.longestSessionSeconds)
    }

    @Test
    fun rangeBooksAreLongestFirstAndInsideTheRange() {
        val snap =
            snapshot(
                listOf(
                    session("1", "a", oct(1), 30),
                    session("2", "b", oct(2), 50),
                    session("3", "b", oct(3), 10, 8, SessionSource.LISTEN, "phone:p"),
                    session("4", "c", sep(30), 120),
                ),
                labels = mapOf("a" to label("a", "A"), "b" to label("b", "B")),
            )
        val month = StatsUi.rangeBooks(snap, StatsRange.MONTH, text)
        assertEquals(listOf("b", "a"), month.map { it.book.id })
        assertEquals(listOf(60 * 60L, 30 * 60L), month.map { it.seconds })
        val all = StatsUi.rangeBooks(snap, StatsRange.ALL, text)
        assertEquals(listOf("c", "b", "a"), all.map { it.book.id })
        assertEquals("A book on your e-reader", all.first().book.title)
        assertTrue(StatsUi.rangeBooks(snap, StatsRange.WEEK, text).isEmpty())
    }

    // ---- Milestones ----

    @Test
    fun milestonesShowTheChosenOnesNewestFirstInDayMonthYear() {
        // Eight days in a row, 13 hours each: a 7-day streak on 7 Sep, 100 hours on 8 Sep.
        val sessions =
            (1..8).map { session("s$it", "a", sep(it), 13 * 60, hour = 6, progressStart = 0.0) } +
                session("l", "b", sep(3), 20, 21, SessionSource.LISTEN, "phone:p")
        val snap =
            snapshot(
                sessions,
                finishes = listOf(BookFinish("a", sep(5), FinishSource.LIBRARY)),
                labels = mapOf("a" to label("a", "The Orchard"), "b" to label("b", "Night Trains")),
            )
        val milestones = StatsUi.milestones(snap, text)
        val byKind = milestones.groupBy { it.kind }

        // Hours 1, 10 and 50 are hidden; 100 shows.
        val hours = byKind.getValue(MilestoneKind.HOURS).single()
        assertEquals("100 hours", hours.title)
        assertEquals("Reading time", hours.detail)
        assertEquals("08/09/2026", hours.whenText)

        val first = byKind.getValue(MilestoneKind.FIRST_FINISH).single()
        assertEquals("First book finished", first.title)
        assertEquals("The Orchard", first.detail)
        assertEquals("05/09/2026", first.whenText)

        val streaks = byKind.getValue(MilestoneKind.STREAK)
        val badge = streaks.single { it.title == "7-day streak" }
        assertEquals("Reached", badge.detail)
        assertEquals("07/09/2026", badge.whenText)
        val record = streaks.single { it.title == "Longest streak" }
        assertEquals("8 days", record.detail)
        assertEquals("08/09/2026", record.whenText)

        val fastest = byKind.getValue(MilestoneKind.FASTEST).single()
        assertEquals("The Orchard in 5 days", fastest.detail)
        assertEquals("05/09/2026", fastest.whenText)

        val biggest = byKind.getValue(MilestoneKind.BIGGEST_DAY).single()
        assertEquals("13 h", biggest.detail)
        assertEquals("01/09/2026", biggest.whenText)

        val listen = byKind.getValue(MilestoneKind.FIRST_LISTEN).single()
        assertEquals("Night Trains", listen.detail)
        assertEquals("03/09/2026", listen.whenText)

        // Newest first.
        val dates = milestones.map { it.whenText.split("/").reversed().joinToString("") }
        assertEquals(dates.sortedDescending(), dates)
        assertEquals(7, milestones.size)
    }

    @Test
    fun booksOneIsTheFirstFinishAndTenIsABadge() {
        val finishes =
            (1..10).map { BookFinish("book$it", sep(it), FinishSource.LIBRARY) } +
                BookFinish("book11", sep(12), FinishSource.LIBRARY)
        val snap =
            snapshot(
                listOf(session("1", "book1", sep(1), 30)),
                finishes = finishes,
                labels = mapOf("book1" to label("book1", "The Orchard")),
            )
        val milestones = StatsUi.milestones(snap, text)
        val books = milestones.filter { it.kind == MilestoneKind.BOOKS }
        assertEquals(listOf("10 books"), books.map { it.title })
        assertEquals("All time", books.single().detail)
        assertEquals("10/09/2026", books.single().whenText)
        val first = milestones.single { it.kind == MilestoneKind.FIRST_FINISH }
        assertEquals("The Orchard", first.detail)
        // A book 25 would be next; nothing else is invented.
        assertFalse(milestones.any { it.title == "1 books" })
    }

    @Test
    fun noMilestonesWithoutData() {
        assertTrue(StatsUi.milestones(snapshot(emptyList()), text).isEmpty())
        // A single short session reaches hour 1 and the first reading day, but neither shows.
        val one = snapshot(listOf(session("1", "a", oct(1), 90)))
        assertEquals(
            listOf(MilestoneKind.BIGGEST_DAY),
            StatsUi.milestones(one, text).map { it.kind },
        )
    }

    // ---- Years ----

    @Test
    fun emptyWithAndWithoutHardcoverYears() {
        val none =
            StatsUi.state(snapshot(emptyList()), StatsRange.MONTH, YearMonth.of(2026, 10), text)
        assertEquals(StatsState.Empty(), none)

        val hardcover =
            snapshot(
                emptyList(),
                finishes =
                    listOf(
                        BookFinish("hc:1", LocalDate.of(2024, 3, 1), FinishSource.HARDCOVER),
                        BookFinish("hc:2", LocalDate.of(2025, 4, 1), FinishSource.HARDCOVER),
                        BookFinish("hc:3", LocalDate.of(2025, 5, 1), FinishSource.HARDCOVER),
                    ),
            )
        val state = StatsUi.state(hardcover, StatsRange.MONTH, YearMonth.of(2026, 10), text)
        val empty = state as StatsState.Empty
        assertEquals(listOf(2025, 2024), empty.years.map { it.year })
        assertEquals(listOf(2, 1), empty.years.map { it.books })
        assertEquals(listOf(0L, 0L), empty.years.map { it.seconds })
        assertTrue(empty.years.all { it.fromHardcover })
    }

    @Test
    fun yearsListYearsWithReadingNewestFirst() {
        val snap =
            snapshot(
                listOf(
                    session("1", "a", LocalDate.of(2024, 5, 1), 60),
                    session("2", "a", LocalDate.of(2024, 5, 2), 60),
                    session("3", "a", oct(1), 30),
                ),
                finishes =
                    listOf(BookFinish("hc:9", LocalDate.of(2025, 2, 1), FinishSource.HARDCOVER)),
            )
        val years = StatsUi.years(snap)
        assertEquals(listOf(2026, 2025, 2024), years.map { it.year })
        assertEquals(listOf(30 * 60L, 0L, 120 * 60L), years.map { it.seconds })
        assertEquals(listOf(1, 0, 2), years.map { it.longestStreak })
        assertEquals(listOf(false, true, false), years.map { it.fromHardcover })
        assertEquals(listOf(0, 1, 0), years.map { it.books })
    }

    @Test
    fun phoneOnlyAndSyncPending() {
        val phone = session("1", "a", oct(1), 30, source = SessionSource.PHONE, device = "phone:p")
        val kindle = session("2", "a", oct(2), 30)
        fun ready(snap: StatsSnapshot) =
            StatsUi.state(snap, StatsRange.MONTH, YearMonth.of(2026, 10), text) as StatsState.Ready

        val onlyPhone = ready(snapshot(listOf(phone)))
        assertTrue(onlyPhone.phoneOnly)
        assertFalse(onlyPhone.syncPending)

        val pending = ready(snapshot(listOf(phone), waitingForEreader = true))
        assertFalse(pending.phoneOnly)
        assertTrue(pending.syncPending)

        val both = ready(snapshot(listOf(phone, kindle)))
        assertFalse(both.phoneOnly)
        assertFalse(both.syncPending)
        assertEquals(StatsRange.MONTH, both.overview.range)
        assertEquals("October 2026", both.month.title)
    }

    @Test
    fun aMappingThatThrowsGivesFailedWithTheRetry() {
        val broken =
            object : HashMap<String, StatsLabel>() {
                override fun get(key: String): StatsLabel? = error("boom")
            }
        var retried = false
        val state =
            StatsUi.state(
                snapshot(listOf(session("1", "a", oct(1), 30)), labels = broken),
                StatsRange.MONTH,
                YearMonth.of(2026, 10),
                text,
            ) {
                retried = true
            }
        (state as StatsState.Failed).retry()
        assertTrue(retried)
    }

    // ---- Goals ----

    @Test
    fun goalsFloorThePaceAndCountOnlyReading() {
        val snap =
            snapshot(
                listOf(
                    session("1", "a", today, 20, hour = 8),
                    session("2", "b", today, 40, 12, SessionSource.LISTEN, "phone:p"),
                ),
                finishes = (1..3).map { BookFinish("f$it", oct(it), FinishSource.LIBRARY) },
                goals = GoalSettings(booksPerYear = 24, minutesPerDay = 30),
            )
        val goals = StatsUi.goals(snap)
        // 24 x 290 / 365 = 19.07.
        assertEquals(19, goals.booksOnPace)
        assertEquals(3, goals.booksSoFar)
        assertEquals(20, goals.minutesToday)
        assertTrue(goals.on)

        val off = StatsUi.goals(snapshot(listOf(session("1", "a", today, 25))))
        assertFalse(off.on)
        assertEquals(25, off.minutesToday)
        assertEquals(0, off.booksSoFar)
    }

    @Test
    fun goalsRoundTripToSettings() {
        val settings = GoalSettings(booksPerYear = 12, minutesPerDay = null)
        val goals = StatsUi.goals(snapshot(emptyList(), goals = settings))
        assertEquals(settings, goals.toSettings())
        assertEquals(GoalSettings(), StatsUi.goals(snapshot(emptyList())).toSettings())
    }

    // ---- One book ----

    @Test
    fun aBookWithOnlyAFinishHasNoPage() {
        val snap =
            snapshot(
                listOf(session("1", "a", oct(1), 30)),
                finishes = listOf(BookFinish("hc:1", sep(1), FinishSource.HARDCOVER)),
            )
        assertNull(StatsUi.book(snap, "hc:1", text))
        assertNull(StatsUi.book(snap, "missing", text))
        assertNotNull(StatsUi.book(snap, "a", text))
    }

    @Test
    fun aBookNamesItsDevicesAndKeepsPagesNull() {
        val snap =
            snapshot(
                listOf(
                    session(
                        "1",
                        "a",
                        oct(3),
                        120,
                        hour = 20,
                        pages = 40,
                        progressStart = 0.0,
                        progressEnd = 0.2,
                    ),
                    session(
                        "2",
                        "a",
                        oct(5),
                        20,
                        hour = 7,
                        source = SessionSource.PHONE,
                        device = "phone:p",
                        progressEnd = 0.3,
                    ),
                    session(
                        "3",
                        "a",
                        oct(5),
                        10,
                        18,
                        SessionSource.LISTEN,
                        "phone:p",
                        progressEnd = 0.35,
                    ),
                    session("4", "a", oct(9), 60, hour = 20, progressEnd = 0.5),
                ),
                deviceNames = mapOf("ereader:k" to "Kindle Paperwhite"),
                labels = mapOf("a" to label("a", "The Orchard")),
            )
        val stats = StatsUi.book(snap, "a", text)!!
        assertEquals("The Orchard", stats.book.title)
        assertEquals(200 * 60L, stats.readingSeconds)
        assertEquals(10 * 60L, stats.listeningSeconds)
        assertEquals(4, stats.sessions)
        assertEquals("3 Oct", stats.startedText)
        assertNull(stats.finishedText)
        assertEquals(15, stats.days)
        assertEquals(0.5f, stats.progress)
        assertEquals(
            listOf("Kindle Paperwhite" to 180 * 60L, "Phone" to 20 * 60L, "Listening" to 10 * 60L),
            stats.byDevice,
        )
        // Latest first.
        assertEquals(
            listOf(60 * 60L, 10 * 60L, 20 * 60L, 120 * 60L),
            stats.recent.map { it.seconds },
        )
        assertEquals(listOf(null, null, null, 40), stats.recent.map { it.pages })
        assertEquals(listOf(false, true, false, false), stats.recent.map { it.listening })
        // x is days from the first day's midnight; listening is hollow.
        assertEquals(4, stats.timeline.size)
        assertEquals(listOf(false, false, true, false), stats.timeline.map { it.listening })
        assertEquals(listOf(0.2f, 0.3f, 0.35f, 0.5f), stats.timeline.map { it.progress })
        assertEquals(0.916f, stats.timeline.first().day, 0.01f)
    }

    @Test
    fun aFinishedBookKnowsItsDays() {
        val snap =
            snapshot(
                listOf(
                    session("1", "a", oct(1), 60, progressStart = 0.0, progressEnd = 0.5),
                    session("2", "a", oct(4), 60, progressEnd = 1.0),
                ),
                finishes = listOf(BookFinish("a", oct(4), FinishSource.LIBRARY)),
            )
        val stats = StatsUi.book(snap, "a", text)!!
        assertEquals("4 Oct", stats.finishedText)
        assertEquals(4, stats.days)
        assertNull(stats.secondsLeft)
        assertEquals(1f, stats.progress)
    }

    // ---- Year in review ----

    @Test
    fun yearReviewByMonthAndRecordsWithoutNulls() {
        val snap =
            snapshot(
                listOf(session("1", "a", oct(1), 90, hour = 22)),
                finishes =
                    listOf(
                        BookFinish("a", oct(4), FinishSource.LIBRARY),
                        BookFinish("hc:b", LocalDate.of(2026, 2, 4), FinishSource.HARDCOVER),
                        BookFinish("hc:c", LocalDate.of(2026, 2, 20), FinishSource.HARDCOVER),
                    ),
                labels = mapOf("a" to label("a", "The Orchard")),
            )
        val review = StatsUi.yearReview(snap, 2026, text)
        assertEquals(2026, review.year)
        assertEquals(3, review.books)
        assertEquals(listOf(0, 2, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0), review.byMonth)
        assertEquals(90 * 60L, review.readingSeconds)
        assertEquals("10 pm", review.favouriteHour)
        assertNull(review.wordsPerMinute)
        assertEquals(listOf("The Orchard"), review.topBooks.map { it.book.title })
        // One reading day is no streak, and nothing was finished fast: only two records.
        assertEquals(
            listOf("Longest session" to "1 h 30 min", "Biggest day" to "1 h 30 min"),
            review.records,
        )
        assertEquals(1, review.longestStreak)
        // Hardcover's 4 Feb finish is the first book; the badge names it as Hardcover does.
        assertEquals(
            listOf("First book finished" to "A book from Hardcover · 4 Feb"),
            review.firsts,
        )
    }

    @Test
    fun yearReviewWithNothingIsAllEmpty() {
        val review = StatsUi.yearReview(snapshot(emptyList()), 2026, text)
        assertEquals(0, review.books)
        assertEquals("—", review.favouriteHour)
        assertTrue(review.records.isEmpty())
        assertTrue(review.firsts.isEmpty())
        assertTrue(review.topBooks.isEmpty())
        assertEquals(List(12) { 0 }, review.byMonth)
    }

    @Test
    fun yearReviewFirstsAndFastestRecord() {
        val sessions =
            (1..8).map { session("s$it", "a", sep(it), 13 * 60, hour = 6, progressStart = 0.0) }
        val snap =
            snapshot(
                sessions,
                finishes = listOf(BookFinish("a", sep(5), FinishSource.LIBRARY)),
                labels = mapOf("a" to label("a", "The Orchard")),
            )
        val review = StatsUi.yearReview(snap, 2026, text)
        assertEquals(
            listOf("First book finished" to "The Orchard · 5 Sep", "First 100 hours" to "8 Sep"),
            review.firsts,
        )
        assertEquals(
            listOf(
                "Longest session" to "13 h",
                "Biggest day" to "13 h",
                "Longest streak" to "8 days",
                "Fastest finish" to "The Orchard · 5 days",
            ),
            review.records,
        )
        assertEquals(8, review.longestStreak)
        // Another year has none of these.
        assertTrue(StatsUi.yearReview(snap, 2025, text).records.isEmpty())
    }
}
