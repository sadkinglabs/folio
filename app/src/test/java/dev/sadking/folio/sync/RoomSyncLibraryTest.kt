package dev.sadking.folio.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.core.Epub
import dev.sadking.folio.core.sync.Baseline
import dev.sadking.folio.core.sync.PhoneAction
import dev.sadking.folio.core.sync.Question
import dev.sadking.folio.core.sync.SyncAnnotation
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.BookAnnotation
import dev.sadking.folio.data.LibraryDatabase
import dev.sadking.folio.data.LibraryRepository
import dev.sadking.folio.data.Membership
import dev.sadking.folio.data.PhoneReading
import dev.sadking.folio.data.RemovedEntry
import dev.sadking.folio.data.Shelf
import dev.sadking.folio.data.WishBook
import dev.sadking.folio.data.encode
import dev.sadking.folio.link.KosyncRequest
import dev.sadking.folio.link.LinkResponse
import dev.sadking.folio.link.SyncRoutes
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The phone's side of sync v2 over Room and the Books folder (M9 step 3b). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomSyncLibraryTest {
    private lateinit var base: File
    private lateinit var books: File
    private lateinit var cache: File
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var state: SyncState
    private lateinit var library: RoomSyncLibrary
    private var clock = 1_760_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = File(context.cacheDir, "sync-${UUID.randomUUID()}").apply { mkdirs() }
        books = File(base, "Books")
        cache = File(base, "cache")
        val isolated =
            object : ContextWrapper(context) {
                override fun getFilesDir() = File(base, "private").apply { mkdirs() }
            }
        db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repo = LibraryRepository(isolated, db) { books }
        repo.folderAccess = { true }
        prefs = context.getSharedPreferences("sync-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        state = SyncState(prefs)
        library = RoomSyncLibrary(repo, state, cache) { clock }
    }

    @After
    fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    // ---- Fixtures ----

    private fun epub(file: File, title: String, author: String, body: String = title): File {
        file.parentFile!!.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            mapOf(
                    "mimetype" to "application/epub+zip",
                    "META-INF/container.xml" to
                        """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                    "book.opf" to
                        """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title><dc:creator>$author</dc:creator><dc:identifier id="uid">urn:test:$title</dc:identifier></metadata><manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""",
                    "c.xhtml" to
                        "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>$body</body></html>",
                )
                .forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
        }
        return file
    }

    /** An upload as the Link server leaves it: a temporary file in [RoomSyncLibrary.uploadDir]. */
    private fun upload(title: String, author: String, body: String = title): File =
        epub(File(library.uploadDir(), "librillo-${UUID.randomUUID()}.upload"), title, author, body)

    /** A book filed in the Books folder, as an import makes it. */
    private fun addToFolder(title: String, author: String, body: String = title): Book =
        library.receive("kindle", "Downloads/$title.epub", upload(title, author, body))!!.let {
            runBlocking { db.library().book(it)!! }
        }

    private fun book(id: String) = runBlocking { db.library().book(id) }

    private fun storedBook(
        id: String,
        path: String,
        hash: String,
        status: String = "Unread",
        statusAt: Long = 0,
        format: String = "epub",
    ) =
        Book(
            id,
            "Title $id",
            encode(listOf("Ann Lee")),
            originalPath = path,
            currentPath = path,
            originalHash = hash,
            currentHash = hash,
            status = status,
            statusAt = statusAt,
            format = format,
        )

    private fun note(
        id: String,
        bookId: String,
        device: String = "kindle-ssh",
        date: String = "2026-01-01 10:00:00",
        pos: String = "/body/p[1]",
    ) =
        BookAnnotation(
            id,
            bookId,
            device,
            "text $id",
            "",
            "One",
            date,
            pos,
            "/body/p[2]",
            "highlight",
        )

    private fun syncNote(id: String, date: String = "2026-02-02 09:00:00") =
        SyncAnnotation(id, "text $id", "a note", "Two", date, "/body/p[3]", "/body/p[4]", "note")

    private fun question(id: String, bookId: String = "b1") =
        Question(
            id,
            bookId,
            "Librillo/A/B.epub",
            "status",
            "Reading",
            "Finished",
            "Which?",
            listOf("Finished", "Reading"),
        )

    // ---- phoneBooks ----

    @Test
    fun phoneBooksListsEveryBookWithItsNotesAndManualLists() = runBlocking {
        val dao = db.library()
        val active = addToFolder("Home", "Ann Lee")
        dao.update(active.copy(status = "Reading", statusAt = 42))
        dao.insert(storedBook("gone", File(base, "nowhere.epub").path, "f".repeat(64)))
        dao.insert(storedBook("removed", File(books, "Ann Lee/Old.epub").path, "e".repeat(64)))
        dao.markRemoved(RemovedEntry("removed", "removed", "Old", "EPUB", 1))
        dao.annotation(note("n1", active.id))
        dao.annotation(note("n2", "removed"))
        dao.insertShelf(Shelf("s1", "Sundays"))
        dao.insertShelf(Shelf("s2", "Finished books", ruleField = "Status", ruleValue = "Finished"))
        dao.addMembership(Membership(active.id, "s1"))
        dao.addMembership(Membership(active.id, "s2"))
        dao.addMembership(Membership("removed", "s1"))

        val phone = library.phoneBooks().associateBy { it.id }

        assertEquals(setOf(active.id, "gone", "removed"), phone.keys)
        val home = phone.getValue(active.id)
        assertEquals(active.currentHash, home.sha256)
        assertEquals("epub", home.format)
        assertEquals("Home", home.title)
        assertEquals(listOf("Ann Lee"), home.authors)
        assertEquals("Reading", home.status)
        assertEquals(42L, home.statusAt)
        assertFalse(home.removed)
        assertTrue(home.hasFile)
        assertEquals(File(active.currentPath).length(), home.size)
        // Only manual lists are memberships.
        assertEquals(listOf("Sundays"), home.lists)
        val n1 = home.annotations.single()
        assertEquals("n1", n1.id)
        assertEquals("2026-01-01 10:00:00", n1.datetime)
        assertEquals("/body/p[1]", n1.pos0)
        assertEquals("/body/p[2]", n1.pos1)
        assertEquals("highlight", n1.kind)
        assertFalse(phone.getValue("gone").hasFile)
        assertEquals(0L, phone.getValue("gone").size)
        // A book in Recently removed keeps its notes and lists for sync.
        val removed = phone.getValue("removed")
        assertTrue(removed.removed)
        assertEquals(listOf("n2"), removed.annotations.map { it.id })
        assertEquals(listOf("Sundays"), removed.lists)
    }

    @Test
    fun aFolderTheAppCannotReadIsNotAMissingFile() = runBlocking {
        val dao = db.library()
        dao.insert(storedBook("folder", File(books, "Ann Lee/Away.epub").path, "a".repeat(64)))
        dao.insert(
            storedBook(
                "private",
                File(base, "private/library/x/original.epub").path,
                "b".repeat(64),
            )
        )
        repo.folderAccess = { false }
        val phone = library.phoneBooks().associateBy { it.id }
        assertTrue(phone.getValue("folder").hasFile)
        assertFalse(phone.getValue("private").hasFile)
    }

    // ---- receive ----

    @Test
    fun receiveFilesANewBookBesideAnExistingFileNeverOverIt() = runBlocking {
        val existing = epub(File(books, "Ann Lee/Home.epub"), "Someone else", "Bo Ray")
        val before = existing.readBytes()
        val sent = upload("Home", "Ann Lee", "new text")
        val hash = Epub.sha256(sent)

        val id = library.receive("kindle", "Downloads/home.epub", sent)!!

        val book = book(id)!!
        assertEquals(hash, book.currentHash)
        assertEquals(
            File(books, "Ann Lee/Home (2).epub").canonicalPath,
            File(book.currentPath).canonicalPath,
        )
        assertArrayEquals(before, existing.readBytes())
        assertFalse("The upload is moved, not copied", sent.exists())
    }

    @Test
    fun receiveReturnsTheBookThatAlreadyHasTheContentWithoutRestoringIt() = runBlocking {
        val home = addToFolder("Home", "Ann Lee")
        db.library().markRemoved(RemovedEntry(home.id, home.id, "Home", "EPUB", 1))
        val again = upload("Home", "Ann Lee")

        assertEquals(home.id, library.receive("kindle", "Downloads/home.epub", again))
        assertEquals(1, db.library().allStoredBooks().size)
        assertNotNull(db.library().removedEntry(home.id))
    }

    @Test
    fun receiveRefusesAFileThatIsNotABook() = runBlocking {
        val junk =
            File(library.uploadDir(), "librillo-junk.upload").apply { writeText("not a book") }
        assertNull(library.receive("kindle", "Downloads/notes.epub", junk))
        assertTrue(db.library().allStoredBooks().isEmpty())
    }

    @Test
    fun receiveWithoutTheBooksFolderKeepsTheBookPrivate() = runBlocking {
        repo.folderAccess = { false }
        val id = library.receive("kindle", "Downloads/home.epub", upload("Home", "Ann Lee"))!!
        val path = File(book(id)!!.currentPath)
        assertTrue(path.isFile)
        assertTrue(repo.isPrivate(path))
    }

    // ---- revise ----

    @Test
    fun reviseKeepsTheOldFileAndTakesItsName() = runBlocking {
        val home = addToFolder("Home", "Ann Lee", "first")
        val oldBytes = File(home.currentPath).readBytes()
        db.library()
            .savePhoneReading(PhoneReading(home.id, home.currentHash, "{}", "/body/p[1]", 0.3, 1))
        val sent = upload("Home", "Ann Lee", "second")
        val newHash = Epub.sha256(sent)

        assertEquals(home.id, library.revise("kindle", home.id, "Librillo/Ann Lee/Home.epub", sent))

        val revised = book(home.id)!!
        assertEquals(home.currentPath, revised.currentPath)
        assertEquals(newHash, revised.currentHash)
        assertEquals(newHash, Epub.sha256(File(revised.currentPath)))
        // The original stays, as an edit keeps it.
        val original = File(books, ".librillo/originals/${home.id}.epub")
        assertEquals(original.absolutePath, revised.originalPath)
        assertArrayEquals(oldBytes, original.readBytes())
        assertEquals(home.originalHash, revised.originalHash)
        assertEquals(0.3, db.library().phoneReading(home.id, newHash)!!.percent!!, 0.0)

        // A second revision keeps the first one as a previous version.
        val firstRevision = File(revised.currentPath).readBytes()
        library.revise(
            "kindle",
            home.id,
            "Librillo/Ann Lee/Home.epub",
            upload("Home", "Ann Lee", "third"),
        )
        val previous = File(books, ".librillo/previous").listFiles()!!.single()
        assertArrayEquals(firstRevision, previous.readBytes())
        assertArrayEquals(oldBytes, original.readBytes())
    }

    @Test
    fun reviseOfAPrivateBookAddsTheFileBesideTheOldOne() = runBlocking {
        repo.folderAccess = { false }
        val id = library.receive("kindle", "a.epub", upload("Home", "Ann Lee", "first"))!!
        val old = File(book(id)!!.currentPath)
        val oldBytes = old.readBytes()

        library.revise(
            "kindle",
            id,
            "Librillo/Ann Lee/Home.epub",
            upload("Home", "Ann Lee", "second"),
        )

        val revised = book(id)!!
        assertNotEquals(old.absolutePath, revised.currentPath)
        assertEquals(
            old.parentFile!!.canonicalPath,
            File(revised.currentPath).parentFile!!.canonicalPath,
        )
        assertArrayEquals(oldBytes, old.readBytes())
    }

    @Test
    fun reviseRefusesAnotherFormatAndChangesNothing() = runBlocking {
        val home = addToFolder("Home", "Ann Lee")
        val bytes = File(home.currentPath).readBytes()
        val pdf =
            File(library.uploadDir(), "librillo-pdf.upload").apply {
                writeText("%PDF-1.4\n%%EOF\n")
            }

        assertNull(library.revise("kindle", home.id, "Librillo/Ann Lee/Home.pdf", pdf))
        assertEquals(home, book(home.id))
        assertArrayEquals(bytes, File(home.currentPath).readBytes())
    }

    @Test
    fun reviseGivesAMissingFileBack() = runBlocking {
        val home = addToFolder("Home", "Ann Lee")
        val bytes = File(home.currentPath).readBytes()
        File(home.currentPath).delete()
        val again = File(library.uploadDir(), "librillo-again.upload").apply { writeBytes(bytes) }

        assertEquals(
            home.id,
            library.revise("kindle", home.id, "Librillo/Ann Lee/Home.epub", again),
        )
        val back = book(home.id)!!
        assertTrue(File(back.currentPath).isFile)
        assertEquals(home.currentHash, back.currentHash)
    }

    // ---- apply ----

    @Test
    fun anImportCarriesTheKindlesStatusNotesAndLists() = runBlocking {
        val sent = upload("Home", "Ann Lee")
        val id = library.receive("kindle", "Downloads/home.epub", sent)!!
        library.apply(
            "kindle",
            listOf(
                PhoneAction.Import(
                    "Downloads/home.epub",
                    status = "Finished",
                    statusAt = 77,
                    annotations = listOf(syncNote("k1")),
                    lists = listOf("Sundays"),
                )
            ),
            mapOf("Downloads/home.epub" to id),
        )
        val book = book(id)!!
        assertEquals("Finished", book.status)
        assertEquals(77L, book.statusAt)
        val row = db.library().allAnnotations().single()
        assertEquals("k1", row.id)
        assertEquals("link:kindle", row.deviceId)
        assertEquals("/body/p[3]", row.position)
        assertEquals("/body/p[4]", row.endPosition)
        assertEquals("2026-02-02 09:00:00", row.date)
        assertEquals("note", row.kind)
        assertEquals(listOf("Sundays"), library.phoneBooks().single().lists)
    }

    @Test
    fun removeGoesToRecentlyRemovedAndRestoreBringsItBack() = runBlocking {
        val home = addToFolder("Home", "Ann Lee")
        library.apply("kindle", listOf(PhoneAction.Remove(home.id)), emptyMap())
        assertNotNull(db.library().removedEntry(home.id))
        assertTrue("Removing never deletes the file", File(home.currentPath).isFile)
        library.apply("kindle", listOf(PhoneAction.Restore(home.id)), emptyMap())
        assertNull(db.library().removedEntry(home.id))
    }

    @Test
    fun setStatusKeepsTheTimeOfTheChange() = runBlocking {
        val home = addToFolder("Home", "Ann Lee")
        library.apply("kindle", listOf(PhoneAction.SetStatus(home.id, "Paused", 1234)), emptyMap())
        assertEquals("Paused", book(home.id)!!.status)
        assertEquals(1234L, book(home.id)!!.statusAt)
        // A change made on the phone is timed now; setting the same status again changes nothing.
        val before = System.currentTimeMillis()
        repo.manageBatch(setOf(home.id), status = "Reading")
        val reading = book(home.id)!!
        assertTrue(reading.statusAt >= before)
        repo.manageBatch(setOf(home.id), status = "Reading", statusAt = 5)
        assertEquals(reading.statusAt, book(home.id)!!.statusAt)
    }

    @Test
    fun addAnnotationsOnlyAddsNewOnes() = runBlocking {
        val home = addToFolder("Home", "Ann Lee")
        db.library().annotation(note("k1", home.id).copy(text = "mine"))
        library.apply(
            "kindle",
            listOf(PhoneAction.AddAnnotations(home.id, listOf(syncNote("k1"), syncNote("k2")))),
            emptyMap(),
        )
        val rows = db.library().allAnnotations().associateBy { it.id }
        assertEquals("mine", rows.getValue("k1").text)
        assertEquals("text k2", rows.getValue("k2").text)
        assertEquals(2, rows.size)
    }

    @Test
    fun setListsMakesManualListsExactAndLeavesSmartListsAlone() = runBlocking {
        val dao = db.library()
        val home = addToFolder("Home", "Ann Lee")
        dao.insertShelf(Shelf("a", "Alpha"))
        dao.insertShelf(Shelf("b", "Beta"))
        dao.insertShelf(Shelf("smart", "Done", ruleField = "Status", ruleValue = "Finished"))
        dao.addMembership(Membership(home.id, "a"))
        dao.addMembership(Membership(home.id, "b"))
        dao.insertWish(
            WishBook("w", "manual:w", "Home", encode(listOf("Ann Lee")), linkedBookId = home.id)
        )

        library.apply(
            "kindle",
            listOf(PhoneAction.SetLists(home.id, listOf("Beta", "Gamma", "Done"))),
            emptyMap(),
        )

        val names = dao.allShelves().associate { it.id to it.name }
        val mine = dao.membershipsOf(home.id).map { names.getValue(it.shelfId) }.toSet()
        assertEquals(setOf("Beta", "Gamma"), mine)
        assertEquals("Done", dao.allShelves().single { it.id == "smart" }.name)
        assertEquals(1, dao.allShelves().count { it.name == "Done" })
        // The attached Want to Read entry follows, as with a change made by hand.
        val wishLists = dao.allWishMemberships().map { names.getValue(it.shelfId) }.toSet()
        assertEquals(setOf("Beta", "Gamma"), wishLists)
    }

    @Test
    fun aFailingActionDoesNotStopTheOthers() = runBlocking {
        val home = addToFolder("Home", "Ann Lee")
        library.apply(
            "kindle",
            listOf(
                PhoneAction.SetStatus(home.id, "Not a status", 1),
                PhoneAction.AddAnnotations(home.id, listOf(syncNote("k1"))),
            ),
            emptyMap(),
        )
        assertEquals("Unread", book(home.id)!!.status)
        assertEquals(listOf("k1"), db.library().allAnnotations().map { it.id })
    }

    // ---- Baselines and pending ----

    @Test
    fun commitReplacesTheEreadersWholeSet() {
        val one =
            Baseline(
                "b1",
                "Librillo/A/One.epub",
                "a".repeat(64),
                "reading",
                5,
                setOf("n2", "n1"),
                setOf("Sundays"),
            )
        val two = Baseline("b2", "Librillo/A/Two.epub", "b".repeat(64), "", 0)
        library.commit("kindle", listOf(one, two))
        library.commit("kobo", listOf(two))
        assertEquals(listOf(one, two), library.baselines("kindle"))

        library.commit("kindle", listOf(two))

        assertEquals(listOf(two), library.baselines("kindle"))
        assertEquals(listOf(two), library.baselines("kobo"))
        assertEquals(clock, db.syncBaselines().get("b2", "kindle")!!.syncedAt)
    }

    @Test
    fun pendingFollowsBaselinesQuestionsAndAnswers() = runBlocking {
        val home = addToFolder("Home", "Ann Lee")
        assertTrue("A book the e-reader never had", library.pending("kindle"))
        val agreed = Baseline(home.id, "Librillo/Ann Lee/Home.epub", home.currentHash, "unread", 0)
        library.commit("kindle", listOf(agreed))
        assertFalse(library.pending("kindle"))

        repo.manageBatch(setOf(home.id), status = "Finished")
        assertTrue(library.pending("kindle"))

        library.saveQuestions("kindle", listOf(question("q-1", home.id)))
        assertFalse("A question is asked at the next sync anyway", library.pending("kindle"))
        assertTrue(state.answer("kindle", "q-1", "Reading"))
        assertTrue("An answer is used at the next sync", library.pending("kindle"))
    }

    // ---- Questions ----

    @Test
    fun questionsAndAnswersAreKeptPerEreader() {
        val q1 = question("q-1")
        val q2 = question("q-2", "b2")
        library.saveQuestions("kindle", listOf(q1, q2))
        assertEquals(listOf(q1, q2), library.phoneQuestions("kindle"))
        assertTrue(library.phoneQuestions("kobo").isEmpty())

        assertFalse(state.answer("kindle", "q-1", "Maybe"))
        assertFalse(state.answer("kindle", "q-9", "Reading"))
        assertTrue(state.answer("kindle", "q-1", "Reading"))
        assertEquals(mapOf("q-1" to "Reading"), library.phoneAnswers("kindle"))
        assertEquals(listOf(q2), library.phoneQuestions("kindle"))

        // Kept across restarts.
        val again = SyncState(prefs)
        assertEquals(listOf(q1, q2), again.get("kindle").questions)
        assertEquals(mapOf("q-1" to "Reading"), again.get("kindle").answers)

        // Once the sync no longer asks it, its answer has been used.
        library.saveQuestions("kindle", listOf(q2))
        assertTrue(library.phoneAnswers("kindle").isEmpty())

        library.synced("kindle", "Kindle Paperwhite", 99)
        library.synced("kindle", "", 100)
        assertEquals("Kindle Paperwhite", state.ereaders.value.getValue("kindle").name)
        assertEquals(100L, state.ereaders.value.getValue("kindle").lastSync)
    }

    // ---- Files ----

    @Test
    fun uploadsWaitOnTheBooksFolderVolumeElseInTheCache() {
        assertEquals(
            File(books, ".librillo/uploads").canonicalPath,
            library.uploadDir().canonicalPath,
        )
        val old =
            File(library.uploadDir(), "librillo-old.upload").apply {
                writeText("x")
                setLastModified(clock - RoomSyncLibrary.STALE_UPLOAD_MS - 1)
            }
        val fresh = File(library.uploadDir(), "librillo-new.upload").apply { writeText("x") }
        fresh.setLastModified(clock)
        library.uploadDir()
        assertFalse(old.exists())
        assertTrue(fresh.exists())

        repo.folderAccess = { false }
        assertEquals(File(cache, "sync-uploads").canonicalPath, library.uploadDir().canonicalPath)
    }

    @Test
    fun bookFileIsOnlyTheBooksOwnFile() = runBlocking {
        val home = addToFolder("Home", "Ann Lee")
        assertEquals(
            File(home.currentPath).canonicalPath,
            library.bookFile(home.id)!!.canonicalPath,
        )
        val outside = File(base, "elsewhere.epub").apply { writeText("x") }
        db.library().insert(storedBook("odd", outside.path, "c".repeat(64)))
        assertNull(library.bookFile("odd"))
        assertNull(library.bookFile("unknown"))
    }

    // ---- One whole sync through the Link server's routes ----

    @Test
    fun aSyncThroughTheRoutesImportsTheKindlesBookAndSendsThePhones() = runBlocking {
        val phoneBook = addToFolder("Home", "Ann Lee")
        val kindleFile = epub(File(base, "kindle/Downloads/dune.epub"), "Dune", "Frank Herbert")
        val routes = SyncRoutes(library, now = { clock })
        fun call(
            method: String,
            route: String,
            body: String? = null,
            upload: java.io.InputStream? = null,
        ) =
            routes.handle(
                KosyncRequest(method, "/folio/v2$route", mapOf("x-folio-device" to "kindle"), body),
                route.substringBefore('?'),
                upload,
            )

        val manifest =
            JSONObject()
                .put("device", JSONObject().put("id", "kindle").put("name", "Kindle"))
                .put("home", "/mnt/us/koreader")
                .put("root", "Librillo")
                .put(
                    "files",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("path", "Downloads/dune.epub")
                                .put("size", kindleFile.length())
                                .put("sha256", Epub.sha256(kindleFile))
                                .put("format", "epub")
                                .put("status", "reading")
                                .put("statusAt", 500)
                                .put("annotations", JSONArray())
                                .put("collections", JSONArray().put("Sundays"))
                                .put("title", "Dune")
                                .put("authors", JSONArray().put("Frank Herbert"))
                        ),
                )
        val plan =
            JSONObject((call("POST", "/sync", manifest.toString()) as LinkResponse.Text).body)
        val session = plan.getString("session")
        val actions = plan.getJSONArray("kindle")
        val ops = (0 until actions.length()).map { actions.getJSONObject(it).getString("op") }
        assertEquals(listOf("upload", "move", "download"), ops)

        val put =
            call(
                "PUT",
                "/upload?session=$session&path=Downloads%2Fdune.epub",
                upload = kindleFile.inputStream(),
            )
                as LinkResponse.Text
        assertEquals(200, put.status)
        val download = actions.getJSONObject(2)
        assertEquals(phoneBook.id, download.getString("bookId"))
        val results = JSONArray()
        (0 until actions.length()).forEach {
            results.put(JSONObject().put("index", it).put("ok", true))
        }
        val finish =
            call(
                "POST",
                "/finish",
                JSONObject().put("session", session).put("results", results).toString(),
            )
        assertEquals(200, finish.status)

        val phone = library.phoneBooks().associateBy { it.title }
        val dune = phone.getValue("Dune")
        assertEquals("Reading", dune.status)
        assertEquals(500L, dune.statusAt)
        assertEquals(listOf("Sundays"), dune.lists)
        assertEquals(
            setOf(phoneBook.id, dune.id),
            library.baselines("kindle").map { it.bookId }.toSet(),
        )
        assertFalse(library.pending("kindle"))
        assertEquals("Kindle", state.get("kindle").name)
        assertTrue(
            "The upload's temporary file is gone",
            library.uploadDir().listFiles()!!.none { it.isFile },
        )
    }
}
