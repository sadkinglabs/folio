package dev.sadking.folio.device

import dev.sadking.folio.FolioApplication
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Installs only Folio's companion, retaining the previous plugin directory for recovery. */
class BridgeInstaller(private val app: FolioApplication) {
    suspend fun install(pluginFolder: String): String =
        withContext(Dispatchers.IO) {
            require(pluginFolder.startsWith("/mnt/us/") && pluginFolder.endsWith("/plugins")) {
                "Choose KOReader's existing plugins folder on the Kindle"
            }
            val local = File(app.cacheDir, "bridge-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                // Every top-level file the app bundles, so new plugin files are never left out.
                val names =
                    app.assets.list("folio.koplugin").orEmpty().filter {
                        it.endsWith(".lua") || it == "LICENSE"
                    }
                require("main.lua" in names && "link.lua" in names)
                val files = names.associateWith { name ->
                    File(local, name).also { destination ->
                        app.assets.open("folio.koplugin/$name").use { input ->
                            destination.outputStream().use(input::copyTo)
                        }
                    }
                }
                val config = app.deviceSettings.load().copy(directory = pluginFolder)
                SftpDevice(config, app.deviceSettings.credential()).use {
                    it.installFolioBridge(files)
                }
                "Folio companion installed. Restart KOReader, configure its Folio book folder to match the paired Kindle folder, then enable Continue from Folio automatically. Previous plugin files were retained."
            } finally {
                local.deleteRecursively()
            }
        }
}
