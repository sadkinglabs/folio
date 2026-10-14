package dev.sadking.folio

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReaderPersistenceTest {
    @Test
    fun revisionAnchorsAndBookmarksSurviveReopenAndPermanentRemovalCascades() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<FolioApplication>()
        val name = "reader-${java.util.UUID.randomUUID()}.db"
        val book =
            Book(
                "book",
                "Fixture",
                "[]",
                originalPath = "fixture.epub",
                currentPath = "fixture.epub",
                originalHash = "original",
                currentHash = "edited",
            )
        val locator =
            "{\"href\":\"chapter.xhtml\",\"type\":\"application/xhtml+xml\",\"locations\":{\"progression\":0.25}}"
        val db = Room.databaseBuilder(app, LibraryDatabase::class.java, name).build()
        db.library().insert(book)
        db.library()
            .savePhoneReading(
                PhoneReading(book.id, "original", locator, "/body/DocFragment/body/p", 0.25, 100)
            )
        db.library()
            .savePhoneReading(
                PhoneReading(book.id, "edited", locator, "/body/DocFragment/body/p[2]", 0.5, 200)
            )
        db.library()
            .addPhoneBookmark(
                PhoneBookmark("bookmark", book.id, "original", locator, "Chapter", 100)
            )
        db.close()
        val reopened = Room.databaseBuilder(app, LibraryDatabase::class.java, name).build()
        try {
            assertEquals(
                0.25,
                reopened.library().phoneReading(book.id, "original")!!.percent!!,
                0.0,
            )
            assertEquals(0.5, reopened.library().phoneReading(book.id, "edited")!!.percent!!, 0.0)
            assertEquals(1, reopened.library().phoneBookmarks(book.id, "original").first().size)
            assertTrue(reopened.library().phoneBookmarks(book.id, "edited").first().isEmpty())
            reopened.library().markRemoved(RemovedEntry(book.id, book.id, book.title, "EPUB", 300))
            assertNotNull(reopened.library().phoneReading(book.id, "original"))
            reopened.library().deleteLocalBook(book.id)
            assertNull(reopened.library().phoneReading(book.id, "original"))
            assertTrue(reopened.library().phoneBookmarks(book.id, "original").first().isEmpty())
        } finally {
            reopened.close()
            app.deleteDatabase(name)
        }
    }
}
