package dev.sadking.folio.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.reader.narration.NarrationStatus
import dev.sadking.folio.reader.narration.SlowChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReadAloudTest {
    @Test
    fun speedButtonStepsThroughEverySpeedAndWraps() {
        var speed = 1.0
        val seen =
            (1..LISTEN_SPEEDS.size).map {
                speed = nextListenSpeed(speed)
                speed
            }
        assertEquals(listOf(1.25, 1.5, 2.0, 0.75, 1.0), seen)
    }

    @Test
    fun unknownSavedSpeedRestartsAtTheFirst() {
        assertEquals(1.0, nextListenSpeed(3.0), 0.0)
    }

    @Test
    fun labelsDropTrailingZeros() {
        assertEquals("1×", listenSpeedLabel(1.0))
        assertEquals("1.25×", listenSpeedLabel(1.25))
        assertEquals("0.75×", listenSpeedLabel(0.75))
    }

    @Test
    fun offlineVoicesComeFirstThenTheBetterOnes() {
        val uk = java.util.Locale.UK
        val sorted =
            sortVoices(
                listOf(
                    ListenVoice("net-high", uk, highQuality = true, needsNetwork = true),
                    ListenVoice("local-low", uk, highQuality = false, needsNetwork = false),
                    ListenVoice("local-high", uk, highQuality = true, needsNetwork = false),
                )
            )
        assertEquals(listOf("local-high", "local-low", "net-high"), sorted.map { it.id })
    }

    @Test
    fun englishIsRecognisedHoweverTheBookWritesIt() {
        listOf("en", "en-GB", "en_US", "EN", "eng", "English", " en-gb ").forEach {
            assertEquals(it, true, isEnglish(it))
        }
        listOf("fr", "es-ES", "deu", "zh-Hans").forEach { assertEquals(it, false, isEnglish(it)) }
        assertEquals(true, englishBook(emptyList()))
        assertEquals(true, englishBook(listOf("")))
        assertEquals(true, englishBook(listOf("fr", "eng")))
        assertEquals(false, englishBook(listOf("fr")))
    }

    @Test
    fun preparingShowsWhilePlayIsWantedAndNarrationIsNotReady() {
        val preparing =
            listOf(
                NarrationStatus.Loading,
                NarrationStatus.Preparing(0.0, 4),
                NarrationStatus.PreparingChapter(0.5f, 60),
            )
        for (status in preparing) {
            assertEquals(ListenState.PREPARING, listenState(true, status))
            assertEquals(ListenState.PAUSED, listenState(false, status))
        }
        assertEquals(ListenState.PLAYING, listenState(true, NarrationStatus.Idle))
        assertEquals(ListenState.PLAYING, listenState(true, NarrationStatus.Playing(30.0, 1.4)))
        // Readium's intent wins over a Paused status the engine has not updated yet.
        assertEquals(ListenState.PLAYING, listenState(true, NarrationStatus.Paused(3.0)))
        val slow = NarrationStatus.TooSlow(0.7, 300, 60, 30, true, 350)
        assertEquals(ListenState.PAUSED, listenState(true, slow))
    }

    @Test
    fun eachTooSlowChoiceReachesItsAction() {
        val calls = mutableListOf<String>()
        val actions =
            object : SlowActions {
                override fun prepareChapter() {
                    calls += "chapter"
                }

                override fun playWithBreaks() {
                    calls += "breaks"
                }

                override fun usePhoneVoice() {
                    calls += "phone"
                }

                override fun getFasterModel() {
                    calls += "faster"
                }
            }
        for (choice in SlowChoice.entries) routeSlow(choice, actions)
        assertEquals(listOf("chapter", "breaks", "phone", "faster", "breaks"), calls)
    }

    @Test
    fun speedFollowsTheEngineAndIsRemembered() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val settings = app.getSharedPreferences("read-aloud-test", 0)
        val readAloud = ReadAloud(app, CoroutineScope(Dispatchers.Main), settings)
        readAloud.followEngineSpeed(1.5)
        assertEquals(1.5, readAloud.speed, 0.0)
        assertEquals(1.5f, settings.getFloat("listenSpeed", 0f))
    }
}
