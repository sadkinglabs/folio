package dev.sadking.folio.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.data.BookAnnotation
import dev.sadking.folio.data.HighlightColour
import dev.sadking.folio.data.UNDERSCORE
import dev.sadking.folio.ui.FolioSpace
import dev.sadking.folio.ui.FolioTheme
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication

/**
 * Highlights and notes in the phone reader (EPUB only). Every live annotation of the book is drawn
 * where its KOReader pointers resolve, from any device, in its colour; the owner adds new ones from
 * the text selection menu. They are stored with KOReader pointers so the e-reader can place them.
 * Tapping one opens its sheet: colour, note, Copy and Delete. A delete is soft, with Undo, and
 * restorable from the book page for 30 days (data/HighlightEdits.kt). Sync carries deletions,
 * colours and notes to the e-reader and back (docs/SYNC_PROTOCOL.md, rule 7).
 *
 * Selecting text that is already highlighted opens that highlight instead of adding another: the
 * same passage (the same start and end), or a passage inside one. A selection that only overlaps a
 * highlight, or covers more than one, is a new highlight: it is a different passage, and nothing
 * else is changed.
 */

/** Device id of highlights and notes made in Librillo's own reader. */
const val PHONE_ANNOTATION_DEVICE = "phone"

/** The Readium decoration group that holds highlights and notes. */
internal const val HIGHLIGHT_GROUP = "highlights"

internal const val HIGHLIGHT_SHEET_TAG = "reader-highlight-sheet"
internal const val HIGHLIGHT_NOTE_DIALOG_TAG = "reader-note-dialog"
internal const val HIGHLIGHT_COLOURS_TAG = "reader-highlight-colours"

/** Items of the menu over selected text. */
internal const val SELECTION_HIGHLIGHT = 0x5f01
internal const val SELECTION_NOTE = 0x5f02
internal const val SELECTION_COPY = 0x5f03
internal const val SELECTION_DEFINE = 0x5f04
internal const val SELECTION_COLOUR = 0x5f05
internal const val SELECTION_FROM_HERE = 0x5f06

/**
 * The selection menu in order, as item id and label: Highlight (in the last colour picked),
 * Colour…, Add note, Define, Copy, then Highlight from here… (ReaderHighlightFromHere.kt). The
 * newest item comes last, so on a narrow screen it is the one that goes into the menu's overflow,
 * never Define.
 */
internal val SELECTION_MENU =
    listOf(
        SELECTION_HIGHLIGHT to R.string.reader_highlight_action,
        SELECTION_COLOUR to R.string.reader_highlight_colour_action,
        SELECTION_NOTE to R.string.reader_highlight_note_action,
        SELECTION_DEFINE to R.string.rd6_define_action,
        SELECTION_COPY to R.string.reader_highlight_copy_action,
        SELECTION_FROM_HERE to R.string.reader_highlight_from_here_action,
    )

/**
 * The default look, for a row without a colour (every one from before schema 13): amber for a
 * highlight, blue for one with a note. Readium tints them.
 */
internal const val HIGHLIGHT_TINT = 0xFFF9A825.toInt()
internal const val NOTE_TINT = 0xFF42A5F5.toInt()

/**
 * The tint for each KOReader colour name. Yellow is the old default amber, so a highlight looks the
 * same after it gets a colour; "purple" is the phone's Pink.
 */
private val KOREADER_TINTS =
    mapOf(
        "yellow" to HIGHLIGHT_TINT,
        "green" to 0xFF43A047.toInt(),
        "blue" to NOTE_TINT,
        "purple" to 0xFFEC407A.toInt(),
        "red" to 0xFFE53935.toInt(),
        "orange" to 0xFFFB8C00.toInt(),
        "olive" to 0xFF9E9D24.toInt(),
        "cyan" to 0xFF26C6DA.toInt(),
        "gray" to 0xFF9E9E9E.toInt(),
    )

/** The tint a swatch shows. */
internal fun swatchTint(colour: HighlightColour): Int =
    KOREADER_TINTS[colour.color] ?: HIGHLIGHT_TINT

private val DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

/** KOReader's annotation time: local time, "YYYY-MM-DD HH:MM:SS". */
fun annotationDatetime(at: LocalDateTime): String = at.format(DATETIME)

/**
 * An annotation's id on every device: the first 32 hex characters of SHA-256 of `device id + "\n" +
 * datetime + "\n" + pos0` (docs/SYNC_PROTOCOL.md).
 */
fun annotationId(deviceId: String, datetime: String, pos0: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$deviceId\n$datetime\n$pos0".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(32)

/**
 * The passage the owner selected, as KOReader pointers into one chapter. [inside] holds the ids of
 * the highlights that contain it (the page works that out on its own words).
 */
data class SelectedPassage(
    val pos0: String,
    val pos1: String,
    val text: String,
    val inside: List<String> = emptyList(),
)

/**
 * The row stored for a highlight made on the phone: kind "note" when [note] has text, else
 * "highlight", in [colour] (KOReader's colour and style; none keeps the default look). When
 * [sameStartTaken] (another annotation already has the plain id: same second, same start), the id
 * also covers the end and kind, as the plugin does for the same case.
 */
internal fun phoneAnnotation(
    bookId: String,
    passage: SelectedPassage,
    note: String,
    chapter: String,
    datetime: String,
    sameStartTaken: Boolean = false,
    colour: HighlightColour? = null,
): BookAnnotation {
    val written = note.trim()
    val kind = if (written.isEmpty()) "highlight" else "note"
    val start = if (sameStartTaken) "${passage.pos0}\n${passage.pos1}\n$kind" else passage.pos0
    return BookAnnotation(
        id = annotationId(PHONE_ANNOTATION_DEVICE, datetime, start),
        bookId = bookId,
        deviceId = PHONE_ANNOTATION_DEVICE,
        text = passage.text,
        note = written,
        chapter = chapter,
        date = datetime,
        position = passage.pos0,
        endPosition = passage.pos1,
        kind = kind,
        color = colour?.color.orEmpty(),
        drawer = colour?.drawer.orEmpty(),
    )
}

/**
 * A page script's answer to `JSON.stringify(...)`: WebView hands back the JSON of that string, so
 * it is decoded twice. Null for anything else.
 */
internal fun bridgeValue(raw: String?): Any? {
    val text = runCatching { JSONTokener(raw ?: return null).nextValue() as? String }.getOrNull()
    return text?.let { runCatching { JSONTokener(it).nextValue() }.getOrNull() }
}

/**
 * The selection the page reported, or null when it is not a passage of chapter [index]: missing or
 * unsupported pointers, pointers into another chapter, or no text.
 */
internal fun parseSelection(raw: String?, index: Int, spineSize: Int): SelectedPassage? {
    val value = bridgeValue(raw) as? JSONObject ?: return null
    val pos0 = value.optString("pos0")
    val pos1 = value.optString("pos1")
    val text = value.optString("text").trim()
    if (text.isEmpty()) return null
    if (xpointerSpineIndex(pos0, spineSize) != index) return null
    if (xpointerSpineIndex(pos1, spineSize) != index) return null
    val inside = value.optJSONArray("inside")
    return SelectedPassage(
        pos0,
        pos1,
        text,
        (0 until (inside?.length() ?: 0)).mapNotNull { inside?.optString(it)?.ifEmpty { null } },
    )
}

/**
 * The page call that reads the selection in chapter [index], and which of [rows] (this chapter's
 * highlights) hold it.
 */
internal fun selectionCall(index: Int, rows: List<BookAnnotation>): String =
    "JSON.stringify(FolioHighlights.selection($index, ${highlightItems(rows)}));"

/**
 * The live highlight the owner meant when selecting [passage], or null for a new one: the one with
 * the same start and end, else the smallest one that contains it. A bookmark is never one.
 */
internal fun existingHighlight(
    passage: SelectedPassage,
    highlights: List<BookAnnotation>,
): BookAnnotation? {
    val live = highlights.filter { !it.deleted && it.kind != "bookmark" }
    live
        .firstOrNull { it.position == passage.pos0 && it.endPosition == passage.pos1 }
        ?.let {
            return it
        }
    return live
        .filter { it.id in passage.inside }
        .minWithOrNull(compareBy<BookAnnotation> { it.text.length }.thenByDescending { it.date })
}

/** An annotation the reader can try to draw, in the chapter with KOReader index [chapter]. */
internal data class PlaceableHighlight(val annotation: BookAnnotation, val chapter: Int)

/**
 * The annotations with a start and end in the same chapter, as KOReader pointers. Bookmarks, PDF
 * pages and passages across chapters are not drawn; the book page still lists them.
 */
internal fun placeableHighlights(
    rows: List<BookAnnotation>,
    spineSize: Int,
): List<PlaceableHighlight> = rows.mapNotNull { row ->
    if (row.kind == "bookmark") return@mapNotNull null
    val start = xpointerSpineIndex(row.position, spineSize) ?: return@mapNotNull null
    val end = xpointerSpineIndex(row.endPosition, spineSize) ?: return@mapNotNull null
    if (start != end || row.position == row.endPosition) null else PlaceableHighlight(row, start)
}

/** The page call that places [rows] in the chapter on screen. */
internal fun placementCall(rows: List<BookAnnotation>): String =
    "JSON.stringify(FolioHighlights.place(${highlightItems(rows)}));"

/** [rows] as the page script takes them: id, pos0 and pos1. */
internal fun highlightItems(rows: List<BookAnnotation>): JSONArray {
    val items = JSONArray()
    rows.forEach {
        items.put(
            JSONObject().put("id", it.id).put("pos0", it.position).put("pos1", it.endPosition)
        )
    }
    return items
}

/** Where one highlight sits in its page, the way Readium anchors a decoration. */
internal data class HighlightQuote(
    val selector: String,
    val before: String,
    val highlight: String,
    val after: String,
)

/** The page's answer to [placementCall]: its own path and the highlights it placed, by id. */
internal data class HighlightPlacement(val path: String, val quotes: Map<String, HighlightQuote>)

internal fun parsePlacement(raw: String?): HighlightPlacement? {
    val value = bridgeValue(raw) as? JSONObject ?: return null
    val path = value.optString("path").takeIf { it.isNotEmpty() } ?: return null
    val placed = value.optJSONObject("placed") ?: return null
    val quotes = mutableMapOf<String, HighlightQuote>()
    placed.keys().forEach { id ->
        val item = placed.optJSONObject(id) ?: return@forEach
        val text = item.optJSONObject("text") ?: return@forEach
        val selector = item.optString("selector")
        val highlight = text.optString("highlight")
        if (selector.isEmpty() || highlight.isBlank()) return@forEach
        quotes[id] =
            HighlightQuote(selector, text.optString("before"), highlight, text.optString("after"))
    }
    return HighlightPlacement(path, quotes)
}

/**
 * True when the page's own [pagePath] (decoded, as `location.pathname`) is the resource
 * [spinePath], so pointers meant for one chapter are never placed in another.
 */
internal fun samePage(pagePath: String, spinePath: String): Boolean {
    if (spinePath.isEmpty()) return false
    val path = pagePath.trimStart('/')
    return path == spinePath || path.endsWith("/$spinePath")
}

/** The highlight's tint: its colour, or the old default look when it has none. */
internal fun highlightTint(annotation: BookAnnotation): Int =
    KOREADER_TINTS[annotation.color.lowercase()]
        ?: if (annotation.note.isBlank()) HIGHLIGHT_TINT else NOTE_TINT

/** How Readium draws the highlight: a line for KOReader's underline, else a filled highlight. */
internal fun highlightStyle(annotation: BookAnnotation): Decoration.Style =
    if (annotation.drawer == UNDERSCORE)
        Decoration.Style.Underline(tint = highlightTint(annotation))
    else Decoration.Style.Highlight(tint = highlightTint(annotation))

/** What Copy puts on the clipboard: the passage, then the note when there is one. */
internal fun highlightCopyText(annotation: BookAnnotation): String =
    listOf(annotation.text.trim(), annotation.note.trim())
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")

/** The chapter title for a resource, from the table of contents, else its reading-order title. */
internal fun chapterTitle(publication: Publication, href: String): String {
    val path = resourcePath(href)
    fun find(links: List<Link>): String? = links.firstNotNullOfOrNull { link ->
        link.title?.takeIf { resourcePath(link.url().toString()) == path } ?: find(link.children)
    }
    return find(publication.tableOfContents)
        ?: publication.readingOrder
            .firstOrNull { resourcePath(it.url().toString()) == path }
            ?.title
            .orEmpty()
}

/** A note being written for a passage the owner selected. */
internal data class PendingNote(val passage: SelectedPassage, val chapter: String)

/** A passage waiting for the owner to pick its colour (Colour… in the selection menu). */
internal data class PendingHighlight(val passage: SelectedPassage, val chapter: String)

/** What the highlight sheet can do. */
internal class HighlightSheetActions(
    val onCopy: () -> Unit = {},
    val onColour: (HighlightColour) -> Unit = {},
    val onNote: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

/**
 * The sheet after tapping a highlight: the passage, its note, where it was made, its colour, and
 * Edit note (or Add note), Copy and Delete.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HighlightSheet(annotation: BookAnnotation, actions: HighlightSheetActions) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = FolioSpace.section)
            .padding(bottom = 32.dp)
            .testTag(HIGHLIGHT_SHEET_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        if (annotation.chapter.isNotBlank())
            Text(
                annotation.chapter,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        Text(
            annotation.text,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Serif,
        )
        if (annotation.note.isNotBlank()) {
            Spacer(Modifier.height(FolioSpace.small))
            Text(
                stringResource(R.string.reader_highlight_your_note),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(annotation.note, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            listOf(
                    stringResource(
                        if (annotation.deviceId == PHONE_ANNOTATION_DEVICE)
                            R.string.reader_highlight_from_phone
                        else R.string.reader_highlight_from_ereader
                    ),
                    annotation.date,
                )
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (annotation.kind != "bookmark") {
            Text(
                stringResource(R.string.reader_highlight_colour),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = FolioSpace.small),
            )
            HighlightColours(HighlightColour.of(annotation), actions.onColour)
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = FolioSpace.small),
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            FilledTonalButton(onClick = actions.onNote) {
                Icon(Icons.Outlined.EditNote, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpace.small))
                Text(
                    stringResource(
                        if (annotation.note.isBlank()) R.string.reader_highlight_add_note
                        else R.string.reader_highlight_edit_note
                    )
                )
            }
            OutlinedButton(onClick = actions.onCopy) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpace.small))
                Text(stringResource(R.string.reader_highlight_copy))
            }
            TextButton(
                onClick = actions.onDelete,
                colors =
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpace.small))
                Text(stringResource(R.string.reader_highlight_delete))
            }
        }
        Text(
            stringResource(R.string.reader_highlight_sync_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The five swatches: Yellow, Green, Blue, Pink and Underline. [selected] has a check. */
@Composable
internal fun HighlightColours(selected: HighlightColour?, onPick: (HighlightColour) -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag(HIGHLIGHT_COLOURS_TAG),
        horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
    ) {
        HighlightColour.entries.forEach { colour ->
            val label = stringResource(colourLabel(colour))
            val tint = Color(swatchTint(colour))
            val chosen = colour == selected
            Box(
                Modifier.size(48.dp)
                    .clip(CircleShape)
                    .then(
                        if (colour == HighlightColour.UNDERLINE)
                            Modifier.border(
                                BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
                                CircleShape,
                            )
                        else Modifier.background(tint)
                    )
                    .then(
                        if (chosen)
                            Modifier.border(
                                BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface),
                                CircleShape,
                            )
                        else Modifier
                    )
                    .selectable(selected = chosen, role = Role.RadioButton) { onPick(colour) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    chosen && colour != HighlightColour.UNDERLINE ->
                        Icon(Icons.Outlined.Check, null, tint = Color.Black.copy(alpha = 0.8f))
                    colour == HighlightColour.UNDERLINE ->
                        Text(
                            "A",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                }
            }
        }
    }
}

private fun colourLabel(colour: HighlightColour): Int =
    when (colour) {
        HighlightColour.YELLOW -> R.string.reader_highlight_yellow
        HighlightColour.GREEN -> R.string.reader_highlight_green
        HighlightColour.BLUE -> R.string.reader_highlight_blue
        HighlightColour.PINK -> R.string.reader_highlight_pink
        HighlightColour.UNDERLINE -> R.string.reader_highlight_underline
    }

/** Colour… from the selection menu: pick one, and the passage is highlighted in it. */
@Composable
internal fun HighlightColourSheet(passage: String, onPick: (HighlightColour) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = FolioSpace.section).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
    ) {
        Text(
            stringResource(R.string.reader_highlight_colour_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            passage,
            maxLines = 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Serif,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HighlightColours(null, onPick)
    }
}

/**
 * Add note or Edit note: the passage and a field for the note, starting from [initial]. Save stores
 * it (a blank note removes one); Cancel keeps nothing.
 */
@Composable
internal fun HighlightNoteDialog(
    passage: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
) {
    var note by rememberSaveable { mutableStateOf(initial) }
    val editing = initial.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(HIGHLIGHT_NOTE_DIALOG_TAG),
        title = {
            Text(
                stringResource(
                    if (editing) R.string.reader_note_edit_title else R.string.reader_note_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FolioSpace.gap)) {
                Text(
                    passage,
                    maxLines = 4,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Serif,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    note,
                    { note = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.reader_note_field)) },
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(note) },
                enabled = if (editing) note.trim() != initial.trim() else note.isNotBlank(),
            ) {
                Text(stringResource(R.string.reader_note_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_note_cancel)) }
        },
    )
}

private val sampleHighlight =
    BookAnnotation(
        id = "sample",
        bookId = "sample1",
        deviceId = "link:kindle",
        text =
            "In March the pruning began. Ada worked from the east end, where the frost lingered longest.",
        note = "Same order as the vineyard chapter.",
        chapter = "Chapter two",
        date = "2026-10-16 08:41:12",
        position = "/body/DocFragment[3]/body/p[2]/text().0",
        endPosition = "/body/DocFragment[3]/body/p[2]/text().91",
        kind = "note",
        color = "green",
        drawer = "lighten",
    )

@Preview(name = "Reader highlight · light", widthDp = 412, heightDp = 560)
@Composable
internal fun ReaderHighlightPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            HighlightSheet(sampleHighlight, HighlightSheetActions())
        }
    }
}

@Preview(name = "Reader highlight colour · light", widthDp = 412, heightDp = 260)
@Composable
internal fun ReaderHighlightColourPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            HighlightColourSheet(sampleHighlight.text, onPick = {})
        }
    }
}
