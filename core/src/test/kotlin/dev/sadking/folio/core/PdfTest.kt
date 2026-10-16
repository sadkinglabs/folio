package dev.sadking.folio.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PdfTest {
    /** A small PDF with a classic xref table; [info] is the Info dictionary, or null for none. */
    private fun pdf(info: String?, extra: String = "", trailer: String = ""): File {
        val body = StringBuilder("%PDF-1.4\n%âãÏÓ\n")
        body.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        body.append("2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n")
        if (info != null) body.append("12 0 obj\n$info\nendobj\n")
        body.append(extra)
        body.append("xref\n0 1\n0000000000 65535 f \n")
        val ref = if (info != null) "/Info 12 0 R" else ""
        body.append("trailer\n<< /Size 13 /Root 1 0 R $ref $trailer >>\nstartxref\n0\n%%EOF\n")
        return Files.createTempFile("folio-", ".pdf").toFile().apply {
            deleteOnExit()
            writeBytes(body.toString().toByteArray(Charsets.ISO_8859_1))
        }
    }

    @Test
    fun titleAndAuthorFromTheInfoDictionary() {
        val file = pdf("<< /Title (The Left Hand of Darkness) /Author (Ursula K. Le Guin) >>")
        assertEquals(
            PdfInfo("The Left Hand of Darkness", "Ursula K. Le Guin"),
            Pdf.info(file),
        )
        val m = Pdf.metadata(file, "scan_0001.pdf")
        assertEquals("The Left Hand of Darkness", m.title)
        assertEquals(listOf("Ursula K. Le Guin"), m.authors)
    }

    @Test
    fun escapesNestedParenthesesAndOctal() {
        val info =
            Pdf.parseInfoDictionary(
                """<< /Title (A \(very\) (nested) caf\351 \
title) /Author (Ann\tLee) >>"""
            )
        assertEquals("A (very) (nested) café title", info.title)
        assertEquals("Ann Lee", info.author)
    }

    @Test
    fun hexAndUtf16Strings() {
        val info = Pdf.parseInfoDictionary("<< /Title <FEFF004E00E9006F> /Author <416E6E> >>")
        assertEquals("Néo", info.title)
        assertEquals("Ann", info.author)
    }

    @Test
    fun pdfDocEncodingPunctuation() {
        // 0x84 is an em dash and 0x8D a left double quote in PDFDocEncoding.
        assertEquals("A—B “q", Pdf.parseInfoDictionary("<< /Title (A\\204B \\215q) >>").title)
    }

    @Test
    fun nestedDictionariesArraysAndNamesAreSkipped() {
        val info =
            Pdf.parseInfoDictionary(
                "<< /Trapped /False /Custom << /Title (Not this) >> /Ids [(x) (y)] " +
                    "/Pages 3 0 R /Title (This one) % a comment (not a title)\n /Author (Bo) >>"
            )
        assertEquals(PdfInfo("This one", "Bo"), info)
    }

    @Test
    fun aLaterRevisionOfTheInfoObjectWins() {
        val file =
            pdf(
                "<< /Title (Old) >>",
                extra = "12 0 obj\n<< /Title (New) /Author (Ann Lee) >>\nendobj\n",
            )
        assertEquals(PdfInfo("New", "Ann Lee"), Pdf.info(file))
    }

    @Test
    fun anObjectNumberIsNotMatchedInsideALongerOne() {
        val file = pdf("<< /Title (Right) >>", extra = "112 0 obj\n<< /Title (Wrong) >>\nendobj\n")
        assertEquals("Right", Pdf.info(file)!!.title)
    }

    @Test
    fun anXrefStreamTrailerStillNamesTheInfo() {
        val text =
            "%PDF-1.5\n12 0 obj\n<< /Title (Streamed) >>\nendobj\n" +
                "20 0 obj\n<< /Type /XRef /Size 21 /Root 1 0 R /Info 12 0 R /Length 0 >>\n" +
                "stream\n\nendstream\nendobj\nstartxref\n0\n%%EOF\n"
        val file =
            Files.createTempFile("folio-", ".pdf").toFile().apply {
                deleteOnExit()
                writeText(text)
            }
        assertEquals("Streamed", Pdf.info(file)!!.title)
    }

    @Test
    fun encryptedOrInfolessFilesUseTheFileName() {
        val encrypted = pdf("<< /Title (ÿþ\u0013) >>", trailer = "/Encrypt 30 0 R")
        assertNull(Pdf.info(encrypted))
        assertEquals("Annual Report 2024", Pdf.metadata(encrypted, "Annual_Report 2024.pdf").title)
        assertEquals("Field Notes", Pdf.metadata(pdf(null), "Field_Notes.pdf").title)
        assertEquals(emptyList<String>(), Pdf.metadata(pdf(null), "Field_Notes.pdf").authors)
    }

    @Test
    fun generatorTitlesUseTheFileName() {
        val word = pdf("<< /Title (Microsoft Word - draft3.docx) /Author (Ann Lee) >>")
        val m = Pdf.metadata(word, "Garden Plan.pdf")
        assertEquals("Garden Plan", m.title)
        assertEquals(listOf("Ann Lee"), m.authors)
        assertEquals("Garden Plan", Pdf.metadata(pdf("<< /Title ( ) >>"), "Garden Plan.pdf").title)
    }

    @Test
    fun severalAuthorsSplitOnSemicolons() {
        val file = pdf("<< /Author (Ann Lee; Bo Lind ;) >>")
        assertEquals(listOf("Ann Lee", "Bo Lind"), Pdf.metadata(file, "x.pdf").authors)
    }
}
