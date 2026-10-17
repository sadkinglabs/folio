package dev.sadking.folio.reader.dictionary

import android.content.Context
import dev.sadking.folio.core.dictionary.DictionaryEntry
import dev.sadking.folio.core.dictionary.DictionaryPackage
import dev.sadking.folio.core.dictionary.WordNetDictionary
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the offline dictionary stands on this phone. */
internal sealed interface DictionaryState {
    data object Missing : DictionaryState

    /** [fraction] of the download, 0..1. */
    data class Downloading(val fraction: Float) : DictionaryState

    data object Unpacking : DictionaryState

    data object Ready : DictionaryState

    data object Failed : DictionaryState
}

/**
 * Downloads and installs the dictionary into [root] (`filesDir/dictionary`). The download goes to
 * `download.part` and resumes with a Range request: 206 appends, 200 starts over. The whole file is
 * checked against [bytes] and [sha256] before anything is unpacked into `<version>.partial`, which
 * is then renamed into place and marked complete. A file that fails the check is deleted.
 */
internal class DictionaryInstaller(
    private val root: File,
    private val url: String = DictionaryPackage.URL,
    private val bytes: Long = DictionaryPackage.BYTES,
    private val sha256: String = DictionaryPackage.SHA256,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val unpack: (File, File) -> Unit = DictionaryPackage::unpack,
) {
    val dir = File(root, DictionaryPackage.VERSION)
    val part = File(root, "download.part")
    private val marker = File(dir, DONE_MARKER)

    fun ready(): Boolean = marker.isFile && runCatching { marker.readText() }.getOrNull() == sha256

    /**
     * Runs the whole install, reporting each step through [state]; ends in [DictionaryState.Ready]
     * or [DictionaryState.Failed]. Cancellation leaves the partial download for the next try.
     */
    suspend fun install(state: MutableStateFlow<DictionaryState>) {
        val unpacking = File(root, dir.name + ".partial")
        try {
            state.value = DictionaryState.Downloading(fraction(part.length()))
            download { state.value = DictionaryState.Downloading(it) }
            // Cut short: keep what arrived, so the next try asks only for the rest.
            if (part.length() < bytes) throw IOException("Download was cut short")
            verify()
            state.value = DictionaryState.Unpacking
            unpacking.deleteRecursively()
            unpack(part, unpacking)
            dir.deleteRecursively()
            if (!unpacking.renameTo(dir)) throw IOException("Could not move the dictionary")
            marker.writeText(sha256)
            part.delete()
            state.value = DictionaryState.Ready
        } catch (cancel: CancellationException) {
            unpacking.deleteRecursively()
            throw cancel
        } catch (_: Exception) {
            unpacking.deleteRecursively()
            state.value = DictionaryState.Failed
        }
    }

    fun remove() {
        dir.deleteRecursively()
        File(root, dir.name + ".partial").deleteRecursively()
        part.delete()
    }

    private fun fraction(read: Long) = (read.toFloat() / bytes).coerceIn(0f, 1f)

    /** Fetches what [part] lacks; https only, redirects only to https. */
    private suspend fun download(progress: (Float) -> Unit) {
        root.mkdirs()
        var have = part.length()
        if (have > bytes) {
            part.delete()
            have = 0
        }
        if (have == bytes) return
        val connection = open(URL(url))
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
        try {
            val append =
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> have > 0
                    HttpURLConnection.HTTP_OK -> false
                    // The partial file no longer fits what the server has: start over next time.
                    416 -> {
                        part.delete()
                        throw IOException("Download failed")
                    }
                    else -> throw IOException("Download failed")
                }
            if (connection.url.protocol != "https") throw IOException("Not https")
            var read = if (append) have else 0L
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        read += count
                        if (read > bytes) throw IOException("Download is larger than expected")
                        output.write(buffer, 0, count)
                        progress(fraction(read))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** The whole file's size and SHA-256; a file that does not match is deleted. */
    private fun verify() {
        val ok = part.length() == bytes && hash(part) == sha256
        if (!ok) {
            part.delete()
            throw IOException("Download did not match the published dictionary")
        }
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DONE_MARKER = ".complete"
    }
}

/**
 * The offline dictionary: a one-time download of Open English WordNet (about 11 MB), checked
 * against its published size and SHA-256, kept in app storage. Downloads run on after the sheet
 * closes and resume where they stopped. Lookups never leave the phone and are never logged.
 */
internal object DictionaryModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<DictionaryState>(DictionaryState.Missing)
    private var job: Job? = null
    private var opened: WordNetDictionary? = null

    private fun installer(context: Context) =
        DictionaryInstaller(File(context.applicationContext.filesDir, "dictionary"))

    /** `filesDir/dictionary/oewn-2025-plus`. */
    fun dir(context: Context): File = installer(context).dir

    /** True when the files are unpacked and marked complete for this package. */
    fun ready(context: Context): Boolean = installer(context).ready()

    /**
     * The current state. Unless an install is running, it is checked against the files each time,
     * so a dictionary removed or added outside this object is noticed; a failure stays shown until
     * the next try.
     */
    fun state(context: Context): StateFlow<DictionaryState> {
        synchronized(this) {
            if (job?.isActive != true)
                state.value =
                    when {
                        ready(context) -> DictionaryState.Ready
                        state.value == DictionaryState.Failed -> DictionaryState.Failed
                        else -> DictionaryState.Missing
                    }
        }
        return state
    }

    /**
     * Downloads, verifies and unpacks the dictionary. Runs on until done, even if the sheet closes.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (job?.isActive == true || ready(app)) return
            state(app)
            job = scope.launch { installer(app).install(state) }
        }
    }

    /** Removes the dictionary to free its space; Define offers the download again. */
    fun remove(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            job?.cancel()
            opened?.close()
            opened = null
            installer(app).remove()
            state.value = DictionaryState.Missing
        }
    }

    /** Entries for the selected text; empty when the dictionary is missing or has no match. */
    suspend fun define(context: Context, selection: String): List<DictionaryEntry> =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            if (!ready(app)) return@withContext emptyList()
            val dictionary =
                synchronized(this@DictionaryModel) {
                    opened ?: WordNetDictionary(dir(app)).also { opened = it }
                }
            dictionary.define(selection)
        }
}
