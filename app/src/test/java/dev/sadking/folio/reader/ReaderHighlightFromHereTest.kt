package dev.sadking.folio.reader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderHighlightFromHereTest {
    private fun p(paragraph: Int, offset: Int, chapter: Int = 3) =
        "/body/DocFragment[$chapter]/body/p[$paragraph]/text().$offset"

    /** The first word the owner picked: "orchard" in paragraph 2 of chapter index 2. */
    private val start = HighlightEnd(2, p(2, 4), p(2, 11))

    /** A word on a later page of the same chapter. */
    private val later = HighlightEnd(2, p(9, 20), p(9, 25))

    /** A word earlier in the chapter than [start]. */
    private val earlier = HighlightEnd(2, p(1, 0), p(1, 5))

    /** What WebView hands back for `JSON.stringify(value)`: the JSON of that JSON string. */
    private fun fromPage(value: Any): String = JSONObject.quote(value.toString())

    private class Clock(var now: Long = 1_000L) : () -> Long {
        override fun invoke() = now
    }

    @Test
    fun anEndAfterTheStartRunsFromTheStartWordToTheEndOfTheEndWord() {
        assertEquals(HighlightSpan(start.pos0, later.pos1), highlightSpan(start, later, -1, -1))
    }

    @Test
    fun anEndBeforeTheStartIsSwappedSoBothWordsAreWhole() {
        assertEquals(HighlightSpan(earlier.pos0, start.pos1), highlightSpan(start, earlier, 1, 1))
    }

    @Test
    fun theSameWordTwiceIsThatWord() {
        assertEquals(HighlightSpan(start.pos0, start.pos1), highlightSpan(start, start, 0, 0))
    }

    @Test
    fun anEndThatHoldsTheStartWordCoversBoth() {
        // The end's selection starts before the start word and finishes after it.
        val around = HighlightEnd(2, p(2, 0), p(2, 30))
        assertEquals(HighlightSpan(around.pos0, around.pos1), highlightSpan(start, around, 1, -1))
    }

    @Test
    fun thePagesOrderIsReadFromItsAnswerAndAnythingElseIsNull() {
        assertEquals(-1 to 1, parseOrder(fromPage(JSONArray(listOf(-1, 1)))))
        assertEquals(0 to 0, parseOrder(fromPage(JSONArray(listOf(0, 0)))))
        // Not in the page, or not an answer at all.
        assertNull(parseOrder(fromPage(JSONArray("[null, 1]"))))
        assertNull(parseOrder(fromPage(JSONArray("[1]"))))
        assertNull(parseOrder(fromPage(JSONObject().put("a", 1))))
        assertNull(parseOrder(null))
        assertNull(parseOrder("null"))
    }

    @Test
    fun thePageCallsQuoteThePointers() {
        val order = orderCall(start, later)
        assertTrue(
            order,
            order.contains("FolioHighlights.compare(\"${start.pos0}\",\"${later.pos0}\")"),
        )
        assertTrue(
            order,
            order.contains("FolioHighlights.compare(\"${start.pos1}\",\"${later.pos1}\")"),
        )
        val passage = passageCall(HighlightSpan(start.pos0, later.pos1), emptyList())
        assertTrue(passage, passage.contains("passage(\"${start.pos0}\",\"${later.pos1}\", []"))
        val mark = markCall(start)
        assertTrue(mark, mark.contains("\"id\":\"$FROM_HERE_MARK_ID\""))
        assertTrue(mark, mark.contains("\"pos0\":\"${start.pos0}\""))
    }

    @Test
    fun startThenPagesThenEndSavesOnce() {
        val clock = Clock()
        val mode = HighlightFromHere(clock = clock)
        assertFalse(mode.active)
        assertNull(mode.end(later))
        mode.begin(start)
        assertTrue(mode.active)
        assertEquals(start, mode.start)
        // Page turns are touches: the mode waits on.
        repeat(5) {
            clock.now += 30_000
            mode.touched()
            assertFalse(mode.idle())
        }
        assertEquals(FromHereEnd.Here(start, later), mode.end(later))
        assertTrue(mode.saving)
        // A second press while saving does nothing.
        assertNull(mode.end(earlier))
        mode.finished()
        assertFalse(mode.active)
        assertNull(mode.start)
    }

    @Test
    fun aFailedSaveWaitsAgainFromTheSameStart() {
        val mode = HighlightFromHere(clock = Clock())
        mode.begin(start)
        mode.end(later)
        mode.failed()
        assertTrue(mode.active)
        assertFalse(mode.saving)
        assertEquals(FromHereEnd.Here(start, earlier), mode.end(earlier))
    }

    @Test
    fun anEndInAnotherChapterIsRefusedAndTheStartIsKept() {
        val mode = HighlightFromHere(clock = Clock())
        mode.begin(start)
        val next = HighlightEnd(3, p(1, 0, chapter = 4), p(1, 4, chapter = 4))
        assertEquals(FromHereEnd.OtherChapter, mode.end(next))
        assertTrue(mode.active)
        assertFalse(mode.saving)
        assertEquals(start, mode.start)
        // Back in the start's chapter, it ends there.
        assertEquals(FromHereEnd.Here(start, later), mode.end(later))
    }

    @Test
    fun cancelEndsTheMode() {
        val mode = HighlightFromHere(clock = Clock())
        mode.begin(start)
        mode.cancel()
        assertFalse(mode.active)
        assertNull(mode.end(later))
    }

    @Test
    fun twoMinutesWithoutATouchStopsIt() {
        val clock = Clock()
        val mode = HighlightFromHere(clock = clock)
        assertFalse(mode.idle())
        mode.begin(start)
        assertEquals(FROM_HERE_IDLE_MS, mode.untilIdle())
        clock.now += FROM_HERE_IDLE_MS - 1
        assertFalse(mode.idle())
        assertEquals(1L, mode.untilIdle())
        // A touch starts the wait again.
        mode.touched()
        clock.now += FROM_HERE_IDLE_MS - 1
        assertFalse(mode.idle())
        clock.now += 1
        assertTrue(mode.idle())
        assertEquals(0L, mode.untilIdle())
        assertEquals(2 * 60_000L, FROM_HERE_IDLE_MS)
    }

    @Test
    fun itNeverStopsWhileSaving() {
        val clock = Clock()
        val mode = HighlightFromHere(clock = clock)
        mode.begin(start)
        mode.end(later)
        clock.now += FROM_HERE_IDLE_MS * 2
        assertFalse(mode.idle())
    }

    @Test
    fun aOneWordSelectionStillOffersDefineButTheEndOfAHighlightOffersOnlyEndHere() {
        // Outside the mode, a long-pressed word gets the whole menu, Define included.
        assertEquals(SELECTION_MENU, selectionMenu(endingHighlight = false))
        assertTrue(selectionMenu(endingHighlight = false).any { it.first == SELECTION_DEFINE })
        assertTrue(selectionMenu(endingHighlight = false).none { it.first == SELECTION_END_HERE })
        // While the mode waits, the menu has one item: End highlight here. No Define, no Copy.
        assertEquals(
            listOf(SELECTION_END_HERE to dev.sadking.folio.R.string.reader_from_here_end_action),
            selectionMenu(endingHighlight = true),
        )
        // Its id is no other item's, so choosing it can never run Highlight, Define or the rest.
        assertTrue(SELECTION_MENU.none { it.first == SELECTION_END_HERE })
    }

    @Test
    fun theNewMenuItemComesLastSoDefineKeepsItsPlace() {
        val ids = SELECTION_MENU.map { it.first }
        assertEquals(SELECTION_FROM_HERE, ids.last())
        assertEquals(3, ids.indexOf(SELECTION_DEFINE))
        assertEquals(
            dev.sadking.folio.R.string.reader_highlight_from_here_action,
            SELECTION_MENU.last().second,
        )
    }
}
