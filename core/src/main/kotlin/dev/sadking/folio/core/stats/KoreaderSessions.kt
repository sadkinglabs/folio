package dev.sadking.folio.core.stats

import java.security.MessageDigest
import kotlin.math.floor

/**
 * KOReader's page statistics turned into reading sessions. The plugin runs the same steps in Lua
 * (`stats.lua`) and sends the sessions; this is the reference, and both are checked against the
 * shared cases in `core/src/test/resources/stats/koreader-sessions.json`. docs/STATS_DESIGN.md (a3)
 * has the rules in words.
 */
object KoreaderSessions {
    /** Time on one page counts up to 2 minutes, as KOReader's own default. */
    const val MAX_PAGE_SECONDS = 120L

    /** More than 5 minutes between one page's counted end and the next page ends a session. */
    const val GAP_SECONDS = 300L

    /** The plugin re-reads this much before the phone's watermark, to finish sessions. */
    const val LOOKBACK_SECONDS = 86_400L

    /** Sessions per request. */
    const val MAX_PER_REQUEST = 500

    /** Requests per sync; the rest go at the next sync. */
    const val MAX_REQUESTS = 40

    /**
     * One row of KOReader's `page_stat_data`, with its book's partial MD5: [page] shown from
     * [start] (epoch seconds) for [duration] seconds, out of [total] pages (0 when unknown).
     */
    data class PageEvent(
        val book: String,
        val page: Int,
        val start: Long,
        val duration: Long,
        val total: Int,
    )

    /**
     * A session as the plugin sends it. [from], [to] and [read] are rounded to 6 decimals, null
     * when no page count was known.
     */
    data class WireSession(
        val book: String,
        val start: Long,
        val seconds: Long,
        val pages: Int,
        val from: Double?,
        val to: Double?,
        val read: Double?,
    )

    private val MD5 = Regex("^[0-9a-f]{32}$")

    /**
     * Sessions from [events], the rows that start at or after the plugin's cut. [before] holds, per
     * book, the latest row before the cut (if any): a first session that continues from it began
     * before the cut, so it is left out rather than sent cut short.
     *
     * Steps, per book (rows whose book is not 32 hex characters after lowercasing, or with a page
     * under 1, a start of 0 or less or a duration of 0 or less, are ignored):
     * 1. Sort by start, then page. Rows with the same start and page are one: the longest.
     * 2. Each row counts `min(duration, 120)` seconds from its start; time a previous row of the
     *    same session already covered is not counted twice.
     * 3. A row that starts more than 300 seconds after the end of the counted time so far starts a
     *    new session.
     * 4. A session's `seconds` is its counted time; `pages` its distinct pages; `from` is `(first
     *    page - 1) / its total`; `to` is `last page / its total`; `read` adds `1 / total` for each
     *    distinct page the first time it is seen with a total. All clamped to 0..1, rounded to 6
     *    decimals; null when no row had a total.
     * 5. Sessions of 0 seconds are dropped. The result is sorted by start, then book.
     */
    fun derive(events: List<PageEvent>, before: List<PageEvent> = emptyList()): List<WireSession> {
        val previous =
            before
                .mapNotNull { clean(it) }
                .groupBy { it.book }
                .mapValues { (_, rows) -> rows.maxBy { it.start } }
        return events
            .mapNotNull { clean(it) }
            .groupBy { it.book }
            .flatMap { (book, rows) -> sessionsOf(book, rows, previous[book]) }
            .sortedWith(compareBy({ it.start }, { it.book }))
    }

    private fun clean(e: PageEvent): PageEvent? {
        val book = e.book.lowercase()
        if (!MD5.matches(book) || e.page < 1 || e.start <= 0 || e.duration <= 0) return null
        return e.copy(book = book, total = e.total.coerceAtLeast(0))
    }

    private class Building(val start: Long, var end: Long, val first: PageEvent) {
        var seconds = 0L
        var last = first
        val pages = HashSet<Int>()
        val counted = HashSet<Int>()
        var read = 0.0
        var anyTotal = false
        var truncated = false
    }

    private fun sessionsOf(
        book: String,
        input: List<PageEvent>,
        before: PageEvent?,
    ): List<WireSession> {
        val rows =
            input
                .groupBy { it.start to it.page }
                .values
                .map { same -> same.maxBy { it.duration } }
                .sortedWith(compareBy({ it.start }, { it.page }))
        val out = mutableListOf<WireSession>()
        var current: Building? = null
        // The end of the counted time of the row before the cut, if any.
        var beforeEnd = before?.let { it.start + minOf(it.duration, MAX_PAGE_SECONDS) }
        rows.forEach { row ->
            val counted = minOf(row.duration, MAX_PAGE_SECONDS)
            val session = current
            if (session == null || row.start - session.end > GAP_SECONDS) {
                session?.let { finish(book, it)?.let(out::add) }
                current =
                    Building(row.start, row.start, row).apply {
                        truncated = beforeEnd != null && row.start - beforeEnd!! <= GAP_SECONDS
                    }
                beforeEnd = null
            }
            val s = current!!
            val from = maxOf(row.start, s.end)
            val to = row.start + counted
            if (to > from) s.seconds += to - from
            s.end = maxOf(s.end, to)
            s.last = row
            s.pages += row.page
            if (row.total > 0) {
                s.anyTotal = true
                if (s.counted.add(row.page)) s.read += 1.0 / row.total
            }
        }
        current?.let { finish(book, it)?.let(out::add) }
        return out
    }

    private fun finish(book: String, s: Building): WireSession? {
        if (s.truncated || s.seconds <= 0) return null
        val firstTotal = s.first.total
        val lastTotal = s.last.total
        return WireSession(
            book = book,
            start = s.start,
            seconds = s.seconds,
            pages = s.pages.size,
            from = if (firstTotal > 0) round6((s.first.page - 1).toDouble() / firstTotal) else null,
            to = if (lastTotal > 0) round6(s.last.page.toDouble() / lastTotal) else null,
            read = if (s.anyTotal) round6(s.read) else null,
        )
    }

    /**
     * Clamped to 0..1 and rounded to 6 decimals, the same way in Lua: floor(x * 1e6 + 0.5) / 1e6.
     */
    fun round6(value: Double): Double = floor(value.coerceIn(0.0, 1.0) * 1e6 + 0.5) / 1e6

    /**
     * The session's stable id: `k` and the first 32 hex characters of SHA-256 of `deviceId + "\n"
     * + book + "\n" + start`. The same KOReader session sent again (grown or not) has the same id.
     */
    fun sessionId(deviceId: String, book: String, start: Long): String {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest("$deviceId\n${book.lowercase()}\n$start".toByteArray())
        return "k" + digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * [wire] from KOReader device [deviceId] (the plugin's `device.id`) as a stored session, its
     * book left as the document until the phone matches it ([deviceKey] is `ereader:<id>`).
     */
    fun toSession(deviceId: String, deviceKey: String, wire: WireSession): ReadingSession =
        ReadingSession(
            id = sessionId(deviceId, wire.book, wire.start),
            source = SessionSource.KINDLE,
            deviceKey = deviceKey,
            bookId = null,
            document = wire.book.lowercase(),
            startMs = wire.start * 1000,
            durationSec = wire.seconds,
            progressStart = wire.from,
            progressEnd = wire.to,
            progressRead = wire.read,
            pages = wire.pages,
        )

    /**
     * The phone's watermark for one e-reader: the latest `start + seconds` (epoch seconds) of its
     * stored KOReader sessions, 0 when none. The plugin re-derives from [LOOKBACK_SECONDS] before.
     */
    fun until(sessions: List<ReadingSession>): Long =
        sessions
            .filter { it.source == SessionSource.KINDLE }
            .maxOfOrNull { it.startMs / 1000 + it.durationSec } ?: 0L

    /** Where the plugin starts reading rows for watermark [until]: 0 (everything) when none. */
    fun cut(until: Long): Long = if (until <= 0) 0 else maxOf(0, until - LOOKBACK_SECONDS)

    /**
     * A received session the phone keeps, or the reason it's skipped: a book of 32 hex characters;
     * a start from 2007 to a day past [nowSec]; 1 to 86,400 seconds; 0 to 100,000 pages; fractions
     * null or 0..1.
     */
    fun accepts(wire: WireSession, nowSec: Long): Boolean =
        MD5.matches(wire.book.lowercase()) &&
            wire.start in EARLIEST_START..(nowSec + 86_400) &&
            wire.seconds in 1..MAX_SESSION_SECONDS &&
            wire.pages in 0..100_000 &&
            listOf(wire.from, wire.to, wire.read).all { it == null || it in 0.0..1.0 }

    /** 2007-01-01 UTC, before any KOReader. */
    const val EARLIEST_START = 1_167_609_600L
}
