package dev.sadking.folio.sync

import dev.sadking.folio.FolioApplication
import dev.sadking.folio.core.hardcover.HardcoverMatching
import dev.sadking.folio.core.hardcover.MatchCandidate
import dev.sadking.folio.core.hardcover.MatchDecision
import dev.sadking.folio.core.hardcover.MatchQuery
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.HardcoverCover
import dev.sadking.folio.data.HardcoverHttpException
import dev.sadking.folio.data.HardcoverLinks
import dev.sadking.folio.data.LibraryRepository
import dev.sadking.folio.data.WishBook
import dev.sadking.folio.data.decode
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Matches library items to Hardcover books (docs/HARDCOVER_SYNC.md A2): the owner's own Hardcover
 * library first, then ISBNs in batches, then title and author through search. It only reads
 * Hardcover. Each item's result is saved before the next one starts, so a stopped pass resumes
 * where it stopped.
 */
class HardcoverMatcher
internal constructor(
    private val repo: LibraryRepository,
    private val store: HardcoverStore,
    private val links: HardcoverLinks,
    private val covers: HardcoverCover,
    /** filesDir/hardcover-candidates: the covers shown in "Check these matches". */
    private val coverDir: File,
    private val now: () -> Long,
) {
    constructor(
        app: FolioApplication
    ) : this(
        app.repository,
        HardcoverStore(app),
        HardcoverLinks(app.repository),
        HardcoverCover(),
        File(app.filesDir, CANDIDATE_COVERS),
        System::currentTimeMillis,
    )

    private val dao
        get() = repo.dao

    /** Items still to match, newest first. */
    suspend fun pending(): List<MatchQuery> =
        withContext(Dispatchers.IO) {
            matchItems(
                dao.allBooks(),
                dao.allStoredWishes(),
                dao.allRemoved().map { it.id }.toSet(),
                store.allMatchRecords().associateBy { it.itemId },
            )
        }

    /**
     * Links items that match a book in the owner's own Hardcover library. Returns how many. No
     * requests. Items Hardcover's search did not find are tried too: the owner may have added the
     * book on Hardcover since.
     */
    suspend fun fromLibrary(library: List<RemoteUserBook>): Int {
        if (library.isEmpty()) return 0
        val own =
            library
                .distinctBy { it.hardcoverId }
                .map {
                    MatchCandidate(
                        it.hardcoverId,
                        it.title,
                        it.authors,
                        it.releaseYear,
                        it.coverUrl,
                    )
                }
        val items =
            withContext(Dispatchers.IO) {
                val records = store.allMatchRecords().associateBy { it.itemId }
                unlinkedItems(
                        dao.allBooks(),
                        dao.allStoredWishes(),
                        dao.allRemoved().map { it.id }.toSet(),
                    )
                    .filter { q ->
                        val record = records[q.itemId]
                        record == null ||
                            record.state == MatchRecordState.NOT_FOUND ||
                            (record.state == MatchRecordState.UNSURE &&
                                record.metadataKey != q.key())
                    }
            }
        var linked = 0
        for (query in items) {
            val hit = HardcoverMatching.fromLibrary(query, own) ?: continue
            if (current(query) == null) continue
            if (link(query, hit)) linked++
        }
        return linked
    }

    /**
     * ISBN batches, then searches, until nothing is pending or [deadline]. Returns how many were
     * linked.
     */
    suspend fun matchBatch(
        api: HardcoverApi,
        deadline: Long,
        progress: (done: Int, total: Int) -> Unit,
    ): Int {
        val items = pending()
        if (items.isEmpty()) return 0
        val total = items.size
        var done = 0
        var linked = 0
        progress(0, total)
        val search = items.filter { it.isbns.isEmpty() }.map { it.itemId }.toMutableSet()
        for (group in isbnGroups(items.filter { it.isbns.isNotEmpty() })) {
            if (now() >= deadline) return linked
            val hits = failSoft {
                withContext(Dispatchers.IO) { api.editionsByIsbn(group.forms()) }
            }
            for (query in group) {
                val decision =
                    if (hits == null) MatchDecision.NotFound
                    else
                        HardcoverMatching.fromIsbn(
                            query,
                            hits
                                .filter { it.isbn13 in query.isbns }
                                .map { it.candidate }
                                .distinctBy { it.hardcoverId },
                        )
                if (decision == MatchDecision.NotFound) {
                    search += query.itemId
                    continue
                }
                if (apply(query, decision)) linked++
                progress(++done, total)
            }
        }
        for (query in items.filter { it.itemId in search }) {
            if (now() >= deadline) return linked
            val text = HardcoverMatching.searchText(query)
            val decision =
                if (text.isBlank()) MatchDecision.NotFound
                else
                    failSoft { withContext(Dispatchers.IO) { api.search(text) } }
                        ?.let { HardcoverMatching.fromSearch(query, it) } ?: MatchDecision.NotFound
            if (apply(query, decision)) linked++
            progress(++done, total)
        }
        return linked
    }

    /** Saves what matching concluded for [query]. True when it was linked. */
    private suspend fun apply(query: MatchQuery, decision: MatchDecision): Boolean {
        // The owner may have linked, edited, removed or ruled out the item meanwhile.
        val fresh = current(query) ?: return false
        if (fresh.key() != query.key()) return false
        if (
            store.allMatchRecords().any {
                it.itemId == query.itemId && it.state == MatchRecordState.NOT_ON_HARDCOVER
            }
        )
            return false
        when (decision) {
            is MatchDecision.Confident -> {
                if (link(query, decision.candidate)) return true
                unsure(query, listOf(decision.candidate))
            }
            is MatchDecision.Unsure ->
                unsure(query, decision.candidates.take(HardcoverMatching.MAX_CANDIDATES))
            MatchDecision.NotFound ->
                store.saveMatch(
                    MatchRecord(
                        query.itemId,
                        MatchRecordState.NOT_FOUND,
                        emptyList(),
                        query.key(),
                        now(),
                    )
                )
        }
        return false
    }

    private suspend fun link(query: MatchQuery, candidate: MatchCandidate): Boolean =
        try {
            links.link(query.itemId, candidate.hardcoverId, candidate.title, candidate.authors)
            withContext(Dispatchers.IO) { deleteCandidateCovers(coverDir, query.itemId) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: IllegalStateException) {
            false
        }

    private suspend fun unsure(query: MatchQuery, candidates: List<MatchCandidate>) {
        val views = candidates.map { c ->
            MatchCandidateView(
                c.hardcoverId,
                c.title,
                c.authors,
                c.releaseYear,
                c.coverUrl?.let { cover(query.itemId, c.hardcoverId, it) },
            )
        }
        withContext(Dispatchers.IO) {
            deleteCandidateCovers(
                coverDir,
                query.itemId,
                keep = views.mapNotNull { it.coverPath }.toSet(),
            )
        }
        store.saveMatch(
            MatchRecord(query.itemId, MatchRecordState.UNSURE, views, query.key(), now())
        )
    }

    /** A candidate's cover in [coverDir], best effort. */
    private suspend fun cover(itemId: String, hardcoverId: Int, url: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val bytes = covers.load(url)
                if (bytes.isEmpty()) return@withContext null
                coverDir.mkdirs()
                val target = File(coverDir, candidateCoverName(itemId, hardcoverId))
                val staged = File(coverDir, "${target.name}.part")
                staged.writeBytes(bytes)
                if (!staged.renameTo(target)) {
                    staged.delete()
                    return@withContext null
                }
                target.absolutePath
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /** The item as it is now, or null when it is gone, removed or already linked. */
    private suspend fun current(query: MatchQuery): MatchQuery? =
        withContext(Dispatchers.IO) {
            val id = query.itemId
            if (dao.removedEntry(id) != null) return@withContext null
            dao.wish(id)?.let { wish ->
                if (wish.hardcoverId != null) return@withContext null
                val book = wish.linkedBookId?.let { dao.book(it) }
                if (book != null && dao.removedEntry(book.id) != null) return@withContext null
                return@withContext wishQuery(wish, book)
            }
            val book = dao.book(id) ?: return@withContext null
            if (dao.allStoredWishes().any { it.linkedBookId == id }) return@withContext null
            bookQuery(id, book)
        }

    /** The request result, or null when Hardcover's answer needs a person (matching moves on). */
    private inline fun <T> failSoft(request: () -> T): T? =
        try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HardcoverHttpException) {
            if (e.transient || e.unauthorized) throw e
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IllegalStateException) {
            null
        }

    private fun List<MatchQuery>.forms(): List<String> = flatMap { it.forms() }.distinct()

    private fun MatchQuery.forms(): List<String> =
        isbns.flatMap(HardcoverMatching::isbnForms).distinct().take(MAX_ISBNS)

    /** Groups whose ISBN strings fit one request. */
    private fun isbnGroups(items: List<MatchQuery>): List<List<MatchQuery>> {
        val groups = mutableListOf<List<MatchQuery>>()
        var current = mutableListOf<MatchQuery>()
        var size = 0
        for (item in items) {
            val n = item.forms().size
            if (current.isNotEmpty() && size + n > MAX_ISBNS) {
                groups += current
                current = mutableListOf()
                size = 0
            }
            current += item
            size += n
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    companion object {
        /** ISBN strings per request (HardcoverApi.editionsByIsbn). */
        const val MAX_ISBNS = 100
    }
}

/** The folder under filesDir that holds candidate covers. */
internal const val CANDIDATE_COVERS = "hardcover-candidates"

internal fun candidateCoverName(itemId: String, hardcoverId: Int) =
    "${itemId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(100)}__$hardcoverId"

/** Deletes the candidate covers of [itemId] in [coverDir], except [keep] (absolute paths). */
internal fun deleteCandidateCovers(coverDir: File, itemId: String, keep: Set<String> = emptySet()) {
    val prefix = candidateCoverName(itemId, 0).removeSuffix("0")
    coverDir
        .listFiles()
        ?.filter {
            it.isFile &&
                it.name.startsWith(prefix) &&
                it.name.removePrefix(prefix).removeSuffix(".part").all(Char::isDigit) &&
                it.absolutePath !in keep
        }
        ?.forEach { it.delete() }
}

private fun strings(json: String): List<String> =
    runCatching { decode(json) }.getOrDefault(emptyList())

/** A book as matching sees it. */
internal fun bookQuery(itemId: String, book: Book): MatchQuery =
    MatchQuery(
        itemId,
        book.title,
        strings(book.authors),
        HardcoverMatching.isbns(strings(book.identifiers)),
    )

/** A catalogue entry as matching sees it: its book's details when it has a file, else its own. */
internal fun wishQuery(wish: WishBook, book: Book?): MatchQuery =
    if (book != null) bookQuery(wish.id, book)
    else
        MatchQuery(
            wish.id,
            wish.title,
            strings(wish.authors),
            HardcoverMatching.isbns(
                runCatching {
                        JSONObject(wish.details).optJSONArray("identifiers")?.let { a ->
                            (0 until a.length()).map { a.optString(it) }
                        }
                    }
                    .getOrNull()
                    .orEmpty()
            ),
        )

internal fun MatchQuery.key(): String = HardcoverMatching.metadataKey(title, authors, isbns)

/**
 * Every unlinked item, newest first: catalogue entries without a Hardcover link, and books no
 * catalogue entry links. Items in Recently removed ([removed]) are left out.
 */
internal fun unlinkedItems(
    books: List<Book>,
    wishes: List<WishBook>,
    removed: Set<String>,
): List<MatchQuery> {
    val kept = books.filter { it.id !in removed }.associateBy { it.id }
    val linkedBooks = wishes.mapNotNull { it.linkedBookId }.toSet()
    val entries =
        wishes
            .filter {
                it.hardcoverId == null &&
                    it.id !in removed &&
                    (it.linkedBookId == null || it.linkedBookId !in removed)
            }
            .map { it.addedAt to wishQuery(it, it.linkedBookId?.let(kept::get)) }
    val loose =
        kept.values.filter { it.id !in linkedBooks }.map { it.addedAt to bookQuery(it.id, it) }
    return (entries + loose).sortedByDescending { it.first }.map { it.second }
}

/** Pure: the items to match. */
fun matchItems(
    books: List<Book>,
    wishes: List<WishBook>,
    removed: Set<String>,
    records: Map<String, MatchRecord>,
): List<MatchQuery> =
    unlinkedItems(books, wishes, removed).filter { query ->
        val record = records[query.itemId] ?: return@filter true
        record.state != MatchRecordState.NOT_ON_HARDCOVER && record.metadataKey != query.key()
    }

/** "Check these matches": unsure records of items that still exist and are still unlinked. */
internal fun matchChecks(
    books: List<Book>,
    wishes: List<WishBook>,
    records: Map<String, MatchRecord>,
): List<MatchCheck> =
    unlinkedItems(books, wishes, emptySet()).mapNotNull { query ->
        val record = records[query.itemId]
        if (record?.state != MatchRecordState.UNSURE || record.candidates.isEmpty()) null
        else MatchCheck(query.itemId, query.title, query.authors, record.candidates)
    }
