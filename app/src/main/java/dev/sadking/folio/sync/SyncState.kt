package dev.sadking.folio.sync

import android.content.Context
import android.content.SharedPreferences
import dev.sadking.folio.core.sync.Question
import dev.sadking.folio.link.Refusal
import dev.sadking.folio.link.RefusedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the phone knows about one e-reader that syncs with it: its name, when it last finished a
 * sync, the questions that sync left open, and the answers given on the phone since. [answers] maps
 * question ids to the option chosen.
 */
data class EreaderSync(
    val id: String,
    /** The e-reader's own name, such as "Kindle Paperwhite"; blank until it sends one. */
    val name: String = "",
    /** When the last sync finished, in epoch milliseconds; 0 before the first one. */
    val lastSync: Long = 0,
    val questions: List<Question> = emptyList(),
    val answers: Map<String, String> = emptyMap(),
    /**
     * Files on the e-reader the phone would not take, by path. Not sent again until they change.
     */
    val refused: List<RefusedFile> = emptyList(),
    /** How many books both sides held after the last sync. */
    val books: Int = 0,
    /**
     * When the sync in progress started (the phone planned it), or 0. Kept in memory only: a sync
     * can't outlive the app's process.
     */
    val syncingSince: Long = 0,
) {
    /** Questions with no answer yet. */
    val waiting: List<Question>
        get() = questions.filter { it.id !in answers }

    /**
     * True while a sync is under way at [now]. A sync that never finished stops counting after
     * [SyncState.SYNCING_MS], when the phone forgets it.
     */
    fun syncing(now: Long): Boolean = syncingSince > 0 && now - syncingSince < SyncState.SYNCING_MS
}

/**
 * Sync's own small state, one [EreaderSync] per e-reader, kept as JSON in SharedPreferences. The
 * Link server writes it as syncs start and finish; the screens read [ereaders] and
 * [syncRequestedAt], answer questions with [answer] and ask for a sync with [requestSync]. Use [of]
 * for the app's one instance.
 */
class SyncState(private val prefs: SharedPreferences) {
    private val _ereaders = MutableStateFlow(load())
    private val _syncRequestedAt = MutableStateFlow(prefs.getLong(REQUESTED, 0))

    /** Every e-reader that has synced, by id. */
    val ereaders: StateFlow<Map<String, EreaderSync>> = _ereaders

    /**
     * When the owner last tapped Sync, in epoch milliseconds; 0 if never. An e-reader whose last
     * sync is older is told the phone has changes ([syncRequested]).
     */
    val syncRequestedAt: StateFlow<Long> = _syncRequestedAt

    fun get(ereaderId: String): EreaderSync = _ereaders.value[ereaderId] ?: EreaderSync(ereaderId)

    /**
     * The Sync button: every e-reader is told the phone has changes at its next check (`hello`), so
     * it syncs even when nothing changed, until it has finished a sync after [at].
     */
    @Synchronized
    fun requestSync(at: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(REQUESTED, at).apply()
        _syncRequestedAt.value = at
    }

    /** True when Sync was tapped after [ereaderId]'s last finished sync. */
    fun syncRequested(ereaderId: String): Boolean = _syncRequestedAt.value > get(ereaderId).lastSync

    /**
     * Keeps [option] as the answer to question [questionId] for [ereaderId], to be used at its next
     * sync. False when there is no such question or [option] is not one of its options.
     */
    @Synchronized
    fun answer(ereaderId: String, questionId: String, option: String): Boolean {
        val ereader = get(ereaderId)
        val question = ereader.questions.firstOrNull { it.id == questionId } ?: return false
        if (option !in question.options) return false
        save(ereader.copy(answers = ereader.answers + (questionId to option)))
        return true
    }

    /**
     * Replaces the questions about [ereaderId]. An answer is kept only while its question is still
     * asked with that option; the others have been used, or the conflict has changed or gone.
     */
    @Synchronized
    fun saveQuestions(ereaderId: String, questions: List<Question>) {
        val ereader = get(ereaderId)
        val options = questions.associate { it.id to it.options }
        save(
            ereader.copy(
                questions = questions,
                answers =
                    ereader.answers.filter { (id, answer) -> answer in options[id].orEmpty() },
            )
        )
    }

    /** Replaces the files [ereaderId] has that the phone refused. */
    @Synchronized
    fun saveRefused(ereaderId: String, refused: List<RefusedFile>) {
        val ereader = get(ereaderId)
        if (ereader.refused != refused) save(ereader.copy(refused = refused))
    }

    /** [ereaderId], called [name] on the e-reader, started a sync at [at]. */
    @Synchronized
    fun started(ereaderId: String, name: String, at: Long) {
        val ereader = get(ereaderId)
        save(ereader.copy(name = name.ifBlank { ereader.name }, syncingSince = at))
    }

    /**
     * A sync with [ereaderId], called [name] on the e-reader, finished at [at], after which both
     * sides held [books] books (null keeps the last count).
     */
    @Synchronized
    fun synced(ereaderId: String, name: String, at: Long, books: Int? = null) {
        val ereader = get(ereaderId)
        save(
            ereader.copy(
                name = name.ifBlank { ereader.name },
                lastSync = at,
                books = books ?: ereader.books,
                syncingSince = 0,
            )
        )
    }

    private fun save(ereader: EreaderSync) {
        val all = _ereaders.value + (ereader.id to ereader)
        prefs.edit().putString(KEY, encode(all.values).toString()).apply()
        _ereaders.value = all
    }

    private fun load(): Map<String, EreaderSync> =
        runCatching { decode(JSONArray(prefs.getString(KEY, "[]"))) }
            .getOrDefault(emptyList())
            .associateBy { it.id }

    companion object {
        const val PREFS = "librillo-sync"
        private const val KEY = "ereaders"
        private const val REQUESTED = "requestedAt"

        /** How long a sync that started but never finished counts as under way. */
        const val SYNCING_MS = 30 * 60 * 1000L

        @Volatile private var shared: SyncState? = null

        /** The app's one instance, shared by the Link server and the screens. */
        fun of(context: Context): SyncState =
            shared
                ?: synchronized(this) {
                    shared
                        ?: SyncState(
                                context.applicationContext.getSharedPreferences(
                                    PREFS,
                                    Context.MODE_PRIVATE,
                                )
                            )
                            .also { shared = it }
                }

        internal fun encode(ereaders: Collection<EreaderSync>): JSONArray =
            JSONArray(
                ereaders
                    .sortedBy { it.id }
                    .map { e ->
                        JSONObject()
                            .put("id", e.id)
                            .put("name", e.name)
                            .put("lastSync", e.lastSync)
                            .put("questions", JSONArray(e.questions.map(::questionJson)))
                            .put("answers", JSONObject(e.answers))
                            .put("refused", JSONArray(e.refused.map(::refusedJson)))
                            .put("books", e.books)
                    }
            )

        private fun refusedJson(r: RefusedFile): JSONObject =
            JSONObject()
                .put("sha256", r.sha256)
                .put("path", r.path)
                .put("reason", r.reason.name)
                .put("at", r.at)

        /** Null for an entry this version can't read, such as a reason it doesn't know. */
        private fun refused(o: JSONObject): RefusedFile? =
            runCatching {
                    RefusedFile(
                        sha256 = o.getString("sha256"),
                        path = o.getString("path"),
                        reason = Refusal.valueOf(o.getString("reason")),
                        at = o.optLong("at"),
                    )
                }
                .getOrNull()

        private fun questionJson(q: Question): JSONObject =
            JSONObject()
                .put("id", q.id)
                .put("bookId", q.bookId ?: JSONObject.NULL)
                .put("path", q.path ?: JSONObject.NULL)
                .put("kind", q.kind)
                .put("phoneSide", q.phoneSide)
                .put("kindleSide", q.kindleSide)
                .put("text", q.text)
                .put("options", JSONArray(q.options))

        private fun question(o: JSONObject): Question =
            Question(
                id = o.getString("id"),
                bookId = if (o.isNull("bookId")) null else o.getString("bookId"),
                path = if (o.isNull("path")) null else o.getString("path"),
                kind = o.getString("kind"),
                phoneSide = o.getString("phoneSide"),
                kindleSide = o.getString("kindleSide"),
                text = o.getString("text"),
                options =
                    o.getJSONArray("options").let { a ->
                        (0 until a.length()).map { a.getString(it) }
                    },
            )

        /** Reads what [encode] wrote. An entry that can't be read is skipped, not the rest. */
        internal fun decode(array: JSONArray): List<EreaderSync> =
            (0 until array.length()).mapNotNull { i ->
                runCatching {
                        val o = array.getJSONObject(i)
                        val answers = o.optJSONObject("answers") ?: JSONObject()
                        EreaderSync(
                            id = o.getString("id"),
                            name = o.optString("name"),
                            lastSync = o.optLong("lastSync"),
                            questions =
                                o.getJSONArray("questions").let { a ->
                                    (0 until a.length()).map { question(a.getJSONObject(it)) }
                                },
                            answers = answers.keys().asSequence().associateWith(answers::getString),
                            // Missing before M9 step 4.
                            refused =
                                o.optJSONArray("refused")?.let { a ->
                                    (0 until a.length()).mapNotNull {
                                        a.optJSONObject(it)?.let(::refused)
                                    }
                                } ?: emptyList(),
                            books = o.optInt("books"),
                        )
                    }
                    .getOrNull()
            }
    }
}
