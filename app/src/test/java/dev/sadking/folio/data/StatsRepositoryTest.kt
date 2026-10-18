package dev.sadking.folio.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.core.stats.BookFinish
import dev.sadking.folio.core.stats.FinishSource
import dev.sadking.folio.core.stats.GoalSettings
import dev.sadking.folio.sync.EreaderSync
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The statistics snapshot (c) over an in-memory Room database, with goals in preferences. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StatsRepositoryTest {
    private lateinit var db: LibraryDatabase
    private lateinit var prefs: SharedPreferences
    private lateinit var goals: StatsGoals
    private val zone = ZoneId.of("Europe/Madrid")
    private val today = LocalDate.of(2026, 10, 17)
    private val now = at(today, 12, 0)

    private fun at(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        prefs = context.getSharedPreferences(StatsGoals.PREFS, Context.MODE_PRIVATE)
        goals = StatsGoals(prefs)
    }

    @After
    fun close() {
        db.close()
    }

    private fun snapshot(
        ereaders: List<EreaderSync> = emptyList(),
        roster: DeviceRoster = DeviceRoster.EMPTY,
    ) = runBlocking {
        StatsRepository(
                db.stats(),
                db.library(),
                flowOf(roster),
                flowOf(ereaders),
                goals,
                { zone },
                { now },
            )
            .snapshot()
            .first()
    }

    private fun addBook(id: String, status: String = "Unread", statusAt: Long = 0) = runBlocking {
        db.library()
            .insert(
                Book(
                    id = id,
                    title = "Title $id",
                    authors = encode(listOf("Author $id")),
                    originalPath = "/books/$id.epub",
                    currentPath = "/books/$id.epub",
                    originalHash = "orig-$id",
                    currentHash = "rev-$id",
                    status = status,
                    statusAt = statusAt,
                )
            )
    }

    private fun digest(digest: String, bookId: String) = runBlocking {
        db.stats().upsertDigests(listOf(KoreaderDigestRow(digest, bookId, "rev-$bookId", now)))
    }

    private fun session(
        id: String,
        source: String,
        deviceKey: String,
        bookId: String? = null,
        document: String? = null,
        startMs: Long = now - 3_600_000,
        seconds: Long = 900,
        progressRead: Double? = null,
    ) = runBlocking {
        db.stats()
            .upsertSessions(
                listOf(
                    ReadingSessionRow(
                        id = id,
                        source = source,
                        deviceKey = deviceKey,
                        bookId = bookId,
                        document = document,
                        startMs = startMs,
                        durationSec = seconds,
                        progressStart = null,
                        progressEnd = null,
                        progressRead = progressRead,
                        pages = null,
                        updatedAt = now,
                    )
                )
            )
    }

    private fun kindle(id: String, document: String, startMs: Long = now - 3_600_000) =
        session(id, "KINDLE", "ereader:k1", document = document, startMs = startMs)

    private fun wish(
        id: String,
        hardcoverId: Int? = null,
        linkedBookId: String? = null,
        status: String = "",
        statusAt: Long = 0,
    ) = runBlocking {
        db.library()
            .insertWish(
                WishBook(
                    id = id,
                    catalogKey = "catalogue-$id",
                    title = "Wish $id",
                    authors = encode(listOf("Someone")),
                    hardcoverId = hardcoverId,
                    linkedBookId = linkedBookId,
                    status = status,
                    statusAt = statusAt,
                )
            )
    }

    private fun read(readId: Int, hardcoverId: Int, finishedOn: String?) = runBlocking {
        db.stats()
            .upsertReads(
                listOf(
                    HardcoverReadRow(
                        accountId = 1,
                        readId = readId,
                        hardcoverId = hardcoverId,
                        startedOn = null,
                        finishedOn = finishedOn,
                        fetchedAt = now,
                    )
                )
            )
    }

    @Test
    fun aKindleSessionWithAKnownDigestCountsForItsBook() {
        addBook("b1")
        digest("d1", "b1")
        kindle("k-1", document = "d1")

        val snap = snapshot()

        assertEquals(1, snap.stats.book("b1")!!.sessions)
        assertNull(snap.stats.book("doc:d1"))
        assertEquals("Title b1", snap.labels["b1"]?.title)
        assertEquals(today, snap.stats.today)
    }

    @Test
    fun aKindleSessionWithAnUnknownDigestStaysItsOwnBookWithoutAName() {
        kindle("k-1", document = "d9")

        val snap = snapshot()

        assertEquals(1, snap.stats.book("doc:d9")!!.sessions)
        assertNull(snap.labels["doc:d9"])
    }

    @Test
    fun aNameKOReaderSentLabelsAnUnmatchedBook() {
        kindle("k-1", document = "d9")
        runBlocking {
            db.stats().upsertBookNames(listOf(StatsBookNameRow("d9", "Dune", "Frank Herbert", now)))
        }

        assertEquals(
            StatsLabel("Dune", "Frank Herbert", null, null),
            snapshot().labels["doc:d9"],
        )
    }

    @Test
    fun aFinishedBookCountsOnItsLocalDate() {
        // 00:30 on 11 October in Madrid is still 10 October in UTC.
        addBook("b1", status = "Finished", statusAt = at(LocalDate.of(2026, 10, 11), 0, 30))
        addBook("b2", status = "Finished", statusAt = 0)

        assertEquals(
            listOf(BookFinish("b1", LocalDate.of(2026, 10, 11), FinishSource.LIBRARY)),
            snapshot().stats.finishes,
        )
    }

    @Test
    fun aHardcoverReadOfALinkedBookIsKeyedByTheBook() {
        addBook("b1")
        wish("w1", hardcoverId = 42, linkedBookId = "b1")
        read(readId = 7, hardcoverId = 42, finishedOn = "2026-03-02")

        assertEquals(
            setOf(BookFinish("b1", LocalDate.of(2026, 3, 2), FinishSource.HARDCOVER)),
            snapshot().stats.finishes.toSet(),
        )
    }

    @Test
    fun aHardcoverReadWithoutABookIsKeyedByItsEntryOrItsHardcoverId() {
        wish("w2", hardcoverId = 43)
        read(readId = 8, hardcoverId = 43, finishedOn = "2026-02-01")
        read(readId = 9, hardcoverId = 44, finishedOn = "2026-01-05")
        read(readId = 10, hardcoverId = 45, finishedOn = null)

        assertEquals(
            setOf(
                BookFinish("w2", LocalDate.of(2026, 2, 1), FinishSource.HARDCOVER),
                BookFinish("hc:44", LocalDate.of(2026, 1, 5), FinishSource.HARDCOVER),
            ),
            snapshot().stats.finishes.toSet(),
        )
    }

    @Test
    fun aFinishedCatalogueEntryCountsOnlyWhenItsBookIsNotInTheLibrary() {
        addBook("b1")
        wish(
            "w3",
            linkedBookId = "b1",
            status = "Finished",
            statusAt = at(LocalDate.of(2026, 5, 1), 10, 0),
        )
        wish("w4", status = "Finished", statusAt = at(LocalDate.of(2026, 6, 1), 10, 0))

        assertEquals(
            listOf(BookFinish("w4", LocalDate.of(2026, 6, 1), FinishSource.LIBRARY)),
            snapshot().stats.finishes,
        )
    }

    @Test
    fun goalsRoundTripAndAZeroGoalIsOff() {
        goals.save(GoalSettings(booksPerYear = 12, minutesPerDay = 30))

        assertEquals(GoalSettings(12, 30), snapshot().goals)
        assertEquals(GoalSettings(12, 30), StatsGoals(prefs).goals.value)

        goals.save(GoalSettings(booksPerYear = 0, minutesPerDay = null))

        assertEquals(GoalSettings(), goals.goals.value)
        assertFalse(prefs.contains(StatsGoals.KEY_BOOKS_PER_YEAR))
        assertFalse(prefs.contains(StatsGoals.KEY_MINUTES_PER_DAY))
    }

    @Test
    fun waitingForEreaderIsTrueUntilItsFirstKindleSessionArrives() {
        val ereader = EreaderSync(id = "k1", name = "Kindle", lastSync = now)

        assertTrue(snapshot(ereaders = listOf(ereader)).waitingForEreader)
        assertFalse(snapshot().waitingForEreader)

        addBook("b1")
        digest("d1", "b1")
        kindle("k-1", document = "d1")

        assertFalse(snapshot(ereaders = listOf(ereader)).waitingForEreader)
    }

    @Test
    fun theOwnersPhonePositionGivesTheProgressOfItsRevisionOnly() {
        addBook("b1")
        addBook("b2")
        session("p-1", "PHONE", "phone:p1", bookId = "b1", seconds = 600, progressRead = 0.1)
        session("p-2", "PHONE", "phone:p1", bookId = "b2", seconds = 600, progressRead = 0.1)
        runBlocking {
            db.library().savePhoneReading(PhoneReading("b1", "rev-b1", "{}", "", 0.5, now))
            db.library().savePhoneReading(PhoneReading("b2", "older-revision", "{}", "", 0.5, now))
        }

        val snap = snapshot()

        // Half the book left, and 10 minutes read for each 10% of the book: 3000 seconds.
        assertEquals(3000L, snap.stats.book("b1")!!.timeLeftSeconds)
        assertNull(snap.stats.book("b2")!!.timeLeftSeconds)
    }

    @Test
    fun aProgressSyncReportIsTheProgressWhenThePhoneHasNone() {
        addBook("b1")
        digest("d1", "b1")
        session(
            "k-1",
            "KINDLE",
            "ereader:k1",
            document = "d1",
            seconds = 600,
            progressRead = 0.1,
        )
        runBlocking {
            db.library()
                .saveKosyncReport(
                    KosyncReport(
                        document = "d1",
                        deviceId = "k1",
                        device = "Kindle",
                        progress = "/body",
                        percentage = 0.5,
                        timestamp = now / 1000,
                        receivedAt = now,
                    )
                )
        }

        assertEquals(3000L, snapshot().stats.book("b1")!!.timeLeftSeconds)
    }

    @Test
    fun anEreaderSessionIsNamedByTheRosterAndAPhoneSessionIsNot() {
        addBook("b1")
        val ereader = EreaderSync(id = "k1", name = "Kindle Paperwhite", lastSync = now)
        val roster =
            DeviceRoster(deviceSightings(listOf(ereader), emptyList(), emptyList()), emptyList())
        kindle("k-1", document = "d9")
        session("p-1", "PHONE", "phone:p1", bookId = "b1")

        assertEquals(
            mapOf("ereader:k1" to "Kindle Paperwhite"),
            snapshot(ereaders = listOf(ereader), roster = roster).deviceNames,
        )
    }
}
