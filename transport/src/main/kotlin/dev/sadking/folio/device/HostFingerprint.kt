package dev.sadking.folio.device

import com.jcraft.jsch.*
import java.security.MessageDigest
import java.util.Base64

/** Public server identity observed during key exchange, before any user authentication. */
data class ObservedHostKey(
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String,
)

internal fun sshFingerprint(key: ByteArray): String =
    "SHA256:" +
        Base64.getEncoder()
            .withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

fun validateEndpoint(host: String, port: Int) {
    require(
        host.isNotBlank() &&
            host.length <= 253 &&
            host.none { it.isWhitespace() || it.code < 32 || it == '/' || it == '\\' } &&
            port in 1..65535
    ) {
        "Enter the Kindle address and a valid SSH port"
    }
}

/**
 * Read-only SSH handshake. CHANGED deliberately aborts before user authentication; this does not
 * authenticate the device. Trust must be an explicit user decision, independent of this probe.
 */
object HostFingerprint {
    fun read(host: String, port: Int): ObservedHostKey {
        validateEndpoint(host, port)
        var observed: ObservedHostKey? = null
        val jsch = JSch()
        jsch.setHostKeyRepository(
            object : HostKeyRepository {
                override fun check(address: String?, key: ByteArray): Int {
                    observed =
                        ObservedHostKey(host, port, HostKey(address, key).type, sshFingerprint(key))
                    return HostKeyRepository.CHANGED
                }

                override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit

                override fun remove(host: String?, type: String?) = Unit

                override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

                override fun getKnownHostsRepositoryID() =
                    "Folio unauthenticated host-key observation"

                override fun getHostKey(): Array<HostKey> = emptyArray()

                override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
            }
        )
        val session = jsch.getSession("folio-fingerprint-probe", host, port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.timeout = 10000
        try {
            session.connect(10000)
            error("Fingerprint probe unexpectedly authenticated")
        } catch (e: JSchException) {
            return observed ?: throw e
        } finally {
            session.disconnect()
        }
    }
}

/** KOReader's patched -n mode accepts an arbitrary non-empty SSH password. This is not a secret. */
internal fun DeviceConfig.passwordFor(credential: DeviceCredential): String =
    if (authentication == "KOReader password-free") "folio-temporary-access" else credential.secret
