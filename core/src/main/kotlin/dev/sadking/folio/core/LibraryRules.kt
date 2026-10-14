package dev.sadking.folio.core

object LibraryRules {
    val fields = listOf("Manual", "Status", "Language", "Series", "Tag", "Missing on Kindle")

    fun matches(
        field: String,
        value: String,
        metadata: Metadata,
        status: String,
        onDevice: Boolean,
    ): Boolean =
        when (field) {
            "Status" -> status.equals(value, true)
            "Language" -> metadata.language.equals(value, true)
            "Series" -> metadata.series.equals(value, true)
            "Tag" -> metadata.tags.any { it.equals(value, true) }
            "Missing on Kindle" -> !onDevice
            else -> false
        }

    fun seriesNumber(value: String): Double =
        value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: Double.MAX_VALUE
}
