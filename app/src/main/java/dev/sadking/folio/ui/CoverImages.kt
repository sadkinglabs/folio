package dev.sadking.folio.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/** Bound native bitmap memory; revision filenames and file stamps invalidate stale artwork. */
internal object CoverImages {
    /** Detail covers are ~130dp wide; 600px stays sharp there and quarters grid memory. */
    private const val MAX_SIDE = 600
    /** Mosaic thumbnails: a list card shows four at ~60 dp, so 160 px is plenty. */
    private const val THUMB_SIDE = 160
    // An eighth of the app's heap (Android's guidance for bitmap caches), at least 24 MB: enough
    // for several screens of covers, so scrolling back does not decode them again.
    private val cache =
        object :
            LruCache<String, Bitmap>(
                maxOf(24 * 1024 * 1024, (Runtime.getRuntime().maxMemory() / 8).toInt())
            ) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }
    // Thumbnails are tiny (~100 KB each); 4 MB holds a few hundred, so every list's mosaic stays
    // decoded once the Lists tab has been seen, and grids never evict them.
    private val thumbs =
        object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }

    fun load(path: String?): Bitmap? = decode(path, MAX_SIDE, cache)

    /** A small decode for list mosaics and colour sampling, with its own cache. */
    fun thumbnail(path: String?): Bitmap? = decode(path, THUMB_SIDE, thumbs)

    private fun decode(path: String?, maxSide: Int, into: LruCache<String, Bitmap>): Bitmap? {
        if (path == null) return null
        val file = File(path)
        if (!file.isFile) return null
        val key = "$path:${file.length()}:${file.lastModified()}"
        into.get(key)?.let {
            return it
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options =
            BitmapFactory.Options().apply {
                var sample = 1
                while (
                    bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide
                ) sample *= 2
                inSampleSize = sample
            }
        return BitmapFactory.decodeFile(path, options)?.also { into.put(key, it) }
    }
}
