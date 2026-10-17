# Hardcover sync (M10)

Decided with the owner on 2026-10-16 (docs/UPGRADE_PLAN.md, "M10: Seamless sync with Hardcover"). The owner's words: "I want seamless sync between Librillo and Hardcover." and "If I add a book after navigating Hardcover to a wishlist, it would be awesome if it appeared in Librillo — and vice versa."

Part A says what the owner gets and the rules. Part B is the build contract for the four workstreams (core, client, sync, ui). The rules in Part A win over anything in Part B that disagrees.

---

# Part A: how it works

**Status (2026-10-16):** built, not committed. Part C at the end records where the code differs from Part B and what was verified.

## A1. What the owner sees

- **Every book matched to Hardcover on its own.** Once, Librillo goes through the whole library. After that, each new book or catalogue entry is matched when it arrives (import, Inbox, e-reader upload, Books folder scan, search, backup restore).
- **Statuses both ways.** Want to Read, Reading, Finished, Paused and Abandoned in Librillo are Want to Read, Currently Reading, Read, Paused and Did Not Finish on Hardcover. A change on either side reaches the other. Reading progress keeps going up as before.
- **Books added on Hardcover appear in Librillo** as catalogue entries (no file yet). Catalogue entries added in Librillo appear on Hardcover.
- **Kindle finishes go to Hardcover on their own**, like the phone's. The "Mark it as read on Hardcover?" prompt is gone.
- **Needs your decision** gains two kinds of card:
  - "Check these matches": a book Librillo could not match for sure, with up to three Hardcover books to pick from, or "Not on Hardcover".
  - "<title>: Hardcover says Read, Librillo says Reading. Which is right?" (under "Hardcover and Librillo disagree") when both sides changed the same book since they last agreed. With two or more, "Use Hardcover for all" and "Use Librillo for all".
- **Settings → Hardcover:** a "Sync with Hardcover" switch (the old "Update Hardcover automatically" setting, so the owner's choice carries over) with Sync now, and one status line: "Synced 5 min ago · 312 matched · 4 to check", or "Matching your library… 120 of 400".
- **Book page, Hardcover tile:** Matched (or Page N), Matching…, Check match, Not on Hardcover, Not found, Not matched.

## A2. Matching rules

1. Items: every book, and every catalogue entry, not in Recently removed, that has no Hardcover link. A book is linked when a catalogue entry (`WishBook`) has `hardcoverId` and `linkedBookId` = the book. A catalogue entry is linked when it has `hardcoverId`.
2. Skipped: items the owner marked "Not on Hardcover" (never asked again for that item), and items already checked whose title, authors and ISBNs have not changed since.
3. Order, cheapest first:
   1. **The owner's own Hardcover library** (from the last pull, no request): a single book whose title and author match exactly is linked.
   2. **ISBN**, many items per request. One Hardcover book carries the ISBN and its title or author agrees → linked. One book that disagrees on both, or several books → "Check these matches". When the ISBN's book has a title that only holds the item's, or the other way round ("Hyperion" for an EPUB of "The Fall of Hyperion", which may carry the first book's ISBN by mistake), the title search decides: its own sure match wins; otherwise nothing is linked and the ISBN's book goes to "Check these matches". An ISBN whose only books are already matched to something else also goes on to the title search.
   3. **Title and author** through Hardcover search. Linked only when one result matches title and author exactly and the next best is clearly worse. Another book of the series (a title that holds the item's, or is held by it, such as "The Fall of Hyperion" for "Hyperion") is not a rival. Otherwise up to three plausible results go to "Check these matches". Nothing plausible → "Not found" (no card; the book page offers the manual search).
4. Linking creates or updates the catalogue entry exactly as the manual link in the Hardcover sheet does. If Hardcover's book already has a catalogue entry with no file, or one whose book no longer exists, the book is attached to it. Nothing links over another book's link on its own: Check these matches and the Hardcover sheet both say which book has it ("Already matched to “The Fall of Hyperion”", "…, which is in Recently removed") and offer "Move the match here" or "Keep it there". Moving takes the Hardcover book's entry (with its lists) to this book, or, when this item has its own entry, moves only the Hardcover link to it; what the match leaves keeps its file, notes, progress, lists and status and goes back to matching.
5. Matching only reads Hardcover. A new link reaches Hardcover only through status sync below, and only while "Sync with Hardcover" is on.

## A3. Status sync rules

Each linked book has a **baseline**: the Hardcover status both sides agreed on last time, per Hardcover account. Like the e-reader sync's baseline, it tells "changed here" from "changed there".

- Librillo's status of an item: the book's, when the catalogue entry has a file; otherwise the entry's own ("Want to Read" when blank).
- **Unread is no opinion.** It never removes or changes anything on Hardcover, and a Hardcover change still comes down over it.
- **Librillo changed, Hardcover did not** → goes up. Finished uses the Read rules: if Hardcover has the book as Currently Reading or Paused, the read ends on the day the status changed in Librillo; otherwise it is Read with no date (read long ago).
- **Hardcover changed, Librillo did not** → comes down to the book or catalogue entry, with Hardcover's change time. An e-reader then gets it at its next sync.
- **Both changed to the same status** → agreed, nothing sent.
- **Both changed, differently** → a question in Needs your decision. Nothing changes on either side until the owner answers.
- **First time (no baseline):** one side Unread or absent → take the other. Same status → agreed. Different → the newer wins when both times are known and more than a minute apart (Librillo's status time against Hardcover's `updated_at`). Otherwise (most existing books have no status time) the status further along wins: Want to Read < Currently Reading < Paused or Did Not Finish < Read. It asks only for Reading or Paused in Librillo against Read on Hardcover (maybe a re-read), and for Paused against Did Not Finish.
- **Nothing is deleted because it is missing on the other side.** A book removed from Hardcover stays in Librillo with its status. An empty pull while some books were agreed to be on Hardcover is not believed (a glitch must not mark every book gone). Librillo remembers "agreed: not on Hardcover", so the book is not put back on Hardcover until its status is changed in Librillo again. A book in Recently removed is left alone both ways.
- A Hardcover status Librillo does not know (an id outside 1–5) is left alone both ways.

## A4. Limits and pacing

- Hardcover allows about 60 requests a minute per token, and stops a query after 30 seconds.
- Every Hardcover request in the app, background or on screen, goes through one gate: one request at a time, at least 1.1 s apart (about 54 a minute).
- "Slow down" (HTTP 429): every request waits for Retry-After (60 s when not given), then the work resumes where it stopped. Server errors and no connection: WorkManager retries later with backoff. A rejected token stops everything until the account is reconnected.
- Work is kept on the phone (WorkManager and Room), so it survives restarts. A background run stops itself after 8 minutes and queues the next one.
- Costs: the pull reads 50 library books per request (400 books = 8 requests). ISBN matching handles up to 50 items per request. A title search costs 2 requests per item. A status change costs 1 request (Read: up to 4). The first pass over 400 books with no ISBNs takes about 15 minutes; it shows its progress.
- When Librillo looks: when it opens (at most every 15 minutes), every 4 hours in the background (network required), and a few seconds after any status change on the phone or from the e-reader.

## A5. What is not covered

- Progress keeps the outbox rules: it only moves a read already in progress on Hardcover forward. A book with no read in progress on Hardcover gets its first page from the book's Hardcover sheet.
- Ratings, reviews, lists and dates other than the finish day are not synced.
- Backups do not include baselines, match checks or questions. After a restore into a new install, the first sync uses the first-time rules (and may ask more).
- Verified on a real device and against the real Hardcover API: nothing yet. CI runs everything against recorded responses. Section B9 lists the device checks.

---

# Part B: build contract

## B1. Workstreams and file ownership

Four workstreams work in parallel. Each file has exactly one owner. A workstream never edits an existing file it does not own.

**Stubs.** If your branch needs a symbol another workstream adds (section B2–B6 give every one), create it at the owner's exact path with the exact signature and `TODO()` bodies, and say so in your report. At merge, the owner's file wins. The contract file in B2 is the one exception: it is copied verbatim into every branch that needs it, so it merges cleanly.

### core (pure Kotlin, `:core`)
- `core/src/main/kotlin/dev/sadking/folio/core/hardcover/HardcoverStatuses.kt` (new)
- `core/src/main/kotlin/dev/sadking/folio/core/hardcover/HardcoverMerge.kt` (new)
- `core/src/main/kotlin/dev/sadking/folio/core/hardcover/HardcoverMatching.kt` (new)
- `core/src/test/kotlin/dev/sadking/folio/core/hardcover/HardcoverStatusesTest.kt` (new)
- `core/src/test/kotlin/dev/sadking/folio/core/hardcover/HardcoverMergeTest.kt` (new)
- `core/src/test/kotlin/dev/sadking/folio/core/hardcover/HardcoverMatchingTest.kt` (new)

### client (Hardcover API, the paced queue, the matcher)
- `app/src/main/java/dev/sadking/folio/data/HardcoverClient.kt`
- `app/src/main/java/dev/sadking/folio/sync/HardcoverContract.kt` (new, verbatim from B2)
- `app/src/main/java/dev/sadking/folio/sync/HardcoverOutbox.kt`
- `app/src/main/java/dev/sadking/folio/sync/HardcoverSync.kt` (new: the facade)
- `app/src/main/java/dev/sadking/folio/sync/HardcoverSyncWorker.kt` (new: workers, the run, the API adapter)
- `app/src/main/java/dev/sadking/folio/sync/HardcoverMatcher.kt` (new)
- `app/src/main/java/dev/sadking/folio/FolioApplication.kt`
- `scripts/check-hardcover-schema.py`
- `app/src/test/java/dev/sadking/folio/data/HardcoverClientTest.kt`, `data/HardcoverLibraryTest.kt`, `sync/HardcoverOutboxTest.kt`
- `app/src/test/java/dev/sadking/folio/sync/HardcoverMatcherTest.kt`, `sync/HardcoverRunTest.kt`, `sync/HardcoverItemStateTest.kt` (new)

### sync (Room, status engine, links, hooks)
- `app/src/main/java/dev/sadking/folio/data/Database.kt`
- `app/schemas/dev.sadking.folio.data.LibraryDatabase/11.json` (generated by the build)
- `app/src/main/java/dev/sadking/folio/data/HardcoverLinks.kt` (new)
- `app/src/main/java/dev/sadking/folio/data/LibraryRepository.kt`
- `app/src/main/java/dev/sadking/folio/data/LibraryBackup.kt`
- `app/src/main/java/dev/sadking/folio/sync/HardcoverStore.kt` (new)
- `app/src/main/java/dev/sadking/folio/sync/HardcoverStatusSync.kt` (new)
- `app/src/main/java/dev/sadking/folio/sync/RoomSyncLibrary.kt`
- `app/src/main/java/dev/sadking/folio/LibraryViewModel.kt`
- `scripts/check-migration.py`
- `docs/SYNC_PROTOCOL.md` (replace the "Hardcover finish prompt" section with a pointer to this file; update the `apply` note)
- `app/src/androidTest/java/dev/sadking/folio/MigrationTest.kt`
- `app/src/test/java/dev/sadking/folio/sync/RoomSyncLibraryTest.kt`
- `app/src/test/java/dev/sadking/folio/sync/HardcoverStatusSyncTest.kt`, `sync/HardcoverStoreTest.kt`, `data/HardcoverLinksTest.kt` (new)
- `app/src/test/java/dev/sadking/folio/data/LibraryBasicsTest.kt` (only if the wish JSON change needs it)

### ui
- `app/src/main/java/dev/sadking/folio/DeviceViewModel.kt`
- `app/src/main/java/dev/sadking/folio/ui/DecisionsSheet.kt`
- `app/src/main/java/dev/sadking/folio/ui/SettingsScreen.kt`
- `app/src/main/java/dev/sadking/folio/ui/BookDetails.kt`
- `app/src/main/java/dev/sadking/folio/ui/CatalogueBookDetails.kt`
- `app/src/main/java/dev/sadking/folio/ui/FolioApp.kt`
- `app/src/main/java/dev/sadking/folio/ui/SyncStatusLine.kt`
- `app/src/main/java/dev/sadking/folio/ui/Previews.kt`
- `app/src/main/java/dev/sadking/folio/ui/HardcoverAccountSheet.kt` (only to start a sync after connecting, if that is where it fits)
- `app/src/main/res/values/strings_m10_hardcover.xml` (new), `strings_m9_sync.xml`, `strings_redesign.xml`
- `app/src/test/java/dev/sadking/folio/ui/SyncUiTest.kt`, `ui/SyncLineTest.kt`, `ui/ScreenshotTest.kt`, `ui/FinishedOnHardcoverTest.kt`
- `app/src/test/java/dev/sadking/folio/ui/HardcoverSyncUiTest.kt` (new)

### Integrator (after merging all four)
- Delete `app/src/main/java/dev/sadking/folio/sync/HardcoverFinishPrompts.kt` and `app/src/test/java/dev/sadking/folio/sync/HardcoverFinishPromptsTest.kt`. After the merge nothing references them (sync removes the uses in `RoomSyncLibrary` and `LibraryViewModel`, ui removes them in `DeviceViewModel`, `FolioApp`, `DecisionsSheet`, `Previews` and tests, client removes the doc mention in `HardcoverOutbox.kt`).
- Add the M10 status line to `docs/UPGRADE_PLAN.md`.
- Run the full checks, `python3 scripts/check-hardcover-schema.py`, `python3 scripts/check-migration.py`, and the screenshots.

## B2. The contract file (verbatim)

Path: `app/src/main/java/dev/sadking/folio/sync/HardcoverContract.kt`. Owner: client. Copy it exactly; do not change it in a workstream. If it must change, stop and report.

```kotlin
package dev.sadking.folio.sync

import dev.sadking.folio.core.hardcover.MatchCandidate
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
)

/** An edition that carries one of the asked ISBNs, as its book. [isbn13] is that ISBN as 13 digits. */
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

    /** Read (3), with HardcoverClient.markRead's rules. [finishedOn] null: read long ago, no date. */
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

/** One "Check these matches" card: the item as Librillo has it, and its candidates. */
data class MatchCheck(
    val itemId: String,
    val title: String,
    val authors: List<String>,
    val candidates: List<MatchCandidateView>,
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
```

## B3. core: `dev.sadking.folio.core.hardcover`

Pure JVM, no Android, no `org.json`. Every rule below has a test.

### HardcoverStatuses.kt
```kotlin
object HardcoverStatuses {
    /** Not in the Hardcover library. Used in baselines and questions only. */
    const val NONE = 0
    const val WANT_TO_READ = 1
    const val CURRENTLY_READING = 2
    const val READ = 3
    const val PAUSED = 4
    const val DID_NOT_FINISH = 5

    /** "Want to Read"→1, "Reading"→2, "Finished"→3, "Paused"→4, "Abandoned"→5; anything else (Unread, blank) → null. */
    fun fromPhone(word: String): Int?

    /** 1..5 → the phone word above; anything else → null. */
    fun toPhone(status: Int): String?

    /** A catalogue entry's status word: its own, or "Want to Read" when blank. */
    fun catalogueWord(status: String): String
}
```

### HardcoverMerge.kt
```kotlin
/** What both sides last agreed. [status] 0..5; 0 = agreed it is not on Hardcover. [statusAt] = Librillo's status time then. */
data class HardcoverBaseline(val status: Int, val statusAt: Long)

/** Librillo's side: [word] is a phone status word; [statusAt] epoch ms, 0 when unknown. */
data class LocalSide(val itemId: String, val title: String, val word: String, val statusAt: Long)

/** Hardcover's side, from the pull. [status] as Hardcover sent it. */
data class RemoteSide(val userBookId: Int, val status: Int, val updatedAt: Long?)

data class MergeInput(
    val hardcoverId: Int,
    val local: LocalSide,
    /** Null: not in the Hardcover library. */
    val remote: RemoteSide?,
    val baseline: HardcoverBaseline?,
)

sealed interface MergeStep {
    val hardcoverId: Int

    /** Both sides already say [status]: record the baseline only. */
    data class Agree(override val hardcoverId: Int, val status: Int, val statusAt: Long) : MergeStep

    /** Set Librillo's item to [word] at [statusAt], then baseline ([status], [statusAt]). */
    data class Down(
        override val hardcoverId: Int,
        val itemId: String,
        val status: Int,
        val word: String,
        val statusAt: Long,
    ) : MergeStep

    /**
     * Send [status] to Hardcover ([userBookId] null: add the book), then baseline ([status],
     * [statusAt]). [finishedOn] only for 3: the day the read ended, or null for "read long ago".
     */
    data class Up(
        override val hardcoverId: Int,
        val itemId: String,
        val status: Int,
        val userBookId: Int?,
        val finishedOn: java.time.LocalDate?,
        val statusAt: Long,
    ) : MergeStep

    /** Hardcover no longer has it: baseline (0, [statusAt]); Librillo is not touched. */
    data class Gone(override val hardcoverId: Int, val statusAt: Long) : MergeStep

    /** Both changed, differently: ask. [hardcoverStatus] 0 when Hardcover no longer has it. */
    data class Ask(
        override val hardcoverId: Int,
        val itemId: String,
        val title: String,
        val hardcoverStatus: Int,
        val librilloWord: String,
    ) : MergeStep
}

object HardcoverMerge {
    /** Times closer than this are "the same moment": ask instead of guessing. */
    const val SAME_TIME_MS = 60_000L

    /** Null when nothing is to be done. [now] stands in for a missing updated_at on Down. */
    fun plan(input: MergeInput, zone: java.time.ZoneId, today: java.time.LocalDate, now: Long): MergeStep?
}
```

Rules for `plan`. L = `fromPhone(local.word)` (null = no opinion), R = `remote?.status ?: 0`, B = `baseline`.
1. `remote != null && remote.status !in 1..5` → null.
2. No baseline:
   - L null, R 0 → null.
   - L null, R > 0 → `Down(R, toPhone(R), remote.updatedAt ?: now)`.
   - L set, R 0 → `Up(L, userBookId = null, finishedOn = null, local.statusAt)`.
   - L == R → `Agree(R, local.statusAt)`.
   - L != R: if `local.statusAt > 0`, `remote.updatedAt != null` and they differ by more than `SAME_TIME_MS` → the newer wins: `Up(L, remote.userBookId, finishedOn, local.statusAt)` or `Down(R, toPhone(R), remote.updatedAt)`. Otherwise `Ask(hardcoverStatus = R, librilloWord = local.word)`.
3. Baseline B:
   - Librillo changed (`lc`): L != null and (B.status == 0 ? `local.statusAt > B.statusAt` : L != B.status).
   - Hardcover changed (`rc`): R != B.status.
   - neither → null.
   - only lc → `Up(L, remote?.userBookId, finishedOn, local.statusAt)`.
   - only rc → R == 0 ? `Gone(local.statusAt)` : `Down(R, ...)`.
   - both → L == R ? `Agree(R, local.statusAt)` : `Ask(hardcoverStatus = R, librilloWord = local.word)`.
4. `finishedOn` (only when Up's status is 3): if R is 2 or 4 → the local date of `local.statusAt` in `zone` (today when `statusAt` is 0), never after `today`. Otherwise null.

### HardcoverMatching.kt
```kotlin
/** One item to match. [isbns]: ISBN-13s from HardcoverMatching.isbns. */
data class MatchQuery(val itemId: String, val title: String, val authors: List<String>, val isbns: List<String>)

data class MatchCandidate(
    val hardcoverId: Int,
    val title: String,
    val authors: List<String>,
    val releaseYear: Int?,
    val coverUrl: String?,
)

sealed interface MatchDecision {
    data class Confident(val candidate: MatchCandidate) : MatchDecision
    /** 1..3 candidates, best first. */
    data class Unsure(val candidates: List<MatchCandidate>) : MatchDecision
    data object NotFound : MatchDecision
}

object HardcoverMatching {
    const val MAX_CANDIDATES = 3

    fun normalizeTitle(title: String): String
    /** The title before a subtitle (":", " - ", " — ", "(", "["), normalised. */
    fun mainTitle(title: String): String
    fun normalizeAuthor(name: String): String
    /** One identifier as an ISBN-13, or null: strips "urn:isbn:", "isbn:", "ISBN", hyphens and spaces; checks the check digit; turns ISBN-10 into 978-ISBN-13. */
    fun isbn13(raw: String): String?
    /** Every valid ISBN in [identifiers] as ISBN-13, distinct, in order. */
    fun isbns(identifiers: List<String>): List<String>
    /** The strings to ask Hardcover for: the ISBN-13, plus its ISBN-10 when it starts with 978. */
    fun isbnForms(isbn13: String): List<String>
    /** 0..4: title 2 (same normalised or same main title), 1 (one contains the other's main title, 4+ letters), else 0; plus author 2 (a normalised name in common), 1 (a surname in common), else 0. No usable author (blank, "Unknown", "Unknown author") scores 0 for author. */
    fun score(query: MatchQuery, candidate: MatchCandidate): Int
    /** ISBN hits (distinct books). One book: Confident when score ≥ 2, else Unsure([it]). Several: like fromSearch, but Unsure(top 3) instead of NotFound. None: NotFound. */
    fun fromIsbn(query: MatchQuery, hits: List<MatchCandidate>): MatchDecision
    /** Search results in Hardcover's order. Rank by score, then order. Confident: best scores 4 and the next ≤ 2. Unsure: those scoring ≥ 2, top 3. Else NotFound. */
    fun fromSearch(query: MatchQuery, results: List<MatchCandidate>): MatchDecision
    /** The owner's own Hardcover books: the single one scoring 4, else null. */
    fun fromLibrary(query: MatchQuery, library: List<MatchCandidate>): MatchCandidate?
    /** "<main title> <first usable author>", at most 300 characters; blank when the title is blank. */
    fun searchText(query: MatchQuery): String
    /** SHA-256 hex of the normalised title, sorted normalised authors and sorted ISBNs. */
    fun metadataKey(title: String, authors: List<String>, isbns: List<String>): String
}
```

Normalisation: Unicode NFKD, drop combining marks, lower case, "&" → "and", any run of non-letters/digits → one space, trim. `normalizeTitle` also drops one leading "the ", "a " or "an " and a trailing series part in brackets ("(The Expanse #1)"). `normalizeAuthor` turns "Last, First" into "First Last", drops periods, and joins runs of single letters ("J. R. R. Tolkien" and "JRR Tolkien" → "jrr tolkien").

Tests must cover at least: accents ("Cien años de soledad" = "Cien anos de soledad"), subtitles, leading articles, "Last, First", initials, ISBN-10 with X, bad check digits rejected, `urn:isbn:` prefixes, the merge table (every row of the rules, both first-time and with baseline, tombstone re-add after a later Librillo change, unknown Hardcover status, Unread over a Hardcover change), and `finishedOn` with a time zone where the day differs from UTC.

## B4. client

### HardcoverClient changes (exact GraphQL)

All strings stay in `HardcoverClient.kt` (the schema script reads only that file). Run `python3 scripts/check-hardcover-schema.py`; set its expected count to **14** distinct operations (13 today: two change, one is new; `setStatus` reuses existing strings). Validated against the published schema (sha256 `b1f4ea98576f5f8a…`, fetched 2026-10-16).

1. `search()`'s book query becomes:
   `query($ids:[Int!]!) { books(where:{id:{_in:$ids}}) { id title slug release_year cached_contributors cached_image } }`
   `HardcoverMatch` gains `val releaseYear: Int? = null, val coverUrl: String? = null` (last, defaulted; `cached_image.url`).
2. `libraryPage()` becomes:
   `query($user:Int!,$after:Int!) { user_books(where:{user_id:{_eq:$user},id:{_gt:$after}},order_by:{id:asc},limit:50) { id status_id edition_id updated_at book { id title slug release_year cached_contributors cached_image } user_book_reads(order_by:{id:desc},limit:1) { progress_pages edition_id } } }`
   `HardcoverLibraryBook` gains `val updatedAt: Long? = null` (last). Parse `updated_at` with `OffsetDateTime.parse` → epoch ms; unparsable → null.
3. New `fun editionsByIsbn(token: String, isbns: List<String>): List<HardcoverIsbnHit>`; `require(isbns.size in 1..100)`:
   `query($isbns:[String!]!) { editions(where:{_or:[{isbn_13:{_in:$isbns}},{isbn_10:{_in:$isbns}}]},limit:200) { id isbn_13 isbn_10 book { id title slug release_year canonical_id cached_contributors cached_image } } }`
   `data class HardcoverIsbnHit(val isbn13: String?, val isbn10: String?, val book: HardcoverMatch)`. The book id is `canonical_id` when set, else `id`.
4. New `fun setStatus(token: String, bookId: Int, status: Int, userBookId: Int?, privacy: Int): Int`, `require(status in setOf(1, 2, 4, 5))`. With no `userBookId`, the existing insert string with `book_id`, `status_id`, `privacy_setting_id`; else the existing update string with `status_id`. Returns the id from `checkedWrite`.
5. Pacing moves to the companion object, shared by every `HardcoverClient` in the process: one request at a time, at least 1 100 ms apart. A 429 sets a shared cool-down until now + Retry-After seconds (60 s when absent). A request during a cool-down waits when 2 s or less remain, else throws `HardcoverHttpException(429, …, retryAfterMs = remaining)` without calling Hardcover. `HardcoverHttpException` gains `val retryAfterMs: Long? = null`. Read timeout 35 s (Hardcover stops queries at 30 s). The fixture path (tests) skips the wait but keeps the cool-down logic testable through an injectable clock: `internal var clock: () -> Long` on the companion.
6. Never put the token, response text or book titles in an exception message or a log.

### The facade: `sync/HardcoverSync.kt`
```kotlin
class HardcoverSync private constructor(private val app: FolioApplication) {
    /** "Sync with Hardcover". Same preference as HardcoverOutbox.automatic (prefs "hardcover-outbox", key "automatic", default true). Turning it on starts a sync. */
    var enabled: Boolean

    val status: StateFlow<HardcoverSyncStatus>
    val checks: Flow<List<MatchCheck>>            // UNSURE records whose item still exists, is not removed and is still unlinked; newest item first
    val questions: Flow<List<StatusQuestion>>     // for the connected account
    /** Book ids and catalogue entry ids → their tile state. */
    val itemStates: Flow<Map<String, HardcoverItemState>>

    /** Once, from FolioApplication.onCreate: the periodic work and the new-item watcher. */
    fun start()
    /** The app came to the foreground: a pull when the last one is 15 min old or more. */
    fun onAppOpen()
    /** Statuses of [ids] (books or catalogue entries) may have changed here or on an e-reader. Never throws. */
    suspend fun statusChanged(ids: Collection<String>)
    /** Settings' "Sync now", and after connecting an account. */
    fun syncNow()
    /** "Check these matches": the owner picked [candidate]. Throws IllegalArgumentException with a sentence to show. */
    suspend fun pick(itemId: String, candidate: MatchCandidateView)
    /** "Not on Hardcover". */
    suspend fun notOnHardcover(itemId: String)
    suspend fun answer(question: StatusQuestion, useHardcover: Boolean)
    suspend fun answerAll(useHardcover: Boolean)
    /** Queue a run. [pull]: read Hardcover's library first. */
    fun kick(pull: Boolean, delayMs: Long = 0)

    companion object {
        const val WORK = "hardcover-sync"
        const val PERIODIC_WORK = "hardcover-sync-periodic"
        const val PULL_EVERY_MS = 15 * 60_000L
        const val PERIODIC_HOURS = 4L
        const val RUN_BUDGET_MS = 8 * 60_000L
        fun get(context: Context): HardcoverSync
    }
}

/** Pure, for tests and the facade. */
fun hardcoverItemState(
    itemId: String,
    books: List<Book>,
    wishes: List<WishBook>,
    records: Map<String, MatchRecord>,
    connected: Boolean,
    enabled: Boolean,
): HardcoverItemState
```
- `hardcoverItemState`: linked (a wish with `hardcoverId` whose id is `itemId` or whose `linkedBookId` is `itemId`) → MATCHED. Otherwise the match item is the wish linked to the book (no `hardcoverId`) or the item itself; its record UNSURE → CHECK, NOT_FOUND → NOT_FOUND (only while its `metadataKey` still matches; else MATCHING), NOT_ON_HARDCOVER → NOT_ON_HARDCOVER; no record → MATCHING when connected and enabled, else OFF.
- Preferences `hardcover-sync`: `pullRequested` (Boolean), `lastPullAt`, `lastSyncAt` (Long), `problem` (String, a `HardcoverProblem` name or absent).
- `statusChanged`, `answer*`, enabling the switch, `syncNow`, the periodic worker and `onAppOpen` (when due) set `pullRequested` and kick: statusChanged with 5 s delay (coalesces a batch), the others with none. `pick` kicks with a pull. Progress (HardcoverOutbox) kicks without a pull, 60 s delay. The new-item watcher kicks without a pull, 10 s delay.
- The new-item watcher: in `start()`, collect `combine(books, wishes, match records)` → the set of pending item ids (B4 matcher); when it gains an id, kick. The first emission after start kicks when anything is pending, which runs the one-time pass and resumes it after a restart. No per-call-site hooks are needed for import, Inbox, e-reader uploads, scans or restores.
- `pick`: `HardcoverLinks(repo).link(itemId, candidate.hardcoverId, candidate.title, candidate.authors)`, then kick with a pull.
- `notOnHardcover`: `HardcoverStore.saveMatch(record with NOT_ON_HARDCOVER, no candidates)`.
- `answer`: `HardcoverStatusSync.answer(accountId, question, useHardcover)`, then kick with a pull. `answerAll` does each current question, then one kick.
- Only work in the background when connected; matching and status sync also need `enabled`. Explicit finishes (Send Finished) run either way.

### WorkManager
- `hardcover-sync`: `OneTimeWorkRequest<HardcoverSyncWorker>`, network CONNECTED, backoff EXPONENTIAL 1 min, `ExistingWorkPolicy.APPEND_OR_REPLACE` (a kick during a run queues one more run after it; runs with nothing due make no requests).
- `hardcover-sync-periodic`: `PeriodicWorkRequest<HardcoverSyncPeriodicWorker>` every 4 h, network CONNECTED, `ExistingPeriodicWorkPolicy.KEEP`, enqueued by `start()`. It only sets `pullRequested` and kicks when connected and enabled.
- `hardcover-outbox` (legacy): `HardcoverOutboxWorker` stays as a shim so work queued by the previous version still runs: it kicks `hardcover-sync` and returns success. `HardcoverOutbox.schedule()` now calls `HardcoverSync.get(app).kick(pull = false, delayMs = 60_000)`.

### The run (`HardcoverSyncWorker` → `HardcoverRun`)
`HardcoverRun` holds the logic so it can be tested without WorkManager; the worker only builds it and maps the outcome to `Result`. The order of work in the queue:

1. Not connected → done. Read the token. `account()` (1 request): account id and privacy; save the id when it changed; clear `problem`.
2. Round (at most 3 per run):
   1. **Pull** when enabled and `pullRequested`: clear the flag first, then read every page (`libraryPage` until a page has fewer than 50 rows; ids must rise; stop at 200 pages). Set `lastPullAt`.
   2. **Matching from the owner's library** (no requests): `HardcoverMatcher.fromLibrary(pulled)`. Right after it, the pull's finished reads (up to 10 per book, from the same request) are upserted to `hardcover_reads`; nothing is deleted.
   3. **Status sync**: `HardcoverStatusSync.sync(api, pulled, createEntries = matcher.pending().isEmpty())`. Set `lastSyncAt`. A pulled library is used for exactly one `sync` call; after any interruption the next attempt pulls again.
   4. **Progress** (when enabled): the outbox positions, as today (`outboxStep`, `recordObservation`). After status sync, so a book that just became Currently Reading is ready for it.
   5. **Send Finished** (explicit finishes, enabled or not), as today.
   6. **Matching** (when enabled): `HardcoverMatcher.matchBatch(api, deadline, progress)`. Any new link sets `pullRequested`.
   7. Another round only when `pullRequested` is set again and time remains.
3. At the 8-minute budget: kick (keeping `pullRequested` as it is) and return success.
4. Errors: 429 → wait `retryAfterMs` (60 s when absent), then start the round again; if the wait does not fit in the budget, kick with that delay and return success. `IOException` or 5xx → `problem = OFFLINE`, `Result.retry()`. 401/403 → `problem = TOKEN_REJECTED`, success (each later kick tries `account()` once). One item's other failure: matching records it NOT_FOUND; status sync skips that item for this run.
5. While it runs, `status.running` is true and the matching pass reports `matchingDone`/`matchingTotal` (an in-process StateFlow; WorkManager runs in the app process).

`ClientHardcoverApi(client: HardcoverClient, token: String, account: HardcoverAccount) : HardcoverApi` adapts the client: `libraryPage` → `RemoteUserBook`; `markRead(id, day)` → `client.markRead(token, id, day, account.id)`; `setStatus` passes `account.privacy`; `editionsByIsbn` turns each hit into `IsbnHit` with `HardcoverMatching.isbn13` (an edition with neither ISBN valid is dropped); `search` maps `HardcoverMatch` to `MatchCandidate`.

### The matcher: `sync/HardcoverMatcher.kt`
```kotlin
class HardcoverMatcher internal constructor(
    private val repo: LibraryRepository,
    private val store: HardcoverStore,
    private val links: HardcoverLinks,
    private val covers: HardcoverCover,
    private val coverDir: File,          // filesDir/hardcover-candidates
    private val now: () -> Long,
) {
    constructor(app: FolioApplication)
    /** Items still to match, newest first. */
    suspend fun pending(): List<MatchQuery>
    /** Links items that match a book in the owner's own Hardcover library. Returns how many. No requests. */
    suspend fun fromLibrary(library: List<RemoteUserBook>): Int
    /** ISBN batches, then searches, until nothing is pending or [deadline]. Returns how many were linked. */
    suspend fun matchBatch(api: HardcoverApi, deadline: Long, progress: (done: Int, total: Int) -> Unit): Int
}

/** Pure: the items to match. */
fun matchItems(books: List<Book>, wishes: List<WishBook>, removed: Set<String>, records: Map<String, MatchRecord>): List<MatchQuery>
```
- An item is: a catalogue entry with no `hardcoverId` (its query uses the linked book's title, authors and identifiers when it has a file, else its own and the `identifiers` in its `details`); or a book that no catalogue entry links. Not removed. Skipped when its record is NOT_ON_HARDCOVER, or UNSURE/NOT_FOUND with the same `metadataKey`.
- ISBN pass: items with ISBNs, in groups whose `isbnForms` total at most 100 strings (one request per group). Each item's hits are the distinct books among the hits for its ISBNs → `fromIsbn`. NotFound, or no ISBNs → search: `searchText` (blank → NOT_FOUND without a request), `api.search` (2 requests) → `fromSearch`.
- Confident → `links.link(...)`; if it throws, record UNSURE with that candidate. Unsure → download each candidate's cover with `HardcoverCover` (not the API; best effort) to `coverDir/<itemId>-<hardcoverId>`, then `store.saveMatch(UNSURE, ...)`. NotFound → `store.saveMatch(NOT_FOUND)`.
- Each item is saved before the next starts, so a stop resumes where it left off.

## B5. sync

### Room schema 11 (`MIGRATION_10_11`, additive only)
```kotlin
@Entity(tableName = "hardcover_baseline", primaryKeys = ["accountId", "hardcoverId"])
data class HardcoverBaselineRow(
    val accountId: Int,
    val hardcoverId: Int,
    /** 0..5; 0 = agreed it is not on Hardcover. */
    val status: Int,
    val statusAt: Long,
    val syncedAt: Long,
)

@Entity(tableName = "hardcover_matches")
data class HardcoverMatchRow(
    @PrimaryKey val itemId: String,
    /** A MatchRecordState name. */
    val state: String,
    /** JSON array: [{"id":1,"title":"…","authors":["…"],"year":2001 or null,"cover":"/path" or null}] */
    val candidates: String,
    val metadataKey: String,
    val checkedAt: Long,
)

@Entity(tableName = "hardcover_questions", primaryKeys = ["accountId", "hardcoverId"])
data class HardcoverQuestionRow(
    val accountId: Int,
    val hardcoverId: Int,
    val itemId: String,
    val title: String,
    val hardcoverStatus: Int,
    val librilloStatus: String,
    val askedAt: Long,
)
```
`WishBook` gains, last: `@ColumnInfo(defaultValue = "0") val statusAt: Long = 0`.

```sql
CREATE TABLE IF NOT EXISTS hardcover_baseline (accountId INTEGER NOT NULL, hardcoverId INTEGER NOT NULL, status INTEGER NOT NULL, statusAt INTEGER NOT NULL, syncedAt INTEGER NOT NULL, PRIMARY KEY(accountId,hardcoverId))
CREATE TABLE IF NOT EXISTS hardcover_matches (itemId TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL, candidates TEXT NOT NULL, metadataKey TEXT NOT NULL, checkedAt INTEGER NOT NULL)
CREATE TABLE IF NOT EXISTS hardcover_questions (accountId INTEGER NOT NULL, hardcoverId INTEGER NOT NULL, itemId TEXT NOT NULL, title TEXT NOT NULL, hardcoverStatus INTEGER NOT NULL, librilloStatus TEXT NOT NULL, askedAt INTEGER NOT NULL, PRIMARY KEY(accountId,hardcoverId))
ALTER TABLE wishlist ADD COLUMN statusAt INTEGER NOT NULL DEFAULT 0
```
- `version = 11`, the three entities added, `MIGRATION_10_11` appended to `ALL_MIGRATIONS`, `11.json` exported and committed.
- `@Dao interface HardcoverDao` and `abstract fun hardcover(): HardcoverDao` on `LibraryDatabase`. Suspend queries for the engine and store, `Flow` for `matches()` and `questions(account)`. Replacing an account's questions happens in one `withTransaction`.
- `MigrationTest`: `schema11AddsHardcoverTablesAndKeepsEverything` (a v10 book, wish, annotation and sync_baseline survive; wish `statusAt` is 0; the three tables accept rows and enforce their keys) and the existing contiguous-chain and all-versions tests pass with 11. `check-migration.py`: the 9→10 section now ends at `val MIGRATION_10_11`; add a 10→11 section in the same style (4 statements, table_info, foreign keys and indexes equal to `11.json`, data kept).
- `LibraryRepository.manageBatch`: a catalogue entry whose status changes also gets `statusAt` (the same `statusAt` argument).
- `LibraryBackup`: `wishJson` writes `statusAt`; `parseWish` reads it with `optLong("statusAt", 0)`, so older backups restore.

### `sync/HardcoverStore.kt`
```kotlin
class HardcoverStore internal constructor(private val database: LibraryDatabase, private val coverDir: File) {
    constructor(app: FolioApplication)   // coverDir = File(app.filesDir, "hardcover-candidates")
    fun matchRecords(): Flow<List<MatchRecord>>
    suspend fun allMatchRecords(): List<MatchRecord>
    suspend fun saveMatch(record: MatchRecord)
    /** Also deletes the record's candidate covers (only files inside coverDir). */
    suspend fun deleteMatch(itemId: String)
    fun questions(accountId: Int): Flow<List<StatusQuestion>>
    suspend fun deleteQuestion(accountId: Int, hardcoverId: Int)
}
```
A row with an unknown state or broken JSON reads as no record (it is matched again), never a crash.

### `data/HardcoverLinks.kt`
```kotlin
class HardcoverLinks(private val repo: LibraryRepository) {
    /**
     * Links [itemId] (a book id or a catalogue entry id) to Hardcover book [hardcoverId], as
     * DiscoveryViewModel.link does, under repo.lock in one transaction. Returns the id of the
     * catalogue entry that carries the link, and deletes the item's MatchRecord. Throws
     * IllegalArgumentException with a plain sentence when the item is gone or in Recently removed,
     * when that Hardcover book's catalogue entry is in Recently removed, or when that Hardcover
     * book is already linked to another book.
     */
    suspend fun link(itemId: String, hardcoverId: Int, title: String, authors: List<String>): String
}
```
- A catalogue entry: set `hardcoverId`, `hardcoverTitle`, `hardcoverSynced = false`, unless another entry already has that `hardcoverId`.
- A book: if a catalogue entry already links it, link that entry as above. Else, if an entry with that `hardcoverId` exists with no file, attach the book to it (`requireAttachment`, copy its list memberships, as `LibraryRepository.attach`). Else insert `WishBook(UUID, "hardcover:<id>", title, encode(authors), cover copied from the book like DiscoveryViewModel.link, hardcoverId, title, linkedBookId = book.id)`.

### `sync/HardcoverStatusSync.kt`
```kotlin
class HardcoverStatusSync internal constructor(
    private val repo: LibraryRepository,
    private val store: HardcoverStore,
    private val covers: HardcoverCover,
    private val now: () -> Long,
    private val zone: () -> ZoneId,
) {
    constructor(app: FolioApplication)
    suspend fun sync(api: HardcoverApi, library: List<RemoteUserBook>, createEntries: Boolean): StatusSyncResult
    suspend fun answer(accountId: Int, question: StatusQuestion, useHardcover: Boolean)
}
```
`sync`, in order:
1. Observations for `api.accountId`: upsert one per pulled book (status, pages, edition, now); delete that account's rows for books not in the pull.
2. One `MergeInput` per catalogue entry with `hardcoverId` (first one per id), skipping entries in Recently removed and entries whose book is. Local side: the book's status and `statusAt` when the entry has a file, else `catalogueWord(entry.status)` and `entry.statusAt`. Remote: the pulled row. Baseline: `hardcover_baseline` for the account.
3. Apply `HardcoverMerge.plan` per item, saving each before the next:
   - Agree → baseline.
   - Down → `repo.manageBatch(setOf(itemId), status = word, statusAt = statusAt)`, then baseline. It does not call the status hook (no echo).
   - Up → 3: `api.markRead(id, finishedOn)`; else `api.setStatus(id, status, userBookId)`. Then baseline and observation. Transient errors propagate to the run (nothing saved for that item, so it is planned again after the next pull). `IllegalArgumentException`/`IllegalStateException` → skip the item this run.
   - Gone → baseline (0, statusAt).
   - Ask → collected.
4. Replace the account's questions with the collected ones.
5. When `createEntries`: each pulled book with a known status whose `hardcoverId` no stored catalogue entry has (Recently removed ones included, so a removed entry is never brought back) becomes `WishBook(UUID, "hardcover:<id>", title, encode(authors), hardcoverId = id, hardcoverTitle = title, hardcoverSynced = true, status = toPhone(status), statusAt = updatedAt ?: now)` with baseline Agree. Then its cover, best effort, through `HardcoverCover` into `repo.root/<entry id>/hardcover-cover`.

`answer`:
- `useHardcover`: hardcoverStatus 0 → baseline (0, the item's current `statusAt`); Librillo keeps its status. Otherwise `manageBatch` to `toPhone(hardcoverStatus)` at now, and baseline (hardcoverStatus, now).
- Librillo's: baseline (hardcoverStatus, 0). The next sync then sees only Librillo changed and sends it (for 0, `statusAt > 0` re-adds it).
- Then delete the question.

### Hooks
- `RoomSyncLibrary`'s app constructor passes `HardcoverSync.get(app)::statusChanged` instead of `HardcoverFinishPrompts(app)::statusChanged`. The internal constructor and `apply`'s call stay as they are.
- `LibraryViewModel`: drop `finishPrompts`; `finishedHere` becomes `HardcoverSync.get(app).statusChanged(ids)` for every status set (no more `queueFinish` there; the engine sends Finished with the date rule). Keep `unmatchedOnHardcover`'s message only for items whose tile state would be NOT_ON_HARDCOVER, NOT_FOUND or OFF; a match in progress is not "won't hear about it".

### Tests (Robolectric, in-memory Room, a fake `HardcoverApi`)
Down to a book and to a catalogue entry; Up for each status, with and without a user_book; Finished from Currently Reading carries the status day; straight to Finished has no date; Unread sends nothing; Ask stores a question and changes nothing; answers both ways; Gone keeps the book and is not re-added until its status changes; new catalogue entries only when `createEntries`; a Recently removed entry is neither touched nor re-created; a transient error leaves no baseline for that item; links for book, entry, attach-to-existing-entry and each refusal.

## B6. ui

All copy goes in `app/src/main/res/values/strings_m10_hardcover.xml` (prefix `hc10_`). Plain words. Proposed copy (adjust only for length):

| Where | Text |
|---|---|
| Settings switch | Sync with Hardcover |
| Switch hint | Statuses both ways, reading progress and new books. Unsure matches wait for you in Needs your decision. |
| Status line | Synced %1$s · %2$d matched · %3$d to check (drop "· 0 to check") |
| | Not synced yet · %1$d matched |
| | Matching your library… %1$d of %2$d |
| | Syncing… |
| | Waiting for a connection |
| | Hardcover turned down the token. Reconnect your account. |
| | Sync is off |
| Action | Sync now |
| Sheet section | Check these matches |
| Card | Which one is it on Hardcover? |
| Candidate | %1$s · %2$d (author · year; author only when no year) |
| Buttons | This one / Not on Hardcover / Search Hardcover |
| Question | %1$s: Hardcover says %2$s, Librillo says %3$s. Which is right? |
| Question, gone | %1$s is no longer on Hardcover. Librillo says %2$s. |
| Buttons | Hardcover: %1$s / Librillo: %1$s; for gone: Leave it off Hardcover / Put it back |
| Bulk (2+ questions) | Use Hardcover for all / Use Librillo for all |
| Tile | Matched · Matching… · Check match (detail: Tap to choose) · Not on Hardcover · Not found (detail: Tap Hardcover) · Not matched (detail: Tap Hardcover) |

Hardcover's status names in questions: Want to Read, Currently Reading, Read, Paused, Did Not Finish. Librillo's are its own words.

- `DecisionsSheet`: remove `FinishPrompt`, `FinishPromptCard`, `settledPrompts`, `finishPromptTag`. New parameters: `checks: List<MatchCheck>`, `questions: List<StatusQuestion>`, `onPick: (MatchCheck, MatchCandidateView) -> Unit`, `onNotOnHardcover: (MatchCheck) -> Unit`, `onSearch: (MatchCheck) -> Unit`, `onAnswer: (StatusQuestion, Boolean) -> Unit` (true = Hardcover), `onAnswerAll: (Boolean) -> Unit`. Order: e-reader questions, Check these matches, Hardcover questions. Candidate rows: cover (`CoverImages.thumbnail(coverPath)`, a placeholder when null), title, author and year, at least 48 dp tall. Test tags for every button.
- `SyncStatusLine.syncLine`: the `prompts` parameter becomes `hardcover: Int` (match checks + questions), still counted with no e-reader.
- `FolioApp`: remove the finish prompt wiring (`finishPrompts`, the `settledPrompts` effect, `finishPromptId` and the reading sheet it opened). Feed the sheet from `HardcoverSync` flows. "Search Hardcover" opens the existing Hardcover sheet for a book (`discovery.openReading`) or the entry search (`discovery.searchHardcover`). Errors from `pick` show in the snackbar.
- `DeviceViewModel`: drop `finishPrompts`, `prompts`, `dismissPrompt`; expose `hardcoverStatus`, `hardcoverChecks`, `hardcoverQuestions` and actions that call the facade in `viewModelScope`.
- Settings → Hardcover: rename the switch, bind it to `HardcoverSync.enabled`, show the status line under it (tap opens Needs your decision when `toCheck > 0`), add Sync now. Keep Import library and Send Finished books. Reword `m9sync_hardcover_automatic_hint` (it says the e-reader asks first) or replace it; remove the unused `m9sync_finish_*` strings.
- Book page and catalogue page Hardcover tile: take a `HardcoverItemState` (from `itemStates`); a page number still wins when known. "Not matched · Tap Hardcover" only for OFF.
- After connecting an account: `syncNow()`.
- Previews and `ScreenshotTest` entries: Decisions with checks and questions; Settings with "synced", "matching" and "token rejected"; the tile in each state. Check light, dark and 200% text before pushing.
- Tests: pick, Not on Hardcover, both answers and the bulk answers call the right callback; the status line text for each case; `syncLine` counts Hardcover items.

## B7. Logging and privacy
No `Log` call with a token, a title, an author or a response body. Counts and state names only. Exceptions carry fixed sentences.

## B8. Definition of done
- `./gradlew checkFormatting :core:test :transport:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` green.
- `python3 scripts/check-hardcover-schema.py` (14 operations) and `python3 scripts/check-migration.py` pass.
- `MigrationTest` passes in CI's emulator.
- Screenshots checked.
- Report verification levels honestly: everything above is JVM, Robolectric or emulator with recorded Hardcover responses. Nothing has run against the real Hardcover API or on the owner's phone until B9 is done.

## B9. Device checks for the owner
1. Install over the current app. The library, lists and positions are intact.
2. Settings → Hardcover shows "Matching your library… n of N", then "Synced … · n matched · n to check".
3. Needs your decision: pick a candidate for one book, and "Not on Hardcover" for another. Neither comes back.
4. Mark a book Want to Read on hardcover.app. Reopen Librillo after 15 minutes (or Sync now): it appears as a catalogue entry.
5. Mark a catalogue entry Want to Read in Librillo: it appears on hardcover.app.
6. Finish a book on the Kindle, sync it: hardcover.app shows it Read with that day. No prompt appears.
7. Change the same book on both sides before syncing: one question appears; the answer wins on both sides.
8. Remove a book from Hardcover: it stays in Librillo and is not added back.

---

# Part C: as built (2026-10-16)

Everything in Parts A and B is built. Where the code differs from Part B, the code below is what runs.

## C1. Differences from the contract
- **Contract file:** `HardcoverContract.kt` is B2 with two KDoc comments wrapped by ktfmt (above `IsbnHit` and `markRead`). The code is identical.
- **Matching rules (core):** several distinct ISBN books are always "Check these matches", never linked (A2 wins over B3's wording). Normalisation also drops apostrophes ("Ender's" = "Enders") and cuts a subtitle at an en dash. `searchText` sends the raw main title and first author (accents kept, "Last, First" turned round), not the normalised form. `metadataKey` has no version marker: changing normalisation later makes every Not found item be checked again (costs requests, loses nothing).
- **Scheduling:** `kick` adds nothing when a queued run starts by the asked time or a run already waits behind a running one; otherwise REPLACE when idle, APPEND_OR_REPLACE during a run. This stops one queued run per page turn.
- **A run with nothing to do makes no request**, not even the account check (unless a problem is recorded, then the account is checked once).
- **The pull** stops with an error at 200 pages (10,000 books) instead of syncing a partial library. After a failed pull or status sync (slow down, server error, no connection, rejected token) the next run pulls again.
- **Matching from the owner's library** also retries Not found items, and Unsure items whose details changed. It costs no requests.
- **Detach:** taking a book off a Hardcover-linked catalogue entry records it as Not found for its current details, so automatic matching does not undo the owner's correction. A manual link still works.
- **Answering "Librillo" for a book gone from Hardcover** sets the baseline time to -1, so the book is put back at the next sync even when its status time is unknown (0).
- **Kindle hook:** `RoomSyncLibrary` looks `HardcoverSync` up when a status changes, not when the Link server starts. `statusChanged` only queues a run for linked items; an unlinked item reaches Hardcover after matching links it (a new link asks for a pull).
- **Sync off:** phone and Kindle status changes do not reach Hardcover until it is on again. Settings' Send Finished books still works with sync off.
- **Catalogue page:** "Add to Hardcover Want to Read" is hidden while sync is on and connected, since status sync sends a matched entry by itself.
- **Copy:** the switch hint is "Statuses, progress and new books." (B6's longer hint was cut off at 200% text). Status questions sit under "Hardcover and Librillo disagree".
- **Search Hardcover** on a "Check these matches" card opens the book's (or entry's) own Hardcover sheet.
- **Pacing in tests:** recorded responses skip the 1.1 s wait but keep the 429 cool-down; `HardcoverClient.resetPacing()` resets the process-wide gate between tests.
- **Integrator cleanups outside the file lists:** `ui/DeviceScreen.kt` (`syncLine(..., hardcover = 0)`), `ui/HardcoverReadingSheet.kt` (the finish prompt's `finishOnOpen` removed), `strings_link.xml` (unused `hardcover_automatic` removed), `HardcoverOutbox.queueFinish`'s comment.

## C2. Known limits
- `libraryPage` still rejects a whole page when one row is invalid (for example a book with a blank title). Then status sync does not run at all; progress, finishes and matching still do, and Settings says nothing about it.
- The matching count in Settings lives in memory: after a process restart "Matching your library… n of N" counts again from 0 for the items still pending.
- Candidate covers come from assets.hardcover.app, not the API, so they are not paced (up to 3 small images per unsure item). Catalogue entry covers are fetched for at most 60 s per sync.
- The ISBN query is capped at 200 editions per request; a batch whose ISBNs have many editions may miss some hits, which then fall back to search.
- The first sync after linking sends every linked book whose Librillo status differs from Hardcover (Unread excluded). For a large library that takes a while at about 54 requests a minute; Read costs up to 4.

## C3. Verification
- **JVM:** core rules (`HardcoverStatusesTest`, `HardcoverMergeTest`, `HardcoverMatchingTest`).
- **JVM with Robolectric and in-memory Room, against recorded Hardcover answers:** client queries and the gate (`HardcoverClientTest`, `HardcoverLibraryTest`), the outbox (`HardcoverOutboxTest`), the run (`HardcoverRunTest`), the matcher (`HardcoverMatcherTest`), tile states (`HardcoverItemStateTest`), the facade (`HardcoverSyncFacadeTest`), links (`HardcoverLinksTest`), the store (`HardcoverStoreTest`), status sync (`HardcoverStatusSyncTest`), the Kindle hook (`RoomSyncLibraryTest`), the UI (`HardcoverSyncUiTest`, `SyncLineTest`, `SyncUiTest`) and screenshots (light, dark, 200% text).
- **Scripts:** `check-hardcover-schema.py` (14 operations against the published schema), `check-migration.py` (10→11 against `11.json`).
- **Compiled only:** `MigrationTest` (10→11 with `MigrationTestHelper`) needs CI's emulator. WorkManager scheduling (the queue, the periodic work, the legacy `hardcover-outbox` shim) has no test.
- **Not verified:** nothing has run against the real Hardcover API or on the owner's phone. B9 lists the device checks.
