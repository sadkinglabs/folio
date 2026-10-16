package dev.sadking.folio.core.sync

import dev.sadking.folio.core.BookFormats
import dev.sadking.folio.core.BookPaths

private val SHA256 = Regex("[0-9a-f]{64}")

internal fun validSha(sha: String) = SHA256.matches(sha)

/** A relative path with "/" separators and no empty, "." or ".." part. */
internal fun validPath(path: String): Boolean =
    path.isNotEmpty() &&
        !path.startsWith('/') &&
        '\\' !in path &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }

/** The library folder relative to KOReader's home, without slashes at either end. */
internal fun cleanRoot(root: String): String {
    val clean = root.trim().trim('/').ifEmpty { SyncPlanner.DEFAULT_ROOT }
    require(validPath(clean)) { "Invalid library folder" }
    return clean
}

internal fun underRoot(root: String, path: String) =
    BookPaths.key(path).startsWith(BookPaths.key(root) + "/")

/**
 * Which e-reader file holds which phone book. [pairs] maps a phone book id to its file, Recently
 * removed books included. [purged] maps a file path to the baseline of a book no longer on the
 * phone at all. [loose] are the other files, in path order.
 */
internal class Matching(
    val pairs: Map<String, KindleFile>,
    val purged: Map<String, Baseline>,
    val loose: List<KindleFile>,
)

/**
 * Pairs e-reader files with phone books (rule 1): by content first, then by the baseline's path (a
 * file edited on one side), then by the baseline's content (moved on the e-reader, edited on the
 * phone). Each file and each book is used once. Books on the phone come before books in Recently
 * removed, so a removed duplicate never takes an active book's file. When several files hold the
 * same content, the one at the book's baseline path wins, then one under the library folder, then
 * the first by path.
 */
internal fun match(
    root: String,
    kindle: List<KindleFile>,
    phone: List<PhoneBook>,
    baselines: List<Baseline>,
): Matching {
    val baseline = baselines.sortedBy { it.bookId }.associateBy { it.bookId }
    val free =
        kindle.sortedWith(compareBy({ !underRoot(root, it.path) }, { it.path })).toMutableList()
    val pairs = LinkedHashMap<String, KindleFile>()
    val books = phone.sortedWith(compareBy({ it.removed }, { !it.hasFile }, { it.id }))

    fun take(file: KindleFile?, bookId: String) {
        if (file == null) return
        pairs[bookId] = file
        free.remove(file)
    }

    for (book in books) {
        if (!validSha(book.sha256)) continue
        val same = free.filter { it.sha256 == book.sha256 }
        val at = baseline[book.id]?.path?.let(BookPaths::key)
        take(same.firstOrNull { BookPaths.key(it.path) == at } ?: same.firstOrNull(), book.id)
    }
    for (book in books) {
        if (book.id in pairs) continue
        val b = baseline[book.id] ?: continue
        take(free.firstOrNull { BookPaths.key(it.path) == BookPaths.key(b.path) }, book.id)
    }
    for (book in books) {
        if (book.id in pairs) continue
        val b = baseline[book.id] ?: continue
        take(free.firstOrNull { it.sha256 == b.sha256 }, book.id)
    }
    val onPhone = phone.map { it.id }.toSet()
    val purged = LinkedHashMap<String, Baseline>()
    for (b in baseline.values) {
        if (b.bookId in onPhone) continue
        val file =
            free.firstOrNull { BookPaths.key(it.path) == BookPaths.key(b.path) }
                ?: free.firstOrNull { it.sha256 == b.sha256 }
                ?: continue
        purged[file.path] = b
        free.remove(file)
    }
    return Matching(pairs, purged, free.sortedBy { it.path })
}

/** The checks both [SyncPlanner.plan] and [SyncPlanner.baselines] make on their input. */
internal fun checkInput(kindle: List<KindleFile>, phone: List<PhoneBook>) {
    kindle.forEach { file ->
        require(validPath(file.path)) { "Invalid e-reader path" }
        require(validSha(file.sha256)) { "Invalid file hash" }
    }
    require(kindle.map { it.path }.distinct().size == kindle.size) { "Duplicate e-reader paths" }
    require(phone.map { it.id }.distinct().size == phone.size) { "Duplicate book ids" }
}

/** Files and books in a format Librillo keeps. */
internal fun kept(format: String) = format.lowercase() in BookFormats.ALL
