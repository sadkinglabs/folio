package dev.sadking.folio

import dev.sadking.folio.core.stats.GoalSettings
import dev.sadking.folio.core.stats.ReadingStats
import dev.sadking.folio.data.StatsSnapshot
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsViewModelTest {
    private fun snapshot() =
        StatsSnapshot(
            stats =
                ReadingStats(
                    sessions = emptyList(),
                    zone = ZoneId.of("Europe/Madrid"),
                    today = LocalDate.of(2026, 10, 18),
                ),
            labels = emptyMap(),
            deviceNames = emptyMap(),
            goals = GoalSettings(),
            waitingForEreader = false,
            hasEreaderSessions = false,
        )

    @Test
    fun aSnapshotIsLoadingThenReadyForEachSnapshot() = runBlocking {
        val first = snapshot()
        val second = snapshot()

        val loads = statsLoads({ flowOf(first, second) }, emptyFlow()).toList()

        assertEquals(3, loads.size)
        assertEquals(StatsLoad.Loading, loads[0])
        assertSame(first, (loads[1] as StatsLoad.Ready).snapshot)
        assertSame(second, (loads[2] as StatsLoad.Ready).snapshot)
    }

    @Test
    fun aFlowThatThrowsGivesLoadingThenFailed() = runBlocking {
        val loads =
            statsLoads({ flow<StatsSnapshot> { throw IllegalStateException("boom") } }, emptyFlow())
                .toList()

        assertEquals(listOf(StatsLoad.Loading, StatsLoad.Failed), loads)
    }

    @Test
    fun aRetryStartsTheLoadAgain() = runBlocking {
        val retries = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val first = snapshot()
        val loads = async {
            withTimeout(10_000) {
                statsLoads({ flowOf(first) }, retries).take(4).toList()
            }
        }
        // A retry is only seen by a collector that is already subscribed.
        retries.subscriptionCount.first { it > 0 }
        retries.tryEmit(Unit)

        val result = loads.await()

        assertEquals(4, result.size)
        assertEquals(StatsLoad.Loading, result[0])
        assertTrue(result[1] is StatsLoad.Ready)
        assertEquals(StatsLoad.Loading, result[2])
        assertTrue(result[3] is StatsLoad.Ready)
    }
}
