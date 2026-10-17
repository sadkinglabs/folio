package dev.sadking.folio.reader

import android.content.Context
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Reading comfort (M11 B) on the JVM: settings kept, and where a tap turns the page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderComfortTest {
    private val prefs =
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("reader-comfort-test", Context.MODE_PRIVATE)

    @Test
    fun missingPreferencesGiveTheDefaults() {
        prefs.edit().clear().commit()
        val comfort = ReaderComfort.load(prefs)
        assertEquals(ReaderComfort(), comfort)
        // The phone's brightness, no warmth, the screen kept on, edges, no animation.
        assertTrue(comfort.autoBrightness)
        assertEquals(0f, comfort.warmth, 0f)
        assertTrue(comfort.keepScreenOn)
        assertEquals(TapZones.EDGES, comfort.tapZones)
        assertFalse(comfort.leftHanded)
        assertFalse(comfort.pageAnimation)
    }

    @Test
    fun choicesSurviveAReopen() {
        prefs.edit().clear().commit()
        val chosen =
            ReaderComfort(
                autoBrightness = false,
                brightness = 0.25f,
                warmth = 0.75f,
                keepScreenOn = false,
                tapZones = TapZones.HALVES,
                leftHanded = true,
                pageAnimation = true,
            )
        ReaderComfort.save(prefs, chosen)
        assertEquals(chosen, ReaderComfort.load(prefs))
    }

    @Test
    fun theTextSettingsAreLeftAlone() {
        prefs.edit().clear().putFloat("fontSize", 130f).putString("theme", "Paper").commit()
        ReaderComfort.save(prefs, ReaderComfort(warmth = 0.5f))
        assertEquals(130f, prefs.getFloat("fontSize", 0f), 0f)
        assertEquals("Paper", prefs.getString("theme", null))
    }

    @Test
    fun valuesOutOfRangeAreBounded() {
        prefs.edit().clear().putFloat("brightness", 0f).putFloat("warmth", 3f).commit()
        val loaded = ReaderComfort.load(prefs)
        assertEquals(ReaderComfort.MIN_BRIGHTNESS, loaded.brightness, 0f)
        assertEquals(1f, loaded.warmth, 0f)
        // An unknown tap-zone name falls back to edges.
        prefs.edit().putString("tapZones", "CORNERS").commit()
        assertEquals(TapZones.EDGES, ReaderComfort.load(prefs).tapZones)
    }

    @Test
    fun autoLeavesBrightnessToThePhone() {
        assertEquals(
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            ReaderComfort(autoBrightness = true, brightness = 0.3f).windowBrightness(),
            0f,
        )
        assertEquals(
            0.3f,
            ReaderComfort(autoBrightness = false, brightness = 0.3f).windowBrightness(),
            0f,
        )
    }

    @Test
    fun warmthOffDrawsNothing() {
        assertNull(warmthTint(0f))
        val half = warmthTint(0.5f)!!
        val full = warmthTint(1f)!!
        assertTrue(half.alpha < full.alpha)
        assertTrue(full.alpha < 1f)
    }

    private val width = 1000f

    private fun tap(x: Float, zones: TapZones, leftHanded: Boolean = false, rtl: Boolean = false) =
        tapAction(x, width, zones, leftHanded, rtl)

    @Test
    fun edgesTurnAndTheCentreShowsTheControls() {
        assertEquals(TapAction.PREVIOUS, tap(50f, TapZones.EDGES))
        assertEquals(TapAction.PREVIOUS, tap(300f, TapZones.EDGES))
        assertEquals(TapAction.CONTROLS, tap(301f, TapZones.EDGES))
        assertEquals(TapAction.CONTROLS, tap(500f, TapZones.EDGES))
        assertEquals(TapAction.CONTROLS, tap(699f, TapZones.EDGES))
        assertEquals(TapAction.NEXT, tap(700f, TapZones.EDGES))
        assertEquals(TapAction.NEXT, tap(990f, TapZones.EDGES))
    }

    @Test
    fun halvesTurnAlmostEverywhere() {
        assertEquals(TapAction.PREVIOUS, tap(100f, TapZones.HALVES))
        assertEquals(TapAction.PREVIOUS, tap(400f, TapZones.HALVES))
        assertEquals(TapAction.CONTROLS, tap(450f, TapZones.HALVES))
        assertEquals(TapAction.CONTROLS, tap(550f, TapZones.HALVES))
        assertEquals(TapAction.NEXT, tap(600f, TapZones.HALVES))
        assertEquals(TapAction.NEXT, tap(900f, TapZones.HALVES))
        // Edges' centre is wider: 350 shows the controls there but turns here.
        assertEquals(TapAction.CONTROLS, tap(350f, TapZones.EDGES))
        assertEquals(TapAction.PREVIOUS, tap(350f, TapZones.HALVES))
    }

    @Test
    fun leftHandedSwapsTheSidesButNotTheCentre() {
        for (zones in TapZones.entries) {
            assertEquals(TapAction.NEXT, tap(50f, zones, leftHanded = true))
            assertEquals(TapAction.PREVIOUS, tap(950f, zones, leftHanded = true))
            assertEquals(TapAction.CONTROLS, tap(500f, zones, leftHanded = true))
        }
    }

    @Test
    fun aRightToLeftBookTurnsForwardOnTheLeft() {
        assertEquals(TapAction.NEXT, tap(50f, TapZones.EDGES, rtl = true))
        assertEquals(TapAction.PREVIOUS, tap(950f, TapZones.EDGES, rtl = true))
        // Left-handed reverses it again.
        assertEquals(TapAction.PREVIOUS, tap(50f, TapZones.EDGES, leftHanded = true, rtl = true))
    }

    @Test
    fun narrowPagesKeepACentre() {
        // Readium's 80-pixel minimum would leave no centre on a 200-pixel page.
        assertEquals(TapAction.CONTROLS, tapAction(100f, 200f, TapZones.EDGES, false))
        assertEquals(TapAction.PREVIOUS, tapAction(10f, 200f, TapZones.EDGES, false))
        // Before the page is laid out, a tap only shows the controls.
        assertEquals(TapAction.CONTROLS, tapAction(10f, 0f, TapZones.HALVES, false))
    }
}
