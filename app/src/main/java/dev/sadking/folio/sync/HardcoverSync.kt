package dev.sadking.folio.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.HardcoverLinks
import dev.sadking.folio.data.WishBook
import dev.sadking.folio.device.HardcoverSettings
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sync with Hardcover (docs/HARDCOVER_SYNC.md): matching, statuses both ways, progress and Send
 * Finished, all through one queue of WorkManager runs ([HardcoverSyncWorker]) whose requests share
 * HardcoverClient's gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HardcoverSync private constructor(private val app: FolioApplication) {
    private val accountPrefs = app.getSharedPreferences(ACCOUNT_PREFS, Context.MODE_PRIVATE)
    private val outboxPrefs = app.getSharedPreferences(OUTBOX_PREFS, Context.MODE_PRIVATE)
    private val syncPrefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val settings = HardcoverSettings(app)
    internal val state: HardcoverRunState = HardcoverSyncPrefs(syncPrefs, outboxPrefs, settings)
    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> }
        )
    private val scheduling = Mutex()
    private val started = AtomicBoolean(false)
    private val store by lazy { HardcoverStore(app) }
    private val dao
        get() = app.database.library()

    private val coverDir
        get() = File(app.filesDir, CANDIDATE_COVERS)

    private val connected = MutableStateFlow(settings.connected)
    private val accountId = MutableStateFlow(settings.accountId)
    private val switchedOn = MutableStateFlow(state.enabled)
    private val saved = MutableStateFlow(Saved.read(syncPrefs))
    /** The run in progress, in this process (WorkManager runs in the app's process). */
    internal val progress = MutableStateFlow(HardcoverRunProgress())

    // SharedPreferences holds listeners weakly; these fields keep them alive.
    private val accountListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val was = connected.value
        connected.value = settings.connected
        accountId.value = settings.accountId
        if (connected.value && !was) scope.launch { ensurePeriodic() }
    }
    private val outboxListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == ENABLED) switchedOn.value = state.enabled
    }
    private val syncListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        saved.value = Saved.read(syncPrefs)
    }

    init {
        accountPrefs.registerOnSharedPreferenceChangeListener(accountListener)
        outboxPrefs.registerOnSharedPreferenceChangeListener(outboxListener)
        syncPrefs.registerOnSharedPreferenceChangeListener(syncListener)
    }

    /**
     * "Sync with Hardcover". Same preference as HardcoverOutbox.automatic (prefs
     * "hardcover-outbox", key "automatic", default true). Turning it on starts a sync.
     */
    var enabled: Boolean
        get() = state.enabled
        set(value) {
            outboxPrefs.edit().putBoolean(ENABLED, value).apply()
            switchedOn.value = value
            if (value) kick(pull = true)
        }

    private val records: Flow<Map<String, MatchRecord>> by lazy {
        store.matchRecords().map { list -> list.associateBy { it.itemId } }
    }

    /**
     * UNSURE records whose item still exists, is not removed and is still unlinked; newest first.
     */
    val checks: Flow<List<MatchCheck>> by lazy {
        combine(dao.storedBooks(), dao.storedWishes(), dao.removed(), records) {
            books,
            wishes,
            removed,
            records ->
            matchChecks(books, wishes, records, removed.map { it.id }.toSet())
        }
    }

    /** The status questions for the connected account. */
    val questions: Flow<List<StatusQuestion>> by lazy {
        combine(connected, accountId) { on, id -> if (on) id else 0 }
            .distinctUntilChanged()
            .flatMapLatest { id -> if (id > 0) store.questions(id) else flowOf(emptyList()) }
    }

    /** Book ids and catalogue entry ids → their tile state. */
    val itemStates: Flow<Map<String, HardcoverItemState>> by lazy {
        combine(dao.books(), dao.wishes(), records, connected, switchedOn) {
            books,
            wishes,
            records,
            on,
            enabled ->
            hardcoverItemStates(books, wishes, records, on, enabled)
        }
    }

    private val counts: Flow<Pair<Int, Int>> by lazy {
        connected.flatMapLatest { on ->
            if (!on) flowOf(0 to 0)
            else
                combine(dao.wishes(), checks, questions) { wishes, checks, questions ->
                    wishes.count { it.hardcoverId != null } to checks.size + questions.size
                }
        }
    }

    val status: StateFlow<HardcoverSyncStatus> by lazy {
        combine(connected, switchedOn, progress, saved, counts) { on, enabled, run, saved, counts ->
                HardcoverSyncStatus(
                    connected = on,
                    enabled = enabled,
                    running = run.running,
                    lastSyncAt = saved.lastSyncAt,
                    matched = counts.first,
                    toCheck = counts.second,
                    matchingDone = run.done,
                    matchingTotal = run.total,
                    problem = saved.problem,
                )
            }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5_000),
                HardcoverSyncStatus(
                    connected = connected.value,
                    enabled = switchedOn.value,
                    lastSyncAt = saved.value.lastSyncAt,
                    problem = saved.value.problem,
                ),
            )
    }

    /** Once, from FolioApplication.onCreate: the periodic work and the new-item watcher. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (settings.connected) scope.launch { ensurePeriodic() }
        scope.launch { watchNewItems() }
    }

    /**
     * Kicks a run without a pull whenever an unmatched item appears: import, Inbox, e-reader
     * upload, scan, search, restore. The first time it also starts (or resumes) the first pass. It
     * reads the library only while an account is connected and sync is on.
     */
    private suspend fun watchNewItems() {
        var before = emptySet<String>()
        combine(connected, switchedOn) { on, enabled -> on && enabled }
            .distinctUntilChanged()
            .flatMapLatest { on ->
                if (!on) flowOf(emptySet())
                else
                    combine(dao.books(), dao.wishes(), records) { books, wishes, records ->
                        matchItems(books, wishes, emptySet(), records).map { it.itemId }.toSet()
                    }
            }
            .collect { pending ->
                if ((pending - before).isNotEmpty()) kick(pull = false, delayMs = NEW_ITEM_DELAY_MS)
                before = pending
            }
    }

    /** The app came to the foreground: a pull when the last one is 15 min old or more. */
    fun onAppOpen() {
        if (!state.connected || !state.enabled) return
        if (System.currentTimeMillis() - state.lastPullAt >= PULL_EVERY_MS) kick(pull = true)
    }

    /**
     * Statuses of [ids] (books or catalogue entries) may have changed here or on an e-reader. Never
     * throws.
     */
    suspend fun statusChanged(ids: Collection<String>) {
        if (ids.isEmpty()) return
        try {
            if (!state.connected || !state.enabled) return
            val linked =
                withContext(Dispatchers.IO) {
                    dao.allWishes().any {
                        it.hardcoverId != null && (it.id in ids || it.linkedBookId in ids)
                    }
                }
            // A short wait turns a batch of changes into one run.
            if (linked) kick(pull = true, delayMs = STATUS_DELAY_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The status change itself has happened; the periodic pull will find it.
        }
    }

    /** Settings' "Sync now", and after connecting an account. */
    fun syncNow() = kick(pull = true)

    /**
     * "Check these matches": the owner picked [candidate]. Throws IllegalArgumentException with a
     * sentence to show, and HardcoverLinkTaken when the book is already matched to something else
     * and [move] is false (the same rules as the Hardcover sheet's Link).
     */
    suspend fun pick(itemId: String, candidate: MatchCandidateView, move: Boolean = false) {
        withContext(Dispatchers.IO) {
            HardcoverLinks(app.repository)
                .link(itemId, candidate.hardcoverId, candidate.title, candidate.authors, move)
            deleteCandidateCovers(coverDir, itemId)
        }
        kick(pull = true)
    }

    /** "Not on Hardcover": never matched or asked again for this item. */
    suspend fun notOnHardcover(itemId: String) =
        withContext(Dispatchers.IO) {
            val books = dao.allBooks()
            val wishes = dao.allStoredWishes()
            val query =
                wishes
                    .firstOrNull { it.id == itemId }
                    ?.let { wish ->
                        wishQuery(
                            wish,
                            wish.linkedBookId?.let { id -> books.firstOrNull { it.id == id } },
                        )
                    } ?: books.firstOrNull { it.id == itemId }?.let { bookQuery(itemId, it) }
            store.saveMatch(
                MatchRecord(
                    itemId,
                    MatchRecordState.NOT_ON_HARDCOVER,
                    emptyList(),
                    query?.key().orEmpty(),
                    System.currentTimeMillis(),
                )
            )
            deleteCandidateCovers(coverDir, itemId)
        }

    suspend fun answer(question: StatusQuestion, useHardcover: Boolean) {
        withContext(Dispatchers.IO) {
            HardcoverStatusSync(app).answer(settings.accountId, question, useHardcover)
        }
        kick(pull = true)
    }

    suspend fun answerAll(useHardcover: Boolean) {
        withContext(Dispatchers.IO) {
            val account = settings.accountId
            if (account <= 0) return@withContext
            val sync = HardcoverStatusSync(app)
            store.questions(account).first().forEach { sync.answer(account, it, useHardcover) }
        }
        kick(pull = true)
    }

    /** Queue a run. [pull]: read Hardcover's library first. */
    fun kick(pull: Boolean, delayMs: Long = 0) {
        if (!state.connected) return
        if (pull) state.pullRequested = true
        scope.launch { schedule(delayMs) }
    }

    /** The periodic worker: a pull, when connected and on. */
    internal suspend fun periodic() {
        if (!state.connected || !state.enabled) return
        state.pullRequested = true
        schedule(0)
    }

    /** One run of the queue, from [HardcoverSyncWorker]. */
    internal suspend fun run(): RunOutcome =
        HardcoverRun(state, AppRunSteps(app), report = { progress.value = it }).run()

    /**
     * Queues a run in [delayMs]. A run already waiting to start sooner covers it; one waiting
     * longer is replaced. During a run, one more run is queued after it.
     */
    internal suspend fun schedule(delayMs: Long) = scheduling.withLock {
        withContext(Dispatchers.IO) {
            try {
                val work = WorkManager.getInstance(app)
                val infos = work.getWorkInfosForUniqueWork(WORK).get()
                val at = System.currentTimeMillis() + delayMs
                val running = infos.any { it.state == WorkInfo.State.RUNNING }
                val waiting = infos.filter {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
                }
                if (
                    waiting.isNotEmpty() &&
                        (running ||
                            waiting.any {
                                it.state == WorkInfo.State.BLOCKED ||
                                    it.nextScheduleTimeMillis <= at
                            })
                )
                    return@withContext
                work.enqueueUniqueWork(
                    WORK,
                    if (running) ExistingWorkPolicy.APPEND_OR_REPLACE
                    else ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<HardcoverSyncWorker>()
                        .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                        .setConstraints(network())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                        .build(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // WorkManager is unavailable (tests): the next kick or app open tries again.
            }
        }
    }

    private suspend fun ensurePeriodic() =
        withContext(Dispatchers.IO) {
            try {
                WorkManager.getInstance(app)
                    .enqueueUniquePeriodicWork(
                        PERIODIC_WORK,
                        ExistingPeriodicWorkPolicy.KEEP,
                        PeriodicWorkRequestBuilder<HardcoverSyncPeriodicWorker>(
                                PERIODIC_HOURS,
                                TimeUnit.HOURS,
                            )
                            .setConstraints(network())
                            .build(),
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {}
        }

    private fun network() =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** What the last run left: when status sync finished, and a problem. */
    private data class Saved(val lastSyncAt: Long, val problem: HardcoverProblem?) {
        companion object {
            fun read(prefs: SharedPreferences) =
                Saved(prefs.getLong(LAST_SYNC_AT, 0), problemOf(prefs.getString(PROBLEM, null)))
        }
    }

    companion object {
        const val WORK = "hardcover-sync"
        const val PERIODIC_WORK = "hardcover-sync-periodic"
        const val PULL_EVERY_MS = 15 * 60_000L
        const val PERIODIC_HOURS = 4L
        const val RUN_BUDGET_MS = 8 * 60_000L
        internal const val STATUS_DELAY_MS = 5_000L
        internal const val NEW_ITEM_DELAY_MS = 10_000L
        internal const val PROGRESS_DELAY_MS = 60_000L

        internal const val PREFS = "hardcover-sync"
        internal const val PULL_REQUESTED = "pullRequested"
        internal const val LAST_PULL_AT = "lastPullAt"
        internal const val LAST_SYNC_AT = "lastSyncAt"
        internal const val PROBLEM = "problem"
        private const val OUTBOX_PREFS = "hardcover-outbox"
        private const val ENABLED = "automatic"
        /** HardcoverSettings' preferences: the token and the account id. */
        private const val ACCOUNT_PREFS = "hardcover"

        @Volatile private var instance: HardcoverSync? = null

        fun get(context: Context): HardcoverSync {
            val app = context.applicationContext as FolioApplication
            instance
                ?.takeIf { it.app === app }
                ?.let {
                    return it
                }
            return synchronized(this) {
                instance?.takeIf { it.app === app }
                    ?: HardcoverSync(app).also {
                        // A new Application (tests): the old one's watcher stops.
                        instance?.scope?.cancel()
                        instance = it
                    }
            }
        }

        internal fun problemOf(name: String?): HardcoverProblem? =
            HardcoverProblem.entries.firstOrNull { it.name == name }
    }
}

/** [HardcoverRunState] in SharedPreferences "hardcover-sync", with the account and the switch. */
internal class HardcoverSyncPrefs(
    private val prefs: SharedPreferences,
    private val outbox: SharedPreferences,
    private val settings: HardcoverSettings,
) : HardcoverRunState {
    override val connected: Boolean
        get() = settings.connected

    override val enabled: Boolean
        get() = outbox.getBoolean("automatic", true)

    override var pullRequested: Boolean
        get() = prefs.getBoolean(HardcoverSync.PULL_REQUESTED, false)
        set(value) = prefs.edit().putBoolean(HardcoverSync.PULL_REQUESTED, value).apply()

    override var lastPullAt: Long
        get() = prefs.getLong(HardcoverSync.LAST_PULL_AT, 0)
        set(value) = prefs.edit().putLong(HardcoverSync.LAST_PULL_AT, value).apply()

    override var lastSyncAt: Long
        get() = prefs.getLong(HardcoverSync.LAST_SYNC_AT, 0)
        set(value) = prefs.edit().putLong(HardcoverSync.LAST_SYNC_AT, value).apply()

    override var problem: HardcoverProblem?
        get() = HardcoverSync.problemOf(prefs.getString(HardcoverSync.PROBLEM, null))
        set(value) =
            prefs
                .edit()
                .apply {
                    if (value == null) remove(HardcoverSync.PROBLEM)
                    else putString(HardcoverSync.PROBLEM, value.name)
                }
                .apply()
}

/** Pure, for tests and the facade. */
fun hardcoverItemState(
    itemId: String,
    books: List<Book>,
    wishes: List<WishBook>,
    records: Map<String, MatchRecord>,
    connected: Boolean,
    enabled: Boolean,
): HardcoverItemState = ItemIndex(books, wishes).state(itemId, records, connected && enabled)

/** Every book and catalogue entry → its tile state. */
internal fun hardcoverItemStates(
    books: List<Book>,
    wishes: List<WishBook>,
    records: Map<String, MatchRecord>,
    connected: Boolean,
    enabled: Boolean,
): Map<String, HardcoverItemState> {
    val index = ItemIndex(books, wishes)
    return (books.map { it.id } + wishes.map { it.id }).associateWith {
        index.state(it, records, connected && enabled)
    }
}

private class ItemIndex(books: List<Book>, wishes: List<WishBook>) {
    private val books = books.associateBy { it.id }
    private val wishes = wishes.associateBy { it.id }
    private val linked =
        wishes
            .filter { it.hardcoverId != null }
            .flatMap { listOfNotNull(it.id, it.linkedBookId) }
            .toSet()
    private val entryOfBook =
        wishes
            .filter { it.linkedBookId != null }
            .groupBy { it.linkedBookId!! }
            .mapValues { it.value.first() }

    fun state(
        itemId: String,
        records: Map<String, MatchRecord>,
        live: Boolean,
    ): HardcoverItemState {
        if (itemId in linked) return HardcoverItemState.MATCHED
        val item = entryOfBook[itemId]?.takeIf { it.hardcoverId == null }?.id ?: itemId
        val record = records[item]
        val waiting = if (live) HardcoverItemState.MATCHING else HardcoverItemState.OFF
        return when (record?.state) {
            MatchRecordState.UNSURE -> HardcoverItemState.CHECK
            MatchRecordState.NOT_ON_HARDCOVER -> HardcoverItemState.NOT_ON_HARDCOVER
            MatchRecordState.NOT_FOUND ->
                if (query(item)?.key().let { it == null || it == record.metadataKey })
                    HardcoverItemState.NOT_FOUND
                else waiting
            null -> waiting
        }
    }

    private fun query(item: String) =
        wishes[item]?.let { wish -> wishQuery(wish, wish.linkedBookId?.let(books::get)) }
            ?: books[item]?.let { bookQuery(item, it) }
}
