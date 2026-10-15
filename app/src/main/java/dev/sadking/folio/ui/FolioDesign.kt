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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
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
