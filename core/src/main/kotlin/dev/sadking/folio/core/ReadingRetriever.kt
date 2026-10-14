package dev.sadking.folio.core

import java.io.ByteArrayOutputStream

object ReadingRetriever {
    /**
     * Adjacent sidecars only. Central/hashed storage requires a separately configured export route.
     */
    fun sidecar(epubPath: String): String {
        require(epubPath.endsWith(".epub", true))
        val extension = epubPath.substringAfterLast('.')
        return "${epubPath.substringBeforeLast('.')}.sdr/metadata.$extension.lua"
    }

    fun read(remote: DeviceFiles, epubPath: String): Pair<ReaderData, String>? {
        val path = sidecar(epubPath)
        if (!remote.exists(path)) return null
        fun bytes() =
            ByteArrayOutputStream()
                .also { out ->
                    remote.read(path).use { Epub.copyBounded(it, out, 4L * 1024 * 1024) }
                }
                .toByteArray()
        val first = bytes()
        val second = bytes()
        check(first.contentEquals(second)) {
            "Reading data changed during retrieval; close the book and retry"
        }
        return KoReaderData.parse(first) to KoReaderData.digest(first)
    }
}
