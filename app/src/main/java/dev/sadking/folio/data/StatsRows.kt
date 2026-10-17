package dev.sadking.folio.data

import dev.sadking.folio.core.stats.ReadingSession
import dev.sadking.folio.core.stats.SessionSource

/** The stored session as the statistics use it. Null when its source name is unknown. */
fun ReadingSessionRow.toSession(): ReadingSession? {
    val known = SessionSource.entries.firstOrNull { it.name == source } ?: return null
    return ReadingSession(
        id = id,
        source = known,
        deviceKey = deviceKey,
        bookId = bookId,
        document = document,
        startMs = startMs,
        durationSec = durationSec,
        progressStart = progressStart,
        progressEnd = progressEnd,
        progressRead = progressRead,
        pages = pages,
    )
}

/** The row form of this session, written at [updatedAt] (epoch milliseconds). */
fun ReadingSession.toRow(updatedAt: Long) =
    ReadingSessionRow(
        id = id,
        source = source.name,
        deviceKey = deviceKey,
        bookId = bookId,
        document = document,
        startMs = startMs,
        durationSec = durationSec,
        progressStart = progressStart,
        progressEnd = progressEnd,
        progressRead = progressRead,
        pages = pages,
        updatedAt = updatedAt,
    )

/**
 * Saves each session, except one whose stored copy with the same id is longer: a session only ever
 * grows. Returns how many rows were written. Nothing is deleted.
 */
suspend fun StatsDao.keepSessions(sessions: List<ReadingSession>, now: Long): Int {
    val stored = sessionsById(sessions.map { it.id }).associateBy { it.id }
    val rows =
        sessions
            .filter { session -> (stored[session.id]?.durationSec ?: 0L) <= session.durationSec }
            .map { it.toRow(now) }
    if (rows.isNotEmpty()) upsertSessions(rows)
    return rows.size
}
