package dev.sadking.folio.reader.narration

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheKeyTest {
    private val base = CacheKey.of("int8:4c3052ab", 3, 1.0, 0.4f, "Hello there.")

    @Test
    fun isSixtyFourLowercaseHexCharacters() {
        assertEquals(64, base.length)
        assertTrue(base.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun isStableAndHashesTheDocumentedString() {
        assertEquals(base, CacheKey.of("int8:4c3052ab", 3, 1.0, 0.4f, "Hello there."))
        val source =
            "v1|int8:4c3052ab|sid=3|speed=1.0|silence=0.4|en-us|${SpeechText.VERSION}|Hello there."
        val expected =
            MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") {
                String.format("%02x", it)
            }
        assertEquals(expected, base)
        assertEquals(1, SpeechText.VERSION)
    }

    @Test
    fun changesWithEveryField() {
        val others =
            listOf(
                CacheKey.of("fp32:c5f7e2d2", 3, 1.0, 0.4f, "Hello there."),
                CacheKey.of("int8:4c3052ab", 4, 1.0, 0.4f, "Hello there."),
                CacheKey.of("int8:4c3052ab", 3, 1.5, 0.4f, "Hello there."),
                CacheKey.of("int8:4c3052ab", 3, 1.0, 0.5f, "Hello there."),
                CacheKey.of("int8:4c3052ab", 3, 1.0, 0.4f, "Hello there!"),
            )
        others.forEach { assertNotEquals(base, it) }
        assertEquals(others.size, others.toSet().size)
    }
}
