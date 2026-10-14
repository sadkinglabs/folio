package dev.sadking.folio.core

import java.io.*
import java.net.URI
import java.security.MessageDigest
import java.util.zip.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.*

data class Metadata(
    val title: String = "Untitled",
    val authors: List<String> = emptyList(),
    val description: String = "",
    val publisher: String = "",
    val language: String = "",
    val identifiers: List<String> = emptyList(),
    val series: String = "",
    val seriesIndex: String = "",
    val tags: List<String> = emptyList(),
)

data class ParsedEpub(
    val metadata: Metadata,
    val cover: ByteArray?,
    val coverType: String?,
    val opfPath: String,
)

/** Bounded ZIP/XML processing. Embedded HTML is never executed or rendered. */
object Epub {
    const val MAX_FILE = 256L * 1024 * 1024
    private const val MAX_XML = 2L * 1024 * 1024
    private const val MAX_ENTRY = 32L * 1024 * 1024
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private const val OPF = "http://www.idpf.org/2007/opf"

    fun copyBounded(input: InputStream, output: OutputStream, limit: Long = MAX_FILE): Long {
        val buffer = ByteArray(32768)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            require(total <= limit) { "File exceeds safety limit" }
            output.write(buffer, 0, n)
        }
        return total
    }

    fun sha256(file: File): String = file.inputStream().use { hash(it) }

    fun hash(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val b = ByteArray(32768)
        var total = 0L
        while (true) {
            val n = input.read(b)
            if (n < 0) break
            total += n
            require(total <= MAX_FILE) { "Resource exceeds safety limit" }
            digest.update(b, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun xml(bytes: ByteArray): Document {
        // EPUB package/container XML is UTF-8 or UTF-16. Decode strictly first so the
        // DTD guard is independent of parser-specific features (Android Harmony lacks them).
        val encoding =
            when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    Charsets.UTF_16LE
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    Charsets.UTF_16BE
                bytes.size >= 2 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte() ->
                    Charsets.UTF_16BE
                bytes.size >= 2 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte() ->
                    Charsets.UTF_16LE
                else -> Charsets.UTF_8
            }
        val text =
            encoding
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix("\uFEFF")
        require(
            !text.contains("<!DOCTYPE", ignoreCase = true) &&
                !text.contains("<!ENTITY", ignoreCase = true)
        ) {
            "EPUB XML DTD/entity declarations are not supported"
        }
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        // Optional defence in depth; the lexical guard and rejecting resolver are mandatory.
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching {
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        f.isExpandEntityReferences = false
        val builder = f.newDocumentBuilder()
        builder.setEntityResolver { _, _ ->
            throw org.xml.sax.SAXException("External XML references are blocked")
        }
        return builder.parse(org.xml.sax.InputSource(StringReader(text)))
    }

    private fun read(z: ZipFile, path: String, max: Long = MAX_XML): ByteArray {
        val e = z.getEntry(path) ?: error("Missing EPUB resource: $path")
        return ByteArrayOutputStream()
            .also { out -> z.getInputStream(e).use { copyBounded(it, out, max) } }
            .toByteArray()
    }

    private fun nodes(d: Document, namespace: String, name: String): List<Element> =
        d.getElementsByTagNameNS(namespace, name).let { n ->
            (0 until n.length).map { n.item(it) as Element }
        }

    private fun resolve(base: String, href: String): String {
        require(!href.contains('\\')) { "Invalid resource path" }
        val u = URI(base.replace(" ", "%20")).resolve(href.replace(" ", "%20")).normalize()
        require(!u.isAbsolute && u.authority == null && u.fragment == null && u.query == null) {
            "External resource unsupported"
        }
        val path = u.path
        require(!path.startsWith('/') && path.split('/').none { it == ".." }) {
            "Unsafe resource path"
        }
        return path
    }

    private fun validate(z: ZipFile) {
        val entries = z.entries().toList()
        require(entries.size <= 10000) { "Too many EPUB entries" }
        require(entries.map { it.name }.distinct().size == entries.size) { "Duplicate ZIP entries" }
        var size = 0L
        for (e in entries) {
            require(
                !e.name.startsWith('/') &&
                    !e.name.contains('\\') &&
                    e.name.split('/').none { it == ".." }
            ) {
                "Unsafe ZIP path"
            }
            require(e.size in 0..MAX_ENTRY) { "Oversized ZIP entry" }
            size += e.size
            require(size <= MAX_FILE) { "Expanded EPUB exceeds safety limit" }
        }
        require(
            String(read(z, "mimetype", 100), Charsets.US_ASCII).trim() == "application/epub+zip"
        ) {
            "Not an EPUB"
        }
    }

    private fun packagePath(z: ZipFile): String {
        val d = xml(read(z, "META-INF/container.xml"))
        val roots = d.getElementsByTagNameNS("*", "rootfile")
        require(roots.length > 0) { "EPUB has no package" }
        return resolve("", (roots.item(0) as Element).getAttribute("full-path"))
    }

    private fun coverItem(d: Document): Element? {
        val items = nodes(d, OPF, "item")
        val coverId =
            nodes(d, OPF, "meta")
                .firstOrNull { it.getAttribute("name") == "cover" }
                ?.getAttribute("content")
        return items.firstOrNull { "cover-image" in it.getAttribute("properties").split(' ') }
            ?: items.firstOrNull { it.getAttribute("id") == coverId }
    }

    private fun seriesElements(meta: List<Element>): List<Element> = meta.filter { candidate ->
        candidate.getAttribute("property") == "belongs-to-collection" &&
            candidate.getAttribute("refines").isEmpty() &&
            candidate.getAttribute("id").isNotEmpty() &&
            meta.any {
                it.getAttribute("refines") == "#${candidate.getAttribute("id")}" &&
                    it.getAttribute("property") == "collection-type" &&
                    it.textContent.trim() == "series"
            }
    }

    fun parse(file: File): ParsedEpub =
        ZipFile(file).use { z ->
            validate(z)
            val path = packagePath(z)
            val d = xml(read(z, path))
            fun values(name: String) =
                nodes(d, DC, name).map { it.textContent.trim() }.filter { it.isNotEmpty() }
            val meta = nodes(d, OPF, "meta")
            fun legacy(name: String) =
                meta
                    .firstOrNull { it.getAttribute("name") == name }
                    ?.getAttribute("content")
                    .orEmpty()
            val series3 = seriesElements(meta).firstOrNull()
            val index3 =
                series3
                    ?.getAttribute("id")
                    ?.let { id ->
                        meta
                            .firstOrNull {
                                it.getAttribute("refines") == "#$id" &&
                                    it.getAttribute("property") == "group-position"
                            }
                            ?.textContent
                    }
                    .orEmpty()
            val m =
                Metadata(
                    values("title").firstOrNull() ?: "Untitled",
                    values("creator"),
                    values("description").firstOrNull().orEmpty(),
                    values("publisher").firstOrNull().orEmpty(),
                    values("language").firstOrNull().orEmpty(),
                    values("identifier"),
                    legacy("calibre:series").ifEmpty { series3?.textContent.orEmpty() },
                    legacy("calibre:series_index").ifEmpty { index3 },
                    values("subject"),
                )
            val cover = coverItem(d)
            val type = cover?.getAttribute("media-type")
            val bytes =
                if (cover != null && type in listOf("image/jpeg", "image/png", "image/webp"))
                    read(z, resolve(path, cover.getAttribute("href")), 10L * 1024 * 1024)
                else null
            ParsedEpub(m, bytes, type, path)
        }

    /**
     * Every spine item in package order, including `linear="no"` items, as ZIP paths. KOReader's
     * `DocFragment[N]` counts the full spine, whereas reading systems may omit non-linear items
     * from their reading order, so positions are mapped through these paths rather than indexes.
     */
    fun spine(file: File): List<String> =
        ZipFile(file).use { z ->
            validate(z)
            val path = packagePath(z)
            val d = xml(read(z, path))
            val hrefs =
                nodes(d, OPF, "item").associate { it.getAttribute("id") to it.getAttribute("href") }
            nodes(d, OPF, "itemref").mapNotNull { ref ->
                hrefs[ref.getAttribute("idref")]?.let { resolve(path, it) }
            }
        }

    /** Writes a new revision; preserves all untouched resources and unknown metadata. */
    fun edit(
        source: File,
        target: File,
        metadata: Metadata,
        newCover: ByteArray? = null,
        coverType: String = "image/jpeg",
    ) {
        require(source.canonicalPath != target.canonicalPath) { "Never overwrite original EPUB" }
        require(metadata.title.isNotBlank()) { "Title is required" }
        require(
            metadata.seriesIndex.isBlank() ||
                metadata.seriesIndex.toDoubleOrNull()?.let { it >= 0 && it.isFinite() } == true
        ) {
            "Invalid series number"
        }
        val originalMetadata = parse(source).metadata
        ZipFile(source).use { z ->
            validate(z)
            require(z.getEntry("META-INF/encryption.xml") == null) {
                "Editing encrypted or obfuscated EPUBs is not supported"
            }
            val path = packagePath(z)
            val d = xml(read(z, path))
            val parent = nodes(d, OPF, "metadata").first()
            fun replace(name: String, values: List<String>) {
                val old = nodes(d, DC, name)
                if (
                    old.map { it.textContent.trim() } ==
                        values.filter { it.isNotBlank() }.map { it.trim() }
                )
                    return
                val ids = old.map { it.getAttribute("id") }.filter { it.isNotEmpty() }.toSet()
                nodes(d, OPF, "meta")
                    .filter { it.getAttribute("refines").removePrefix("#") in ids }
                    .forEach { it.parentNode.removeChild(it) }
                old.forEach { it.parentNode.removeChild(it) }
                values
                    .filter { it.isNotBlank() }
                    .forEach { value ->
                        parent.appendChild(
                            d.createElementNS(DC, "dc:$name").also { it.textContent = value }
                        )
                    }
            }
            replace("title", listOf(metadata.title))
            replace("creator", metadata.authors)
            replace("description", listOf(metadata.description))
            replace("publisher", listOf(metadata.publisher))
            replace("language", listOf(metadata.language))
            replace("subject", metadata.tags)
            // Keep the package's unique-identifier stable; add/remove secondary identifiers only.
            val uid = d.documentElement.getAttribute("unique-identifier")
            val primary = nodes(d, DC, "identifier").firstOrNull { it.getAttribute("id") == uid }
            val secondary = nodes(d, DC, "identifier").filter { it !== primary }
            val removed = secondary.filter { it.textContent.trim() !in metadata.identifiers }
            val removedIds =
                removed.map { it.getAttribute("id") }.filter { it.isNotEmpty() }.toSet()
            nodes(d, OPF, "meta")
                .filter { it.getAttribute("refines").removePrefix("#") in removedIds }
                .forEach { it.parentNode.removeChild(it) }
            removed.forEach { it.parentNode.removeChild(it) }
            val retainedValues = nodes(d, DC, "identifier").map { it.textContent.trim() }.toSet()
            metadata.identifiers
                .distinct()
                .filter { it.isNotBlank() && it !in retainedValues }
                .forEach { value ->
                    parent.appendChild(
                        d.createElementNS(DC, "dc:identifier").also { it.textContent = value }
                    )
                }
            fun legacy(name: String, value: String) {
                if (value.isNotBlank())
                    parent.appendChild(
                        d.createElementNS(OPF, "meta").also {
                            it.setAttribute("name", name)
                            it.setAttribute("content", value)
                        }
                    )
            }
            if (
                metadata.series != originalMetadata.series ||
                    metadata.seriesIndex != originalMetadata.seriesIndex
            ) {
                val metas = nodes(d, OPF, "meta")
                val oldSeries = seriesElements(metas)
                val seriesIds = oldSeries.map { it.getAttribute("id") }.toSet()
                metas
                    .filter {
                        it.getAttribute("name") in
                            listOf("calibre:series", "calibre:series_index") ||
                            it in oldSeries ||
                            it.getAttribute("refines").removePrefix("#") in seriesIds
                    }
                    .forEach { it.parentNode.removeChild(it) }
                legacy("calibre:series", metadata.series)
                legacy("calibre:series_index", metadata.seriesIndex)
                if (
                    d.documentElement.getAttribute("version").startsWith("3") &&
                        metadata.series.isNotBlank()
                ) {
                    val seriesId = "folio-series-" + java.util.UUID.randomUUID()
                    fun property(name: String, value: String, refines: Boolean) {
                        parent.appendChild(
                            d.createElementNS(OPF, "meta").also {
                                it.setAttribute("property", name)
                                it.textContent = value
                                if (refines) it.setAttribute("refines", "#$seriesId")
                                else it.setAttribute("id", seriesId)
                            }
                        )
                    }
                    property("belongs-to-collection", metadata.series, false)
                    property("collection-type", "series", true)
                    if (metadata.seriesIndex.isNotBlank())
                        property("group-position", metadata.seriesIndex, true)
                }
            }
            var coverPath: String? = null
            if (newCover != null) {
                require(
                    newCover.size <= 10 * 1024 * 1024 &&
                        coverType in listOf("image/jpeg", "image/png", "image/webp")
                )
                var item = coverItem(d)
                if (item == null) {
                    val id = "folio-cover-" + java.util.UUID.randomUUID()
                    item =
                        d.createElementNS(OPF, "item").also {
                            it.setAttribute("id", id)
                            it.setAttribute("href", "$id.jpg")
                        }
                    nodes(d, OPF, "manifest").first().appendChild(item)
                    legacy("cover", id)
                }
                item.setAttribute("media-type", coverType)
                if (d.documentElement.getAttribute("version").startsWith("3"))
                    item.setAttribute(
                        "properties",
                        (item.getAttribute("properties").split(' ').filter { it.isNotBlank() } +
                                "cover-image")
                            .distinct()
                            .joinToString(" "),
                    )
                coverPath = resolve(path, item.getAttribute("href"))
            }
            if (d.documentElement.getAttribute("version").startsWith("3")) {
                nodes(d, OPF, "meta")
                    .filter { it.getAttribute("property") == "dcterms:modified" }
                    .forEach { it.parentNode.removeChild(it) }
                parent.appendChild(
                    d.createElementNS(OPF, "meta").also {
                        it.setAttribute("property", "dcterms:modified")
                        it.textContent =
                            java.time.Instant.now()
                                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                                .toString()
                    }
                )
            }
            val opf =
                ByteArrayOutputStream()
                    .also { out ->
                        TransformerFactory.newInstance()
                            .newTransformer()
                            .transform(DOMSource(d), StreamResult(out))
                    }
                    .toByteArray()
            try {
                ZipOutputStream(target.outputStream().buffered()).use { out ->
                    var expanded = 0L
                    fun put(name: String, bytes: ByteArray, stored: Boolean = false) {
                        expanded += bytes.size
                        require(expanded <= MAX_FILE) { "Expanded EPUB exceeds safety limit" }
                        val entry = ZipEntry(name)
                        if (stored) {
                            entry.method = ZipEntry.STORED
                            entry.size = bytes.size.toLong()
                            entry.compressedSize = entry.size
                            entry.crc = CRC32().also { it.update(bytes) }.value
                        }
                        out.putNextEntry(entry)
                        out.write(bytes)
                        out.closeEntry()
                    }
                    put("mimetype", "application/epub+zip".toByteArray(), true)
                    for (e in z.entries().toList()) {
                        if (e.name == "mimetype") continue
                        when (e.name) {
                            path -> put(path, opf)
                            coverPath -> put(e.name, newCover!!)
                            else -> {
                                out.putNextEntry(ZipEntry(e.name))
                                expanded +=
                                    z.getInputStream(e).use {
                                        copyBounded(it, out, minOf(MAX_ENTRY, MAX_FILE - expanded))
                                    }
                                out.closeEntry()
                            }
                        }
                    }
                    if (coverPath != null && z.getEntry(coverPath) == null)
                        put(coverPath, newCover!!)
                }
                parse(target)
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }
    }
}
