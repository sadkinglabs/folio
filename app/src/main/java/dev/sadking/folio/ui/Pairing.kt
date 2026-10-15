package dev.sadking.folio.ui

import dev.sadking.folio.device.validateEndpoint

/** The four steps of SSH pairing, in the order the form shows them. */
enum class PairingStep {
    NETWORK,
    ADDRESS,
    LOGIN,
    FOLDER,
}

/**
 * Which step the pairing form is on: the first one whose details are still missing. The network
 * step is done once an address is typed; the address step once a fingerprint is trusted; login once
 * the chosen mode has what it needs (a key, a password, or nothing); folder is the rest.
 */
fun pairingStep(
    host: String,
    port: Int,
    fingerprint: String,
    authentication: String,
    hasSecret: Boolean,
    reuseSavedSecret: Boolean,
): PairingStep =
    when {
        runCatching { validateEndpoint(host, port) }.isFailure -> PairingStep.NETWORK
        !fingerprint.matches(Regex("SHA256:[A-Za-z0-9+/]{43}=?")) -> PairingStep.ADDRESS
        authentication != "KOReader password-free" && !hasSecret && !reuseSavedSecret ->
            PairingStep.LOGIN
        else -> PairingStep.FOLDER
    }
