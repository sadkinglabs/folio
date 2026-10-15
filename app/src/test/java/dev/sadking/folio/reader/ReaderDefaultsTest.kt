package dev.sadking.folio.reader

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderDefaultsTest {
    private val prefs =
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("reader-defaults-test", Context.MODE_PRIVATE)

    @Test
    fun missingPreferencesGiveTheReaderFallbacks() {
        prefs.edit().clear().commit()
        assertEquals(ReaderTypography(), ReaderDefaults.typography(prefs))
        assertEquals("System", ReaderDefaults.pageTheme(prefs))
        assertTrue(ReaderDefaults.volumeKeys(prefs))
    }

    @Test
    fun savedValuesRoundTripUnderTheReaderKeys() {
        prefs.edit().clear().commit()
        // Values a float stores exactly, since the reader keeps them as floats.
        val value = ReaderTypography(130.0, false, true, 1.75, 0.5, true)
        ReaderDefaults.saveTypography(prefs, value)
        ReaderDefaults.savePageTheme(prefs, "Paper")
        ReaderDefaults.saveVolumeKeys(prefs, false)
        assertEquals(value, ReaderDefaults.typography(prefs))
        // The keys the reader itself reads.
        assertEquals(130f, prefs.getFloat("fontSize", 0f), 0f)
        assertFalse(prefs.getBoolean("serif", true))
        assertEquals("Paper", prefs.getString("theme", null))
        assertFalse(prefs.getBoolean("volumeKeys", true))
    }

    @Test
    fun outOfRangeValuesAreBoundedOnSave() {
        prefs.edit().clear().commit()
        ReaderDefaults.saveTypography(prefs, ReaderTypography(fontSize = 900.0, margins = -1.0))
        val saved = ReaderDefaults.typography(prefs)
        assertEquals(MAX_TEXT, saved.fontSize, 0.0)
        assertEquals(0.0, saved.margins, 0.0)
    }

    @Test
    fun aStoredValueSnapsToTheNearestChoice() {
        assertEquals(1.5, nearestChoice(1.55, LINE_SPACING_CHOICES), 0.0)
        assertEquals(1.8, nearestChoice(2.0, LINE_SPACING_CHOICES), 0.0)
        assertEquals(0.5, nearestChoice(0.0, MARGIN_CHOICES), 0.0)
    }
}
