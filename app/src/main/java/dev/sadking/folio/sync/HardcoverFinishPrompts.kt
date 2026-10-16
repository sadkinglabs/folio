package dev.sadking.folio.sync

import android.content.Context
import android.content.SharedPreferences
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.data.LibraryDao
import dev.sadking.folio.device.HardcoverSettings
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** "You finished [title]. Mark it as read on Hardcover?" for book [bookId]. */
data class FinishPrompt(val bookId: String, val title: String)

/**
 * Asks before finishing a Hardcover read. Progress goes to Hardcover on its own
 * ([HardcoverOutbox]), but finishing a read is only ever done when the owner taps it. When a book
 * becomes Finished, on the phone or by a sync from an e-reader, and its Hardcover read is
 * "Currently Reading" for the linked account, a prompt waits here, one per book, until the owner
 * answers it ([dismiss]) or the book is no longer Finished.
 *
 * "Currently Reading" is what the phone last saw on Hardcover (the `hardcover_observations` table,
 * kept current by the reading sheet, the Hardcover import and the outbox). Nothing here goes
 * online.
 *
 * Prompts are kept in SharedPreferences, so they survive restarts, and every instance sees the same
 * ones.
 */
class HardcoverFinishPrompts
internal constructor(
    private val prefs: SharedPreferences,
    private val dao: LibraryDao,
    /** The linked Hardcover account's id, or null when no account is linked. */
    private val account: () -> Int?,
) {
    constructor(
        context: Context
    ) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        (context.applicationContext as FolioApplication).database.library(),
        linkedAccount(context.applicationContext),
    )

    /** One waiting prompt: [finishedAt] is the book's status time when it became Finished. */
    private data class Waiting(val bookId: String, val finishedAt: Long)

    /** Prompts in the order they came, and the finishes already answered (book id to time). */
    private data class Saved(val waiting: List<Waiting>, val answered: Map<String, Long>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val saved = MutableStateFlow(load())
    // SharedPreferences holds listeners weakly; this field keeps it alive with the instance.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == WAITING) saved.value = load()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * Prompts to show, oldest first, with each book's current title. A book that is no longer
     * Finished, or is in Recently removed, is left out.
     */
    val prompts: StateFlow<List<FinishPrompt>> =
        combine(saved, dao.books()) { saved, books ->
                val finished = books.filter { it.status == FINISHED }.associateBy { it.id }
                saved.waiting.mapNotNull { w ->
                    finished[w.bookId]?.let { FinishPrompt(it.id, it.title) }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The owner answered the prompt for [bookId]: Mark as read (after Hardcover took it) or Not
     * now. It is not asked again until the book is finished again.
     */
    fun dismiss(bookId: String) =
        synchronized(LOCK) {
            val now = load()
            val waiting = now.waiting.firstOrNull { it.bookId == bookId } ?: return@synchronized
            save(
                Saved(
                    now.waiting - waiting,
                    now.answered + (bookId to waiting.finishedAt),
                )
            )
        }

    /**
     * Call after the status of [ids] may have changed: books, or catalogue entries linked to books
     * (a status set on one is set on its book). A book that became Finished gets a prompt when a
     * Hardcover account is linked, the book is linked to Hardcover and its read there is Currently
     * Reading. Prompts and answers for books that are no longer Finished are dropped. It never
     * fails: a problem here must not undo or stop a status change.
     */
    suspend fun statusChanged(ids: Collection<String>) {
        if (ids.isEmpty()) return
        try {
            withContext(Dispatchers.IO) {
                val books = dao.allBooks().associateBy { it.id }
                val wishes = dao.allWishes()
                val targets =
                    ids.toSet() + wishes.filter { it.id in ids }.mapNotNull { it.linkedBookId }
                val user = account()
                val reading =
                    if (user == null) emptySet()
                    else
                        dao.hardcoverObservations()
                            .first()
                            .filter { it.accountId == user && it.status == HARDCOVER_READING }
                            .map { it.hardcoverId }
                            .toSet()
                synchronized(LOCK) {
                    val now = load()
                    // A finish counts while the book is still Finished since that same moment.
                    fun current(id: String, at: Long) =
                        books[id]?.let { it.status == FINISHED && it.statusAt == at } == true
                    val waiting = now.waiting.filter { books[it.bookId]?.status == FINISHED }
                    val answered = now.answered.filter { (id, at) -> current(id, at) }
                    val added =
                        targets.sorted().mapNotNull { id ->
                            val book = books[id] ?: return@mapNotNull null
                            if (book.status != FINISHED) return@mapNotNull null
                            if (answered[id] == book.statusAt) return@mapNotNull null
                            if (waiting.any { it.bookId == id }) return@mapNotNull null
                            val hardcoverId =
                                wishes
                                    .firstOrNull {
                                        (it.linkedBookId == id || it.id == id) &&
                                            it.hardcoverId != null
                                    }
                                    ?.hardcoverId
                            if (hardcoverId == null || hardcoverId !in reading) null
                            else Waiting(id, book.statusAt)
                        }
                    val next = Saved(waiting + added, answered)
                    if (next != now) save(next)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The prompt is a courtesy; the status change it follows has already happened.
        }
    }

    /** The books with a prompt waiting, before [prompts] checks they are still Finished. */
    internal fun waitingBookIds(): List<String> = saved.value.waiting.map { it.bookId }

    private fun load(): Saved {
        val waiting =
            runCatching {
                    val a = JSONArray(prefs.getString(WAITING, "[]"))
                    (0 until a.length()).map {
                        val o = a.getJSONObject(it)
                        Waiting(o.getString("bookId"), o.getLong("finishedAt"))
                    }
                }
                .getOrDefault(emptyList())
        val answered =
            runCatching {
                    val o = JSONObject(prefs.getString(ANSWERED, "{}") ?: "{}")
                    o.keys().asSequence().associateWith { o.getLong(it) }
                }
                .getOrDefault(emptyMap())
        return Saved(waiting, answered)
    }

    private fun save(value: Saved) {
        prefs
            .edit()
            .putString(
                WAITING,
                JSONArray(
                        value.waiting.map {
                            JSONObject().put("bookId", it.bookId).put("finishedAt", it.finishedAt)
                        }
                    )
                    .toString(),
            )
            .putString(ANSWERED, JSONObject(value.answered).toString())
            .apply()
        saved.value = value
    }

    companion object {
        const val PREFS = "hardcover-finish-prompts"
        private const val WAITING = "waiting"
        private const val ANSWERED = "answered"
        private const val FINISHED = "Finished"
        /** Hardcover's "Currently Reading". */
        const val HARDCOVER_READING = 2
        /** One lock for every instance: they share the same preferences. */
        private val LOCK = Any()

        private fun linkedAccount(context: Context): () -> Int? = {
            val settings = HardcoverSettings(context)
            settings.accountId.takeIf { settings.connected && it > 0 }
        }
    }
}
