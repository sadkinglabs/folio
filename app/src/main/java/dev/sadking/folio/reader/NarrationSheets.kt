@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.sadking.folio.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.reader.narration.BenchmarkReport
import dev.sadking.folio.reader.narration.ConfigResult
import dev.sadking.folio.reader.narration.NarrationStatus
import dev.sadking.folio.reader.narration.SlowChoice
import dev.sadking.folio.ui.FolioMotion
import dev.sadking.folio.ui.FolioSpace
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.roundToInt

internal const val READER_LISTEN_NOTE_TAG = "reader-listen-note"
internal const val NARRATION_SLOW_SHEET_TAG = "narration-slow-sheet"
internal const val NARRATION_TEST_SHEET_TAG = "narration-test-sheet"

/** The sustained-run lengths the speed test offers, in minutes. */
internal val NARRATION_TEST_MINUTES = listOf(5, 15, 30)

/** What the listen bar says above its controls while narration is not simply playing. */
internal sealed interface ListenNote {
    /** Audio is being made before speech can start; [etaSeconds] when the engine can tell. */
    data class Preparing(val etaSeconds: Int?) : ListenNote

    /** The whole chapter is being made ahead ("Prepare this chapter first"). */
    data class Chapter(val fraction: Float) : ListenNote {
        val percent: Int
            get() = (fraction * 100).toInt().coerceIn(0, 100)
    }
}

/**
 * The note for the listen bar. "Preparing narration…" shows only in [ListenState.PREPARING], which
 * the engine sets only while audio is actually missing; chapter preparation shows its progress.
 */
internal fun listenNote(state: ListenState, narration: NarrationStatus): ListenNote? =
    when {
        state == ListenState.OFF -> null
        narration is NarrationStatus.PreparingChapter -> ListenNote.Chapter(narration.fraction)
        state == ListenState.PREPARING ->
            ListenNote.Preparing(
                (narration as? NarrationStatus.Preparing)?.etaSeconds?.takeIf { it > 0 }
            )
        else -> null
    }

/** A pace or speed ratio with one decimal, as "1.4", in the reader's locale. */
internal fun formatPace(value: Double, locale: Locale): String =
    String.format(locale, "%.1f", value)

/** Listening speed per second of work from a real-time factor; null when not measurable. */
internal fun paceOf(rtf: Double): Double? = if (rtf > 0 && rtf.isFinite()) 1 / rtf else null

/** Whole minutes for "about N min", never 0. */
internal fun aboutMinutes(seconds: Int): Int = ceil(seconds / 60.0).toInt().coerceAtLeast(1)

/** Download size in MB as the sheet shows it: 349 906 910 bytes is 350 MB. */
internal fun megabytes(bytes: Long): Int = (bytes / 1_000_000.0).roundToInt()

/**
 * A rough length of the speed test: loading and the pause check, about 6 min of settings per
 * installed model, then the sustained run.
 */
internal fun narrationTestMinutes(variants: Int, sustainedMinutes: Int): Int =
    2 + 6 * variants.coerceAtLeast(1) + sustainedMinutes

/**
 * Zips every file under [dir] into [out], with paths relative to [dir], for one Share. The folders
 * hold timings, lengths and the sample passage's audio only, never book text.
 */
internal fun zipFolder(dir: File, out: File) {
    if (!dir.isDirectory) throw IOException("Nothing to share")
    out.parentFile?.mkdirs()
    val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
    ZipOutputStream(out.outputStream().buffered()).use { zip ->
        files.forEach { file ->
            zip.putNextEntry(ZipEntry(file.relativeTo(dir).invariantSeparatorsPath))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

/** The pill above the listen bar's controls: a spinner or the chapter's progress, and the words. */
@Composable
internal fun ListenNoteChip(note: ListenNote, modifier: Modifier = Modifier) {
    Surface(
        modifier.semantics(mergeDescendants = true) {}.testTag(READER_LISTEN_NOTE_TAG),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            when (note) {
                is ListenNote.Preparing -> {
                    // With "remove animations" on, a still shape; the words carry the state.
                    if (FolioMotion.reduced) LoadingIndicator({ 0.7f }, Modifier.size(28.dp))
                    else LoadingIndicator(Modifier.size(28.dp))
                    Text(
                        if (note.etaSeconds != null)
                            stringResource(R.string.listen_preparing_eta, note.etaSeconds)
                        else stringResource(R.string.listen_preparing),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                is ListenNote.Chapter -> {
                    CircularProgressIndicator(
                        progress = { note.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.padding(4.dp).size(20.dp),
                        strokeWidth = 3.dp,
                    )
                    Text(
                        stringResource(R.string.listen_chapter_progress, note.percent),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/**
 * The natural voice cannot keep up on this phone: why, in numbers, and four ways on. Reading stays
 * paused while it shows; "Not now" closes it and keeps the pause.
 */
@Composable
internal fun TooSlowSheet(
    slow: NarrationStatus.TooSlow,
    onChoice: (SlowChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = FolioSpace.section)
            .padding(bottom = 32.dp)
            .testTag(NARRATION_SLOW_SHEET_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        Text(
            stringResource(R.string.listen_slow_title),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.listen_slow_body, formatPace(slow.pace, locale)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(FolioSpace.small))
        Button(
            onClick = { onChoice(SlowChoice.PREPARE_CHAPTER) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            ButtonText(
                stringResource(
                    R.string.listen_slow_prepare,
                    aboutMinutes(slow.prepareChapterSeconds),
                )
            )
        }
        FilledTonalButton(
            onClick = { onChoice(SlowChoice.WITH_BREAKS) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            ButtonText(
                stringResource(
                    R.string.listen_slow_breaks,
                    slow.breakSeconds,
                    slow.breakEverySeconds,
                )
            )
        }
        FilledTonalButton(
            onClick = { onChoice(SlowChoice.PHONE_VOICE) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            ButtonText(stringResource(R.string.listen_slow_phone))
        }
        if (slow.offerFasterModel)
            FilledTonalButton(
                onClick = { onChoice(SlowChoice.FASTER_MODEL) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                ButtonText(stringResource(R.string.listen_slow_faster, slow.fasterModelMb))
            }
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.listen_slow_dismiss))
        }
    }
}

@Composable
private fun ButtonText(text: String) {
    Text(text, Modifier.padding(vertical = 4.dp), textAlign = TextAlign.Center)
}

/** "Standard" or "Faster" for a benchmark row's variant, whatever case the engine names it in. */
@Composable
private fun variantLabel(variant: String): String =
    stringResource(
        if (variant.contains("fp32", ignoreCase = true)) R.string.listen_variant_faster_name
        else R.string.listen_variant_standard
    )

@Composable
private fun stepLabel(step: NarrationBenchmark.Step): String =
    stringResource(
        when (step) {
            NarrationBenchmark.Step.LOAD -> R.string.listen_test_step_load
            NarrationBenchmark.Step.SWEEP -> R.string.listen_test_step_sweep
            NarrationBenchmark.Step.DIAGNOSTIC -> R.string.listen_test_step_diagnostic
            NarrationBenchmark.Step.SUSTAINED -> R.string.listen_test_step_sustained
            NarrationBenchmark.Step.RESULT -> R.string.listen_test_step_result
        }
    )

/**
 * "Test read-aloud speed": runs the on-device benchmark on a sample passage and shows what this
 * phone can do. The test runs on in the background if the sheet closes; reopening shows it.
 */
@Composable
internal fun NarrationTestSheet(
    state: NarrationBenchmark.State,
    sustainedMinutes: Int,
    /** How many natural voice models are installed, for the length estimate. */
    variants: Int,
    onMinutes: (Int) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onShare: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = FolioSpace.section)
            .padding(bottom = 32.dp)
            .testTag(NARRATION_TEST_SHEET_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        Text(
            stringResource(R.string.listen_test),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        when (state) {
            NarrationBenchmark.State.Idle -> {
                TestIntro(sustainedMinutes, variants, onMinutes)
                Button(onClick = onStart) { Text(stringResource(R.string.listen_test_start)) }
            }
            is NarrationBenchmark.State.Running -> TestRunning(state, onCancel)
            is NarrationBenchmark.State.Done -> {
                TestResult(state.report)
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
                    FilledTonalButton(onClick = onShare) {
                        Text(stringResource(R.string.listen_test_share))
                    }
                    TextButton(onClick = onStart) {
                        Text(stringResource(R.string.listen_test_again))
                    }
                }
            }
            is NarrationBenchmark.State.Failed -> {
                Text(
                    stringResource(R.string.listen_test_failed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                if (state.message.isNotBlank())
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                TestIntro(sustainedMinutes, variants, onMinutes)
                Button(onClick = onStart) { Text(stringResource(R.string.listen_test_again)) }
            }
        }
    }
}

@Composable
private fun TestIntro(sustainedMinutes: Int, variants: Int, onMinutes: (Int) -> Unit) {
    Text(
        stringResource(R.string.listen_test_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.listen_test_minutes, sustainedMinutes),
        style = MaterialTheme.typography.titleSmall,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
        NARRATION_TEST_MINUTES.forEach { minutes ->
            FilterChip(
                selected = minutes == sustainedMinutes,
                onClick = { onMinutes(minutes) },
                label = { Text(stringResource(R.string.listen_sleep_minutes, minutes)) },
            )
        }
    }
    Text(
        stringResource(
            R.string.listen_test_duration,
            narrationTestMinutes(variants, sustainedMinutes),
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TestRunning(state: NarrationBenchmark.State.Running, onCancel: () -> Unit) {
    Text(
        stringResource(R.string.listen_test_running, state.stepIndex, state.stepCount),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(stepLabel(state.step), style = MaterialTheme.typography.bodyLarge)
    LinearProgressIndicator(
        progress = { state.fraction.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
    )
    state.detail?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.step == NarrationBenchmark.Step.SUSTAINED)
        Text(
            stringResource(R.string.listen_test_screen_off),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.listen_test_cancel)) }
}

@Composable
private fun TestResult(report: BenchmarkReport) {
    val locale = LocalConfiguration.current.locales[0]
    val best = report.best
    val bestPace = best?.let { paceOf(it.rtfMean) }
    Text(
        when {
            best == null || bestPace == null -> stringResource(R.string.listen_test_failed)
            report.tooSlow ->
                stringResource(
                    R.string.listen_test_result_slow,
                    variantLabel(best.variant),
                    formatPace(bestPace, locale),
                )
            report.predictedStartSeconds != null ->
                stringResource(
                    R.string.listen_test_result_ok,
                    variantLabel(best.variant),
                    best.threads,
                    formatPace(bestPace, locale),
                    ceil(report.predictedStartSeconds).toInt().coerceAtLeast(1),
                )
            else ->
                stringResource(
                    R.string.listen_test_result_pace,
                    variantLabel(best.variant),
                    best.threads,
                    formatPace(bestPace, locale),
                )
        },
        style = MaterialTheme.typography.titleMedium,
    )
    if (report.configs.isNotEmpty()) {
        Text(
            stringResource(R.string.listen_test_settings),
            Modifier.padding(top = FolioSpace.small),
            style = MaterialTheme.typography.titleSmall,
        )
        report.configs.forEach { ConfigRow(it, chosen = it == best) }
    }
    val whole = report.wholeGapP50Ms
    val separate = report.separateGapP50Ms
    if (whole != null && separate != null)
        Text(
            stringResource(R.string.listen_test_gaps, whole, separate),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    val minutes = report.sustainedMinutes
    val first = report.sustainedRtfFirst?.let(::paceOf)
    val last = report.sustainedRtfLast?.let(::paceOf)
    if (minutes != null && first != null && last != null)
        Text(
            stringResource(
                R.string.listen_test_sustained,
                minutes,
                formatPace(first, locale),
                formatPace(last, locale),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
}

/** One setting the test tried, and how many seconds of speech it made per second. */
@Composable
private fun ConfigRow(config: ConfigResult, chosen: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    val name = variantLabel(config.variant)
    val label =
        when {
            config.fellBack ->
                stringResource(
                    R.string.listen_test_config_fell_back,
                    name,
                    config.threads,
                    config.provider,
                )
            config.provider.equals("cpu", ignoreCase = true) ->
                stringResource(R.string.listen_test_config, name, config.threads)
            else ->
                stringResource(
                    R.string.listen_test_config_provider,
                    name,
                    config.threads,
                    config.provider,
                )
        }
    val colour =
        if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val weight = if (chosen) FontWeight.SemiBold else null
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
    ) {
        Text(
            label,
            Modifier.weight(1f),
            color = colour,
            fontWeight = weight,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            paceOf(config.rtfMean)?.let { formatPace(it, locale) + "×" } ?: "–",
            color = colour,
            fontWeight = weight,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
