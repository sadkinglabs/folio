package dev.sadking.folio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sadking.folio.core.stats.GoalSettings
import dev.sadking.folio.data.StatsSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** The Stats tab's data: still loading, the snapshot, or failed (the screen offers a retry). */
sealed interface StatsLoad {
    data object Loading : StatsLoad

    data class Ready(val snapshot: StatsSnapshot) : StatsLoad

    data object Failed : StatsLoad
}

/**
 * [snapshots] as loads: Loading, then Ready for each; Failed when the flow throws. Each [retry]
 * emission starts again.
 */
internal fun statsLoads(snapshots: () -> Flow<StatsSnapshot>, retry: Flow<Unit>): Flow<StatsLoad> =
    retry
        .onStart { emit(Unit) }
        .flatMapLatest {
            snapshots()
                .map<StatsSnapshot, StatsLoad> { StatsLoad.Ready(it) }
                .onStart { emit(StatsLoad.Loading) }
                .catch { emit(StatsLoad.Failed) }
        }

/**
 * The Stats tab's state: the repository's snapshot, read while the tab is shown, and the owner's
 * goals saved from its sheet (M12, docs/STATS_DESIGN.md).
 */
class StatsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FolioApplication
    private val retries = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val load: StateFlow<StatsLoad> =
        statsLoads({ app.stats.snapshot() }, retries)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsLoad.Loading)

    /** Starts the load again after it failed. */
    fun retry() {
        retries.tryEmit(Unit)
    }

    /** Saves the owner's goals; a goal that is null or 0 is off. */
    fun saveGoals(settings: GoalSettings) = app.statsGoals.save(settings)
}
