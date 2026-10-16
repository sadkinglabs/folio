package dev.sadking.folio.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.HardcoverAccount
import dev.sadking.folio.data.HardcoverClient
import dev.sadking.folio.data.HardcoverHttpException
import dev.sadking.folio.data.HardcoverReading
import dev.sadking.folio.data.LibraryDatabase
import dev.sadking.folio.data.WishBook
import dev.sadking.folio.data.encode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HardcoverOutboxTest {
    private val reading =
        HardcoverReading(userBookId = 10, status = 2, readId = 20, pages = 100, editionId = 30)

    @Test
    fun movesAnOpenReadForwardOnItsOwnEdition() {
        assertEquals(OutboxStep.Send(150), outboxStep(0.5, reading, editionPages = 300))
    }

    @Test
    fun neverGoesBackwardsOrRepeats() {
        assertEquals(OutboxStep.AlreadyThere, outboxStep(0.2, reading, editionPages = 300))
        assertEquals(OutboxStep.AlreadyThere, outboxStep(1.0 / 3, reading, editionPages = 300))
    }

    @Test
    fun leavesAnythingUnusualForReview() {
        assertEquals(
            OutboxStep.NeedsReview(OutboxStep.Reason.NOT_LINKED),
            outboxStep(0.5, HardcoverReading(null, null, null, null, null), 300),
        )
        // Want to Read, or Currently Reading without an open read: do not start one.
        assertEquals(
            OutboxStep.NeedsReview(OutboxStep.Reason.NOT_READING),
            outboxStep(0.5, reading.copy(status = 1), 300),
        )
        assertEquals(
            OutboxStep.NeedsReview(OutboxStep.Reason.NOT_READING),
            outboxStep(0.5, reading.copy(readId = null), 300),
        )
        assertEquals(
            OutboxStep.NeedsReview(OutboxStep.Reason.NO_EDITION),
            outboxStep(0.5, reading, editionPages = null),
        )
        assertEquals(
            OutboxStep.NeedsReview(OutboxStep.Reason.NO_EDITION),
            outboxStep(0.5, reading.copy(editionId = null), 300),
        )
    }

    @Test
    fun aFirstPageCountIsSentWhenHardcoverHasNone() {
        assertEquals(OutboxStep.Send(30), outboxStep(0.1, reading.copy(pages = null), 300))
    }

    // Sending, through the one Hardcover queue (M10).

    private lateinit var db: LibraryDatabase
    private lateinit var outbox: HardcoverOutbox
    private var answer: (String) -> JSONObject = { error("no answer") }
    private val asked = mutableListOf<String>()

    private val api by lazy {
        ClientHardcoverApi(
            HardcoverClient { _, body ->
                val query = body.getString("query")
                asked += query
                answer(query)
            },
            "token",
            HardcoverAccount(7, "reader", 1),
        )
    }

    private fun data(text: String) = JSONObject().put("data", JSONObject(text))

    /** Hardcover has book 9 [status], with an open read on page 100 of a 300-page edition. */
    private fun hardcover(status: Int = 2, open: Boolean = true): (String) -> JSONObject = { q ->
        when {
            q.contains("editions_by_pk") ->
                data("""{"editions_by_pk":{"id":30,"book_id":9,"pages":300}}""")
            q.contains("user_books(") && open ->
                data(
                    """{"user_books":[{"id":10,"status_id":$status,"edition_id":30,"user_book_reads":[{"id":20,"progress_pages":100,"edition_id":30,"started_at":"2026-10-01"}]}]}"""
                )
            q.contains("user_books(") ->
                data(
                    """{"user_books":[{"id":10,"status_id":$status,"edition_id":30,"user_book_reads":[]}]}"""
                )
            else -> error("unexpected request")
        }
    }

    @Before
    fun setUp() = runBlocking {
        HardcoverClient.resetPacing()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        // A connected account, as HardcoverSettings sees it; the token itself is never read here.
        context
            .getSharedPreferences("hardcover", Context.MODE_PRIVATE)
            .edit()
            .putString("token", "sealed")
            .commit()
        outbox = HardcoverOutbox(context)
        db.library()
            .insert(
                Book(
                    "b",
                    "Dune",
                    encode(listOf("Frank Herbert")),
                    originalPath = "/nowhere/b.epub",
                    currentPath = "/nowhere/b.epub",
                    originalHash = "b".padEnd(64, '0'),
                    currentHash = "b".padEnd(64, '0'),
                )
            )
        db.library()
            .insertWish(
                WishBook("w", "hardcover:9", "Dune", "[]", hardcoverId = 9, linkedBookId = "b")
            )
        Unit
    }

    @After fun tearDown() = db.close()

    @Test
    fun aPositionHardcoverAlreadyHasIsDoneAndObserved() = runBlocking {
        answer = hardcover()
        outbox.queueBook("b", 0.2)
        outbox.sendPositions(api, db.library()) { 5 }
        assertTrue(outbox.entries().isEmpty())
        assertTrue(asked.none { it.startsWith("mutation") })
        val seen = db.library().hardcoverObservations().first().single()
        assertEquals(
            listOf(7, 9, 2, 100),
            listOf(seen.accountId, seen.hardcoverId, seen.status, seen.pages),
        )
    }

    @Test
    fun aServerErrorKeepsThePositionForLater() = runBlocking {
        answer = { throw HardcoverHttpException(503, "Hardcover request failed") }
        outbox.queueBook("b", 0.5)
        val error = runCatching { outbox.sendPositions(api, db.library()) }.exceptionOrNull()
        assertEquals(503, (error as HardcoverHttpException).code)
        assertEquals(listOf("book:b" to 0.5), outbox.entries())
    }

    @Test
    fun aPositionThatNeedsAPersonIsDropped() = runBlocking {
        answer = { data("""{"user_books":[{"id":1,"status_id":2},{"id":2,"status_id":2}]}""") }
        outbox.queueBook("b", 0.5)
        outbox.sendPositions(api, db.library())
        assertTrue(outbox.entries().isEmpty())
    }

    @Test
    fun positionsWaitWhileSyncIsOff() = runBlocking {
        answer = hardcover()
        outbox.queueBook("b", 0.2)
        outbox.automatic = false
        outbox.sendPositions(api, db.library())
        assertTrue(asked.isEmpty())
        assertEquals(1, outbox.entries().size)
    }

    @Test
    fun aFinishTheOwnerAskedForIsSentEvenWithSyncOff() = runBlocking {
        answer = hardcover(status = 3, open = false)
        outbox.automatic = false
        outbox.queueFinish("b", null, explicit = true)
        outbox.sendFinishes(api, db.library())
        assertTrue(outbox.finishes().isEmpty())
        assertEquals(3, db.library().hardcoverObservations().first().single().status)
    }

    @Test
    fun aFinishForABookNotOnHardcoverIsDropped() = runBlocking {
        answer = { error("no request expected") }
        outbox.queueFinish("unlinked", null, explicit = true)
        outbox.sendFinishes(api, db.library())
        assertTrue(outbox.finishes().isEmpty())
        assertTrue(asked.isEmpty())
    }
}
