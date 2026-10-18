package dev.sadking.folio.ui.stats

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.sadking.folio.StatsLoad
import dev.sadking.folio.core.stats.GoalSettings
import dev.sadking.folio.core.stats.ReadingSession
import dev.sadking.folio.core.stats.ReadingStats
import dev.sadking.folio.core.stats.SessionSource
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.StatsLabel
import dev.sadking.folio.data.StatsSnapshot
import dev.sadking.folio.ui.FolioTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Stats tab's state and its two sheets on the JVM (Robolectric): a Ready load made from a real
 * [ReadingStats] (zone Europe/Madrid, today Saturday 17 October 2026), what the taps do, and what
 * the screen hands back. Never the Loading state: its indicator never settles.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class StatsRouteTest {
    @get:Rule val compose = createComposeRule()

    private val zone = ZoneId.of("Europe/Madrid")
    private val today = LocalDate.of(2026, 10, 17)
    private val book =
        Book(
            "b1",
            "The Orchard Keeper",
            "[\"Ines Marlow\"]",
            originalPath = "",
            currentPath = "",
            originalHash = "",
            currentHash = "",
        )

    private fun session(id: String, day: Int, minutes: Long) =
        ReadingSession(
            id = id,
            source = SessionSource.KINDLE,
            deviceKey = "ereader:k",
            bookId = "b1",
            document = null,
            startMs =
                LocalDate.of(2026, 10, day).atTime(20, 0).atZone(zone).toInstant().toEpochMilli(),
            durationSec = minutes * 60,
        )

    private fun ready(): StatsLoad =
        StatsLoad.Ready(
            StatsSnapshot(
                stats =
                    ReadingStats(
                        listOf(session("1", 3, 90)),
                        emptyList(),
                        emptyList(),
                        zone = zone,
                        today = today,
                        firstDayOfWeek = DayOfWeek.MONDAY,
                    ),
                labels = mapOf("b1" to StatsLabel(book.title, "Ines Marlow", book, null)),
                deviceNames = emptyMap(),
                goals = GoalSettings(),
                waitingForEreader = false,
                hasEreaderSessions = true,
            )
        )

    private class Calls {
        val goals = mutableListOf<GoalSettings>()
        var retries = 0
        val books = mutableListOf<String>()
    }

    private fun show(load: StatsLoad): Calls {
        val calls = Calls()
        compose.setContent {
            FolioTheme(dynamic = false) {
                StatsRouteContent(
                    load,
                    onSaveGoals = { calls.goals += it },
                    onRetry = { calls.retries++ },
                    onOpenBook = { calls.books += it },
                    onYear = { _, _ -> },
                    onLibrary = {},
                )
            }
        }
        return calls
    }

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(androidx.compose.ui.test.hasText(text))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

    private fun describedStartingWith(prefix: String) =
        SemanticsMatcher("content description starts with $prefix") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any {
                it.startsWith(prefix)
            } == true
        }

    @Test
    fun theHeroShowsTheMonthsTotal() {
        show(ready())
        waitForText("1 h 30 min")
        compose.onNodeWithText("THIS MONTH").assertExists()
    }

    @Test
    fun tappingACalendarDayOpensTheDaySheetWithItsTitle() {
        show(ready())
        waitForText("1 h 30 min")
        compose.onNode(describedStartingWith("3: ")).performClick()
        waitForText("Saturday 3 October")
    }

    @Test
    fun savingAMinutesGoalHandsItBack() {
        val calls = show(ready())
        waitForText("1 h 30 min")
        compose.onNode(hasContentDescription("Goals")).performClick()
        waitForText("Minutes a day")
        // The range buttons toggle too; the sheet's switches come last, Minutes a day after Books.
        compose.onAllNodes(isToggleable()).onLast().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(listOf(GoalSettings(minutesPerDay = 20)), calls.goals) }
    }

    @Test
    fun aFailedLoadOffersTryAgain() {
        val calls = show(StatsLoad.Failed)
        waitForText("Try again")
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, calls.retries) }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w412dp-h4000dp-xxhdpi")
    fun aBookInTheBooksRowOpensIt() {
        val calls = show(ready())
        waitForText("1 h 30 min")
        compose.onNode(hasContentDescription(book.title, substring = true)).performClick()
        compose.runOnIdle { assertEquals(listOf("b1"), calls.books) }
    }
}
