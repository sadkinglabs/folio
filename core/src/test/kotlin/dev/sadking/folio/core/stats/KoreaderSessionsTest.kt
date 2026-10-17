package dev.sadking.folio.core.stats

import dev.sadking.folio.core.stats.KoreaderSessions.PageEvent
import dev.sadking.folio.core.stats.KoreaderSessions.WireSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KoreaderSessionsTest {
    private val cases =
        JSONObject(checkNotNull(javaClass.getResource("/stats/koreader-sessions.json")).readText())
            .getJSONArray("cases")

    private fun events(array: JSONArray) =
        (0 until array.length()).map {
            val e = array.getJSONArray(it)
            PageEvent(e.getString(0), e.getInt(1), e.getLong(2), e.getLong(3), e.getInt(4))
        }

    private fun fraction(o: JSONObject, name: String) =
        if (o.isNull(name)) null else o.getDouble(name)

    @Test
    fun everySharedCaseMatches() {
        assertTrue(cases.length() >= 10)
        (0 until cases.length()).forEach { i ->
            val case = cases.getJSONObject(i)
            val expected =
                case.getJSONArray("sessions").let { list ->
                    (0 until list.length()).map {
                        val s = list.getJSONObject(it)
                        WireSession(
                            s.getString("book"),
                            s.getLong("start"),
                            s.getLong("seconds"),
                            s.getInt("pages"),
                            fraction(s, "from"),
                            fraction(s, "to"),
                            fraction(s, "read"),
                        )
                    }
                }
            assertEquals(
                case.getString("name"),
                expected,
                KoreaderSessions.derive(
                    events(case.getJSONArray("events")),
                    events(case.getJSONArray("before")),
                ),
            )
        }
    }

    @Test
    fun theIdIsStableAndDependsOnDeviceBookAndStart() {
        val book = "0123456789abcdef0123456789abcdef"
        val id = KoreaderSessions.sessionId("kindle-1", book, 1700000000)
        assertEquals(id, KoreaderSessions.sessionId("kindle-1", book.uppercase(), 1700000000))
        assertEquals(33, id.length)
        assertTrue(id.startsWith("k"))
        assertNotEquals(id, KoreaderSessions.sessionId("kindle-2", book, 1700000000))
        assertNotEquals(id, KoreaderSessions.sessionId("kindle-1", book, 1700000001))
    }

    @Test
    fun aWireSessionBecomesAKindleSessionForItsDocument() {
        val wire =
            WireSession("0123456789abcdef0123456789abcdef", 1700000000, 300, 4, 0.1, 0.14, 0.04)
        val s = KoreaderSessions.toSession("kindle-1", "ereader:kindle-1", wire)
        assertEquals(SessionSource.KINDLE, s.source)
        assertNull(s.bookId)
        assertEquals("doc:0123456789abcdef0123456789abcdef", s.book)
        assertEquals(1_700_000_000_000, s.startMs)
        assertEquals(300, s.durationSec)
        assertEquals(0.04, s.progressRead!!, 0.0)
        assertEquals(4, s.pages)
        assertEquals(KoreaderSessions.sessionId("kindle-1", wire.book, wire.start), s.id)
    }

    @Test
    fun theWatermarkIsTheLatestEndAndTheCutLooksBackADay() {
        val a =
            WireSession("0123456789abcdef0123456789abcdef", 1700000000, 300, 4, null, null, null)
        val b = a.copy(start = 1700100000, seconds = 60)
        val stored = listOf(a, b).map { KoreaderSessions.toSession("k", "ereader:k", it) }
        assertEquals(1700100060, KoreaderSessions.until(stored))
        assertEquals(0, KoreaderSessions.until(emptyList()))
        assertEquals(0, KoreaderSessions.cut(0))
        assertEquals(1700100060 - 86_400, KoreaderSessions.cut(1700100060))
        assertEquals(0, KoreaderSessions.cut(1000))
    }

    @Test
    fun thePhoneAcceptsOnlySaneSessions() {
        val now = 1_800_000_000L
        val ok = WireSession("0123456789abcdef0123456789abcdef", 1700000000, 300, 4, 0.1, 0.2, 0.1)
        assertTrue(KoreaderSessions.accepts(ok, now))
        assertTrue(KoreaderSessions.accepts(ok.copy(from = null, to = null, read = null), now))
        assertFalse(KoreaderSessions.accepts(ok.copy(book = "abc"), now))
        assertFalse(KoreaderSessions.accepts(ok.copy(start = 1000), now))
        assertFalse(KoreaderSessions.accepts(ok.copy(start = now + 2 * 86_400), now))
        assertFalse(KoreaderSessions.accepts(ok.copy(seconds = 0), now))
        assertFalse(KoreaderSessions.accepts(ok.copy(seconds = 86_401), now))
        assertFalse(KoreaderSessions.accepts(ok.copy(pages = -1), now))
        assertFalse(KoreaderSessions.accepts(ok.copy(read = 1.5), now))
    }
}
