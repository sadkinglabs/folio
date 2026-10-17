package dev.sadking.folio.ui

import org.junit.Assert.*
import org.junit.Test

/** The tablet and foldable rules: width classes, the frame per window, and where routes land. */
class AdaptiveLayoutTest {
    @Test
    fun widthClassesFollowMaterialBreakpoints() {
        assertEquals(WidthClass.COMPACT, widthClass(0))
        assertEquals(WidthClass.COMPACT, widthClass(599))
        assertEquals(WidthClass.MEDIUM, widthClass(600))
        assertEquals(WidthClass.MEDIUM, widthClass(839))
        assertEquals(WidthClass.EXPANDED, widthClass(840))
        assertEquals(WidthClass.EXPANDED, widthClass(1280))
    }

    @Test
    fun aPhoneKeepsTheBarAndOnePane() {
        val phone = folioWindow(412, 892)
        assertEquals(FolioLayout.BAR, phone.layout)
        assertFalse(phone.twoPane)
        // The default before the real window is known is a phone, so nothing changes by accident.
        assertEquals(FolioLayout.BAR, folioWindow(412, 892).layout)
    }

    @Test
    fun mediumWidthGetsTheRailAndOnePane() {
        val unfolded = folioWindow(700, 840)
        assertEquals(WidthClass.MEDIUM, unfolded.width)
        assertEquals(FolioLayout.RAIL, unfolded.layout)
        assertFalse(unfolded.twoPane)
    }

    @Test
    fun expandedWidthGetsTwoPanes() {
        assertEquals(FolioLayout.TWO_PANE, folioWindow(840, 1280).layout)
        assertEquals(FolioLayout.TWO_PANE, folioWindow(1280, 800).layout)
    }

    @Test
    fun aHingeSplitsAMediumWindowInTwo() {
        val book = folioWindow(700, 840, hingeDp = 350)
        assertEquals(350, book.hingeDp)
        assertEquals(FolioLayout.TWO_PANE, book.layout)
        assertTrue(book.twoPane)
    }

    @Test
    fun aHingeNeverSplitsAPhoneOrSitsOutsideTheWindow() {
        // A phone-sized window stays one pane even if a fold is reported.
        assertEquals(FolioLayout.BAR, folioWindow(412, 892, hingeDp = 206).layout)
        // A hinge at an edge or beyond it is not a split.
        assertNull(folioWindow(700, 840, hingeDp = 0).hingeDp)
        assertNull(folioWindow(700, 840, hingeDp = 700).hingeDp)
        assertEquals(FolioLayout.RAIL, folioWindow(700, 840, hingeDp = 900).layout)
        // No hinge, no hinge width.
        assertEquals(0, folioWindow(700, 840, hingeWidthDp = 20).hingeWidthDp)
    }

    @Test
    fun onlyAVerticalSeparatingFoldIsAHinge() {
        // 2.5 px per dp: a 0-width fold at 875 px is at 350 dp.
        assertEquals(350 to 0, verticalHinge(true, true, 875, 875, 2.5f))
        // A hinge between two screens has a width.
        assertEquals(400 to 20, verticalHinge(true, true, 1000, 1050, 2.5f))
        // Flat (not separating), tabletop (horizontal), or no density: not a hinge.
        assertNull(verticalHinge(false, true, 875, 875, 2.5f))
        assertNull(verticalHinge(true, false, 875, 875, 2.5f))
        assertNull(verticalHinge(true, true, 875, 875, 0f))
    }

    @Test
    fun theListPaneIsBetween360And400WithoutAHinge() {
        // Tablet portrait: 840 - 80 rail = 760 dp of room; 40% is 304, so 360.
        assertEquals(360, listPaneWidthDp(folioWindow(840, 1280), 80))
        // Tablet landscape: 1200 dp of room; 40% is 480, so 400.
        assertEquals(400, listPaneWidthDp(folioWindow(1280, 800), 80))
        // In between, 40%.
        assertEquals(380, listPaneWidthDp(folioWindow(1030, 800), 80))
    }

    @Test
    fun theListPaneEndsAtTheHinge() {
        // Hinge at 350 dp from the window's left, panes start after an 80 dp rail.
        assertEquals(270, listPaneWidthDp(folioWindow(700, 840, 350), 80))
        assertEquals(370, listPaneWidthDp(folioWindow(900, 840, 450), 80))
        // A hinge that would leave either pane too narrow is ignored.
        val lopsided = folioWindow(900, 840, 300)
        assertEquals(listPaneWidthDp(folioWindow(900, 840), 80), listPaneWidthDp(lopsided, 80))
    }

    @Test
    fun theListPaneAlwaysLeavesRoomForTheDetail() {
        val window = folioWindow(600, 900, hingeDp = null)
        val list = listPaneWidthDp(window, 80)
        assertTrue(600 - 80 - list >= MIN_PANE_DP)
    }

    @Test
    fun aBookOrRecordOrListOnTopFillsTheRightPane() {
        val nav = FolioNav()
        assertNull(nav.detail)
        nav.open(Route.Book("a"))
        assertEquals(Route.Book("a"), nav.detail)
        nav.open(Route.Edit("a"))
        assertEquals(Route.Edit("a"), nav.detail)
        nav.root(FolioNav.LIBRARY)
        nav.open(Route.Record("w"))
        assertEquals(Route.Record("w"), nav.detail)
        nav.root(FolioNav.LISTS)
        nav.open(Route.List("l"))
        assertEquals(Route.List("l"), nav.detail)
        assertNull(nav.listBeside)
    }

    @Test
    fun theScanScreenIsNeverADetail() {
        val nav = FolioNav()
        nav.open(Route.Book("a"))
        nav.open(Route.Discover)
        assertNull(nav.detail)
        assertNull(nav.listBeside)
    }

    @Test
    fun aBookOpenedFromAListKeepsTheListOnTheLeft() {
        val nav = FolioNav(FolioNav.LISTS)
        nav.open(Route.List("l"))
        nav.open(Route.Book("a"))
        assertEquals(Route.Book("a"), nav.detail)
        assertEquals("l", nav.listBeside)
        nav.open(Route.Edit("a"))
        assertEquals("l", nav.listBeside)
        // Back pops as on a phone: the editor, then the book, then the list is on the right.
        nav.back()
        nav.back()
        assertEquals(Route.List("l"), nav.detail)
        assertNull(nav.listBeside)
    }

    @Test
    fun choosingFromTheLeftPaneReplacesTheDetailInsteadOfPilingUp() {
        val nav = FolioNav()
        nav.open(Route.Book("a"))
        nav.clearAbove(null)
        nav.open(Route.Book("b"))
        assertEquals(listOf<Route>(Route.Book("b")), nav.stack.toList())
        // From a list on the left, the list stays under the new book.
        nav.root(FolioNav.LISTS)
        nav.open(Route.List("l"))
        nav.open(Route.Book("a"))
        nav.clearAbove(Route.List("l"))
        nav.open(Route.Book("b"))
        assertEquals(listOf(Route.List("l"), Route.Book("b")), nav.stack.toList())
        // A base that is not open clears everything.
        nav.clearAbove(Route.List("gone"))
        assertTrue(nav.stack.isEmpty())
        assertEquals(FolioNav.LISTS, nav.tab)
    }

    @Test
    fun theStackAndTabSurviveRecreationWhateverTheLayout() {
        // Rotation or folding recreates the activity; the saver brings back the open book, which
        // is the selected item in two panes and the screen on top on a phone.
        val nav = FolioNav(FolioNav.LISTS, listOf(Route.List("l"), Route.Book("a")))
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val saved = with(FolioNav.saver) { scope.save(nav) }!!
        val restored = FolioNav.saver.restore(saved)!!
        assertEquals(FolioNav.LISTS, restored.tab)
        assertEquals(Route.Book("a"), restored.detail)
        assertEquals("l", restored.listBeside)
    }
}
