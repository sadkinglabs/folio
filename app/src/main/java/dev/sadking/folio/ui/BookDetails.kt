@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.sadking.folio.ui

import android.content.SharedPreferences
import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.R
import dev.sadking.folio.data.*
import dev.sadking.folio.reader.ReaderKind
import dev.sadking.folio.reader.ReaderStart
import dev.sadking.folio.reader.koreaderDigest
import dev.sadking.folio.reader.opensOnPhone
import dev.sadking.folio.reader.readerKind
import dev.sadking.folio.sync.HardcoverItemState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

internal const val BOOK_PAGE_TAG = "book-page"
internal const val BOOK_PRIMARY_TAG = "book-primary"
internal const val BOOK_READ_MENU_TAG = "book-read-menu"
internal const val BOOK_ACTION_ATTACH_TAG = "book-action-attach"
internal const val BOOK_ACTION_EDIT_TAG = "book-action-edit"
internal const val BOOK_ACTION_PROGRESS_TAG = "book-action-progress"
internal const val BOOK_ACTION_HARDCOVER_TAG = "book-action-hardcover"
internal const val BOOK_ACTION_SHARE_TAG = "book-action-share"
internal const val BOOK_ACTION_REMOVE_TAG = "book-action-remove"
internal const val BOOK_STATUS_STRIP_TAG = "book-status-strip"
internal const val BOOK_LISTS_ADD_TAG = "book-lists-add"
internal const val BOOK_OVERFLOW_TAG = "book-overflow"
internal const val PROGRESS_SHEET_TAG = "progress-sheet"
internal const val BOOK_FILE_MISSING_TAG = "book-file-missing"
internal const val BOOK_CHANGED_OUTSIDE_TAG = "book-changed-outside"

/**
 * The one book page, for books with and without an EPUB. A record ([hasEpub] false) gets the same
 * page with fewer actions: Get this book instead of Read, Attach, no Share, and a status strip with
 * Hardcover only. Sync puts every book on the e-reader by itself, so there is no Send.
 *
 * Busy rules: a running library operation ([busy]) disables changes. Reading is never blocked. A
 * book whose file is gone says so above Read, and Share waits for the file.
 */
@Composable
fun BookDetails(
    book: Book,
    hasEpub: Boolean = true,
    lists: List<String> = emptyList(),
    signals: BookSignals? = null,
    phoneReading: PhoneReading? = null,
    reading: ReadingSnapshot? = null,
    /** Every old SSH position of the book, one per saved connection; [reading] by default. */
    snapshots: List<ReadingSnapshot> = listOfNotNull(reading),
    hardcoverLinked: Boolean = signals?.hardcover != null,
    hardcoverPage: Int? = null,
    /** What the Hardcover tile says; null: Matched or Not matched, from [hardcoverLinked]. */
    hardcoverState: HardcoverItemState? = null,
    annotations: List<BookAnnotation> = emptyList(),
    nextInSeries: Book? = null,
    busy: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(),
    /** The book's file is gone. Null checks the disk off the main thread; previews pass a value. */
    fileMissing: Boolean? = null,
    /** The folder scan took a new version made outside Librillo. Null reads [FolderScanNotes]. */
    changedOutside: Boolean? = null,
    onRead: () -> Unit = {},
    /** The Read menu's other starts: from the beginning, read aloud, or an e-reader's place. */
    onReadFrom: (ReaderStart) -> Unit = {},
    onAttach: () -> Unit = {},
    onEdit: () -> Unit = {},
    onHardcover: () -> Unit = {},
    /** The Hardcover tile while a match waits for the owner: opens Needs your decision. */
    onCheckMatch: () -> Unit = {},
    onShare: () -> Unit = {},
    onRemove: () -> Unit = {},
    onLists: () -> Unit = {},
    onStatus: (String) -> Unit = {},
    onLookup: () -> Unit = {},
    onExport: () -> Unit = {},
    onNext: (Book) -> Unit = {},
    /** Content under the status strip; a record puts its Hardcover offer here. */
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val reports = if (hasEpub) linkReports(book) else emptyList()
    val devices = LocalDevices.current
    val entries =
        remember(
            hasEpub,
            phoneReading,
            snapshots,
            reports,
            hardcoverLinked,
            hardcoverPage,
            devices,
        ) {
            progressEntries(
                hasEpub,
                phoneReading,
                snapshots.takeIf { hasEpub }.orEmpty(),
                reports,
                hardcoverLinked,
                hardcoverPage,
                devices,
            )
        }
    var progressSheet by rememberSaveable(book.id) { mutableStateOf(false) }
    val missing = rememberFileMissing(book, hasEpub, fileMissing)
    val (changed, clearChanged) = rememberChangedOutside(book.id, changedOutside)
    // Opening the book is when the "changed outside Librillo" note has done its job.
    val read = {
        clearChanged()
        onRead()
    }
    val readFrom: (ReaderStart) -> Unit = {
        clearChanged()
        onReadFrom(it)
    }
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .testTag(BOOK_PAGE_TAG)
    ) {
        CoverHero(book)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = FolioSpace.page),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.section),
        ) {
            if (missing)
                BookNotice(
                    Icons.Outlined.SearchOff,
                    stringResource(R.string.m9ui_file_missing),
                    stringResource(R.string.m9ui_file_missing_body),
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                    BOOK_FILE_MISSING_TAG,
                )
            else if (changed)
                BookNotice(
                    Icons.Outlined.Info,
                    stringResource(R.string.m9ui_changed_outside),
                    stringResource(R.string.m9ui_changed_outside_body),
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                    BOOK_CHANGED_OUTSIDE_TAG,
                )
            val readLabel =
                when (bookPrimary(hasEpub, phoneReading)) {
                    BookPrimary.CONTINUE ->
                        stringResource(
                            R.string.book_continue,
                            ((phoneReading?.percent ?: 0.0) * 100).toInt(),
                        )
                    BookPrimary.READ -> stringResource(R.string.book_read)
                    BookPrimary.FIND_EPUB -> stringResource(R.string.rd_find_epub)
                }
            val starts =
                readStarts(
                    bookPrimary(hasEpub, phoneReading) == BookPrimary.CONTINUE,
                    reports,
                    epub = readerKind(book.format) == ReaderKind.EPUB,
                )
            if (hasEpub && !opensOnPhone(book.format))
                // The phone cannot show this format; the book is still kept and synced.
                Button(
                    onClick = {},
                    enabled = false,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(BOOK_PRIMARY_TAG),
                ) {
                    Text(stringResource(R.string.book_m9_opens_on_ereader))
                }
            else if (hasEpub && starts.isNotEmpty())
                ReadSplitButton(readLabel, starts, onRead = read, onReadFrom = readFrom)
            else
                Button(
                    onClick = read,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(BOOK_PRIMARY_TAG),
                ) {
                    Text(readLabel)
                }
            ActionRow(
                bookPageActions(hasEpub).map { action ->
                    when (action) {
                        BookPageAction.ATTACH ->
                            IconAction(
                                Icons.Outlined.AttachFile,
                                stringResource(R.string.rd_action_attach),
                                enabled = !busy,
                                tag = BOOK_ACTION_ATTACH_TAG,
                                onClick = onAttach,
                            )
                        BookPageAction.EDIT ->
                            IconAction(
                                Icons.Outlined.Edit,
                                stringResource(R.string.rd_action_edit),
                                enabled = !busy,
                                tag = BOOK_ACTION_EDIT_TAG,
                                onClick = onEdit,
                            )
                        BookPageAction.PROGRESS ->
                            IconAction(
                                Icons.Outlined.Timeline,
                                stringResource(R.string.rd_action_progress),
                                enabled = !busy,
                                tag = BOOK_ACTION_PROGRESS_TAG,
                            ) {
                                progressSheet = true
                            }
                        BookPageAction.HARDCOVER ->
                            IconAction(
                                Icons.Outlined.Link,
                                stringResource(R.string.rd_action_hardcover),
                                enabled = !busy,
                                tag = BOOK_ACTION_HARDCOVER_TAG,
                                onClick = onHardcover,
                            )
                        BookPageAction.SHARE ->
                            IconAction(
                                Icons.Outlined.Share,
                                stringResource(R.string.rd_action_share),
                                enabled = !busy && !missing,
                                tag = BOOK_ACTION_SHARE_TAG,
                                onClick = onShare,
                            )
                        BookPageAction.REMOVE ->
                            IconAction(
                                Icons.Outlined.DeleteOutline,
                                stringResource(R.string.rd_action_remove),
                                enabled = !busy,
                                destructive = true,
                                tag = BOOK_ACTION_REMOVE_TAG,
                                onClick = onRemove,
                            )
                    }
                }
            )
            StatusStrip(
                statusTiles(
                    hasEpub,
                    entries,
                    hardcoverState
                        ?: if (hardcoverLinked) HardcoverItemState.MATCHED
                        else HardcoverItemState.OFF,
                    onCheckMatch,
                ),
                onClick = { progressSheet = true },
            )
            reading
                ?.takeIf { hasEpub && it.documentHash != book.currentHash }
                ?.let {
                    Text(
                        stringResource(R.string.library_book_other_version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            extra()
            ListChips(lists, onLists, enabled = !busy)
            if (book.description.isNotBlank()) Description(book)
            if (nextInSeries != null) NextInSeries(book, nextInSeries) { onNext(nextInSeries) }
            DetailsSection(book, busy, onLookup)
            if (hasEpub) NotesSection(annotations, busy, onExport)
            Spacer(Modifier.height(FolioSpace.item))
        }
    }
    if (progressSheet)
        ProgressSheet(
            book = book,
            hasEpub = hasEpub,
            entries = entries,
            busy = busy,
            onStatus = onStatus,
            onDismiss = { progressSheet = false },
        )
}

/** A tonal note on the book page: an icon, a short title and one plain sentence. */
@Composable
private fun BookNotice(
    icon: ImageVector,
    title: String,
    body: String,
    container: Color,
    content: Color,
    tag: String,
) {
    Surface(
        Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {},
        shape = FolioSpace.panelShape,
        color = container,
        contentColor = content,
    ) {
        Row(
            Modifier.padding(FolioSpace.item),
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        ) {
            Icon(icon, null, Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Whether the book's recorded file is gone, checked off the main thread when the page opens, when
 * Librillo comes back, and when the path changes. [override] fixes the answer for previews and
 * tests.
 */
@Composable
private fun rememberFileMissing(book: Book, hasEpub: Boolean, override: Boolean?): Boolean {
    val inspection = LocalInspectionMode.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(book.id) {
        resumes++
        onPauseOrDispose {}
    }
    val checked by
        produceState(false, book.currentPath, hasEpub, resumes) {
            value =
                if (override != null || !hasEpub || inspection) false
                else withContext(Dispatchers.IO) { bookFileMissing(book) }
        }
    return override ?: checked
}

/**
 * Whether the folder scan flagged this book as changed outside Librillo, and a way to clear the
 * flag once the book is opened. Follows [FolderScanNotes] while the page is shown, so a scan that
 * ends meanwhile shows up. [override] fixes the answer for previews and tests.
 */
@Composable
private fun rememberChangedOutside(bookId: String, override: Boolean?): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    val live = override == null && !LocalInspectionMode.current
    var changed by remember(bookId) { mutableStateOf(false) }
    if (live)
        LaunchedEffect(bookId) {
            val prefs =
                withContext(Dispatchers.IO) {
                    FolderScanNotes.preferences(context).also { FolderScanNotes.changedIds(it) }
                }
            changed = bookId in FolderScanNotes.changedIds(prefs)
            // Held here, strongly: SharedPreferences keeps listeners only weakly.
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                if (key == null || key == FolderScanNotes.CHANGED_IDS)
                    changed = bookId in FolderScanNotes.changedIds(p)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            try {
                awaitCancellation()
            } finally {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }
    val clear: () -> Unit = {
        if (live && changed)
            FolderScanNotes.clearChanged(FolderScanNotes.preferences(context), bookId)
        changed = false
    }
    return (override ?: changed) to clear
}

/**
 * The cover, 160 dp and lifted, over a soft radial glow in the cover's own tone; below it the title
 * in serif, the author and the series. The cover settles in with a short scale and fade.
 */
@Composable
internal fun CoverHero(book: Book, modifier: Modifier = Modifier) {
    val glow by
        animateColorAsState(coverGlowColor(book), FolioMotion.standardSpec(), label = "Glow")
    // Reduced motion keeps the fade and drops the scale.
    val reduced = FolioMotion.reduced
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val settle by
        animateFloatAsState(
            if (shown || reduced) 1f else 0.94f,
            FolioMotion.enterSpec(),
            label = "S",
        )
    val fade by animateFloatAsState(if (shown) 1f else 0f, FolioMotion.enterSpec(), label = "A")
    val large = LocalDensity.current.fontScale > 1.3f
    val coverWidth = if (large) 132.dp else 160.dp
    Column(
        modifier.fillMaxWidth().drawBehind {
            val centre =
                Offset(size.width / 2f, (coverWidth / FolioSpace.COVER_RATIO).toPx() * 0.55f)
            drawRect(
                Brush.radialGradient(
                    listOf(glow.copy(alpha = 0.55f), glow.copy(alpha = 0f)),
                    center = centre,
                    radius = size.width * 0.72f,
                )
            )
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(FolioSpace.section))
        // Decoration for TalkBack: the title under it says the same thing.
        CoverTile(
            book,
            Modifier.width(coverWidth)
                .graphicsLayer {
                    scaleX = settle
                    scaleY = settle
                    alpha = fade
                }
                .clearAndSetSemantics {},
        )
        Spacer(Modifier.height(FolioSpace.section))
        Column(
            Modifier.padding(horizontal = FolioSpace.page),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            Text(
                book.title,
                style =
                    if (large) MaterialTheme.typography.headlineSmall
                    else MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                book.authorLabel.ifBlank { stringResource(R.string.shell_unknown_author) },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (book.series.isNotBlank())
                Text(
                    listOf(book.series, book.seriesIndex)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
        }
        Spacer(Modifier.height(FolioSpace.section))
    }
}

/** The cover's tone for the glow; see [coverToneColor]. */
@Composable private fun coverGlowColor(book: Book): Color = coverToneColor(book)

/** One tile of the status strip. [onClick] replaces the strip's own action for this tile. */
internal class StatusTile(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val detail: String? = null,
    val tag: String,
    val onClick: (() -> Unit)? = null,
)

/**
 * Three compact tiles: Phone, E-reader, Hardcover, each with its position or one word of state.
 * Tapping one opens the Progress sheet, unless the tile has its own action. At large text sizes the
 * tiles stack.
 */
@Composable
internal fun StatusStrip(
    tiles: List<StatusTile>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stacked = LocalDensity.current.fontScale > 1.3f
    @Composable
    fun Tile(tile: StatusTile, modifier: Modifier) {
        Surface(
            modifier
                .clip(FolioSpace.panelShape)
                .clickable(role = Role.Button, onClick = tile.onClick ?: onClick)
                .testTag(tile.tag),
            shape = FolioSpace.panelShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            // Snug to its two or three lines: no minimum height, so nothing sits under the value.
            Column(
                Modifier.padding(horizontal = FolioSpace.gap, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        tile.icon,
                        null,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        tile.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Two lines, so "Not on Hardcover" fits a third of the width.
                Text(
                    tile.value,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                tile.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    // In a row the tiles share one height: a "2 hours ago" line on one sets it for the others.
    if (stacked)
        Column(
            modifier.fillMaxWidth().testTag(BOOK_STATUS_STRIP_TAG),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            tiles.forEach { Tile(it, Modifier.fillMaxWidth()) }
        }
    else
        Row(
            modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag(BOOK_STATUS_STRIP_TAG),
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            tiles.forEach { Tile(it, Modifier.weight(1f).fillMaxHeight()) }
        }
}

internal const val STATUS_TILE_PHONE_TAG = "status-tile-phone"
internal const val STATUS_TILE_EREADER_TAG = "status-tile-ereader"
internal const val STATUS_TILE_HARDCOVER_TAG = "status-tile-hardcover"

@Composable
private fun statusTiles(
    hasEpub: Boolean,
    entries: List<ProgressEntry>,
    hardcoverState: HardcoverItemState,
    onCheckMatch: () -> Unit,
): List<StatusTile> {
    val tiles = mutableListOf<StatusTile>()
    if (hasEpub) {
        val phone = entries.firstOrNull { it.source == ProgressSource.PHONE }
        tiles +=
            StatusTile(
                Icons.Outlined.Smartphone,
                stringResource(R.string.rd_strip_phone),
                phone?.percent?.let { percentText(it) }
                    ?: stringResource(R.string.progress_not_started),
                phone?.at?.let { relativeTime(it) },
                STATUS_TILE_PHONE_TAG,
            )
        // A device that syncs first; an old position from before sync only when there is no other.
        val ereader =
            entries.firstOrNull { it.source == ProgressSource.EREADER && !it.old }
                ?: entries.firstOrNull { it.source == ProgressSource.EREADER }
        tiles +=
            StatusTile(
                Icons.Outlined.TabletAndroid,
                ereader?.device ?: stringResource(R.string.progress_device),
                ereader?.percent?.let { percentText(it) }
                    ?: stringResource(R.string.m9sync_ereader_no_position),
                ereader?.at?.let { relativeTime(it) },
                STATUS_TILE_EREADER_TAG,
            )
    }
    tiles +=
        hardcoverTile(
            entries.firstOrNull { it.source == ProgressSource.HARDCOVER }?.page,
            hardcoverState,
            onCheckMatch,
        )
    return tiles
}

/**
 * The Hardcover tile: the page Hardcover has when known, else what [state] says. While a match
 * waits for the owner, tapping it calls [onCheckMatch] instead of opening the Progress sheet.
 */
@Composable
internal fun hardcoverTile(
    page: Int?,
    state: HardcoverItemState,
    onCheckMatch: () -> Unit = {},
): StatusTile =
    StatusTile(
        Icons.Outlined.Link,
        stringResource(R.string.progress_hardcover),
        if (page != null) stringResource(R.string.rd_hardcover_page, page)
        else hardcoverTileValue(state),
        hardcoverTileDetail(state),
        STATUS_TILE_HARDCOVER_TAG,
        onClick = onCheckMatch.takeIf { state == HardcoverItemState.CHECK },
    )

/** The Hardcover tile's word: Matched, Matching…, Check match, Not on Hardcover, Not found. */
@Composable
internal fun hardcoverTileValue(state: HardcoverItemState): String =
    when (state) {
        HardcoverItemState.MATCHED -> stringResource(R.string.rd_hardcover_linked)
        HardcoverItemState.MATCHING -> stringResource(R.string.hc10_tile_matching)
        HardcoverItemState.CHECK -> stringResource(R.string.hc10_tile_check)
        HardcoverItemState.NOT_ON_HARDCOVER -> stringResource(R.string.hc10_tile_not_on_hardcover)
        HardcoverItemState.NOT_FOUND -> stringResource(R.string.hc10_tile_not_found)
        HardcoverItemState.OFF -> stringResource(R.string.rd_hardcover_not_linked)
    }

/**
 * The line under it, when the owner has something to do: "Tap to choose" for a match to check, and
 * "Tap Hardcover" (the Hardcover action) when no match is coming on its own.
 */
@Composable
internal fun hardcoverTileDetail(state: HardcoverItemState): String? =
    when (state) {
        HardcoverItemState.CHECK -> stringResource(R.string.hc10_tile_check_detail)
        HardcoverItemState.NOT_FOUND,
        HardcoverItemState.OFF -> stringResource(R.string.rd_hardcover_not_linked_detail)
        HardcoverItemState.MATCHED,
        HardcoverItemState.MATCHING,
        HardcoverItemState.NOT_ON_HARDCOVER -> null
    }

@Composable
private fun percentText(fraction: Double): String =
    stringResource(R.string.rd_percent, (fraction * 100).toInt())

/** "2 hours ago", "Yesterday": the system's wording, so it matches the rest of the phone. */
internal fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
            millis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        )
        .toString()

/**
 * Every position known for the book, one row per device (as the owner named, merged or forgot them
 * on the Device tab), then the status on this phone as chips. An e-reader that is ahead gets a
 * note; an old position from before sync says so. No source is rewritten.
 */
@Composable
internal fun ProgressSheet(
    book: Book,
    hasEpub: Boolean,
    entries: List<ProgressEntry>,
    busy: Boolean,
    onStatus: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    FolioSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        ProgressContent(book, hasEpub, entries, busy, onStatus)
    }
}

/** The Progress sheet's content, so previews and tests can show it without a sheet window. */
@Composable
internal fun ProgressContent(
    book: Book,
    hasEpub: Boolean,
    entries: List<ProgressEntry>,
    busy: Boolean = false,
    onStatus: (String) -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
            .testTag(PROGRESS_SHEET_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        Text(
            stringResource(R.string.progress_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = FolioSpace.section).semantics { heading() },
        )
        val ahead = aheadOfPhone(entries)
        entries.forEach { entry ->
            val name =
                when (entry.source) {
                    ProgressSource.PHONE -> stringResource(R.string.progress_phone)
                    ProgressSource.EREADER ->
                        entry.device
                            ?: stringResource(
                                if (entry.old) R.string.dev_old_name else R.string.progress_device
                            )
                    ProgressSource.HARDCOVER -> stringResource(R.string.progress_hardcover)
                }
            ProgressRow(
                icon =
                    when {
                        entry.source == ProgressSource.PHONE -> Icons.Outlined.Smartphone
                        entry.old -> Icons.Outlined.History
                        entry.source == ProgressSource.EREADER -> Icons.Outlined.TabletAndroid
                        else -> Icons.Outlined.Link
                    },
                name = name,
                value =
                    when {
                        entry.percent != null -> percentText(entry.percent)
                        entry.page != null -> stringResource(R.string.rd_hardcover_page, entry.page)
                        entry.source == ProgressSource.HARDCOVER ->
                            stringResource(R.string.rd_hardcover_linked)
                        else -> stringResource(R.string.progress_not_started)
                    },
                detail = entry.at?.let { relativeTime(it) },
                progress = entry.percent,
            )
        }
        // A book with no e-reader position yet: one row that says so.
        if (hasEpub && entries.none { it.source == ProgressSource.EREADER })
            ProgressRow(
                icon = Icons.Outlined.TabletAndroid,
                name = stringResource(R.string.progress_device),
                value = stringResource(R.string.m9sync_ereader_no_position),
                detail = null,
                progress = null,
            )
        if (ahead != null)
            Surface(
                Modifier.padding(horizontal = FolioSpace.section).fillMaxWidth(),
                shape = FolioSpace.panelShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Text(
                    stringResource(
                        R.string.rd_progress_ahead,
                        ahead.device ?: stringResource(R.string.progress_device),
                        ((ahead.percent ?: 0.0) * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(FolioSpace.gap),
                )
            }
        Spacer(Modifier.height(FolioSpace.small))
        Text(
            stringResource(R.string.rd_status_on_phone).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = FolioSpace.section),
        )
        FlowRow(
            Modifier.padding(horizontal = FolioSpace.section),
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            phoneStatuses.forEach { status ->
                FilterChip(
                    selected = status == book.status,
                    onClick = { onStatus(status) },
                    enabled = !busy,
                    label = { Text(libraryStatusLabel(status)) },
                )
            }
        }
        Text(
            stringResource(R.string.shell_status_body),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = FolioSpace.section),
        )
    }
}

@Composable
private fun ProgressRow(
    icon: ImageVector,
    name: String,
    value: String,
    detail: String?,
    progress: Double?,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(name) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                detail?.let { Text(it) }
                progress?.let {
                    LinearProgressIndicator(
                        progress = { it.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.titleMedium)
                trailing?.invoke()
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = FolioSpace.small),
    )
}

/** The lists this book is in, as chips, and a chip to add it to another. */
@Composable
private fun ListChips(lists: List<String>, onLists: () -> Unit, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
        Text(
            stringResource(R.string.shell_lists).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            lists.forEach { name ->
                AssistChip(onClick = onLists, enabled = enabled, label = { Text(name) })
            }
            AssistChip(
                onClick = onLists,
                enabled = enabled,
                label = { Text(stringResource(R.string.rd_add_to_list)) },
                leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)) },
                modifier = Modifier.testTag(BOOK_LISTS_ADD_TAG),
            )
        }
    }
}

/** Four lines of the description, then "More". */
@Composable
private fun Description(book: Book) {
    var more by rememberSaveable(book.id) { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    val text =
        remember(book.description) {
            android.text.Html.fromHtml(book.description, android.text.Html.FROM_HTML_MODE_COMPACT)
                .toString()
                .trim()
        }
    Column {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = if (more) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!more) overflows = it.hasVisualOverflow },
            modifier = Modifier.animateContentSize(FolioMotion.standardSpec()),
        )
        if (overflows || more)
            TextButton(onClick = { more = !more }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(if (more) R.string.rd_less else R.string.rd_more))
            }
    }
}

@Composable
private fun NextInSeries(book: Book, next: Book, onOpen: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth()
            .clip(FolioSpace.panelShape)
            .clickable(role = Role.Button, onClick = onOpen),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        ListItem(
            overlineContent = {
                Text(stringResource(R.string.library_book_next_in_series, book.series))
            },
            headlineContent = { Text(next.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = { CoverTile(next, Modifier.width(40.dp), lifted = false) },
            trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun DetailsSection(book: Book, busy: Boolean, onLookup: () -> Unit) {
    val summary =
        listOf(book.publisher, book.language).filter { it.isNotBlank() }.joinToString(" · ")
    ExpandableSection(
        stringResource(R.string.library_book_info),
        summary.ifBlank { stringResource(R.string.library_book_info_summary) },
    ) {
        if (book.publisher.isNotBlank())
            DetailLine(stringResource(R.string.library_book_publisher), book.publisher)
        if (book.language.isNotBlank())
            DetailLine(stringResource(R.string.library_book_language), book.language)
        if (book.identifiers != "[]")
            DetailLine(
                stringResource(R.string.library_book_identifiers),
                decode(book.identifiers).joinToString("\n"),
            )
        if (book.tags != "[]")
            DetailLine(
                stringResource(R.string.library_book_tags),
                decode(book.tags).joinToString(", "),
            )
        TextButton(onClick = onLookup, enabled = !busy, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.library_book_find_details))
        }
    }
}

@Composable
private fun NotesSection(annotations: List<BookAnnotation>, busy: Boolean, onExport: () -> Unit) {
    ExpandableSection(
        stringResource(R.string.library_book_notes),
        pluralStringResource(
            R.plurals.library_book_notes_count,
            annotations.size,
            annotations.size,
        ),
    ) {
        if (annotations.isEmpty()) {
            Text(
                stringResource(R.string.library_book_notes_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TextButton(onClick = onExport, enabled = !busy, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpace.small))
                Text(stringResource(R.string.shell_action_export_notes))
            }
            annotations.take(100).forEach { note ->
                Text(
                    note.chapter.ifBlank {
                        if (note.kind == "bookmark") stringResource(R.string.library_book_bookmark)
                        else stringResource(R.string.library_book_highlight)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (note.text.isNotBlank()) Text(note.text)
                if (note.note.isNotBlank())
                    Text(note.note, style = MaterialTheme.typography.bodyMedium)
                if (note.date.isNotBlank())
                    Text(note.date, style = MaterialTheme.typography.labelSmall)
                HorizontalDivider()
            }
            if (annotations.size > 100)
                Text(
                    stringResource(R.string.library_book_notes_limited),
                    style = MaterialTheme.typography.bodySmall,
                )
        }
    }
}

/** One item of the book page's overflow menu. */
internal class OverflowItem(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The glass bar over the book page: Back, and an overflow menu when there is anything in it. The
 * page scrolls under it, so the glow and the cover blur through.
 */
@Composable
internal fun BookTopBar(
    hazeState: HazeState?,
    onBack: () -> Unit,
    backEnabled: Boolean = true,
    overflow: List<OverflowItem> = emptyList(),
    title: String = "",
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    GlassSurface(hazeState, hairlineAtTop = false) {
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = backEnabled) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.shell_back),
                )
            }
            // The book's title, so the bar names the page instead of sitting empty.
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            if (overflow.isNotEmpty())
                Box {
                    IconButton(
                        onClick = { menu = true },
                        modifier = Modifier.testTag(BOOK_OVERFLOW_TAG),
                    ) {
                        Icon(Icons.Outlined.MoreVert, stringResource(R.string.shell_book_actions))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        overflow.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.label) },
                                leadingIcon = { Icon(item.icon, null) },
                                enabled = item.enabled,
                                onClick = {
                                    menu = false
                                    item.onClick()
                                },
                            )
                        }
                    }
                }
        }
    }
}

/**
 * Folio Link reports for this book's current file from other devices, newest first, one per device
 * as the owner arranged them (named, merged, forgotten: [LocalDevices]). KOReader keys them by a
 * fingerprint of the file, so another edition of the book does not match.
 */
@Composable
internal fun linkReports(book: Book): List<KosyncReport> {
    val devices = LocalDevices.current
    val app = LocalContext.current.applicationContext as? FolioApplication ?: return emptyList()
    val document by
        produceState<String?>(null, book.currentPath) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { koreaderDigest(java.io.File(book.currentPath)) }.getOrNull()
                }
        }
    val reports by
        remember(document) {
                document?.let { app.database.library().kosyncReportsFlow(it) }
                    ?: flowOf(emptyList())
            }
            .collectAsStateWithLifecycle(emptyList())
    val phone = remember { app.link.settings.phoneDeviceId }
    return remember(reports, phone, devices) {
        devices.reports(reports.filter { it.deviceId != phone })
    }
}

/** One entry in the Read menu. */
internal sealed interface ReadStart {
    /** Opens at the place an e-reader reported through Folio Link. */
    data class Device(val report: KosyncReport) : ReadStart

    data object Beginning : ReadStart

    data object Listen : ReadStart
}

/**
 * The Read menu, most useful first: each e-reader's place (newest first, as reported), then
 * starting over when the phone has a place of its own, then reading aloud. E-reader places and
 * reading aloud work in EPUB books only.
 */
internal fun readStarts(
    phoneHasPlace: Boolean,
    reports: List<KosyncReport>,
    epub: Boolean = true,
): List<ReadStart> =
    (if (epub) reports.map { ReadStart.Device(it) } else emptyList()) +
        listOfNotNull(ReadStart.Beginning.takeIf { phoneHasPlace }) +
        listOfNotNull(ReadStart.Listen.takeIf { epub })

/** Read or Continue, with an arrow that opens the other ways to start. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReadSplitButton(
    label: String,
    starts: List<ReadStart>,
    onRead: () -> Unit,
    onReadFrom: (ReaderStart) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val more = stringResource(R.string.book_read_more)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SplitButtonDefaults.Spacing),
    ) {
        SplitButtonDefaults.LeadingButton(
            onClick = onRead,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(BOOK_PRIMARY_TAG),
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            Tooltipped(more) {
                SplitButtonDefaults.TrailingButton(
                    checked = open,
                    onCheckedChange = { open = it },
                    modifier = Modifier.heightIn(min = 56.dp).testTag(BOOK_READ_MENU_TAG),
                ) {
                    val turn by animateFloatAsState(if (open) 180f else 0f, label = "read-menu")
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = more,
                        modifier = Modifier.graphicsLayer { rotationZ = turn },
                    )
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                starts.forEach { start ->
                    val (icon, text) =
                        when (start) {
                            is ReadStart.Device ->
                                Icons.Outlined.TabletAndroid to
                                    stringResource(
                                        R.string.book_read_from_device,
                                        start.report.device,
                                        (start.report.percentage * 100).toInt(),
                                    )
                            ReadStart.Beginning ->
                                Icons.Outlined.Replay to
                                    stringResource(R.string.book_read_beginning)
                            ReadStart.Listen ->
                                Icons.Outlined.Headphones to
                                    stringResource(R.string.book_read_listen)
                        }
                    DropdownMenuItem(
                        text = { Text(text) },
                        leadingIcon = { Icon(icon, contentDescription = null) },
                        onClick = {
                            open = false
                            onReadFrom(
                                when (start) {
                                    is ReadStart.Device -> ReaderStart.Device(start.report.progress)
                                    ReadStart.Beginning -> ReaderStart.Beginning
                                    ReadStart.Listen -> ReaderStart.Listen
                                }
                            )
                        },
                    )
                }
            }
        }
    }
}
