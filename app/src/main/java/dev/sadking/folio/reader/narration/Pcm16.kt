package dev.sadking.folio.reader.narration

import kotlin.math.min
import kotlin.math.roundToInt

/** 16-bit mono PCM helpers. Pure functions. */
internal object Pcm16 {
    /** NaN/Inf → 0, clamp to ±1, [fadeSamples] linear fade at both ends, then scale to Short. */
    fun fromFloat(samples: FloatArray, fadeSamples: Int): ShortArray {
        val n = samples.size
        val fade = min(fadeSamples.coerceAtLeast(0), n / 2)
        return ShortArray(n) { i ->
            val raw = samples[i]
            val x = if (raw.isNaN() || raw.isInfinite()) 0f else raw.coerceIn(-1f, 1f)
            val gain =
                when {
                    fade == 0 -> 1f
                    i < fade -> i.toFloat() / fade
                    i >= n - fade -> (n - 1 - i).toFloat() / fade
                    else -> 1f
                }
            (x * gain * Short.MAX_VALUE).roundToInt().toShort()
        }
    }

    fun toBytesLittleEndian(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun fromBytesLittleEndian(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): ShortArray {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "range" }
        return ShortArray(length / 2) { i ->
            val lo = bytes[offset + 2 * i].toInt() and 0xFF
            val hi = bytes[offset + 2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort()
        }
    }

    fun seconds(frames: Int, sampleRate: Int): Double = frames.toDouble() / sampleRate
}
