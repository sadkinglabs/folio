package dev.sadking.folio.core.stats

/**
 * Records the phone's sessions from what the reader or the read-aloud player reports. Pure: the
 * caller gives every time, and saves (upserts by id) each session it returns. One tracker per
 * source: [SessionSource.PHONE] for reading, [SessionSource.LISTEN] for listening.
 *
 * Reading ([SessionSource.PHONE]), as KOReader counts it:
 * - [start] when a book's pages show (the reader opens or comes back), [mark] at each settled page,
 *   [stop] when the reader pauses, closes or read-aloud starts.
 * - Time on a page counts up to [KoreaderSessions.MAX_PAGE_SECONDS]. A page held longer than that
 *   plus [KoreaderSessions.GAP_SECONDS] ends the session at that page; the next page starts a new
 *   one.
 *
 * Listening ([SessionSource.LISTEN]): [start] when playback plays, [mark] at each new sentence,
 * [stop] when it pauses or stops. All playing time counts.
 *
 * Both: a [start] on the same book within [KoreaderSessions.GAP_SECONDS] of the last [stop]
 * continues that session (same id, the paused time not counted). A forward step of at most
 * [MAX_STEP] of the book that is not a jump adds to [ReadingSession.progressRead]; anything else
 * (back, a jump, a bigger step) only moves the position.
 */
class SessionTracker(
    private val source: SessionSource,
    private val deviceKey: String,
    private val newId: () -> String,
) {
    private val capMs: Long? =
        if (source == SessionSource.LISTEN) null else KoreaderSessions.MAX_PAGE_SECONDS * 1000

    private class Open(
        val id: String,
        val bookId: String,
        val startMs: Long,
        val progressStart: Double?,
    ) {
        var countedMs = 0L
        var progressEnd: Double? = progressStart
        var read = 0.0
        var anyRead = false
        /** The last mark while running; null while stopped. */
        var lastMs: Long? = null
        var stoppedAt = 0L
    }

    private var open: Open? = null

    /** The session as it stands, or null when none has counted time yet. */
    val current: ReadingSession?
        get() = open?.let(::snapshot)

    /**
     * [bookId] is on screen (or playing) from [atMs], at [progress]. Returns the session to save
     * when starting ended another one.
     */
    fun start(bookId: String, atMs: Long, progress: Double?): ReadingSession? {
        val o = open
        if (o != null && o.bookId == bookId) {
            if (o.lastMs != null) return null
            if (atMs - o.stoppedAt in 0..GAP_MS) {
                o.lastMs = atMs
                move(o, progress, jump = true)
                return null
            }
        }
        val ended = if (o != null) stop(atMs) else null
        open = Open(newId(), bookId, atMs, progress?.coerceIn(0.0, 1.0)).apply { lastMs = atMs }
        return ended
    }

    /**
     * A settled page (or a new sentence) at [atMs], at [progress]. [jump] when the owner moved
     * there by contents, search, a link, the slider or a bookmark. Returns the session to save, or
     * null when nothing changed. Ignored while stopped.
     */
    fun mark(atMs: Long, progress: Double?, jump: Boolean = false): ReadingSession? {
        val o = open ?: return null
        val last = o.lastMs ?: return null
        val elapsed = (atMs - last).coerceAtLeast(0)
        val cap = capMs
        if (cap != null && elapsed - minOf(elapsed, cap) > GAP_MS) {
            // The page was left alone: the session ended there, and a new one starts here.
            o.countedMs += cap
            val ended = snapshot(o)
            open =
                Open(newId(), o.bookId, atMs, progress?.coerceIn(0.0, 1.0)).apply { lastMs = atMs }
            return ended
        }
        o.countedMs += if (cap != null) minOf(elapsed, cap) else elapsed
        o.lastMs = atMs
        move(o, progress, jump)
        return snapshot(o)
    }

    /** Reading or playing stopped at [atMs]. Returns the session to save, or null if none. */
    fun stop(atMs: Long): ReadingSession? {
        val o = open ?: return null
        val last = o.lastMs ?: return null
        val elapsed = (atMs - last).coerceAtLeast(0)
        o.countedMs += capMs?.let { minOf(elapsed, it) } ?: elapsed
        o.lastMs = null
        o.stoppedAt = atMs
        return snapshot(o)
    }

    private fun move(o: Open, progress: Double?, jump: Boolean) {
        val to = progress?.coerceIn(0.0, 1.0) ?: return
        val from = o.progressEnd
        if (!jump && from != null && to > from && to - from <= MAX_STEP) {
            o.read += to - from
            o.anyRead = true
        }
        o.progressEnd = to
    }

    private fun snapshot(o: Open): ReadingSession? {
        val seconds = o.countedMs / 1000
        if (seconds < 1) return null
        return ReadingSession(
            id = o.id,
            source = source,
            deviceKey = deviceKey,
            bookId = o.bookId,
            document = null,
            startMs = o.startMs,
            durationSec = seconds,
            progressStart = o.progressStart,
            progressEnd = o.progressEnd,
            progressRead = if (o.anyRead) KoreaderSessions.round6(o.read) else null,
            pages = null,
        )
    }

    companion object {
        /** The largest forward step that counts as reading on: 5% of the book. */
        const val MAX_STEP = 0.05

        private const val GAP_MS = KoreaderSessions.GAP_SECONDS * 1000
    }
}
