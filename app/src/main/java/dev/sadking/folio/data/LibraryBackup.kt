package dev.sadking.folio.data

import androidx.room.withTransaction
import dev.sadking.folio.core.*
import dev.sadking.folio.core.stats.MAX_SESSION_SECONDS
import dev.sadking.folio.core.stats.ReadingSession
import dev.sadking.folio.core.stats.SessionSource
import java.io.File
import java.util.UUID
import kotlinx.coroutines.sync.withLock
import org.json.*

/**
 * Uses immutable revision files and the repository lock; credentials and live device records are
 * excluded.
 */
class LibraryBackup(private val repo: LibraryRepository) {
    private val dao = repo.dao
    private val stats = repo.database.stats()

    suspend fun export(destination: File) =
        repo.lock.withLock {
            val files = linkedMapOf<String, File>()
            fun present(location: String): String? = location.takeIf {
                it.isNotBlank() && File(it).isFile
            }
            fun path(book: Book, location: String): String {
                val file = File(location)
                require(repo.holds(book.id, file)) { "Invalid library resource" }
                // A book in the Books folder is named by its role, so its names never meet the
                // private folder's (original.epub, <revision>.epub, cover-…).
                val name =
                    if (file.canonicalFile.parentFile == File(repo.root, book.id).canonicalFile)
                        file.name
                    else if (location == book.originalPath) "folder-original.${book.format}"
                    else "folder-current.${book.format}"
                val relative = "library/${book.id}/$name"
                files[relative] = file
                return relative
            }
            val manifest =
                repo.database.withTransaction {
                    val books =
                        dao.allBooks().map { b ->
                            File(repo.root, b.id)
                                .listFiles()
                                .orEmpty()
                                .filter { it.isFile && !it.name.startsWith('.') }
                                .forEach { path(b, it.absolutePath) }
                            JSONObject()
                                .put("id", b.id)
                                .put("metadata", metadataJson(b.metadata()))
                                .put("status", b.status)
                                .put("statusAt", b.statusAt)
                                .put("bookFormat", b.format)
                                .put("addedAt", b.addedAt)
                                // A book whose file is missing (moved away or deleted by hand) is
                                // backed up as a record: its reading data, notes and lists are
                                // kept, and the folder scan relinks the file if it comes back.
                                .put(
                                    "original",
                                    present(b.originalPath)?.let { path(b, it) } ?: JSONObject.NULL,
                                )
                                .put(
                                    "current",
                                    present(b.currentPath)?.let { path(b, it) } ?: JSONObject.NULL,
                                )
                                .put(
                                    "cover",
                                    b.coverPath?.let(::present)?.let { path(b, it) }
                                        ?: JSONObject.NULL,
                                )
                                .put("originalHash", b.originalHash)
                                .put("currentHash", b.currentHash)
                        }
                    JSONObject()
                        .put("format", "folio-backup")
                        .put("version", 4)
                        .put("createdAt", System.currentTimeMillis())
                        .put("books", JSONArray(books))
                        .put(
                            "wishlist",
                            JSONArray(
                                dao.allWishes().map { wish ->
                                    val cover =
                                        wish.coverPath?.let { location ->
                                            val file = File(location)
                                            require(
                                                file.canonicalFile.parentFile ==
                                                    File(repo.root, wish.id).canonicalFile
                                            )
                                            val relative = "library/${wish.id}/${file.name}"
                                            files[relative] = file
                                            relative
                                        }
                                    wishJson(wish).put("coverPath", cover ?: JSONObject.NULL)
                                }
                            ),
                        )
                        .put(
                            "shelves",
                            JSONArray(
                                dao.allShelves().map {
                                    JSONObject()
                                        .put("id", it.id)
                                        .put("name", it.name)
                                        .put("field", it.ruleField)
                                        .put("value", it.ruleValue)
                                        .put("sync", it.syncEnabled)
                                }
                            ),
                        )
                        .put(
                            "memberships",
                            JSONArray(
                                dao.allMemberships().map {
                                    JSONObject().put("book", it.bookId).put("shelf", it.shelfId)
                                }
                            ),
                        )
                        .put(
                            "wishMemberships",
                            JSONArray(
                                dao.allWishMemberships().map {
                                    JSONObject().put("wish", it.wishId).put("shelf", it.shelfId)
                                }
                            ),
                        )
                        .put(
                            "phoneReading",
                            JSONArray(
                                dao.allPhoneReading().map { p ->
                                    JSONObject()
                                        .put("bookId", p.bookId)
                                        .put("revisionHash", p.revisionHash)
                                        .put("locator", p.locator)
                                        .put("xpointer", p.xpointer)
                                        .put("percent", p.percent)
                                        .put("updatedAt", p.updatedAt)
                                }
                            ),
                        )
                        .put(
                            "phoneBookmarks",
                            JSONArray(
                                dao.allPhoneBookmarks().map { p ->
                                    JSONObject()
                                        .put("id", p.id)
                                        .put("bookId", p.bookId)
                                        .put("revisionHash", p.revisionHash)
                                        .put("locator", p.locator)
                                        .put("title", p.title)
                                        .put("createdAt", p.createdAt)
                                }
                            ),
                        )
                        .put("reading", JSONArray(dao.allReading().map { readingJson(it) }))
                        .put(
                            "statistics",
                            JSONArray(
                                dao.allStatistics().map {
                                    JSONObject()
                                        .put("deviceId", it.deviceId)
                                        .put("totalSeconds", it.totalSeconds)
                                        .put("exportedAt", it.exportedAt)
                                        .put("fetchedAt", it.fetchedAt)
                                        .put("days", JSONArray(it.days))
                                }
                            ),
                        )
                        .put(
                            "annotations",
                            JSONArray(dao.allAnnotations().map { annotationJson(it) }),
                        )
                        // Added in schema 12; older backups have neither and still restore.
                        .put(
                            "kosync",
                            JSONArray(dao.everyKosyncReport().map { kosyncJson(it) }),
                        )
                        .put(
                            "deviceProfiles",
                            JSONArray(dao.allDeviceProfiles().map { deviceProfileJson(it) }),
                        )
                        // Added with the statistics (D8, A1); older backups have none of these.
                        .put(
                            "readingSessions",
                            JSONArray(stats.allSessions().map { readingSessionJson(it) }),
                        )
                        .put("bookWords", JSONArray(stats.allWords().map { bookWordsJson(it) }))
                        .put(
                            "statsBookNames",
                            JSONArray(stats.allBookNames().map { statsBookNameJson(it) }),
                        )
                        .toString()
                        .toByteArray()
                }
            BackupArchive.write(destination, manifest, files)
        }

    suspend fun restore(archive: File): String =
        repo.lock.withLock {
            val stage = File(repo.root, ".restore-${UUID.randomUUID()}")
            val created = mutableListOf<File>()
            var committed = false
            try {
                val root = JSONObject(BackupArchive.unpack(archive, stage).toString(Charsets.UTF_8))
                require(
                    root.getString("format") == "folio-backup" && root.getInt("version") in 1..4
                ) {
                    "Unsupported backup version"
                }
                fun id(value: String): String {
                    UUID.fromString(value)
                    return value
                }
                val books =
                    root.getJSONArray("books").objects().map { o ->
                        val bookId = id(o.getString("id"))
                        fun resource(key: String): File {
                            val name = o.getString(key)
                            require(name.startsWith("library/$bookId/"))
                            return File(stage, name).also { require(it.isFile) }
                        }
                        // A book backed up while its file was missing has no file here.
                        val original = if (o.isNull("original")) null else resource("original")
                        val current = if (o.isNull("current")) null else resource("current")
                        require(
                            (original == null ||
                                Epub.sha256(original) == o.getString("originalHash")) &&
                                (current == null ||
                                    Epub.sha256(current) == o.getString("currentHash"))
                        ) {
                            "Book fingerprint mismatch"
                        }
                        // Backups made before PDFs and comics hold only EPUBs.
                        val format = o.optString("bookFormat", BookFormats.EPUB)
                        require(format in BookFormats.ALL) { "Unsupported book format" }
                        for (file in listOfNotNull(original, current).distinct()) {
                            if (format == BookFormats.EPUB) Epub.parse(file)
                            else
                                require(BookFormats.detect(file) == format) {
                                    "Book file does not match its format"
                                }
                        }
                        val cover =
                            if (o.isNull("cover")) null
                            else
                                resource("cover").also { require(it.length() <= 10L * 1024 * 1024) }
                        val m = parseMetadata(o.getJSONObject("metadata"))
                        require(m.title.isNotBlank())
                        val status = o.getString("status")
                        require(status in phoneStatuses)
                        Book(
                                bookId,
                                m.title,
                                encode(m.authors),
                                originalPath = original?.name.orEmpty(),
                                currentPath = current?.name.orEmpty(),
                                coverPath = cover?.name,
                                originalHash = o.getString("originalHash"),
                                currentHash = o.getString("currentHash"),
                                addedAt = o.getLong("addedAt"),
                                status = status,
                                format = format,
                                // Older backups don't have it: 0 is "unknown".
                                statusAt = o.optLong("statusAt", 0).coerceAtLeast(0),
                            )
                            .withMetadata(m)
                    }
                require(books.size <= 10000 && books.map { it.id }.distinct().size == books.size)
                val shelves =
                    root.getJSONArray("shelves").objects().map { o ->
                        Shelf(
                                id(o.getString("id")),
                                o.getString("name"),
                                o.getString("field"),
                                o.getString("value"),
                                o.getBoolean("sync"),
                            )
                            .also {
                                require(
                                    it.name.isNotBlank() &&
                                        it.name.length <= 120 &&
                                        it.ruleField in LibraryRules.fields
                                )
                            }
                    }
                require(
                    shelves.map { it.id }.distinct().size == shelves.size &&
                        shelves.map { it.name }.distinct().size == shelves.size
                )
                val sourceIds = books.map { it.id }.toSet()
                val existing = dao.allStoredBooks()
                val mappings = books.associate { b ->
                    val sameId = existing.firstOrNull { it.id == b.id }
                    require(sameId == null || sameId.originalHash == b.originalHash) {
                        "Conflicting book identity; nothing restored"
                    }
                    b.id to (existing.firstOrNull { it.originalHash == b.originalHash }?.id ?: b.id)
                }
                val shelfMap = mutableMapOf<String, String>()
                val newShelves = mutableListOf<Shelf>()
                val localShelves = dao.allShelves()
                shelves.forEach { s ->
                    val sameId = localShelves.firstOrNull { it.id == s.id }
                    require(sameId == null || sameId.name == s.name) {
                        "Conflicting collection identity"
                    }
                    val existingShelf = localShelves.firstOrNull { it.name == s.name }
                    shelfMap[s.id] = existingShelf?.id ?: s.id
                    if (existingShelf == null)
                        newShelves +=
                            s.copy(
                                syncEnabled = false
                            ) // Restored collections never enable unattended transfer.
                }
                val memberships =
                    root.getJSONArray("memberships").objects().map { o ->
                        Membership(
                            mappings[o.getString("book")] ?: error("Unknown backup book"),
                            shelfMap[o.getString("shelf")] ?: error("Unknown backup collection"),
                        )
                    }
                val reading =
                    root.getJSONArray("reading").objects().map { o ->
                        parseReading(o)
                            .also { require(it.bookId in sourceIds) }
                            .copy(bookId = mappings.getValue(o.getString("bookId")))
                    }
                val annotations =
                    root.getJSONArray("annotations").objects().map { o ->
                        parseAnnotation(o)
                            .also { require(it.bookId in sourceIds) }
                            .copy(bookId = mappings.getValue(o.getString("bookId")))
                    }
                val statistics =
                    root.optJSONArray("statistics")?.objects()?.map { o ->
                        DeviceStatistics(
                                o.getString("deviceId"),
                                o.getLong("totalSeconds"),
                                o.getLong("exportedAt"),
                                o.getLong("fetchedAt"),
                                o.getJSONArray("days").toString(),
                            )
                            .also { validateStatistics(it) }
                    } ?: emptyList()
                val wishes =
                    root.optJSONArray("wishlist")?.objects()?.map { value ->
                        parseWish(value)
                            .also { wish ->
                                require(wish.id !in sourceIds)
                                wish.coverPath?.let { path ->
                                    require(
                                        path.startsWith("library/${wish.id}/") &&
                                            File(stage, path).isFile
                                    )
                                    require(File(stage, path).length() <= 10L * 1024 * 1024)
                                }
                            }
                            .let { wish ->
                                wish.copy(
                                    linkedBookId = wish.linkedBookId?.let { mappings[it] },
                                    hardcoverSynced = false,
                                )
                            }
                    } ?: emptyList()
                require(
                    wishes.map { it.id }.distinct().size == wishes.size &&
                        wishes.map { it.catalogKey }.distinct().size == wishes.size
                )
                val localWishes = dao.allStoredWishes()
                val wishMap = wishes.associate { w ->
                    w.id to (localWishes.firstOrNull { it.catalogKey == w.catalogKey }?.id ?: w.id)
                }
                val wishMemberships =
                    root.optJSONArray("wishMemberships")?.objects()?.map { o ->
                        WishMembership(
                            wishMap[o.getString("wish")]
                                ?: error("Unknown catalogue book in backup"),
                            shelfMap[o.getString("shelf")] ?: error("Unknown list in backup"),
                        )
                    } ?: emptyList()
                val phonePositions =
                    root
                        .optJSONArray("phoneReading")
                        ?.objects()
                        ?.map { o ->
                            val bookId =
                                mappings[o.getString("bookId")]
                                    ?: error("Unknown phone reading book")
                            val revision =
                                o.getString("revisionHash").also {
                                    require(it.matches(Regex("[a-f0-9]{64}")))
                                }
                            val locator =
                                o.getString("locator").also {
                                    require(it.length <= 65536)
                                    require(
                                        org.readium.r2.shared.publication.Locator.fromJSON(
                                            JSONObject(it)
                                        ) != null
                                    )
                                }
                            val pointer =
                                o.getString("xpointer").also { require(it.length <= 4096) }
                            val percent =
                                if (o.isNull("percent")) null
                                else o.getDouble("percent").also { require(it in 0.0..1.0) }
                            PhoneReading(
                                bookId,
                                revision,
                                locator,
                                pointer,
                                percent,
                                o.getLong("updatedAt"),
                            )
                        }
                        .orEmpty()
                val phoneMarks =
                    root
                        .optJSONArray("phoneBookmarks")
                        ?.objects()
                        ?.map { o ->
                            val locator =
                                o.getString("locator").also {
                                    require(it.length <= 65536)
                                    require(
                                        org.readium.r2.shared.publication.Locator.fromJSON(
                                            JSONObject(it)
                                        ) != null
                                    )
                                }
                            PhoneBookmark(
                                id(o.getString("id")),
                                mappings[o.getString("bookId")] ?: error("Unknown bookmark book"),
                                o.getString("revisionHash").also {
                                    require(it.matches(Regex("[a-f0-9]{64}")))
                                },
                                locator,
                                o.getString("title").take(2000),
                                o.getLong("createdAt"),
                            )
                        }
                        .orEmpty()
                // Progress sync reports and device names: optional, and a row this version cannot
                // read is skipped rather than stopping the restore.
                val kosync =
                    root
                        .optJSONArray("kosync")
                        ?.objects()
                        ?.mapNotNull { runCatching { parseKosync(it) }.getOrNull() }
                        .orEmpty()
                val deviceProfiles =
                    root
                        .optJSONArray("deviceProfiles")
                        ?.objects()
                        ?.mapNotNull { runCatching { parseDeviceProfile(it) }.getOrNull() }
                        .orEmpty()
                // Statistics (D8, A1): optional, and a row this version cannot read is skipped.
                // A session that appears twice keeps its longer copy.
                val sessions =
                    root
                        .optJSONArray("readingSessions")
                        ?.objects()
                        ?.mapNotNull { o ->
                            runCatching { parseReadingSession(o, mappings) }.getOrNull()
                        }
                        ?.groupBy { it.id }
                        ?.values
                        ?.map { rows -> rows.maxBy { it.durationSec } }
                        .orEmpty()
                val bookWords =
                    root
                        .optJSONArray("bookWords")
                        ?.objects()
                        ?.mapNotNull { o ->
                            runCatching { parseBookWords(o, mappings) }.getOrNull()
                        }
                        .orEmpty()
                val bookNames =
                    root
                        .optJSONArray("statsBookNames")
                        ?.objects()
                        ?.mapNotNull { o ->
                            runCatching { parseStatsBookName(o) }.getOrNull()
                        }
                        ?.groupBy { it.digest }
                        ?.values
                        ?.map { rows -> rows.maxBy { it.updatedAt } }
                        .orEmpty()
                var added = 0
                repo.database.withTransaction {
                    books.forEach { b ->
                        dao.removedEntry(mappings.getValue(b.id))?.let {
                            dao.restoreRemoved(it.groupId)
                        }
                        if (existing.none { it.id == mappings[b.id] }) {
                            val source = File(stage, "library/${b.id}")
                            val destination = File(repo.root, b.id)
                            require(!destination.exists()) {
                                "Untracked library folder exists; restore cancelled"
                            }
                            // A record backed up without its file may have no folder at all.
                            if (source.exists()) {
                                check(source.renameTo(destination))
                                created += destination
                            }
                            fun placed(name: String) =
                                if (name.isEmpty()) "" else File(destination, name).absolutePath
                            dao.insert(
                                b.copy(
                                    originalPath = placed(b.originalPath),
                                    currentPath = placed(b.currentPath),
                                    coverPath =
                                        b.coverPath?.let { File(destination, it).absolutePath },
                                )
                            )
                            added++
                        }
                    }
                    wishes.forEach { wish ->
                        localWishes
                            .firstOrNull { it.catalogKey == wish.catalogKey }
                            ?.let { local ->
                                dao.removedEntry(local.id)?.let { dao.restoreRemoved(it.groupId) }
                            }
                        if (localWishes.none { it.catalogKey == wish.catalogKey }) {
                            require(
                                localWishes.none { it.id == wish.id } &&
                                    existing.none { it.id == wish.id }
                            ) {
                                "Conflicting Want to Read identity"
                            }
                            val destination = File(repo.root, wish.id)
                            val cover =
                                wish.coverPath?.let { path ->
                                    require(!destination.exists()) {
                                        "Untracked Want to Read folder exists"
                                    }
                                    check(File(stage, "library/${wish.id}").renameTo(destination))
                                    created += destination
                                    File(destination, File(path).name).absolutePath
                                }
                            dao.insertWish(wish.copy(coverPath = cover))
                        }
                    }
                    newShelves.forEach { dao.insertShelf(it) }
                    memberships.forEach { dao.addMembership(it) }
                    wishMemberships.forEach {
                        dao.addWishMembership(it)
                        dao.wish(it.wishId)?.linkedBookId?.let { book ->
                            dao.addMembership(Membership(book, it.shelfId))
                        }
                    }
                    val currentReading = dao.allReading().associateBy { it.bookId to it.deviceId }
                    reading.forEach {
                        if (
                            currentReading[it.bookId to it.deviceId]?.fetchedAt?.let { time ->
                                time >= it.fetchedAt
                            } != true
                        )
                            dao.reading(it)
                    }
                    phonePositions.forEach { position ->
                        if (
                            (dao.phoneReading(position.bookId, position.revisionHash)?.updatedAt
                                ?: -1) < position.updatedAt
                        )
                            dao.savePhoneReading(position)
                    }
                    phoneMarks.forEach { dao.addPhoneBookmark(it) }
                    annotations.forEach { dao.annotation(it) }
                    val currentStats = dao.allStatistics().associateBy { it.deviceId }
                    statistics.forEach {
                        if (
                            currentStats[it.deviceId]?.exportedAt?.let { time ->
                                time >= it.exportedAt
                            } != true
                        )
                            dao.statistics(it)
                    }
                    // The newer of the phone's report and the backup's wins, per device and book.
                    val currentKosync =
                        dao.everyKosyncReport().associateBy { it.document to it.deviceId }
                    kosync.forEach { report ->
                        val mine = currentKosync[report.document to report.deviceId]
                        if (
                            mine == null ||
                                compareValuesBy(mine, report, { it.timestamp }, { it.receivedAt }) <
                                    0
                        )
                            dao.saveKosyncReport(report)
                    }
                    val currentProfiles = dao.allDeviceProfiles().associateBy { it.deviceKey }
                    deviceProfiles.forEach { profile ->
                        if (
                            (currentProfiles[profile.deviceKey]?.updatedAt ?: -1) <
                                profile.updatedAt
                        )
                            dao.saveDeviceProfile(profile)
                    }
                    // Sessions are kept by length: a stored session is never shrunk.
                    stats.keepSessions(sessions, System.currentTimeMillis())
                    // Word counts only for a revision the restored book has, and only where the
                    // book has no count yet.
                    val counted = stats.allWords().map { it.bookId }.toSet()
                    for (row in bookWords.distinctBy { it.bookId }) {
                        if (row.bookId in counted) continue
                        if (dao.book(row.bookId)?.currentHash == row.revisionHash)
                            stats.upsertWords(row)
                    }
                    // The newer name wins, per digest.
                    val currentNames = stats.allBookNames().associateBy { it.digest }
                    bookNames
                        .filter { (currentNames[it.digest]?.updatedAt ?: -1) < it.updatedAt }
                        .let { stats.upsertBookNames(it) }
                }
                committed = true
                "$added books restored · existing books retained · credentials excluded"
            } finally {
                if (!committed) created.forEach { it.deleteRecursively() }
                stage.deleteRecursively()
            }
        }
}

internal fun JSONArray.objects(): List<JSONObject> {
    require(length() <= 100000)
    return (0 until length()).map { getJSONObject(it) }
}

internal fun metadataJson(m: Metadata) =
    JSONObject()
        .put("title", m.title)
        .put("authors", JSONArray(m.authors))
        .put("description", m.description)
        .put("publisher", m.publisher)
        .put("language", m.language)
        .put("identifiers", JSONArray(m.identifiers))
        .put("series", m.series)
        .put("seriesIndex", m.seriesIndex)
        .put("tags", JSONArray(m.tags))

internal fun parseMetadata(o: JSONObject) =
    Metadata(
        o.getString("title"),
        decode(o.getJSONArray("authors").toString()),
        o.getString("description"),
        o.getString("publisher"),
        o.getString("language"),
        decode(o.getJSONArray("identifiers").toString()),
        o.getString("series"),
        o.getString("seriesIndex"),
        decode(o.getJSONArray("tags").toString()),
    )

internal fun readingJson(r: ReadingSnapshot) =
    JSONObject()
        .put("bookId", r.bookId)
        .put("deviceId", r.deviceId)
        .put("percent", r.percent ?: JSONObject.NULL)
        .put("position", r.position)
        .put("status", r.status)
        .put("rating", r.rating)
        .put("fetchedAt", r.fetchedAt)
        .put("sourcePath", r.sourcePath)
        .put("sourceHash", r.sourceHash)
        .put("documentHash", r.documentHash)

internal fun parseReading(o: JSONObject) =
    ReadingSnapshot(
        o.getString("bookId"),
        o.getString("deviceId"),
        if (o.isNull("percent")) null else o.getDouble("percent").also { require(it in 0.0..1.0) },
        o.getString("position"),
        o.getString("status"),
        o.getInt("rating").also { require(it in 0..5) },
        o.getLong("fetchedAt"),
        o.getString("sourcePath"),
        o.getString("sourceHash"),
        o.getString("documentHash"),
    )

internal fun kosyncJson(r: KosyncReport) =
    JSONObject()
        .put("document", r.document)
        .put("deviceId", r.deviceId)
        .put("device", r.device)
        .put("progress", r.progress)
        .put("percentage", r.percentage)
        .put("timestamp", r.timestamp)
        .put("receivedAt", r.receivedAt)

internal fun parseKosync(o: JSONObject) =
    KosyncReport(
        o.getString("document").also { require(it.isNotBlank() && it.length <= 128) },
        o.getString("deviceId").also { require(it.isNotBlank() && it.length <= 256) },
        o.getString("device").also { require(it.length <= 256) },
        o.getString("progress").also { require(it.length <= 65536) },
        o.getDouble("percentage").also { require(it.isFinite()) },
        o.getLong("timestamp"),
        o.getLong("receivedAt"),
    )

internal fun deviceProfileJson(p: DeviceProfile) =
    JSONObject()
        .put("deviceKey", p.deviceKey)
        .put("displayName", p.displayName)
        .put("mergedInto", p.mergedInto ?: JSONObject.NULL)
        .put("hiddenAt", p.hiddenAt)
        .put("updatedAt", p.updatedAt)

internal fun parseDeviceProfile(o: JSONObject) =
    DeviceProfile(
        o.getString("deviceKey").also { require(it.isNotBlank() && it.length <= 300) },
        o.optString("displayName").also { require(it.length <= DeviceRoster.MAX_NAME) },
        if (o.isNull("mergedInto")) null
        else o.getString("mergedInto").also { require(it.isNotBlank() && it.length <= 300) },
        o.optLong("hiddenAt").coerceAtLeast(0),
        o.getLong("updatedAt"),
    )

internal fun annotationJson(a: BookAnnotation) =
    JSONObject()
        .put("id", a.id)
        .put("bookId", a.bookId)
        .put("deviceId", a.deviceId)
        .put("text", a.text)
        .put("note", a.note)
        .put("chapter", a.chapter)
        .put("date", a.date)
        .put("position", a.position)
        .put("endPosition", a.endPosition)
        .put("kind", a.kind)
        .put("color", a.color)
        .put("drawer", a.drawer)
        .put("editedAt", a.editedAt)
        .put("deletedAt", a.deletedAt)

/**
 * An annotation from a backup. Backups made before schema 13 have no colour, style, edit time or
 * deletion: such a row comes back as it was, live and unedited.
 */
internal fun parseAnnotation(o: JSONObject) =
    BookAnnotation(
        o.getString("id"),
        o.getString("bookId"),
        o.getString("deviceId"),
        o.getString("text"),
        o.getString("note"),
        o.getString("chapter"),
        o.getString("date"),
        o.getString("position"),
        o.getString("endPosition"),
        o.getString("kind"),
        color = o.optString("color").also { require(it.length <= 32) },
        drawer = o.optString("drawer").also { require(it.length <= 32) },
        editedAt = o.optString("editedAt").also { require(it.length <= 32) },
        deletedAt = o.optLong("deletedAt").coerceAtLeast(0),
    )

internal fun validateStatistics(value: DeviceStatistics) {
    require(value.totalSeconds in 0..10_000_000_000L && value.exportedAt > 0)
    val days = JSONArray(value.days)
    require(days.length() <= 366)
    val dates = mutableSetOf<String>()
    days.objects().forEach { d ->
        require(dates.add(java.time.LocalDate.parse(d.getString("date")).toString()))
        require(d.getLong("seconds") in 0..10_000_000L)
    }
}

internal fun wishJson(wish: WishBook): JSONObject =
    JSONObject()
        .put("id", wish.id)
        .put("catalogKey", wish.catalogKey)
        .put("title", wish.title)
        .put("authors", JSONArray(wish.authors))
        .put("coverPath", wish.coverPath ?: JSONObject.NULL)
        .put("hardcoverId", wish.hardcoverId ?: JSONObject.NULL)
        .put("hardcoverTitle", wish.hardcoverTitle)
        .put("hardcoverSynced", wish.hardcoverSynced)
        .put("addedAt", wish.addedAt)
        .put("linkedBookId", wish.linkedBookId ?: JSONObject.NULL)
        .put("details", JSONObject(wish.details))
        .put("status", wish.status)
        .put("statusAt", wish.statusAt)

internal fun parseWish(value: JSONObject): WishBook {
    val id = value.getString("id").also { UUID.fromString(it) }
    val catalog =
        value.getString("catalogKey").also {
            require(
                it.matches(Regex("/works/OL[0-9]+W|hardcover:[1-9][0-9]*|manual:[a-fA-F0-9-]{36}"))
            )
        }
    val title = value.getString("title").also { require(it.isNotBlank() && it.length <= 2000) }
    val authors = value.getJSONArray("authors").also { require(it.length() <= 100) }
    decode(authors.toString()).forEach { require(it.length <= 1000) }
    val hardcover =
        if (value.isNull("hardcoverId")) null
        else value.getInt("hardcoverId").also { require(it > 0) }
    val linked =
        if (value.isNull("linkedBookId")) null
        else value.getString("linkedBookId").also { UUID.fromString(it) }
    return WishBook(
        id,
        catalog,
        title,
        authors.toString(),
        if (value.isNull("coverPath")) null else value.getString("coverPath"),
        hardcover,
        value.getString("hardcoverTitle").also { require(it.length <= 2000) },
        false,
        value.getLong("addedAt"),
        linked,
        (value.optJSONObject("details") ?: JSONObject())
            .also { if (it.length() > 0) parseMetadata(it) }
            .toString(),
        value.optString("status", "").also { require(it.isBlank() || it in phoneStatuses) },
        // Backups from before schema 11 have no status time: unknown.
        value.optLong("statusAt", 0).coerceAtLeast(0),
    )
}

internal fun readingSessionJson(r: ReadingSessionRow): JSONObject =
    JSONObject()
        .put("id", r.id)
        .put("source", r.source)
        .put("deviceKey", r.deviceKey)
        .put("bookId", r.bookId ?: JSONObject.NULL)
        .put("document", r.document ?: JSONObject.NULL)
        .put("startMs", r.startMs)
        .put("durationSec", r.durationSec)
        .put("progressStart", r.progressStart ?: JSONObject.NULL)
        .put("progressEnd", r.progressEnd ?: JSONObject.NULL)
        .put("progressRead", r.progressRead ?: JSONObject.NULL)
        .put("pages", r.pages ?: JSONObject.NULL)
        .put("updatedAt", r.updatedAt)

/**
 * A session from a backup (D8). A book the backup doesn't list keeps its id, so its sessions stay
 * with it. Throws on a row this version cannot read; the caller skips that row.
 */
internal fun parseReadingSession(o: JSONObject, mappings: Map<String, String>): ReadingSession {
    val session =
        ReadingSession(
            id = o.getString("id").also { require(it.isNotBlank() && it.length <= 256) },
            source =
                SessionSource.entries.firstOrNull { it.name == o.getString("source") }
                    ?: error("Unknown session source"),
            deviceKey =
                o.getString("deviceKey").also { require(it.isNotBlank() && it.length <= 300) },
            bookId =
                if (o.isNull("bookId")) null
                else
                    o.getString("bookId")
                        .let { mappings[it] ?: it }
                        .also {
                            require(it.isNotBlank() && it.length <= 256)
                        },
            document =
                if (o.isNull("document")) null
                else o.getString("document").also { require(it.matches(Regex("[a-f0-9]{32}"))) },
            startMs = o.getLong("startMs").also { require(it > 0) },
            durationSec = o.getLong("durationSec").also { require(it in 1..MAX_SESSION_SECONDS) },
            progressStart = o.fraction("progressStart"),
            progressEnd = o.fraction("progressEnd"),
            progressRead = o.fraction("progressRead"),
            pages = if (o.isNull("pages")) null else o.getInt("pages").also { require(it >= 0) },
        )
    require(!session.bookId.isNullOrBlank() || !session.document.isNullOrBlank())
    return session
}

private fun JSONObject.fraction(key: String): Double? =
    if (isNull(key)) null else getDouble(key).also { require(it in 0.0..1.0) }

internal fun bookWordsJson(r: BookWordsRow): JSONObject =
    JSONObject()
        .put("bookId", r.bookId)
        .put("revisionHash", r.revisionHash)
        .put("words", r.words ?: JSONObject.NULL)
        .put("countedAt", r.countedAt)

/** A word count from a backup, attached to the restored id of its book. */
internal fun parseBookWords(o: JSONObject, mappings: Map<String, String>): BookWordsRow =
    BookWordsRow(
        bookId = mappings[o.getString("bookId")] ?: error("Unknown word count book"),
        revisionHash = o.getString("revisionHash").also { require(it.length <= 128) },
        words = if (o.isNull("words")) null else o.getLong("words").also { require(it >= 0) },
        countedAt = o.getLong("countedAt"),
    )

internal fun statsBookNameJson(r: StatsBookNameRow): JSONObject =
    JSONObject()
        .put("digest", r.digest)
        .put("title", r.title)
        .put("authors", r.authors)
        .put("updatedAt", r.updatedAt)

/** A Kindle book's name from a backup (A1), with the limits the sync server applies. */
internal fun parseStatsBookName(o: JSONObject): StatsBookNameRow {
    val digest = o.getString("digest")
    val title = o.getString("title")
    val authors = o.getString("authors")
    require(
        digest.matches(Regex("[0-9a-f]{32}")) &&
            title.isNotBlank() &&
            title.length <= 512 &&
            title.none { it.isISOControl() } &&
            authors.length <= 512 &&
            authors.none { it.isISOControl() }
    )
    return StatsBookNameRow(digest, title, authors, o.getLong("updatedAt"))
}
