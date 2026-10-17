package dev.sadking.folio.reader.narration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EdgeSilenceTest {
    private fun tone(n: Int, level: Short = 10_000) =
        ShortArray(n) { if (it % 2 == 0) level else (-level).toShort() }

    private fun silence(n: Int) = ShortArray(n)

    @Test
    fun trimsOnlyTheEdgesDownToTheKeep() {
        val pcm = silence(1000) + tone(500) + silence(2000)
        val trim = EdgeSilence.trim(pcm, threshold = 0.005, keepFrames = 100)
        assertEquals(700, trim.pcm.size)
        assertEquals(900, trim.leadTrimmedFrames)
        assertEquals(1900, trim.trailTrimmedFrames)
        assertEquals(0, trim.pcm[99].toInt())
        assertEquals(10_000, trim.pcm[100].toInt())
    }

    @Test
    fun neverTrimsSilenceInsideSpeech() {
        val pcm = tone(300) + silence(3000) + tone(300)
        val trim = EdgeSilence.trim(pcm, threshold = 0.005, keepFrames = 100)
        assertSame(pcm, trim.pcm)
        assertEquals(0, trim.leadTrimmedFrames)
        assertEquals(0, trim.trailTrimmedFrames)
    }

    @Test
    fun edgesShorterThanTheKeepStay() {
        val pcm = silence(50) + tone(200) + silence(80)
        assertArrayEquals(pcm, EdgeSilence.trim(pcm, 0.005, 100).pcm)
    }

    @Test
    fun quietSamplesBelowTheThresholdCountAsSilence() {
        // 0.005 × 32767 ≈ 164.
        val pcm = ShortArray(500) { 100 } + tone(10) + ShortArray(500) { 200 }
        val trim = EdgeSilence.trim(pcm, 0.005, 10)
        assertEquals(490, trim.leadTrimmedFrames)
        assertEquals(0, trim.trailTrimmedFrames)
    }

    @Test
    fun aSilentUnitKeepsOnlyTheKeep() {
        val trim = EdgeSilence.trim(silence(1000), 0.005, 120)
        assertEquals(120, trim.pcm.size)
        assertEquals(880, trim.leadTrimmedFrames)
    }

    @Test
    fun gapFramesByKindAndSpeed() {
        val t = NarrationTuning.DEFAULT
        // Target pause minus the two 50 ms edges kept on either side of the boundary.
        assertEquals(5280, EdgeSilence.gapFrames(GapKind.SENTENCE, 1.0, 24_000, t))
        assertEquals(12_000, EdgeSilence.gapFrames(GapKind.PARAGRAPH, 1.0, 24_000, t))
        assertEquals(480, EdgeSilence.gapFrames(GapKind.CLAUSE, 1.0, 24_000, t))
        assertEquals(0, EdgeSilence.gapFrames(GapKind.FRAGMENT, 1.0, 24_000, t))
        assertEquals(1440, EdgeSilence.gapFrames(GapKind.SENTENCE, 2.0, 24_000, t))
        assertEquals(0, EdgeSilence.gapFrames(GapKind.CLAUSE, 2.0, 24_000, t))
        assertEquals(
            4800,
            EdgeSilence.gapFrames(GapKind.PARAGRAPH, 1.0, 24_000, t.copy(edgeKeepSeconds = 0.2)),
        )
    }

    @Test
    fun measuresEdgesOfASyntheticSignal() {
        val pcm = silence(240) + ShortArray(100) { 300 } + tone(1000) + silence(480)
        // 300 is under 0.01 × 32767 ≈ 328, so it is silence too.
        assertEquals(340 to 480, EdgeSilence.measureEdges(pcm))
        assertEquals(240 to 480, EdgeSilence.measureEdges(pcm, threshold = 0.005))
        assertEquals(50 to 50, EdgeSilence.measureEdges(silence(50)))
    }
}
