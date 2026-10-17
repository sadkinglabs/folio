@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.sadking.folio.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import dev.sadking.folio.reader.narration.BoundaryRecord
import dev.sadking.folio.reader.narration.CacheKey
import dev.sadking.folio.reader.narration.EdgeSilence
import dev.sadking.folio.reader.narration.GateDecision
import dev.sadking.folio.reader.narration.GateInput
import dev.sadking.folio.reader.narration.HeardEvent
import dev.sadking.folio.reader.narration.Match
import dev.sadking.folio.reader.narration.NarrationMetrics
import dev.sadking.folio.reader.narration.NarrationStatus
import dev.sadking.folio.reader.narration.NarrationTuning
import dev.sadking.folio.reader.narration.Pcm16
import dev.sadking.folio.reader.narration.PcmQueue
import dev.sadking.folio.reader.narration.PendingUnit
import dev.sadking.folio.reader.narration.PlannedUtterance
import dev.sadking.folio.reader.narration.ReadyUnit
import dev.sadking.folio.reader.narration.SessionSummaryFixed
import dev.sadking.folio.reader.narration.Span
import dev.sadking.folio.reader.narration.StartGate
import dev.sadking.folio.reader.narration.SynthesisUnit
import dev.sadking.folio.reader.narration.ThroughputModel
import dev.sadking.folio.reader.narration.ThroughputPrior
import dev.sadking.folio.reader.narration.UnitBuilder
import dev.sadking.folio.reader.narration.UnitRecord
import dev.sadking.folio.reader.narration.UtteranceAligner
import dev.sadking.folio.reader.narration.UtteranceSpans
import dev.sadking.folio.reader.narration.WaterMarks
import dev.sadking.folio.reader.narration.normalizeKey
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.r2.navigator.preferences.PreferencesEditor
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try

/**
 * Text compared without regard to spacing, so a sentence planned ahead matches the one asked for.
 */
internal fun normalize(text: String) = text.trim().replace(Regex("\\s+"), " ")

/** A Kokoro speaker: its ID in the model, a name and its accent. */
internal class KokoroVoice(val speaker: Int, val name: String, override val language: Language) :
    TtsEngine.Voice

/** The English speakers offered, best-rated first (Kokoro's own grades). */
internal val KOKORO_VOICES =
    listOf(
        KokoroVoice(3, "Heart", Language("en-US")),
        KokoroVoice(2, "Bella", Language("en-US")),
        KokoroVoice(6, "Nicole", Language("en-US")),
        KokoroVoice(9, "Sarah", Language("en-US")),
        KokoroVoice(5, "Kore", Language("en-US")),
        KokoroVoice(16, "Michael", Language("en-US")),
        KokoroVoice(14, "Fenrir", Language("en-US")),
        KokoroVoice(18, "Puck", Language("en-US")),
        KokoroVoice(21, "Emma", Language("en-GB")),
        KokoroVoice(22, "Isabella", Language("en-GB")),
        KokoroVoice(26, "George", Language("en-GB")),
        KokoroVoice(25, "Fable", Language("en-GB")),
    )

internal const val KOKORO_DEFAULT_SPEAKER = 3

internal data class KokoroSettings(
    override val language: Language,
    val speed: Double,
    val speaker: Int,
) : TtsEngine.Settings {
    override val overrideContentLanguage: Boolean = false
}

internal data class KokoroPreferences(
    override val language: Language? = null,
    val speed: Double? = null,
    val speaker: Int? = null,
) : TtsEngine.Preferences<KokoroPreferences> {
    override fun plus(other: KokoroPreferences) =
        KokoroPreferences(
            other.language ?: language,
            other.speed ?: speed,
            other.speaker ?: speaker,
        )
}

internal class KokoroPreferencesEditor(initial: KokoroPreferences) :
    PreferencesEditor<KokoroPreferences> {
    override var preferences: KokoroPreferences = initial
        private set

    override fun clear() {
        preferences = KokoroPreferences()
    }
}

internal class KokoroError(override val message: String, override val cause: Error? = null) :
    TtsEngine.Error

/** Keeps the CPU awake while narration is being prepared with the screen off. */
internal interface NarrationWakeLock {
    fun hold(timeoutMs: Long)

    fun release()
}

/** The phone's thermal status (PowerManager.THERMAL_STATUS_*) and headroom, when known. */
internal data class ThermalReading(val status: Int, val headroom: Float?)

/** What the engine needs from Android and from the session, behind seams for JVM tests. */
internal class NarrationEnv(
    val tuning: NarrationTuning = NarrationTuning.DEFAULT,
    val clock: () -> Long = SystemClock::elapsedRealtime,
    val cache: NarrationCache? = null,
    val io: CoroutineDispatcher = narrationIo,
    val wakeLock: NarrationWakeLock? = null,
    val thermal: () -> ThermalReading = { ThermalReading(0, null) },
    val screenOn: () -> Boolean = { true },
    val recorder: NarrationRecorder? = null,
    val variant: KokoroModel.Variant = KokoroModel.Variant.INT8,
    val threads: Int = 4,
    val prior: ThroughputPrior = NarrationPriors.default(variant),
    val savePrior: (ThroughputPrior) -> Unit = {},
    /** When Listen was tapped, on [clock]: the start budget counts from here. */
    val tappedAtMs: Long? = null,
    val modelLoadMs: Long = 0,
    val warmup: SynthesisObservation? = null,
    /** True when the faster model is not installed and could be offered. */
    val fasterModelOffer: Boolean = false,
)

/**
 * Speaks with Kokoro, keeping the voice continuous across sentences.
 *
 * Readium asks for one utterance at a time and waits for [TtsEngine.Listener.onDone]. Instead of
 * making and playing each one on request, the engine plans the utterances ahead itself
 * ([UtterancePlanner]), makes them on the synthesis thread one after another ([KokoroRuntime]),
 * queues the audio ([PcmQueue]) and streams it into one output that never stops between sentences
 * ([AudioTrackSink]). Readium's requests are matched to the plan; `onStart` and `onDone` fire when
 * the playback head reaches the utterance, so the highlight and the saved position follow what was
 * heard, not what was made.
 *
 * Playback starts as soon as the reserve built so far and the measured synthesis speed say it will
 * not stop again ([StartGate]); a deeper reserve then builds while listening, up to the high water
 * mark. Every job, unit and event carries a generation: a seek, voice or speed change bumps it, so
 * late results of the old one never play.
 *
 * Main-thread confined: Readium calls it on Main, and the synthesis and audio threads post back.
 * Every entry point is guarded: an unexpected exception ends narration with an error (Readium's
 * player then stops) instead of ending the app.
 */
internal class KokoroEngine(
    private val runner: SynthesisRunner,
    sink: (PcmQueue, WriteLimits, SinkEvents) -> AudioSink,
    private val source: PlanSource,
    startAt: Locator?,
    initial: KokoroPreferences,
    private val env: NarrationEnv = NarrationEnv(),
) : TtsEngine<KokoroSettings, KokoroPreferences, KokoroError, KokoroVoice> {
    private val tuning = env.tuning
    private val sampleRate = runner.sampleRate
    private val main = Handler(Looper.getMainLooper())
    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Main.immediate +
                CoroutineExceptionHandler { _, failure -> crashed(failure) }
        )
    private val genRef = AtomicLong(1)
    private val gen: Long
        get() = genRef.get()

    private val queue = PcmQueue(sampleRate, tuning)
    private val limits = WriteLimits().also { it.gen = gen }
    private val output: AudioSink = sink(queue, limits, Events())
    private val model = ThroughputModel(env.prior, tuning)
    private val gate = StartGate(model, tuning)
    private val marks = WaterMarks(tuning)
    private val metrics = NarrationMetrics(tuning)
    private val builder = UnitBuilder(tuning)
    private val modelId = KokoroModel.modelId(env.variant)

    private val mutableSettings = MutableStateFlow(resolve(initial))
    override val settings: StateFlow<KokoroSettings> = mutableSettings
    override val voices: Set<KokoroVoice> = KOKORO_VOICES.toSet()

    private val mutableStatus = MutableStateFlow<NarrationStatus>(NarrationStatus.Idle)
    private val mutablePace = MutableStateFlow<Double?>(null)
    private val mutableReadyAhead = MutableStateFlow<Int?>(null)

    /** What narration is doing: preparing, playing, paused, too slow or failed. */
    val status: StateFlow<NarrationStatus> = mutableStatus

    /** Seconds of speech made per second, from measurements; above 1 it keeps ahead. */
    val pace: StateFlow<Double?> = mutablePace

    /** Listening seconds ready ahead of the voice. */
    val readyAheadSeconds: StateFlow<Int?> = mutableReadyAhead

    // Plan (Main).
    private val planned = ArrayDeque<PlannedUtterance>()
    private var nextIndex = 0L
    private var walker: PlanWalker? = null
    private var walking: Job? = null
    private var planEnded = false
    /** False after the reader's position could not be found: no look-ahead until it is. */
    private var aligned = true
    private var primed = false
    private var pendingSpeak: Pair<TtsEngine.RequestId, String>? = null
    private var pendingLocation: Pair<Locator, String>? = null

    // Units of the current generation, in plan order.
    private val slots = ArrayDeque<Slot>()
    private val reorder = TreeMap<Long, ReadyUnit>()
    private var nextOfferSeq = 0L
    private val pieces = HashMap<Long, Piece>()
    private var nextPiece = 0L
    private var lastPieceUtterance: Long? = null

    // Readium.
    private var listener: TtsEngine.Listener<KokoroError>? = null
    private var request: TtsEngine.RequestId? = null
    private var requestIndex = -1L
    private var held: Long? = null
    private var heldAtMs = 0L
    private val heardStarted = HashSet<Long>()
    private val heardDone = HashSet<Long>()
    private var lastDoneAtMs: Long? = null
    private var flushesSent = 0

    // Playback.
    private var playing = false
    private var waitSinceMs: Long? = env.tappedAtMs
    private var stallStartMs: Long? = null
    private val stallTimes = ArrayDeque<Long>()
    private var mode = Mode.NORMAL
    private var chapterHref: String? = null
    private var slowShown = false
    private var emptyStreak = 0
    /** Set once narration cannot go on (the model fails): every later request gets the error. */
    private var failure: String? = null
    private var closed = false
    private var wakeHeld = false
    private var thermal = ThermalReading(0, null)
    private var thermalAtMs = Long.MIN_VALUE

    // Measurements.
    private var firstAudioAtMs: Long? = null
    private var firstUnitSynthMs: Long? = null
    private var gatePredictedWaitMs: Long? = null
    private var gateActualWaitMs: Long? = null
    private var alignMiss = 0
    private var readiumLate = 0
    private var underrunsSeen = 0

    private enum class Mode {
        NORMAL,
        WITH_BREAKS,
        CHAPTER,
    }

    private enum class SlotState {
        PENDING,
        LOADING,
        READY,
        /** Made and on disk, not in memory (chapter preparation beyond the high water mark). */
        CACHED,
        FAILED,
    }

    /**
     * One synthesis unit. Each of its parts gets a piece id, unique and increasing in plan order;
     * the queue counts pieces, not utterances, so a sentence split across two units starts at its
     * first piece and ends at its last.
     */
    private class Slot(val unit: SynthesisUnit, val key: String, val pieces: List<Long>) {
        var state = SlotState.PENDING
        var attempts = 0
        var queuedAtMs = 0L
        @Volatile var startedNanos = 0L
        var audioSeconds: Double? = null
    }

    private class Piece(val utterance: Long, val first: Boolean, val last: Boolean)

    private sealed interface Outcome {
        class Made(
            val pcm: ShortArray,
            val synthMs: Long,
            val leadMs: Int,
            val trailMs: Int,
        ) : Outcome

        class Failed(val message: String) : Outcome
    }

    init {
        env.warmup?.let {
            model.observe(it.chars, 1.0, it.synthSeconds, it.audioSeconds, weight = 0.5)
        }
        startWalk(startAt)
        scope.launch { tick() }
    }

    private fun resolve(preferences: KokoroPreferences) =
        KokoroSettings(
            preferences.language ?: Language("en-US"),
            preferences.speed ?: 1.0,
            preferences.speaker ?: KOKORO_DEFAULT_SPEAKER,
        )

    override fun setListener(listener: TtsEngine.Listener<KokoroError>?) {
        this.listener = listener
    }

    // ---- Readium ----------------------------------------------------------------------------

    override fun submitPreferences(preferences: KokoroPreferences) = guard {
        val before = mutableSettings.value
        val after = resolve(preferences)
        mutableSettings.value = after
        if (closed || (after.speaker == before.speaker && after.speed == before.speed)) return@guard
        // Readium follows with stop() and speak(current): have that sentence on its way already.
        val at =
            held ?: requestIndex.takeIf { it >= 0 } ?: planned.firstOrNull()?.index ?: return@guard
        reanchor(at)
    }

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) = guard {
        if (closed) return@guard
        request = requestId
        // Asking again after the "too slow" question means: try again.
        slowShown = false
        failure?.let { message ->
            post { if (!closed && request == requestId) fail(message) }
            return@guard
        }
        if (!primed) {
            pendingSpeak = requestId to text
            return@guard
        }
        align(requestId, text)
    }

    /**
     * Readium stopped (pause, skip, restart). Pausing keeps everything buffered, so the next
     * [speak] of the same sentence continues mid-sentence; any other decides then.
     */
    override fun stop() = guard {
        if (closed) return@guard
        val interrupted = request
        pauseNow()
        request = null
        pendingSpeak = null
        if (interrupted != null) listener?.onInterrupted(interrupted)
    }

    /**
     * Pauses the output at once. Readium only calls [stop] while it waits on an utterance; a pause
     * between two utterances reaches the engine through [ReadAloud] instead.
     */
    fun pauseNow() = guard {
        if (closed) return@guard
        if (held == null && requestIndex >= 0) {
            held = requestIndex
            heldAtMs = env.clock()
        }
        output.send(SinkCommand.Pause)
        playing = false
        waitSinceMs = null
        stallStartMs = null
        if (failure == null) setStatus(NarrationStatus.Paused(reserve()))
        updateWakeLock()
    }

    /** From ReadAloud's location collect: the utterance Readium shows now. */
    fun onLocation(locator: Locator, text: String) = guard {
        if (closed) return@guard
        val key = normalizeKey(text)
        if (!primed) {
            pendingLocation = locator to key
            return@guard
        }
        checkLocation(locator, key)
    }

    /** Prepare the rest of the chapter before playing (TooSlow choice). */
    fun prepareChapter() = guard {
        if (closed) return@guard
        mode = Mode.CHAPTER
        chapterHref = planned.firstOrNull { it.index == anchorIndex() }?.href
        slowShown = false
        waitSinceMs = waitSinceMs ?: env.clock()
        pinChapter()
        pump()
        evaluateGate()
    }

    /**
     * Play now with a pause whenever the reserve runs out (TooSlow choice; automatic when the
     * screen is off).
     */
    fun playWithBreaks() = guard {
        if (closed) return@guard
        mode = Mode.WITH_BREAKS
        slowShown = false
        waitSinceMs = waitSinceMs ?: env.clock()
        pump()
        evaluateGate()
    }

    override fun close() {
        if (closed) return
        val summary = runCatching {
            metrics.summary(
                env.clock(),
                SessionSummaryFixed(
                    tapToFirstAudioMs =
                        firstAudioAtMs?.let { first -> env.tappedAtMs?.let { first - it } },
                    modelLoadMs = env.modelLoadMs,
                    firstUnitSynthMs = firstUnitSynthMs,
                    gatePredictedWaitMs = gatePredictedWaitMs,
                    gateActualWaitMs = gateActualWaitMs,
                    variant = env.variant.name.lowercase(),
                    threads = env.threads,
                    speed = mutableSettings.value.speed,
                    silenceScale = tuning.silenceScale,
                    alignMiss = alignMiss,
                    readiumLate = readiumLate,
                ),
            )
        }
        closed = true
        genRef.incrementAndGet()
        // Each step on its own: one that fails must not keep the model or the output held.
        quietly { output.send(SinkCommand.Release) }
        // The runtime frees the model on its own thread, after any request in progress.
        quietly { runner.release() }
        quietly { walking?.cancel() }
        quietly { scope.cancel() }
        main.removeCallbacks(recheck)
        quietly { releaseWakeLock() }
        quietly {
            if (model.observations > 0) env.savePrior(model.toPrior(System.currentTimeMillis()))
        }
        summary.getOrNull()?.let { done ->
            quietly { env.recorder?.finish(done) }
            NarrationRecorder.log {
                "session tapToFirstAudioMs=${done.tapToFirstAudioMs} stalls=${done.stalls}"
            }
        }
        quietly { env.cache?.let { cache -> narrationScope.launch { cache.pin(emptyList()) } } }
        setStatus(NarrationStatus.Idle)
    }

    /** Runs [block]; an unexpected exception ends narration with an error, never the app. */
    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            crashed(failure)
        }
    }

    /** Posts [block] to Main, guarded. */
    private fun post(block: () -> Unit) {
        main.post { guard(block) }
    }

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {}
    }

    /** A bug or a failure nobody planned for: stop narrating and say so. */
    private fun crashed(failure: Throwable) {
        NarrationRecorder.log { "engine error ${failure.javaClass.name}" }
        if (closed) return
        val message = failure.message ?: "Narration failed"
        try {
            fail(message)
        } catch (_: Throwable) {
            // fail() itself broke (a listener threw): at least show that narration stopped.
            this.failure = message
            request = null
            setStatus(NarrationStatus.Failed(message))
        }
    }

    // ---- Matching Readium's requests to the plan -------------------------------------------

    private fun align(id: TtsEngine.RequestId, text: String) {
        val key = normalizeKey(text)
        val heldIndex = held
        val match =
            UtteranceAligner.match(
                key,
                planned.toList(),
                requestIndex,
                heldIndex,
                recentIndices(),
                tuning.alignWindow,
            )
        held = null
        when (match) {
            is Match.Planned -> {
                val skipped = match.index > requestIndex + 1 && requestIndex + 1 !in heardDone
                if ((heldIndex != null && match.index != heldIndex) || skipped)
                    rewindTo(match.index)
                bind(id, match.index)
            }
            Match.Held -> bind(id, heldIndex!!)
            is Match.Recent -> {
                rewindTo(match.index)
                bind(id, match.index)
            }
            Match.Miss -> miss(id, key, text)
        }
    }

    /** Utterances already heard whose audio is still held, for skip-previous. */
    private fun recentIndices(): List<Long> =
        (requestIndex - RECENT_SCAN..requestIndex).filter { index ->
            index >= 0 && firstPieceOf(index)?.let { queue.hasUtterance(it) } == true
        }

    private fun bind(id: TtsEngine.RequestId, index: Long) {
        val now = env.clock()
        val previous = requestIndex
        requestIndex = index
        updateLimits()
        if (mode == Mode.CHAPTER && hrefOf(index) != chapterHref) mode = Mode.NORMAL
        val heardAhead = index in heardDone
        boundary(now, previous, index, heardAhead)
        when {
            heardAhead -> {
                // Readium's round trip took longer than the sentence: it was heard already.
                readiumLate++
                post {
                    if (!closed && request == id) {
                        listener?.onStart(id)
                        request = null
                        lastDoneAtMs = env.clock()
                        listener?.onDone(id)
                    }
                }
            }
            index in heardStarted -> post { if (!closed && request == id) listener?.onStart(id) }
        }
        prune()
        if (!playing) {
            if (waitSinceMs == null) waitSinceMs = now
            evaluateGate()
        }
        pump()
    }

    private fun boundary(now: Long, previous: Long, index: Long, heardAhead: Boolean) {
        val underruns = output.underruns
        val record =
            BoundaryRecord(
                tMs = now,
                fromSeq = slotFor(previous)?.unit?.seq ?: -1,
                toSeq = slotFor(index)?.unit?.seq ?: -1,
                readiumRoundtripMs = lastDoneAtMs?.let { now - it },
                heardAhead = heardAhead,
                stallMs = stallStartMs?.let { now - it } ?: 0,
                underrunsDelta = underruns - underrunsSeen,
            )
        underrunsSeen = underruns
        metrics.boundary(record)
        env.recorder?.boundary(record)
    }

    /** Plays [index] next from audio already made, or makes it again if it is gone. */
    private fun rewindTo(index: Long) {
        val piece = firstPieceOf(index)
        if (piece == null || !queue.hasUtterance(piece)) {
            reanchor(index)
            return
        }
        flushesSent++
        output.send(SinkCommand.Flush(piece, gen))
        playing = false
        heardStarted.removeAll { it >= index }
        heardDone.removeAll { it >= index }
    }

    /**
     * Readium asked for something the plan does not have (a seek, or the plan lost its place). The
     * sentence is made at once on its own; the plan finds its place again from the next location.
     */
    private fun miss(id: TtsEngine.RequestId, key: String, text: String) {
        alignMiss++
        invalidate()
        walking?.cancel()
        walking = null
        walker = null
        aligned = false
        planEnded = false
        val adHoc =
            PlannedUtterance(
                index = nextIndex++,
                key = key,
                text = text,
                href = "",
                elementOrdinal = AD_HOC,
                endsSentence = endsSentence(text),
                endsElement = true,
            )
        planned.clear()
        planned.addLast(adHoc)
        restartUnits()
        (builder.push(adHoc) + builder.flush()).forEach(::addSlot)
        waitSinceMs = env.clock()
        bind(id, adHoc.index)
    }

    private fun checkLocation(locator: Locator, key: String) {
        val inStep = planned.any {
            it.key == key && it.index in (requestIndex - 1)..(requestIndex + tuning.alignWindow)
        }
        if (inStep) return
        val current = planned.firstOrNull { it.index == requestIndex }
        when {
            current != null && current.elementOrdinal == AD_HOC && current.key == key ->
                rebase(locator, key, keep = current)
            requestIndex < 0 && request == null -> rebase(locator, key, keep = null)
        }
    }

    // ---- Plan -------------------------------------------------------------------------------

    private fun startWalk(from: Locator?) {
        val walk = source.walk(from)
        walker = walk
        walking = scope.launch {
            val first =
                try {
                    walk.next(FIRST_BATCH)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    aligned = false
                    null
                }
            walking = null
            restartUnits()
            if (first != null) appendUtterances(first)
            primed = true
            pendingLocation?.let { (locator, key) ->
                pendingLocation = null
                checkLocation(locator, key)
            }
            pendingSpeak?.let { (id, text) ->
                pendingSpeak = null
                if (request == id) align(id, text)
            }
            pump()
        }
    }

    /** Walks again from [locator] and continues the plan from the utterance whose key matches. */
    private fun rebase(locator: Locator, key: String, keep: PlannedUtterance?) {
        walking?.cancel()
        val walk = source.walk(locator)
        walker = walk
        planEnded = false
        val started = gen
        walking = scope.launch {
            var found: List<PlannedUtterance>? = null
            try {
                var scanned = 0
                while (found == null && scanned < tuning.rebaseScanLimit) {
                    val batch = walk.next(BATCH)
                    if (batch.isEmpty()) break
                    val at = batch.indexOfFirst { it.key == key }
                    if (at >= 0) found = batch.drop(at) else scanned += batch.size
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                found = null
            }
            walking = null
            if (gen != started) return@launch
            val utterances = found
            if (utterances == null) {
                aligned = false
                alignMiss++
                walker = null
                return@launch
            }
            aligned = true
            if (keep != null) {
                // The sentence being made on its own is this one: keep its audio, go on after it.
                planned.clear()
                planned.addLast(utterances.first().copy(index = keep.index))
                appendUtterances(utterances.drop(1), endIfEmpty = false)
            } else {
                invalidate()
                planned.clear()
                restartUnits()
                appendUtterances(utterances)
            }
            pump()
        }
    }

    private fun appendUtterances(utterances: List<PlannedUtterance>, endIfEmpty: Boolean = true) {
        if (utterances.isEmpty()) {
            if (endIfEmpty) endPlan()
            return
        }
        for (utterance in utterances) {
            val numbered = utterance.copy(index = nextIndex++)
            planned.addLast(numbered)
            builder.push(numbered).forEach(::addSlot)
        }
    }

    private fun endPlan() {
        planEnded = true
        builder.flush().forEach(::addSlot)
        offerReady()
    }

    private fun maybeExtendPlan() {
        val walk = walker
        // Main.immediate may finish a walk step before launch returns: test isActive, not null.
        if (!primed || !aligned || planEnded || walking?.isActive == true || walk == null) return
        if (planned.size >= tuning.plannerCapUtterances) return
        val wanted =
            if (mode == Mode.CHAPTER) planned.lastOrNull()?.href == chapterHref
            else aheadSeconds() < tuning.plannerAheadSeconds
        if (!wanted) return
        walking = scope.launch {
            val batch =
                try {
                    walk.next(BATCH)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    null
                }
            walking = null
            if (walker !== walk) return@launch
            if (batch == null) {
                aligned = false
                return@launch
            }
            appendUtterances(batch)
            if (mode == Mode.CHAPTER) pinChapter()
            pump()
        }
    }

    /** Predicted listening seconds of the units from the current one on. */
    private fun aheadSeconds(): Double {
        val speed = mutableSettings.value.speed
        return slots
            .filter { it.unit.lastUtterance >= anchorIndex() }
            .sumOf { it.audioSeconds ?: model.predictAudioSeconds(it.unit.chars, speed) }
    }

    private fun prune() {
        val keepFrom = requestIndex - KEEP_BEHIND
        while (planned.size > 1 && planned.first().index < keepFrom) planned.removeFirst()
        while (
            slots.size > 1 &&
                slots.first().unit.lastUtterance < keepFrom &&
                slots.first().state == SlotState.READY &&
                slots.first().unit.seq < nextOfferSeq
        ) slots.removeFirst().pieces.forEach { pieces.remove(it) }
    }

    // ---- Units and synthesis ----------------------------------------------------------------

    private fun restartUnits() {
        builder.restart(gen, builder.nextSeq)
        nextOfferSeq = builder.nextSeq
    }

    private fun addSlot(unit: SynthesisUnit) {
        val now = mutableSettings.value
        val ids =
            unit.parts.mapIndexed { k, part ->
                val utterance = part.utterance.index
                val id = nextPiece++
                pieces[id] =
                    Piece(
                        utterance,
                        first = lastPieceUtterance != utterance,
                        last = !(k == unit.parts.lastIndex && unit.continues),
                    )
                lastPieceUtterance = utterance
                id
            }
        slots.addLast(
            Slot(
                unit,
                CacheKey.of(modelId, now.speaker, now.speed, tuning.silenceScale, unit.speechText),
                ids,
            )
        )
        updateLimits()
    }

    private fun firstPieceOf(index: Long): Long? = slots.firstNotNullOfOrNull { slot ->
        slot.pieces.firstOrNull { pieces[it]?.utterance == index }
    }

    private fun lastPieceOf(index: Long): Long? =
        slots.reversed().firstNotNullOfOrNull { slot ->
            slot.pieces.lastOrNull { pieces[it]?.utterance == index }
        }

    /** What the writer may write: up to two utterances past Readium's request, 3 s at most. */
    private fun updateLimits() {
        if (requestIndex < 0) return
        val lastKnown = slots.lastOrNull()?.pieces?.lastOrNull() ?: -1
        limits.requestStart = firstPieceOf(requestIndex) ?: -1
        limits.requestEnd = lastPieceOf(requestIndex) ?: -1
        limits.maxUtterance = lastPieceOf(requestIndex + tuning.speculativeUtterances) ?: lastKnown
        limits.maxFramesPastRequestEnd = (tuning.speculativeSeconds * sampleRate).toLong()
    }

    /** Drops everything made for the old settings or place; late results are ignored. */
    private fun invalidate() {
        genRef.incrementAndGet()
        limits.gen = gen
        limits.planEnded = false
        queue.invalidate(gen)
        flushesSent++
        output.send(SinkCommand.Flush(null, gen))
        playing = false
        slots.clear()
        reorder.clear()
        pieces.clear()
        lastPieceUtterance = null
        heardStarted.clear()
        heardDone.clear()
        emptyStreak = 0
        stallTimes.clear()
    }

    /** Remakes the plan from utterance [at] under a new generation. */
    private fun reanchor(at: Long) {
        invalidate()
        restartUnits()
        val from = planned.indexOfFirst { it.index == at }
        if (from >= 0) {
            planned.drop(from).forEach { builder.push(it).forEach(::addSlot) }
            if (planEnded) builder.flush().forEach(::addSlot)
        }
        waitSinceMs = env.clock()
        pump()
    }

    private fun anchorIndex(): Long =
        held ?: requestIndex.takeIf { it >= 0 } ?: planned.firstOrNull()?.index ?: 0

    private fun slotFor(index: Long): Slot? = slots.firstOrNull {
        index in it.unit.firstUtterance..it.unit.lastUtterance
    }

    private fun hrefOf(index: Long) = planned.firstOrNull { it.index == index }?.href

    /**
     * Keeps synthesis going: the unit Readium waits on first (P0), then the following ones while
     * the reserve is under the high water mark, at most [MAX_IN_FLIGHT] at a time so a change of
     * plan wastes little.
     */
    private fun pump() {
        if (closed || !primed) return
        sampleThermal()
        val anchor = anchorIndex()
        val first = slots.indexOfFirst { anchor <= it.unit.lastUtterance }
        if (first >= 0) {
            val p0 = slots[first]
            if (p0.state == SlotState.FAILED) p0.attempts = 0
            if (p0.state in LOADABLE) load(p0, PRIORITY_NOW)
            val heldTooLong =
                held != null && env.clock() - heldAtMs > tuning.holdSynthesisSeconds * 1000
            if (!heldTooLong) {
                val reserve = reserve()
                val chapter = mode == Mode.CHAPTER
                val synthesize = marks.shouldSynthesize(reserve, strained())
                var inFlight = slots.count { it.state == SlotState.LOADING }
                for (i in first until slots.size) {
                    if (inFlight >= MAX_IN_FLIGHT) break
                    val slot = slots[i]
                    val wanted =
                        when (slot.state) {
                            SlotState.PENDING ->
                                synthesize ||
                                    (chapter &&
                                        planned.any {
                                            it.index == slot.unit.firstUtterance &&
                                                it.href == chapterHref
                                        })
                            SlotState.CACHED -> synthesize
                            else -> false
                        }
                    if (wanted) {
                        load(slot, PRIORITY_AHEAD)
                        inFlight++
                    }
                }
            }
        }
        updateLimits()
        maybeExtendPlan()
        updateWakeLock()
    }

    private fun load(slot: Slot, priority: Int) {
        slot.state = SlotState.LOADING
        slot.queuedAtMs = env.clock()
        val started = gen
        val cache = env.cache
        if (cache == null) {
            synthesize(slot, priority, started)
            return
        }
        scope.launch {
            val hit =
                try {
                    withContext(env.io) { cache.read(slot.key) }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    null
                }
            if (gen != started || slot.state != SlotState.LOADING) return@launch
            if (hit != null && hit.sampleRate == sampleRate)
                ready(slot, hit.pcm, cacheHit = true, synthMs = 0, leadMs = 0, trailMs = 0)
            else synthesize(slot, priority, started)
        }
    }

    private fun synthesize(slot: Slot, priority: Int, started: Long) {
        val unit = slot.unit
        val now = mutableSettings.value
        val cache = env.cache
        val variant = env.variant
        runner.submit(priority, unit.seq, started, { genRef.get() == started }) { synth ->
            slot.startedNanos = System.nanoTime()
            var cut = false
            val outcome =
                try {
                    val samples =
                        synth.synthesize(
                            unit.speechText,
                            now.speaker,
                            now.speed.toFloat(),
                            tuning.silenceScale,
                        ) {
                            (genRef.get() == started).also { if (!it) cut = true }
                        }
                    val synthMs = (System.nanoTime() - slot.startedNanos) / 1_000_000
                    val pcm = Pcm16.fromFloat(samples, tuning.fadeSamples)
                    val (lead, trail) = EdgeSilence.measureEdges(pcm)
                    val trimmed =
                        EdgeSilence.trim(
                            pcm,
                            tuning.edgeSilenceThreshold,
                            (tuning.edgeKeepSeconds * sampleRate).toInt(),
                        )
                    // Valid audio for this voice and speed even if no longer wanted now.
                    if (!cut && cache != null && trimmed.pcm.isNotEmpty())
                        narrationScope.launch {
                            cache.write(slot.key, variant, sampleRate, trimmed.pcm)
                        }
                    Outcome.Made(
                        trimmed.pcm,
                        synthMs,
                        (lead * 1000L / sampleRate).toInt(),
                        (trail * 1000L / sampleRate).toInt(),
                    )
                } catch (failure: Throwable) {
                    Outcome.Failed(failure.message ?: "Kokoro failed")
                }
            post { onMade(slot, started, outcome) }
        }
    }

    private fun onMade(slot: Slot, started: Long, outcome: Outcome) {
        if (closed || started != gen) return
        when (outcome) {
            is Outcome.Failed -> {
                slot.attempts++
                if (slot.attempts < 2) slot.state = SlotState.PENDING
                else {
                    slot.state = SlotState.FAILED
                    if (waitsOn(slot)) fail(outcome.message)
                }
                pump()
            }
            is Outcome.Made -> {
                var pcm = outcome.pcm
                if (pcm.isEmpty()) {
                    // Say nothing for this unit rather than stop reading; three in a row is broken.
                    emptyStreak++
                    if (emptyStreak >= EMPTY_LIMIT) {
                        fail("Kokoro produced no audio")
                        return
                    }
                    pcm = ShortArray((EMPTY_SILENCE_SECONDS * sampleRate).toInt())
                } else {
                    emptyStreak = 0
                    model.observe(
                        slot.unit.chars,
                        mutableSettings.value.speed,
                        outcome.synthMs / 1000.0,
                        pcm.size.toDouble() / sampleRate,
                    )
                }
                if (firstUnitSynthMs == null) firstUnitSynthMs = outcome.synthMs
                env.wakeLock?.takeIf { wakeHeld }?.hold(tuning.wakeLockTimeoutMs)
                ready(slot, pcm, false, outcome.synthMs, outcome.leadMs, outcome.trailMs)
            }
        }
    }

    private fun waitsOn(slot: Slot) =
        request != null && requestIndex in slot.unit.firstUtterance..slot.unit.lastUtterance

    private fun fail(message: String) {
        failure = message
        output.send(SinkCommand.Pause)
        playing = false
        setStatus(NarrationStatus.Failed(message))
        val id = request ?: return
        request = null
        listener?.onError(id, KokoroError(message))
    }

    private fun ready(
        slot: Slot,
        pcm: ShortArray,
        cacheHit: Boolean,
        synthMs: Long,
        leadMs: Int,
        trailMs: Int,
    ) {
        val unit = slot.unit
        val speed = mutableSettings.value.speed
        val gapFrames = EdgeSilence.gapFrames(unit.gapAfter, speed, sampleRate, tuning)
        slot.audioSeconds = (pcm.size + gapFrames).toDouble() / sampleRate
        record(slot, cacheHit, synthMs, leadMs, trailMs, gapFrames, pcm.size)
        if (shouldPark(slot)) {
            // Beyond what memory may hold while preparing a chapter: it waits on disk.
            slot.state = SlotState.CACHED
            pump()
            return
        }
        val spans =
            UtteranceSpans.split(unit, pcm, sampleRate).mapIndexed { k, range ->
                Span(slot.pieces[k], unit.parts[k].utterance.key, range.first, range.last + 1)
            }
        reorder[unit.seq] =
            ReadyUnit(
                seq = unit.seq,
                gen = unit.gen,
                firstUtterance = slot.pieces.first(),
                lastUtterance = slot.pieces.last(),
                pcm = pcm,
                gapFrames = gapFrames,
                spans = spans,
                cacheKey = slot.key,
                cacheHit = cacheHit,
            )
        slot.state = SlotState.READY
        offerReady()
        if (!playing) evaluateGate()
        pump()
        publish()
    }

    private fun shouldPark(slot: Slot): Boolean {
        if (mode != Mode.CHAPTER || env.cache == null) return false
        val earlierParked = slots.any {
            it.unit.seq < slot.unit.seq && it.state == SlotState.CACHED
        }
        return earlierParked || reserve() >= tuning.highWaterSeconds
    }

    /** Hands ready units to the queue in plan order. */
    private fun offerReady() {
        while (true) {
            val next = reorder.firstEntry() ?: break
            if (next.key != nextOfferSeq) break
            reorder.pollFirstEntry()
            queue.offer(next.value, gen)
            nextOfferSeq++
        }
        limits.planEnded =
            planEnded && reorder.isEmpty() && slots.all { it.state == SlotState.READY }
    }

    private fun record(
        slot: Slot,
        cacheHit: Boolean,
        synthMs: Long,
        leadMs: Int,
        trailMs: Int,
        gapFrames: Int,
        frames: Int,
    ) {
        val now = env.clock()
        val record =
            UnitRecord(
                tMs = now,
                gen = slot.unit.gen,
                seq = slot.unit.seq,
                utterances = slot.unit.parts.map { it.utterance.index }.distinct().size,
                chars = slot.unit.chars,
                cacheHit = cacheHit,
                queueWaitMs = now - slot.queuedAtMs - synthMs,
                synthMs = synthMs,
                audioMs = frames * 1000L / sampleRate,
                edgeLeadMs = leadMs,
                edgeTrailMs = trailMs,
                gapMs = (gapFrames * 1000L / sampleRate).toInt(),
                reserveMsAfter = (reserve() * 1000).toLong(),
                thermalStatus = thermal.status,
                thermalHeadroom = thermal.headroom,
            )
        metrics.unit(record)
        env.recorder?.unit(record)
        NarrationRecorder.log {
            "unit seq=${record.seq} chars=${record.chars} synthMs=$synthMs " +
                "audioMs=${record.audioMs} cache=$cacheHit reserveMs=${record.reserveMsAfter}"
        }
    }

    // ---- Start gate -------------------------------------------------------------------------

    private fun reserve(): Double = queue.reserveSeconds(output.headFrame) + cachedAheadSeconds()

    /** Units on disk right after the queued ones: ready without synthesis. */
    private fun cachedAheadSeconds(): Double =
        slots
            .dropWhile { it.state == SlotState.READY }
            .takeWhile { it.state == SlotState.CACHED }
            .sumOf { it.audioSeconds ?: 0.0 }

    private fun isThermal() = thermal.status >= PowerManager.THERMAL_STATUS_MODERATE

    private fun strained(): Boolean = isThermal() || effectiveRatio() > tuning.strainedRatio

    private fun pendingChars(): List<Int> =
        slots
            .filter { it.state != SlotState.READY && it.state != SlotState.CACHED }
            .take(tuning.horizonUnits)
            .map { it.unit.chars }

    private fun effectiveRatio(): Double =
        model.effectiveRatio(
            pendingChars().ifEmpty { listOf(TYPICAL_UNIT_CHARS) },
            mutableSettings.value.speed,
            isThermal(),
        )

    /** Decides whether to start (or restart after a stall) now, wait, or say it is too slow. */
    private fun evaluateGate() {
        if (closed || playing || held != null || slowShown || !primed) return
        if (request == null && requestIndex < 0) return
        val now = env.clock()
        val snapshot = queue.snapshot(output.headFrame)
        val reserve = snapshot.reserveSeconds + cachedAheadSeconds()
        // The last few milliseconds left in the output after a stall are not a reserve.
        val unitReady =
            snapshot.readyUnits > 0 ||
                snapshot.writtenUnheardSeconds >= RESUME_BUFFERED_SECONDS ||
                limits.planEnded
        val decision =
            if (mode == Mode.WITH_BREAKS) breaksDecision(reserve, unitReady)
            else
                gate.evaluate(
                    GateInput(
                        reserveSeconds = reserve,
                        unitReady = unitReady,
                        pending = pendingUnits(),
                        speed = mutableSettings.value.speed,
                        thermal = isThermal(),
                        budgetRemainingSeconds =
                            if (mode == Mode.CHAPTER) null
                            else tuning.startBudgetSeconds - (now - (waitSinceMs ?: now)) / 1000.0,
                        chapterHorizon = mode == Mode.CHAPTER,
                    ),
                    env.prior.n,
                )
        when (decision) {
            GateDecision.Start -> startPlaying(now)
            is GateDecision.Wait -> {
                if (gatePredictedWaitMs == null && firstAudioAtMs == null)
                    gatePredictedWaitMs =
                        (now - (waitSinceMs ?: now)) + (decision.etaSeconds * 1000).toLong()
                showWaiting(now, reserve, decision.etaSeconds)
            }
            is GateDecision.TooSlow -> tooSlow(decision.effectiveRatio)
        }
    }

    private val recheck = Runnable { guard { if (!closed && !playing) evaluateGate() } }

    private fun pendingUnits(): List<PendingUnit> {
        val anchor = anchorIndex()
        return slots
            .asSequence()
            .filter { it.unit.lastUtterance >= anchor }
            .filter { it.state != SlotState.READY && it.state != SlotState.CACHED }
            .filter { mode != Mode.CHAPTER || hrefOf(it.unit.firstUtterance) == chapterHref }
            .take(tuning.horizonUnits)
            .map { slot ->
                val startedAt = slot.startedNanos
                PendingUnit(
                    slot.unit.seq,
                    slot.unit.chars,
                    if (slot.state == SlotState.LOADING && startedAt > 0)
                        (System.nanoTime() - startedAt) / 1e9
                    else null,
                )
            }
            .toList()
    }

    private fun breaksDecision(reserve: Double, unitReady: Boolean): GateDecision {
        if (reserve >= BREAK_RESERVE_SECONDS || (limits.planEnded && unitReady))
            return GateDecision.Start
        return GateDecision.Wait((BREAK_RESERVE_SECONDS - reserve) * effectiveRatio())
    }

    private fun startPlaying(now: Long) {
        playing = true
        output.send(SinkCommand.Play)
        if (gateActualWaitMs == null && firstAudioAtMs == null)
            gateActualWaitMs = waitSinceMs?.let { now - it }
        stallStartMs?.let { metrics.stall(it, now - it) }
        stallStartMs = null
        waitSinceMs = null
        publish()
        updateWakeLock()
    }

    private fun showWaiting(now: Long, reserve: Double, etaSeconds: Double) {
        val eta = ceil(etaSeconds).toInt().takeIf { etaSeconds > 0 }
        val stalled = stallStartMs
        // After a stall, a short wait shows nothing: the voice is only between sentences.
        if (stalled != null && now - stalled < tuning.preparingDebounceMs) {
            main.postDelayed(recheck, tuning.preparingDebounceMs - (now - stalled) + 1)
            return
        }
        if (mode == Mode.CHAPTER) {
            val chapter = slots.filter { hrefOf(it.unit.firstUtterance) == chapterHref }
            val made = chapter.count { it.state == SlotState.READY || it.state == SlotState.CACHED }
            setStatus(
                NarrationStatus.PreparingChapter(
                    if (chapter.isEmpty()) 0f else made.toFloat() / chapter.size,
                    eta ?: 0,
                )
            )
        } else setStatus(NarrationStatus.Preparing(reserve, eta))
        updateWakeLock()
    }

    private fun tooSlow(ratio: Double) {
        val r = ratio.coerceAtLeast(0.01)
        val speed = mutableSettings.value.speed
        val chapter = hrefOf(anchorIndex())
        val prepareSeconds =
            slots
                .filter { hrefOf(it.unit.firstUtterance) == chapter && it.state != SlotState.READY }
                .sumOf { model.predictSynthSeconds(it.unit.chars, speed, isThermal()) }
        val status =
            NarrationStatus.TooSlow(
                pace = 1 / r,
                prepareChapterSeconds = prepareSeconds.roundToInt(),
                breakEverySeconds =
                    if (r > 1) (BREAK_RESERVE_SECONDS * r / (r - 1)).roundToInt()
                    else NO_BREAKS_SECONDS,
                breakSeconds = (BREAK_RESERVE_SECONDS * r).roundToInt(),
                offerFasterModel = env.fasterModelOffer,
                fasterModelMb = (KokoroModel.Variant.FP32.downloadBytes / 1_000_000).toInt(),
            )
        NarrationRecorder.log { "tooSlow ratio=$r" }
        if (!env.screenOn()) {
            // Nobody can answer a question now (in a pocket, in the car): read with breaks.
            mode = Mode.WITH_BREAKS
            evaluateGate()
            return
        }
        slowShown = true
        setStatus(status)
        updateWakeLock()
    }

    // ---- Events from the audio thread -------------------------------------------------------

    private inner class Events : SinkEvents {
        override fun onHeard(flushes: Int, events: List<HeardEvent>) {
            post { heard(flushes, events) }
        }

        override fun onStarved(gen: Long) {
            post { starved(gen) }
        }

        override fun onDrained(gen: Long) {
            post { drained(gen) }
        }

        override fun onFirstAudio() {
            post {
                if (firstAudioAtMs == null) {
                    firstAudioAtMs = env.clock()
                    NarrationRecorder.log {
                        "firstAudio tapToFirstAudioMs=" +
                            env.tappedAtMs?.let { firstAudioAtMs!! - it }
                    }
                }
            }
        }

        override fun onRewindFailed(gen: Long, utteranceIndex: Long) {
            post {
                if (gen == this@KokoroEngine.gen && !closed)
                    reanchor(pieces[utteranceIndex]?.utterance ?: requestIndex)
            }
        }

        override fun onError(message: String) {
            post { if (!closed) fail(message) }
        }
    }

    private fun heard(flushes: Int, events: List<HeardEvent>) {
        if (closed || flushes != flushesSent) return
        for (event in events) {
            if (event.gen != gen) continue
            val piece = pieces[event.utteranceIndex] ?: continue
            val index = piece.utterance
            val id = request
            when (event) {
                is HeardEvent.Started -> {
                    if (!piece.first) continue
                    heardStarted += index
                    if (id != null && index == requestIndex) listener?.onStart(id)
                }
                is HeardEvent.Done -> {
                    if (!piece.last) continue
                    heardDone += index
                    if (id != null && index == requestIndex) {
                        request = null
                        lastDoneAtMs = env.clock()
                        listener?.onDone(id)
                    }
                }
            }
        }
        pump()
        publish()
    }

    private fun starved(gen: Long) {
        if (closed || gen != this.gen || !playing) return
        playing = false
        val now = env.clock()
        stallStartMs = now
        waitSinceMs = now
        stallTimes.addLast(now)
        while (
            stallTimes.isNotEmpty() && now - stallTimes.first() > tuning.stallWindowSeconds * 1000
        ) stallTimes.removeFirst()
        NarrationRecorder.log { "stall reserveMs=${(reserve() * 1000).toLong()}" }
        completeHeardRequest()
        if (mode == Mode.NORMAL && stallTimes.size >= tuning.stallsForTooSlow) {
            tooSlow(effectiveRatio())
            return
        }
        evaluateGate()
        pump()
    }

    /**
     * Everything offered to the queue has been heard and the request's units were among it, so the
     * request was heard to its end even if the queue did not say so (it may wait for the next unit
     * to place an utterance's end).
     */
    private fun completeHeardRequest() {
        val id = request ?: return
        if (requestIndex in heardDone) return
        val units = slots.filter { requestIndex in it.unit.firstUtterance..it.unit.lastUtterance }
        if (units.isEmpty() || units.any { it.unit.seq >= nextOfferSeq }) return
        val snapshot = queue.snapshot(output.headFrame)
        if (snapshot.readyUnits > 0 || snapshot.writtenUnheardSeconds > tuning.starvationSeconds)
            return
        heardDone += requestIndex
        request = null
        lastDoneAtMs = env.clock()
        if (requestIndex !in heardStarted) listener?.onStart(id)
        listener?.onDone(id)
    }

    /** The end of the book was heard: Readium's last request is done. */
    private fun drained(gen: Long) {
        if (closed || gen != this.gen) return
        val id = request ?: return
        heardDone += requestIndex
        request = null
        listener?.onStart(id)
        listener?.onDone(id)
    }

    // ---- Status, wake lock, ticker ----------------------------------------------------------

    private fun setStatus(status: NarrationStatus) {
        mutableStatus.value = status
    }

    /** Refreshes the reserve and pace the UI shows. */
    private fun publish() {
        if (closed || failure != null) return
        val reserve = reserve()
        mutableReadyAhead.value = reserve.toInt()
        val pace = if (model.observations > 0) 1 / effectiveRatio() else null
        mutablePace.value = pace
        when {
            playing -> setStatus(NarrationStatus.Playing(reserve, pace))
            mutableStatus.value is NarrationStatus.Paused ->
                setStatus(NarrationStatus.Paused(reserve))
        }
    }

    private suspend fun tick() {
        while (scope.isActive) {
            val waiting = !playing && held == null && !slowShown && waitSinceMs != null
            delay(if (waiting) WAIT_TICK_MS else PLAY_TICK_MS)
            if (closed) return
            if (!playing && held == null && waitSinceMs != null) evaluateGate()
            val now = env.clock()
            metrics.reserve(now, reserve(), env.screenOn())
            pump()
            publish()
        }
    }

    private fun sampleThermal() {
        val now = env.clock()
        if (now - thermalAtMs < tuning.thermalSampleMs && thermalAtMs != Long.MIN_VALUE) return
        thermalAtMs = now
        thermal = env.thermal()
    }

    private fun updateWakeLock() {
        val busy = slots.any { it.state == SlotState.LOADING }
        val preparing =
            when (mutableStatus.value) {
                NarrationStatus.Loading,
                is NarrationStatus.Preparing,
                is NarrationStatus.PreparingChapter -> !playing && held == null
                else -> false
            }
        val want = !closed && (preparing || (busy && held == null))
        if (want && !wakeHeld) {
            wakeHeld = true
            env.wakeLock?.hold(tuning.wakeLockTimeoutMs)
        } else if (!want && wakeHeld) releaseWakeLock()
    }

    private fun releaseWakeLock() {
        if (!wakeHeld) return
        wakeHeld = false
        env.wakeLock?.release()
    }

    private fun pinChapter() {
        val cache = env.cache ?: return
        val keys = slots.filter { hrefOf(it.unit.firstUtterance) == chapterHref }.map { it.key }
        narrationScope.launch { cache.pin(keys) }
    }

    private companion object {
        const val PRIORITY_NOW = 0
        const val PRIORITY_AHEAD = 1
        const val MAX_IN_FLIGHT = 2
        const val FIRST_BATCH = 24
        const val BATCH = 32
        const val KEEP_BEHIND = 20
        const val RECENT_SCAN = 8
        const val AD_HOC = -1L
        const val EMPTY_LIMIT = 3
        const val EMPTY_SILENCE_SECONDS = 0.2
        const val WAIT_TICK_MS = 250L
        const val PLAY_TICK_MS = 1000L
        const val TYPICAL_UNIT_CHARS = 80
        /** Listening seconds gathered before each stretch when reading with breaks. */
        const val BREAK_RESERVE_SECONDS = 20.0
        const val NO_BREAKS_SECONDS = 3600
        /** Audio still in the output that counts as ready on resume (it holds up to 0.5 s). */
        const val RESUME_BUFFERED_SECONDS = 0.25
        val LOADABLE = setOf(SlotState.PENDING, SlotState.CACHED, SlotState.FAILED)
    }
}

/**
 * Disk work that must finish even after its engine closed (cache writes of finished audio). A disk
 * error loses that audio, never the app.
 */
private val narrationScope =
    CoroutineScope(
        SupervisorJob() +
            narrationIo +
            CoroutineExceptionHandler { _, failure ->
                NarrationRecorder.log { "disk error ${failure.javaClass.name}" }
            }
    )

/** Creates [KokoroEngine]s on the shared [KokoroRuntime]; [engine] is the last one made. */
internal class KokoroEngineProvider(
    private val context: Context,
    private val startAt: Locator?,
    private val tappedAtMs: Long,
    private val variant: KokoroModel.Variant,
    private val recorder: NarrationRecorder?,
) :
    TtsEngineProvider<
        KokoroSettings,
        KokoroPreferences,
        KokoroPreferencesEditor,
        KokoroError,
        KokoroVoice,
    > {
    var engine: KokoroEngine? = null
        private set

    override suspend fun createEngine(
        publication: Publication,
        initialPreferences: KokoroPreferences,
    ): Try<KokoroEngine, Error> =
        try {
            val app = context.applicationContext
            val config = KokoroRuntime.configFor(app, variant)
            // Parse the first sentences while the model loads.
            val plan =
                PrefetchingPlanSource(UtterancePlanner(publication), startAt, prefetchScope, 24)
            val handle = KokoroRuntime.acquire(app, config)
            try {
                createWith(app, config, handle, plan, initialPreferences)
            } catch (failure: Throwable) {
                // The engine never started: give the model back.
                handle.release()
                throw failure
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            // UnsatisfiedLinkError included: a missing native library must not crash the reader.
            Try.failure(KokoroError(failure.message ?: "Kokoro could not start"))
        }

    private fun createWith(
        app: Context,
        config: KokoroRuntime.Config,
        handle: KokoroRuntime.Handle,
        plan: PlanSource,
        initialPreferences: KokoroPreferences,
    ): Try<KokoroEngine, Error> {
        val tuning = KokoroRuntime.tuning
        val power = app.getSystemService(PowerManager::class.java)
        val env =
            NarrationEnv(
                tuning = tuning,
                cache = NarrationCache.get(app),
                wakeLock = AndroidWakeLock(power),
                thermal = {
                    ThermalReading(
                        power.currentThermalStatus,
                        power.getThermalHeadroom(10).takeIf { !it.isNaN() },
                    )
                },
                screenOn = { power.isInteractive },
                recorder = recorder,
                variant = variant,
                threads = config.threads,
                prior = NarrationPriors.load(app, variant, config.threads),
                savePrior = { NarrationPriors.save(app, variant, config.threads, it) },
                tappedAtMs = tappedAtMs,
                modelLoadMs = handle.loadMs,
                warmup = handle.warmup,
                fasterModelOffer =
                    variant == KokoroModel.Variant.INT8 &&
                        !KokoroModel.ready(app, KokoroModel.Variant.FP32),
            )
        val created =
            KokoroEngine(
                handle,
                { queue, limits, events ->
                    AudioTrackSink(queue, handle.sampleRate, tuning, limits, events)
                },
                plan,
                startAt,
                initialPreferences,
                env,
            )
        return Try.success(created.also { engine = it })
    }

    override fun createPreferencesEditor(
        publication: Publication,
        initialPreferences: KokoroPreferences,
    ) = KokoroPreferencesEditor(initialPreferences)

    override fun createEmptyPreferences() = KokoroPreferences()

    override fun getPlaybackParameters(settings: KokoroSettings) =
        PlaybackParameters(settings.speed.toFloat())

    override fun updatePlaybackParameters(
        previous: KokoroPreferences,
        playbackParameters: PlaybackParameters,
    ) = previous.copy(speed = playbackParameters.speed.toDouble())

    // Readium's provider contract requires building the exception; Media3 marks that unstable.
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun mapEngineError(error: KokoroError) =
        PlaybackException(error.message, null, PlaybackException.ERROR_CODE_UNSPECIFIED)

    private companion object {
        val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/** A partial wake lock, "librillo:narration", renewed with a timeout so it can never leak. */
private class AndroidWakeLock(power: PowerManager) : NarrationWakeLock {
    private val lock =
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "librillo:narration").apply {
            setReferenceCounted(false)
        }

    override fun hold(timeoutMs: Long) = lock.acquire(timeoutMs)

    override fun release() {
        if (lock.isHeld) lock.release()
    }
}
