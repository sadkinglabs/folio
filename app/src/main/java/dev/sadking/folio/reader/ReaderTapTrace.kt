package dev.sadking.folio.reader

import android.os.SystemClock

/**
 * The last few steps touches took through the reader: the touch on the window, the page's own touch
 * end and tap as Readium reports them, a tapped highlight, and its sheet. It answers "where did
 * this tap stop?" when an instrumented test's tap does nothing. Kept in memory only, never logged,
 * and it holds no text from the book: only steps, screen points and times.
 *
 * Steps come from the main thread and from the page's script thread, so every call is synchronized.
 */
internal class TapTrace(
    private val capacity: Int = 40,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    private val steps = ArrayDeque<String>(capacity)

    /** Adds [step], stamped with the uptime in milliseconds; the oldest goes when it is full. */
    @Synchronized
    fun add(step: String) {
        if (steps.size == capacity) steps.removeFirst()
        steps.addLast("${clock()} $step")
    }

    /** The steps kept, oldest first. */
    @Synchronized fun steps(): List<String> = steps.toList()
}
