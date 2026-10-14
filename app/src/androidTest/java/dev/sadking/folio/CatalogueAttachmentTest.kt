package dev.sadking.folio

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CatalogueAttachmentTest {
    @Test
    fun attachmentPreservesListsLinksAndDeviceHistoryAcrossRetry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val repo = LibraryRepository(context, db)
            val dao = db.library()
            val book =
                Book(
                    "file",
                    "Different EPUB title",
                    "[]",
                    originalPath = "original.epub",
                    currentPath = "revision.epub",
                    originalHash = "a",
                    currentHash = "b",
                    status = "Reading",
                )
            val wish =
                WishBook("catalogue", "hardcover:1", "Catalogue title", "[]", hardcoverId = 1)
            dao.insert(book)
            dao.insertWish(wish)
            dao.insertShelf(Shelf("list", "Next"))
            dao.addWishMembership(WishMembership(wish.id, "list"))
            val transfer = Transfer(book.id, "device", "books/file.epub", book.currentHash, 123)
            val reading =
                ReadingSnapshot(
                    book.id,
                    "device",
                    0.5,
                    "/body/p[2]",
                    "reading",
                    4,
                    1000,
                    "sidecar",
                    "hash",
                    book.currentHash,
                )
            dao.transfer(transfer)
            dao.reading(reading)
            repo.attach(wish.id, book.id)
            repo.attach(wish.id, book.id)
            assertEquals(book, dao.book(book.id))
            assertEquals(1, dao.wish(wish.id)!!.hardcoverId)
            assertEquals(book.id, dao.wish(wish.id)!!.linkedBookId)
            assertEquals(listOf(Membership(book.id, "list")), dao.allMemberships())
            assertEquals(listOf(reading), dao.allReading())
            assertEquals(listOf(transfer), dao.transfers("device"))
            dao.hardcoverObservation(HardcoverObservation(1, 1, 2, 42, 10, 1000))
            dao.hardcoverObservation(HardcoverObservation(2, 1, 3, 100, 10, 1001))
            assertEquals(2, dao.hardcoverObservations().first().size)
            val other = wish.copy(id = "other", catalogKey = "hardcover:2", hardcoverId = 2)
            dao.insertWish(other)
            try {
                repo.attach(other.id, book.id)
                fail("Claimed file attached twice")
            } catch (_: IllegalArgumentException) {}
            assertNull(dao.wish(other.id)!!.linkedBookId)
        } finally {
            db.close()
        }
    }
}
