package dev.sadking.folio.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.DeviceStatistics
import dev.sadking.folio.data.LibraryDatabase
import dev.sadking.folio.data.LibraryRepository
import dev.sadking.folio.link.KosyncRequest
import dev.sadking.folio.link.SyncRoutes
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * KOReader's reading time arriving through the Link server's route (`POST /folio/v2/statistics`)
 * into Room's `device_statistics`, keyed `link:<e-reader id>`, checked as a backup's are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReadingStatisticsSyncTest {
    private lateinit var base: File
    private lateinit var db: LibraryDatabase
    private lateinit var routes: SyncRoutes
    private var clock = 1_791_750_100_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = File(context.cacheDir, "stats-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated =
            object : ContextWrapper(context) {
                override fun getFilesDir() = File(base, "private").apply { mkdirs() }
            }
        db =
            Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val repo = LibraryRepository(isolated, db) { File(base, "Books") }
        val prefs = context.getSharedPreferences("stats-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val library = RoomSyncLibrary(repo, SyncState(prefs), File(base, "cache")) { clock }
        routes = SyncRoutes(library, now = { clock })
    }

    @After
    fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    private fun day(date: String, seconds: Long) =
        JSONObject().put("date", date).put("seconds", seconds)

    private fun body(
        id: String = "kindle-7f3a",
        seconds: Long = 43_440,
        exportedAt: Long = 1_791_750_000,
        days: JSONArray = JSONArray().put(day("2026-10-14", 2700)).put(day("2026-10-13", 1500)),
    ) =
        JSONObject()
            .put("device", JSONObject().put("id", id).put("name", "Kindle Paperwhite"))
            .put("format", "folio-statistics")
            .put("version", 1)
            .put("seconds", seconds)
            .put("exportedAt", exportedAt)
            .put("days", days)
            .toString()

    private fun post(body: String) =
        routes
            .handle(
                KosyncRequest("POST", "/folio/v2/statistics", mapOf("x-folio-device" to "x"), body),
                "/statistics",
            )
            .status

    private fun stored() = runBlocking { db.library().allStatistics() }

    @Test
    fun theRouteKeepsReadingTimeUnderTheEreadersId() {
        // An old row fetched over SSH stays as it is.
        val old = DeviceStatistics("saved-ssh", 9 * 3600, 1_780_000_000, 1_780_000_000_000, "[]")
        runBlocking { db.library().statistics(old) }

        assertEquals(200, post(body()))
        val row = stored().single { it.deviceId == "link:kindle-7f3a" }
        assertEquals(43_440L, row.totalSeconds)
        assertEquals(1_791_750_000L, row.exportedAt)
        assertEquals(clock, row.fetchedAt)
        val days = JSONArray(row.days)
        assertEquals(2, days.length())
        assertEquals("2026-10-14", days.getJSONObject(0).getString("date"))
        assertEquals(2700L, days.getJSONObject(0).getLong("seconds"))

        // The next sync's totals replace that e-reader's; another e-reader gets its own row.
        clock += 60_000
        assertEquals(200, post(body(seconds = 50_000, exportedAt = 1_791_760_000)))
        assertEquals(200, post(body(id = "kobo-21", seconds = 600)))
        val rows = stored().associateBy { it.deviceId }
        assertEquals(setOf("saved-ssh", "link:kindle-7f3a", "link:kobo-21"), rows.keys)
        assertEquals(50_000L, rows.getValue("link:kindle-7f3a").totalSeconds)
        assertEquals(clock, rows.getValue("link:kindle-7f3a").fetchedAt)
        assertEquals(old, rows.getValue("saved-ssh"))
    }

    @Test
    fun badReadingTimeIsRefusedAndWhatWasKeptStays() {
        assertEquals(200, post(body()))
        val kept = stored()
        val tooMany = JSONArray()
        (0..366).forEach {
            tooMany.put(
                day(java.time.LocalDate.of(2025, 1, 1).plusDays(it.toLong()).toString(), 60)
            )
        }
        listOf(
                body(seconds = -1),
                body(seconds = 10_000_000_001),
                body(exportedAt = 0),
                body(days = JSONArray().put(day("2026-02-30", 60))),
                body(days = JSONArray().put(day("14/10/2026", 60))),
                body(days = JSONArray().put(day("2026-10-14", 60)).put(day("2026-10-14", 60))),
                body(days = JSONArray().put(day("2026-10-14", -5))),
                body(days = JSONArray().put(day("2026-10-14", 10_000_001))),
                body(days = tooMany),
            )
            .forEach { assertEquals(it, 403, post(it)) }
        assertEquals(kept, stored())
    }
}
