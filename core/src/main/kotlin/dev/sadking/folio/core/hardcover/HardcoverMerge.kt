package dev.sadking.folio.core.hardcover

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * What both sides last agreed. [status] 0..5; 0 = agreed it is not on Hardcover. [statusAt] =
 * Librillo's status time then.
 */
data class HardcoverBaseline(val status: Int, val statusAt: Long)

/** Librillo's side: [word] is a phone status word; [statusAt] epoch ms, 0 when unknown. */
data class LocalSide(val itemId: String, val title: String, val word: String, val statusAt: Long)

/** Hardcover's side, from the pull. [status] as Hardcover sent it. */
data class RemoteSide(val userBookId: Int, val status: Int, val updatedAt: Long?)

data class MergeInput(
    val hardcoverId: Int,
    val local: LocalSide,
    /** Null: not in the Hardcover library. */
    val remote: RemoteSide?,
    val baseline: HardcoverBaseline?,
)

sealed interface MergeStep {
    val hardcoverId: Int

    /** Both sides already say [status]: record the baseline only. */
    data class Agree(override val hardcoverId: Int, val status: Int, val statusAt: Long) : MergeStep

    /** Set Librillo's item to [word] at [statusAt], then baseline ([status], [statusAt]). */
    data class Down(
        override val hardcoverId: Int,
        val itemId: String,
        val status: Int,
        val word: String,
        val statusAt: Long,
    ) : MergeStep

    /**
     * Send [status] to Hardcover ([userBookId] null: add the book), then baseline ([status],
     * [statusAt]). [finishedOn] only for 3: the day the read ended, or null for "read long ago".
     */
    data class Up(
        override val hardcoverId: Int,
        val itemId: String,
        val status: Int,
        val userBookId: Int?,
        val finishedOn: LocalDate?,
        val statusAt: Long,
    ) : MergeStep

    /** Hardcover no longer has it: baseline (0, [statusAt]); Librillo is not touched. */
    data class Gone(override val hardcoverId: Int, val statusAt: Long) : MergeStep

    /** Both changed, differently: ask. [hardcoverStatus] 0 when Hardcover no longer has it. */
    data class Ask(
        override val hardcoverId: Int,
        val itemId: String,
        val title: String,
        val hardcoverStatus: Int,
        val librilloWord: String,
    ) : MergeStep
}

/**
 * Two-way status sync for one linked book, from Librillo's status, Hardcover's and the baseline
 * both last agreed on. Rules: docs/HARDCOVER_SYNC.md, A3 and B3.
 */
object HardcoverMerge {
    /** Times closer than this are "the same moment": ask instead of guessing. */
    const val SAME_TIME_MS = 60_000L

    /** Null when nothing is to be done. [now] stands in for a missing updated_at on Down. */
    fun plan(input: MergeInput, zone: ZoneId, today: LocalDate, now: Long): MergeStep? {
        val local = input.local
        val remote = input.remote
        if (remote != null && remote.status !in 1..5) return null
        val l = HardcoverStatuses.fromPhone(local.word)
        val r = remote?.status ?: HardcoverStatuses.NONE

        fun down(): MergeStep.Down =
            MergeStep.Down(
                input.hardcoverId,
                local.itemId,
                r,
                checkNotNull(HardcoverStatuses.toPhone(r)),
                remote?.updatedAt ?: now,
            )

        fun up(status: Int): MergeStep.Up =
            MergeStep.Up(
                input.hardcoverId,
                local.itemId,
                status,
                remote?.userBookId,
                finishedOn(status, r, local.statusAt, zone, today),
                local.statusAt,
            )

        fun ask(): MergeStep.Ask =
            MergeStep.Ask(input.hardcoverId, local.itemId, local.title, r, local.word)

        val baseline = input.baseline
        if (baseline == null) {
            if (l == null) return if (r == HardcoverStatuses.NONE) null else down()
            if (r == HardcoverStatuses.NONE) return up(l)
            if (l == r) return MergeStep.Agree(input.hardcoverId, r, local.statusAt)
            val updatedAt = remote?.updatedAt
            if (
                local.statusAt > 0 &&
                    updatedAt != null &&
                    abs(local.statusAt - updatedAt) > SAME_TIME_MS
            ) {
                return if (local.statusAt > updatedAt) up(l) else down()
            }
            // No usable times (most books from before status times were kept): the status further
            // along wins, so a first sync doesn't ask about every book. Reading or Paused here
            // against Read on Hardcover may be a re-read, so that one asks.
            val reread =
                r == HardcoverStatuses.READ &&
                    (l == HardcoverStatuses.CURRENTLY_READING || l == HardcoverStatuses.PAUSED)
            val ahead = progress(l).compareTo(progress(r))
            return when {
                reread || ahead == 0 -> ask()
                ahead > 0 -> up(l)
                else -> down()
            }
        }

        val localChanged =
            l != null &&
                if (baseline.status == HardcoverStatuses.NONE) local.statusAt > baseline.statusAt
                else l != baseline.status
        val remoteChanged = r != baseline.status
        return when {
            !localChanged && !remoteChanged -> null
            localChanged && !remoteChanged -> up(checkNotNull(l))
            !localChanged ->
                if (r == HardcoverStatuses.NONE) MergeStep.Gone(input.hardcoverId, local.statusAt)
                else down()
            l == r -> MergeStep.Agree(input.hardcoverId, r, local.statusAt)
            else -> ask()
        }
    }

    /** How far along a status is: Want to Read, then Reading, then Paused or stopped, then Read. */
    private fun progress(status: Int): Int =
        when (status) {
            HardcoverStatuses.WANT_TO_READ -> 1
            HardcoverStatuses.CURRENTLY_READING -> 2
            HardcoverStatuses.PAUSED,
            HardcoverStatuses.DID_NOT_FINISH -> 3
            HardcoverStatuses.READ -> 4
            else -> 0
        }

    /**
     * The day a read ends when Librillo's Finished goes up. Only when Hardcover has a read going
     * (Currently Reading or Paused): the local day of [statusAt] (today when unknown), never after
     * [today]. Otherwise null: read long ago, no date.
     */
    private fun finishedOn(
        status: Int,
        remote: Int,
        statusAt: Long,
        zone: ZoneId,
        today: LocalDate,
    ): LocalDate? {
        if (status != HardcoverStatuses.READ) return null
        if (remote != HardcoverStatuses.CURRENTLY_READING && remote != HardcoverStatuses.PAUSED) {
            return null
        }
        if (statusAt <= 0) return today
        val day = Instant.ofEpochMilli(statusAt).atZone(zone).toLocalDate()
        return if (day.isAfter(today)) today else day
    }
}
