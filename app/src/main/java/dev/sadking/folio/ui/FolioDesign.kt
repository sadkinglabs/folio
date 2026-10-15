package dev.sadking.folio.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.data.*

/** Shared rhythm, rather than per-screen dimensions and nested decorative cards. */
internal object FolioSpace {
    val small = 8.dp
    val gap = 12.dp
    val item = 16.dp
    val page = 20.dp
    val section = 24.dp
    /** Covers: 6 dp, 2:3, never cropped. */
    val coverShape = RoundedCornerShape(6.dp)
    /** The centre tile and other small lifted squares. */
    val tileShape = RoundedCornerShape(12.dp)
    val panelShape = RoundedCornerShape(20.dp)
    /** Sheets and the bottom bar: 28 dp top corners, flush bottom. */
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    /** The cover's width to height ratio. */
    const val COVER_RATIO = 2f / 3f
}

/** Durations and easings. 200–350 ms, never a loop. */
internal object FolioMotion {
    const val STANDARD = 200
    const val EMPHASIZED_IN = 350
    const val EMPHASIZED_OUT = 250
    const val MICRO = 120
    /** The centre tile's progress ring, when the reader closes. */
    const val RING = 600
    val standard: Easing = FastOutSlowInEasing
    val emphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> standardSpec(): FiniteAnimationSpec<T> = tween(STANDARD, easing = standard)

    fun <T> enterSpec(): FiniteAnimationSpec<T> =
        tween(EMPHASIZED_IN, easing = emphasizedDecelerate)

    fun <T> exitSpec(): FiniteAnimationSpec<T> =
        tween(EMPHASIZED_OUT, easing = emphasizedAccelerate)
}

/**
 * The shared-element scope of the shell, for covers that grow into pages. Null outside the shell
 * (previews, tests), so components fall back to no shared element.
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
internal val LocalSharedTransitionScope =
    staticCompositionLocalOf<androidx.compose.animation.SharedTransitionScope?> { null }

/**
 * How much of the screen's bottom the glass bar covers. Scrolling screens extend under the bar and
 * pad their content by this much instead, so the blur shows real content; fixed screens apply it as
 * ordinary padding.
 */
internal val LocalContentBottomPadding = compositionLocalOf { 0.dp }

/** Glass: the backdrop blurred, then a tint of `surface`, then a hairline on the content edge. */
internal object FolioGlass {
    val blurRadius = 24.dp

    @Composable
    fun tint(): Color {
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        return MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.64f else 0.72f)
    }

    /** The tint when nothing can be blurred: nearly opaque, same layout and copy. */
    @Composable fun flatTint(): Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)

    @Composable fun hairline(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
}

/**
 * Tier 3, "lifted": covers, the centre tile, sheets, menus. Soft ambient plus a spot shadow in
 * light; a deeper shadow in dark. Panels (tier 2) never use it. Chain it with `then`.
 */
@Composable
internal fun lift(shape: Shape, elevation: Dp = 6.dp): Modifier {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return Modifier.shadow(
        elevation = if (dark) elevation + 2.dp else elevation,
        shape = shape,
        ambientColor = Color.Black.copy(alpha = if (dark) 0.4f else 0.06f),
        spotColor = Color.Black.copy(alpha = if (dark) 0.4f else 0.14f),
    )
}

@Composable
internal fun BookHero(book: Book) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f
        if (stacked)
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
            ) {
                Cover(book, Modifier.width(132.dp).aspectRatio(0.68f))
                BookBibliography(book)
            }
        else
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.page),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(book, Modifier.width(116.dp).aspectRatio(0.68f))
                Column(Modifier.weight(1f)) { BookBibliography(book) }
            }
    }
}

@Composable
private fun BookBibliography(book: Book) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
        Text(book.title, style = MaterialTheme.typography.headlineSmall)
        Text(
            book.authorLabel.ifBlank { stringResource(R.string.shell_unknown_author) },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (book.series.isNotBlank())
            Text(
                listOf(book.series, book.seriesIndex)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
    }
}

@Composable
internal fun BookPrimaryActions(
    fileLabel: String,
    onFile: () -> Unit,
    onProgress: () -> Unit,
    enabled: Boolean,
    progressEnabled: Boolean = enabled,
    /** False when another action on the screen is primary: both buttons are then outlined. */
    prominent: Boolean = true,
) {
    @Composable
    fun FileButton(modifier: Modifier) {
        if (prominent)
            Button(onClick = onFile, enabled = enabled, modifier = modifier) { Text(fileLabel) }
        else
            OutlinedButton(onClick = onFile, enabled = enabled, modifier = modifier) {
                Text(fileLabel)
            }
    }
    @Composable
    fun ProgressButton(modifier: Modifier) {
        if (prominent)
            FilledTonalButton(
                onClick = onProgress,
                enabled = progressEnabled,
                modifier = modifier,
            ) {
                Text(stringResource(R.string.shell_update_progress))
            }
        else
            OutlinedButton(onClick = onProgress, enabled = progressEnabled, modifier = modifier) {
                Text(stringResource(R.string.shell_update_progress))
            }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f)
            Column(verticalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
                FileButton(Modifier.fillMaxWidth())
                ProgressButton(Modifier.fillMaxWidth())
            }
        else
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap)) {
                FileButton(Modifier.weight(1f))
                ProgressButton(Modifier.weight(1f))
            }
    }
}

@Composable
internal fun displayDeviceState(signals: BookSignals): String =
    when (signals.device) {
        "On Kindle" -> stringResource(R.string.shell_device_on_ereader)
        "Needs update" -> stringResource(R.string.shell_device_needs_update)
        "Phone only",
        "Not sent" -> stringResource(R.string.shell_device_not_sent)
        "Queued" -> stringResource(R.string.shell_device_queued)
        "Sending" -> stringResource(R.string.shell_device_sending)
        "Transfer paused" -> stringResource(R.string.shell_device_paused)
        "Transfer failed" -> stringResource(R.string.shell_device_failed)
        "No EPUB" -> stringResource(R.string.shell_device_no_epub)
        else -> signals.device
    }

internal fun observationDate(time: Long): String =
    java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT,
            java.text.DateFormat.SHORT,
        )
        .format(java.util.Date(time))

@Composable
internal fun BookStateSummary(signals: BookSignals) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = FolioSpace.panelShape) {
        Column(
            Modifier.fillMaxWidth().padding(FolioSpace.item),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            Text(signals.reading, style = MaterialTheme.typography.titleSmall)
            signals.progress?.let {
                LinearProgressIndicator(
                    progress = { it.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.shell_saved_progress, (it * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                displayDeviceState(signals),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            signals.deviceVerifiedAt?.let {
                Text(
                    stringResource(R.string.shell_last_confirmed, observationDate(it)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            signals.readingFetchedAt?.let {
                Text(
                    stringResource(R.string.shell_reading_checked, observationDate(it)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            signals.hardcover?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
internal fun BookListsSummary(names: List<String>, onManage: () -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.shell_lists), style = MaterialTheme.typography.titleMedium)
            Text(
                names.takeIf { it.isNotEmpty() }?.joinToString(" · ")
                    ?: stringResource(R.string.shell_no_lists),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onManage, enabled = enabled) {
            Text(stringResource(R.string.shell_manage_lists))
        }
    }
}
