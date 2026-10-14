package dev.sadking.folio.data

import dev.sadking.folio.core.Epub
import java.net.HttpURLConnection
import java.net.URI

/**
 * Public image request. No account token, redirects, private endpoints or arbitrary provider URLs.
 */
class HardcoverCover(private val fixture: ((String) -> ByteArray)? = null) {
    fun load(url: String): ByteArray {
        val target = URI(url)
        require(
            target.scheme == "https" &&
                target.host?.lowercase() in
                    listOf("assets.hardcover.app", "covers.openlibrary.org") &&
                target.userInfo == null &&
                target.port in listOf(-1, 443)
        ) {
            "Unsupported cover URL"
        }
        val limit = 5L * 1024 * 1024
        fixture?.let {
            return it(url).also { bytes -> require(bytes.size.toLong() <= limit) }
        }
        val connection = target.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.instanceFollowRedirects = false
        try {
            require(connection.responseCode == 200) { "Cover unavailable" }
            return java.io
                .ByteArrayOutputStream()
                .also { out -> connection.inputStream.use { Epub.copyBounded(it, out, limit) } }
                .toByteArray()
        } finally {
            connection.disconnect()
        }
    }
}
