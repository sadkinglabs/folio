package dev.sadking.folio

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.link.LinkLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * The real foreground service, Ktor server and Room store on the emulator, called over HTTP the way
 * KOReader's Progress sync calls it. The e-reader side is simulated; no KOReader is involved.
 */
class FolioLinkServiceTest {
    private val app
        get() = ApplicationProvider.getApplicationContext<FolioApplication>()

    @After
    fun turnOff() {
        app.link.stop()
        runBlocking { withTimeout(10_000) { app.link.port.first { it == null } } }
    }

    /**
     * HTTP/1.1 over a plain socket. The test runs inside the app, whose network policy forbids
     * cleartext through the HTTP clients; that policy is right for the app and stays as it is.
     */
    private fun call(
        port: Int,
        method: String,
        path: String,
        body: String? = null,
        key: String = app.link.settings.account().key,
        extra: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val bytes = body?.toByteArray() ?: ByteArray(0)
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                append("Accept: application/vnd.koreader.v1+json\r\n")
                append("x-auth-user: folio\r\n")
                append("x-auth-key: $key\r\n")
                extra.forEach { (name, value) -> append("$name: $value\r\n") }
                if (body != null) append("Content-Type: application/json\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(head.toByteArray())
                write(bytes)
                flush()
            }
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val status = response.substringAfter(' ').substringBefore(' ').toInt()
            return status to response.substringAfter("\r\n\r\n")
        }
    }

    @Test
    fun anEreaderCanLogInSendAndReadPositionsThroughTheService() {
        ActivityScenario.launch(MainActivity::class.java).use {
            app.link.log.clear()
            app.link.start()
            val port = runBlocking { withTimeout(15_000) { app.link.port.first { it != null }!! } }

            assertEquals(200, call(port, "GET", "/users/auth").first)
            val document = "f0e1d2c3b4a5968778695a4b3c2d1e0f"
            val sent =
                JSONObject()
                    .put("document", document)
                    .put("progress", "/body/DocFragment[5]/body/p[3]/text().12")
                    .put("percentage", 0.31)
                    .put("device", "Kobo")
                    .put("device_id", "emulated-ereader")
                    .toString()
            assertEquals(200, call(port, "PUT", "/syncs/progress", sent).first)

            val (status, text) = call(port, "GET", "/syncs/progress/$document")
            assertEquals(200, status)
            assertEquals(0.31, JSONObject(text).getDouble("percentage"), 0.0)

            val stored = runBlocking { app.database.library().kosyncReports(document) }
            assertEquals(listOf("emulated-ereader"), stored.map { it.deviceId })

            // The server logs a request after sending its response, so the last entry can lag.
            val kinds = runBlocking {
                withTimeout(5_000) {
                    app.link.log.entries
                        .first { list -> list.count { it.kind == LinkLog.Kind.REQUEST } >= 3 }
                        .map { it.kind }
                }
            }
            assertTrue(LinkLog.Kind.STARTED in kinds)
            assertEquals(3, kinds.count { it == LinkLog.Kind.REQUEST })
            assertNotNull(app.link.log.lastHeard)
        }
    }

    @Test
    fun aWrongPasswordIsRefused() {
        ActivityScenario.launch(MainActivity::class.java).use {
            app.link.start()
            val port = runBlocking { withTimeout(15_000) { app.link.port.first { it != null }!! } }
            assertEquals(401, call(port, "GET", "/users/auth", key = "not-the-key").first)
        }
    }

    @Test
    fun theFirstPluginFindsNothingQueuedButCanStillDownloadABook() {
        val id = java.util.UUID.randomUUID().toString()
        val dir = java.io.File(app.repository.root, id).apply { mkdirs() }
        val file =
            java.io.File(dir, "original.epub").apply { writeText("not really an epub, only bytes") }
        val hash = dev.sadking.folio.core.Epub.sha256(file)
        runBlocking {
            app.database
                .library()
                .insert(
                    dev.sadking.folio.data.Book(
                        id,
                        "Queued Fixture",
                        "[\"QA Author\"]",
                        originalPath = file.path,
                        currentPath = file.path,
                        originalHash = hash,
                        currentHash = hash,
                    )
                )
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                app.link.start()
                val port = runBlocking {
                    withTimeout(15_000) { app.link.port.first { it != null }!! }
                }
                val device = mapOf("x-folio-device" to "emulated-kobo")
                // Sync puts every book on the e-reader; nothing is queued for sending any more.
                val queue = JSONObject(call(port, "GET", "/folio/v1/queue", extra = device).second)
                assertEquals(0, queue.getJSONArray("books").length())
                val (status, body) = call(port, "GET", "/folio/v1/books/$id.epub", extra = device)
                assertEquals(200, status)
                assertEquals(file.readText(), body)
            }
        } finally {
            runBlocking { app.database.library().deleteLocalBook(id) }
            dir.deleteRecursively()
        }
    }
}
