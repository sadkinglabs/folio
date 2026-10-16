package dev.sadking.folio.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File

/** Presentation derives from independent observations; it never rewrites a reading source. */
data class BookSignals(
    val reading: String,
    val progress: Double?,
    val hardcover: String?,
    val readingFetchedAt: Long? = null,
)

fun bookSignals(book: Book, reading: ReadingSnapshot?, hardcoverLinked: Boolean): BookSignals {
    val status =
        when {
            reading?.status == "complete" -> "Finished · Kindle"
            reading?.status == "reading" -> "Reading · Kindle"
            book.status != "Unread" -> "${book.status} · phone"
            else -> "Unread"
        }
    return BookSignals(
        status,
        reading?.percent?.takeIf { it.isFinite() && it in 0.0..1.0 },
        if (hardcoverLinked) "Hardcover matched" else null,
        reading?.fetchedAt,
    )
}

/**
 * True when a book has a recorded file that is not there any more (deleted or moved by hand). The
 * book keeps its record, progress, notes and lists. A blank path (no file yet) is not missing.
 * Reads the disk: call it off the main thread.
 */
fun bookFileMissing(book: Book, isFile: (String) -> Boolean = { File(it).isFile }): Boolean =
    book.currentPath.isNotBlank() && !isFile(book.currentPath)

/**
 * What the Books folder scan leaves for the screens, in SharedPreferences "books-folder-changed":
 * - "changed_ids" (string set): books whose file the scan accepted as a new version made outside
 *   Librillo. The scan adds ids; the book page removes one when that book is opened.
 * - "last_scan_at" (long, epoch millis) and "last_scan_message" (plain English, empty when nothing
 *   changed): the last scan's result, shown in Settings, Books folder.
 */
object FolderScanNotes {
    const val PREFERENCES = "books-folder-changed"
    const val CHANGED_IDS = "changed_ids"
    const val LAST_SCAN_AT = "last_scan_at"
    const val LAST_SCAN_MESSAGE = "last_scan_message"

    data class LastScan(val at: Long, val message: String)

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** A copy: the set SharedPreferences returns must not be changed. */
    fun changedIds(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(CHANGED_IDS, null)?.toSet().orEmpty()

    fun clearChanged(prefs: SharedPreferences, id: String) {
        val ids = changedIds(prefs)
        if (id in ids) prefs.edit { putStringSet(CHANGED_IDS, ids - id) }
    }

    /**
     * Adds [ids] to the books changed outside Librillo and drops ids that aren't in [books] any
     * more. Written as a fresh set: the one SharedPreferences returns must not be changed.
     */
    fun markChanged(prefs: SharedPreferences, ids: Set<String>, books: Set<String>) {
        val old = changedIds(prefs)
        val next = (old + ids).filter { it in books }.toSet()
        if (next != old) prefs.edit { putStringSet(CHANGED_IDS, next) }
    }

    /**
     * The last check of the Books folder, for Settings. [message] is empty when nothing changed.
     */
    fun recordScan(prefs: SharedPreferences, at: Long, message: String) {
        prefs.edit {
            putLong(LAST_SCAN_AT, at)
            putString(LAST_SCAN_MESSAGE, message)
        }
    }

    fun lastScan(prefs: SharedPreferences): LastScan? =
        prefs
            .getLong(LAST_SCAN_AT, 0L)
            .takeIf { it > 0L }
            ?.let {
                LastScan(it, prefs.getString(LAST_SCAN_MESSAGE, null).orEmpty())
            }
}
