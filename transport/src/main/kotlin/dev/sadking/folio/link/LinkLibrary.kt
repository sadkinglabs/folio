package dev.sadking.folio.link

import java.io.File

/** A book Folio Link can offer an e-reader. [file] is the current EPUB revision. */
data class LinkBook(
    val id: String,
    val title: String,
    val authors: List<String>,
    val file: File,
    val sha256: String,
    val addedAt: Long,
    val cover: File? = null,
) {
    /** "Author - Title.epub", safe on FAT32 e-reader storage. */
    val filename: String
        get() = linkFilename(authors.firstOrNull().orEmpty(), title)
}

data class LinkShelf(val id: String, val name: String, val count: Int)

/** What Folio Link serves from the phone's library. Implemented by the app over Room. */
interface LinkLibrary {
    fun recent(limit: Int): List<LinkBook>

    fun shelves(): List<LinkShelf>

    fun shelf(id: String): List<LinkBook>

    /**
     * Books queued for e-readers, oldest first, including ones [device] already has: the plugin
     * checks each against [savedPath] and fetches it again if the file is no longer there.
     */
    fun queue(device: String): List<LinkBook>

    /** Where [device] last saved this revision of [id], or null if it never confirmed it. */
    fun savedPath(device: String, id: String): String? = null

    fun book(id: String): LinkBook?

    /** The plugin saved [id] on [device] at [path] with [sha256]. False if it does not match. */
    fun acknowledge(device: String, id: String, sha256: String, path: String): Boolean
}

/**
 * A readable filename that works on FAT32 (Kindle, Kobo): no reserved characters, no trailing dots
 * or spaces, at most 120 characters before the extension.
 */
fun linkFilename(author: String, title: String): String {
    fun clean(value: String) =
        value
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', ' ')
    val name =
        listOf(clean(author), clean(title))
            .filter { it.isNotEmpty() }
            .joinToString(" - ")
            .ifEmpty {
                "Book"
            }
    return name.take(120).trimEnd('.', ' ') + ".epub"
}
