package dev.sadking.folio.data

import java.io.IOException

/**
 * The phone ran out of space while Librillo was writing a book, a cover or a backup. Nothing was
 * half-saved: the work that failed is undone or left for later. [message] is for the user.
 */
class StorageFull(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A book's file is not what Librillo last saved: it was changed, replaced or removed outside
 * Librillo, so an edit would overwrite something Librillo has not seen. Nothing was written.
 * [message] is for the user.
 */
class FileChangedOutside(message: String) : IllegalStateException(message)

/**
 * True when [e], or one of its causes, is the system's "no space left on device" error (ENOSPC).
 * Android reports it in the message of an `ErrnoException` or of the `IOException` wrapping it;
 * reading the text keeps this free of Android classes, so it runs on the plain JVM.
 */
fun isStorageFull(e: Throwable): Boolean {
    var cause: Throwable? = e
    var depth = 0
    while (cause != null && depth < MAX_CAUSES) {
        if (cause is StorageFull) return true
        val text = cause.message.orEmpty()
        if (text.contains("ENOSPC") || text.contains("No space left on device", ignoreCase = true))
            return true
        if (cause.cause === cause) break
        cause = cause.cause
        depth++
    }
    return false
}

private const val MAX_CAUSES = 8
