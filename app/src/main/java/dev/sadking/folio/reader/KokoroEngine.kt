@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.sadking.folio.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.r2.navigator.preferences.PreferencesEditor
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.TextContentTokenizer
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.tokenizer.DefaultTextContentTokenizer
import org.readium.r2.shared.util.tokenizer.TextUnit
import org.readium.r2.shared.util.tokenizer.Tokenizer

/**
 * Splits text into sentences. Readium's player and Kokoro's look-ahead both use it, so the
 * sentences generated ahead are exactly the ones the player then asks for.
 */
internal val sentenceTokenizer: (Language?) -> Tokenizer<String, IntRange> = {
    DefaultTextContentTokenizer(TextUnit.Sentence, it)
}

/** Text compared without regard to spacing, so a sentence found ahead matches the one asked for. */
internal fun normalize(text: String) = text.trim().replace(Regex("\\s+"), " ")

/**
 * The [count] utterances that follow [current], walking the book from [locator] exactly as
 * Readium's player does: each paragraph is split into sentences with [TextContentTokenizer], then
 * every text piece of a sentence (italics make several) is one utterance. Empty when [current] is
 * not found nearby: better no look-ahead than generating the wrong sentences.
 */
internal suspend fun sentencesAfter(
    publication: Publication,
    locator: Locator,
    current: String,
    count: Int,
): List<String> {
    val elements = publication.content(locator)?.iterator() ?: return emptyList()
    val splitter =
        TextContentTokenizer(
            language = publication.metadata.language ?: Language(java.util.Locale.getDefault()),
            overrideContentLanguage = false,
            textTokenizerFactory = sentenceTokenizer,
        )
    val target = normalize(current)
    val found = mutableListOf<String>()
    var passed = false
    var scanned = 0
    while (found.size < count && scanned < SCAN_LIMIT && elements.hasNext()) {
        for (piece in splitter.tokenize(elements.next())) {
            for (text in utteranceTexts(piece)) {
                scanned++
                if (!passed) passed = normalize(text) == target
                else {
                    found += text
                    if (found.size == count) return found
                }
            }
        }
    }
    return if (passed) found else emptyList()
}

/** The utterances Readium makes of one sentence element: one per non-blank text piece. */
internal fun utteranceTexts(element: Content.Element): List<String> =
    when (element) {
        is Content.TextElement -> element.segments.map { it.text }.filter { it.isNotBlank() }
        is Content.TextualElement -> listOfNotNull(element.text?.takeIf { it.isNotBlank() })
        else -> emptyList()
    }

/**
 * Splits a long sentence at commas, semicolons, colons and dashes into parts of at least
 * [CLAUSE_MIN] characters, so the first part can play while the rest is generated. Short sentences
 * stay whole: each part is spoken with its own intonation.
 */
internal fun clauses(text: String): List<String> {
    if (text.length <= CLAUSE_SPLIT_FROM) return listOf(text)
    val parts = mutableListOf<String>()
    var start = 0
    for (match in CLAUSE_BREAK.findAll(text)) {
        val cut = match.range.last + 1
        if (cut - start >= CLAUSE_MIN && text.length - cut >= CLAUSE_MIN) {
            parts += text.substring(start, cut)
            start = cut
        }
    }
    parts += text.substring(start)
    return parts
}

private val CLAUSE_BREAK = Regex("[,;:\u2014\u2013]\\s+")
private const val CLAUSE_SPLIT_FROM = 120
private const val CLAUSE_MIN = 50
private const val FADE_SAMPLES = 120

/**
 * Makes generated audio safe to play: values the model failed to produce (NaN) become silence,
 * levels stay within range, and each part fades in and out over 5 ms so its edges do not click.
 */
internal fun cleanAudio(samples: FloatArray): FloatArray {
    val out = FloatArray(samples.size)
    for (i in samples.indices) {
        val value = samples[i]
        out[i] = if (value.isNaN() || value.isInfinite()) 0f else value.coerceIn(-1f, 1f)
    }
    val fade = minOf(FADE_SAMPLES, out.size / 2)
    for (i in 0 until fade) {
        val gain = i.toFloat() / fade
        out[i] *= gain
        out[out.size - 1 - i] *= gain
    }
    return out
}

/** How far to look for the sentence being read before giving up on look-ahead. */
private const val SCAN_LIMIT = 400

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

/**
 * Speaks with Kokoro through sherpa-onnx. Readium asks for one sentence at a time; to avoid a pause
 * while each is generated, [prefetchFrom] generates the next few ahead, and playback streams each
 * sentence into one [AudioTrack] so they follow without a gap.
 */
internal class KokoroEngine(
    private val tts: OfflineTts,
    private val publication: Publication,
    initial: KokoroPreferences,
) : TtsEngine<KokoroSettings, KokoroPreferences, KokoroError, KokoroVoice> {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val synthesisThread = Executors.newSingleThreadExecutor()
    private val outputThread = Executors.newSingleThreadExecutor()
    private val synthesis = synthesisThread.asCoroutineDispatcher()
    private val output = outputThread.asCoroutineDispatcher()
    private val sampleRate = tts.sampleRate()
    private val track =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(sampleRate * 4 / 2)
            .build()

    private val mutableSettings = MutableStateFlow(resolve(initial))
    override val settings: StateFlow<KokoroSettings> = mutableSettings
    override val voices: Set<KokoroVoice> = KOKORO_VOICES.toSet()

    private var listener: TtsEngine.Listener<KokoroError>? = null
    /**
     * Sentences generated or being generated, by voice, speed and text; each is a list of parts
     * (see [clauses]) so a long sentence can start playing before it is all made. Main thread only.
     */
    private val audio = LinkedHashMap<String, List<Deferred<FloatArray>>>()
    private var speaking: Job? = null
    private var speakingId: TtsEngine.RequestId? = null
    private var lookahead: Job? = null

    /** Recent (seconds of speech, seconds to make it), for [pace]. Synthesis thread only. */
    private val timings = ArrayDeque<Pair<Double, Double>>()
    private val mutablePace = MutableStateFlow<Double?>(null)

    /**
     * How fast this phone makes speech compared with reading it aloud: above 1 it keeps ahead,
     * below 1 long sentences can pause. Null until something has been generated.
     */
    val pace: StateFlow<Double?> = mutablePace

    private fun resolve(preferences: KokoroPreferences) =
        KokoroSettings(
            preferences.language ?: Language("en-US"),
            preferences.speed ?: 1.0,
            preferences.speaker ?: KOKORO_DEFAULT_SPEAKER,
        )

    override fun submitPreferences(preferences: KokoroPreferences) {
        val before = mutableSettings.value
        mutableSettings.value = resolve(preferences)
        if (mutableSettings.value != before) dropAhead(keep = emptySet())
    }

    override fun setListener(listener: TtsEngine.Listener<KokoroError>?) {
        this.listener = listener
    }

    private fun key(text: String): String {
        val now = mutableSettings.value
        return "${now.speaker}|${now.speed}|${normalize(text)}"
    }

    /** The sentence's parts, generated now or already on their way, in order. */
    private fun audioFor(text: String): List<Deferred<FloatArray>> {
        val key = key(text)
        audio[key]?.let {
            return it
        }
        val now = mutableSettings.value
        val parts =
            clauses(text).map { part ->
                scope.async(synthesis) { generate(part, now.speaker, now.speed.toFloat()) }
            }
        audio[key] = parts
        return parts
    }

    /** Generates one part, cleans it for playback and records how long it took. */
    private fun generate(text: String, speaker: Int, speed: Float): FloatArray {
        val started = System.nanoTime()
        val samples = cleanAudio(tts.generate(text, speaker, speed).samples)
        val took = (System.nanoTime() - started) / 1e9
        timings.addLast(samples.size.toDouble() / sampleRate to took)
        while (timings.size > PACE_WINDOW) timings.removeFirst()
        val made = timings.sumOf { it.second }
        if (made > 0) mutablePace.value = timings.sumOf { it.first } / made
        return samples
    }

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        speaking?.cancel()
        speakingId = requestId
        val key = key(text)
        speaking = scope.launch {
            try {
                val count = audioFor(text).size
                var heard = 0
                for (index in 0 until count) {
                    val samples = awaitPart(text, index)
                    if (samples.isEmpty()) continue
                    if (heard == 0) listener?.onStart(requestId)
                    heard += samples.size
                    val last = index == count - 1
                    withContext(output) { play(samples, untilHeard = last) }
                }
                audio.remove(key)
                // Never report an empty sentence as heard: the player would race through the book.
                if (heard == 0) throw IllegalStateException("Kokoro produced no audio")
                speakingId = null
                listener?.onDone(requestId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                speakingId = null
                listener?.onError(requestId, KokoroError(failure.message ?: "Kokoro failed"))
            }
        }
    }

    /**
     * Waits for part [index] of [text]. A voice or speed change drops parts generated for the old
     * settings, including one being waited for; it is then generated again with the new ones rather
     * than leaving the player waiting for an answer that never comes.
     */
    private suspend fun awaitPart(text: String, index: Int): FloatArray {
        while (true) {
            val pending = audioFor(text)[index]
            try {
                return pending.await()
            } catch (cancel: CancellationException) {
                kotlin.coroutines.coroutineContext.ensureActive()
            }
        }
    }

    /**
     * Streams [samples]. Parts of one sentence are queued back to back; after the last
     * ([untilHeard]) it returns only once they have been heard, so the next sentence follows.
     */
    private suspend fun play(samples: FloatArray, untilHeard: Boolean) {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        var offset = 0
        while (offset < samples.size) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val count = minOf(CHUNK, samples.size - offset)
            val written = track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
            if (written < 0) throw IllegalStateException("Audio output failed ($written)")
            offset += written
            queued += written
        }
        if (!untilHeard) return
        while (track.playbackHeadPosition.toLong() < queued) {
            kotlin.coroutines.coroutineContext.ensureActive()
            delay(15)
        }
    }

    /** Frames written since the last flush; the playback head counts from there too. */
    @Volatile private var queued = 0L

    override fun stop() {
        val interrupted = speakingId
        speaking?.cancel()
        speaking = null
        speakingId = null
        track.pause()
        track.flush()
        queued = 0
        if (interrupted != null) listener?.onInterrupted(interrupted)
    }

    /**
     * Generates the sentences after [current], the one now being read at [locator], so each is
     * ready before it is needed. Sentences no longer ahead are dropped.
     */
    fun prefetchFrom(locator: Locator, current: String) {
        lookahead?.cancel()
        lookahead = scope.launch {
            try {
                val ahead = sentencesAfter(publication, locator, current, LOOKAHEAD)
                dropAhead(keep = (listOf(current) + ahead).map(::key).toSet())
                ahead.forEach(::audioFor)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Without look-ahead each sentence is generated when asked for.
            }
        }
    }

    private fun dropAhead(keep: Set<String>) {
        val iterator = audio.entries.iterator()
        while (iterator.hasNext()) {
            val (key, parts) = iterator.next()
            if (key !in keep) {
                parts.forEach { it.cancel() }
                iterator.remove()
            }
        }
    }

    /**
     * Frees the voice. Kokoro's native memory is released on its own thread, after any sentence
     * being generated there finishes, and the audio output on its thread after the last write:
     * releasing either while in use crashes the app.
     */
    override fun close() {
        stop()
        scope.cancel()
        outputThread.execute { track.release() }
        synthesisThread.execute { tts.release() }
        outputThread.shutdown()
        synthesisThread.shutdown()
    }

    private companion object {
        /** Sentences generated ahead of the one being read. */
        const val LOOKAHEAD = 5
        const val CHUNK = 4096
        /** Parts the pace is averaged over. */
        const val PACE_WINDOW = 20
    }
}

/** Creates [KokoroEngine]s from the downloaded model; [engine] is the last one made. */
internal class KokoroEngineProvider(private val context: Context) :
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
            val dir = KokoroModel.dir(context)
            val tts = withContext(Dispatchers.IO) { OfflineTts(null, config(dir)) }
            Try.success(KokoroEngine(tts, publication, initialPreferences).also { engine = it })
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            // UnsatisfiedLinkError included: a missing native library must not crash the reader.
            Try.failure(KokoroError(failure.message ?: "Kokoro could not start"))
        }

    /**
     * Every voice uses the American English pronunciation rules. The British rules ("en-gb") make
     * this model produce no audio at all; British voices keep their accent through the voice.
     */
    private fun config(dir: File): OfflineTtsConfig {
        val kokoro =
            OfflineTtsKokoroModelConfig(
                model = File(dir, "model.int8.onnx").path,
                voices = File(dir, "voices.bin").path,
                tokens = File(dir, "tokens.txt").path,
                dataDir = File(dir, "espeak-ng-data").path,
                lexicon = File(dir, "lexicon-us-en.txt").path,
                lang = "en-us",
            )
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(kokoro = kokoro, numThreads = threads)
        )
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
}
