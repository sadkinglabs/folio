package dev.sadking.folio.core

/** A book file found in the Books folder: its path relative to the folder, and its SHA-256. */
data class FolderEntry(val path: String, val hash: String)

/**
 * A library book as the folder scan sees it. [path] is the book's file relative to the Books folder
 * when the scan found that file there. It is null when the file is somewhere the scan doesn't look
 * (Librillo's private storage, a file still being written) or wasn't found; [present] then says
 * whether a file exists at the book's recorded path. A book in Recently removed ([removed]) keeps
 * its files out of the scan.
 */
data class ScanBook(
    val id: String,
    val path: String?,
    val present: Boolean,
    val currentHash: String,
    val originalHash: String,
    val removed: Boolean = false,
)

/** What the folder scan does about one book or file. */
sealed interface ScanAction {
    /** The book's content is now at [path]: moved or renamed by hand. */
    data class Relink(val bookId: String, val path: String) : ScanAction

    /** The book's own [path] holds new content, [hash]: changed outside Librillo. */
    data class Revise(val bookId: String, val path: String, val hash: String) : ScanAction

    /** The book's content is nowhere in the folder. Its record stays as it is. */
    data class Missing(val bookId: String) : ScanAction

    /** Content the library doesn't have, at [path]: added as a new book where it is. */
    data class Add(val path: String, val hash: String) : ScanAction
}

/**
 * Compares the Books folder with the library (M9 step 2). Content identifies a book; a path only
 * says where it is. A file that disappeared is never a deletion: its book is reported missing and
 * keeps its record. Content already in the library, Recently removed included, is never added
 * again. Actions come in the order they are applied: relinks, revisions, missing, additions.
 */
object FolderScanPlan {
    private val SHA256 = Regex("[0-9a-f]{64}")

    fun plan(files: List<FolderEntry>, books: List<ScanBook>): List<ScanAction> {
        files.forEach { file ->
            require(validPath(file.path)) { "Invalid folder path" }
            require(SHA256.matches(file.hash)) { "Invalid file hash" }
        }
        require(files.map { it.path }.distinct().size == files.size) { "Duplicate folder paths" }
        val byPath = files.associate { it.path to it.hash }
        // A path that holds a book's file belongs to that book, removed or not.
        val claimed = books.mapNotNull { it.path }.filter { it in byPath }.toMutableSet()
        // Content the library already has, in any book, Recently removed included.
        val known =
            books
                .flatMap { listOf(it.currentHash, it.originalHash) }
                .filter { it.isNotBlank() }
                .toMutableSet()
        // Files no book claims, by content, in path order: where a lost book may have gone.
        val free: Map<String, MutableList<String>> =
            files
                .filter { it.path !in claimed }
                .sortedBy { it.path }
                .groupBy({ it.hash }, { it.path })
                .mapValues { it.value.toMutableList() }
        val lost =
            books
                .filter { book ->
                    val path = book.path
                    val holdsIt =
                        if (path == null) book.present else byPath[path] == book.currentHash
                    !book.removed && book.currentHash.isNotBlank() && !holdsIt
                }
                .sortedBy { it.id }
        val relinks = mutableListOf<ScanAction>()
        val revisions = mutableListOf<ScanAction>()
        val missing = mutableListOf<ScanAction>()
        val released = mutableSetOf<String>()
        val changedInPlace = mutableListOf<ScanBook>()
        for (book in lost) {
            val path = book.path
            val target = free[book.currentHash]?.removeFirstOrNull()
            when {
                target != null -> {
                    relinks += ScanAction.Relink(book.id, target)
                    claimed += target
                    if (path != null) released += path
                }
                path != null && path in byPath -> changedInPlace += book
                else -> missing += ScanAction.Missing(book.id)
            }
        }
        for (book in changedInPlace) {
            val path = book.path!!
            val hash = byPath.getValue(path)
            // Another book's content at this path is not a new revision of this one.
            if (known.add(hash)) revisions += ScanAction.Revise(book.id, path, hash)
            else missing += ScanAction.Missing(book.id)
        }
        // A path its book moved away from takes new content, unless another book records it too.
        claimed -= released.filter { path -> books.count { it.path == path } == 1 }.toSet()
        val additions =
            files
                .filter { it.path !in claimed }
                .sortedBy { it.path }
                .mapNotNull { file ->
                    // The first path in order wins when the same new content is there twice.
                    if (known.add(file.hash)) ScanAction.Add(file.path, file.hash) else null
                }
        return relinks + revisions + missing + additions
    }

    private fun validPath(path: String): Boolean =
        path.isNotEmpty() &&
            !path.startsWith('/') &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}
