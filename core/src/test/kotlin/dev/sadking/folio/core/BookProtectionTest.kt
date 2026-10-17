package dev.sadking.folio.core

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/** DRM detection for downloaded books (M5). */
class BookProtectionTest {
    private fun epub(vararg extra: Pair<String, String>): File {
        val file = Files.createTempFile("folio-", ".epub").toFile().apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            (listOf(
                    "mimetype" to "application/epub+zip",
                    "META-INF/container.xml" to
                        """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                    "book.opf" to "<package/>",
                ) + extra)
                .forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
        }
        return file
    }

    private fun encryption(vararg algorithms: String) =
        """<?xml version="1.0" encoding="UTF-8"?>
        <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
            xmlns:enc="http://www.w3.org/2001/04/xmlenc#">""" +
            algorithms.withIndex().joinToString("") { (i, algorithm) ->
                """<enc:EncryptedData><enc:EncryptionMethod Algorithm="$algorithm"/>
                <enc:CipherData><enc:CipherReference URI="r$i"/></enc:CipherData>
                </enc:EncryptedData>"""
            } +
            "</encryption>"

    private fun pdf(trailer: String, extra: String = ""): File {
        val body = StringBuilder("%PDF-1.6\n")
        body.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        body.append("2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n")
        body.append(extra)
        body.append("xref\n0 1\n0000000000 65535 f \n")
        body.append("trailer\n<< /Size 13 /Root 1 0 R $trailer >>\nstartxref\n0\n%%EOF\n")
        return Files.createTempFile("folio-", ".pdf").toFile().apply {
            deleteOnExit()
            writeBytes(body.toString().toByteArray(Charsets.ISO_8859_1))
        }
    }

    @Test
    fun aPlainEpubIsOpen() {
        assertEquals(Protection.NONE, BookProtection.check(epub(), BookFormats.EPUB))
    }

    @Test
    fun fontObfuscationAloneIsOpen() {
        val file =
            epub(
                "META-INF/encryption.xml" to
                    encryption(
                        "http://www.idpf.org/2008/embedding",
                        "http://ns.adobe.com/pdf/enc#RC",
                    )
            )
        assertEquals(Protection.NONE, BookProtection.check(file, BookFormats.EPUB))
    }

    @Test
    fun aesEncryptedContentIsLocked() {
        val file =
            epub(
                "META-INF/encryption.xml" to
                    encryption(
                        "http://www.idpf.org/2008/embedding",
                        "http://www.w3.org/2001/04/xmlenc#aes128-cbc",
                    )
            )
        assertEquals(Protection.ENCRYPTED, BookProtection.check(file, BookFormats.EPUB))
    }

    @Test
    fun adobeRightsFileIsAdobe() {
        val file = epub("META-INF/rights.xml" to "<rights/>")
        assertEquals(Protection.ADOBE, BookProtection.check(file, BookFormats.EPUB))
    }

    @Test
    fun lcpLicenceIsLcp() {
        val file = epub("META-INF/license.lcpl" to "{}")
        assertEquals(Protection.LCP, BookProtection.check(file, BookFormats.EPUB))
    }

    @Test
    fun appleSinfIsApple() {
        val file = epub("META-INF/sinf.xml" to "<fairplay/>")
        assertEquals(Protection.APPLE, BookProtection.check(file, BookFormats.EPUB))
    }

    @Test
    fun anUnparseableEncryptionListIsLocked() {
        val broken = epub("META-INF/encryption.xml" to "<encryption><EncryptedData")
        assertEquals(Protection.ENCRYPTED, BookProtection.check(broken, BookFormats.EPUB))
        val doctype =
            epub(
                "META-INF/encryption.xml" to
                    "<!DOCTYPE x [<!ENTITY e \"x\">]>" +
                        encryption("http://www.idpf.org/2008/embedding")
            )
        assertEquals(Protection.ENCRYPTED, BookProtection.check(doctype, BookFormats.EPUB))
        val noMethod =
            epub(
                "META-INF/encryption.xml" to
                    """<encryption xmlns:enc="http://www.w3.org/2001/04/xmlenc#"><enc:EncryptedData/></encryption>"""
            )
        assertEquals(Protection.ENCRYPTED, BookProtection.check(noMethod, BookFormats.EPUB))
    }

    @Test
    fun aFileThatIsNotAZipIsLeftToTheImport() {
        val file = Files.createTempFile("folio-", ".epub").toFile().apply { deleteOnExit() }
        file.writeText("not a zip")
        assertEquals(Protection.NONE, BookProtection.check(file, BookFormats.EPUB))
    }

    @Test
    fun pdfPasswordHandlerIsOpenButAdeptIsLocked() {
        assertEquals(Protection.NONE, BookProtection.check(pdf(""), BookFormats.PDF))
        val standard = pdf("/Encrypt 7 0 R", "7 0 obj\n<< /Filter /Standard /V 4 /R 4 >>\nendobj\n")
        assertEquals(Protection.NONE, BookProtection.check(standard, BookFormats.PDF))
        val adept = pdf("/Encrypt 7 0 R", "7 0 obj\n<< /Filter /EBX_HANDLER /V 2 >>\nendobj\n")
        assertEquals(Protection.PDF_DRM, BookProtection.check(adept, BookFormats.PDF))
        val inline = pdf("/Encrypt << /Filter /Adobe.APS /V 4 >>")
        assertEquals(Protection.PDF_DRM, BookProtection.check(inline, BookFormats.PDF))
        // /EncryptMetadata is not an /Encrypt dictionary.
        val metadata = pdf("", "3 0 obj\n<< /EncryptMetadata false >>\nendobj\n")
        assertEquals(Protection.NONE, BookProtection.check(metadata, BookFormats.PDF))
    }

    @Test
    fun comicsAreNeverLocked() {
        assertEquals(
            Protection.NONE,
            BookProtection.check(epub("META-INF/rights.xml" to "<rights/>"), BookFormats.CBZ),
        )
        assertEquals(Protection.NONE, BookProtection.check(pdf(""), BookFormats.CBR))
    }

    @Test
    fun acsmByBytesNameAndMime() {
        val token =
            """<?xml version="1.0"?><fulfillmentToken fulfillmentType="loan" xmlns="http://ns.adobe.com/adept"><distributor>x</distributor></fulfillmentToken>"""
                .toByteArray()
        assertTrue(BookProtection.isAcsm(token, null, null))
        assertTrue(BookProtection.isAcsm(ByteArray(0), "URLLink.ACSM", null))
        assertTrue(
            BookProtection.isAcsm(
                ByteArray(0),
                null,
                "application/vnd.adobe.adept+xml; charset=utf-8",
            )
        )
        assertFalse(BookProtection.isAcsm("<html>".toByteArray(), "book.epub", "text/html"))
        // The element alone, outside Adobe's namespace, is not a token.
        assertFalse(BookProtection.isAcsm("<fulfillmentToken/>".toByteArray(), null, null))
    }
}
