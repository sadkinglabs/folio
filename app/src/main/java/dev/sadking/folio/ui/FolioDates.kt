package dev.sadking.folio.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dates as the owner reads them, whatever the phone's region: day, month, year ("17/10/2026"). The
 * time of day keeps the phone's own 12- or 24-hour style.
 */
object FolioDates {
    private val DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

    /** "17/10/2026". */
    fun day(date: LocalDate): String = date.format(DAY)

    /** "17/10/2026" for [epochMs] in [zone]. */
    fun day(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        day(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate())

    /** "17/10/2026 9:05 pm": the day, then the time in the phone's style. */
    fun dayAndTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val time =
            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).apply {
                timeZone = java.util.TimeZone.getTimeZone(zone)
            }
        return "${day(epochMs, zone)} ${time.format(java.util.Date(epochMs))}"
    }

    /**
     * The locale for Material's date picker, so its typed field reads DD/MM/YYYY too: English
     * becomes British English (same words, day first); other languages already put the day first or
     * keep their own order.
     */
    fun pickerLocale(locale: Locale): Locale =
        if (locale.language == "en") Locale.Builder().setLocale(locale).setRegion("GB").build()
        else locale
}
