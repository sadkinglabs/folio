package dev.sadking.folio.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.sadking.folio.R
import dev.sadking.folio.data.CurrentBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A tab in the bar. The selected tab shows the filled icon. */
private class BarTab(
    @androidx.annotation.StringRes val title: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val tabs =
    listOf(
        BarTab(R.string.shell_tab_library, Icons.Outlined.AutoStories, Icons.Filled.AutoStories),
        BarTab(
            R.string.shell_tab_lists,
            Icons.Outlined.CollectionsBookmark,
            Icons.Filled.CollectionsBookmark,
        ),
        BarTab(R.string.shell_tab_device, Icons.Outlined.Devices, Icons.Filled.Devices),
        BarTab(R.string.shell_settings, Icons.Outlined.Tune, Icons.Filled.Tune),
    )

/** How far the centre tile rises above the bar's top edge. */
private val tileLift = 10.dp
internal val nowReadingTileSize = 56.dp
internal const val NOW_READING_TILE_TAG = "now-reading-tile"
internal const val FOLIO_BAR_TAG = "folio-bar"
internal const val SELECTION_BAR_TAG = "selection-bar"

/**
 * The bottom bar: four tabs on glass with the Now Reading tile breaking its top edge. The tile is
 * an action, not a tab: tap continues reading, long-press (or the TalkBack action) opens the Now
 * Reading sheet. With no current book the tile is a book glyph and tap opens the sheet's empty
 * form.
 */
@Composable
internal fun FolioBar(
    tab: Int,
    onTab: (Int) -> Unit,
    current: CurrentBook?,
    onContinue: () -> Unit,
    onOptions: () -> Unit,
    transferActive: Boolean,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().testTag(FOLIO_BAR_TAG)) {
        GlassSurface(
            hazeState,
            Modifier.padding(top = tileLift).fillMaxWidth(),
            shape = FolioSpace.sheetShape,
        ) {
            NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                tabs.forEachIndexed { i, d ->
                    if (i == FolioNav.DEVICE) Spacer(Modifier.weight(1f))
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { onTab(i) },
                        icon = {
                            BadgedBox(
                                badge = { if (i == FolioNav.DEVICE && transferActive) Badge() }
                            ) {
                                Icon(if (tab == i) d.selectedIcon else d.icon, null)
                            }
                        },
                        label = { Text(stringResource(d.title), maxLines = 1) },
                    )
                }
            }
        }
        NowReadingTile(
            current,
            onClick = if (current != null) onContinue else onOptions,
            onOptions = onOptions,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * The bar while books are selected: the four tabs give way to Send, Add to list, Status and Remove
 * as icon-and-label actions on the same glass, at the same height, so the content under it does not
 * move.
 */
@Composable
internal fun SelectionActionBar(
    enabled: Boolean,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    onLists: () -> Unit,
    onStatus: () -> Unit,
    onRemove: () -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        hazeState,
        modifier.fillMaxWidth().padding(top = tileLift).testTag(SELECTION_BAR_TAG),
        shape = FolioSpace.sheetShape,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                .height(80.dp)
                .padding(horizontal = FolioSpace.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction(
                Icons.AutoMirrored.Outlined.Send,
                stringResource(R.string.rd_action_send),
                enabled && sendEnabled,
                onSend,
            )
            BarAction(
                Icons.Outlined.BookmarkAdd,
                stringResource(R.string.shell_add_to_list),
                enabled,
                onLists,
            )
            BarAction(
                Icons.Outlined.Timeline,
                stringResource(R.string.rd_action_status),
                enabled,
                onStatus,
            )
            BarAction(
                Icons.Outlined.DeleteOutline,
                stringResource(R.string.rd_action_remove),
                enabled,
                onRemove,
                destructive = true,
            )
        }
    }
}

@Composable
private fun RowScope.BarAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val tint =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            destructive -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        }
    Column(
        Modifier.weight(1f)
            .fillMaxHeight()
            .clip(FolioSpace.tileShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/**
 * The 56 dp centre tile: the current book's cover under a thin progress ring, lifted. Without a
 * current book, a book glyph on a tonal disc.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NowReadingTile(
    current: CurrentBook?,
    onClick: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val options = stringResource(R.string.rd_book_options)
    val percent = current?.percent?.let { (it * 100).toInt() }
    val description =
        when {
            current == null -> stringResource(R.string.rd_pick_a_book)
            percent == null -> stringResource(R.string.rd_continue_reading, current.book.title)
            else ->
                stringResource(R.string.rd_continue_reading_progress, current.book.title, percent)
        }
    val shape = if (current == null) CircleShape else FolioSpace.tileShape
    val ring by
        animateFloatAsState(
            (current?.percent ?: 0.0).toFloat().coerceIn(0f, 1f),
            tween(FolioMotion.RING, easing = FolioMotion.standard),
            label = "Now reading progress",
        )
    val ringColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.primaryContainer
    Box(
        modifier
            .size(nowReadingTileSize)
            .testTag(NOW_READING_TILE_TAG)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                customActions =
                    listOf(
                        CustomAccessibilityAction(options) {
                            onOptions()
                            true
                        }
                    )
            }
            .then(lift(shape, 8.dp))
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOptions()
                },
            )
            .then(
                if (current != null)
                    Modifier.drawWithContent {
                        drawContent()
                        // A thin ring just inside the tile's edge, drawn along its rounded outline.
                        val stroke = 2.5.dp.toPx()
                        val inset = stroke / 2
                        val rect =
                            RoundRect(
                                inset,
                                inset,
                                size.width - inset,
                                size.height - inset,
                                CornerRadius(12.dp.toPx() - inset),
                            )
                        val outline = Path().apply { addRoundRect(rect) }
                        drawPath(outline, trackColor, style = Stroke(stroke))
                        if (ring > 0f) {
                            val measure = PathMeasure().apply { setPath(outline, true) }
                            val segment = Path()
                            measure.getSegment(0f, measure.length * ring, segment, true)
                            drawPath(
                                segment,
                                ringColor,
                                style = Stroke(stroke, cap = StrokeCap.Round),
                            )
                        }
                    }
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (current == null)
            Icon(
                Icons.Outlined.MenuBook,
                null,
                Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        else TileCover(current)
    }
}

/** The cover cropped to the square tile; a glyph on the title's colour when there is no artwork. */
@Composable
private fun TileCover(current: CurrentBook) {
    val image by
        produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, current.book.coverPath) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { CoverImages.load(current.book.coverPath)?.asImageBitmap() }
                        .getOrNull()
                }
        }
    if (image != null)
        Image(image!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else
        Box(
            Modifier.fillMaxSize().background(generatedCoverColor(current.book.title)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.MenuBook,
                null,
                Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
}
