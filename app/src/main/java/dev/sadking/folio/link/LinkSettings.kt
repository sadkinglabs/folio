package dev.sadking.folio.link

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Folio Link's settings. The password is generated on the phone and stored encrypted with a key in
 * the Android Keystore; it is only shown to the user, never logged.
 */
class LinkSettings(context: Context) {
    private val prefs = context.getSharedPreferences("folio-link", Context.MODE_PRIVATE)
    @Volatile private var cached: KosyncAccount? = null

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    var port: Int
        get() = prefs.getInt("port", DEFAULT_PORT)
        set(value) = prefs.edit().putInt("port", value.coerceIn(1024, 65535)).apply()

    /** Holds the CPU awake while Link runs, for phones that stop answering with the screen off. */
    var keepAwake: Boolean
        get() = prefs.getBoolean("keep-awake", false)
        set(value) = prefs.edit().putBoolean("keep-awake", value).apply()

    val username: String
        get() = USERNAME

    /** The password to type into KOReader, created the first time it is needed. */
    @Synchronized
    fun password(): String {
        prefs.getString("password", null)?.let { stored ->
            runCatching {
                return String(decrypt(stored))
            }
        }
        val created = generate()
        prefs.edit().putString("password", encrypt(created.toByteArray())).apply()
        cached = null
        return created
    }

    /** Makes a new password; KOReader must log in again. */
    @Synchronized
    fun resetPassword(): String {
        prefs.edit().remove("password").apply()
        cached = null
        return password()
    }

    fun account(): KosyncAccount =
        cached ?: KosyncAccount.fromPassword(USERNAME, password()).also { cached = it }

    private fun generate(): String {
        // No look-alike characters: it is read off a phone and typed on an e-reader keyboard.
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..12).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    private fun encrypt(value: ByteArray): String {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): ByteArray {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            }
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }

    companion object {
        const val DEFAULT_PORT = 8765
        const val USERNAME = "folio"
        private const val ALIAS = "folio-link"
    }
}
