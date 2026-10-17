package dev.sadking.folio.reader

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReadingSpeedTest {
    private val prefs =
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("reading-speed-test", Context.MODE_PRIVATE)
    private var now = 1_000_000L
    private lateinit var speed: ReadingSpeed

    @Before
    fun fresh() {
        prefs.edit().clear().commit()
        speed = ReadingSpeed(prefs) { now }
    }

    private fun samples(kind: ReaderKind) = prefs.getInt(ReadingSpeed.samplesKey(kind), 0)

    /** Turns [pages] pages one position at a time, [seconds] apart. */
    private fun read(kind: ReaderKind, from: Int, pages: Int, seconds: Long) {
        speed.observe(kind, from)
        for (step in 1..pages) {
            now += seconds * 1000
            speed.observe(kind, from + step)
        }
    }

    @Test
    fun defaultsStandInUntilTwentySamples() {
        assertEquals(1.2, speed.positionsPerMinute(ReaderKind.EPUB), 0.0)
        assertEquals(1.0, speed.positionsPerMinute(ReaderKind.PDF), 0.0)
        assertEquals(4.0, speed.positionsPerMinute(ReaderKind.COMIC), 0.0)
        // 19 pages at 30 s each: two a minute, measured but not yet trusted.
        read(ReaderKind.EPUB, 1, 19, 30)
        assertEquals(19, samples(ReaderKind.EPUB))
        assertFalse(speed.measured(ReaderKind.EPUB))
        assertEquals(1.2, speed.positionsPerMinute(ReaderKind.EPUB), 0.0)
        now += 30_000
        speed.observe(ReaderKind.EPUB, 21)
        assertTrue(speed.measured(ReaderKind.EPUB))
        assertEquals(2.0, speed.positionsPerMinute(ReaderKind.EPUB), 1e-6)
    }

    @Test
    fun idleGapsNeverCount() {
        speed.observe(ReaderKind.EPUB, 10)
        // Away for ten minutes, then a page turn: no sample, and a new run starts there.
        now += 10 * 60_000
        speed.observe(ReaderKind.EPUB, 11)
        assertEquals(0, samples(ReaderKind.EPUB))
        // A flick through pages faster than three seconds is not reading either.
        now += 1_000
        speed.observe(ReaderKind.EPUB, 12)
        assertEquals(0, samples(ReaderKind.EPUB))
        now += 40_000
        speed.observe(ReaderKind.EPUB, 13)
        assertEquals(1, samples(ReaderKind.EPUB))
    }

    @Test
    fun jumpsAndBackwardMovesStartANewRunWithoutASample() {
        speed.observe(ReaderKind.EPUB, 10)
        now += 30_000
        speed.observe(ReaderKind.EPUB, 40) // the slider
        now += 30_000
        speed.observe(ReaderKind.EPUB, 39) // back a page
        assertEquals(0, samples(ReaderKind.EPUB))
        // A jump the reader announces (contents, search, links) breaks the run too.
        now += 30_000
        speed.interrupt()
        speed.observe(ReaderKind.EPUB, 40)
        assertEquals(0, samples(ReaderKind.EPUB))
        now += 30_000
        speed.observe(ReaderKind.EPUB, 42)
        assertEquals(1, samples(ReaderKind.EPUB))
        // Two positions in 30 s: four a minute.
        assertEquals(4f, prefs.getFloat(ReadingSpeed.speedKey(ReaderKind.EPUB), 0f), 1e-4f)
    }

    @Test
    fun theSamePageSettlingAgainKeepsTheClockRunning() {
        speed.observe(ReaderKind.EPUB, 5)
        now += 20_000
        speed.observe(ReaderKind.EPUB, 5) // a small phone page inside one position
        now += 40_000
        speed.observe(ReaderKind.EPUB, 6)
        // One position in the whole minute, not in the last 40 seconds.
        assertEquals(1f, prefs.getFloat(ReadingSpeed.speedKey(ReaderKind.EPUB), 0f), 1e-4f)
    }

    @Test
    fun theMeanIsExponentiallyWeightedAfterWarmingUp() {
        // Ten samples at two a minute: a plain mean of 2.
        read(ReaderKind.PDF, 1, 10, 30)
        assertEquals(2f, prefs.getFloat(ReadingSpeed.speedKey(ReaderKind.PDF), 0f), 1e-4f)
        // The eleventh, at six a minute, moves it by a tenth of the difference.
        now += 10_000
        speed.observe(ReaderKind.PDF, 12)
        assertEquals(2.4f, prefs.getFloat(ReadingSpeed.speedKey(ReaderKind.PDF), 0f), 1e-4f)
    }

    @Test
    fun eachFormatHasItsOwnSpeed() {
        read(ReaderKind.COMIC, 1, 3, 10)
        assertEquals(3, samples(ReaderKind.COMIC))
        assertEquals(0, samples(ReaderKind.EPUB))
        // Switching format starts a new run rather than comparing pages of different books.
        now += 10_000
        speed.observe(ReaderKind.EPUB, 5)
        assertEquals(0, samples(ReaderKind.EPUB))
        // Stored under the keys the reader preferences use.
        assertTrue(prefs.contains("speed_comic"))
        assertTrue(prefs.contains("speed_samples_comic"))
    }
}
