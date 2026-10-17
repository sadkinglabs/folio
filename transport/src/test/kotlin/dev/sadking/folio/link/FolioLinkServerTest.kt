package dev.sadking.folio.link

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The real server on a free local port, called over HTTP the way KOReader calls it. */
class FolioLinkServerTest {
    private val account = KosyncAccount.fromPassword("folio", "secret")
    private val events = mutableListOf<LinkEvent>()
    private val server =
        FolioLinkServer(Kosync(MemoryKosyncStore(), { account })) {
            synchronized(events) { events += it }
        }
    private val client = HttpClient.newHttpClient()

    @After fun stop() = server.stop()

    private fun request(port: Int, path: String) =
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Accept", "application/vnd.koreader.v1+json")
            .header("x-auth-user", account.username)
            .header("x-auth-key", account.key)

    @Test
    fun koreaderStyleRequestsRoundTripOverHttp() = runBlocking {
        val port = server.start(0, "127.0.0.1")
        val auth =
            client.send(
                request(port, "/users/auth").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(200, auth.statusCode())
        assertTrue(
            auth.headers().firstValue("Content-Type").orElse("").startsWith("application/json")
        )

        val body =
            JSONObject()
                .put("document", "0b4b0f1c2a5d6e7f8091a2b3c4d5e6f7")
                .put("progress", "/body/DocFragment[3]/body/p[2]/text().0")
                .put("percentage", 0.25)
                .put("device", "Kobo")
                .put("device_id", "kobo-1")
                .toString()
        val put =
            client.send(
                request(port, "/syncs/progress")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(200, put.statusCode())

        val get =
            client.send(
                request(port, "/syncs/progress/0b4b0f1c2a5d6e7f8091a2b3c4d5e6f7").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(0.25, JSONObject(get.body()).getDouble("percentage"), 0.0)

        // The log says what happened without the book's key or any credential. The server logs a
        // request after answering it, so the last entry can land a moment after the response.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (synchronized(events) { events.size } < 3 && System.nanoTime() < deadline) Thread
            .sleep(10)
        val logged = synchronized(events) { events.toList() }
        assertEquals(
            listOf("/users/auth", "/syncs/progress", "/syncs/progress/…"),
            logged.map { it.route },
        )
        assertEquals(listOf(200, 200, 200), logged.map { it.status })
        assertTrue(logged.none { "0b4b0f1c" in it.route })
    }

    @Test
    fun oversizedBodiesAreRefusedBeforeReading() = runBlocking {
        val port = server.start(0, "127.0.0.1")
        val response =
            client.send(
                request(port, "/syncs/progress")
                    .PUT(HttpRequest.BodyPublishers.ofString("x".repeat(Kosync.MAX_BODY + 10)))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(413, response.statusCode())
    }

    @Test
    fun theServerCanBeStoppedAndStartedAgain() = runBlocking {
        val first = server.start(0, "127.0.0.1")
        server.stop()
        val second = server.start(0, "127.0.0.1")
        val health =
            client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$second/healthcheck")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(200, health.statusCode())
        assertTrue(first > 0)
    }
}
