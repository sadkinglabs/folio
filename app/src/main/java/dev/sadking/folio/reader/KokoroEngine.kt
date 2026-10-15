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
 * The [count] sentences that follow [current], walking the book from [locator] with the same
 * splitter as Readium's player. Empty when [current] is not found nearby: better no look-ahead than
 * generating the wrong sentences.
 */
internal suspend fun sentencesAfter(
    publication: Publication,
    locator: Locator,
    current: String,
    count: Int,
): List<String> {
    val elements = publication.content(locator)?.iterator() ?: return emptyList()
    val target = normalize(current)
    val found = mutableListOf<String>()
    var passed = false
    var scanned = 0
    while (found.size < count && scanned < SCAN_LIMIT && elements.hasNext()) {
        val element = elements.next() as? Content.TextElement ?: continue
        for (segment in element.segments) {
            val text = segment.text
            val language = segment.language ?: element.language
            for (range in sentenceTokenizer(language).tokenize(text)) {
                val sentence = text.substring(range)
                if (sentence.isBlank()) continue
                scanned++
                if (!passed) passed = normalize(sentence) == target
                else {
                    found += sentence
                    if (found.size == count) return found
                }
            }
        }
    }
    return if (passed) found else emptyList()
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
    /** Sentences generated or being generated, by voice, speed and text. Main thread only. */
    private val audio = LinkedHashMap<String, Deferred<FloatArray>>()
    private var speaking: Job? = null
    private var speakingId: TtsEngine.RequestId? = null
    private var lookahead: Job? = null

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

    /** The sentence's audio, generated now or already on its way. */
    private fun audioFor(text: String): Deferred<FloatArray> {
        val key = key(text)
        audio[key]?.let {
            return it
        }
        val now = mutableSettings.value
        val job =
            scope.async(synthesis) {
                tts.generate(text, now.speaker, now.speed.toFloat()).samples
            }
        audio[key] = job
        return job
    }

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        speaking?.cancel()
        speakingId = requestId
        val key = key(text)
        speaking = scope.launch {
            try {
                val samples = awaitAudio(text)
                audio.remove(key)
                // Never report an empty sentence as heard: the player would race through the book.
                if (samples.isEmpty()) throw IllegalStateException("Kokoro produced no audio")
                listener?.onStart(requestId)
                withContext(output) { play(samples) }
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
     * Waits for [text]'s audio. A voice or speed change drops sentences generated for the old
     * settings, including one being waited for; it is then generated again with the new ones rather
     * than leaving the player waiting for an answer that never comes.
     */
    private suspend fun awaitAudio(text: String): FloatArray {
        while (true) {
            val pending = audioFor(text)
            try {
                return pending.await()
            } catch (cancel: CancellationException) {
                kotlin.coroutines.coroutineContext.ensureActive()
            }
        }
    }

    /** Streams [samples] and returns once they have been heard, so the next sentence follows. */
    private suspend fun play(samples: FloatArray) {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        val start = track.playbackHeadPosition.toLong()
        var offset = 0
        while (offset < samples.size) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val count = minOf(CHUNK, samples.size - offset)
            val written = track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
            if (written < 0) throw IllegalStateException("Audio output failed ($written)")
            offset += written
        }
        val end = start + samples.size
        while (track.playbackHeadPosition.toLong() < end) {
            kotlin.coroutines.coroutineContext.ensureActive()
            delay(15)
        }
    }

    override fun stop() {
        val interrupted = speakingId
        speaking?.cancel()
        speaking = null
        speakingId = null
        track.pause()
        track.flush()
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
            val (key, job) = iterator.next()
            if (key !in keep) {
                job.cancel()
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
        const val LOOKAHEAD = 3
        const val CHUNK = 4096
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
