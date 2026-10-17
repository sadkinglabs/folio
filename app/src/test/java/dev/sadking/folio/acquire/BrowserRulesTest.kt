package dev.sadking.folio.acquire

import org.junit.Assert.*
import org.junit.Test

/** Which links the browser loads, and where it opens (M5). Plain JVM. */
class BrowserRulesTest {
    @Test
    fun httpsLoadsHttpIsUpgradedEverythingElseIsBlocked() {
        assertEquals(Navigation.Load, BrowserPolicy.decide("https://www.gutenberg.org/"))
        assertEquals(
            Navigation.Upgrade("https://www.gutenberg.org/ebooks/1"),
            BrowserPolicy.decide("http://www.gutenberg.org/ebooks/1"),
        )
        assertEquals(Navigation.Upgrade("https://x.org/"), BrowserPolicy.decide("HTTP://x.org/"))
        listOf(
                "intent://scan/#Intent;scheme=zxing;end",
                "market://details?id=x",
                "mailto:a@b.c",
                "file:///sdcard/x.epub",
                "content://media/external/1",
                "javascript:alert(1)",
                "data:text/html,hi",
                "tel:123",
                "https://",
                "",
            )
            .forEach { assertEquals(it, Navigation.Block, BrowserPolicy.decide(it)) }
        assertEquals(Navigation.Load, BrowserPolicy.decide("about:blank"))
        assertNull(BrowserPolicy.https("about:blank"))
        assertEquals("https://a.org/b", BrowserPolicy.https("http://a.org/b"))
    }

    @Test
    fun findWithATitleOpensTheFirstSourceThatSearches() {
        val visible = BookSources.BUILT_IN.drop(1)
        val start = BrowserStart.from("find", "Emma", listOf("Jane Austen"), "wish-1", visible)
        assertEquals(BrowserPage.WEB, start.page)
        assertEquals("gutenberg", start.sourceId)
        assertEquals(
            "https://www.gutenberg.org/ebooks/search/?query=Emma%20Jane%20Austen",
            start.url,
        )
        assertEquals("wish-1", start.wishId)
        assertEquals("Emma Jane Austen", start.query)
        assertEquals(BrowserPage.START, start.root)
    }

    @Test
    fun findSkipsSourcesWithoutSearch() {
        val noSearch = BookSource("user-a", "A", "https://a.org/", searchUrl = null)
        val start =
            BrowserStart.from(
                "find",
                "Emma",
                emptyList(),
                null,
                listOf(noSearch) + BookSources.BUILT_IN,
            )
        assertEquals("standard-ebooks", start.sourceId)
        val none = BrowserStart.from("find", "Emma", emptyList(), null, listOf(noSearch))
        assertEquals(BrowserPage.START, none.page)
        assertEquals("Emma", none.query)
    }

    @Test
    fun findWithoutATitleShowsTheStartPage() {
        val start = BrowserStart.from("find", null, emptyList(), null, BookSources.BUILT_IN)
        assertEquals(BrowserStart(BrowserPage.START), start)
        assertEquals(
            BrowserPage.START,
            BrowserStart.from(null, " ", emptyList(), "", BookSources.BUILT_IN).page,
        )
    }

    @Test
    fun sourcesModeOpensTheSourcesAndIgnoresTheRest() {
        val start = BrowserStart.from("sources", "Emma", emptyList(), "w", BookSources.BUILT_IN)
        assertEquals(BrowserStart(BrowserPage.SOURCES), start)
        assertEquals(BrowserPage.SOURCES, start.root)
    }
}
