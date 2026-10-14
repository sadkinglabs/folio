package dev.sadking.folio.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/** Bound native bitmap memory; revision filenames and file stamps invalidate stale artwork. */
internal object CoverImages {
    /** Detail covers are ~130dp wide; 600px stays sharp there and quarters grid memory. */
    private const val MAX_SIDE = 600
    // An eighth of the app's heap (Android's guidance for bitmap caches), at least 24 MB: enough
    // for several screens of covers, so scrolling back does not decode them again.
    private val cache =
        object :
            LruCache<String, Bitmap>(
                maxOf(24 * 1024 * 1024, (Runtime.getRuntime().maxMemory() / 8).toInt())
            ) {
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
                while (
                    bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE
                ) sample *= 2
                inSampleSize = sample
            }
        return BitmapFactory.decodeFile(path, options)?.also { cache.put(key, it) }
    }
}
