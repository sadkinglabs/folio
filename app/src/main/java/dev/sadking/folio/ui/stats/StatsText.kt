package dev.sadking.folio.ui.stats

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * The words the Stats mapper ([StatsUi]) needs, behind one small object so the mapper stays plain
 * JVM code: it never touches a Context. Tests build one from Robolectric's resources.
 */
internal class StatsText(private val res: Resources) {
    /** The first locale of the phone's configuration: month and weekday names follow it. */
    val locale: Locale
        get() = res.configuration.locales[0]

    /** "1 h 20 min", "48 min": [formatDuration]. */
    fun duration(seconds: Long): String = formatDuration(res, seconds)

    fun string(@StringRes id: Int, vararg args: Any): String = res.getString(id, *args)

    /** The plural for [n]; [args] are the format arguments (usually [n] itself). */
    fun plural(@PluralsRes id: Int, n: Int, vararg args: Any): String =
        res.getQuantityString(id, n, *args)
}

/** A [StatsText] for the current resources; a new one when they or the locale change. */
@Composable
internal fun rememberStatsText(): StatsText {
    val res = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0]
    return remember(res, locale) { StatsText(res) }
}
