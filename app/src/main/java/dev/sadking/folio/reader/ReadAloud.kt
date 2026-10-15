@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.sadking.folio.reader

import android.app.Application
import android.content.ComponentName
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.readium.navigator.media.common.MediaNavigator
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsSettings
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
)

/** Voices that work offline first, then the better ones, so the top of the list is the pick. */
internal fun sortVoices(voices: List<ListenVoice>): List<ListenVoice> =
    voices.sortedWith(
        compareBy<ListenVoice> { it.needsNetwork }
            .thenByDescending { it.highQuality }
            .thenBy { it.locale.toLanguageTag() }
            .thenBy { it.id }
    )

internal enum class ListenState {
    OFF,
    STARTING,
    PLAYING,
    PAUSED,
}

/** The speeds the speed button steps through, in order. */
internal val LISTEN_SPEEDS = listOf(1.0, 1.25, 1.5, 2.0, 0.75)

internal fun nextListenSpeed(current: Double): Double =
    LISTEN_SPEEDS[(LISTEN_SPEEDS.indexOfFirst { it == current } + 1) % LISTEN_SPEEDS.size]

/** "1.25×": the speed as the button shows it. */
internal fun listenSpeedLabel(speed: Double): String =
    (if (speed % 1.0 == 0.0) speed.toInt().toString() else speed.toString()) + "×"

/**
 * Reads the book aloud with Readium's TTS navigator over Android's text-to-speech, so whichever
 * voice engine the phone has speaks. [utterance] is the sentence being read; the reader highlights
 * it and turns to its page, which saves the place like any page turn. [ListenService] keeps it
 * playing with the screen off and puts its controls on the lock screen.
 */
internal class ReadAloud(
    private val app: Application,
    private val scope: CoroutineScope,
    private val settings: SharedPreferences,
) {
    private var navigator:
        TtsNavigator<
            AndroidTtsSettings,
            AndroidTtsPreferences,
            AndroidTtsEngine.Error,
            AndroidTtsEngine.Voice,
        >? =
        null
    private var watch: Job? = null
    /** Binds [ListenService] while reading, so Media3 can make it foreground when it plays. */
    private var controller: ListenableFuture<MediaController>? = null
    private var sleepJob: Job? = null
    /** With the end-of-chapter timer, the chapter that was playing when it was set. */
    private var sleepChapter: Url? = null

    var state by mutableStateOf(ListenState.OFF)
        private set

    var speed by mutableDoubleStateOf(settings.getFloat("listenSpeed", 1f).toDouble())
        private set

    var utterance by mutableStateOf<Locator?>(null)
        private set

    var sleep by mutableStateOf(SleepTimer.OFF)
        private set

    /** The engine's voices for the book's language; empty until reading starts. */
    var voices by mutableStateOf<List<ListenVoice>>(emptyList())
        private set

    /** The chosen voice's ID, or null for the engine's default. Remembered per language. */
    var voice by mutableStateOf<String?>(null)
        private set

    private var language: Language? = null

    /**
     * Starts reading at [from]. Returns false when no voice engine could be set up; [onFailure] is
     * also told when playback later stops on an engine error.
     */
    suspend fun start(
        bookId: String,
        publication: Publication,
        from: Locator?,
        onFailure: () -> Unit,
    ): Boolean {
        if (state != ListenState.OFF) return true
        state = ListenState.STARTING
        val factory = TtsNavigatorFactory(app, publication)
        val created =
            factory
                ?.createNavigator(
                    object : TtsNavigator.Listener {
                        override fun onStopRequested() = stop()
                    },
                    from,
                    AndroidTtsPreferences(speed = speed),
                )
                ?.getOrNull()
        if (created == null) {
            state = ListenState.OFF
            return false
        }
        navigator = created
        val spoken = created.settings.value.language
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
        if (voice != null) created.submitPreferences(preferences())
        watch = scope.launch {
            launch {
                created.location.collect {
                    utterance = it.utteranceLocator
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
                    else ->
                        state =
                            if (playback.playWhenReady) ListenState.PLAYING else ListenState.PAUSED
                }
            }
        }
        ListenService.current = ListenService.Listening(bookId, created.asMedia3Player(), ::stop)
        controller =
            MediaController.Builder(
                    app,
                    SessionToken(app, ComponentName(app, ListenService::class.java)),
                )
                .buildAsync()
        created.play()
        return true
    }

    fun toggle() {
        val current = navigator ?: return
        if (state == ListenState.PLAYING) current.pause() else current.play()
    }

    fun pause() {
        if (state == ListenState.PLAYING) navigator?.pause()
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
        navigator?.submitPreferences(preferences())
    }

    /** Picks a voice for this language, or the engine's default with null. */
    fun chooseVoice(id: String?) {
        val spoken = language ?: return
        voice = id
        settings
            .edit()
            .apply { if (id == null) remove(voiceKey(spoken)) else putString(voiceKey(spoken), id) }
            .apply()
        navigator?.submitPreferences(preferences())
    }

    private fun voiceKey(spoken: Language) = "listenVoice:" + spoken.removeRegion().code

    private fun preferences(): AndroidTtsPreferences {
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
        utterance = null
        voices = emptyList()
        voice = null
        language = null
        state = ListenState.OFF
    }
}
