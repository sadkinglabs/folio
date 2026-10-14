package dev.sadking.folio.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/** Bound native bitmap memory; revision filenames and file stamps invalidate stale artwork. */
internal object CoverImages {
    private val cache =
        object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }

    fun load(path: String?): Bitmap? {
        if (path == null) return null
        val file = File(path)
        if (!file.isFile) return null
        val key = "$path:${file.length()}:${file.lastModified()}"
        cache.get(key)?.let {
            return it
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options =
            BitmapFactory.Options().apply {
                var sample = 1
                while (bounds.outWidth / sample > 800 || bounds.outHeight / sample > 800) sample *=
                    2
                inSampleSize = sample
            }
        return BitmapFactory.decodeFile(path, options)?.also { cache.put(key, it) }
    }
}
