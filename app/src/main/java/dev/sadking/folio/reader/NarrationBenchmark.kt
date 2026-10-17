package dev.sadking.folio.reader

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import dev.sadking.folio.R
import dev.sadking.folio.reader.narration.BenchmarkReport
import dev.sadking.folio.reader.narration.BenchmarkRow
import dev.sadking.folio.reader.narration.BenchmarkStats
import dev.sadking.folio.reader.narration.ConfigResult
import dev.sadking.folio.reader.narration.EdgeSilence
import dev.sadking.folio.reader.narration.GateDecision
import dev.sadking.folio.reader.narration.GateInput
import dev.sadking.folio.reader.narration.Pcm16
import dev.sadking.folio.reader.narration.PendingUnit
import dev.sadking.folio.reader.narration.PlannedUtterance
import dev.sadking.folio.reader.narration.StartGate
import dev.sadking.folio.reader.narration.SynthesisUnit
import dev.sadking.folio.reader.narration.ThroughputModel
import dev.sadking.folio.reader.narration.ThroughputPrior
import dev.sadking.folio.reader.narration.UnitBuilder
import dev.sadking.folio.reader.narration.normalizeKey
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Test read-aloud speed": measures Kokoro on this phone with a fixed public-domain passage
 * (`res/raw/narration_passage.txt`, the same text as the host measurements), never with a book.
 *
 * 1. Load: cold model load per installed variant.
 * 2. Sweep: variant × threads, about a minute of audio each, through the real [UnitBuilder]; one
 *    row each for the `nnapi` and `xnnpack` providers at the best thread count.
 * 3. Diagnostic: the same text rendered in one call and unit by unit, saved as WAV with the silence
 *    between sentences measured in both, to tell synthesis delay from built-in silence.
 * 4. Sustained: a synthesis loop for [Plan.sustainedMinutes] with the screen allowed off, logging
 *    thermal status, to catch throttling.
 * 5. Result: saves the measured speed for the start gate (`narration_priors`, best threads) and
 *    exports CSV, JSON and WAV to `files/narration/bench-<start-ms>/`.
 *
 * One run at a time per process, never while read-aloud holds the model. Called from Main; the work
 * runs on its own thread.
 */
internal object NarrationBenchmark {
    sealed interface State {
        data object Idle : State

        data class Running(
            val step: Step,
            val stepIndex: Int,
            val stepCount: Int,
            val fraction: Float,
            val detail: String?,
        ) : State

        data class Done(val report: BenchmarkReport, val exportDir: File) : State

        data class Failed(val message: String) : State
    }

    enum class Step {
        LOAD,
        SWEEP,
        DIAGNOSTIC,
        SUSTAINED,
        RESULT,
    }

    data class Plan(
        val sustainedMinutes: Int = 5,
        val threads: List<Int> = listOf(2, 3, 4, 5, 6),
        val tryProviders: Boolean = true,
    )

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Executors.newSingleThreadExecutor {
                        Thread(it, "kokoro-bench").apply { isDaemon = true }
                    }
                    .asCoroutineDispatcher()
        )
    private var job: Job? = null

    /** Builds a model; replaced in tests. */
    @Volatile
    internal var loader: (Context, KokoroRuntime.Config) -> LoadedVoice = { context, config ->
        SherpaSynthesizer.load(context, config)
    }

    /** Listening seconds per sweep configuration. */
    internal var sweepAudioSeconds = 60.0

    /** Listening seconds per provider row. */
    internal var providerAudioSeconds = 20.0

    /** Characters of the passage used for the diagnostic render (about a minute of speech). */
    internal var diagnosticChars = 1200

    private val silenceScale
        get() = KokoroRuntime.tuning.silenceScale

    /** The variants that can be benchmarked (installed). */
    fun installedVariants(context: Context): List<KokoroModel.Variant> =
        KokoroModel.installed(context)

    fun start(context: Context, plan: Plan) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        mutableState.value = State.Running(Step.LOAD, 1, stepCount(plan), 0f, null)
        job = scope.launch {
            try {
                run(app, plan)
            } catch (cancel: CancellationException) {
                mutableState.value = State.Idle
                throw cancel
            } catch (failure: Throwable) {
                mutableState.value = State.Failed(failure.message ?: "The test did not finish")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        mutableState.value = State.Idle
    }

    private fun stepCount(plan: Plan) = if (plan.sustainedMinutes > 0) 5 else 4

    private fun progress(plan: Plan, step: Step, fraction: Float, detail: String?) {
        val index =
            when (step) {
                Step.LOAD -> 1
                Step.SWEEP -> 2
                Step.DIAGNOSTIC -> 3
                Step.SUSTAINED -> 4
                Step.RESULT -> stepCount(plan)
            }
        mutableState.value =
            State.Running(step, index, stepCount(plan), fraction.coerceIn(0f, 1f), detail)
    }

    private suspend fun run(app: Context, plan: Plan) {
        // Read-aloud was just stopped to make room: its engine lets go of the voice as it closes.
        withTimeoutOrNull(RELEASE_WAIT_MS) { while (KokoroRuntime.inUse) delay(100) }
        check(!KokoroRuntime.inUse) { "Read-aloud is using the voice" }
        val variants = installedVariants(app)
        check(variants.isNotEmpty()) { "No natural voice is installed" }
        // Free an idle read-aloud model so two models do not compete for memory.
        KokoroRuntime.releaseIdle()
        val startedAt = System.currentTimeMillis()
        val dir = File(app.filesDir, "narration/bench-$startedAt").apply { mkdirs() }
        val passage =
            app.resources.openRawResource(R.raw.narration_passage).bufferedReader().use {
                it.readText()
            }
        val units = passageUnits(passage)
        val power = app.getSystemService(PowerManager::class.java)
        val thermal = {
            power.currentThermalStatus to power.getThermalHeadroom(10).takeIf { !it.isNaN() }
        }
        val wake =
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "librillo:narration-test").apply {
                setReferenceCounted(false)
                acquire((plan.sustainedMinutes + 30) * 60_000L)
            }
        val clockStart = SystemClock.elapsedRealtime()
        val rows = mutableListOf<BenchmarkRow>()
        try {
            // 1. Load.
            val loadMs = LinkedHashMap<String, Long>()
            variants.forEachIndexed { i, variant ->
                progress(plan, Step.LOAD, i.toFloat() / variants.size, name(variant))
                val began = SystemClock.elapsedRealtime()
                val voice = loader(app, config(variant, 4, "cpu"))
                loadMs[name(variant)] = SystemClock.elapsedRealtime() - began
                voice.release()
            }

            // 2. Sweep.
            val configs = mutableListOf<ConfigResult>()
            val sweeps = variants.flatMap { v -> plan.threads.map { v to it } }
            sweeps.forEachIndexed { i, (variant, threads) ->
                progress(plan, Step.SWEEP, i.toFloat() / sweeps.size, "${name(variant)} × $threads")
                val voice = loader(app, config(variant, threads, "cpu"))
                try {
                    val measured =
                        measure(voice, units, "sweep", variant, threads, "cpu", sweepAudioSeconds) {
                            thermal()
                        }
                    rows += measured.map { it.copy(tMs = it.tMs - clockStart) }
                    configs += BenchmarkStats.summarize(measured)
                } finally {
                    voice.release()
                }
            }
            if (plan.tryProviders) {
                for (variant in variants) {
                    val bestThreads =
                        configs
                            .filter { it.variant == name(variant) && it.provider == "cpu" }
                            .minByOrNull { it.rtfMean }
                            ?.threads ?: continue
                    for (provider in listOf("nnapi", "xnnpack")) {
                        progress(plan, Step.SWEEP, 1f, "${name(variant)} $provider")
                        val voice =
                            try {
                                loader(app, config(variant, bestThreads, provider))
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Throwable) {
                                null
                            }
                        if (voice == null) continue
                        try {
                            // sherpa-onnx falls back to the CPU with only a log line; compare the
                            // row with the CPU one to tell.
                            val measured =
                                measure(
                                    voice,
                                    units,
                                    "provider",
                                    variant,
                                    bestThreads,
                                    provider,
                                    providerAudioSeconds,
                                ) {
                                    thermal()
                                }
                            rows += measured.map { it.copy(tMs = it.tMs - clockStart) }
                            configs += BenchmarkStats.summarize(measured)
                        } finally {
                            voice.release()
                        }
                    }
                }
            }
            val best = configs.filter { it.provider == "cpu" }.minByOrNull { it.rtfMean }!!
            val bestVariant = KokoroModel.Variant.entries.first { name(it) == best.variant }

            // 3. Diagnostic render.
            progress(plan, Step.DIAGNOSTIC, 0f, null)
            val diagnostic = diagnosticText(passage)
            val voice = loader(app, config(bestVariant, best.threads, "cpu"))
            val wholeGaps: List<Int>
            val separateGaps: List<Int>
            try {
                val pieces =
                    voice
                        .synthesizePieces(diagnostic, KOKORO_DEFAULT_SPEAKER, 1f, silenceScale)
                        .map { Pcm16.fromFloat(it, 0) }
                wholeGaps = BenchmarkStats.gaps(pieces, voice.sampleRate)
                writeWav(File(dir, "whole.wav"), pieces, voice.sampleRate)
                progress(plan, Step.DIAGNOSTIC, 0.5f, null)
                val separate =
                    passageUnits(diagnostic).map { unit ->
                        kotlin.coroutines.coroutineContext.ensureActive()
                        Pcm16.fromFloat(
                            voice.synthesize(
                                unit.speechText,
                                KOKORO_DEFAULT_SPEAKER,
                                1f,
                                silenceScale,
                            ) {
                                true
                            },
                            0,
                        )
                    }
                separateGaps = BenchmarkStats.gaps(separate, voice.sampleRate)
                writeWav(File(dir, "separate.wav"), separate, voice.sampleRate)
            } finally {
                voice.release()
            }

            // 4. Sustained.
            var sustainedFirst: Double? = null
            var sustainedLast: Double? = null
            var thermalMax: Int? = null
            if (plan.sustainedMinutes > 0) {
                val sustained = loader(app, config(bestVariant, best.threads, "cpu"))
                try {
                    val began = SystemClock.elapsedRealtime()
                    val limitMs = plan.sustainedMinutes * 60_000L
                    val measured = mutableListOf<BenchmarkRow>()
                    var seq = 0
                    while (SystemClock.elapsedRealtime() - began < limitMs) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val unit = units[seq % units.size]
                        measured +=
                            row(
                                sustained,
                                unit,
                                seq++,
                                "sustained",
                                bestVariant,
                                best.threads,
                                "cpu",
                            ) {
                                thermal()
                            }
                        val elapsed = SystemClock.elapsedRealtime() - began
                        progress(
                            plan,
                            Step.SUSTAINED,
                            elapsed.toFloat() / limitMs,
                            "${elapsed / 60_000} / ${plan.sustainedMinutes} min",
                        )
                    }
                    rows += measured.map { it.copy(tMs = it.tMs - clockStart) }
                    val start = measured.first().tMs
                    val end = measured.last().tMs
                    sustainedFirst =
                        measured
                            .filter { it.tMs - start <= DRIFT_WINDOW_MS }
                            .map { it.rtf }
                            .average()
                    sustainedLast =
                        measured.filter { end - it.tMs <= DRIFT_WINDOW_MS }.map { it.rtf }.average()
                    thermalMax = measured.maxOf { it.thermalStatus }
                } finally {
                    sustained.release()
                }
            }

            // 5. Result.
            progress(plan, Step.RESULT, 0f, null)
            val bestRows = rows.filter {
                it.variant == best.variant && it.threads == best.threads && it.provider == "cpu"
            }
            val prior = BenchmarkStats.prior(bestRows, System.currentTimeMillis())
            NarrationPriors.save(app, bestVariant, best.threads, prior)
            NarrationPriors.saveBestThreads(app, bestVariant, best.threads)
            for (variant in variants.filter { it != bestVariant }) {
                configs
                    .filter { it.variant == name(variant) && it.provider == "cpu" }
                    .minByOrNull { it.rtfMean }
                    ?.let { cfg ->
                        val variantRows = rows.filter {
                            it.variant == cfg.variant &&
                                it.threads == cfg.threads &&
                                it.provider == "cpu"
                        }
                        NarrationPriors.save(
                            app,
                            variant,
                            cfg.threads,
                            BenchmarkStats.prior(variantRows, System.currentTimeMillis()),
                        )
                        NarrationPriors.saveBestThreads(app, variant, cfg.threads)
                    }
            }
            val predicted = predictStart(prior)
            val report =
                BenchmarkReport(
                    loadMs = loadMs,
                    configs = configs,
                    best = best,
                    wholeGapP50Ms = percentile(wholeGaps, 0.5),
                    wholeGapP95Ms = percentile(wholeGaps, 0.95),
                    separateGapP50Ms = percentile(separateGaps, 0.5),
                    separateGapP95Ms = percentile(separateGaps, 0.95),
                    sustainedMinutes = plan.sustainedMinutes.takeIf { it > 0 },
                    sustainedRtfFirst = sustainedFirst,
                    sustainedRtfLast = sustainedLast,
                    thermalMax = thermalMax,
                    predictedStartSeconds = predicted,
                    tooSlow = predicted == null,
                )
            File(dir, "rows.csv")
                .writeText(
                    (listOf(BenchmarkStats.csvHeader()) + rows.map(BenchmarkStats::csvRow))
                        .joinToString("\n", postfix = "\n")
                )
            File(dir, "report.json").writeText(report.toJson())
            NarrationRecorder.log {
                "bench best=${best.variant}x${best.threads} rtf=${best.rtfMean} start=$predicted"
            }
            mutableState.value = State.Done(report, dir)
        } finally {
            if (wake.isHeld) wake.release()
        }
    }

    private fun name(variant: KokoroModel.Variant) = variant.name.lowercase()

    private fun config(variant: KokoroModel.Variant, threads: Int, provider: String) =
        KokoroRuntime.Config(variant, threads, provider, silenceScale)

    /** Synthesises [units] in order (repeating) until [audioSeconds] of speech were made. */
    private suspend fun measure(
        voice: LoadedVoice,
        units: List<SynthesisUnit>,
        phase: String,
        variant: KokoroModel.Variant,
        threads: Int,
        provider: String,
        audioSeconds: Double,
        thermal: () -> Pair<Int, Float?>,
    ): List<BenchmarkRow> {
        val rows = mutableListOf<BenchmarkRow>()
        var audioMs = 0L
        var seq = 0
        while (audioMs < audioSeconds * 1000) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val row =
                row(voice, units[seq % units.size], seq, phase, variant, threads, provider, thermal)
            seq++
            rows += row
            audioMs += max(1L, row.audioMs)
        }
        return rows
    }

    private suspend fun row(
        voice: LoadedVoice,
        unit: SynthesisUnit,
        seq: Int,
        phase: String,
        variant: KokoroModel.Variant,
        threads: Int,
        provider: String,
        thermal: () -> Pair<Int, Float?>,
    ): BenchmarkRow {
        val context = kotlin.coroutines.coroutineContext
        val began = System.nanoTime()
        val samples =
            voice.synthesize(unit.speechText, KOKORO_DEFAULT_SPEAKER, 1f, silenceScale) {
                context.isActive
            }
        val synthMs = (System.nanoTime() - began) / 1_000_000
        context.ensureActive()
        val pcm = Pcm16.fromFloat(samples, 0)
        val (lead, trail) = EdgeSilence.measureEdges(pcm)
        val (status, headroom) = thermal()
        return BenchmarkRow(
            phase = phase,
            variant = name(variant),
            threads = threads,
            provider = provider,
            fellBack = false,
            seq = seq,
            chars = unit.chars,
            synthMs = synthMs,
            audioMs = pcm.size * 1000L / voice.sampleRate,
            edgeLeadMs = (lead * 1000L / voice.sampleRate).toInt(),
            edgeTrailMs = (trail * 1000L / voice.sampleRate).toInt(),
            thermalStatus = status,
            thermalHeadroom = headroom,
            tMs = SystemClock.elapsedRealtime(),
        )
    }

    /** The passage as read-aloud would make it: paragraphs, sentences, then synthesis units. */
    internal fun passageUnits(text: String): List<SynthesisUnit> {
        val builder = UnitBuilder(KokoroRuntime.tuning).apply { restart(gen = 0) }
        val units = mutableListOf<SynthesisUnit>()
        var index = 0L
        text
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEachIndexed { ordinal, paragraph ->
                val sentences =
                    KokoroSentenceTokenizer().tokenize(paragraph).map {
                        paragraph.substring(it.first, it.last + 1)
                    }
                sentences.forEachIndexed { i, sentence ->
                    units +=
                        builder.push(
                            PlannedUtterance(
                                index = index++,
                                key = normalizeKey(sentence),
                                text = sentence,
                                href = "passage",
                                elementOrdinal = ordinal.toLong(),
                                endsSentence = endsSentence(sentence),
                                endsElement = i == sentences.lastIndex,
                            )
                        )
                }
            }
        units += builder.flush()
        return units
    }

    /** The first paragraphs of the passage, about [diagnosticChars] long. */
    private fun diagnosticText(passage: String): String {
        val out = StringBuilder()
        for (paragraph in passage.split(Regex("\\n\\s*\\n")).map { it.trim() }) {
            if (paragraph.isEmpty()) continue
            if (out.isNotEmpty() && out.length + paragraph.length > diagnosticChars) break
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(paragraph)
        }
        return out.toString()
    }

    /**
     * The wait from the tap to the first word the start gate predicts with [prior], for a 3 s first
     * sentence and 4 s sentences after it; null when it would say "too slow".
     */
    internal fun predictStart(prior: ThroughputPrior): Double? {
        val model = ThroughputModel(prior.copy(n = max(prior.n, 1)), KokoroRuntime.tuning)
        // One observation at the prior itself, so the gate trusts it as a measurement.
        val firstChars = (3.0 / prior.alpha1).toInt()
        val nextChars = (4.0 / prior.alpha1).toInt()
        model.observe(nextChars, 1.0, prior.a + prior.b1 * nextChars, prior.alpha1 * nextChars)
        val pending =
            listOf(PendingUnit(0, firstChars, 0.0)) +
                (1..100).map { PendingUnit(it.toLong(), nextChars, null) }
        val decision =
            StartGate(model, KokoroRuntime.tuning)
                .evaluate(GateInput(0.0, false, pending, 1.0, false, null, false), prior.n)
        val first = model.predictSynthSeconds(firstChars, 1.0, false)
        return when (decision) {
            is GateDecision.TooSlow -> null
            is GateDecision.Wait -> max(decision.etaSeconds, first)
            GateDecision.Start -> first
        }
    }

    private fun percentile(values: List<Int>, p: Double): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    /** 16-bit mono WAV of [segments] laid end to end. */
    private fun writeWav(file: File, segments: List<ShortArray>, sampleRate: Int) {
        val frames = segments.sumOf { it.size }
        val header =
            ByteBuffer.allocate(44)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray())
                .putInt(36 + frames * 2)
                .put("WAVEfmt ".toByteArray())
                .putInt(16)
                .putShort(1)
                .putShort(1)
                .putInt(sampleRate)
                .putInt(sampleRate * 2)
                .putShort(2)
                .putShort(16)
                .put("data".toByteArray())
                .putInt(frames * 2)
                .array()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.write(header)
            segments.forEach { out.write(Pcm16.toBytesLittleEndian(it)) }
        }
    }

    private const val DRIFT_WINDOW_MS = 120_000L
    private const val RELEASE_WAIT_MS = 5_000L
}
