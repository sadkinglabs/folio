package dev.sadking.folio.reader

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.LocatorCollection
import org.readium.r2.shared.publication.services.search.SearchError
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.SearchTry
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How Readium's search pages become the list in the search sheet, with a stand-in iterator. The
 * search over a real EPUB runs on the emulator (ReaderSearchTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderSearchResultsTest {
    private fun locator(chapter: String?, before: String, match: String, after: String) =
        Locator(
            Url("chapter.xhtml")!!,
            MediaType.XHTML,
            title = chapter,
            text = Locator.Text(before = before, highlight = match, after = after),
        )

    private class Pages(private val pages: List<SearchTry<LocatorCollection?>>) : SearchIterator {
        var closed = false
        var asked = 0

        override suspend fun next(): SearchTry<LocatorCollection?> =
            pages.getOrElse(asked++) { Try.success(null) }

        override fun close() {
            closed = true
        }
    }

    private fun page(vararg locators: Locator): SearchTry<LocatorCollection?> =
        Try.success(LocatorCollection(locators = locators.toList()))

    @Test
    fun aHitKeepsItsChapterAndTextOnOneLine() {
        val hit =
            searchHit(locator(" Chapter two ", "the east\n  end of the ", "orchard", ",\twhere"))!!
        assertEquals("Chapter two", hit.chapter)
        assertEquals("the east end of the ", hit.before)
        assertEquals("orchard", hit.match)
        assertEquals(", where", hit.after)
    }

    @Test
    fun longContextIsShortenedAndEmptyMatchesDropped() {
        val hit = searchHit(locator(null, "a".repeat(200), "orchard", "b".repeat(200)))!!
        assertNull(hit.chapter)
        assertTrue(hit.before.startsWith("…"))
        assertEquals(81, hit.before.length)
        assertTrue(hit.after.endsWith("…"))
        assertNull(searchHit(locator("x", "before", "  ", "after")))
    }

    @Test
    fun pagesAccumulateUntilTheIteratorEnds() = runBlocking {
        val iterator =
            Pages(
                listOf(
                    page(locator("One", "", "dog", ""), locator("One", "", "Dog", "")),
                    page(locator("Two", "", "dög", "")),
                )
            )
        val emitted = searchPages(iterator, limit = 500).toList()
        assertEquals(listOf(2, 3), emitted.map { it.size })
        assertEquals(listOf("One", "One", "Two"), emitted.last().map { it.chapter })
        // Asked once more to learn the search is over.
        assertEquals(3, iterator.asked)
    }

    @Test
    fun theListStopsAtTheLimit() = runBlocking {
        val many = (1..4).map { locator("C", "", "word$it", "") }.toTypedArray()
        val iterator = Pages(listOf(page(*many), page(*many), page(*many)))
        val emitted = searchPages(iterator, limit = 6).toList()
        assertEquals(6, emitted.last().size)
        // Two pages were enough; the third was never read.
        assertEquals(2, iterator.asked)
        assertTrue(
            SearchResults("q", emitted.last().let { List(SEARCH_LIMIT) { _ -> it[0] } }).capped
        )
    }

    @Test
    fun aReadiumErrorFailsTheSearch() {
        val iterator =
            Pages(
                listOf(
                    page(locator("One", "", "dog", "")),
                    Try.failure(SearchError.Engine(DebugError("broken"))),
                )
            )
        assertThrows(ReaderSearchFailed::class.java) {
            runBlocking { searchPages(iterator, limit = 500).toList() }
        }
    }
}
