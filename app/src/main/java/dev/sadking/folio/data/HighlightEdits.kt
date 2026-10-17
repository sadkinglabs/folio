package dev.sadking.folio.data

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The colours the owner picks for a highlight, as KOReader stores them: a colour name and a drawing
 * style. KOReader has no pink; Pink is its "purple" (a magenta), so it round-trips. Underline keeps
 * the colour and draws a line ("underscore").
 */
enum class HighlightColour(val color: String, val drawer: String) {
    YELLOW("yellow", LIGHTEN),
    GREEN("green", LIGHTEN),
    BLUE("blue", LIGHTEN),
    PINK("purple", LIGHTEN),
    UNDERLINE("", UNDERSCORE);

    companion object {
        /** The swatch that shows [row]'s look, or null for the default look or another colour. */
        fun of(row: BookAnnotation): HighlightColour? =
            if (row.drawer == UNDERSCORE) UNDERLINE
            else entries.firstOrNull { it != UNDERLINE && it.color == row.color }
    }
}

/** KOReader's drawing styles. */
const val LIGHTEN = "lighten"
const val UNDERSCORE = "underscore"

/**
 * The owner's own changes to highlights and notes, from the reader (and the Highlights & notes
 * sheet to come): delete, undo, restore, colour and note. A delete is soft: the row stays with its
 * text for [KEEP_DELETED_MS], can be restored, and sync tells the e-reader. After that only the
 * text and note are cleared; the row stays so sync still knows it was deleted. Nothing here removes
 * a row.
 */
class HighlightEdits(
    private val dao: LibraryDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val localTime: () -> LocalDateTime = LocalDateTime::now,
) {
    /** Deletes [id] softly. Returns the row as it was, for Undo, or null if it was not live. */
    suspend fun delete(id: String): BookAnnotation? {
        val row = dao.annotationById(id)?.takeIf { !it.deleted } ?: return null
        return if (dao.softDeleteAnnotation(id, now()) > 0) row else null
    }

    /** Brings back [id] if it was deleted in the last 30 days. True when it is live again. */
    suspend fun restore(id: String): Boolean =
        dao.restoreAnnotation(id, now() - KEEP_DELETED_MS) > 0

    /** Gives [id] a new colour or style. False when it is gone or already looks that way. */
    suspend fun recolour(id: String, colour: HighlightColour): Boolean {
        val row = dao.annotationById(id)?.takeIf { !it.deleted } ?: return false
        val color = if (colour == HighlightColour.UNDERLINE) row.color else colour.color
        if (color == row.color && colour.drawer == row.drawer) return false
        return dao.editAnnotation(id, row.note, row.kind, color, colour.drawer, stamp()) > 0
    }

    /**
     * Sets [id]'s note; a blank note removes it. A highlight with a note is a note, and back; a
     * bookmark stays a bookmark. False when it is gone or the note is the same.
     */
    suspend fun editNote(id: String, note: String): Boolean {
        val row = dao.annotationById(id)?.takeIf { !it.deleted } ?: return false
        val written = note.trim()
        if (written == row.note) return false
        val kind =
            when {
                row.kind == "bookmark" -> row.kind
                written.isEmpty() -> "highlight"
                else -> "note"
            }
        return dao.editAnnotation(id, written, kind, row.color, row.drawer, stamp()) > 0
    }

    /** Clears the text and note of rows deleted more than 30 days ago. The rows stay. */
    suspend fun clearExpired(): Int = dao.clearDeletedAnnotations(now() - KEEP_DELETED_MS)

    /** The time of an edit, the way KOReader writes it, so the newest edit wins at sync. */
    private fun stamp(): String = localTime().format(DATETIME)

    companion object {
        /** How long a deleted highlight can be restored: 30 days. */
        const val KEEP_DELETED_MS = 30L * 24 * 60 * 60 * 1000

        private val DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    }
}
