@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.sadking.folio.reader

import android.app.Application
import android.content.ComponentName
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.sadking.folio.reader.narration.NarrationStatus
import dev.sadking.folio.reader.narration.SlowChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.readium.navigator.media.common.MediaNavigator
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Url

/** The sleep timer's choices, in menu order. */
internal enum class SleepTimer(val minutes: Int) {
    OFF(0),
    MINUTES_15(15),
    MINUTES_30(30),
    MINUTES_60(60),
    CHAPTER(0),
}

/** One voice the engine offers for the book's language. */
internal data class ListenVoice(
    val id: String,
    val locale: java.util.Locale,
    val highQuality: Boolean,
    val needsNetwork: Boolean,
    /** A natural (Kokoro) voice's name, such as Heart; phone voices are named by language. */
    val name: String? = null,
)

/** The ID a natural voice has in [ListenVoice.id]: `kokoro:3`. */
internal fun naturalVoiceId(speaker: Int) = "kokoro:$speaker"

internal fun naturalSpeaker(id: String?): Int? =
    id?.removePrefix("kokoro:")?.takeIf { id.startsWith("kokoro:") }?.toIntOrNull()

/** The natural voices as the sheet lists them. */
internal val NATURAL_VOICES = KOKORO_VOICES.map {
    ListenVoice(
        naturalVoiceId(it.speaker),
        it.language.locale,
        highQuality = true,
        needsNetwork = false,
        name = it.name,
    )
}

/** Voices that work offline first, then the better ones, so the top of the list is the pick. */
internal fun sortVoices(voices: List<ListenVoice>): List<ListenVoice> =
    voices.sortedWith(
        compareBy<ListenVoice> { it.needsNetwork }
            .thenByDescending { it.highQuality }
            .thenBy { it.locale.toLanguageTag() }
            .thenBy { it.id }
    )

/**
 * Whether a language as books write it is English: `en`, `en-GB`, `en_US`, `EN`, `eng` or even
 * `English`. Kokoro, the natural voice, speaks English only.
 */
internal fun isEnglish(code: String): Boolean =
    code.trim().lowercase().replace('_', '-').substringBefore('-') in setOf("en", "eng", "english")

/** A book with no language given is taken as English; otherwise any English entry counts. */
internal fun englishBook(languages: List<String>): Boolean =
    languages.none { it.isNotBlank() } || languages.any(::isEnglish)

internal enum class ListenState {
    OFF,
    STARTING,
    /** Asked to play, while the natural voice prepares narration. */
    PREPARING,
    PLAYING,
    PAUSED,
}

/** What the listen bar shows for Readium's play intent and the natural voice's status. */
internal fun listenState(playWhenReady: Boolean, narration: NarrationStatus): ListenState =
    when {
        !playWhenReady -> ListenState.PAUSED
        narration is NarrationStatus.TooSlow -> ListenState.PAUSED
        narration is NarrationStatus.Loading ||
            narration is NarrationStatus.Preparing ||
            narration is NarrationStatus.PreparingChapter -> ListenState.PREPARING
        else -> ListenState.PLAYING
    }

/** The engine actions behind the "too slow" choices. */
internal interface SlowActions {
    fun prepareChapter()

    fun playWithBreaks()

    fun usePhoneVoice()

    fun getFasterModel()
}

/** Routes one "too slow" choice; the faster model also starts reading with breaks meanwhile. */
internal fun routeSlow(choice: SlowChoice, actions: SlowActions) {
    when (choice) {
        SlowChoice.PREPARE_CHAPTER -> actions.prepareChapter()
        SlowChoice.WITH_BREAKS -> actions.playWithBreaks()
        SlowChoice.PHONE_VOICE -> actions.usePhoneVoice()
        SlowChoice.FASTER_MODEL -> {
            actions.getFasterModel()
            actions.playWithBreaks()
        }
    }
}

/** The speeds the speed button steps through, in order. */
internal val LISTEN_SPEEDS = listOf(1.0, 1.25, 1.5, 2.0, 0.75)

internal fun nextListenSpeed(current: Double): Double =
    LISTEN_SPEEDS[(LISTEN_SPEEDS.indexOfFirst { it == current } + 1) % LISTEN_SPEEDS.size]

/** "1.25×": the speed as the button shows it. */
internal fun listenSpeedLabel(speed: Double): String =
    (if (speed % 1.0 == 0.0) speed.toInt().toString() else speed.toString()) + "×"

/**
 * Reads the book aloud through Readium's TTS navigator. The voice is Kokoro, the natural voice,
 * when it is downloaded and the book is in English; otherwise whichever engine the phone uses.
 * [utterance] is the sentence being read; the reader highlights it and turns to its page, which
 * saves the place like any page turn. [ListenService] keeps it playing with the screen off and puts
 * its controls on the lock screen.
 */
internal class ReadAloud(
    private val app: Application,
    private val scope: CoroutineScope,
    private val settings: SharedPreferences,
) {
    private var navigator: TtsNavigator<*, *, *, *>? = null
    /** Sends the current speed and voice to whichever engine is speaking. */
    private var applyPreferences: () -> Unit = {}
    private var kokoro: KokoroEngineProvider? = null
    private var watch: Job? = null
    /** Binds [ListenService] while reading, so Media3 can make it foreground when it plays. */
    private var controller: ListenableFuture<MediaController>? = null
    private var sleepJob: Job? = null
    /** With the end-of-chapter timer, the chapter that was playing when it was set. */
    private var sleepChapter: Url? = null
    private var reading: Reading? = null

    private class Reading(
        val bookId: String,
        val publication: Publication,
        /** Where the reader's page is now; read-aloud starts there when it has no sentence. */
        val page: suspend () -> Locator?,
        val onFailure: () -> Unit,
    )

    /** A voice change that came while starting, applied once started. */
    private var restartWhenStarted = false

    var state by mutableStateOf(ListenState.OFF)
        private set

    var speed by mutableDoubleStateOf(settings.getFloat("listenSpeed", 1f).toDouble())
        private set

    var utterance by mutableStateOf<Locator?>(null)
        private set

    var sleep by mutableStateOf(SleepTimer.OFF)
        private set

    /** True while Kokoro is speaking. */
    var natural by mutableStateOf(false)
        private set

    /** How fast Kokoro makes speech on this phone compared with reading it; null until known. */
    var naturalPace by mutableStateOf<Double?>(null)
        private set

    /** Listening seconds ready ahead of the voice; null when not natural. */
    var readyAheadSeconds by mutableStateOf<Int?>(null)
        private set

    /**
     * The natural voice's status; Idle when not natural. A "too slow" question stays until
     * answered.
     */
    var narration by mutableStateOf<NarrationStatus>(NarrationStatus.Idle)
        private set

    /** The question on screen, kept while the engine pauses for it. */
    private var slow: NarrationStatus.TooSlow? = null

    /** Which Kokoro model plays: the chosen one if installed, else the faster measured one. */
    var naturalVariant by mutableStateOf(chooseVariant())
        private set

    /** Saves each listening session's timings under files/narration/sessions (numbers only). */
    var recordMeasurements by mutableStateOf(settings.getBoolean("narrationRecord", false))
        private set

    /** The last finished recording, for Share; null when none. */
    var lastRecording by mutableStateOf<java.io.File?>(NarrationRecorder.last(app))
        private set

    private var recorder: NarrationRecorder? = null

    /** True when the book can use the natural voice: Kokoro speaks English only. */
    var naturalFits by mutableStateOf(false)
        private set

    /** The phone engine's voices for the book's language; empty while Kokoro speaks. */
    var voices by mutableStateOf<List<ListenVoice>>(emptyList())
        private set

    /**
     * The voice speaking: `kokoro:<speaker>`, a phone voice's ID, or null for the phone default.
     */
    var voice by mutableStateOf<String?>(null)
        private set

    private var language: Language? = null

    private val speaker: Int
        get() = settings.getInt("kokoroSpeaker", KOKORO_DEFAULT_SPEAKER)

    /**
     * Starts reading at [from]. Returns false when no voice engine could be set up; [onFailure] is
     * also told when playback later stops on an engine error.
     */
    suspend fun start(
        bookId: String,
        publication: Publication,
        page: suspend () -> Locator?,
        onFailure: () -> Unit,
        from: Locator? = null,
    ): Boolean {
        if (state != ListenState.OFF) return true
        val tappedAtMs = SystemClock.elapsedRealtime()
        state = ListenState.STARTING
        reading = Reading(bookId, publication, page, onFailure)
        val from = from ?: page()
        naturalFits = englishBook(publication.metadata.languages)
        val wantsNatural =
            naturalFits &&
                KokoroModel.installed(app).isNotEmpty() &&
                settings.getString("listenEngine", "natural") == "natural"
        val started =
            (wantsNatural && startNatural(publication, from, tappedAtMs)) ||
                startPhone(publication, from)
        if (!started) {
            state = ListenState.OFF
            reading = null
            restartWhenStarted = false
            return false
        }
        ListenService.current =
            ListenService.Listening(bookId, navigator!!.asMedia3Player(), ::stop)
        controller =
            MediaController.Builder(
                    app,
                    SessionToken(app, ComponentName(app, ListenService::class.java)),
                )
                .buildAsync()
        navigator?.play()
        if (restartWhenStarted) {
            restartWhenStarted = false
            restartHere()
        }
        return true
    }

    private suspend fun startNatural(
        publication: Publication,
        from: Locator?,
        tappedAtMs: Long,
    ): Boolean {
        val variant = chooseVariant().also { naturalVariant = it }
        // The model starts loading now, while Readium opens the book's first chapter.
        KokoroRuntime.preload(app, KokoroRuntime.configFor(app, variant))
        narration = NarrationStatus.Loading
        val recording = if (recordMeasurements) NarrationRecorder.start(app) else null
        val provider = KokoroEngineProvider(app, from, tappedAtMs, variant, recording)
        val created =
            TtsNavigatorFactory(app, publication, provider, kokoroSentenceTokenizer)
                ?.createNavigator(listener, from, kokoroPreferences())
                ?.getOrNull()
        if (created == null) {
            narration = NarrationStatus.Idle
            return false
        }
        recorder = recording
        kokoro = provider
        natural = true
        voices = emptyList()
        voice = naturalVoiceId(speaker)
        applyPreferences = { created.submitPreferences(kokoroPreferences()) }
        attach(created)
        return true
    }

    private suspend fun startPhone(publication: Publication, from: Locator?): Boolean {
        val created =
            TtsNavigatorFactory(app, publication)
                ?.createNavigator(listener, from, AndroidTtsPreferences(speed = speed))
                ?.getOrNull() ?: return false
        kokoro = null
        natural = false
        val spoken = created.settings.value.language
        // Readium's own reading of the book's language counts too.
        if (isEnglish(spoken.code)) naturalFits = true
        language = spoken
        voices =
            sortVoices(
                created.voices
                    .filter { it.language.removeRegion() == spoken.removeRegion() }
                    .map {
                        ListenVoice(
                            it.id.value,
                            it.language.locale,
                            it.quality >= AndroidTtsEngine.Voice.Quality.High,
                            it.requiresNetwork,
                        )
                    }
            )
        voice =
            settings.getString(voiceKey(spoken), null)?.takeIf { id -> voices.any { it.id == id } }
        applyPreferences = { created.submitPreferences(androidPreferences()) }
        if (voice != null) applyPreferences()
        attach(created)
        return true
    }

    private val listener =
        object : TtsNavigator.Listener {
            override fun onStopRequested() = stop()
        }

    private fun attach(created: TtsNavigator<*, *, *, *>) {
        navigator = created
        val onFailure = reading?.onFailure ?: {}
        watch = scope.launch {
            kokoro?.engine?.let { engine ->
                launch { engine.pace.collect { naturalPace = it } }
                launch { engine.readyAheadSeconds.collect { readyAheadSeconds = it } }
                launch { engine.settings.collect { followEngineSpeed(it.speed) } }
                launch {
                    engine.status.collect { status ->
                        if (status is NarrationStatus.TooSlow) {
                            slow = status
                            // The question waits with the reader paused.
                            created.pause()
                        }
                        narration = slow ?: status
                        val playing = created.playback.value.playWhenReady
                        if (state != ListenState.OFF) state = listenState(playing, narration)
                    }
                }
            }
            launch {
                created.location.collect {
                    utterance = it.utteranceLocator
                    kokoro?.engine?.onLocation(it.utteranceLocator, it.utterance)
                    val chapter = sleepChapter
                    if (sleep == SleepTimer.CHAPTER && chapter != null && it.href != chapter) {
                        created.pause()
                        clearSleep()
                    }
                }
            }
            created.playback.collect { playback ->
                when (playback.state) {
                    is MediaNavigator.State.Ended -> stop()
                    is MediaNavigator.State.Failure -> {
                        stop()
                        onFailure()
                    }
                    else -> {
                        // Readium does not tell the engine about a pause between sentences.
                        if (!playback.playWhenReady) kokoro?.engine?.pauseNow()
                        state = listenState(playback.playWhenReady, narration)
                    }
                }
            }
        }
    }

    fun toggle() {
        val current = navigator ?: return
        if (state == ListenState.PLAYING || state == ListenState.PREPARING) current.pause()
        else current.play()
    }

    fun pause() {
        if (state == ListenState.PLAYING || state == ListenState.PREPARING) navigator?.pause()
    }

    /** The speed the engine uses now, which a media controller may also change. */
    internal fun followEngineSpeed(engineSpeed: Double) {
        if (engineSpeed == speed) return
        speed = engineSpeed
        settings.edit().putFloat("listenSpeed", engineSpeed.toFloat()).apply()
    }

    /**
     * Acts on the "too slow" question. The phone voice restarts reading with it; the faster model
     * downloads while reading goes on with breaks.
     */
    fun chooseSlow(choice: SlowChoice) {
        settings.edit().putString("narrationSlowChoice", choice.name).apply()
        slow = null
        val engine = kokoro?.engine
        routeSlow(
            choice,
            object : SlowActions {
                override fun prepareChapter() {
                    engine?.prepareChapter()
                    navigator?.play()
                }

                override fun playWithBreaks() {
                    engine?.playWithBreaks()
                    navigator?.play()
                }

                override fun usePhoneVoice() = chooseVoice(null)

                override fun getFasterModel() = KokoroModel.install(app, KokoroModel.Variant.FP32)
            },
        )
        engine?.status?.value?.let { narration = it }
    }

    /** Closes the question and stays paused. */
    fun dismissSlow() {
        slow = null
        navigator?.pause()
        narration = kokoro?.engine?.status?.value ?: NarrationStatus.Idle
    }

    /** Picks the Kokoro model; restarts reading at the sentence being read if it is speaking. */
    fun chooseVariant(variant: KokoroModel.Variant) {
        settings.edit().putString("narrationVariant", variant.name).apply()
        if (variant == naturalVariant) return
        naturalVariant = variant
        if (natural) restartHere()
    }

    private fun chooseVariant(): KokoroModel.Variant =
        KokoroRuntime.chooseVariant(
            app,
            settings.getString("narrationVariant", null)?.let { name ->
                KokoroModel.Variant.entries.firstOrNull { it.name == name }
            },
        )

    // The property's private setter already has the plain JVM name.
    @JvmName("turnRecordMeasurements")
    fun setRecordMeasurements(on: Boolean) {
        recordMeasurements = on
        settings.edit().putBoolean("narrationRecord", on).apply()
    }

    fun previous() {
        navigator?.skipToPreviousUtterance()
    }

    fun next() {
        navigator?.skipToNextUtterance()
    }

    fun cycleSpeed() {
        speed = nextListenSpeed(speed)
        settings.edit().putFloat("listenSpeed", speed.toFloat()).apply()
        applyPreferences()
    }

    /**
     * Picks a voice: a natural one (`kokoro:<speaker>`), a phone voice, or null for the phone's
     * default. Moving between Kokoro and the phone restarts at the sentence being read.
     */
    fun chooseVoice(id: String?) {
        val chosen = naturalSpeaker(id)
        if (chosen != null) {
            val restart = !natural
            settings
                .edit()
                .putString("listenEngine", "natural")
                .putInt("kokoroSpeaker", chosen)
                .apply()
            voice = id
            if (restart) restartHere() else applyPreferences()
            return
        }
        settings.edit().putString("listenEngine", "phone").apply()
        if (natural) {
            restartHere()
            return
        }
        val spoken = language ?: return
        voice = id
        settings
            .edit()
            .apply { if (id == null) remove(voiceKey(spoken)) else putString(voiceKey(spoken), id) }
            .apply()
        applyPreferences()
    }

    /** Stops and starts again at the sentence being read, with the voice now chosen. */
    private fun restartHere() {
        val current = reading ?: return
        if (state == ListenState.STARTING) {
            restartWhenStarted = true
            return
        }
        val at = utterance
        val paused = state == ListenState.PAUSED
        stop()
        scope.launch {
            // No sentence yet: start from the page on screen, never from the top of the book.
            val started =
                start(current.bookId, current.publication, current.page, current.onFailure, at)
            if (started && paused) navigator?.pause()
        }
    }

    private fun voiceKey(spoken: Language) = "listenVoice:" + spoken.removeRegion().code

    private fun kokoroPreferences() = KokoroPreferences(speed = speed, speaker = speaker)

    private fun androidPreferences(): AndroidTtsPreferences {
        val spoken = language
        val chosen = voice
        return AndroidTtsPreferences(
            speed = speed,
            voices =
                if (spoken != null && chosen != null)
                    mapOf(spoken to AndroidTtsEngine.Voice.Id(chosen))
                else null,
        )
    }

    /** Pauses after [timer]'s minutes or at the end of the current chapter; OFF cancels it. */
    fun sleepIn(timer: SleepTimer) {
        clearSleep()
        sleep = timer
        when (timer) {
            SleepTimer.OFF -> Unit
            SleepTimer.CHAPTER -> sleepChapter = navigator?.location?.value?.href
            else -> sleepJob = scope.launch {
                    delay(timer.minutes * 60_000L)
                    pause()
                    sleep = SleepTimer.OFF
                }
        }
    }

    private fun clearSleep() {
        sleepJob?.cancel()
        sleepJob = null
        sleepChapter = null
        sleep = SleepTimer.OFF
    }

    fun stop() {
        clearSleep()
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        ListenService.end()
        watch?.cancel()
        watch = null
        navigator?.close()
        navigator = null
        kokoro = null
        applyPreferences = {}
        utterance = null
        voices = emptyList()
        voice = null
        language = null
        natural = false
        naturalPace = null
        readyAheadSeconds = null
        narration = NarrationStatus.Idle
        slow = null
        recorder?.let { lastRecording = it.dir }
        recorder = null
        state = ListenState.OFF
    }
}
