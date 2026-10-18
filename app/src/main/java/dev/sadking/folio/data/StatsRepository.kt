package dev.sadking.folio.data

import dev.sadking.folio.core.stats.BookFinish
import dev.sadking.folio.core.stats.DOCUMENT_PREFIX
import dev.sadking.folio.core.stats.FinishSource
import dev.sadking.folio.core.stats.GoalSettings
import dev.sadking.folio.core.stats.ReadingSession
import dev.sadking.folio.core.stats.ReadingStats
import dev.sadking.folio.core.stats.SessionSource
import dev.sadking.folio.core.stats.StatsBook
import dev.sadking.folio.sync.EreaderSync
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

/**
 * The statistics the screens read: one [StatsSnapshot] now, then again after each change to the
 * sessions, books, finishes, devices or goals, and each time the date changes. It reads the stored
 * rows and does the mapping the core can't: a Kindle session is keyed to its book through its
 * digest, a finish comes from a library status, a Hardcover read or a catalogue entry, and names,
 * device names and A1 names are looked up. Every number is [ReadingStats]'. docs/STATS_DESIGN.md
 * (a7, a2, c) has the rules.
 */
class StatsRepository(
    private val stats: StatsDao,
    private val library: LibraryDao,
    /** The device roster, as [deviceRosterFlow] builds it for the Device tab. */
    private val roster: Flow<DeviceRoster>,
    /** The e-readers that sync, most recent first. */
    private val ereaders: Flow<List<EreaderSync>>,
    private val goals: StatsGoals,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * The snapshot now and after every change. Building it runs on the default dispatcher, and a
     * newer change replaces a build still running.
     */
    fun snapshot(): Flow<StatsSnapshot> {
        val stored =
            combine(
                stats.sessions(),
                stats.digests(),
                stats.words(),
                stats.reads(),
                stats.everyBook(),
            ) { sessions, digests, words, reads, books ->
                Stored(sessions, digests, words, reads, books)
            }
        val linked =
            combine(
                library.wishes(),
                library.phoneReadingFlow(),
                library.kosyncFlow(),
                roster,
                ereaders,
            ) { wishes, phone, reports, devices, eReaderList ->
                Linked(wishes, phone, reports, devices, eReaderList)
            }
        return combine(stored, linked, stats.bookNames(), goals.goals, today()) { s, l, n, g, day ->
                Input(s, l, n, g, day)
            }
            .mapLatest { build(it) }
            .flowOn(Dispatchers.Default)
    }

    private fun build(input: Input): StatsSnapshot {
        val zone = zone()
        val stored = input.stored
        val linked = input.linked
        val digestBooks = stored.digests.associate { it.digest to it.bookId }
        val bookIds = stored.books.map { it.id }.toSet()
        // A Kindle session whose digest matches a library book belongs to that book.
        val sessions =
            stored.sessions
                .mapNotNull { it.toSession() }
                .map { session ->
                    val matched = session.document?.let { digestBooks[it] }
                    if (session.source == SessionSource.KINDLE && matched != null) {
                        session.copy(bookId = matched)
                    } else {
                        session
                    }
                }
        val reading =
            ReadingStats(
                sessions,
                books = bookStats(stored, linked, digestBooks),
                finishes = finishes(stored, linked, bookIds, zone),
                zone = zone,
                today = input.today,
                firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
            )
        return StatsSnapshot(
            stats = reading,
            labels = labels(stored, linked, input.names, digestBooks),
            deviceNames = deviceNames(sessions, linked.roster),
            goals = input.goals,
            waitingForEreader =
                linked.ereaders.isNotEmpty() && sessions.none { it.source == SessionSource.KINDLE },
        )
    }

    /**
     * One [StatsBook] per library book, Recently removed included. Words are kept only for the
     * revision they were counted from. Progress is the phone's position for the same revision, else
     * the newest Progress sync report for any of the book's digests.
     */
    private fun bookStats(
        stored: Stored,
        linked: Linked,
        digestBooks: Map<String, String>,
    ): List<StatsBook> {
        val words = stored.words.associateBy { it.bookId }
        val phone = linked.phone.associateBy { it.bookId to it.revisionHash }
        val newestReport =
            linked.reports
                .mapNotNull { report -> digestBooks[report.document]?.let { it to report } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, rows) ->
                    rows.maxWith(compareBy({ it.timestamp }, { it.receivedAt }))
                }
        return stored.books.map { book ->
            StatsBook(
                key = book.id,
                words = words[book.id]?.takeIf { it.revisionHash == book.currentHash }?.words,
                progress =
                    phone[book.id to book.currentHash]?.percent
                        ?: newestReport[book.id]?.percentage,
            )
        }
    }

    /**
     * The finishes (a7): library books and catalogue entries with a Finished status and a date, and
     * each dated Hardcover read. A catalogue entry whose library book is there is left out: the
     * book counts. A Hardcover read is keyed to the book linked to its Hardcover book, else to the
     * catalogue entry, else to `hc:<id>`.
     */
    private fun finishes(
        stored: Stored,
        linked: Linked,
        bookIds: Set<String>,
        zone: ZoneId,
    ): List<BookFinish> {
        val fromBooks =
            stored.books
                .filter { it.status == FINISHED && it.statusAt > 0 }
                .map { BookFinish(it.id, dateOf(it.statusAt, zone), FinishSource.LIBRARY) }
        val fromWishes =
            linked.wishes
                .filter {
                    it.status == FINISHED && it.statusAt > 0 && linkedBook(it, bookIds) == null
                }
                .map { BookFinish(it.id, dateOf(it.statusAt, zone), FinishSource.LIBRARY) }
        val wishByHardcover =
            linked.wishes.filter { it.hardcoverId != null }.associateBy { it.hardcoverId }
        val fromHardcover =
            stored.reads.mapNotNull { read ->
                val date =
                    read.finishedOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                        ?: return@mapNotNull null
                val wish = wishByHardcover[read.hardcoverId]
                val key =
                    if (wish == null) "$HARDCOVER_PREFIX${read.hardcoverId}"
                    else linkedBook(wish, bookIds) ?: wish.id
                BookFinish(key, date, FinishSource.HARDCOVER)
            }
        return fromBooks + fromWishes + fromHardcover
    }

    /**
     * The name of each book a label is shown for. A library book and a catalogue entry use their
     * own title and authors; a Kindle book the phone can't match uses the name KOReader sent (A1),
     * when it sent one. Without a name it has no label, and the screens say "A book on your
     * e-reader".
     */
    private fun labels(
        stored: Stored,
        linked: Linked,
        names: List<StatsBookNameRow>,
        digestBooks: Map<String, String>,
    ): Map<String, StatsLabel> {
        val labels = HashMap<String, StatsLabel>()
        for (book in stored.books) {
            labels[book.id] = StatsLabel(book.title, book.authorLabel, book, book.coverPath)
        }
        for (wish in linked.wishes) {
            labels.putIfAbsent(
                wish.id,
                StatsLabel(wish.title, wish.authorLabel, null, wish.coverPath),
            )
        }
        for (name in names) {
            if (name.digest !in digestBooks) {
                labels[DOCUMENT_PREFIX + name.digest] =
                    StatsLabel(name.title, name.authors, null, null)
            }
        }
        return labels
    }

    /** The names of the e-readers that sessions came from, as the roster names them. */
    private fun deviceNames(
        sessions: List<ReadingSession>,
        roster: DeviceRoster,
    ): Map<String, String> =
        sessions
            .map { it.deviceKey }
            .distinct()
            .filter { it.startsWith(EREADER_PREFIX) }
            .mapNotNull { key -> roster.name(key)?.let { key to it } }
            .toMap()

    /** The library book a catalogue entry is linked to, if that book is in the library. */
    private fun linkedBook(wish: WishBook, bookIds: Set<String>): String? =
        wish.linkedBookId?.takeIf { it in bookIds }

    /** Today in the phone's zone, read now and then every minute. Emitted only when it changes. */
    private fun today(): Flow<LocalDate> =
        flow {
                while (true) {
                    emit(Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate())
                    delay(TICK_MS)
                }
            }
            .distinctUntilChanged()

    private fun dateOf(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /** What the snapshot is built from: the stored rows, the linked tables, and the day. */
    private class Input(
        val stored: Stored,
        val linked: Linked,
        /** The names KOReader sent for books the phone can't match (A1). */
        val names: List<StatsBookNameRow>,
        val goals: GoalSettings,
        val today: LocalDate,
    )

    /** The rows that describe reading: sessions, digests, word counts, Hardcover reads, books. */
    private class Stored(
        val sessions: List<ReadingSessionRow>,
        val digests: List<KoreaderDigestRow>,
        val words: List<BookWordsRow>,
        val reads: List<HardcoverReadRow>,
        val books: List<Book>,
    )

    /** The rows that link devices and books: catalogue entries, positions, devices, e-readers. */
    private class Linked(
        val wishes: List<WishBook>,
        val phone: List<PhoneReading>,
        val reports: List<KosyncReport>,
        val roster: DeviceRoster,
        val ereaders: List<EreaderSync>,
    )

    private companion object {
        const val FINISHED = "Finished"
        const val HARDCOVER_PREFIX = "hc:"
        val EREADER_PREFIX = DeviceKeys.ereader("")
        const val TICK_MS = 60_000L
    }
}
