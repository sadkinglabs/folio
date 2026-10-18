package dev.sadking.folio.data

import android.content.SharedPreferences
import dev.sadking.folio.core.stats.GoalSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The owner's reading goals (a8): books a year and minutes a day. A goal is off when its key is
 * absent or 0. They live in the `stats` preferences and are not in backups, like other settings.
 */
class StatsGoals(private val prefs: SharedPreferences) {
    private val current = MutableStateFlow(load())

    /** The goals as they are now. A goal that is off is null. */
    val goals: StateFlow<GoalSettings> = current.asStateFlow()

    /**
     * Saves [settings]. A null or zero value turns that goal off: its key is removed, not kept
     * as 0.
     */
    fun save(settings: GoalSettings) {
        prefs
            .edit()
            .putOrRemove(KEY_BOOKS_PER_YEAR, settings.booksPerYear)
            .putOrRemove(KEY_MINUTES_PER_DAY, settings.minutesPerDay)
            .apply()
        current.value = load()
    }

    private fun load() =
        GoalSettings(
            booksPerYear = prefs.getInt(KEY_BOOKS_PER_YEAR, 0).takeIf { it > 0 },
            minutesPerDay = prefs.getInt(KEY_MINUTES_PER_DAY, 0).takeIf { it > 0 },
        )

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: Int?) =
        if (value != null && value > 0) putInt(key, value) else remove(key)

    companion object {
        /** The preferences file the goals are kept in. */
        const val PREFS = "stats"

        const val KEY_BOOKS_PER_YEAR = "goalBooksPerYear"
        const val KEY_MINUTES_PER_DAY = "goalMinutesPerDay"
    }
}
