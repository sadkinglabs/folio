package dev.sadking.folio.reader

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** KOReader's binary-matching digest, separate from Folio's stable UUID and SHA-256 revision. */
fun koreaderDigest(file: File): String {
    val digest = MessageDigest.getInstance("MD5")
    RandomAccessFile(file, "r").use { input ->
        val buffer = ByteArray(1024)
        listOf(
                0L,
                1024,
                4096,
                16384,
                65536,
                262144,
                1048576,
                4194304,
                16777216,
                67108864,
                268435456,
                1073741824,
            )
            .forEach { offset ->
                if (offset < input.length()) {
                    input.seek(offset)
                    val count = input.read(buffer)
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Only classical V2 and explicit V2 body paths supported by our tested DOM converter. */
fun xpointerSpineIndex(pointer: String, spineSize: Int): Int? {
    if (pointer.length !in 1..4096 || pointer.any { it.code < 32 }) return null
    val match =
        Regex(
                "^/body(?:\\[1\\])?/DocFragment(?:\\[([1-9][0-9]*)\\])?/body(?:\\[1\\])?(?:/[^\\s]+|\\.[0-9]+)?$"
            )
            .matchEntire(pointer) ?: return null
    val index = (match.groupValues[1].ifBlank { "1" }.toIntOrNull() ?: return null) - 1
    return index.takeIf { it in 0 until spineSize }
}

/** Decoded resource path of a publication href, without query, fragment or leading slash. */
fun resourcePath(href: String): String =
    (runCatching { java.net.URI(href).path }.getOrNull() ?: href.substringBefore('#')).trimStart(
        '/'
    )

/**
 * Zero-based KOReader `DocFragment` index of [href] in the full package [spine] (see `Epub.spine`),
 * or null when the resource is not a spine item.
 */
fun docFragmentIndex(href: String, spine: List<String>): Int? =
    spine.indexOf(resourcePath(href)).takeIf { it >= 0 }
