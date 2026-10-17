package dev.sadking.folio

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The crash report keeps what helps find a bug (exception classes, code messages, stack frames, a
 * native abort's message) and nothing personal: no book text, titles, book paths or links.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CrashReportsTest {
    private val books = "/storage/emulated/0/Books"
    private val library = "/data/user/0/dev.sadking.folio/files/library"
    private val roots = listOf(books, library)

    @Test
    fun pathsLinksAndBookFilesAreCut() {
        val text =
            "open failed: $books/Jane Austen/Pride and Prejudice.epub (No such file); " +
                "content://com.android.providers.downloads/document/42 and " +
                "$library/1b2c/original.epub and /sdcard/Download/My Diary.pdf"
        val out = CrashReports.redact(text, roots)
        assertFalse(out, "Austen" in out)
        assertFalse(out, "Pride" in out)
        assertFalse(out, "providers.downloads" in out)
        assertFalse(out, "1b2c" in out)
        assertFalse(out, "Diary" in out)
        assertTrue(out, out.startsWith("open failed: <path>"))
        assertTrue(out, "content://…" in out)
    }

    @Test
    fun quotedTextIsCutButCodeWordsStay() {
        val out =
            CrashReports.redact(
                "Cannot speak \"It is a truth universally acknowledged, that a single man\" now",
                roots,
            )
        assertEquals("Cannot speak \"…\" now", out)
        assertEquals(
            "Expected \"utf-8\"",
            CrashReports.redact("Expected \"utf-8\"", roots),
        )
    }

    @Test
    fun messagesThatCanQuoteTheirInputKeepOnlyTheClass() {
        val parse = org.json.JSONException("Unterminated string at 40 [It was a dark and stormy]")
        assertEquals("org.json.JSONException", CrashReports.summary(parse, roots))
        val number = NumberFormatException("For input string: \"chapter seven\"")
        assertEquals("java.lang.NumberFormatException", CrashReports.summary(number, roots))
        val state = IllegalStateException("WakeLock under-locked librillo:narration")
        assertEquals(
            "java.lang.IllegalStateException: WakeLock under-locked librillo:narration",
            CrashReports.summary(state, roots),
        )
    }

    @Test
    fun aLongMessageIsLeftOut() {
        val long = IllegalArgumentException("x".repeat(400))
        assertEquals(
            "java.lang.IllegalArgumentException: (message left out)",
            CrashReports.summary(long, roots),
        )
    }

    @Test
    fun aJavaCrashKeepsTheThreadClassesFramesAndCauses() {
        val failure =
            RuntimeException(
                "Narration failed",
                IllegalStateException("play() called on uninitialized AudioTrack"),
            )
        val text =
            CrashReports.formatJavaCrash(
                "Librillo 0.12.0-dev (18)",
                "kokoro-audio",
                failure,
                0,
                roots,
            )
        assertTrue(text, text.startsWith("Java crash at 1970-01-01T00:00:00Z\n"))
        assertTrue(text, "Thread: kokoro-audio" in text)
        assertTrue(text, "java.lang.RuntimeException: Narration failed" in text)
        assertTrue(
            text,
            "Caused by: java.lang.IllegalStateException: play() called on uninitialized AudioTrack" in
                text,
        )
        assertTrue(text, "\tat dev.sadking.folio.CrashReportsTest." in text)
    }

    @Test
    fun aNativeTraceKeepsItsAbortAndBacktraceButNotMemoryOrLogs() {
        val tombstone =
            """
            *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
            pid: 1234, tid: 1300, name: kokoro-synth  >>> dev.sadking.folio <<<
            signal 6 (SIGABRT), code -1 (SI_QUEUE), fault addr --------
            Abort message: 'JNI DETECTED ERROR IN APPLICATION: JNI NewFloatArray called with pending exception java.lang.NoSuchMethodError: no non-static method "Lkotlin/jvm/functions/Function1;.invoke([F)Ljava/lang/Integer;"'
                x0  0000000000000000  x1  0000000000000514
            backtrace:
                  #00 pc 000000000005c4d0  /apex/com.android.runtime/lib64/bionic/libc.so (abort+168)
                  #01 pc 0000000000123456  /data/app/~~abc/dev.sadking.folio-xyz/lib/arm64/libsherpa-onnx-jni.so
            memory near x1:
                0000007ff4f5e0a0 6120736177207449 6b72616420612073  It was a dark a
            open files:
                fd 87: $books/Jane Austen/Emma.epub
            --------- tail end of log main
            10-17 02:48:16.000  1234  1300 D Reader  : Chapter One. It was a dark and stormy night
            """
                .trimIndent()
        val out = CrashReports.sanitizeTrace(tombstone, roots)
        assertTrue(out, "Function1;.invoke([F)Ljava/lang/Integer;" in out)
        assertTrue(out, "libsherpa-onnx-jni.so" in out)
        assertTrue(out, "name: kokoro-synth" in out)
        assertFalse(out, "dark" in out)
        assertFalse(out, "Austen" in out)
        assertFalse(out, "Emma" in out)
        assertFalse(out, "tail end of log" in out)
    }

    @Test
    fun aReportHasTheExitAndTheJavaCrash() {
        val report =
            CrashReports.formatReport(
                "Librillo 0.12.0-dev (18), Android 16 (API 36), Google Pixel 9 Pro XL",
                CrashReports.ExitRecord(
                    timestampMs = 1_000,
                    reason = "native crash",
                    description = "crash at $books/Secret Title.epub",
                    importance = 100,
                    trace = "backtrace:\n  #00 pc 0001  /system/lib64/libc.so",
                ),
                "Java crash at 1970-01-01T00:00:02Z\nThread: main",
                roots,
            )
        assertTrue(report, report.startsWith("Librillo crash report\n"))
        assertTrue(report, "Exit: native crash at 1970-01-01T00:00:01Z" in report)
        assertTrue(report, "Importance: 100" in report)
        assertTrue(report, "Description: crash at <path>" in report)
        assertTrue(report, "#00 pc 0001" in report)
        assertTrue(report, "Thread: main" in report)
        assertFalse(report, "Secret" in report)
    }

    @Test
    fun sharingSendsPlainTextAndCutsAVeryLongReport() {
        val chooser = CrashReports.shareIntent("x".repeat(CrashReports.SHARE_LIMIT + 10), "Subject")
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("text/plain", send.type)
        val text = send.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(text.length < CrashReports.SHARE_LIMIT + 100)
        assertTrue(text.endsWith("too long to share as text)"))
    }

    @Test
    fun aJavaCrashWrittenByTheHandlerBecomesAnUnseenReport() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(app.filesDir, "crash").apply { mkdirs() }
        File(dir, "last-java.txt").writeText("Java crash at 1970-01-01T00:00:02Z\nThread: main\n")
        CrashReports.collect(app)
        assertTrue(CrashReports.unseen.value)
        val report = CrashReports.report(app)!!
        assertTrue(report, "Thread: main" in report)
        CrashReports.markSeen(app)
        assertFalse(CrashReports.unseen.value)
        // Nothing new: no new notice.
        CrashReports.collect(app)
        assertFalse(CrashReports.unseen.value)
        File(dir, "last-report.txt").delete()
        assertNull(CrashReports.report(app))
    }
}
