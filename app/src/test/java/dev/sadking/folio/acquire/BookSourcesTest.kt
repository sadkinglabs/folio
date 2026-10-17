package dev.sadking.folio.acquire

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The built-in and owner-added book sources (M5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BookSourcesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clear() {
        context.getSharedPreferences(BookSourceStore.PREFERENCES, 0).edit().clear().commit()
    }

    @Test
    fun builtInsAreTheThreePublicDomainSites() {
        assertEquals(
            listOf("standard-ebooks", "gutenberg", "internet-archive"),
            BookSources.BUILT_IN.map { it.id },
        )
        assertEquals(listOf(false, false, true), BookSources.BUILT_IN.map { it.javaScript })
        BookSources.BUILT_IN.forEach {
            assertTrue(it.builtIn)
            assertNull(BookSources.problem(it.name, it.startUrl, it.searchUrl))
        }
        assertEquals("gutenberg.org", BookSources.BUILT_IN[1].host)
        assertFalse(BookSources.BUILT_IN.any { "annas" in it.startUrl })
    }

    @Test
    fun searchAddressEncodesTitleAndAuthors() {
        val archive = BookSources.BUILT_IN[2]
        assertEquals(
            "https://archive.org/search?query=Caf%C3%A9%20%26%20Co.%20A%2BB%20Writer&and%5B%5D=mediatype%3A%22texts%22",
            BookSources.searchUrl(archive, " Café  & Co. ", listOf("A+B", "Writer")),
        )
        assertEquals(
            "https://standardebooks.org/ebooks?query=Emma",
            BookSources.searchUrl(BookSources.BUILT_IN[0], "Emma", emptyList()),
        )
        assertNull(BookSources.searchUrl(BookSources.BUILT_IN[0], " ", emptyList()))
        val noSearch = BookSource("user-x", "X", "https://x.org/")
        assertNull(BookSources.searchUrl(noSearch, "Emma", emptyList()))
    }

    @Test
    fun problemsAreNamed() {
        assertEquals(SourceProblem.NAME_MISSING, BookSources.problem(" ", "https://a.org/", null))
        assertEquals(SourceProblem.NOT_HTTPS, BookSources.problem("A", "http://a.org/", null))
        assertEquals(SourceProblem.NOT_HTTPS, BookSources.problem("A", "a.org", null))
        assertEquals(SourceProblem.NOT_HTTPS, BookSources.problem("A", "https://", null))
        assertEquals(
            SourceProblem.NOT_HTTPS,
            BookSources.problem("A", "https://a.org/", "http://a.org/?q={query}"),
        )
        assertEquals(
            SourceProblem.QUERY_MISSING,
            BookSources.problem("A", "https://a.org/", "https://a.org/search?q="),
        )
        assertNull(BookSources.problem("A", "https://a.org/", "https://a.org/s/{query}"))
        assertNull(BookSources.problem("A", "https://a.org/", " "))
    }

    @Test
    fun builtInsCanBeHiddenButNotRemovedOrEdited() {
        val store = BookSourceStore(context)
        store.setHidden("gutenberg", true)
        assertEquals(listOf("standard-ebooks", "internet-archive"), store.visible().map { it.id })
        store.remove("gutenberg")
        assertEquals(3, store.sources.value.size)
        // Saving a built-in only changes whether it is shown.
        store.save(BookSources.BUILT_IN[0].copy(startUrl = "https://evil.example/", hidden = true))
        val saved = store.source("standard-ebooks")!!
        assertEquals("https://standardebooks.org/", saved.startUrl)
        assertTrue(saved.hidden)
        // A fresh store reads the same choices back.
        val again = BookSourceStore(context)
        assertEquals(listOf("internet-archive"), again.visible().map { it.id })
        again.setHidden("gutenberg", false)
        assertEquals(
            listOf("gutenberg", "internet-archive"),
            BookSourceStore(context).visible().map { it.id },
        )
    }

    @Test
    fun ownSitesAreAddedEditedAndRemovedInOrder() {
        val store = BookSourceStore(context)
        val a = BookSource(BookSources.newId(), "A", "https://a.org/", "https://a.org/?q={query}")
        val b = BookSource(BookSources.newId(), "B", "https://b.org/", javaScript = false)
        store.save(a)
        store.save(b)
        assertEquals(listOf("A", "B"), store.sources.value.drop(3).map { it.name })
        store.save(a.copy(name = "A2"))
        assertEquals(listOf("A2", "B"), store.sources.value.drop(3).map { it.name })
        store.remove(a.id)
        val read = BookSourceStore(context).sources.value
        assertEquals(4, read.size)
        assertEquals(b, read.last())
        assertFalse(read.last().builtIn)
        // An unusable site is refused.
        assertThrows(IllegalArgumentException::class.java) {
            store.save(BookSource(BookSources.newId(), "C", "http://c.org/"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.save(BookSource("not-user", "C", "https://c.org/"))
        }
    }

    @Test
    fun jsonRoundTrip() {
        val sources =
            BookSources.BUILT_IN.map {
                if (it.id == "internet-archive") it.copy(hidden = true) else it
            } +
                BookSource("user-1", "One", "https://one.org/", "https://one.org/s?q={query}") +
                BookSource(
                    "user-2",
                    "Two",
                    "https://two.org/",
                    null,
                    javaScript = false,
                    hidden = true,
                )
        assertEquals(sources, BookSources.fromJson(BookSources.toJson(sources)))
    }

    @Test
    fun unknownFieldsAreIgnoredAndBadEntriesSkipped() {
        val json =
            JSONObject()
                .put("version", 7)
                .put("future", "x")
                .put("hidden", JSONArray(listOf("gutenberg", "nonexistent")))
                .put(
                    "user",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("id", "user-1")
                                .put("name", "One")
                                .put("startUrl", "https://one.org/")
                                .put("colour", "blue")
                        )
                        .put(
                            JSONObject()
                                .put("id", "user-2")
                                .put("name", "Bad")
                                .put("startUrl", "http://x")
                        )
                        .put(
                            JSONObject()
                                .put("id", "gutenberg")
                                .put("name", "Fake")
                                .put("startUrl", "https://f.org/")
                        )
                        .put("not an object"),
                )
                .toString()
        val read = BookSources.fromJson(json)
        assertEquals(
            listOf("standard-ebooks", "gutenberg", "internet-archive", "user-1"),
            read.map { it.id },
        )
        assertTrue(read[1].hidden)
        assertEquals("Project Gutenberg", read[1].name)
        assertTrue(read[3].javaScript)
        assertNull(read[3].searchUrl)
        assertEquals(BookSources.BUILT_IN, BookSources.fromJson("{broken"))
        assertEquals(BookSources.BUILT_IN, BookSources.fromJson(null))
    }
}
