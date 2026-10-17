package dev.sadking.folio.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dev.sadking.folio.R
import dev.sadking.folio.core.ExportBook
import dev.sadking.folio.core.ExportLabels
import dev.sadking.folio.core.ExportNote
import dev.sadking.folio.core.NotePlace
import dev.sadking.folio.core.NotesMarkdown
import dev.sadking.folio.sync.SyncAnnotations
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A book's notes as a Markdown file: its name and its text. */
data class ExportFile(val name: String, val text: String)

/**
 * Exports a book's highlights, notes and bookmarks as Markdown ([NotesMarkdown]): shared through
 * the app's file provider, or saved to a folder the owner picked once (an Obsidian vault, say). The
 * folder is remembered with a permission that survives restarts; each book has one file there,
 * named from its title and author, and a new export replaces it. A file of the same name that
 * Librillo did not write for this book is never replaced: the export takes the next free name
 * instead. Nothing here logs book text.
 */
class NotesExport(
    private val context: Context,
    private val dao: LibraryDao,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** The remembered folder, while Librillo may still write there; null otherwise. */
    fun folder(): Uri? {
        val uri = prefs.getString(FOLDER, null)?.toUri() ?: return null
        val held =
            runCatching {
                    context.contentResolver.persistedUriPermissions.any {
                        it.uri == uri && it.isWritePermission
                    }
                }
                .getOrDefault(false)
        return uri.takeIf { held }
    }

    /** The remembered folder's name, for "Save to …"; null without one. */
    fun folderName(): String? = folder()?.let { prefs.getString(FOLDER_NAME, null) ?: "" }

    /**
     * Keeps [tree], picked in Android's folder picker, for later exports. Returns its name, or null
     * when Android did not let Librillo keep access to it.
     */
    fun remember(tree: Uri): String? {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(tree, flags)
        } catch (_: SecurityException) {
            return null
        }
        // An earlier folder's permission is kept: another part of the app may use the same one.
        val name = displayName(tree).orEmpty()
        prefs.edit().putString(FOLDER, tree.toString()).putString(FOLDER_NAME, name).apply()
        return name
    }

    /** Forgets the folder, after it could not be reached. */
    fun forget() {
        prefs.edit().remove(FOLDER).remove(FOLDER_NAME).apply()
    }

    /** The book's notes as Markdown, with the file name they are saved under. */
    suspend fun file(bookId: String): ExportFile =
        withContext(Dispatchers.IO) {
            val book = dao.book(bookId) ?: error(context.getString(R.string.msg_book_gone))
            val rows = SyncAnnotations.live(dao.bookAnnotations(bookId).first())
            val marks = dao.phoneBookmarks(bookId, book.currentHash).first()
            val hardcover =
                dao.allStoredWishes()
                    .firstOrNull { it.linkedBookId == bookId && it.hardcoverId != null }
                    ?.hardcoverId
            val authors = decode(book.authors)
            val text =
                NotesMarkdown.build(
                    ExportBook(book.id, book.title, authors, hardcover),
                    exportNotes(rows, marks),
                    today(),
                    ExportLabels(
                        noChapter = context.getString(R.string.notes_export_no_chapter),
                        bookmarks = context.getString(R.string.notes_export_bookmarks),
                        empty = context.getString(R.string.notes_export_empty),
                    ),
                )
            ExportFile(NotesMarkdown.fileName(book.title, authors), text)
        }

    /**
     * Writes [file] where the share sheet can read it and returns the share sheet for it. Only the
     * latest export is kept there.
     */
    suspend fun shareIntent(file: ExportFile, title: String): Intent =
        withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, SHARE_DIR)
            folder.deleteRecursively()
            folder.mkdirs()
            val out = File(folder, file.name)
            out.writeText(file.text)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", out)
            val send =
                Intent(Intent.ACTION_SEND)
                    .setType(MIME)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, title)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            Intent.createChooser(send, null)
        }

    /**
     * Saves [file] in the folder [tree] for book [bookId], replacing that book's earlier export.
     * Returns where it went, as "Folder/File.md".
     */
    suspend fun save(tree: Uri, file: ExportFile, bookId: String): String =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val parent =
                DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                )
            val existing = children(tree)
            val name =
                exportTarget(file.name, existing.keys) { taken ->
                    existing[taken]?.let { isOurs(it, bookId) } == true
                }
            val target =
                existing[name]
                    ?: DocumentsContract.createDocument(resolver, parent, MIME, name)
                    ?: error(context.getString(R.string.notes_export_folder_failed))
            resolver.openOutputStream(target, "wt")?.use { it.write(file.text.toByteArray()) }
                ?: error(context.getString(R.string.notes_export_folder_failed))
            val shownName = displayName(target) ?: name
            listOfNotNull(prefs.getString(FOLDER_NAME, null)?.ifEmpty { null }, shownName)
                .joinToString("/")
        }

    /** Writes [file] to a document the owner created in Android's save dialog. */
    suspend fun write(uri: Uri, file: ExportFile) =
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(file.text.toByteArray())
            } ?: error(context.getString(R.string.msg_write_failed))
        }

    /** The files directly in [tree], by name. */
    private fun children(tree: Uri): Map<String, Uri> {
        val list =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
        val found = mutableMapOf<String, Uri>()
        context.contentResolver
            .query(
                list,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = cursor.getString(1) ?: continue
                    found[name] =
                        DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                }
            }
        return found
    }

    /** True when [document] starts with this book's export front matter. */
    private fun isOurs(document: Uri, bookId: String): Boolean =
        runCatching {
                context.contentResolver.openInputStream(document)?.use { input ->
                    val head = ByteArray(HEAD_BYTES)
                    var read = 0
                    while (read < head.size) {
                        val n = input.read(head, read, head.size - read)
                        if (n < 0) break
                        read += n
                    }
                    NotesMarkdown.isExportOf(String(head, 0, read, Charsets.UTF_8), bookId)
                } ?: false
            }
            .getOrDefault(false)

    private fun displayName(uri: Uri): String? =
        runCatching {
                val document =
                    if (DocumentsContract.isDocumentUri(context, uri)) uri
                    else
                        DocumentsContract.buildDocumentUriUsingTree(
                            uri,
                            DocumentsContract.getTreeDocumentId(uri),
                        )
                context.contentResolver
                    .query(
                        document,
                        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }
            .getOrNull()

    companion object {
        const val PREFERENCES = "notes-export"
        const val MIME = "text/markdown"
        private const val FOLDER = "folder"
        private const val FOLDER_NAME = "folderName"
        private const val SHARE_DIR = "epub-share/notes"
        private const val HEAD_BYTES = 4096
    }
}

/**
 * The name to save a book's export under in a folder holding [existing] files: [name] when it is
 * free or [ours] (this book's earlier export), else "Name (2).md", "Name (3).md", … the first that
 * is free or ours. A file Librillo did not write for this book is never chosen.
 */
internal fun exportTarget(name: String, existing: Set<String>, ours: (String) -> Boolean): String {
    val base = name.removeSuffix(".md")
    var n = 1
    while (true) {
        val candidate = if (n == 1) name else "$base ($n).md"
        if (candidate !in existing || ours(candidate)) return candidate
        n++
    }
}

/** The file a book's notes are saved under: `Title - Author.md`. */
fun notesFileName(book: Book): String = NotesMarkdown.fileName(book.title, decode(book.authors))

private val EXPORT_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

/** The colour a highlight is exported with: plain names, KOReader's purple as the phone's pink. */
internal fun exportColour(row: BookAnnotation): String =
    when {
        row.drawer == UNDERSCORE -> "underline"
        row.color.equals("purple", ignoreCase = true) -> "pink"
        else -> row.color.trim().lowercase(Locale.ROOT)
    }

/**
 * The book's live highlights, notes and bookmarks ([rows], from every device) and the phone's own
 * bookmarks ([marks]) as the export sees them. A phone bookmark's place is how far into the book it
 * is, since its chapter's KOReader number is only known with the book open.
 */
internal fun exportNotes(
    rows: List<BookAnnotation>,
    marks: List<PhoneBookmark>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ExportNote> =
    rows
        .filter { !it.deleted }
        .map { row ->
            ExportNote(
                bookmark = row.kind == "bookmark",
                text = row.text,
                note = row.note,
                chapter = row.chapter,
                date = row.date,
                colour = if (row.kind == "bookmark") "" else exportColour(row),
                place = NotePlace.of(row.position),
            )
        } +
        marks.map { mark ->
            val progression =
                runCatching {
                        JSONObject(mark.locator)
                            .optJSONObject("locations")
                            ?.optDouble("totalProgression")
                            ?.takeIf { !it.isNaN() }
                    }
                    .getOrNull()
            ExportNote(
                bookmark = true,
                text = mark.title,
                date = EXPORT_DATETIME.format(Instant.ofEpochMilli(mark.createdAt).atZone(zone)),
                place = NotePlace(progression = progression),
            )
        }
