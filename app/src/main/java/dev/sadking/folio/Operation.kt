package dev.sadking.folio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class Operation(
    val busy: Boolean = false,
    val message: String = "",
    val progress: Float? = null,
    val failed: Boolean = false,
)

/**
 * Runs user-visible work one item at a time and reports it. Held by the application so every
 * screen's ViewModel shares one status line and one queue.
 *
 * @param transferActive whether a transfer job is running; device work refuses to start during one.
 * @param authentication the paired device's sign-in method, used to explain SSH failures.
 */
class OperationRunner(
    private val transferActive: suspend () -> Boolean,
    private val authentication: () -> String,
) {
    val state = MutableStateFlow(Operation())
    val notices = MutableSharedFlow<Operation>(extraBufferCapacity = 8)
    private val lock = Mutex()

    fun launch(scope: CoroutineScope, label: String, device: Boolean, work: suspend () -> String) {
        scope.launch {
            lock.withLock {
                state.value = Operation(true, label)
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            if (device)
                                check(!transferActive()) {
                                    "Finish or cancel the current transfer first"
                                }
                            work()
                        }
                    state.value = Operation(message = result)
                    notices.emit(state.value)
                } catch (e: CancellationException) {
                    // The screen that started it has gone; do not leave the status line busy.
                    state.value = Operation()
                    throw e
                } catch (e: Exception) {
                    state.value = Operation(message = safeError(e, authentication()), failed = true)
                    notices.emit(state.value)
                }
            }
        }
    }
}

internal fun safeError(e: Exception, authentication: String = ""): String =
    when (e) {
        is com.jcraft.jsch.JSchException ->
            when {
                e.message.orEmpty().contains("HostKey", ignoreCase = true) ->
                    "SSH host key changed or was rejected. Check the IP shown by KOReader, then read the fingerprint in setup and explicitly review any replacement."
                e.message.orEmpty().contains("Auth fail", ignoreCase = true) ->
                    if (authentication == "KOReader password-free")
                        "KOReader rejected password-free access. Stop SSH, disable key-only login, enable Login without password and restart SSH. Check the username is root."
                    else
                        "SSH authentication was rejected. Check the username, authorized public key and key passphrase or password."
                e.message.orEmpty().contains("Algorithm negotiation", ignoreCase = true) ->
                    "The SSH server's algorithms are unsupported. Update its configuration; obsolete algorithms are not automatically enabled."
                else ->
                    "SSH connection failed. Check Wi-Fi, the Kindle's address, SSH port and whether its server is running."
            }
        is com.jcraft.jsch.SftpException ->
            "SFTP operation failed. Check folder permissions, storage and connection; preview again before retrying."
        is IllegalArgumentException,
        is IllegalStateException -> e.message?.take(200) ?: "Operation could not be completed"
        else ->
            "Operation failed. Check the file, available storage and connection, then try again."
    }
