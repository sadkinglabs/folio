package dev.sadking.folio.device

import dev.sadking.folio.FolioApplication
import dev.sadking.folio.R
import dev.sadking.folio.link.FolioLink
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Where KOReader keeps its plugins on each e-reader it runs on, most common first. */
internal val KOREADER_PLUGIN_FOLDERS =
    listOf(
        // Kindle
        "/mnt/us/koreader/plugins",
        // Kobo
        "/mnt/onboard/.adds/koreader/plugins",
        // PocketBook
        "/mnt/ext1/applications/koreader/plugins",
    )

/**
 * The phone address to give the e-reader: the one on the same network as [ereader] (same first
 * three parts, as on a home Wi-Fi or the phone's hotspot), else the first. Null without a network.
 */
internal fun phoneAddressFor(ereader: String, addresses: List<String>): String? {
    val network = ereader.substringBeforeLast('.', "")
    return addresses.firstOrNull { network.isNotEmpty() && it.substringBeforeLast('.') == network }
        ?: addresses.firstOrNull()
}

/** The one-time setup the plugin applies to Progress sync on its first start, then removes. */
internal fun linkSetupJson(server: String, username: String, userkey: String): String =
    JSONObject()
        .put("format", "librillo-link-setup")
        .put("version", 1)
        .put("server", server)
        .put("username", username)
        .put("userkey", userkey)
        .put("auto_fetch", true)
        .toString()

/**
 * Installs Librillo's KOReader plugin over the paired SSH connection: finds where KOReader is
 * installed, copies the plugin (the previous one is kept for recovery) and leaves the Link address
 * and sign-in for the plugin to apply, so nothing has to be typed on the e-reader.
 */
class BridgeInstaller(private val app: FolioApplication) {
    suspend fun install(): String =
        withContext(Dispatchers.IO) {
            val config = app.deviceSettings.load()
            val credential = app.deviceSettings.credential()
            val plugins =
                SftpDevice(config, credential).use { device ->
                    KOREADER_PLUGIN_FOLDERS.firstOrNull { device.isDirectory(it) }
                } ?: error(app.getString(R.string.msg_companion_no_koreader))
            val local = File(app.cacheDir, "bridge-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                // Every top-level file the app bundles, so new plugin files are never left out.
                val names =
                    app.assets.list("folio.koplugin").orEmpty().filter {
                        it.endsWith(".lua") || it == "LICENSE"
                    }
                require("main.lua" in names && "link.lua" in names)
                val files =
                    names
                        .associateWith { name ->
                            File(local, name).also { destination ->
                                app.assets.open("folio.koplugin/$name").use { input ->
                                    destination.outputStream().use(input::copyTo)
                                }
                            }
                        }
                        .toMutableMap()
                val address = phoneAddressFor(config.host, FolioLink.addresses())
                if (address != null) {
                    val link = app.link.settings
                    val account = link.account()
                    files[SETUP_FILE] =
                        File(local, SETUP_FILE).apply {
                            writeText(
                                linkSetupJson(
                                    "http://$address:${link.port}",
                                    account.username,
                                    account.key,
                                )
                            )
                        }
                }
                SftpDevice(config.copy(directory = plugins), credential).use {
                    it.installFolioBridge(files)
                }
                app.getString(
                    if (address != null) R.string.msg_companion_installed
                    else R.string.msg_companion_installed_no_link
                )
            } finally {
                local.deleteRecursively()
            }
        }

    private companion object {
        const val SETUP_FILE = "link-setup.json"
    }
}
