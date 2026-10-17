package dev.sadking.folio.acquire

import android.content.Context
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.Operation
import dev.sadking.folio.data.TooLargeBook
import dev.sadking.folio.link.LinkSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The browser's download, if any. */
internal sealed interface DownloadState {
    data object Idle : DownloadState

    /** [bytes] arrived of [total], when the server said. */
    data class Running(val name: String, val bytes: Long, val total: Long?) : DownloadState

    /** Done; [ereader] when the e-reader is set up, so the card can say when it gets there. */
    data class Finished(val outcome: AcquireOutcome, val ereader: Boolean) : DownloadState
}

/**
 * One download at a time, for the whole app. It runs in its own scope, so closing the browser
 * doesn't stop it; Stop does, and deletes the partial file. The outcome is also sent to the app's
 * notices, so the Library shows it if the browser was closed meanwhile.
 */
internal object AcquireDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = mutableState
    @Volatile private var job: Job? = null

    val busy: Boolean
        get() = job?.isActive == true

    /**
     * Starts fetching [request] as [name], to be linked to [wishId] when set. False when another
     * download is still running.
     */
    @Synchronized
    fun start(context: Context, request: DownloadRequest, name: String, wishId: String?): Boolean {
        if (busy) return false
        val app = context.applicationContext as FolioApplication
        mutableState.value = DownloadState.Running(name, 0, null)
        job = scope.launch {
            val importer = AcquireImporter(app.repository, app.cacheDir)
            val staged =
                try {
                    importer.stage()
                } catch (e: Exception) {
                    report(app, failure(e))
                    return@launch
                }
            val outcome =
                try {
                    val result =
                        BookDownloader().download(request, staged.file) { bytes, total ->
                            mutableState.value = DownloadState.Running(name, bytes, total)
                        }
                    // The final response's file name wins: it comes after any redirects.
                    val finalName =
                        if (result.contentDisposition != null)
                            DownloadSniffer.fileName(
                                result.finalUrl,
                                result.contentDisposition,
                                result.contentType,
                            )
                        else name
                    // Once the bytes are here, the import runs to the end.
                    withContext(NonCancellable) { importer.finish(staged, finalName, wishId) }
                } catch (cancel: CancellationException) {
                    importer.discard(staged)
                    mutableState.value = DownloadState.Idle
                    throw cancel
                } catch (_: TooLargeBook) {
                    importer.discard(staged)
                    AcquireOutcome.TooLarge
                } catch (e: Exception) {
                    importer.discard(staged)
                    failure(e)
                }
            report(app, outcome)
        }
        return true
    }

    /** Stops the running download and deletes what arrived. */
    fun stop() {
        job?.cancel()
    }

    /** Shows [outcome] without a download, such as for an ACSM ticket. */
    fun report(context: Context, outcome: AcquireOutcome) {
        val app = context.applicationContext as FolioApplication
        val ereader = runCatching { LinkSettings(app).isSetUp }.getOrDefault(false)
        mutableState.value = DownloadState.Finished(outcome, ereader)
        val text =
            (listOf(outcome.headline(app.resources)) + outcome.details(app.resources, ereader))
                .joinToString("\n")
        app.operations.notices.tryEmit(Operation(message = text, failed = outcome.failed))
    }

    /** Clears a shown outcome. */
    fun dismiss() {
        if (mutableState.value is DownloadState.Finished) mutableState.value = DownloadState.Idle
    }
}
