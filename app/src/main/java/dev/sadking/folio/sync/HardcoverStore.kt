package dev.sadking.folio.sync

import dev.sadking.folio.FolioApplication
import dev.sadking.folio.data.HardcoverMatchRow
import dev.sadking.folio.data.HardcoverQuestionRow
import dev.sadking.folio.data.LibraryDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Match records ("Check these matches", Not found, Not on Hardcover) and status questions, as Room
 * keeps them (docs/HARDCOVER_SYNC.md, B5). A row that can't be read is no record: the item is
 * matched or asked again, never a crash.
 */
class HardcoverStore
internal constructor(private val database: LibraryDatabase, private val coverDir: File) {
    constructor(app: FolioApplication) : this(app.database, File(app.filesDir, COVER_DIR))

    private val dao = database.hardcover()

    fun matchRecords(): Flow<List<MatchRecord>> =
        dao.matches().map { rows -> rows.mapNotNull { it.toRecord() } }

    suspend fun allMatchRecords(): List<MatchRecord> = dao.allMatches().mapNotNull { it.toRecord() }

    /** Saves [record] over the item's old one; covers only the old one used are deleted. */
    suspend fun saveMatch(record: MatchRecord) {
        val old = dao.match(record.itemId)?.toRecord()
        dao.saveMatch(record.toRow())
        val kept = record.candidates.mapNotNull { it.coverPath }.toSet()
        deleteCovers(old?.candidates.orEmpty().mapNotNull { it.coverPath }.filter { it !in kept })
    }

    /** Also deletes the record's candidate covers (only files inside coverDir). */
    suspend fun deleteMatch(itemId: String) {
        val old = dao.match(itemId)?.toRecord()
        dao.deleteMatch(itemId)
        deleteCovers(old?.candidates.orEmpty().mapNotNull { it.coverPath })
    }

    fun questions(accountId: Int): Flow<List<StatusQuestion>> =
        dao.questions(accountId).map { rows -> rows.map { it.toQuestion() } }

    suspend fun deleteQuestion(accountId: Int, hardcoverId: Int) =
        dao.deleteQuestion(accountId, hardcoverId)

    /** Deletes [paths] that are files directly inside [coverDir]; anything else is left alone. */
    private suspend fun deleteCovers(paths: List<String>) {
        if (paths.isEmpty()) return
        withContext(Dispatchers.IO) {
            val dir = coverDir.canonicalFile
            paths
                .map { File(it).canonicalFile }
                .filter { it.parentFile == dir && it.isFile }
                .forEach { it.delete() }
        }
    }

    companion object {
        /** Under filesDir: candidate covers for "Check these matches". */
        const val COVER_DIR = "hardcover-candidates"

        internal fun MatchRecord.toRow() =
            HardcoverMatchRow(
                itemId = itemId,
                state = state.name,
                candidates = candidatesJson(candidates),
                metadataKey = metadataKey,
                checkedAt = checkedAt,
            )

        /** Null for an unknown state or broken candidates. */
        internal fun HardcoverMatchRow.toRecord(): MatchRecord? {
            val state = MatchRecordState.entries.firstOrNull { it.name == state } ?: return null
            val candidates = runCatching { parseCandidates(candidates) }.getOrNull() ?: return null
            return MatchRecord(itemId, state, candidates, metadataKey, checkedAt)
        }

        internal fun HardcoverQuestionRow.toQuestion() =
            StatusQuestion(hardcoverId, itemId, title, hardcoverStatus, librilloStatus)

        internal fun candidatesJson(candidates: List<MatchCandidateView>): String =
            JSONArray(
                    candidates.map {
                        JSONObject()
                            .put("id", it.hardcoverId)
                            .put("title", it.title)
                            .put("authors", JSONArray(it.authors))
                            .put("year", it.releaseYear ?: JSONObject.NULL)
                            .put("cover", it.coverPath ?: JSONObject.NULL)
                    }
                )
                .toString()

        internal fun parseCandidates(json: String): List<MatchCandidateView> {
            val array = JSONArray(json)
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val authors = o.getJSONArray("authors")
                MatchCandidateView(
                    hardcoverId = o.getInt("id").also { require(it > 0) },
                    title = o.getString("title"),
                    authors = (0 until authors.length()).map { authors.getString(it) },
                    releaseYear = if (o.isNull("year")) null else o.getInt("year"),
                    coverPath = if (o.isNull("cover")) null else o.getString("cover"),
                )
            }
        }
    }
}
