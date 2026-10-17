package dev.sadking.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.core.NotePlace
import dev.sadking.folio.data.BookAnnotation
import dev.sadking.folio.data.HighlightColour
import dev.sadking.folio.data.PhoneBookmark
import dev.sadking.folio.data.UNDERSCORE
import dev.sadking.folio.ui.FolioSearchBar
import dev.sadking.folio.ui.FolioSpace
import dev.sadking.folio.ui.Tooltipped
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Highlights & notes (M11 C): the second tab of the Contents sheet lists the book's highlights,
 * notes and bookmarks from every device, in reading order. Chips filter them (all, highlights,
 * notes, bookmarks), a field searches their passages and notes, a tap jumps there, and each row's
 * menu changes it the way the highlight sheet does, with Undo. Export writes them as Markdown.
 */
internal const val READER_NOTES_TAG = "reader-notes"
internal const val READER_NOTES_SEARCH_TAG = "reader-notes-search"
internal const val NOTES_EXPORT_TAG = "notes-export"

internal fun noteRowTag(id: String) = "reader-note-$id"

/** The chips over the list. */
internal enum class NotesFilter {
    ALL,
    HIGHLIGHTS,
    NOTES,
    BOOKMARKS,
}

/**
 * One row: a highlight, note or e-reader bookmark ([annotation]), or a phone bookmark ([mark]).
 * [passage] is the highlighted text, or a bookmark's name; [date] is KOReader's `YYYY-MM-DD
 * HH:MM:SS`.
 */
internal data class NoteItem(
    val id: String,
    val bookmark: Boolean,
    val passage: String,
    val note: String,
    val chapter: String,
    val date: String,
    val place: NotePlace,
    val annotation: BookAnnotation? = null,
    val mark: PhoneBookmark? = null,
)

private val KOREADER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

/**
 * The list's rows in reading order: the book's live annotations ([highlights], deleted ones are
 * never there) and the phone's bookmarks ([marks]). A phone bookmark is placed in its chapter by
 * the book's [spine]; one outside it goes last.
 */
internal fun noteItems(
    highlights: List<BookAnnotation>,
    marks: List<PlacedBookmark>,
    spine: List<String>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<NoteItem> {
    val rows =
        highlights
            .filter { !it.deleted }
            .map { row ->
                NoteItem(
                    row.id,
                    row.kind == "bookmark",
                    row.text.trim().ifEmpty { if (row.kind == "bookmark") row.chapter else "" },
                    row.note.trim(),
                    row.chapter,
                    row.date,
                    NotePlace.of(row.position),
                    annotation = row,
                )
            }
    val phone = marks.map { placed ->
        val locator = placed.locator
        val index = locator?.let { docFragmentIndex(it.href.toString(), spine) }
        val place =
            if (index != null) NotePlace(index + 1, progression = locator.locations.progression)
            else NotePlace(progression = locator?.locations?.totalProgression)
        NoteItem(
            placed.mark.id,
            true,
            placed.mark.title,
            "",
            "",
            KOREADER_TIME.format(Instant.ofEpochMilli(placed.mark.createdAt).atZone(zone)),
            place,
            mark = placed.mark,
        )
    }
    return (rows + phone).sortedWith(
        compareBy(NotePlace.ORDER) { item: NoteItem -> item.place }.thenBy { it.date }
    )
}

/** True when [item] belongs under [filter]. A note is any row the owner wrote a note on. */
internal fun inFilter(item: NoteItem, filter: NotesFilter): Boolean =
    when (filter) {
        NotesFilter.ALL -> true
        NotesFilter.HIGHLIGHTS -> !item.bookmark
        NotesFilter.NOTES -> item.note.isNotBlank()
        NotesFilter.BOOKMARKS -> item.bookmark
    }

/** Lower case without accents, so "cafe" finds "Café". */
internal fun searchFold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)

/**
 * The rows under [filter] whose passage or note holds every word of [query], in any case and with
 * or without accents. A blank query keeps them all.
 */
internal fun filterNotes(
    items: List<NoteItem>,
    filter: NotesFilter,
    query: String,
): List<NoteItem> {
    val words = searchFold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    return items.filter { item ->
        inFilter(item, filter) &&
            (words.isEmpty() ||
                searchFold(item.passage + "\n" + item.note).let { text ->
                    words.all { it in text }
                })
    }
}

/** "Oct 16, 2026", in the phone's language, from KOReader's time; the text itself otherwise. */
internal fun noteDay(date: String, locale: Locale = Locale.getDefault()): String =
    runCatching {
            LocalDate.parse(date.trim().take(10))
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        }
        .getOrDefault(date.trim())

/** What a row can do. Every highlight change goes through part A's view-model functions. */
internal class NotesActions(
    val onOpen: (NoteItem) -> Unit = {},
    val onColour: (BookAnnotation, HighlightColour) -> Unit = { _, _ -> },
    val onNote: (BookAnnotation) -> Unit = {},
    val onCopy: (BookAnnotation) -> Unit = {},
    val onDelete: (NoteItem) -> Unit = {},
    val onRename: (PhoneBookmark) -> Unit = {},
)

/**
 * The export menu's actions. [folder] is the remembered folder's name ("" when it has none), or
 * null when no folder is remembered yet: then Save asks for one first.
 */
class ExportActions(
    val folder: String? = null,
    val onShare: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onChooseFolder: () -> Unit = {},
)

/**
 * The Highlights & notes tab: search, filter chips with counts, and the rows. [initialFilter] is
 * for previews and tests.
 */
@Composable
internal fun ReaderNotesList(
    items: List<NoteItem>,
    actions: NotesActions,
    export: ExportActions,
    initialFilter: NotesFilter = NotesFilter.ALL,
) {
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(items, filter, query) { filterNotes(items, filter, query) }
    Column(Modifier.fillMaxWidth().testTag(READER_NOTES_TAG)) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = FolioSpace.item, end = FolioSpace.small, top = FolioSpace.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FolioSearchBar(
                query,
                { query = it },
                stringResource(R.string.notes_search),
                stringResource(R.string.notes_search_clear),
                modifier = Modifier.weight(1f),
                fieldModifier = Modifier.testTag(READER_NOTES_SEARCH_TAG),
                onSheet = true,
            )
            NotesExportButton(export, enabled = items.isNotEmpty(), iconOnly = true)
        }
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = FolioSpace.item, vertical = FolioSpace.small),
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            NotesFilter.entries.forEach { option ->
                val count = items.count { inFilter(it, option) }
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = {
                        Text(
                            stringResource(
                                R.string.notes_filter_count,
                                stringResource(filterLabel(option)),
                                count,
                            )
                        )
                    },
                )
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 460.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            if (shown.isEmpty())
                item {
                    Text(
                        stringResource(
                            when {
                                items.isEmpty() -> R.string.notes_empty
                                query.isNotBlank() -> R.string.notes_no_match
                                filter == NotesFilter.HIGHLIGHTS -> R.string.notes_empty_highlights
                                filter == NotesFilter.NOTES -> R.string.notes_empty_notes
                                filter == NotesFilter.BOOKMARKS -> R.string.bookmarks_empty
                                else -> R.string.notes_empty
                            }
                        ),
                        Modifier.padding(
                            horizontal = FolioSpace.section,
                            vertical = FolioSpace.item,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            items(shown, key = { it.id }) { item -> NoteRow(item, actions) }
        }
    }
}

private fun filterLabel(filter: NotesFilter): Int =
    when (filter) {
        NotesFilter.ALL -> R.string.notes_filter_all
        NotesFilter.HIGHLIGHTS -> R.string.notes_filter_highlights
        NotesFilter.NOTES -> R.string.notes_filter_notes
        NotesFilter.BOOKMARKS -> R.string.notes_filter_bookmarks
    }

/**
 * A row: its colour, the passage (three lines at most), the note, then the chapter and day. A tap
 * jumps there; the menu changes or deletes it; a swipe to the left deletes it too.
 */
@Composable
private fun NoteRow(item: NoteItem, actions: NotesActions) {
    val dismiss = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismiss.currentValue) {
        if (dismiss.currentValue == SwipeToDismissBoxValue.EndToStart) actions.onDelete(item)
    }
    SwipeToDismissBox(
        dismiss,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    null,
                    Modifier.padding(horizontal = FolioSpace.section),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(role = Role.Button) { actions.onOpen(item) }
                .testTag(noteRowTag(item.id))
                .padding(start = FolioSpace.section, end = FolioSpace.small)
                .padding(vertical = FolioSpace.gap),
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        ) {
            NoteMarker(item, Modifier.padding(top = 4.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.passage.isNotBlank())
                    Text(
                        item.passage,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = if (item.mark == null) FontFamily.Serif else null,
                    )
                if (item.note.isNotBlank())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            Icons.Outlined.EditNote,
                            null,
                            Modifier.size(18.dp).padding(top = 2.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            item.note,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                val meta =
                    listOf(item.chapter.takeIf { it != item.passage }.orEmpty(), noteDay(item.date))
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                if (meta.isNotEmpty())
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            NoteMenu(item, actions)
        }
    }
}

/** The swatch in the highlight's colour, a line for an underline, or a ribbon for a bookmark. */
@Composable
private fun NoteMarker(item: NoteItem, modifier: Modifier = Modifier) {
    val row = item.annotation
    if (item.bookmark || row == null) {
        Icon(
            Icons.Outlined.BookmarkBorder,
            stringResource(R.string.notes_bookmark),
            modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        return
    }
    val colour = HighlightColour.of(row)
    val label = colour?.let { stringResource(colourName(it)) }
    val tint = Color(highlightTint(row))
    val described =
        if (label != null) Modifier.semantics { contentDescription = label } else Modifier
    if (row.drawer == UNDERSCORE)
        Box(modifier.then(described).size(18.dp), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(tint, CircleShape))
        }
    else Box(modifier.then(described).size(18.dp).clip(CircleShape).background(tint))
}

private fun colourName(colour: HighlightColour): Int =
    when (colour) {
        HighlightColour.YELLOW -> R.string.reader_highlight_yellow
        HighlightColour.GREEN -> R.string.reader_highlight_green
        HighlightColour.BLUE -> R.string.reader_highlight_blue
        HighlightColour.PINK -> R.string.reader_highlight_pink
        HighlightColour.UNDERLINE -> R.string.reader_highlight_underline
    }

/**
 * The row's menu. A highlight: its colours, Add or Edit note, Copy and Delete. An e-reader
 * bookmark: Add or Edit note and Delete. A phone bookmark: Rename and Delete.
 */
@Composable
private fun NoteMenu(item: NoteItem, actions: NotesActions) {
    var open by remember { mutableStateOf(false) }
    val row = item.annotation
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.notes_options))
        }
        DropdownMenu(open, { open = false }) {
            if (row != null && !item.bookmark)
                Box(Modifier.padding(horizontal = FolioSpace.gap, vertical = FolioSpace.small)) {
                    HighlightColours(HighlightColour.of(row)) {
                        open = false
                        actions.onColour(row, it)
                    }
                }
            if (row != null)
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (row.note.isBlank()) R.string.reader_highlight_add_note
                                else R.string.reader_highlight_edit_note
                            )
                        )
                    },
                    onClick = {
                        open = false
                        actions.onNote(row)
                    },
                    leadingIcon = { Icon(Icons.Outlined.EditNote, null) },
                )
            if (row != null && !item.bookmark)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reader_highlight_copy)) },
                    onClick = {
                        open = false
                        actions.onCopy(row)
                    },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                )
            item.mark?.let { mark ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.notes_rename)) },
                    onClick = {
                        open = false
                        actions.onRename(mark)
                    },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.reader_highlight_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    open = false
                    actions.onDelete(item)
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
    }
}

/**
 * Export, with its menu: Share as Markdown, Save to the remembered folder (or Save to a folder…,
 * which asks for one), and Choose another folder… once one is remembered. [iconOnly] shows only the
 * icon, with Export as its name and tooltip.
 */
@Composable
internal fun NotesExportButton(
    export: ExportActions,
    enabled: Boolean = true,
    iconOnly: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.notes_export)
    Box(Modifier.testTag(NOTES_EXPORT_TAG)) {
        if (iconOnly)
            Tooltipped(label) {
                IconButton(onClick = { open = true }, enabled = enabled) {
                    Icon(Icons.Outlined.IosShare, label)
                }
            }
        else
            TextButton(
                onClick = { open = true },
                enabled = enabled,
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(Icons.Outlined.IosShare, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpace.small))
                Text(label)
            }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.notes_export_share)) },
                onClick = {
                    open = false
                    export.onShare()
                },
                leadingIcon = { Icon(Icons.Outlined.Share, null) },
            )
            val folder = export.folder
            DropdownMenuItem(
                text = {
                    Text(
                        when {
                            folder == null -> stringResource(R.string.notes_export_save_folder)
                            folder.isEmpty() -> stringResource(R.string.notes_export_save_chosen)
                            else -> stringResource(R.string.notes_export_save_to, folder)
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                onClick = {
                    open = false
                    export.onSave()
                },
                leadingIcon = { Icon(Icons.Outlined.Folder, null) },
            )
            if (folder != null)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.notes_export_change_folder)) },
                    onClick = {
                        open = false
                        export.onChooseFolder()
                    },
                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                )
        }
    }
}

/**
 * Offers Undo in [host] for [key] (something just deleted) while it is set: [onUndo] when Undo is
 * tapped, else [onSettled] when the offer ends. [host] is read when the offer starts, so it shows
 * over the sheet that is open then.
 */
@Composable
internal fun <T : Any> UndoOffer(
    key: T?,
    message: String,
    undo: String,
    host: () -> SnackbarHostState,
    duration: SnackbarDuration,
    onUndo: (T) -> Unit,
    onSettled: () -> Unit,
) {
    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        val result = host().showSnackbar(message, actionLabel = undo, duration = duration)
        if (result == SnackbarResult.ActionPerformed) onUndo(key) else onSettled()
    }
}
