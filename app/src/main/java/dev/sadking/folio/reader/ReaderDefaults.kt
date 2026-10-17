package dev.sadking.folio.reader

import android.content.SharedPreferences

/**
 * The reader's defaults for every book, as the Settings tab reads and edits them. Keys and
 * fallbacks match [ReaderViewModel], which reads the same preferences when a book opens.
 */
object ReaderDefaults {
    const val PREFERENCES = "reader"

    fun typography(prefs: SharedPreferences): ReaderTypography =
        ReaderTypography(
                prefs.getFloat("fontSize", 110f).toDouble(),
                prefs.getBoolean("serif", true),
                prefs.getBoolean("scroll", false),
                prefs.getFloat("lineHeight", 1.5f).toDouble(),
                prefs.getFloat("margins", 1.0f).toDouble(),
                prefs.getBoolean("publisherStyles", false),
                prefs.getBoolean("twoPages", true),
            )
            .bounded()

    fun saveTypography(prefs: SharedPreferences, value: ReaderTypography) {
        val bounded = value.bounded()
        prefs
            .edit()
            .putFloat("fontSize", bounded.fontSize.toFloat())
            .putBoolean("serif", bounded.serif)
            .putBoolean("scroll", bounded.scroll)
            .putFloat("lineHeight", bounded.lineHeight.toFloat())
            .putFloat("margins", bounded.margins.toFloat())
            .putBoolean("publisherStyles", bounded.publisherStyles)
            .putBoolean("twoPages", bounded.twoPages)
            .apply()
    }

    fun pageTheme(prefs: SharedPreferences): String = prefs.getString("theme", "System") ?: "System"

    fun savePageTheme(prefs: SharedPreferences, theme: String) {
        prefs.edit().putString("theme", theme).apply()
    }

    fun volumeKeys(prefs: SharedPreferences): Boolean = prefs.getBoolean("volumeKeys", true)

    fun saveVolumeKeys(prefs: SharedPreferences, on: Boolean) {
        prefs.edit().putBoolean("volumeKeys", on).apply()
    }
}

/** The three spacing choices the reader offers, and the stored value each stands for. */
val LINE_SPACING_CHOICES = listOf(1.3, 1.5, 1.8)

/** The three margin choices the reader offers, and the stored value each stands for. */
val MARGIN_CHOICES = listOf(0.5, 1.0, 1.6)

/** The choice closest to a stored value, so a value set elsewhere still selects one option. */
fun nearestChoice(value: Double, choices: List<Double>): Double =
    choices.minByOrNull { kotlin.math.abs(it - value) } ?: value
