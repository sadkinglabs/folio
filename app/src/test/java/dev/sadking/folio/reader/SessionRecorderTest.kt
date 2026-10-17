package dev.sadking.folio.reader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.core.stats.SessionSource
import dev.sadking.folio.data.LibraryDatabase
import dev.sadking.folio.data.ReadingSessionRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's sessions as the reader and read-aloud report them, written to an in-memory Room with
 * a fake clock. Writes are asynchronous, so each check waits for the rows it expects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionRecorderTest {
    private lateinit var db: LibraryDatabase
    private lateinit var scope: CoroutineScope
    private var now = 1_791_750_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        scope = CoroutineScope(SupervisorJob())
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun recorder() = SessionRecorder(db.stats(), scope, "phone:test-device") { now }

    /** The stored rows once [until] holds for them. */
    private fun awaitRows(until: (List<ReadingSessionRow>) -> Boolean): List<ReadingSessionRow> =
        runBlocking {
            db.stats().sessions().first(until)
        }

    private fun List<ReadingSessionRow>.ofSource(source: SessionSource) = filter {
        it.source == source.name
    }

    @Test
    fun shownThenPagesThenHiddenWritesOneReadingRow() {
        val r = recorder()
        r.shown(BOOK, 0.10)
        now += 10_000
        r.page(0.11)
        now += 10_000
        r.page(0.12)
        now += 10_000
        r.page(0.13)
        now += 10_000
        r.hidden()

        val rows = awaitRows { rows -> rows.any { it.durationSec == 40L } }
        val row = rows.single()
        assertEquals(SessionSource.PHONE.name, row.source)
        assertEquals("phone:test-device", row.deviceKey)
        assertEquals(BOOK, row.bookId)
        assertEquals(40L, row.durationSec)
        assertEquals(0.10, row.progressStart!!, 1e-9)
        assertEquals(0.13, row.progressEnd!!, 1e-9)
        assertEquals(0.03, row.progressRead!!, 1e-9)
    }

    @Test
    fun aJumpDoesNotAddToProgressRead() {
        val r = recorder()
        r.shown(BOOK, 0.10)
        now += 10_000
        r.page(0.11)
        now += 10_000
        r.jumped()
        r.page(0.60)
        now += 10_000
        r.page(0.61)
        now += 10_000
        r.hidden()

        val row = awaitRows { rows -> rows.any { it.durationSec == 40L } }.single()
        assertEquals(0.02, row.progressRead!!, 1e-9)
        assertEquals(0.61, row.progressEnd!!, 1e-9)
    }

    @Test
    fun playingStopsReadingAndStartsAListenRow() {
        val r = recorder()
        r.shown(BOOK, 0.10)
        now += 10_000
        r.playing(BOOK, 0.12)
        now += 5_000
        r.utterance(0.13)
        now += 5_000
        r.stopped()

        val rows = awaitRows { rows ->
            rows.any { it.source == SessionSource.LISTEN.name && it.durationSec == 10L } &&
                rows.any { it.source == SessionSource.PHONE.name && it.durationSec == 10L }
        }
        val reading = rows.ofSource(SessionSource.PHONE).single()
        val listening = rows.ofSource(SessionSource.LISTEN).single()
        assertEquals(BOOK, reading.bookId)
        assertEquals(BOOK, listening.bookId)
        assertEquals(10L, reading.durationSec)
        assertEquals(10L, listening.durationSec)
        assertEquals(0.12, listening.progressStart!!, 1e-9)
    }

    @Test
    fun stoppedWhileVisibleStartsANewReadingSessionAfterTheGap() {
        val r = recorder()
        val started = now
        r.shown(BOOK, 0.10)
        now += 10_000
        r.playing(BOOK, 0.11)
        // Listening for longer than the five-minute gap, so reading does not continue.
        now += 400_000
        r.stopped()
        now += 10_000
        r.hidden()

        val rows = awaitRows { rows -> rows.ofSource(SessionSource.PHONE).size == 2 }
        val (first, second) = rows.ofSource(SessionSource.PHONE).sortedBy { it.startMs }
        assertEquals(started, first.startMs)
        assertEquals(10L, first.durationSec)
        assertEquals(started + 410_000, second.startMs)
        assertEquals(10L, second.durationSec)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun nothingUnderASecondIsWritten() {
        val r = recorder()
        r.shown(BOOK, 0.10)
        now += 900
        r.hidden()

        // A later session, long enough to save, proves the earlier one wrote nothing.
        now += 20 * 60_000L
        r.shown(BOOK, 0.20)
        now += 3_000
        r.hidden()

        val row = awaitRows { rows -> rows.any { it.durationSec == 3L } }.single()
        assertEquals(3L, row.durationSec)
        assertEquals(0.20, row.progressStart!!, 1e-9)
    }

    private companion object {
        const val BOOK = "book-1"
    }
}
