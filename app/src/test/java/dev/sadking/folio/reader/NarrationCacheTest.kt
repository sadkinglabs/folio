package dev.sadking.folio.reader

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationCacheTest {
    private val dir: File = Files.createTempDirectory("narration").toFile()

    private fun pcm(frames: Int, seed: Int = 1) =
        ShortArray(frames) { (it * seed % 3000).toShort() }

    private fun key(i: Int) = "%064x".format(i)

    /** Bytes one entry of [frames] takes on disk. */
    private fun bytes(frames: Int) = NarrationCache.HEADER + frames * 2L

    @Test
    fun audioComesBackAsWrittenUnderAHashedNameWithItsHeader() {
        val cache = NarrationCache(dir)
        val audio = pcm(5_000, seed = 7)
        cache.write(key(1), KokoroModel.Variant.INT8, 24_000, audio)
        val back = cache.read(key(1))
        assertNotNull(back)
        assertEquals(24_000, back!!.sampleRate)
        assertArrayEquals(audio, back.pcm)
        val file = File(dir, key(1) + ".pcm")
        assertEquals(bytes(5_000), file.length())
        assertEquals("NAR1", String(file.readBytes().copyOfRange(0, 4)))
        assertNull(cache.read(key(2)))
    }

    @Test
    fun writesAreAtomicAndLeftoversAreCleanedOnOpen() {
        NarrationCache(dir).write(key(1), KokoroModel.Variant.INT8, 24_000, pcm(100))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        File(dir, key(2) + ".tmp").writeText("half a write")
        val reopened = NarrationCache(dir)
        assertNotNull(reopened.read(key(1)))
        assertFalse(File(dir, key(2) + ".tmp").exists())
        assertEquals(bytes(100), reopened.sizeBytes)
    }

    @Test
    fun theLeastRecentlyUsedGoUntilNinetyPercentOfTheBudget() {
        val budget = bytes(1_000) * 10
        val cache = NarrationCache(dir, budgetBytes = budget, evictToFraction = 0.9)
        for (i in 0 until 10) cache.write(key(i), KokoroModel.Variant.INT8, 24_000, pcm(1_000))
        assertNotNull(cache.read(key(0))) // used again: now the most recent
        cache.write(key(10), KokoroModel.Variant.INT8, 24_000, pcm(1_000))
        assertTrue(cache.sizeBytes <= budget * 0.9)
        assertNotNull(cache.read(key(0)))
        assertNull("oldest unused went first", cache.read(key(1)))
        assertNotNull(cache.read(key(10)))
    }

    @Test
    fun pinnedEntriesSurviveEviction() {
        val budget = bytes(1_000) * 4
        val cache = NarrationCache(dir, budgetBytes = budget, evictToFraction = 0.5)
        for (i in 0 until 4) cache.write(key(i), KokoroModel.Variant.INT8, 24_000, pcm(1_000))
        cache.pin(listOf(key(0), key(1)))
        cache.write(key(4), KokoroModel.Variant.INT8, 24_000, pcm(1_000))
        assertNotNull(cache.read(key(0)))
        assertNotNull(cache.read(key(1)))
        assertNull(cache.read(key(2)))
    }

    @Test
    fun aDamagedEntryIsAMissAndIsDeleted() {
        val cache = NarrationCache(dir)
        cache.write(key(1), KokoroModel.Variant.INT8, 24_000, pcm(1_000))
        val file = File(dir, key(1) + ".pcm")
        file.writeBytes(file.readBytes().copyOf(500))
        assertNull(cache.read(key(1)))
        assertFalse(file.exists())
        File(dir, key(2) + ".pcm").writeText("not audio at all")
        assertNull(NarrationCache(dir).read(key(2)))
        assertFalse(File(dir, key(2) + ".pcm").exists())
    }

    @Test
    fun removingAVariantClearsOnlyItsAudio() {
        val cache = NarrationCache(dir)
        cache.write(key(1), KokoroModel.Variant.INT8, 24_000, pcm(100))
        cache.write(key(2), KokoroModel.Variant.FP32, 24_000, pcm(100))
        cache.removeVariant(KokoroModel.Variant.FP32)
        assertNotNull(cache.read(key(1)))
        assertNull(cache.read(key(2)))
        assertEquals(bytes(100), cache.sizeBytes)
    }
}
