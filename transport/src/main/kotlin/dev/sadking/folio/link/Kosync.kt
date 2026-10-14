package dev.sadking.folio.link

import org.json.JSONException
import org.json.JSONObject

/**
 * The kosync protocol that KOReader's built-in Progress sync speaks, answered the way KOReader's
 * reference server answers it: the same routes, status codes, error codes and JSON shapes.
 *
 * Folio is a single-account server. The account is the one Folio shows the user; KOReader can
 * "Register" or "Login" with it and either works. Every device's latest report is kept, and a read
 * returns the newest one, so a phone or e-reader that falls behind is offered the furthest-recent
 * position by KOReader itself.
 */
class Kosync(
    private val store: KosyncStore,
    private val account: () -> KosyncAccount?,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val failures = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun handle(request: KosyncRequest): KosyncResponse {
        val path = request.path.substringBefore('?').trimEnd('/')
        return when {
            request.method == "GET" && path == "/healthcheck" -> ok(JSONObject().put("state", "OK"))
            request.method == "POST" && path == "/users/create" -> create(request)
            request.method == "GET" && path == "/users/auth" ->
                authorized(request) { ok(JSONObject().put("authorized", "OK")) }
            request.method == "PUT" && path == "/syncs/progress" ->
                authorized(request) { user -> update(user, request.body) }
            request.method == "GET" && path.startsWith("/syncs/progress/") ->
                authorized(request) { user ->
                    read(user, decodePath(path.removePrefix("/syncs/progress/")))
                }
            else -> KosyncResponse(404, error(2000, "Not found"))
        }
    }

    private fun create(request: KosyncRequest): KosyncResponse {
        val body = parse(request.body) ?: return invalid()
        val username = body.optString("username")
        val key = body.optString("password")
        if (username.isEmpty() || key.isEmpty()) return invalid()
        val expected = account()
        // Only Folio's own account exists. Registering it again with the right key succeeds, so
        // the user can press either button in KOReader.
        return if (expected != null && expected.username == username && expected.key == key)
            KosyncResponse(201, JSONObject().put("username", username).toString())
        else KosyncResponse(402, error(2002, "Username is already registered."))
    }

    private fun authorized(
        request: KosyncRequest,
        then: (String) -> KosyncResponse,
    ): KosyncResponse {
        val time = now()
        if (failures.size > 256) failures.clear()
        val recent =
            failures
                .getOrPut(request.remote) { ArrayDeque() }
                .apply {
                    while (isNotEmpty() && time - first() > FAILURE_WINDOW_MS) removeFirst()
                }
        if (recent.size >= MAX_FAILURES) return KosyncResponse(429, error(2001, "Unauthorized"))
        val expected = account()
        val user = request.header("x-auth-user")
        val key = request.header("x-auth-key")
        if (expected == null || user != expected.username || key != expected.key) {
            recent.addLast(time)
            return KosyncResponse(401, error(2001, "Unauthorized"))
        }
        recent.clear()
        return then(user)
    }

    private fun update(user: String, text: String?): KosyncResponse {
        val body = parse(text) ?: return invalid()
        val document = body.optString("document")
        if (document.isEmpty() || document.length > 128)
            return KosyncResponse(403, error(2004, "Field 'document' not provided."))
        val progress = body.opt("progress")?.toString().orEmpty()
        val percentage = body.optDouble("percentage", Double.NaN)
        val device = body.optString("device")
        val deviceId = body.optString("device_id")
        if (
            progress.isEmpty() ||
                progress.length > 4096 ||
                percentage.isNaN() ||
                percentage !in 0.0..1.0 ||
                device.isEmpty() ||
                device.length > 128 ||
                deviceId.length > 128
        )
            return invalid()
        val timestamp = now() / 1000
        store.save(
            user,
            KosyncProgress(document, progress, percentage, device, deviceId, timestamp),
        )
        return ok(JSONObject().put("document", document).put("timestamp", timestamp))
    }

    private fun read(user: String, document: String): KosyncResponse {
        if (document.isEmpty())
            return KosyncResponse(403, error(2004, "Field 'document' not provided."))
        val latest = store.latest(user, document) ?: return ok(JSONObject())
        return ok(
            JSONObject()
                .put("document", latest.document)
                .put("progress", latest.progress)
                .put("percentage", latest.percentage)
                .put("device", latest.device)
                .put("device_id", latest.deviceId)
                .put("timestamp", latest.timestamp)
        )
    }

    private fun parse(text: String?): JSONObject? =
        try {
            text?.takeIf { it.length <= MAX_BODY }?.let(::JSONObject)
        } catch (_: JSONException) {
            null
        }

    private fun decodePath(value: String) =
        runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault("")

    private fun ok(body: JSONObject) = KosyncResponse(200, body.toString())

    private fun invalid() = KosyncResponse(403, error(2003, "Invalid request"))

    private fun error(code: Int, message: String) =
        JSONObject().put("code", code).put("message", message).toString()

    companion object {
        const val MAX_BODY = 16 * 1024
        const val MAX_FAILURES = 10
        const val FAILURE_WINDOW_MS = 60_000L
    }
}

/** [key] is what KOReader sends as `x-auth-key`: the MD5 of the password, in lowercase hex. */
data class KosyncAccount(val username: String, val key: String) {
    companion object {
        fun fromPassword(username: String, password: String) =
            KosyncAccount(
                username,
                java.security.MessageDigest.getInstance("MD5")
                    .digest(password.toByteArray())
                    .joinToString("") { "%02x".format(it) },
            )
    }
}

data class KosyncRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val remote: String = "",
) {
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
}

data class KosyncResponse(val status: Int, val body: String)

/** One device's report. [timestamp] is in seconds, as KOReader expects. */
data class KosyncProgress(
    val document: String,
    val progress: String,
    val percentage: Double,
    val device: String,
    val deviceId: String,
    val timestamp: Long,
)

/** Keeps every device's latest report per document; [latest] is the newest across devices. */
interface KosyncStore {
    fun save(user: String, progress: KosyncProgress)

    fun latest(user: String, document: String): KosyncProgress?
}

class MemoryKosyncStore : KosyncStore {
    private val reports = LinkedHashMap<Triple<String, String, String>, KosyncProgress>()

    @Synchronized
    override fun save(user: String, progress: KosyncProgress) {
        val key = Triple(user, progress.document, progress.deviceId.ifEmpty { progress.device })
        // Re-inserting keeps insertion order meaningful: on equal timestamps, the last write wins.
        reports.remove(key)
        reports[key] = progress
    }

    @Synchronized
    override fun latest(user: String, document: String) =
        reports.entries
            .filter { it.key.first == user && it.key.second == document }
            .map { it.value }
            .reduceOrNull { best, next -> if (next.timestamp >= best.timestamp) next else best }
}
