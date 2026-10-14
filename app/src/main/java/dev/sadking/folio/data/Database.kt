package dev.sadking.folio.data

import androidx.room.*
import dev.sadking.folio.core.Metadata
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

@Entity(tableName = "books", indices = [Index(value = ["originalHash"], unique = true)])
data class Book(
    @PrimaryKey val id: String,
    val title: String,
    val authors: String,
    val description: String = "",
    val publisher: String = "",
    val language: String = "",
    val identifiers: String = "[]",
    val series: String = "",
    val seriesIndex: String = "",
    val tags: String = "[]",
    val originalPath: String,
    val currentPath: String,
    val coverPath: String? = null,
    val originalHash: String,
    val currentHash: String,
    val addedAt: Long = System.currentTimeMillis(),
    val status: String = "Unread",
) {
    fun metadata() =
        Metadata(
            title,
            decode(authors),
            description,
            publisher,
            language,
            decode(identifiers),
            series,
            seriesIndex,
            decode(tags),
        )

    fun withMetadata(m: Metadata) =
        copy(
            title = m.title,
            authors = encode(m.authors),
            description = m.description,
            publisher = m.publisher,
            language = m.language,
            identifiers = encode(m.identifiers),
            series = m.series,
            seriesIndex = m.seriesIndex,
            tags = encode(m.tags),
        )

    val authorLabel: String
        get() = decode(authors).joinToString(", ").ifEmpty { "Unknown author" }
}

fun encode(values: List<String>): String = JSONArray(values).toString()

fun decode(value: String): List<String> =
    JSONArray(value).let { a -> (0 until a.length()).map { a.getString(it) } }

@Entity(tableName = "wishlist", indices = [Index(value = ["catalogKey"], unique = true)])
data class WishBook(
    @PrimaryKey val id: String,
    val catalogKey: String,
    val title: String,
    val authors: String,
    val coverPath: String? = null,
    val hardcoverId: Int? = null,
    val hardcoverTitle: String = "",
    val hardcoverSynced: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val linkedBookId: String? = null,
    @ColumnInfo(defaultValue = "'{}'") val details: String = "{}",
    @ColumnInfo(defaultValue = "''") val status: String = "",
) {
    val authorLabel: String
        get() = decode(authors).joinToString(", ")
}

@Entity(
    tableName = "wish_memberships",
    primaryKeys = ["wishId", "shelfId"],
    indices = [Index("shelfId")],
    foreignKeys =
        [
            ForeignKey(
                entity = WishBook::class,
                parentColumns = ["id"],
                childColumns = ["wishId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = Shelf::class,
                parentColumns = ["id"],
                childColumns = ["shelfId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
)
data class WishMembership(val wishId: String, val shelfId: String)

@Entity(tableName = "removed_entries", indices = [Index("groupId")])
data class RemovedEntry(
    @PrimaryKey val id: String,
    val groupId: String,
    val title: String,
    val kind: String,
    val removedAt: Long,
)

@Entity(tableName = "hardcover_observations", primaryKeys = ["accountId", "hardcoverId"])
data class HardcoverObservation(
    val accountId: Int,
    val hardcoverId: Int,
    val status: Int,
    val pages: Int?,
    val editionId: Int?,
    val fetchedAt: Long,
)

@Entity(tableName = "collections", indices = [Index(value = ["name"], unique = true)])
data class Shelf(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "'Manual'") val ruleField: String = "Manual",
    @ColumnInfo(defaultValue = "''") val ruleValue: String = "",
    @ColumnInfo(defaultValue = "0") val syncEnabled: Boolean = false,
)

@Entity(tableName = "reading", primaryKeys = ["bookId", "deviceId"])
data class ReadingSnapshot(
    val bookId: String,
    val deviceId: String,
    val percent: Double?,
    val position: String,
    val status: String,
    val rating: Int,
    val fetchedAt: Long,
    val sourcePath: String,
    val sourceHash: String,
    val documentHash: String,
)

/** Phone anchors are scoped to a file revision; device snapshots remain independent. */
@Entity(
    tableName = "phone_reading",
    primaryKeys = ["bookId", "revisionHash"],
    foreignKeys =
        [
            ForeignKey(
                entity = Book::class,
                parentColumns = ["id"],
                childColumns = ["bookId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class PhoneReading(
    val bookId: String,
    val revisionHash: String,
    val locator: String,
    val xpointer: String,
    val percent: Double?,
    val updatedAt: Long,
)

@Entity(
    tableName = "phone_bookmarks",
    indices = [Index("bookId")],
    foreignKeys =
        [
            ForeignKey(
                entity = Book::class,
                parentColumns = ["id"],
                childColumns = ["bookId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class PhoneBookmark(
    @PrimaryKey val id: String,
    val bookId: String,
    val revisionHash: String,
    val locator: String,
    val title: String,
    val createdAt: Long,
)

@Entity(tableName = "device_statistics")
data class DeviceStatistics(
    @PrimaryKey val deviceId: String,
    val totalSeconds: Long,
    val exportedAt: Long,
    val fetchedAt: Long,
    val days: String,
)

@Entity(tableName = "annotations", indices = [Index("bookId")])
data class BookAnnotation(
    @PrimaryKey val id: String,
    val bookId: String,
    val deviceId: String,
    val text: String,
    val note: String,
    val chapter: String,
    val date: String,
    val position: String,
    val endPosition: String,
    val kind: String,
)

@Entity(
    tableName = "memberships",
    primaryKeys = ["bookId", "shelfId"],
    indices = [Index("shelfId")],
    foreignKeys =
        [
            ForeignKey(
                entity = Book::class,
                parentColumns = ["id"],
                childColumns = ["bookId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = Shelf::class,
                parentColumns = ["id"],
                childColumns = ["shelfId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
)
data class Membership(val bookId: String, val shelfId: String)

@Entity(tableName = "transfers", primaryKeys = ["bookId", "deviceId"])
data class Transfer(
    val bookId: String,
    val deviceId: String,
    val remotePath: String,
    val hash: String,
    val completedAt: Long,
)

@Entity(tableName = "sync_journal")
data class Journal(
    @PrimaryKey val id: String,
    val bookId: String,
    val state: String,
    val deviceId: String,
    val startedAt: Long = System.currentTimeMillis(),
    val message: String = "",
)

@Entity(tableName = "sync_jobs")
data class SyncJob(
    @PrimaryKey val id: String,
    val deviceId: String,
    val state: String,
    val totalBooks: Int,
    val totalBytes: Long,
    val completedBooks: Int = 0,
    val bytesSent: Long = 0,
    val phase: String = "",
    val currentTitle: String = "",
    val message: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val active: Boolean
        get() = state in listOf("QUEUED", "RUNNING", "CANCELLING")
}

@Entity(
    tableName = "sync_tasks",
    indices = [Index("jobId")],
    foreignKeys =
        [
            ForeignKey(
                entity = SyncJob::class,
                parentColumns = ["id"],
                childColumns = ["jobId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class SyncTask(
    @PrimaryKey val id: String,
    val jobId: String,
    val sequence: Int,
    val bookId: String,
    val title: String,
    val sourcePath: String,
    val hash: String,
    val remotePath: String,
    val bytes: Long,
    val state: String = "QUEUED",
)

@Dao
interface LibraryDao {
    @Query(
        "SELECT sync_tasks.* FROM sync_tasks INNER JOIN sync_jobs ON sync_tasks.jobId = sync_jobs.id WHERE sync_jobs.state IN ('QUEUED','RUNNING','CANCELLING','FAILED','PAUSED') AND sync_tasks.state != 'COMPLETE'"
    )
    fun activeTasks(): Flow<List<SyncTask>>

    @Query("SELECT * FROM sync_jobs ORDER BY updatedAt DESC LIMIT 20")
    fun jobs(): Flow<List<SyncJob>>

    @Query("SELECT * FROM sync_jobs WHERE id = :id") suspend fun job(id: String): SyncJob?

    @Query("SELECT * FROM sync_jobs WHERE state NOT IN ('COMPLETE','CANCELLED') LIMIT 1")
    suspend fun unfinishedJob(): SyncJob?

    @Insert suspend fun insertJob(job: SyncJob)

    @Update suspend fun updateJob(job: SyncJob)

    @Insert suspend fun insertTasks(tasks: List<SyncTask>)

    @Update suspend fun updateTask(task: SyncTask)

    @Query("SELECT * FROM sync_tasks WHERE jobId = :id ORDER BY sequence")
    suspend fun tasks(id: String): List<SyncTask>

    @Query(
        "UPDATE sync_jobs SET state=CASE WHEN state='CANCELLING' THEN 'CANCELLED' ELSE 'PAUSED' END, message='Interrupted; completed files retained', updatedAt=:now WHERE state IN ('QUEUED','RUNNING','CANCELLING')"
    )
    suspend fun recoverJobs(now: Long)

    @Query("SELECT * FROM wish_memberships WHERE wishId NOT IN (SELECT id FROM removed_entries)")
    fun wishMemberships(): Flow<List<WishMembership>>

    @Query("SELECT * FROM wish_memberships WHERE wishId NOT IN (SELECT id FROM removed_entries)")
    suspend fun allWishMemberships(): List<WishMembership>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addWishMembership(value: WishMembership)

    @Query("DELETE FROM wish_memberships WHERE wishId=:wish AND shelfId=:shelf")
    suspend fun removeWishMembership(wish: String, shelf: String)

    @Query("SELECT * FROM phone_reading WHERE bookId=:book AND revisionHash=:revision")
    suspend fun phoneReading(book: String, revision: String): PhoneReading?

    @Query("SELECT * FROM phone_reading WHERE bookId NOT IN (SELECT id FROM removed_entries)")
    fun phoneReadingFlow(): Flow<List<PhoneReading>>

    @Query("SELECT * FROM phone_reading WHERE bookId NOT IN (SELECT id FROM removed_entries)")
    suspend fun allPhoneReading(): List<PhoneReading>

    @Query("SELECT * FROM phone_bookmarks WHERE bookId NOT IN (SELECT id FROM removed_entries)")
    suspend fun allPhoneBookmarks(): List<PhoneBookmark>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePhoneReading(value: PhoneReading)

    @Query(
        "SELECT * FROM phone_bookmarks WHERE bookId=:book AND revisionHash=:revision ORDER BY createdAt DESC"
    )
    fun phoneBookmarks(book: String, revision: String): Flow<List<PhoneBookmark>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addPhoneBookmark(value: PhoneBookmark)

    @Query("DELETE FROM phone_bookmarks WHERE id=:id") suspend fun deletePhoneBookmark(id: String)

    @Query("SELECT * FROM hardcover_observations")
    fun hardcoverObservations(): Flow<List<HardcoverObservation>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hardcoverObservation(value: HardcoverObservation)

    @Query("DELETE FROM hardcover_observations WHERE accountId=:account AND hardcoverId=:book")
    suspend fun deleteHardcoverObservation(account: Int, book: Int)

    @Query("SELECT * FROM books") suspend fun allStoredBooks(): List<Book>

    @Query("SELECT * FROM wishlist") suspend fun allStoredWishes(): List<WishBook>

    @Query("SELECT * FROM removed_entries ORDER BY removedAt DESC")
    fun removed(): Flow<List<RemovedEntry>>

    @Query("SELECT * FROM removed_entries") suspend fun allRemoved(): List<RemovedEntry>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun markRemoved(value: RemovedEntry)

    @Query("DELETE FROM removed_entries WHERE groupId=:group")
    suspend fun restoreRemoved(group: String)

    @Query("SELECT * FROM removed_entries WHERE id=:id")
    suspend fun removedEntry(id: String): RemovedEntry?

    @Query("DELETE FROM collections WHERE id=:id") suspend fun deleteShelf(id: String)

    @Query(
        "SELECT * FROM wishlist WHERE id NOT IN (SELECT id FROM removed_entries) ORDER BY addedAt DESC"
    )
    fun wishes(): Flow<List<WishBook>>

    @Query("SELECT * FROM wishlist WHERE id NOT IN (SELECT id FROM removed_entries)")
    suspend fun allWishes(): List<WishBook>

    @Query("SELECT * FROM wishlist WHERE id = :id") suspend fun wish(id: String): WishBook?

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertWish(value: WishBook): Long

    @Update suspend fun updateWish(value: WishBook)

    @Query("DELETE FROM wishlist WHERE id = :id") suspend fun deleteWish(id: String)

    @Query("DELETE FROM books WHERE id=:id") suspend fun deleteLocalBook(id: String)

    @Query("DELETE FROM reading WHERE bookId=:id") suspend fun deleteLocalReading(id: String)

    @Query("DELETE FROM annotations WHERE bookId=:id")
    suspend fun deleteLocalAnnotations(id: String)

    @Query("DELETE FROM transfers WHERE bookId=:id") suspend fun deleteLocalTransfers(id: String)

    @Query("DELETE FROM sync_journal WHERE bookId=:id") suspend fun deleteLocalJournal(id: String)

    @Query(
        "SELECT * FROM books WHERE id NOT IN (SELECT id FROM removed_entries) ORDER BY addedAt DESC"
    )
    fun books(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id NOT IN (SELECT id FROM removed_entries)")
    suspend fun allBooks(): List<Book>

    @Query("SELECT * FROM books WHERE id = :id") suspend fun book(id: String): Book?

    @Query("SELECT * FROM books WHERE originalHash = :hash OR currentHash = :hash LIMIT 1")
    suspend fun duplicate(hash: String): Book?

    @Insert suspend fun insert(book: Book)

    @Update suspend fun update(book: Book)

    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE")
    fun shelves(): Flow<List<Shelf>>

    @Query("SELECT * FROM memberships WHERE bookId NOT IN (SELECT id FROM removed_entries)")
    fun memberships(): Flow<List<Membership>>

    @Query("SELECT * FROM collections") suspend fun allShelves(): List<Shelf>

    @Query("SELECT * FROM memberships WHERE bookId NOT IN (SELECT id FROM removed_entries)")
    suspend fun allMemberships(): List<Membership>

    @Query("SELECT * FROM reading WHERE bookId NOT IN (SELECT id FROM removed_entries)")
    suspend fun allReading(): List<ReadingSnapshot>

    @Query("SELECT * FROM annotations WHERE bookId NOT IN (SELECT id FROM removed_entries)")
    suspend fun allAnnotations(): List<BookAnnotation>

    @Query("SELECT * FROM device_statistics") suspend fun allStatistics(): List<DeviceStatistics>

    @Query("SELECT * FROM device_statistics") fun statistics(): Flow<List<DeviceStatistics>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun statistics(value: DeviceStatistics)

    @Query("SELECT * FROM reading WHERE bookId NOT IN (SELECT id FROM removed_entries)")
    fun reading(): Flow<List<ReadingSnapshot>>

    @Query(
        "SELECT * FROM annotations WHERE bookId NOT IN (SELECT id FROM removed_entries) ORDER BY date DESC"
    )
    fun annotations(): Flow<List<BookAnnotation>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun reading(value: ReadingSnapshot)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun annotation(value: BookAnnotation)

    @Update suspend fun updateShelf(shelf: Shelf)

    @Insert suspend fun insertShelf(shelf: Shelf)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMembership(membership: Membership)

    @Query("DELETE FROM memberships WHERE bookId = :book AND shelfId = :shelf")
    suspend fun removeMembership(book: String, shelf: String)

    @Query("SELECT * FROM transfers WHERE deviceId = :device")
    suspend fun transfers(device: String): List<Transfer>

    @Query("DELETE FROM transfers WHERE deviceId = :device")
    suspend fun deleteLegacyTransfers(device: String)

    @Query("UPDATE sync_journal SET deviceId = :current WHERE deviceId = :legacy")
    suspend fun rekeyJournal(legacy: String, current: String)

    @Query("SELECT * FROM transfers WHERE bookId NOT IN (SELECT id FROM removed_entries)")
    fun transferFlow(): Flow<List<Transfer>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun transfer(transfer: Transfer)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun journal(journal: Journal)

    @Query("SELECT * FROM sync_journal ORDER BY startedAt DESC LIMIT 100")
    fun history(): Flow<List<Journal>>

    @Query(
        "UPDATE sync_journal SET state = 'INTERRUPTED', message = 'Interrupted; preview again to reconcile' WHERE state IN ('COMPARING','TRANSFERRING','VERIFYING','PUBLISHING')"
    )
    suspend fun recover()
}

@Database(
    entities =
        [
            Book::class,
            PhoneReading::class,
            PhoneBookmark::class,
            WishBook::class,
            WishMembership::class,
            RemovedEntry::class,
            HardcoverObservation::class,
            Shelf::class,
            Membership::class,
            Transfer::class,
            Journal::class,
            ReadingSnapshot::class,
            BookAnnotation::class,
            DeviceStatistics::class,
            SyncJob::class,
            SyncTask::class,
        ],
    version = 7,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
}

val MIGRATION_1_2 =
    object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE collections ADD COLUMN ruleField TEXT NOT NULL DEFAULT 'Manual'"
            )
            db.execSQL("ALTER TABLE collections ADD COLUMN ruleValue TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE collections ADD COLUMN syncEnabled INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS reading (bookId TEXT NOT NULL, deviceId TEXT NOT NULL, percent REAL, position TEXT NOT NULL, status TEXT NOT NULL, rating INTEGER NOT NULL, fetchedAt INTEGER NOT NULL, sourcePath TEXT NOT NULL, sourceHash TEXT NOT NULL, documentHash TEXT NOT NULL, PRIMARY KEY(bookId,deviceId))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS annotations (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, deviceId TEXT NOT NULL, text TEXT NOT NULL, note TEXT NOT NULL, chapter TEXT NOT NULL, date TEXT NOT NULL, position TEXT NOT NULL, endPosition TEXT NOT NULL, kind TEXT NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_annotations_bookId ON annotations(bookId)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS device_statistics (deviceId TEXT NOT NULL PRIMARY KEY, totalSeconds INTEGER NOT NULL, exportedAt INTEGER NOT NULL, fetchedAt INTEGER NOT NULL, days TEXT NOT NULL)"
            )
        }
    }

val MIGRATION_2_3 =
    object : androidx.room.migration.Migration(2, 3) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS wishlist (id TEXT NOT NULL PRIMARY KEY, catalogKey TEXT NOT NULL, title TEXT NOT NULL, authors TEXT NOT NULL, coverPath TEXT, hardcoverId INTEGER, hardcoverTitle TEXT NOT NULL, hardcoverSynced INTEGER NOT NULL, addedAt INTEGER NOT NULL, linkedBookId TEXT)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_wishlist_catalogKey ON wishlist(catalogKey)"
            )
        }
    }

val MIGRATION_3_4 =
    object : androidx.room.migration.Migration(3, 4) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_jobs (id TEXT NOT NULL PRIMARY KEY, deviceId TEXT NOT NULL, state TEXT NOT NULL, totalBooks INTEGER NOT NULL, totalBytes INTEGER NOT NULL, completedBooks INTEGER NOT NULL, bytesSent INTEGER NOT NULL, phase TEXT NOT NULL, currentTitle TEXT NOT NULL, message TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_tasks (id TEXT NOT NULL PRIMARY KEY, jobId TEXT NOT NULL, sequence INTEGER NOT NULL, bookId TEXT NOT NULL, title TEXT NOT NULL, sourcePath TEXT NOT NULL, hash TEXT NOT NULL, remotePath TEXT NOT NULL, bytes INTEGER NOT NULL, state TEXT NOT NULL, FOREIGN KEY(jobId) REFERENCES sync_jobs(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_tasks_jobId ON sync_tasks(jobId)")
        }
    }

val MIGRATION_4_5 =
    object : androidx.room.migration.Migration(4, 5) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS wish_memberships (wishId TEXT NOT NULL, shelfId TEXT NOT NULL, PRIMARY KEY(wishId,shelfId), FOREIGN KEY(wishId) REFERENCES wishlist(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(shelfId) REFERENCES collections(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_wish_memberships_shelfId ON wish_memberships(shelfId)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS hardcover_observations (accountId INTEGER NOT NULL, hardcoverId INTEGER NOT NULL, status INTEGER NOT NULL, pages INTEGER, editionId INTEGER, fetchedAt INTEGER NOT NULL, PRIMARY KEY(accountId,hardcoverId))"
            )
        }
    }

val MIGRATION_5_6 =
    object : androidx.room.migration.Migration(5, 6) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE wishlist ADD COLUMN details TEXT NOT NULL DEFAULT '{}'")
            db.execSQL("ALTER TABLE wishlist ADD COLUMN status TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS removed_entries (id TEXT NOT NULL PRIMARY KEY, groupId TEXT NOT NULL, title TEXT NOT NULL, kind TEXT NOT NULL, removedAt INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_removed_entries_groupId ON removed_entries(groupId)"
            )
        }
    }

val MIGRATION_6_7 =
    object : androidx.room.migration.Migration(6, 7) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS phone_reading (bookId TEXT NOT NULL, revisionHash TEXT NOT NULL, locator TEXT NOT NULL, xpointer TEXT NOT NULL, percent REAL, updatedAt INTEGER NOT NULL, PRIMARY KEY(bookId,revisionHash), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS phone_bookmarks (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, revisionHash TEXT NOT NULL, locator TEXT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_phone_bookmarks_bookId ON phone_bookmarks(bookId)"
            )
        }
    }

/** Every shipped migration, in order. The app and migration tests must use this same list. */
val ALL_MIGRATIONS =
    arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
    )
