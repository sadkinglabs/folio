package dev.sadking.folio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.core.BackupArchive
import dev.sadking.folio.core.stats.ReadingSession
import dev.sadking.folio.core.stats.SessionSource
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Backups carry reading sessions, word counts and Kindle book names (D8, A1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StatsBackupTest {
    private lateinit var base: File
    private val databases = mutableListOf<LibraryDatabase>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = File(context.cacheDir, "stats-backup-${UUID.randomUUID()}").apply { mkdirs() }
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

    /** A book record without a file: the backup keeps the record and its statistics. */
    private fun book(id: String, hash: String) =
        Book(
            id = id,
            title = "Book $id",
            authors = "[]",
            originalPath = "",
            currentPath = "",
            originalHash = hash,
            currentHash = hash,
            addedAt = 1,
        )

    /** A Kindle session when [bookId] is null (it has a document), else a phone session. */
    private fun session(id: String, seconds: Long, bookId: String? = null) =
        ReadingSession(
            id = id,
            source = if (bookId == null) SessionSource.KINDLE else SessionSource.PHONE,
            deviceKey = if (bookId == null) "ereader:kindle-1" else "phone:phone-1",
            bookId = bookId,
            document = if (bookId == null) DOCUMENT else null,
            startMs = 1_800_000_000_000L,
            durationSec = seconds,
            progressStart = 0.25,
            progressEnd = 0.5,
            progressRead = 0.125,
            pages = 7,
        )

    @Test
    fun sessionsAndWordCountsRoundTripIntoAnEmptyLibrary() = runBlocking {
        val repo = library("phone")
        repo.dao.insert(book(BOOK, HASH))
        val kindle = session("k1", 600)
        val phone = session("p1", 300, bookId = BOOK)
        repo.database.stats().upsertSessions(listOf(kindle.toRow(1), phone.toRow(2)))
        repo.database.stats().upsertWords(BookWordsRow(BOOK, HASH, 4200, 3))
        val archive = File(base, "backup.zip")
        LibraryBackup(repo).export(archive)

        val fresh = library("new-phone")
        LibraryBackup(fresh).restore(archive)
        assertEquals(
            setOf(kindle, phone),
            fresh.database.stats().allSessions().mapNotNull { it.toSession() }.toSet(),
        )
        assertEquals(listOf(BookWordsRow(BOOK, HASH, 4200, 3)), fresh.database.stats().allWords())
    }

    @Test
    fun aRestoredSessionKeepsTheLongerCopy() = runBlocking {
        val repo = library("phone")
        repo.database
            .stats()
            .upsertSessions(
                listOf(session("longer", 1200).toRow(1), session("shorter", 300).toRow(1))
            )
        val archive = File(base, "backup.zip")
        LibraryBackup(repo).export(archive)

        val fresh = library("new-phone")
        fresh.database
            .stats()
            .upsertSessions(
                listOf(session("longer", 900).toRow(1), session("shorter", 600).toRow(1))
            )
        LibraryBackup(fresh).restore(archive)
        assertEquals(
            mapOf("longer" to 1200L, "shorter" to 600L),
            fresh.database.stats().sessionsById(listOf("longer", "shorter")).associate {
                it.id to it.durationSec
            },
        )
    }

    @Test
    fun aBackupWithoutTheStatisticsKeysStillRestores() = runBlocking {
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
        repo.database.stats().upsertSessions(listOf(session("k1", 600).toRow(1)))
        repo.database.stats().upsertWords(BookWordsRow(BOOK, HASH, 10, 1))
        LibraryBackup(repo).restore(archive)
        assertEquals(listOf(600L), repo.database.stats().allSessions().map { it.durationSec })
        assertEquals(listOf(BookWordsRow(BOOK, HASH, 10, 1)), repo.database.stats().allWords())
    }

    @Test
    fun aSessionOfARemappedBookPointsAtTheNewId() = runBlocking {
        val repo = library("phone")
        repo.dao.insert(book(BOOK, HASH))
        repo.database.stats().upsertSessions(listOf(session("p1", 300, bookId = BOOK).toRow(1)))
        repo.database.stats().upsertWords(BookWordsRow(BOOK, HASH, 100, 1))
        val archive = File(base, "backup.zip")
        LibraryBackup(repo).export(archive)

        // The same file is in this library under another id.
        val fresh = library("new-phone")
        fresh.dao.insert(book(OTHER_BOOK, HASH))
        LibraryBackup(fresh).restore(archive)
        assertEquals(
            OTHER_BOOK,
            fresh.database.stats().sessionsById(listOf("p1")).single().bookId,
        )
        assertEquals(listOf(OTHER_BOOK), fresh.database.stats().allWords().map { it.bookId })
    }

    @Test
    fun wordCountsOnlyForTheRevisionTheBookHasAndNeverOverAnExistingCount() = runBlocking {
        val repo = library("phone")
        repo.dao.insert(book(BOOK, HASH))
        repo.dao.insert(book(OTHER_BOOK, OTHER_HASH))
        repo.database.stats().upsertWords(BookWordsRow(BOOK, HASH, 100, 1))
        repo.database.stats().upsertWords(BookWordsRow(OTHER_BOOK, "an older revision", 50, 1))
        val archive = File(base, "backup.zip")
        LibraryBackup(repo).export(archive)

        val fresh = library("new-phone")
        fresh.dao.insert(book(BOOK, HASH))
        fresh.database.stats().upsertWords(BookWordsRow(BOOK, HASH, 999, 5))
        LibraryBackup(fresh).restore(archive)
        // The count already here is kept; the other book's count is for a revision it lacks.
        assertEquals(listOf(BookWordsRow(BOOK, HASH, 999, 5)), fresh.database.stats().allWords())
    }

    @Test
    fun kindleBookNamesFollowTheNewerName() = runBlocking {
        val repo = library("phone")
        repo.database
            .stats()
            .upsertBookNames(
                listOf(
                    StatsBookNameRow(DIGEST_A, "Backup title", "Author", 100),
                    StatsBookNameRow(DIGEST_B, "New in backup", "", 300),
                    StatsBookNameRow(DIGEST_C, "Backup wins", "Writer", 500),
                )
            )
        val archive = File(base, "backup.zip")
        LibraryBackup(repo).export(archive)

        val fresh = library("new-phone")
        fresh.database
            .stats()
            .upsertBookNames(
                listOf(
                    StatsBookNameRow(DIGEST_A, "Local title", "Local", 200),
                    StatsBookNameRow(DIGEST_C, "Local old", "", 200),
                )
            )
        LibraryBackup(fresh).restore(archive)
        assertEquals(
            mapOf(
                DIGEST_A to "Local title",
                DIGEST_B to "New in backup",
                DIGEST_C to "Backup wins",
            ),
            fresh.database.stats().allBookNames().associate { it.digest to it.title },
        )
    }

    private companion object {
        const val BOOK = "11111111-1111-1111-1111-111111111111"
        const val OTHER_BOOK = "22222222-2222-2222-2222-222222222222"
        const val HASH = "aa"
        const val OTHER_HASH = "bb"
        const val DOCUMENT = "0123456789abcdef0123456789abcdef"
        val DIGEST_A = "1".repeat(32)
        val DIGEST_B = "2".repeat(32)
        val DIGEST_C = "3".repeat(32)
    }
}
