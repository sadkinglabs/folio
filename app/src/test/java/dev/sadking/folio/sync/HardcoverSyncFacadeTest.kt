package dev.sadking.folio.sync

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.WishBook
import dev.sadking.folio.data.encode
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The facade the screens and hooks call (docs/HARDCOVER_SYNC.md B4): the owner's answers in "Check
 * these matches", the switch, and when a change asks for a pull. WorkManager is not running here,
 * so the queue itself is not exercised; HardcoverRunTest covers the run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HardcoverSyncFacadeTest {
    private val app: FolioApplication
        get() = ApplicationProvider.getApplicationContext()

    private val sync
        get() = HardcoverSync.get(app)

    private val syncPrefs
        get() = app.getSharedPreferences(HardcoverSync.PREFS, Context.MODE_PRIVATE)

    private val dao
        get() = app.database.library()

    @Before
    fun clear() {
        app.getSharedPreferences("hardcover", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("hardcover-outbox", Context.MODE_PRIVATE).edit().clear().commit()
        syncPrefs.edit().clear().commit()
    }

    /** An account as far as the facade can tell: a stored token (never read here). */
    private fun connect() {
        app.getSharedPreferences("hardcover", Context.MODE_PRIVATE)
            .edit()
            .putString("token", "sealed")
            .putInt("accountId", 7)
            .commit()
        // Preference listeners are told on the main thread.
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun book(title: String = "Dune"): Book = runBlocking {
        val id = UUID.randomUUID().toString()
        Book(
                id,
                title,
                encode(listOf("Frank Herbert")),
                originalPath = "",
                currentPath = "",
                originalHash = id,
                currentHash = id,
            )
            .also { dao.insert(it) }
    }

    private fun candidate(id: Int) =
        MatchCandidateView(id, "Dune", listOf("Frank Herbert"), 1965, null)

    @Test
    fun notOnHardcoverIsRememberedAndTheBookLeavesMatching() = runBlocking {
        connect()
        val dune = book()
        assertEquals(HardcoverItemState.MATCHING, sync.itemStates.first()[dune.id])
        sync.notOnHardcover(dune.id)
        val record = HardcoverStore(app).allMatchRecords().single()
        assertEquals(MatchRecordState.NOT_ON_HARDCOVER, record.state)
        assertEquals(HardcoverItemState.NOT_ON_HARDCOVER, sync.itemStates.first()[dune.id])
        assertTrue(HardcoverMatcher(app).pending().isEmpty())
        // Still ruled out after its details change.
        dao.update(dune.copy(title = "Dune Messiah"))
        assertTrue(HardcoverMatcher(app).pending().isEmpty())
    }

    @Test
    fun pickingACandidateLinksTheBookAndClearsTheCheck() = runBlocking {
        connect()
        val dune = book()
        HardcoverStore(app)
            .saveMatch(
                MatchRecord(
                    dune.id,
                    MatchRecordState.UNSURE,
                    listOf(candidate(9), candidate(10)),
                    "key",
                    1,
                )
            )
        assertEquals(listOf(dune.id), sync.checks.first().map { it.itemId })
        assertEquals(HardcoverItemState.CHECK, sync.itemStates.first()[dune.id])
        sync.pick(dune.id, candidate(10))
        assertEquals(10, dao.allWishes().single { it.linkedBookId == dune.id }.hardcoverId)
        assertTrue(sync.checks.first().isEmpty())
        assertTrue(HardcoverStore(app).allMatchRecords().isEmpty())
        assertEquals(HardcoverItemState.MATCHED, sync.itemStates.first()[dune.id])
        // A link asks for a pull, so the book's status reaches Hardcover.
        assertTrue(syncPrefs.getBoolean(HardcoverSync.PULL_REQUESTED, false))
    }

    @Test
    fun theSwitchKeepsTheOwnersEarlierChoice() {
        app.getSharedPreferences("hardcover-outbox", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("automatic", false)
            .commit()
        assertFalse(sync.enabled)
        sync.enabled = true
        assertTrue(app.hardcoverOutbox.automatic)
        sync.enabled = false
        assertFalse(app.hardcoverOutbox.automatic)
    }

    @Test
    fun withSyncOffAnUnmatchedBookSaysNotMatched() = runBlocking {
        connect()
        sync.enabled = false
        val dune = book()
        assertEquals(HardcoverItemState.OFF, sync.itemStates.first()[dune.id])
    }

    @Test
    fun aStatusChangeOnALinkedBookAsksForAPull() = runBlocking {
        connect()
        val dune = book()
        dao.insertWish(
            WishBook(
                UUID.randomUUID().toString(),
                "hardcover:9",
                "Dune",
                encode(listOf("Frank Herbert")),
                hardcoverId = 9,
                linkedBookId = dune.id,
            )
        )
        sync.statusChanged(listOf(dune.id))
        assertTrue(syncPrefs.getBoolean(HardcoverSync.PULL_REQUESTED, false))
    }

    @Test
    fun aStatusChangeOnAnUnmatchedBookWaitsForItsMatch() = runBlocking {
        connect()
        sync.statusChanged(listOf(book().id))
        assertFalse(syncPrefs.getBoolean(HardcoverSync.PULL_REQUESTED, false))
    }

    @Test
    fun statusChangesWithoutAnAccountDoNothingAndNeverThrow() = runBlocking {
        sync.statusChanged(listOf("gone", book().id))
        assertFalse(syncPrefs.getBoolean(HardcoverSync.PULL_REQUESTED, false))
        assertNull(HardcoverStore(app).allMatchRecords().firstOrNull())
    }

    @Test
    fun openingLibrilloPullsAtMostEveryFifteenMinutes() {
        connect()
        syncPrefs.edit().putLong(HardcoverSync.LAST_PULL_AT, System.currentTimeMillis()).commit()
        sync.onAppOpen()
        assertFalse(syncPrefs.getBoolean(HardcoverSync.PULL_REQUESTED, false))
        syncPrefs
            .edit()
            .putLong(
                HardcoverSync.LAST_PULL_AT,
                System.currentTimeMillis() - HardcoverSync.PULL_EVERY_MS,
            )
            .commit()
        sync.onAppOpen()
        assertTrue(syncPrefs.getBoolean(HardcoverSync.PULL_REQUESTED, false))
    }
}
