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
        nav.open(Route.List("l"))
        nav.open(Route.List("l"))
        assertEquals(1, nav.stack.size)
    }

    @Test
    fun listDetailIsARouteAboveTheLibrarysListsTabAndABookAboveIt() {
        val nav = FolioNav()
        nav.root(FolioNav.LISTS)
        assertEquals(FolioNav.LIBRARY, nav.tab)
        assertTrue(nav.listsRequested)
        nav.listsShown()
        nav.open(Route.List("reading:FINISHED"))
        assertEquals("reading:FINISHED", nav.listId)
        assertNull(nav.bookId)
        nav.open(Route.Book("a"))
        assertNull(nav.listId)
        assertEquals("a", nav.bookId)
        assertTrue(nav.back())
        assertEquals("reading:FINISHED", nav.listId)
        // A tap on Lists returns to its overview; nothing else is remembered.
        nav.root(FolioNav.LISTS)
        assertNull(nav.listId)
        assertTrue(nav.stack.isEmpty())
        assertEquals(FolioNav.LIBRARY, nav.tab)
        assertTrue(nav.listsRequested)
    }

    @Test
    fun rootingAnotherTabClearsTheListsRequestAndListsShownClearsIt() {
        val nav = FolioNav()
        assertFalse(nav.listsRequested)
        nav.root(FolioNav.LISTS)
        assertTrue(nav.listsRequested)
        nav.listsShown()
        assertFalse(nav.listsRequested)
        nav.root(FolioNav.LISTS)
        nav.root(FolioNav.DEVICE)
        assertFalse(nav.listsRequested)
        assertEquals(FolioNav.DEVICE, nav.tab)
    }

    @Test
    fun anOldSavedListsTabRestoresToTheLibraryWithListsAndSavesAsLibrary() {
        val restored = FolioNav.saver.restore(listOf("1", "list:l"))!!
        assertEquals(FolioNav.LIBRARY, restored.tab)
        assertTrue(restored.listsRequested)
        assertEquals(listOf<Route>(Route.List("l")), restored.stack.toList())
        val saved = with(FolioNav.saver) { SaverScope { true }.save(restored) }!!
        assertEquals(listOf("0", "list:l"), saved)
    }

    @Test
    fun fiveSavedIndicesWithStatsLastAndListsInsideTheLibrary() {
        assertEquals(5, FolioNav.TABS)
        assertEquals(0, FolioNav.LIBRARY)
        // 1 is no bar item any more: it shows the Library's Lists tab.
        assertEquals(1, FolioNav.LISTS)
        assertEquals(2, FolioNav.DEVICE)
        assertEquals(3, FolioNav.SETTINGS)
        assertEquals(4, FolioNav.STATS)
        val nav = FolioNav()
        nav.root(FolioNav.SETTINGS)
        assertEquals(FolioNav.SETTINGS, nav.tab)
        nav.root(FolioNav.STATS)
        assertEquals(4, nav.tab)
        assertFalse(nav.listsRequested)
        nav.root(9)
        assertEquals(FolioNav.STATS, nav.tab)
    }

    @Test
    fun aSavedStatsTabRestoresToStats() {
        val restored = FolioNav.saver.restore(listOf("4"))!!
        assertEquals(FolioNav.STATS, restored.tab)
        val saved = with(FolioNav.saver) { SaverScope { true }.save(restored) }!!
        assertEquals(listOf("4"), saved)
    }

    @Test
    fun listAndSearchRoutesRoundTripAndListsAreForgotten() {
        val nav = FolioNav()
        nav.open(Route.List("l"))
        nav.open(Route.Search("pride and: prejudice"))
        val saved = with(FolioNav.saver) { SaverScope { true }.save(nav) }!!
        val restored = FolioNav.saver.restore(saved)!!
        assertEquals(nav.stack.toList(), restored.stack.toList())
        nav.forget(setOf("l"))
        assertEquals(listOf<Route>(Route.Search("pride and: prejudice")), nav.stack.toList())
    }

    @Test
    fun aYearInReviewIsARouteThatSurvivesRestoreAndIsNotADetail() {
        val nav = FolioNav(tab = FolioNav.STATS)
        nav.open(Route.Year(2026))
        assertEquals(2026, nav.yearShown)
        assertNull(nav.detail)
        assertNull(nav.bookId)
        assertEquals("year:2026", Route.Year(2026).encode())
        val saved = with(FolioNav.saver) { SaverScope { true }.save(nav) }!!
        assertEquals(listOf("4", "year:2026"), saved)
        val restored = FolioNav.saver.restore(saved)!!
        assertEquals(FolioNav.STATS, restored.tab)
        assertEquals(listOf<Route>(Route.Year(2026)), restored.stack.toList())
        nav.forget(setOf("2026", "anything"))
        assertEquals(2026, nav.yearShown)
        // A book opened from the review sits above it; back returns to the review.
        nav.open(Route.Book("a"))
        assertNull(nav.yearShown)
        assertTrue(nav.back())
        assertEquals(2026, nav.yearShown)
        assertTrue(nav.back())
        assertNull(nav.yearShown)
    }

    @Test
    fun aYearRouteWithoutARealYearIsDropped() {
        val restored =
            FolioNav.saver.restore(listOf("4", "year:abc", "year:12", "year:", "year:10000"))!!
        assertTrue(restored.stack.isEmpty())
        assertNull(Route.decode("year:1899"))
        assertEquals(Route.Year(1900), Route.decode("year:1900"))
        assertEquals(Route.Year(9999), Route.decode("year:9999"))
    }

    @Test
    fun scanACoverIsARouteThatSurvivesRestoreAndBackLeavesIt() {
        val nav = FolioNav()
        nav.open(Route.Discover)
        assertTrue(nav.discovering)
        assertNull(nav.bookId)
        val saved = with(FolioNav.saver) { SaverScope { true }.save(nav) }!!
        val restored = FolioNav.saver.restore(saved)!!
        assertTrue(restored.discovering)
        nav.forget(setOf("anything"))
        assertTrue(nav.discovering)
        assertTrue(nav.back())
        assertFalse(nav.discovering)
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
        nav.open(Route.List("l"))
        val saved = with(FolioNav.saver) { SaverScope { true }.save(nav) }!!
        val restored = FolioNav.saver.restore(saved)!!
        assertEquals(3, restored.tab)
        assertEquals(nav.stack.toList(), restored.stack.toList())
        // Unknown or malformed entries from older versions are dropped, not fatal. "settings" was
        // a route before Settings became a tab.
        val legacy =
            FolioNav.saver.restore(listOf("9", "book:", "mystery:x", "settings", "record:r"))!!
        assertEquals(FolioNav.TABS - 1, legacy.tab)
        assertEquals(listOf<Route>(Route.Record("r")), legacy.stack.toList())
    }
}
