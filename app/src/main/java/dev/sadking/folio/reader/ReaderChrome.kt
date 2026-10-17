@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package dev.sadking.folio.reader

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.sadking.folio.R
import dev.sadking.folio.ui.*
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal const val READER_TOP_BAR_TAG = "reader-top-bar"
internal const val READER_BOTTOM_BAR_TAG = "reader-bottom-bar"
internal const val READER_SLIDER_TAG = "reader-slider"
internal const val READER_PAGE_LABEL_TAG = "reader-page-label"
internal const val READER_TIME_LEFT_TAG = "reader-time-left"
internal const val READER_TEXT_SHEET_TAG = "reader-text-sheet"
internal const val READER_CONTENTS_SHEET_TAG = "reader-contents-sheet"
internal const val READER_DEVICES_SHEET_TAG = "reader-devices-sheet"
internal const val READER_LINK_OFFER_TAG = "reader-link-offer"
internal const val READER_FAILED_TAG = "reader-failed"
internal const val READER_LISTEN_BAR_TAG = "reader-listen-bar"
internal const val READER_LISTEN_SHEET_TAG = "reader-listen-sheet"
internal const val READER_BOOKMARK_BUTTON_TAG = "reader-bookmark-button"

/** The test tag of one theme swatch in the Text sheet: `reader-theme-Paper`. */
internal fun readerThemeTag(theme: String) = "reader-theme-$theme"

/** How long the Folio Link offer stays before it steps aside unanswered. */
internal const val LINK_OFFER_TIMEOUT_MS = 8_000L

/** The sheets the top bar and the listen bar open. */
internal enum class ReaderPanel {
    LISTEN,
    /** "Test read-aloud speed", opened from the Listening sheet. */
    LISTEN_TEST,
    CONTENTS,
    TEXT,
    DEVICES,
    /** Search in the book. */
    SEARCH,
}

/**
 * The top glass bar: Back, the title on one line, Search, Listen, Devices and the bookmark ribbon.
 * Contents and Text sit in the bottom bar, so the title keeps room on a phone.
 */
@Composable
internal fun ReaderTopBar(
    hazeState: HazeState?,
    title: String?,
    enabled: Boolean,
    backEnabled: Boolean,
    insets: WindowInsets,
    onBack: () -> Unit,
    onPanel: (ReaderPanel) -> Unit,
    modifier: Modifier = Modifier,
    onListen: (() -> Unit)? = null,
    /** The page shown has a bookmark: the ribbon is filled. */
    bookmarked: Boolean = false,
    /** Bookmarks the page, or removes its bookmark; null leaves the button out. */
    onBookmark: (() -> Unit)? = null,
) {
    GlassBar(
        hazeState,
        top = true,
        insets = insets,
        modifier = modifier.testTag(READER_TOP_BAR_TAG),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = backEnabled) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.reader_back))
            }
            Text(
                title ?: stringResource(R.string.reader_title_fallback),
                Modifier.weight(1f).padding(horizontal = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            // Icons only: each has Material's tooltip, its name on a long press.
            BarAction(Icons.Outlined.Search, R.string.rd6_search, enabled) {
                onPanel(ReaderPanel.SEARCH)
            }
            if (onListen != null)
                BarAction(Icons.Outlined.Headphones, R.string.reader_listen, enabled, onListen)
            BarAction(Icons.Outlined.Devices, R.string.reader_devices, enabled) {
                onPanel(ReaderPanel.DEVICES)
            }
            if (onBookmark != null) BookmarkAction(bookmarked, enabled, onBookmark)
        }
    }
}

/**
 * The top bar's ribbon: outlined to bookmark the page, filled when the page has one, and then it
 * removes it.
 */
@Composable
private fun BookmarkAction(bookmarked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val name =
        stringResource(
            if (bookmarked) R.string.reader_bookmark_remove else R.string.reader_bookmark
        )
    val state = stringResource(R.string.reader_bookmarked)
    Tooltipped(name) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier =
                Modifier.testTag(READER_BOOKMARK_BUTTON_TAG).semantics {
                    if (bookmarked) stateDescription = state
                },
        ) {
            if (bookmarked)
                Icon(Icons.Filled.Bookmark, name, tint = MaterialTheme.colorScheme.primary)
            else Icon(Icons.Outlined.BookmarkBorder, name)
        }
    }
}

/** An icon-only bar action with its tooltip. */
@Composable
private fun BarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val name = stringResource(label)
    Tooltipped(name) { IconButton(onClick = onClick, enabled = enabled) { Icon(icon, name) } }
}

/**
 * The bottom glass bar: the book as a slider with a tick where each chapter starts, the page label,
 * and the page buttons. Dragging scrubs: the label follows the thumb and names the chapter under
 * it; letting go jumps there through [onJump], at a 0..1 fraction of the book.
 */
@Composable
internal fun ReaderBottomBar(
    hazeState: HazeState?,
    fraction: Float,
    label: PageLabel,
    ticks: List<Float>,
    pageCount: Int,
    chapterAt: (Float) -> String?,
    insets: WindowInsets,
    onJump: (Float) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    /** "12 min left in chapter · 4 h 10 min in book", under the page label; null hides it. */
    timeLeft: String? = null,
    /** Opens Contents or Text from either end of the page row; null leaves them out. */
    onPanel: ((ReaderPanel) -> Unit)? = null,
    enabled: Boolean = true,
) {
    val state = remember { SliderState(fraction) }
    LaunchedEffect(fraction) { if (!state.isDragging) state.value = fraction }
    val scrubbing = state.isDragging
    val shown = if (scrubbing) scrubLabel(state.value, pageCount) else label
    val chapter = if (scrubbing) chapterAt(state.value) else null
    val sliderName = stringResource(R.string.reader_slider)
    GlassBar(
        hazeState,
        top = false,
        insets = insets,
        modifier = modifier.testTag(READER_BOTTOM_BAR_TAG),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            if (chapter != null)
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(horizontal = 24.dp),
                    shape = FolioSpace.tileShape,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ) {
                    Text(
                        chapter,
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            // The pill's room is kept whether or not it shows, so the slider never shifts.
            Spacer(Modifier.height(32.dp))
        }
        Slider(
            state = state,
            onValueChange = { state.value = it },
            enabled = pageCount > 0,
            onValueChangeFinished = { onJump(state.value) },
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .semantics { contentDescription = sliderName }
                    .testTag(READER_SLIDER_TAG),
            track = { BookTrack(it, ticks) },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onPanel != null)
                BarAction(
                    Icons.AutoMirrored.Outlined.FormatListBulleted,
                    R.string.reader_contents,
                    enabled,
                ) {
                    onPanel(ReaderPanel.CONTENTS)
                }
            IconButton(onClick = onPrevious) {
                Icon(
                    Icons.AutoMirrored.Outlined.NavigateBefore,
                    stringResource(R.string.reader_previous_page),
                )
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    pageLabelText(shown),
                    Modifier.testTag(READER_PAGE_LABEL_TAG),
                    textAlign = TextAlign.Center,
                    // Two lines at large text sizes, so the percentage is never cut off.
                    maxLines = 2,
                    style = MaterialTheme.typography.labelLarge,
                )
                // While scrubbing, the label names another page: the time left would not match.
                if (timeLeft != null && !scrubbing)
                    Text(
                        timeLeft,
                        Modifier.testTag(READER_TIME_LEFT_TAG),
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            IconButton(onClick = onNext) {
                Icon(
                    Icons.AutoMirrored.Outlined.NavigateNext,
                    stringResource(R.string.reader_next_page),
                )
            }
            if (onPanel != null)
                BarAction(Icons.Outlined.TextFields, R.string.reader_appearance, enabled) {
                    onPanel(ReaderPanel.TEXT)
                }
        }
    }
}

@Composable
internal fun pageLabelText(label: PageLabel): String =
    if (label.page != null)
        stringResource(R.string.reader_progress_pages, label.page, label.total, label.percent)
    else stringResource(R.string.reader_progress_percent, label.percent)

/** The slider's track: read part in `primary`, the rest in `secondaryContainer`, chapter ticks. */
@Composable
private fun BookTrack(state: SliderState, ticks: List<Float>) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.secondaryContainer
    val tickOnActive = MaterialTheme.colorScheme.onPrimary
    val tickOnInactive = MaterialTheme.colorScheme.onSecondaryContainer
    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
        val y = size.height / 2
        val thumb = state.coercedValueAsFraction * size.width
        val gap = 6.dp.toPx()
        val stroke = 8.dp.toPx()
        if (thumb - gap > 0)
            drawLine(active, Offset(0f, y), Offset(thumb - gap, y), stroke, StrokeCap.Round)
        if (thumb + gap < size.width)
            drawLine(
                inactive,
                Offset(thumb + gap, y),
                Offset(size.width, y),
                stroke,
                StrokeCap.Round,
            )
        val radius = 2.dp.toPx()
        ticks.forEach { tick ->
            val x = tick * size.width
            if (kotlin.math.abs(x - thumb) > gap)
                drawCircle(if (x < thumb) tickOnActive else tickOnInactive, radius, Offset(x, y))
        }
    }
}

/** Everything the Text sheet shows. */
internal data class ReaderTextState(
    val fontSize: Double = 110.0,
    val pageTheme: String = "System",
    val serif: Boolean = true,
    val scroll: Boolean = false,
    val lineHeight: Double = 1.5,
    val margins: Double = 1.0,
    val publisherStyles: Boolean = false,
    val bookOnly: Boolean = false,
    val twoPages: Boolean = true,
    /** The window is wide enough for two pages and the book is paginated. */
    val showTwoPages: Boolean = false,
)

/** What the Text sheet changes. Each applies at once. */
internal class ReaderTextActions(
    val onSize: (Double) -> Unit,
    val onTheme: (String) -> Unit,
    val onSerif: (Boolean) -> Unit,
    val onSpacing: (Double) -> Unit,
    val onMargins: (Double) -> Unit,
    val onBookStyles: (Boolean) -> Unit,
    val onScroll: (Boolean) -> Unit,
    /** Null where there is no book, as in Settings: the switch is then left out. */
    val onBookOnly: ((Boolean) -> Unit)?,
    /** "Two pages side by side"; null leaves the switch out. */
    val onTwoPages: ((Boolean) -> Unit)? = null,
)

/**
 * The Text sheet: the Aa sample with its stepper, theme swatches, Serif or Sans, spacing and margin
 * sliders, and three switches. No hints: every control shows its state.
 */
@Composable
internal fun ReaderTextSheet(
    state: ReaderTextState,
    actions: ReaderTextActions,
    /** One line under the title, such as Settings' note that these are the defaults. */
    subtitle: String? = null,
    tag: String = READER_TEXT_SHEET_TAG,
    /** More groups under the text settings, such as the reader's screen and page-turn choices. */
    more: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        Column(Modifier.padding(horizontal = FolioSpace.section)) {
            Text(
                stringResource(R.string.appearance_title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            if (subtitle != null)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        TextSizeStepper(state.fontSize, state.serif, actions.onSize)
        ThemeSwatches(state.pageTheme, actions.onTheme)
        val haptics = rememberHaptics()
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = FolioSpace.section)
        ) {
            listOf(R.string.appearance_serif to true, R.string.appearance_sans to false)
                .forEachIndexed { index, (label, serif) ->
                    SegmentedButton(
                        selected = state.serif == serif,
                        onClick = {
                            if (state.serif != serif) haptics.tick()
                            actions.onSerif(serif)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        label = { Text(stringResource(label), textAlign = TextAlign.Center) },
                    )
                }
        }
        ChoiceSlider(
            stringResource(R.string.appearance_spacing),
            listOf(
                stringResource(R.string.appearance_compact),
                stringResource(R.string.appearance_normal),
                stringResource(R.string.appearance_relaxed),
            ),
            LINE_SPACING_CHOICES,
            state.lineHeight,
            enabled = !state.publisherStyles,
            onSelect = actions.onSpacing,
        )
        ChoiceSlider(
            stringResource(R.string.appearance_margins),
            listOf(
                stringResource(R.string.appearance_narrow),
                stringResource(R.string.appearance_normal),
                stringResource(R.string.appearance_wide),
            ),
            MARGIN_CHOICES,
            state.margins,
            enabled = !state.publisherStyles,
            onSelect = actions.onMargins,
        )
        Column {
            SheetSwitch(
                stringResource(R.string.appearance_book_styles),
                state.publisherStyles,
                actions.onBookStyles,
            )
            SheetSwitch(
                stringResource(R.string.appearance_scrolling),
                state.scroll,
                actions.onScroll,
            )
            actions.onTwoPages
                ?.takeIf { state.showTwoPages }
                ?.let { SheetSwitch(stringResource(R.string.rd6_two_pages), state.twoPages, it) }
            actions.onBookOnly?.let {
                SheetSwitch(stringResource(R.string.appearance_book_only), state.bookOnly, it)
            }
        }
        more?.invoke()
    }
}

/**
 * Text size in 10% steps between 75% and 200%: A− and A+ around an "Aa" drawn in the chosen font,
 * which springs to each new size, with the percentage under it.
 */
@Composable
internal fun TextSizeStepper(size: Double, serif: Boolean, onSize: (Double) -> Unit) {
    val smaller = stringResource(R.string.appearance_smaller)
    val larger = stringResource(R.string.appearance_larger)
    val haptics = rememberHaptics()
    val scale by
        animateFloatAsState(
            (size / 100).toFloat(),
            FolioMotion.orSnap(spring(stiffness = Spring.StiffnessMedium)),
            label = "textSize",
        )
    fun step(up: Boolean) {
        haptics.tick()
        onSize(steppedTextSize(size, up))
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = FolioSpace.section),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalIconButton(
            onClick = { step(up = false) },
            enabled = size > MIN_TEXT,
            modifier = Modifier.semantics { contentDescription = smaller },
        ) {
            Text("A−", style = MaterialTheme.typography.labelLarge)
        }
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A fixed-height box keeps the buttons still while the sample grows.
            Box(Modifier.height(72.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Aa",
                    fontSize = (28 * scale).sp,
                    fontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            Text(
                stringResource(R.string.appearance_text_size, size.toInt()),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        FilledTonalIconButton(
            onClick = { step(up = true) },
            enabled = size < MAX_TEXT,
            modifier = Modifier.semantics { contentDescription = larger },
        ) {
            Text("A+", style = MaterialTheme.typography.labelLarge)
        }
    }
}

private class ThemeSwatch(val name: String, val label: Int, val fill: Color?)

/** The page colour Readium paints for each theme; System shows both halves. */
private val themeSwatches =
    listOf(
        ThemeSwatch("System", R.string.appearance_theme_system, null),
        ThemeSwatch("Light", R.string.appearance_theme_light, Color.White),
        ThemeSwatch("Paper", R.string.appearance_theme_paper, Color(0xFFFAF4E8)),
        ThemeSwatch("Dark", R.string.appearance_theme_dark, Color.Black),
    )

/** Four page colours as circles with a label; the chosen one carries a check. */
@Composable
private fun ThemeSwatches(selected: String, onTheme: (String) -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = FolioSpace.item).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        themeSwatches.forEach { swatch ->
            val chosen = swatch.name == selected
            Column(
                Modifier.weight(1f)
                    .clip(FolioSpace.tileShape)
                    .selectable(chosen, role = Role.RadioButton) {
                        haptics.tick()
                        onTheme(swatch.name)
                    }
                    .padding(vertical = FolioSpace.small)
                    .testTag(readerThemeTag(swatch.name)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val ring =
                    if (chosen) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                Box(
                    Modifier.size(40.dp)
                        .clip(CircleShape)
                        .then(
                            if (swatch.fill != null) Modifier.background(swatch.fill) else Modifier
                        )
                        .border(if (chosen) 2.dp else 1.dp, ring, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (swatch.fill == null)
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(Color.White, size = size.copy(width = size.width / 2))
                            drawRect(
                                Color.Black,
                                topLeft = Offset(size.width / 2, 0f),
                                size = size.copy(width = size.width / 2),
                            )
                        }
                    if (chosen)
                        Icon(
                            Icons.Outlined.Check,
                            null,
                            Modifier.size(20.dp),
                            tint =
                                if (swatch.fill == null) MaterialTheme.colorScheme.primary
                                else if (swatch.fill.luminance() < 0.5f) Color.White
                                else Color.Black,
                        )
                }
                // Capped at 150 % so a word never breaks in two in a narrow pane (a tablet's
                // settings pane) at very large text.
                Text(
                    stringResource(swatch.label),
                    style =
                        dev.sadking.folio.ui.cappedTextStyle(
                            MaterialTheme.typography.labelLarge,
                            1.5f,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color =
                        if (chosen) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** A three-stop slider for spacing or margins: the label left, the chosen stop's name right. */
@Composable
private fun ChoiceSlider(
    label: String,
    names: List<String>,
    values: List<Double>,
    current: Double,
    enabled: Boolean,
    onSelect: (Double) -> Unit,
) {
    val index = values.indexOf(nearestChoice(current, values)).coerceAtLeast(0)
    val haptics = rememberHaptics()
    Column(Modifier.padding(horizontal = FolioSpace.section)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                names.getOrElse(index) { "" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = index.toFloat(),
            onValueChange = { position ->
                val chosen = values.getOrNull(position.roundToInt()) ?: return@Slider
                if (chosen != values[index]) {
                    haptics.tick()
                    onSelect(chosen)
                }
            },
            valueRange = 0f..(values.size - 1).toFloat(),
            steps = (values.size - 2).coerceAtLeast(0),
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/**
 * A switch row inside a sheet: no icon, the title does the work, with an optional line under it.
 */
@Composable
internal fun SheetSwitch(
    title: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    inset: androidx.compose.ui.unit.Dp = FolioSpace.section,
    detail: String? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    val alpha = if (enabled) 1f else 0.38f
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, enabled = enabled, role = Role.Switch) {
                haptics.toggle(it)
                onChecked(it)
            }
            .padding(horizontal = inset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            if (detail != null)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
        }
        Switch(checked, null, enabled = enabled)
    }
}

/** One table-of-contents row, flattened: a title and how deep it nests. */
internal data class ContentsEntry(val title: String?, val depth: Int)

/**
 * The Contents sheet, with two tabs. Contents: the chapters, the current one in `primary` behind a
 * dot. Highlights & notes ([ReaderNotesList]): everything the owner marked in the book, bookmarks
 * included, with filter, search, jump and export. Bookmarks live there rather than in a tab of
 * their own, so each mark is in one place and its chip counts it.
 */
@Composable
internal fun ReaderContentsSheet(
    entries: List<ContentsEntry>,
    current: Int,
    notes: List<NoteItem>,
    onEntry: (Int) -> Unit,
    actions: NotesActions = NotesActions(),
    export: ExportActions = ExportActions(),
    /** 0 opens on Contents, 1 on Highlights & notes. */
    initialTab: Int = 0,
    /** The filter Highlights & notes opens with; for previews and tests. */
    initialFilter: NotesFilter = NotesFilter.ALL,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val contentsState =
        rememberLazyListState(initialFirstVisibleItemIndex = current.coerceAtLeast(0))
    val currentLabel = stringResource(R.string.contents_current)
    Column(Modifier.testTag(READER_CONTENTS_SHEET_TAG)) {
        PrimaryTabRow(tab, containerColor = Color.Transparent) {
            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.contents_tab)) })
            Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.contents_notes_tab)) })
        }
        if (tab == 1) {
            ReaderNotesList(notes, actions, export, initialFilter)
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 520.dp),
            state = contentsState,
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        ) {
            if (entries.isEmpty()) item { SheetNote(stringResource(R.string.contents_empty)) }
            items(entries.size) { index ->
                val entry = entries[index]
                val isCurrent = index == current
                val colour =
                    if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(role = Role.Button) { onEntry(index) }
                        .semantics {
                            selected = isCurrent
                            if (isCurrent) stateDescription = currentLabel
                        }
                        .padding(
                            start = FolioSpace.section + (entry.depth * 16).dp,
                            end = FolioSpace.section,
                            top = 12.dp,
                            bottom = 12.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
                ) {
                    Box(
                        Modifier.size(8.dp)
                            .background(if (isCurrent) colour else Color.Transparent, CircleShape)
                    )
                    Text(
                        entry.title ?: stringResource(R.string.contents_chapter_fallback),
                        Modifier.weight(1f),
                        color = colour,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetNote(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = FolioSpace.section, vertical = FolioSpace.item),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Another device's place in this book. [legacy] rows are the e-reader's place saved by an older
 * Librillo.
 */
internal data class DeviceRow(
    val id: String,
    val name: String,
    val percent: Int,
    val time: Long,
    val legacy: Boolean = false,
)

/**
 * The Devices sheet, "Continue elsewhere": each device's place with a Jump button. Positions arrive
 * by themselves through Progress sync, so there is nothing to fetch or send here.
 */
@Composable
internal fun ReaderDevicesSheet(rows: List<DeviceRow>, busy: Boolean, onJump: (DeviceRow) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 32.dp).testTag(READER_DEVICES_SHEET_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        Text(
            stringResource(R.string.devices_title),
            Modifier.padding(horizontal = FolioSpace.section).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        if (rows.isEmpty()) SheetNote(stringResource(R.string.devices_none))
        rows.forEach { row ->
            ListItem(
                headlineContent = {
                    Text(
                        row.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.devices_row,
                            row.percent,
                            relativeTime(row.time),
                        )
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Outlined.TabletAndroid,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    FilledTonalButton(onClick = { onJump(row) }, enabled = !busy) {
                        Text(stringResource(R.string.devices_jump))
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.padding(horizontal = FolioSpace.small),
            )
        }
    }
}

/**
 * "Continue from Kobo at 43%?": offered once at the bottom when another device read on. Unanswered,
 * it steps aside after [LINK_OFFER_TIMEOUT_MS] through [onTimeout].
 */
@Composable
internal fun LinkOfferCard(
    device: String,
    percent: Int,
    modifier: Modifier = Modifier,
    onContinue: () -> Unit,
    onStay: () -> Unit,
    onTimeout: () -> Unit,
) {
    LaunchedEffect(Unit) {
        delay(LINK_OFFER_TIMEOUT_MS)
        onTimeout()
    }
    Surface(
        modifier
            .fillMaxWidth()
            .then(lift(FolioSpace.panelShape))
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(READER_LINK_OFFER_TAG),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.padding(FolioSpace.item),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            Text(
                stringResource(R.string.reader_link_offer, device, percent),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.small, Alignment.End),
            ) {
                TextButton(onClick = onStay) { Text(stringResource(R.string.reader_link_stay)) }
                FilledTonalButton(onClick = onContinue) {
                    Text(stringResource(R.string.reader_link_continue))
                }
            }
        }
    }
}

/**
 * The read-aloud player, floating over the page while listening: previous and next sentence,
 * play/pause, speed, and stop. It stays while the bars are hidden so the page can be read along.
 * [note] puts "Preparing narration…" or the chapter's preparation above the controls; play/pause
 * stays usable meanwhile, so preparation can always be paused.
 */
@Composable
internal fun ListenBar(
    playing: Boolean,
    starting: Boolean,
    speed: Double,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeed: () -> Unit,
    onStop: () -> Unit,
    timerOn: Boolean = false,
    onOptions: () -> Unit = {},
    note: ListenNote? = null,
) {
    Column(
        modifier.testTag(READER_LISTEN_BAR_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        if (note != null) ListenNoteChip(note)
        ListenControls(
            playing,
            starting,
            speed,
            onToggle,
            onPrevious,
            onNext,
            onSpeed,
            onStop,
            timerOn,
            onOptions,
        )
    }
}

@Composable
private fun ListenControls(
    playing: Boolean,
    starting: Boolean,
    speed: Double,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeed: () -> Unit,
    onStop: () -> Unit,
    timerOn: Boolean,
    onOptions: () -> Unit,
) {
    // Material's floating toolbar: the player's controls in one rounded, raised strip.
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
    ) {
        val speedLabel = listenSpeedLabel(speed)
        val speedName = stringResource(R.string.listen_speed, speedLabel)
        TextButton(
            onClick = onSpeed,
            modifier = Modifier.semantics { contentDescription = speedName },
        ) {
            Text(speedLabel, style = MaterialTheme.typography.titleMedium)
        }
        val previous = stringResource(R.string.listen_previous)
        Tooltipped(previous) {
            IconButton(onClick = onPrevious, enabled = !starting) {
                Icon(Icons.Outlined.SkipPrevious, previous)
            }
        }
        FilledIconButton(onClick = onToggle, enabled = !starting, modifier = Modifier.size(56.dp)) {
            if (starting) LoadingIndicator(Modifier.size(32.dp))
            else if (playing) Icon(Icons.Outlined.Pause, stringResource(R.string.listen_pause))
            else Icon(Icons.Outlined.PlayArrow, stringResource(R.string.listen_play))
        }
        val next = stringResource(R.string.listen_next)
        Tooltipped(next) {
            IconButton(onClick = onNext, enabled = !starting) {
                Icon(Icons.Outlined.SkipNext, next)
            }
        }
        // Voice and sleep timer; a moon while a timer runs.
        val options = stringResource(R.string.listen_options)
        Tooltipped(options) {
            IconButton(onClick = onOptions) {
                if (timerOn)
                    Icon(Icons.Filled.Bedtime, options, tint = MaterialTheme.colorScheme.primary)
                else Icon(Icons.Outlined.Tune, options)
            }
        }
        val stop = stringResource(R.string.listen_stop)
        Tooltipped(stop) { IconButton(onClick = onStop) { Icon(Icons.Outlined.Close, stop) } }
    }
}

@Composable
private fun sleepLabel(timer: SleepTimer): String =
    when (timer) {
        SleepTimer.OFF -> stringResource(R.string.listen_sleep_off)
        SleepTimer.CHAPTER -> stringResource(R.string.listen_sleep_chapter)
        else -> stringResource(R.string.listen_sleep_minutes, timer.minutes)
    }

/** What the Listening sheet shows about the natural voice. */
internal class NaturalVoiceRow(
    /** The book is in English, so Kokoro can read it. */
    val fits: Boolean,
    val state: NaturalVoice?,
    /** Kokoro is the voice speaking now. */
    val speaking: Boolean,
    /** How fast this phone makes Kokoro's speech compared with reading it; null until measured. */
    val pace: Double? = null,
    /** Listening seconds made ahead of the voice while it speaks; null otherwise. */
    val readyAheadSeconds: Int? = null,
    /** The model that speaks the natural voice. */
    val variant: KokoroModel.Variant = KokoroModel.Variant.INT8,
    /** Where the faster (fp32) model stands on this phone; [state] is the standard one. */
    val faster: NaturalVoice? = null,
    /** "Record read-aloud measurements" is on. */
    val recording: Boolean = false,
    /** A finished recording exists to share. */
    val hasRecording: Boolean = false,
) {
    /** At least one model is installed, so its voices can be chosen. */
    val installed: Boolean
        get() = state == NaturalVoice.Ready || faster == NaturalVoice.Ready
}

/** What the natural voice's model, test and measurement rows in the Listening sheet do. */
internal class NarrationActions(
    val onVariant: (KokoroModel.Variant) -> Unit = {},
    val onDownloadFaster: () -> Unit = {},
    val onTest: () -> Unit = {},
    val onRecord: (Boolean) -> Unit = {},
    val onShareRecording: () -> Unit = {},
)

/**
 * Listening options: the sleep timer, the natural voice (Kokoro: download, then its voices, how far
 * ahead it is, and which model makes it) and the phone's voices for the book's language. More phone
 * voices can be added in Android's text-to-speech settings through [onAddVoices]. Once a natural
 * model is installed, the end of the sheet offers the speed test and the measurement recording.
 */
@Composable
internal fun ListenOptionsSheet(
    sleep: SleepTimer,
    voices: List<ListenVoice>,
    voice: String?,
    onSleep: (SleepTimer) -> Unit,
    onVoice: (String?) -> Unit,
    onAddVoices: () -> Unit,
    natural: NaturalVoiceRow = NaturalVoiceRow(fits = false, state = null, speaking = false),
    onDownload: () -> Unit = {},
    narration: NarrationActions = NarrationActions(),
) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = FolioSpace.section)
            .padding(bottom = 32.dp)
            .testTag(READER_LISTEN_SHEET_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        Text(
            stringResource(R.string.listen_title),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(stringResource(R.string.listen_sleep), style = MaterialTheme.typography.titleMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            SleepTimer.entries.forEach { timer ->
                FilterChip(
                    selected = timer == sleep,
                    onClick = { onSleep(timer) },
                    label = { Text(sleepLabel(timer)) },
                )
            }
        }
        if (natural.fits) {
            Spacer(Modifier.height(FolioSpace.small))
            Text(
                stringResource(R.string.listen_natural),
                style = MaterialTheme.typography.titleMedium,
            )
            NaturalVoiceSection(
                if (natural.installed) NaturalVoice.Ready else natural.state,
                voice,
                onVoice,
                onDownload,
            )
            natural.pace?.let { pace ->
                Text(
                    stringResource(
                        if (pace >= 1.0) R.string.listen_pace else R.string.listen_pace_slow,
                        formatPace(pace, LocalConfiguration.current.locales[0]),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            natural.readyAheadSeconds?.let {
                Text(
                    stringResource(R.string.listen_ready_ahead, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (natural.installed) NaturalModelChoice(natural, narration)
        }
        Spacer(Modifier.height(FolioSpace.small))
        Text(
            stringResource(
                if (natural.fits) R.string.listen_phone_voices else R.string.listen_voice
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Column(Modifier.selectableGroup()) {
            VoiceRow(
                stringResource(R.string.listen_voice_default),
                null,
                selected = !natural.speaking && voice == null,
                onClick = { onVoice(null) },
            )
            voices.forEachIndexed { index, it ->
                VoiceRow(
                    voiceName(it, voices, index),
                    listOfNotNull(
                            stringResource(R.string.listen_voice_high).takeIf { _ ->
                                it.highQuality
                            },
                            stringResource(R.string.listen_voice_network).takeIf { _ ->
                                it.needsNetwork
                            },
                        )
                        .joinToString(" · ")
                        .ifEmpty { null },
                    selected = voice == it.id,
                    onClick = { onVoice(it.id) },
                )
            }
        }
        TextButton(onClick = onAddVoices) { Text(stringResource(R.string.listen_add_voices)) }
        if (natural.fits && natural.installed) {
            HorizontalDivider(Modifier.padding(vertical = FolioSpace.small))
            NarrationTools(natural, narration)
        }
    }
}

/**
 * Which model makes the natural voice: the standard 8-bit one, or the faster, larger one, offered
 * for download here when it is missing.
 */
@Composable
private fun NaturalModelChoice(natural: NaturalVoiceRow, actions: NarrationActions) {
    Text(
        stringResource(R.string.listen_variant),
        Modifier.padding(top = FolioSpace.small),
        style = MaterialTheme.typography.titleSmall,
    )
    Column(Modifier.selectableGroup()) {
        VoiceRow(
            stringResource(R.string.listen_variant_standard),
            null,
            selected = natural.variant == KokoroModel.Variant.INT8,
            onClick = { actions.onVariant(KokoroModel.Variant.INT8) },
            enabled = natural.state == NaturalVoice.Ready,
        )
        VoiceRow(
            stringResource(R.string.listen_variant_faster),
            null,
            selected = natural.variant == KokoroModel.Variant.FP32,
            onClick = { actions.onVariant(KokoroModel.Variant.FP32) },
            enabled = natural.faster == NaturalVoice.Ready,
        )
    }
    when (val faster = natural.faster) {
        NaturalVoice.Ready -> Unit
        is NaturalVoice.Downloading -> {
            LinearProgressIndicator(
                progress = { faster.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(
                    R.string.listen_natural_downloading,
                    (faster.fraction * 100).toInt(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NaturalVoice.Unpacking -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.listen_natural_unpacking),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> {
            if (faster == NaturalVoice.Failed)
                Text(
                    stringResource(R.string.listen_natural_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            FilledTonalButton(onClick = actions.onDownloadFaster) {
                Text(
                    stringResource(
                        R.string.listen_natural_download,
                        megabytes(KokoroModel.Variant.FP32.downloadBytes),
                    )
                )
            }
        }
    }
}

/** The speed test, and the switch that records measurements while listening. */
@Composable
private fun NarrationTools(natural: NaturalVoiceRow, actions: NarrationActions) {
    TextButton(onClick = actions.onTest) { Text(stringResource(R.string.listen_test)) }
    SheetSwitch(
        stringResource(R.string.listen_record),
        natural.recording,
        actions.onRecord,
        inset = 4.dp,
    )
    if (natural.hasRecording)
        TextButton(onClick = actions.onShareRecording) {
            Text(stringResource(R.string.listen_record_share))
        }
}

/** The natural voice: an offer to download it, its progress, or its voices once it is here. */
@Composable
private fun NaturalVoiceSection(
    state: NaturalVoice?,
    voice: String?,
    onVoice: (String?) -> Unit,
    onDownload: () -> Unit,
) {
    when (state) {
        NaturalVoice.Ready ->
            Column(Modifier.selectableGroup()) {
                NATURAL_VOICES.forEach {
                    VoiceRow(
                        it.name.orEmpty(),
                        it.locale.getDisplayName(it.locale),
                        selected = voice == it.id,
                        onClick = { onVoice(it.id) },
                    )
                }
            }
        is NaturalVoice.Downloading -> {
            LinearProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.listen_natural_downloading, (state.fraction * 100).toInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NaturalVoice.Unpacking -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.listen_natural_unpacking),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> {
            Text(
                stringResource(R.string.listen_natural_offer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state == NaturalVoice.Failed)
                Text(
                    stringResource(R.string.listen_natural_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            FilledTonalButton(onClick = onDownload) {
                Text(
                    stringResource(
                        R.string.listen_natural_download,
                        (KokoroModel.DOWNLOAD_BYTES / 1_000_000).toInt(),
                    )
                )
            }
        }
    }
}

/** "English (United Kingdom) 2": the language and, when it has several voices, which one. */
private fun voiceName(voice: ListenVoice, voices: List<ListenVoice>, index: Int): String {
    val same = voices.filter { it.locale == voice.locale }
    val place = voices.take(index + 1).count { it.locale == voice.locale }
    val language = voice.locale.getDisplayName(voice.locale).replaceFirstChar { it.titlecase() }
    return if (same.size > 1) "$language $place" else language
}

@Composable
private fun VoiceRow(
    title: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(FolioSpace.tileShape)
            .selectable(
                selected = selected,
                onClick = onClick,
                enabled = enabled,
                role = Role.RadioButton,
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            if (detail != null)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

/** The book would not open: one title, the reason, and the way back. */
@Composable
internal fun ReaderFailed(reason: String, onBack: () -> Unit, backEnabled: Boolean) {
    Surface(Modifier.fillMaxSize().testTag(READER_FAILED_TAG)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                stringResource(R.string.reader_open_failed),
                reason,
                Icons.Outlined.MenuBook,
                action = onBack,
                actionLabel = stringResource(R.string.reader_back),
                actionEnabled = backEnabled,
                illustration = FolioIllustration.Books,
            )
        }
    }
}
