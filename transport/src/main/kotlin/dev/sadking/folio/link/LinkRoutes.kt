package dev.sadking.folio.link

import java.io.File
import java.net.URLDecoder
import java.time.Instant
import java.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A Folio Link reply: text (JSON or XML) or a file streamed from disk. */
sealed interface LinkResponse {
    val status: Int
    val headers: Map<String, String>

    data class Text(
        override val status: Int,
        val type: String,
        val body: String,
        override val headers: Map<String, String> = emptyMap(),
    ) : LinkResponse

    data class Download(
        val file: File,
        val type: String,
        val name: String,
        override val status: Int = 200,
        override val headers: Map<String, String> = emptyMap(),
    ) : LinkResponse
}

/**
 * Everything Folio Link answers. Progress sync goes to [kosync]. The OPDS catalogue (`/opds`) and
 * the Folio plugin's API (`/folio/v1`) serve the library; both accept HTTP Basic login with the
 * same username and password, and the plugin may also use kosync's headers.
 */
/** Catalogue text shown on the e-reader; the app supplies translated values. */
data class LinkLabels(
    val catalogue: String = "Folio",
    val recent: String = "Recently added",
    val lists: String = "Lists",
)

class LinkRoutes(
    private val kosync: Kosync,
    private val library: LinkLibrary?,
    private val account: () -> KosyncAccount?,
    private val labels: () -> LinkLabels = { LinkLabels() },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val failures = HashMap<String, ArrayDeque<Long>>()

    fun handle(request: KosyncRequest): LinkResponse {
        val path = request.path.substringBefore('?').trimEnd('/').ifEmpty { "/" }
        if (library == null || !(path.startsWith("/opds") || path.startsWith("/folio/")))
            return kosync.handle(request).let { LinkResponse.Text(it.status, JSON, it.body) }
        if (throttled(request.remote))
            return LinkResponse.Text(429, JSON, """{"code":2001,"message":"Unauthorized"}""")
        if (!authorized(request)) {
            failed(request.remote)
            return LinkResponse.Text(
                401,
                JSON,
                """{"code":2001,"message":"Unauthorized"}""",
                mapOf("WWW-Authenticate" to "Basic realm=\"Folio\", charset=\"UTF-8\""),
            )
        }
        synchronized(failures) { failures.remove(request.remote) }
        return when {
            path.startsWith("/opds") -> opds(request.method, path, library)
            path.startsWith("/folio/v1/") ->
                plugin(request, path.removePrefix("/folio/v1"), library)
            else -> notFound()
        }
    }

    /** The same limit as progress sync: ten failed logins a minute per address. */
    private fun throttled(remote: String): Boolean =
        synchronized(failures) {
            val time = now()
            if (failures.size > 256) failures.clear()
            val recent = failures[remote] ?: return false
            while (recent.isNotEmpty() && time - recent.first() > Kosync.FAILURE_WINDOW_MS) recent
                .removeFirst()
            recent.size >= Kosync.MAX_FAILURES
        }

    private fun failed(remote: String) =
        synchronized(failures) { failures.getOrPut(remote) { ArrayDeque() }.addLast(now()) }

    private fun authorized(request: KosyncRequest): Boolean {
        val expected = account() ?: return false
        if (request.header("x-auth-user") == expected.username)
            return request.header("x-auth-key") == expected.key
        val basic = request.header("Authorization")?.takeIf { it.startsWith("Basic ", true) }
        val decoded =
            basic
                ?.let { runCatching { String(Base64.getDecoder().decode(it.substring(6).trim())) } }
                ?.getOrNull() ?: return false
        val user = decoded.substringBefore(':')
        val password = decoded.substringAfter(':', "")
        return KosyncAccount.fromPassword(user, password) == expected
    }

    // ---- Folio plugin API ----

    private fun plugin(request: KosyncRequest, path: String, library: LinkLibrary): LinkResponse {
        val device = request.header("x-folio-device").orEmpty().take(128)
        return when {
            request.method == "GET" && path == "/hello" ->
                json(
                    JSONObject()
                        .put("name", "Folio")
                        .put("api", API_VERSION)
                        .put("capabilities", JSONArray(listOf("kosync", "opds", "queue")))
                )
            request.method == "GET" && path == "/queue" -> {
                if (device.isEmpty()) return invalid()
                json(
                    JSONObject()
                        .put(
                            "books",
                            JSONArray(
                                library.queue(device).map {
                                    JSONObject()
                                        .put("id", it.id)
                                        .put("title", it.title)
                                        .put("filename", it.filename)
                                        .put("size", it.file.length())
                                        .put("sha256", it.sha256)
                                        .put(
                                            "saved_path",
                                            library.savedPath(device, it.id) ?: JSONObject.NULL,
                                        )
                                }
                            ),
                        )
                )
            }
            request.method == "GET" && path.startsWith("/books/") ->
                download(library, decode(path.removePrefix("/books/")).removeSuffix(".epub"))
            request.method == "POST" && path == "/ack" -> {
                if (device.isEmpty()) return invalid()
                val body =
                    try {
                        JSONObject(request.body.orEmpty())
                    } catch (_: JSONException) {
                        return invalid()
                    }
                val id = body.optString("id")
                val sha256 = body.optString("sha256")
                val saved = body.optString("path")
                if (id.isEmpty() || sha256.length != 64 || saved.isEmpty() || saved.length > 1024)
                    return invalid()
                if (library.acknowledge(device, id, sha256, saved))
                    json(JSONObject().put("ok", true))
                else
                    LinkResponse.Text(
                        409,
                        JSON,
                        JSONObject()
                            .put("ok", false)
                            .put("message", "The saved file does not match this book")
                            .toString(),
                    )
            }
            else -> notFound()
        }
    }

    // ---- OPDS 1.2 catalogue ----

    private fun opds(method: String, path: String, library: LinkLibrary): LinkResponse {
        if (method != "GET") return notFound()
        return when {
            path == "/opds" -> navigation(library)
            path == "/opds/recent" -> acquisition("recent", labels().recent, library.recent(RECENT))
            path == "/opds/lists" -> lists(library)
            path.startsWith("/opds/lists/") -> {
                val id = decode(path.removePrefix("/opds/lists/"))
                val shelf = library.shelves().firstOrNull { it.id == id } ?: return notFound()
                acquisition("lists/${shelf.id}", shelf.name, library.shelf(shelf.id))
            }
            path.startsWith("/opds/books/") ->
                download(library, decode(path.removePrefix("/opds/books/")).removeSuffix(".epub"))
            path.startsWith("/opds/covers/") -> {
                val book =
                    library.book(decode(path.removePrefix("/opds/covers/"))) ?: return notFound()
                val cover = book.cover?.takeIf { it.isFile } ?: return notFound()
                LinkResponse.Download(cover, imageType(cover), cover.name)
            }
            else -> notFound()
        }
    }

    private fun navigation(library: LinkLibrary) =
        feed("root", labels().catalogue, NAVIGATION) {
            append(navigationEntry("recent", labels().recent, "/opds/recent"))
            if (library.shelves().isNotEmpty())
                append(navigationEntry("lists", labels().lists, "/opds/lists", NAVIGATION))
        }

    private fun lists(library: LinkLibrary) =
        feed("lists", labels().lists, NAVIGATION) {
            library.shelves().forEach {
                append(
                    navigationEntry(
                        "lists/${it.id}",
                        "${it.name} (${it.count})",
                        "/opds/lists/${encode(it.id)}",
                    )
                )
            }
        }

    private fun acquisition(id: String, title: String, books: List<LinkBook>) =
        feed(id, title, ACQUISITION) {
            books.forEach { book ->
                append("<entry>")
                append("<id>urn:folio:book:${xml(book.id)}</id>")
                append("<title>${xml(book.title)}</title>")
                book.authors.forEach { append("<author><name>${xml(it)}</name></author>") }
                append("<updated>${Instant.ofEpochMilli(book.addedAt)}</updated>")
                append(
                    "<link rel=\"http://opds-spec.org/acquisition\" type=\"application/epub+zip\" " +
                        "href=\"/opds/books/${encode(book.id)}.epub\" length=\"${book.file.length()}\"/>"
                )
                if (book.cover?.isFile == true) {
                    val type = imageType(book.cover)
                    val href = "/opds/covers/${encode(book.id)}"
                    append(
                        "<link rel=\"http://opds-spec.org/image\" type=\"$type\" href=\"$href\"/>"
                    )
                    append(
                        "<link rel=\"http://opds-spec.org/image/thumbnail\" type=\"$type\" href=\"$href\"/>"
                    )
                }
                append("</entry>")
            }
        }

    private fun feed(id: String, title: String, kind: String, entries: StringBuilder.() -> Unit) =
        LinkResponse.Text(
            200,
            kind,
            buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                append(
                    "<feed xmlns=\"http://www.w3.org/2005/Atom\" xmlns:opds=\"http://opds-spec.org/2010/catalog\">"
                )
                append("<id>urn:folio:${xml(id)}</id>")
                append("<title>${xml(title)}</title>")
                append("<updated>${Instant.now()}</updated>")
                append("<link rel=\"start\" type=\"$NAVIGATION\" href=\"/opds\"/>")
                append(
                    "<link rel=\"self\" type=\"$kind\" href=\"/opds/${xml(id).removePrefix("root")}\"/>"
                )
                entries()
                append("</feed>")
            },
        )

    private fun navigationEntry(
        id: String,
        title: String,
        href: String,
        type: String = ACQUISITION,
    ) =
        "<entry><id>urn:folio:${xml(id)}</id><title>${xml(title)}</title>" +
            "<updated>${Instant.now()}</updated>" +
            "<link rel=\"subsection\" type=\"$type\" href=\"${xml(href)}\"/></entry>"

    // ---- Shared ----

    private fun download(library: LinkLibrary, id: String): LinkResponse {
        val book = library.book(id) ?: return notFound()
        if (!book.file.isFile) return notFound()
        return LinkResponse.Download(book.file, "application/epub+zip", book.filename)
    }

    private fun json(body: JSONObject) = LinkResponse.Text(200, JSON, body.toString())

    private fun invalid() =
        LinkResponse.Text(403, JSON, """{"code":2003,"message":"Invalid request"}""")

    private fun notFound() = LinkResponse.Text(404, JSON, """{"code":2000,"message":"Not found"}""")

    private fun decode(value: String) =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault("")

    private fun encode(value: String) =
        java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    private fun imageType(file: File) =
        if (file.name.endsWith(".png", true)) "image/png" else "image/jpeg"

    companion object {
        const val API_VERSION = 1
        const val RECENT = 50
        const val JSON = "application/json"
        const val NAVIGATION = "application/atom+xml;profile=opds-catalog;kind=navigation"
        const val ACQUISITION = "application/atom+xml;profile=opds-catalog;kind=acquisition"

        fun xml(value: String) =
            value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }
    }
}
