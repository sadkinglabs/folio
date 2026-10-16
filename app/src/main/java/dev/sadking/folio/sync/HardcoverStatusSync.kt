package dev.sadking.folio.sync

import androidx.room.withTransaction
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.core.hardcover.HardcoverBaseline
import dev.sadking.folio.core.hardcover.HardcoverMerge
import dev.sadking.folio.core.hardcover.HardcoverStatuses
import dev.sadking.folio.core.hardcover.LocalSide
import dev.sadking.folio.core.hardcover.MergeInput
import dev.sadking.folio.core.hardcover.MergeStep
import dev.sadking.folio.core.hardcover.RemoteSide
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.HardcoverBaselineRow
import dev.sadking.folio.data.HardcoverCover
import dev.sadking.folio.data.HardcoverHttpException
import dev.sadking.folio.data.HardcoverLinks
import dev.sadking.folio.data.HardcoverObservation
import dev.sadking.folio.data.HardcoverQuestionRow
import dev.sadking.folio.data.LibraryRepository
import dev.sadking.folio.data.WishBook
import dev.sadking.folio.data.encode
import dev.sadking.folio.data.hardcoverCatalogKey
import dev.sadking.folio.data.parseWish
import dev.sadking.folio.data.wishJson
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Two-way status sync between Librillo and the connected Hardcover account (M10). For each linked
 * catalogue entry it compares Librillo's status, Hardcover's (from the pull) and the baseline both
 * last agreed on, and applies [HardcoverMerge.plan]: down to Librillo, up to Hardcover, or a
 * question for the owner. Rules: docs/HARDCOVER_SYNC.md, A3 and B5.
 *
 * Nothing is deleted because it is missing on the other side. Each item is saved before the next,
 * so a run that stops resumes where it left off: an item whose request failed has no new baseline
 * and is planned again after the next pull.
 */
class HardcoverStatusSync
internal constructor(
    private val repo: LibraryRepository,
    private val store: HardcoverStore,
    private val covers: HardcoverCover,
    private val now: () -> Long,
    private val zone: () -> ZoneId,
) {
    constructor(
        app: FolioApplication
    ) : this(
        app.repository,
        HardcoverStore(app),
        HardcoverCover(),
        System::currentTimeMillis,
        ZoneId::systemDefault,
    )

    private val dao = repo.dao
    private val hardcover = repo.database.hardcover()

    /**
     * Syncs statuses with [library], the account's whole Hardcover library as just pulled. With
     * [createEntries], Hardcover books Librillo has no catalogue entry for become entries. Throws
     * what [api] throws for the run to handle (rate limits, server errors, no connection, a
     * rejected token); a request Hardcover refuses for one book skips that book this time.
     */
    suspend fun sync(
        api: HardcoverApi,
        library: List<RemoteUserBook>,
        createEntries: Boolean,
    ): StatusSyncResult =
        withContext(Dispatchers.IO) {
            val account = api.accountId
            val at = now()
            val zone = zone()
            val today = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            val pulled = LinkedHashMap<Int, RemoteUserBook>()
            library.forEach { pulled.putIfAbsent(it.hardcoverId, it) }
            val baselines = hardcover.baselines(account).associateBy { it.hardcoverId }
            // An empty library while books were agreed to be there is not believed: a glitch must
            // not make Librillo think every book left Hardcover.
            val trustAbsence =
                pulled.isNotEmpty() || baselines.values.none { it.status in KNOWN_STATUSES }

            // 1. What the phone last saw on Hardcover, for the reading sheet and progress.
            repo.database.withTransaction {
                pulled.values.forEach { observe(account, it.hardcoverId, it.status, it, at) }
                if (trustAbsence)
                    hardcover
                        .observations(account)
                        .filter { it.hardcoverId !in pulled }
                        .forEach { dao.deleteHardcoverObservation(account, it.hardcoverId) }
            }

            // 2. One input per linked catalogue entry, Recently removed left alone.
            val removed = dao.allRemoved().map { it.id }.toSet()
            val books = dao.allStoredBooks().associateBy { it.id }
            val entries =
                dao.allStoredWishes()
                    .filter { it.hardcoverId != null && it.id !in removed }
                    .filter { it.linkedBookId == null || it.linkedBookId !in removed }
                    .sortedWith(compareBy({ it.addedAt }, { it.id }))
                    .distinctBy { it.hardcoverId }

            // 3. Plan and apply, one item at a time.
            var down = 0
            var up = 0
            var gone = 0
            val asked = mutableListOf<Pair<MergeStep.Ask, HardcoverBaselineRow?>>()
            for (entry in entries) {
                val hardcoverId = entry.hardcoverId!!
                val remote = pulled[hardcoverId]
                val baseline = baselines[hardcoverId]
                if (remote == null && !trustAbsence && baseline?.status in KNOWN_STATUSES) continue
                val step =
                    HardcoverMerge.plan(
                        MergeInput(
                            hardcoverId,
                            localSide(entry, books),
                            remote?.let { RemoteSide(it.userBookId, it.status, it.updatedAt) },
                            baseline?.let { HardcoverBaseline(it.status, it.statusAt) },
                        ),
                        zone,
                        today,
                        at,
                    ) ?: continue
                when (step) {
                    is MergeStep.Agree -> {
                        agree(account, hardcoverId, step.status, step.statusAt)
                        if (step.status != HardcoverStatuses.NONE) published(entry)
                    }
                    is MergeStep.Down ->
                        skippable {
                            repo.manageBatch(
                                setOf(step.itemId),
                                status = step.word,
                                statusAt = step.statusAt,
                            )
                            agree(account, hardcoverId, step.status, step.statusAt)
                            published(entry)
                            down++
                        }
                    is MergeStep.Up ->
                        skippable {
                            if (step.status == HardcoverStatuses.READ)
                                api.markRead(hardcoverId, step.finishedOn)
                            else api.setStatus(hardcoverId, step.status, step.userBookId)
                            repo.database.withTransaction {
                                agree(account, hardcoverId, step.status, step.statusAt)
                                observe(account, hardcoverId, step.status, remote, now())
                            }
                            published(entry)
                            up++
                        }
                    is MergeStep.Gone -> {
                        agree(account, hardcoverId, HardcoverStatuses.NONE, step.statusAt)
                        gone++
                    }
                    is MergeStep.Ask -> asked += step to baseline
                }
            }

            // 4. The open questions are exactly this sync's.
            val questions = replaceQuestions(account, asked, at)

            // 5. Books added on Hardcover appear in Librillo.
            val added = if (createEntries) createEntries(account, pulled.values, at) else 0
            fillCovers(pulled, at + COVER_BUDGET_MS)
            StatusSyncResult(down = down, up = up, asked = questions, added = added, gone = gone)
        }

    /**
     * The owner answered [question]. [useHardcover]: Librillo takes Hardcover's status (or, for a
     * book no longer on Hardcover, leaves it off); else Librillo's status goes to Hardcover at the
     * next sync. Either way the question is gone.
     */
    suspend fun answer(accountId: Int, question: StatusQuestion, useHardcover: Boolean) =
        withContext(Dispatchers.IO) {
            val itemAt = dao.book(question.itemId)?.statusAt ?: dao.wish(question.itemId)?.statusAt
            val status = question.hardcoverStatus
            if (itemAt != null) {
                if (useHardcover) {
                    if (status == HardcoverStatuses.NONE)
                        agree(accountId, question.hardcoverId, status, itemAt)
                    else {
                        val word = HardcoverStatuses.toPhone(status)
                        if (word != null) {
                            val at = now()
                            repo.manageBatch(setOf(question.itemId), status = word, statusAt = at)
                            agree(accountId, question.hardcoverId, status, at)
                        }
                    }
                } else {
                    // A baseline Librillo's status differs from, so the next sync sends it. For a
                    // book gone from Hardcover, a time before any status time puts it back.
                    val before = if (status == HardcoverStatuses.NONE) -1L else 0L
                    agree(accountId, question.hardcoverId, status, before)
                }
            }
            store.deleteQuestion(accountId, question.hardcoverId)
        }

    /** Librillo's side of [entry]: its book's status when it has a file, else its own. */
    private fun localSide(entry: WishBook, books: Map<String, Book>) =
        entry.linkedBookId?.let(books::get)?.let {
            LocalSide(it.id, it.title, it.status, it.statusAt)
        }
            ?: LocalSide(
                entry.id,
                entry.title,
                HardcoverStatuses.catalogueWord(entry.status),
                entry.statusAt,
            )

    /**
     * Marks [entry] as on Hardcover, so the catalogue page no longer offers to add it there by
     * hand. Re-read under the library lock, so nothing changed meanwhile is overwritten.
     */
    private suspend fun published(entry: WishBook) {
        if (entry.hardcoverSynced) return
        repo.lock.withLock {
            val current = dao.wish(entry.id) ?: return
            if (!current.hardcoverSynced) dao.updateWish(current.copy(hardcoverSynced = true))
        }
    }

    private suspend fun agree(account: Int, hardcoverId: Int, status: Int, statusAt: Long) =
        hardcover.saveBaseline(HardcoverBaselineRow(account, hardcoverId, status, statusAt, now()))

    /**
     * Records that Hardcover has [hardcoverId] at [status], with the pages and edition of [row],
     * the pulled book, when there is one.
     */
    private suspend fun observe(
        account: Int,
        hardcoverId: Int,
        status: Int,
        row: RemoteUserBook?,
        at: Long,
    ) =
        dao.hardcoverObservation(
            HardcoverObservation(account, hardcoverId, status, row?.pages, row?.editionId, at)
        )

    /**
     * Replaces the account's questions with [asked]. A question whose baseline changed while this
     * sync ran (the owner answered it meanwhile) is not asked again. One that was already open
     * keeps its place.
     */
    private suspend fun replaceQuestions(
        account: Int,
        asked: List<Pair<MergeStep.Ask, HardcoverBaselineRow?>>,
        at: Long,
    ): Int =
        repo.database.withTransaction {
            val open = hardcover.allQuestions(account).associateBy { it.hardcoverId }
            val rows =
                asked
                    .filter { (step, planned) ->
                        hardcover.baseline(account, step.hardcoverId) == planned
                    }
                    .map { (step, _) ->
                        val old = open[step.hardcoverId]
                        val same =
                            old != null &&
                                old.itemId == step.itemId &&
                                old.hardcoverStatus == step.hardcoverStatus &&
                                old.librilloStatus == step.librilloWord
                        HardcoverQuestionRow(
                            account,
                            step.hardcoverId,
                            step.itemId,
                            step.title,
                            step.hardcoverStatus,
                            step.librilloWord,
                            if (same) old!!.askedAt else at,
                        )
                    }
            hardcover.deleteQuestions(account)
            hardcover.saveQuestions(rows)
            rows.size
        }

    /**
     * Each pulled book with a known status that no catalogue entry has, Recently removed ones
     * included (a removed entry is never brought back), becomes a catalogue entry with Hardcover's
     * status. Both sides then agree on it.
     */
    private suspend fun createEntries(
        account: Int,
        pulled: Collection<RemoteUserBook>,
        at: Long,
    ): Int {
        var added = 0
        val known = dao.allStoredWishes().mapNotNull { it.hardcoverId }.toSet()
        for (row in pulled) {
            if (row.status !in KNOWN_STATUSES || row.hardcoverId in known) continue
            val word = HardcoverStatuses.toPhone(row.status) ?: continue
            val statusAt = row.updatedAt ?: at
            val inserted =
                repo.lock.withLock {
                    repo.database.withTransaction {
                        if (hardcover.hasEntry(row.hardcoverId)) return@withTransaction false
                        val id = UUID.randomUUID().toString()
                        val title = row.title.trim().take(HardcoverLinks.MAX_TITLE)
                        val entry =
                            WishBook(
                                id,
                                hardcoverCatalogKey(hardcover, row.hardcoverId, id),
                                title,
                                encode(
                                    row.authors
                                        .map { it.take(HardcoverLinks.MAX_AUTHOR) }
                                        .take(HardcoverLinks.MAX_AUTHORS)
                                ),
                                hardcoverId = row.hardcoverId,
                                hardcoverTitle = title,
                                hardcoverSynced = true,
                                status = word,
                                statusAt = statusAt,
                            )
                        // A book Hardcover sends with no usable title is left out.
                        if (runCatching { parseWish(wishJson(entry)) }.isFailure)
                            return@withTransaction false
                        if (dao.insertWish(entry) == -1L) return@withTransaction false
                        agree(account, row.hardcoverId, row.status, statusAt)
                        true
                    }
                }
            if (inserted) added++
        }
        return added
    }

    /**
     * Gives catalogue entries without a file or a cover the cover Hardcover has for them, until
     * [deadline]. Best effort: an entry whose cover can't be fetched is tried at a later sync.
     */
    private suspend fun fillCovers(pulled: Map<Int, RemoteUserBook>, deadline: Long) {
        val removed = dao.allRemoved().map { it.id }.toSet()
        val bare =
            dao.allStoredWishes().filter {
                it.coverPath == null &&
                    it.linkedBookId == null &&
                    it.id !in removed &&
                    it.hardcoverId?.let(pulled::get)?.coverUrl != null
            }
        for (entry in bare) {
            if (now() >= deadline) return
            val url = pulled.getValue(entry.hardcoverId!!).coverUrl!!
            val bytes =
                try {
                    covers.load(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
            val file = File(File(repo.root, entry.id).apply { mkdirs() }, COVER_FILE)
            try {
                file.writeBytes(bytes)
            } catch (_: java.io.IOException) {
                continue
            }
            val used =
                repo.lock.withLock {
                    val current = dao.wish(entry.id)
                    if (current == null || current.coverPath != null) false
                    else {
                        dao.updateWish(current.copy(coverPath = file.absolutePath))
                        true
                    }
                }
            if (!used) file.delete()
        }
    }

    /**
     * Runs one item's step. A request Hardcover turned down for this book, or a book that changed
     * under it, skips the item this time; rate limits, server errors, no connection and a rejected
     * token stop the sync for the run to handle.
     */
    private inline fun skippable(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HardcoverHttpException) {
            if (e.transient || e.unauthorized) throw e
        } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }

    companion object {
        private val KNOWN_STATUSES = 1..5
        /** How long one sync may spend fetching covers for catalogue entries. */
        const val COVER_BUDGET_MS = 60_000L
        const val COVER_FILE = "hardcover-cover"
    }
}
