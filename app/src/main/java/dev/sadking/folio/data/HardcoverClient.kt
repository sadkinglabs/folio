package dev.sadking.folio.data

import dev.sadking.folio.core.Epub
import dev.sadking.folio.core.Metadata
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import java.time.OffsetDateTime
import org.json.*

data class HardcoverMatch(
    val id: Int,
    val title: String,
    val authors: List<String>,
    val slug: String,
    val releaseYear: Int? = null,
    /** Hardcover's cover image (`cached_image.url`), or null. */
    val coverUrl: String? = null,
)

data class HardcoverAccount(val id: Int, val username: String, val privacy: Int)

/**
 * A Hardcover HTTP failure. Still an [IllegalArgumentException], as callers already expect.
 * [retryAfterMs]: for 429, how long every request now waits.
 */
class HardcoverHttpException(val code: Int, message: String, val retryAfterMs: Long? = null) :
    IllegalArgumentException(message) {
    /** Worth retrying later: rate limits and server errors. */
    val transient: Boolean
        get() = code == 429 || code >= 500

    val unauthorized: Boolean
        get() = code == 401 || code == 403
}

/** An edition carrying one of the asked ISBNs, and its book (the canonical book when set). */
data class HardcoverIsbnHit(val isbn13: String?, val isbn10: String?, val book: HardcoverMatch)

/**
 * Personal-account GraphQL client. No token-bearing redirects and no response text in errors. Every
 * request in the process goes through one gate ([Companion]): one at a time, at least [SPACING_MS]
 * apart, and none while Hardcover asked to slow down.
 */
class HardcoverClient(private val fixture: ((String, JSONObject) -> JSONObject)? = null) {
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
            paced(live = fixture == null) { fixture?.invoke(token, body) ?: post(token, body) }
        require(result.optJSONArray("errors")?.length()?.let { it > 0 } != true) {
            "Hardcover could not complete this operation; no success was recorded"
        }
        return result.optJSONObject("data") ?: error("Invalid Hardcover response")
    }

    private fun post(token: String, body: JSONObject): JSONObject {
        val connection =
            URI("https://api.hardcover.app/v1/graphql").toURL().openConnection()
                as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        // Hardcover stops a query after 30 seconds.
        connection.readTimeout = 35000
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
                        429 -> BUSY
                        else -> "Hardcover request failed; check internet and retry"
                    },
                    if (code == 429) retryAfterMs(connection.getHeaderField("Retry-After"))
                    else null,
                )
            val bytes = java.io.ByteArrayOutputStream()
            connection.inputStream.use { Epub.copyBounded(it, bytes, 2L * 1024 * 1024) }
            return JSONObject(bytes.toString("UTF-8"))
        } finally {
            connection.disconnect()
        }
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
                    "query(\$ids:[Int!]!) { books(where:{id:{_in:\$ids}}) { id title slug release_year cached_contributors cached_image } }",
                    JSONObject().put("ids", JSONArray(positive)),
                )
                .getJSONArray("books")
        return books
            .objects()
            .map { bookMatch(it) }
            .also {
                require(it.size <= 12 && it.all { b -> b.id in positive && b.title.isNotBlank() })
            }
            .sortedBy { positive.indexOf(it.id) }
    }

    /**
     * Editions that carry any of [isbns] (ISBN-10 or ISBN-13 strings, at most 100), each with its
     * book. Read only. Editions whose book has no title are left out.
     */
    fun editionsByIsbn(token: String, isbns: List<String>): List<HardcoverIsbnHit> {
        require(
            isbns.size in 1..100 &&
                isbns.all { it.length in 10..13 && it.all { c -> c.isDigit() || c == 'X' } }
        )
        val rows =
            query(
                    token,
                    "query(\$isbns:[String!]!) { editions(where:{_or:[{isbn_13:{_in:\$isbns}},{isbn_10:{_in:\$isbns}}]},limit:200) { id isbn_13 isbn_10 book { id title slug release_year canonical_id cached_contributors cached_image } } }",
                    JSONObject().put("isbns", JSONArray(isbns)),
                )
                .getJSONArray("editions")
        require(rows.length() <= 200) { "Invalid Hardcover edition response" }
        return rows.objects().mapNotNull { o ->
            val b = o.optJSONObject("book") ?: return@mapNotNull null
            val canonical =
                if (b.isNull("canonical_id")) null else b.optInt("canonical_id").takeIf { it > 0 }
            val book = bookMatch(b, canonical ?: b.optInt("id"))
            if (book.id <= 0 || book.title.isBlank() || book.title.length > 2000)
                return@mapNotNull null
            HardcoverIsbnHit(
                text(o, "isbn_13").takeIf { it.isNotBlank() },
                text(o, "isbn_10").takeIf { it.isNotBlank() },
                book,
            )
        }
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
                    "query(\$user:Int!,\$after:Int!) { user_books(where:{user_id:{_eq:\$user},id:{_gt:\$after}},order_by:{id:asc},limit:50) { id status_id edition_id updated_at book { id title slug release_year cached_contributors cached_image } user_book_reads(order_by:{id:desc},limit:1) { progress_pages edition_id } finished: user_book_reads(where:{finished_at:{_is_null:false}},order_by:{id:asc},limit:50) { id started_at finished_at } } }",
                    JSONObject().put("user", accountId).put("after", after),
                )
                .getJSONArray("user_books")
        require(rows.length() <= 50)
        val result =
            rows.objects().map { row ->
                val read = row.getJSONArray("user_book_reads").optJSONObject(0)
                HardcoverLibraryBook(
                    row.getInt("id"),
                    bookMatch(row.getJSONObject("book")),
                    row.getInt("status_id"),
                    if (read == null || read.isNull("progress_pages")) null
                    else read.getInt("progress_pages"),
                    if (read != null && !read.isNull("edition_id")) read.getInt("edition_id")
                    else if (row.isNull("edition_id")) null else row.getInt("edition_id"),
                    epochMillis(text(row, "updated_at")),
                    finishedReads(row),
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
                    (it.pages == null || it.pages >= 0) &&
                    it.reads.all { r -> r.id > 0 }
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
     * Sets the book's status on Hardcover to [status]: Want to Read (1), Currently Reading (2),
     * Paused (4) or Did Not Finish (5); Read goes through [markRead]. With no [userBookId] the book
     * is added with [privacy] (the account's setting). Reads, rating and review are kept. Returns
     * the user_book id.
     */
    fun setStatus(token: String, bookId: Int, status: Int, userBookId: Int?, privacy: Int): Int {
        require(bookId > 0 && status in setOf(1, 2, 4, 5) && (userBookId == null || userBookId > 0))
        return if (userBookId == null)
            checkedWrite(
                query(
                    token,
                    "mutation(\$object:UserBookCreateInput!) { insert_user_book(object:\$object) { id error } }",
                    JSONObject()
                        .put(
                            "object",
                            JSONObject()
                                .put("book_id", bookId)
                                .put("status_id", status)
                                .put("privacy_setting_id", privacy),
                        ),
                ),
                "insert_user_book",
            )
        else
            checkedWrite(
                query(
                    token,
                    "mutation(\$id:Int!,\$object:UserBookUpdateInput!) { update_user_book(id:\$id,object:\$object) { id error } }",
                    JSONObject()
                        .put("id", userBookId)
                        .put("object", JSONObject().put("status_id", status)),
                ),
                "update_user_book",
            )
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

    /**
     * Marks the book Read on Hardcover, finished on [finishedOn], whatever its state there. A read
     * in progress on an edition with a page count is finished as [finish] does; another read in
     * progress just gets the date; a book with no read in progress, or not in the library yet, gets
     * a new finished read. With no [finishedOn] (a book read long ago) the book is marked Read with
     * no dates, except that a read in progress on Hardcover ends today. Earlier reads, rating and
     * review are kept. A book already Read with no read in progress is left alone.
     */
    fun markRead(token: String, bookId: Int, finishedOn: LocalDate?, userId: Int? = null): String {
        require(finishedOn == null || !finishedOn.isAfter(LocalDate.now())) {
            "Choose a finish date that is not in the future"
        }
        val current = reading(token, bookId, userId)
        if (current.status == 3 && current.readId == null) return "Already marked Read on Hardcover"
        val day = finishedOn ?: LocalDate.now().takeIf { current.readId != null }
        if (
            day != null &&
                current.readId != null &&
                current.editionId?.let { editionPages(token, it) } != null
        )
            return finish(token, bookId, day, current)
        val userBookId =
            if (current.userBookId == null) {
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
                                    .put("status_id", 3)
                                    .put("privacy_setting_id", me.privacy),
                            ),
                    ),
                    "insert_user_book",
                )
            } else current.userBookId
        if (current.readId != null)
            checkedWrite(
                query(
                    token,
                    "mutation(\$id:Int!,\$value:DatesReadInput!) { update_user_book_read(id:\$id,object:\$value) { id error } }",
                    JSONObject()
                        .put("id", current.readId)
                        .put("value", JSONObject().put("finished_at", day.toString())),
                ),
                "update_user_book_read",
            )
        else if (day != null)
            checkedWrite(
                query(
                    token,
                    "mutation(\$id:Int!,\$value:DatesReadInput!) { insert_user_book_read(user_book_id:\$id,user_book_read:\$value) { id error } }",
                    JSONObject()
                        .put("id", userBookId)
                        .put(
                            "value",
                            JSONObject().put("finished_at", day.toString()).apply {
                                current.bookEditionId?.let { put("edition_id", it) }
                            },
                        ),
                ),
                "insert_user_book_read",
            )
        if (current.userBookId != null && current.status != 3)
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
        return if (day == null) "Marked Read on Hardcover"
        else "Marked Read on Hardcover, finished $day"
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

    private fun text(o: JSONObject?, key: String): String =
        o?.optString(key)?.takeUnless { it == "null" }.orEmpty()

    /**
     * The first ten finished reads of a library row (docs/HARDCOVER_SYNC.md D7). A read whose
     * finish or start date does not parse is dropped; a blank start date is simply unknown.
     */
    private fun finishedReads(row: JSONObject): List<HardcoverRead> =
        (row.optJSONArray("finished") ?: JSONArray()).objects().mapNotNull { r ->
            val finishedOn = isoDate(text(r, "finished_at")) ?: return@mapNotNull null
            val started = text(r, "started_at")
            val startedOn =
                if (started.isBlank()) null else isoDate(started) ?: return@mapNotNull null
            HardcoverRead(r.getInt("id"), startedOn, finishedOn)
        }

    /** A book as Hardcover's `books` rows give it; [id] overrides the row's own id. */
    private fun bookMatch(o: JSONObject, id: Int = o.getInt("id")): HardcoverMatch =
        HardcoverMatch(
            id,
            o.getString("title"),
            (o.optJSONArray("cached_contributors") ?: JSONArray())
                .objects()
                .mapNotNull { c ->
                    c.optJSONObject("author")?.optString("name")?.takeIf { it.isNotBlank() }
                        ?: c.optString("name").takeIf { it.isNotBlank() }
                }
                .distinct(),
            o.optString("slug"),
            if (o.isNull("release_year")) null
            else o.optInt("release_year").takeIf { it in 1..9999 },
            text(o.optJSONObject("cached_image"), "url").takeIf { it.startsWith("https://") },
        )

    companion object {
        /** Hardcover allows about 60 requests a minute; one every 1.1 s stays under it. */
        internal const val SPACING_MS = 1_100L
        /** How long to wait after a 429 that names no Retry-After. */
        internal const val DEFAULT_RETRY_AFTER_MS = 60_000L
        /** A cool-down this short is waited out; a longer one fails fast with a 429. */
        internal const val SHORT_WAIT_MS = 2_000L
        private const val MAX_RETRY_AFTER_MS = 60 * 60_000L
        private const val BUSY = "Hardcover is busy; retry later"

        private val gate = Any()
        private var lastRequest = 0L
        private var coolUntil = 0L

        /** The gate's clock. Tests replace it. */
        @Volatile internal var clock: () -> Long = System::currentTimeMillis

        /** Tests: forget earlier requests and cool-downs. */
        internal fun resetPacing() =
            synchronized(gate) {
                lastRequest = 0
                coolUntil = 0
            }

        /** Retry-After in whole seconds, as milliseconds; null when absent or not seconds. */
        internal fun retryAfterMs(header: String?): Long? =
            header
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it >= 0 }
                ?.let { (it * 1000).coerceIn(1_000L, MAX_RETRY_AFTER_MS) }

        /**
         * Runs one request through the process-wide gate. [live] requests are spaced and wait out a
         * short cool-down; recorded responses (tests) skip the waiting but not the cool-down rule.
         */
        internal fun <T> paced(live: Boolean, send: () -> T): T =
            synchronized(gate) {
                val remaining = coolUntil - clock()
                if (remaining > SHORT_WAIT_MS) throw HardcoverHttpException(429, BUSY, remaining)
                if (live) {
                    if (remaining > 0) Thread.sleep(remaining)
                    val wait = SPACING_MS - (clock() - lastRequest)
                    if (wait > 0) Thread.sleep(wait)
                }
                lastRequest = clock()
                try {
                    send()
                } catch (e: HardcoverHttpException) {
                    if (e.code != 429) throw e
                    val wait = e.retryAfterMs ?: DEFAULT_RETRY_AFTER_MS
                    coolUntil = clock() + wait
                    throw HardcoverHttpException(429, BUSY, wait)
                }
            }

        /**
         * The day of a Hardcover date or timestamp ("2024-03-01" or "2024-03-01T20:15:00+00:00").
         */
        private fun isoDate(value: String): LocalDate? =
            runCatching { LocalDate.parse(value.take(10)) }.getOrNull()

        private fun epochMillis(value: String): Long? =
            value
                .takeIf { it.isNotBlank() }
                ?.let {
                    runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
                }
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

/** One finished read of a library book. [startedOn] null when Hardcover has no start date. */
data class HardcoverRead(val id: Int, val startedOn: LocalDate?, val finishedOn: LocalDate)

data class HardcoverLibraryBook(
    val userBookId: Int,
    val book: HardcoverMatch,
    val status: Int,
    val pages: Int?,
    val editionId: Int?,
    /** When Hardcover last changed this entry, epoch milliseconds, or null. */
    val updatedAt: Long? = null,
    /** The book's finished reads, oldest first (at most 10). */
    val reads: List<HardcoverRead> = emptyList(),
)

data class HardcoverBookData(val id: Int, val metadata: Metadata, val coverUrl: String?)
