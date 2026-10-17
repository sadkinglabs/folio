package dev.sadking.folio.reader.narration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16Test {
    @Test
    fun nanAndInfinityBecomeSilence() {
        val out =
            Pcm16.fromFloat(
                floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.5f),
                0,
            )
        assertArrayEquals(shortArrayOf(0, 0, 0, 16384), out)
    }

    @Test
    fun clampsToFullScale() {
        assertArrayEquals(shortArrayOf(32767, -32767), Pcm16.fromFloat(floatArrayOf(2f, -3f), 0))
    }

    @Test
    fun fadesTheFirstAndLast120Samples() {
        val out = Pcm16.fromFloat(FloatArray(1000) { 1f }, 120)
        assertEquals(0, out[0].toInt())
        assertEquals(16384, out[60].toInt())
        assertTrue(out[119] < 32767)
        assertEquals(32767, out[120].toInt())
        assertEquals(32767, out[879].toInt())
        assertTrue(out[880] < 32767)
        assertEquals(0, out[999].toInt())
        for (i in 1 until 120) assertTrue("rising at $i", out[i] > out[i - 1])
    }

    @Test
    fun fadeOnAShortBufferUsesHalfItsLength() {
        val out = Pcm16.fromFloat(FloatArray(10) { 1f }, 120)
        assertEquals(0, out[0].toInt())
        assertEquals(0, out[9].toInt())
        assertTrue(out[4] > 0)
    }

    @Test
    fun bytesAreLittleEndianAndRoundTrip() {
        val pcm = shortArrayOf(0, 1, -1, 32767, -32768, 258)
        val bytes = Pcm16.toBytesLittleEndian(pcm)
        assertEquals(12, bytes.size)
        assertEquals(0x02, bytes[10].toInt())
        assertEquals(0x01, bytes[11].toInt())
        assertArrayEquals(pcm, Pcm16.fromBytesLittleEndian(bytes))
        val framed = ByteArray(4) + bytes
        assertArrayEquals(pcm, Pcm16.fromBytesLittleEndian(framed, offset = 4))
        assertArrayEquals(shortArrayOf(1, -1), Pcm16.fromBytesLittleEndian(bytes, 2, 4))
    }

    @Test
    fun secondsFromFrames() {
        assertEquals(1.5, Pcm16.seconds(36_000, 24_000), 1e-12)
    }
}
