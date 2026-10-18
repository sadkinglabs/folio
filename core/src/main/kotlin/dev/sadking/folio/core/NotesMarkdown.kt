package dev.sadking.folio.core

import java.text.Normalizer
import java.time.LocalDate

/**
 * Where a highlight, note or bookmark sits in the book, for putting them in reading order.
 * [fragment] is KOReader's 1-based `DocFragment` (the spine item), [pointer] the KOReader pointer
 * or PDF page, [progression] how far into the chapter (or, without [fragment], into the book).
 */
data class NotePlace(
    val fragment: Int? = null,
    val pointer: String = "",
    val progression: Double? = null,
) {
    companion object {
        private val FRAGMENT = Regex("^/body(?:\\[1\\])?/DocFragment(?:\\[([0-9]+)\\])?(?:/|\\.|$)")

        /** The place a KOReader pointer (or a PDF page number) names. */
        fun of(pointer: String): NotePlace {
            val match = FRAGMENT.find(pointer)
            val fragment = match?.let { it.groupValues[1].ifEmpty { "1" }.toIntOrNull() }
            return NotePlace(fragment, pointer)
        }

        /**
         * Reading order: by chapter (unknown last), then by pointer, compared with its numbers as
         * numbers, or by progression. In one chapter a place known only by progression comes before
         * one with a pointer, since the two cannot be compared.
         */
        val ORDER: Comparator<NotePlace> = Comparator { a, b ->
            val byFragment = compareValues(a.fragment ?: Int.MAX_VALUE, b.fragment ?: Int.MAX_VALUE)
            if (byFragment != 0) return@Comparator byFragment
            val aPointer = a.pointer.isNotEmpty()
            val bPointer = b.pointer.isNotEmpty()
            when {
                aPointer && bPointer -> naturalCompare(a.pointer, b.pointer)
                !aPointer && !bPointer -> compareValues(a.progression ?: 0.0, b.progression ?: 0.0)
                aPointer -> 1
                else -> -1
            }
        }

        /** Compares text with its runs of digits as numbers: `p[9]` before `p[10]`. */
        internal fun naturalCompare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                    val endA = a.digitsEnd(i)
                    val endB = b.digitsEnd(j)
                    val numA = a.substring(i, endA).trimStart('0')
                    val numB = b.substring(j, endB).trimStart('0')
                    val byNumber =
                        if (numA.length != numB.length) numA.length - numB.length
                        else numA.compareTo(numB)
                    if (byNumber != 0) return byNumber
                    i = endA
                    j = endB
                } else {
                    if (a[i] != b[j]) return a[i].compareTo(b[j])
                    i++
                    j++
                }
            }
            return (a.length - i).compareTo(b.length - j)
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'

        private fun String.digitsEnd(from: Int): Int {
            var k = from
            while (k < length && this[k].isAsciiDigit()) k++
            return k
        }
    }
}

/**
 * One highlight, note or bookmark to export. A highlight with a [note] is a note. For a bookmark
 * [text] is its name or the page's text. [colour] is a plain colour name ("yellow", "green",
 * "blue", "pink", "underline", ...), blank for the default look. [date] is KOReader's `YYYY-MM-DD
 * HH:MM:SS`; only the day is written.
 */
data class ExportNote(
    val bookmark: Boolean,
    val text: String,
    val note: String = "",
    val chapter: String = "",
    val date: String = "",
    val colour: String = "",
    val place: NotePlace = NotePlace(),
)

/** The book the notes belong to. [id] lets a later export find and replace its own file. */
data class ExportBook(
    val id: String,
    val title: String,
    val authors: List<String>,
    val hardcoverId: Int? = null,
)

/** The few words the file itself says, in the owner's language. */
data class ExportLabels(
    val noChapter: String = "Other highlights",
    val bookmarks: String = "Bookmarks",
    val empty: String = "No highlights or notes yet.",
)

/**
 * A book's highlights, notes and bookmarks as one Markdown file that reads well in Obsidian:
 * - front matter with the title, the authors, the Hardcover id when the book is matched, the export
 *   date and the book's Librillo id;
 * - a `# Title` heading, then a `## Chapter` heading per chapter, in reading order;
 * - each highlight as a `>` quote, its note below as plain text, then its colour as a
 *   `#highlight/<colour>` tag and the day it was made;
 * - bookmarks, if any, in a list at the end.
 *
 * Every piece of book text is escaped, so a passage holding `>`, `#`, `*`, list markers or line
 * breaks stays inside its quote and never turns into a heading, list or link.
 */
object NotesMarkdown {
    /** The front matter key that marks a file as this book's export. */
    const val ID_KEY = "librillo_id"

    fun build(
        book: ExportBook,
        notes: List<ExportNote>,
        exportedOn: LocalDate,
        labels: ExportLabels = ExportLabels(),
    ): String =
        buildString {
                append("---\n")
                append("title: ").append(yaml(book.title)).append('\n')
                val authors = book.authors.map(::oneLine).filter { it.isNotEmpty() }
                if (authors.isNotEmpty()) {
                    append("author:\n")
                    authors.forEach { append("  - ").append(yaml(it)).append('\n') }
                }
                book.hardcoverId?.let { append("hardcover_id: ").append(it).append('\n') }
                append("exported: ").append(exportedOn).append('\n')
                append(ID_KEY).append(": ").append(yaml(book.id)).append('\n')
                append("---\n\n")
                append("# ").append(heading(book.title)).append("\n\n")
                val ordered =
                    notes.sortedWith(
                        compareBy(NotePlace.ORDER) { it: ExportNote -> it.place }.thenBy { it.date }
                    )
                val (marks, highlights) = ordered.partition { it.bookmark }
                val kept = highlights.filter { it.text.isNotBlank() || it.note.isNotBlank() }
                if (kept.isEmpty() && marks.isEmpty()) {
                    append(escapeLine(labels.empty)).append('\n')
                    return@buildString
                }
                // Chapters in the order their first highlight appears; a chapter seen again later
                // (a
                // pointer that sorts out of place) joins its first heading rather than repeating
                // it.
                val chapters = LinkedHashMap<String, MutableList<ExportNote>>()
                kept.forEach { chapters.getOrPut(oneLine(it.chapter)) { mutableListOf() } += it }
                val untitled = chapters.remove("")
                val groups =
                    chapters.entries.map { it.key to it.value } +
                        listOfNotNull(untitled?.let { labels.noChapter to it })
                groups.forEach { (chapter, items) ->
                    // Only a book whose highlights all lack a chapter goes without a heading.
                    if (chapters.isNotEmpty()) append("## ").append(heading(chapter)).append("\n\n")
                    items.forEach { appendHighlight(it) }
                }
                if (marks.isNotEmpty()) {
                    append("## ").append(heading(labels.bookmarks)).append("\n\n")
                    marks.forEach { appendBookmark(it) }
                    append('\n')
                }
            }
            .trimEnd('\n') + "\n"

    private fun StringBuilder.appendHighlight(item: ExportNote) {
        val quote = paragraphs(item.text)
        if (quote.isNotEmpty()) {
            quote.forEach { line ->
                if (line.isEmpty()) append(">\n")
                else append("> ").append(escapeLine(line)).append('\n')
            }
            append('\n')
        }
        val note = paragraphs(item.note)
        if (note.isNotEmpty()) {
            note.forEach { line -> append(escapeLine(line)).append('\n') }
            append('\n')
        }
        val meta = listOfNotNull(tag(item.colour), day(item.date))
        if (meta.isNotEmpty()) append(meta.joinToString(" · ")).append("\n\n")
    }

    private fun StringBuilder.appendBookmark(item: ExportNote) {
        val name =
            listOf(oneLine(item.chapter), oneLine(item.text))
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(" · ") { escapeLine(it) }
        val parts = listOfNotNull(name.ifEmpty { null }, day(item.date))
        append("- ").append(parts.joinToString(" · ").ifEmpty { "…" }).append('\n')
        // The note continues the list item, indented, one line at a time.
        paragraphs(item.note).forEach { line ->
            if (line.isEmpty()) append('\n') else append("  ").append(escapeLine(line)).append('\n')
        }
    }

    /** The colour as an Obsidian tag, `#highlight/green`; null for the default look. */
    internal fun tag(colour: String): String? {
        val name =
            colour.trim().lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        return if (name.isEmpty()) null else "#highlight/$name"
    }

    /** The day of KOReader's `YYYY-MM-DD HH:MM:SS` as DD/MM/YYYY, or null when it is not one. */
    private fun day(date: String): String? =
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(date.trim())?.destructured?.let { (y, m, d) ->
            "$d/$m/$y"
        }

    /**
     * Text split into lines: line breaks of every kind become one, blank runs become a single empty
     * line, and blank lines at either end are dropped.
     */
    internal fun paragraphs(text: String): List<String> {
        val lines =
            text
                .replace("\r\n", "\n")
                .split('\n', '\r', '\u2028', '\u2029', '\u000B', '\u000C', '\u0085')
                .map { it.trim() }
        val out = mutableListOf<String>()
        lines.forEach { line ->
            if (line.isEmpty()) {
                if (out.isNotEmpty() && out.last().isNotEmpty()) out += ""
            } else out += line
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return out
    }

    /** One line: every run of white space, line breaks included, becomes one space. */
    private fun oneLine(text: String): String =
        text
            .filterNot { it.isISOControl() && !it.isWhitespace() }
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun heading(text: String): String = escapeLine(oneLine(text)).ifEmpty { "…" }

    /** Markdown and Obsidian characters that mean something anywhere in a line. */
    private const val SPECIAL = "\\`*_[]<>#|~\$^"

    private val ENTITY = Regex("#?[A-Za-z0-9]+;")

    /**
     * One line of book text as literal Markdown: every character with a meaning in Markdown or
     * Obsidian is escaped with a backslash, and a line that would start a list or a heading has its
     * marker escaped. Leading spaces (an indented code block) and control characters go.
     */
    internal fun escapeLine(text: String): String {
        val clean = text.filterNot { it.isISOControl() }.trim()
        val out = StringBuilder(clean.length + 8)
        clean.forEachIndexed { i, c ->
            val special =
                when (c) {
                    // `==mark==` and `%%comment%%` need two; one alone stays readable.
                    '=',
                    '%' -> clean.getOrNull(i - 1) == c || clean.getOrNull(i + 1) == c
                    // Only an entity such as `&amp;` would be read as something else.
                    '&' -> ENTITY.matchesAt(clean, i + 1)
                    else -> c in SPECIAL
                }
            if (special) out.append('\\')
            out.append(c)
        }
        // "- ", "+ " start a list, "1. " or "1) " a numbered one.
        if (out.isNotEmpty() && (out[0] == '-' || out[0] == '+')) out.insert(0, '\\')
        Regex("^(\\d{1,9})([.)])").find(out)?.let { m ->
            out.insert(m.groups[2]!!.range.first, '\\')
        }
        return out.toString()
    }

    /**
     * True when [head], the start of a file, is the front matter of book [bookId]'s export: such a
     * file may be replaced by a new export, any other may not.
     */
    fun isExportOf(head: String, bookId: String): Boolean {
        val lines = head.removePrefix("\uFEFF").lines()
        if (lines.firstOrNull()?.trim() != "---") return false
        val marker = "$ID_KEY: ${yaml(bookId)}"
        return lines.drop(1).takeWhile { it.trim() != "---" }.any { it.trim() == marker }
    }

    /** A YAML string in double quotes, on one line. */
    internal fun yaml(text: String): String =
        "\"" + oneLine(text).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Characters that file systems or Obsidian links do not allow in a name. */
    private const val UNSAFE = "\\/:*?\"<>|#^[]"

    /** The longest file name kept, without `.md`. */
    const val NAME_MAX = 100

    /**
     * The file for a book's notes: `Title - First author.md`, the same every time so a new export
     * replaces the old one. Characters that are not safe in file names or Obsidian links become
     * spaces; the name is cut to [NAME_MAX] characters; an empty one is "Highlights".
     */
    fun fileName(title: String, authors: List<String>): String {
        fun clean(text: String) =
            Normalizer.normalize(text, Normalizer.Form.NFC)
                .map { if (it.isISOControl() || it in UNSAFE) ' ' else it }
                .joinToString("")
                .replace(Regex("\\s+"), " ")
                .trim()
        val author = authors.map(::clean).firstOrNull { it.isNotEmpty() }
        var name = listOfNotNull(clean(title).ifEmpty { null }, author).joinToString(" - ")
        if (name.codePointCount(0, name.length) > NAME_MAX)
            name = name.substring(0, name.offsetByCodePoints(0, NAME_MAX))
        name = name.trim().trimStart('.').trimEnd('.', ' ').trim()
        return name.ifEmpty { "Highlights" } + ".md"
    }
}
