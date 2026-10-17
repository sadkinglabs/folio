package dev.sadking.folio.reader.narration

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceSpansTest {
    private val rate = 24_000

    private fun utterance(index: Long, text: String) =
        PlannedUtterance(index, normalizeKey(text), text, "c.xhtml", 0, true, false)

    private fun unit(vararg texts: String): SynthesisUnit {
        val parts = texts.mapIndexed { i, t -> UnitPart(utterance(i.toLong(), t), 0, t.length) }
        return SynthesisUnit(0, 1, parts, texts.joinToString(" "), GapKind.SENTENCE, false)
    }

    private fun tone(frames: Int) =
        ShortArray(frames) { (8000 * sin(it * 0.2)).roundToInt().toShort() }

    private fun assertCovers(ranges: List<IntRange>, n: Int) {
        assertEquals(0, ranges.first().first)
        assertEquals(n, ranges.last().last + 1)
        ranges.zipWithNext { a, b -> assertEquals(a.last + 1, b.first) }
    }

    @Test
    fun aSingleUtteranceIsExact() {
        val pcm = tone(30_000)
        assertEquals(listOf(0 until 30_000), UtteranceSpans.split(unit("Hello there."), pcm, rate))
    }

    @Test
    fun twoUtterancesSnapToTheMiddleOfTheNearbyPause() {
        val a = "Hello there, my friend."
        val b = "General Kenobi, you are a bold one."
        val n = 3 * rate
        val estimate = n * a.length / (a.length + b.length)
        val dipCentre = estimate + rate / 4 // 0.25 s later than the character share says
        val dipHalf = rate / 20 // a 100 ms pause
        val pcm = tone(n)
        for (i in dipCentre - dipHalf until dipCentre + dipHalf) pcm[i] = 0
        val ranges = UtteranceSpans.split(unit(a, b), pcm, rate)
        assertEquals(2, ranges.size)
        assertCovers(ranges, n)
        assertTrue(
            "boundary ${ranges[1].first} vs $dipCentre",
            abs(ranges[1].first - dipCentre) <= rate / 200,
        )
    }

    @Test
    fun aPauseOutsideTheSearchWindowIsIgnored() {
        val a = "Hello there, my friend."
        val b = "General Kenobi, you are a bold one."
        val n = 4 * rate
        val estimate = n * a.length / (a.length + b.length)
        val pcm = tone(n)
        val far = estimate + (0.6 * rate).toInt()
        for (i in far - 2400 until far + 2400) pcm[i] = 0
        val ranges = UtteranceSpans.split(unit(a, b), pcm, rate)
        assertTrue(abs(ranges[1].first - estimate) <= (0.4 * rate).toInt() + 1)
        assertTrue(abs(ranges[1].first - far) > rate / 10)
    }

    @Test
    fun threeUtterancesStayInOrderAndCoverTheAudio() {
        val n = 5 * rate
        val ranges =
            UtteranceSpans.split(
                unit("One.", "Two words here.", "And three at the end."),
                tone(n),
                rate,
            )
        assertEquals(3, ranges.size)
        assertCovers(ranges, n)
        assertTrue(ranges.all { !it.isEmpty() })
    }

    @Test
    fun tinyAudioStillGivesOneRangePerUtterance() {
        val ranges = UtteranceSpans.split(unit("One.", "Two.", "Three."), tone(2), rate)
        assertEquals(3, ranges.size)
        assertEquals(0, ranges.first().first)
        assertEquals(2, ranges.last().last + 1)
    }
}
