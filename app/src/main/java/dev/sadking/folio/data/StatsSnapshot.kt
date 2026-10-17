package dev.sadking.folio.data

import dev.sadking.folio.core.stats.GoalSettings
import dev.sadking.folio.core.stats.ReadingStats

/** How a book key is shown. [book] is set for a library book (for Cover()); null otherwise. */
data class StatsLabel(
    val title: String,
    val authors: String,
    val book: Book?,
    val coverPath: String?,
)

data class StatsSnapshot(
    val stats: ReadingStats,
    /**
     * By book key. A key missing here (an unmatched `doc:` key) shows R.string.stats_unknown_book.
     */
    val labels: Map<String, StatsLabel>,
    /** By device key, as the device roster names them. `phone:` keys are absent: "This phone". */
    val deviceNames: Map<String, String>,
    val goals: GoalSettings,
    /** An e-reader syncs, but none of its sessions has arrived yet: "first sync pending". */
    val waitingForEreader: Boolean,
)
