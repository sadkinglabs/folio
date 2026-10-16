package dev.sadking.folio.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.sadking.folio.data.HardcoverHttpException
import dev.sadking.folio.data.HardcoverObservation
import dev.sadking.folio.data.HardcoverReading
import dev.sadking.folio.data.LibraryDao
import dev.sadking.folio.data.estimatedHardcoverPage
import dev.sadking.folio.device.HardcoverSettings
import dev.sadking.folio.reader.koreaderDigest
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What to do with one queued position, given what Hardcover has now. */
sealed interface OutboxStep {
    data class Send(val page: Int) : OutboxStep

    /** Hardcover is already at or past this page; nothing to send. */
    data object AlreadyThere : OutboxStep

    /** Folio will not decide on its own; the review sheet can still send it. */
    data class NeedsReview(val reason: Reason) : OutboxStep

    enum class Reason {
        NOT_LINKED,
        NOT_READING,
        NO_EDITION,
    }
}

/**
 * Decides an automatic update. It only moves an existing "Currently Reading" read forward, on the
 * edition that read already uses. It never adds books, starts reads or goes backwards.
 */
fun outboxStep(fraction: Double, reading: HardcoverReading, editionPages: Int?): OutboxStep {
    if (reading.userBookId == null) return OutboxStep.NeedsReview(OutboxStep.Reason.NOT_LINKED)
    if (reading.status != 2 || reading.readId == null)
        return OutboxStep.NeedsReview(OutboxStep.Reason.NOT_READING)
    if (reading.editionId == null || editionPages == null || editionPages <= 0)
        return OutboxStep.NeedsReview(OutboxStep.Reason.NO_EDITION)
    val page = estimatedHardcoverPage(fraction.coerceIn(0.0, 1.0) * 100, editionPages)
    return if (reading.pages != null && page <= reading.pages) OutboxStep.AlreadyThere
    else OutboxStep.Send(page)
}

/**
 * Keeps the phone's copy of what Hardcover says about book [hardcoverId] for [account] current, as
 * the reading sheet does: its status, pages and edition, or nothing when the book is not in the
 * account's library.
 */
internal suspend fun recordObservation(
    dao: LibraryDao,
    account: Int,
    hardcoverId: Int,
    reading: HardcoverReading,
    at: Long,
) {
    if (reading.userBookId != null && reading.status != null)
        dao.hardcoverObservation(
            HardcoverObservation(
                account,
                hardcoverId,
                reading.status,
                reading.pages,
                reading.editionId,
                at,
            )
        )
    else dao.deleteHardcoverObservation(account, hardcoverId)
}

/**
 * Reading positions waiting to go to Hardcover: one per book, the latest wins. A phone position is
 * keyed by book; an e-reader's Folio Link position by KOReader's document key, which the run
 * matches to a book. Also the explicit finishes of Settings' Send Finished books. Stored on the
 * phone, so the queue survives restarts. They are sent by the one Hardcover queue ([HardcoverSync],
 * docs/HARDCOVER_SYNC.md), after status sync.
 */
class HardcoverOutbox(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("hardcover-outbox", Context.MODE_PRIVATE)

    /** "Sync with Hardcover" ([HardcoverSync.enabled] is the same preference). */
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) = prefs.edit().putBoolean("automatic", value).apply()

    fun queueBook(bookId: String, fraction: Double) = queue("book:$bookId", fraction)

    fun queueDocument(document: String, fraction: Double) = queue("document:$document", fraction)

    private fun queue(key: String, fraction: Double) {
        if (!automatic || !fraction.isFinite() || fraction !in 0.0..1.0) return
        if (!HardcoverSettings(app).connected) return
        prefs
            .edit()
            .putString(
                "entry:$key",
                JSONObject()
                    .put("fraction", fraction)
                    .put("queuedAt", System.currentTimeMillis())
                    .toString(),
            )
            .apply()
        schedule()
    }

    /**
     * Mark [id] (a book, or a catalogue entry) Read on Hardcover, finished on [finishedOn], or with
     * no date for a book read long ago (null). Settings' Send Finished books queues these as
     * [explicit] requests, sent even with "Sync with Hardcover" off. Everyday status changes go
     * through status sync instead (HardcoverStatusSync).
     */
    fun queueFinish(id: String, finishedOn: java.time.LocalDate?, explicit: Boolean = false) {
        if ((!automatic && !explicit) || !HardcoverSettings(app).connected) return
        prefs.edit().putString("finish:$id", finishedOn?.toString().orEmpty()).apply()
        schedule()
    }

    /** Finishes waiting to go to Hardcover: id and finish date (null: no date). */
    fun finishes(): List<Pair<String, java.time.LocalDate?>> =
        prefs.all
            .filterKeys { it.startsWith("finish:") }
            .mapNotNull { (key, value) ->
                runCatching {
                        val day = (value as String).takeIf { it.isNotEmpty() }
                        key.removePrefix("finish:") to day?.let(java.time.LocalDate::parse)
                    }
                    .getOrNull()
            }
            .sortedBy { it.first }

    /** Removes the finish for [id] unless it was queued again with another date meanwhile. */
    fun finishDone(id: String, finishedOn: java.time.LocalDate?) {
        if (prefs.getString("finish:$id", null) == finishedOn?.toString().orEmpty())
            prefs.edit().remove("finish:$id").apply()
    }

    // Waiting a minute turns a run of page turns into one update.
    private fun schedule() =
        HardcoverSync.get(app).kick(pull = false, delayMs = HardcoverSync.PROGRESS_DELAY_MS)

    /** Queued keys and fractions, oldest first. */
    fun entries(): List<Pair<String, Double>> =
        prefs.all
            .filterKeys { it.startsWith("entry:") }
            .mapNotNull { (key, value) ->
                runCatching {
                        val o = JSONObject(value as String)
                        Triple(
                            key.removePrefix("entry:"),
                            o.getDouble("fraction"),
                            o.getLong("queuedAt"),
                        )
                    }
                    .getOrNull()
            }
            .sortedBy { it.third }
            .map { it.first to it.second }

    /** Removes [key] unless a newer position was queued while it was being sent. */
    fun done(key: String, fraction: Double) {
        val stored = prefs.getString("entry:$key", null) ?: return
        if (runCatching { JSONObject(stored).getDouble("fraction") }.getOrNull() == fraction)
            prefs.edit().remove("entry:$key").apply()
    }

    /** Whether positions (when [automatic]) or finishes wait to be sent. */
    fun hasWork(): Boolean = (automatic && entries().isNotEmpty()) || finishes().isNotEmpty()

    /**
     * Sends the queued positions, when [automatic]. A position that Hardcover cannot take without a
     * person (see [outboxStep]), or for a book not linked to Hardcover, is dropped. Throws on a
     * rate limit, a server error, a rejected token or no connection, keeping that position and the
     * later ones queued.
     */
    internal suspend fun sendPositions(
        api: ClientHardcoverApi,
        dao: LibraryDao,
        now: () -> Long = System::currentTimeMillis,
    ) =
        withContext(Dispatchers.IO) {
            if (!automatic) return@withContext
            val queued = entries()
            if (queued.isEmpty()) return@withContext
            val books = dao.allBooks()
            val wishes = dao.allWishes()
            val client = api.client
            val token = api.token
            val user = api.accountId
            for ((key, fraction) in queued) {
                val bookId =
                    when {
                        key.startsWith("book:") -> key.removePrefix("book:")
                        else -> {
                            val document = key.removePrefix("document:")
                            books
                                .firstOrNull {
                                    runCatching { koreaderDigest(File(it.currentPath)) }
                                        .getOrNull() == document
                                }
                                ?.id
                        }
                    }
                val hardcoverId =
                    wishes.firstOrNull { it.linkedBookId == bookId && bookId != null }?.hardcoverId
                if (hardcoverId == null) {
                    // Not linked to Hardcover: there is nowhere to send it.
                    done(key, fraction)
                    continue
                }
                needsPerson({ done(key, fraction) }) {
                    val reading = client.reading(token, hardcoverId, user)
                    val pages = reading.editionId?.let { client.editionPages(token, it) }
                    val step = outboxStep(fraction, reading, pages)
                    if (step is OutboxStep.Send)
                        client.progress(
                            token,
                            hardcoverId,
                            reading.editionId!!,
                            step.page,
                            reading,
                            allowNewRead = false,
                            allowDecrease = false,
                            userId = user,
                        )
                    recordObservation(
                        dao,
                        user,
                        hardcoverId,
                        if (step is OutboxStep.Send) reading.copy(pages = step.page) else reading,
                        now(),
                    )
                    done(key, fraction)
                }
            }
        }

    /**
     * Sends the queued finishes (Send Finished books), whatever [automatic] says: the owner asked
     * for them. Errors as [sendPositions].
     */
    internal suspend fun sendFinishes(
        api: ClientHardcoverApi,
        dao: LibraryDao,
        now: () -> Long = System::currentTimeMillis,
    ) =
        withContext(Dispatchers.IO) {
            val queued = finishes()
            if (queued.isEmpty()) return@withContext
            val wishes = dao.allWishes()
            for ((id, finishedOn) in queued) {
                val hardcoverId =
                    wishes
                        .firstOrNull {
                            (it.linkedBookId == id || it.id == id) && it.hardcoverId != null
                        }
                        ?.hardcoverId
                if (hardcoverId == null) {
                    finishDone(id, finishedOn)
                    continue
                }
                // Hardcover's state needs a person (two entries, two open reads): the book's
                // Hardcover sheet still offers Finish.
                needsPerson({ finishDone(id, finishedOn) }) {
                    api.client.markRead(api.token, hardcoverId, finishedOn, api.accountId)
                    recordObservation(
                        dao,
                        api.accountId,
                        hardcoverId,
                        api.client.reading(api.token, hardcoverId, api.accountId),
                        now(),
                    )
                    finishDone(id, finishedOn)
                }
            }
        }

    companion object {
        /** The queue's work name before M10; [HardcoverOutboxWorker] still answers to it. */
        const val WORK = "hardcover-outbox"
    }
}

/**
 * Runs [send]. When Hardcover's answer needs a person, or is a client error, calls [drop] instead
 * of failing. Rate limits, server errors, a rejected token and no connection are thrown.
 */
private inline fun needsPerson(drop: () -> Unit, send: () -> Unit) {
    try {
        send()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HardcoverHttpException) {
        if (e.transient || e.unauthorized) throw e
        drop()
    } catch (e: IllegalArgumentException) {
        drop()
    } catch (e: IllegalStateException) {
        drop()
    }
}

/**
 * Work queued as "hardcover-outbox" by the version before M10. It hands over to the one Hardcover
 * queue ([HardcoverSync.WORK]).
 */
class HardcoverOutboxWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        HardcoverSync.get(applicationContext).schedule(0)
        return Result.success()
    }
}
