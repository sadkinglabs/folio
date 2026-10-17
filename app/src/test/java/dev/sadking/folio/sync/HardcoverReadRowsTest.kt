package dev.sadking.folio.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.HardcoverRead
import dev.sadking.folio.data.LibraryDatabase
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The finished reads of a Hardcover pull land in `hardcover_reads` (docs/HARDCOVER_SYNC.md D7).
 * Calls the helper the run's saveReads step uses, on in-memory Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HardcoverReadRowsTest {
    private lateinit var db: LibraryDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A pulled book: Hardcover book id is [id] + 1000. */
    private fun book(id: Int, vararg reads: HardcoverRead) =
        RemoteUserBook(
            id,
            id + 1000,
            3,
            null,
            "T$id",
            listOf("A"),
            null,
            null,
            null,
            null,
            reads.toList(),
        )

    private suspend fun stored() = db.stats().reads().first().sortedBy { it.readId }

    @Test
    fun aPullStoresEveryFinishedRead() = runBlocking {
        val library =
            listOf(
                book(
                    1,
                    HardcoverRead(5, LocalDate.of(2026, 1, 2), LocalDate.of(2026, 2, 3)),
                    HardcoverRead(6, null, LocalDate.of(2026, 9, 30)),
                ),
                book(2, HardcoverRead(9, null, LocalDate.of(2026, 3, 1))),
            )
        saveHardcoverReads(db.stats(), 7, library, now = 100)

        val rows = stored()
        assertEquals(listOf(5, 6, 9), rows.map { it.readId })
        assertEquals(listOf(1001, 1001, 1002), rows.map { it.hardcoverId })
        assertEquals(listOf("2026-01-02", null, null), rows.map { it.startedOn })
        assertEquals(listOf("2026-02-03", "2026-09-30", "2026-03-01"), rows.map { it.finishedOn })
        assertTrue(rows.all { it.accountId == 7 && it.fetchedAt == 100L })
    }

    @Test
    fun aLaterPullWithOneReadMissingKeepsIt() = runBlocking {
        val first =
            listOf(
                book(
                    1,
                    HardcoverRead(5, null, LocalDate.of(2026, 2, 3)),
                    HardcoverRead(6, null, LocalDate.of(2026, 9, 30)),
                )
            )
        saveHardcoverReads(db.stats(), 7, first, now = 100)

        // Read 5 is gone from this pull: nothing is deleted, read 6 is refreshed.
        val second = listOf(book(1, HardcoverRead(6, null, LocalDate.of(2026, 9, 30))))
        saveHardcoverReads(db.stats(), 7, second, now = 200)

        val rows = stored()
        assertEquals(listOf(5, 6), rows.map { it.readId })
        assertEquals(listOf(100L, 200L), rows.map { it.fetchedAt })
    }

    @Test
    fun aPullWithNoReadsWritesNothing() = runBlocking {
        saveHardcoverReads(db.stats(), 7, listOf(book(1)), now = 100)
        assertTrue(stored().isEmpty())
    }
}
