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
    /** The file's format and extension: "epub", "pdf", "cbz" or "cbr" (see BookFormats). */
    @ColumnInfo(defaultValue = "'epub'") val format: String = "epub",
    /**
     * When [status] last changed, in epoch milliseconds. 0 when unknown: books from before schema
     * 10, or never given a status. Sync uses it to pick the newer status when there is no baseline.
     */
    @ColumnInfo(defaultValue = "0") val statusAt: Long = 0,
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
    /**
     * When [status] last changed, in epoch milliseconds. 0 when unknown: entries from before schema
     * 11, or never given a status. Hardcover sync uses it like [Book.statusAt].
     */
    @ColumnInfo(defaultValue = "0") val statusAt: Long = 0,
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

/**
 * The latest Folio Link (kosync) report from one device for one KOReader document. [document] is
 * KOReader's partial MD5 of the EPUB, so the same bytes are needed on both sides. [timestamp] is in
 * seconds, as kosync sends it; [receivedAt] breaks ties in milliseconds.
 */
@Entity(tableName = "kosync_progress", primaryKeys = ["document", "deviceId"])
data class KosyncReport(
    val document: String,
    val deviceId: String,
    val device: String,
    val progress: String,
    val percentage: Double,
    val timestamp: Long,
    val receivedAt: Long,
)

/**
 * How Librillo shows one device that reports positions (schema 12): the owner's name for it, the
 * device it is the same as, and when the owner forgot it. It only changes how devices are shown:
 * their reports, snapshots and positions are never changed or deleted. [deviceKey] is a
 * [DeviceKeys] key. [hiddenAt] is 0 while shown; a device heard from after it shows again.
 */
@Entity(tableName = "device_profiles")
data class DeviceProfile(
    @PrimaryKey val deviceKey: String,
    @ColumnInfo(defaultValue = "''") val displayName: String = "",
    val mergedInto: String? = null,
    @ColumnInfo(defaultValue = "0") val hiddenAt: Long = 0,
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

/**
 * A highlight, note or bookmark from any device. [color] and [drawer] are KOReader's highlight
 * colour and drawing style ("yellow", "lighten", "underscore", ...), blank for the reader's default
 * (every row from before schema 13). [editedAt] is when the note, colour or style last changed, in
 * KOReader's form (`YYYY-MM-DD HH:MM:SS`, local time), blank when never: the newest edit wins at
 * sync. [deletedAt] is 0 for a live annotation; the owner's delete sets it (epoch ms) and keeps the
 * row, so it can be restored for 30 days and sync can tell the e-reader. After 30 days its text and
 * note are cleared, and the row stays as a tombstone.
 */
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
    @ColumnInfo(defaultValue = "''") val color: String = "",
    @ColumnInfo(defaultValue = "''") val drawer: String = "",
    @ColumnInfo(defaultValue = "''") val editedAt: String = "",
    @ColumnInfo(defaultValue = "0") val deletedAt: Long = 0,
) {
    val deleted: Boolean
        get() = deletedAt > 0
}

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

/**
 * What the phone and one e-reader agreed about a book at their last sync: the common ancestor for
 * the 3-way compare. Without it a book missing on one side can't be told apart from one that was
 * never there. [annotationIds] and [collections] are JSON arrays. No foreign key to books on
 * purpose: purging a book must not erase what the e-reader last had.
 */
@Entity(tableName = "sync_baseline", primaryKeys = ["bookId", "ereaderId"])
data class SyncBaseline(
    val bookId: String,
    val ereaderId: String,
    /** The book's path on the e-reader when both sides last agreed. */
    val path: String,
    /** SHA-256 of the file both sides had. */
    val hash: String,
    val status: String,
    /** When [status] last changed, in epoch milliseconds. */
    val statusAt: Long,
    val annotationIds: String = "[]",
    val collections: String = "[]",
    /** When this baseline was recorded, in epoch milliseconds. */
    val syncedAt: Long,
    /**
     * A JSON object from annotation id to the state both sides agreed on (schema 13), or null when
     * the e-reader did not take annotation edits and deletions at that sync.
     */
    val annotationStates: String? = null,
)

/**
 * What Librillo and one Hardcover account last agreed about a book's status (M10): the common
 * ancestor that tells "changed here" from "changed there". [status] is Hardcover's status id 1..5,
 * or 0 when both agreed the book is not on Hardcover. [statusAt] is Librillo's status time then.
 * Rules: docs/HARDCOVER_SYNC.md, A3.
 */
@Entity(tableName = "hardcover_baseline", primaryKeys = ["accountId", "hardcoverId"])
data class HardcoverBaselineRow(
    val accountId: Int,
    val hardcoverId: Int,
    /** 0..5; 0 = agreed it is not on Hardcover. */
    val status: Int,
    val statusAt: Long,
    val syncedAt: Long,
)

/** What matching concluded for an unlinked book or catalogue entry ([itemId]). */
@Entity(tableName = "hardcover_matches")
data class HardcoverMatchRow(
    @PrimaryKey val itemId: String,
    /** A MatchRecordState name. */
    val state: String,
    /**
     * JSON array: [{"id":1,"title":"…","authors":["…"],"year":2001 or null,"cover":"/path" or
     * null}]
     */
    val candidates: String,
    val metadataKey: String,
    val checkedAt: Long,
)

/** Both sides changed a book's status since they last agreed: the owner decides. */
@Entity(tableName = "hardcover_questions", primaryKeys = ["accountId", "hardcoverId"])
data class HardcoverQuestionRow(
    val accountId: Int,
    val hardcoverId: Int,
    val itemId: String,
    val title: String,
    /** Hardcover's status id 1..5, or 0: no longer in the Hardcover library. */
    val hardcoverStatus: Int,
    /** Librillo's status word. */
    val librilloStatus: String,
    val askedAt: Long,
)

/**
 * One stretch of reading or listening (M12): the stored form of a `ReadingSession`. [source] is a
 * `SessionSource` name. [bookId] is null for a KOReader session until its digest matches a book.
 * [updatedAt] is when the row was last written, epoch milliseconds.
 */
@Entity(
    tableName = "reading_sessions",
    indices = [Index("startMs"), Index("bookId"), Index("document"), Index("deviceKey")],
)
data class ReadingSessionRow(
    @PrimaryKey val id: String,
    val source: String,
    val deviceKey: String,
    val bookId: String?,
    val document: String?,
    val startMs: Long,
    val durationSec: Long,
    val progressStart: Double?,
    val progressEnd: Double?,
    val progressRead: Double?,
    val pages: Int?,
    val updatedAt: Long,
)

/** Which library book a KOReader file digest (its MD5) belongs to, for the file revision seen. */
@Entity(tableName = "koreader_digests", indices = [Index("bookId")])
data class KoreaderDigestRow(
    @PrimaryKey val digest: String,
    val bookId: String,
    val revisionHash: String,
    val computedAt: Long,
)

/**
 * A book's word count, for the file revision it was counted from. Null when the file can't be read.
 */
@Entity(tableName = "book_words")
data class BookWordsRow(
    @PrimaryKey val bookId: String,
    val revisionHash: String,
    val words: Long?,
    val countedAt: Long,
)

/** One Hardcover read of a book, with its dates as `YYYY-MM-DD` (null when Hardcover has none). */
@Entity(
    tableName = "hardcover_reads",
    primaryKeys = ["accountId", "readId"],
    indices = [Index("hardcoverId")],
)
data class HardcoverReadRow(
    val accountId: Int,
    val readId: Int,
    val hardcoverId: Int,
    val startedOn: String?,
    val finishedOn: String?,
    val fetchedAt: Long,
)

/**
 * The title and authors KOReader knows for a Kindle book the phone can't match (A1). The digest is
 * the file's MD5. Newest wins.
 */
@Entity(tableName = "stats_book_names")
data class StatsBookNameRow(
    @PrimaryKey val digest: String,
    val title: String,
    val authors: String,
    val updatedAt: Long,
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

    // Folio Link. Blocking on purpose: the server calls these from its own threads.
    @Upsert fun saveKosyncReport(report: KosyncReport)

    @Query(
        "SELECT * FROM kosync_progress WHERE document = :document ORDER BY timestamp DESC, receivedAt DESC LIMIT 1"
    )
    fun latestKosyncReport(document: String): KosyncReport?

    @Query("SELECT * FROM kosync_progress WHERE document = :document ORDER BY timestamp DESC")
    suspend fun kosyncReports(document: String): List<KosyncReport>

    @Query("SELECT * FROM kosync_progress ORDER BY timestamp DESC")
    fun kosyncFlow(): Flow<List<KosyncReport>>

    @Query("SELECT * FROM kosync_progress WHERE document = :document ORDER BY timestamp DESC")
    fun kosyncReportsFlow(document: String): Flow<List<KosyncReport>>

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

    @Query("UPDATE phone_bookmarks SET title=:title WHERE id=:id")
    suspend fun renamePhoneBookmark(id: String, title: String)

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

    /** Takes one entry out of Recently removed, leaving the rest of its group there. */
    @Query("DELETE FROM removed_entries WHERE id=:id") suspend fun unmarkRemoved(id: String)

    /** Every stored book, Recently removed included. */
    @Query("SELECT * FROM books") fun storedBooks(): Flow<List<Book>>

    /** Every stored catalogue entry, Recently removed included. */
    @Query("SELECT * FROM wishlist") fun storedWishes(): Flow<List<WishBook>>

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

    /** Forgets that Librillo plugins received [id], so sending it again fetches it again. */
    @Query("DELETE FROM transfers WHERE bookId=:id AND deviceId LIKE 'link:%'")
    suspend fun forgetLinkDeliveries(id: String)

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

    /** Every annotation, books in Recently removed included (sync needs theirs too). */
    @Query("SELECT * FROM annotations") suspend fun everyAnnotation(): List<BookAnnotation>

    /** Every list membership, books in Recently removed included. */
    @Query("SELECT * FROM memberships") suspend fun everyMembership(): List<Membership>

    @Query("SELECT * FROM memberships WHERE bookId = :bookId")
    suspend fun membershipsOf(bookId: String): List<Membership>

    @Query("SELECT * FROM device_statistics") suspend fun allStatistics(): List<DeviceStatistics>

    @Query("SELECT * FROM device_statistics") fun statistics(): Flow<List<DeviceStatistics>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun statistics(value: DeviceStatistics)

    @Query("SELECT * FROM reading WHERE bookId NOT IN (SELECT id FROM removed_entries)")
    fun reading(): Flow<List<ReadingSnapshot>>

    /** Every annotation of the books not in Recently removed, deleted ones included. */
    @Query(
        "SELECT * FROM annotations WHERE bookId NOT IN (SELECT id FROM removed_entries) ORDER BY date DESC"
    )
    fun annotations(): Flow<List<BookAnnotation>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun reading(value: ReadingSnapshot)

    /** Every e-reader position the old SSH bridge read, books in Recently removed included. */
    @Query("SELECT * FROM reading") fun everyReading(): Flow<List<ReadingSnapshot>>

    /** Every Progress sync report from every device. */
    @Query("SELECT * FROM kosync_progress") suspend fun everyKosyncReport(): List<KosyncReport>

    @Query("SELECT * FROM device_profiles") fun deviceProfiles(): Flow<List<DeviceProfile>>

    @Query("SELECT * FROM device_profiles") suspend fun allDeviceProfiles(): List<DeviceProfile>

    @Query("SELECT * FROM device_profiles WHERE deviceKey = :key")
    suspend fun deviceProfile(key: String): DeviceProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDeviceProfile(value: DeviceProfile)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun annotation(value: BookAnnotation)

    /** One book's highlights, notes and bookmarks from every device, oldest first. */
    @Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY date, id")
    fun bookAnnotations(bookId: String): Flow<List<BookAnnotation>>

    @Query("SELECT * FROM annotations WHERE id = :id")
    suspend fun annotationById(id: String): BookAnnotation?

    /** Deletes an annotation softly: the row stays, recoverable, and sync tells the e-reader. */
    @Query("UPDATE annotations SET deletedAt = :at WHERE id = :id AND deletedAt = 0")
    suspend fun softDeleteAnnotation(id: String, at: Long): Int

    /** Brings back an annotation deleted since [since], while its text is still kept. */
    @Query("UPDATE annotations SET deletedAt = 0 WHERE id = :id AND deletedAt >= :since")
    suspend fun restoreAnnotation(id: String, since: Long): Int

    /** A new note, colour or style; [kind] follows the note (a highlight with a note is a note). */
    @Query(
        "UPDATE annotations SET note = :note, kind = :kind, color = :color, drawer = :drawer, editedAt = :editedAt WHERE id = :id"
    )
    suspend fun editAnnotation(
        id: String,
        note: String,
        kind: String,
        color: String,
        drawer: String,
        editedAt: String,
    ): Int

    /**
     * Sync's edit: the e-reader's note, colour and style, and back from deleted when it was edited
     * there (an edit beats a delete).
     */
    @Query(
        "UPDATE annotations SET note = :note, kind = :kind, color = :color, drawer = :drawer, editedAt = :editedAt, deletedAt = 0, text = CASE WHEN text = '' THEN :text ELSE text END WHERE id = :id AND bookId = :bookId"
    )
    suspend fun syncEditAnnotation(
        id: String,
        bookId: String,
        text: String,
        note: String,
        kind: String,
        color: String,
        drawer: String,
        editedAt: String,
    ): Int

    /** Annotations deleted since [since] that can still be restored, newest first. */
    @Query(
        "SELECT * FROM annotations WHERE deletedAt >= :since AND bookId NOT IN (SELECT id FROM removed_entries) ORDER BY deletedAt DESC"
    )
    fun deletedAnnotations(since: Long): Flow<List<BookAnnotation>>

    /**
     * Clears the text and note of annotations deleted before [before]. The row stays as a
     * tombstone, so sync still knows it was deleted.
     */
    @Query(
        "UPDATE annotations SET text = '', note = '' WHERE deletedAt > 0 AND deletedAt < :before AND (text != '' OR note != '')"
    )
    suspend fun clearDeletedAnnotations(before: Long): Int

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

/**
 * Sync baselines, one per book per e-reader. Blocking on purpose, like the kosync calls: the Link
 * server's sync session calls these from its own threads. Not used by the UI.
 */
@Dao
interface SyncBaselineDao {
    @Upsert fun upsert(baseline: SyncBaseline)

    @Query("SELECT * FROM sync_baseline WHERE bookId = :bookId AND ereaderId = :ereaderId")
    fun get(bookId: String, ereaderId: String): SyncBaseline?

    @Query("SELECT * FROM sync_baseline WHERE ereaderId = :ereaderId")
    fun forEreader(ereaderId: String): List<SyncBaseline>

    /** Every e-reader's baselines, as they change. */
    @Query("SELECT * FROM sync_baseline") fun all(): Flow<List<SyncBaseline>>

    @Query("DELETE FROM sync_baseline WHERE bookId = :bookId AND ereaderId = :ereaderId")
    fun delete(bookId: String, ereaderId: String)

    @Query("DELETE FROM sync_baseline WHERE ereaderId = :ereaderId")
    fun deleteForEreader(ereaderId: String)

    @Upsert fun upsertAll(baselines: List<SyncBaseline>)
}

/** Hardcover sync (M10): baselines, match records, questions and the account's observations. */
@Dao
interface HardcoverDao {
    @Query("SELECT * FROM hardcover_baseline WHERE accountId = :account")
    suspend fun baselines(account: Int): List<HardcoverBaselineRow>

    @Query("SELECT * FROM hardcover_baseline WHERE accountId = :account AND hardcoverId = :book")
    suspend fun baseline(account: Int, book: Int): HardcoverBaselineRow?

    @Upsert suspend fun saveBaseline(row: HardcoverBaselineRow)

    @Query("SELECT * FROM hardcover_matches") fun matches(): Flow<List<HardcoverMatchRow>>

    @Query("SELECT * FROM hardcover_matches") suspend fun allMatches(): List<HardcoverMatchRow>

    @Query("SELECT * FROM hardcover_matches WHERE itemId = :itemId")
    suspend fun match(itemId: String): HardcoverMatchRow?

    @Upsert suspend fun saveMatch(row: HardcoverMatchRow)

    @Query("DELETE FROM hardcover_matches WHERE itemId = :itemId")
    suspend fun deleteMatch(itemId: String)

    @Query(
        "SELECT * FROM hardcover_questions WHERE accountId = :account ORDER BY askedAt, hardcoverId"
    )
    fun questions(account: Int): Flow<List<HardcoverQuestionRow>>

    @Query("SELECT * FROM hardcover_questions WHERE accountId = :account")
    suspend fun allQuestions(account: Int): List<HardcoverQuestionRow>

    @Query("SELECT * FROM hardcover_questions WHERE accountId = :account AND hardcoverId = :book")
    suspend fun question(account: Int, book: Int): HardcoverQuestionRow?

    @Upsert suspend fun saveQuestions(rows: List<HardcoverQuestionRow>)

    @Query("DELETE FROM hardcover_questions WHERE accountId = :account")
    suspend fun deleteQuestions(account: Int)

    @Query("DELETE FROM hardcover_questions WHERE accountId = :account AND hardcoverId = :book")
    suspend fun deleteQuestion(account: Int, book: Int)

    @Query("SELECT * FROM hardcover_observations WHERE accountId = :account")
    suspend fun observations(account: Int): List<HardcoverObservation>

    /** Whether any catalogue entry, Recently removed ones included, has Hardcover book [book]. */
    @Query("SELECT EXISTS(SELECT 1 FROM wishlist WHERE hardcoverId = :book)")
    suspend fun hasEntry(book: Int): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM wishlist WHERE catalogKey = :key)")
    suspend fun catalogKeyTaken(key: String): Boolean
}

@Dao
interface StatsDao {
    @Query("SELECT * FROM reading_sessions") fun sessions(): Flow<List<ReadingSessionRow>>

    @Query("SELECT * FROM reading_sessions") suspend fun allSessions(): List<ReadingSessionRow>

    @Query("SELECT * FROM reading_sessions WHERE id IN (:ids)")
    suspend fun sessionsById(ids: List<String>): List<ReadingSessionRow>

    @Upsert suspend fun upsertSessions(rows: List<ReadingSessionRow>)

    @Query(
        "SELECT MAX(startMs / 1000 + durationSec) FROM reading_sessions WHERE source = 'KINDLE' AND deviceKey = :deviceKey"
    )
    suspend fun kindleUntil(deviceKey: String): Long?

    @Query("SELECT * FROM koreader_digests") fun digests(): Flow<List<KoreaderDigestRow>>

    @Query("SELECT * FROM koreader_digests WHERE bookId = :bookId")
    suspend fun digestsOf(bookId: String): List<KoreaderDigestRow>

    @Upsert suspend fun upsertDigests(rows: List<KoreaderDigestRow>)

    /**
     * The digests of KOReader files that match a book in the library (A1). A book in Recently
     * removed doesn't count, and neither does a digest whose book is gone.
     */
    @Query(
        "SELECT d.digest FROM koreader_digests d INNER JOIN books b ON b.id = d.bookId WHERE d.bookId NOT IN (SELECT id FROM removed_entries)"
    )
    suspend fun knownDigests(): List<String>

    @Query("SELECT * FROM stats_book_names") suspend fun allBookNames(): List<StatsBookNameRow>

    @Query("SELECT * FROM stats_book_names") fun bookNames(): Flow<List<StatsBookNameRow>>

    @Upsert suspend fun upsertBookNames(rows: List<StatsBookNameRow>)

    @Query("SELECT * FROM book_words") fun words(): Flow<List<BookWordsRow>>

    @Query("SELECT * FROM book_words") suspend fun allWords(): List<BookWordsRow>

    @Upsert suspend fun upsertWords(row: BookWordsRow)

    @Query("SELECT * FROM hardcover_reads") fun reads(): Flow<List<HardcoverReadRow>>

    @Upsert suspend fun upsertReads(rows: List<HardcoverReadRow>)

    /** Every book, Recently removed included: a removed book's sessions still have a title. */
    @Query("SELECT * FROM books") fun everyBook(): Flow<List<Book>>
}

@Database(
    entities =
        [
            Book::class,
            PhoneReading::class,
            PhoneBookmark::class,
            KosyncReport::class,
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
            SyncBaseline::class,
            HardcoverBaselineRow::class,
            HardcoverMatchRow::class,
            HardcoverQuestionRow::class,
            DeviceProfile::class,
            ReadingSessionRow::class,
            KoreaderDigestRow::class,
            BookWordsRow::class,
            HardcoverReadRow::class,
            StatsBookNameRow::class,
        ],
    version = 14,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao

    abstract fun syncBaselines(): SyncBaselineDao

    abstract fun hardcover(): HardcoverDao

    abstract fun stats(): StatsDao
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

val MIGRATION_7_8 =
    object : androidx.room.migration.Migration(7, 8) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS kosync_progress (document TEXT NOT NULL, deviceId TEXT NOT NULL, device TEXT NOT NULL, progress TEXT NOT NULL, percentage REAL NOT NULL, timestamp INTEGER NOT NULL, receivedAt INTEGER NOT NULL, PRIMARY KEY(document,deviceId))"
            )
        }
    }

/** M9: every existing book is an EPUB; sync gets one baseline row per book per e-reader. */
val MIGRATION_8_9 =
    object : androidx.room.migration.Migration(8, 9) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN format TEXT NOT NULL DEFAULT 'epub'")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_baseline (bookId TEXT NOT NULL, ereaderId TEXT NOT NULL, path TEXT NOT NULL, hash TEXT NOT NULL, status TEXT NOT NULL, statusAt INTEGER NOT NULL, annotationIds TEXT NOT NULL, collections TEXT NOT NULL, syncedAt INTEGER NOT NULL, PRIMARY KEY(bookId,ereaderId))"
            )
        }
    }

/** Schema 10: books remember when their status last changed; 0 for every existing book. */
val MIGRATION_9_10 =
    object : androidx.room.migration.Migration(9, 10) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN statusAt INTEGER NOT NULL DEFAULT 0")
        }
    }

/**
 * Schema 11 (M10, Hardcover sync): baselines, match records and questions, and catalogue entries
 * remember when their status last changed (0 for every existing entry).
 */
val MIGRATION_10_11 =
    object : androidx.room.migration.Migration(10, 11) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS hardcover_baseline (accountId INTEGER NOT NULL, hardcoverId INTEGER NOT NULL, status INTEGER NOT NULL, statusAt INTEGER NOT NULL, syncedAt INTEGER NOT NULL, PRIMARY KEY(accountId,hardcoverId))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS hardcover_matches (itemId TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL, candidates TEXT NOT NULL, metadataKey TEXT NOT NULL, checkedAt INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS hardcover_questions (accountId INTEGER NOT NULL, hardcoverId INTEGER NOT NULL, itemId TEXT NOT NULL, title TEXT NOT NULL, hardcoverStatus INTEGER NOT NULL, librilloStatus TEXT NOT NULL, askedAt INTEGER NOT NULL, PRIMARY KEY(accountId,hardcoverId))"
            )
            db.execSQL("ALTER TABLE wishlist ADD COLUMN statusAt INTEGER NOT NULL DEFAULT 0")
        }
    }

/**
 * Schema 12: device profiles, the owner's names, merges and forgets for the devices that report
 * positions. A new table; nothing existing changes.
 */
val MIGRATION_11_12 =
    object : androidx.room.migration.Migration(11, 12) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS device_profiles (deviceKey TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL DEFAULT '', mergedInto TEXT, hiddenAt INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL)"
            )
        }
    }

/**
 * Schema 13 (M11): highlights get a colour, a drawing style, an edit time and a soft delete. Every
 * existing row keeps its look (blank colour and style), is unedited and live. Sync baselines can
 * remember the annotation states both sides agreed on (null for every existing row: unknown).
 */
val MIGRATION_12_13 =
    object : androidx.room.migration.Migration(12, 13) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE annotations ADD COLUMN color TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE annotations ADD COLUMN drawer TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE annotations ADD COLUMN editedAt TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE annotations ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sync_baseline ADD COLUMN annotationStates TEXT")
        }
    }

/**
 * Schema 14 (M12): reading statistics. Four tables for sessions, KOReader file digests, word counts
 * and Hardcover read dates, and one for the names of Kindle books the phone can't match. Only new
 * tables: every existing row stays as it was.
 */
val MIGRATION_13_14 =
    object : androidx.room.migration.Migration(13, 14) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS reading_sessions (id TEXT NOT NULL, source TEXT NOT NULL, deviceKey TEXT NOT NULL, bookId TEXT, document TEXT, startMs INTEGER NOT NULL, durationSec INTEGER NOT NULL, progressStart REAL, progressEnd REAL, progressRead REAL, pages INTEGER, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_reading_sessions_startMs ON reading_sessions (startMs)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_reading_sessions_bookId ON reading_sessions (bookId)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_reading_sessions_document ON reading_sessions (document)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_reading_sessions_deviceKey ON reading_sessions (deviceKey)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS koreader_digests (digest TEXT NOT NULL, bookId TEXT NOT NULL, revisionHash TEXT NOT NULL, computedAt INTEGER NOT NULL, PRIMARY KEY(digest))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_koreader_digests_bookId ON koreader_digests (bookId)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS book_words (bookId TEXT NOT NULL, revisionHash TEXT NOT NULL, words INTEGER, countedAt INTEGER NOT NULL, PRIMARY KEY(bookId))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS hardcover_reads (accountId INTEGER NOT NULL, readId INTEGER NOT NULL, hardcoverId INTEGER NOT NULL, startedOn TEXT, finishedOn TEXT, fetchedAt INTEGER NOT NULL, PRIMARY KEY(accountId, readId))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_hardcover_reads_hardcoverId ON hardcover_reads (hardcoverId)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS stats_book_names (digest TEXT NOT NULL, title TEXT NOT NULL, authors TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(digest))"
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
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
    )
