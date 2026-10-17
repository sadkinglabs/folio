package dev.sadking.folio.core.dictionary

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryPackageTest {
    private val temp = Files.createTempDirectory("dictionary-package").toFile()

    @After fun clean() = temp.deleteRecursively().let {}

    private fun zip(entries: List<Pair<String, String>>): File =
        File(temp, "package.zip").apply {
            ZipOutputStream(outputStream()).use { zip ->
                entries.forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    private val everyKeptFile =
        DictionaryPackage.KEEP.map { "oewn2025-plus/$it" to "content of $it" }

    @Test
    fun theKeepListIsTheTwelveLookupFiles() {
        assertEquals(
            setOf(
                "index.noun",
                "index.verb",
                "index.adj",
                "index.adv",
                "data.noun",
                "data.verb",
                "data.adj",
                "data.adv",
                "noun.exc",
                "verb.exc",
                "adj.exc",
                "adv.exc",
            ),
            DictionaryPackage.KEEP,
        )
    }

    @Test
    fun unpacksTheKeptFilesWithoutTheTopFolder() {
        val into = File(temp, "out")
        DictionaryPackage.unpack(
            zip(
                listOf("oewn2025-plus/" to "") +
                    everyKeptFile +
                    listOf(
                        "oewn2025-plus/index.sense" to "dropped",
                        "oewn2025-plus/README.md" to "dropped",
                    )
            ),
            into,
        )
        assertEquals(DictionaryPackage.KEEP, into.list()!!.toSet())
        assertEquals("content of data.noun", File(into, "data.noun").readText())
        assertFalse(File(into, "index.sense").exists())
        assertFalse(File(into, "oewn2025-plus").exists())
    }

    @Test
    fun aPathThatEscapesIsRefused() {
        val into = File(temp, "out")
        val escaping = zip(everyKeptFile + listOf("oewn2025-plus/../../index.noun" to "evil"))
        assertThrows(IOException::class.java) { DictionaryPackage.unpack(escaping, into) }
        assertFalse(File(temp, "index.noun").exists())
        val absolute = zip(everyKeptFile + listOf("/etc/index.noun" to "evil"))
        assertThrows(IOException::class.java) { DictionaryPackage.unpack(absolute, into) }
    }

    @Test
    fun aMissingKeptFileIsRefused() {
        val into = File(temp, "out")
        val incomplete = zip(everyKeptFile.filterNot { it.first.endsWith("/data.verb") })
        val failure =
            assertThrows(IOException::class.java) { DictionaryPackage.unpack(incomplete, into) }
        assertTrue(failure.message.orEmpty().contains("incomplete"))
    }

    @Test
    fun thePublishedPackageIsPinned() {
        assertEquals(10_982_041L, DictionaryPackage.BYTES)
        assertEquals(
            "cf4afe7ad4cca3f56091d791e665614c564ac84e3782de4e789cf5b745556e2e",
            DictionaryPackage.SHA256,
        )
        assertTrue(DictionaryPackage.URL.startsWith("https://github.com/globalwordnet/"))
    }
}
