package dev.sadking.folio.sync

import android.content.Context
import android.content.SharedPreferences
import dev.sadking.folio.core.sync.Question
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
    val name: String = "",
    /** When the last sync finished, in epoch milliseconds; 0 before the first one. */
    val lastSync: Long = 0,
    val questions: List<Question> = emptyList(),
    val answers: Map<String, String> = emptyMap(),
) {
    /** Questions with no answer yet. */
    val waiting: List<Question>
        get() = questions.filter { it.id !in answers }
}

/**
 * Sync's own small state, one [EreaderSync] per e-reader, kept as JSON in SharedPreferences. The
 * Link server writes it as syncs finish; the Library screen reads [ereaders] and answers questions
 * with [answer]. Use [of] for the app's one instance.
 */
class SyncState(private val prefs: SharedPreferences) {
    private val _ereaders = MutableStateFlow(load())

    /** Every e-reader that has synced, by id. */
    val ereaders: StateFlow<Map<String, EreaderSync>> = _ereaders

    fun get(ereaderId: String): EreaderSync = _ereaders.value[ereaderId] ?: EreaderSync(ereaderId)

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

    /** A sync with [ereaderId], called [name] on the e-reader, finished at [at]. */
    @Synchronized
    fun synced(ereaderId: String, name: String, at: Long) {
        val ereader = get(ereaderId)
        save(ereader.copy(name = name.ifBlank { ereader.name }, lastSync = at))
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
                    }
            )

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
                        )
                    }
                    .getOrNull()
            }
    }
}
