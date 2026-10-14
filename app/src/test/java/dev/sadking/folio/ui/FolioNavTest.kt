package dev.sadking.folio.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class FolioNavTest {
    @Test
    fun bookToNextBookToBackReturnsToTheFirstBook() {
        val nav = FolioNav()
        nav.open(Route.Book("a"))
        nav.open(Route.Book("b"))
        assertEquals("b", nav.bookId)
        assertTrue(nav.back())
        assertEquals("a", nav.bookId)
        assertTrue(nav.back())
        assertNull(nav.top)
        assertFalse(nav.back())
    }

    @Test
    fun editorKeepsItsBookAndBackLeavesOnlyTheEditor() {
        val nav = FolioNav()
        nav.open(Route.Book("a"))
        nav.open(Route.Edit("a"))
        assertTrue(nav.editing)
        assertEquals("a", nav.bookId)
        nav.back()
        assertFalse(nav.editing)
        assertEquals("a", nav.bookId)
    }

    @Test
    fun reopeningTheTopScreenDoesNotStackDuplicates() {
        val nav = FolioNav()
        nav.open(Route.Settings)
        nav.open(Route.Settings)
        assertEquals(1, nav.stack.size)
    }

    @Test
    fun choosingATabClearsScreensAndRemovalForgetsDeletedBooks() {
        val nav = FolioNav()
        nav.open(Route.Book("a"))
        nav.open(Route.Record("r"))
        nav.forget(setOf("r"))
        assertEquals(listOf<Route>(Route.Book("a")), nav.stack.toList())
        nav.root(2)
        assertEquals(2, nav.tab)
        assertTrue(nav.stack.isEmpty())
    }

    @Test
    fun recordThatGainsAnEpubBecomesThatBook() {
        val nav = FolioNav()
        nav.open(Route.Record("r"))
        nav.replaceTop(Route.Book("b"))
        assertEquals(listOf<Route>(Route.Book("b")), nav.stack.toList())
    }

    @Test
    fun stateSurvivesSaveAndRestore() {
        val nav = FolioNav(tab = 3)
        nav.open(Route.Book("a"))
        nav.open(Route.Edit("a"))
        nav.open(Route.Settings)
        val saved = with(FolioNav.saver) { SaverScope { true }.save(nav) }!!
        val restored = FolioNav.saver.restore(saved)!!
        assertEquals(3, restored.tab)
        assertEquals(nav.stack.toList(), restored.stack.toList())
        // Unknown or malformed entries from older versions are dropped, not fatal.
        val legacy = FolioNav.saver.restore(listOf("9", "book:", "mystery:x", "record:r"))!!
        assertEquals(FolioNav.TABS - 1, legacy.tab)
        assertEquals(listOf<Route>(Route.Record("r")), legacy.stack.toList())
    }
}
