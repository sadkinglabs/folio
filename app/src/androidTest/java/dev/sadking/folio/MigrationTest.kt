package dev.sadking.folio

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sadking.folio.data.ALL_MIGRATIONS
import dev.sadking.folio.data.DeviceProfile
import dev.sadking.folio.data.HardcoverBaselineRow
import dev.sadking.folio.data.HardcoverMatchRow
import dev.sadking.folio.data.HardcoverQuestionRow
import dev.sadking.folio.data.LibraryDatabase
import dev.sadking.folio.data.MIGRATION_10_11
import dev.sadking.folio.data.MIGRATION_11_12
import dev.sadking.folio.data.MIGRATION_7_8
import dev.sadking.folio.data.MIGRATION_8_9
import dev.sadking.folio.data.MIGRATION_9_10
import dev.sadking.folio.data.SyncBaseline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrades from every exported schema to the current one and validates the result against the
 * exported current schema. Users can arrive from any released version, not only version 1.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            LibraryDatabase::class.java,
        )

    private val current = ALL_MIGRATIONS.last().endVersion

    @Test
    fun migrationChainIsContiguous() {
        ALL_MIGRATIONS.forEachIndexed { index, migration ->
            assertEquals(index + 1, migration.startVersion)
            assertEquals(index + 2, migration.endVersion)
        }
    }

    @Test
    fun everyReleasedSchemaMigratesToCurrent() {
        for (start in 1 until current) {
            val name = "migration-from-$start.db"
            helper.createDatabase(name, start).close()
            helper
                .runMigrationsAndValidate(
                    name,
                    current,
                    true,
                    *ALL_MIGRATIONS.filter { it.startVersion >= start }.toTypedArray(),
                )
                .close()
        }
    }

    @Test
    fun folioLinkUpgradeKeepsReadingDataAndAcceptsReports() {
        val name = "folio-link.db"
        helper.createDatabase(name, 7).use {
            it.execSQL(
                "INSERT INTO reading VALUES ('book', 'kindle', 0.4, 'xp', 'reading', 0, 1, 'a.sdr', 'h', 'd')"
            )
        }
        helper.runMigrationsAndValidate(name, 8, true, MIGRATION_7_8).use { db ->
            db.query("SELECT percent, position FROM reading WHERE bookId = 'book'").use {
                assertTrue(it.moveToFirst())
                assertEquals(0.4, it.getDouble(0), 0.0)
                assertEquals("xp", it.getString(1))
            }
            db.execSQL(
                "INSERT INTO kosync_progress VALUES ('doc', 'kobo-1', 'Kobo', '/body/p[1]', 0.5, 10, 10000)"
            )
            db.query("SELECT COUNT(*) FROM kosync_progress").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
        }
    }

    private val bookAtVersion8 =
        "INSERT INTO books (id, title, authors, description, publisher, language, identifiers, series, seriesIndex, tags, originalPath, currentPath, coverPath, originalHash, currentHash, addedAt, status) VALUES ('book', 'Kept', '[]', '', '', '', '[]', '', '', '[]', 'A/Kept.epub', 'A/Kept.epub', NULL, 'h', 'h', 1, 'Reading')"

    @Test
    fun schema9MarksExistingBooksAsEpubAndKeepsReadingData() {
        val name = "formats.db"
        helper.createDatabase(name, 8).use {
            it.execSQL(bookAtVersion8)
            it.execSQL(
                "INSERT INTO reading VALUES ('book', 'kindle', 0.4, 'xp', 'reading', 0, 1, 'a.sdr', 'h', 'd')"
            )
            it.execSQL(
                "INSERT INTO annotations VALUES ('note', 'book', 'kindle', 'text', 'my note', 'Ch 1', '2026-10-01', 'p0', 'p1', 'highlight')"
            )
            it.execSQL(
                "INSERT INTO kosync_progress VALUES ('doc', 'kindle', 'Kindle', '/body/p[1]', 0.5, 10, 10000)"
            )
        }
        helper.runMigrationsAndValidate(name, 9, true, MIGRATION_8_9).use { db ->
            db.query("SELECT format, title, status FROM books WHERE id = 'book'").use {
                assertTrue(it.moveToFirst())
                assertEquals("epub", it.getString(0))
                assertEquals("Kept", it.getString(1))
                assertEquals("Reading", it.getString(2))
            }
            db.query("SELECT percent FROM reading WHERE bookId = 'book'").use {
                assertTrue(it.moveToFirst())
                assertEquals(0.4, it.getDouble(0), 0.0)
            }
            for (table in listOf("annotations", "kosync_progress")) {
                db.query("SELECT COUNT(*) FROM $table").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(table, 1, it.getInt(0))
                }
            }
            db.execSQL(
                "INSERT INTO sync_baseline VALUES ('book', 'kindle', 'A/Kept.epub', 'h', 'Reading', 1, '[\"note\"]', '[\"Shelf\"]', 2)"
            )
            db.execSQL(
                "INSERT INTO sync_baseline VALUES ('book', 'kobo', 'A/Kept.epub', 'h', 'Reading', 1, '[]', '[]', 2)"
            )
            db.query("SELECT COUNT(*) FROM sync_baseline WHERE bookId = 'book'").use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0))
            }
        }
    }

    @Test
    fun schema10GivesBooksAStatusTimeAndKeepsEverything() {
        val name = "status-at.db"
        helper.createDatabase(name, 9).use {
            it.execSQL(
                "INSERT INTO books (id, title, authors, description, publisher, language, identifiers, series, seriesIndex, tags, originalPath, currentPath, coverPath, originalHash, currentHash, addedAt, status, format) VALUES ('book', 'Kept', '[]', '', '', '', '[]', '', '', '[]', 'A/Kept.pdf', 'A/Kept.pdf', NULL, 'h', 'h', 1, 'Finished', 'pdf')"
            )
            it.execSQL(
                "INSERT INTO annotations VALUES ('note', 'book', 'kindle', 'text', 'my note', 'Ch 1', '2026-10-01', 'p0', 'p1', 'highlight')"
            )
            it.execSQL(
                "INSERT INTO sync_baseline VALUES ('book', 'kindle', 'Librillo/A/Kept.pdf', 'h', 'finished', 1, '[\"note\"]', '[]', 2)"
            )
        }
        helper.runMigrationsAndValidate(name, 10, true, MIGRATION_9_10).use { db ->
            db.query("SELECT statusAt, status, format, title FROM books WHERE id = 'book'").use {
                assertTrue(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
                assertEquals("Finished", it.getString(1))
                assertEquals("pdf", it.getString(2))
                assertEquals("Kept", it.getString(3))
            }
            for (table in listOf("annotations", "sync_baseline")) {
                db.query("SELECT COUNT(*) FROM $table").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(table, 1, it.getInt(0))
                }
            }
            db.execSQL("UPDATE books SET status = 'Reading', statusAt = 5 WHERE id = 'book'")
            db.query("SELECT statusAt FROM books WHERE id = 'book'").use {
                assertTrue(it.moveToFirst())
                assertEquals(5L, it.getLong(0))
            }
        }
    }

    @Test
    fun schema11AddsHardcoverTablesAndKeepsEverything() {
        val name = "hardcover-sync.db"
        helper.createDatabase(name, 10).use {
            it.execSQL(
                "INSERT INTO books (id, title, authors, description, publisher, language, identifiers, series, seriesIndex, tags, originalPath, currentPath, coverPath, originalHash, currentHash, addedAt, status, format, statusAt) VALUES ('book', 'Kept', '[]', '', '', '', '[]', '', '', '[]', 'A/Kept.epub', 'A/Kept.epub', NULL, 'h', 'h', 1, 'Reading', 'epub', 7)"
            )
            it.execSQL(
                "INSERT INTO wishlist (id, catalogKey, title, authors, coverPath, hardcoverId, hardcoverTitle, hardcoverSynced, addedAt, linkedBookId, details, status) VALUES ('wish', 'hardcover:42', 'Kept entry', '[]', NULL, 42, 'Kept entry', 1, 1, 'book', '{}', 'Reading')"
            )
            it.execSQL(
                "INSERT INTO annotations VALUES ('note', 'book', 'kindle', 'text', 'my note', 'Ch 1', '2026-10-01', 'p0', 'p1', 'highlight')"
            )
            it.execSQL(
                "INSERT INTO sync_baseline VALUES ('book', 'kindle', 'Librillo/A/Kept.epub', 'h', 'reading', 7, '[\"note\"]', '[]', 2)"
            )
            it.execSQL("INSERT INTO hardcover_observations VALUES (1, 42, 2, 10, 5, 3)")
        }
        helper.runMigrationsAndValidate(name, 11, true, MIGRATION_10_11).use { db ->
            db.query("SELECT status, statusAt, title FROM books WHERE id = 'book'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Reading", it.getString(0))
                assertEquals(7L, it.getLong(1))
                assertEquals("Kept", it.getString(2))
            }
            db.query("SELECT status, statusAt, hardcoverId, linkedBookId FROM wishlist").use {
                assertTrue(it.moveToFirst())
                assertEquals("Reading", it.getString(0))
                assertEquals(0L, it.getLong(1))
                assertEquals(42, it.getInt(2))
                assertEquals("book", it.getString(3))
            }
            for (table in listOf("annotations", "sync_baseline", "hardcover_observations")) {
                db.query("SELECT COUNT(*) FROM $table").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(table, 1, it.getInt(0))
                }
            }
            db.execSQL("INSERT INTO hardcover_baseline VALUES (1, 42, 2, 7, 8)")
            db.execSQL("INSERT INTO hardcover_baseline VALUES (2, 42, 3, 7, 8)")
            db.execSQL("INSERT INTO hardcover_matches VALUES ('other', 'UNSURE', '[]', 'key', 8)")
            db.execSQL(
                "INSERT INTO hardcover_questions VALUES (1, 42, 'book', 'Kept', 3, 'Reading', 8)"
            )
            for (duplicate in
                listOf(
                    "INSERT INTO hardcover_baseline VALUES (1, 42, 3, 9, 9)",
                    "INSERT INTO hardcover_matches VALUES ('other', 'NOT_FOUND', '[]', 'key', 9)",
                    "INSERT INTO hardcover_questions VALUES (1, 42, 'book', 'Kept', 1, 'Paused', 9)",
                )) {
                assertThrows(duplicate, SQLiteConstraintException::class.java) {
                    db.execSQL(duplicate)
                }
            }
            db.query("SELECT COUNT(*) FROM hardcover_baseline").use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0))
            }
        }
    }

    @Test
    fun schema12AddsDeviceProfilesAndKeepsEveryPosition() {
        val name = "device-profiles.db"
        helper.createDatabase(name, 11).use {
            it.execSQL(
                "INSERT INTO books (id, title, authors, description, publisher, language, identifiers, series, seriesIndex, tags, originalPath, currentPath, coverPath, originalHash, currentHash, addedAt, status, format, statusAt) VALUES ('book', 'Hyperion', '[]', '', '', '', '[]', '', '', '[]', 'A/H.epub', 'A/H.epub', NULL, 'h', 'h', 1, 'Reading', 'epub', 7)"
            )
            it.execSQL(
                "INSERT INTO reading VALUES ('book', 'saved-ssh', 0.46, '/p', 'reading', 0, 5, 'a.sdr', 'h', 'h')"
            )
            it.execSQL(
                "INSERT INTO kosync_progress VALUES ('doc', 'kindle-1', 'KindlePaperWhite5SE', '/body/p', 0.5, 9, 9000)"
            )
            it.execSQL(
                "INSERT INTO annotations VALUES ('note', 'book', 'kindle-1', 'text', 'my note', 'Ch 1', '2026-10-01', 'p0', 'p1', 'highlight')"
            )
        }
        helper.runMigrationsAndValidate(name, 12, true, MIGRATION_11_12).use { db ->
            for (table in listOf("books", "reading", "kosync_progress", "annotations")) {
                db.query("SELECT COUNT(*) FROM $table").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(table, 1, it.getInt(0))
                }
            }
            db.query("SELECT percent FROM reading WHERE deviceId = 'saved-ssh'").use {
                assertTrue(it.moveToFirst())
                assertEquals(0.46, it.getDouble(0), 0.0)
            }
            db.execSQL(
                "INSERT INTO device_profiles (deviceKey, updatedAt) VALUES ('ssh:saved-ssh', 10)"
            )
            db.query("SELECT displayName, mergedInto, hiddenAt FROM device_profiles").use {
                assertTrue(it.moveToFirst())
                assertEquals("", it.getString(0))
                assertTrue(it.isNull(1))
                assertEquals(0L, it.getLong(2))
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "INSERT INTO device_profiles VALUES ('ssh:saved-ssh', 'Again', NULL, 0, 11)"
                )
            }
        }
    }

    @Test
    fun roomOpensSchema12AndSavesDeviceProfiles() = runBlocking {
        val name = "device-profiles-dao.db"
        helper.createDatabase(name, 11).close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database =
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
        try {
            val dao = database.library()
            dao.saveDeviceProfile(DeviceProfile("ssh:old", mergedInto = "ereader:k", updatedAt = 1))
            dao.saveDeviceProfile(DeviceProfile("ssh:old", hiddenAt = 5, updatedAt = 2))
            val saved = dao.deviceProfile("ssh:old")!!
            assertNull(saved.mergedInto)
            assertEquals(5L, saved.hiddenAt)
            assertEquals(1, dao.allDeviceProfiles().size)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun roomOpensSchema11AndKeepsHardcoverRows() = runBlocking {
        val name = "hardcover-dao.db"
        helper.createDatabase(name, 10).close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database =
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
        try {
            val hardcover = database.hardcover()
            hardcover.saveBaseline(HardcoverBaselineRow(1, 42, 2, 7, 8))
            hardcover.saveBaseline(HardcoverBaselineRow(1, 42, 3, 9, 10))
            assertEquals(3, hardcover.baseline(1, 42)!!.status)
            assertNull(hardcover.baseline(2, 42))
            hardcover.saveMatch(HardcoverMatchRow("item", "UNSURE", "[]", "key", 1))
            assertEquals("UNSURE", hardcover.match("item")!!.state)
            hardcover.saveQuestions(
                listOf(HardcoverQuestionRow(1, 42, "item", "Title", 3, "Reading", 1))
            )
            assertEquals(1, hardcover.allQuestions(1).size)
            assertEquals(0, hardcover.allQuestions(2).size)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun roomOpensAnUpgradedLibraryAndKeepsBaselines() = runBlocking {
        val name = "baseline-dao.db"
        helper.createDatabase(name, 8).use { it.execSQL(bookAtVersion8) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database =
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
        try {
            assertEquals("epub", database.library().book("book")!!.format)
            assertEquals(0L, database.library().book("book")!!.statusAt)
            val baselines = database.syncBaselines()
            val first =
                SyncBaseline(
                    bookId = "book",
                    ereaderId = "kindle",
                    path = "A/Kept.epub",
                    hash = "h",
                    status = "Reading",
                    statusAt = 1,
                    annotationIds = "[\"note\"]",
                    collections = "[\"Shelf\"]",
                    syncedAt = 2,
                )
            baselines.upsert(first)
            baselines.upsert(first.copy(status = "Finished", statusAt = 3, syncedAt = 4))
            baselines.upsert(first.copy(ereaderId = "kobo"))
            assertEquals("Finished", baselines.get("book", "kindle")!!.status)
            assertEquals(listOf("book"), baselines.forEreader("kobo").map { it.bookId })
            baselines.delete("book", "kindle")
            assertNull(baselines.get("book", "kindle"))
            assertEquals(1, baselines.forEreader("kobo").size)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
