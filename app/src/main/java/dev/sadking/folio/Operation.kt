package dev.sadking.folio

import dev.sadking.folio.data.isStorageFull
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class Operation(
    val busy: Boolean = false,
    val message: String = "",
    val progress: Float? = null,
    val failed: Boolean = false,
    /** Reverses the work just done, offered as Undo on the message. */
    val undo: (() -> Unit)? = null,
)

/**
 * The words used to explain a failure. Kept free of Android so the runner and its rules run on the
 * plain JVM; `failureText` in Messages.kt fills it from string resources.
 */
class FailureText(
    val hostKey: String,
    val passwordFreeRejected: String,
    val signInRejected: String,
    val unsupportedAlgorithms: String,
    val connection: String,
    val fileTransfer: String,
    val incomplete: String,
    val generic: String,
    /** The phone ran out of space. Empty reads as [generic]. */
    val storageFull: String = "",
)

/**
 * Runs user-visible work one item at a time and reports it. Held by the application so every
 * screen's ViewModel shares one status line and one queue.
 *
 * @param authentication the paired device's sign-in method, used to explain SSH failures.
 * @param text the words used to explain failures, read when one happens.
 */
class OperationRunner(
    private val authentication: () -> String,
    private val text: () -> FailureText,
) {
    val state = MutableStateFlow(Operation())
    val notices = MutableSharedFlow<Operation>(extraBufferCapacity = 8)
    private val lock = Mutex()

    fun launch(scope: CoroutineScope, label: String, work: suspend () -> String) =
        launchUndoable(scope, label) { work() to null }

    /** As [launch], for work that can be reversed: it returns its message and how to undo it. */
    fun launchUndoable(
        scope: CoroutineScope,
        label: String,
        work: suspend () -> Pair<String, (() -> Unit)?>,
    ) {
        scope.launch {
            lock.withLock {
                state.value = Operation(true, label)
                try {
                    val (result, undo) = withContext(Dispatchers.IO) { work() }
                    state.value = Operation(message = result, undo = undo)
                    notices.emit(state.value)
                } catch (e: CancellationException) {
                    // The screen that started it has gone; do not leave the status line busy.
                    state.value = Operation()
                    throw e
                } catch (e: Exception) {
                    state.value =
                        Operation(message = safeError(e, text(), authentication()), failed = true)
                    notices.emit(state.value)
                }
            }
        }
    }
}

/**
 * The message of a `require`, `check` or `error` call, which is written for the user. Kotlin's
 * defaults for a bare `require(...)` or `check(...)` are not, so they count as no message.
 */
internal fun userMessage(e: Exception): String? =
    e.message?.takeUnless { it == "Failed requirement." || it == "Check failed." }?.take(200)

internal fun safeError(e: Exception, text: FailureText, authentication: String = ""): String =
    when {
        // A full disk on the e-reader comes through SSH; that one is not the phone's.
        e is com.jcraft.jsch.JSchException || e is com.jcraft.jsch.SftpException ->
            knownError(e, text, authentication)
        isStorageFull(e) -> text.storageFull.ifEmpty { text.generic }
        else -> knownError(e, text, authentication)
    }

private fun knownError(e: Exception, text: FailureText, authentication: String): String =
    when (e) {
        is com.jcraft.jsch.JSchException ->
            when {
                e.message.orEmpty().contains("HostKey", ignoreCase = true) -> text.hostKey
                e.message.orEmpty().contains("Auth fail", ignoreCase = true) ->
                    if (authentication == "KOReader password-free") text.passwordFreeRejected
                    else text.signInRejected
                e.message.orEmpty().contains("Algorithm negotiation", ignoreCase = true) ->
                    text.unsupportedAlgorithms
                else -> text.connection
            }
        is com.jcraft.jsch.SftpException -> text.fileTransfer
        is IllegalArgumentException,
        is IllegalStateException -> userMessage(e) ?: text.incomplete
        else -> text.generic
    }
