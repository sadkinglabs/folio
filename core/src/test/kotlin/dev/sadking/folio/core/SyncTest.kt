package dev.sadking.folio.core

import java.io.*
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class FakeDevice : DeviceFiles {
    val files = mutableMapOf<String, ByteArray>()
    var interrupt = false
    var corrupt = false
    var uploads = 0
    var failAfterPublish = false

    override fun inventory() =
        files
            .filterKeys { it.endsWith(".epub") }
            .map { RemoteEntry(it.key, it.value.size.toLong()) }

    override fun exists(path: String) = path in files

    override fun read(path: String) = (files[path] ?: error("Not found")).inputStream()

    override fun upload(path: String, source: File, progress: (Long, Long) -> Unit) {
        uploads++
        val bytes = source.readBytes()
        files[path] = if (corrupt) byteArrayOf(9) else bytes
        if (interrupt) {
            files[path] = bytes.take(1).toByteArray()
            throw IOException("Disconnected")
        }
        progress(bytes.size.toLong(), bytes.size.toLong())
    }

    override fun publish(temporary: String, destination: String) {
        check(!exists(destination))
        files[destination] = files.remove(temporary)!!
        if (failAfterPublish) throw IOException("Disconnect after rename")
    }

    override fun removeTemporary(path: String) {
        files.remove(path)
    }

    override fun close() = Unit
}

class SyncTest {
    private fun book(): SyncBook {
        val file =
            Files.createTempFile("sync-test", ".epub").toFile().apply {
                writeText("EPUB bytes")
                deleteOnExit()
            }
        return SyncBook(UUID.randomUUID().toString(), "A book", file, Epub.sha256(file))
    }

    @Test
    fun cancellationDuringUploadCleansTemporaryFile() {
        val d = FakeDevice()
        val item = SyncPlanner.plan(listOf(book()), d).single()
        var cancel = false
        assertTrue(
            runCatching {
                    SyncEngine(d)
                        .send(
                            item,
                            {},
                            { _, _ -> cancel = true },
                            { if (cancel) throw java.util.concurrent.CancellationException() },
                        )
                }
                .isFailure
        )
        assertTrue(d.files.isEmpty())
    }

    @Test
    fun cancellationDuringPublicationFinishesCurrentBookAndStopsNext() {
        val d = FakeDevice()
        val books = listOf(book(), book())
        val items = SyncPlanner.plan(books, d)
        var cancel = false
        val completed = mutableListOf<Int>()
        assertTrue(
            runCatching {
                    BatchTransfer.run(
                        items,
                        d,
                        { id -> books.first { it.id == id }.hash },
                        checkCancelled = {
                            if (cancel) throw java.util.concurrent.CancellationException()
                        },
                        onState = { _, state -> if (state == "PUBLISHING") cancel = true },
                        onComplete = completed::add,
                    )
                }
                .isFailure
        )
        assertEquals(listOf(0), completed)
        assertEquals(1, d.uploads)
        assertTrue(d.exists(items[0].path))
        assertFalse(d.exists(items[1].path))
        BatchTransfer.run(items, d, { id -> books.first { it.id == id }.hash })
        assertEquals(2, d.uploads)
    }

    @Test
    fun queuedRevisionChangeStopsBeforeUpload() {
        val d = FakeDevice()
        val item = SyncPlanner.plan(listOf(book()), d).single()
        assertTrue(runCatching { BatchTransfer.run(listOf(item), d, { "changed" }) }.isFailure)
        assertEquals(0, d.uploads)
    }

    @Test
    fun comparisonReadsEachCandidateOnlyOnce() {
        val d = FakeDevice()
        val b = book()
        d.files["old.epub"] = b.file.readBytes()
        var reads = 0
        val remote =
            object : DeviceFiles by d {
                override fun read(path: String): InputStream {
                    reads++
                    return d.read(path)
                }
            }
        val other = book().copy(hash = "missing-hash")
        SyncPlanner.plan(listOf(other, b), remote)
        assertEquals(1, reads)
    }

    @Test
    fun newBookUploadsAndVerifies() {
        val d = FakeDevice()
        val b = book()
        val item = SyncPlanner.plan(listOf(b), d).single()
        val states = mutableListOf<String>()
        SyncEngine(d).send(item, states::add)
        assertArrayEquals(b.file.readBytes(), d.files[item.path])
        assertEquals(listOf("TRANSFERRING", "VERIFYING", "PUBLISHING", "COMPLETE"), states)
        assertEquals(Action.PRESENT, SyncPlanner.plan(listOf(b), d).single().action)
    }

    @Test
    fun repeatedSendIsIdempotent() {
        val d = FakeDevice()
        val item = SyncPlanner.plan(listOf(book()), d).single()
        val engine = SyncEngine(d)
        engine.send(item, {})
        engine.send(item, {})
        assertEquals(1, d.uploads)
    }

    @Test
    fun detectsExistingBookWithUnrelatedFilename() {
        val d = FakeDevice()
        val b = book()
        d.files["Author/old-name.epub"] = b.file.readBytes()
        val item = SyncPlanner.plan(listOf(b), d).single()
        assertEquals(Action.PRESENT, item.action)
        assertEquals("Author/old-name.epub", item.path)
    }

    @Test
    fun interruptionLeavesNoPublishedBookAndRetryWorks() {
        val d = FakeDevice()
        val item = SyncPlanner.plan(listOf(book()), d).single()
        d.interrupt = true
        try {
            SyncEngine(d).send(item, {})
            fail()
        } catch (_: IOException) {}
        assertFalse(d.exists(item.path))
        assertTrue(d.files.isEmpty())
        d.interrupt = false
        SyncEngine(d).send(item, {})
        assertTrue(d.exists(item.path))
    }

    @Test
    fun corruptionDoesNotPublish() {
        val d = FakeDevice()
        val item = SyncPlanner.plan(listOf(book()), d).single()
        d.corrupt = true
        try {
            SyncEngine(d).send(item, {})
            fail()
        } catch (_: IllegalStateException) {}
        assertTrue(d.files.isEmpty())
    }

    @Test
    fun changedBookIsConflictEvenIfDeviceFileMissing() {
        val d = FakeDevice()
        val b = book().copy(previousHash = "old")
        assertEquals(Action.CONFLICT, SyncPlanner.plan(listOf(b), d).single().action)
    }

    @Test
    fun conflictDoesNotDeleteOrOverwrite() {
        val d = FakeDevice()
        val b = book()
        d.files[SyncPlanner.path(b.id)] = byteArrayOf(5)
        val item = SyncPlanner.plan(listOf(b), d).single()
        assertEquals(Action.CONFLICT, item.action)
        assertArrayEquals(byteArrayOf(5), d.files[item.path])
    }

    @Test
    fun reconnectAfterPublishReconcilesWithoutSecondUpload() {
        val d = FakeDevice()
        val b = book()
        val item = SyncPlanner.plan(listOf(b), d).single()
        d.failAfterPublish = true
        try {
            SyncEngine(d).send(item, {})
            fail()
        } catch (_: IOException) {}
        d.failAfterPublish = false
        SyncEngine(d).send(item, {})
        assertEquals(1, d.uploads)
    }

    @Test
    fun changedLocalRevisionInvalidatesPreview() {
        val d = FakeDevice()
        val b = book()
        val item = SyncPlanner.plan(listOf(b), d).single()
        b.file.appendText("edit")
        try {
            SyncEngine(d).send(item, {})
            fail()
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, d.uploads)
    }

    @Test
    fun remoteRaceIsBlockedBeforeTransfer() {
        val d = FakeDevice()
        val b = book()
        val item = SyncPlanner.plan(listOf(b), d).single()
        d.files[item.path] = byteArrayOf(7)
        try {
            SyncEngine(d).send(item, {})
            fail()
        } catch (_: IllegalStateException) {}
        assertEquals(0, d.uploads)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidBookIdRejected() {
        SyncPlanner.path("../../malicious")
    }

    @Test
    fun collectionScopeTransfersOnlySelectedBooks() {
        val remote = FakeDevice()
        val first = book()
        val second = book()
        val plan = SyncPlanner.plan(listOf(first, second), remote)
        SyncPlanner.scope(plan, setOf(first.id)).forEach { SyncEngine(remote).send(it, {}) }
        assertEquals(1, remote.uploads)
        assertTrue(remote.files.containsKey(SyncPlanner.path(first.id)))
        assertFalse(remote.files.containsKey(SyncPlanner.path(second.id)))
    }

    @Test
    fun emptyCollectionNeverTriggersWholeLibraryTransfer() {
        val remote = FakeDevice()
        val plan = SyncPlanner.plan(listOf(book()), remote)
        SyncPlanner.scope(plan, emptySet()).forEach { SyncEngine(remote).send(it, {}) }
        assertEquals(0, remote.uploads)
        assertTrue(remote.files.isEmpty())
        assertEquals(plan, SyncPlanner.scope(plan, null))
    }
}
