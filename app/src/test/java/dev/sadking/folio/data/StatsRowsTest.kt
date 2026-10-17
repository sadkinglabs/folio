package dev.sadking.folio.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.core.stats.ReadingSession
import dev.sadking.folio.core.stats.SessionSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The statistics' session rows over an in-memory Room database on the JVM (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StatsRowsTest {
    private lateinit var db: LibraryDatabase

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun close() {
        db.close()
    }

    private fun session(id: String = "s1", seconds: Long = 600) =
        ReadingSession(
            id = id,
            source = SessionSource.KINDLE,
            deviceKey = "ereader:kindle-1",
            bookId = null,
            document = "0123456789abcdef0123456789abcdef",
            startMs = 1_800_000_000_000L,
            durationSec = seconds,
            progressStart = 0.25,
            progressEnd = 0.5,
            progressRead = 0.125,
            pages = 7,
        )

    @Test
    fun sessionSurvivesARoundTrip() = runBlocking {
        val stats = db.stats()
        val original = session()
        stats.upsertSessions(listOf(original.toRow(updatedAt = 1)))
        val read = stats.sessionsById(listOf("s1")).single()
        assertEquals(original, read.toSession())
        assertEquals(1L, read.updatedAt)
    }

    @Test
    fun unknownSourceGivesNull() {
        val row = session().toRow(updatedAt = 1).copy(source = "BOOKSHELF")
        assertNull(row.toSession())
    }

    @Test
    fun keepSessionsNeverShrinksAStoredSession() = runBlocking {
        val stats = db.stats()
        assertEquals(1, stats.keepSessions(listOf(session(seconds = 600)), now = 1))
        assertEquals(0, stats.keepSessions(listOf(session(seconds = 300)), now = 2))
        assertEquals(600L, stats.sessionsById(listOf("s1")).single().durationSec)
        assertEquals(1, stats.keepSessions(listOf(session(seconds = 900)), now = 3))
        assertEquals(900L, stats.sessionsById(listOf("s1")).single().durationSec)
        assertEquals(3L, stats.sessionsById(listOf("s1")).single().updatedAt)
    }
}
