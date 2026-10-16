package dev.sadking.folio.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.core.*
import kotlinx.coroutines.*

/**
 * A phone folder the owner picked in Settings; its EPUBs are imported on demand. SAF grants access
 * to only the selected tree. It is scanned on demand; no fictitious file watcher.
 */
class PairedFolder(private val app: FolioApplication) {
    private val prefs = app.getSharedPreferences("paired-folder", Context.MODE_PRIVATE)
    private val resolver = app.contentResolver
    val uri: Uri?
        get() = prefs.getString("tree", null)?.let(Uri::parse)

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
}
