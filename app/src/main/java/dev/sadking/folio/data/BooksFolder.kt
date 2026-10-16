package dev.sadking.folio.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

/**
 * Where the Books folder lives: `Books/Author/Title.epub`, the only copy of each EPUB (M8). Reading
 * and writing it needs Android's All files access, because the reader, the KOReader fingerprint,
 * sending and backups all work on file paths.
 */
class BooksFolder(context: Context) {
    private val prefs = context.getSharedPreferences("books-folder", Context.MODE_PRIVATE)

    val location: File
        get() = prefs.getString(PATH, null)?.let(::File) ?: File(storageRoot(), DEFAULT_NAME)

    /** Android's All files access, which the user grants in system settings. */
    val hasAccess: Boolean
        get() = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /**
     * Uses the folder picked in Android's folder picker. Only folders in the phone's own storage,
     * below its top level, can be used. Returns false for anything else.
     */
    fun choose(tree: Uri): Boolean {
        val path =
            treePath(DocumentsContract.getTreeDocumentId(tree), storageRoot()) ?: return false
        return prefs.edit().putString(PATH, path.path).commit()
    }

    companion object {
        const val DEFAULT_NAME = "Books"
        private const val PATH = "path"

        private fun storageRoot(): File = Environment.getExternalStorageDirectory()

        /** Opens the system page where the user turns on All files access for Librillo. */
        fun accessIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )

        /** The location as people see it: "Phone storage/Books" rather than a system path. */
        fun shown(location: File, phoneStorage: String): String {
            val root = storageRoot().path
            return if (location.path == root || location.path.startsWith("$root/"))
                phoneStorage + location.path.removePrefix(root)
            else location.path
        }
    }
}

/**
 * The file path for a folder picked in Android's folder picker, from its document ID
 * (`primary:Documents/Books`). Null for other volumes, the top level of storage, or a malformed ID.
 */
internal fun treePath(documentId: String, root: File): File? {
    val parts = documentId.split(':', limit = 2)
    if (parts.size != 2 || parts[0] != "primary") return null
    val relative = parts[1].trim('/')
    if (relative.isEmpty()) return null
    if (relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
    return File(root, relative)
}
