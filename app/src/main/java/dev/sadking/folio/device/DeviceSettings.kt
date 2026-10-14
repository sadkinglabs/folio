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

class DeviceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("folio-ssh", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "folio-ssh",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    fun load() =
        DeviceConfig(
            prefs.getString("host", "").orEmpty(),
            prefs.getInt("port", 2222),
            prefs.getString("user", "root").orEmpty(),
            prefs.getString("dir", "/mnt/us/documents").orEmpty(),
            prefs.getString("key", "").orEmpty(),
            prefs.getString("auth", "Key").orEmpty(),
        )

    private fun encrypt(value: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(value), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): ByteArray {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val c =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
        return c.doFinal(bytes.copyOfRange(12, bytes.size))
    }

    fun save(config: DeviceConfig, password: String, privateKey: ByteArray? = null) {
        config.validate()
        val old = load()
        val edit =
            prefs
                .edit()
                .putString("host", config.host)
                .putInt("port", config.port)
                .putString("user", config.username)
                .putString("dir", config.directory)
                .putString("key", config.fingerprint)
                .putString("auth", config.authentication)
        if (config.authentication == "KOReader password-free") {
            edit.remove("secret").remove("private")
        } else if (config.authentication == "Key") {
            if (privateKey != null) {
                edit
                    .putString("private", encrypt(privateKey))
                    .putString("secret", encrypt(password.toByteArray()))
            } else
                require(
                    old.fingerprint == config.fingerprint &&
                        old.username == config.username &&
                        old.authentication == config.authentication &&
                        prefs.contains("private")
                ) {
                    "Select a private key for this device"
                }
        } else if (password.isNotEmpty()) {
            edit.putString("secret", encrypt(password.toByteArray())).remove("private")
        } else
            require(
                old.fingerprint == config.fingerprint &&
                    old.username == config.username &&
                    old.authentication == config.authentication &&
                    prefs.contains("secret")
            ) {
                "Enter a password for this device"
            }
        check(edit.commit()) { "Could not save pairing" }
    }

    fun credential(): DeviceCredential {
        if (load().authentication == "KOReader password-free") return DeviceCredential("")
        val secret = prefs.getString("secret", null) ?: error("Save Kindle credentials first")
        return DeviceCredential(
            decrypt(secret).toString(Charsets.UTF_8),
            prefs.getString("private", null)?.let(::decrypt),
        )
    }

    fun forget() {
        prefs.edit().clear().apply()
    }
}
