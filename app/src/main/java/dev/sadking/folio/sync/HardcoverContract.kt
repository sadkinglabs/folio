package dev.sadking.folio.sync

import dev.sadking.folio.core.hardcover.MatchCandidate
import dev.sadking.folio.data.HardcoverRead
import java.time.LocalDate

// M10 contract: docs/HARDCOVER_SYNC.md, section B2. Identical in every workstream.

/** One book in the connected account's Hardcover library, as the pull reads it. */
data class RemoteUserBook(
    val userBookId: Int,
    val hardcoverId: Int,
    /** Hardcover's status_id as sent. 1..5 are known; anything else is left alone. */
    val status: Int,
    /** user_books.updated_at in epoch milliseconds, or null when Hardcover has none. */
    val updatedAt: Long?,
    val title: String,
    val authors: List<String>,
    val releaseYear: Int?,
    val coverUrl: String?,
    /** Pages of the latest read, and its edition (else the entry's), as libraryPage reads them. */
    val pages: Int?,
    val editionId: Int?,
    /**
     * The finished reads of this book, as the pull reads them (at most 10). Never deleted locally.
     */
    val reads: List<HardcoverRead> = emptyList(),
)

/**
 * An edition that carries one of the asked ISBNs, as its book. [isbn13] is that ISBN as 13 digits.
 */
data class IsbnHit(val isbn13: String, val candidate: MatchCandidate)

/**
 * Hardcover for the connected account. Every call is a request through HardcoverClient's
 * process-wide gate: one at a time, at least 1.1 s apart. Blocking: call on Dispatchers.IO. Throws
 * HardcoverHttpException (429 with retryAfterMs; 401/403 unauthorized; 5xx transient),
 * java.io.IOException, or IllegalArgumentException / IllegalStateException when Hardcover's answer
 * needs a person.
 */
interface HardcoverApi {
    val accountId: Int

    /** The account's user_books with id > [after], by id ascending, at most 50. */
    fun libraryPage(after: Int): List<RemoteUserBook>

    /**
     * Sets status 1, 2, 4 or 5. With no [userBookId] the book is added with the account's privacy
     * setting. Returns the user_book id.
     */
    fun setStatus(hardcoverId: Int, status: Int, userBookId: Int?): Int

    /**
     * Read (3), with HardcoverClient.markRead's rules. [finishedOn] null: read long ago, no date.
     */
    fun markRead(hardcoverId: Int, finishedOn: LocalDate?)

    /** Editions carrying any of [isbns] (at most 100 ISBN-10 or ISBN-13 strings), as books. */
    fun editionsByIsbn(isbns: List<String>): List<IsbnHit>

    /** Hardcover's search, best first, at most 12. */
    fun search(text: String): List<MatchCandidate>
}

/** A Hardcover book offered for an unsure match. [coverPath] is a local image file, or null. */
data class MatchCandidateView(
    val hardcoverId: Int,
    val title: String,
    val authors: List<String>,
    val releaseYear: Int?,
    val coverPath: String?,
)

enum class MatchRecordState {
    /** Candidates wait for the owner in "Check these matches". */
    UNSURE,
    /** Nothing plausible was found. Tried again when the item's title, authors or ISBNs change. */
    NOT_FOUND,
    /** The owner said "Not on Hardcover". Never matched or asked again. */
    NOT_ON_HARDCOVER,
}

/** What matching concluded for an unlinked library item: a book id or a catalogue entry id. */
data class MatchRecord(
    val itemId: String,
    val state: MatchRecordState,
    /** Best first, at most 3; empty unless UNSURE. */
    val candidates: List<MatchCandidateView>,
    /** HardcoverMatching.metadataKey of the item when it was checked. */
    val metadataKey: String,
    val checkedAt: Long,
)

/**
 * One "Check these matches" card: the item as Librillo has it, and its candidates. [held] maps the
 * Hardcover id of a candidate that is already matched to something else to what holds it, so the
 * card can say so before the owner picks it.
 */
data class MatchCheck(
    val itemId: String,
    val title: String,
    val authors: List<String>,
    val candidates: List<MatchCandidateView>,
    val held: Map<Int, dev.sadking.folio.data.HardcoverHolder> = emptyMap(),
)

/** Both sides changed a book's status since they last agreed. */
data class StatusQuestion(
    val hardcoverId: Int,
    /** The book id when the catalogue entry has a file, else the catalogue entry id. */
    val itemId: String,
    val title: String,
    /** Hardcover's status id 1..5, or 0: no longer in the Hardcover library. */
    val hardcoverStatus: Int,
    /** Librillo's status word (one of phoneStatuses). */
    val librilloStatus: String,
)

/** What a book page's Hardcover tile says. */
enum class HardcoverItemState {
    MATCHED,
    MATCHING,
    CHECK,
    NOT_ON_HARDCOVER,
    NOT_FOUND,
    /** Not linked, and no account or sync is off: the manual match in the Hardcover sheet. */
    OFF,
}

enum class HardcoverProblem {
    TOKEN_REJECTED,
    OFFLINE,
}

/** Settings' status line. */
data class HardcoverSyncStatus(
    val connected: Boolean = false,
    val enabled: Boolean = true,
    val running: Boolean = false,
    /** When status sync last finished, epoch ms; 0 never. */
    val lastSyncAt: Long = 0,
    val matched: Int = 0,
    /** Match checks plus status questions. */
    val toCheck: Int = 0,
    /** The matching pass in progress; both 0 when none. */
    val matchingDone: Int = 0,
    val matchingTotal: Int = 0,
    val problem: HardcoverProblem? = null,
)

/** What one status sync did. */
data class StatusSyncResult(
    val down: Int = 0,
    val up: Int = 0,
    val asked: Int = 0,
    val added: Int = 0,
    val gone: Int = 0,
)
