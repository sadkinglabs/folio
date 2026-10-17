package dev.sadking.folio.acquire

import android.content.Context
import androidx.core.content.edit
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A website to get books from: one of the built-ins, or one the owner added. */
data class BookSource(
    /** "standard-ebooks", "gutenberg", "internet-archive", or "user-<uuid>". */
    val id: String,
    val name: String,
    /** https only. */
    val startUrl: String,
    /** https only; contains "{query}". Null: the source has no search, "Get this book" skips it. */
    val searchUrl: String? = null,
    /**
     * Scripts on, for sites that need them. Built-ins: Standard Ebooks false, Gutenberg false,
     * Archive true. User sites default true.
     */
    val javaScript: Boolean = true,
    val builtIn: Boolean = false,
    val hidden: Boolean = false,
) {
    /** The site's host, for a row's second line. */
    val host: String
        get() = runCatching { URI(startUrl).host?.removePrefix("www.") }.getOrNull().orEmpty()
}

/** What is wrong with a site the owner typed in. */
enum class SourceProblem {
    NAME_MISSING,
    NOT_HTTPS,
    QUERY_MISSING,
}

object BookSources {
    const val QUERY = "{query}"

    /** Public-domain sites the owner chose (2026-10-17); their addresses were checked then. */
    val BUILT_IN: List<BookSource> =
        listOf(
            BookSource(
                id = "standard-ebooks",
                name = "Standard Ebooks",
                startUrl = "https://standardebooks.org/",
                searchUrl = "https://standardebooks.org/ebooks?query={query}",
                javaScript = false,
                builtIn = true,
            ),
            BookSource(
                id = "gutenberg",
                name = "Project Gutenberg",
                startUrl = "https://www.gutenberg.org/",
                searchUrl = "https://www.gutenberg.org/ebooks/search/?query={query}",
                javaScript = false,
                builtIn = true,
            ),
            BookSource(
                id = "internet-archive",
                name = "Internet Archive",
                startUrl = "https://archive.org/details/texts",
                searchUrl =
                    "https://archive.org/search?query={query}&and%5B%5D=mediatype%3A%22texts%22",
                javaScript = true,
                builtIn = true,
            ),
        )

    /** The words searched for a book: its title and authors, at most 200 characters. */
    fun query(title: String, authors: List<String>): String =
        (listOf(title) + authors)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .take(200)

    /**
     * [source]'s search address for a title and authors (URL-encoded "title author"), or null
     * without a search address or without words to search for.
     */
    fun searchUrl(source: BookSource, title: String, authors: List<String>): String? =
        searchFor(source, query(title, authors))

    /** [source]'s search address for [query] as typed, or null. */
    fun searchFor(source: BookSource, query: String): String? {
        val template = source.searchUrl ?: return null
        val words = query.trim().takeIf { it.isNotEmpty() } ?: return null
        // %20 rather than +: it means a space in a path as well as in a query string.
        return template.replace(QUERY, URLEncoder.encode(words, "UTF-8").replace("+", "%20"))
    }

    /** Null when acceptable; otherwise what is wrong, for a plain message. */
    fun problem(name: String, startUrl: String, searchUrl: String?): SourceProblem? {
        if (name.isBlank()) return SourceProblem.NAME_MISSING
        if (!isHttps(startUrl)) return SourceProblem.NOT_HTTPS
        val search = searchUrl?.trim().orEmpty()
        if (search.isEmpty()) return null
        if (!isHttps(search.replace(QUERY, "q"))) return SourceProblem.NOT_HTTPS
        if (QUERY !in search) return SourceProblem.QUERY_MISSING
        return null
    }

    /** An absolute https address with a host. */
    internal fun isHttps(url: String): Boolean =
        runCatching {
                val uri = URI(url.trim())
                uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
            }
            .getOrDefault(false)

    /**
     * The stored form: which built-ins are hidden, and the owner's sites in the order added.
     * Built-ins themselves are not stored, so an update can correct their addresses.
     */
    internal fun toJson(sources: List<BookSource>): String =
        JSONObject()
            .put("version", 1)
            .put("hidden", JSONArray(sources.filter { it.builtIn && it.hidden }.map { it.id }))
            .put(
                "user",
                JSONArray(
                    sources
                        .filter { !it.builtIn }
                        .map { s ->
                            JSONObject()
                                .put("id", s.id)
                                .put("name", s.name)
                                .put("startUrl", s.startUrl)
                                .put("searchUrl", s.searchUrl ?: JSONObject.NULL)
                                .put("javaScript", s.javaScript)
                                .put("hidden", s.hidden)
                        }
                ),
            )
            .toString()

    /**
     * The sources from [json]: built-ins first, in [BUILT_IN] order, then the owner's. Unknown
     * fields are ignored; a damaged entry is skipped, never the whole list.
     */
    internal fun fromJson(json: String?): List<BookSource> {
        val root = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val hidden =
            root.optJSONArray("hidden")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: emptyList()
        val user =
            root.optJSONArray("user")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id")
                    val name = o.optString("name")
                    val start = o.optString("startUrl")
                    val search =
                        if (o.isNull("searchUrl")) null
                        else o.optString("searchUrl").takeIf { it.isNotBlank() }
                    if (!id.startsWith(USER_PREFIX) || problem(name, start, search) != null)
                        return@mapNotNull null
                    BookSource(
                        id,
                        name,
                        start,
                        search,
                        javaScript = o.optBoolean("javaScript", true),
                        hidden = o.optBoolean("hidden", false),
                    )
                }
            } ?: emptyList()
        return BUILT_IN.map { it.copy(hidden = it.id in hidden) } +
            user.distinctBy { it.id }.filter { u -> BUILT_IN.none { it.id == u.id } }
    }

    internal const val USER_PREFIX = "user-"

    /** A fresh id for a site the owner adds. */
    fun newId(): String = USER_PREFIX + UUID.randomUUID()
}

/**
 * The book sources, kept in SharedPreferences `book_sources` (settings, not library data). Not in
 * backups yet.
 */
class BookSourceStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(BookSources.fromJson(prefs.getString(KEY, null)))

    /** Built-ins first, in [BookSources.BUILT_IN] order, then the owner's in the order added. */
    val sources: StateFlow<List<BookSource>> = state

    fun visible(): List<BookSource> = state.value.filter { !it.hidden }

    fun source(id: String): BookSource? = state.value.firstOrNull { it.id == id }

    /** Adds or edits a site. A built-in keeps its addresses: only `hidden` can change. */
    fun save(source: BookSource) {
        val current = state.value
        val existing = current.firstOrNull { it.id == source.id }
        if (existing?.builtIn == true || BookSources.BUILT_IN.any { it.id == source.id }) {
            setHidden(source.id, source.hidden)
            return
        }
        require(source.id.startsWith(BookSources.USER_PREFIX)) { "Not a site id" }
        require(BookSources.problem(source.name, source.startUrl, source.searchUrl) == null) {
            "Not a usable site"
        }
        val clean =
            source.copy(
                name = source.name.trim(),
                startUrl = source.startUrl.trim(),
                searchUrl = source.searchUrl?.trim()?.takeIf { it.isNotEmpty() },
                builtIn = false,
            )
        write(
            if (existing == null) current + clean
            else current.map { if (it.id == clean.id) clean else it }
        )
    }

    /** Removes one of the owner's sites; built-ins can only be hidden. */
    fun remove(id: String) {
        val current = state.value
        if (current.none { it.id == id && !it.builtIn }) return
        write(current.filterNot { it.id == id })
    }

    fun setHidden(id: String, hidden: Boolean) {
        write(state.value.map { if (it.id == id) it.copy(hidden = hidden) else it })
    }

    private fun write(sources: List<BookSource>) {
        state.value = sources
        prefs.edit { putString(KEY, BookSources.toJson(sources)) }
    }

    companion object {
        const val PREFERENCES = "book_sources"
        private const val KEY = "sources"
    }
}
