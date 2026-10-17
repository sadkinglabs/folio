package dev.sadking.folio.acquire

import android.webkit.CookieManager
import dev.sadking.folio.core.Epub
import dev.sadking.folio.data.TooLargeBook
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** One download the browser asked for. [referer] is the page the link was on. */
internal data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val referer: String?,
)

/** What the server sent, from the last response after any redirects. */
internal data class DownloadResult(
    val bytes: Long,
    val contentType: String?,
    val contentDisposition: String?,
    val finalUrl: String,
)

/** A redirect to a plain http address, or a request that wasn't https to begin with. */
internal class InsecureDownload : IOException("Not https")

/**
 * Fetches a book the way the WebView would: with its cookies for each address, its user agent and
 * the page as Referer, so sites that need a session still hand the file over. https only, redirects
 * included; never more than [limit] bytes. On any failure or cancellation the partial file is
 * deleted.
 *
 * @param cookies the WebView's cookies for an address, or null.
 * @param open opens a connection; tests replace it.
 */
internal class BookDownloader(
    private val cookies: (String) -> String? = { CookieManager.getInstance().getCookie(it) },
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val limit: Long = Epub.MAX_FILE,
) {
    suspend fun download(
        request: DownloadRequest,
        into: File,
        progress: (bytes: Long, total: Long?) -> Unit = { _, _ -> },
    ): DownloadResult {
        try {
            return fetch(request, into, progress)
        } catch (e: Throwable) {
            into.delete()
            throw e
        }
    }

    private suspend fun fetch(
        request: DownloadRequest,
        into: File,
        progress: (Long, Long?) -> Unit,
    ): DownloadResult {
        var url = URL(request.url)
        repeat(MAX_REDIRECTS + 1) {
            if (!url.protocol.equals("https", ignoreCase = true)) throw InsecureDownload()
            coroutineContext.ensureActive()
            val connection = open(url)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("Accept", "*/*")
                request.userAgent
                    ?.takeIf { it.isNotBlank() }
                    ?.let { connection.setRequestProperty("User-Agent", it) }
                request.referer
                    ?.takeIf { BrowserPolicy.decide(it) == Navigation.Load }
                    ?.let { connection.setRequestProperty("Referer", it) }
                cookies(url.toString())
                    ?.takeIf { it.isNotBlank() }
                    ?.let { connection.setRequestProperty("Cookie", it) }
                val code = connection.responseCode
                if (code in REDIRECTS) {
                    val location =
                        connection.getHeaderField("Location") ?: throw IOException("No location")
                    url = URL(url, location)
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
                val length = connection.contentLengthLong.takeIf { it >= 0 }
                if (length != null && length > limit) throw TooLargeBook()
                val read = copy(connection, into, length, progress)
                if (length != null && read != length) throw IOException("Download was cut short")
                return DownloadResult(
                    read,
                    connection.contentType,
                    connection.getHeaderField("Content-Disposition"),
                    url.toString(),
                )
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private suspend fun copy(
        connection: HttpURLConnection,
        into: File,
        length: Long?,
        progress: (Long, Long?) -> Unit,
    ): Long {
        into.parentFile?.mkdirs()
        var read = 0L
        connection.inputStream.use { input ->
            FileOutputStream(into).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    read += count
                    if (read > limit) throw TooLargeBook()
                    output.write(buffer, 0, count)
                    progress(read, length)
                }
                output.fd.sync()
            }
        }
        return read
    }

    private companion object {
        const val MAX_REDIRECTS = 8
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
