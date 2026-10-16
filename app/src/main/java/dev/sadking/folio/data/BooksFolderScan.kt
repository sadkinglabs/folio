package dev.sadking.folio.data

import android.content.SharedPreferences
import dev.sadking.folio.core.BookFormats
import dev.sadking.folio.core.Epub
import dev.sadking.folio.core.FolderEntry
import dev.sadking.folio.core.FolderScanPlan
import dev.sadking.folio.core.ScanAction
import dev.sadking.folio.core.ScanBook
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * What one folder scan found. Each list holds book titles, except [notAdded], which holds paths in
 * the Books folder. The counts are the lists' sizes.
 */
data class ScanResult(
    /** Books whose file was moved or renamed by hand. They follow it. */
    val relinked: List<String> = emptyList(),
    /** Books whose file was changed outside Librillo. The new content is kept. */
    val revised: List<String> = emptyList(),
    /** Books whose file is nowhere in the folder. Their records stay. */
    val missing: List<String> = emptyList(),
    /** Files added as new books, where they are. */
    val added: List<String> = emptyList(),
    /** Files with a book's extension that couldn't be read as one. Left where they are. */
    val notAdded: List<String> = emptyList(),
    /** When the scan finished, in epoch milliseconds. 0 when it didn't run. */
    val finishedAt: Long = 0,
)

/**
 * Mirrors the Books folder into the library (M9 step 2). Walks `Books/`, but not `Inbox`, hidden
 * folders such as `.librillo`, or KOReader's `.sdr` folders, for EPUB, PDF, CBZ and CBR files. Only
 * files whose size or modified time changed since the last scan are hashed again; the hashes are
 * kept in [cacheFile]. Then [FolderScanPlan] decides: a book follows a file moved by hand, takes
 * new content at its own path as a revision, or is reported missing and kept; unknown files are
 * added where they are. It never writes, renames or deletes a book file. Use one instance per app,
 * so runs don't overlap.
 */
class BooksFolderScan(
    private val repo: LibraryRepository,
    private val cacheFile: File,
    /**
     * Where the "changed outside Librillo" notes live ([FolderScanNotes]), shared with the book
     * page, which clears a note when the book is opened.
     */
    private val notes: SharedPreferences,
    /** False without All files access: the folder can't be read, so nothing is concluded. */
    private val canRead: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val running = Mutex()

    suspend fun run(): ScanResult = withContext(Dispatchers.IO) { running.withLock { scan() } }

    /** Books whose file was changed outside Librillo, until each is opened ([opened]). */
    fun changedOutside(): Set<String> = FolderScanNotes.changedIds(notes)

    /** Books whose file the last scan found nowhere. */
    fun missing(): Set<String> = read().missing

    /** The last scan's result, or null before the first one. */
    fun lastResult(): ScanResult? = read().last

    /** Clears the "changed outside Librillo" note once the book has been opened. */
    fun opened(bookId: String) {
        FolderScanNotes.clearChanged(notes, bookId)
    }

    private suspend fun scan(): ScanResult {
        val root = repo.booksRoot()
        if (!canRead() || !root.isDirectory || root.list() == null) return ScanResult()
        val base = root.canonicalFile
        // Hashing happens outside the library lock: a first scan of a large folder takes a while.
        val first = hashAll(walk(base), read().files)
        return repo.lock.withLock {
            // Imports, edits and moves wait now. Only what changed meanwhile is hashed again.
            val files = hashAll(walk(base), first)
            val removed = repo.dao.allRemoved().filter { it.kind == "EPUB" }.map { it.id }.toSet()
            val books = repo.dao.allStoredBooks()
            val plan =
                FolderScanPlan.plan(
                    files.map { (path, cached) -> FolderEntry(path, cached.sha256) },
                    books.map { scanBook(it, base, files.keys, it.id in removed) },
                )
            applyPlan(plan, root, books.associateBy { it.id }, files)
        }
    }

    private fun scanBook(book: Book, base: File, found: Set<String>, removed: Boolean): ScanBook {
        val file = File(book.currentPath)
        val path =
            if (book.currentPath.isBlank()) null
            else
                runCatching { file.canonicalFile.relativeToOrNull(base)?.invariantSeparatorsPath }
                    .getOrNull()
                    ?.takeIf { it in found }
        val present = path != null || (book.currentPath.isNotBlank() && file.isFile)
        return ScanBook(book.id, path, present, book.currentHash, book.originalHash, removed)
    }

    private suspend fun applyPlan(
        plan: List<ScanAction>,
        root: File,
        books: Map<String, Book>,
        files: Map<String, Cached>,
    ): ScanResult {
        val relinked = mutableListOf<String>()
        val revised = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val added = mutableListOf<String>()
        val notAdded = mutableListOf<String>()
        val revisedIds = mutableSetOf<String>()
        val missingIds = mutableSetOf<String>()
        fun lost(id: String) {
            books[id]?.let {
                missing += it.title
                missingIds += it.id
            }
        }
        for (action in plan) {
            try {
                when (action) {
                    is ScanAction.Relink ->
                        repo.relink(action.bookId, File(root, action.path))?.let {
                            relinked += it.title
                        }
                    is ScanAction.Revise -> {
                        val book =
                            repo.acceptRevision(action.bookId, File(root, action.path), action.hash)
                        if (book == null) lost(action.bookId)
                        else {
                            revised += book.title
                            revisedIds += book.id
                        }
                    }
                    is ScanAction.Missing -> lost(action.bookId)
                    is ScanAction.Add -> {
                        val book = repo.addInPlace(File(root, action.path), action.hash)
                        if (book == null) notAdded += action.path else added += book.title
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // One file that fails leaves the rest of the scan to run; it is tried again later.
                if (action is ScanAction.Add) notAdded += action.path
            }
        }
        val result = ScanResult(relinked, revised, missing, added, notAdded, finishedAt = now())
        runCatching { FolderScanNotes.markChanged(notes, revisedIds, books.keys) }
        runCatching {
            update { CacheState(files = files, missing = missingIds, last = result) }
        }
        return result
    }

    /** Book files under [base] by their path relative to it, in path order. */
    private fun walk(base: File): Map<String, File> {
        val found = sortedMapOf<String, File>()
        fun visit(dir: File, prefix: String, depth: Int) {
            for (child in dir.listFiles().orEmpty().sortedBy { it.name }) {
                val name = child.name
                if (name.startsWith('.')) continue
                val link = runCatching { Files.isSymbolicLink(child.toPath()) }.getOrDefault(true)
                if (link) continue
                if (child.isDirectory) {
                    val skip =
                        (depth == 0 && name.equals(INBOX, ignoreCase = true)) ||
                            name.endsWith(".sdr", ignoreCase = true)
                    if (!skip && depth < MAX_DEPTH) visit(child, "$prefix$name/", depth + 1)
                } else if (
                    child.isFile &&
                        name.substringAfterLast('.', "").lowercase() in BookFormats.ALL &&
                        child.length() <= Epub.MAX_FILE &&
                        settled(child) &&
                        found.size < MAX_FILES
                ) {
                    found["$prefix$name"] = child
                }
            }
        }
        visit(base, "", 0)
        return found
    }

    /** A file written in the last few seconds may still be copying; it waits for a later scan. */
    private fun settled(file: File): Boolean {
        val age = now() - file.lastModified()
        return age >= SETTLE_MILLIS || age < -SETTLE_MILLIS
    }

    /** Hashes of [files], reusing [known] for a file whose size and modified time are the same. */
    private suspend fun hashAll(
        files: Map<String, File>,
        known: Map<String, Cached>,
    ): Map<String, Cached> {
        val hashed = linkedMapOf<String, Cached>()
        for ((path, file) in files) {
            currentCoroutineContext().ensureActive()
            val size = file.length()
            val mtime = file.lastModified()
            val sha256 =
                known[path]?.takeIf { it.size == size && it.mtime == mtime }?.sha256
                    ?: runCatching { Epub.sha256(file) }.getOrNull()
                    ?: continue
            hashed[path] = Cached(size, mtime, sha256)
        }
        return hashed
    }

    private data class Cached(val size: Long, val mtime: Long, val sha256: String)

    private data class CacheState(
        val files: Map<String, Cached> = emptyMap(),
        val missing: Set<String> = emptySet(),
        val last: ScanResult? = null,
    )

    /** The cache, or an empty one when it is missing or damaged. */
    @Synchronized
    private fun read(): CacheState =
        runCatching { parse(JSONObject(cacheFile.readText())) }.getOrDefault(CacheState())

    @Synchronized
    private fun update(change: (CacheState) -> CacheState) {
        val old = read()
        val next = change(old)
        if (next != old) write(next)
    }

    private fun parse(json: JSONObject): CacheState {
        val files = json.optJSONObject("files")
        val cached = mutableMapOf<String, Cached>()
        if (files != null) {
            files.keys().forEach { path ->
                val o = files.optJSONObject(path) ?: return@forEach
                val sha256 = o.optString("sha256")
                if (SHA256.matches(sha256))
                    cached[path] = Cached(o.optLong("size", -1), o.optLong("mtime", -1), sha256)
            }
        }
        val last =
            json.optJSONObject("last")?.let { o ->
                ScanResult(
                    relinked = o.optJSONArray("relinked").strings(),
                    revised = o.optJSONArray("revised").strings(),
                    missing = o.optJSONArray("missing").strings(),
                    added = o.optJSONArray("added").strings(),
                    notAdded = o.optJSONArray("notAdded").strings(),
                    finishedAt = o.optLong("finishedAt"),
                )
            }
        return CacheState(
            files = cached,
            missing = json.optJSONArray("missing").strings().toSet(),
            last = last,
        )
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }

    /** Written under a hidden name, then renamed, so a crash never leaves half a cache. */
    private fun write(state: CacheState) {
        val files = JSONObject()
        state.files.forEach { (path, f) ->
            files.put(
                path,
                JSONObject().put("size", f.size).put("mtime", f.mtime).put("sha256", f.sha256),
            )
        }
        val json =
            JSONObject()
                .put("version", 1)
                .put("files", files)
                .put("missing", JSONArray(state.missing.sorted()))
        state.last?.let { r ->
            json.put(
                "last",
                JSONObject()
                    .put("relinked", JSONArray(r.relinked))
                    .put("revised", JSONArray(r.revised))
                    .put("missing", JSONArray(r.missing))
                    .put("added", JSONArray(r.added))
                    .put("notAdded", JSONArray(r.notAdded))
                    .put("finishedAt", r.finishedAt),
            )
        }
        val folder = cacheFile.absoluteFile.parentFile!!.apply { mkdirs() }
        val temp = File(folder, ".${cacheFile.name}.tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(cacheFile)) {
            temp.delete()
            error("Cannot save the folder scan cache")
        }
    }

    companion object {
        /** The cache's file name in the app's files folder. */
        const val CACHE_NAME = "books-folder-cache.json"
        private const val INBOX = "Inbox"
        private const val SETTLE_MILLIS = 5_000L
        private const val MAX_DEPTH = 12
        private const val MAX_FILES = 50_000
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
