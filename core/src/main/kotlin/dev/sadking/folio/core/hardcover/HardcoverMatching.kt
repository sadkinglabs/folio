package dev.sadking.folio.core.hardcover

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** One item to match. [isbns]: ISBN-13s from HardcoverMatching.isbns. */
data class MatchQuery(
    val itemId: String,
    val title: String,
    val authors: List<String>,
    val isbns: List<String>,
)

data class MatchCandidate(
    val hardcoverId: Int,
    val title: String,
    val authors: List<String>,
    val releaseYear: Int?,
    val coverUrl: String?,
)

sealed interface MatchDecision {
    data class Confident(val candidate: MatchCandidate) : MatchDecision

    /** 1..3 candidates, best first. */
    data class Unsure(val candidates: List<MatchCandidate>) : MatchDecision

    data object NotFound : MatchDecision
}

/**
 * Which Hardcover book a library item is, from its ISBNs, title and authors. Pure: the requests are
 * made elsewhere. Rules: docs/HARDCOVER_SYNC.md, A2 and B3.
 */
object HardcoverMatching {
    const val MAX_CANDIDATES = 3
    private const val MAX_SEARCH_TEXT = 300
    private const val MIN_CONTAINED = 4

    private val marks = Regex("\\p{M}+")
    private val apostrophes = Regex("['‘’ʼ`]")
    private val nonWords = Regex("[^\\p{L}\\p{N}]+")
    private val trailingBrackets = Regex("\\s*[(\\[][^()\\[\\]]*[)\\]]\\s*$")
    private val subtitle = Regex(":|\\s[-–—]\\s|[(\\[]")
    private val spaces = Regex("\\s+")
    private val articles = listOf("the ", "a ", "an ")
    private val unknownAuthors = setOf("unknown", "unknown author")

    /** Accents and case folded, "&" as "and", apostrophes dropped, other punctuation as spaces. */
    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(marks, "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(apostrophes, "")
            .replace(nonWords, " ")
            .trim()

    fun normalizeTitle(title: String): String {
        val bare = title.replace(trailingBrackets, "").ifBlank { title }
        val folded = fold(bare)
        val article = articles.firstOrNull { folded.startsWith(it) && folded.length > it.length }
        return if (article == null) folded else folded.removePrefix(article)
    }

    /** The title before a subtitle (":", " - ", " — ", "(", "["), normalised. */
    fun mainTitle(title: String): String = normalizeTitle(rawMainTitle(title))

    private fun rawMainTitle(title: String): String {
        val cut = subtitle.find(title)?.range?.first ?: return title
        return title.substring(0, cut).ifBlank { title }
    }

    /** "Last, First" as "First Last"; any other name as it is. */
    private fun firstLast(name: String): String {
        val parts = name.split(',')
        return if (parts.size == 2 && parts.all { it.isNotBlank() }) "${parts[1]} ${parts[0]}"
        else name
    }

    private fun usable(normalized: String) =
        normalized.isNotEmpty() && normalized !in unknownAuthors

    fun normalizeAuthor(name: String): String {
        val words = fold(firstLast(name).replace('.', ' ')).split(' ').filter { it.isNotEmpty() }
        val joined = mutableListOf<String>()
        var initials = ""
        for (word in words) {
            if (word.length == 1) {
                initials += word
                continue
            }
            if (initials.isNotEmpty()) joined += initials
            initials = ""
            joined += word
        }
        if (initials.isNotEmpty()) joined += initials
        return joined.joinToString(" ")
    }

    /**
     * One identifier as an ISBN-13, or null: strips "urn:isbn:", "isbn:", "ISBN", hyphens and
     * spaces; checks the check digit; turns ISBN-10 into 978-ISBN-13.
     */
    fun isbn13(raw: String): String? {
        var text = raw.trim().lowercase(Locale.ROOT).removePrefix("urn:").trimStart()
        if (text.startsWith("isbn")) {
            text = text.removePrefix("isbn")
            // "ISBN-13: …" or "ISBN-10 …": the label's length is not part of the number.
            text = text.replace(Regex("^\\s*-?1[03](?=\\s*:|\\s)"), "")
        }
        val clean = text.filterNot { it.isWhitespace() || it in "-:‐‑‒–" }
        return when {
            clean.length == 13 && clean.all { it.isDigit() } ->
                clean.takeIf {
                    (it.startsWith("978") || it.startsWith("979")) &&
                        check13(it.substring(0, 12)) == it[12]
                }
            clean.length == 10 && clean.substring(0, 9).all { it.isDigit() } -> {
                val last = clean[9].uppercaseChar()
                if (last != 'X' && !last.isDigit()) return null
                if (check10(clean.substring(0, 9)) != last) return null
                val body = "978" + clean.substring(0, 9)
                body + check13(body)
            }
            else -> null
        }
    }

    /** Every valid ISBN in [identifiers] as ISBN-13, distinct, in order. */
    fun isbns(identifiers: List<String>): List<String> = identifiers.mapNotNull(::isbn13).distinct()

    /** The strings to ask Hardcover for: the ISBN-13, plus its ISBN-10 when it starts with 978. */
    fun isbnForms(isbn13: String): List<String> {
        if (isbn13.length != 13 || !isbn13.startsWith("978") || !isbn13.all { it.isDigit() }) {
            return listOf(isbn13)
        }
        val body = isbn13.substring(3, 12)
        return listOf(isbn13, body + check10(body))
    }

    private fun check13(twelve: String): Char {
        val sum = twelve.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return '0' + (10 - sum % 10) % 10
    }

    private fun check10(nine: String): Char {
        val sum = nine.mapIndexed { i, c -> (c - '0') * (10 - i) }.sum()
        val check = (11 - sum % 11) % 11
        return if (check == 10) 'X' else '0' + check
    }

    private fun usableAuthors(authors: List<String>): List<String> =
        authors.map(::normalizeAuthor).filter(::usable)

    private fun contains(full: String, part: String): Boolean =
        part.count { it.isLetterOrDigit() } >= MIN_CONTAINED && " $full ".contains(" $part ")

    private fun titleScore(a: String, b: String): Int {
        val fullA = normalizeTitle(a)
        val fullB = normalizeTitle(b)
        val mainA = mainTitle(a)
        val mainB = mainTitle(b)
        return when {
            fullA.isNotEmpty() && fullA == fullB -> 2
            mainA.isNotEmpty() && mainA == mainB -> 2
            contains(fullA, mainB) || contains(fullB, mainA) -> 1
            else -> 0
        }
    }

    private fun authorScore(a: List<String>, b: List<String>): Int {
        val left = usableAuthors(a)
        val right = usableAuthors(b)
        if (left.isEmpty() || right.isEmpty()) return 0
        if (left.any { it in right }) return 2
        val surnames = right.map { it.substringAfterLast(' ') }.toSet()
        return if (left.any { it.substringAfterLast(' ') in surnames }) 1 else 0
    }

    /**
     * 0..4: title 2 (same normalised or same main title), 1 (one contains the other's main title,
     * 4+ letters or digits, as whole words), else 0; plus author 2 (a normalised name in common), 1
     * (a surname in common), else 0. No usable author (blank, "Unknown", "Unknown author") scores 0
     * for author.
     */
    fun score(query: MatchQuery, candidate: MatchCandidate): Int =
        titleScore(query.title, candidate.title) + authorScore(query.authors, candidate.authors)

    /**
     * True when the titles differ but one holds the other's main title: most often another book of
     * the same series, such as "The Fall of Hyperion" for "Hyperion", or "Dune Messiah" for "Dune";
     * sometimes a translation that keeps the title ("El Hobbit").
     */
    fun seriesSibling(query: MatchQuery, candidate: MatchCandidate): Boolean =
        titleScore(query.title, candidate.title) == 1

    /**
     * True when a sure ISBN answer must be checked with a title search before linking: its title
     * only holds the item's (or the other way round), so it may be another book of the series whose
     * ISBN the EPUB carries by mistake — the EPUB of "The Fall of Hyperion" with the ISBN of
     * "Hyperion".
     */
    fun needsSearch(query: MatchQuery, isbn: MatchDecision): Boolean =
        isbn is MatchDecision.Confident && seriesSibling(query, isbn.candidate)

    /**
     * A sure ISBN answer that [needsSearch], checked against the title search's [results] (null
     * when the search could not be made). The search's own sure answer wins, whichever book it is.
     * Otherwise nothing is linked: the ISBN's book and the plausible results, at most 3, go to
     * "Check these matches".
     */
    fun confirmIsbn(
        query: MatchQuery,
        isbn: MatchDecision.Confident,
        results: List<MatchCandidate>?,
    ): MatchDecision {
        val search = results?.let { fromSearch(query, it) }
        if (search is MatchDecision.Confident) return search
        val others = (search as? MatchDecision.Unsure)?.candidates.orEmpty()
        return MatchDecision.Unsure(
            (listOf(isbn.candidate) + others).distinctBy { it.hardcoverId }.take(MAX_CANDIDATES)
        )
    }

    private class Scored(val candidate: MatchCandidate, val score: Int)

    /** Distinct books by score, best first; ties keep Hardcover's order. */
    private fun rank(query: MatchQuery, candidates: List<MatchCandidate>): List<Scored> =
        candidates
            .distinctBy { it.hardcoverId }
            .map { Scored(it, score(query, it)) }
            .sortedByDescending { it.score }

    /**
     * The best result when it scores 4 and every other one is clearly worse: 2 or less, or a series
     * sibling ([seriesSibling]), which is another book and not a rival for this one.
     */
    private fun confident(query: MatchQuery, ranked: List<Scored>): MatchCandidate? {
        val best = ranked.firstOrNull() ?: return null
        val rivals = ranked.drop(1).filter { it.score > 2 && !seriesSibling(query, it.candidate) }
        return best.candidate.takeIf { best.score == 4 && rivals.isEmpty() }
    }

    /**
     * ISBN hits (distinct books). One book: Confident when score ≥ 2, else Unsure([it]). Several:
     * Unsure, the top 3 by score then Hardcover's order (A2: several books always go to "Check
     * these matches"). None: NotFound. A Confident answer whose title only holds the item's, or the
     * other way round, still needs the title search ([needsSearch], [confirmIsbn]).
     */
    fun fromIsbn(query: MatchQuery, hits: List<MatchCandidate>): MatchDecision {
        val ranked = rank(query, hits)
        return when (ranked.size) {
            0 -> MatchDecision.NotFound
            1 ->
                if (ranked[0].score >= 2) MatchDecision.Confident(ranked[0].candidate)
                else MatchDecision.Unsure(listOf(ranked[0].candidate))
            else -> MatchDecision.Unsure(ranked.take(MAX_CANDIDATES).map { it.candidate })
        }
    }

    /**
     * Search results in Hardcover's order. Rank by score, then order. Confident: best scores 4 and
     * every other result scores ≤ 2 or is a series sibling. Unsure: those scoring ≥ 2, top 3. Else
     * NotFound.
     */
    fun fromSearch(query: MatchQuery, results: List<MatchCandidate>): MatchDecision {
        val ranked = rank(query, results)
        confident(query, ranked)?.let {
            return MatchDecision.Confident(it)
        }
        val plausible = ranked.filter { it.score >= 2 }.take(MAX_CANDIDATES)
        return if (plausible.isEmpty()) MatchDecision.NotFound
        else MatchDecision.Unsure(plausible.map { it.candidate })
    }

    /** The owner's own Hardcover books: the single one scoring 4, else null. */
    fun fromLibrary(query: MatchQuery, library: List<MatchCandidate>): MatchCandidate? =
        library.distinctBy { it.hardcoverId }.filter { score(query, it) == 4 }.singleOrNull()

    /**
     * "<main title> <first usable author>", at most 300 characters; blank when the title is blank.
     * Accents and case are kept for Hardcover's search; "Last, First" becomes "First Last".
     */
    fun searchText(query: MatchQuery): String {
        val title = rawMainTitle(query.title.replace(trailingBrackets, "").ifBlank { query.title })
        val cleanTitle = title.replace(spaces, " ").trim()
        if (cleanTitle.isEmpty()) return ""
        val author =
            query.authors
                .firstOrNull { usable(normalizeAuthor(it)) }
                ?.let(::firstLast)
                ?.replace(spaces, " ")
                ?.trim()
                .orEmpty()
        return listOf(cleanTitle, author)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .take(MAX_SEARCH_TEXT)
            .trim()
    }

    /** SHA-256 hex of the normalised title, sorted normalised authors and sorted ISBNs. */
    fun metadataKey(title: String, authors: List<String>, isbns: List<String>): String {
        val text =
            listOf(
                    normalizeTitle(title),
                    authors
                        .map(::normalizeAuthor)
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .sorted()
                        .joinToString("|"),
                    isbns
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .sorted()
                        .joinToString("|"),
                )
                .joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
