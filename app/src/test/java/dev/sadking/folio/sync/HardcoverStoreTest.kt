package dev.sadking.folio.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.HardcoverMatchRow
import dev.sadking.folio.data.HardcoverQuestionRow
import dev.sadking.folio.data.LibraryDatabase
import java.io.File
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

/** Match records and status questions in Room (M10, docs/HARDCOVER_SYNC.md B5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HardcoverStoreTest {
    private lateinit var base: File
    private lateinit var covers: File
    private lateinit var db: LibraryDatabase
    private lateinit var store: HardcoverStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = File(context.cacheDir, "store-${UUID.randomUUID()}").apply { mkdirs() }
        covers = File(base, "candidates").apply { mkdirs() }
        db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        store = HardcoverStore(db, covers)
    }

    @After
    fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    private fun cover(name: String, dir: File = covers) =
        File(dir, name).apply { writeBytes(byteArrayOf(1)) }

    private fun candidate(id: Int, cover: File? = null, year: Int? = 1965) =
        MatchCandidateView(id, "Dune $id", listOf("Frank Herbert", "Other"), year, cover?.path)

    private fun unsure(itemId: String, vararg candidates: MatchCandidateView) =
        MatchRecord(itemId, MatchRecordState.UNSURE, candidates.toList(), "key-$itemId", 10)

    @Test
    fun matchRecordsRoundTrip() = runBlocking {
        val record = unsure("book", candidate(1, cover("book-1")), candidate(2, year = null))
        val notFound = MatchRecord("entry", MatchRecordState.NOT_FOUND, emptyList(), "k", 11)
        val never = MatchRecord("other", MatchRecordState.NOT_ON_HARDCOVER, emptyList(), "k", 12)
        listOf(record, notFound, never).forEach { store.saveMatch(it) }
        assertEquals(
            setOf(record, notFound, never),
            store.allMatchRecords().toSet(),
        )
        assertEquals(setOf(record, notFound, never), store.matchRecords().first().toSet())
    }

    @Test
    fun aBrokenRowReadsAsNoRecord() = runBlocking {
        db.hardcover().saveMatch(HardcoverMatchRow("a", "SOMETHING_NEW", "[]", "k", 1))
        db.hardcover().saveMatch(HardcoverMatchRow("b", "UNSURE", "{not json", "k", 1))
        db.hardcover().saveMatch(HardcoverMatchRow("c", "UNSURE", """[{"id":0}]""", "k", 1))
        store.saveMatch(unsure("d", candidate(4)))
        assertEquals(listOf("d"), store.allMatchRecords().map { it.itemId })
        assertEquals(listOf("d"), store.matchRecords().first().map { it.itemId })
    }

    @Test
    fun deletingARecordDeletesItsCoversAndNothingElse() = runBlocking {
        val mine = cover("book-1")
        val outside = cover("keep.jpg", base)
        store.saveMatch(unsure("book", candidate(1, mine), candidate(2, outside)))
        store.deleteMatch("book")
        assertTrue(store.allMatchRecords().isEmpty())
        assertFalse(mine.exists())
        assertTrue("A file outside the candidates folder stays", outside.exists())
    }

    @Test
    fun replacingARecordDeletesCoversItNoLongerUses() = runBlocking {
        val first = cover("book-1")
        val kept = cover("book-2")
        store.saveMatch(unsure("book", candidate(1, first), candidate(2, kept)))
        store.saveMatch(unsure("book", candidate(2, kept)))
        assertFalse(first.exists())
        assertTrue(kept.exists())
        store.saveMatch(MatchRecord("book", MatchRecordState.NOT_ON_HARDCOVER, emptyList(), "k", 2))
        assertFalse(kept.exists())
        assertEquals(MatchRecordState.NOT_ON_HARDCOVER, store.allMatchRecords().single().state)
    }

    @Test
    fun questionsArePerAccount() = runBlocking {
        db.hardcover()
            .saveQuestions(
                listOf(
                    HardcoverQuestionRow(1, 42, "book", "Dune", 3, "Reading", 20),
                    HardcoverQuestionRow(1, 41, "entry", "Emma", 0, "Paused", 10),
                    HardcoverQuestionRow(2, 42, "book", "Dune", 1, "Reading", 5),
                )
            )
        assertEquals(
            listOf(
                StatusQuestion(41, "entry", "Emma", 0, "Paused"),
                StatusQuestion(42, "book", "Dune", 3, "Reading"),
            ),
            store.questions(1).first(),
        )
        store.deleteQuestion(1, 42)
        assertEquals(listOf(41), store.questions(1).first().map { it.hardcoverId })
        assertEquals(1, store.questions(2).first().size)
    }
}
