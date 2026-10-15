@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.sadking.folio.reader

import android.app.Application
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.readium.navigator.media.common.MediaNavigator
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

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
 * it and turns to its page, which saves the place like any page turn. Plays only while the reader
 * is open: there is no background service yet.
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

    var state by mutableStateOf(ListenState.OFF)
        private set

    var speed by mutableDoubleStateOf(settings.getFloat("listenSpeed", 1f).toDouble())
        private set

    var utterance by mutableStateOf<Locator?>(null)
        private set

    /**
     * Starts reading at [from]. Returns false when no voice engine could be set up; [onFailure] is
     * also told when playback later stops on an engine error.
     */
    suspend fun start(publication: Publication, from: Locator?, onFailure: () -> Unit): Boolean {
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
        watch = scope.launch {
            launch { created.location.collect { utterance = it.utteranceLocator } }
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
        navigator?.submitPreferences(AndroidTtsPreferences(speed = speed))
    }

    fun stop() {
        watch?.cancel()
        watch = null
        navigator?.close()
        navigator = null
        utterance = null
        state = ListenState.OFF
    }
}
