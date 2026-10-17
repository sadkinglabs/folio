package dev.sadking.folio.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.sadking.folio.reader.narration.BenchmarkReport
import dev.sadking.folio.reader.narration.ConfigResult
import dev.sadking.folio.reader.narration.NarrationStatus
import dev.sadking.folio.reader.narration.SlowChoice
import dev.sadking.folio.ui.FolioTheme
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The narration parts of the reader's chrome on the JVM (Robolectric), not on a device: the listen
 * bar's preparation note, the too-slow sheet, the speed test sheet and the Listening sheet's model
 * and measurement rows. The engine is not involved; each composable gets its state directly.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ReaderChromeNarrationTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private val preparingText = "Preparing narration…"

    // --- The note's mapping (pure) ---

    @Test
    fun theNoteSaysPreparingOnlyInPreparing() {
        val preparing = NarrationStatus.Preparing(reserveSeconds = 0.0, etaSeconds = null)
        ListenState.entries.forEach { state ->
            val note = listenNote(state, preparing)
            if (state == ListenState.PREPARING) assertEquals(ListenNote.Preparing(null), note)
            else assertNull("$state", note)
        }
        // Loading the model is preparation too: no ETA yet.
        assertEquals(
            ListenNote.Preparing(null),
            listenNote(ListenState.PREPARING, NarrationStatus.Loading),
        )
    }

    @Test
    fun theNoteCarriesOnlyAPositiveEta() {
        assertEquals(
            ListenNote.Preparing(6),
            listenNote(ListenState.PREPARING, NarrationStatus.Preparing(1.0, 6)),
        )
        assertEquals(
            ListenNote.Preparing(null),
            listenNote(ListenState.PREPARING, NarrationStatus.Preparing(1.0, 0)),
        )
    }

    @Test
    fun chapterPreparationShowsWhileListeningAndClampsItsPercent() {
        val chapter = NarrationStatus.PreparingChapter(fraction = 1.4f, etaSeconds = 30)
        assertEquals(ListenNote.Chapter(1.4f), listenNote(ListenState.PAUSED, chapter))
        assertEquals(100, ListenNote.Chapter(1.4f).percent)
        assertEquals(42, ListenNote.Chapter(0.425f).percent)
        assertNull(listenNote(ListenState.OFF, chapter))
    }

    // --- The listen bar ---

    @Test
    fun theBarShowsTheSpinnerAndPreparingOnlyInPreparing() {
        var state by mutableStateOf(ListenState.STARTING)
        val narration = NarrationStatus.Preparing(reserveSeconds = 0.0, etaSeconds = null)
        compose.setContent {
            FolioTheme(dynamic = false) {
                ListenBar(
                    playing = state == ListenState.PLAYING || state == ListenState.PREPARING,
                    starting = state == ListenState.STARTING,
                    speed = 1.0,
                    onToggle = {},
                    onPrevious = {},
                    onNext = {},
                    onSpeed = {},
                    onStop = {},
                    note = listenNote(state, narration),
                )
            }
        }
        listOf(ListenState.STARTING, ListenState.PLAYING, ListenState.PAUSED).forEach {
            state = it
            compose.waitForIdle()
            compose.onAllNodesWithText(preparingText).assertCountEquals(0)
            compose.onAllNodesWithTag(READER_LISTEN_NOTE_TAG).assertCountEquals(0)
        }
        state = ListenState.PREPARING
        compose.waitForIdle()
        compose.onNodeWithTag(READER_LISTEN_NOTE_TAG).assertIsDisplayed()
        compose.onNodeWithText(preparingText).assertIsDisplayed()
        // The bar itself keeps its tag either way, for the emulator journey test.
        compose.onNodeWithTag(READER_LISTEN_BAR_TAG).assertExists()
    }

    @Test
    fun theBarShowsTheEtaWhenKnown() {
        listenBar(ListenNote.Preparing(etaSeconds = 6))
        compose.onNodeWithText("Preparing narration… about 6 s").assertIsDisplayed()
        compose.onAllNodesWithText(preparingText).assertCountEquals(0)
    }

    @Test
    fun playPauseStaysUsableWhilePreparing() {
        var toggled = 0
        listenBar(ListenNote.Preparing(etaSeconds = null), onToggle = { toggled++ })
        compose.onNodeWithContentDescription("Pause").assertIsEnabled().performClick()
        assertEquals(1, toggled)
        compose.onNodeWithContentDescription("Next sentence").assertIsEnabled()
    }

    @Test
    fun chapterPreparationShowsItsPercent() {
        listenBar(ListenNote.Chapter(0.42f), playing = false)
        compose.onNodeWithText("Preparing chapter… 42%").assertIsDisplayed()
        compose.onAllNodesWithText(preparingText).assertCountEquals(0)
    }

    private fun listenBar(note: ListenNote?, playing: Boolean = true, onToggle: () -> Unit = {}) {
        compose.setContent {
            FolioTheme(dynamic = false) {
                ListenBar(
                    playing = playing,
                    starting = false,
                    speed = 1.0,
                    onToggle = onToggle,
                    onPrevious = {},
                    onNext = {},
                    onSpeed = {},
                    onStop = {},
                    note = note,
                )
            }
        }
    }

    // --- The too-slow sheet ---

    private val slow =
        NarrationStatus.TooSlow(
            pace = 0.8,
            prepareChapterSeconds = 150,
            breakEverySeconds = 50,
            breakSeconds = 10,
            offerFasterModel = true,
            fasterModelMb = 350,
        )

    @Test
    fun theTooSlowSheetOffersFourWaysWithTheNumbers() {
        val chosen = mutableListOf<SlowChoice>()
        var dismissed = 0
        compose.setContent {
            FolioTheme(dynamic = false) {
                TooSlowSheet(slow, onChoice = { chosen += it }, onDismiss = { dismissed++ })
            }
        }
        compose
            .onNodeWithText("This phone can't make the natural voice fast enough")
            .assertIsDisplayed()
        compose
            .onNodeWithText(
                "It makes speech at 0.8× the speed it reads, so reading would keep stopping."
            )
            .assertIsDisplayed()
        // 150 s rounds up to 3 min.
        compose.onNodeWithText("Prepare this chapter first (about 3 min)").performClick()
        compose.onNodeWithText("Start now, with a pause of about 10 s every 50 s").performClick()
        compose.onNodeWithText("Use the phone voice").performClick()
        compose.onNodeWithText("Get the faster natural voice (350 MB)").performClick()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(
            listOf(
                SlowChoice.PREPARE_CHAPTER,
                SlowChoice.WITH_BREAKS,
                SlowChoice.PHONE_VOICE,
                SlowChoice.FASTER_MODEL,
            ),
            chosen,
        )
        assertEquals(1, dismissed)
    }

    @Test
    fun theTooSlowSheetHidesTheFasterVoiceWhenNotOffered() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                TooSlowSheet(slow.copy(offerFasterModel = false), onChoice = {}, onDismiss = {})
            }
        }
        compose.onAllNodesWithText("faster natural voice", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Use the phone voice").assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed()
    }

    // --- The speed test sheet ---

    private fun config(variant: String, threads: Int, rtf: Double, provider: String = "cpu") =
        ConfigResult(
            variant = variant,
            threads = threads,
            provider = provider,
            fellBack = false,
            rtfMean = rtf,
            rtfP95 = rtf * 1.3,
            cv = 0.1,
            firstUnitMs = 1500,
            overheadA = 0.2,
            perCharB1 = 0.03,
        )

    private fun report(best: ConfigResult?, tooSlow: Boolean, start: Double? = 2.4) =
        BenchmarkReport(
            loadMs = mapOf("INT8" to 2900L),
            configs = listOfNotNull(best, config("INT8", 2, 1.9)),
            best = best,
            wholeGapP50Ms = 170,
            wholeGapP95Ms = 240,
            separateGapP50Ms = 180,
            separateGapP95Ms = 260,
            sustainedMinutes = 15,
            sustainedRtfFirst = 0.5,
            sustainedRtfLast = 0.625,
            thermalMax = 2,
            predictedStartSeconds = start,
            tooSlow = tooSlow,
        )

    private class TestCalls {
        var started = 0
        var cancelled = 0
        var shared = 0
        val minutes = mutableListOf<Int>()
    }

    private fun testSheet(state: NarrationBenchmark.State): TestCalls {
        val calls = TestCalls()
        compose.setContent {
            FolioTheme(dynamic = false) {
                NarrationTestSheet(
                    state,
                    sustainedMinutes = 5,
                    variants = 2,
                    onMinutes = { calls.minutes += it },
                    onStart = { calls.started++ },
                    onCancel = { calls.cancelled++ },
                    onShare = { calls.shared++ },
                )
            }
        }
        return calls
    }

    @Test
    fun theTestSheetStartsFromIdle() {
        val calls = testSheet(NarrationBenchmark.State.Idle)
        compose.onNodeWithText("Test read-aloud speed").assertIsDisplayed()
        compose.onNodeWithText("Sustained run: 5 min").assertIsDisplayed()
        // 2 + 6 per model × 2 + 5.
        compose.onNodeWithText("Takes about 19 min.").assertIsDisplayed()
        compose.onNodeWithText("15 min").performClick()
        compose.onNodeWithText("Start test").performClick()
        assertEquals(listOf(15), calls.minutes)
        assertEquals(1, calls.started)
    }

    @Test
    fun theTestSheetShowsItsStepAndCanStop() {
        val calls =
            testSheet(
                NarrationBenchmark.State.Running(
                    NarrationBenchmark.Step.SWEEP,
                    stepIndex = 2,
                    stepCount = 5,
                    fraction = 0.3f,
                    detail = "Standard, 4 threads",
                )
            )
        compose.onNodeWithText("Testing… step 2 of 5").assertIsDisplayed()
        compose.onNodeWithText("Trying each setting").assertIsDisplayed()
        compose.onNodeWithText("Standard, 4 threads").assertIsDisplayed()
        compose.onAllNodesWithText("You can turn the screen off now.").assertCountEquals(0)
        compose.onAllNodesWithText("Start test").assertCountEquals(0)
        compose.onNodeWithText("Stop test").performClick()
        assertEquals(1, calls.cancelled)
    }

    @Test
    fun theLongRunSaysTheScreenCanGoOff() {
        testSheet(
            NarrationBenchmark.State.Running(
                NarrationBenchmark.Step.SUSTAINED,
                stepIndex = 4,
                stepCount = 5,
                fraction = 0.1f,
                detail = null,
            )
        )
        compose.onNodeWithText("Long run").assertIsDisplayed()
        compose.onNodeWithText("You can turn the screen off now.").assertIsDisplayed()
    }

    @Test
    fun aFastResultSaysHowFastAndWhenItStarts() {
        val best = config("FP32", 4, 0.45)
        val calls =
            testSheet(NarrationBenchmark.State.Done(report(best, tooSlow = false), File("b")))
        compose
            .onNodeWithText(
                "Best: Faster, 4 threads. Makes speech 2.2× faster than it reads. " +
                    "Starts in about 3 s."
            )
            .assertIsDisplayed()
        compose.onNodeWithText("Faster, 4 threads").assertExists()
        compose.onNodeWithText("Standard, 2 threads").assertExists()
        compose.onNodeWithText("0.5×").assertExists()
        compose
            .onNodeWithText("Pause between sentences: 170 ms read whole, 180 ms one by one.")
            .assertExists()
        compose.onNodeWithText("Over 15 min the speed went from 2.0× to 1.6×.").assertExists()
        compose.onNodeWithText("Share results").performScrollTo().performClick()
        compose.onNodeWithText("Test again").performScrollTo().performClick()
        assertEquals(1, calls.shared)
        assertEquals(1, calls.started)
    }

    @Test
    fun aResultWithoutAStartEstimateLeavesItOut() {
        testSheet(
            NarrationBenchmark.State.Done(
                report(config("INT8", 4, 0.5), tooSlow = false, start = null),
                File("b"),
            )
        )
        compose
            .onNodeWithText("Best: Standard, 4 threads. Makes speech 2.0× faster than it reads.")
            .assertIsDisplayed()
    }

    @Test
    fun aSlowResultSaysItCannotKeepUp() {
        testSheet(
            NarrationBenchmark.State.Done(
                report(config("int8", 4, 1.25), tooSlow = true),
                File("b"),
            )
        )
        compose
            .onNodeWithText("Standard makes speech at 0.8× its reading speed: it can't keep up.")
            .assertIsDisplayed()
    }

    @Test
    fun aFailedTestSaysSoAndCanRunAgain() {
        val calls = testSheet(NarrationBenchmark.State.Failed("The voice could not load."))
        compose.onNodeWithText("The test did not finish.").assertIsDisplayed()
        compose.onNodeWithText("The voice could not load.").assertIsDisplayed()
        compose.onNodeWithText("Test again").performScrollTo().performClick()
        assertEquals(1, calls.started)
    }

    // --- The Listening sheet ---

    @Test
    fun theListeningSheetShowsReadyAheadPaceModelAndTools() {
        val variants = mutableListOf<KokoroModel.Variant>()
        val records = mutableListOf<Boolean>()
        var tests = 0
        var shares = 0
        var downloads = 0
        compose.setContent {
            FolioTheme(dynamic = false) {
                ListenOptionsSheet(
                    SleepTimer.OFF,
                    emptyList(),
                    naturalVoiceId(3),
                    onSleep = {},
                    onVoice = {},
                    onAddVoices = {},
                    natural =
                        NaturalVoiceRow(
                            fits = true,
                            NaturalVoice.Ready,
                            speaking = true,
                            pace = 1.4,
                            readyAheadSeconds = 42,
                            faster = NaturalVoice.Missing,
                            recording = false,
                            hasRecording = true,
                        ),
                    narration =
                        NarrationActions(
                            onVariant = { variants += it },
                            onDownloadFaster = { downloads++ },
                            onTest = { tests++ },
                            onRecord = { records += it },
                            onShareRecording = { shares++ },
                        ),
                )
            }
        }
        compose.onNodeWithText("Makes speech 1.4× faster than it reads").assertExists()
        compose.onNodeWithText("Ready ahead: 42 s").assertExists()
        compose.onNodeWithText("Natural voice model").assertExists()
        compose.onNodeWithText("Standard").performScrollTo().assertIsSelected().performClick()
        // The faster model is not here yet: its row waits for the download.
        compose.onNodeWithText("Faster (larger download)").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Download (350 MB)").performScrollTo().performClick()
        compose.onNodeWithText("Test read-aloud speed").performScrollTo().performClick()
        compose.onNodeWithText("Record read-aloud measurements").performScrollTo().performClick()
        compose.onNodeWithText("Share last measurements").performScrollTo().performClick()
        assertEquals(listOf(KokoroModel.Variant.INT8), variants)
        assertEquals(1, downloads)
        assertEquals(1, tests)
        assertEquals(listOf(true), records)
        assertEquals(1, shares)
    }

    @Test
    fun theSlowPaceLineAndNoShareWithoutARecording() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                ListenOptionsSheet(
                    SleepTimer.OFF,
                    emptyList(),
                    naturalVoiceId(3),
                    onSleep = {},
                    onVoice = {},
                    onAddVoices = {},
                    natural =
                        NaturalVoiceRow(
                            fits = true,
                            NaturalVoice.Ready,
                            speaking = true,
                            pace = 0.8,
                            variant = KokoroModel.Variant.FP32,
                            faster = NaturalVoice.Ready,
                        ),
                )
            }
        }
        compose.onNodeWithText("Makes speech at 0.8× its reading speed").assertExists()
        compose.onNodeWithText("Faster (larger download)").performScrollTo().assertIsSelected()
        compose.onAllNodesWithText("Download", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Ready ahead", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Share last measurements").assertCountEquals(0)
    }

    @Test
    fun noModelRowsOrToolsBeforeTheVoiceIsDownloaded() {
        compose.setContent {
            FolioTheme(dynamic = false) {
                ListenOptionsSheet(
                    SleepTimer.OFF,
                    emptyList(),
                    null,
                    onSleep = {},
                    onVoice = {},
                    onAddVoices = {},
                    natural = NaturalVoiceRow(fits = true, NaturalVoice.Missing, speaking = false),
                )
            }
        }
        compose.onNodeWithText("Download (132 MB)").assertExists()
        compose.onAllNodesWithText("Natural voice model").assertCountEquals(0)
        compose.onAllNodesWithText("Test read-aloud speed").assertCountEquals(0)
        compose.onAllNodesWithText("Record read-aloud measurements").assertCountEquals(0)
    }

    // --- Copy and helpers ---

    /**
     * Every string in strings_narration.xml numbers its arguments 1..n with no gaps and no
     * unnumbered ones, and formats with dummy values to text with no placeholder left over.
     */
    @Test
    fun narrationStringsHaveNoStrayFormatArguments() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val file = File("src/main/res/values/strings_narration.xml")
        assertTrue("run from the app module", file.isFile)
        val nodes =
            DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(file)
                .getElementsByTagName("string")
        assertTrue(nodes.length > 0)
        val numbered = Regex("""%(\d+)\$([sd])""")
        val anyPlaceholder = Regex("""%(\d+\$)?[a-zA-Z]""")
        for (i in 0 until nodes.length) {
            val name = nodes.item(i).attributes.getNamedItem("name").nodeValue
            val raw = nodes.item(i).textContent
            val withoutPercent = raw.replace("%%", "")
            val args = numbered.findAll(withoutPercent).toList()
            assertEquals(
                "$name has an unnumbered placeholder",
                args.size,
                anyPlaceholder.findAll(withoutPercent).count(),
            )
            val positions = args.map { it.groupValues[1].toInt() }.toSortedSet()
            assertEquals("$name numbers its arguments 1..n", (1..positions.size).toSet(), positions)
            val types = args.associate { it.groupValues[1].toInt() to it.groupValues[2] }
            val values =
                (1..positions.size)
                    .map<Int, Any> { if (types[it] == "d") 7 else "x" }
                    .toTypedArray()
            val id = context.resources.getIdentifier(name, "string", context.packageName)
            assertTrue("$name is a resource", id != 0)
            val text = context.getString(id, *values)
            assertFalse("$name left a placeholder: $text", anyPlaceholder.containsMatchIn(text))
        }
    }

    @Test
    fun helpersRoundAsTheSheetsSay() {
        assertEquals(1, aboutMinutes(0))
        assertEquals(1, aboutMinutes(60))
        assertEquals(3, aboutMinutes(121))
        assertEquals(350, megabytes(349_906_910))
        assertEquals(132, megabytes(132_303_094))
        assertEquals(2.0, paceOf(0.5)!!, 1e-9)
        assertNull(paceOf(0.0))
        assertNull(paceOf(Double.NaN))
        assertEquals("1.4", formatPace(1.43, Locale.US))
        assertEquals("1,4", formatPace(1.43, Locale.GERMANY))
        assertEquals(20, narrationTestMinutes(variants = 0, sustainedMinutes = 12))
    }

    @Test
    fun zipFolderKeepsRelativePathsAndContents() {
        val dir = temp.newFolder("bench-1")
        File(dir, "summary.json").writeText("{\"rtf\":0.5}")
        File(dir, "wav").mkdirs()
        File(dir, "wav/whole.wav").writeBytes(byteArrayOf(1, 2, 3))
        val out = File(temp.root, "share/narration.zip")
        zipFolder(dir, out)
        ZipFile(out).use { zip ->
            assertEquals(
                listOf("summary.json", "wav/whole.wav"),
                zip.entries().toList().map { it.name },
            )
            assertEquals(
                "{\"rtf\":0.5}",
                zip.getInputStream(zip.getEntry("summary.json")).reader().readText(),
            )
        }
    }

    @Test(expected = java.io.IOException::class)
    fun zipFolderRefusesAMissingFolder() {
        zipFolder(File(temp.root, "missing"), File(temp.root, "out.zip"))
    }
}
