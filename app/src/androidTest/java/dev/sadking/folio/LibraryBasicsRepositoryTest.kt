package dev.sadking.folio

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.core.Metadata
import dev.sadking.folio.data.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryBasicsRepositoryTest {
    private suspend fun fixture(block: suspend (LibraryRepository, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, "basics-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated =
            object : ContextWrapper(context) {
                override fun getFilesDir() = folder
            }
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            block(LibraryRepository(isolated, db), folder)
        } finally {
            db.close()
            folder.deleteRecursively()
        }
    }

    private suspend fun owned(repo: LibraryRepository): Book {
        val id = UUID.randomUUID().toString()
        val file =
            File(repo.root, "$id/original.epub").apply {
                parentFile!!.mkdirs()
                writeText("fixture managed file")
            }
        return Book(
                id,
                "Owned book",
                "[]",
                originalPath = file.absolutePath,
                currentPath = file.absolutePath,
                originalHash = id,
                currentHash = id,
            )
            .also { repo.dao.insert(it) }
    }

    @Test
    fun removeRestorePreservesOriginalIdentityFilesAnnotationsAndAssociation() = runBlocking {
        fixture { repo, _ ->
            val book = owned(repo)
            val wish =
                WishBook(
                    UUID.randomUUID().toString(),
                    "hardcover:1",
                    "Catalogue",
                    "[]",
                    linkedBookId = book.id,
                )
            repo.dao.insertWish(wish)
            val shelf = Shelf(UUID.randomUUID().toString(), "Shelf")
            repo.dao.insertShelf(shelf)
            repo.dao.addMembership(Membership(book.id, shelf.id))
            repo.dao.addWishMembership(WishMembership(wish.id, shelf.id))
            val note =
                BookAnnotation(
                    "note",
                    book.id,
                    "device",
                    "Text",
                    "Note",
                    "Chapter",
                    "2026-10-12",
                    "pointer",
                    "end",
                    "highlight",
                )
            repo.dao.annotation(note)
            assertEquals(1, repo.removeLocal(setOf(book.id)).first)
            assertTrue(repo.dao.allBooks().isEmpty())
            assertTrue(repo.dao.allWishes().isEmpty())
            assertTrue(repo.dao.allAnnotations().isEmpty())
            assertTrue(File(book.originalPath).isFile)
            repo.restoreRemoved(book.id)
            assertEquals(book, repo.dao.allBooks().single())
            assertEquals(wish, repo.dao.allWishes().single())
            assertEquals(note, repo.dao.allAnnotations().single())
            assertEquals(Membership(book.id, shelf.id), repo.dao.allMemberships().single())
        }
    }

    @Test
    fun permanentRemovalDeletesOnlyManagedTargetCopies() = runBlocking {
        fixture { repo, folder ->
            val removed = owned(repo)
            val retained = owned(repo)
            val external = File(folder, "download.epub").apply { writeText("external source") }
            repo.removeLocal(setOf(removed.id))
            assertTrue(repo.purgeRemoved(removed.id))
            assertFalse(File(removed.originalPath).exists())
            assertTrue(File(retained.originalPath).isFile)
            assertTrue(external.isFile)
            assertNull(repo.dao.book(removed.id))
            assertTrue(repo.dao.allRemoved().isEmpty())
        }
    }

    @Test
    fun detachmentAndMixedBatchListRemovalRetainReaderData() = runBlocking {
        fixture { repo, _ ->
            val book = owned(repo)
            val wish =
                WishBook(
                    UUID.randomUUID().toString(),
                    "hardcover:1",
                    "Work",
                    "[]",
                    linkedBookId = book.id,
                )
            repo.dao.insertWish(wish)
            val shelf = Shelf(UUID.randomUUID().toString(), "Shelf")
            repo.dao.insertShelf(shelf)
            repo.manageBatch(setOf(book.id), shelf.id, true, status = "Reading")
            repo.detach(book.id)
            assertNull(repo.dao.wish(wish.id)!!.linkedBookId)
            assertTrue(File(book.currentPath).isFile)
            assertEquals(1, repo.dao.allWishMemberships().size)
            repo.manageBatch(setOf(book.id, wish.id), shelf.id, false, status = "Finished")
            assertTrue(repo.dao.allMemberships().isEmpty())
            assertTrue(repo.dao.allWishMemberships().isEmpty())
            assertEquals("Finished", repo.dao.book(book.id)!!.status)
            assertEquals("Finished", repo.dao.wish(wish.id)!!.status)
        }
    }

    @Test
    fun queuedTransferBlocksRemovalWithoutHidingFiles() = runBlocking {
        fixture { repo, _ ->
            val book = owned(repo)
            val job = SyncJob("job", "device", "PAUSED", 1, 1)
            repo.dao.insertJob(job)
            repo.dao.insertTasks(
                listOf(
                    SyncTask(
                        "task",
                        job.id,
                        0,
                        book.id,
                        book.title,
                        book.currentPath,
                        book.currentHash,
                        "remote.epub",
                        1,
                    )
                )
            )
            try {
                repo.removeLocal(setOf(book.id))
                fail("Queued book removed")
            } catch (_: IllegalArgumentException) {}
            assertTrue(repo.dao.allRemoved().isEmpty())
            assertTrue(File(book.currentPath).isFile)
        }
    }

    @Test
    fun offlineCreationAndMetadataEditingRetainStableId() = runBlocking {
        fixture { repo, _ ->
            val created =
                repo.saveCatalogue(
                    null,
                    Metadata("Offline", listOf("Author"), tags = listOf("History")),
                    null,
                )
            val changed =
                repo.saveCatalogue(
                    created.id,
                    Metadata("Corrected", listOf("Author"), series = "Series", seriesIndex = "1"),
                    null,
                )
            assertEquals(created.id, changed.id)
            assertTrue(changed.catalogKey.startsWith("manual:"))
            assertEquals("Series", changed.displayBook().series)
        }
    }
}
