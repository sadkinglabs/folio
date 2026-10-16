package dev.sadking.folio.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.core.hardcover.MatchCandidate
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.HardcoverBaselineRow
import dev.sadking.folio.data.HardcoverCover
import dev.sadking.folio.data.HardcoverHttpException
import dev.sadking.folio.data.HardcoverObservation
import dev.sadking.folio.data.LibraryDatabase
import dev.sadking.folio.data.LibraryRepository
import dev.sadking.folio.data.RemovedEntry
import dev.sadking.folio.data.WishBook
import dev.sadking.folio.data.encode
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two-way status sync with Hardcover (M10, docs/HARDCOVER_SYNC.md A3 and B5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HardcoverStatusSyncTest {
    private lateinit var base: File
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository
    private lateinit var store: HardcoverStore
    private lateinit var sync: HardcoverStatusSync
    private lateinit var api: FakeApi
    private var clock = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()
    private var zone: ZoneId = ZoneId.of("UTC")
    private val coverRequests = mutableListOf<String>()
    /** Entries are synced oldest first; each fixture is a millisecond younger. */
    private var addedAt = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = File(context.cacheDir, "hc-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated =
            object : ContextWrapper(context) {
                override fun getFilesDir() = File(base, "private").apply { mkdirs() }
            }
        db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repo = LibraryRepository(isolated, db) { File(base, "Books") }
        repo.folderAccess = { false }
        store = HardcoverStore(db, File(base, "candidates"))
        val covers = HardcoverCover { url ->
            coverRequests += url
            byteArrayOf(1, 2, 3)
        }
        sync = HardcoverStatusSync(repo, store, covers, { clock }, { zone })
        api = FakeApi()
    }

    @After
    fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    // ---- Fixtures ----

    /** Hardcover for the tests: records what was sent, fails where told to. */
    private class FakeApi(override val accountId: Int = ACCOUNT) : HardcoverApi {
        val sent = mutableListOf<String>()
        val failures = mutableMapOf<Int, Exception>()

        override fun libraryPage(after: Int): List<RemoteUserBook> = error("Not used")

        override fun setStatus(hardcoverId: Int, status: Int, userBookId: Int?): Int {
            failures[hardcoverId]?.let { throw it }
            sent += "status $hardcoverId $status ${userBookId ?: "new"}"
            return userBookId ?: (900 + hardcoverId)
        }

        override fun markRead(hardcoverId: Int, finishedOn: LocalDate?) {
            failures[hardcoverId]?.let { throw it }
            sent += "read $hardcoverId ${finishedOn ?: "no date"}"
        }

        override fun editionsByIsbn(isbns: List<String>): List<IsbnHit> = error("Not used")

        override fun search(text: String): List<MatchCandidate> = error("Not used")
    }

    private fun remote(
        hardcoverId: Int,
        status: Int,
        updatedAt: Long? = null,
        title: String = "Remote $hardcoverId",
        cover: String? = null,
    ) =
        RemoteUserBook(
            userBookId = 500 + hardcoverId,
            hardcoverId = hardcoverId,
            status = status,
            updatedAt = updatedAt,
            title = title,
            authors = listOf("Ann Lee"),
            releaseYear = 2001,
            coverUrl = cover,
            pages = 40,
            editionId = 7,
        )

    /** A book with [status], linked to Hardcover book [hardcoverId]. Returns the book's id. */
    private fun linkedBook(hardcoverId: Int, status: String, statusAt: Long = 100): String =
        runBlocking {
            val id = UUID.randomUUID().toString()
            db.library()
                .insert(
                    Book(
                        id,
                        "Book $hardcoverId",
                        encode(listOf("Ann Lee")),
                        originalPath = "",
                        currentPath = "",
                        originalHash = id,
                        currentHash = id,
                        status = status,
                        statusAt = statusAt,
                    )
                )
            entry(hardcoverId, status = "", linkedBookId = id)
            id
        }

    /** A catalogue entry linked to [hardcoverId]. Returns the entry's id. */
    private fun entry(
        hardcoverId: Int?,
        status: String = "",
        statusAt: Long = 0,
        linkedBookId: String? = null,
    ): String = runBlocking {
        val id = UUID.randomUUID().toString()
        db.library()
            .insertWish(
                WishBook(
                    id,
                    hardcoverId?.let { "hardcover:$it" } ?: "manual:$id",
                    "Entry $hardcoverId",
                    encode(listOf("Ann Lee")),
                    hardcoverId = hardcoverId,
                    hardcoverTitle = "Entry $hardcoverId",
                    addedAt = addedAt++,
                    linkedBookId = linkedBookId,
                    status = status,
                    statusAt = statusAt,
                )
            )
        id
    }

    private fun agreed(hardcoverId: Int, status: Int, statusAt: Long = 100) = runBlocking {
        db.hardcover().saveBaseline(HardcoverBaselineRow(ACCOUNT, hardcoverId, status, statusAt, 1))
    }

    private fun baseline(hardcoverId: Int) = runBlocking {
        db.hardcover().baseline(ACCOUNT, hardcoverId)
    }

    private fun book(id: String) = runBlocking { db.library().book(id)!! }

    private fun wish(id: String) = runBlocking { db.library().wish(id)!! }

    private fun run(vararg library: RemoteUserBook, createEntries: Boolean = false) = runBlocking {
        sync.sync(api, library.toList(), createEntries)
    }

    private fun questions() = runBlocking { store.questions(ACCOUNT).first() }

    /** Another Hardcover book, so the pulled library is never empty. */
    private val other = remote(999, 1)

    // ---- Down ----

    @Test
    fun hardcoverChangeComesDownToABook() {
        val book = linkedBook(10, "Reading")
        agreed(10, 2)
        val result = run(remote(10, 3, updatedAt = 5_000))
        assertEquals("Finished", book(book).status)
        assertEquals(5_000L, book(book).statusAt)
        assertEquals(HardcoverBaselineRow(ACCOUNT, 10, 3, 5_000, clock), baseline(10))
        assertTrue("Nothing goes up", api.sent.isEmpty())
        assertEquals(1, result.down)
    }

    @Test
    fun hardcoverChangeComesDownToACatalogueEntry() {
        val entry = entry(11)
        agreed(11, 1, statusAt = 0)
        run(remote(11, 4, updatedAt = 7_000))
        assertEquals("Paused", wish(entry).status)
        assertEquals(7_000L, wish(entry).statusAt)
        assertEquals(4, baseline(11)!!.status)
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun aDownWithNoChangeTimeUsesNow() {
        val book = linkedBook(12, "Unread", statusAt = 0)
        run(remote(12, 1))
        assertEquals("Want to Read", book(book).status)
        assertEquals(clock, book(book).statusAt)
    }

    @Test
    fun unreadIsNoOpinionAndHardcoverStillComesDown() {
        val book = linkedBook(13, "Unread")
        agreed(13, 2)
        run(remote(13, 2), other)
        assertTrue("Unread never changes Hardcover", api.sent.isEmpty())
        assertEquals("Unread", book(book).status)

        linkedBook(14, "Unread")
        run(remote(13, 2), other)
        assertTrue("Unread with Hardcover not having it sends nothing", api.sent.isEmpty())
        assertNull(baseline(14))

        run(remote(13, 3, updatedAt = 9_000), other)
        assertEquals("Finished", book(book).status)
    }

    // ---- Up ----

    @Test
    fun eachLibrilloStatusGoesUpWithAndWithoutAUserBook() {
        val words = mapOf("Want to Read" to 1, "Reading" to 2, "Paused" to 4, "Abandoned" to 5)
        for ((word, status) in words) {
            val notThere = 20 + status
            val there = 30 + status
            linkedBook(notThere, word)
            linkedBook(there, word)
            agreed(there, if (status == 1) 2 else 1)
            run(remote(there, if (status == 1) 2 else 1), other)
            assertTrue(word, "status $notThere $status new" in api.sent)
            assertTrue(word, "status $there $status ${500 + there}" in api.sent)
            assertEquals(status, baseline(notThere)!!.status)
            assertEquals(status, baseline(there)!!.status)
            val observed = runBlocking { db.hardcover().observations(ACCOUNT) }
            assertEquals(word, status, observed.single { it.hardcoverId == there }.status)
        }
    }

    @Test
    fun aCatalogueEntryWithNoStatusGoesUpAsWantToRead() {
        val entry = entry(40)
        assertFalse(wish(entry).hardcoverSynced)
        val result = run(other)
        assertEquals(listOf("status 40 1 new"), api.sent)
        assertEquals(1, result.up)
        assertTrue("No longer offered to add by hand", wish(entry).hardcoverSynced)
    }

    @Test
    fun finishedFromCurrentlyReadingEndsTheReadThatDay() {
        zone = ZoneId.of("America/Los_Angeles")
        // 03:00 UTC on the 16th is still the 15th in Los Angeles.
        val finished = Instant.parse("2026-10-16T03:00:00Z").toEpochMilli()
        linkedBook(41, "Finished", statusAt = finished)
        agreed(41, 2)
        run(remote(41, 2))
        assertEquals(listOf("read 41 2026-10-15"), api.sent)
        assertEquals(HardcoverBaselineRow(ACCOUNT, 41, 3, finished, clock), baseline(41))
    }

    @Test
    fun finishedFromPausedEndsTheReadToo() {
        linkedBook(42, "Finished", statusAt = clock - 1_000)
        agreed(42, 4)
        run(remote(42, 4))
        assertEquals(listOf("read 42 2026-10-16"), api.sent)
    }

    @Test
    fun straightToFinishedIsReadWithNoDate() {
        linkedBook(43, "Finished", statusAt = clock)
        agreed(43, 1)
        run(remote(43, 1))
        linkedBook(44, "Finished", statusAt = clock)
        run(remote(43, 3), other)
        assertEquals(listOf("read 43 no date", "read 44 no date"), api.sent)
    }

    @Test
    fun aChangeOnTheEreaderGoesUpLikeOneOnThePhone() {
        val book = linkedBook(45, "Reading")
        agreed(45, 2)
        runBlocking { repo.manageBatch(setOf(book), status = "Finished", statusAt = clock) }
        run(remote(45, 2))
        assertEquals(listOf("read 45 2026-10-16"), api.sent)
    }

    // ---- First sync ----

    @Test
    fun onAFirstSyncTheNewerSideWins() {
        val newer = linkedBook(50, "Reading", statusAt = 10_000_000)
        val older = linkedBook(51, "Reading", statusAt = 1_000)
        run(remote(50, 3, updatedAt = 1_000), remote(51, 3, updatedAt = 10_000_000))
        assertEquals(listOf("status 50 2 550"), api.sent)
        assertEquals("Reading", book(newer).status)
        assertEquals("Finished", book(older).status)
    }

    @Test
    fun onAFirstSyncTheSameStatusIsAgreed() {
        linkedBook(52, "Paused", statusAt = 5)
        run(remote(52, 4))
        assertTrue(api.sent.isEmpty())
        assertEquals(HardcoverBaselineRow(ACCOUNT, 52, 4, 5, clock), baseline(52))
    }

    @Test
    fun onAFirstSyncCloseOrUnknownTimesAsk() {
        linkedBook(53, "Reading", statusAt = 100_000)
        linkedBook(54, "Reading", statusAt = 0)
        linkedBook(55, "Reading", statusAt = 100_000)
        run(
            remote(53, 3, updatedAt = 130_000),
            remote(54, 3, updatedAt = 130_000),
            remote(55, 3, updatedAt = null),
        )
        assertTrue(api.sent.isEmpty())
        assertEquals(setOf(53, 54, 55), questions().map { it.hardcoverId }.toSet())
    }

    // ---- Questions ----

    @Test
    fun bothChangedAsksAndChangesNothing() {
        val book = linkedBook(60, "Finished", statusAt = 200)
        agreed(60, 2)
        val result = run(remote(60, 5))
        assertTrue(api.sent.isEmpty())
        assertEquals("Finished", book(book).status)
        assertEquals(2, baseline(60)!!.status)
        assertEquals(1, result.asked)
        assertEquals(listOf(StatusQuestion(60, book, "Book 60", 5, "Finished")), questions())

        val askedAt = runBlocking { db.hardcover().allQuestions(ACCOUNT).single().askedAt }
        clock += 60_000
        run(remote(60, 5))
        assertEquals(
            "Asked once, in the same place",
            askedAt,
            runBlocking { db.hardcover().allQuestions(ACCOUNT).single().askedAt },
        )
    }

    @Test
    fun bothChangedToTheSameStatusIsAgreed() {
        linkedBook(61, "Finished", statusAt = 200)
        agreed(61, 2)
        run(remote(61, 3))
        assertTrue(api.sent.isEmpty())
        assertTrue(questions().isEmpty())
        assertEquals(3, baseline(61)!!.status)
    }

    @Test
    fun answeringWithHardcoverSetsLibrillo() {
        val book = linkedBook(62, "Finished", statusAt = 200)
        agreed(62, 2)
        run(remote(62, 5))
        runBlocking { sync.answer(ACCOUNT, questions().single(), useHardcover = true) }
        assertEquals("Abandoned", book(book).status)
        assertEquals(HardcoverBaselineRow(ACCOUNT, 62, 5, clock, clock), baseline(62))
        assertTrue(questions().isEmpty())
        run(remote(62, 5))
        assertTrue("Nothing left to do", api.sent.isEmpty())
        assertTrue(questions().isEmpty())
    }

    @Test
    fun answeringWithLibrilloSendsItAtTheNextSync() {
        val book = linkedBook(63, "Finished", statusAt = 200)
        agreed(63, 2)
        run(remote(63, 5))
        runBlocking { sync.answer(ACCOUNT, questions().single(), useHardcover = false) }
        assertTrue(questions().isEmpty())
        assertTrue("The answer itself sends nothing", api.sent.isEmpty())
        run(remote(63, 5))
        assertEquals(listOf("read 63 no date"), api.sent)
        assertEquals("Finished", book(book).status)
        assertEquals(3, baseline(63)!!.status)
    }

    @Test
    fun aBookGoneFromHardcoverThatChangedHereAsksAndCanBePutBack() {
        linkedBook(64, "Finished", statusAt = 200)
        agreed(64, 2)
        run(other)
        assertEquals(0, questions().single().hardcoverStatus)

        runBlocking { sync.answer(ACCOUNT, questions().single(), useHardcover = false) }
        run(other)
        assertEquals(listOf("read 64 no date"), api.sent)
    }

    @Test
    fun aBookGoneFromHardcoverCanBeLeftOff() {
        val book = linkedBook(65, "Finished", statusAt = 200)
        agreed(65, 2)
        run(other)
        runBlocking { sync.answer(ACCOUNT, questions().single(), useHardcover = true) }
        assertEquals("Finished", book(book).status)
        assertEquals(HardcoverBaselineRow(ACCOUNT, 65, 0, 200, clock), baseline(65))
        run(other)
        assertTrue(api.sent.isEmpty())
        assertTrue(questions().isEmpty())
    }

    @Test
    fun anAnswerForAnItemThatIsGoneOnlyClearsTheQuestion() {
        linkedBook(66, "Finished", statusAt = 200)
        agreed(66, 2)
        run(remote(66, 5))
        val question = questions().single().copy(itemId = UUID.randomUUID().toString())
        runBlocking { sync.answer(ACCOUNT, question, useHardcover = true) }
        assertTrue(questions().isEmpty())
        assertEquals(2, baseline(66)!!.status)
    }

    // ---- Gone ----

    @Test
    fun aBookRemovedFromHardcoverStaysAndIsNotPutBackUntilItChanges() {
        val book = linkedBook(70, "Reading", statusAt = 100)
        agreed(70, 2)
        val result = run(other)
        assertEquals(1, result.gone)
        assertEquals("Reading", book(book).status)
        assertNotNull(db.library().let { runBlocking { it.book(book) } })
        assertEquals(HardcoverBaselineRow(ACCOUNT, 70, 0, 100, clock), baseline(70))

        run(other)
        assertTrue("Not put back on its own", api.sent.isEmpty())

        clock += 1_000
        runBlocking { repo.manageBatch(setOf(book), status = "Paused", statusAt = clock) }
        run(other)
        assertEquals(listOf("status 70 4 new"), api.sent)
    }

    @Test
    fun anEmptyLibraryIsNotTakenAsEveryBookGone() {
        val book = linkedBook(71, "Reading")
        agreed(71, 2)
        runBlocking {
            db.library().hardcoverObservation(HardcoverObservation(ACCOUNT, 71, 2, 10, 7, 1))
        }
        val result = run()
        assertEquals(0, result.gone)
        assertEquals(2, baseline(71)!!.status)
        assertEquals("Reading", book(book).status)
        assertEquals(1, runBlocking { db.hardcover().observations(ACCOUNT) }.size)
    }

    // ---- New entries ----

    @Test
    fun booksAddedOnHardcoverBecomeCatalogueEntriesOnlyWhenAsked() {
        val cover = "https://assets.hardcover.app/books/80/cover.jpg"
        run(remote(80, 1, updatedAt = 3_000, title = "Wishful", cover = cover), other)
        assertTrue(runBlocking { db.library().allWishes() }.none { it.hardcoverId == 80 })

        val result =
            run(
                remote(80, 1, updatedAt = 3_000, title = "Wishful", cover = cover),
                createEntries = true,
            )
        val entry = runBlocking { db.library().allWishes() }.single { it.hardcoverId == 80 }
        assertEquals("Wishful", entry.title)
        assertEquals("Want to Read", entry.status)
        assertEquals(3_000L, entry.statusAt)
        assertTrue(entry.hardcoverSynced)
        assertNull(entry.linkedBookId)
        assertEquals(HardcoverBaselineRow(ACCOUNT, 80, 1, 3_000, clock), baseline(80))
        assertTrue(File(entry.coverPath!!).isFile)
        assertEquals(listOf(cover), coverRequests)
        assertEquals(1, result.added)

        run(
            remote(80, 1, updatedAt = 3_000, title = "Wishful", cover = cover),
            createEntries = true,
        )
        assertEquals(1, runBlocking { db.library().allWishes() }.count { it.hardcoverId == 80 })
        assertTrue("Nothing goes up for it", api.sent.isEmpty())
    }

    @Test
    fun aBookWithAnUnknownStatusIsLeftAlone() {
        val book = linkedBook(81, "Reading")
        agreed(81, 2)
        run(remote(81, 6), remote(82, 7), createEntries = true)
        assertEquals("Reading", book(book).status)
        assertEquals(2, baseline(81)!!.status)
        assertTrue(api.sent.isEmpty())
        assertTrue(runBlocking { db.library().allWishes() }.none { it.hardcoverId == 82 })
    }

    @Test
    fun recentlyRemovedIsNeitherTouchedNorRecreated() {
        val book = linkedBook(83, "Reading")
        val entry = runBlocking { db.library().allWishes() }.single { it.hardcoverId == 83 }
        runBlocking {
            db.library().markRemoved(RemovedEntry(book, book, "Book 83", "EPUB", 1))
            db.library().markRemoved(RemovedEntry(entry.id, book, "Book 83", "Catalogue", 1))
        }
        run(remote(83, 3, updatedAt = 9_000), createEntries = true)
        assertEquals("Reading", book(book).status)
        assertNull(baseline(83))
        assertEquals(
            1,
            runBlocking { db.library().allStoredWishes() }.count { it.hardcoverId == 83 },
        )
        assertTrue(api.sent.isEmpty())
    }

    // ---- Failures ----

    @Test
    fun aRateLimitStopsTheSyncAndLeavesNoBaselineForThatBook() {
        linkedBook(90, "Reading")
        linkedBook(91, "Paused")
        api.failures[91] = HardcoverHttpException(429, "Slow down")
        assertThrows(HardcoverHttpException::class.java) { run(other) }
        assertEquals(listOf("status 90 2 new"), api.sent)
        assertEquals(2, baseline(90)!!.status)
        assertNull(baseline(91))

        api.failures[91] = IOException("offline")
        assertThrows(IOException::class.java) { run(remote(90, 2), other) }
        assertNull(baseline(91))
    }

    @Test
    fun aBookHardcoverRefusesIsSkippedAndTheRestGoOn() {
        linkedBook(92, "Reading")
        linkedBook(93, "Paused")
        linkedBook(94, "Abandoned")
        api.failures[92] = HardcoverHttpException(422, "Refused")
        api.failures[93] = IllegalStateException("Hardcover did not confirm the change")
        run(other)
        assertEquals(listOf("status 94 5 new"), api.sent)
        assertNull(baseline(92))
        assertNull(baseline(93))
    }

    @Test
    fun aRejectedTokenStopsTheSync() {
        linkedBook(95, "Reading")
        api.failures[95] = HardcoverHttpException(401, "Reconnect")
        assertThrows(HardcoverHttpException::class.java) { run(other) }
        assertNull(baseline(95))
    }

    // ---- Observations and status times ----

    @Test
    fun observationsFollowThePull() {
        runBlocking {
            db.library().hardcoverObservation(HardcoverObservation(ACCOUNT, 96, 2, 10, 7, 1))
            db.library().hardcoverObservation(HardcoverObservation(ACCOUNT + 1, 96, 2, 10, 7, 1))
        }
        run(remote(97, 2))
        val mine = runBlocking { db.hardcover().observations(ACCOUNT) }
        assertEquals(listOf(97), mine.map { it.hardcoverId })
        assertEquals(40, mine.single().pages)
        assertEquals(1, runBlocking { db.hardcover().observations(ACCOUNT + 1) }.size)
    }

    @Test
    fun aCatalogueEntryRemembersWhenItsStatusChanged() {
        val entry = entry(null, status = "Want to Read", statusAt = 5)
        runBlocking { repo.manageBatch(setOf(entry), status = "Want to Read", statusAt = 50) }
        assertEquals("The same status keeps its time", 5L, wish(entry).statusAt)
        runBlocking { repo.manageBatch(setOf(entry), status = "Reading", statusAt = 60) }
        assertEquals("Reading", wish(entry).status)
        assertEquals(60L, wish(entry).statusAt)
    }

    companion object {
        const val ACCOUNT = 7
    }
}
