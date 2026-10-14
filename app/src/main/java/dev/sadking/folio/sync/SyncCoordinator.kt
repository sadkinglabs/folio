package dev.sadking.folio.sync

import androidx.room.withTransaction
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
import dev.sadking.folio.device.SftpDevice
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Room owns durable jobs; the foreground service owns execution. Only user actions start/retry. */
class SyncCoordinator(private val app: FolioApplication) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = app.database.library()
    private val execution = Mutex()
    private val activeId = AtomicReference<String?>(null)
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val remoteSession = AtomicReference<DeviceFiles?>(null)
    private val recovered = scope.async {
        app.database.withTransaction {
            dao.recover()
            dao.recoverJobs(System.currentTimeMillis())
        }
    }

    suspend fun awaitRecovery() {
        recovered.await()
    }

    suspend fun enqueue(items: List<SyncItem>, deviceId: String): String = execution.withLock {
        awaitRecovery()
        require(
            items.isNotEmpty() && items.size <= 10000 && items.all { it.action == Action.UPLOAD }
        ) {
            "Choose missing books from a sync preview"
        }
        check(app.deviceSettings.load().id == deviceId) { "Pairing changed; preview again" }
        val id = UUID.randomUUID().toString()
        val tasks = items.mapIndexed { index, item ->
            val file = item.book.file
            require(
                file.isFile &&
                    file.length() in 1..Epub.MAX_FILE &&
                    file.canonicalFile.parentFile ==
                        File(app.repository.root, item.book.id).canonicalFile
            ) {
                "Queued library revision is unavailable"
            }
            SyncTask(
                UUID.randomUUID().toString(),
                id,
                index,
                item.book.id,
                item.book.title,
                file.absolutePath,
                item.book.hash,
                item.path,
                file.length(),
            )
        }
        app.database.withTransaction {
            check(dao.unfinishedJob() == null) { "Resume or cancel the unfinished sync job first" }
            dao.insertJob(SyncJob(id, deviceId, "QUEUED", tasks.size, tasks.sumOf { it.bytes }))
            dao.insertTasks(tasks)
        }
        id
    }

    suspend fun prepareResume(id: String) = execution.withLock {
        awaitRecovery()
        val job = dao.job(id) ?: error("Sync job no longer exists")
        require(job.state in listOf("PAUSED", "FAILED")) { "This job cannot be resumed" }
        check(app.deviceSettings.load().id == job.deviceId) {
            "Pairing changed; cancel the old job and preview again"
        }
        dao.updateJob(
            job.copy(state = "QUEUED", message = "", updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun startFailed(id: String) {
        val job = dao.job(id) ?: return
        if (job.state == "QUEUED")
            dao.updateJob(
                job.copy(
                    state = "PAUSED",
                    message =
                        "Android could not start the transfer. Return to Folio and tap Resume",
                    updatedAt = System.currentTimeMillis(),
                )
            )
    }

    suspend fun cancel(id: String) {
        awaitRecovery()
        cancelled.add(id)
        app.database.withTransaction {
            val job = dao.job(id) ?: return@withTransaction
            if (job.state !in listOf("COMPLETE", "CANCELLED")) {
                val state = if (activeId.get() == id) "CANCELLING" else "CANCELLED"
                dao.updateJob(
                    job.copy(
                        state = state,
                        message = "Cancelling; completed files are retained",
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                if (state == "CANCELLED")
                    dao.tasks(id)
                        .filter { it.state != "COMPLETE" }
                        .forEach { dao.updateTask(it.copy(state = "CANCELLED")) }
            }
        }
    }

    fun disconnect() {
        scope.launch { runCatching { remoteSession.get()?.close() } }
    }

    suspend fun execute(id: String, onUpdate: (SyncJob) -> Unit) = execution.withLock {
        awaitRecovery()
        var job = dao.job(id) ?: return@withLock
        if (job.state != "QUEUED") return@withLock
        activeId.set(id)
        val context = currentCoroutineContext()
        val deadline = android.os.SystemClock.elapsedRealtime() + 30 * 60 * 1000L
        fun checkStop() {
            context.ensureActive()
            if (cancelled.contains(id))
                throw java.util.concurrent.CancellationException("Cancelled by user")
            if (android.os.SystemClock.elapsedRealtime() >= deadline) throw SyncWindowExpired()
        }
        suspend fun update(value: SyncJob) {
            job =
                value.copy(
                    state =
                        if (cancelled.contains(id) && value.state == "RUNNING") "CANCELLING"
                        else value.state,
                    updatedAt = System.currentTimeMillis(),
                )
            dao.updateJob(job)
            onUpdate(job)
        }
        var currentAttempt: Journal? = null
        var currentTask: SyncTask? = null
        try {
            app.repository.lock.withLock {
                val config = app.deviceSettings.load()
                check(config.id == job.deviceId) {
                    "Pairing changed; cancel this job and preview again"
                }
                val all = dao.tasks(id)
                val pending = all.filter { it.state != "COMPLETE" }
                var completedBytes = all.filter { it.state == "COMPLETE" }.sumOf { it.bytes }
                update(
                    job.copy(
                        state = "RUNNING",
                        completedBooks = all.count { it.state == "COMPLETE" },
                        bytesSent = completedBytes,
                        phase = "CONNECTING",
                        message = "Connecting to Kindle",
                    )
                )
                checkStop()
                SftpDevice(config, app.deviceSettings.credential()).use { remote ->
                    remoteSession.set(remote)
                    val items = pending.map { task ->
                        SyncItem(
                            SyncBook(task.bookId, task.title, File(task.sourcePath), task.hash),
                            task.remotePath,
                            Action.UPLOAD,
                            "Queued revision",
                        )
                    }
                    var lastProgress = 0L
                    BatchTransfer.run(
                        items,
                        remote,
                        currentHash = { book -> runBlocking { dao.book(book)?.currentHash } },
                        checkCancelled = ::checkStop,
                        onStart = { index ->
                            currentTask = pending[index]
                            currentAttempt =
                                Journal(
                                    UUID.randomUUID().toString(),
                                    pending[index].bookId,
                                    "PLANNED",
                                    config.id,
                                )
                            runBlocking {
                                dao.journal(currentAttempt!!)
                                dao.updateTask(pending[index].copy(state = "RUNNING"))
                                update(
                                    job.copy(
                                        currentTitle = pending[index].title,
                                        message =
                                            "Transferring book ${job.completedBooks+1} of ${job.totalBooks}",
                                    )
                                )
                            }
                        },
                        onState = { _, state ->
                            runBlocking {
                                val journal = currentAttempt!!
                                dao.journal(
                                    journal.copy(
                                        state =
                                            if (state == "FAILED" && cancelled.contains(id))
                                                "CANCELLED"
                                            else state
                                    )
                                )
                                update(job.copy(phase = state))
                            }
                        },
                        onProgress = { _, sent, _ ->
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastProgress >= 250) {
                                lastProgress = now
                                runBlocking { update(job.copy(bytesSent = completedBytes + sent)) }
                            }
                        },
                        onComplete = { index ->
                            runBlocking {
                                val task = pending[index]
                                completedBytes += task.bytes
                                app.database.withTransaction {
                                    dao.updateTask(task.copy(state = "COMPLETE"))
                                    dao.transfer(
                                        Transfer(
                                            task.bookId,
                                            config.id,
                                            task.remotePath,
                                            task.hash,
                                            System.currentTimeMillis(),
                                        )
                                    )
                                    update(
                                        job.copy(
                                            completedBooks = job.completedBooks + 1,
                                            bytesSent = completedBytes,
                                        )
                                    )
                                }
                                currentTask = null
                                currentAttempt = null
                            }
                        },
                    )
                }
                update(
                    job.copy(
                        state = "COMPLETE",
                        phase = "COMPLETE",
                        currentTitle = "",
                        message = "${job.completedBooks} books verified on Kindle",
                    )
                )
            }
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                val state =
                    when {
                        cancelled.contains(id) -> "CANCELLED"
                        !context.isActive || e is CancellationException || e is SyncWindowExpired ->
                            "PAUSED"
                        else -> "FAILED"
                    }
                val message =
                    when (state) {
                        "CANCELLED" -> "Cancelled. Already transferred books were retained"
                        "PAUSED" -> "Sync stopped; resume to verify and continue"
                        else -> syncError(e)
                    }
                currentTask?.let { dao.updateTask(it.copy(state = state)) }
                currentAttempt?.let {
                    dao.journal(
                        it.copy(
                            state = if (state == "PAUSED") "INTERRUPTED" else state,
                            message = message,
                        )
                    )
                }
                update(job.copy(state = state, message = message))
            }
        } finally {
            remoteSession.set(null)
            activeId.set(null)
            cancelled.remove(id)
        }
    }
}

private class SyncWindowExpired : RuntimeException()

internal fun syncError(e: Exception): String =
    when (e) {
        is com.jcraft.jsch.JSchException ->
            when {
                e.message.orEmpty().contains("HostKey", true) ->
                    "Kindle host key changed. Review pairing before retrying"
                e.message.orEmpty().contains("Auth fail", true) ->
                    "SSH login rejected. Check the selected login mode and KOReader settings"
                else ->
                    "Connection interrupted. Check Wi-Fi, Kindle address and SSH server, then retry"
            }
        is com.jcraft.jsch.SftpException ->
            "SFTP failed. Check connection, Kindle storage and folder permissions, then retry"
        is IllegalArgumentException,
        is IllegalStateException -> e.message?.take(200) ?: "Sync could not continue"
        else -> "Sync interrupted; check your connection and retry"
    }
