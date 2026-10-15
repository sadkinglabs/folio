package dev.sadking.folio.data

import dev.sadking.folio.core.*
import java.net.*
import org.json.*

data class MetadataCandidate(
    val key: String,
    val title: String,
    val authors: List<String>,
    val coverId: Long?,
    val editionKey: String?,
)

data class MetadataProposal(val metadata: Metadata, val coverId: Long?)

/** One field a lookup can replace: its key (stable, in English), what is there, what is offered. */
data class LookupField(val key: String, val current: String, val suggested: String)

/** The field keys a lookup can replace, in the order the sheet shows them. */
val lookupFieldKeys =
    listOf("Title", "Authors", "Description", "Publisher", "Language", "Identifiers")

/** The fields with a suggestion, as rows for the review list. Blank suggestions are left out. */
fun lookupFields(current: Metadata, proposed: Metadata): List<LookupField> =
    lookupFieldKeys
        .map { key ->
            LookupField(key, lookupValue(current, key), lookupValue(proposed, key))
        }
        .filter { it.suggested.isNotBlank() }

private fun lookupValue(metadata: Metadata, key: String): String =
    when (key) {
        "Title" -> metadata.title
        "Authors" -> metadata.authors.joinToString(", ")
        "Description" -> metadata.description
        "Publisher" -> metadata.publisher
        "Language" -> metadata.language
        "Identifiers" -> metadata.identifiers.joinToString(", ")
        else -> ""
    }

/** [current] with only the [selected] fields taken from [proposed]; series and tags never move. */
fun applyLookup(current: Metadata, proposed: Metadata, selected: Set<String>): Metadata =
    current.copy(
        title = if ("Title" in selected) proposed.title else current.title,
        authors = if ("Authors" in selected) proposed.authors else current.authors,
        description = if ("Description" in selected) proposed.description else current.description,
        publisher = if ("Publisher" in selected) proposed.publisher else current.publisher,
        language = if ("Language" in selected) proposed.language else current.language,
        identifiers = if ("Identifiers" in selected) proposed.identifiers else current.identifiers,
    )

/**
 * User-triggered Open Library queries; HTTPS only, bounded responses, no API key or book upload.
 */
class MetadataLookup(private val fixture: ((String, Long) -> ByteArray)? = null) {
    private var lastRequest = 0L

    @Synchronized
    private fun get(url: String, limit: Long): ByteArray {
        val target = URI(url)
        require(
            target.scheme == "https" &&
                target.host in listOf("openlibrary.org", "covers.openlibrary.org")
        )
        fixture?.let { request ->
            return request(url, limit).also {
                require(it.size.toLong() <= limit) { "Metadata response exceeds safety limit" }
            }
        }
        val remaining = 1100 - (System.currentTimeMillis() - lastRequest)
        if (remaining > 0) Thread.sleep(remaining)
        lastRequest = System.currentTimeMillis()
        var current = target
        repeat(4) { attempt ->
            val connection = current.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty(
                "User-Agent",
                "Folio/0.2.0 (personal Android ebook library)",
            )
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    require(attempt < 3) { "Too many metadata redirects" }
                    val location =
                        connection.getHeaderField("Location") ?: error("Invalid metadata redirect")
                    current = providerRedirect(target, current.resolve(location))
                } else {
                    require(status == 200) {
                        if (status == 429) "Metadata service is busy; try again later"
                        else "Metadata service could not complete the request"
                    }
                    return java.io
                        .ByteArrayOutputStream()
                        .also { out ->
                            connection.inputStream.use { Epub.copyBounded(it, out, limit) }
                        }
                        .toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Metadata service could not complete the request")
    }

    fun search(query: String): List<MetadataCandidate> {
        require(query.isNotBlank() && query.length <= 300)
        val url =
            "https://openlibrary.org/search.json?q=${URLEncoder.encode(query,"UTF-8")}&limit=12&fields=key,title,author_name,cover_i,editions,editions.key,editions.title"
        val docs =
            JSONObject(get(url, 2L * 1024 * 1024).toString(Charsets.UTF_8)).getJSONArray("docs")
        return docs.objects().map { d ->
            MetadataCandidate(
                d.getString("key"),
                d.optString("title"),
                d.optJSONArray("author_name")?.let { decode(it.toString()) } ?: emptyList(),
                d.optLong("cover_i").takeIf { it > 0 },
                d.optJSONObject("editions")
                    ?.optJSONArray("docs")
                    ?.optJSONObject(0)
                    ?.optString("key")
                    ?.takeIf { it.matches(Regex("/books/OL[0-9]+M")) },
            )
        }
    }

    fun proposal(c: MetadataCandidate): MetadataProposal {
        require(c.key.matches(Regex("/works/OL[0-9]+W")))
        require(c.editionKey == null || c.editionKey.matches(Regex("/books/OL[0-9]+M")))
        val work =
            JSONObject(
                get("https://openlibrary.org${c.key}.json", 2L * 1024 * 1024)
                    .toString(Charsets.UTF_8)
            )
        val edition =
            c.editionKey?.let {
                JSONObject(
                    get("https://openlibrary.org$it.json", 2L * 1024 * 1024)
                        .toString(Charsets.UTF_8)
                )
            }
        fun description(o: JSONObject?) =
            o?.opt("description")?.let {
                if (it is JSONObject) it.optString("value") else it as? String
            } ?: ""
        fun strings(key: String) =
            edition?.optJSONArray(key)?.let { decode(it.toString()) } ?: emptyList()
        val language =
            edition
                ?.optJSONArray("languages")
                ?.optJSONObject(0)
                ?.optString("key")
                ?.substringAfterLast('/') ?: ""
        val cover = edition?.optJSONArray("covers")?.optLong(0)?.takeIf { it > 0 } ?: c.coverId
        return MetadataProposal(
            Metadata(
                edition?.optString("title")?.ifBlank { c.title } ?: c.title,
                c.authors,
                description(edition).ifBlank { description(work) },
                strings("publishers").joinToString(", "),
                language,
                strings("isbn_13") + strings("isbn_10"),
            ),
            cover,
        )
    }

    fun cover(id: Long): ByteArray {
        require(id > 0)
        return get("https://covers.openlibrary.org/b/id/$id-L.jpg?default=false", 10L * 1024 * 1024)
    }
}

/** Cover storage redirects are limited to Open Library and Internet Archive over normal HTTPS. */
internal fun providerRedirect(original: URI, destination: URI): URI {
    val host = destination.host.orEmpty().lowercase(java.util.Locale.ROOT)
    val allowed =
        if (original.host == "covers.openlibrary.org")
            host == "covers.openlibrary.org" ||
                host == "archive.org" ||
                host.endsWith(".archive.org")
        else host == "openlibrary.org"
    require(
        destination.scheme == "https" &&
            destination.userInfo == null &&
            destination.port in listOf(-1, 443) &&
            allowed
    ) {
        "Metadata redirect destination rejected"
    }
    return destination
}
