package dev.sadking.folio.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderProgressTest {
    @Test
    fun pageLabelShowsPagesOnlyOncePositionsAreKnown() {
        assertEquals(PageLabel(null, 0, 4), pageLabel(0.042, 12, 0))
        assertEquals(PageLabel(12, 340, 4), pageLabel(0.042, 12, 340))
        assertEquals(PageLabel(null, 340, 4), pageLabel(0.042, null, 340))
        assertEquals(PageLabel(null, 0, 0), pageLabel(null, null, 0))
    }

    @Test
    fun progressFractionIsBoundedAndStartsAtZero() {
        assertEquals(0f, progressFraction(null), 0f)
        assertEquals(1f, progressFraction(1.7), 0f)
        assertEquals(0.25f, progressFraction(0.25), 0f)
    }

    @Test
    fun scrubbingNamesThePageTheSliderWouldOpen() {
        assertEquals(PageLabel(1, 340, 0), scrubLabel(0f, 340))
        assertEquals(PageLabel(340, 340, 100), scrubLabel(1f, 340))
        assertEquals(PageLabel(171, 340, 50), scrubLabel(0.5f, 340))
        assertEquals(PageLabel(null, 0, 50), scrubLabel(0.5f, 0))
    }

    @Test
    fun positionIndexNeverLeavesTheList() {
        assertEquals(0, positionIndexAt(-1f, 10))
        assertEquals(9, positionIndexAt(1f, 10))
        assertEquals(9, positionIndexAt(0.99f, 10))
        assertEquals(0, positionIndexAt(0.5f, 0))
    }

    @Test
    fun chapterTicksMarkWhereEachLaterChapterStarts() {
        assertEquals(listOf(0.25f, 0.75f), chapterTicks(listOf(2, 4, 2)))
        // An empty chapter makes no tick and does not move the next one.
        assertEquals(listOf(0.5f), chapterTicks(listOf(2, 0, 2)))
        assertEquals(emptyList<Float>(), chapterTicks(listOf(5)))
        assertEquals(emptyList<Float>(), chapterTicks(emptyList()))
    }

    @Test
    fun thinningKeepsTheFirstOfAnyCluster() {
        assertEquals(
            listOf(0.1f, 0.2f, 0.5f),
            thinTicks(listOf(0.1f, 0.105f, 0.2f, 0.209f, 0.5f), 0.01f),
        )
        assertEquals(emptyList<Float>(), thinTicks(emptyList(), 0.01f))
    }

    @Test
    fun chapterTitleIsTheLastOneStartingBeforeTheThumb() {
        val starts = listOf(0f, 0.3f, 0.6f)
        val titles = listOf("One", null, "Three")
        assertEquals("One", chapterTitleAt(0.1f, starts, titles))
        assertNull(chapterTitleAt(0.4f, starts, titles))
        assertEquals("Three", chapterTitleAt(0.6f, starts, titles))
        assertEquals("Three", chapterTitleAt(2f, starts, titles))
        assertNull(chapterTitleAt(0.5f, emptyList(), emptyList()))
    }
}
