package dev.sadking.folio.acquire

import java.net.URI
import java.net.URLDecoder

/** What a download offered by a web page is, before any byte is fetched. */
enum class DownloadKind {
    /** An EPUB, PDF, CBZ or CBR by its type or name. The bytes still decide on import. */
    BOOK,
    /** An Adobe ACSM ticket for a protected book. Never downloaded. */
    ACSM,
    /** Anything else. */
    OTHER,
}

/** What to do with a download a page offers. */
sealed interface DownloadDecision {
    /** Fetch [url] (https) and import it as [name]. */
    data class Fetch(val url: String, val name: String) : DownloadDecision

    /** Show [outcome] at once; nothing is fetched. */
    data class Refuse(val outcome: AcquireOutcome) : DownloadDecision

    /** Not a book: a short message, and the page stays as it is. */
    data object NotABook : DownloadDecision
}

/**
 * Sorts a WebView download by its MIME type, its Content-Disposition file name and its URL, and
 * makes a safe file name for it. Pure, so it is tested on the JVM.
 */
object DownloadSniffer {
    private val BOOK_TYPES =
        mapOf(
            "application/epub+zip" to "epub",
            "application/pdf" to "pdf",
            "application/x-pdf" to "pdf",
            "application/vnd.comicbook+zip" to "cbz",
            "application/x-cbz" to "cbz",
            "application/vnd.comicbook-rar" to "cbr",
            "application/x-cbr" to "cbr",
        )

    /** Types servers use for any file; the name's extension decides for these. */
    private val GENERIC_TYPES =
        setOf(
            "",
            "application/octet-stream",
            "binary/octet-stream",
            "application/zip",
            "application/x-zip-compressed",
            "application/x-rar-compressed",
            "application/vnd.rar",
            "application/force-download",
            "application/download",
        )

    private const val ACSM_TYPE = "application/vnd.adobe.adept+xml"
    private val BOOK_EXTENSIONS = setOf("epub", "pdf", "cbz", "cbr")
    private const val MAX_NAME = 120
    private val UNSAFE = Regex("[\\u0000-\\u001f\\u007f<>:\"|?*\\\\/]")

    /** From the MIME type, the Content-Disposition file name, and the URL path's extension. */
    fun kind(url: String, contentDisposition: String?, mimeType: String?): DownloadKind {
        val type = type(mimeType)
        val extension = extension(rawName(url, contentDisposition))
        return when {
            type == ACSM_TYPE || extension == "acsm" -> DownloadKind.ACSM
            type in BOOK_TYPES -> DownloadKind.BOOK
            extension in BOOK_EXTENSIONS && type in GENERIC_TYPES -> DownloadKind.BOOK
            else -> DownloadKind.OTHER
        }
    }

    /**
     * A safe file name: no path parts, no control characters, no leading dots, at most 120
     * characters. A book whose name lacks its extension gets one from its MIME type.
     */
    fun fileName(url: String, contentDisposition: String?, mimeType: String?): String {
        val raw = rawName(url, contentDisposition).orEmpty()
        val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
        var name =
            leaf
                .replace(UNSAFE, "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trimStart('.', ' ')
                .trimEnd('.', ' ')
        if (name.isEmpty()) name = "book"
        val wanted = BOOK_TYPES[type(mimeType)]
        if (wanted != null && extension(name) != wanted) name = "$name.$wanted"
        return limit(name)
    }

    /**
     * What the browser does with a download the page offers: fetch it (https only), or say at once
     * why not. [contentLength] is negative when unknown.
     */
    fun decide(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
        limit: Long,
    ): DownloadDecision =
        when (kind(url, contentDisposition, mimeType)) {
            DownloadKind.ACSM -> DownloadDecision.Refuse(AcquireOutcome.Protected)
            DownloadKind.OTHER -> DownloadDecision.NotABook
            DownloadKind.BOOK -> {
                val https = BrowserPolicy.https(url)
                when {
                    https == null -> DownloadDecision.Refuse(AcquireOutcome.Failed)
                    contentLength > limit -> DownloadDecision.Refuse(AcquireOutcome.TooLarge)
                    else ->
                        DownloadDecision.Fetch(https, fileName(url, contentDisposition, mimeType))
                }
            }
        }

    /** [name] with [extension] at its end, unless it already has it. */
    fun withExtension(name: String, extension: String): String =
        if (extension(name) == extension) name else limit("$name.$extension")

    /** At most [MAX_NAME] characters, cutting the stem and keeping a short extension. */
    private fun limit(name: String): String {
        if (name.length <= MAX_NAME) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 10) name.substring(dot) else ""
        return name.substring(0, MAX_NAME - ext.length).trimEnd('.', ' ') + ext
    }

    private fun type(mimeType: String?): String =
        mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()

    private fun extension(name: String?): String? =
        name?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase()?.takeIf {
            it.isNotEmpty()
        }

    /** The name the server gives, else the URL path's last part, decoded. */
    private fun rawName(url: String, contentDisposition: String?): String? =
        contentDisposition?.let(::dispositionName)?.takeIf { it.isNotBlank() }
            ?: runCatching { URI(url).rawPath }
                .getOrNull()
                ?.substringAfterLast('/')
                ?.let { decode(it) }
                ?.takeIf { it.isNotBlank() }

    /** `filename*=UTF-8''…` (RFC 6266) first, then `filename="…"` or `filename=…`. */
    internal fun dispositionName(header: String): String? {
        val parts = splitParameters(header)
        parts
            .firstOrNull { it.first.equals("filename*", ignoreCase = true) }
            ?.second
            ?.let { value ->
                val quote = value.indexOf("''")
                if (quote >= 0) {
                    val charset = value.substring(0, quote).ifBlank { "UTF-8" }
                    val decoded =
                        runCatching {
                                URLDecoder.decode(
                                    value.substring(quote + 2).replace("+", "%2B"),
                                    charset,
                                )
                            }
                            .getOrNull()
                    if (!decoded.isNullOrBlank()) return decoded
                }
            }
        return parts.firstOrNull { it.first.equals("filename", ignoreCase = true) }?.second
    }

    /** `attachment; a=b; c="d; e"` as name and value pairs, quotes removed. */
    private fun splitParameters(header: String): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < header.length) {
            val eq = header.indexOf('=', i)
            if (eq < 0) break
            val name = header.substring(i, eq).substringAfterLast(';').trim()
            var j = eq + 1
            while (j < header.length && header[j] == ' ') j++
            val value: String
            if (j < header.length && header[j] == '"') {
                val sb = StringBuilder()
                j++
                while (j < header.length && header[j] != '"') {
                    if (header[j] == '\\' && j + 1 < header.length) j++
                    sb.append(header[j])
                    j++
                }
                value = sb.toString()
                j++
            } else {
                val end = header.indexOf(';', j).let { if (it < 0) header.length else it }
                value = header.substring(j, end).trim()
                j = end
            }
            found += name to value
            i = j
        }
        return found
    }

    private fun decode(part: String): String =
        runCatching { URLDecoder.decode(part.replace("+", "%2B"), "UTF-8") }.getOrDefault(part)
}
