package dev.sadking.folio

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sadking.folio.data.ALL_MIGRATIONS
import dev.sadking.folio.data.LibraryDatabase
import dev.sadking.folio.data.MIGRATION_7_8
import dev.sadking.folio.data.MIGRATION_8_9
import dev.sadking.folio.data.SyncBaseline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
