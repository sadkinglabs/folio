package dev.sadking.folio.reader

import org.json.JSONObject

/**
 * Text settings a single book may override. The page theme and volume-button paging stay global:
 * they describe the reading environment, not the book.
 */
data class ReaderTypography(
    val fontSize: Double = 110.0,
    val serif: Boolean = true,
    val scroll: Boolean = false,
    val lineHeight: Double = 1.5,
    val margins: Double = 1.0,
    val publisherStyles: Boolean = false,
) {
    fun toJson(): String =
        JSONObject()
            .put("fontSize", fontSize)
            .put("serif", serif)
            .put("scroll", scroll)
            .put("lineHeight", lineHeight)
            .put("margins", margins)
            .put("publisherStyles", publisherStyles)
            .toString()

    /** Keeps every value inside the ranges the reader offers. */
    fun bounded() =
        copy(
            fontSize = fontSize.coerceIn(75.0, 200.0),
            lineHeight = lineHeight.coerceIn(1.0, 2.0),
            margins = margins.coerceIn(0.0, 2.0),
        )

    companion object {
        /** Reads a stored override; missing or malformed fields fall back to [fallback]. */
        fun fromJson(text: String, fallback: ReaderTypography): ReaderTypography =
            runCatching {
                    val json = JSONObject(text)
                    ReaderTypography(
                            json.optDouble("fontSize", fallback.fontSize),
                            json.optBoolean("serif", fallback.serif),
                            json.optBoolean("scroll", fallback.scroll),
                            json.optDouble("lineHeight", fallback.lineHeight),
                            json.optDouble("margins", fallback.margins),
                            json.optBoolean("publisherStyles", fallback.publisherStyles),
                        )
                        .bounded()
                }
                .getOrDefault(fallback)
    }
}

/** Text size is stored as a percentage (110 = 110%); Readium wants a multiplier (1.1). */
fun readiumFontScale(percent: Double): Double = percent.coerceIn(MIN_TEXT, MAX_TEXT) / 100.0

/** The next size up or down, in steps of [TEXT_STEP] between [MIN_TEXT] and [MAX_TEXT]. */
fun steppedTextSize(current: Double, up: Boolean): Double {
    val snapped = Math.round(current / TEXT_STEP) * TEXT_STEP
    return (if (up) snapped + TEXT_STEP else snapped - TEXT_STEP).coerceIn(MIN_TEXT, MAX_TEXT)
}

const val MIN_TEXT = 75.0
const val MAX_TEXT = 200.0
const val TEXT_STEP = 10.0
