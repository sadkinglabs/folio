package dev.sadking.folio.reader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Selecting across a page turn, on the JVM: when the page turns under a held selection. */
class ReaderSelectionTurnTest {
    private fun fromPage(value: Any?): String = JSONObject.quote(value.toString())

    @Test
    fun readsThePagesAnswer() {
        val raw = fromPage(JSONObject().put("edge", 1).put("length", 42).put("key", "3/1:7@0"))
        assertEquals(SelectionEdge(1, 42, "3/1:7@0"), parseSelectionEdge(raw))
        // Nothing selected, a broken answer, or no key: nothing to follow.
        assertNull(parseSelectionEdge("null"))
        assertNull(parseSelectionEdge(fromPage("null")))
        assertNull(parseSelectionEdge("not json"))
        assertNull(parseSelectionEdge(fromPage(JSONObject().put("edge", 1))))
        // Anything else is clamped to a direction.
        val odd = fromPage(JSONObject().put("edge", 5).put("length", -1).put("key", "k"))
        assertEquals(SelectionEdge(1, 0, "k"), parseSelectionEdge(odd))
    }

    @Test
    fun aSelectionHeldOnThePagesLastWordTurnsOnceAfterTheDwell() {
        val turner = SelectionPageTurner(dwellMs = 500)
        // Long-pressed in the middle, then dragged on to the last word of the page.
        assertEquals(0, turner.next(SelectionEdge(0, 5, "a@0"), 0))
        assertEquals(0, turner.next(SelectionEdge(0, 30, "b@0"), 120))
        assertEquals(0, turner.next(SelectionEdge(1, 60, "end@0"), 240))
        assertEquals(0, turner.next(SelectionEdge(1, 60, "end@0"), 600))
        assertEquals(1, turner.next(SelectionEdge(1, 60, "end@0"), 740))
        // Held there still: no second turn.
        assertEquals(0, turner.next(SelectionEdge(1, 60, "end@0"), 1500))
        // On the new page the end is off screen until the handle moves on.
        assertEquals(0, turner.next(SelectionEdge(0, 60, "end@1080"), 1620))
        assertEquals(0, turner.next(SelectionEdge(0, 90, "next@1080"), 1740))
    }

    @Test
    fun movingTheHandleRestartsTheWait() {
        val turner = SelectionPageTurner(dwellMs = 500)
        turner.next(SelectionEdge(0, 5, "a@0"), 0)
        turner.next(SelectionEdge(1, 40, "last@0"), 100)
        // Back off the last word before the wait is over, then onto it again.
        turner.next(SelectionEdge(0, 35, "before@0"), 400)
        assertEquals(0, turner.next(SelectionEdge(1, 40, "last@0"), 500))
        assertEquals(0, turner.next(SelectionEdge(1, 40, "last@0"), 900))
        assertEquals(1, turner.next(SelectionEdge(1, 40, "last@0"), 1000))
    }

    @Test
    fun anEndJitteringAtTheCornerStillTurnsAfterTheDwell() {
        // Near the corner the end jumps between the last word and the next page's column.
        val turner = SelectionPageTurner()
        turner.next(SelectionEdge(0, 5, "a@0"), 0)
        assertEquals(0, turner.next(SelectionEdge(1, 60, "last@0"), 100))
        assertEquals(0, turner.next(SelectionEdge(1, 61, "past@0"), 250))
        assertEquals(0, turner.next(SelectionEdge(1, 60, "last@0"), 400))
        assertEquals(1, turner.next(SelectionEdge(1, 61, "past@0"), 600))
        // Still at the edge on the new page's side: no second turn until it leaves.
        assertEquals(0, turner.next(SelectionEdge(1, 61, "past@1080"), 1200))
    }

    @Test
    fun goingBackWorksTheSameWay() {
        val turner = SelectionPageTurner(dwellMs = 500)
        turner.next(SelectionEdge(0, 5, "s@1080"), 0)
        turner.next(SelectionEdge(-1, 50, "first@1080"), 100)
        assertEquals(-1, turner.next(SelectionEdge(-1, 50, "first@1080"), 600))
    }

    @Test
    fun aLongPressedLastWordDoesNotTurn() {
        // Selecting the page's last word, to look it up: the selection never grew.
        val turner = SelectionPageTurner(dwellMs = 500)
        assertEquals(0, turner.next(SelectionEdge(1, 7, "w@0"), 0))
        assertEquals(0, turner.next(SelectionEdge(1, 7, "w@0"), 2000))
    }

    @Test
    fun aClearedSelectionStartsOver() {
        val turner = SelectionPageTurner(dwellMs = 500)
        turner.next(SelectionEdge(0, 5, "a@0"), 0)
        turner.next(SelectionEdge(1, 40, "z@0"), 100)
        assertEquals(1, turner.next(SelectionEdge(1, 40, "z@0"), 600))
        assertEquals(0, turner.next(null, 700))
        // A new long press on the page's last word is its first size again.
        assertEquals(0, turner.next(SelectionEdge(1, 40, "z@0"), 800))
        assertEquals(0, turner.next(SelectionEdge(1, 40, "z@0"), 1400))
    }

    @Test
    fun aPassageAcrossTwoPagesOfAChapterIsOneHighlight() {
        // The pointers of a selection that runs from one page onto the next: the same chapter.
        val raw =
            fromPage(
                JSONObject()
                    .put("pos0", "/body/DocFragment[3]/body/p[4]/text().12")
                    .put("pos1", "/body/DocFragment[3]/body/p[9]/text().20")
                    .put("text", "the end of one page and the start of the next")
            )
        // DocFragment[3] is the chapter with KOReader index 2.
        val passage = parseSelection(raw, 2, 5)
        assertNotNull(passage)
        val row = phoneAnnotation("b1", passage!!, "", "Chapter two", "2026-10-17 10:00:00")
        assertEquals(listOf(2), placeableHighlights(listOf(row), 5).map { it.chapter })
    }
}
