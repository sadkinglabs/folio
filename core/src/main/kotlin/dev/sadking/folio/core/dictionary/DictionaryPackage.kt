package dev.sadking.folio.core.dictionary

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * The dictionary download: Open English WordNet 2025+ in WNDB format, from its GitHub release (CC
 * BY 4.0, based on Princeton WordNet 3.1 under the WordNet licence). The size and SHA-256 were
 * checked on 2026-10-17; en-word.net serves a rebuilt zip with another hash, so only this asset is
 * used.
 */
object DictionaryPackage {
    const val URL =
        "https://github.com/globalwordnet/english-wordnet/releases/download/2025-edition/english-wordnet-2025-plus.zip"
    const val BYTES = 10_982_041L
    const val SHA256 = "cf4afe7ad4cca3f56091d791e665614c564ac84e3782de4e789cf5b745556e2e"
    const val VERSION = "oewn-2025-plus"

    /** The files kept (29,467,576 bytes in all); `index.sense` and the rest are dropped. */
    val KEEP: Set<String> =
        PartOfSpeech.entries
            .flatMap { listOf("index.${it.suffix}", "data.${it.suffix}", "${it.suffix}.exc") }
            .toSet()

    /** Larger than any kept file (data.noun is 16.3 MB); a bigger entry is refused. */
    private const val MAX_ENTRY = 64L * 1024 * 1024

    /**
     * Unpacks [KEEP] from [zip] into [into], dropping the package's top folder. Refuses entries
     * whose path would escape (absolute or with `..`); throws if a kept file is missing.
     */
    fun unpack(zip: File, into: File) {
        into.mkdirs()
        val root = into.canonicalPath + File.separator
        val written = mutableSetOf<String>()
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                val parts = name.split('/')
                if (name.startsWith("/") || parts.any { it == ".." })
                    throw IOException("Unsafe path in dictionary")
                if (entry.isDirectory) continue
                val path = name.substringAfter('/', "")
                if (path !in KEEP) continue
                val out = File(into, path)
                if (!out.canonicalPath.startsWith(root))
                    throw IOException("Unsafe path in dictionary")
                out.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_ENTRY) throw IOException("Dictionary file too large")
                        output.write(buffer, 0, count)
                    }
                }
                written += path
            }
        }
        val missing = KEEP - written
        if (missing.isNotEmpty()) throw IOException("The dictionary package is incomplete")
    }
}
