package dev.sadking.folio.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.core.hardcover.HardcoverMatching
import dev.sadking.folio.core.hardcover.MatchCandidate
import dev.sadking.folio.data.HardcoverAccount
import dev.sadking.folio.data.HardcoverClient
import dev.sadking.folio.data.HardcoverHttpException
import dev.sadking.folio.data.HardcoverMatch
import dev.sadking.folio.device.HardcoverSettings
import java.io.IOException
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** [HardcoverApi] over [HardcoverClient] for one token and account. */
class ClientHardcoverApi(
    internal val client: HardcoverClient,
    internal val token: String,
    private val account: HardcoverAccount,
) : HardcoverApi {
    override val accountId: Int
        get() = account.id

    override fun libraryPage(after: Int): List<RemoteUserBook> =
        client.libraryPage(token, account.id, after).map {
            RemoteUserBook(
                it.userBookId,
                it.book.id,
                it.status,
                it.updatedAt,
                it.book.title,
                it.book.authors,
                it.book.releaseYear,
                it.book.coverUrl,
                it.pages,
                it.editionId,
            )
        }

    override fun setStatus(hardcoverId: Int, status: Int, userBookId: Int?): Int =
        client.setStatus(token, hardcoverId, status, userBookId, account.privacy)

    override fun markRead(hardcoverId: Int, finishedOn: LocalDate?) {
        client.markRead(token, hardcoverId, finishedOn, account.id)
    }

    override fun editionsByIsbn(isbns: List<String>): List<IsbnHit> =
        client.editionsByIsbn(token, isbns).flatMap { hit ->
            val candidate = hit.book.candidate()
            listOfNotNull(hit.isbn13, hit.isbn10)
                .mapNotNull(HardcoverMatching::isbn13)
                .distinct()
                .map { IsbnHit(it, candidate) }
        }

    override fun search(text: String): List<MatchCandidate> =
        client.search(token, text).map { it.candidate() }

    private fun HardcoverMatch.candidate() =
        MatchCandidate(id, title, authors, releaseYear, coverUrl)
}

/** What the run reads and keeps between runs. [HardcoverSync] keeps it in SharedPreferences. */
internal interface HardcoverRunState {
    val connected: Boolean
    val enabled: Boolean
    var pullRequested: Boolean
    var lastPullAt: Long
    var lastSyncAt: Long
    var problem: HardcoverProblem?
}

/** What a run in progress shows: Settings' "Matching your library… done of total". */
internal data class HardcoverRunProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
)

/** The pieces a run calls, in the order of docs/HARDCOVER_SYNC.md B4. Tests replace them. */
internal interface HardcoverRunSteps {
    /** Whether anything waits. No requests. */
    suspend fun due(enabled: Boolean, pullRequested: Boolean): Boolean

    /** Reads the token and checks the account (1 request). */
    suspend fun connect(): HardcoverApi

    suspend fun pending(): Int

    suspend fun fromLibrary(library: List<RemoteUserBook>): Int

    suspend fun syncStatuses(
        api: HardcoverApi,
        library: List<RemoteUserBook>,
        createEntries: Boolean,
    ): StatusSyncResult

    suspend fun sendProgress(api: HardcoverApi)

    suspend fun sendFinishes(api: HardcoverApi)

    suspend fun matchBatch(
        api: HardcoverApi,
        deadline: Long,
        progress: (done: Int, total: Int) -> Unit,
    ): Int
}

internal sealed interface RunOutcome {
    data object Done : RunOutcome

    /** No connection or a server error: WorkManager tries again with backoff. */
    data object Retry : RunOutcome

    /** Stopped early (time budget or "slow down"): queue another run in [delayMs]. */
    data class Again(val delayMs: Long) : RunOutcome
}

/**
 * One pass of the Hardcover queue: pull, matching from the owner's library, status sync, progress,
 * Send Finished, then ISBN and search matching. At most [MAX_ROUNDS] rounds and [budgetMs].
 */
internal class HardcoverRun(
    private val state: HardcoverRunState,
    private val steps: HardcoverRunSteps,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val report: (HardcoverRunProgress) -> Unit = {},
    private val budgetMs: Long = HardcoverSync.RUN_BUDGET_MS,
) {
    suspend fun run(): RunOutcome {
        if (!state.connected) return RunOutcome.Done
        // A run with nothing to do makes no request; a problem is checked again once.
        if (state.problem == null && !steps.due(state.enabled, state.pullRequested))
            return RunOutcome.Done
        val deadline = now() + budgetMs
        report(HardcoverRunProgress(running = true))
        try {
            val api = steps.connect()
            state.problem = null
            var rounds = 0
            var waits = 0
            while (rounds < MAX_ROUNDS) {
                try {
                    round(api, deadline)
                } catch (e: HardcoverHttpException) {
                    if (e.code != 429) throw e
                    val wait = e.retryAfterMs ?: HardcoverClient.DEFAULT_RETRY_AFTER_MS
                    if (now() + wait >= deadline || ++waits > MAX_WAITS)
                        return RunOutcome.Again(wait)
                    sleep(wait)
                    continue
                }
                rounds++
                if (now() >= deadline) return RunOutcome.Again(0)
                if (!(state.enabled && state.pullRequested)) break
            }
            return RunOutcome.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: HardcoverHttpException) {
            return when {
                e.code == 429 ->
                    RunOutcome.Again(e.retryAfterMs ?: HardcoverClient.DEFAULT_RETRY_AFTER_MS)
                e.unauthorized -> {
                    state.problem = HardcoverProblem.TOKEN_REJECTED
                    RunOutcome.Done
                }
                e.transient -> {
                    state.problem = HardcoverProblem.OFFLINE
                    RunOutcome.Retry
                }
                else -> RunOutcome.Done
            }
        } catch (e: IOException) {
            state.problem = HardcoverProblem.OFFLINE
            return RunOutcome.Retry
        } catch (e: Exception) {
            // Hardcover's answer needs a person, or the token cannot be read: stop quietly. What
            // was saved stays saved; the next kick starts again.
            return RunOutcome.Done
        } finally {
            report(HardcoverRunProgress())
        }
    }

    private suspend fun round(api: HardcoverApi, deadline: Long) {
        if (state.enabled && state.pullRequested) {
            // Cleared first: a change during the pull asks for another round.
            state.pullRequested = false
            try {
                val library = pull(api)
                state.lastPullAt = now()
                steps.fromLibrary(library)
                steps.syncStatuses(api, library, createEntries = steps.pending() == 0)
                state.lastSyncAt = now()
            } catch (e: Exception) {
                val needsPerson =
                    e !is CancellationException &&
                        (e is IllegalArgumentException || e is IllegalStateException) &&
                        !(e is HardcoverHttpException && (e.transient || e.unauthorized))
                // An answer that needs a person: statuses wait for the next pull. Anything else
                // (slow down, no connection, a rejected token) pulls again next time.
                if (!needsPerson) {
                    state.pullRequested = true
                    throw e
                }
            }
        }
        if (state.enabled) steps.sendProgress(api)
        steps.sendFinishes(api)
        if (state.enabled) {
            val linked =
                steps.matchBatch(api, deadline) { done, total ->
                    report(HardcoverRunProgress(running = true, done = done, total = total))
                }
            if (linked > 0) state.pullRequested = true
            report(HardcoverRunProgress(running = true))
        }
    }

    /** Every book in the account's library, 50 per request. */
    private fun pull(api: HardcoverApi): List<RemoteUserBook> {
        val rows = ArrayList<RemoteUserBook>()
        var after = 0
        repeat(MAX_PAGES) {
            val page = api.libraryPage(after)
            check(page.all { it.userBookId > after }) { "Hardcover pagination changed" }
            rows += page
            if (page.size < PAGE_SIZE) return rows
            after = page.last().userBookId
        }
        error("The Hardcover library is too large to sync")
    }

    companion object {
        const val MAX_ROUNDS = 3
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 200
        /** "Slow down" answers waited out within one run before handing over to a later one. */
        const val MAX_WAITS = 5
    }
}

/** The steps of a real run. */
internal class AppRunSteps(private val app: FolioApplication) : HardcoverRunSteps {
    private val settings = HardcoverSettings(app)
    private val matcher by lazy { HardcoverMatcher(app) }
    private val statuses by lazy { HardcoverStatusSync(app) }
    private val dao
        get() = app.database.library()

    override suspend fun due(enabled: Boolean, pullRequested: Boolean): Boolean =
        app.hardcoverOutbox.hasWork() ||
            (enabled && (pullRequested || matcher.pending().isNotEmpty()))

    override suspend fun connect(): HardcoverApi =
        withContext(Dispatchers.IO) {
            val token = settings.token()
            val client = HardcoverClient()
            val account = client.account(token)
            if (account.id != settings.accountId) settings.accountId = account.id
            ClientHardcoverApi(client, token, account)
        }

    override suspend fun pending(): Int = matcher.pending().size

    override suspend fun fromLibrary(library: List<RemoteUserBook>): Int =
        matcher.fromLibrary(library)

    override suspend fun syncStatuses(
        api: HardcoverApi,
        library: List<RemoteUserBook>,
        createEntries: Boolean,
    ): StatusSyncResult = statuses.sync(api, library, createEntries)

    override suspend fun sendProgress(api: HardcoverApi) =
        app.hardcoverOutbox.sendPositions(api as ClientHardcoverApi, dao)

    override suspend fun sendFinishes(api: HardcoverApi) =
        app.hardcoverOutbox.sendFinishes(api as ClientHardcoverApi, dao)

    override suspend fun matchBatch(
        api: HardcoverApi,
        deadline: Long,
        progress: (done: Int, total: Int) -> Unit,
    ): Int = matcher.matchBatch(api, deadline, progress)
}

/** The one Hardcover queue ([HardcoverSync.WORK]). */
class HardcoverSyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val sync = HardcoverSync.get(applicationContext)
            when (val outcome = sync.run()) {
                RunOutcome.Done -> Result.success()
                RunOutcome.Retry -> Result.retry()
                is RunOutcome.Again -> {
                    sync.schedule(outcome.delayMs)
                    Result.success()
                }
            }
        }
}

/** Every [HardcoverSync.PERIODIC_HOURS] hours: asks for a pull and queues a run. */
class HardcoverSyncPeriodicWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        HardcoverSync.get(applicationContext).periodic()
        return Result.success()
    }
}
