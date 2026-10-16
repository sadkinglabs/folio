package dev.sadking.folio.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.data.Book
import dev.sadking.folio.sync.HardcoverItemState
import dev.sadking.folio.sync.HardcoverProblem
import dev.sadking.folio.sync.HardcoverSyncStatus
import dev.sadking.folio.sync.MatchCandidateView
import dev.sadking.folio.sync.MatchCheck
import dev.sadking.folio.sync.StatusQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Hardcover sync's screens on the JVM (Robolectric): "Check these matches" and the status questions
 * in Needs your decision, Settings' switch and status line, and the book page's Hardcover tile.
 * Each says what it should in plain words and calls back with the right values. Nothing here talks
 * to Hardcover.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class HardcoverSyncUiTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_800_000_000_000L

    private val first = MatchCandidateView(11, "Home", listOf("Ann Lee"), 2019, null)
    private val second = MatchCandidateView(12, "Home: A Novel", listOf("Ann Lee"), null, null)
    private val third = MatchCandidateView(13, "Homeward", emptyList(), 2001, null)
    private val check = MatchCheck("b1", "Home", listOf("Ann Lee"), listOf(first, second, third))
    private val read = StatusQuestion(7, "b2", "The Orchard", 3, "Reading")
    private val gone = StatusQuestion(8, "w3", "Salt and Iron", 0, "Want to Read")

    private class Calls {
        var picked: Pair<MatchCheck, MatchCandidateView>? = null
        var notOnHardcover: MatchCheck? = null
        var searched: MatchCheck? = null
        val answers = mutableListOf<Pair<StatusQuestion, Boolean>>()
        val all = mutableListOf<Boolean>()
    }

    private fun decisions(
        checks: List<MatchCheck> = listOf(check),
        questions: List<StatusQuestion> = emptyList(),
    ): Calls {
        val calls = Calls()
        compose.setContent {
            FolioTheme(dynamic = false) {
                DecisionsContent(
                    emptyList(),
                    checks,
                    questions,
                    onPick = { c, candidate -> calls.picked = c to candidate },
                    onNotOnHardcover = { calls.notOnHardcover = it },
                    onSearch = { calls.searched = it },
                    onHardcoverAnswer = { q, useHardcover -> calls.answers += q to useHardcover },
                    onAnswerAll = { calls.all += it },
                )
            }
        }
        return calls
    }

    @Test
    fun aMatchToCheckShowsTheBookAndEveryCandidate() {
        decisions()
        compose.onNodeWithText("Check these matches").assertIsDisplayed()
        compose.onNodeWithText("Which one is it on Hardcover?").assertIsDisplayed()
        compose.onNodeWithText("Ann Lee · 2019").assertIsDisplayed()
        compose.onNodeWithText("Home: A Novel").assertIsDisplayed()
        compose.onNodeWithText("2001").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Nothing needs your decision.").assertDoesNotExist()
    }

    @Test
    fun thisOnePicksThatCandidate() {
        val calls = decisions()
        compose.onNodeWithTag(matchPickTag("b1", 12)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(check to second, calls.picked) }
    }

    @Test
    fun notOnHardcoverAndSearchCallBackWithTheCheck() {
        val calls = decisions()
        compose.onNodeWithTag(matchNotOnHardcoverTag("b1")).performScrollTo().performClick()
        compose.onNodeWithTag(matchSearchTag("b1")).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(check, calls.notOnHardcover)
            assertEquals(check, calls.searched)
            assertEquals(null, calls.picked)
        }
    }

    @Test
    fun aStatusQuestionSaysBothSidesInTheirOwnWords() {
        val calls = decisions(emptyList(), listOf(read))
        compose
            .onNodeWithText(
                "The Orchard: Hardcover says Read, Librillo says Reading. Which is right?"
            )
            .assertIsDisplayed()
        compose.onNodeWithTag(hardcoverAnswerTag(7, true)).assertTextEquals("Hardcover: Read")
        compose.onNodeWithTag(hardcoverAnswerTag(7, false)).assertTextEquals("Librillo: Reading")
        compose.onNodeWithTag(hardcoverAnswerTag(7, true)).performClick()
        compose.onNodeWithTag(hardcoverAnswerTag(7, false)).performClick()
        compose.runOnIdle { assertEquals(listOf(read to true, read to false), calls.answers) }
        // One question needs no "for all" buttons.
        compose.onNodeWithTag(hardcoverAnswerAllTag(true)).assertDoesNotExist()
    }

    @Test
    fun aBookGoneFromHardcoverAsksToLeaveItOffOrPutItBack() {
        val calls = decisions(emptyList(), listOf(gone))
        compose
            .onNodeWithText("Salt and Iron is no longer on Hardcover. Librillo says Want to Read.")
            .assertIsDisplayed()
        compose.onNodeWithText("Leave it off Hardcover").performClick()
        compose.onNodeWithText("Put it back").performClick()
        compose.runOnIdle { assertEquals(listOf(gone to true, gone to false), calls.answers) }
    }

    @Test
    fun twoOrMoreQuestionsCanBeAnsweredTogether() {
        val calls = decisions(emptyList(), listOf(read, gone))
        compose.onNodeWithTag(hardcoverAnswerAllTag(true)).assertTextEquals("Use Hardcover for all")
        compose.onNodeWithTag(hardcoverAnswerAllTag(false)).assertTextEquals("Use Librillo for all")
        compose.onNodeWithTag(hardcoverAnswerAllTag(false)).performClick()
        compose.onNodeWithTag(hardcoverAnswerAllTag(true)).performClick()
        compose.runOnIdle {
            assertEquals(listOf(false, true), calls.all)
            assertTrue(calls.answers.isEmpty())
        }
    }

    @Test
    fun matchesComeBeforeStatusQuestions() {
        decisions(listOf(check), listOf(read))
        val checks = compose.onNodeWithText("Check these matches").fetchSemanticsNode()
        val questions =
            compose.onNodeWithText("Hardcover and Librillo disagree").fetchSemanticsNode()
        assertTrue(checks.positionInRoot.y < questions.positionInRoot.y)
    }

    private fun settings(
        status: HardcoverSyncStatus,
        onSync: (Boolean) -> Unit = {},
        onSyncNow: () -> Unit = {},
        onDecisions: () -> Unit = {},
        onHardcover: () -> Unit = {},
    ) = compose.setContent {
        FolioTheme(dynamic = false) {
            HardcoverSettings(
                "alex",
                status,
                now,
                onHardcover = onHardcover,
                onSync = onSync,
                onSyncNow = onSyncNow,
                onDecisions = onDecisions,
            )
        }
    }

    private fun lineText(status: HardcoverSyncStatus): String {
        settings(status)
        return compose
            .onNodeWithTag(SETTINGS_HARDCOVER_LINE_TAG)
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString("")
    }

    private val connected = HardcoverSyncStatus(connected = true, matched = 312)

    @Test
    fun theSwitchTurnsSyncOffAndSyncNowSyncs() {
        var on: Boolean? = null
        var synced = 0
        settings(
            connected.copy(lastSyncAt = now - 5 * 60_000),
            onSync = { on = it },
            onSyncNow = { synced++ },
        )
        compose.onNodeWithText("Sync with Hardcover").assertIsDisplayed()
        compose.onNodeWithTag(SETTINGS_HARDCOVER_SYNC_TAG).assertIsOn().performClick()
        compose.onNodeWithTag(SETTINGS_HARDCOVER_SYNC_NOW_TAG).performClick()
        compose.runOnIdle {
            assertEquals(false, on)
            assertEquals(1, synced)
        }
    }

    @Test
    fun theStatusLineSaysWhenItSyncedAndWhatWaits() {
        var opened = 0
        settings(
            connected.copy(lastSyncAt = now - 5 * 60_000, toCheck = 4),
            onDecisions = { opened++ },
        )
        compose
            .onNodeWithTag(SETTINGS_HARDCOVER_LINE_TAG)
            .assertTextEquals("Synced 5 min ago · 312 matched · 4 to check")
            .performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun theStatusLineInEveryOtherCase() {
        assertEquals(
            "Synced 5 min ago · 312 matched",
            lineText(connected.copy(lastSyncAt = now - 5 * 60_000)),
        )
    }

    @Test
    fun theStatusLineBeforeTheFirstSync() {
        assertEquals("Not synced yet · 312 matched", lineText(connected))
    }

    @Test
    fun theStatusLineWhileMatching() {
        assertEquals(
            "Matching your library… 120 of 400",
            lineText(connected.copy(running = true, matchingDone = 120, matchingTotal = 400)),
        )
        compose.onNodeWithTag(SETTINGS_HARDCOVER_SYNC_NOW_TAG).assertDoesNotExist()
    }

    @Test
    fun theStatusLineWhileSyncing() {
        assertEquals("Syncing…", lineText(connected.copy(running = true)))
    }

    @Test
    fun theStatusLineWithoutAConnection() {
        assertEquals(
            "Waiting for a connection",
            lineText(connected.copy(problem = HardcoverProblem.OFFLINE)),
        )
    }

    @Test
    fun theStatusLineWhenSyncIsOff() {
        assertEquals("Sync is off", lineText(connected.copy(enabled = false)))
        compose.onNodeWithTag(SETTINGS_HARDCOVER_SYNC_TAG).assertIsOff()
        compose.onNodeWithTag(SETTINGS_HARDCOVER_SYNC_NOW_TAG).assertDoesNotExist()
    }

    @Test
    fun aRejectedTokenSendsTheOwnerToTheAccount() {
        var account = 0
        settings(
            connected.copy(problem = HardcoverProblem.TOKEN_REJECTED),
            onHardcover = { account++ },
        )
        compose
            .onNodeWithTag(SETTINGS_HARDCOVER_LINE_TAG)
            .assertTextEquals("Hardcover turned down the token. Reconnect your account.")
            .performClick()
        compose.runOnIdle { assertEquals(1, account) }
    }

    @Test
    fun withNoAccountThereIsNoStatusLine() {
        settings(HardcoverSyncStatus())
        compose.onNodeWithTag(SETTINGS_HARDCOVER_SYNC_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SETTINGS_HARDCOVER_LINE_TAG).assertDoesNotExist()
    }

    private val book =
        Book(
            "b1",
            "Home",
            "[\"Ann Lee\"]",
            originalPath = "",
            currentPath = "",
            originalHash = "",
            currentHash = "",
        )

    private fun tile(state: HardcoverItemState?, linked: Boolean = false): Pair<Int, Int> {
        var checked = 0
        var progress = 0
        compose.setContent {
            FolioTheme(dynamic = false) {
                BookDetails(
                    book,
                    hasEpub = false,
                    hardcoverLinked = linked,
                    hardcoverState = state,
                    fileMissing = false,
                    changedOutside = false,
                    onCheckMatch = { checked++ },
                )
            }
        }
        compose.onNodeWithTag(STATUS_TILE_HARDCOVER_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        if (compose.onAllNodesWithTag(PROGRESS_SHEET_TAG).fetchSemanticsNodes().isNotEmpty())
            progress++
        return checked to progress
    }

    private fun tileSays(vararg text: String) = text.forEach {
        compose.onNodeWithTag(STATUS_TILE_HARDCOVER_TAG).assert(hasText(it))
    }

    @Test
    fun aMatchToCheckOnTheTileOpensNeedsYourDecision() {
        assertEquals(1 to 0, tile(HardcoverItemState.CHECK))
        tileSays("Check match", "Tap to choose")
    }

    @Test
    fun aMatchInProgressSaysSoAndNothingMore() {
        assertEquals(0 to 1, tile(HardcoverItemState.MATCHING))
        tileSays("Matching…")
        compose.onNodeWithText("Tap Hardcover").assertDoesNotExist()
    }

    @Test
    fun notOnHardcoverIsRemembered() {
        assertEquals(0 to 1, tile(HardcoverItemState.NOT_ON_HARDCOVER))
        tileSays("Not on Hardcover")
        compose.onNodeWithText("Tap Hardcover").assertDoesNotExist()
    }

    @Test
    fun notFoundPointsToTheHardcoverAction() {
        tile(HardcoverItemState.NOT_FOUND)
        tileSays("Not found", "Tap Hardcover")
    }

    @Test
    fun withSyncOffANotMatchedBookPointsToTheHardcoverAction() {
        tile(HardcoverItemState.OFF)
        tileSays("Not matched", "Tap Hardcover")
    }

    @Test
    fun withoutAStateTheTileFollowsTheLink() {
        tile(null, linked = true)
        tileSays("Matched")
        compose.onNodeWithText("Tap Hardcover").assertDoesNotExist()
    }
}
