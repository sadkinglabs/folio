package dev.sadking.folio.ui.stats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.sadking.folio.StatsLoad
import dev.sadking.folio.core.stats.GoalSettings
import dev.sadking.folio.core.stats.ReadingSession
import dev.sadking.folio.core.stats.ReadingStats
import dev.sadking.folio.core.stats.SessionSource
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.StatsLabel
import dev.sadking.folio.data.StatsSnapshot
import dev.sadking.folio.ui.FolioTheme
import dev.sadking.folio.ui.LocalReducedMotion
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Stats dashboard's two scrolls on the JVM (Robolectric): tapping the tab again takes it back
 * to the top, and a Year column takes it to the calendar. Same Ready load as [StatsRouteTest]
 * (today Saturday 17 October 2026). The scroll is checked, not how it looks: that is for a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class StatsScrollTest {
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

    private fun ready(): StatsLoad =
        StatsLoad.Ready(
            StatsSnapshot(
                stats =
                    ReadingStats(
                        listOf(
                            ReadingSession(
                                id = "1",
                                source = SessionSource.KINDLE,
                                deviceKey = "ereader:k",
                                bookId = "b1",
                                document = null,
                                startMs =
                                    LocalDate.of(2026, 10, 3)
                                        .atTime(20, 0)
                                        .atZone(zone)
                                        .toInstant()
                                        .toEpochMilli(),
                                durationSec = 90 * 60,
                            )
                        ),
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

    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope
    private var toTop by mutableIntStateOf(0)

    private fun show(reduced: Boolean = false) {
        compose.setContent {
            list = rememberLazyListState()
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                FolioTheme(dynamic = false) {
                    StatsRouteContent(
                        ready(),
                        onSaveGoals = {},
                        onRetry = {},
                        onOpenBook = {},
                        onYear = { _, _ -> },
                        onLibrary = {},
                        toTop = toTop,
                        listState = list,
                    )
                }
            }
        }
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(androidx.compose.ui.test.hasText("1 h 30 min"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun scrollDownTo(index: Int) {
        compose.runOnIdle { scope.launch { list.scrollToItem(index) } }
        compose.waitForIdle()
        assertTrue(list.firstVisibleItemIndex >= index - 1)
    }

    private val trendChart =
        SemanticsMatcher("the trend chart") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any {
                it.contains("of reading and")
            } == true
        }

    @Test
    fun tappingTheTabAgainScrollsBackToTheTop() {
        show()
        scrollDownTo(5)
        compose.runOnIdle { toTop++ }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun withRemovedAnimationsTheTabTapJumpsToTheTop() {
        show(reduced = true)
        scrollDownTo(5)
        compose.runOnIdle { toTop++ }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun theFirstCountDoesNotScrollAnything() {
        show()
        scrollDownTo(5)
        // A recomposition with the same count (a rotation, a reload) leaves the list where it is.
        compose.runOnIdle { toTop = 0 }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(list.firstVisibleItemIndex >= 4) }
    }

    @Test
    fun aYearColumnOpensItsMonthAndScrollsToTheCalendar() {
        show()
        compose.onNodeWithText("Year").performClick()
        // The year's chart is mapped off the main thread: wait until it is on screen.
        compose.waitUntil(30_000) {
            compose
                .onAllNodes(androidx.compose.ui.test.hasText("This year", ignoreCase = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, list.firstVisibleItemIndex) }
        // September: the ninth of twelve equal slots.
        compose.onNode(trendChart).performTouchInput {
            click(Offset(width * 8.5f / 12f, height / 2f))
        }
        compose.waitUntil(30_000) {
            compose
                .onAllNodes(androidx.compose.ui.test.hasText("September 2026"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(statsCalendarIndex(showTitle = true), list.firstVisibleItemIndex)
            assertEquals(
                STATS_CALENDAR_KEY,
                list.layoutInfo.visibleItemsInfo
                    .first { it.index == list.firstVisibleItemIndex }
                    .key,
            )
        }
    }
}
