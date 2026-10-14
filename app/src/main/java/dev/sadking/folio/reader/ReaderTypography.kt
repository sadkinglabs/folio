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
