package dev.sadking.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sadking.folio.data.PhoneBookmark
import dev.sadking.folio.reader.narration.BenchmarkReport
import dev.sadking.folio.reader.narration.ConfigResult
import dev.sadking.folio.reader.narration.NarrationStatus
import dev.sadking.folio.ui.FolioTheme
import dev.sadking.folio.ui.LocalReducedMotion

/**
 * Previews of the reader's chrome over a stand-in page, since Readium's WebView cannot render in a
 * preview or on the JVM. The bars, sheets, offer card and failed state are the real composables.
 */
private const val SAMPLE_TITLE = "The Orchard"

private val sampleParagraphs =
    listOf(
        "The orchard had been planted by someone who expected to be forgotten, and in this at least they had been right. Nobody in the village remembered a name, only that the trees were older than the oldest house.",
        "In March the pruning began. Ada worked from the east end, where the frost lingered longest, and by the time she reached the walnut at the gate the first blossom had opened behind her.",
        "She kept no diary, but the trees did. Every cut she made that year would be read, decades on, by whoever came next with a saw and a question.",
        "The river ran a hundred yards below the lowest terrace. On still evenings its sound climbed the slope and settled among the apples like weather.",
    )

/** Four paragraphs of serif text on the page colour, where the book would be. */
@Composable
internal fun ReaderPagePlaceholder(dark: Boolean) {
    Column(
        Modifier.fillMaxSize()
            .background(if (dark) Color.Black else Color.White)
            .padding(horizontal = 28.dp, vertical = 72.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        sampleParagraphs.forEach {
            Text(
                it,
                color = if (dark) Color(0xFFE0E0E0) else Color(0xFF202020),
                fontFamily = FontFamily.Serif,
                fontSize = 17.sp,
                lineHeight = 27.sp,
            )
        }
    }
}

private val sampleTicks = listOf(0.08f, 0.21f, 0.33f, 0.5f, 0.62f, 0.77f, 0.9f)

@Preview(name = "Reader controls · light", widthDp = 412, heightDp = 892)
@Preview(
    name = "Reader controls · dark",
    widthDp = 412,
    heightDp = 892,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun ReaderControlsPreview() {
    val dark = isSystemInDarkTheme()
    FolioTheme(dynamic = false) {
        Box(Modifier.fillMaxSize()) {
            ReaderPagePlaceholder(dark)
            ReaderTopBar(
                null,
                SAMPLE_TITLE,
                enabled = true,
                backEnabled = true,
                insets = WindowInsets(top = 24.dp),
                onBack = {},
                onPanel = {},
                modifier = Modifier.align(Alignment.TopCenter),
                onListen = {},
            )
            ReaderBottomBar(
                null,
                fraction = 0.04f,
                label = PageLabel(12, 340, 4),
                ticks = sampleTicks,
                pageCount = 340,
                chapterAt = { "Chapter one" },
                insets = WindowInsets(bottom = 24.dp),
                onJump = {},
                onPrevious = {},
                onNext = {},
                onBookmark = {},
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Preview(name = "Reader text sheet · light", widthDp = 412, heightDp = 760)
@Composable
internal fun ReaderTextPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ReaderTextSheet(
                ReaderTextState(fontSize = 120.0, pageTheme = "Paper"),
                ReaderTextActions({}, {}, {}, {}, {}, {}, {}, {}),
            )
        }
    }
}

private val sampleContents =
    listOf(
        ContentsEntry("Cover", 0),
        ContentsEntry("Part one · Spring", 0),
        ContentsEntry("Chapter one", 1),
        ContentsEntry("Chapter two", 1),
        ContentsEntry("Chapter three", 1),
        ContentsEntry("Part two · The long dry", 0),
        ContentsEntry("Chapter four", 1),
        ContentsEntry(null, 1),
        ContentsEntry("Afterword", 0),
    )

private val sampleBookmarks =
    listOf(
        PhoneBookmark("b1", "sample1", "", "{}", "Chapter one · 4%", 0),
        PhoneBookmark("b2", "sample1", "", "{}", "Chapter three · 31%", 0),
    )

@Preview(name = "Reader contents · light", widthDp = 412, heightDp = 560)
@Composable
internal fun ReaderContentsPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ReaderContentsSheet(sampleContents, 3, sampleBookmarks, {}, {}, {})
        }
    }
}

private val sampleDevices =
    listOf(
        DeviceRow("kobo", "Kobo", 41, System.currentTimeMillis() - 2 * 60 * 60 * 1000),
        DeviceRow("kindle", "Kindle", 38, System.currentTimeMillis() - 26 * 60 * 60 * 1000),
        DeviceRow(
            "ssh",
            "E-reader",
            36,
            System.currentTimeMillis() - 3 * 24 * 60 * 60 * 1000,
            true,
        ),
    )

@Preview(name = "Reader devices · light", widthDp = 412, heightDp = 420)
@Composable
internal fun ReaderDevicesPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ReaderDevicesSheet(sampleDevices, busy = false, {})
        }
    }
}

@Preview(name = "Reader devices · none", widthDp = 412, heightDp = 260)
@Composable
internal fun ReaderDevicesEmptyPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ReaderDevicesSheet(emptyList(), busy = false, {})
        }
    }
}

@Preview(name = "Reader link offer · light", widthDp = 412, heightDp = 892)
@Composable
internal fun ReaderLinkOfferPreview() {
    val dark = isSystemInDarkTheme()
    FolioTheme(dynamic = false) {
        Box(Modifier.fillMaxSize()) {
            ReaderPagePlaceholder(dark)
            LinkOfferCard(
                "Kindle",
                41,
                Modifier.align(Alignment.BottomCenter).padding(16.dp),
                onContinue = {},
                onStay = {},
                onTimeout = {},
            )
        }
    }
}

@Preview(name = "Reader listening · light", widthDp = 412, heightDp = 892)
@Composable
internal fun ReaderListenPreview() {
    val dark = isSystemInDarkTheme()
    FolioTheme(dynamic = false) {
        Box(Modifier.fillMaxSize()) {
            ReaderPagePlaceholder(dark)
            ListenBar(
                playing = true,
                starting = false,
                speed = 1.25,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                onToggle = {},
                onPrevious = {},
                onNext = {},
                onSpeed = {},
                onStop = {},
                timerOn = true,
            )
        }
    }
}

@Preview(name = "Reader listening options · light", widthDp = 412, heightDp = 892)
@Composable
internal fun ReaderListenOptionsPreview() {
    val uk = java.util.Locale.UK
    val us = java.util.Locale.US
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ListenOptionsSheet(
                SleepTimer.MINUTES_30,
                listOf(
                    ListenVoice("uk-1", uk, highQuality = true, needsNetwork = false),
                    ListenVoice("uk-2", uk, highQuality = true, needsNetwork = false),
                    ListenVoice("us-1", us, highQuality = false, needsNetwork = false),
                    ListenVoice("us-2", us, highQuality = true, needsNetwork = true),
                ),
                "uk-2",
                onSleep = {},
                onVoice = {},
                onAddVoices = {},
            )
        }
    }
}

@Preview(name = "Reader natural voice · light", widthDp = 412, heightDp = 892)
@Composable
internal fun ReaderListenNaturalPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
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
                        hasRecording = true,
                    ),
            )
        }
    }
}

@Preview(name = "Reader natural voice offer · light", widthDp = 412, heightDp = 892)
@Composable
internal fun ReaderListenOfferPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ListenOptionsSheet(
                SleepTimer.OFF,
                listOf(
                    ListenVoice(
                        "uk-1",
                        java.util.Locale.UK,
                        highQuality = true,
                        needsNetwork = false,
                    )
                ),
                null,
                onSleep = {},
                onVoice = {},
                onAddVoices = {},
                natural =
                    NaturalVoiceRow(fits = true, NaturalVoice.Downloading(0.42f), speaking = false),
            )
        }
    }
}

/**
 * The listen bar while the first audio is made: the play button pauses, the pill counts down. Drawn
 * with reduced motion, so the spinner is still and the JVM renderer can settle on one frame.
 */
@Preview(name = "Reader listening preparing · light", widthDp = 412, heightDp = 892)
@Composable
internal fun ReaderListenPreparingPreview() {
    val dark = isSystemInDarkTheme()
    FolioTheme(dynamic = false) {
        CompositionLocalProvider(LocalReducedMotion provides true) {
            Box(Modifier.fillMaxSize()) {
                ReaderPagePlaceholder(dark)
                ListenBar(
                    playing = true,
                    starting = false,
                    speed = 1.0,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    onToggle = {},
                    onPrevious = {},
                    onNext = {},
                    onSpeed = {},
                    onStop = {},
                    note = ListenNote.Preparing(etaSeconds = 6),
                )
            }
        }
    }
}

@Preview(name = "Reader listening chapter · light", widthDp = 412, heightDp = 892)
@Composable
internal fun ReaderListenChapterPreview() {
    val dark = isSystemInDarkTheme()
    FolioTheme(dynamic = false) {
        Box(Modifier.fillMaxSize()) {
            ReaderPagePlaceholder(dark)
            ListenBar(
                playing = false,
                starting = false,
                speed = 1.5,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                onToggle = {},
                onPrevious = {},
                onNext = {},
                onSpeed = {},
                onStop = {},
                note = ListenNote.Chapter(0.42f),
            )
        }
    }
}

internal val sampleTooSlow =
    NarrationStatus.TooSlow(
        pace = 0.8,
        prepareChapterSeconds = 150,
        breakEverySeconds = 50,
        breakSeconds = 10,
        offerFasterModel = true,
        fasterModelMb = 350,
    )

@Preview(name = "Reader listening too slow · light", widthDp = 412, heightDp = 892)
@Composable
internal fun ReaderListenTooSlowPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            TooSlowSheet(sampleTooSlow, onChoice = {}, onDismiss = {})
        }
    }
}

@Preview(name = "Reader speed test running · light", widthDp = 412, heightDp = 892)
@Composable
internal fun ReaderListenBenchmarkRunningPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            NarrationTestSheet(
                NarrationBenchmark.State.Running(
                    step = NarrationBenchmark.Step.SWEEP,
                    stepIndex = 2,
                    stepCount = 5,
                    fraction = 0.35f,
                    detail = "Standard, 4 threads",
                ),
                sustainedMinutes = 15,
                variants = 2,
                onMinutes = {},
                onStart = {},
                onCancel = {},
                onShare = {},
            )
        }
    }
}

private fun sampleConfig(variant: String, threads: Int, rtf: Double, provider: String = "cpu") =
    ConfigResult(
        variant = variant,
        threads = threads,
        provider = provider,
        fellBack = provider != "cpu",
        rtfMean = rtf,
        rtfP95 = rtf * 1.3,
        cv = 0.12,
        firstUnitMs = 1800,
        overheadA = 0.2,
        perCharB1 = 0.03,
    )

internal val sampleBenchmarkReport: BenchmarkReport = run {
    val best = sampleConfig("FP32", 4, 0.45)
    BenchmarkReport(
        loadMs = mapOf("INT8" to 2900L, "FP32" to 3400L),
        configs =
            listOf(
                sampleConfig("INT8", 2, 1.9),
                sampleConfig("INT8", 4, 1.3),
                sampleConfig("FP32", 2, 0.71),
                best,
                sampleConfig("FP32", 6, 0.58),
                sampleConfig("FP32", 4, 0.47, provider = "nnapi"),
            ),
        best = best,
        wholeGapP50Ms = 170,
        wholeGapP95Ms = 240,
        separateGapP50Ms = 180,
        separateGapP95Ms = 260,
        sustainedMinutes = 15,
        sustainedRtfFirst = 0.45,
        sustainedRtfLast = 0.52,
        thermalMax = 2,
        predictedStartSeconds = 2.4,
        tooSlow = false,
    )
}

@Preview(name = "Reader speed test result · light", widthDp = 412, heightDp = 892)
@Composable
internal fun ReaderListenBenchmarkResultPreview() {
    FolioTheme(dynamic = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            NarrationTestSheet(
                NarrationBenchmark.State.Done(sampleBenchmarkReport, java.io.File("bench")),
                sustainedMinutes = 15,
                variants = 2,
                onMinutes = {},
                onStart = {},
                onCancel = {},
                onShare = {},
            )
        }
    }
}

@Preview(name = "Reader failed · light", widthDp = 412, heightDp = 892)
@Composable
internal fun ReaderFailedPreview() {
    FolioTheme(dynamic = false) {
        ReaderFailed(
            "This EPUB is protected and cannot be opened here.",
            onBack = {},
            backEnabled = true,
        )
    }
}
