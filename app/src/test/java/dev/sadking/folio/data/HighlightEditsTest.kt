package dev.sadking.folio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.core.BackupArchive
import dev.sadking.folio.sync.SyncAnnotations
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.flow.first
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

/**
 * The owner's own highlight changes (M11) over Room on the JVM (Robolectric): soft delete and Undo,
 * colour and note, the 30 days, and backups.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HighlightEditsTest {
    private lateinit var base: File
    private val databases = mutableListOf<LibraryDatabase>()
    private var clock = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L
    private val book = "0b1a2c3d-0000-4000-8000-000000000001"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = File(context.cacheDir, "highlights-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        databases.forEach { it.close() }
        base.deleteRecursively()
    }

    private fun library(name: String): LibraryRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(base, name).apply { mkdirs() }
        val db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
                .also { databases += it }
        return LibraryRepository(
            object : ContextWrapper(context) {
                override fun getFilesDir() = File(folder, "private").apply { mkdirs() }
            },
            db,
        ) {
            File(folder, "Books")
        }
    }

    private fun row(id: String, text: String = "text $id") =
        BookAnnotation(
            id,
            book,
            "phone",
            text,
            "",
            "One",
            "2026-10-01 10:00:00",
            "/body/DocFragment[2]/p[1]/text().0/$id",
            "/body/DocFragment[2]/p[1]/text().9",
            "highlight",
        )

    private fun edits(repo: LibraryRepository) =
        HighlightEdits(repo.dao, { clock }, { LocalDateTime.of(2026, 10, 17, 9, 30, 0) })

    @Test
    fun aDeleteIsSoftAndUndoBringsItBack() = runBlocking {
        val repo = library("phone")
        val dao = repo.dao
        dao.annotation(row("h1"))
        dao.annotation(row("h2"))
        val e = edits(repo)

        val removed = e.delete("h1")
        assertEquals(row("h1"), removed)
        assertNull("deleting twice does nothing", e.delete("h1"))
        val stored = dao.annotationById("h1")!!
        assertEquals("the row stays, with its text", "text h1", stored.text)
        assertEquals(clock, stored.deletedAt)
        // It stops showing at once, everywhere the owner sees highlights.
        assertEquals(
            listOf("h2"),
            SyncAnnotations.live(dao.bookAnnotations(book).first()).map { it.id },
        )
        assertEquals(listOf("h1"), dao.deletedAnnotations(clock - day).first().map { it.id })

        assertTrue(e.restore("h1"))
        assertEquals(row("h1"), dao.annotationById("h1"))
        assertTrue(dao.deletedAnnotations(clock - day).first().isEmpty())
        assertFalse("a live one can't be restored", e.restore("h1"))
    }

    @Test
    fun afterThirtyDaysOnlyTheTextGoesAndTheRowStaysForSync() = runBlocking {
        val repo = library("phone")
        val dao = repo.dao
        dao.annotation(row("old").copy(note = "mine", kind = "note"))
        dao.annotation(row("recent"))
        dao.annotation(row("live"))
        val e = edits(repo)
        e.delete("old")
        clock += 20 * day
        e.delete("recent")
        clock += 11 * day

        assertFalse("past 30 days it can't be restored", e.restore("old"))
        assertEquals(1, e.clearExpired())
        val old = dao.annotationById("old")!!
        assertEquals("" to "", old.text to old.note)
        assertTrue(old.deleted)
        assertEquals("text recent", dao.annotationById("recent")!!.text)
        assertEquals("text live", dao.annotationById("live")!!.text)
        assertEquals(0, e.clearExpired())
        // Sync still sees the deletion.
        val sent = SyncAnnotations.forSync(dao.bookAnnotations(book).first()).associateBy { it.id }
        assertTrue(sent.getValue("old").deleted)
        assertFalse(sent.getValue("live").deleted)
        assertTrue(e.restore("recent"))
    }

    @Test
    fun colourAndNoteAreEditsWithATime() = runBlocking {
        val repo = library("phone")
        val dao = repo.dao
        dao.annotation(row("h1"))
        dao.annotation(row("mark").copy(kind = "bookmark"))
        val e = edits(repo)

        assertTrue(e.recolour("h1", HighlightColour.PINK))
        var h = dao.annotationById("h1")!!
        assertEquals("purple" to "lighten", h.color to h.drawer)
        assertEquals("2026-10-17 09:30:00", h.editedAt)
        assertEquals(HighlightColour.PINK, HighlightColour.of(h))
        assertFalse("the same look is no edit", e.recolour("h1", HighlightColour.PINK))
        assertTrue(e.recolour("h1", HighlightColour.UNDERLINE))
        h = dao.annotationById("h1")!!
        assertEquals("purple" to "underscore", h.color to h.drawer)
        assertEquals(HighlightColour.UNDERLINE, HighlightColour.of(h))

        assertTrue(e.editNote("h1", "  why it matters "))
        h = dao.annotationById("h1")!!
        assertEquals("why it matters" to "note", h.note to h.kind)
        assertFalse(e.editNote("h1", "why it matters"))
        assertTrue(e.editNote("h1", " "))
        h = dao.annotationById("h1")!!
        assertEquals("" to "highlight", h.note to h.kind)
        assertTrue(e.editNote("mark", "a note"))
        assertEquals("bookmark", dao.annotationById("mark")!!.kind)

        e.delete("h1")
        assertFalse("a deleted one is not edited", e.recolour("h1", HighlightColour.BLUE))
        assertFalse(e.editNote("h1", "x"))
    }

    @Test
    fun aBackupKeepsColoursNotesAndDeletionsAndAnOldOneStillRestores() = runBlocking {
        val repo = library("phone")
        val dao = repo.dao
        dao.insert(
            Book(
                book,
                "Home",
                encode(listOf("Ann Lee")),
                originalPath = File(base, "gone.epub").path,
                currentPath = File(base, "gone.epub").path,
                originalHash = "a".repeat(64),
                currentHash = "a".repeat(64),
            )
        )
        val live =
            row("h1").copy(color = "green", drawer = "underscore", editedAt = "2026-10-02 08:00:00")
        val deleted = row("h2").copy(deletedAt = clock)
        dao.annotation(live)
        dao.annotation(deleted)
        val archive = File(base, "backup.zip")
        LibraryBackup(repo).export(archive)

        val fresh = library("new-phone")
        LibraryBackup(fresh).restore(archive)
        val restored = fresh.dao.allAnnotations().associateBy { it.id }
        val bookId = restored.getValue("h1").bookId
        assertEquals(live.copy(bookId = bookId), restored.getValue("h1"))
        assertEquals(deleted.copy(bookId = bookId), restored.getValue("h2"))

        // A backup from before schema 13: its highlights come back live, as they looked.
        val manifest =
            java.util.zip.ZipFile(archive).use { zip ->
                JSONObject(
                    zip.getInputStream(zip.getEntry("manifest.json"))
                        .readBytes()
                        .toString(Charsets.UTF_8)
                )
            }
        val old = manifest.getJSONArray("annotations")
        val stripped = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            listOf("color", "drawer", "editedAt", "deletedAt").forEach { o.remove(it) }
            stripped.put(o)
        }
        manifest.put("annotations", stripped)
        val oldArchive = File(base, "old.zip")
        BackupArchive.write(oldArchive, manifest.toString().toByteArray(), emptyMap())
        val older = library("older-phone")
        LibraryBackup(older).restore(oldArchive)
        val back = older.dao.allAnnotations().associateBy { it.id }
        assertEquals(setOf("h1", "h2"), back.keys)
        assertTrue(back.values.all { !it.deleted && it.color == "" && it.drawer == "" })
    }
}
