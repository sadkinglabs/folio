package dev.sadking.folio.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.data.HardcoverClient
import dev.sadking.folio.data.HardcoverHttpException
import dev.sadking.folio.data.HardcoverReading
import dev.sadking.folio.data.estimatedHardcoverPage
import dev.sadking.folio.device.HardcoverSettings
import dev.sadking.folio.reader.koreaderDigest
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
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
 * Reading positions waiting to go to Hardcover: one per book, the latest wins. A phone position is
 * keyed by book; an e-reader's Folio Link position by KOReader's document key, which the worker
 * matches to a book. Stored on the phone, so the queue survives restarts.
 */
class HardcoverOutbox(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("hardcover-outbox", Context.MODE_PRIVATE)

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
        // Waiting a minute turns a run of page turns into one update.
        WorkManager.getInstance(app)
            .enqueueUniqueWork(
                WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<HardcoverOutboxWorker>()
                    .setInitialDelay(1, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build(),
            )
    }

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

    companion object {
        const val WORK = "hardcover-outbox"
    }
}

class HardcoverOutboxWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val app = applicationContext as FolioApplication
            val outbox = app.hardcoverOutbox
            val settings = HardcoverSettings(app)
            if (!outbox.automatic || !settings.connected) return@withContext Result.success()
            val token = settings.token()
            // The account id saved at connect time saves one request per book.
            val user = settings.accountId.takeIf { it > 0 }
            val client = HardcoverClient()
            val dao = app.database.library()
            val books = dao.allBooks()
            val wishes = dao.allWishes()
            var retry = false
            for ((key, fraction) in outbox.entries()) {
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
                    outbox.done(key, fraction)
                    continue
                }
                try {
                    val reading = client.reading(token, hardcoverId, user)
                    val pages = reading.editionId?.let { client.editionPages(token, it) }
                    when (val step = outboxStep(fraction, reading, pages)) {
                        is OutboxStep.Send ->
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
                        else -> Unit
                    }
                    outbox.done(key, fraction)
                } catch (e: IOException) {
                    retry = true
                } catch (e: HardcoverHttpException) {
                    if (e.transient) retry = true
                    // A rejected token stops everything; the queue waits for a reconnect.
                    else if (e.unauthorized) return@withContext Result.success()
                    else outbox.done(key, fraction)
                } catch (e: IllegalArgumentException) {
                    // Hardcover's state needs a person; the review sheet still offers it.
                    outbox.done(key, fraction)
                } catch (e: IllegalStateException) {
                    outbox.done(key, fraction)
                }
            }
            if (retry) Result.retry() else Result.success()
        }
}
