package dev.sadking.folio.link

import android.content.Context
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The bundled KOReader plugin as a zip holding one `folio.koplugin` folder, for installing over USB
 * on any e-reader. Unzip it into KOReader's plugins folder.
 */
object PluginExport {
    private const val ROOT = "folio.koplugin"

    fun write(context: Context, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun add(path: String) {
                val children = context.assets.list(path).orEmpty()
                if (children.isEmpty()) {
                    zip.putNextEntry(ZipEntry(path))
                    context.assets.open(path).use { it.copyTo(zip) }
                    zip.closeEntry()
                } else children.forEach { add("$path/$it") }
            }
            add(ROOT)
        }
    }
}
