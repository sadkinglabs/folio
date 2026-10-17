@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.data.*
import dev.sadking.folio.sync.EreaderSync
import dev.sadking.folio.sync.MatchCandidateView
import dev.sadking.folio.sync.MatchCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * On the JVM (Robolectric): the device list and sheet, the Progress sheet after a rename, merge or
 * forget, the "Already matched to …" prompt in both places it shows, and messages shown inside the
 * open sheet instead of under it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class DevicesUiTest {
    @get:Rule val compose = createComposeRule()

    private val hour = 3_600_000L
    private val now = System.currentTimeMillis()
    private val kindle = DeviceKeys.ereader("kindle-1")
    private val ssh = DeviceKeys.ssh("saved")
    private val report =
        KosyncReport(
            "doc",
            "kindle-1",
            "KindlePaperWhite5SE",
            "/p",
            0.5,
            (now - 10 * hour) / 1000,
            now - 10 * hour,
        )
    private val snapshot =
        ReadingSnapshot("book", "saved", 0.46, "/p", "reading", 0, now - 23 * hour, "", "", "")
    private val phone = PhoneReading("book", "h", "{}", "/p", 0.49, now - hour)
    private val book =
        Book(
            "book",
            "Hyperion",
            "[\"Dan Simmons\"]",
            originalPath = "",
            currentPath = "",
            originalHash = "",
            currentHash = "h",
        )

    private fun roster(vararg profiles: DeviceProfile) =
        DeviceRoster(
            deviceSightings(
                listOf(EreaderSync("kindle-1", "", now - 2 * hour)),
                listOf(report),
                listOf(snapshot),
            ),
            profiles.toList(),
        )

    private fun progress(roster: DeviceRoster) {
        val entries =
            progressEntries(
                true,
                phone,
                listOf(snapshot),
                roster.reports(listOf(report)),
                true,
                392,
                roster,
            )
        compose.setContent { FolioTheme(dynamic = false) { ProgressContent(book, true, entries) } }
    }

    @Test
    fun theProgressSheetShowsTheOldPositionForWhatItIs() {
        progress(roster())
        compose.onNodeWithText("KindlePaperWhite5SE").assertIsDisplayed()
        compose.onNodeWithText("Old e-reader position (before sync)").assertIsDisplayed()
        compose.onNodeWithText("E-reader").assertDoesNotExist()
        compose
            .onNodeWithText("KindlePaperWhite5SE is ahead at 50%", substring = true)
            .assertExists()
    }

    @Test
    fun afterAMergeTheProgressSheetHasOneRowForTheKindle() {
        progress(roster(DeviceProfile(ssh, mergedInto = kindle, updatedAt = now)))
        compose.onNodeWithText("Old e-reader position (before sync)").assertDoesNotExist()
        compose.onAllNodesWithText("KindlePaperWhite5SE").assertCountEquals(1)
        compose.onNodeWithText("50%").assertExists()
        compose.onNodeWithText("46%").assertDoesNotExist()
    }

    @Test
    fun afterARenameAndAForgetTheProgressSheetUsesThem() {
        progress(
            roster(
                DeviceProfile(kindle, "My Paperwhite", updatedAt = now),
                DeviceProfile(ssh, hiddenAt = now, updatedAt = now),
            )
        )
        compose.onNodeWithText("My Paperwhite").assertIsDisplayed()
        compose.onNodeWithText("KindlePaperWhite5SE").assertDoesNotExist()
        compose.onNodeWithText("Old e-reader position (before sync)").assertDoesNotExist()
    }

    @Test
    fun theDeviceSheetRenamesMergesAndForgets() {
        val roster = roster()
        val old = roster.device(ssh)!!
        var renamed: String? = null
        var merged: KnownDevice? = null
        var forgot = false
        compose.setContent {
            FolioTheme(dynamic = false) {
                DeviceSheetContent(
                    old,
                    roster.shown.filter { it.key != ssh },
                    now,
                    onRename = { renamed = it },
                    onMerge = { merged = it },
                    onForget = { forgot = true },
                )
            }
        }
        compose
            .onNodeWithText("Read over SSH before sync. It won’t update.", substring = true)
            .assertExists()
        compose.onNodeWithTag(DEVICE_NAME_TAG).performTextReplacement("Kindle before sync")
        compose.onNodeWithTag(DEVICE_SAVE_NAME_TAG).performClick()
        compose
            .onNodeWithText("This is the same e-reader as KindlePaperWhite5SE")
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag(DEVICE_FORGET_TAG).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("Kindle before sync", renamed)
            assertEquals(kindle, merged?.key)
            assertTrue(forgot)
        }
    }

    @Test
    fun theDeviceListShowsEveryDeviceWithWhatItReports() {
        compose.setContent {
            FolioTheme(dynamic = false) { DeviceList(roster().shown, now, onOpen = {}) }
        }
        compose.onNodeWithText("KindlePaperWhite5SE").assertIsDisplayed()
        compose.onNodeWithText("Old e-reader position (before sync)").assertIsDisplayed()
        compose.onNodeWithText("Syncs books", substring = true).assertExists()
    }

    private val holder = HardcoverHolder("entry", "fall", "The Fall of Hyperion", false)
    private val candidate = MatchCandidateView(77, "Hyperion", listOf("Dan Simmons"), 1989, null)
    private val offer = MoveOffer("book", 77, "Hyperion", listOf("Dan Simmons"), holder)

    @Test
    fun checkTheseMatchesSaysWhereTheBookIsAndOffersToMoveIt() {
        var moved: MoveOffer? = null
        var kept = false
        compose.setContent {
            FolioTheme(dynamic = false) {
                DecisionsContent(
                    emptyList(),
                    listOf(
                        MatchCheck(
                            "book",
                            "Hyperion",
                            listOf("Dan Simmons"),
                            listOf(candidate),
                            mapOf(77 to holder),
                        )
                    ),
                    offer = offer,
                    onMove = { moved = it },
                    onKeep = { kept = true },
                )
            }
        }
        compose
            .onAllNodesWithText("Already matched to “The Fall of Hyperion”.")
            .assertCountEquals(2)
        compose.onNodeWithTag(MOVE_MATCH_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(KEEP_MATCH_TAG).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(offer, moved)
            assertTrue(kept)
        }
    }

    @Test
    fun theHardcoverSheetOffersTheSameMove() {
        var moved: MoveOffer? = null
        compose.setContent {
            FolioTheme(dynamic = false) {
                HardcoverReadingContent(
                    book = book,
                    username = "alex",
                    busy = false,
                    error = "Already matched to “The Fall of Hyperion”.",
                    reading = null,
                    editions = emptyList(),
                    matches = emptyList(),
                    snapshot = null,
                    phonePercent = null,
                    reports = emptyList(),
                    onConnect = {},
                    onRefresh = {},
                    onSearch = {},
                    onLink = {},
                    onMarkReading = {},
                    onSend = { _, _, _ -> },
                    moveOffer = offer.copy(holder = holder.copy(removed = true)),
                    onMove = { moved = it },
                )
            }
        }
        compose
            .onNodeWithText(
                "Already matched to “The Fall of Hyperion”, which is in Recently removed."
            )
            .assertIsDisplayed()
        compose.onNodeWithText("Move the match here").performClick()
        compose.runOnIdle { assertEquals(77, moved?.hardcoverId) }
    }

    @Test
    fun aMessageShowsInTheOpenSheetNotUnderIt() {
        val messages = AppMessages(SnackbarHostState())
        compose.setContent {
            FolioTheme(dynamic = false) {
                CompositionLocalProvider(LocalAppMessages provides messages) {
                    FolioSheet(onDismissRequest = {}) { Text("Sheet body") }
                    LaunchedEffect(Unit) { messages.host.showSnackbar("Match moved.") }
                }
            }
        }
        compose.waitUntil(5_000) { messages.inSheet }
        compose.onNodeWithText("Match moved.").assertIsDisplayed()
        compose.onNodeWithTag(SHEET_MESSAGES_TAG).assertExists()
        compose.runOnIdle { assertTrue("The screen's own host stays empty", messages.inSheet) }
    }

    @Test
    fun withNoSheetOpenTheScreenShowsMessages() {
        val messages = AppMessages(SnackbarHostState())
        compose.setContent {
            FolioTheme(dynamic = false) {
                CompositionLocalProvider(LocalAppMessages provides messages) { Text("Screen") }
            }
        }
        compose.runOnIdle { assertFalse(messages.inSheet) }
    }
}
