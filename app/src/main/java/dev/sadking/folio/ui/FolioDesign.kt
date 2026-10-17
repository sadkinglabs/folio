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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/**
 * True when the system's "remove animations" setting is on (animator duration scale 0). Non-
 * essential motion (scale, slide, springs, the progress ring) is skipped; fades stay. Provided by
 * [FolioTheme]; defaults to full motion in previews and tests.
 */
internal val LocalReducedMotion = staticCompositionLocalOf { false }

/** Reads the system setting once per composition of the theme. */
@Composable
internal fun systemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
                android.provider.Settings.Global.getFloat(
                    context.contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                ) == 0f
            }
            .getOrDefault(false)
    }
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

    /** Whether to skip non-essential motion; see [LocalReducedMotion]. */
    val reduced: Boolean
        @Composable get() = LocalReducedMotion.current

    /** [spec], or a snap when motion is reduced. */
    @Composable
    fun <T> orSnap(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
        if (reduced) androidx.compose.animation.core.snap() else spec
}

/**
 * The app's haptics in one place, so every screen says the same thing: a long-press that opens
 * something or starts a selection, a tick on a stepper step or a chosen chip, a confirm when a send
 * is queued, a reject when work fails, and the switch feel on a toggle. Never on navigation.
 */
internal class FolioHaptics(private val feedback: HapticFeedback) {
    fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)

    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)

    fun reject() = feedback.performHapticFeedback(HapticFeedbackType.Reject)

    fun toggle(on: Boolean) =
        feedback.performHapticFeedback(
            if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
        )
}

@Composable
internal fun rememberHaptics(): FolioHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { FolioHaptics(feedback) }
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

internal fun observationDate(time: Long): String =
    java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT,
            java.text.DateFormat.SHORT,
        )
        .format(java.util.Date(time))

/**
 * [style] grown with the font scale only up to [cap]: for short labels under icons, where the icon
 * carries the meaning and a larger label would cut mid-word.
 */
@Composable
internal fun cappedTextStyle(
    style: androidx.compose.ui.text.TextStyle,
    cap: Float,
): androidx.compose.ui.text.TextStyle {
    val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    return if (scale <= cap) style
    else
        style.copy(
            fontSize = style.fontSize * (cap / scale),
            lineHeight = style.lineHeight * (cap / scale),
        )
}
