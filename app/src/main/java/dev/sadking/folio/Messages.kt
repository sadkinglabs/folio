package dev.sadking.folio

import android.content.res.Resources

/** The failure explanations in the current language. */
internal fun Resources.failureText() =
    FailureText(
        hostKey = getString(R.string.msg_error_host_key),
        passwordFreeRejected = getString(R.string.msg_error_password_free_rejected),
        signInRejected = getString(R.string.msg_error_sign_in_rejected),
        unsupportedAlgorithms = getString(R.string.msg_error_unsupported_algorithms),
        connection = getString(R.string.msg_error_connection),
        fileTransfer = getString(R.string.msg_error_file_transfer),
        incomplete = getString(R.string.msg_error_incomplete),
        generic = getString(R.string.msg_error_generic),
        transferBusy = getString(R.string.msg_error_transfer_busy),
    )

/** Explains [e] to the user without raw details such as paths or server replies. */
internal fun safeError(e: Exception, resources: Resources, authentication: String = ""): String =
    safeError(e, resources.failureText(), authentication)
