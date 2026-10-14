package dev.sadking.folio.core

import org.junit.Assert.*
import org.junit.Test

class ReadingDataTest {
    private val fixture =
        """
        -- KOReader sidecar, independently constructed
                return {
                    ["last_xpointer"] = "/body/DocFragment[2]/p[7].0",
                    ["percent_finished"] = 0.42,
                    ["summary"] = { ["status"] = "reading", ["rating"] = 4 },
                    ["annotations"] = {
                        [1] = { ["text"] = "A passage\\nwith a second line", ["note"] = "Remember this", ["chapter"] = "Chapter 2", ["pos0"] = "start", ["pos1"] = "end", ["datetime"] = "2026-10-11 21:00:00" },
                        [2] = { ["text"] = "A bookmark", ["page"] = "pointer", ["datetime"] = "2026-10-11 21:01:00" },
                    },
                }
        """
            .trimIndent()

    @Test
    fun modernAnnotationsAndExactPosition() {
        val data = KoReaderData.parse(fixture.toByteArray())
        assertEquals(0.42, data.percent!!, 0.001)
        assertEquals("/body/DocFragment[2]/p[7].0", data.position)
        assertEquals("reading", data.status)
        assertEquals(4, data.rating)
        assertEquals(2, data.annotations.size)
        assertEquals("highlight", data.annotations[0].kind)
        assertEquals("bookmark", data.annotations[1].kind)
        assertEquals("Remember this", data.annotations[0].note)
    }

    @Test
    fun executableLuaRejected() {
        listOf(
                "return os.execute('touch /tmp/bad')",
                "return { x = function() end }",
                "return {x = 1 + 2}",
                "return {} print('bad')",
            )
            .forEach { assertThrows(Exception::class.java) { LuaData(it).parse() } }
    }

    @Test
    fun oversizedDeepAndDuplicateTablesRejected() {
        assertThrows(Exception::class.java) {
            LuaData("return " + "{".repeat(60) + "}".repeat(60)).parse()
        }
        assertThrows(Exception::class.java) { LuaData("return {x=1,x=2}").parse() }
        assertThrows(Exception::class.java) {
            LuaData("return {x='" + "x".repeat(4 * 1024 * 1024) + "'}").parse()
        }
    }

    @Test
    fun malformedNumbersAndInvalidUtf8Rejected() {
        assertThrows(Exception::class.java) { KoReaderData.parse(byteArrayOf(0xff.toByte())) }
        assertThrows(Exception::class.java) { LuaData("return {x=1e999}").parse() }
        assertNull(KoReaderData.parse("return {percent_finished=9}".toByteArray()).percent)
    }

    @Test
    fun legacyHighlightsAndBookmarks() {
        val data =
            KoReaderData.parse(
                "return {highlight={[3]={[1]={text='Legacy',pos0='x',pos1='y'}}},bookmarks={{text='Bookmark',page='z'}}}"
                    .toByteArray()
            )
        assertEquals(listOf("highlight", "bookmark"), data.annotations.map { it.kind })
    }

    @Test
    fun annotationIdentityStableAndNoteRevisionsRetained() {
        val a = KoReaderData.parse(fixture.toByteArray()).annotations
        assertEquals(a, KoReaderData.parse(fixture.toByteArray()).annotations)
        val b =
            KoReaderData.parse(fixture.replace("Remember this", "Changed note").toByteArray())
                .annotations
        assertNotEquals(a[0].key, b[0].key)
        assertEquals(a[1].key, b[1].key)
    }

    @Test
    fun markdownMultilineQuotesAndNotes() {
        val a =
            ReaderAnnotation(
                "id",
                "line one\nline two",
                "my note",
                "Chapter",
                "2026-10-11",
                "x",
                "y",
                "highlight",
            )
        val m = KoReaderData.markdown("Title", "Author", listOf(a))
        assertTrue(m.contains("> line one\n> line two"))
        assertTrue(m.contains("my note"))
    }

    @Test
    fun stableSidecarRead() {
        val remote = FakeDevice()
        remote.files["folder/book.sdr/metadata.epub.lua"] = fixture.toByteArray()
        assertEquals(
            0.42,
            ReadingRetriever.read(remote, "folder/book.epub")!!.first.percent!!,
            0.001,
        )
        assertNull(ReadingRetriever.read(remote, "missing.epub"))
    }

    @Test
    fun changingSidecarRejected() {
        val base = FakeDevice()
        base.files["book.sdr/metadata.epub.lua"] = fixture.toByteArray()
        var reads = 0
        val remote =
            object : DeviceFiles by base {
                override fun read(path: String): java.io.InputStream {
                    reads++
                    return (if (reads == 1) fixture else fixture.replace("0.42", "0.43"))
                        .byteInputStream()
                }
            }
        assertThrows(Exception::class.java) { ReadingRetriever.read(remote, "book.epub") }
    }
}
