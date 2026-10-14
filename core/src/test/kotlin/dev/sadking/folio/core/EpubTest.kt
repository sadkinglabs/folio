package dev.sadking.folio.core

import java.io.File
import java.nio.file.Files
import java.util.zip.*
import org.junit.Assert.*
import org.junit.Test

class EpubTest {
    private fun epub(metadata: String = "", extra: Map<String, ByteArray> = emptyMap()): File {
        val file = Files.createTempFile("folio-", ".epub").toFile().apply { deleteOnExit() }
        val entries =
            linkedMapOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to
                    """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>"""
                        .toByteArray(),
                "OPS/package.opf" to
                    """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:edition-1</dc:identifier><dc:title>The Orchard</dc:title><dc:creator>Alex Reader</dc:creator><meta property="custom:keep">preserve me</meta>$metadata</metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="cover" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/></manifest><spine><itemref idref="chapter"/></spine></package>"""
                        .toByteArray(),
                "OPS/chapter.xhtml" to
                    "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>Unchanged chapter &amp; text</body></html>"
                        .toByteArray(),
                "OPS/cover.jpg" to byteArrayOf(1, 2, 3, 4),
            ) + extra
        ZipOutputStream(file.outputStream()).use { z ->
            entries.forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return file
    }

    private fun target() =
        Files.createTempFile("folio-edited-", ".epub").toFile().apply { deleteOnExit() }

    @Test
    fun extractsMetadataAndCover() {
        val p = Epub.parse(epub())
        assertEquals("The Orchard", p.metadata.title)
        assertEquals(listOf("Alex Reader"), p.metadata.authors)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), p.cover)
    }

    @Test
    fun parsesEpub3Series() {
        val p =
            Epub.parse(
                epub(
                    """<meta property="belongs-to-collection" id="series">Orchard Cycle</meta><meta refines="#series" property="collection-type">series</meta><meta refines="#series" property="group-position">2.5</meta>"""
                )
            )
        assertEquals("Orchard Cycle", p.metadata.series)
        assertEquals("2.5", p.metadata.seriesIndex)
    }

    @Test
    fun editPreservesOriginalAndChapterAndUnknownMetadata() {
        val source = epub()
        val before = Epub.sha256(source)
        val target = target()
        Epub.edit(
            source,
            target,
            Epub.parse(source)
                .metadata
                .copy(
                    title = "A & B",
                    authors = listOf("New Author"),
                    series = "Cycle",
                    seriesIndex = "1.5",
                ),
        )
        assertEquals(before, Epub.sha256(source))
        assertEquals("A & B", Epub.parse(target).metadata.title)
        assertEquals("Cycle", Epub.parse(target).metadata.series)
        ZipFile(source).use { a ->
            ZipFile(target).use { b ->
                assertArrayEquals(
                    a.getInputStream(a.getEntry("OPS/chapter.xhtml")).readBytes(),
                    b.getInputStream(b.getEntry("OPS/chapter.xhtml")).readBytes(),
                )
                val xml = b.getInputStream(b.getEntry("OPS/package.opf")).reader().readText()
                assertTrue(xml.contains("preserve me"))
                assertTrue(xml.contains("urn:uuid:edition-1"))
                val first = b.entries().nextElement()
                assertEquals("mimetype", first.name)
                assertEquals(ZipEntry.STORED, first.method)
            }
        }
    }

    @Test
    fun coverReplacementRoundTrips() {
        val source = epub()
        val target = target()
        Epub.edit(source, target, Epub.parse(source).metadata, byteArrayOf(5, 6), "image/png")
        assertArrayEquals(byteArrayOf(5, 6), Epub.parse(target).cover)
        assertEquals("image/png", Epub.parse(target).coverType)
    }

    @Test
    fun spineKeepsNonLinearItemsInPackageOrder() {
        val opf =
            """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">x</dc:identifier><dc:title>T</dc:title></metadata><manifest><item id="notes" href="text/notes%20page.xhtml" media-type="application/xhtml+xml"/><item id="c1" href="text/one.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="../two.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/><itemref idref="notes" linear="no"/><itemref idref="missing"/><itemref idref="c2"/></spine></package>"""
        val spine = Epub.spine(epub(extra = mapOf("OPS/package.opf" to opf.toByteArray())))
        assertEquals(listOf("OPS/text/one.xhtml", "OPS/text/notes page.xhtml", "two.xhtml"), spine)
    }

    @Test
    fun identicalBytesHaveIdenticalFingerprint() {
        val a = epub()
        val b = target()
        a.copyTo(b, overwrite = true)
        assertEquals(Epub.sha256(a), Epub.sha256(b))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesOriginalOverwrite() {
        val a = epub()
        Epub.edit(a, a, Epub.parse(a).metadata)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversalEntry() {
        Epub.parse(epub(extra = mapOf("../escape" to byteArrayOf(1))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidSeriesNumber() {
        val a = epub()
        Epub.edit(a, target(), Epub.parse(a).metadata.copy(seriesIndex = "NaN"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesEncryptedBookEditing() {
        val a = epub(extra = mapOf("META-INF/encryption.xml" to "<encryption/>".toByteArray()))
        Epub.edit(a, target(), Epub.parse(a).metadata)
    }

    @Test
    fun boundedCopyRejectsOversizeInput() {
        try {
            Epub.copyBounded(byteArrayOf(1, 2, 3).inputStream(), java.io.ByteArrayOutputStream(), 2)
            fail("Expected bound rejection")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun xmlEntityExpansionIsRejected() {
        val a =
            epub(
                extra =
                    mapOf(
                        "META-INF/container.xml" to
                            """<!DOCTYPE container [<!ENTITY e SYSTEM "file:///etc/passwd">]><container>&e;</container>"""
                                .toByteArray()
                    )
            )
        try {
            Epub.parse(a)
            fail("Expected unsafe XML rejection")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun primaryIdentifierCannotBeRemovedByEditor() {
        val a = epub()
        val b = target()
        Epub.edit(a, b, Epub.parse(a).metadata.copy(identifiers = listOf("isbn:123")))
        assertEquals(listOf("urn:uuid:edition-1", "isbn:123"), Epub.parse(b).metadata.identifiers)
    }

    @Test
    fun utf16ContainerIsSupported() {
        val container =
            """<?xml version="1.0" encoding="UTF-16"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>"""
        val a =
            epub(extra = mapOf("META-INF/container.xml" to container.toByteArray(Charsets.UTF_16)))
        assertEquals("The Orchard", Epub.parse(a).metadata.title)
    }

    @Test
    fun utf16DtdIsRejectedBeforeParserCreation() {
        val container =
            """<!DOCTYPE container [<!ENTITY e SYSTEM "file:///etc/passwd">]><container>&e;</container>"""
        val a =
            epub(extra = mapOf("META-INF/container.xml" to container.toByteArray(Charsets.UTF_16)))
        try {
            Epub.parse(a)
            fail("Expected DTD rejection")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun metadataWorksWhenParserSecurityFeaturesAreUnsupported() {
        val property = "javax.xml.parsers.DocumentBuilderFactory"
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, FeatureLimitedFactory::class.java.name)
            assertEquals("The Orchard", Epub.parse(epub()).metadata.title)
        } finally {
            if (previous == null) System.clearProperty(property)
            else System.setProperty(property, previous)
        }
    }

    @Test
    fun editingSeriesPreservesUnrelatedEpubCollections() {
        val a =
            epub(
                """<meta property="belongs-to-collection" id="club">Book Club</meta><meta refines="#club" property="collection-type">set</meta>"""
            )
        assertEquals("", Epub.parse(a).metadata.series)
        val b = target()
        Epub.edit(a, b, Epub.parse(a).metadata.copy(series = "New Series", seriesIndex = "2"))
        ZipFile(b).use { z ->
            val xml = z.getInputStream(z.getEntry("OPS/package.opf")).reader().readText()
            assertTrue(xml.contains("Book Club"))
            assertTrue(xml.contains("#club"))
        }
        assertEquals("New Series", Epub.parse(b).metadata.series)
    }

    @Test
    fun titleEditPreservesUnchangedCreatorRefinements() {
        val base = epub()
        val packageXml =
            ZipFile(base)
                .use { z -> z.getInputStream(z.getEntry("OPS/package.opf")).reader().readText() }
                .replace("<dc:creator>", "<dc:creator id=\"creator1\">")
                .replace(
                    "</metadata>",
                    "<meta refines=\"#creator1\" property=\"file-as\">Reader, Alex</meta></metadata>",
                )
        val a = epub(extra = mapOf("OPS/package.opf" to packageXml.toByteArray()))
        val b = target()
        Epub.edit(a, b, Epub.parse(a).metadata.copy(title = "New title"))
        ZipFile(b).use { z ->
            val xml = z.getInputStream(z.getEntry("OPS/package.opf")).reader().readText()
            assertTrue(xml.contains("creator1"))
            assertTrue(xml.contains("Reader, Alex"))
        }
    }
}

/** Models Android's limited factory feature surface while using a real JVM DOM builder. */
class FeatureLimitedFactory : javax.xml.parsers.DocumentBuilderFactory() {
    private val delegate = javax.xml.parsers.DocumentBuilderFactory.newDefaultInstance()

    override fun newDocumentBuilder(): javax.xml.parsers.DocumentBuilder {
        delegate.isNamespaceAware = isNamespaceAware
        return delegate.newDocumentBuilder()
    }

    override fun setFeature(name: String, value: Boolean) {
        throw javax.xml.parsers.ParserConfigurationException(name)
    }

    override fun getFeature(name: String): Boolean {
        throw javax.xml.parsers.ParserConfigurationException(name)
    }

    override fun setAttribute(name: String, value: Any) {
        delegate.setAttribute(name, value)
    }

    override fun getAttribute(name: String): Any = delegate.getAttribute(name)
}
