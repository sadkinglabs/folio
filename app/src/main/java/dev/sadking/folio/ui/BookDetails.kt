@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.sadking.folio.ui

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
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Sync
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.R
import dev.sadking.folio.data.*
import dev.sadking.folio.reader.koreaderDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

internal const val BOOK_PAGE_TAG = "book-page"
internal const val BOOK_PRIMARY_TAG = "book-primary"
internal const val BOOK_ACTION_SEND_TAG = "book-action-send"
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

/**
 * The one book page, for books with and without an EPUB. A record ([hasEpub] false) gets the same
 * page with fewer actions: Find EPUB instead of Read, Attach instead of Send, no Share, and a
 * status strip with Hardcover only.
 *
 * Busy rules: a running library operation ([busy]) disables changes; a transfer ([deviceBusy]) only
 * disables sending and the e-reader refresh. Reading is never blocked.
 */
@Composable
fun BookDetails(
    book: Book,
    hasEpub: Boolean = true,
    lists: List<String> = emptyList(),
    signals: BookSignals? = null,
    phoneReading: PhoneReading? = null,
    reading: ReadingSnapshot? = null,
    hardcoverLinked: Boolean = signals?.hardcover != null,
    hardcoverPage: Int? = null,
    annotations: List<BookAnnotation> = emptyList(),
    nextInSeries: Book? = null,
    busy: Boolean = false,
    deviceBusy: Boolean = busy,
    contentPadding: PaddingValues = PaddingValues(),
    onRead: () -> Unit = {},
    onSend: () -> Unit = {},
    onAttach: () -> Unit = {},
    onEdit: () -> Unit = {},
    onHardcover: () -> Unit = {},
    onShare: () -> Unit = {},
    onRemove: () -> Unit = {},
    onLists: () -> Unit = {},
    onStatus: (String) -> Unit = {},
    onRefreshReading: () -> Unit = {},
    onLookup: () -> Unit = {},
    onExport: () -> Unit = {},
    onNext: (Book) -> Unit = {},
    /** Content under the status strip; a record puts its Hardcover offer here. */
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val reports = if (hasEpub) linkReports(book) else emptyList()
    val entries =
        remember(hasEpub, phoneReading, reading, reports, hardcoverLinked, hardcoverPage) {
            progressEntries(hasEpub, phoneReading, reading, reports, hardcoverLinked, hardcoverPage)
        }
    var progressSheet by rememberSaveable(book.id) { mutableStateOf(false) }
    // The Send tile shows a check for a moment after a send is queued.
    var justSent by remember { mutableStateOf(false) }
    LaunchedEffect(justSent) {
        if (justSent) {
            delay(1500)
            justSent = false
        }
    }
    val sending = signals?.device in listOf("Sending", "Queued")
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
            Button(
                onClick = onRead,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(BOOK_PRIMARY_TAG),
            ) {
                Text(
                    when (bookPrimary(hasEpub, phoneReading)) {
                        BookPrimary.CONTINUE ->
                            stringResource(
                                R.string.book_continue,
                                ((phoneReading?.percent ?: 0.0) * 100).toInt(),
                            )
                        BookPrimary.READ -> stringResource(R.string.book_read)
                        BookPrimary.FIND_EPUB -> stringResource(R.string.rd_find_epub)
                    }
                )
            }
            ActionRow(
                bookPageActions(hasEpub).map { action ->
                    when (action) {
                        BookPageAction.SEND ->
                            IconAction(
                                if (justSent) Icons.Outlined.Check
                                else Icons.AutoMirrored.Outlined.Send,
                                stringResource(
                                    if (justSent) R.string.rd_action_queued
                                    else R.string.rd_action_send
                                ),
                                enabled = !deviceBusy && !justSent,
                                busy = sending,
                                tag = BOOK_ACTION_SEND_TAG,
                            ) {
                                justSent = true
                                onSend()
                            }
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
                                enabled = !busy,
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
                statusTiles(hasEpub, entries, signals, hardcoverLinked),
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
            if (hasEpub) NotesSection(annotations, busy, deviceBusy, onExport, onRefreshReading)
            Spacer(Modifier.height(FolioSpace.item))
        }
    }
    if (progressSheet)
        ProgressSheet(
            book = book,
            hasEpub = hasEpub,
            entries = entries,
            signals = signals,
            busy = busy,
            deviceBusy = deviceBusy,
            onStatus = onStatus,
            onRefresh = onRefreshReading,
            onDismiss = { progressSheet = false },
        )
}

/**
 * The cover, 160 dp and lifted, over a soft radial glow in the cover's own tone; below it the title
 * in serif, the author and the series. The cover settles in with a short scale and fade.
 */
@Composable
internal fun CoverHero(book: Book, modifier: Modifier = Modifier) {
    val glow by
        animateColorAsState(coverGlowColor(book), FolioMotion.standardSpec(), label = "Glow")
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val settle by
        animateFloatAsState(if (shown) 1f else 0.94f, FolioMotion.enterSpec(), label = "S")
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
        CoverTile(
            book,
            Modifier.width(coverWidth).graphicsLayer {
                scaleX = settle
                scaleY = settle
                alpha = fade
            },
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

/** One tile of the status strip. */
internal class StatusTile(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val detail: String? = null,
    val tag: String,
)

/**
 * Three compact tiles: Phone, E-reader, Hardcover, each with its position or one word of state.
 * Tapping any of them opens the Progress sheet. At large text sizes the tiles stack.
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
                .clickable(role = Role.Button, onClick = onClick)
                .testTag(tile.tag),
            shape = FolioSpace.panelShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                Modifier.padding(horizontal = FolioSpace.gap, vertical = FolioSpace.gap)
                    .heightIn(min = 72.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
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
                Text(
                    tile.value,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
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
    if (stacked)
        Column(
            modifier.fillMaxWidth().testTag(BOOK_STATUS_STRIP_TAG),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            tiles.forEach { Tile(it, Modifier.fillMaxWidth()) }
        }
    else
        Row(
            modifier.fillMaxWidth().testTag(BOOK_STATUS_STRIP_TAG),
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            tiles.forEach { Tile(it, Modifier.weight(1f)) }
        }
}

internal const val STATUS_TILE_PHONE_TAG = "status-tile-phone"
internal const val STATUS_TILE_EREADER_TAG = "status-tile-ereader"
internal const val STATUS_TILE_HARDCOVER_TAG = "status-tile-hardcover"

@Composable
private fun statusTiles(
    hasEpub: Boolean,
    entries: List<ProgressEntry>,
    signals: BookSignals?,
    hardcoverLinked: Boolean,
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
        val ereader = entries.firstOrNull { it.source == ProgressSource.EREADER }
        tiles +=
            StatusTile(
                Icons.Outlined.TabletAndroid,
                ereader?.device ?: stringResource(R.string.progress_device),
                ereader?.percent?.let { percentText(it) }
                    ?: ereaderStateLabel(ereaderState(signals?.device ?: "Phone only")),
                ereader?.at?.let { relativeTime(it) },
                STATUS_TILE_EREADER_TAG,
            )
    }
    val hardcover = entries.firstOrNull { it.source == ProgressSource.HARDCOVER }
    tiles +=
        StatusTile(
            Icons.Outlined.Link,
            stringResource(R.string.progress_hardcover),
            when {
                hardcover?.page != null ->
                    stringResource(R.string.rd_hardcover_page, hardcover.page)
                hardcoverLinked -> stringResource(R.string.rd_hardcover_linked)
                else -> stringResource(R.string.rd_hardcover_not_linked)
            },
            null,
            STATUS_TILE_HARDCOVER_TAG,
        )
    return tiles
}

@Composable
private fun ereaderStateLabel(state: EreaderState): String =
    stringResource(
        when (state) {
            EreaderState.SENT -> R.string.rd_ereader_sent
            EreaderState.CHANGED -> R.string.rd_ereader_changed
            EreaderState.NOT_SENT -> R.string.rd_ereader_not_sent
            EreaderState.QUEUED -> R.string.rd_ereader_queued
            EreaderState.SENDING -> R.string.rd_ereader_sending
            EreaderState.PAUSED -> R.string.rd_ereader_paused
            EreaderState.FAILED -> R.string.rd_ereader_failed
            EreaderState.NO_EPUB -> R.string.rd_ereader_no_epub
        }
    )

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
 * Every position known for the book, one row per source, then the status on this phone as chips. An
 * e-reader that is ahead gets a note; nothing is merged and no source is rewritten.
 */
@Composable
internal fun ProgressSheet(
    book: Book,
    hasEpub: Boolean,
    entries: List<ProgressEntry>,
    signals: BookSignals?,
    busy: Boolean,
    deviceBusy: Boolean,
    onStatus: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                modifier = Modifier.padding(horizontal = FolioSpace.section),
            )
            val ahead = aheadOfPhone(entries)
            entries.forEach { entry ->
                val name =
                    when (entry.source) {
                        ProgressSource.PHONE -> stringResource(R.string.progress_phone)
                        ProgressSource.EREADER ->
                            entry.device ?: stringResource(R.string.progress_device)
                        ProgressSource.HARDCOVER -> stringResource(R.string.progress_hardcover)
                    }
                ProgressRow(
                    icon =
                        when (entry.source) {
                            ProgressSource.PHONE -> Icons.Outlined.Smartphone
                            ProgressSource.EREADER -> Icons.Outlined.TabletAndroid
                            ProgressSource.HARDCOVER -> Icons.Outlined.Link
                        },
                    name = name,
                    value =
                        when {
                            entry.percent != null -> percentText(entry.percent)
                            entry.page != null ->
                                stringResource(R.string.rd_hardcover_page, entry.page)
                            entry.source == ProgressSource.HARDCOVER ->
                                stringResource(R.string.rd_hardcover_linked)
                            else -> stringResource(R.string.progress_not_started)
                        },
                    detail = entry.at?.let { relativeTime(it) },
                    progress = entry.percent,
                    trailing =
                        if (
                            entry.source == ProgressSource.EREADER &&
                                entry.device == null &&
                                hasEpub
                        ) {
                            {
                                IconButton(onClick = onRefresh, enabled = !deviceBusy) {
                                    Icon(
                                        Icons.Outlined.Sync,
                                        stringResource(R.string.progress_refresh),
                                    )
                                }
                            }
                        } else null,
                )
            }
            // An EPUB with no e-reader position: one row with the send state and the refresh.
            if (
                hasEpub && entries.none { it.source == ProgressSource.EREADER && it.device == null }
            )
                ProgressRow(
                    icon = Icons.Outlined.TabletAndroid,
                    name = stringResource(R.string.progress_device),
                    value = ereaderStateLabel(ereaderState(signals?.device ?: "Phone only")),
                    detail = null,
                    progress = null,
                ) {
                    IconButton(onClick = onRefresh, enabled = !deviceBusy) {
                        Icon(Icons.Outlined.Sync, stringResource(R.string.progress_refresh))
                    }
                }
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
private fun NotesSection(
    annotations: List<BookAnnotation>,
    busy: Boolean,
    deviceBusy: Boolean,
    onExport: () -> Unit,
    onRefresh: () -> Unit,
) {
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
            TextButton(
                onClick = onRefresh,
                enabled = !deviceBusy,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(stringResource(R.string.library_book_notes_get))
            }
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
            Spacer(Modifier.weight(1f))
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
 * Folio Link reports for this book's current file from other devices, newest first. KOReader keys
 * them by a fingerprint of the file, so another edition of the book does not match.
 */
@Composable
internal fun linkReports(book: Book): List<KosyncReport> {
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
    return reports.filter { it.deviceId != phone }
}
