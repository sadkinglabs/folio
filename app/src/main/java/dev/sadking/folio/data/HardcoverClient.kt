package dev.sadking.folio.data

import dev.sadking.folio.core.Epub
import dev.sadking.folio.core.Metadata
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import org.json.*

data class HardcoverMatch(
    val id: Int,
    val title: String,
    val authors: List<String>,
    val slug: String,
)

data class HardcoverAccount(val id: Int, val username: String, val privacy: Int)

/** Personal-account GraphQL client. No token-bearing redirects and no response text in errors. */
/** A Hardcover HTTP failure. Still an [IllegalArgumentException], as callers already expect. */
class HardcoverHttpException(val code: Int, message: String) : IllegalArgumentException(message) {
    /** Worth retrying later: rate limits and server errors. */
    val transient: Boolean
        get() = code == 429 || code >= 500

    val unauthorized: Boolean
        get() = code == 401 || code == 403
}

class HardcoverClient(private val fixture: ((String, JSONObject) -> JSONObject)? = null) {
    private var lastRequest = 0L

    @Synchronized
    private fun query(
        token: String,
        document: String,
        variables: JSONObject = JSONObject(),
    ): JSONObject {
        require(token.isNotBlank() && token.length <= 4096 && token.none { it.isWhitespace() }) {
            "Enter the token without the Bearer prefix"
        }
        val body = JSONObject().put("query", document).put("variables", variables)
        val result =
            fixture?.invoke(token, body)
                ?: run {
                    val wait = 1100 - (System.currentTimeMillis() - lastRequest)
                    if (wait > 0) Thread.sleep(wait)
                    lastRequest = System.currentTimeMillis()
                    val connection =
                        URI("https://api.hardcover.app/v1/graphql").toURL().openConnection()
                            as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 15000
                    connection.readTimeout = 20000
                    connection.instanceFollowRedirects = false
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    try {
                        connection.outputStream.use { it.write(body.toString().toByteArray()) }
                        val code = connection.responseCode
                        if (code != 200)
                            throw HardcoverHttpException(
                                code,
                                when (code) {
                                    401,
                                    403 -> "Hardcover token was rejected; reconnect your account"
                                    429 -> "Hardcover is busy; retry later"
                                    else -> "Hardcover request failed; check internet and retry"
                                },
                            )
                        val bytes = java.io.ByteArrayOutputStream()
                        connection.inputStream.use { Epub.copyBounded(it, bytes, 2L * 1024 * 1024) }
                        JSONObject(bytes.toString("UTF-8"))
                    } finally {
                        connection.disconnect()
                    }
                }
        require(result.optJSONArray("errors")?.length()?.let { it > 0 } != true) {
            "Hardcover could not complete this operation; no success was recorded"
        }
        return result.optJSONObject("data") ?: error("Invalid Hardcover response")
    }

    fun account(token: String): HardcoverAccount {
        val me =
            query(token, "{ me { id username account_privacy_setting_id } }").getJSONArray("me")
        require(me.length() == 1) { "Hardcover account could not be identified" }
        val o = me.getJSONObject(0)
        return HardcoverAccount(
            o.getInt("id"),
            o.getString("username"),
            o.getInt("account_privacy_setting_id"),
        )
    }

    fun search(token: String, text: String): List<HardcoverMatch> {
        require(text.isNotBlank() && text.length <= 300)
        val found =
            query(
                    token,
                    "query(\$text:String!) { search(query:\$text,per_page:12,page:1,query_type:\"Book\") { ids error } }",
                    JSONObject().put("text", text),
                )
                .getJSONObject("search")
        require(found.optString("error").let { it.isBlank() || it == "null" }) {
            "Hardcover search failed"
        }
        val ids = found.optJSONArray("ids") ?: return emptyList()
        require(ids.length() <= 12)
        val positive =
            (0 until ids.length()).map { ids.getInt(it).also { n -> require(n > 0) } }.distinct()
        if (positive.isEmpty()) return emptyList()
        val books =
            query(
                    token,
                    "query(\$ids:[Int!]!) { books(where:{id:{_in:\$ids}}) { id title slug cached_contributors } }",
                    JSONObject().put("ids", JSONArray(positive)),
                )
                .getJSONArray("books")
        return books
            .objects()
            .map { o ->
                val contributors = o.optJSONArray("cached_contributors") ?: JSONArray()
                HardcoverMatch(
                    o.getInt("id"),
                    o.getString("title"),
                    contributors
                        .objects()
                        .mapNotNull { c ->
                            c.optJSONObject("author")?.optString("name")?.takeIf { it.isNotBlank() }
                                ?: c.optString("name").takeIf { it.isNotBlank() }
                        }
                        .distinct(),
                    o.optString("slug"),
                )
            }
            .also {
                require(it.size <= 12 && it.all { b -> b.id in positive && b.title.isNotBlank() })
            }
            .sortedBy { positive.indexOf(it.id) }
    }

    /**
     * Existing entries retain their status, progress and privacy, including on retry after a lost
     * response.
     */
    fun wantToRead(token: String, bookId: Int): String {
        require(bookId > 0)
        val me = account(token)
        val existing =
            query(
                    token,
                    "query(\$book:Int!,\$user:Int!) { user_books(where:{book_id:{_eq:\$book},user_id:{_eq:\$user}}) { id status_id } }",
                    JSONObject().put("book", bookId).put("user", me.id),
                )
                .getJSONArray("user_books")
        if (existing.length() > 0) return "Already in Hardcover; existing reading status retained"
        val value =
            query(
                    token,
                    "mutation(\$object:UserBookCreateInput!) { insert_user_book(object:\$object) { id error } }",
                    JSONObject()
                        .put(
                            "object",
                            JSONObject()
                                .put("book_id", bookId)
                                .put("status_id", 1)
                                .put("privacy_setting_id", me.privacy),
                        ),
                )
                .getJSONObject("insert_user_book")
        require(
            value.optString("error").let { it.isBlank() || it == "null" } && value.optInt("id") > 0
        ) {
            "Hardcover did not confirm the addition; retry to reconcile"
        }
        return "Added to Hardcover Want to Read using your account privacy setting"
    }

    fun bookMetadata(token: String, id: Int): HardcoverBookData {
        require(id > 0)
        val book =
            query(
                    token,
                    "query(\$id:Int!) { books_by_pk(id:\$id) { id title description cached_contributors cached_image cached_tags(path:\"$.Genre\") featured_book_series { position series { name } } default_ebook_edition { isbn_10 isbn_13 language { code2 } publisher { name } } default_physical_edition { isbn_10 isbn_13 language { code2 } publisher { name } } } }",
                    JSONObject().put("id", id),
                )
                .optJSONObject("books_by_pk")
                ?: error("This Hardcover book is unavailable; search again")
        require(book.getInt("id") == id) { "Hardcover returned another book; search again" }
        val title = book.getString("title").also { require(it.isNotBlank() && it.length <= 2000) }
        val authors =
            (book.optJSONArray("cached_contributors") ?: JSONArray())
                .objects()
                .mapNotNull { c ->
                    c.optJSONObject("author")?.optString("name")?.takeIf { it.isNotBlank() }
                        ?: c.optString("name").takeIf { it.isNotBlank() }
                }
                .distinct()
        require(authors.size <= 100 && authors.all { it.length <= 1000 })
        fun text(o: JSONObject?, key: String): String =
            o?.optString(key)?.takeUnless { it == "null" }.orEmpty()
        val edition =
            book.optJSONObject("default_ebook_edition")
                ?: book.optJSONObject("default_physical_edition")
        val featured = book.optJSONObject("featured_book_series")
        val genres = book.optJSONArray("cached_tags") ?: JSONArray()
        require(genres.length() <= 100)
        val tags =
            (0 until genres.length())
                .mapNotNull { n ->
                    when (val value = genres.opt(n)) {
                        is String -> value
                        is JSONObject -> text(value, "tag").ifBlank { text(value, "name") }
                        else -> null
                    }
                }
                .filter { it.isNotBlank() && it.length <= 200 }
                .distinct()
        val metadata =
            Metadata(
                title,
                authors,
                text(book, "description").take(128000),
                text(edition?.optJSONObject("publisher"), "name"),
                text(edition?.optJSONObject("language"), "code2"),
                listOf("isbn_13", "isbn_10")
                    .map { text(edition, it) }
                    .filter { it.isNotBlank() }
                    .distinct(),
                text(featured?.optJSONObject("series"), "name"),
                text(featured, "position"),
                tags,
            )
        val cover = text(book.optJSONObject("cached_image"), "url").takeIf { it.isNotBlank() }
        return HardcoverBookData(id, metadata, cover)
    }

    fun libraryPage(token: String, accountId: Int, after: Int): List<HardcoverLibraryBook> {
        require(accountId > 0 && after >= 0)
        val rows =
            query(
                    token,
                    "query(\$user:Int!,\$after:Int!) { user_books(where:{user_id:{_eq:\$user},id:{_gt:\$after}},order_by:{id:asc},limit:50) { id status_id edition_id book { id title slug cached_contributors } user_book_reads(order_by:{id:desc},limit:1) { progress_pages edition_id } } }",
                    JSONObject().put("user", accountId).put("after", after),
                )
                .getJSONArray("user_books")
        require(rows.length() <= 50)
        val result =
            rows.objects().map { row ->
                val b = row.getJSONObject("book")
                val authors =
                    (b.optJSONArray("cached_contributors") ?: JSONArray())
                        .objects()
                        .mapNotNull { c ->
                            c.optJSONObject("author")?.optString("name")?.takeIf { it.isNotBlank() }
                                ?: c.optString("name").takeIf { it.isNotBlank() }
                        }
                        .distinct()
                val read = row.getJSONArray("user_book_reads").optJSONObject(0)
                HardcoverLibraryBook(
                    row.getInt("id"),
                    HardcoverMatch(
                        b.getInt("id"),
                        b.getString("title"),
                        authors,
                        b.optString("slug"),
                    ),
                    row.getInt("status_id"),
                    if (read == null || read.isNull("progress_pages")) null
                    else read.getInt("progress_pages"),
                    if (read != null && !read.isNull("edition_id")) read.getInt("edition_id")
                    else if (row.isNull("edition_id")) null else row.getInt("edition_id"),
                )
            }
        require(
            result.all {
                it.userBookId > after &&
                    it.book.id > 0 &&
                    it.book.title.isNotBlank() &&
                    it.book.title.length <= 2000 &&
                    it.book.authors.size <= 100 &&
                    it.book.authors.all { a -> a.length <= 1000 } &&
                    (it.pages == null || it.pages >= 0)
            }
        ) {
            "Invalid Hardcover library response"
        }
        require(result.zipWithNext().all { (a, b) -> a.userBookId < b.userBookId }) {
            "Hardcover pagination changed; refresh before importing"
        }
        return result
    }

    /** The page count of one edition, or null when Hardcover has none. */
    fun editionPages(token: String, editionId: Int): Int? =
        query(
                token,
                "query(\$id:Int!) { editions_by_pk(id:\$id) { id book_id pages } }",
                JSONObject().put("id", editionId),
            )
            .optJSONObject("editions_by_pk")
            ?.optInt("pages")
            ?.takeIf { it > 0 }

    fun editions(token: String, bookId: Int): List<HardcoverEdition> {
        require(bookId > 0)
        val editions =
            query(
                token,
                "query(\$id:Int!) { editions(where:{book_id:{_eq:\$id}},limit:50,order_by:{users_count:desc}) { id title pages edition_format release_date language { code2 } publisher { name } } }",
                JSONObject().put("id", bookId),
            )
        return editions.getJSONArray("editions").objects().map { o ->
            HardcoverEdition(
                o.getInt("id"),
                o.optString("title"),
                o.optInt("pages"),
                listOf(
                        o.optString("edition_format"),
                        o.optJSONObject("language")?.optString("code2").orEmpty(),
                        o.optJSONObject("publisher")?.optString("name").orEmpty(),
                        o.optString("release_date").takeIf { it != "null" }.orEmpty(),
                    )
                    .filter { it.isNotBlank() && it != "null" }
                    .joinToString(" · "),
            )
        }
    }

    /** [userId] skips the account lookup when the caller already knows it. */
    fun reading(token: String, bookId: Int, userId: Int? = null): HardcoverReading {
        require(bookId > 0)
        val me = userId ?: account(token).id
        val rows =
            query(
                    token,
                    "query(\$book:Int!,\$user:Int!) { user_books(where:{book_id:{_eq:\$book},user_id:{_eq:\$user}}) { id status_id edition_id user_book_reads(where:{finished_at:{_is_null:true}},order_by:{id:desc},limit:2) { id progress_pages edition_id started_at } } }",
                    JSONObject().put("book", bookId).put("user", me),
                )
                .getJSONArray("user_books")
        require(rows.length() <= 1) {
            "Multiple Hardcover library entries; resolve them before updating"
        }
        if (rows.length() == 0) return HardcoverReading(null, null, null, null, null)
        val row = rows.getJSONObject(0)
        val reads = row.getJSONArray("user_book_reads")
        require(reads.length() <= 1) {
            "Multiple unfinished Hardcover reads; resolve them before updating"
        }
        val read = reads.optJSONObject(0)
        fun number(o: JSONObject?, name: String): Int? =
            if (o == null || o.isNull(name)) null else o.getInt(name)
        return HardcoverReading(
            row.getInt("id"),
            row.getInt("status_id"),
            number(read, "id"),
            number(read, "progress_pages"),
            number(read, "edition_id") ?: number(row, "edition_id"),
            number(row, "edition_id"),
            read?.takeUnless { it.isNull("started_at") }?.optString("started_at")?.take(10),
        )
    }

    private fun checkedWrite(data: JSONObject, key: String): Int {
        val value = data.getJSONObject(key)
        require(value.optString("error").let { it.isBlank() || it == "null" }) {
            "Hardcover did not confirm the update; reload and retry"
        }
        return value.optInt("id").takeIf { it > 0 }
            ?: error("Hardcover did not acknowledge the update; reload before retrying")
    }

    /**
     * Ends the read in progress on [finishedOn]: the read moves to its edition's last page and gets
     * the finished date, and the book is marked Read. Earlier reads, rating and review are kept.
     * Fails before writing when Hardcover no longer matches [expected].
     */
    fun finish(
        token: String,
        bookId: Int,
        finishedOn: LocalDate,
        expected: HardcoverReading,
    ): String {
        require(!finishedOn.isAfter(LocalDate.now())) {
            "Choose a finish date that is not in the future"
        }
        val current = reading(token, bookId)
        require(
            current.userBookId == expected.userBookId &&
                current.readId == expected.readId &&
                current.pages == expected.pages &&
                current.editionId == expected.editionId
        ) {
            "Hardcover progress changed; reload and review before updating"
        }
        val readId = current.readId ?: error("There is no read in progress on Hardcover to finish")
        val editionId = current.editionId ?: error("Choose an edition first")
        val started = current.startedOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        require(started == null || !finishedOn.isBefore(started)) {
            "Choose a finish date on or after the start date"
        }
        val pages = editionPages(token, editionId) ?: error("This edition has no page count")
        checkedWrite(
            query(
                token,
                "mutation(\$id:Int!,\$value:DatesReadInput!) { update_user_book_read(id:\$id,object:\$value) { id error } }",
                JSONObject()
                    .put("id", readId)
                    .put(
                        "value",
                        JSONObject()
                            .put("edition_id", editionId)
                            .put("progress_pages", pages)
                            .put("finished_at", finishedOn.toString()),
                    ),
            ),
            "update_user_book_read",
        )
        checkedWrite(
            query(
                token,
                "mutation(\$id:Int!,\$object:UserBookUpdateInput!) { update_user_book(id:\$id,object:\$object) { id error } }",
                JSONObject()
                    .put("id", current.userBookId)
                    .put("object", JSONObject().put("status_id", 3)),
            ),
            "update_user_book",
        )
        val latest = reading(token, bookId)
        require(latest.readId == null && latest.status == 3) {
            "Hardcover changed during the update; reload before retrying"
        }
        return "Marked Read on Hardcover, finished $finishedOn"
    }

    fun currentlyReading(token: String, bookId: Int): String {
        val state = reading(token, bookId)
        if (state.status == 2) return "Already marked Currently Reading on Hardcover"
        if (state.userBookId == null) {
            val me = account(token)
            checkedWrite(
                query(
                    token,
                    "mutation(\$object:UserBookCreateInput!) { insert_user_book(object:\$object) { id error } }",
                    JSONObject()
                        .put(
                            "object",
                            JSONObject()
                                .put("book_id", bookId)
                                .put("status_id", 2)
                                .put("privacy_setting_id", me.privacy),
                        ),
                ),
                "insert_user_book",
            )
        } else
            checkedWrite(
                query(
                    token,
                    "mutation(\$id:Int!,\$object:UserBookUpdateInput!) { update_user_book(id:\$id,object:\$object) { id error } }",
                    JSONObject()
                        .put("id", state.userBookId)
                        .put("object", JSONObject().put("status_id", 2)),
                ),
                "update_user_book",
            )
        return "Hardcover updated to Currently Reading; previous read history retained"
    }

    fun progress(
        token: String,
        bookId: Int,
        editionId: Int,
        pages: Int,
        expected: HardcoverReading,
        allowNewRead: Boolean,
        allowDecrease: Boolean,
        userId: Int? = null,
        /** The start date of a new read; today when not given. */
        startedOn: LocalDate? = null,
    ): String {
        require(startedOn == null || !startedOn.isAfter(LocalDate.now())) {
            "Choose a start date that is not in the future"
        }
        val edition =
            query(
                    token,
                    "query(\$id:Int!) { editions_by_pk(id:\$id) { id book_id pages } }",
                    JSONObject().put("id", editionId),
                )
                .getJSONObject("editions_by_pk")
        require(
            edition.getInt("book_id") == bookId &&
                edition.getInt("pages") > 0 &&
                pages in 0..edition.getInt("pages")
        ) {
            "Choose a matching edition and a valid page count"
        }
        val current = reading(token, bookId, userId)
        if (
            current.readId != null &&
                current.pages == pages &&
                current.editionId == editionId &&
                current.status == 2
        ) {
            if (current.bookEditionId != editionId)
                checkedWrite(
                    query(
                        token,
                        "mutation(\$id:Int!,\$object:UserBookUpdateInput!) { update_user_book(id:\$id,object:\$object) { id error } }",
                        JSONObject()
                            .put("id", current.userBookId)
                            .put("object", JSONObject().put("edition_id", editionId)),
                    ),
                    "update_user_book",
                )
            return "Progress already recorded on Hardcover"
        }
        require(
            current.userBookId == expected.userBookId &&
                current.readId == expected.readId &&
                current.pages == expected.pages &&
                current.editionId == expected.editionId
        ) {
            "Hardcover progress changed; reload and review before updating"
        }
        require(current.readId != null || allowNewRead) {
            "Confirm starting a new reading session first"
        }
        require(allowDecrease || current.pages == null || pages >= current.pages) {
            "Confirm decreasing progress before updating"
        }
        currentlyReading(token, bookId)
        val ready = reading(token, bookId)
        require(
            ready.userBookId == current.userBookId &&
                ready.readId == current.readId &&
                ready.pages == current.pages &&
                ready.editionId == current.editionId
        ) {
            "Hardcover progress changed; reload before retrying"
        }
        val objectValue = JSONObject().put("edition_id", editionId).put("progress_pages", pages)
        if (ready.readId == null) {
            objectValue.put("started_at", (startedOn ?: LocalDate.now()).toString())
            checkedWrite(
                query(
                    token,
                    "mutation(\$id:Int!,\$value:DatesReadInput!) { insert_user_book_read(user_book_id:\$id,user_book_read:\$value) { id error } }",
                    JSONObject().put("id", ready.userBookId).put("value", objectValue),
                ),
                "insert_user_book_read",
            )
        } else
            checkedWrite(
                query(
                    token,
                    "mutation(\$id:Int!,\$value:DatesReadInput!) { update_user_book_read(id:\$id,object:\$value) { id error } }",
                    JSONObject().put("id", ready.readId).put("value", objectValue),
                ),
                "update_user_book_read",
            )
        // The book entry keeps the selected edition too; this does not change
        // rating/privacy/review.
        val latest = reading(token, bookId)
        require(latest.pages == pages && latest.editionId == editionId && latest.readId != null) {
            "Hardcover progress changed during update; reload before retrying"
        }
        if (latest.bookEditionId != editionId)
            checkedWrite(
                query(
                    token,
                    "mutation(\$id:Int!,\$object:UserBookUpdateInput!) { update_user_book(id:\$id,object:\$object) { id error } }",
                    JSONObject()
                        .put("id", latest.userBookId)
                        .put("object", JSONObject().put("edition_id", editionId)),
                ),
                "update_user_book",
            )
        return "$pages pages recorded on Hardcover"
    }
}

data class HardcoverEdition(val id: Int, val title: String, val pages: Int, val label: String)

data class HardcoverReading(
    val userBookId: Int?,
    val status: Int?,
    val readId: Int?,
    val pages: Int?,
    val editionId: Int?,
    val bookEditionId: Int? = null,
    /** When the read in progress started, as Hardcover's yyyy-mm-dd. */
    val startedOn: String? = null,
)

fun estimatedHardcoverPage(percent: Double, total: Int): Int {
    require(percent.isFinite() && percent in 0.0..100.0 && total > 0)
    return kotlin.math.round(percent / 100.0 * total).toInt().coerceIn(0, total)
}

/** KOReader snapshots store a fraction in [0,1], unlike the percent input shown to people. */
fun koreaderPercent(fraction: Double): Double {
    require(fraction.isFinite() && fraction in 0.0..1.0)
    return fraction * 100.0
}

data class HardcoverLibraryBook(
    val userBookId: Int,
    val book: HardcoverMatch,
    val status: Int,
    val pages: Int?,
    val editionId: Int?,
)

data class HardcoverBookData(val id: Int, val metadata: Metadata, val coverUrl: String?)
