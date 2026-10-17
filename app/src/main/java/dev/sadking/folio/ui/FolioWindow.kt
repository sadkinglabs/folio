package dev.sadking.folio.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlin.math.roundToInt

/** Material's window width classes. */
enum class WidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

/** Material breakpoints: under 600 dp compact, under 840 dp medium, else expanded. */
fun widthClass(widthDp: Int): WidthClass =
    when {
        widthDp < 600 -> WidthClass.COMPACT
        widthDp < 840 -> WidthClass.MEDIUM
        else -> WidthClass.EXPANDED
    }

/** The window the app draws in: its width class and size, and a fold's hinge when there is one. */
data class FolioWindow(
    val width: WidthClass,
    val widthDp: Int,
    val heightDp: Int,
    /** A vertical, separating hinge (book posture): where it is, in dp from the left. */
    val hingeDp: Int? = null,
    /** The hinge's own width: 0 on a folding screen, wider between two screens. */
    val hingeWidthDp: Int = 0,
) {
    /** How the app's frame is laid out in this window. */
    val layout: FolioLayout
        get() =
            when {
                width == WidthClass.COMPACT -> FolioLayout.BAR
                width == WidthClass.EXPANDED || hingeDp != null -> FolioLayout.TWO_PANE
                else -> FolioLayout.RAIL
            }

    /** Lists and their details side by side. */
    val twoPane: Boolean
        get() = layout == FolioLayout.TWO_PANE
}

/** A window of [widthDp] x [heightDp], with a hinge only where it can split the window in two. */
fun folioWindow(widthDp: Int, heightDp: Int, hingeDp: Int? = null, hingeWidthDp: Int = 0) =
    FolioWindow(
        widthClass(widthDp),
        widthDp,
        heightDp,
        hingeDp?.takeIf { it in 1 until widthDp },
        if (hingeDp != null) hingeWidthDp.coerceAtLeast(0) else 0,
    )

/**
 * The app's frame per window:
 * - [BAR]: a phone. The bottom bar, one screen at a time, exactly as before tablets were supported.
 * - [RAIL]: 600 to 839 dp wide. A navigation rail, one pane, centred at most 640 dp wide.
 * - [TWO_PANE]: 840 dp or wider, or a fold's hinge down the middle. A rail, and a list beside the
 *   chosen item.
 */
enum class FolioLayout {
    BAR,
    RAIL,
    TWO_PANE,
}

/** The width of one centred pane on a rail layout. */
internal const val PANE_MAX_WIDTH_DP = 640

/** The Device tab and a book over Device or Settings stay readable on a wide screen. */
internal const val WIDE_PANE_MAX_WIDTH_DP = 720

/** The narrowest a pane may be beside another: two cover columns, or a list with its titles. */
internal const val MIN_PANE_DP = 240

/**
 * The left pane's width beside a detail, for panes that start [paneLeftDp] from the window's left
 * edge (after the rail). Up to a hinge when there is one; otherwise 40% of the room, kept between
 * 360 and 400 dp.
 */
fun listPaneWidthDp(window: FolioWindow, paneLeftDp: Int): Int {
    val room = window.widthDp - paneLeftDp
    val hinge = window.hingeDp
    if (hinge != null) {
        val beforeHinge = hinge - paneLeftDp
        if (beforeHinge >= MIN_PANE_DP && room - beforeHinge >= MIN_PANE_DP) return beforeHinge
    }
    return (room * 2 / 5).coerceIn(360, 400).coerceAtMost((room - MIN_PANE_DP).coerceAtLeast(0))
}

/**
 * The hinge of one folding feature, in dp, when it is vertical and separates the window: the left
 * edge and the width. Null for a flat fold, a horizontal one (tabletop), or a feature with no width
 * and no separation.
 */
fun verticalHinge(
    separating: Boolean,
    vertical: Boolean,
    leftPx: Int,
    rightPx: Int,
    density: Float,
): Pair<Int, Int>? {
    if (!separating || !vertical || density <= 0f) return null
    val left = (leftPx / density).roundToInt()
    val width = ((rightPx - leftPx).coerceAtLeast(0) / density).roundToInt()
    return left to width
}

/** The window as the app sees it; phone-sized until something provides the real one. */
val LocalFolioWindow: ProvidableCompositionLocal<FolioWindow> = compositionLocalOf {
    folioWindow(412, 892)
}

/**
 * The current window: its size from [space] when given (the room the app is drawn in), else the
 * configuration (so rotation and folding recompose), and the fold's hinge from Jetpack
 * WindowManager while the screen is half open like a book. Previews and tests have no activity and
 * so no hinge.
 */
@Composable
fun rememberFolioWindow(space: DpSize? = null): FolioWindow {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val activity = remember(context) { context.findActivity() }
    val hinge by
        produceState<Pair<Int, Int>?>(null, activity, density) {
            val host = activity ?: return@produceState
            runCatching {
                WindowInfoTracker.getOrCreate(host).windowLayoutInfo(host).collect { info ->
                    value =
                        info.displayFeatures
                            .filterIsInstance<FoldingFeature>()
                            .firstNotNullOfOrNull {
                                verticalHinge(
                                    it.isSeparating,
                                    it.orientation == FoldingFeature.Orientation.VERTICAL,
                                    it.bounds.left,
                                    it.bounds.right,
                                    density,
                                )
                            }
                }
            }
        }
    // Rounded, not truncated: a forced or scaled 840 dp window measures 839.99 dp.
    val widthDp = space?.width?.value?.roundToInt() ?: configuration.screenWidthDp
    val heightDp = space?.height?.value?.roundToInt() ?: configuration.screenHeightDp
    return remember(widthDp, heightDp, hinge) {
        folioWindow(
            widthDp,
            heightDp,
            hinge?.first,
            hinge?.second ?: 0,
        )
    }
}

/** Provides [rememberFolioWindow] to [content]. */
@Composable
fun ProvideFolioWindow(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFolioWindow provides rememberFolioWindow(), content = content)
}

/** The activity behind [this] context, unwrapping wrappers; null in previews. */
internal tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
