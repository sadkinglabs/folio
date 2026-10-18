package dev.sadking.folio.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Markdown export's Android side on the JVM (Robolectric, in-memory Room): what it reads from
 * the library, the file name, the share file, and the rule for names in a folder. Saving into a
 * real folder through Android's document picker is not covered here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotesExportTest {
    private lateinit var db: LibraryDatabase
    private lateinit var context: Context
    private val book =
        Book(
            id = "00000000-0000-0000-0000-000000000001",
            title = "The Orchard: a novel",
            authors = encode(listOf("Ada Lovelace", "B. Second")),
            originalPath = "/books/orchard.epub",
            currentPath = "/books/orchard.epub",
            originalHash = "rev1",
            currentHash = "rev1",
        )

    private fun row(
        id: String,
        text: String,
        fragment: Int,
        note: String = "",
        kind: String = "highlight",
        color: String = "",
        drawer: String = "lighten",
        deletedAt: Long = 0,
    ) =
        BookAnnotation(
            id,
            book.id,
            "link:kindle",
            text,
            note,
            "Chapter $fragment",
            "2026-10-1$fragment 08:00:00",
            "/body/DocFragment[$fragment]/body/p[1]/text().0",
            "/body/DocFragment[$fragment]/body/p[1]/text().9",
            kind,
            color = color,
            drawer = drawer,
            deletedAt = deletedAt,
        )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        runBlocking { db.library().insert(book) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun export() = NotesExport(context, db.library()) { LocalDate.of(2026, 10, 17) }

    @Test
    fun theFileHoldsLiveHighlightsBookmarksAndTheHardcoverId() = runBlocking {
        val dao = db.library()
        dao.annotation(row("a", "Later passage", 4, color = "purple"))
        dao.annotation(row("b", "Earlier passage", 2, note = "A thought", kind = "note"))
        dao.annotation(row("c", "Gone passage", 3, deletedAt = 5))
        dao.annotation(row("d", "", 3, note = "Kindle mark", kind = "bookmark", drawer = ""))
        dao.addPhoneBookmark(
            PhoneBookmark(
                "m1",
                book.id,
                "rev1",
                """{"href":"c.xhtml","type":"application/xhtml+xml","locations":{"totalProgression":0.5}}""",
                "Chapter 3 · 50%",
                0,
            )
        )
        // An older revision's bookmark is not this file's.
        dao.addPhoneBookmark(PhoneBookmark("m2", book.id, "rev0", "{}", "Old revision", 0))
        dao.insertWish(
            WishBook(
                "w1",
                "key",
                book.title,
                book.authors,
                hardcoverId = 99,
                linkedBookId = book.id,
            )
        )
        val file = export().file(book.id)
        assertEquals("The Orchard a novel - Ada Lovelace.md", file.name)
        val md = file.text
        assertTrue(md.contains("hardcover_id: 99\n"))
        assertTrue(md.contains("author:\n  - \"Ada Lovelace\"\n  - \"B. Second\"\n"))
        assertTrue(md.contains("# The Orchard: a novel\n"))
        assertFalse(md.contains("Gone passage"))
        assertFalse(md.contains("Old revision"))
        // Reading order, not date or id order; KOReader's purple is the phone's pink.
        assertTrue(md.indexOf("> Earlier passage") < md.indexOf("> Later passage"))
        assertTrue(md.contains("> Earlier passage\n\nA thought\n\n12/10/2026\n"))
        assertTrue(md.contains("#highlight/pink · 14/10/2026"))
        val marks = md.substringAfter("## Bookmarks\n\n")
        assertEquals(
            "- Chapter 3 · 13/10/2026\n  Kindle mark\n- Chapter 3 · 50% · 01/01/1970\n",
            marks.replace(Regex("1970-01-01|1969-12-31"), "1970-01-01"),
        )
    }

    @Test
    fun aBookWithNothingStillExports() = runBlocking {
        val md = export().file(book.id).text
        assertTrue(md.endsWith("No highlights or notes yet.\n"))
        assertFalse(md.contains("hardcover_id"))
    }

    @Test
    fun shareWritesOneMarkdownFileThroughTheFileProvider() = runBlocking {
        val notes = export()
        val chooser = notes.shareIntent(ExportFile("The Orchard - Ada.md", "# The Orchard\n"), "T")
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("text/markdown", send.type)
        @Suppress("DEPRECATION") val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("${context.packageName}.photos", uri.authority)
        val written = File(context.cacheDir, "epub-share/notes/The Orchard - Ada.md")
        assertEquals("# The Orchard\n", written.readText())
        // Only the latest export stays in the share folder.
        notes.shareIntent(ExportFile("Other.md", "x"), "T")
        assertFalse(written.exists())
    }

    @Test
    fun noFolderIsRememberedAtFirst() {
        assertNull(export().folder())
        assertNull(export().folderName())
    }

    @Test
    fun aNameIsReusedOnlyForThisBooksOwnExport() {
        val name = "The Orchard - Ada.md"
        assertEquals(name, exportTarget(name, emptySet()) { false })
        assertEquals(name, exportTarget(name, setOf(name)) { it == name })
        // Someone else's file of that name is left alone.
        assertEquals("The Orchard - Ada (2).md", exportTarget(name, setOf(name)) { false })
        assertEquals(
            "The Orchard - Ada (3).md",
            exportTarget(name, setOf(name, "The Orchard - Ada (2).md")) { false },
        )
        // A second copy made earlier for this book is found again.
        assertEquals(
            "The Orchard - Ada (2).md",
            exportTarget(name, setOf(name, "The Orchard - Ada (2).md")) { it.endsWith("(2).md") },
        )
    }

    @Test
    fun coloursUsePlainNames() {
        assertEquals("pink", exportColour(row("a", "x", 1, color = "purple")))
        assertEquals(
            "underline",
            exportColour(row("a", "x", 1, color = "red", drawer = UNDERSCORE)),
        )
        assertEquals("", exportColour(row("a", "x", 1)))
        assertEquals("green", exportColour(row("a", "x", 1, color = " Green ")))
    }

    @Test
    fun aPhoneBookmarkIsPlacedByHowFarIntoTheBookItIs() {
        val notes =
            exportNotes(
                emptyList(),
                listOf(
                    PhoneBookmark(
                        "m",
                        book.id,
                        "rev1",
                        """{"locations":{"totalProgression":0.25}}""",
                        "Here",
                        0,
                    ),
                    PhoneBookmark("n", book.id, "rev1", "not json", "Broken", 86_400_000),
                ),
                ZoneOffset.UTC,
            )
        assertEquals(0.25, notes[0].place.progression!!, 0.0)
        assertEquals("1970-01-01 00:00:00", notes[0].date)
        assertNull(notes[1].place.progression)
        assertEquals("1970-01-02 00:00:00", notes[1].date)
        assertTrue(notes.all { it.bookmark && it.colour.isEmpty() })
    }
}
