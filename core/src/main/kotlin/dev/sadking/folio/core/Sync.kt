package dev.sadking.folio.core

import java.io.*
import java.util.UUID

data class RemoteEntry(val path: String, val size: Long)

interface DeviceFiles : AutoCloseable {
    fun inventory(): List<RemoteEntry>

    fun exists(path: String): Boolean

    fun read(path: String): InputStream

    fun upload(path: String, source: File, progress: (Long, Long) -> Unit)

    /** Destination must not exist. Implementations must not intentionally overwrite it. */
    fun publish(temporary: String, destination: String)

    fun removeTemporary(path: String)
}

data class SyncBook(
    val id: String,
    val title: String,
    val file: File,
    val hash: String,
    val previousHash: String? = null,
    val previousPath: String? = null,
)

enum class Action {
    UPLOAD,
    PRESENT,
    CONFLICT,
}

data class SyncItem(val book: SyncBook, val path: String, val action: Action, val reason: String)

object SyncPlanner {
    /** Null means whole library; an explicitly empty collection always means no actions. */
    fun scope(plan: List<SyncItem>, selected: Set<String>?): List<SyncItem> =
        if (selected == null) plan else plan.filter { it.book.id in selected }

    fun path(id: String): String {
        UUID.fromString(id)
        return "$id.epub"
    }

    fun plan(books: List<SyncBook>, remote: DeviceFiles): List<SyncItem> {
        val inventory = remote.inventory()
        val hashes = mutableMapOf<String, String>()
        fun hash(path: String): String =
            hashes.getOrPut(path) { remote.read(path).use { Epub.hash(it) } }
        return books.map { book ->
            val path = book.previousPath ?: path(book.id)
            if (book.previousHash != null && book.previousHash != book.hash) {
                SyncItem(
                    book,
                    path,
                    Action.CONFLICT,
                    "Edited since transfer. Replacement requires a reading-data preservation workflow.",
                )
            } else if (!remote.exists(path)) {
                val identical = inventory.firstOrNull {
                    it.size == book.file.length() && hash(it.path) == book.hash
                }
                if (identical != null)
                    SyncItem(book, identical.path, Action.PRESENT, "Identical EPUB found on Kindle")
                else SyncItem(book, path, Action.UPLOAD, "Missing on Kindle")
            } else {
                val remoteHash = hash(path)
                if (remoteHash == book.hash)
                    SyncItem(book, path, Action.PRESENT, "Verified on Kindle")
                else
                    SyncItem(
                        book,
                        path,
                        Action.CONFLICT,
                        "Different content at destination. Nothing will be overwritten.",
                    )
            }
        }
    }
}

/** Journal is persisted before transfer. A retry reconciles destination bytes first. */
class SyncEngine(private val remote: DeviceFiles) {
    fun send(
        item: SyncItem,
        journal: (String) -> Unit,
        progress: (Long, Long) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
    ) {
        checkCancelled()
        require(item.action == Action.UPLOAD)
        require(
            item.book.file.inputStream().use { checkedHash(it, checkCancelled) } == item.book.hash
        ) {
            "Local revision changed; preview again"
        }
        val path = item.path
        if (remote.exists(path)) {
            check(remote.read(path).use { checkedHash(it, checkCancelled) } == item.book.hash) {
                "Remote conflict; preview again"
            }
            journal("COMPLETE")
            return
        }
        val temp = ".folio-${UUID.randomUUID()}.part"
        journal("TRANSFERRING")
        try {
            remote.upload(temp, item.book.file) { sent, total ->
                checkCancelled()
                progress(sent, total)
            }
            checkCancelled()
            journal("VERIFYING")
            check(
                remote.read(temp).use { input -> checkedHash(input, checkCancelled) } ==
                    item.book.hash
            ) {
                "Transfer verification failed"
            }
            checkCancelled()
            check(!remote.exists(path)) { "Destination appeared during transfer; preview again" }
            journal("PUBLISHING")
            remote.publish(temp, path)
            check(remote.read(path).use { Epub.hash(it) } == item.book.hash) {
                "Published file verification failed"
            }
            journal("COMPLETE")
        } catch (e: Exception) {
            runCatching { remote.removeTemporary(temp) }
            journal("FAILED")
            throw e
        }
    }
}

private fun checkedHash(input: InputStream, checkCancelled: () -> Unit): String =
    Epub.hash(
        object : FilterInputStream(input) {
            override fun read(): Int {
                checkCancelled()
                return super.read()
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                checkCancelled()
                return super.read(bytes, offset, length)
            }
        }
    )

/**
 * Ordered, independently testable batch. Publication finishes before cancellation is observed
 * again.
 */
object BatchTransfer {
    fun run(
        items: List<SyncItem>,
        remote: DeviceFiles,
        currentHash: (String) -> String?,
        checkCancelled: () -> Unit = {},
        onStart: (Int) -> Unit = {},
        onState: (Int, String) -> Unit = { _, _ -> },
        onProgress: (Int, Long, Long) -> Unit = { _, _, _ -> },
        onComplete: (Int) -> Unit = {},
    ) {
        items.forEachIndexed { index, item ->
            checkCancelled()
            check(currentHash(item.book.id) == item.book.hash) {
                "Book changed since queued; cancel this job and preview again"
            }
            onStart(index)
            SyncEngine(remote)
                .send(
                    item,
                    { onState(index, it) },
                    { sent, total -> onProgress(index, sent, total) },
                    checkCancelled,
                )
            onComplete(index)
        }
    }
}
