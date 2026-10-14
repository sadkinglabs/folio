package dev.sadking.folio.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.core.*
import dev.sadking.folio.device.SftpDevice
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

/**
 * SAF grants access to only the selected tree. It is scanned on demand; no fictitious file watcher.
 */
class PairedFolder(private val app: FolioApplication) {
    private val prefs = app.getSharedPreferences("paired-folder", Context.MODE_PRIVATE)
    private val resolver = app.contentResolver
    val uri: Uri?
        get() = prefs.getString("tree", null)?.let(Uri::parse)

    var lastReport: List<String> = emptyList()
        private set

    val label: String
        get() = prefs.getString("label", "").orEmpty()

    fun select(tree: Uri) {
        resolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val name =
            resolver
                .query(
                    DocumentsContract.buildDocumentUriUsingTree(
                        tree,
                        DocumentsContract.getTreeDocumentId(tree),
                    ),
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Phone folder"
        check(prefs.edit().putString("tree", tree.toString()).putString("label", name).commit())
    }

    fun forget() {
        val old = uri
        check(prefs.edit().clear().commit())
        old?.let {
            runCatching {
                resolver.releasePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    private data class Entry(val path: String, val uri: Uri)

    private fun children(tree: Uri, parentId: String): List<Triple<String, String, String>> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
        return resolver.query(childUri, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    require(size < 10000) { "Phone folder contains too many entries" }
                    add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        } ?: error("Phone folder is unavailable. Grant access again in Settings.")
    }

    private fun scan(tree: Uri): List<Entry> {
        val result = mutableListOf<Entry>()
        var visited = 0
        fun walk(id: String, path: String, depth: Int) {
            require(depth <= 12) { "Phone folder is too deeply nested" }
            val entries = children(tree, id)
            require(entries.map { it.second }.distinct().size == entries.size) {
                "Folder provider returned duplicate names"
            }
            for ((childId, name, mime) in entries) {
                require(++visited <= 10000) { "Phone folder contains too many entries" }
                if (name.startsWith('.') || name.endsWith(".sdr", true)) continue
                require(
                    name.isNotEmpty() && name.none { it == '/' || it == '\\' || it.code < 32 }
                ) {
                    "Unsupported folder name"
                }
                val next = if (path.isEmpty()) name else "$path/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(childId, next, depth + 1)
                else if (name.endsWith(".epub", true)) {
                    FolderReconciliation.validatePath(next)
                    result +=
                        Entry(next, DocumentsContract.buildDocumentUriUsingTree(tree, childId))
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        return result
    }

    suspend fun refresh(): Int =
        withContext(Dispatchers.IO) {
            val tree = uri ?: error("Choose a phone folder in Settings first")
            var added = 0
            for (entry in scan(tree)) {
                currentCoroutineContext().ensureActive()
                if (app.repository.import(entry.uri).second) added++
            }
            added
        }

    private fun stage(uri: Uri): File {
        val file = File(app.cacheDir, "folder-${UUID.randomUUID()}.epub")
        try {
            resolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { Epub.copyBounded(input, it) }
            } ?: error("Cannot read EPUB in phone folder")
            return file
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    private fun ensureParent(tree: Uri, path: String): Uri {
        var id = DocumentsContract.getTreeDocumentId(tree)
        for (name in path.split('/').dropLast(1)) {
            val same = children(tree, id).filter { it.second == name }
            require(same.size <= 1) { "Duplicate phone folder name" }
            id =
                if (same.isEmpty()) {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    val created =
                        DocumentsContract.createDocument(
                            resolver,
                            parent,
                            DocumentsContract.Document.MIME_TYPE_DIR,
                            name,
                        ) ?: error("Folder provider cannot create directories")
                    DocumentsContract.getDocumentId(created)
                } else {
                    require(same.single().third == DocumentsContract.Document.MIME_TYPE_DIR) {
                        "A file blocks the phone folder"
                    }
                    same.single().first
                }
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    private fun publishPhone(tree: Uri, path: String, source: File): Uri {
        val parent = ensureParent(tree, path)
        val name = path.substringAfterLast('/')
        require(
            children(tree, DocumentsContract.getDocumentId(parent)).none { it.second == name }
        ) {
            "Phone destination appeared during sync; nothing overwritten"
        }
        val temporary =
            DocumentsContract.createDocument(
                resolver,
                parent,
                "application/octet-stream",
                ".folio-${UUID.randomUUID()}.part",
            ) ?: error("Cannot stage EPUB in phone folder")
        var published = false
        try {
            resolver.openOutputStream(temporary, "wt")?.use { out ->
                source.inputStream().use { Epub.copyBounded(it, out) }
            } ?: error("Cannot write phone folder")
            require(
                resolver.openInputStream(temporary)?.use { Epub.hash(it) } == Epub.sha256(source)
            ) {
                "Phone copy verification failed"
            }
            require(
                children(tree, DocumentsContract.getDocumentId(parent)).none { it.second == name }
            ) {
                "Phone destination appeared during sync"
            }
            val result =
                DocumentsContract.renameDocument(resolver, temporary, name)
                    ?: error("Provider does not support safe publishing. Choose a local folder.")
            published = true
            return result
        } finally {
            if (!published) runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
        }
    }

    private suspend fun reconcileReading(books: List<Book>): String {
        val dao = app.database.library()
        val config = app.deviceSettings.load()
        var incoming = 0
        var outgoing = 0
        var conflicts = 0
        var unavailable = 0
        val handoff = dev.sadking.folio.reader.ReaderHandoff(app)
        for (book in books.distinctBy { it.id }) {
            currentCoroutineContext().ensureActive()
            val phone = dao.phoneReading(book.id, book.currentHash)
            val remote =
                try {
                    handoff.retrieve(book)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    null
                }
            if (phone == null && remote == null) {
                unavailable++
                continue
            }
            if (phone != null && phone.xpointer.isBlank()) {
                lastReport =
                    lastReport +
                        "${book.title} · Phone anchor is unavailable. Open a text page before sending progress."
                conflicts++
                continue
            }
            val key =
                "baseline:" + Epub.hash((config.id + book.id + book.currentHash).byteInputStream())
            val base =
                prefs
                    .getString(key, null)
                    ?.let { org.json.JSONObject(it) }
                    ?.let { PositionBaseline(it.getString("phone"), it.getString("kindle")) }
            val phonePointer = phone?.xpointer.orEmpty()
            val kindlePointer = remote?.position.orEmpty()
            val decision = PositionReconciliation.plan(phonePointer, kindlePointer, base)
            when (decision) {
                PositionAction.CONFLICT -> {
                    conflicts++
                    lastReport =
                        lastReport +
                            "${book.title} · Both devices have saved positions. Choose the source in the reader’s Kindle panel."
                }
                PositionAction.TO_PHONE -> {
                    if (
                        remote == null ||
                            dev.sadking.folio.reader.xpointerSpineIndex(
                                remote.position,
                                Int.MAX_VALUE,
                            ) == null
                    ) {
                        lastReport =
                            lastReport +
                                "${book.title} · KOReader position format is unsupported. Existing positions were kept."
                        conflicts++
                        continue
                    }
                    check(
                        prefs
                            .edit()
                            .putString(
                                "incoming:${book.id}",
                                org.json
                                    .JSONObject()
                                    .put("revision", book.currentHash)
                                    .put("position", remote.position)
                                    .put("expectedPhone", phone?.locator.orEmpty())
                                    .toString(),
                            )
                            .commit()
                    )
                    incoming++
                }
                PositionAction.TO_KINDLE -> {
                    if (phone == null) {
                        unavailable++
                        continue
                    }
                    try {
                        handoff.send(book, phone)
                        outgoing++
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        lastReport =
                            lastReport +
                                "${book.title} · Position request failed. Check the connection and matching EPUB, then retry."
                        conflicts++
                        continue
                    }
                }
                PositionAction.UNCHANGED -> Unit
            }
            // Pending requests are not marked applied. Baselines reflect only observed positions.
            if (decision == PositionAction.UNCHANGED)
                check(
                    prefs
                        .edit()
                        .putString(
                            key,
                            org.json
                                .JSONObject()
                                .put("phone", phonePointer)
                                .put("kindle", kindlePointer)
                                .toString(),
                        )
                        .commit()
                )
        }
        return "$incoming Kindle positions ready for phone · $outgoing phone positions queued for KOReader · $conflicts reading conflicts · $unavailable without saved progress"
    }

    /** Additions only. Changed editions are reported for explicit review; no sidecar mirroring. */
    suspend fun reconcile(): String =
        withContext(Dispatchers.IO) {
            lastReport = emptyList()
            val tree = uri ?: error("Choose a phone folder in Settings first")
            val coroutine = currentCoroutineContext()
            val config = app.deviceSettings.load()
            val phoneEntries = scan(tree).associateBy { it.path }
            val files = mutableMapOf<String, File>()
            val id = UUID.randomUUID().toString()
            val started = System.currentTimeMillis()
            app.database
                .library()
                .journal(
                    Journal(
                        id = id,
                        bookId = "",
                        state = "COMPARING",
                        deviceId = config.id,
                        startedAt = started,
                        message = "Comparing paired folders",
                    )
                )
            try {
                var stagedBytes = 0L
                for ((path, entry) in phoneEntries) {
                    currentCoroutineContext().ensureActive()
                    files[path] = stage(entry.uri)
                    stagedBytes += files.getValue(path).length()
                    require(stagedBytes <= 1024L * 1024 * 1024) {
                        "Selected folder exceeds the 1 GiB staging limit. Choose a smaller reading folder."
                    }
                }
                val phone = files.map { FolderFile(it.key, Epub.sha256(it.value)) }
                var sent = 0
                var received = 0
                var conflicts = 0
                var verified = 0
                val reconciledBooks = mutableListOf<Book>()
                SftpDevice(config, app.deviceSettings.credential()).use { remote ->
                    val kindle =
                        remote.inventory().map { entry ->
                            currentCoroutineContext().ensureActive()
                            FolderFile(entry.path, remote.read(entry.path).use { Epub.hash(it) })
                        }
                    for (change in FolderReconciliation.plan(phone, kindle)) {
                        currentCoroutineContext().ensureActive()
                        if (change.action == FolderAction.CONFLICT) {
                            phoneEntries[change.path]?.let { app.repository.import(it.uri) }
                            lastReport =
                                lastReport +
                                    "${change.path} · Different EPUBs on phone and Kindle; both kept"
                            conflicts++
                            continue
                        }
                        val book: Book
                        when (change.action) {
                            FolderAction.TO_PHONE -> {
                                val stage = File(app.cacheDir, "download-${UUID.randomUUID()}.epub")
                                try {
                                    remote.read(change.path).use { input ->
                                        stage.outputStream().use { Epub.copyBounded(input, it) }
                                    }
                                    val hash = kindle.first { it.path == change.path }.hash
                                    require(
                                        Epub.sha256(stage) == hash &&
                                            remote.read(change.path).use { Epub.hash(it) } == hash
                                    ) {
                                        "Kindle EPUB changed while downloading; retry"
                                    }
                                    Epub.parse(stage)
                                    val uri = publishPhone(tree, change.path, stage)
                                    book = app.repository.import(uri).first
                                    received++
                                } finally {
                                    stage.delete()
                                }
                            }
                            else -> {
                                val entry = phoneEntries.getValue(change.path)
                                book = app.repository.import(entry.uri).first
                                val expected = phone.first { it.path == change.path }.hash
                                if (book.currentHash != expected) {
                                    lastReport =
                                        lastReport +
                                            "${change.path} · Edited library revision differs from the watched file; export the edited EPUB to a new filename"
                                    conflicts++
                                    continue
                                } // An edited library revision needs review.
                                if (change.action == FolderAction.TO_KINDLE) {
                                    var parent = ""
                                    for (name in change.path.split('/').dropLast(1)) {
                                        remote.createDirectory(parent, name)
                                        parent = if (parent.isEmpty()) name else "$parent/$name"
                                    }
                                    SyncEngine(remote)
                                        .send(
                                            SyncItem(
                                                SyncBook(
                                                    book.id,
                                                    book.title,
                                                    files.getValue(change.path),
                                                    expected,
                                                ),
                                                change.path,
                                                Action.UPLOAD,
                                                "Paired-folder addition",
                                            ),
                                            journal = {},
                                            checkCancelled = { coroutine.ensureActive() },
                                        )
                                    sent++
                                } else verified++
                            }
                        }
                        val hash = remote.read(change.path).use { Epub.hash(it) }
                        require(hash == book.currentHash) {
                            "Edition changed during reconciliation; progress left untouched"
                        }
                        reconciledBooks += book
                        app.database
                            .library()
                            .transfer(
                                Transfer(
                                    book.id,
                                    config.id,
                                    change.path,
                                    hash,
                                    System.currentTimeMillis(),
                                )
                            )
                    }
                }
                val readingResult = reconcileReading(reconciledBooks)
                val result =
                    "$sent sent to Kindle · $received added to phone · $verified verified · $conflicts changed editions need review. No files or annotations deleted. $readingResult."
                app.database
                    .library()
                    .journal(
                        Journal(
                            id = id,
                            bookId = "",
                            state =
                                if (conflicts > 0 || lastReport.isNotEmpty()) "CONFLICT"
                                else "COMPLETE",
                            deviceId = config.id,
                            startedAt = started,
                            message = result,
                        )
                    )
                result
            } catch (failure: Exception) {
                app.database
                    .library()
                    .journal(
                        Journal(
                            id = id,
                            bookId = "",
                            state = "INTERRUPTED",
                            deviceId = config.id,
                            startedAt = started,
                            message =
                                "Folder sync interrupted. Completed copies retained; retry reconciles verified files.",
                        )
                    )
                throw failure
            } finally {
                files.values.forEach { it.delete() }
            }
        }
}
