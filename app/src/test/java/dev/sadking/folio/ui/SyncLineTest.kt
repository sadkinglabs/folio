package dev.sadking.folio.ui

import dev.sadking.folio.R
import dev.sadking.folio.core.sync.Question
import dev.sadking.folio.device.libraryFolderName
import dev.sadking.folio.sync.EreaderSync
import dev.sadking.folio.sync.HardcoverProblem
import dev.sadking.folio.sync.HardcoverSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the sync line says, which questions the decisions sheet lists, and the setup checks. */
class SyncLineTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun question(id: String, options: List<String> = listOf("Finished", "Reading")) =
        Question(
            id,
            "b-$id",
            "Librillo/A/$id.epub",
            "status",
            "Reading",
            "Finished",
            "$id?",
            options,
        )

    @Test
    fun nothingSetUpAsksForSetup() {
        val state = syncLine(emptyList(), 0, pluginInstalled = false, hardcover = 0, now)
        assertEquals(SyncLineState(SyncLine.NotSetUp, 0), state)
    }

    @Test
    fun hardcoverChecksAndQuestionsCountAsDecisionsEvenWithoutAnEreader() {
        val state = syncLine(emptyList(), 0, pluginInstalled = false, hardcover = 2, now)
        assertEquals(2, state.decisions)
    }

    @Test
    fun installedButNeverSyncedWaitsForTheFirstSync() {
        val state = syncLine(emptyList(), 0, pluginInstalled = true, hardcover = 0, now)
        assertEquals(SyncLine.NeverSynced(null), state.line)
    }

    @Test
    fun anEreaderThatSyncedOnceCountsAsSetUpWithoutTheInstallRecord() {
        val kindle = EreaderSync("k", "Kindle", lastSync = now - 2 * minute)
        val state = syncLine(listOf(kindle), 0, pluginInstalled = false, hardcover = 0, now)
        assertEquals(SyncLine.Synced("Kindle", now - 2 * minute), state.line)
    }

    @Test
    fun aSyncUnderWayWins() {
        val kindle =
            EreaderSync("k", "Kindle", lastSync = now - 60 * minute, syncingSince = now - minute)
        val state = syncLine(listOf(kindle), now - 5 * minute, true, 0, now)
        assertEquals(SyncLine.Syncing("Kindle"), state.line)
    }

    @Test
    fun aSyncThatNeverFinishedStopsCountingAfterHalfAnHour() {
        val kindle =
            EreaderSync(
                "k",
                "Kindle",
                lastSync = now - 90 * minute,
                syncingSince = now - 31 * minute,
            )
        val state = syncLine(listOf(kindle), 0, true, 0, now)
        assertEquals(SyncLine.Synced("Kindle", now - 90 * minute), state.line)
    }

    @Test
    fun syncTappedAfterTheLastSyncSaysItWillComeWithinFiveMinutes() {
        val kindle = EreaderSync("k", "Kindle", lastSync = now - 60 * minute)
        val state = syncLine(listOf(kindle), now - minute, true, 0, now)
        assertEquals(SyncLine.Requested("Kindle"), state.line)
    }

    @Test
    fun aSyncAfterTheTapEndsTheRequest() {
        val kindle = EreaderSync("k", "Kindle", lastSync = now - minute)
        val state = syncLine(listOf(kindle), now - 2 * minute, true, 0, now)
        assertEquals(SyncLine.Synced("Kindle", now - minute), state.line)
    }

    @Test
    fun aRequestNobodyAnsweredForHalfAnHourFallsBackToTheLastSync() {
        val kindle = EreaderSync("k", "Kindle", lastSync = now - 120 * minute)
        val state = syncLine(listOf(kindle), now - 31 * minute, true, 0, now)
        assertEquals(SyncLine.Synced("Kindle", now - 120 * minute), state.line)
    }

    @Test
    fun syncTappedBeforeAnyEreaderSyncedIsRequestedWithoutAName() {
        val state = syncLine(emptyList(), now - minute, true, 0, now)
        assertEquals(SyncLine.Requested(null), state.line)
    }

    @Test
    fun aKnownEreaderThatNeverFinishedASyncHasNotSyncedYet() {
        val kindle = EreaderSync("k", "Kindle")
        assertEquals(
            SyncLine.NeverSynced("Kindle"),
            syncLine(listOf(kindle), 0, false, 0, now).line,
        )
    }

    @Test
    fun aBlankNameFallsBackToYourEreader() {
        val kindle = EreaderSync("k", "  ", lastSync = now - minute)
        assertEquals(
            SyncLine.Synced(null, now - minute),
            syncLine(listOf(kindle), 0, true, 0, now).line,
        )
    }

    @Test
    fun theLineFollowsTheEreaderThatSyncedLast() {
        val old = EreaderSync("kobo", "Kobo", lastSync = now - 600 * minute)
        val recent = EreaderSync("kindle", "Kindle", lastSync = now - 3 * minute)
        assertEquals(
            SyncLine.Synced("Kindle", now - 3 * minute),
            syncLine(listOf(old, recent), 0, true, 0, now).line,
        )
    }

    @Test
    fun decisionsCountUnansweredQuestionsOfEveryEreaderAndHardcover() {
        val kindle =
            EreaderSync(
                "k",
                "Kindle",
                lastSync = now,
                questions = listOf(question("q1"), question("q2")),
                answers = mapOf("q2" to "Reading"),
            )
        val kobo =
            EreaderSync("o", "Kobo", lastSync = now - minute, questions = listOf(question("q3")))
        assertEquals(3, syncLine(listOf(kindle, kobo), 0, true, 1, now).decisions)
    }

    @Test
    fun theSheetListsOpenQuestionsBeforeAnsweredOnes() {
        val kindle =
            EreaderSync(
                "k",
                "Kindle",
                questions = listOf(question("q1"), question("q2")),
                answers = mapOf("q1" to "Finished"),
            )
        val listed = decisions(listOf(kindle))
        assertEquals(listOf("q2", "q1"), listed.map { it.question.id })
        assertNull(listed[0].answer)
        assertEquals("Finished", listed[1].answer)
        assertEquals("k", listed[1].ereaderId)
        assertEquals("Kindle", listed[1].ereaderName)
    }

    @Test
    fun anEreaderWithoutANameGivesTheSheetNoName() {
        val listed = decisions(listOf(EreaderSync("k", "", questions = listOf(question("q1")))))
        assertNull(listed.single().ereaderName)
    }

    private val connected = HardcoverSyncStatus(connected = true, matched = 312)

    @Test
    fun hardcoverSaysNothingWithoutAnAccount() {
        assertNull(hardcoverLine(HardcoverSyncStatus(running = true, lastSyncAt = now)))
    }

    @Test
    fun hardcoverSyncTurnedOffSaysSoWhateverElseIsKnown() {
        val off =
            connected.copy(
                enabled = false,
                running = true,
                problem = HardcoverProblem.TOKEN_REJECTED,
            )
        assertEquals(HardcoverLine.Off, hardcoverLine(off))
    }

    @Test
    fun aRejectedTokenComesBeforeEverythingElse() {
        val status =
            connected.copy(
                running = true,
                matchingDone = 1,
                matchingTotal = 9,
                problem = HardcoverProblem.TOKEN_REJECTED,
            )
        assertEquals(HardcoverLine.TokenRejected, hardcoverLine(status))
    }

    @Test
    fun theMatchingPassShowsItsCountWhileItRuns() {
        val status = connected.copy(running = true, matchingDone = 120, matchingTotal = 400)
        assertEquals(HardcoverLine.Matching(120, 400), hardcoverLine(status))
        assertEquals(
            HardcoverLine.Matching(5, 5),
            hardcoverLine(connected.copy(matchingDone = 7, matchingTotal = 5)),
        )
    }

    @Test
    fun aRunWithoutMatchingIsSyncing() {
        val status = connected.copy(running = true, problem = HardcoverProblem.OFFLINE)
        assertEquals(HardcoverLine.Syncing, hardcoverLine(status))
    }

    @Test
    fun noConnectionWaits() {
        val status = connected.copy(lastSyncAt = now, problem = HardcoverProblem.OFFLINE)
        assertEquals(HardcoverLine.Offline, hardcoverLine(status))
    }

    @Test
    fun beforeTheFirstSyncOnlyTheMatchedCountShows() {
        assertEquals(HardcoverLine.NeverSynced(312), hardcoverLine(connected.copy(toCheck = 3)))
    }

    @Test
    fun afterASyncTheLineSaysWhenAndWhatWaits() {
        val status = connected.copy(lastSyncAt = now - 5 * minute, toCheck = 4)
        assertEquals(HardcoverLine.Synced(now - 5 * minute, 312, 4), hardcoverLine(status))
    }

    @Test
    fun aLibraryFolderIsOnePlainFolderName() {
        assertEquals("Librillo", libraryFolderName("  Librillo "))
        assertEquals("My Books", libraryFolderName("My Books"))
        assertNull(libraryFolderName(""))
        assertNull(libraryFolderName("   "))
        assertNull(libraryFolderName("Books/Librillo"))
        assertNull(libraryFolderName("Books\\Librillo"))
        assertNull(libraryFolderName(".hidden"))
        assertNull(libraryFolderName(".."))
        assertNull(libraryFolderName("a\u0001b"))
        assertNull(libraryFolderName("x".repeat(65)))
        assertEquals("x".repeat(64), libraryFolderName("x".repeat(64)))
    }

    @Test
    fun theActivityLogNamesEverySyncStep() {
        assertEquals(R.string.m9sync_log_sync_started, describeRoute("/folio/v2/sync", 200))
        assertEquals(R.string.m9sync_log_sync_finished, describeRoute("/folio/v2/finish", 200))
        assertEquals(R.string.m9sync_log_uploaded, describeRoute("/folio/v2/upload", 200))
        assertEquals(R.string.link_log_downloaded, describeRoute("/folio/v2/books/b1", 200))
        assertEquals(R.string.link_log_checked, describeRoute("/folio/v2/hello", 200))
        assertEquals(R.string.link_log_refused, describeRoute("/folio/v2/sync", 401))
        assertEquals(R.string.link_log_other, describeRoute("/elsewhere", 200))
    }
}
