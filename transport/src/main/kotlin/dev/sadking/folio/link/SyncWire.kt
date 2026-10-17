package dev.sadking.folio.link

import dev.sadking.folio.core.sync.KindleAction
import dev.sadking.folio.core.sync.KindleFile
import dev.sadking.folio.core.sync.Question
import dev.sadking.folio.core.sync.SyncAnnotation
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * JSON for sync protocol v2. Field names are the core types' property names; see
 * docs/SYNC_PROTOCOL.md. Readers throw [JSONException] on anything malformed.
 */
internal object SyncWire {
    val FORMATS = setOf("epub", "pdf", "cbz", "cbr")
    val STATUSES = setOf("unread", "reading", "finished", "abandoned")
    val KINDS = setOf("highlight", "note", "bookmark")
    const val MAX_PATH = 1024
    /** Longest colour or drawing style kept; anything longer is taken as unknown (blank). */
    const val MAX_STYLE = 32
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** Relative to KOReader's home, "/" separators, no empty, "." or ".." parts. */
    fun validPath(path: String): Boolean =
        path.isNotEmpty() &&
            path.length <= MAX_PATH &&
            !path.startsWith("/") &&
            '\\' !in path &&
            path.none { it < ' ' } &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    fun kindleFile(json: JSONObject): KindleFile {
        val path = json.getString("path")
        need(validPath(path), "path")
        val sha256 = json.getString("sha256").lowercase()
        need(SHA256.matches(sha256), "sha256")
        val format = json.getString("format").lowercase()
        need(format in FORMATS, "format")
        val size = json.getLong("size")
        need(size >= 0, "size")
        val status = text(json, "status")?.lowercase()
        need(status == null || status in STATUSES, "status")
        return KindleFile(
            path = path,
            size = size,
            sha256 = sha256,
            format = format,
            status = status,
            statusAt = if (json.isNull("statusAt")) null else json.getLong("statusAt"),
            annotations = objects(json, "annotations").map(::annotation),
            collections = strings(json, "collections"),
            title = text(json, "title"),
            authors = strings(json, "authors"),
            annotationsComplete = json.optBoolean("annotationsComplete", false),
        )
    }

    fun toJson(file: KindleFile): JSONObject =
        JSONObject()
            .put("path", file.path)
            .put("size", file.size)
            .put("sha256", file.sha256)
            .put("format", file.format)
            .put("status", file.status ?: JSONObject.NULL)
            .put("statusAt", file.statusAt ?: JSONObject.NULL)
            .put("annotations", JSONArray(file.annotations.map(::toJson)))
            .put("collections", JSONArray(file.collections))
            .put("title", file.title ?: JSONObject.NULL)
            .put("authors", JSONArray(file.authors))
            .put("annotationsComplete", file.annotationsComplete)

    fun annotation(json: JSONObject): SyncAnnotation {
        val id = json.getString("id")
        need(id.isNotEmpty() && id.length <= 512, "annotation id")
        val kind = text(json, "kind") ?: "highlight"
        need(kind in KINDS, "annotation kind")
        return SyncAnnotation(
            id = id,
            text = text(json, "text").orEmpty(),
            note = text(json, "note").orEmpty(),
            chapter = text(json, "chapter").orEmpty(),
            datetime = text(json, "datetime").orEmpty(),
            pos0 = text(json, "pos0").orEmpty(),
            pos1 = text(json, "pos1").orEmpty(),
            kind = kind,
            color = style(json, "color"),
            drawer = style(json, "drawer"),
            updated = text(json, "updated").orEmpty().trim().take(MAX_STYLE),
        )
    }

    /** A colour or drawing style: lower case, or blank when missing or odd. */
    private fun style(json: JSONObject, key: String): String {
        val value = text(json, key).orEmpty().trim().lowercase()
        return if (value.length <= MAX_STYLE && value.none { it < ' ' }) value else ""
    }

    fun toJson(annotation: SyncAnnotation): JSONObject =
        JSONObject()
            .put("id", annotation.id)
            .put("text", annotation.text)
            .put("note", annotation.note)
            .put("chapter", annotation.chapter)
            .put("datetime", annotation.datetime)
            .put("pos0", annotation.pos0)
            .put("pos1", annotation.pos1)
            .put("kind", annotation.kind)
            .put("color", annotation.color)
            .put("drawer", annotation.drawer)
            .put("updated", annotation.updated)

    fun toJson(question: Question): JSONObject =
        JSONObject()
            .put("id", question.id)
            .put("bookId", question.bookId ?: JSONObject.NULL)
            .put("path", question.path ?: JSONObject.NULL)
            .put("kind", question.kind)
            .put("phoneSide", question.phoneSide)
            .put("kindleSide", question.kindleSide)
            .put("text", question.text)
            .put("options", JSONArray(question.options))

    fun question(json: JSONObject): Question =
        Question(
            id = json.getString("id"),
            bookId = text(json, "bookId"),
            path = text(json, "path"),
            kind = json.getString("kind"),
            phoneSide = json.getString("phoneSide"),
            kindleSide = json.getString("kindleSide"),
            text = json.getString("text"),
            options = strings(json, "options"),
        )

    /**
     * One action for the e-reader, numbered by its place in the list. [size] gives a download's
     * real file size, since the planner does not know it.
     */
    fun toJson(
        index: Int,
        action: KindleAction,
        session: String,
        size: (KindleAction.Download) -> Long,
    ): JSONObject {
        val json = JSONObject().put("index", index)
        return when (action) {
            is KindleAction.Download ->
                json
                    .put("op", "download")
                    .put("bookId", action.bookId)
                    .put("sha256", action.sha256)
                    .put("size", size(action))
                    .put("to", action.to)
                    .put("replaces", action.replaces ?: JSONObject.NULL)
                    .put(
                        "url",
                        "/folio/v2/books/${encode(action.bookId)}?session=${encode(session)}",
                    )
            is KindleAction.Move ->
                json.put("op", "move").put("from", action.from).put("to", action.to)
            is KindleAction.Delete -> json.put("op", "delete").put("path", action.path)
            is KindleAction.Upload ->
                json
                    .put("op", "upload")
                    .put("path", action.path)
                    .put(
                        "url",
                        "/folio/v2/upload?session=${encode(session)}&path=${encode(action.path)}",
                    )
            is KindleAction.SetStatus ->
                json.put("op", "status").put("path", action.path).put("status", action.status)
            is KindleAction.AddAnnotations ->
                json
                    .put("op", "annotations")
                    .put("path", action.path)
                    .put("annotations", JSONArray(action.annotations.map(::toJson)))
            is KindleAction.EditAnnotations ->
                json
                    .put("op", "edit-annotations")
                    .put("path", action.path)
                    .put("annotations", JSONArray(action.annotations.map(::toJson)))
            is KindleAction.DeleteAnnotations ->
                json
                    .put("op", "delete-annotations")
                    .put("path", action.path)
                    .put("ids", JSONArray(action.ids))
            is KindleAction.SetCollections ->
                json
                    .put("op", "collections")
                    .put("path", action.path)
                    .put("collections", JSONArray(action.collections))
        }
    }

    fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    /**
     * A string field, or null when it is missing or null. Numbers and objects become their text.
     */
    fun text(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.get(key).toString()

    fun strings(json: JSONObject, key: String): List<String> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }

    fun objects(json: JSONObject, key: String): List<JSONObject> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun need(ok: Boolean, what: String) {
        if (!ok) throw JSONException("Invalid $what")
    }
}
