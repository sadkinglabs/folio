package dev.sadking.folio.sync

import dev.sadking.folio.core.hardcover.MatchCandidate
import dev.sadking.folio.data.HardcoverHttpException
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The order of work in one run of the Hardcover queue, and what it does with each failure. */
class HardcoverRunTest {
    private var now = 0L
    private val log = mutableListOf<String>()

    private class State(
        override var connected: Boolean = true,
        override var enabled: Boolean = true,
        override var pullRequested: Boolean = false,
        override var lastPullAt: Long = 0,
        override var lastSyncAt: Long = 0,
        override var problem: HardcoverProblem? = null,
    ) : HardcoverRunState

    private fun book(userBookId: Int) =
        RemoteUserBook(
            userBookId,
            userBookId + 1000,
            1,
            null,
            "T$userBookId",
            listOf("A"),
            null,
            null,
            null,
            null,
        )

    /** Hardcover with [size] library books; every call is logged and takes one second. */
    private inner class Api(size: Int = 3) : HardcoverApi {
        val library = (1..size).map { book(it) }
        var failPage: Exception? = null
        override val accountId = 7

        override fun libraryPage(after: Int): List<RemoteUserBook> {
            now += 1_000
            log += "page $after"
            failPage?.let { throw it.also { failPage = null } }
            return library.filter { it.userBookId > after }.take(50)
        }

        override fun setStatus(hardcoverId: Int, status: Int, userBookId: Int?) = error("unused")

        override fun markRead(hardcoverId: Int, finishedOn: LocalDate?) = error("unused")

        override fun editionsByIsbn(isbns: List<String>): List<IsbnHit> = error("unused")

        override fun search(text: String): List<MatchCandidate> = error("unused")
    }

    private inner class Steps(val api: Api = Api()) : HardcoverRunSteps {
        var due = true
        var pending = 0
        var linkedPerBatch = 0
        var connectError: Exception? = null
        var statusError: Exception? = null
        var matchError: Exception? = null
        var matchTime = 0L
        var lastLibrary: List<RemoteUserBook>? = null
        var createEntries: Boolean? = null

        override suspend fun due(enabled: Boolean, pullRequested: Boolean) = due

        override suspend fun connect(): HardcoverApi {
            log += "account"
            connectError?.let { throw it }
            return api
        }

        override suspend fun pending() = pending

        override suspend fun fromLibrary(library: List<RemoteUserBook>): Int {
            log += "library ${library.size}"
            return 0
        }

        override suspend fun syncStatuses(
            api: HardcoverApi,
            library: List<RemoteUserBook>,
            createEntries: Boolean,
        ): StatusSyncResult {
            log += "statuses"
            statusError?.let { throw it.also { statusError = null } }
            lastLibrary = library
            this.createEntries = createEntries
            return StatusSyncResult()
        }

        override suspend fun sendProgress(api: HardcoverApi) {
            log += "progress"
        }

        override suspend fun sendFinishes(api: HardcoverApi) {
            log += "finishes"
        }

        override suspend fun matchBatch(
            api: HardcoverApi,
            deadline: Long,
            progress: (Int, Int) -> Unit,
        ): Int {
            log += "match"
            matchError?.let { throw it.also { matchError = null } }
            progress(1, 2)
            now += matchTime
            return linkedPerBatch.also { linkedPerBatch = 0 }
        }
    }

    private val reports = mutableListOf<HardcoverRunProgress>()

    private fun run(state: State, steps: Steps) = runBlocking {
        HardcoverRun(
                state,
                steps,
                now = { now },
                sleep = {
                    now += it
                    log += "wait $it"
                },
                report = { reports += it },
            )
            .run()
    }

    @Test
    fun aFullRoundRunsInTheAgreedOrder() {
        val state = State(pullRequested = true)
        val steps = Steps(Api(size = 120))
        assertEquals(RunOutcome.Done, run(state, steps))
        assertEquals(
            listOf(
                "account",
                "page 0",
                "page 50",
                "page 100",
                "library 120",
                "statuses",
                "progress",
                "finishes",
                "match",
            ),
            log,
        )
        assertEquals(120, steps.lastLibrary!!.size)
        assertFalse(state.pullRequested)
        assertTrue(state.lastPullAt > 0 && state.lastSyncAt > 0)
        assertEquals(true, steps.createEntries)
    }

    @Test
    fun noNewCatalogueEntriesWhileItemsWaitForMatching() {
        val steps = Steps().apply { pending = 4 }
        run(State(pullRequested = true), steps)
        assertEquals(false, steps.createEntries)
    }

    @Test
    fun nothingDueMakesNoRequest() {
        val steps = Steps().apply { due = false }
        assertEquals(RunOutcome.Done, run(State(), steps))
        assertTrue(log.isEmpty())
    }

    @Test
    fun notConnectedDoesNothing() {
        assertEquals(RunOutcome.Done, run(State(connected = false), Steps()))
        assertTrue(log.isEmpty())
    }

    @Test
    fun withoutAPullStatusesAreLeftAlone() {
        run(State(pullRequested = false), Steps())
        assertEquals(listOf("account", "progress", "finishes", "match"), log)
    }

    @Test
    fun syncOffStillSendsTheFinishesTheOwnerAskedFor() {
        run(State(enabled = false, pullRequested = true), Steps())
        assertEquals(listOf("account", "finishes"), log)
    }

    @Test
    fun aNewLinkPullsAgainInAnotherRound() {
        val steps = Steps().apply { linkedPerBatch = 2 }
        val state = State(pullRequested = true)
        run(state, steps)
        assertEquals(2, log.count { it == "statuses" })
        assertFalse(state.pullRequested)
    }

    @Test
    fun atMostThreeRounds() {
        val steps =
            object : HardcoverRunSteps by Steps() {
                override suspend fun matchBatch(
                    api: HardcoverApi,
                    deadline: Long,
                    progress: (Int, Int) -> Unit,
                ): Int {
                    log += "match"
                    return 1
                }
            }
        val state = State(pullRequested = true)
        runBlocking { HardcoverRun(state, steps, now = { now }).run() }
        assertEquals(3, log.count { it == "statuses" })
        assertTrue(state.pullRequested)
    }

    @Test
    fun slowDownWaitsThenStartsTheRoundAgain() {
        val steps = Steps()
        steps.api.failPage = HardcoverHttpException(429, "busy", retryAfterMs = 30_000)
        val state = State(pullRequested = true)
        assertEquals(RunOutcome.Done, run(state, steps))
        assertEquals(listOf("account", "page 0", "wait 30000", "page 0"), log.take(4))
        assertEquals(1, log.count { it == "statuses" })
        assertFalse(state.pullRequested)
    }

    @Test
    fun slowDownLongerThanTheBudgetHandsOverToALaterRun() {
        val steps = Steps().apply { matchError = HardcoverHttpException(429, "busy", 9 * 60_000L) }
        val state = State(pullRequested = true)
        assertEquals(RunOutcome.Again(9 * 60_000L), run(state, steps))
        assertFalse(log.any { it.startsWith("wait") })
        // The statuses were synced before the slow down; they are not pulled again.
        assertFalse(state.pullRequested)
    }

    @Test
    fun slowDownOnTheAccountCheckHandsOver() {
        val steps = Steps().apply { connectError = HardcoverHttpException(429, "busy", 20_000) }
        assertEquals(RunOutcome.Again(20_000), run(State(), steps))
    }

    @Test
    fun theBudgetEndsTheRunAndQueuesTheNext() {
        val steps = Steps().apply { matchTime = HardcoverSync.RUN_BUDGET_MS }
        assertEquals(RunOutcome.Again(0), run(State(pullRequested = true), steps))
    }

    @Test
    fun noConnectionRetriesAndPullsAgain() {
        val steps = Steps()
        steps.api.failPage = IOException("offline")
        val state = State(pullRequested = true)
        assertEquals(RunOutcome.Retry, run(state, steps))
        assertEquals(HardcoverProblem.OFFLINE, state.problem)
        assertTrue(state.pullRequested)
    }

    @Test
    fun serverErrorRetries() {
        val steps = Steps().apply { statusError = HardcoverHttpException(502, "failed") }
        val state = State(pullRequested = true)
        assertEquals(RunOutcome.Retry, run(state, steps))
        assertEquals(HardcoverProblem.OFFLINE, state.problem)
        // The pulled library is used once; the next attempt pulls again.
        assertTrue(state.pullRequested)
    }

    @Test
    fun aRejectedTokenStopsEverything() {
        val steps = Steps().apply { connectError = HardcoverHttpException(401, "rejected") }
        val state = State(pullRequested = true)
        assertEquals(RunOutcome.Done, run(state, steps))
        assertEquals(HardcoverProblem.TOKEN_REJECTED, state.problem)
        assertEquals(listOf("account"), log)
    }

    @Test
    fun aProblemIsCheckedAgainEvenWithNothingDue() {
        val steps = Steps().apply { due = false }
        val state = State(problem = HardcoverProblem.TOKEN_REJECTED)
        assertEquals(RunOutcome.Done, run(state, steps))
        assertNull(state.problem)
        assertEquals("account", log.first())
    }

    @Test
    fun anAnswerThatNeedsAPersonSkipsStatusesButNotTheRest() {
        val steps = Steps().apply { statusError = IllegalStateException("odd") }
        val state = State(pullRequested = true)
        assertEquals(RunOutcome.Done, run(state, steps))
        assertEquals(listOf("progress", "finishes", "match"), log.takeLast(3))
        assertFalse(state.pullRequested)
    }

    @Test
    fun progressIsReportedAndClearedAtTheEnd() {
        run(State(), Steps())
        assertTrue(reports.contains(HardcoverRunProgress(running = true, done = 1, total = 2)))
        assertEquals(HardcoverRunProgress(), reports.last())
    }
}
