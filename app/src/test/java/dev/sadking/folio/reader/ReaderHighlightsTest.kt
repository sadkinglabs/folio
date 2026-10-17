package dev.sadking.folio.reader

import dev.sadking.folio.data.BookAnnotation
import dev.sadking.folio.sync.SyncAnnotations
import java.security.MessageDigest
import java.time.LocalDateTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReaderHighlightsTest {
    private val pos0 = "/body/DocFragment[3]/body/p[2]/text().4"
    private val pos1 = "/body/DocFragment[3]/body/p[2]/text().11"
    private val passage = SelectedPassage(pos0, pos1, "orchard")

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    /** What WebView hands back for `JSON.stringify(value)`: the JSON of that JSON string. */
    private fun fromPage(value: Any): String = JSONObject.quote(value.toString())

    private fun row(
        id: String,
        start: String,
        end: String,
        kind: String = "highlight",
        device: String = "link:kindle",
    ) = BookAnnotation(id, "b1", device, "text", "", "One", "2026-10-16 09:00:00", start, end, kind)

    @Test
    fun idFollowsTheProtocolRecipe() {
        val id = annotationId("phone", "2026-10-16 09:05:03", pos0)
        assertEquals(sha256("phone\n2026-10-16 09:05:03\n$pos0").take(32), id)
        assertTrue(id, Regex("^[0-9a-f]{32}$").matches(id))
        // Any change of device, time or start is another annotation.
        assertNotEquals(id, annotationId("link:kindle", "2026-10-16 09:05:03", pos0))
        assertNotEquals(id, annotationId("phone", "2026-10-16 09:05:04", pos0))
        assertNotEquals(id, annotationId("phone", "2026-10-16 09:05:03", pos1))
    }

    @Test
    fun datetimeIsKoreadersLocalFormat() {
        val text = annotationDatetime(LocalDateTime.of(2026, 1, 2, 3, 4, 5, 999_000_000))
        assertEquals("2026-01-02 03:04:05", text)
        // The pattern sync.lua accepts for an incoming annotation's time.
        assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$").matches(text))
    }

    @Test
    fun phoneHighlightCarriesKoreaderPointersAndThePhoneDevice() {
        val made = phoneAnnotation("b1", passage, "  ", "Chapter two", "2026-10-16 09:05:03")
        assertEquals("phone", made.deviceId)
        assertEquals(PHONE_ANNOTATION_DEVICE, made.deviceId)
        assertEquals("b1", made.bookId)
        assertEquals(pos0, made.position)
        assertEquals(pos1, made.endPosition)
        assertEquals("orchard", made.text)
        assertEquals("", made.note)
        assertEquals("Chapter two", made.chapter)
        assertEquals("2026-10-16 09:05:03", made.date)
        assertEquals("highlight", made.kind)
        assertEquals(annotationId("phone", "2026-10-16 09:05:03", pos0), made.id)
    }

    @Test
    fun aNoteIsKindNoteWithItsTextTrimmed() {
        val made = phoneAnnotation("b1", passage, "  Worth rereading.\n", "", "2026-10-16 09:05:03")
        assertEquals("note", made.kind)
        assertEquals("Worth rereading.", made.note)
        // Same id rule as a highlight: the note does not enter the id.
        assertEquals(annotationId("phone", "2026-10-16 09:05:03", pos0), made.id)
    }

    @Test
    fun sameSecondSameStartUsesThePluginsFallbackId() {
        val made =
            phoneAnnotation("b1", passage, "", "", "2026-10-16 09:05:03", sameStartTaken = true)
        // sync.lua: annotation_id(device, datetime, anchor .. "\n" .. pos1 .. "\n" .. kind)
        assertEquals(
            sha256("phone\n2026-10-16 09:05:03\n$pos0\n$pos1\nhighlight").take(32),
            made.id,
        )
        assertNotEquals(phoneAnnotation("b1", passage, "", "", "2026-10-16 09:05:03").id, made.id)
    }

    @Test
    fun phoneRowsReachSyncWithTheirPointersAndId() {
        val made = phoneAnnotation("b1", passage, "Mine", "Two", "2026-10-16 09:05:03")
        val sent = SyncAnnotations.forSync(listOf(made)).single()
        assertEquals(made.id, sent.id)
        assertEquals(pos0, sent.pos0)
        assertEquals(pos1, sent.pos1)
        assertEquals("2026-10-16 09:05:03", sent.datetime)
        assertEquals("note", sent.kind)
        assertEquals("Mine", sent.note)
    }

    @Test
    fun selectionIsReadFromTheDoubleEncodedPageAnswer() {
        val answer = JSONObject().put("pos0", pos0).put("pos1", pos1).put("text", " orchard ")
        assertEquals(passage, parseSelection(fromPage(answer), 2, 3))
    }

    @Test
    fun selectionOutsideTheChapterOrWithoutPointersIsRefused() {
        fun answer(start: String, end: String, text: String = "orchard") =
            fromPage(JSONObject().put("pos0", start).put("pos1", end).put("text", text))
        // Another chapter than the one on screen.
        assertNull(parseSelection(answer(pos0, pos1), 1, 3))
        // End in another chapter.
        assertNull(parseSelection(answer(pos0, "/body/DocFragment[2]/body/p/text().1"), 2, 3))
        // Beyond the spine, unsupported shapes, no text.
        assertNull(parseSelection(answer(pos0, pos1), 2, 2))
        assertNull(parseSelection(answer("/body/DocFragment[3]/div/p", pos1), 2, 3))
        assertNull(parseSelection(answer("", pos1), 2, 3))
        assertNull(parseSelection(answer(pos0, pos1, "   "), 2, 3))
        // No selection, or not an answer at all.
        assertNull(parseSelection("\"null\"", 2, 3))
        assertNull(parseSelection("null", 2, 3))
        assertNull(parseSelection(null, 2, 3))
        assertNull(parseSelection("not json", 2, 3))
    }

    @Test
    fun onlyRangesInOneChapterArePlaced() {
        val rows =
            listOf(
                row("kindle", pos0, pos1),
                row("phone", pos0, pos1, kind = "note", device = "phone"),
                row(
                    "first",
                    "/body/DocFragment/body/p/text().0",
                    "/body/DocFragment/body/p/text().5",
                ),
                row("bookmark", pos0, pos0, kind = "bookmark"),
                row("pdf", "12", "12"),
                row("across", pos0, "/body/DocFragment[2]/body/p/text().3"),
                row("no end", pos0, ""),
                row("point", pos0, pos0),
                row(
                    "beyond",
                    "/body/DocFragment[9]/body/p/text().0",
                    "/body/DocFragment[9]/body/p/text().2",
                ),
            )
        val placeable = placeableHighlights(rows, 3)
        assertEquals(listOf("kindle", "phone", "first"), placeable.map { it.annotation.id })
        assertEquals(listOf(2, 2, 0), placeable.map { it.chapter })
    }

    @Test
    fun placementCallSendsEachHighlightsPointersAsJson() {
        val tricky = row("q\"1", "/body/DocFragment[3]/body/p[2]/text().4", "</script>'")
        val call = placementCall(listOf(tricky, row("b", pos0, pos1)))
        assertTrue(call, call.startsWith("JSON.stringify(FolioHighlights.place(["))
        assertTrue(call, call.endsWith("]));"))
        val items =
            JSONArray(
                call.removePrefix("JSON.stringify(FolioHighlights.place(").removeSuffix("));")
            )
        assertEquals(2, items.length())
        assertEquals("q\"1", items.getJSONObject(0).getString("id"))
        assertEquals("</script>'", items.getJSONObject(0).getString("pos1"))
        assertEquals(pos0, items.getJSONObject(1).getString("pos0"))
    }

    @Test
    fun placementKeepsCompleteQuotesOnly() {
        fun quote(selector: String, highlight: String) =
            JSONObject()
                .put("selector", selector)
                .put(
                    "text",
                    JSONObject()
                        .put("before", "The ")
                        .put("highlight", highlight)
                        .put("after", " was"),
                )
        val answer =
            JSONObject()
                .put("path", "/publication/OPS/two.xhtml")
                .put(
                    "placed",
                    JSONObject()
                        .put("a", quote("html > body:nth-of-type(1) > p:nth-of-type(2)", "orchard"))
                        .put("blank", quote("html > body:nth-of-type(1)", "  "))
                        .put("noSelector", quote("", "orchard"))
                        .put("broken", "x"),
                )
        val placement = parsePlacement(fromPage(answer))!!
        assertEquals("/publication/OPS/two.xhtml", placement.path)
        assertEquals(setOf("a"), placement.quotes.keys)
        assertEquals(
            HighlightQuote(
                "html > body:nth-of-type(1) > p:nth-of-type(2)",
                "The ",
                "orchard",
                " was",
            ),
            placement.quotes["a"],
        )
        assertNull(parsePlacement(fromPage(JSONObject().put("placed", JSONObject()))))
        assertNull(parsePlacement("\"null\""))
        assertNull(parsePlacement(null))
    }

    @Test
    fun highlightsArePlacedOnlyInTheirOwnPage() {
        assertTrue(samePage("/publication/OPS/two.xhtml", "OPS/two.xhtml"))
        assertTrue(samePage("/OPS/two.xhtml", "OPS/two.xhtml"))
        assertTrue(samePage("/publication/OPS/notes page.xhtml", "OPS/notes page.xhtml"))
        assertFalse(samePage("/publication/OPS/one.xhtml", "OPS/two.xhtml"))
        // Whole path segments only.
        assertFalse(samePage("/publication/XOPS/two.xhtml", "OPS/two.xhtml"))
        assertFalse(samePage("/publication/OPS/two.xhtml", ""))
    }

    @Test
    fun copyGivesThePassageThenTheNote() {
        val plain = row("a", pos0, pos1).copy(text = " The orchard. ")
        assertEquals("The orchard.", highlightCopyText(plain))
        assertEquals("The orchard.\n\nMine", highlightCopyText(plain.copy(note = "Mine ")))
        assertNotEquals(highlightTint(plain), highlightTint(plain.copy(note = "Mine")))
    }

    // ---- M11: one highlight per passage, colours ----

    @Test
    fun selectingTheSamePassageOpensItsHighlight() {
        val mine = row("mine", pos0, pos1)
        assertEquals(mine, existingHighlight(passage, listOf(row("other", pos1, pos1 + "9"), mine)))
    }

    @Test
    fun selectingInsideAHighlightOpensTheSmallestThatHoldsIt() {
        val wide = row("wide", "/a", "/z").copy(text = "a long passage around it")
        val narrow = row("narrow", "/b", "/y").copy(text = "around it")
        val elsewhere = row("elsewhere", "/c", "/d").copy(text = "x")
        val inside = passage.copy(inside = listOf("wide", "narrow"))
        assertEquals(narrow, existingHighlight(inside, listOf(wide, narrow, elsewhere)))
    }

    @Test
    fun anOverlapOrADeletedOneOrABookmarkIsANewHighlight() {
        // Overlapping only: the page does not list it as holding the selection.
        assertNull(existingHighlight(passage, listOf(row("overlap", "/a", pos1 + "x"))))
        assertNull(existingHighlight(passage, listOf(row("gone", pos0, pos1).copy(deletedAt = 5))))
        assertNull(existingHighlight(passage, listOf(row("mark", pos0, pos1, kind = "bookmark"))))
    }

    @Test
    fun thePageSaysWhichHighlightsHoldTheSelection() {
        val answer =
            JSONObject()
                .put("pos0", pos0)
                .put("pos1", pos1)
                .put("text", "orchard")
                .put("inside", JSONArray(listOf("h1", "", "h2")))
        assertEquals(
            passage.copy(inside = listOf("h1", "h2")),
            parseSelection(fromPage(answer), 2, 3),
        )
        val call = selectionCall(2, listOf(row("h1", pos0, pos1)))
        assertTrue(call, call.startsWith("JSON.stringify(FolioHighlights.selection(2, [{"))
        assertTrue(call, call.contains("\"id\":\"h1\""))
    }

    @Test
    fun aColourIsDrawnInItsTintAndAnUnderlineAsALine() {
        val plain = row("a", pos0, pos1)
        assertEquals(HIGHLIGHT_TINT, highlightTint(plain))
        assertEquals(HIGHLIGHT_TINT, highlightTint(plain.copy(color = "yellow")))
        assertNotEquals(HIGHLIGHT_TINT, highlightTint(plain.copy(color = "green")))
        assertEquals(
            swatchTint(dev.sadking.folio.data.HighlightColour.PINK),
            highlightTint(plain.copy(color = "purple")),
        )
        assertTrue(
            highlightStyle(plain.copy(drawer = "underscore"))
                is org.readium.r2.navigator.Decoration.Style.Underline
        )
        assertTrue(highlightStyle(plain) is org.readium.r2.navigator.Decoration.Style.Highlight)
    }

    @Test
    fun aPhoneHighlightKeepsThePickedColour() {
        val made =
            phoneAnnotation(
                "b1",
                passage,
                "",
                "One",
                "2026-10-16 09:05:03",
                colour = dev.sadking.folio.data.HighlightColour.UNDERLINE,
            )
        assertEquals("underscore", made.drawer)
        assertEquals("", made.color)
        val green =
            phoneAnnotation(
                "b1",
                passage,
                "",
                "One",
                "2026-10-16 09:05:03",
                colour = dev.sadking.folio.data.HighlightColour.GREEN,
            )
        assertEquals("green" to "lighten", green.color to green.drawer)
        assertEquals("green", SyncAnnotations.toSync(green).color)
    }
}
