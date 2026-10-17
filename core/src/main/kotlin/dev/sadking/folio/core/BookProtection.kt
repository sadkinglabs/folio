package dev.sadking.folio.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** How a book file is locked, if it is. Anything but [NONE] can't be opened by Librillo. */
enum class Protection {
    NONE,
    /** Adobe Digital Editions (ADEPT): `META-INF/rights.xml`. */
    ADOBE,
    /** Readium LCP: `META-INF/license.lcpl`. */
    LCP,
    /** Apple FairPlay: `META-INF/sinf.xml`. */
    APPLE,
    /** Content encrypted with something other than font obfuscation, or an unreadable list. */
    ENCRYPTED,
    /** A PDF locked by a DRM handler rather than a plain password. */
    PDF_DRM,
}

/**
 * Tells a DRM-protected book from an open one, from the file alone. Used before a downloaded book
 * is imported, so a borrowed or bought book that won't open gets a plain message instead of a
 * broken entry. Reads are bounded; nothing is decrypted.
 */
object BookProtection {
    /** Font obfuscation (IDPF and Adobe): fonts only, the text stays readable. */
    private val FONT_OBFUSCATION =
        setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
    private const val ADEPT_MIME = "application/vnd.adobe.adept+xml"
    private const val ADEPT_NS = "http://ns.adobe.com/adept"
    private const val MAX_ENCRYPTION_XML = 1024 * 1024
    private const val ACSM_HEADER = 4096
    private const val PDF_HEAD = 64 * 1024
    private const val PDF_TAIL = 1024 * 1024
    private val ENCRYPT = Regex("/Encrypt(?![A-Za-z0-9])")
    private val ENCRYPT_REF = Regex("/Encrypt\\s+(\\d+)\\s+(\\d+)\\s+R")
    private val ENCRYPT_INLINE = Regex("/Encrypt\\s*<<(.*?)>>", RegexOption.DOT_MATCHES_ALL)
    private val FILTER = Regex("/Filter\\s*/([^\\s/<>\\[\\]()]+)")

    /**
     * An Adobe ACSM fulfilment token: by MIME type, `.acsm` name, or `<fulfillmentToken` in the
     * adept namespace in the first bytes. It is a ticket for a protected book, not a book.
     */
    fun isAcsm(header: ByteArray, fileName: String?, mimeType: String?): Boolean {
        if (mimeType?.substringBefore(';')?.trim()?.equals(ADEPT_MIME, ignoreCase = true) == true)
            return true
        if (fileName?.substringAfterLast('/')?.endsWith(".acsm", ignoreCase = true) == true)
            return true
        val text = String(header, 0, minOf(header.size, ACSM_HEADER), Charsets.ISO_8859_1)
        return text.contains("<fulfillmentToken") && text.contains(ADEPT_NS)
    }

    /**
     * The protection on [file], whose kind is [format] (a [BookFormats] value). CBZ and CBR are
     * always [Protection.NONE]. A file that isn't readable as its format is [Protection.NONE] here:
     * the import decides that it isn't a book.
     */
    fun check(file: File, format: String): Protection =
        when (format) {
            BookFormats.EPUB -> epub(file)
            BookFormats.PDF -> pdf(file)
            else -> Protection.NONE
        }

    private fun epub(file: File): Protection =
        try {
            ZipFile(file).use { zip ->
                val names = HashMap<String, String>()
                val all = zip.entries()
                while (all.hasMoreElements()) {
                    val name = all.nextElement().name
                    names[name.lowercase()] = name
                }
                when {
                    "meta-inf/rights.xml" in names -> Protection.ADOBE
                    "meta-inf/license.lcpl" in names -> Protection.LCP
                    "meta-inf/sinf.xml" in names -> Protection.APPLE
                    else ->
                        names["meta-inf/encryption.xml"]?.let { name ->
                            encryption(zip, zip.getEntry(name))
                        } ?: Protection.NONE
                }
            }
        } catch (e: IOException) {
            Protection.NONE
        } catch (e: IllegalArgumentException) {
            Protection.NONE
        }

    /** [Protection.ENCRYPTED] unless every encrypted resource is an obfuscated font. */
    private fun encryption(zip: ZipFile, entry: java.util.zip.ZipEntry): Protection {
        val bytes =
            zip.getInputStream(entry)
                .use { it.readNBytes(MAX_ENCRYPTION_XML + 1) }
                .takeIf { it.size <= MAX_ENCRYPTION_XML } ?: return Protection.ENCRYPTED
        return if (onlyFonts(bytes)) Protection.NONE else Protection.ENCRYPTED
    }

    /** True when the encryption list parses and names font obfuscation only. */
    internal fun onlyFonts(xml: ByteArray): Boolean {
        val text = String(xml, Charsets.UTF_8)
        // No DTDs: Android's parser can't be told to refuse them, so the guard is on the text.
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY")) return false
        val document =
            try {
                val factory = DocumentBuilderFactory.newInstance()
                factory.isNamespaceAware = true
                factory.isExpandEntityReferences = false
                runCatching {
                    factory.setFeature(
                        "http://apache.org/xml/features/disallow-doctype-decl",
                        true,
                    )
                }
                factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
            } catch (e: Exception) {
                return false
            }
        // Encrypted data that doesn't say how is treated as locked.
        val encrypted = document.getElementsByTagNameNS("*", "EncryptedData")
        for (i in 0 until encrypted.length) {
            val data = encrypted.item(i) as Element
            if (data.getElementsByTagNameNS("*", "EncryptionMethod").length == 0) return false
        }
        val methods = document.getElementsByTagNameNS("*", "EncryptionMethod")
        for (i in 0 until methods.length) {
            val algorithm = (methods.item(i) as Element).getAttribute("Algorithm").trim()
            if (algorithm !in FONT_OBFUSCATION) return false
        }
        return true
    }

    /**
     * A PDF whose `/Encrypt` dictionary names a handler other than `/Standard` (the plain password
     * handler, which Pdfium opens). Only the first 64 KB and the last 1 MB are read, where the
     * trailer and its dictionary live; an `/Encrypt` dictionary that can't be found there counts as
     * [Protection.NONE], and the reader reports the file if it can't open it.
     */
    private fun pdf(file: File): Protection {
        val text =
            try {
                window(file)
            } catch (e: IOException) {
                return Protection.NONE
            }
        if (!ENCRYPT.containsMatchIn(text)) return Protection.NONE
        val dictionary =
            ENCRYPT_REF.find(text)?.let { ref ->
                val (number, generation) = ref.destructured
                Regex(
                        "(?<![0-9])$number\\s+$generation\\s+obj(.*?)endobj",
                        RegexOption.DOT_MATCHES_ALL,
                    )
                    .find(text)
                    ?.groupValues
                    ?.get(1)
            } ?: ENCRYPT_INLINE.find(text)?.groupValues?.get(1) ?: return Protection.NONE
        val filter = FILTER.find(dictionary)?.groupValues?.get(1) ?: return Protection.NONE
        return if (filter == "Standard") Protection.NONE else Protection.PDF_DRM
    }

    /** The first [PDF_HEAD] and last [PDF_TAIL] bytes as Latin-1 text, joined. */
    private fun window(file: File): String =
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val head = ByteArray(minOf(length, PDF_HEAD.toLong()).toInt())
            raf.readFully(head)
            val tailStart = maxOf(head.size.toLong(), length - PDF_TAIL)
            val tail = ByteArray((length - tailStart).toInt())
            raf.seek(tailStart)
            raf.readFully(tail)
            String(head, Charsets.ISO_8859_1) + "\n" + String(tail, Charsets.ISO_8859_1)
        }
}
