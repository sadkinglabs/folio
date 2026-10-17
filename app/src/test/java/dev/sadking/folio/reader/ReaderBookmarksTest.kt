package dev.sadking.folio.reader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.LibraryDatabase
import dev.sadking.folio.data.PhoneBookmark
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Bookmarks (M11 B) on the JVM with an in-memory Room: toggle, Undo, rename, page match. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderBookmarksTest {
    private lateinit var db: LibraryDatabase
    private lateinit var store: BookmarkStore
    private var clock = 1_000L
    private val book =
        Book(
            id = "00000000-0000-0000-0000-000000000001",
            title = "The Orchard",
            authors = "[]",
            originalPath = "/books/orchard.epub",
            currentPath = "/books/orchard.epub",
            originalHash = "rev1",
            currentHash = "rev1",
        )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        runBlocking { db.library().insert(book) }
        store = BookmarkStore(db.library()) { clock++ }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun page(
        href: String = "chapter1.xhtml",
        progression: Double? = 0.25,
        position: Int? = null,
        title: String? = "Chapter one",
        total: Double = 0.12,
    ) =
        Locator(
            Url(href)!!,
            MediaType.XHTML,
            title = title,
            locations =
                Locator.Locations(
                    progression = progression,
                    position = position,
                    totalProgression = total,
                ),
        )

    private fun stored(): List<PhoneBookmark> = runBlocking {
        db.library().phoneBookmarks(book.id, book.currentHash).first()
    }

    @Test
    fun oneTapAddsAndASecondRemoves() = runBlocking {
        val added = store.toggle(book, page(), span = null, byPosition = false)
        assertTrue(added is BookmarkChange.Added)
        val mark = (added as BookmarkChange.Added).mark
        assertEquals("Chapter one · 12%", mark.title)
        assertEquals(listOf(mark), stored())

        val removed = store.toggle(book, page(), span = null, byPosition = false)
        assertEquals(BookmarkChange.Removed(listOf(mark)), removed)
        assertTrue(stored().isEmpty())
    }

    @Test
    fun undoPutsBackTheSameBookmark() = runBlocking {
        store.toggle(book, page(), null, false)
        store.renameBookmarkFor(stored().single().id, "Where Ada starts")
        val before = stored().single()
        val removed = store.toggle(book, page(), null, false) as BookmarkChange.Removed
        assertTrue(stored().isEmpty())
        store.restore(removed.marks)
        // Same id, name, place and date: nothing about it was lost.
        assertEquals(listOf(before), stored())
        // Undo twice is harmless.
        store.restore(removed.marks)
        assertEquals(listOf(before), stored())
    }

    private suspend fun BookmarkStore.renameBookmarkFor(id: String, title: String) =
        assertTrue(rename(id, title))

    @Test
    fun removingAPageTakesEveryBookmarkOnIt() = runBlocking {
        // Two bookmarks from an older text size, both on the page shown now.
        db.library().addPhoneBookmark(mark("a", page(progression = 0.31)))
        db.library().addPhoneBookmark(mark("b", page(progression = 0.33)))
        db.library().addPhoneBookmark(mark("c", page(progression = 0.50)))
        val removed = store.toggle(book, page(progression = 0.30), span = 0.05, byPosition = false)
        assertEquals(
            setOf("a", "b"),
            (removed as BookmarkChange.Removed).marks.map { it.id }.toSet(),
        )
        assertEquals(listOf("c"), stored().map { it.id })
    }

    @Test
    fun anotherPageGetsItsOwnBookmark() = runBlocking {
        store.toggle(book, page(progression = 0.25), null, false)
        val second = store.toggle(book, page(progression = 0.30), null, false)
        assertTrue(second is BookmarkChange.Added)
        assertEquals(2, stored().size)
    }

    @Test
    fun renameKeepsOneTrimmedLineAndIgnoresBlank() = runBlocking {
        store.toggle(book, page(), null, false)
        val id = stored().single().id
        assertTrue(store.rename(id, "  Ada's   walnut \n tree "))
        assertEquals("Ada's walnut tree", stored().single().title)
        assertFalse(store.rename(id, "   "))
        assertEquals("Ada's walnut tree", stored().single().title)
        assertTrue(store.rename(id, "x".repeat(500)))
        assertEquals(BOOKMARK_TITLE_MAX, stored().single().title.length)
    }

    @Test
    fun aBookmarkOnAnotherRevisionIsNotTouched() = runBlocking {
        val old = mark("old", page()).copy(revisionHash = "rev0")
        db.library().addPhoneBookmark(old)
        val change = store.toggle(book, page(), null, false)
        assertTrue(change is BookmarkChange.Added)
        assertEquals(
            listOf(old),
            db.library().phoneBookmarks(book.id, "rev0").first(),
        )
    }

    private fun mark(id: String, at: Locator) =
        PhoneBookmark(id, book.id, book.currentHash, at.toJSON().toString(), "Mark $id", clock++)

    @Test
    fun withoutTheScreenSizeOnlyTheSamePageStartMatches() {
        assertTrue(bookmarkOnPage(page(progression = 0.25), page(progression = 0.25), null))
        assertFalse(bookmarkOnPage(page(progression = 0.26), page(progression = 0.25), null))
        assertFalse(bookmarkOnPage(page(progression = 0.25), page("chapter2.xhtml", 0.25), null))
    }

    @Test
    fun withTheScreenSizeAnyBookmarkOnTheScreenMatches() {
        val shown = page(progression = 0.20)
        assertTrue(bookmarkOnPage(page(progression = 0.20), shown, 0.1))
        assertTrue(bookmarkOnPage(page(progression = 0.25), shown, 0.1))
        assertTrue(bookmarkOnPage(page(progression = 0.29), shown, 0.1))
        // The next page starts at 0.30.
        assertFalse(bookmarkOnPage(page(progression = 0.30), shown, 0.1))
        assertFalse(bookmarkOnPage(page(progression = 0.15), shown, 0.1))
    }

    @Test
    fun pdfsAndComicsMatchByPageNumber() {
        val pdf = page("book.pdf", progression = null, position = 12)
        assertTrue(bookmarkOnPage(page("book.pdf", 0.9, 12), pdf, null, byPosition = true))
        assertFalse(bookmarkOnPage(page("book.pdf", null, 13), pdf, null, byPosition = true))
    }

    @Test
    fun aBadStoredPlaceNeverMatches() = runBlocking {
        db.library().addPhoneBookmark(PhoneBookmark("bad", book.id, book.currentHash, "{", "?", 1))
        assertNull(placed(stored().single()).locator)
        // The page gets a new bookmark; the unreadable one stays.
        assertTrue(store.toggle(book, page(), null, false) is BookmarkChange.Added)
        assertEquals(2, stored().size)
    }

    @Test
    fun bookmarkNamesAndThePageScript() {
        assertEquals("40%", bookmarkTitle(page(title = null, total = 0.4)))
        assertNull(cleanBookmarkTitle(" \n "))
        assertTrue(pageSpanScript(scrolling = false).contains("innerWidth/e.scrollWidth"))
        assertTrue(pageSpanScript(scrolling = true).contains("innerHeight/e.scrollHeight"))
    }
}
