package dev.sadking.folio.reader

import dev.sadking.folio.core.stats.ReadingSession
import dev.sadking.folio.core.stats.SessionSource
import dev.sadking.folio.core.stats.SessionTracker
import dev.sadking.folio.data.StatsDao
import dev.sadking.folio.data.toRow
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Records the phone's reading and listening sessions from what the reader and read-aloud report.
 * The rules are [SessionTracker]'s; this class gives them the times and saves what they return.
 * Saves go to [dao] in the order they were made. Call it from the main thread. It never holds book
 * text: only ids, times and positions.
 */
internal class SessionRecorder(
    private val dao: StatsDao,
    private val scope: CoroutineScope,
    private val deviceKey: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val reading =
        SessionTracker(SessionSource.PHONE, deviceKey) { UUID.randomUUID().toString() }
    private val listening =
        SessionTracker(SessionSource.LISTEN, deviceKey) { UUID.randomUUID().toString() }

    /** One write at a time, in order. */
    private val writes = Dispatchers.IO.limitedParallelism(1)

    /** The reader is on screen. */
    private var visible = false
    private var bookId: String? = null
    private var lastProgress: Double? = null

    /** The owner jumped; the next settled page does not count as reading on. */
    private var jumpPending = false

    /** Read-aloud is playing: reading does not count while it plays. */
    private var playing = false

    /** The reader's book is on screen at [progress], from now. */
    fun shown(bookId: String, progress: Double?) {
        visible = true
        this.bookId = bookId
        lastProgress = progress
        if (!playing) save(reading.start(bookId, clock(), progress))
    }

    /** The reader's book is off screen. */
    fun hidden() {
        visible = false
        save(reading.stop(clock()))
    }

    /** A settled page at [progress]. */
    fun page(progress: Double?) {
        lastProgress = progress
        if (!playing) {
            save(reading.mark(clock(), progress, jumpPending))
            jumpPending = false
        }
    }

    /** The owner moved to another place (contents, search, a link, the slider or a bookmark). */
    fun jumped() {
        jumpPending = true
    }

    /** Read-aloud started playing [bookId] at [progress]. Reading stops; listening starts. */
    fun playing(bookId: String, progress: Double?) {
        playing = true
        val now = clock()
        save(reading.stop(now))
        save(listening.start(bookId, now, progress))
    }

    /** Read-aloud began a sentence at [progress]. */
    fun utterance(progress: Double?) {
        save(listening.mark(clock(), progress))
    }

    /** Read-aloud stopped playing. Reading resumes when the reader is still on screen. */
    fun stopped() {
        playing = false
        save(listening.stop(clock()))
        val book = bookId
        if (visible && book != null) save(reading.start(book, clock(), lastProgress))
    }

    private fun save(session: ReadingSession?) {
        session ?: return
        scope.launch(writes) {
            try {
                dao.upsertSessions(listOf(session.toRow(clock())))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // The session's next save writes it again: a stored session only ever grows.
            }
        }
    }
}
