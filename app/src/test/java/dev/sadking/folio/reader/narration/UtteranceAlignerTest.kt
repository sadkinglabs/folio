package dev.sadking.folio.reader.narration

import org.junit.Assert.assertEquals
import org.junit.Test

class UtteranceAlignerTest {
    private fun plan(indices: Iterable<Long>, key: (Long) -> String = { "k$it" }) = indices.map {
        PlannedUtterance(it, key(it), key(it), "c.xhtml", it, true, true)
    }

    private val plan = plan(10L..20L)

    private fun match(
        key: String,
        last: Long = 12,
        held: Long? = null,
        recent: List<Long> = emptyList(),
        window: Int = 3,
    ) = UtteranceAligner.match(key, plan, last, held, recent, window)

    @Test
    fun hitsTheNextThreePlannedUtterances() {
        assertEquals(Match.Planned(13), match("k13"))
        assertEquals(Match.Planned(14), match("k14"))
        assertEquals(Match.Planned(15), match("k15"))
    }

    @Test
    fun theWindowIsRespected() {
        assertEquals(Match.Miss, match("k16"))
        assertEquals(Match.Planned(16), match("k16", window = 4))
        assertEquals(Match.Miss, match("k12"))
    }

    @Test
    fun theHeldUtteranceResumes() {
        assertEquals(Match.Held, match("k12", held = 12))
    }

    @Test
    fun heldWinsOverAnIdenticalNextUtterance() {
        val repeated = plan(10L..20L) { if (it == 12L || it == 13L) "No." else "k$it" }
        assertEquals(Match.Held, UtteranceAligner.match("No.", repeated, 12, 12, emptyList(), 3))
        assertEquals(
            Match.Planned(13),
            UtteranceAligner.match("No.", repeated, 12, null, emptyList(), 3),
        )
    }

    @Test
    fun recentUtterancesServeSkipPrevious() {
        assertEquals(Match.Recent(11), match("k11", recent = listOf(10, 11)))
        val repeated = plan(10L..20L) { if (it == 10L || it == 11L) "Same." else "k$it" }
        assertEquals(
            Match.Recent(11),
            UtteranceAligner.match("Same.", repeated, 12, null, listOf(10, 11), 3),
        )
    }

    @Test
    fun anythingElseMisses() {
        assertEquals(Match.Miss, match("zzz", held = 12, recent = listOf(10, 11)))
        assertEquals(
            Match.Miss,
            UtteranceAligner.match("k1", emptyList(), -1, null, emptyList(), 3),
        )
    }

    @Test
    fun theFirstRequestMatchesFromTheStart() {
        val fresh = plan(0L..5L)
        assertEquals(
            Match.Planned(0),
            UtteranceAligner.match("k0", fresh, -1, null, emptyList(), 3),
        )
    }

    @Test
    fun aPlanWithHolesIsSearched() {
        val holes = plan(listOf(1L, 2L, 5L, 6L, 9L))
        assertEquals(Match.Planned(5), UtteranceAligner.match("k5", holes, 2, null, emptyList(), 3))
        assertEquals(Match.Recent(1), UtteranceAligner.match("k1", holes, 5, null, listOf(1), 3))
    }
}
