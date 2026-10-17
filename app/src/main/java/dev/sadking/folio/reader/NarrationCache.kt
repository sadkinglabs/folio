package dev.sadking.folio.reader

import android.content.Context
import dev.sadking.folio.reader.narration.NarrationTuning
import dev.sadking.folio.reader.narration.Pcm16
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers

/** One thread for narration's disk work: the audio cache and measurement files. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal val narrationIo = Dispatchers.IO.limitedParallelism(1)

/**
 * Narration audio already made, kept on disk so a passage heard again starts at once and a chapter
 * can be prepared ahead. Each entry is one synthesis unit: 16-bit mono PCM, edge-trimmed, without
 * the pause added after it. File names are hashes of the key (voice, speed, model, text), so no
 * book text is stored. Least recently used entries go first once the budget is reached; entries of
 * the chapter being prepared or played are pinned. Not part of Folio backups.
 *
 * Thread-safe; callers use [narrationIo] so disk work stays off the main and audio threads.
 */
internal class NarrationCache(
    private val dir: File,
    private val budgetBytes: Long = NarrationTuning.DEFAULT.cacheBudgetBytes,
    private val evictToFraction: Double = NarrationTuning.DEFAULT.cacheEvictToFraction,
) {
    /** Audio read back from the cache. */
    class Entry(val pcm: ShortArray, val sampleRate: Int)

    private class Info(var bytes: Long, var lastUsed: Long)

    private val lock = Any()
    private val index = HashMap<String, Info>()
    private val pinned = HashSet<String>()
    private var total = 0L
    private var opened = false
    private var lastStamp = 0L

    /** Use order: wall time, made strictly increasing so entries written together still rank. */
    private fun stamp(): Long =
        maxOf(System.currentTimeMillis(), lastStamp + 1).also { lastStamp = it }

    /** Bytes the cache holds now. */
    val sizeBytes: Long
        get() = synchronized(lock) { open().let { total } }

    private fun open() {
        if (opened) return
        opened = true
        dir.mkdirs()
        dir.listFiles()?.forEach { file ->
            when {
                file.name.endsWith(TMP) -> file.delete()
                file.name.endsWith(EXT) -> {
                    index[file.name.removeSuffix(EXT)] = Info(file.length(), file.lastModified())
                    total += file.length()
                    lastStamp = maxOf(lastStamp, file.lastModified())
                }
            }
        }
    }

    private fun file(key: String) = File(dir, key + EXT)

    /** The audio for [key], or null. A damaged entry is deleted and counts as a miss. */
    fun read(key: String): Entry? =
        synchronized(lock) {
            open()
            val file = file(key)
            if (!file.isFile) {
                forget(key)
                return null
            }
            try {
                val bytes = file.readBytes()
                val header = ByteBuffer.wrap(bytes, 0, minOf(HEADER, bytes.size))
                header.order(ByteOrder.LITTLE_ENDIAN)
                if (bytes.size < HEADER || header.int != MAGIC) throw IOException("bad header")
                val sampleRate = header.int
                val frames = header.int
                if (sampleRate <= 0 || frames < 0 || bytes.size != HEADER + frames * 2)
                    throw IOException("bad length")
                val now = stamp()
                file.setLastModified(now)
                index[key]?.lastUsed = now
                Entry(Pcm16.fromBytesLittleEndian(bytes, HEADER, frames * 2), sampleRate)
            } catch (_: IOException) {
                file.delete()
                forget(key)
                null
            }
        }

    /** Stores [pcm] under [key] atomically, then trims the cache to its budget. */
    fun write(key: String, variant: KokoroModel.Variant, sampleRate: Int, pcm: ShortArray) {
        synchronized(lock) {
            open()
            val header =
                ByteBuffer.allocate(HEADER)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(MAGIC)
                    .putInt(sampleRate)
                    .putInt(pcm.size)
                    .putInt(variant.ordinal + 1)
                    .array()
            val tmp = File(dir, key + TMP)
            try {
                tmp.outputStream().use {
                    it.write(header)
                    it.write(Pcm16.toBytesLittleEndian(pcm))
                }
                val target = file(key)
                if (!tmp.renameTo(target)) throw IOException("rename failed")
                forget(key)
                index[key] = Info(target.length(), stamp())
                total += target.length()
            } catch (_: IOException) {
                tmp.delete()
                return
            }
            evict()
        }
    }

    /** Keeps exactly [keys] safe from eviction (the chapter being prepared or played). */
    fun pin(keys: Collection<String>) =
        synchronized(lock) {
            pinned.clear()
            pinned.addAll(keys)
        }

    /** Deletes every entry made with [variant]; called when that model is removed. */
    fun removeVariant(variant: KokoroModel.Variant) =
        synchronized(lock) {
            open()
            for (key in index.keys.toList()) {
                val file = file(key)
                val owner =
                    try {
                        RandomAccessFile(file, "r").use { raf ->
                            val header = ByteArray(HEADER)
                            raf.readFully(header)
                            ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
                        }
                    } catch (_: IOException) {
                        -1
                    }
                if (owner == variant.ordinal + 1 || owner == -1) {
                    file.delete()
                    forget(key)
                }
            }
        }

    private fun forget(key: String) {
        index.remove(key)?.let { total -= it.bytes }
    }

    private fun evict() {
        if (total <= budgetBytes) return
        val target = (budgetBytes * evictToFraction).toLong()
        for ((key, _) in index.entries.sortedBy { it.value.lastUsed }) {
            if (total <= target) break
            if (key in pinned) continue
            file(key).delete()
            forget(key)
        }
    }

    companion object {
        private const val EXT = ".pcm"
        private const val TMP = ".tmp"
        const val HEADER = 16
        /** "NAR1" read as a little-endian Int. */
        private const val MAGIC = 0x3152414e

        @Volatile private var shared: NarrationCache? = null

        /** The app's cache under `files/narration/cache` (kept by the system, unlike cacheDir). */
        fun get(context: Context): NarrationCache =
            shared
                ?: synchronized(this) {
                    shared
                        ?: NarrationCache(
                                File(context.applicationContext.filesDir, "narration/cache")
                            )
                            .also { shared = it }
                }
    }
}
