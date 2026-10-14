package dev.sadking.folio

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sadking.folio.data.ALL_MIGRATIONS
import dev.sadking.folio.data.LibraryDatabase
import dev.sadking.folio.data.MIGRATION_7_8
import org.junit.Assert.assertEquals
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
}
