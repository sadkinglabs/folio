package dev.sadking.folio.reader

import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.ui.FolioMotion
import dev.sadking.folio.ui.FolioTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Edge slides (M11 B): a vertical slide that starts at the left edge of the page sets the warmth,
 * one at the right edge sets the brightness; up is more. The reader watches every touch before the
 * page does. A touch stays the page's until it moves clearly up or down from an edge strip soon
 * after it starts: then the page gets a cancel (so it is neither a tap, a page turn nor a
 * selection) and the slide is the reader's until the finger lifts. A tap at an edge, a sideways
 * swipe and the system's Back swipe, which is sideways too, all reach the page as before.
 */
enum class EdgeSide {
    /** Warmth. */
    LEFT,
    /** Brightness. */
    RIGHT,
}

internal const val READER_EDGE_LEVEL_TAG = "reader-edge-level"

/** The strip along each side where a slide may start: about a thumb's width. */
internal const val EDGE_STRIP_DP = 32

/** A slide over this share of the page's height goes from the lowest level to the highest. */
internal const val EDGE_SWIPE_SPAN = 0.6f

/** How long the level stays on screen after the finger lifts. */
internal const val EDGE_LEVEL_LINGER_MS = 900L

/** The side whose strip [x] is in, on a page [width] wide with strips [strip] wide; else null. */
internal fun edgeSide(x: Float, width: Float, strip: Float): EdgeSide? =
    when {
        width <= 0f || strip <= 0f || strip * 2 >= width -> null
        x <= strip -> EdgeSide.LEFT
        x >= width - strip -> EdgeSide.RIGHT
        else -> null
    }

/**
 * The level after sliding [travel] pixels up (down when negative) on a page [height] tall, from
 * [start]; it stays within [min]..1.
 */
internal fun edgeLevel(start: Float, travel: Float, height: Float, min: Float = 0f): Float {
    if (height <= 0f) return start.coerceIn(min, 1f)
    return (start + travel / (height * EDGE_SWIPE_SPAN)).coerceIn(min, 1f)
}

/**
 * The comfort after a slide of [travel] pixels on [side], from [start] (the comfort when the slide
 * began). The right edge leaves Auto for the reader's own brightness, starting from the last one
 * the owner chose.
 */
internal fun edgeSwiped(
    start: ReaderComfort,
    side: EdgeSide,
    travel: Float,
    height: Float,
): ReaderComfort =
    when (side) {
        EdgeSide.LEFT -> start.copy(warmth = edgeLevel(start.warmth, travel, height))
        EdgeSide.RIGHT ->
            start.copy(
                autoBrightness = false,
                brightness =
                    edgeLevel(start.brightness, travel, height, ReaderComfort.MIN_BRIGHTNESS),
            )
    }

/** What the reader does with one touch event while it watches for an edge slide. */
internal enum class EdgeStep {
    /** The page's: hand it on. */
    PASS,
    /** The slide starts here: cancel the touch on the page and keep the rest. */
    CLAIM,
    /** The slide moved: [EdgeSwipeTracker.travel] has the distance. */
    DRAG,
    /** The finger lifted (or the system took the touch): keep the level. */
    END,
}

/**
 * Follows one touch at a time and decides when it is an edge slide. [strip] and [slop] are pixels;
 * a touch that has not moved clearly up or down within [claimWithinMs] stays the page's, so a long
 * press at an edge still selects text.
 */
internal class EdgeSwipeTracker(
    private val strip: Float,
    private val slop: Float,
    private val claimWithinMs: Long,
) {
    /** The side being slid on, while a slide is followed. */
    var side: EdgeSide? = null
        private set

    /** How far the finger has moved up since the slide began, in pixels; negative is down. */
    var travel = 0f
        private set

    private var candidate: EdgeSide? = null
    private var claimed = false
    private var startX = 0f
    private var startY = 0f
    private var downAt = 0L

    /**
     * The step for one event: its masked [action], the first finger's [x] and [y] on a page [width]
     * wide, its [time] and how many [pointers] are down. [enabled] is checked when a touch starts;
     * a slide already followed goes on to its end.
     */
    fun onTouch(
        action: Int,
        x: Float,
        y: Float,
        width: Float,
        time: Long,
        pointers: Int,
        enabled: Boolean,
    ): EdgeStep {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                if (enabled && pointers == 1) {
                    candidate = edgeSide(x, width, strip)
                    startX = x
                    startY = y
                    downAt = time
                }
                return EdgeStep.PASS
            }
            MotionEvent.ACTION_MOVE -> {
                if (claimed) {
                    travel = startY - y
                    return EdgeStep.DRAG
                }
                val from = candidate ?: return EdgeStep.PASS
                val dx = abs(x - startX)
                val dy = abs(y - startY)
                when {
                    pointers > 1 || time - downAt > claimWithinMs -> candidate = null
                    // Decided once the touch has moved: clearly up or down is a slide; any other
                    // way stays the page's for good.
                    dx > slop || dy > slop ->
                        if (dy > dx * 2) {
                            claimed = true
                            side = from
                            // Measured from where the slide was claimed, so the level does not
                            // jump.
                            startY = y
                            travel = 0f
                            return EdgeStep.CLAIM
                        } else candidate = null
                }
                return EdgeStep.PASS
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val wasClaimed = claimed
                reset()
                return if (wasClaimed) EdgeStep.END else EdgeStep.PASS
            }
            else -> {
                // A second finger: the slide keeps following the first; a page touch stays its.
                if (pointers > 1) candidate = null
                return if (claimed) EdgeStep.DRAG else EdgeStep.PASS
            }
        }
    }

    private fun reset() {
        candidate = null
        claimed = false
        travel = 0f
    }
}

/**
 * The level shown while sliding along an edge: the setting's icon, a bar and the percentage. It
 * only draws; touches go to the page.
 */
@Composable
internal fun EdgeLevelIndicator(
    side: EdgeSide?,
    comfort: ReaderComfort,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && side != null,
        modifier = modifier,
        enter = fadeIn(FolioMotion.standardSpec()),
        exit = fadeOut(FolioMotion.standardSpec()),
    ) {
        val shown = side ?: EdgeSide.RIGHT
        val level = if (shown == EdgeSide.LEFT) comfort.warmth else comfort.brightness
        val name =
            stringResource(
                if (shown == EdgeSide.LEFT) R.string.reader_warmth else R.string.reader_brightness
            )
        val percent = (level * 100).roundToInt()
        val label = stringResource(R.string.reader_edge_level, name, percent)
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            modifier =
                Modifier.testTag(READER_EDGE_LEVEL_TAG).clearAndSetSemantics {
                    contentDescription = label
                },
        ) {
            Row(
                Modifier.padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (shown == EdgeSide.LEFT) Icons.Outlined.WbTwilight
                    else Icons.Outlined.LightMode,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Box(
                    Modifier.width(120.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.3f))
                ) {
                    Box(
                        Modifier.fillMaxHeight()
                            .fillMaxWidth(level.coerceIn(0f, 1f))
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.inversePrimary)
                    )
                }
                Text(
                    stringResource(R.string.reader_warmth_value, percent),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.widthIn(min = 40.dp),
                )
            }
        }
    }
}

/**
 * A page while sliding up the left edge: the warmth over it and its level at the top. The second
 * level is the right edge's, for comparison.
 */
@Preview(name = "Reader edge level · light", widthDp = 412, heightDp = 892)
@Composable
internal fun ReaderEdgeLevelPreview() {
    val comfort = ReaderComfort(autoBrightness = false, brightness = 0.62f, warmth = 0.3f)
    FolioTheme(dynamic = false) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(top = 24.dp)) {
                ReaderPagePlaceholder(dark = isSystemInDarkTheme())
            }
            WarmthOverlay(comfort.warmth, Modifier.matchParentSize())
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EdgeLevelIndicator(EdgeSide.LEFT, comfort, true)
                EdgeLevelIndicator(EdgeSide.RIGHT, comfort, true)
            }
        }
    }
}
