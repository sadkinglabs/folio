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
 * Kokoro, the natural voice: a one-time download of one of sherpa-onnx's Kokoro 1.0 packages,
 * checked against its published size and SHA-256 before anything is unpacked. Only the English
 * files are kept, in app storage, so the APK stays the same size.
 */
internal object KokoroModel {
    /**
     * The two packages of the same model. [INT8] is the original download and keeps its folder, so
     * existing installs keep working. [FP32] is a larger download that runs faster on CPUs whose
     * 8-bit kernels are slow (measured on the host: 0.43 vs 1.45 seconds per second of speech).
     */
    enum class Variant(val dirName: String, val downloadBytes: Long, val modelFile: String) {
        INT8("kokoro-v1_0", 132_303_094L, "model.int8.onnx"),
        FP32("kokoro-v1_0-fp32", 349_906_910L, "model.onnx"),
    }

    private const val RELEASES =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"

    private fun url(variant: Variant) =
        RELEASES +
            when (variant) {
                Variant.INT8 -> "kokoro-int8-multi-lang-v1_0.tar.bz2"
                Variant.FP32 -> "kokoro-multi-lang-v1_0.tar.bz2"
            }

    /** Published SHA-256 of each package (FP32 checked against a download on 2026-10-17). */
    internal fun sha256(variant: Variant) =
        when (variant) {
            Variant.INT8 -> "4c3052abaa60943a341f193888cf6abd68787dae6ab8ae5c925a706caa247e4e"
            Variant.FP32 -> "c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298"
        }

    /** Names the exact model in cache keys: audio from another package is never reused. */
    internal fun modelId(variant: Variant) = "${variant.name.lowercase()}:${sha256(variant)}"

    /** Kept for the download button's size label: the standard (INT8) package. */
    const val DOWNLOAD_BYTES = 132_303_094L
    private const val DONE_MARKER = ".complete"

    /** The files read-aloud needs; the Chinese dictionaries and rules are left out. */
    internal fun keep(path: String, variant: Variant = Variant.INT8): Boolean =
        path in
            setOf(variant.modelFile, "voices.bin", "tokens.txt", "lexicon-us-en.txt", "LICENSE") ||
            path.startsWith("espeak-ng-data/")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = Variant.entries.associateWith { MutableStateFlow<NaturalVoice?>(null) }
    private val jobs = HashMap<Variant, Job>()

    fun dir(context: Context, variant: Variant = Variant.INT8) =
        File(context.filesDir, "voices/${variant.dirName}")

    fun ready(context: Context, variant: Variant = Variant.INT8) =
        File(dir(context, variant), DONE_MARKER).isFile

    /** The installed variants, standard first. */
    fun installed(context: Context): List<Variant> = Variant.entries.filter { ready(context, it) }

    /** The current state, checked against the files on first use. */
    fun state(context: Context, variant: Variant = Variant.INT8): StateFlow<NaturalVoice?> {
        val state = states.getValue(variant)
        if (state.value == null)
            state.value = if (ready(context, variant)) NaturalVoice.Ready else NaturalVoice.Missing
        return state
    }

    /** Downloads, verifies and unpacks the voice. Runs on until done, even if the sheet closes. */
    fun install(context: Context, variant: Variant = Variant.INT8) {
        val app = context.applicationContext
        if (jobs[variant]?.isActive == true || ready(app, variant)) return
        val state = states.getValue(variant)
        jobs[variant] = scope.launch {
            val target = dir(app, variant)
            val archive = File(app.cacheDir, "kokoro-${variant.name.lowercase()}.tar.bz2")
            val unpacking = File(target.parentFile, target.name + ".partial")
            try {
                state.value = NaturalVoice.Downloading(0f)
                download(variant, archive) { state.value = NaturalVoice.Downloading(it) }
                state.value = NaturalVoice.Unpacking
                unpacking.deleteRecursively()
                unpack(archive, unpacking, variant)
                target.deleteRecursively()
                if (!unpacking.renameTo(target)) throw IOException("Could not move the voice")
                File(target, DONE_MARKER).writeText(sha256(variant))
                state.value = NaturalVoice.Ready
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                unpacking.deleteRecursively()
                state.value = NaturalVoice.Failed
            } finally {
                archive.delete()
            }
        }
    }

    /**
     * Removes the voice to free its space, with the narration it made; read-aloud falls back to the
     * other variant or the phone's voice.
     */
    fun remove(context: Context, variant: Variant = Variant.INT8) {
        val app = context.applicationContext
        jobs[variant]?.cancel()
        dir(app, variant).deleteRecursively()
        states.getValue(variant).value = NaturalVoice.Missing
        scope.launch(narrationIo) { NarrationCache.get(app).removeVariant(variant) }
    }

    private suspend fun download(variant: Variant, file: File, progress: (Float) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URL(url(variant)).openConnection() as HttpURLConnection
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
                        progress((read.toFloat() / variant.downloadBytes).coerceIn(0f, 1f))
                    }
                }
            }
            if (read != variant.downloadBytes) throw IOException("Download was cut short")
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != sha256(variant))
                throw IOException("Download did not match the published voice")
        } finally {
            connection.disconnect()
        }
    }

    /** Unpacks the kept files, dropping the package's top folder; refuses paths that escape. */
    internal fun unpack(archive: File, into: File, variant: Variant = Variant.INT8) {
        into.mkdirs()
        val root = into.canonicalPath + File.separator
        TarArchiveInputStream(
                BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))
            )
            .use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val path = entry.name.substringAfter('/', "")
                    if (path.isEmpty() || !keep(path, variant)) continue
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
        if (!File(into, variant.modelFile).isFile)
            throw IOException("The voice package is incomplete")
    }
}
