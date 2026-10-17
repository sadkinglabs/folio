package dev.sadking.folio.reader.narration

import java.security.MessageDigest

/** The disk-cache key of one unit's audio. A hash, so no book text reaches the file system. */
internal object CacheKey {
    /**
     * SHA-256 hex of "v1|modelId|sid=..|speed=..|silence=..|en-us|SpeechText.VERSION|speechText".
     */
    fun of(
        modelId: String,
        sid: Int,
        speed: Double,
        silenceScale: Float,
        speechText: String,
    ): String {
        val source =
            "v1|$modelId|sid=$sid|speed=$speed|silence=$silenceScale|en-us|" +
                "${SpeechText.VERSION}|$speechText"
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            hex.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return hex.toString()
    }

    private const val HEX = "0123456789abcdef"
}
