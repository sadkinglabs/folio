package dev.sadking.folio.link

import dev.sadking.folio.core.sync.KindleAction
import dev.sadking.folio.core.sync.KindleFile
import dev.sadking.folio.core.sync.KindleResult
import dev.sadking.folio.core.sync.PhoneAction
import dev.sadking.folio.core.sync.PhoneBook
import dev.sadking.folio.core.sync.SyncPlan
import dev.sadking.folio.core.sync.SyncPlanner
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Sync protocol v2 under `/folio/v2`, reached through [LinkRoutes] after the login check. The
 * e-reader's plugin drives every step; docs/SYNC_PROTOCOL.md has the wire format.
 * 1. `POST /sync` sends the e-reader's manifest. The phone plans and answers with a session and the
 *    e-reader's actions.
 * 2. The e-reader runs them in order: it downloads `GET /books/<id>?session=` and uploads `PUT
 *    /upload?session=&path=` (the path as the manifest listed it).
 * 3. `POST /finish` reports each action's result. Only then does the phone take uploads, apply its
 *    own actions and record what both sides now agree on.
 *
 * A session lasts [SESSION_MS] after its last request. Each device has at most one; a new `/sync`
 * replaces it.
 */
class SyncRoutes(
    private val library: SyncLibrary,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val maxUpload: Long = MAX_UPLOAD,
) {
    private class Session(
        val id: String,
        val device: String,
        val name: String,
        val root: String,
        val files: List<KindleFile>,
        /** The phone's books the plan was made from. */
        val phone: List<PhoneBook>,
        val plan: SyncPlan,
        /** The manifest entry for each path the plan asks the e-reader to upload. */
        val uploads: Map<String, KindleFile>,
        var used: Long,
    ) {
        /** Verified uploads waiting on disk for finish, by e-reader path. */
        val received = HashMap<String, File>()

        fun discard() {
            received.values.forEach { it.delete() }
            received.clear()
        }
    }

    private class Result(val ok: Boolean, val path: String?)

    private val lock = Any()
    private val sessions = HashMap<String, Session>() // by device id

    /** [route] is the path after `/folio/v2`. [upload] is the raw body of `PUT /upload`. */
    fun handle(request: KosyncRequest, route: String, upload: InputStream? = null): LinkResponse {
        val query = query(request.path)
        return when {
            request.method == "GET" && route == "/hello" -> hello(request)
            request.method == "POST" && route == "/sync" -> sync(request.body)
            request.method == "GET" && route.startsWith("/books/") ->
                book(decode(route.removePrefix("/books/")), query["session"].orEmpty())
            request.method == "PUT" && route == "/upload" ->
                upload(request, query["session"].orEmpty(), query["path"].orEmpty(), upload)
            request.method == "POST" && route == "/finish" -> finish(request.body)
            else -> notFound()
        }
    }

    private fun hello(request: KosyncRequest): LinkResponse {
        val device = request.header("x-folio-device").orEmpty().take(MAX_ID)
        return json(
            JSONObject()
                .put("api", API_VERSION)
                .put("name", NAME)
                .put("pending", device.isNotEmpty() && library.pending(device))
        )
    }

    private fun sync(text: String?): LinkResponse {
        val body = parse(text) ?: return invalid()
        val device: String
        val name: String
        val home: String
        val root: String
        val files: List<KindleFile>
        val answers = HashMap<String, String>()
        try {
            val about = body.getJSONObject("device")
            device = about.getString("id").trim()
            name = SyncWire.text(about, "name").orEmpty().trim().take(MAX_ID)
            home = SyncWire.text(body, "home").orEmpty()
            root = (SyncWire.text(body, "root") ?: DEFAULT_ROOT).trim('/')
            files = SyncWire.objects(body, "files").map(SyncWire::kindleFile)
            body.optJSONObject("answers")?.let { given ->
                given.keys().forEach { answers[it] = given.getString(it) }
            }
        } catch (_: JSONException) {
            return invalid()
        }
        if (
            device.isEmpty() ||
                device.length > MAX_ID ||
                home.length > SyncWire.MAX_PATH ||
                !SyncWire.validPath(root) ||
                files.size > MAX_FILES ||
                files.map { it.path }.toSet().size != files.size ||
                answers.size > MAX_FILES
        )
            return invalid()
        synchronized(lock) {
            purge()
            sessions.remove(device)?.discard()
            if (sessions.size >= MAX_SESSIONS)
                sessions.values
                    .minByOrNull { it.used }
                    ?.let { sessions.remove(it.device)?.discard() }
            val phone = library.phoneBooks()
            // Answers given on the e-reader just now win over older ones from the phone.
            val plan =
                try {
                    SyncPlanner.plan(
                        home = home,
                        root = root,
                        kindle = files,
                        phone = phone,
                        baselines = library.baselines(device),
                        answers = library.phoneAnswers(device) + answers,
                    )
                } catch (_: IllegalArgumentException) {
                    return invalid()
                }
            val byPath = files.associateBy { it.path }
            val session =
                Session(
                    id = UUID.randomUUID().toString(),
                    device = device,
                    name = name,
                    root = root,
                    files = files,
                    phone = phone,
                    plan = plan,
                    uploads =
                        plan.kindle
                            .filterIsInstance<KindleAction.Upload>()
                            .mapNotNull { action -> byPath[action.path]?.let { action.path to it } }
                            .toMap(),
                    used = now(),
                )
            sessions[device] = session
            return json(
                JSONObject()
                    .put("session", session.id)
                    .put(
                        "kindle",
                        JSONArray(
                            plan.kindle.mapIndexed { index, action ->
                                SyncWire.toJson(index, action, session.id) {
                                    library.bookFile(it.bookId)?.length() ?: it.size
                                }
                            }
                        ),
                    )
                    .put("questions", JSONArray(plan.questions.map(SyncWire::toJson)))
                    .put("phoneQuestions", library.phoneQuestions(device).size)
            )
        }
    }

    private fun book(bookId: String, sessionId: String): LinkResponse {
        val session = synchronized(lock) { current(sessionId) } ?: return gone()
        val download =
            session.plan.kindle.filterIsInstance<KindleAction.Download>().firstOrNull {
                it.bookId == bookId
            } ?: return invalid()
        val file = library.bookFile(bookId)?.takeIf { it.isFile } ?: return notFound()
        return LinkResponse.Download(
            file,
            contentType(download.to),
            download.to.substringAfterLast('/'),
        )
    }

    private fun upload(
        request: KosyncRequest,
        sessionId: String,
        path: String,
        body: InputStream?,
    ): LinkResponse {
        val session = synchronized(lock) { current(sessionId) } ?: return gone()
        val listed = session.uploads[path] ?: return invalid()
        if (body == null) return invalid()
        val declared = request.header("Content-Length")?.toLongOrNull()
        if (declared != null && declared > maxUpload) return tooLarge()
        val temp =
            try {
                File.createTempFile("librillo-", ".upload", library.uploadDir().apply { mkdirs() })
            } catch (_: IOException) {
                return failed("The phone could not store the upload")
            }
        val sha256 =
            try {
                copy(body, temp)
            } catch (_: IOException) {
                temp.delete()
                return failed("The upload was cut off")
            }
        if (sha256 == null) {
            temp.delete()
            return tooLarge()
        }
        if (sha256 != listed.sha256) {
            temp.delete()
            return LinkResponse.Text(
                409,
                LinkRoutes.JSON,
                JSONObject()
                    .put("ok", false)
                    .put("message", "The upload does not match the file listed")
                    .toString(),
            )
        }
        synchronized(lock) {
            if (sessions[session.device] !== session) {
                temp.delete()
                return gone()
            }
            session.received.put(path, temp)?.delete()
            session.used = now()
        }
        return json(
            JSONObject()
                .put("ok", true)
                .put("path", path)
                .put("size", temp.length())
                .put("sha256", sha256)
        )
    }

    /** Copies [input] to [file] and returns its SHA-256, or null once it passes [maxUpload]. */
    private fun copy(input: InputStream, file: File): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxUpload) return null
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun finish(text: String?): LinkResponse {
        val body = parse(text) ?: return invalid()
        val results = HashMap<Int, Result>()
        try {
            SyncWire.objects(body, "results").forEach {
                val path = SyncWire.text(it, "path")?.takeIf(SyncWire::validPath)
                results[it.getInt("index")] = Result(it.optBoolean("ok", false), path)
            }
        } catch (_: JSONException) {
            return invalid()
        }
        synchronized(lock) {
            val session = current(SyncWire.text(body, "session").orEmpty()) ?: return gone()
            sessions.remove(session.device)
            try {
                complete(session, results)
            } finally {
                session.discard()
            }
        }
        return json(JSONObject().put("ok", true))
    }

    /**
     * The phone's side of a finished sync. Uploads are taken first; an upload counts as done only
     * when the phone took its file. Then the phone applies its actions whose uploads were taken,
     * and the baselines become what both sides now agree on (see [SyncPlanner.baselines]).
     */
    private fun complete(session: Session, results: Map<Int, Result>) {
        val device = session.device
        val plan = session.plan
        val revisions = plan.phone.filterIsInstance<PhoneAction.Revise>().associateBy { it.path }
        val imports = plan.phone.filterIsInstance<PhoneAction.Import>().map { it.path }.toSet()
        val received = LinkedHashMap<String, String>()
        plan.kindle.forEachIndexed { index, action ->
            if (action !is KindleAction.Upload || results[index]?.ok != true) return@forEachIndexed
            val file = session.received[action.path] ?: return@forEachIndexed
            val revision = revisions[action.path]
            val bookId =
                when {
                    revision != null -> library.revise(device, revision.bookId, action.path, file)
                    action.path in imports -> library.receive(device, action.path, file)
                    else -> null
                }
            if (bookId != null) received[action.path] = bookId
        }
        val done =
            plan.kindle.mapIndexed { index, action ->
                val result = results[index]
                val ok =
                    result?.ok == true &&
                        (action !is KindleAction.Upload || action.path in received)
                KindleResult(index, ok, result?.path)
            }
        library.apply(device, SyncPlanner.phoneActionsAfter(plan, done), received)
        val kindle = SyncPlanner.kindleAfter(session.files, plan.kindle, session.phone, done)
        library.commit(
            device,
            SyncPlanner.baselines(
                kindle = kindle,
                phone = library.phoneBooks(),
                previous = library.baselines(device),
                root = session.root,
            ),
        )
        library.saveQuestions(device, plan.questions)
        library.synced(device, session.name, now())
    }

    /** The session [id], if it is still current; using it keeps it alive. Call under [lock]. */
    private fun current(id: String): Session? {
        purge()
        val session = sessions.values.firstOrNull { it.id == id } ?: return null
        session.used = now()
        return session
    }

    private fun purge() {
        val time = now()
        sessions.values
            .filter { time - it.used > SESSION_MS }
            .forEach { sessions.remove(it.device)?.discard() }
    }

    private fun parse(text: String?): JSONObject? =
        try {
            text?.takeIf { it.length <= MAX_BODY }?.let(::JSONObject)
        } catch (_: JSONException) {
            null
        }

    private fun json(body: JSONObject) = LinkResponse.Text(200, LinkRoutes.JSON, body.toString())

    private fun invalid() =
        LinkResponse.Text(403, LinkRoutes.JSON, """{"code":2003,"message":"Invalid request"}""")

    private fun notFound() =
        LinkResponse.Text(404, LinkRoutes.JSON, """{"code":2000,"message":"Not found"}""")

    private fun gone() =
        LinkResponse.Text(
            410,
            LinkRoutes.JSON,
            """{"code":2005,"message":"This sync has ended. Sync again."}""",
        )

    private fun tooLarge() =
        LinkResponse.Text(413, LinkRoutes.JSON, """{"code":2003,"message":"File too large"}""")

    private fun failed(message: String) =
        LinkResponse.Text(
            500,
            LinkRoutes.JSON,
            JSONObject().put("code", 2006).put("message", message).toString(),
        )

    companion object {
        const val API_VERSION = 2
        const val NAME = "Librillo"
        const val DEFAULT_ROOT = "Librillo"
        const val UPLOAD_ROUTE = "/folio/v2/upload"
        const val SESSION_MS = 30 * 60 * 1000L
        const val MAX_UPLOAD = 512L * 1024 * 1024
        /** Largest manifest or finish body: a whole library with its notes. */
        const val MAX_BODY = 16 * 1024 * 1024
        const val MAX_FILES = 50_000
        const val MAX_SESSIONS = 16
        const val MAX_ID = 128

        fun contentType(path: String): String =
            when (path.substringAfterLast('.', "").lowercase()) {
                "epub" -> "application/epub+zip"
                "pdf" -> "application/pdf"
                "cbz" -> "application/vnd.comicbook+zip"
                "cbr" -> "application/vnd.comicbook-rar"
                else -> "application/octet-stream"
            }

        private fun query(path: String): Map<String, String> =
            path
                .substringAfter('?', "")
                .split('&')
                .filter { it.isNotEmpty() }
                .associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }

        private fun decode(value: String) =
            runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault("")
    }
}
