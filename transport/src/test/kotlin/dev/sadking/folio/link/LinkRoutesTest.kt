package dev.sadking.folio.link

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LinkRoutesTest {
    private val folder = kotlin.io.path.createTempDirectory("folio-link").toFile()
    private val account = KosyncAccount.fromPassword("folio", "secret")

    private fun book(id: String, title: String, author: String): LinkBook {
        val file = File(folder, "$id.epub").apply { writeText("EPUB bytes for $title") }
        return LinkBook(id, title, listOf(author), file, "a".repeat(64), 1_700_000_000_000)
    }

    private val books =
        listOf(
            book("b1", "Pride & Prejudice", "Jane Austen"),
            book("b2", "What? A <Book>: Part 1/2", "Anon"),
        )
    private val acknowledged = mutableListOf<Triple<String, String, String>>()

    private val library =
        object : LinkLibrary {
            override fun recent(limit: Int) = books.take(limit)

            override fun shelves() = listOf(LinkShelf("s1", "Sundays", 1))

            override fun shelf(id: String) = if (id == "s1") books.take(1) else emptyList()

            override fun queue(device: String) =
                if (device == "kobo-1") books.take(1) else emptyList()

            override fun book(id: String) = books.firstOrNull { it.id == id }

            override fun acknowledge(device: String, id: String, sha256: String, path: String) =
                (sha256 == books.first { it.id == id }.sha256).also {
                    if (it) acknowledged += Triple(device, id, path)
                }
        }

    private val routes = LinkRoutes(Kosync(MemoryKosyncStore(), { account }), library, { account })

    @After fun clean() = folder.deleteRecursively().let {}

    private val basic = "Basic " + Base64.getEncoder().encodeToString("folio:secret".toByteArray())

    private fun get(path: String, headers: Map<String, String> = mapOf("Authorization" to basic)) =
        routes.handle(KosyncRequest("GET", path, headers, remote = "10.0.0.9"))

    private fun xml(response: LinkResponse) =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse((response as LinkResponse.Text).body.byteInputStream())

    @Test
    fun catalogueNeedsALoginAndAsksForOne() {
        val response = get("/opds", emptyMap())
        assertEquals(401, response.status)
        assertTrue(response.headers.getValue("WWW-Authenticate").startsWith("Basic"))
        val wrong = "Basic " + Base64.getEncoder().encodeToString("folio:nope".toByteArray())
        assertEquals(401, get("/opds", mapOf("Authorization" to wrong)).status)
    }

    @Test
    fun repeatedWrongLoginsAreThrottled() {
        val wrong =
            mapOf(
                "Authorization" to
                    "Basic " + Base64.getEncoder().encodeToString("folio:x".toByteArray())
            )
        repeat(Kosync.MAX_FAILURES) { get("/opds", wrong) }
        assertEquals(429, get("/opds").status)
    }

    @Test
    fun navigationFeedLinksToRecentAndLists() {
        val document = xml(get("/opds"))
        val links =
            document.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "link").let { nodes ->
                (0 until nodes.length).map {
                    nodes.item(it).attributes.getNamedItem("href").nodeValue
                }
            }
        assertTrue(links.containsAll(listOf("/opds/recent", "/opds/lists")))
    }

    @Test
    fun acquisitionFeedEscapesTitlesAndOffersEpubs() {
        val response = get("/opds/recent") as LinkResponse.Text
        assertTrue(response.type.contains("kind=acquisition"))
        val document = xml(response)
        val titles = document.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "title")
        val values = (0 until titles.length).map { titles.item(it).textContent }
        assertTrue(values.contains("Pride & Prejudice"))
        assertTrue(values.contains("What? A <Book>: Part 1/2"))
        assertTrue(response.body.contains("href=\"/opds/books/b1.epub\""))
        assertTrue(response.body.contains("http://opds-spec.org/acquisition"))
    }

    @Test
    fun listsAreBrowsable() {
        assertTrue((get("/opds/lists") as LinkResponse.Text).body.contains("/opds/lists/s1"))
        assertTrue(
            (get("/opds/lists/s1") as LinkResponse.Text).body.contains("Pride &amp; Prejudice")
        )
        assertEquals(404, get("/opds/lists/missing").status)
    }

    @Test
    fun downloadsUseReadableSafeFilenames() {
        val download = get("/opds/books/b2.epub") as LinkResponse.Download
        assertEquals("Anon - What A Book Part 1 2.epub", download.name)
        assertEquals("application/epub+zip", download.type)
        assertEquals(404, get("/opds/books/missing.epub").status)
    }

    @Test
    fun pluginSeesItsQueueAndConfirmsDownloads() {
        val headers = mapOf("Authorization" to basic, "x-folio-device" to "kobo-1")
        val hello = JSONObject((get("/folio/v1/hello", headers) as LinkResponse.Text).body)
        assertEquals(LinkRoutes.API_VERSION, hello.getInt("api"))
        val queue = JSONObject((get("/folio/v1/queue", headers) as LinkResponse.Text).body)
        val first = queue.getJSONArray("books").getJSONObject(0)
        assertEquals("Jane Austen - Pride & Prejudice.epub", first.getString("filename"))
        assertEquals(403, get("/folio/v1/queue", mapOf("Authorization" to basic)).status)

        fun ack(sha: String) =
            routes.handle(
                KosyncRequest(
                    "POST",
                    "/folio/v1/ack",
                    headers,
                    JSONObject()
                        .put("id", "b1")
                        .put("sha256", sha)
                        .put("path", "/mnt/onboard/b1.epub")
                        .toString(),
                )
            )
        assertEquals(409, ack("b".repeat(64)).status)
        assertEquals(200, ack("a".repeat(64)).status)
        assertEquals(listOf(Triple("kobo-1", "b1", "/mnt/onboard/b1.epub")), acknowledged)
    }

    @Test
    fun pluginMayUseKosyncHeadersToo() {
        val headers = mapOf("x-auth-user" to "folio", "x-auth-key" to account.key)
        assertEquals(200, get("/folio/v1/hello", headers).status)
        assertEquals(
            401,
            get("/folio/v1/hello", mapOf("x-auth-user" to "folio", "x-auth-key" to "x")).status,
        )
    }

    @Test
    fun progressSyncStillWorksBesideTheLibrary() {
        val headers = mapOf("x-auth-user" to "folio", "x-auth-key" to account.key)
        assertEquals(200, get("/users/auth", headers).status)
    }

    @Test
    fun filenamesAreSafeOnFat32() {
        assertEquals("Book.epub", linkFilename("", "  "))
        assertEquals("A - B.epub", linkFilename("A", "B..."))
        assertTrue(linkFilename("x".repeat(300), "y").length <= 125)
    }

    @Test
    fun aBookDownloadsIntactOverHttp() = runBlocking {
        val server = FolioLinkServer(routes)
        try {
            val port = server.start(0, "127.0.0.1")
            val response =
                HttpClient.newHttpClient()
                    .send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/opds/books/b1.epub"))
                            .header("Authorization", basic)
                            .build(),
                        HttpResponse.BodyHandlers.ofByteArray(),
                    )
            assertEquals(200, response.statusCode())
            assertArrayEquals(books[0].file.readBytes(), response.body())
            assertTrue(
                response.headers().firstValue("Content-Disposition").orElse("").contains("Pride")
            )
            val feed =
                HttpClient.newHttpClient()
                    .send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/opds"))
                            .header("Authorization", basic)
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
            assertTrue(
                feed
                    .headers()
                    .firstValue("Content-Type")
                    .orElse("")
                    .contains("profile=opds-catalog")
            )
        } finally {
            server.stop()
        }
    }
}
