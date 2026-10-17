package dev.sadking.folio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.sync.HardcoverStore
import dev.sadking.folio.sync.MatchCandidateView
import dev.sadking.folio.sync.MatchRecord
import dev.sadking.folio.sync.MatchRecordState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Automatic and picked Hardcover links (M10, docs/HARDCOVER_SYNC.md A2 and B5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HardcoverLinksTest {
    private lateinit var base: File
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository
    private lateinit var store: HardcoverStore
    private lateinit var links: HardcoverLinks
    private lateinit var context: Context

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        base = File(app.cacheDir, "links-${UUID.randomUUID()}").apply { mkdirs() }
        context =
            object : ContextWrapper(app) {
                override fun getFilesDir() = File(base, "private").apply { mkdirs() }
            }
        db =
            Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repo = LibraryRepository(context, db) { File(base, "Books") }
        repo.folderAccess = { false }
        store = HardcoverStore(db, File(context.filesDir, HardcoverStore.COVER_DIR))
        links = HardcoverLinks(repo)
    }

    @After
    fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    private val dao
        get() = db.library()

    private fun book(title: String = "Dune", cover: Boolean = false): Book = runBlocking {
        val id = UUID.randomUUID().toString()
        val coverPath =
            if (cover)
                File(File(repo.root, id).apply { mkdirs() }, "cover-original")
                    .apply { writeBytes(byteArrayOf(9)) }
                    .path
            else null
        Book(
                id,
                title,
                encode(listOf("Frank Herbert")),
                originalPath = "",
                currentPath = "",
                originalHash = id,
                currentHash = id,
                coverPath = coverPath,
            )
            .also { dao.insert(it) }
    }

    private fun entry(hardcoverId: Int? = null, linkedBookId: String? = null): WishBook =
        runBlocking {
            val id = UUID.randomUUID().toString()
            WishBook(
                    id,
                    hardcoverId?.let { "hardcover:$it" } ?: "manual:$id",
                    "Entry",
                    encode(listOf("Frank Herbert")),
                    hardcoverId = hardcoverId,
                    hardcoverTitle = if (hardcoverId != null) "Entry" else "",
                    hardcoverSynced = true,
                    linkedBookId = linkedBookId,
                )
                .also { dao.insertWish(it) }
        }

    private fun unsure(itemId: String) = runBlocking {
        store.saveMatch(
            MatchRecord(
                itemId,
                MatchRecordState.UNSURE,
                listOf(MatchCandidateView(42, "Dune", listOf("Frank Herbert"), 1965, null)),
                "key",
                1,
            )
        )
    }

    private fun records() = runBlocking { store.allMatchRecords().map { it.itemId } }

    private fun link(itemId: String, hardcoverId: Int = 42) = runBlocking {
        links.link(itemId, hardcoverId, "Dune", listOf("Frank Herbert"))
    }

    private fun refused(itemId: String, hardcoverId: Int = 42): String =
        assertThrows(IllegalArgumentException::class.java) { link(itemId, hardcoverId) }.message!!

    private fun text(id: Int) = context.getString(id)

    private fun removed(id: String, kind: String = "EPUB") = runBlocking {
        dao.markRemoved(RemovedEntry(id, id, "Removed", kind, 1))
    }

    @Test
    fun aBookWithNoEntryGetsANewLinkedEntry() {
        val book = book(cover = true)
        unsure(book.id)
        val entryId = link(book.id)
        val entry = runBlocking { dao.wish(entryId)!! }
        assertEquals("hardcover:42", entry.catalogKey)
        assertEquals(42, entry.hardcoverId)
        assertEquals("Dune", entry.hardcoverTitle)
        assertEquals(book.id, entry.linkedBookId)
        assertFalse("Hardcover hears about it through status sync", entry.hardcoverSynced)
        assertTrue(File(entry.coverPath!!).isFile)
        assertTrue(records().isEmpty())
    }

    @Test
    fun aCatalogueEntryIsLinkedInPlace() {
        val entry = entry()
        unsure(entry.id)
        assertEquals(entry.id, link(entry.id))
        val linked = runBlocking { dao.wish(entry.id)!! }
        assertEquals(42, linked.hardcoverId)
        assertEquals("Dune", linked.hardcoverTitle)
        assertFalse(linked.hardcoverSynced)
        assertEquals(1, runBlocking { dao.allWishes() }.size)
        assertTrue(records().isEmpty())
    }

    @Test
    fun aBookWithAnUnlinkedEntryLinksThatEntry() {
        val book = book()
        val entry = entry(linkedBookId = book.id)
        // Matching knows this item by its catalogue entry.
        unsure(entry.id)
        assertEquals(entry.id, link(book.id))
        assertEquals(42, runBlocking { dao.wish(entry.id)!! }.hardcoverId)
        assertEquals(1, runBlocking { dao.allWishes() }.size)
        assertTrue(records().isEmpty())
    }

    @Test
    fun aBookJoinsTheEntryHardcoverAlreadyHasAndItsLists() {
        val book = book()
        val entry = entry(hardcoverId = 42)
        runBlocking {
            dao.insertShelf(Shelf("s1", "Summer"))
            dao.addWishMembership(WishMembership(entry.id, "s1"))
        }
        assertEquals(entry.id, link(book.id))
        assertEquals(book.id, runBlocking { dao.wish(entry.id)!! }.linkedBookId)
        assertEquals(listOf("s1"), runBlocking { dao.membershipsOf(book.id) }.map { it.shelfId })
        assertEquals(1, runBlocking { dao.allWishes() }.size)
    }

    @Test
    fun linkingTwiceChangesNothing() {
        val book = book()
        val first = link(book.id)
        assertEquals(first, link(book.id))
        assertEquals(1, runBlocking { dao.allWishes() }.size)
    }

    @Test
    fun aKeyLeftByARelinkedEntryIsNotReused() {
        entry(hardcoverId = 7).let { runBlocking { dao.updateWish(it.copy(hardcoverId = 8)) } }
        val book = book()
        val entry = runBlocking { dao.wish(link(book.id, hardcoverId = 7))!! }
        assertEquals("manual:${entry.id}", entry.catalogKey)
        assertEquals(7, entry.hardcoverId)
    }

    @Test
    fun aBookTakenOffItsHardcoverEntryIsNotMatchedAgainOnItsOwn() {
        val book = book()
        val entryId = link(book.id)
        runBlocking { repo.detach(book.id) }
        assertNull(runBlocking { dao.wish(entryId)!! }.linkedBookId)
        val record = runBlocking { store.allMatchRecords() }.single()
        assertEquals(book.id, record.itemId)
        assertEquals(MatchRecordState.NOT_FOUND, record.state)
        assertEquals(
            "Matching skips it while its details stay the same",
            dev.sadking.folio.core.hardcover.HardcoverMatching.metadataKey(
                "Dune",
                listOf("Frank Herbert"),
                emptyList(),
            ),
            record.metadataKey,
        )
        assertEquals("It can still be matched by hand", entryId, link(book.id))
    }

    @Test
    fun detachingFromAnUnlinkedEntryLeavesMatchingAlone() {
        val book = book()
        entry(linkedBookId = book.id)
        runBlocking { repo.detach(book.id) }
        assertTrue(records().isEmpty())
    }

    @Test
    fun purgingABookDropsItsMatchRecord() {
        val book = book()
        val cover = File(context.filesDir, "${HardcoverStore.COVER_DIR}/${book.id}-42")
        cover.parentFile!!.mkdirs()
        cover.writeBytes(byteArrayOf(1))
        runBlocking {
            store.saveMatch(
                MatchRecord(
                    book.id,
                    MatchRecordState.UNSURE,
                    listOf(MatchCandidateView(42, "Dune", emptyList(), null, cover.path)),
                    "key",
                    1,
                )
            )
            val (_, _, groups) = repo.removeLocal(setOf(book.id))
            groups.forEach { repo.purgeRemoved(it) }
        }
        assertTrue(records().isEmpty())
        assertFalse(cover.exists())
    }

    @Test
    fun aGoneItemIsRefused() {
        assertEquals(text(dev.sadking.folio.R.string.msg_book_gone), refused("missing"))
    }

    @Test
    fun anItemInRecentlyRemovedIsRefused() {
        val book = book()
        removed(book.id)
        assertEquals(text(dev.sadking.folio.R.string.msg_library_restore_first), refused(book.id))
        val entry = entry()
        removed(entry.id, "Catalogue")
        assertEquals(
            text(dev.sadking.folio.R.string.msg_library_restore_first),
            refused(entry.id),
        )
    }

    private fun taken(itemId: String, hardcoverId: Int = 42): HardcoverLinkTaken =
        assertThrows(HardcoverLinkTaken::class.java) { link(itemId, hardcoverId) }

    private fun move(itemId: String, hardcoverId: Int = 42) = runBlocking {
        links.link(itemId, hardcoverId, "Hyperion", listOf("Dan Simmons"), move = true)
    }

    private fun takenText(title: String) =
        context.getString(dev.sadking.folio.R.string.hc_link_taken, title)

    private fun takenRemovedText(title: String) =
        context.getString(dev.sadking.folio.R.string.hc_link_taken_removed, title)

    /** Notes, a phone position and an e-reader position for [bookId]: none may be lost. */
    private fun readingData(bookId: String, hash: String) = runBlocking {
        dao.annotation(
            BookAnnotation("note-$bookId", bookId, "kindle", "text", "my note", "", "", "", "", "")
        )
        dao.savePhoneReading(PhoneReading(bookId, hash, "{}", "/body/p", 0.4, 10))
        dao.reading(ReadingSnapshot(bookId, "kindle", 0.5, "/p", "reading", 0, 11, "", "", ""))
    }

    private fun assertReadingDataKept(bookId: String, hash: String) = runBlocking {
        assertNotNull(dao.book(bookId))
        assertEquals(
            listOf("note-$bookId"),
            dao.everyAnnotation().filter { it.bookId == bookId }.map { it.id },
        )
        assertNotNull(dao.phoneReading(bookId, hash))
        // Straight from the table: the DAO's lists leave out books in Recently removed.
        db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM reading WHERE bookId = ?", arrayOf(bookId))
            .use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
    }

    @Test
    fun aHardcoverBookWhoseEntryIsInRecentlyRemovedSaysSoAndCanMoveHere() {
        val other = entry(hardcoverId = 42)
        runBlocking {
            dao.insertShelf(Shelf("s1", "Summer"))
            dao.addWishMembership(WishMembership(other.id, "s1"))
        }
        removed(other.id, "Catalogue")
        val book = book("Hyperion")
        unsure(book.id)
        val refusal = taken(book.id)
        assertEquals(takenRemovedText("Entry"), refusal.message)
        assertTrue(refusal.holder.removed)
        assertEquals("The match stays to check", listOf(book.id), records())
        assertEquals(other.id, move(book.id))
        val entry = runBlocking { dao.wish(other.id)!! }
        assertEquals(book.id, entry.linkedBookId)
        assertNull("Out of Recently removed", runBlocking { dao.removedEntry(other.id) })
        assertEquals(listOf("s1"), runBlocking { dao.membershipsOf(book.id) }.map { it.shelfId })
        assertTrue(records().isEmpty())
    }

    @Test
    fun aHolderWhoseBookIsInRecentlyRemovedSaysSoAndKeepsThatBooksData() {
        val fall = book("The Fall of Hyperion")
        readingData(fall.id, fall.currentHash)
        val holder = runBlocking { dao.wish(link(fall.id))!! }
        runBlocking { repo.removeLocal(setOf(fall.id)) }
        val hyperion = book("Hyperion")
        val refusal = taken(hyperion.id)
        assertEquals(takenRemovedText("The Fall of Hyperion"), refusal.message)
        assertEquals(fall.id, refusal.holder.bookId)
        move(hyperion.id)
        assertEquals(hyperion.id, runBlocking { dao.wish(holder.id)!! }.linkedBookId)
        // The removed book stays in Recently removed, unmatched, with everything it had.
        assertNotNull(runBlocking { dao.removedEntry(fall.id) })
        assertReadingDataKept(fall.id, fall.currentHash)
        runBlocking { repo.restoreRemoved(fall.id) }
        assertTrue(runBlocking { dao.allWishes() }.none { it.linkedBookId == fall.id })
        assertReadingDataKept(fall.id, fall.currentHash)
    }

    @Test
    fun aLinkToABookThatNoLongerExistsIsRepairedNotRefused() {
        val gone = UUID.randomUUID().toString()
        val stale = entry(hardcoverId = 42, linkedBookId = gone)
        val book = book("Hyperion")
        unsure(book.id)
        assertEquals(stale.id, link(book.id))
        assertEquals(book.id, runBlocking { dao.wish(stale.id)!! }.linkedBookId)
        assertTrue(records().isEmpty())
        assertEquals(1, runBlocking { dao.allWishes() }.size)
    }

    @Test
    fun aStaleLinkIsNoAttachmentForAttachEither() {
        val stale = entry(hardcoverId = 42, linkedBookId = UUID.randomUUID().toString())
        val book = book("Hyperion")
        runBlocking { repo.attach(stale.id, book.id) }
        assertEquals(book.id, runBlocking { dao.wish(stale.id)!! }.linkedBookId)
    }

    @Test
    fun aHardcoverBookMatchedToAnotherBookNamesItAndMovesHere() {
        // The owner's case: "The Fall of Hyperion" was matched to Hardcover's "Hyperion".
        val fall = book("The Fall of Hyperion")
        readingData(fall.id, fall.currentHash)
        runBlocking {
            dao.insertShelf(Shelf("s2", "Sci-fi"))
            dao.addMembership(Membership(fall.id, "s2"))
        }
        val holder = runBlocking { dao.wish(link(fall.id))!! }
        val hyperion = book("Hyperion")
        unsure(hyperion.id)
        val refusal = taken(hyperion.id)
        assertEquals(takenText("The Fall of Hyperion"), refusal.message)
        assertEquals(
            HardcoverHolder(holder.id, fall.id, "The Fall of Hyperion", false),
            refusal.holder,
        )
        assertEquals(1, runBlocking { dao.allWishes() }.size)
        assertEquals(holder.id, move(hyperion.id))
        assertEquals(hyperion.id, runBlocking { dao.wish(holder.id)!! }.linkedBookId)
        // The other book is unmatched, goes back to matching, and keeps everything it had.
        assertTrue(runBlocking { dao.allWishes() }.none { it.linkedBookId == fall.id })
        assertReadingDataKept(fall.id, fall.currentHash)
        assertEquals(listOf("s2"), runBlocking { dao.membershipsOf(fall.id) }.map { it.shelfId })
        assertTrue(records().isEmpty())
        val pending =
            dev.sadking.folio.sync.matchItems(
                runBlocking { dao.allBooks() },
                runBlocking { dao.allStoredWishes() },
                emptySet(),
                emptyMap(),
            )
        assertEquals(listOf(fall.id), pending.map { it.itemId })
    }

    @Test
    fun anEntryCanTakeTheHardcoverBookFromAnotherEntryThatKeepsTheRest() {
        val fall = book("The Fall of Hyperion")
        val other = entry(hardcoverId = 42, linkedBookId = fall.id)
        runBlocking { dao.updateWish(other.copy(status = "Finished")) }
        val mine = entry()
        assertEquals(takenText("The Fall of Hyperion"), taken(mine.id).message)
        assertNull(runBlocking { dao.wish(mine.id)!! }.hardcoverId)
        assertEquals(mine.id, move(mine.id))
        assertEquals(42, runBlocking { dao.wish(mine.id)!! }.hardcoverId)
        val left = runBlocking { dao.wish(other.id)!! }
        assertNull(left.hardcoverId)
        assertEquals(fall.id, left.linkedBookId)
        assertEquals("Finished", left.status)
    }

    @Test
    fun anEntryCannotTakeAHardcoverBookAnotherEntryHasWithoutMoving() {
        entry(hardcoverId = 42)
        val entry = entry()
        assertEquals(takenText("Entry"), taken(entry.id).message)
        assertNull(runBlocking { dao.wish(entry.id)!! }.hardcoverId)
    }

    @Test
    fun checkTheseMatchesSaysWhichBookHoldsACandidate() {
        val fall = book("The Fall of Hyperion")
        val holder = runBlocking { dao.wish(link(fall.id))!! }
        val hyperion = book("Hyperion")
        unsure(hyperion.id)
        val checks =
            dev.sadking.folio.sync.matchChecks(
                runBlocking { dao.allStoredBooks() },
                runBlocking { dao.allStoredWishes() },
                runBlocking { store.allMatchRecords() }.associateBy { it.itemId },
            )
        val check = checks.single()
        assertEquals(hyperion.id, check.itemId)
        assertEquals(
            HardcoverHolder(holder.id, fall.id, "The Fall of Hyperion", false),
            check.held[42],
        )
    }
}
