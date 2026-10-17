@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.reader

import android.content.SharedPreferences
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.ui.FolioSpace
import dev.sadking.folio.ui.rememberHaptics
import kotlin.math.roundToInt
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ReadingProgression

internal const val READER_COMFORT_TAG = "reader-comfort"
internal const val READER_BRIGHTNESS_TAG = "reader-brightness"
internal const val READER_WARMTH_TAG = "reader-warmth"
internal const val READER_WARMTH_OVERLAY_TAG = "reader-warmth-overlay"

/** Where a tap on a paged book turns the page. */
enum class TapZones {
    /** The left and right halves turn; a narrow strip in the middle shows the controls. */
    HALVES,
    /** The outer 30% on each side turns, as Readium does; the centre shows the controls. */
    EDGES,
}

/**
 * The reader's screen and page-turn choices. They are the reader's own, kept in its preferences
 * with the text settings, and touch nothing system-wide: brightness and the screen staying on apply
 * to the reader's window only, so they end when another app or the library is in front.
 */
data class ReaderComfort(
    /** The phone's own brightness; [brightness] is then kept for the next time it is turned off. */
    val autoBrightness: Boolean = true,
    /** The reader's brightness, 0..1, used when [autoBrightness] is off. */
    val brightness: Float = 0.5f,
    /** Strength of the amber filter over the page, 0 (off) to 1. */
    val warmth: Float = 0f,
    /** The screen stays on while reading, until [IDLE_SLEEP_MS] pass without a page turn or tap. */
    val keepScreenOn: Boolean = true,
    val tapZones: TapZones = TapZones.EDGES,
    /** Reverses the tap sides: the left side turns forward. */
    val leftHanded: Boolean = false,
    /** Taps, volume keys and the bar's arrows slide the page instead of jumping. */
    val pageAnimation: Boolean = false,
    /** Sliding up or down along the left edge sets [warmth]; along the right edge, brightness. */
    val edgeSwipes: Boolean = true,
) {
    fun bounded() =
        copy(
            brightness = brightness.coerceIn(MIN_BRIGHTNESS, 1f),
            warmth = warmth.coerceIn(0f, 1f),
        )

    /** The value for the window's `screenBrightness`: the phone's own, or the reader's. */
    fun windowBrightness(): Float =
        if (autoBrightness) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        else bounded().brightness

    companion object {
        /** Low enough for a dark room, high enough that the page never goes black. */
        const val MIN_BRIGHTNESS = 0.02f

        /**
         * With nothing touched or turned for this long, the screen may sleep as the phone's own
         * timeout says. A book left open on a table does not drain the battery.
         */
        const val IDLE_SLEEP_MS = 10 * 60 * 1000L

        fun load(prefs: SharedPreferences) =
            ReaderComfort(
                    autoBrightness = prefs.getBoolean("brightnessAuto", true),
                    brightness = prefs.getFloat("brightness", 0.5f),
                    warmth = prefs.getFloat("warmth", 0f),
                    keepScreenOn = prefs.getBoolean("keepScreenOn", true),
                    tapZones =
                        TapZones.entries.firstOrNull {
                            it.name == prefs.getString("tapZones", null)
                        } ?: TapZones.EDGES,
                    leftHanded = prefs.getBoolean("leftHanded", false),
                    pageAnimation = prefs.getBoolean("pageAnimation", false),
                    edgeSwipes = prefs.getBoolean("edgeSwipes", true),
                )
                .bounded()

        fun save(prefs: SharedPreferences, value: ReaderComfort) {
            val bounded = value.bounded()
            prefs
                .edit()
                .putBoolean("brightnessAuto", bounded.autoBrightness)
                .putFloat("brightness", bounded.brightness)
                .putFloat("warmth", bounded.warmth)
                .putBoolean("keepScreenOn", bounded.keepScreenOn)
                .putString("tapZones", bounded.tapZones.name)
                .putBoolean("leftHanded", bounded.leftHanded)
                .putBoolean("pageAnimation", bounded.pageAnimation)
                .putBoolean("edgeSwipes", bounded.edgeSwipes)
                .apply()
        }
    }
}

/** What a tap on the page does. */
enum class TapAction {
    PREVIOUS,
    NEXT,
    CONTROLS,
}

/** Readium's own edge rule: 30% of the width, and never less than this many pixels. */
private const val EDGE_FRACTION = 0.3f
private const val EDGE_MIN_PX = 80f

/** In halves, the middle strip that shows the controls: 20% of the width. */
private const val HALVES_SIDE_FRACTION = 0.4f

/**
 * The action for a tap at [x] across a page [width] pixels wide. The left side goes back and the
 * right side forward; [leftHanded] swaps them, and so does a right-to-left book, where forward is
 * to the left. The middle always shows or hides the controls.
 */
fun tapAction(
    x: Float,
    width: Float,
    zones: TapZones,
    leftHanded: Boolean,
    rightToLeft: Boolean = false,
): TapAction {
    if (width <= 0f) return TapAction.CONTROLS
    val side =
        when (zones) {
            TapZones.EDGES -> maxOf(EDGE_MIN_PX, width * EDGE_FRACTION).coerceAtMost(width / 3)
            TapZones.HALVES -> width * HALVES_SIDE_FRACTION
        }
    val right =
        when {
            x <= side -> false
            x >= width - side -> true
            else -> return TapAction.CONTROLS
        }
    val forward = right != leftHanded != rightToLeft
    return if (forward) TapAction.NEXT else TapAction.PREVIOUS
}

/**
 * Page taps by [tapAction]. Scrolling books turn no pages on a tap: every tap shows or hides the
 * controls, as before. Points and width are both in the page view's pixels, as Readium's own
 * adapter compares them.
 */
internal class PageTaps(
    private val navigator: OverflowableNavigator,
    private val comfort: () -> ReaderComfort,
    private val onControls: () -> Unit,
) : InputListener {
    override fun onTap(event: TapEvent): Boolean {
        val overflow = navigator.overflow.value
        val choice = comfort()
        val action =
            if (overflow.scroll) TapAction.CONTROLS
            else
                tapAction(
                    event.point.x,
                    navigator.publicationView.width.toFloat(),
                    choice.tapZones,
                    choice.leftHanded,
                    overflow.readingProgression == ReadingProgression.RTL,
                )
        when (action) {
            TapAction.NEXT -> navigator.goForward(animated = choice.pageAnimation)
            TapAction.PREVIOUS -> navigator.goBackward(animated = choice.pageAnimation)
            TapAction.CONTROLS -> onControls()
        }
        return true
    }
}

/** The night-light amber: about 1900 K. */
private val AMBER = Color(0xFFFF9329)

/** The strongest filter, at full warmth. */
private const val WARMTH_MAX_ALPHA = 0.6f

/** The filter's colour at [warmth], or null when it is off. */
internal fun warmthTint(warmth: Float): Color? =
    if (warmth <= 0.005f) null else AMBER.copy(alpha = warmth.coerceIn(0f, 1f) * WARMTH_MAX_ALPHA)

/**
 * The warmth filter over the page. It multiplies, like a night light: white paper turns amber,
 * sepia warmer, and a black page stays black while its pale text warms. It draws only, so every
 * touch reaches the page beneath, and it sits under the bars and outside the sheets' windows.
 */
@Composable
internal fun WarmthOverlay(warmth: Float, modifier: Modifier = Modifier) {
    val tint = warmthTint(warmth) ?: return
    Box(
        modifier
            .testTag(READER_WARMTH_OVERLAY_TAG)
            .clearAndSetSemantics {}
            .drawBehind { drawRect(tint, blendMode = BlendMode.Multiply) }
    )
}

/**
 * Screen and page-turn choices, under the Text sheet's own: brightness with Auto, warmth, keeping
 * the screen on, then tap zones, the left-handed swap and the page-turn animation. [scrolling] dims
 * the tap choices, which apply to pages only.
 */
@Composable
internal fun ReaderComfortSection(
    comfort: ReaderComfort,
    scrolling: Boolean,
    onChange: (ReaderComfort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Column(
        modifier.fillMaxWidth().testTag(READER_COMFORT_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        SectionHeading(stringResource(R.string.reader_comfort_screen))
        val brightnessName = stringResource(R.string.reader_brightness)
        val autoName = stringResource(R.string.reader_brightness_auto)
        Column(Modifier.padding(horizontal = FolioSpace.section)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    brightnessName,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilterChip(
                    selected = comfort.autoBrightness,
                    onClick = {
                        haptics.tick()
                        onChange(comfort.copy(autoBrightness = !comfort.autoBrightness))
                    },
                    label = { Text(autoName) },
                )
            }
            Slider(
                value = comfort.brightness,
                onValueChange = {
                    onChange(comfort.copy(autoBrightness = false, brightness = it).bounded())
                },
                valueRange = ReaderComfort.MIN_BRIGHTNESS..1f,
                modifier =
                    Modifier.semantics {
                            contentDescription = brightnessName
                            if (comfort.autoBrightness) stateDescription = autoName
                        }
                        .testTag(READER_BRIGHTNESS_TAG),
                colors =
                    if (comfort.autoBrightness)
                        SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.outline,
                            activeTrackColor = MaterialTheme.colorScheme.outlineVariant,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            inactiveTickColor = MaterialTheme.colorScheme.outlineVariant,
                        )
                    else SliderDefaults.colors(),
            )
        }
        val warmthName = stringResource(R.string.reader_warmth)
        val warmthValue =
            if (warmthTint(comfort.warmth) == null) stringResource(R.string.reader_warmth_off)
            else stringResource(R.string.reader_warmth_value, (comfort.warmth * 100).roundToInt())
        Column(Modifier.padding(horizontal = FolioSpace.section)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    warmthName,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    warmthValue,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = comfort.warmth,
                onValueChange = { onChange(comfort.copy(warmth = it).bounded()) },
                modifier =
                    Modifier.semantics {
                            contentDescription = warmthName
                            stateDescription = warmthValue
                        }
                        .testTag(READER_WARMTH_TAG),
            )
        }
        SheetSwitch(
            stringResource(R.string.reader_keep_screen_on),
            comfort.keepScreenOn,
            { onChange(comfort.copy(keepScreenOn = it)) },
            detail = stringResource(R.string.reader_keep_screen_on_detail),
        )
        SheetSwitch(
            stringResource(R.string.reader_edge_swipes),
            comfort.edgeSwipes,
            { onChange(comfort.copy(edgeSwipes = it)) },
            detail = stringResource(R.string.reader_edge_swipes_detail),
        )
        SectionHeading(stringResource(R.string.reader_comfort_pages))
        Column(Modifier.selectableGroup()) {
            TapZoneRow(
                stringResource(R.string.reader_tap_edges),
                stringResource(R.string.reader_tap_edges_detail),
                selected = comfort.tapZones == TapZones.EDGES,
                enabled = !scrolling,
            ) {
                haptics.tick()
                onChange(comfort.copy(tapZones = TapZones.EDGES))
            }
            TapZoneRow(
                stringResource(R.string.reader_tap_halves),
                stringResource(R.string.reader_tap_halves_detail),
                selected = comfort.tapZones == TapZones.HALVES,
                enabled = !scrolling,
            ) {
                haptics.tick()
                onChange(comfort.copy(tapZones = TapZones.HALVES))
            }
        }
        if (scrolling)
            Text(
                stringResource(R.string.reader_tap_scroll_note),
                Modifier.padding(horizontal = FolioSpace.section),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        SheetSwitch(
            stringResource(R.string.reader_left_handed),
            comfort.leftHanded,
            { onChange(comfort.copy(leftHanded = it)) },
            detail = stringResource(R.string.reader_left_handed_detail),
            enabled = !scrolling,
        )
        SheetSwitch(
            stringResource(R.string.reader_page_animation),
            comfort.pageAnimation,
            { onChange(comfort.copy(pageAnimation = it)) },
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = FolioSpace.section)
            .padding(top = FolioSpace.small)
            .semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun TapZoneRow(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(FolioSpace.tileShape)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = FolioSpace.section, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
    }
}
