package dev.sadking.folio.device

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class HardcoverSettings(context: Context) {
    private val prefs = context.getSharedPreferences("hardcover", Context.MODE_PRIVATE)
    val username: String
        get() = prefs.getString("username", "").orEmpty()

    var accountId: Int
        get() = prefs.getInt("accountId", 0)
        set(value) {
            check(prefs.edit().putInt("accountId", value).commit())
        }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("folio-hardcover", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "folio-hardcover",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    fun save(token: String, username: String) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted =
            Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)
        check(prefs.edit().putString("token", encrypted).putString("username", username).commit())
    }

    val connected: Boolean
        get() = prefs.contains("token")

    fun token(): String {
        val value = prefs.getString("token", null) ?: error("Connect Hardcover first")
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        return Cipher.getInstance("AES/GCM/NoPadding")
            .apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            .doFinal(bytes.copyOfRange(12, bytes.size))
            .toString(Charsets.UTF_8)
    }

    fun forget() {
        check(prefs.edit().clear().commit())
    }
}
