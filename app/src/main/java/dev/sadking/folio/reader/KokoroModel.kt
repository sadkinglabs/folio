package dev.sadking.folio.reader

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Where the natural voice stands on this phone. */
internal sealed interface NaturalVoice {
    data object Missing : NaturalVoice

    /** [fraction] of the download, 0..1. */
    data class Downloading(val fraction: Float) : NaturalVoice

    data object Unpacking : NaturalVoice

    data object Ready : NaturalVoice

    data object Failed : NaturalVoice
}

/**
 * Kokoro, the natural voice: a one-time download of sherpa-onnx's 8-bit Kokoro 1.0 package, checked
 * against its published size and SHA-256 before anything is unpacked. Only the English files are
 * kept, in app storage, so the APK stays the same size.
 */
internal object KokoroModel {
    private const val URL_PACKAGE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2"
    private const val SHA256 = "4c3052abaa60943a341f193888cf6abd68787dae6ab8ae5c925a706caa247e4e"
    const val DOWNLOAD_BYTES = 132_303_094L
    private const val DONE_MARKER = ".complete"

    /** The files read-aloud needs; the Chinese dictionaries and rules are left out. */
    internal fun keep(path: String): Boolean =
        path in
            setOf(
                "model.int8.onnx",
                "voices.bin",
                "tokens.txt",
                "lexicon-us-en.txt",
                "LICENSE",
            ) || path.startsWith("espeak-ng-data/")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<NaturalVoice?>(null)
    private var job: Job? = null

    fun dir(context: Context) = File(context.filesDir, "voices/kokoro-v1_0")

    fun ready(context: Context) = File(dir(context), DONE_MARKER).isFile

    /** The current state, checked against the files on first use. */
    fun state(context: Context): StateFlow<NaturalVoice?> {
        if (mutableState.value == null)
            mutableState.value = if (ready(context)) NaturalVoice.Ready else NaturalVoice.Missing
        return mutableState
    }

    /** Downloads, verifies and unpacks the voice. Runs on until done, even if the sheet closes. */
    fun install(context: Context) {
        val app = context.applicationContext
        if (job?.isActive == true || ready(app)) return
        job = scope.launch {
            val target = dir(app)
            val archive = File(app.cacheDir, "kokoro.tar.bz2")
            val unpacking = File(target.parentFile, target.name + ".partial")
            try {
                mutableState.value = NaturalVoice.Downloading(0f)
                download(archive)
                mutableState.value = NaturalVoice.Unpacking
                unpacking.deleteRecursively()
                unpack(archive, unpacking)
                target.deleteRecursively()
                if (!unpacking.renameTo(target)) throw IOException("Could not move the voice")
                File(target, DONE_MARKER).writeText(SHA256)
                mutableState.value = NaturalVoice.Ready
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                unpacking.deleteRecursively()
                mutableState.value = NaturalVoice.Failed
            } finally {
                archive.delete()
            }
        }
    }

    /** Removes the voice to free its space; read-aloud falls back to the phone's voice. */
    fun remove(context: Context) {
        job?.cancel()
        dir(context).deleteRecursively()
        mutableState.value = NaturalVoice.Missing
    }

    private suspend fun download(file: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URL(URL_PACKAGE).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            if (connection.responseCode != 200)
                throw IOException("Download failed: HTTP ${connection.responseCode}")
            var read = 0L
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        read += count
                        mutableState.value =
                            NaturalVoice.Downloading(
                                (read.toFloat() / DOWNLOAD_BYTES).coerceIn(0f, 1f)
                            )
                    }
                }
            }
            if (read != DOWNLOAD_BYTES) throw IOException("Download was cut short")
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != SHA256) throw IOException("Download did not match the published voice")
        } finally {
            connection.disconnect()
        }
    }

    /** Unpacks the kept files, dropping the package's top folder; refuses paths that escape. */
    internal fun unpack(archive: File, into: File) {
        into.mkdirs()
        val root = into.canonicalPath + File.separator
        TarArchiveInputStream(
                BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))
            )
            .use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val path = entry.name.substringAfter('/', "")
                    if (path.isEmpty() || !keep(path)) continue
                    val out = File(into, path)
                    if (!out.canonicalPath.startsWith(root))
                        throw IOException("Unsafe path in voice")
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                    }
                }
            }
        if (!File(into, "model.int8.onnx").isFile)
            throw IOException("The voice package is incomplete")
    }
}
