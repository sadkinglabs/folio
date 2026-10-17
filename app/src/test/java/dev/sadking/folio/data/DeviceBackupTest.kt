package dev.sadking.folio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.core.BackupArchive
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Backups keep the owner's device names, merges and forgets (schema 12) and every Progress sync
 * report; a backup made before them still restores.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceBackupTest {
    private lateinit var base: File
    private val databases = mutableListOf<LibraryDatabase>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = File(context.cacheDir, "device-backup-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        databases.forEach { it.close() }
        base.deleteRecursively()
    }

    private fun library(name: String): LibraryRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(base, name).apply { mkdirs() }
        val db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
                .also { databases += it }
        return LibraryRepository(
            object : ContextWrapper(context) {
                override fun getFilesDir() = File(folder, "private").apply { mkdirs() }
            },
            db,
        ) {
            File(folder, "Books")
        }
    }

    private val report =
        KosyncReport("doc", "kindle-1", "KindlePaperWhite5SE", "/body/p", 0.5, 9, 9000)

    @Test
    fun profilesAndReportsRoundTripAndAForgottenDeviceKeepsItsReports() = runBlocking {
        val repo = library("phone")
        repo.dao.saveKosyncReport(report)
        repo.dao.saveDeviceProfile(
            DeviceProfile("ereader:kindle-1", "Paperwhite", hiddenAt = 50, updatedAt = 60)
        )
        repo.dao.saveDeviceProfile(
            DeviceProfile("ssh:saved", mergedInto = "ereader:kindle-1", updatedAt = 61)
        )
        val archive = File(base, "backup.zip")
        LibraryBackup(repo).export(archive)

        val fresh = library("new-phone")
        LibraryBackup(fresh).restore(archive)
        assertEquals(listOf(report), fresh.dao.everyKosyncReport())
        assertEquals(
            setOf(
                DeviceProfile("ereader:kindle-1", "Paperwhite", hiddenAt = 50, updatedAt = 60),
                DeviceProfile("ssh:saved", mergedInto = "ereader:kindle-1", updatedAt = 61),
            ),
            fresh.dao.allDeviceProfiles().toSet(),
        )
        // Restoring again over a newer local change keeps the newer one.
        fresh.dao.saveDeviceProfile(DeviceProfile("ereader:kindle-1", "Mine", updatedAt = 99))
        fresh.dao.saveKosyncReport(
            report.copy(percentage = 0.6, timestamp = 10, receivedAt = 10_000)
        )
        LibraryBackup(fresh).restore(archive)
        assertEquals("Mine", fresh.dao.deviceProfile("ereader:kindle-1")!!.displayName)
        assertEquals(0.6, fresh.dao.everyKosyncReport().single().percentage, 0.0)
    }

    @Test
    fun aBackupFromBeforeDeviceProfilesStillRestores() = runBlocking {
        val manifest =
            JSONObject()
                .put("format", "folio-backup")
                .put("version", 4)
                .put("createdAt", 1)
                .put("books", JSONArray())
                .put("wishlist", JSONArray())
                .put("shelves", JSONArray())
                .put("memberships", JSONArray())
                .put("reading", JSONArray())
                .put("annotations", JSONArray())
        val archive = File(base, "old.zip")
        BackupArchive.write(archive, manifest.toString().toByteArray(), emptyMap())
        val repo = library("old")
        repo.dao.saveKosyncReport(report)
        LibraryBackup(repo).restore(archive)
        assertEquals("Nothing on the phone is lost", listOf(report), repo.dao.everyKosyncReport())
        assertTrue(repo.dao.allDeviceProfiles().isEmpty())
    }
}
