package dev.sadking.folio.core

import java.security.MessageDigest

/** Restricted Lua literal reader. Functions, calls, operators and metatables cannot execute. */
class LuaData(private val text: String) {
    private var offset = 0
    private var nodes = 0

    init {
        require(text.length <= 4 * 1024 * 1024) { "Sidecar too large" }
    }

    fun parse(): Map<Any, Any?> {
        skip()
        require(text.startsWith("return", offset))
        offset += 6
        val value = value(0)
        skip()
        if (offset < text.length && text[offset] == ';') {
            offset++
            skip()
        }
        require(offset == text.length) { "Unsupported Lua expression" }
        @Suppress("UNCHECKED_CAST")
        return value as? Map<Any, Any?> ?: error("Expected Lua table")
    }

    private fun skip() {
        while (offset < text.length) {
            if (text[offset].isWhitespace()) offset++
            else if (text.startsWith("--", offset)) {
                offset += 2
                if (text.startsWith("[[", offset)) {
                    val end = text.indexOf("]]", offset + 2)
                    require(end >= 0)
                    offset = end + 2
                } else {
                    while (offset < text.length && text[offset] != '\n') offset++
                }
            } else break
        }
    }

    private fun expect(c: Char) {
        skip()
        require(offset < text.length && text[offset++] == c) { "Malformed Lua data" }
    }

    private fun value(depth: Int): Any? {
        skip()
        require(depth <= 48 && ++nodes <= 100000 && offset < text.length) {
            "Sidecar exceeds parsing limits"
        }
        val c = text[offset]
        if (c == '{') {
            offset++
            val map = linkedMapOf<Any, Any?>()
            var index = 1
            while (true) {
                skip()
                require(offset < text.length)
                if (text[offset] == '}') {
                    offset++
                    return map
                }
                var key: Any? = null
                if (text[offset] == '[' && !text.startsWith("[[", offset)) {
                    offset++
                    key = value(depth + 1) ?: error("Nil key")
                    expect(']')
                    expect('=')
                } else if (text[offset].isLetter() || text[offset] == '_') {
                    val saved = offset
                    val id = identifier()
                    skip()
                    if (offset < text.length && text[offset] == '=') {
                        offset++
                        key = id
                    } else offset = saved
                }
                val v = value(depth + 1)
                val k = key ?: index++.toDouble()
                require(!map.containsKey(k)) { "Duplicate Lua key" }
                map[k] = v
                skip()
                require(offset < text.length)
                if (text[offset] == ',' || text[offset] == ';') offset++
                else require(text[offset] == '}')
            }
        }
        if (c == '\'' || c == '"') {
            offset++
            val out = StringBuilder()
            while (offset < text.length) {
                val ch = text[offset++]
                if (ch == c) return out.toString()
                if (ch != '\\') {
                    out.append(ch)
                    continue
                }
                require(offset < text.length)
                val esc = text[offset++]
                out.append(
                    when (esc) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'v' -> '\u000b'
                        'a' -> '\u0007'
                        '\\' -> '\\'
                        '\'' -> '\''
                        '"' -> '"'
                        '\n' -> '\n'
                        in '0'..'9' -> {
                            val start = offset - 1
                            var count = 1
                            while (offset < text.length && count < 3 && text[offset].isDigit()) {
                                offset++
                                count++
                            }
                            val code = text.substring(start, offset).toInt()
                            require(code <= 255)
                            code.toChar()
                        }
                        else -> error("Unsupported Lua escape")
                    }
                )
            }
            error("Unterminated Lua string")
        }
        if (text.startsWith("[[", offset)) {
            val end = text.indexOf("]]", offset + 2)
            require(end >= 0)
            val s = text.substring(offset + 2, end)
            offset = end + 2
            return s
        }
        if (c == '-' || c.isDigit()) {
            val match =
                Regex("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?").find(text, offset)
            require(match != null && match.range.first == offset)
            offset = match.range.last + 1
            return match.value.toDouble().also { require(it.isFinite()) }
        }
        return when (identifier()) {
            "nil" -> null
            "true" -> true
            "false" -> false
            else -> error("Executable Lua is not supported")
        }
    }

    private fun identifier(): String {
        val start = offset
        while (
            offset < text.length && (text[offset].isLetterOrDigit() || text[offset] == '_')
        ) offset++
        require(offset > start)
        return text.substring(start, offset)
    }
}

data class ReaderAnnotation(
    val key: String,
    val text: String,
    val note: String,
    val chapter: String,
    val date: String,
    val position: String,
    val endPosition: String,
    val kind: String,
)

data class ReaderData(
    val percent: Double?,
    val position: String,
    val status: String,
    val rating: Int,
    val annotations: List<ReaderAnnotation>,
)

object KoReaderData {
    fun parse(bytes: ByteArray): ReaderData {
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val root = LuaData(text).parse()
        fun table(value: Any?) = value as? Map<*, *> ?: emptyMap<Any, Any?>()
        fun str(value: Any?) = value as? String ?: ""
        val summary = table(root["summary"])
        val annotations = mutableListOf<ReaderAnnotation>()
        fun add(a: Map<*, *>, kind: String) {
            val text = str(a["text"])
            val note = str(a["note"]).ifBlank { str(a["notes"]) }
            val start = str(a["pos0"]).ifBlank { str(a["page"]) }
            val end = str(a["pos1"])
            val date = str(a["datetime"])
            val actualKind =
                if (a["pos0"] != null || kind == "highlight") "highlight" else "bookmark"
            val parts = listOf(actualKind, start, end, date, text, note)
            // Length-prefixed components avoid delimiter collisions. Changed notes create retained
            // revisions.
            val key = digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray())
            annotations +=
                ReaderAnnotation(key, text, note, str(a["chapter"]), date, start, end, actualKind)
        }
        val modern = table(root["annotations"])
        if (modern.isNotEmpty()) modern.values.forEach { add(table(it), "bookmark") }
        else {
            table(root["highlight"]).values.forEach { group ->
                table(group).values.forEach { add(table(it), "highlight") }
            }
            table(root["bookmarks"]).values.forEach { add(table(it), "bookmark") }
        }
        val p = (root["percent_finished"] as? Number)?.toDouble()?.takeIf { it in 0.0..1.0 }
        return ReaderData(
            p,
            str(root["last_xpointer"]),
            str(summary["status"]),
            (summary["rating"] as? Number)?.toInt()?.coerceIn(0, 5) ?: 0,
            annotations.distinctBy { it.key },
        )
    }

    fun digest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
