package dev.sadking.folio.reader

import dev.sadking.folio.FolioApplication
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
import dev.sadking.folio.device.SftpDevice
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** File transfer and reading-position handoff are separate operations. Never edits sidecars. */
class ReaderHandoff(private val app: FolioApplication) {
    private val dao = app.database.library()

    private suspend fun verifiedBook(book: Book, remote: DeviceFiles): Transfer {
        val transfer =
            dao.transfers(app.deviceSettings.load().id).firstOrNull { it.bookId == book.id }
                ?: error("Send this EPUB to Kindle first from book details")
        require(transfer.hash == book.currentHash) {
            "Phone and Kindle have different EPUB revisions. Review the transfer first."
        }
        require(
            remote.exists(transfer.remotePath) &&
                remote.read(transfer.remotePath).use { Epub.hash(it) } == book.currentHash
        ) {
            "Kindle EPUB is missing or has changed. Review the transfer before handing off a position."
        }
        return transfer
    }

    suspend fun send(book: Book, position: PhoneReading) =
        withContext(Dispatchers.IO) {
            require(position.bookId == book.id && position.revisionHash == book.currentHash) {
                "Position belongs to another EPUB revision"
            }
            require(xpointerSpineIndex(position.xpointer, Int.MAX_VALUE) != null) {
                "This page cannot yet be translated to KOReader. Try a text page; no approximate position will be sent."
            }
            val settings = app.deviceSettings
            val config = settings.load()
            SftpDevice(config, settings.credential()).use { remote ->
                val transfer = verifiedBook(book, remote)
                val expectedKindle =
                    ReadingRetriever.read(remote, transfer.remotePath)?.first?.position.orEmpty()
                val nonce =
                    UUID.nameUUIDFromBytes(
                            (config.id +
                                    book.id +
                                    book.currentHash +
                                    position.xpointer +
                                    expectedKindle)
                                .toByteArray()
                        )
                        .toString()
                val filename = ".folio-reading-$nonce.json"
                val temporary = ".folio-${UUID.randomUUID()}.part"
                val source = File(app.cacheDir, "reading-${UUID.randomUUID()}.json")
                if (remote.exists(filename)) {
                    val existing =
                        remote
                            .read(filename)
                            .use { input ->
                                java.io
                                    .ByteArrayOutputStream()
                                    .also { Epub.copyBounded(input, it, 16384) }
                                    .toString("UTF-8")
                            }
                            .let(::JSONObject)
                    require(
                        existing.optString("format") == "folio-reading" &&
                            existing.optString("position") == position.xpointer &&
                            existing.optString("revision") == book.currentHash
                    ) {
                        "Conflicting reading request; review it on KOReader"
                    }
                    return@withContext
                }

                val local = File(book.currentPath)
                require(Epub.sha256(local) == book.currentHash) {
                    "Phone EPUB has changed; reopen it before handoff"
                }
                source.writeText(
                    JSONObject()
                        .put("format", "folio-reading")
                        .put("version", 1)
                        .put("id", nonce)
                        .put("createdAt", System.currentTimeMillis())
                        .put("path", "${config.directory.trimEnd('/')}/${transfer.remotePath}")
                        .put("digest", koreaderDigest(local))
                        .put("revision", book.currentHash)
                        .put("position", position.xpointer)
                        .put("expectedKindle", expectedKindle)
                        .toString()
                )
                try {
                    remote.upload(temporary, source) { _, _ -> }
                    require(remote.read(temporary).use { Epub.hash(it) } == Epub.sha256(source)) {
                        "Position request verification failed"
                    }
                    remote.publish(temporary, filename)
                    require(remote.read(filename).use { Epub.hash(it) } == Epub.sha256(source)) {
                        "Published position request verification failed"
                    }
                } finally {
                    source.delete()
                    runCatching { remote.removeTemporary(temporary) }
                }
            }
        }

    suspend fun retrieve(book: Book): ReadingSnapshot =
        withContext(Dispatchers.IO) {
            val config = app.deviceSettings.load()
            SftpDevice(config, app.deviceSettings.credential()).use { remote ->
                val record = verifiedBook(book, remote)
                val data =
                    ReadingRetriever.read(remote, record.remotePath)
                        ?: error(
                            "Close this book in KOReader to save its position, then retrieve again"
                        )
                val (parsed, hash) = data
                val snapshot =
                    ReadingSnapshot(
                        book.id,
                        config.id,
                        parsed.percent,
                        parsed.position,
                        parsed.status,
                        parsed.rating,
                        System.currentTimeMillis(),
                        ReadingRetriever.sidecar(record.remotePath),
                        hash,
                        book.currentHash,
                    )
                require(snapshot.position.isNotBlank()) {
                    "KOReader has no saved reading position for this EPUB"
                }
                dao.reading(snapshot)
                snapshot
            }
        }
}
