package dev.sadking.folio.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class NotesMarkdownTest {
    private val book = ExportBook("b1", "The Orchard", listOf("Ada Lovelace"), hardcoverId = 4321)
    private val day = LocalDate.of(2026, 10, 17)

    private fun highlight(
        text: String,
        chapter: String = "Chapter one",
        pointer: String = "/body/DocFragment[2]/body/p[1]/text().0",
        note: String = "",
        colour: String = "",
        date: String = "2026-10-16 08:41:12",
    ) = ExportNote(false, text, note, chapter, date, colour, NotePlace.of(pointer))

    @Test
    fun frontMatterTitleAndAQuotedHighlightWithItsNoteAndColour() {
        val md =
            NotesMarkdown.build(
                book,
                listOf(
                    highlight(
                        "In March the pruning began.",
                        note = "Like the vineyard.",
                        colour = "green",
                    )
                ),
                day,
            )
        assertEquals(
            """
            ---
            title: "The Orchard"
            author:
              - "Ada Lovelace"
            hardcover_id: 4321
            exported: 2026-10-17
            librillo_id: "b1"
            ---

            # The Orchard

            ## Chapter one

            > In March the pruning began.

            Like the vineyard.

            #highlight/green · 16/10/2026

            """
                .trimIndent(),
            md,
        )
    }

    @Test
    fun noHardcoverIdWithoutAMatchAndYamlIsEscaped() {
        val md =
            NotesMarkdown.build(
                ExportBook("b2", "A \"quoted\"\ntitle \\ here", listOf("One", "Two")),
                emptyList(),
                day,
            )
        assertFalse(md.contains("hardcover_id"))
        assertTrue(md.contains("title: \"A \\\"quoted\\\" title \\\\ here\"\n"))
        assertTrue(md.contains("author:\n  - \"One\"\n  - \"Two\"\n"))
        // The heading is one line, its own characters escaped.
        assertTrue(md.contains("# A \"quoted\" title \\\\ here\n"))
    }

    @Test
    fun anEmptyBookSaysSo() {
        val md = NotesMarkdown.build(book, emptyList(), day, ExportLabels(empty = "Nothing yet."))
        assertTrue(md.endsWith("# The Orchard\n\nNothing yet.\n"))
        assertFalse(md.contains("## "))
    }

    @Test
    fun aPassageWithMarkdownCharactersStaysInsideItsQuote() {
        val nasty =
            "> not a quote\n# not a heading\n* not a list\n- nor this\n1. nor this\n\n\n" +
                "**bold** _it_ `code` [link](x) <b>html</b> #tag ==mark== %%c%% \$x\$ a|b ~~s~~ &amp; ^id \\"
        val md = NotesMarkdown.build(book, listOf(highlight(nasty)), day)
        val quote =
            md.substringAfter("## Chapter one\n\n")
                .substringBefore("\n\n#highlight")
                .substringBefore("\n\n16/10/2026")
        val lines = quote.lines()
        // Every line is part of the one quote; blank runs became one quoted blank line.
        assertTrue(lines.all { it == ">" || it.startsWith("> ") })
        assertEquals(
            listOf(
                "> \\> not a quote",
                "> \\# not a heading",
                "> \\* not a list",
                "> \\- nor this",
                "> 1\\. nor this",
                ">",
                "> \\*\\*bold\\*\\* \\_it\\_ \\`code\\` \\[link\\](x) \\<b\\>html\\</b\\> \\#tag " +
                    "\\=\\=mark\\=\\= \\%\\%c\\%\\% \\\$x\\\$ a\\|b \\~\\~s\\~\\~ \\&amp; \\^id \\\\",
            ),
            lines,
        )
    }

    @Test
    fun loneCharactersThatOnlyMatterInPairsStayReadable() {
        assertEquals("100% = Tom & Jerry", NotesMarkdown.escapeLine("100% = Tom & Jerry"))
        assertEquals(
            "\\=\\= \\%\\% \\&copy; \\&\\#169;",
            NotesMarkdown.escapeLine("== %% &copy; &#169;"),
        )
    }

    @Test
    fun aNoteKeepsItsParagraphsAndCannotBreakTheStructure() {
        val md =
            NotesMarkdown.build(
                book,
                listOf(
                    highlight(
                        "Passage.",
                        note = "## Not a heading\r\n\r\n> nor a quote\n    nor code",
                    )
                ),
                day,
            )
        assertTrue(
            md.contains("> Passage.\n\n\\#\\# Not a heading\n\n\\> nor a quote\nnor code\n\n")
        )
        assertFalse(md.lines().any { it.startsWith("## Not") })
    }

    @Test
    fun chaptersFollowReadingOrderNotDateOrder() {
        val md =
            NotesMarkdown.build(
                book,
                listOf(
                    highlight(
                        "Late one",
                        "Chapter ten",
                        "/body/DocFragment[10]/body/p[1]/text().0",
                        date = "2026-01-01 00:00:00",
                    ),
                    highlight("Second", "Chapter two", "/body/DocFragment[3]/body/p[10]/text().0"),
                    highlight("First", "Chapter two", "/body/DocFragment[3]/body/p[9]/text().4"),
                    highlight(
                        "Opening",
                        "Chapter one",
                        "/body/DocFragment[2]/body/p[1]/text().0",
                        date = "2026-12-01 00:00:00",
                    ),
                ),
                day,
            )
        val order =
            listOf(
                "## Chapter one",
                "> Opening",
                "## Chapter two",
                "> First",
                "> Second",
                "## Chapter ten",
                "> Late one",
            )
        val at = order.map { md.indexOf(it) }
        assertTrue(at.toString(), at.all { it >= 0 })
        assertEquals(at.sorted(), at)
        // Each chapter has one heading.
        assertEquals(1, Regex("## Chapter two").findAll(md).count())
    }

    @Test
    fun highlightsWithoutAChapterGoUnderTheirOwnHeadingOrNoneAtAll() {
        val mixed =
            NotesMarkdown.build(
                book,
                listOf(highlight("Known"), highlight("Unknown", chapter = "  ", pointer = "12")),
                day,
                ExportLabels(noChapter = "Other highlights"),
            )
        assertTrue(mixed.indexOf("## Chapter one") < mixed.indexOf("## Other highlights"))
        assertTrue(mixed.indexOf("## Other highlights") < mixed.indexOf("> Unknown"))
        val none = NotesMarkdown.build(book, listOf(highlight("Alone", chapter = "")), day)
        assertFalse(none.contains("## "))
        assertTrue(none.contains("> Alone"))
    }

    @Test
    fun coloursAreTagsAndTheDefaultLookHasNone() {
        val md =
            NotesMarkdown.build(
                book,
                listOf(
                    highlight("Plain", pointer = "/body/DocFragment[2]/body/p[1]", date = ""),
                    highlight("Pink", pointer = "/body/DocFragment[2]/body/p[2]", colour = "pink"),
                    highlight(
                        "Lined",
                        pointer = "/body/DocFragment[2]/body/p[3]",
                        colour = "underline",
                    ),
                ),
                day,
            )
        assertTrue(md.contains("> Plain\n\n> Pink"))
        assertTrue(md.contains("> Pink\n\n#highlight/pink · 16/10/2026\n"))
        assertTrue(md.contains("#highlight/underline · 16/10/2026"))
        assertNull(NotesMarkdown.tag(""))
        assertEquals("#highlight/red", NotesMarkdown.tag(" Red "))
    }

    @Test
    fun aNoteWithoutAPassageIsStillWritten() {
        val md = NotesMarkdown.build(book, listOf(highlight("", note = "Only a thought")), day)
        assertTrue(md.contains("## Chapter one\n\nOnly a thought\n"))
        assertFalse(md.contains("> "))
    }

    @Test
    fun bookmarksComeLastAsAList() {
        val md =
            NotesMarkdown.build(
                book,
                listOf(
                    ExportNote(
                        true,
                        "Chapter one · 4%",
                        date = "2026-10-15 09:00:00",
                        place = NotePlace(progression = 0.04),
                    ),
                    highlight("A passage"),
                    ExportNote(
                        true,
                        "",
                        note = "Come back\n- here",
                        chapter = "Chapter two",
                        date = "2026-10-14 10:00:00",
                        place = NotePlace.of("/body/DocFragment[3]/body/p[1]"),
                    ),
                ),
                day,
                ExportLabels(bookmarks = "Bookmarks"),
            )
        val marks = md.substringAfter("## Bookmarks\n\n")
        assertTrue(md.indexOf("> A passage") < md.indexOf("## Bookmarks"))
        // Kindle bookmarks (a known chapter) first, then the phone's, by place.
        assertEquals(
            "- Chapter two · 14/10/2026\n  Come back\n  \\- here\n- Chapter one · 4% · 15/10/2026\n",
            marks,
        )
    }

    @Test
    fun onlyBookmarksStillGetTheirSection() {
        val md = NotesMarkdown.build(book, listOf(ExportNote(true, "Here")), day)
        assertTrue(md.endsWith("# The Orchard\n\n## Bookmarks\n\n- Here\n"))
    }

    @Test
    fun fileNameIsTitleAndFirstAuthorInSafeCharacters() {
        assertEquals(
            "The Orchard - Ada Lovelace.md",
            NotesMarkdown.fileName("The Orchard", listOf("Ada Lovelace", "B")),
        )
        assertEquals("Why - me.md", NotesMarkdown.fileName("Why?", listOf("me")))
        assertEquals("A B C D E - F G.md", NotesMarkdown.fileName("A/B\\C:D*E", listOf("F|G")))
        assertEquals("x y z - w.md", NotesMarkdown.fileName("x#y^[z]", listOf("  ", "w")))
        assertEquals("Solo.md", NotesMarkdown.fileName("Solo", emptyList()))
        assertEquals("Highlights.md", NotesMarkdown.fileName("  ...  ", emptyList()))
        assertEquals("hidden.md", NotesMarkdown.fileName("..hidden.", emptyList()))
        assertEquals("Señor - Ñandú.md", NotesMarkdown.fileName("Señor", listOf("Ñandú")))
        val long = NotesMarkdown.fileName("x".repeat(300), listOf("y"))
        assertEquals(NotesMarkdown.NAME_MAX + 3, long.length)
        // A cut never splits a character in two.
        val emoji = NotesMarkdown.fileName("📚".repeat(150), emptyList())
        assertEquals(
            NotesMarkdown.NAME_MAX,
            emoji.removeSuffix(".md").codePointCount(0, emoji.length - 3),
        )
        assertFalse(NotesMarkdown.fileName("tab\there\nnew", emptyList()).any { it.isISOControl() })
    }

    @Test
    fun placesCompareNumbersAsNumbers() {
        val a = NotePlace.of("/body/DocFragment[2]/body/p[9]/text().12")
        val b = NotePlace.of("/body/DocFragment[2]/body/p[10]/text().3")
        val c = NotePlace.of("/body/DocFragment[10]/body/p[1]")
        assertEquals(2, a.fragment)
        assertEquals(1, NotePlace.of("/body/DocFragment/body/p[1]").fragment)
        assertNull(NotePlace.of("12").fragment)
        assertEquals(listOf(a, b, c), listOf(c, b, a).sortedWith(NotePlace.ORDER))
        assertEquals(0, NotePlace.naturalCompare("p[007]", "p[7]"))
        assertTrue(NotePlace.naturalCompare("text().9", "text().10") < 0)
    }

    @Test
    fun onlyThisBooksExportCountsAsOurs() {
        val md = NotesMarkdown.build(book, emptyList(), day)
        assertTrue(NotesMarkdown.isExportOf(md, "b1"))
        assertTrue(NotesMarkdown.isExportOf(md.replace("\n", "\r\n"), "b1"))
        assertFalse(NotesMarkdown.isExportOf(md, "b2"))
        assertFalse(NotesMarkdown.isExportOf("# My own notes\nlibrillo_id: \"b1\"\n", "b1"))
        assertFalse(NotesMarkdown.isExportOf("---\ntitle: x\n---\nlibrillo_id: \"b1\"\n", "b1"))
    }
}
