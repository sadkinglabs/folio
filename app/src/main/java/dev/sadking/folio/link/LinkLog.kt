package dev.sadking.folio.link

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Folio Link heard and when, newest first. It answers "did my e-reader reach the phone while
 * the screen was off?" No credentials, headers or book keys are stored.
 */
class LinkLog(private val file: File) {
    enum class Kind {
        REQUEST,
        STARTED,
        STOPPED,
        FAILED_TO_START,
        SCREEN_OFF,
        SCREEN_ON,
    }

    data class Entry(
        val time: Long,
        val kind: Kind,
        val route: String = "",
        val status: Int = 0,
        val remote: String = "",
        val port: Int = 0,
    )

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<Entry>> = _entries

    /** The last time an e-reader reached the phone with a valid login. */
    val lastHeard: Long?
        get() =
            _entries.value.firstOrNull { it.kind == Kind.REQUEST && it.status in 200..299 }?.time

    @Synchronized
    fun add(entry: Entry) {
        _entries.value = (listOf(entry) + _entries.value).take(MAX)
        runCatching { save(_entries.value) }
    }

    fun request(event: LinkEvent) =
        add(Entry(event.time, Kind.REQUEST, event.route, event.status, event.remote))

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        file.delete()
    }

    private fun save(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put("time", it.time)
                    .put("kind", it.kind.name)
                    .put("route", it.route)
                    .put("status", it.status)
                    .put("remote", it.remote)
                    .put("port", it.port)
            )
        }
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(array.toString())
        temporary.renameTo(file)
    }

    private fun load(): List<Entry> =
        runCatching {
                val array = JSONArray(file.readText())
                (0 until array.length()).mapNotNull { index ->
                    val o = array.getJSONObject(index)
                    val kind =
                        runCatching { Kind.valueOf(o.getString("kind")) }.getOrNull()
                            ?: return@mapNotNull null
                    Entry(
                        o.getLong("time"),
                        kind,
                        o.optString("route"),
                        o.optInt("status"),
                        o.optString("remote"),
                        o.optInt("port"),
                    )
                }
            }
            .getOrDefault(emptyList())

    companion object {
        const val MAX = 200
    }
}
