package dev.sadking.folio

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A plain crash report the owner can send without a computer. On start it reads why the app last
 * ended (Android keeps crashes, native crashes and "not responding" with their traces); a handler
 * writes the stack of a Java crash before the process dies. The report keeps code facts only: stack
 * frames, exception classes and short messages. Book text, titles, file paths with book names,
 * content links and anything long or quoted are cut out; nothing is ever sent by itself.
 */
internal object CrashReports {
    private const val DIR = "crash"
    private const val JAVA = "last-java.txt"
    private const val REPORT = "last-report.txt"
    private const val PREFS = "crash-reports"
    /** Bytes of a trace read from Android: the crashing thread comes first. */
    const val TRACE_LIMIT = 256 * 1024
    /** Characters shared as text; a share larger than this can fail to send. */
    const val SHARE_LIMIT = 100_000
    private const val MESSAGE_LIMIT = 300
    private const val FRAMES_LIMIT = 60
    private const val CAUSES_LIMIT = 8
    private const val ABORT_LIMIT = 600

    private val REASONS =
        setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
        )

    private val installed = AtomicBoolean(false)
    private val mutableUnseen = MutableStateFlow(false)

    /** True when a report nobody has seen yet is waiting. */
    val unseen: StateFlow<Boolean> = mutableUnseen

    /** Catches Java crashes from now on and looks for one from last time. Once per process. */
    fun install(context: Context) {
        val app = context.applicationContext
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            try {
                writeJavaCrash(app, thread.name, failure)
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, failure)
        }
        Thread(
                {
                    try {
                        collect(app)
                    } catch (_: Throwable) {}
                },
                "crash-reports",
            )
            .apply { isDaemon = true }
            .start()
    }

    /** The last report, or null when Librillo has not closed unexpectedly. */
    fun report(context: Context): String? =
        File(dir(context), REPORT).takeIf { it.isFile }?.readText()

    /** When the last report was made, or null. */
    fun reportAt(context: Context): Long? =
        File(dir(context), REPORT).takeIf { it.isFile }?.lastModified()

    fun markSeen(context: Context) {
        prefs(context).edit().putBoolean("unseen", false).apply()
        mutableUnseen.value = false
    }

    /** A chooser to send [report] as plain text. */
    fun shareIntent(report: String, subject: String): Intent {
        val text =
            if (report.length <= SHARE_LIMIT) report
            else report.take(SHARE_LIMIT) + "\n(cut: the rest was too long to share as text)"
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, text)
        return Intent.createChooser(send, null)
    }

    // ---- Collecting -----------------------------------------------------------------------

    private fun dir(context: Context) = File(context.applicationContext.filesDir, DIR)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun writeJavaCrash(app: Context, threadName: String, failure: Throwable) {
        val text =
            formatJavaCrash(
                header(app),
                threadName,
                failure,
                System.currentTimeMillis(),
                roots(app),
            )
        dir(app).mkdirs()
        File(dir(app), JAVA).writeText(text)
    }

    /** Builds a new report when Android or the handler recorded a crash since the last one. */
    internal fun collect(app: Context) {
        val prefs = prefs(app)
        val manager = app.getSystemService(ActivityManager::class.java)
        val lastExit = prefs.getLong("exitAt", 0)
        val exit =
            manager
                ?.getHistoricalProcessExitReasons(app.packageName, 0, 5)
                .orEmpty()
                .filter { it.reason in REASONS && it.timestamp > lastExit }
                .maxByOrNull { it.timestamp }
        val java =
            File(dir(app), JAVA).takeIf {
                it.isFile && it.lastModified() > prefs.getLong("javaAt", 0)
            }
        if (exit == null && java == null) {
            mutableUnseen.value = prefs.getBoolean("unseen", false) && report(app) != null
            return
        }
        val record = exit?.let {
            ExitRecord(
                timestampMs = it.timestamp,
                reason = reasonName(it.reason),
                description = it.description,
                importance = it.importance,
                trace =
                    try {
                        it.traceInputStream?.use { stream ->
                            String(stream.readNBytes(TRACE_LIMIT), Charsets.UTF_8)
                        }
                    } catch (_: Exception) {
                        null
                    },
            )
        }
        val text = formatReport(header(app), record, java?.readText(), roots(app))
        dir(app).mkdirs()
        File(dir(app), REPORT).writeText(text)
        prefs
            .edit()
            .putLong("exitAt", exit?.timestamp ?: lastExit)
            .putLong("javaAt", java?.lastModified() ?: prefs.getLong("javaAt", 0))
            .putBoolean("unseen", true)
            .apply()
        mutableUnseen.value = true
    }

    private fun header(app: Context) =
        "Librillo ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}"

    /** Folders whose paths name books: the Books folder and the app's own library. */
    private fun roots(app: Context): List<String> =
        listOfNotNull(
            runCatching { dev.sadking.folio.data.BooksFolder(app).location.path }.getOrNull(),
            File(app.filesDir, "library").path,
        )

    private fun reasonName(reason: Int) =
        when (reason) {
            ApplicationExitInfo.REASON_CRASH -> "Java crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
            else -> "reason $reason"
        }

    // ---- Formatting (pure, tested on the JVM) ---------------------------------------------

    /** What Android recorded about one exit. */
    internal data class ExitRecord(
        val timestampMs: Long,
        val reason: String,
        val description: String?,
        val importance: Int,
        val trace: String?,
    )

    internal fun formatReport(
        header: String,
        exit: ExitRecord?,
        javaCrash: String?,
        roots: List<String>,
    ): String = buildString {
        appendLine("Librillo crash report")
        appendLine(header)
        if (exit != null) {
            appendLine()
            appendLine("Exit: ${exit.reason} at ${Instant.ofEpochMilli(exit.timestampMs)}")
            appendLine("Importance: ${exit.importance}")
            exit.description?.let { appendLine("Description: ${message(it, roots)}") }
            exit.trace?.let {
                appendLine()
                appendLine("Trace:")
                appendLine(sanitizeTrace(it, roots))
            }
        }
        if (javaCrash != null) {
            appendLine()
            // Written by this app's own handler, already cleaned; cleaned again in case.
            append(sanitizeTrace(javaCrash, roots))
        }
    }

    internal fun formatJavaCrash(
        header: String,
        threadName: String,
        failure: Throwable,
        atMs: Long,
        roots: List<String>,
    ): String = buildString {
        appendLine("Java crash at ${Instant.ofEpochMilli(atMs)}")
        appendLine(header)
        appendLine("Thread: ${redact(threadName, roots)}")
        var current: Throwable? = failure
        var depth = 0
        val seen = HashSet<Throwable>()
        while (current != null && depth < CAUSES_LIMIT && seen.add(current)) {
            if (depth > 0) append("Caused by: ")
            appendLine(summary(current, roots))
            current.stackTrace.take(FRAMES_LIMIT).forEach { appendLine("\tat $it") }
            if (current.stackTrace.size > FRAMES_LIMIT)
                appendLine("\t... ${current.stackTrace.size - FRAMES_LIMIT} more")
            current = current.cause
            depth++
        }
    }

    /** "java.lang.IllegalStateException: message", or the class alone when unsafe. */
    internal fun summary(failure: Throwable, roots: List<String>): String {
        val name = failure.javaClass.name
        val raw = failure.message ?: return name
        if (CONTENT_PRONE.any { it in name }) return name
        val kept = message(raw, roots)
        return if (kept.isEmpty()) name else "$name: $kept"
    }

    /** A message as code wrote it, or nothing when it is long and could hold text from a book. */
    internal fun message(raw: String, roots: List<String>): String {
        val cleaned = redact(raw, roots).replace(Regex("\\s+"), " ").trim()
        return if (cleaned.length > MESSAGE_LIMIT) "(message left out)" else cleaned
    }

    /**
     * A trace as Android or the handler wrote it, without what could hold personal data: the log
     * lines at the end of a native crash, memory dumps (their text column can show any bytes) and
     * paths and quoted text anywhere.
     */
    internal fun sanitizeTrace(trace: String, roots: List<String>): String =
        trace
            .lineSequence()
            .takeWhile { !it.startsWith("--------- ") }
            .filterNot { MEMORY_DUMP.containsMatchIn(it) }
            .joinToString("\n") { line ->
                // A native abort's own message is the most useful line and comes from code
                // (CheckJNI names the call that failed): keep it whole, paths still cut.
                if (line.trimStart().startsWith("Abort message:"))
                    redact(line, roots, quotes = false).take(ABORT_LIMIT)
                else redact(line, roots)
            }
            .trimEnd()

    /** Cuts content links, book and storage paths, book files and quoted text. */
    internal fun redact(text: String, roots: List<String>, quotes: Boolean = true): String {
        var out = text
        for (root in roots.filter { it.length > 1 }.sortedByDescending { it.length }) {
            out = out.replace(Regex(Regex.escape(root) + PATH_TAIL), "<path>")
        }
        out = out.replace(CONTENT_URI, "content://…")
        out = out.replace(FILE_URI, "file://…")
        out = out.replace(STORAGE_PATH, "<path>")
        out = out.replace(BOOK_FILE, "<file>")
        if (quotes) out = out.replace(QUOTED, "\"…\"")
        return out
    }

    /** Exceptions whose messages usually quote the input they failed on. */
    private val CONTENT_PRONE =
        listOf("Json", "JSON", "Xml", "XML", "Parse", "NumberFormat", "SAX", "Malformed")

    private val CONTENT_URI = Regex("content://\\S*")
    private val FILE_URI = Regex("file://\\S*")
    private const val BOOK_EXTENSIONS = "(?:epub|kepub|pdf|cbz|cbr|mobi|azw3?|fb2|txt|sdr)"
    /**
     * The rest of a path: up to a book file's extension even across spaces ("Jane Austen/Pride and
     * Prejudice.epub"), else up to the first space.
     */
    private const val PATH_TAIL = "(?:[^\\n'\"]*?\\.(?i:$BOOK_EXTENSIONS)\\b|[^\\s'\"),;\\]]*)"
    private val STORAGE_PATH =
        Regex("(?<![\\w.])/(?:storage|sdcard|mnt|data/media)(?:/$PATH_TAIL)?")
    private val BOOK_FILE =
        Regex("[^\\s'\"(),;\\[\\]]+\\.$BOOK_EXTENSIONS\\b", RegexOption.IGNORE_CASE)
    /** Quoted spans longer than a code identifier would be. */
    private val QUOTED = Regex("[\"“”].{24,}?[\"“”]")
    /** Tombstone memory lines: an address, words of hex, then a text column. */
    private val MEMORY_DUMP = Regex("^\\s*(?:0x)?[0-9a-f]{8,16}(?: [0-9a-f]{8,16}){2,}")
}
