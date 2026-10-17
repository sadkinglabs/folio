package dev.sadking.folio.link

import dev.sadking.folio.core.stats.KoreaderSessions
import dev.sadking.folio.core.sync.Baseline
import dev.sadking.folio.core.sync.KindleFile
import dev.sadking.folio.core.sync.PhoneAction
import dev.sadking.folio.core.sync.PhoneBook
import dev.sadking.folio.core.sync.Question
import dev.sadking.folio.core.sync.SyncAnnotation
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

private fun sha256(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** The phone's library in memory, with the same rules the app follows. */
class FakeSyncLibrary(private val folder: File) : SyncLibrary {
    val books = sortedMapOf<String, PhoneBook>()
    val files = HashMap<String, File>()
    val baselines = HashMap<String, List<Baseline>>()
    val questions = HashMap<String, List<Question>>()
    val answers = HashMap<String, MutableMap<String, String>>()
    val pendingFor = mutableSetOf<String>()
    /** Title and authors the app would read from an uploaded file, by e-reader path. */
    val incoming = HashMap<String, Pair<String, List<String>>>()
    val applied = mutableListOf<List<PhoneAction>>()
    val receivedFiles = mutableListOf<Pair<String, ByteArray>>()
    val synced = mutableListOf<String>()
    val started = mutableListOf<String>()
    /** E-reader paths whose upload the phone refuses, and why. */
    val refuse = HashMap<String, Refusal>()
    val refusedFiles = HashMap<String, List<RefusedFile>>()
    private var next = 1

    fun add(
        id: String,
        title: String,
        author: String,
        bytes: ByteArray,
        status: String = "Unread",
    ) {
        val file = File(folder, "$id.epub").apply { writeBytes(bytes) }
        files[id] = file
        books[id] =
            PhoneBook(
                id = id,
                sha256 = sha256(bytes),
                format = "epub",
                title = title,
                authors = listOf(author),
                status = status,
                statusAt = 0,
                removed = false,
                hasFile = true,
                annotations = emptyList(),
                lists = emptyList(),
            )
    }

    override fun phoneBooks() = books.values.toList()

    override fun baselines(ereaderId: String) = baselines[ereaderId].orEmpty()

    override fun bookFile(bookId: String) = files[bookId]

    override fun receive(ereaderId: String, kindlePath: String, file: File): String? {
        refuse[kindlePath]?.let { throw SyncRefusal(it) }
        val bytes = file.readBytes()
        receivedFiles += kindlePath to bytes
        val id = "p${next++}"
        val format = kindlePath.substringAfterLast('.')
        files[id] = File(folder, "$id-${bytes.size}.$format").apply { writeBytes(bytes) }
        val (title, authors) =
            incoming[kindlePath]
                ?: (kindlePath.substringAfterLast('/').substringBeforeLast('.') to emptyList())
        books[id] = PhoneBook(id, sha256(bytes), format, title, authors, "Unread", 0)
        return id
    }

    override fun revise(
        ereaderId: String,
        bookId: String,
        kindlePath: String,
        file: File,
    ): String? {
        val book = books[bookId] ?: return null
        refuse[kindlePath]?.let { throw SyncRefusal(it) }
        val bytes = file.readBytes()
        receivedFiles += kindlePath to bytes
        // A new revision sits beside the old file.
        files[bookId] =
            File(folder, "$bookId-${bytes.size}.${book.format}").apply { writeBytes(bytes) }
        books[bookId] = book.copy(sha256 = sha256(bytes), hasFile = true)
        return bookId
    }

    override fun apply(
        ereaderId: String,
        actions: List<PhoneAction>,
        received: Map<String, String>,
    ) {
        applied += actions
        for (action in actions) {
            when (action) {
                is PhoneAction.Import ->
                    edit(received.getValue(action.path)) {
                        it.copy(
                            status = action.status ?: it.status,
                            statusAt = action.statusAt ?: it.statusAt,
                            annotations = action.annotations,
                            lists = action.lists,
                        )
                    }
                is PhoneAction.Remove -> edit(action.bookId) { it.copy(removed = true) }
                is PhoneAction.Restore -> edit(action.bookId) { it.copy(removed = false) }
                is PhoneAction.SetStatus ->
                    edit(action.bookId) { it.copy(status = action.status, statusAt = action.at) }
                is PhoneAction.AddAnnotations ->
                    edit(action.bookId) {
                        it.copy(
                            annotations =
                                (it.annotations + action.annotations).distinctBy { a -> a.id }
                        )
                    }
                is PhoneAction.EditAnnotations ->
                    edit(action.bookId) { b ->
                        val edits = action.annotations.associateBy { it.id }
                        b.copy(annotations = b.annotations.map { edits[it.id] ?: it })
                    }
                is PhoneAction.DeleteAnnotations ->
                    edit(action.bookId) { b ->
                        b.copy(
                            annotations =
                                b.annotations.map {
                                    if (it.id in action.ids) it.copy(deleted = true) else it
                                }
                        )
                    }
                is PhoneAction.SetLists -> edit(action.bookId) { it.copy(lists = action.lists) }
                is PhoneAction.Revise -> Unit // revise already took the file.
            }
        }
    }

    private fun edit(id: String, change: (PhoneBook) -> PhoneBook) {
        books[id]?.let { books[id] = change(it) }
    }

    override fun commit(ereaderId: String, baselines: List<Baseline>) {
        this.baselines[ereaderId] = baselines
    }

    override fun pending(ereaderId: String) = ereaderId in pendingFor

    override fun phoneQuestions(ereaderId: String) = questions[ereaderId].orEmpty()

    override fun saveQuestions(ereaderId: String, questions: List<Question>) {
        this.questions[ereaderId] = questions
        answers[ereaderId]?.keys?.retainAll(questions.map { it.id }.toSet())
    }

    override fun phoneAnswers(ereaderId: String) = answers[ereaderId].orEmpty()

    override fun uploadDir() = File(folder, "uploads")

    override fun synced(ereaderId: String, deviceName: String, at: Long) {
        synced += ereaderId
    }

    override fun refused(ereaderId: String) = refusedFiles[ereaderId].orEmpty()

    override fun saveRefused(ereaderId: String, refused: List<RefusedFile>) {
        refusedFiles[ereaderId] = refused
    }

    override fun started(ereaderId: String, deviceName: String, at: Long) {
        started += ereaderId
    }

    val statistics = HashMap<String, ReadingStatistics>()
    /** Refuses statistics the way the app does when they fail its checks. */
    var refuseStatistics = false

    override fun saveStatistics(ereaderId: String, statistics: ReadingStatistics) {
        require(!refuseStatistics)
        this.statistics[ereaderId] = statistics
    }

    /** Sessions kept from each e-reader, in the order they were sent. */
    val sessions = HashMap<String, List<KoreaderSessions.WireSession>>()
    /** The watermark each e-reader's sessions answer; 0 when none. */
    val until = HashMap<String, Long>()
    /** Refuses sessions the way the app does when they fail its checks. */
    var refuseSessions = false
    /** The digests the phone can match to a library book. */
    var known = emptyList<String>()
    /** Names kept for each e-reader, by digest. */
    val names = HashMap<String, Map<String, Pair<String, String>>>()

    override fun sessionsUntil(ereaderId: String) = until[ereaderId] ?: 0L

    override fun saveSessions(
        ereaderId: String,
        sessions: List<KoreaderSessions.WireSession>,
    ): Int {
        require(!refuseSessions)
        this.sessions[ereaderId] = this.sessions[ereaderId].orEmpty() + sessions
        return sessions.size
    }

    override fun knownDigests() = known

    override fun saveBookNames(ereaderId: String, names: Map<String, Pair<String, String>>) {
        this.names[ereaderId] = names
    }
}

/** A Kindle running the plugin: it keeps files in memory and does what the phone asks. */
class FakeKindle(
    private val call: (KosyncRequest, InputStream?) -> LinkResponse,
    val id: String = "kindle-1",
    /** A plugin that reads notes in full and takes edits and deletions (M11). */
    var takesEdits: Boolean = false,
) {
    class Book(
        val bytes: ByteArray,
        val title: String?,
        val authors: List<String>,
        var status: String? = null,
        var statusAt: Long? = null,
        val annotations: MutableList<SyncAnnotation> = mutableListOf(),
        var collections: List<String> = emptyList(),
    )

    val books = sortedMapOf<String, Book>()
    val headers =
        mapOf(
            "Authorization" to
                "Basic " + Base64.getEncoder().encodeToString("folio:secret".toByteArray()),
            "x-folio-device" to id,
        )

    fun manifest(): List<KindleFile> = books.map { (path, book) ->
        KindleFile(
            path = path,
            size = book.bytes.size.toLong(),
            sha256 = sha256(book.bytes),
            format = path.substringAfterLast('.'),
            status = book.status,
            statusAt = book.statusAt,
            annotations = book.annotations.toList(),
            collections = book.collections,
            title = book.title,
            authors = book.authors,
            annotationsComplete = takesEdits,
        )
    }

    fun syncBody(answers: Map<String, String> = emptyMap(), files: List<KindleFile> = manifest()) =
        JSONObject()
            .put("device", JSONObject().put("id", id).put("name", "Kindle"))
            .put("home", "/mnt/us/koreader")
            .put("root", "Librillo")
            .put("files", JSONArray(files.map(SyncWire::toJson)))
            .put("answers", JSONObject(answers))
            .toString()

    fun sync(answers: Map<String, String> = emptyMap()): JSONObject {
        val response =
            call(KosyncRequest("POST", "/folio/v2/sync", headers, syncBody(answers)), null)
        assertEquals((response as LinkResponse.Text).body, 200, response.status)
        return JSONObject(response.body)
    }

    /** Runs every action in order, like the plugin, and returns the results for finish. */
    fun run(plan: JSONObject, fail: Set<Int> = emptySet()): JSONArray {
        val moved = HashMap<String, String>()
        fun at(path: String): String = moved[path]?.let(::at) ?: path
        val results = JSONArray()
        val actions = plan.getJSONArray("kindle")
        for (i in 0 until actions.length()) {
            val action = actions.getJSONObject(i)
            val index = action.getInt("index")
            var path: String? = null
            val ok =
                index !in fail &&
                    when (action.getString("op")) {
                        "download" -> {
                            val response =
                                call(KosyncRequest("GET", action.getString("url"), headers), null)
                            val to = at(action.getString("to"))
                            val old = books[to]
                            // Without replaces the target must be free; with it, it must hold
                            // exactly that file, which is replaced keeping its reading data.
                            val fits =
                                if (action.isNull("replaces")) old == null
                                else
                                    old != null && sha256(old.bytes) == action.getString("replaces")
                            if (fits && response is LinkResponse.Download) {
                                val bytes = response.file.readBytes()
                                path = to
                                books[to] =
                                    old?.let {
                                        Book(
                                            bytes,
                                            it.title,
                                            it.authors,
                                            it.status,
                                            it.statusAt,
                                            it.annotations,
                                            it.collections,
                                        )
                                    } ?: Book(bytes, null, emptyList())
                                sha256(bytes) == action.getString("sha256")
                            } else false
                        }
                        "move" -> {
                            val from = at(action.getString("from"))
                            path = action.getString("to")
                            moved[from] = path
                            books.remove(from)?.let { books[path] = it } != null
                        }
                        "delete" -> books.remove(at(action.getString("path"))) != null
                        "upload" -> {
                            val bytes = books.getValue(at(action.getString("path"))).bytes
                            call(
                                    KosyncRequest(
                                        "PUT",
                                        action.getString("url"),
                                        headers + ("Content-Length" to "${bytes.size}"),
                                    ),
                                    ByteArrayInputStream(bytes),
                                )
                                .status == 200
                        }
                        "status" -> {
                            books.getValue(at(action.getString("path"))).status =
                                action.getString("status")
                            true
                        }
                        "annotations" -> {
                            val list = action.getJSONArray("annotations")
                            books.getValue(at(action.getString("path"))).annotations +=
                                (0 until list.length()).map {
                                    SyncWire.annotation(list.getJSONObject(it))
                                }
                            true
                        }
                        "edit-annotations" -> {
                            val list = action.getJSONArray("annotations")
                            val edits =
                                (0 until list.length())
                                    .map { SyncWire.annotation(list.getJSONObject(it)) }
                                    .associateBy { it.id }
                            val notes = books.getValue(at(action.getString("path"))).annotations
                            notes.replaceAll { edits[it.id] ?: it }
                            true
                        }
                        "delete-annotations" -> {
                            val ids = action.getJSONArray("ids")
                            val gone = (0 until ids.length()).map { ids.getString(it) }.toSet()
                            books.getValue(at(action.getString("path"))).annotations.removeAll {
                                it.id in gone
                            }
                            true
                        }
                        "collections" -> {
                            val list = action.getJSONArray("collections")
                            books.getValue(at(action.getString("path"))).collections =
                                (0 until list.length()).map { list.getString(it) }
                            true
                        }
                        else -> false
                    }
            results.put(
                JSONObject().put("index", index).put("ok", ok).put("path", path ?: JSONObject.NULL)
            )
        }
        return results
    }

    fun finish(plan: JSONObject, results: JSONArray): LinkResponse =
        call(
            KosyncRequest(
                "POST",
                "/folio/v2/finish",
                headers,
                JSONObject()
                    .put("session", plan.getString("session"))
                    .put("results", results)
                    .toString(),
            ),
            null,
        )
}

class SyncRoutesTest {
    private val folder = kotlin.io.path.createTempDirectory("folio-sync").toFile()
    private val account = KosyncAccount.fromPassword("folio", "secret")
    private var clock = 1_800_000_000_000L
    private val library = FakeSyncLibrary(folder)
    private val routes =
        LinkRoutes(
            Kosync(MemoryKosyncStore(), { account }),
            library = null,
            account = { account },
            now = { clock },
            sync = library,
        )
    private val kindle = FakeKindle(routes::handle)

    @After fun clean() = folder.deleteRecursively().let {}

    private val emma = "Emma, the EPUB".toByteArray()
    private val dune = "Dune, the EPUB".toByteArray()
    private val note =
        SyncAnnotation(
            "kindle-1|2026-10-01 10:00:00|/body/p[3]",
            "Fear is the mind-killer.",
            "",
            "Chapter 1",
            "2026-10-01 10:00:00",
            "/body/p[3]",
            "/body/p[3].20",
            "highlight",
        )

    private fun uploads() = File(folder, "uploads").listFiles().orEmpty().toList()

    private fun ops(plan: JSONObject): List<String> {
        val actions = plan.getJSONArray("kindle")
        return (0 until actions.length()).map { actions.getJSONObject(it).getString("op") }
    }

    private fun action(plan: JSONObject, op: String): JSONObject {
        val actions = plan.getJSONArray("kindle")
        return (0 until actions.length())
            .map { actions.getJSONObject(it) }
            .first { it.getString("op") == op }
    }

    /** Emma is only on the phone; Dune is loose in the Kindle's documents folder. */
    private fun firstSyncLibraries() {
        library.add("b1", "Emma", "Jane Austen", emma)
        kindle.books["documents/Dune.epub"] =
            FakeKindle.Book(
                dune,
                "Dune",
                listOf("Frank Herbert"),
                "finished",
                1_700_000_000_000,
                mutableListOf(note),
                listOf("Sci-fi"),
            )
        library.incoming["documents/Dune.epub"] = "Dune" to listOf("Frank Herbert")
    }

    @Test
    fun aFirstSyncMirrorsBothLibrariesAndTheNextSyncHasNothingToDo() {
        firstSyncLibraries()
        val plan = kindle.sync()
        val download = action(plan, "download")
        assertEquals("b1", download.getString("bookId"))
        assertEquals("Librillo/Jane Austen/Emma.epub", download.getString("to"))
        assertEquals(emma.size.toLong(), download.getLong("size"))
        assertEquals("documents/Dune.epub", action(plan, "upload").getString("path"))
        assertEquals("Librillo/Frank Herbert/Dune.epub", action(plan, "move").getString("to"))
        assertEquals(0, plan.getJSONArray("questions").length())

        val results = kindle.run(plan)
        // Nothing on the phone changes until the Kindle says it is done.
        assertEquals(setOf("b1"), library.books.keys)
        assertEquals(200, kindle.finish(plan, results).status)

        assertEquals(
            setOf("Librillo/Frank Herbert/Dune.epub", "Librillo/Jane Austen/Emma.epub"),
            kindle.books.keys,
        )
        assertArrayEquals(emma, kindle.books.getValue("Librillo/Jane Austen/Emma.epub").bytes)
        val imported = library.books.values.single { it.title == "Dune" }
        assertEquals(sha256(dune), imported.sha256)
        assertEquals("Finished", imported.status)
        assertEquals(listOf(note), imported.annotations)
        assertEquals(listOf("Sci-fi"), imported.lists)
        assertEquals(
            listOf("Librillo/Frank Herbert/Dune.epub", "Librillo/Jane Austen/Emma.epub"),
            library.baselines("kindle-1").map { it.path }.sorted(),
        )
        val baseline = library.baselines("kindle-1").single { it.bookId == imported.id }
        assertEquals("finished", baseline.status)
        assertEquals(setOf(note.id), baseline.annotationIds)
        assertEquals(setOf("Sci-fi"), baseline.collections)
        assertTrue("temporary uploads are cleaned up", uploads().isEmpty())
        assertEquals(listOf("kindle-1"), library.synced)

        val again = kindle.sync()
        assertEquals(emptyList<String>(), ops(again))
        assertEquals(0, again.getJSONArray("questions").length())
        assertEquals(200, kindle.finish(again, JSONArray()).status)
        assertEquals(emptyList<PhoneAction>(), library.applied.last())
    }

    @Test
    fun aFailedUploadImportsNothingAndAFailedDownloadRecordsNoBaseline() {
        firstSyncLibraries()
        val plan = kindle.sync()
        val failed =
            setOf(action(plan, "upload").getInt("index"), action(plan, "download").getInt("index"))
        assertEquals(200, kindle.finish(plan, kindle.run(plan, failed)).status)
        assertEquals(setOf("b1"), library.books.keys)
        assertTrue(library.receivedFiles.isEmpty())
        assertTrue(library.applied.single().none { it is PhoneAction.Import })
        assertTrue(library.baselines("kindle-1").isEmpty())
        assertTrue(uploads().isEmpty())
    }

    @Test
    fun aBookDeletedOnTheKindleGoesToRecentlyRemovedAndItsBaselineIsForgotten() {
        library.add("b1", "Emma", "Jane Austen", emma)
        library.baselines["kindle-1"] =
            listOf(
                Baseline(
                    "b1",
                    "Librillo/Jane Austen/Emma.epub",
                    sha256(emma),
                    "unread",
                    0,
                    emptySet(),
                    emptySet(),
                )
            )
        val plan = kindle.sync()
        assertEquals(emptyList<String>(), ops(plan))
        assertEquals(200, kindle.finish(plan, JSONArray()).status)
        assertTrue(library.books.getValue("b1").removed)
        // Restoring it later sends it back: nothing remembers it was on the Kindle.
        assertTrue(library.baselines("kindle-1").isEmpty())
    }

    @Test
    fun questionsAreKeptForThePhoneAndAnAnswerFromEitherSideIsUsed() {
        library.add("b1", "Emma", "Jane Austen", emma, status = "Finished")
        kindle.books["Librillo/Jane Austen/Emma.epub"] =
            FakeKindle.Book(emma, "Emma", listOf("Jane Austen"), "abandoned", 5)
        library.baselines["kindle-1"] =
            listOf(
                Baseline(
                    "b1",
                    "Librillo/Jane Austen/Emma.epub",
                    sha256(emma),
                    "reading",
                    0,
                    emptySet(),
                    emptySet(),
                )
            )
        val plan = kindle.sync()
        val question = plan.getJSONArray("questions").getJSONObject(0)
        assertEquals("status", question.getString("kind"))
        assertEquals(200, kindle.finish(plan, kindle.run(plan)).status)
        assertEquals(
            listOf(question.getString("id")),
            library.phoneQuestions("kindle-1").map { it.id },
        )
        // The baseline keeps the old status while the question is open.
        assertEquals("reading", library.baselines("kindle-1").single().status)

        val waiting = kindle.sync()
        assertEquals(1, waiting.getInt("phoneQuestions"))
        library.answers["kindle-1"] =
            mutableMapOf(question.getString("id") to question.getJSONArray("options").getString(0))
        val answeredOnPhone = kindle.sync()
        assertEquals(0, answeredOnPhone.getJSONArray("questions").length())

        library.answers.clear()
        val answeredOnKindle =
            kindle.sync(
                mapOf(question.getString("id") to question.getJSONArray("options").getString(1))
            )
        assertEquals(0, answeredOnKindle.getJSONArray("questions").length())
        assertEquals(200, kindle.finish(answeredOnKindle, kindle.run(answeredOnKindle)).status)
        assertTrue(library.phoneQuestions("kindle-1").isEmpty())
        assertEquals(1, library.baselines("kindle-1").size)
    }

    @Test
    fun syncNeedsTheSameLoginAsThePlugin() {
        val anonymous =
            KosyncRequest(
                "POST",
                "/folio/v2/sync",
                mapOf("x-folio-device" to "kindle-1"),
                kindle.syncBody(),
            )
        assertEquals(401, routes.handle(anonymous).status)
        val wrong = mapOf("x-auth-user" to "folio", "x-auth-key" to "nope")
        assertEquals(401, routes.handle(KosyncRequest("GET", "/folio/v2/hello", wrong)).status)
        val right = mapOf("x-auth-user" to "folio", "x-auth-key" to account.key)
        assertEquals(200, routes.handle(KosyncRequest("GET", "/folio/v2/hello", right)).status)
    }

    @Test
    fun helloSaysWhetherThePhoneHasChangesForThisKindle() {
        library.pendingFor += "kindle-1"
        val hello =
            JSONObject(
                (routes.handle(KosyncRequest("GET", "/folio/v2/hello", kindle.headers))
                        as LinkResponse.Text)
                    .body
            )
        assertEquals(2, hello.getInt("api"))
        assertEquals("Librillo", hello.getString("name"))
        assertTrue(hello.getBoolean("pending"))
        val other = kindle.headers + ("x-folio-device" to "kobo-2")
        val quiet =
            JSONObject(
                (routes.handle(KosyncRequest("GET", "/folio/v2/hello", other)) as LinkResponse.Text)
                    .body
            )
        assertFalse(quiet.getBoolean("pending"))
    }

    @Test
    fun withoutASyncLibraryV2IsNotFoundAndV1StillWorks() {
        val plain =
            LinkRoutes(Kosync(MemoryKosyncStore(), { account }), EmptyLinkLibrary, { account })
        assertEquals(
            404,
            plain.handle(KosyncRequest("GET", "/folio/v2/hello", kindle.headers)).status,
        )
        assertEquals(
            200,
            plain.handle(KosyncRequest("GET", "/folio/v1/hello", kindle.headers)).status,
        )
        assertEquals(200, routes.handle(KosyncRequest("GET", "/healthcheck")).status)
    }

    @Test
    fun aSessionEndsThirtyMinutesAfterItsLastRequest() {
        library.add("b1", "Emma", "Jane Austen", emma)
        val plan = kindle.sync()
        val url = action(plan, "download").getString("url")
        fun download() = routes.handle(KosyncRequest("GET", url, kindle.headers)).status
        clock += 20 * 60_000
        assertEquals(200, download())
        clock += 20 * 60_000
        assertEquals(200, download())
        clock += SyncRoutes.SESSION_MS + 1
        assertEquals(410, download())
        assertEquals(410, kindle.finish(plan, JSONArray()).status)
        assertTrue(library.applied.isEmpty())
    }

    @Test
    fun aNewSyncReplacesTheKindlesOldSession() {
        library.add("b1", "Emma", "Jane Austen", emma)
        val first = kindle.sync()
        val second = kindle.sync()
        assertNotEquals(first.getString("session"), second.getString("session"))
        assertEquals(
            410,
            routes
                .handle(
                    KosyncRequest("GET", action(first, "download").getString("url"), kindle.headers)
                )
                .status,
        )
        assertEquals(
            200,
            routes
                .handle(
                    KosyncRequest(
                        "GET",
                        action(second, "download").getString("url"),
                        kindle.headers,
                    )
                )
                .status,
        )
    }

    @Test
    fun onlyBooksNamedInTheSessionCanBeDownloaded() {
        library.add("b1", "Emma", "Jane Austen", emma)
        library.add("b2", "Persuasion", "Jane Austen", "Persuasion".toByteArray())
        kindle.books["Librillo/Jane Austen/Persuasion.epub"] =
            FakeKindle.Book("Persuasion".toByteArray(), "Persuasion", listOf("Jane Austen"))
        val plan = kindle.sync()
        val session = plan.getString("session")
        fun get(id: String, session: String) =
            routes
                .handle(
                    KosyncRequest("GET", "/folio/v2/books/$id?session=$session", kindle.headers)
                )
                .status
        assertEquals(200, get("b1", session))
        assertEquals(403, get("b2", session))
        assertEquals(410, get("b1", "not-a-session"))
    }

    @Test
    fun onlyPathsNamedInTheSessionCanBeUploaded() {
        firstSyncLibraries()
        val plan = kindle.sync()
        val session = plan.getString("session")
        fun put(path: String, bytes: ByteArray) =
            routes
                .handle(
                    KosyncRequest(
                        "PUT",
                        "/folio/v2/upload?session=$session&path=${SyncWire.encode(path)}",
                        kindle.headers,
                    ),
                    ByteArrayInputStream(bytes),
                )
                .status
        assertEquals(403, put("documents/Other.epub", dune))
        // The right path with different bytes from the ones listed.
        assertEquals(409, put("documents/Dune.epub", "something else".toByteArray()))
        assertEquals(200, put("documents/Dune.epub", dune))
        assertEquals(1, uploads().size)
        assertEquals(
            410,
            routes
                .handle(
                    KosyncRequest(
                        "PUT",
                        "/folio/v2/upload?session=x&path=documents%2FDune.epub",
                        kindle.headers,
                    ),
                    ByteArrayInputStream(dune),
                )
                .status,
        )
    }

    @Test
    fun oversizeUploadsAreRefused() {
        firstSyncLibraries()
        val plan = kindle.sync()
        val url = action(plan, "upload").getString("url")
        val unread =
            object : InputStream() {
                override fun read(): Int = throw AssertionError("an oversize body must not be read")
            }
        val declared = kindle.headers + ("Content-Length" to "${SyncRoutes.MAX_UPLOAD + 1}")
        assertEquals(413, routes.handle(KosyncRequest("PUT", url, declared), unread).status)

        // No length given: the copy stops once it passes the limit and nothing is kept.
        val small = SyncRoutes(library, now = { clock }, maxUpload = 8)
        val session =
            JSONObject(
                (small.handle(
                        KosyncRequest("POST", "/folio/v2/sync", kindle.headers, kindle.syncBody()),
                        "/sync",
                    ) as LinkResponse.Text)
                    .body
            )
        val upload = action(session, "upload").getString("url")
        assertEquals(
            413,
            small
                .handle(
                    KosyncRequest("PUT", upload, kindle.headers),
                    "/upload",
                    ByteArrayInputStream(dune),
                )
                .status,
        )
        assertTrue(uploads().isEmpty())
    }

    private fun emmaOnBothSides(kindleBytes: ByteArray, phoneBytes: ByteArray, base: ByteArray) {
        library.add("b1", "Emma", "Jane Austen", phoneBytes)
        kindle.books["Librillo/Jane Austen/Emma.epub"] =
            FakeKindle.Book(kindleBytes, "Emma", listOf("Jane Austen"))
        library.baselines["kindle-1"] =
            listOf(
                Baseline(
                    "b1",
                    "Librillo/Jane Austen/Emma.epub",
                    sha256(base),
                    "unread",
                    0,
                    emptySet(),
                    emptySet(),
                )
            )
    }

    @Test
    fun aFileEditedOnTheKindleBecomesANewRevisionOfTheSameBook() {
        val edited = "Emma, corrected on the Kindle".toByteArray()
        emmaOnBothSides(kindleBytes = edited, phoneBytes = emma, base = emma)
        val plan = kindle.sync()
        assertEquals(listOf("upload"), ops(plan))
        assertEquals(200, kindle.finish(plan, kindle.run(plan)).status)
        assertEquals(setOf("b1"), library.books.keys)
        assertEquals(sha256(edited), library.books.getValue("b1").sha256)
        assertArrayEquals(edited, library.receivedFiles.single().second)
        assertEquals(sha256(edited), library.baselines("kindle-1").single().sha256)
        assertEquals(emptyList<String>(), ops(kindle.sync()))
    }

    @Test
    fun aFileEditedOnThePhoneReplacesTheKindlesCopyInPlace() {
        val edited = "Emma, corrected on the phone".toByteArray()
        emmaOnBothSides(kindleBytes = emma, phoneBytes = edited, base = emma)
        kindle.books.getValue("Librillo/Jane Austen/Emma.epub").annotations += note
        val plan = kindle.sync()
        val download = action(plan, "download")
        assertEquals(sha256(emma), download.getString("replaces"))
        assertEquals("Librillo/Jane Austen/Emma.epub", download.getString("to"))
        assertEquals(200, kindle.finish(plan, kindle.run(plan)).status)
        val onKindle = kindle.books.getValue("Librillo/Jane Austen/Emma.epub")
        assertArrayEquals(edited, onKindle.bytes)
        assertEquals("notes stay with the file", listOf(note), onKindle.annotations)
        assertEquals(listOf(note), library.books.getValue("b1").annotations)
        assertEquals(sha256(edited), library.baselines("kindle-1").single().sha256)
        assertEquals(emptyList<String>(), ops(kindle.sync()))
    }

    @Test
    fun aDownloadIntoAnOccupiedPathIsRefusedByThePluginAndNothingIsRecorded() {
        library.add("b1", "Emma", "Jane Austen", emma)
        val plan = kindle.sync()
        // The owner copies another file to the same place before the download runs.
        kindle.books["Librillo/Jane Austen/Emma.epub"] =
            FakeKindle.Book(dune, "Dune", listOf("Frank Herbert"))
        assertEquals(200, kindle.finish(plan, kindle.run(plan)).status)
        assertArrayEquals(dune, kindle.books.getValue("Librillo/Jane Austen/Emma.epub").bytes)
        assertTrue(library.baselines("kindle-1").isEmpty())
    }

    @Test
    fun aBookRemovedOnThePhoneIsDeletedFromTheKindleAndForgotten() {
        emmaOnBothSides(kindleBytes = emma, phoneBytes = emma, base = emma)
        library.books["b1"] = library.books.getValue("b1").copy(removed = true)
        val plan = kindle.sync()
        assertEquals(listOf("delete"), ops(plan))
        assertEquals(200, kindle.finish(plan, kindle.run(plan)).status)
        assertTrue(kindle.books.isEmpty())
        assertTrue(library.baselines("kindle-1").isEmpty())
        // Restored on the phone, it is sent again rather than taken for a Kindle deletion.
        library.books["b1"] = library.books.getValue("b1").copy(removed = false)
        assertEquals(listOf("download"), ops(kindle.sync()))
    }

    @Test
    fun malformedManifestsAreRefused() {
        val good =
            KindleFile(
                "documents/Dune.epub",
                4,
                sha256(dune),
                "epub",
                "reading",
                5,
                listOf(note),
                listOf("Sci-fi"),
                "Dune",
                listOf("Frank Herbert"),
            )
        assertEquals(good, SyncWire.kindleFile(SyncWire.toJson(good)))
        fun status(file: KindleFile) =
            routes
                .handle(
                    KosyncRequest(
                        "POST",
                        "/folio/v2/sync",
                        kindle.headers,
                        kindle.syncBody(files = listOf(file)),
                    )
                )
                .status
        assertEquals(200, status(good))
        assertEquals(403, status(good.copy(path = "../etc/Dune.epub")))
        assertEquals(403, status(good.copy(path = "/mnt/us/Dune.epub")))
        assertEquals(403, status(good.copy(path = "documents//Dune.epub")))
        assertEquals(403, status(good.copy(sha256 = "abc")))
        assertEquals(403, status(good.copy(format = "mobi")))
        assertEquals(403, status(good.copy(status = "complete")))
        assertEquals(
            403,
            routes.handle(KosyncRequest("POST", "/folio/v2/sync", kindle.headers, "{")).status,
        )
        val twice = kindle.syncBody(files = listOf(good, good))
        assertEquals(
            403,
            routes.handle(KosyncRequest("POST", "/folio/v2/sync", kindle.headers, twice)).status,
        )
    }

    @Test
    fun aLargeManifestAndAnUploadTravelOverHttp() = runBlocking {
        val big = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val notes = (1..400).map { note.copy(id = "n$it", text = "x".repeat(100)) }
        kindle.books["documents/Big.epub"] =
            FakeKindle.Book(big, "Big", listOf("Anon"), annotations = notes.toMutableList())
        library.incoming["documents/Big.epub"] = "Big" to listOf("Anon")
        val server = FolioLinkServer(routes)
        try {
            val port = server.start(0, "127.0.0.1")
            val client = HttpClient.newHttpClient()
            fun request(path: String) =
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).apply {
                    kindle.headers.forEach { (name, value) -> header(name, value) }
                }
            val body = kindle.syncBody()
            assertTrue("bigger than v1's limit", body.length > Kosync.MAX_BODY)
            val sync =
                client.send(
                    request("/folio/v2/sync")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(200, sync.statusCode())
            val plan = JSONObject(sync.body())
            val upload = action(plan, "upload")
            val put =
                client.send(
                    request(upload.getString("url"))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(big))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(put.body(), 200, put.statusCode())
            assertEquals(sha256(big), JSONObject(put.body()).getString("sha256"))
            val results =
                JSONArray().put(JSONObject().put("index", upload.getInt("index")).put("ok", true))
            val finish =
                client.send(
                    request("/folio/v2/finish")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                JSONObject()
                                    .put("session", plan.getString("session"))
                                    .put("results", results)
                                    .toString()
                            )
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(200, finish.statusCode())
            assertArrayEquals(big, library.receivedFiles.single().second)
        } finally {
            server.stop()
        }
    }

    // ---- Files the phone refuses ----

    @Test
    fun aFileThePhoneRefusesIsRememberedAndNotUploadedAgainUntilItChanges() {
        firstSyncLibraries()
        library.refuse["documents/Dune.epub"] = Refusal.NOT_A_BOOK
        val plan = kindle.sync()
        assertEquals(listOf("kindle-1"), library.started)
        assertEquals(200, kindle.finish(plan, kindle.run(plan)).status)
        assertTrue(library.books.values.none { it.title == "Dune" })
        assertEquals(
            listOf(RefusedFile(sha256(dune), "documents/Dune.epub", Refusal.NOT_A_BOOK, clock)),
            library.refused("kindle-1"),
        )
        assertEquals("Dune.epub", library.refused("kindle-1").single().name)
        assertTrue("its temporary upload is gone", uploads().isEmpty())

        // Wherever the Kindle keeps it, the same content is not sent again.
        val again = kindle.sync()
        assertEquals(emptyList<String>(), ops(again))
        assertEquals(200, kindle.finish(again, kindle.run(again)).status)
        assertEquals(1, library.refused("kindle-1").size)

        // A new version of the file is offered again, and the old refusal is forgotten.
        val path = kindle.books.keys.single { it.endsWith("Dune.epub") }
        val fixed = "Dune, repaired".toByteArray()
        kindle.books[path] = FakeKindle.Book(fixed, "Dune", listOf("Frank Herbert"))
        library.refuse.clear()
        val third = kindle.sync()
        assertEquals(listOf("upload"), ops(third))
        assertEquals(200, kindle.finish(third, kindle.run(third)).status)
        assertEquals(sha256(fixed), library.books.values.single { it.title == "Dune" }.sha256)
        assertTrue(library.refused("kindle-1").isEmpty())
    }

    @Test
    fun aRefusedRevisionIsRememberedAndTheBookKeepsItsFile() {
        val edited = "Emma, scrambled on the Kindle".toByteArray()
        emmaOnBothSides(kindleBytes = edited, phoneBytes = emma, base = emma)
        library.refuse["Librillo/Jane Austen/Emma.epub"] = Refusal.OTHER_FORMAT
        val plan = kindle.sync()
        assertEquals(listOf("upload"), ops(plan))
        assertEquals(200, kindle.finish(plan, kindle.run(plan)).status)
        assertEquals(sha256(emma), library.books.getValue("b1").sha256)
        assertEquals(
            listOf(Refusal.OTHER_FORMAT),
            library.refused("kindle-1").map { it.reason },
        )
        assertEquals(emptyList<String>(), ops(kindle.sync()))
    }

    @Test
    fun anOversizeUploadIsRememberedAsTooLarge() {
        val small = SyncRoutes(library, now = { clock }, maxUpload = 8)
        val reader =
            FakeKindle({ request, body ->
                small.handle(
                    request,
                    request.path.removePrefix("/folio/v2").substringBefore('?'),
                    body,
                )
            })
        reader.books["documents/Dune.epub"] = FakeKindle.Book(dune, "Dune", listOf("Frank Herbert"))
        val plan = reader.sync()
        val results = reader.run(plan)
        assertFalse(results.getJSONObject(action(plan, "upload").getInt("index")).getBoolean("ok"))
        assertEquals(200, reader.finish(plan, results).status)
        assertTrue(library.receivedFiles.isEmpty())
        assertEquals(
            listOf(Refusal.TOO_LARGE),
            library.refused("kindle-1").map { it.reason },
        )
        assertTrue(ops(reader.sync()).none { it == "upload" })
    }

    @Test
    fun aRefusedFileThatLeavesTheKindleIsForgotten() {
        library.refusedFiles["kindle-1"] =
            listOf(RefusedFile(sha256(dune), "documents/Dune.epub", Refusal.TOO_LARGE, 1))
        kindle.books["documents/Dune.epub"] = FakeKindle.Book(dune, "Dune", listOf("Frank Herbert"))
        val kept = kindle.sync()
        assertEquals(emptyList<String>(), ops(kept))
        assertEquals(200, kindle.finish(kept, kindle.run(kept)).status)
        assertEquals(1, library.refused("kindle-1").size)

        kindle.books.clear()
        val gone = kindle.sync()
        assertEquals(200, kindle.finish(gone, kindle.run(gone)).status)
        assertTrue(library.refused("kindle-1").isEmpty())
    }

    // ---- Rule 7: deletions and edits (M11) ----

    /** Emma on both sides with [note], as agreed at the last sync. */
    private fun emmaWithNote(kindleTakesEdits: Boolean) {
        library.add("b1", "Emma", "Jane Austen", emma)
        library.books["b1"] = library.books.getValue("b1").copy(annotations = listOf(note))
        kindle.books["Librillo/Jane Austen/Emma.epub"] =
            FakeKindle.Book(emma, "Emma", listOf("Jane Austen"), annotations = mutableListOf(note))
        kindle.takesEdits = kindleTakesEdits
        val first = kindle.sync()
        assertEquals(200, kindle.finish(first, kindle.run(first)).status)
        assertEquals(setOf(note.id), library.baselines("kindle-1").single().annotationIds)
    }

    @Test
    fun aHighlightDeletedOnThePhoneIsDeletedOnAKindleThatTakesIt() {
        emmaWithNote(kindleTakesEdits = true)
        assertNotNull(library.baselines("kindle-1").single().annotationStates)
        library.books["b1"] =
            library.books.getValue("b1").copy(annotations = listOf(note.copy(deleted = true)))
        val plan = kindle.sync()
        assertEquals(listOf("delete-annotations"), ops(plan))
        assertEquals(200, kindle.finish(plan, kindle.run(plan)).status)
        assertTrue(kindle.books.getValue("Librillo/Jane Austen/Emma.epub").annotations.isEmpty())
        assertTrue(library.baselines("kindle-1").single().annotationIds.isEmpty())
        assertEquals(emptyList<String>(), ops(kindle.sync()))
    }

    @Test
    fun aHighlightDeletedOnTheKindleIsDeletedOnThePhoneSoftly() {
        emmaWithNote(kindleTakesEdits = true)
        kindle.books.getValue("Librillo/Jane Austen/Emma.epub").annotations.clear()
        val plan = kindle.sync()
        assertEquals(emptyList<String>(), ops(plan))
        assertEquals(200, kindle.finish(plan, kindle.run(plan)).status)
        assertEquals(
            listOf(PhoneAction.DeleteAnnotations("b1", listOf(note.id))),
            library.applied.last(),
        )
        assertTrue(library.books.getValue("b1").annotations.single().deleted)
        assertEquals(emptyList<String>(), ops(kindle.sync()))
    }

    @Test
    fun anOldPluginNeverCausesADeletion() {
        emmaWithNote(kindleTakesEdits = false)
        assertNull(library.baselines("kindle-1").single().annotationStates)
        // Deleted on the Kindle: it goes back, as before.
        kindle.books.getValue("Librillo/Jane Austen/Emma.epub").annotations.clear()
        val plan = kindle.sync()
        assertEquals(listOf("annotations"), ops(plan))
        assertEquals(200, kindle.finish(plan, kindle.run(plan)).status)
        assertFalse(library.books.getValue("b1").annotations.single().deleted)
        assertEquals(
            listOf(note),
            kindle.books.getValue("Librillo/Jane Austen/Emma.epub").annotations,
        )
        // Deleted on the phone: the Kindle is not asked to delete it.
        library.books["b1"] =
            library.books.getValue("b1").copy(annotations = listOf(note.copy(deleted = true)))
        assertEquals(emptyList<String>(), ops(kindle.sync()))
    }

    @Test
    fun aColourAndANoteTravelOnTheWire() {
        emmaWithNote(kindleTakesEdits = true)
        val edited =
            note.copy(
                note = "Litany",
                kind = "note",
                color = "green",
                drawer = "underscore",
                updated = "2026-10-17 09:00:00",
            )
        library.books["b1"] = library.books.getValue("b1").copy(annotations = listOf(edited))
        val plan = kindle.sync()
        val sent = action(plan, "edit-annotations").getJSONArray("annotations").getJSONObject(0)
        assertEquals("green", sent.getString("color"))
        assertEquals("underscore", sent.getString("drawer"))
        assertEquals("2026-10-17 09:00:00", sent.getString("updated"))
        assertEquals(200, kindle.finish(plan, kindle.run(plan)).status)
        assertEquals(
            listOf(edited),
            kindle.books.getValue("Librillo/Jane Austen/Emma.epub").annotations,
        )
        assertEquals(emptyList<String>(), ops(kindle.sync()))
    }

    @Test
    fun anOddColourFromThePluginIsTakenAsUnknown() {
        val json = SyncWire.toJson(note).put("color", "x".repeat(40)).put("drawer", "LIGHTEN")
        val read = SyncWire.annotation(json)
        assertEquals("", read.color)
        assertEquals("lighten", read.drawer)
        assertFalse("a tombstone flag never comes from the wire", read.deleted)
        assertFalse(SyncWire.toJson(note.copy(deleted = true)).has("deleted"))
    }

    private fun statisticsBody(
        days: JSONArray =
            JSONArray()
                .put(JSONObject().put("date", "2026-10-14").put("seconds", 2700))
                .put(JSONObject().put("date", "2026-10-13").put("seconds", 1500)),
        change: JSONObject.() -> Unit = {},
    ) =
        JSONObject()
            .put("device", JSONObject().put("id", "kindle-1").put("name", "Kindle"))
            .put("format", "folio-statistics")
            .put("version", 1)
            .put("seconds", 43_440)
            .put("exportedAt", 1_791_750_000)
            .put("days", days)
            .apply(change)
            .toString()

    private fun postStatistics(body: String, headers: Map<String, String> = kindle.headers) =
        routes.handle(KosyncRequest("POST", "/folio/v2/statistics", headers, body))

    @Test
    fun readingStatisticsAreKeptForTheEreaderThatSentThem() {
        val response = postStatistics(statisticsBody()) as LinkResponse.Text
        assertEquals(200, response.status)
        assertTrue(JSONObject(response.body).getBoolean("ok"))
        assertEquals(
            ReadingStatistics(
                43_440,
                1_791_750_000,
                listOf(ReadingDay("2026-10-14", 2700), ReadingDay("2026-10-13", 1500)),
            ),
            library.statistics.getValue("kindle-1"),
        )
        // No sync session is needed, and none is started.
        assertTrue(library.started.isEmpty() && library.synced.isEmpty())
        // Only after the login check.
        assertEquals(401, postStatistics(statisticsBody(), mapOf("x-folio-device" to "x")).status)
    }

    @Test
    fun malformedReadingStatisticsAreRefusedAndNothingIsKept() {
        val tooMany = JSONArray()
        repeat(SyncRoutes.MAX_DAYS + 1) {
            tooMany.put(JSONObject().put("date", "2026-01-01").put("seconds", 1))
        }
        listOf(
                "{",
                "[]",
                statisticsBody { put("format", "other") },
                statisticsBody { put("version", 2) },
                statisticsBody { remove("seconds") },
                statisticsBody { put("seconds", "lots") },
                statisticsBody { remove("exportedAt") },
                statisticsBody { put("device", JSONObject().put("id", " ")) },
                statisticsBody { remove("device") },
                statisticsBody(JSONArray().put(JSONObject().put("date", "2026-10-14"))),
                statisticsBody(JSONArray().put("2026-10-14")),
                statisticsBody(tooMany),
            )
            .forEach { assertEquals(it, 403, postStatistics(it).status) }
        // The phone's own checks refuse the rest.
        library.refuseStatistics = true
        assertEquals(403, postStatistics(statisticsBody()).status)
        assertTrue(library.statistics.isEmpty())
    }

    private val digest = "0123456789abcdef0123456789abcdef"

    private fun sessionJson(
        book: String = digest,
        start: Long = 1_791_740_000L,
        seconds: Long = 1260,
    ): JSONObject =
        JSONObject()
            .put("book", book)
            .put("start", start)
            .put("seconds", seconds)
            .put("pages", 14)
            .put("from", 0.4012)
            .put("to", 0.4421)
            .put("read", 0.0409)

    private fun sessionsBody(
        sessions: JSONArray,
        books: JSONObject? = null,
        change: JSONObject.() -> Unit = {},
    ) =
        JSONObject()
            .put("device", JSONObject().put("id", "kindle-1").put("name", "Kindle"))
            .put("format", "folio-statistics")
            .put("version", 2)
            .put("exportedAt", 1_791_750_000)
            .put("sessions", sessions)
            .apply { if (books != null) put("books", books) }
            .apply(change)
            .toString()

    private fun postSessions(body: String) = postStatistics(body) as LinkResponse.Text

    private fun named(
        digest: String = this.digest,
        title: String = "Dune",
        authors: String = "Frank Herbert",
    ) = JSONObject().put(digest, JSONObject().put("title", title).put("authors", authors))

    @Test
    fun statisticsGetAnswersTheWatermarkAndTheKnownDigests() {
        library.until["kindle-1"] = 1_791_750_000L
        library.known = listOf(digest)
        val response =
            routes.handle(KosyncRequest("GET", "/folio/v2/statistics", kindle.headers))
                as LinkResponse.Text
        assertEquals(200, response.status)
        val answer = JSONObject(response.body)
        assertEquals("folio-statistics", answer.getString("format"))
        assertEquals("[1,2]", answer.getJSONArray("versions").toString())
        assertEquals(1_791_750_000L, answer.getLong("until"))
        assertEquals("[\"$digest\"]", answer.getJSONArray("known").toString())
        // Without the device header, or with a blank one, it is refused.
        val noDevice = kindle.headers.filterKeys { it != "x-folio-device" }
        assertEquals(
            403,
            routes.handle(KosyncRequest("GET", "/folio/v2/statistics", noDevice)).status,
        )
        assertEquals(
            403,
            routes
                .handle(
                    KosyncRequest(
                        "GET",
                        "/folio/v2/statistics",
                        kindle.headers + ("x-folio-device" to " "),
                    )
                )
                .status,
        )
    }

    @Test
    fun aVersionTwoBodyKeepsTheGoodSessionsAndSkipsTheBadOne() {
        val body =
            sessionsBody(
                JSONArray()
                    .put(sessionJson())
                    .put(sessionJson(start = 1_791_741_000L))
                    .put(sessionJson(book = "abc"))
            )
        val response = postSessions(body)
        assertEquals(200, response.status)
        val answer = JSONObject(response.body)
        assertTrue(answer.getBoolean("ok"))
        assertEquals(2, answer.getInt("kept"))
        assertEquals(1, answer.getInt("skipped"))
        assertEquals(
            KoreaderSessions.WireSession(digest, 1_791_740_000L, 1260, 14, 0.4012, 0.4421, 0.0409),
            library.sessions.getValue("kindle-1")[0],
        )
        assertEquals(2, library.sessions.getValue("kindle-1").size)
        // Sessions are not a sync, so none is started.
        assertTrue(library.started.isEmpty() && library.synced.isEmpty())
    }

    @Test
    fun aMissingOrNullFractionIsUnknown() {
        val missing = sessionJson(start = 1_791_741_000L).apply { remove("from") }
        val nulled = sessionJson(start = 1_791_742_000L).put("read", JSONObject.NULL)
        assertEquals(
            200,
            postSessions(sessionsBody(JSONArray().put(missing).put(nulled))).status,
        )
        val kept = library.sessions.getValue("kindle-1")
        assertNull(kept[0].from)
        assertEquals(0.0409, kept[0].read)
        assertNull(kept[1].read)
        assertEquals(0.4012, kept[1].from)
    }

    @Test
    fun aVersionTwoBodyThatIsTooLargeOrMisshapenIsRefused() {
        val many = JSONArray()
        repeat(KoreaderSessions.MAX_PER_REQUEST + 1) { many.put(sessionJson()) }
        listOf(
                sessionsBody(many),
                sessionsBody(JSONArray(), change = { put("sessions", sessionJson()) }),
                sessionsBody(JSONArray(), change = { put("version", 3) }),
                sessionsBody(JSONArray(), change = { put("exportedAt", 0) }),
                sessionsBody(JSONArray().put(sessionJson().apply { remove("pages") })),
                sessionsBody(JSONArray().put(sessionJson().put("seconds", "lots"))),
            )
            .forEach { assertEquals(it, 403, postStatistics(it).status) }
        // The phone's own checks refuse the rest, and nothing is kept.
        library.refuseSessions = true
        assertEquals(403, postStatistics(sessionsBody(JSONArray().put(sessionJson()))).status)
        assertTrue(library.sessions.isEmpty())
    }

    @Test
    fun namesForBooksThePhoneCannotMatchAreKept() {
        val books =
            named().apply {
                put(
                    "fedcba9876543210fedcba9876543210",
                    JSONObject().put("title", "Emma").put("authors", ""),
                )
            }
        val response = postSessions(sessionsBody(JSONArray().put(sessionJson()), books = books))
        assertEquals(200, response.status)
        assertEquals(
            mapOf(
                digest to ("Dune" to "Frank Herbert"),
                "fedcba9876543210fedcba9876543210" to ("Emma" to ""),
            ),
            library.names["kindle-1"],
        )
    }

    @Test
    fun anUntitledBookIsSkippedAndTheRestIsKept() {
        // One untitled book on the e-reader must not stop its sessions or the other names.
        val books =
            named().apply {
                put(
                    "fedcba9876543210fedcba9876543210",
                    JSONObject().put("title", " ").put("authors", ""),
                )
            }
        val response = postSessions(sessionsBody(JSONArray().put(sessionJson()), books = books))
        assertEquals(200, response.status)
        assertEquals(1, JSONObject(response.body).getInt("kept"))
        assertEquals(mapOf(digest to ("Dune" to "Frank Herbert")), library.names["kindle-1"])
    }

    @Test
    fun aBodyWithoutBooksStillWorks() {
        val response = postSessions(sessionsBody(JSONArray().put(sessionJson())))
        assertEquals(200, response.status)
        assertEquals(1, JSONObject(response.body).getInt("kept"))
        assertNull(library.names["kindle-1"])
    }

    @Test
    fun aBadNameRefusesTheWholeBodyAndNothingIsKept() {
        listOf(
                named(digest = "0123456789ABCDEF0123456789ABCDEF"),
                named(digest = "abc"),
                named(title = "x".repeat(SyncRoutes.MAX_NAME + 1)),
                named(title = "Dune\u0007"),
                named(authors = "Frank\nHerbert"),
            )
            .forEach {
                val body = sessionsBody(JSONArray().put(sessionJson()), books = it)
                assertEquals(it.toString(), 403, postStatistics(body).status)
            }
        assertTrue(library.sessions.isEmpty())
        assertTrue(library.names.isEmpty())
        // A refused session keeps no names either.
        library.refuseSessions = true
        assertEquals(
            403,
            postStatistics(sessionsBody(JSONArray().put(sessionJson()), books = named())).status,
        )
        assertTrue(library.names.isEmpty())
    }

    @Test
    fun aSyncThatNeverFinishesRemembersNoRefusal() {
        firstSyncLibraries()
        library.refuse["documents/Dune.epub"] = Refusal.NOT_A_BOOK
        val plan = kindle.sync()
        kindle.run(plan)
        assertTrue(library.refused("kindle-1").isEmpty())
        assertTrue(library.synced.isEmpty())
    }
}

private object EmptyLinkLibrary : LinkLibrary {
    override fun recent(limit: Int) = emptyList<LinkBook>()

    override fun shelves() = emptyList<LinkShelf>()

    override fun shelf(id: String) = emptyList<LinkBook>()

    override fun queue(device: String) = emptyList<LinkBook>()

    override fun book(id: String): LinkBook? = null

    override fun acknowledge(device: String, id: String, sha256: String, path: String) = false
}
