package dev.sadking.folio

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sadking.folio.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    @Test
    fun persistenceAndDuplicateLookupSurviveReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "test-${java.util.UUID.randomUUID()}.db"
        val b =
            Book(
                "id",
                "Orchard",
                "[\"Alex\"]",
                originalPath = "original.epub",
                currentPath = "original.epub",
                originalHash = "hash",
                currentHash = "hash",
            )
        val first = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            first.library().insert(b)
        } finally {
            first.close()
        }
        val reopened = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            assertEquals(b, reopened.library().duplicate("hash"))
        } finally {
            reopened.close()
        }
        context.deleteDatabase(name)
    }
}
