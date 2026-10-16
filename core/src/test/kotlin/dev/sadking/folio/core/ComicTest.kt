package dev.sadking.folio.core

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ComicTest {
    private fun cbz(vararg entries: Pair<String, ByteArray>): File =
        Files.createTempFile("folio-", ".cbz").toFile().apply {
            deleteOnExit()
            ZipOutputStream(outputStream()).use { z ->
                entries.forEach { (name, data) ->
                    z.putNextEntry(ZipEntry(name))
                    z.write(data)
                    z.closeEntry()
                }
            }
        }

    private val info =
        """<?xml version="1.0" encoding="utf-8"?>
        <ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <Title>The Hunt</Title>
          <Series>Saga</Series>
          <Number>3</Number>
          <Writer>Brian K. Vaughan, Fiona Staples</Writer>
          <Penciller>Fiona Staples</Penciller>
          <Summary>Alana and Marko run.</Summary>
          <Publisher>Image</Publisher>
          <LanguageISO>en</LanguageISO>
          <Genre>Science Fiction, Fantasy</Genre>
        </ComicInfo>"""

    @Test
    fun comicInfoFields() {
        val m = Comic.comicInfo(info.toByteArray())!!
        assertEquals("The Hunt", m.title)
        assertEquals(listOf("Brian K. Vaughan", "Fiona Staples"), m.authors)
        assertEquals("Saga", m.series)
        assertEquals("3", m.seriesIndex)
        assertEquals("Alana and Marko run.", m.description)
        assertEquals("Image", m.publisher)
        assertEquals("en", m.language)
        assertEquals(listOf("Science Fiction", "Fantasy"), m.tags)
    }

    @Test
    fun withoutATitleTheSeriesAndNumberNameIt() {
        val m =
            Comic.comicInfo(
                "<ComicInfo><Series>Saga</Series><Number>12</Number></ComicInfo>".toByteArray()
            )!!
        assertEquals("Saga #12", m.title)
        val odd =
            Comic.comicInfo(
                "<ComicInfo><Series>Saga</Series><Number>1/2</Number></ComicInfo>".toByteArray()
            )!!
        // Only a number is kept as the series number; edits require one.
        assertEquals("", odd.seriesIndex)
    }

    @Test
    fun unsafeOrForeignXmlIsIgnored() {
        assertNull(
            Comic.comicInfo(
                """<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><ComicInfo><Title>&e;</Title></ComicInfo>"""
                    .toByteArray()
            )
        )
        assertNull(Comic.comicInfo("<html><Title>x</Title></html>".toByteArray()))
        assertNull(Comic.comicInfo("not xml".toByteArray()))
    }

    @Test
    fun pagesInNaturalOrderWithoutClutter() {
        val names =
            listOf(
                "Saga 03/page10.jpg",
                "Saga 03/page2.JPG",
                "Saga 03/page1.png",
                "Saga 03/ComicInfo.xml",
                "__MACOSX/Saga 03/._page1.png",
                "Saga 03/.thumb.jpg",
                "Saga 03/",
                "Saga 03/page002b.webp",
            )
        assertEquals(
            listOf(
                "Saga 03/page1.png",
                "Saga 03/page2.JPG",
                "Saga 03/page002b.webp",
                "Saga 03/page10.jpg",
            ),
            Comic.pages(names),
        )
    }

    @Test
    fun parseUsesComicInfoAndTheFirstPageAsCover() {
        val file =
            cbz(
                "p10.jpg" to byteArrayOf(10),
                "p2.jpg" to byteArrayOf(2),
                "ComicInfo.xml" to info.toByteArray(),
            )
        val parsed = Comic.parse(file, "whatever.cbz")
        assertEquals("The Hunt", parsed.metadata.title)
        assertArrayEquals(byteArrayOf(2), parsed.cover)
        assertEquals("image/jpeg", parsed.coverType)
    }

    @Test
    fun parseWithoutComicInfoUsesTheFileName() {
        val parsed = Comic.parse(cbz("01.png" to byteArrayOf(1)), "Saga_Vol_1.cbz")
        assertEquals("Saga Vol 1", parsed.metadata.title)
        assertEquals(emptyList<String>(), parsed.metadata.authors)
        assertEquals("image/png", parsed.coverType)
    }

    @Test
    fun aComicWithoutPagesIsRefused() {
        assertTrue(runCatching { Comic.parse(cbz("a.txt" to byteArrayOf(1)), "x.cbz") }.isFailure)
    }

    @Test
    fun zipKindLooksInsideTheArchive() {
        assertEquals(
            "epub",
            Comic.zipKind(cbz("mimetype" to "application/epub+zip".toByteArray())),
        )
        assertEquals(
            "cbz",
            Comic.zipKind(cbz("notes.txt" to byteArrayOf(1), "pages/1.jpg" to byteArrayOf(1))),
        )
        assertNull(Comic.zipKind(cbz("notes.txt" to byteArrayOf(1))))
        val notZip =
            Files.createTempFile("folio-", ".cbz").toFile().apply {
                deleteOnExit()
                writeText("plain text")
            }
        assertNull(Comic.zipKind(notZip))
    }

    @Test
    fun fileTitles() {
        assertEquals("My Comic 01", FileTitle.from("My_Comic_01.cbz"))
        assertEquals("Vol. 1", FileTitle.from("Vol. 1"))
        assertEquals("Report", FileTitle.from("primary:Download/Report.PDF"))
        assertEquals("Untitled", FileTitle.from(null))
        assertEquals("Untitled", FileTitle.from(".pdf"))
    }
}
