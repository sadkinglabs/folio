package dev.sadking.folio.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.core.hardcover.MatchCandidate
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.HardcoverCover
import dev.sadking.folio.data.HardcoverHttpException
import dev.sadking.folio.data.HardcoverLinks
import dev.sadking.folio.data.LibraryDatabase
import dev.sadking.folio.data.LibraryRepository
import dev.sadking.folio.data.RemovedEntry
import dev.sadking.folio.data.WishBook
import dev.sadking.folio.data.encode
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Automatic matching (docs/HARDCOVER_SYNC.md A2), against recorded Hardcover answers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HardcoverMatcherTest {
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository
    private lateinit var store: HardcoverStore
    private lateinit var coverDir: File
    private lateinit var matcher: HardcoverMatcher
    private val api = FakeApi()

    private class FakeApi : HardcoverApi {
        val isbnRequests = mutableListOf<List<String>>()
        val searches = mutableListOf<String>()
        val hits = mutableMapOf<String, MatchCandidate>()
        /** Search results for a text containing the key (any case). */
        val results = mutableMapOf<String, List<MatchCandidate>>()
        var failSearchAfter = Int.MAX_VALUE
        override val accountId = 7

        override fun libraryPage(after: Int): List<RemoteUserBook> = error("unused")

        override fun setStatus(hardcoverId: Int, status: Int, userBookId: Int?): Int =
            error("matching never writes to Hardcover")

        override fun markRead(hardcoverId: Int, finishedOn: LocalDate?) =
            error("matching never writes to Hardcover")

        override fun editionsByIsbn(isbns: List<String>): List<IsbnHit> {
            assertTrue(isbns.size <= 100)
            isbnRequests += isbns
            return isbns.mapNotNull { isbn -> hits[isbn]?.let { IsbnHit(isbn, it) } }
        }

        override fun search(text: String): List<MatchCandidate> {
            if (searches.size >= failSearchAfter)
                throw HardcoverHttpException(429, "busy", retryAfterMs = 60_000)
            searches += text
            return results.entries
                .firstOrNull { text.contains(it.key, ignoreCase = true) }
                ?.value
                .orEmpty()
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repo = LibraryRepository(context, db)
        coverDir = File(context.filesDir, "candidates-${UUID.randomUUID()}")
        store = HardcoverStore(db, coverDir)
        matcher =
            HardcoverMatcher(
                repo,
                store,
                HardcoverLinks(repo),
                HardcoverCover { byteArrayOf(1, 2, 3) },
                coverDir,
            ) {
                100L
            }
    }

    @After
    fun tearDown() {
        db.close()
        coverDir.deleteRecursively()
    }

    /** Book ids are UUIDs, as in the app (a catalogue entry's linkedBookId must be one). */
    private fun uid(name: String) = UUID.nameUUIDFromBytes(name.toByteArray()).toString()

    /** A valid ISBN-13 for [n]. */
    private fun isbn(n: Int): String {
        val body = "978" + n.toString().padStart(9, '0')
        val sum = body.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return body + (10 - sum % 10) % 10
    }

    private suspend fun book(
        id: String,
        title: String = "Dune",
        authors: List<String> = listOf("Frank Herbert"),
        isbns: List<String> = emptyList(),
    ): Book =
        Book(
                uid(id),
                title,
                encode(authors),
                identifiers = encode(isbns.map { "urn:isbn:$it" }),
                originalPath = "/nowhere/$id.epub",
                currentPath = "/nowhere/$id.epub",
                originalHash = "$id#".padEnd(64, '0'),
                currentHash = "$id#".padEnd(64, '0'),
            )
            .also { db.library().insert(it) }

    private fun candidate(id: Int, title: String = "Dune", author: String = "Frank Herbert") =
        MatchCandidate(id, title, listOf(author), 1965, "https://assets.hardcover.app/$id.jpg")

    private suspend fun link(bookId: String) =
        db.library().allStoredWishes().firstOrNull { it.linkedBookId == uid(bookId) }

    private suspend fun record(itemId: String) =
        store.allMatchRecords().firstOrNull { it.itemId == uid(itemId) }

    private fun batch(deadline: Long = Long.MAX_VALUE) = runBlocking {
        matcher.matchBatch(api, deadline) { _, _ -> }
    }

    @Test
    fun oneIsbnHitThatAgreesLinksTheBook() = runBlocking {
        book("b", isbns = listOf(isbn(1)))
        api.hits[isbn(1)] = candidate(9)
        assertEquals(1, batch())
        val wish = link("b")!!
        assertEquals(9, wish.hardcoverId)
        assertEquals(1, api.isbnRequests.size)
        assertTrue(api.searches.isEmpty())
        assertTrue(matcher.pending().isEmpty())
    }

    @Test
    fun manyBooksShareOneIsbnRequest() = runBlocking {
        repeat(60) { book("b$it", title = "Book $it", isbns = listOf(isbn(it + 1))) }
        batch()
        // Each ISBN-13 starting with 978 is also asked as its ISBN-10: 50 books per request.
        assertEquals(listOf(100, 20), api.isbnRequests.map { it.size })
        assertEquals(60, api.searches.size)
    }

    @Test
    fun noIsbnSearchesTitleAndAuthor() = runBlocking {
        book("b")
        api.results["dune"] =
            listOf(
                candidate(9),
                candidate(10, "The Dune Encyclopedia", "Willis McNelly"),
                candidate(11, "Emma", "Jane Austen"),
            )
        assertEquals(1, batch())
        assertEquals(1, api.searches.size)
        assertTrue(api.searches.single().contains("herbert", ignoreCase = true))
        assertEquals(9, link("b")!!.hardcoverId)
    }

    @Test
    fun anUnsureMatchKeepsItsCandidatesAndCovers() = runBlocking {
        book("b")
        api.results["dune"] = listOf(candidate(9), candidate(10))
        assertEquals(0, batch())
        assertNull(link("b"))
        val saved = record("b")!!
        assertEquals(MatchRecordState.UNSURE, saved.state)
        assertEquals(listOf(9, 10), saved.candidates.map { it.hardcoverId })
        for (c in saved.candidates) {
            val cover = File(c.coverPath!!)
            assertEquals(coverDir, cover.parentFile)
            assertArrayEquals(byteArrayOf(1, 2, 3), cover.readBytes())
        }
        assertEquals(1965, saved.candidates[0].releaseYear)
        // Asked once; not asked again until the owner answers or the book changes.
        assertTrue(matcher.pending().isEmpty())
    }

    @Test
    fun nothingFoundIsRememberedUntilTheBookChanges() = runBlocking {
        val dune = book("b")
        batch()
        assertEquals(MatchRecordState.NOT_FOUND, record("b")!!.state)
        assertTrue(matcher.pending().isEmpty())
        // A bracketed series or edition note is ignored; a different title counts.
        db.library().update(dune.copy(title = "Dune (Deluxe Edition)"))
        assertTrue(matcher.pending().isEmpty())
        db.library().update(dune.copy(title = "Dune Messiah"))
        assertEquals(listOf(uid("b")), matcher.pending().map { it.itemId })
    }

    @Test
    fun aBlankTitleIsNotSearched() = runBlocking {
        book("b", title = "", authors = emptyList())
        batch()
        assertTrue(api.searches.isEmpty())
        assertEquals(MatchRecordState.NOT_FOUND, record("b")!!.state)
    }

    @Test
    fun notOnHardcoverIsNeverMatchedAgain() = runBlocking {
        book("b")
        store.saveMatch(
            MatchRecord(uid("b"), MatchRecordState.NOT_ON_HARDCOVER, emptyList(), "", 1)
        )
        assertTrue(matcher.pending().isEmpty())
        batch()
        assertTrue(api.searches.isEmpty())
    }

    @Test
    fun recentlyRemovedBooksAreLeftAlone() = runBlocking {
        book("b")
        db.library().markRemoved(RemovedEntry(uid("b"), "g", "Dune", "book", 1))
        assertTrue(matcher.pending().isEmpty())
    }

    @Test
    fun theDeadlineStopsBeforeTheNextRequest() = runBlocking {
        book("b1", isbns = listOf(isbn(1)))
        book("b2")
        assertEquals(0, matcher.matchBatch(api, deadline = 100L) { _, _ -> })
        assertTrue(api.isbnRequests.isEmpty() && api.searches.isEmpty())
        assertEquals(2, matcher.pending().size)
    }

    @Test
    fun slowDownStopsTheBatchAndKeepsWhatWasSaved() = runBlocking {
        book("b1", title = "Emma", authors = listOf("Jane Austen"))
        book("b2")
        api.failSearchAfter = 1
        val error = runCatching { batch() }.exceptionOrNull() as HardcoverHttpException
        assertEquals(429, error.code)
        assertEquals(1, store.allMatchRecords().size)
        assertEquals(1, matcher.pending().size)
    }

    @Test
    fun progressCountsEveryItem() = runBlocking {
        book("b1", isbns = listOf(isbn(1)))
        book("b2", title = "Emma", authors = listOf("Jane Austen"))
        api.hits[isbn(1)] = candidate(9)
        val seen = mutableListOf<Pair<Int, Int>>()
        matcher.matchBatch(api, Long.MAX_VALUE) { done, total -> seen += done to total }
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), seen)
    }

    @Test
    fun theOwnersHardcoverLibraryMatchesWithoutRequests() = runBlocking {
        book("b")
        val own =
            RemoteUserBook(1, 9, 1, null, "Dune", listOf("Frank Herbert"), 1965, null, null, null)
        assertEquals(1, matcher.fromLibrary(listOf(own)))
        assertEquals(9, link("b")!!.hardcoverId)
        assertTrue(api.isbnRequests.isEmpty() && api.searches.isEmpty())
    }

    @Test
    fun aCatalogueEntryWithoutAFileIsMatchedOnItsOwnDetails() = runBlocking {
        db.library()
            .insertWish(
                WishBook(
                    "w",
                    "manual:w",
                    "Dune",
                    encode(listOf("Frank Herbert")),
                    details = """{"identifiers":["${isbn(1)}"]}""",
                )
            )
        api.hits[isbn(1)] = candidate(9)
        assertEquals(1, batch())
        assertEquals(9, db.library().wish("w")!!.hardcoverId)
    }

    @Test
    fun aConfidentMatchAlreadyLinkedElsewhereIsLeftToTheOwner() = runBlocking {
        book("other")
        db.library()
            .insertWish(
                WishBook(
                    "w",
                    "hardcover:9",
                    "Dune",
                    encode(listOf("Frank Herbert")),
                    hardcoverId = 9,
                    linkedBookId = uid("other"),
                )
            )
        book("b", isbns = listOf(isbn(1)))
        api.hits[isbn(1)] = candidate(9)
        assertEquals(0, batch())
        assertNull(link("b"))
        val saved = record("b")!!
        assertEquals(MatchRecordState.UNSURE, saved.state)
        assertEquals(listOf(9), saved.candidates.map { it.hardcoverId })
    }
}
