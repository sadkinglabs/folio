package dev.sadking.folio.reader

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import dev.sadking.folio.R
import dev.sadking.folio.data.BookAnnotation
import dev.sadking.folio.ui.FolioSpace
import dev.sadking.folio.ui.FolioTheme
import dev.sadking.folio.ui.Tooltipped
import dev.sadking.folio.ui.lift
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Highlight from here…" (M11): a highlight as long as the owner wants, over as many pages as it
 * takes, without holding a selection while the page turns. Holding a selection across a turn never
 * worked on the phone: the WebView's selection handles are windows of their own, and any other
 * touch ended their drag.
 *
 * 1. The owner selects the first word as usual (long-press) and chooses "Highlight from here…" in
 *    the selection menu. The selection's start and end are kept as KOReader pointers, as a
 *    highlight's are ([HighlightEnd]); the selection is cleared, a faint underline marks the start,
 *    and a bar says what to do next.
 * 2. Page turns work as always: tap zones, swipes, the bottom bar, the contents.
 * 3. The owner presses and holds the word where the highlight ends. The page selects that word, as
 *    a long-press always does, and the selection menu offers one item only: "End highlight here"
 *    ([selectionMenu]). Choosing it runs the highlight from the first word to the end of that one,
 *    whichever comes first in the book ([highlightSpan]), and saves it like any other (the last
 *    colour, synced to KOReader). Its sheet opens.
 *
 * A press and hold, not a tap, ends it: a tap still turns the page or shows the controls, as the
 * owner's tap zones say, so turning pages and ending the highlight can never be confused. The end
 * is read when its menu item is chosen, as Highlight from here… reads the start, not when the menu
 * opens: a menu with no items (the first design) never ended a highlight on the emulator.
 *
 * Both ends must be in one chapter. A chapter is one document; the next is another, which the
 * reader never had on screen together with this one, so it cannot build the pointers and text of a
 * passage running into it (or through chapters that were skipped) without guessing. A press in
 * another chapter says so and keeps the start, so the owner can go back
 * ([FromHereEnd.OtherChapter]).
 *
 * Cancel, Back, closing the book, or [FROM_HERE_IDLE_MS] without a touch ends the mode and removes
 * the start's underline.
 */

/** How long the mode waits without a touch or key before it stops by itself. */
internal const val FROM_HERE_IDLE_MS = 2 * 60_000L

/** The Readium decoration group for the start's underline. */
internal const val FROM_HERE_GROUP = "from-here"

/** The start's decoration id, in [FROM_HERE_GROUP]. */
internal const val FROM_HERE_MARK_ID = "from-here-start"

internal const val FROM_HERE_BAR_TAG = "reader-from-here-bar"

/**
 * One end the owner picked: the passage selected then, as KOReader pointers [pos0] to [pos1], in
 * chapter [chapter] (its KOReader DocFragment index, zero-based).
 */
internal data class HighlightEnd(val chapter: Int, val pos0: String, val pos1: String)

/** The highlight's start and end pointers, in reading order. */
internal data class HighlightSpan(val pos0: String, val pos1: String)

/**
 * The highlight from [start] to [end], both in one chapter, given how the page orders them:
 * [startsOrder] compares start.pos0 with end.pos0 and [endsOrder] start.pos1 with end.pos1 (-1
 * before, 0 same, 1 after). It begins where the earlier one begins and finishes where the later one
 * finishes, so both picked words are whole, whichever was picked first.
 */
internal fun highlightSpan(
    start: HighlightEnd,
    end: HighlightEnd,
    startsOrder: Int,
    endsOrder: Int,
): HighlightSpan =
    HighlightSpan(
        if (startsOrder <= 0) start.pos0 else end.pos0,
        if (endsOrder >= 0) start.pos1 else end.pos1,
    )

/** The page's two comparisons (see [orderCall]), or null when either is missing. */
internal fun parseOrder(raw: String?): Pair<Int, Int>? {
    val value = bridgeValue(raw) as? JSONArray ?: return null
    if (value.length() != 2 || value.isNull(0) || value.isNull(1)) return null
    val first = value.opt(0) as? Number ?: return null
    val second = value.opt(1) as? Number ?: return null
    return first.toInt().coerceIn(-1, 1) to second.toInt().coerceIn(-1, 1)
}

/** The page call that orders [start] against [end]: their starts, then their ends. */
internal fun orderCall(start: HighlightEnd, end: HighlightEnd): String =
    "JSON.stringify([FolioHighlights.compare(${JSONArray(listOf(start.pos0, end.pos0)).join(",")})," +
        " FolioHighlights.compare(${JSONArray(listOf(start.pos1, end.pos1)).join(",")})]);"

/** The page call that reads the passage [span] in chapter page, with [rows] as in selectionCall. */
internal fun passageCall(span: HighlightSpan, rows: List<BookAnnotation>) =
    "JSON.stringify(FolioHighlights.passage(" +
        "${JSONArray(listOf(span.pos0, span.pos1)).join(",")}, ${highlightItems(rows)}));"

/** The page call that places the start's underline: where [start] sits in the page on screen. */
internal fun markCall(start: HighlightEnd): String =
    "JSON.stringify(FolioHighlights.place([" +
        JSONObject().put("id", FROM_HERE_MARK_ID).put("pos0", start.pos0).put("pos1", start.pos1) +
        "]));"

/**
 * The items the selection menu offers for a new selection: all of [SELECTION_MENU] (a one-word
 * selection offers Define, as always), or only End highlight here while Highlight from here… waits
 * for its end, so that selection cannot open Define or anything else by accident.
 */
internal fun selectionMenu(endingHighlight: Boolean): List<Pair<Int, Int>> =
    if (endingHighlight) FROM_HERE_END_MENU else SELECTION_MENU

/** The selection menu while Highlight from here… waits for its end. */
internal val FROM_HERE_END_MENU = listOf(SELECTION_END_HERE to R.string.reader_from_here_end_action)

/** What End highlight here means while the mode waits for the end. */
internal sealed interface FromHereEnd {
    /** In the start's chapter: the highlight runs between [start] and [end]. */
    data class Here(val start: HighlightEnd, val end: HighlightEnd) : FromHereEnd

    /** In another chapter: refused, and the mode still waits. */
    data object OtherChapter : FromHereEnd
}

/**
 * The mode's state: idle, waiting for the end (from [begin] on, through any number of page turns),
 * or saving. Pure apart from Compose state, so the bar follows it; [clock] is in milliseconds.
 */
internal class HighlightFromHere(
    private val idleMs: Long = FROM_HERE_IDLE_MS,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    /** The start, while the mode is on (waiting or saving). */
    var start by mutableStateOf<HighlightEnd?>(null)
        private set

    /** True between a press that ends the highlight and [finished] or [failed]. */
    var saving by mutableStateOf(false)
        private set

    private var lastTouch = 0L

    /** Whether the mode is on: the bar shows, and a press and hold offers End highlight here. */
    val active: Boolean
        get() = start != null

    /** Starts the mode at [from], or starts it again there. */
    fun begin(from: HighlightEnd) {
        start = from
        saving = false
        lastTouch = clock()
    }

    /** A touch or key: the idle time starts again. */
    fun touched() {
        lastTouch = clock()
    }

    /** How long until the mode stops by itself, from now; 0 once it is due. */
    fun untilIdle(): Long = (lastTouch + idleMs - clock()).coerceAtLeast(0)

    /** Whether the mode has gone [idleMs] without a touch and should stop. Never while saving. */
    fun idle(): Boolean = active && !saving && untilIdle() == 0L

    /**
     * End highlight here picked [end]. Null when the mode is off or already saving (a second choice
     * meanwhile does nothing). [FromHereEnd.Here] starts saving.
     */
    fun end(end: HighlightEnd): FromHereEnd? {
        val from = start ?: return null
        if (saving) return null
        touched()
        if (end.chapter != from.chapter) return FromHereEnd.OtherChapter
        saving = true
        return FromHereEnd.Here(from, end)
    }

    /** The highlight was saved (or found already there): the mode ends. */
    fun finished() = cancel()

    /** Saving failed: the mode waits for the end again, from the same start. */
    fun failed() {
        saving = false
        touched()
    }

    /** Ends the mode without a highlight. */
    fun cancel() {
        start = null
        saving = false
    }
}

/**
 * The bar shown while the mode waits for the end: what to do next, and ✕ to cancel. The same raised
 * card as the Folio Link offer, so the reader's floating notices look alike.
 */
@Composable
internal fun HighlightFromHereBar(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier
            .fillMaxWidth()
            .then(lift(FolioSpace.panelShape))
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(FROM_HERE_BAR_TAG),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(start = FolioSpace.item, end = FolioSpace.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.reader_from_here_bar),
                Modifier.weight(1f).padding(vertical = FolioSpace.item),
                style = MaterialTheme.typography.bodyLarge,
            )
            val cancel = stringResource(R.string.reader_from_here_cancel)
            Tooltipped(cancel) {
                IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, cancel) }
            }
        }
    }
}

@Preview(name = "Highlight from here bar · light", widthDp = 412, heightDp = 120)
@Composable
internal fun HighlightFromHereBarPreview() {
    FolioTheme(dynamic = false) {
        Box(Modifier.fillMaxSize().padding(FolioSpace.item)) {
            HighlightFromHereBar(onCancel = {})
        }
    }
}
