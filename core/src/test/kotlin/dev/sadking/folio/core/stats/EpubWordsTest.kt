package dev.sadking.folio.core.stats

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class EpubWordsTest {
    @Test
    fun wordsAreRunsOfLettersAndDigits() {
        assertEquals(0, EpubWords.count(""))
        assertEquals(4, EpubWords.count("One two,three. 4"))
        assertEquals(3, EpubWords.count("Don't stop — well-known"))
        assertEquals(2, EpubWords.count("Ça été"))
        assertEquals(2, EpubWords.count("trailing- 'quoted'"))
        // Each Chinese or Japanese character is a word.
        assertEquals(4, EpubWords.count("日本語だ"))
    }

    @Test
    fun markupKeepsOnlyTheBodyText() {
        val text =
            EpubWords.markupText(
                "<html><head><title>Not counted</title><style>p{}</style></head>" +
                    "<body><p>One</p><p>two&amp;three &#x41;b caf&eacute;</p>" +
                    "<script>var x = 1</script><br/>four</body></html>"
            )
        assertEquals(6, EpubWords.count(text))
    }

    @Test
    fun anEpubIsCountedOverItsSpine() {
        val file = epub("<p>One two three.</p>", "<p>Four five</p>")
        assertEquals(5L, EpubWords.count(file))
    }

    @Test
    fun anUnreadableFileHasNoCount() {
        val file = Files.createTempFile("folio-", ".epub").toFile().apply { deleteOnExit() }
        file.writeText("not a zip")
        assertNull(EpubWords.count(file))
    }

    private fun epub(vararg chapters: String): File {
        val file = Files.createTempFile("folio-", ".epub").toFile().apply { deleteOnExit() }
        val items =
            chapters.indices.joinToString("") {
                """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>"""
            }
        val spine = chapters.indices.joinToString("") { """<itemref idref="c$it"/>""" }
        val entries =
            linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to
                    """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>""",
                "OPS/package.opf" to
                    """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:words</dc:identifier><dc:title>Words</dc:title></metadata><manifest>$items</manifest><spine>$spine</spine></package>""",
            )
        chapters.forEachIndexed { i, body ->
            entries["OPS/c$i.xhtml"] =
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter</title></head><body>$body</body></html>"""
        }
        ZipOutputStream(file.outputStream()).use { z ->
            entries.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return file
    }
}
