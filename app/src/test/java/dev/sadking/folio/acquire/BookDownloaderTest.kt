package dev.sadking.folio.acquire

import dev.sadking.folio.data.TooLargeBook
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Downloading a book like the WebView would, https only and size-capped (M5). Plain JVM. */
class BookDownloaderTest {
    private val dir = Files.createTempDirectory("folio-download").toFile()
    private val target = File(dir, "x.part")
    private val opened = mutableListOf<FakeConnection>()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** A canned response; the request headers it was sent are kept for the test to read. */
    inner class FakeConnection(
        url: URL,
        private val code: Int,
        private val body: ByteArray = ByteArray(0),
        private val headers: Map<String, String> = emptyMap(),
        private val declaredLength: Long = body.size.toLong(),
        private val stream: (() -> InputStream)? = null,
    ) : HttpURLConnection(url) {
        val sent = mutableMapOf<String, String>()
        var disconnected = false

        override fun setRequestProperty(key: String, value: String) {
            sent[key] = value
        }

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy() = false

        override fun getResponseCode() = code

        override fun getContentLengthLong() = declaredLength

        override fun getContentType() = headers["Content-Type"]

        override fun getHeaderField(name: String): String? = headers[name]

        override fun getInputStream(): InputStream = stream?.invoke() ?: ByteArrayInputStream(body)
    }

    private fun downloader(
        limit: Long = 1024,
        cookies: (String) -> String? = { "session=abc; host=${URL(it).host}" },
        respond: (URL) -> FakeConnection,
    ) =
        BookDownloader(
            cookies = cookies,
            open = { url -> respond(url).also { opened += it } },
            limit = limit,
        )

    private val request =
        DownloadRequest(
            "https://books.example.org/get/1.epub",
            "Mozilla/5.0 Test",
            "https://books.example.org/book/1",
        )

    @Test
    fun cookiesUserAgentAndRefererAreSentAndTheFileArrives() = runBlocking {
        val body = "PK fake book".toByteArray()
        val result =
            downloader {
                    FakeConnection(
                        it,
                        200,
                        body,
                        mapOf(
                            "Content-Type" to "application/epub+zip",
                            "Content-Disposition" to "attachment; filename=\"One.epub\"",
                        ),
                    )
                }
                .download(request, target)
        assertArrayEquals(body, target.readBytes())
        assertEquals(body.size.toLong(), result.bytes)
        assertEquals("application/epub+zip", result.contentType)
        assertEquals("attachment; filename=\"One.epub\"", result.contentDisposition)
        val sent = opened.single().sent
        assertEquals("session=abc; host=books.example.org", sent["Cookie"])
        assertEquals("Mozilla/5.0 Test", sent["User-Agent"])
        assertEquals("https://books.example.org/book/1", sent["Referer"])
        assertTrue(opened.single().disconnected)
        assertFalse(opened.single().instanceFollowRedirects)
    }

    @Test
    fun httpsRedirectsAreFollowedWithThatHostsCookies() = runBlocking {
        val result =
            downloader {
                    if (it.host == "books.example.org")
                        FakeConnection(
                            it,
                            302,
                            headers = mapOf("Location" to "https://cdn.example.net/f/1.epub"),
                        )
                    else FakeConnection(it, 200, "book".toByteArray())
                }
                .download(request, target)
        assertEquals("https://cdn.example.net/f/1.epub", result.finalUrl)
        assertEquals("session=abc; host=cdn.example.net", opened[1].sent["Cookie"])
        assertEquals("book", target.readText())
    }

    @Test
    fun aRedirectToPlainHttpIsRefused() {
        val d = downloader {
            FakeConnection(
                it,
                301,
                headers = mapOf("Location" to "http://books.example.org/1.epub"),
            )
        }
        assertThrows(InsecureDownload::class.java) { runBlocking { d.download(request, target) } }
        assertEquals(1, opened.size)
        assertFalse(target.exists())
    }

    @Test
    fun aPlainHttpRequestIsRefusedBeforeConnecting() {
        val d = downloader { FakeConnection(it, 200, "x".toByteArray()) }
        assertThrows(InsecureDownload::class.java) {
            runBlocking {
                d.download(request.copy(url = "http://books.example.org/1.epub"), target)
            }
        }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun aDeclaredSizeOverTheCapIsRefused() {
        val d =
            downloader(limit = 10) { FakeConnection(it, 200, ByteArray(5), declaredLength = 11) }
        assertThrows(TooLargeBook::class.java) { runBlocking { d.download(request, target) } }
        assertFalse(target.exists())
    }

    @Test
    fun anUndeclaredBodyOverTheCapIsCutOffAndDeleted() {
        val d =
            downloader(limit = 10) { FakeConnection(it, 200, ByteArray(11), declaredLength = -1) }
        assertThrows(TooLargeBook::class.java) { runBlocking { d.download(request, target) } }
        assertFalse(target.exists())
    }

    @Test
    fun aBodyCutShortIsDeleted() {
        val d = downloader { FakeConnection(it, 200, ByteArray(5), declaredLength = 50) }
        assertThrows(IOException::class.java) { runBlocking { d.download(request, target) } }
        assertFalse(target.exists())
    }

    @Test
    fun aConnectionDroppedMidwayIsDeleted() {
        val d = downloader {
            FakeConnection(it, 200, declaredLength = -1) {
                object : InputStream() {
                    var sent = 0

                    override fun read(): Int = throw IOException("reset")

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (sent++ > 0) throw IOException("reset")
                        b[off] = 1
                        return 1
                    }
                }
            }
        }
        assertThrows(IOException::class.java) { runBlocking { d.download(request, target) } }
        assertFalse(target.exists())
    }

    @Test
    fun serverErrorsAndEndlessRedirectsFail() {
        assertThrows(IOException::class.java) {
            runBlocking { downloader { FakeConnection(it, 404) }.download(request, target) }
        }
        assertThrows(IOException::class.java) {
            runBlocking {
                downloader { FakeConnection(it, 302, headers = mapOf("Location" to "/again")) }
                    .download(request, target)
            }
        }
        assertFalse(target.exists())
    }

    @Test
    fun cancellingDeletesThePartialFile() {
        val job = Job()
        val d =
            downloader(limit = 1_000_000) {
                FakeConnection(it, 200, declaredLength = -1) {
                    object : InputStream() {
                        override fun read(): Int = 1

                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            // Stop after the first chunk is written.
                            job.cancel()
                            return len
                        }
                    }
                }
            }
        assertThrows(CancellationException::class.java) {
            runBlocking { withContext(job) { d.download(request, target) } }
        }
        assertFalse(target.exists())
    }
}
