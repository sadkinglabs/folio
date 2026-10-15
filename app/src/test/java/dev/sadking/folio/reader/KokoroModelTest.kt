package dev.sadking.folio.reader

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroModelTest {
    private fun archive(entries: Map<String, String>): File {
        val file = Files.createTempFile("kokoro", ".tar.bz2").toFile()
        TarArchiveOutputStream(BZip2CompressorOutputStream(file.outputStream())).use { tar ->
            entries.forEach { (name, text) ->
                val bytes = text.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return file
    }

    @Test
    fun keepsOnlyTheEnglishFilesWithoutThePackageFolder() {
        val into = Files.createTempDirectory("voice").toFile()
        KokoroModel.unpack(
            archive(
                mapOf(
                    "kokoro/model.int8.onnx" to "model",
                    "kokoro/voices.bin" to "voices",
                    "kokoro/lexicon-us-en.txt" to "us",
                    "kokoro/lexicon-zh.txt" to "zh",
                    "kokoro/dict/jieba.dict.utf8" to "dict",
                    "kokoro/espeak-ng-data/en_dict" to "en",
                )
            ),
            into,
        )
        assertEquals("model", File(into, "model.int8.onnx").readText())
        assertEquals("en", File(into, "espeak-ng-data/en_dict").readText())
        assertTrue(File(into, "lexicon-us-en.txt").isFile)
        assertFalse(File(into, "lexicon-zh.txt").exists())
        assertFalse(File(into, "dict").exists())
        assertFalse(File(into, "kokoro").exists())
    }

    @Test
    fun refusesAPathThatLeavesTheVoiceFolder() {
        val into = Files.createTempDirectory("voice").toFile()
        val bad = archive(mapOf("kokoro/espeak-ng-data/../../escaped" to "x"))
        assertThrows(IOException::class.java) { KokoroModel.unpack(bad, into) }
        assertFalse(File(into.parentFile, "escaped").exists())
    }

    @Test
    fun refusesAPackageWithoutTheModel() {
        val into = Files.createTempDirectory("voice").toFile()
        val empty = archive(mapOf("kokoro/voices.bin" to "voices"))
        assertThrows(IOException::class.java) { KokoroModel.unpack(empty, into) }
    }

    @Test
    fun sentencesMatchWhateverTheSpacing() {
        assertEquals(normalize("A  quiet\n beginning. "), normalize(" A quiet beginning."))
    }

    @Test
    fun longSentencesSplitAtClausesShortOnesStayWhole() {
        val short = "They shook hands over the body."
        assertEquals(listOf(short), clauses(short))
        val long =
            "A little squeamishly, Ben helped him turn the body on its belly, and the flesh was " +
                "cool, not yet cold, still pliant, while Jimmy stared closely at the back and then " +
                "pulled the jockey shorts down from the buttocks."
        val parts = clauses(long)
        assertTrue(parts.size > 1)
        assertEquals(long, parts.joinToString(""))
        assertTrue(parts.all { it.length >= 50 })
    }

    @Test
    fun cleanAudioSilencesBadValuesAndFadesTheEdges() {
        val raw =
            FloatArray(1000) { 0.5f }
                .also {
                    it[500] = Float.NaN
                    it[501] = 3f
                }
        val clean = cleanAudio(raw)
        assertEquals(0f, clean[500], 0f)
        assertEquals(1f, clean[501], 0f)
        assertEquals(0f, clean[0], 0f)
        assertEquals(0f, clean[999], 0f)
        assertEquals(0.5f, clean[300], 0f)
    }
}
