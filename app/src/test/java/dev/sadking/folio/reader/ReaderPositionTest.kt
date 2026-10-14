package dev.sadking.folio.reader

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ReaderPositionTest {
    @Test
    fun digestUsesKoreaderSamplesInsteadOfFullHash() {
        val file = File.createTempFile("reader-digest", ".epub")
        try {
            val bytes = ByteArray(70000) { (it * 17).toByte() }
            file.writeBytes(bytes)
            val md5 = MessageDigest.getInstance("MD5")
            listOf(0, 1024, 4096, 16384, 65536).forEach {
                md5.update(bytes, it, minOf(1024, bytes.size - it))
            }
            assertEquals(md5.digest().joinToString("") { "%02x".format(it) }, koreaderDigest(file))
            assertNotEquals(
                MessageDigest.getInstance("MD5").digest(bytes).joinToString("") {
                    "%02x".format(it)
                },
                koreaderDigest(file),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun smallFileDigestAndTruncatedLastSample() {
        val file = File.createTempFile("reader-digest", ".epub")
        try {
            file.writeText("A short EPUB fixture")
            assertEquals(
                MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") {
                    "%02x".format(it)
                },
                koreaderDigest(file),
            )
            file.writeBytes(ByteArray(1100) { it.toByte() })
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(file.readBytes(), 0, 1024)
            md5.update(file.readBytes(), 1024, 76)
            assertEquals(md5.digest().joinToString("") { "%02x".format(it) }, koreaderDigest(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportedAnchorsPreserveSpineIndex() {
        assertEquals(0, xpointerSpineIndex("/body/DocFragment/body/p[1]/text().32", 3))
        assertEquals(2, xpointerSpineIndex("/body[1]/DocFragment[3]/body[1]/p[1]/text()[1].32", 3))
    }

    @Test
    fun unknownOutOfRangeAndUnboundedAnchorsFailClosed() {
        listOf(
                "/body/DocFragment[0]/body/p",
                "/body/DocFragment[4]/body/p",
                "/body/DocFragment[999999999999]/body/p",
                "/body/DocFragment[1]/div/p",
                "/body/DocFragment[1]/body/p\n",
                "percent:0.5",
                "/body/DocFragment/body/" + "x".repeat(4100),
            )
            .forEach { assertNull(it, xpointerSpineIndex(it, 3)) }
    }
}
