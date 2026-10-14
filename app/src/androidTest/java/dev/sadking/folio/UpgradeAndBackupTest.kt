package dev.sadking.folio

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sadking.folio.data.*
import java.io.File
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpgradeAndBackupTest {
    private val context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private fun epub(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries =
                mapOf(
                    "mimetype" to "application/epub+zip",
                    "META-INF/container.xml" to
                        """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                    "book.opf" to
                        """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Test Library Book</dc:title><dc:creator>Test Author</dc:creator><dc:identifier id="uid">urn:test:backup</dc:identifier></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>""",
                    "chapter.xhtml" to
                        "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>Chapter</body></html>",
                )
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test
    fun queueRecoveryRetainsCompletedTasksAndAllowsExplicitResume() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val dao = db.library()
            dao.insertJob(SyncJob("job", "device", "RUNNING", 2, 200, 1, 100))
            dao.insertTasks(
                listOf(
                    SyncTask(
                        "done",
                        "job",
                        0,
                        "book1",
                        "Book 1",
                        "/private/one.epub",
                        "hash1",
                        "one.epub",
                        100,
                        "COMPLETE",
                    ),
                    SyncTask(
                        "pending",
                        "job",
                        1,
                        "book2",
                        "Book 2",
                        "/private/two.epub",
                        "hash2",
                        "two.epub",
                        100,
                        "RUNNING",
                    ),
                )
            )
            dao.recoverJobs(1234)
            assertEquals("PAUSED", dao.job("job")!!.state)
            assertEquals(1, dao.job("job")!!.completedBooks)
            assertEquals("COMPLETE", dao.tasks("job").first().state)
            assertEquals("job", dao.unfinishedJob()!!.id)
            dao.updateJob(dao.job("job")!!.copy(state = "COMPLETE"))
            assertNull(dao.unfinishedJob())
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationPreservesVersionOneBooksAndShelves() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema =
            JSONObject(
                    context.assets
                        .open("dev.sadking.folio.data.LibraryDatabase/1.json")
                        .bufferedReader()
                        .readText()
                )
                .getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                db.execSQL(
                    e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName"))
                )
                val indexes = e.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indexes.length()) db.execSQL(
                    indexes
                        .getJSONObject(j)
                        .getString("createSql")
                        .replace("\${TABLE_NAME}", e.getString("tableName"))
                )
            }
            val values = android.content.ContentValues()
            val bookEntity = entities.getJSONObject(0)
            val fields = bookEntity.getJSONArray("fields")
            for (i in 0 until fields.length()) {
                val field = fields.getJSONObject(i)
                val column = field.getString("columnName")
                if (column == "coverPath") values.putNull(column)
                else if (field.getString("affinity") == "INTEGER") values.put(column, 1000L)
                else
                    values.put(
                        column,
                        when (column) {
                            "id" -> "old-book"
                            "title" -> "Preserved title"
                            "authors",
                            "tags",
                            "identifiers" -> "[]"
                            "status" -> "Unread"
                            else -> "old-value"
                        },
                    )
            }
            assertTrue(db.insertOrThrow("books", null, values) > 0)
            db.execSQL("INSERT INTO collections(id,name) VALUES ('shelf','Saved collection')")
            db.version = 1
        }
        val database =
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                )
                .build()
        try {
            assertEquals("Preserved title", database.library().allBooks().single().title)
            val s = database.library().allShelves().single()
            assertEquals("Saved collection", s.name)
            assertEquals("Manual", s.ruleField)
            assertFalse(s.syncEnabled)
            assertTrue(database.library().allReading().isEmpty())
            assertTrue(database.library().allStatistics().isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun backupRestoreIsAdditiveAndRepeatable() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val source = File(context.cacheDir, "backup-source-${UUID.randomUUID()}.epub")
        epub(source)
        val archive = File(context.cacheDir, "test-backup-${UUID.randomUUID()}.zip")
        val repo = LibraryRepository(context, database)
        var id: String? = null
        try {
            val book = repo.import(Uri.fromFile(source)).first
            id = book.id
            val shelf = Shelf(UUID.randomUUID().toString(), "Test shelf", "Tag", "History", true)
            repo.dao.insertShelf(shelf)
            repo.dao.addMembership(Membership(book.id, shelf.id))
            repo.dao.annotation(
                BookAnnotation(
                    "annotation",
                    book.id,
                    "device",
                    "Quote",
                    "Note",
                    "Chapter",
                    "2026-10-11",
                    "x",
                    "y",
                    "highlight",
                )
            )
            LibraryBackup(repo).export(archive)
            assertTrue(LibraryBackup(repo).restore(archive).startsWith("0 books restored"))
            assertEquals(1, repo.dao.allBooks().size)
            assertEquals(1, repo.dao.allAnnotations().size)
            assertEquals(1, repo.dao.allMemberships().size)
        } finally {
            database.close()
            id?.let { File(repo.root, it).deleteRecursively() }
            source.delete()
            archive.delete()
        }
    }

    @Test
    fun restoreIntoEmptyLibraryRetainsEpubAndReadingData() = runBlocking {
        val first = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val second = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        // Separate private Context directories simulate moving the archive to another installation.
        val exportContext = android.content.ContextWrapper(context)
        val isolated = File(context.cacheDir, "isolated-${UUID.randomUUID()}").apply { mkdir() }
        val restoreContext =
            object : android.content.ContextWrapper(context) {
                override fun getFilesDir() = isolated
            }
        val source = File(context.cacheDir, "source-${UUID.randomUUID()}.epub")
        epub(source)
        val archive = File(context.cacheDir, "backup-${UUID.randomUUID()}.zip")
        val exportRepo = LibraryRepository(exportContext, first)
        val restoreRepo = LibraryRepository(restoreContext, second)
        var id: String? = null
        try {
            val book = exportRepo.import(Uri.fromFile(source)).first
            id = book.id
            exportRepo.dao.insertShelf(
                Shelf(
                    UUID.randomUUID().toString(),
                    "Restored smart shelf",
                    "Status",
                    "Unread",
                    true,
                )
            )
            exportRepo.dao.reading(
                ReadingSnapshot(
                    book.id,
                    "device",
                    0.5,
                    "/body/p[2]",
                    "reading",
                    4,
                    1000,
                    "book.sdr/metadata.epub.lua",
                    "sidecar-hash",
                    book.currentHash,
                )
            )
            LibraryBackup(exportRepo).export(archive)
            assertTrue(LibraryBackup(restoreRepo).restore(archive).startsWith("1 books restored"))
            val restored = restoreRepo.dao.allBooks().single()
            assertArrayEquals(source.readBytes(), File(restored.originalPath).readBytes())
            assertFalse(restoreRepo.dao.allShelves().single().syncEnabled)
            assertEquals("/body/p[2]", restoreRepo.dao.allReading().single().position)
        } finally {
            first.close()
            second.close()
            id?.let { File(exportRepo.root, it).deleteRecursively() }
            isolated.deleteRecursively()
            source.delete()
            archive.delete()
        }
    }

    @Test
    fun wishlistPersistenceDeduplicationAndBackupWithoutAnEpub() = runBlocking {
        val first = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val second = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val isolated =
            File(context.cacheDir, "wishlist-restore-${UUID.randomUUID()}").apply { mkdir() }
        val restoreContext =
            object : android.content.ContextWrapper(context) {
                override fun getFilesDir() = isolated
            }
        val source = LibraryRepository(context, first)
        val destination = LibraryRepository(restoreContext, second)
        val archive = File(context.cacheDir, "wishlist-${UUID.randomUUID()}.zip")
        val wish =
            WishBook(
                UUID.randomUUID().toString(),
                "/works/OL1W",
                "Future read",
                encode(listOf("Author")),
                hardcoverId = 44,
                hardcoverTitle = "Future read",
                hardcoverSynced = true,
            )
        try {
            assertTrue(source.dao.insertWish(wish) > 0)
            assertEquals(-1L, source.dao.insertWish(wish.copy(id = UUID.randomUUID().toString())))
            val shelf = Shelf(UUID.randomUUID().toString(), "Future reads")
            source.dao.insertShelf(shelf)
            source.dao.addWishMembership(WishMembership(wish.id, shelf.id))
            LibraryBackup(source).export(archive)
            LibraryBackup(destination).restore(archive)
            LibraryBackup(destination).restore(archive)
            assertTrue(destination.dao.allBooks().isEmpty())
            val restored = destination.dao.allWishes().single()
            assertEquals(wish.title, restored.title)
            assertEquals(wish.hardcoverId, restored.hardcoverId)
            assertFalse(restored.hardcoverSynced)
            assertEquals(
                WishMembership(wish.id, shelf.id),
                destination.dao.allWishMemberships().single(),
            )
        } finally {
            first.close()
            second.close()
            isolated.deleteRecursively()
            archive.delete()
        }
    }
}
