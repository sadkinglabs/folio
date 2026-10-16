package dev.sadking.folio.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.Operation
import dev.sadking.folio.SetupProgress
import dev.sadking.folio.core.sync.Question
import dev.sadking.folio.device.ObservedHostKey
import dev.sadking.folio.link.Refusal
import dev.sadking.folio.link.RefusedFile
import dev.sadking.folio.sync.EreaderSync
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sync screens on the JVM (Robolectric): the Library's sync line, the decisions sheet, the
 * Device tab and the setup sheet. Each says what it should in plain words and calls back with the
 * right values. Nothing here talks to an e-reader.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class SyncUiTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val question =
        Question(
            "q-1",
            "b1",
            "Librillo/Ann Lee/Home.epub",
            "status",
            "Reading",
            "Finished",
            "Home: Kindle says Finished, phone says Reading. Which is right?",
            listOf("Finished", "Reading"),
        )

    private fun line(
        state: SyncLineState,
        onSync: () -> Unit = {},
        onSetUp: () -> Unit = {},
        onDecisions: () -> Unit = {},
    ) = compose.setContent {
        FolioTheme(dynamic = false) {
            SyncStatusLine(
                state,
                now,
                onSync = onSync,
                onSetUp = onSetUp,
                onDecisions = onDecisions,
            )
        }
    }

    @Test
    fun theLineOffersSetUpUntilAnEreaderIsSetUp() {
        var setUp = 0
        line(SyncLineState(SyncLine.NotSetUp), onSetUp = { setUp++ })
        compose.onNodeWithText("Your e-reader isn't set up yet").assertIsDisplayed()
        compose.onNodeWithTag(SYNC_LINE_BUTTON_TAG).assertTextEquals("Set up").performClick()
        compose.runOnIdle { assertEquals(1, setUp) }
        compose.onNodeWithTag(SYNC_LINE_DECISIONS_TAG).assertDoesNotExist()
    }

    @Test
    fun theLineSaysWhenItSyncedAndSyncsOnTap() {
        var synced = 0
        var opened = 0
        line(
            SyncLineState(SyncLine.Synced("Kindle Paperwhite", now - 2 * 60_000), 1),
            onSync = { synced++ },
            onDecisions = { opened++ },
        )
        compose.onNodeWithText("Kindle Paperwhite synced 2 min ago").assertIsDisplayed()
        compose.onNodeWithTag(SYNC_LINE_BUTTON_TAG).assertTextEquals("Sync").performClick()
        compose.onNodeWithText("1 decision needed").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, synced)
            assertEquals(1, opened)
        }
    }

    @Test
    fun aTappedSyncSaysWhatHappensNext() {
        line(SyncLineState(SyncLine.Requested(null)))
        compose
            .onNodeWithText("Your e-reader will sync within 5 minutes while its Wi-Fi is on")
            .assertIsDisplayed()
    }

    @Test
    fun whileSyncingThereIsNoButton() {
        line(SyncLineState(SyncLine.Syncing("Kindle")))
        compose.onNodeWithText("Syncing with Kindle…").assertIsDisplayed()
        compose.onNodeWithTag(SYNC_LINE_BUTTON_TAG).assertDoesNotExist()
    }

    @Test
    fun aDecisionIsAnsweredWithOneOfItsOptions() {
        var answered: Pair<Decision, String>? = null
        val decision = Decision("kindle-1", "Kindle", question, null)
        compose.setContent {
            FolioTheme(dynamic = false) {
                DecisionsContent(
                    listOf(decision),
                    onAnswer = { d, option -> answered = d to option },
                )
            }
        }
        compose.onNodeWithText("Needs your decision").assertIsDisplayed()
        compose.onNodeWithText("Kindle uses your answer at its next sync.").assertIsDisplayed()
        compose.onNodeWithText(question.text).assertIsDisplayed()
        compose.onNodeWithTag(decisionOptionTag("q-1", "Reading")).performClick()
        compose.runOnIdle { assertEquals(decision to "Reading", answered) }
    }

    @Test
    fun anAnsweredDecisionShowsTheChoiceAndWhenItApplies() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                DecisionsContent(listOf(Decision("kindle-1", null, question, "Finished")))
            }
        }
        compose.onNodeWithText("Saved. Your e-reader uses it at its next sync.").assertIsDisplayed()
    }

    @Test
    fun nothingToDecideSaysSo() {
        compose.setContent {
            FolioTheme(dynamic = false) { DecisionsContent(emptyList()) }
        }
        compose.onNodeWithText("Nothing needs your decision.").assertIsDisplayed()
    }

    private fun device(
        ereaders: List<EreaderSync>,
        line: SyncLineState,
        autoSync: Boolean = true,
        onAutoSync: (Boolean) -> Unit = {},
        onSync: () -> Unit = {},
        onSetUp: () -> Unit = {},
        onDecisions: () -> Unit = {},
    ) = compose.setContent {
        FolioTheme(dynamic = false) {
            DeviceScreen(
                ereaders = ereaders,
                line = line,
                requestedAt = 0,
                now = now,
                autoSync = autoSync,
                onAutoSync = onAutoSync,
                keepAwake = false,
                onKeepAwake = {},
                noWifi = false,
                onOpenWifi = {},
                onSync = onSync,
                onSetUp = onSetUp,
                onDecisions = onDecisions,
                activity = emptyList(),
                onClearActivity = {},
                onSignIn = {},
                onSavePlugin = {},
            )
        }
    }

    @Test
    fun theDeviceTabShowsTheEreaderItsSyncAndWhatItCouldNotTake() {
        var synced = 0
        var decisions = 0
        val kindle =
            EreaderSync(
                "kindle-1",
                "Kindle Paperwhite",
                lastSync = now - 2 * 60_000,
                questions = listOf(question),
                refused =
                    listOf(
                        RefusedFile("a".repeat(64), "Downloads/Scan.pdf", Refusal.NOT_A_BOOK, 0),
                        RefusedFile("b".repeat(64), "Big.cbz", Refusal.TOO_LARGE, 0),
                    ),
                books = 12,
            )
        device(
            listOf(kindle),
            SyncLineState(SyncLine.Synced(kindle.name, kindle.lastSync), 1),
            onSync = { synced++ },
            onDecisions = { decisions++ },
        )
        compose.onNodeWithTag(DEVICE_SYNC_CARD_TAG).assertIsDisplayed()
        compose.onNodeWithText("Kindle Paperwhite").assertIsDisplayed()
        compose.onNodeWithText("Synced 2 min ago").assertIsDisplayed()
        compose.onNodeWithText("12 books on both").assertIsDisplayed()
        compose.onNodeWithTag(DEVICE_SYNC_BUTTON_TAG).performClick()
        compose.onNodeWithTag(DEVICE_DECISIONS_TAG).performScrollTo().performClick()
        compose
            .onNodeWithText("Couldn't take: Scan.pdf — not a book Librillo can open")
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText("Couldn't take: Big.cbz — larger than 256 MB, the most Librillo takes")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(DEVICE_SETUP_TAG).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, synced)
            assertEquals(1, decisions)
        }
    }

    @Test
    fun withNoEreaderTheDeviceTabOffersSetupAndTheSyncSwitch() {
        var setUp = 0
        var auto: Boolean? = null
        device(
            emptyList(),
            SyncLineState(SyncLine.NotSetUp),
            onSetUp = { setUp++ },
            onAutoSync = { auto = it },
        )
        compose.onNodeWithTag(DEVICE_SETUP_TAG).assertIsDisplayed()
        compose.onNodeWithText("Set up").performClick()
        compose.onNodeWithTag(DEVICE_SYNC_CARD_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DEVICE_REINSTALL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DEVICE_AUTO_SYNC_TAG).performScrollTo().assertIsOn().performClick()
        compose.onNodeWithText("Send", substring = true).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, setUp)
            assertEquals(false, auto)
        }
    }

    @Test
    fun setUpButNeverSyncedSaysWhatToDoOnTheEreader() {
        device(emptyList(), SyncLineState(SyncLine.NeverSynced(null)))
        compose.onNodeWithText("Your e-reader").assertIsDisplayed()
        compose.onNodeWithText("Hasn't synced yet").assertIsDisplayed()
        compose.onNodeWithText("On the e-reader, tap Librillo → Sync now.").assertIsDisplayed()
        compose.onNodeWithTag(DEVICE_REINSTALL_TAG).performScrollTo().assertIsDisplayed()
    }

    private val key =
        ObservedHostKey(
            "192.168.1.30",
            2222,
            "ssh-ed25519",
            "SHA256:Xq3r0pXyZ1c2V4bN5m6L7k8J9h0G1f2D3s4A5q6W7e8",
        )

    private fun setup(
        observed: ObservedHostKey?,
        progress: SetupProgress = SetupProgress(),
        onFind: (String, Int) -> Unit = { _, _ -> },
        onInstall: (String, Int, String, String) -> Unit = { _, _, _, _ -> },
        onDone: () -> Unit = {},
    ) = compose.setContent {
        FolioTheme(dynamic = false) {
            PluginSetupContent(
                reinstall = false,
                savedHost = "192.168.1.30",
                savedPort = 2222,
                savedFingerprint = "",
                folder = "Librillo",
                observed = observed,
                probe = Operation(),
                progress = progress,
                onFind = onFind,
                onInstall = onInstall,
                onDone = onDone,
            )
        }
    }

    @Test
    fun setupFindsTheEreaderBeforeItCanInstall() {
        var found: Pair<String, Int>? = null
        setup(null, onFind = { host, port -> found = host to port })
        compose.onNodeWithTag(SETUP_INSTALL_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(SETUP_FIND_TAG).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("192.168.1.30" to 2222, found) }
    }

    @Test
    fun setupInstallsWithTheKeyFoundAndTheFolderChosen() {
        var installed: List<Any>? = null
        setup(
            key,
            onInstall = { host, port, fp, folder -> installed = listOf(host, port, fp, folder) },
        )
        compose
            .onNodeWithText("Found the e-reader at 192.168.1.30.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(SETUP_FOLDER_TAG).performScrollTo().performTextReplacement("Books")
        compose.onNodeWithTag(SETUP_INSTALL_TAG).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("192.168.1.30", 2222, key.fingerprint, "Books"), installed)
        }
    }

    @Test
    fun aFolderThatIsAPathCannotBeInstalled() {
        setup(key)
        compose.onNodeWithTag(SETUP_FOLDER_TAG).performScrollTo().performTextReplacement("a/b")
        compose.onNodeWithText("Use a plain folder name, like Librillo.").assertExists()
        compose.onNodeWithTag(SETUP_INSTALL_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aDifferentAddressNeedsAnotherLook() {
        setup(key)
        compose
            .onNodeWithTag(SETUP_HOST_TAG)
            .performScrollTo()
            .performTextReplacement("192.168.1.31")
        compose.onNodeWithText("Found the e-reader at 192.168.1.30.").assertDoesNotExist()
        compose.onNodeWithTag(SETUP_INSTALL_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun afterInstallingThePhoneSaysTheThreeStepsLeft() {
        var done = 0
        setup(null, progress = SetupProgress(SetupProgress.Stage.DONE), onDone = { done++ })
        compose.onNodeWithTag(SETUP_DONE_TAG).assertIsDisplayed()
        compose
            .onNodeWithText("Restart KOReader. If it says to, restart it once more.")
            .assertIsDisplayed()
        compose.onNodeWithText("Tap Librillo → Sync now.").assertIsDisplayed()
        compose
            .onNodeWithText("Turn KOReader's SSH server off: gear tab → Network → SSH server.")
            .assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertEquals(1, done) }
    }

    @Test
    fun aFailedInstallSaysWhy() {
        setup(
            key,
            progress =
                SetupProgress(
                    SetupProgress.Stage.FAILED,
                    "KOReader was not found on the e-reader.",
                ),
        )
        compose
            .onNodeWithText("KOReader was not found on the e-reader.")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
