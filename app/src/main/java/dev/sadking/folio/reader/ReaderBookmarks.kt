package dev.sadking.folio.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.LibraryDao
import dev.sadking.folio.data.PhoneBookmark
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

internal const val READER_BOOKMARK_RIBBON_TAG = "reader-bookmark-ribbon"
internal const val READER_BOOKMARK_RENAME_TAG = "reader-bookmark-rename"

/** The longest bookmark name kept; a longer one is cut. */
internal const val BOOKMARK_TITLE_MAX = 200

/**
 * How much of a chapter one screen shows, as a fraction of it, measured in the page itself. It is
 * the width of a screen of columns over the chapter's width, or the screen's height over the
 * chapter's height when scrolling. Readium's progression is the same ratio for the page's start.
 */
internal fun pageSpanScript(scrolling: Boolean): String =
    "(function(){var e=document.scrollingElement;if(!e)return null;" +
        (if (scrolling) "return window.innerHeight/e.scrollHeight;"
        else "return window.innerWidth/e.scrollWidth;") +
        "})()"

/** The screen's share of the chapter at [path], once measured; see [pageSpanScript]. */
internal data class PageSpan(val path: String, val span: Double)

/** A bookmark with its stored place, read once. Null when the stored place does not parse. */
internal class PlacedBookmark(val mark: PhoneBookmark, val locator: Locator?)

internal fun placed(mark: PhoneBookmark) =
    PlacedBookmark(
        mark,
        runCatching { Locator.fromJSON(JSONObject(mark.locator)) }.getOrNull(),
    )

/** Two progressions this close are the same page start. */
private const val SAME_PLACE = 1e-6

/**
 * True when [mark] falls on the page now shown, which starts at [page]. EPUB pages compare where
 * they start in the chapter: with the screen's [span] known, a bookmark anywhere on the screen
 * counts, so one made at another text size still shows; without it, only the same page start does.
 * PDFs and comics ([byPosition]) compare page numbers.
 */
internal fun bookmarkOnPage(
    mark: Locator,
    page: Locator,
    span: Double?,
    byPosition: Boolean = false,
): Boolean {
    if (resourcePath(mark.href.toString()) != resourcePath(page.href.toString())) return false
    if (byPosition) {
        val at = mark.locations.position
        return at != null && at == page.locations.position
    }
    val m = mark.locations.progression ?: return false
    val p = page.locations.progression ?: return false
    if (span != null && span > 0.0) {
        // A hair of tolerance either side, for the rounding of a page start in the page script.
        val tolerance = span * 0.01
        return m >= p - tolerance && m < p + span - tolerance
    }
    return kotlin.math.abs(m - p) < SAME_PLACE
}

/** "Chapter one · 34%": the chapter, when known, and how far into the book. */
internal fun bookmarkTitle(locator: Locator): String =
    listOfNotNull(
            locator.title?.takeIf { it.isNotBlank() },
            "${((locator.locations.totalProgression ?: 0.0) * 100).toInt()}%",
        )
        .joinToString(" · ")

/** What a tap on the top bar's bookmark did. */
internal sealed interface BookmarkChange {
    data class Added(val mark: PhoneBookmark) : BookmarkChange

    /** Every bookmark on the page was removed; they are kept here for Undo. */
    data class Removed(val marks: List<PhoneBookmark>) : BookmarkChange
}

/**
 * The phone's bookmarks for the open book. Bookmarks stay on the phone: they are not synced. A
 * removal returns the rows so Undo can put back the very same bookmarks, names and dates included.
 */
internal class BookmarkStore(
    private val dao: LibraryDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    /**
     * Removes the bookmarks on the page at [page], or adds one there when it has none. It reads the
     * stored bookmarks afresh under a lock, so two quick taps never add the same page twice.
     */
    suspend fun toggle(
        book: Book,
        page: Locator,
        span: Double?,
        byPosition: Boolean,
    ): BookmarkChange = lock.withLock {
        val here =
            dao.phoneBookmarks(book.id, book.currentHash).first().filter { mark ->
                placed(mark).locator?.let { bookmarkOnPage(it, page, span, byPosition) } == true
            }
        if (here.isNotEmpty()) {
            here.forEach { dao.deletePhoneBookmark(it.id) }
            BookmarkChange.Removed(here)
        } else {
            val mark =
                PhoneBookmark(
                    UUID.randomUUID().toString(),
                    book.id,
                    book.currentHash,
                    page.toJSON().toString(),
                    bookmarkTitle(page),
                    now(),
                )
            dao.addPhoneBookmark(mark)
            BookmarkChange.Added(mark)
        }
    }

    /** Puts removed bookmarks back as they were. One already back is left alone. */
    suspend fun restore(marks: List<PhoneBookmark>) = lock.withLock {
        marks.forEach { dao.addPhoneBookmark(it) }
    }

    /** Renames a bookmark. A blank name changes nothing and returns false. */
    suspend fun rename(id: String, title: String): Boolean {
        val name = cleanBookmarkTitle(title) ?: return false
        lock.withLock { dao.renamePhoneBookmark(id, name) }
        return true
    }

    suspend fun delete(id: String) = lock.withLock { dao.deletePhoneBookmark(id) }
}

/** A bookmark name as stored: one line, trimmed, at most [BOOKMARK_TITLE_MAX]; null when blank. */
internal fun cleanBookmarkTitle(title: String): String? =
    title.replace(Regex("\\s+"), " ").trim().take(BOOKMARK_TITLE_MAX).takeIf { it.isNotEmpty() }

/** The ribbon's colour on a light or sepia page, and on a dark one. */
private val RIBBON_LIGHT = Color(0xFFB3261E)
private val RIBBON_DARK = Color(0xFFE8A39C)

/**
 * The small ribbon hanging from the top of a bookmarked page. Drawn over the page, never into the
 * book, and silent to TalkBack: the top bar's bookmark button says the page is bookmarked.
 */
@Composable
internal fun BookmarkRibbon(pageDark: Boolean, modifier: Modifier = Modifier) {
    val colour = (if (pageDark) RIBBON_DARK else RIBBON_LIGHT).copy(alpha = 0.85f)
    Canvas(
        modifier
            .size(width = 14.dp, height = 24.dp)
            .testTag(READER_BOOKMARK_RIBBON_TAG)
            .clearAndSetSemantics {}
    ) {
        val notch = size.height * 0.75f
        val path =
            Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(size.width / 2, notch)
                lineTo(0f, size.height)
                close()
            }
        drawPath(path, colour)
        drawLine(
            colour.copy(alpha = 1f),
            Offset(0f, 0f),
            Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

/**
 * The name field of [RenameBookmarkDialog], one line, with Done saving. Apart from the dialog so it
 * can be tested on the JVM, where Robolectric never settles a text field inside a dialog.
 */
@Composable
internal fun RenameBookmarkField(
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    onDone: () -> Unit,
) {
    OutlinedTextField(
        value,
        { onValue(it.copy(text = it.text.take(BOOKMARK_TITLE_MAX))) },
        Modifier.testTag(READER_BOOKMARK_RENAME_TAG),
        label = { Text(stringResource(R.string.bookmark_rename_label)) },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

/** Renames a bookmark: its current name, with the cursor at the end. */
@Composable
internal fun RenameBookmarkDialog(
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember {
        mutableStateOf(TextFieldValue(current, selection = TextRange(current.length)))
    }
    val valid = cleanBookmarkTitle(text.text) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmark_rename)) },
        text = {
            RenameBookmarkField(text, { text = it }, onDone = { if (valid) onSave(text.text) })
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.text) }, enabled = valid) {
                Text(stringResource(R.string.bookmark_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.bookmark_rename_cancel))
            }
        },
    )
}
