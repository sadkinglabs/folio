package dev.sadking.folio.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.HardcoverObservation
import dev.sadking.folio.data.HardcoverReading
import dev.sadking.folio.data.LibraryDatabase
import dev.sadking.folio.data.RemovedEntry
import dev.sadking.folio.data.WishBook
import dev.sadking.folio.data.encode
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Hardcover finish prompt (M9 step 4): asked once per finish, never finishes on its own. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HardcoverFinishPromptsTest {
    private lateinit var db: LibraryDatabase
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var prompts: HardcoverFinishPrompts
    private var account: Int? = ACCOUNT

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        prefs = context.getSharedPreferences("finish-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        prompts = HardcoverFinishPrompts(prefs, db.library()) { account }
    }

    @After fun tearDown() = db.close()

    private fun book(id: String, status: String = "Finished", statusAt: Long = 10) =
        Book(
            id,
            "Title $id",
            encode(listOf("Ann Lee")),
            originalPath = "/nowhere/$id.epub",
            currentPath = "/nowhere/$id.epub",
            originalHash = id.padEnd(64, '0'),
            currentHash = id.padEnd(64, '0'),
            status = status,
            statusAt = statusAt,
        )

    /** A book linked to Hardcover book [hardcoverId], whose read there has [hardcoverStatus]. */
    private suspend fun tracked(
        id: String,
        hardcoverId: Int = 42,
        hardcoverStatus: Int = HardcoverFinishPrompts.HARDCOVER_READING,
        status: String = "Finished",
    ): Book {
        val book = book(id, status)
        db.library().insert(book)
        db.library()
            .insertWish(
                WishBook(
                    "wish-$id",
                    "hardcover:$hardcoverId",
                    book.title,
                    book.authors,
                    hardcoverId = hardcoverId,
                    linkedBookId = id,
                )
            )
        db.library()
            .hardcoverObservation(
                HardcoverObservation(ACCOUNT, hardcoverId, hardcoverStatus, 100, 7, 1)
            )
        return book
    }

    private suspend fun shown(expected: List<FinishPrompt>): List<FinishPrompt> =
        withTimeout(5_000) { prompts.prompts.first { it == expected } }

    private suspend fun setStatus(id: String, status: String, at: Long) {
        val dao = db.library()
        dao.update(dao.book(id)!!.copy(status = status, statusAt = at))
    }

    @Test
    fun aFinishedBookWhoseHardcoverReadIsOpenIsAskedAboutOnce() =
        runBlocking<Unit> {
            val home = tracked("b1")
            prompts.statusChanged(setOf("b1"))
            shown(listOf(FinishPrompt("b1", home.title)))
            prompts.statusChanged(setOf("b1"))
            shown(listOf(FinishPrompt("b1", home.title)))

            prompts.dismiss("b1")
            shown(emptyList())
            prompts.statusChanged(setOf("b1"))
            assertEquals("Answered: not asked again", emptyList<String>(), prompts.waitingBookIds())
        }

    @Test
    fun finishingTheBookAgainAsksAgain() =
        runBlocking<Unit> {
            val home = tracked("b1")
            prompts.statusChanged(setOf("b1"))
            prompts.dismiss("b1")

            // Re-read: even a change the prompts never heard of counts, by its time.
            setStatus("b1", "Reading", 20)
            setStatus("b1", "Finished", 30)
            prompts.statusChanged(setOf("b1"))
            shown(listOf(FinishPrompt("b1", home.title)))
        }

    @Test
    fun onlyALinkedAccountBookAndOpenReadAreAsked() =
        runBlocking<Unit> {
            tracked("read", hardcoverId = 1, hardcoverStatus = 3)
            tracked("want", hardcoverId = 2, hardcoverStatus = 1)
            tracked("reading", hardcoverId = 3, status = "Reading")
            db.library().insert(book("unlinked"))
            val open = tracked("open", hardcoverId = 4)
            prompts.statusChanged(setOf("read", "want", "reading", "unlinked", "unknown", "open"))
            shown(listOf(FinishPrompt("open", open.title)))

            tracked("late", hardcoverId = 5)
            account = null
            prompts.statusChanged(setOf("late"))
            assertEquals("No account linked", listOf("open"), prompts.waitingBookIds())
            account = ACCOUNT + 1
            prompts.statusChanged(setOf("late"))
            assertEquals("Another account's read", listOf("open"), prompts.waitingBookIds())
        }

    @Test
    fun aStatusSetOnACatalogueEntryReachesItsBook() =
        runBlocking<Unit> {
            val home = tracked("b1")
            prompts.statusChanged(setOf("wish-b1"))
            shown(listOf(FinishPrompt("b1", home.title)))
        }

    @Test
    fun aPromptGoesWhenTheBookIsNoLongerFinishedOrIsRemoved() =
        runBlocking<Unit> {
            val one = tracked("b1", hardcoverId = 1)
            val two = tracked("b2", hardcoverId = 2)
            prompts.statusChanged(setOf("b1", "b2"))
            shown(listOf(FinishPrompt("b1", one.title), FinishPrompt("b2", two.title)))

            setStatus("b1", "Reading", 20)
            shown(listOf(FinishPrompt("b2", two.title)))
            db.library().markRemoved(RemovedEntry("b2", "b2", two.title, "EPUB", 1))
            shown(emptyList())

            // Once a status change is reported, both are dropped for good.
            prompts.statusChanged(setOf("b1"))
            assertEquals(emptyList<String>(), prompts.waitingBookIds())
        }

    @Test
    fun promptsAreKeptAcrossRestartsAndSharedByEveryInstance() =
        runBlocking<Unit> {
            val home = tracked("b1")
            prompts.statusChanged(setOf("b1"))
            val again = HardcoverFinishPrompts(prefs, db.library()) { account }
            assertEquals(
                listOf(FinishPrompt("b1", home.title)),
                withTimeout(5_000) { again.prompts.first { it.isNotEmpty() } },
            )
            again.dismiss("b1")
            val third = HardcoverFinishPrompts(prefs, db.library()) { account }
            third.statusChanged(setOf("b1"))
            assertEquals("The answer is kept too", emptyList<String>(), third.waitingBookIds())
        }

    @Test
    fun theOutboxKeepsWhatHardcoverSaysCurrent() =
        runBlocking<Unit> {
            val dao = db.library()
            recordObservation(dao, ACCOUNT, 42, HardcoverReading(10, 2, 20, 50, 30), 5)
            val seen = dao.hardcoverObservations().first().single()
            assertEquals(HardcoverObservation(ACCOUNT, 42, 2, 50, 30, 5), seen)

            recordObservation(dao, ACCOUNT, 42, HardcoverReading(null, null, null, null, null), 6)
            assertTrue(dao.hardcoverObservations().first().isEmpty())
        }

    companion object {
        const val ACCOUNT = 7
    }
}
