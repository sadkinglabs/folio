package dev.sadking.folio

import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.link.PluginExport
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plugin zip for USB installs holds the whole bundled plugin in one folder. */
class PluginExportTest {
    @Test
    fun zipHoldsEveryPluginFileInOneFolder() {
        val app = ApplicationProvider.getApplicationContext<FolioApplication>()
        val bytes = ByteArrayOutputStream().also { PluginExport.write(app, it) }.toByteArray()
        val names = mutableListOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                assertTrue(entry.name, zip.readBytes().isNotEmpty())
            }
        }
        assertTrue(names.toString(), names.all { it.startsWith("folio.koplugin/") })
        assertTrue(
            names.toString(),
            names.containsAll(
                listOf(
                    "folio.koplugin/main.lua",
                    "folio.koplugin/link.lua",
                    "folio.koplugin/bridge.lua",
                    "folio.koplugin/_meta.lua",
                    "folio.koplugin/LICENSE",
                    "folio.koplugin/licenses/AGPL-3.0.txt",
                )
            ),
        )
    }
}
