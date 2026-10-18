# Reading statistics (M12): design and build plan

The spec for M12's data and numbers. The owner's decisions are in `docs/UPGRADE_PLAN.md`, "M12: Reading statistics". How the Stats tab looks is in `docs/STATS_UI.md` (written separately). This file covers:
- (a) the data contract: what a session is, where each source's sessions come from, the wire format and the storage;
- (b) the rules behind every number;
- (c) what the UI can ask for;
- (d) the build plan for phase 2.

**Built in phase 1** (package `dev.sadking.folio.core.stats`, pure Kotlin, JVM-tested):

| File | What it holds |
|---|---|
| `StatsModel.kt` | The data classes: inputs (`ReadingSession`, `StatsBook`, `BookFinish`, `GoalSettings`) and every result type. |
| `ReadingStats.kt` | The aggregation behind every number in (b). |
| `KoreaderSessions.kt` | KOReader page rows to sessions (the reference for the plugin's Lua), session ids, the watermark, and the phone's checks. |
| `SessionTracker.kt` | The phone's reading and listening sessions, from page turns and playback. |
| `EpubWords.kt` | A book's word count from its EPUB text. |

Tests are under `core/src/test/kotlin/dev/sadking/folio/core/stats/`. The KOReader cases shared with the plugin are in `core/src/test/resources/stats/koreader-sessions.json`.

---

## (a) Data contract

### a1. The session record

`ReadingSession` is one stretch of reading or listening. Field by field:

| Field | Type | Meaning |
|---|---|---|
| `id` | String | Stable across repeated syncs. The same id is the same session: it is upserted, never copied. |
| `source` | `KINDLE`, `PHONE`, `LISTEN` | Where it was recorded. |
| `deviceKey` | String | A `DeviceKeys` key: `ereader:<KOReader device id>` or `phone:<phone device id>`. |
| `bookId` | String? | The library book id, when known. |
| `document` | String? | KOReader's partial MD5 of the file (32 lowercase hex), the same digest as `koreaderDigest` in `reader/ReaderPosition.kt`. At least one of `bookId` and `document` is set. |
| `startMs` | Long | Start, epoch milliseconds. |
| `durationSec` | Long | Seconds that count, with idle time already removed. 1 to 86,400. |
| `progressStart`, `progressEnd` | Double? | Where in the book it started and ended, 0..1. |
| `progressRead` | Double? | How much of the book was actually read, 0..1, jumps left out. Words read = `progressRead × book words`. |
| `pages` | Int? | Distinct pages seen (KOReader only). |

The statistics group sessions by `book`: `bookId`, else `doc:<document>`.

What each source fills:

| Field | KINDLE | PHONE | LISTEN |
|---|---|---|---|
| `id` | `k` + 32 hex of SHA-256(`deviceId\nmd5\nstartSec`) (`KoreaderSessions.sessionId`) | random UUID when the session starts | random UUID |
| `deviceKey` | `ereader:<id>` | `phone:<phoneDeviceId>` | `phone:<phoneDeviceId>` |
| `bookId` | null when stored. Filled when read, from `koreader_digests`. | the book | the book |
| `document` | the MD5 | null | null |
| `startMs` | first page row's start | first page shown | playback starts |
| `durationSec` | sum of the page times, each capped at 2 minutes | the same, from page turns | playing time |
| `progressStart`, `progressEnd` | `(first page − 1) / total`, `last page / total` | Readium `totalProgression` at start and at the last page | the spoken sentence's `totalProgression` |
| `progressRead` | sum of `1 / total` over distinct pages | the sum of forward steps of at most 5% that were not jumps | the same, sentence to sentence |
| `pages` | distinct pages | null | null |

PDFs and comics get sessions too. They have no word count, so they have no words per minute; time left falls back to time per share of the book (b8).

### a2. Book keys

- A library book: its `id`.
- A Kindle session: stored with `document`. When read, the phone looks up the digest in `koreader_digests` (a6); a match gives `bookId`. An unmatched document stays its own book (`doc:<md5>`) until a match appears. Nothing is rewritten.
- A Hardcover finish (a7): the library book linked to that Hardcover book, else its catalogue entry's id, else `hc:<hardcoverId>`.
- A catalogue entry (`WishBook`) without a file: its own id.

### a3. KOReader page statistics to sessions

**Where:** in the plugin (Lua), not on the phone. Why:
- A full history of page rows is large. A heavy reader has hundreds of thousands of rows: tens of MB of JSON, over the routes' 16 MB body limit. The same history is a few thousand sessions: a few hundred KB.
- Only sessions leave the e-reader: no titles, no page text.

The Kotlin `KoreaderSessions.derive` is the reference. The Lua port must give the same output for every case in `koreader-sessions.json`. `scripts/check-plugin.py` checks this (task D2).

**Input rows:** `page_stat_data` joined to `book`:
```sql
SELECT b.md5, p.page, p.start_time, p.duration,
       CASE WHEN p.total_pages > 0 THEN p.total_pages ELSE COALESCE(b.pages, 0) END
FROM page_stat_data p JOIN book b ON b.id = p.id_book
WHERE p.start_time >= <from> AND p.start_time < <to> AND p.duration > 0 AND b.md5 IS NOT NULL
ORDER BY p.start_time, p.page, b.md5, p.duration DESC;
```

**Rules (per book, keyed by MD5):**
1. Skip a row when its MD5 is not 32 hex characters after lowercasing, its page is under 1, its start is 0 or less, or its duration is 0 or less.
2. Sort the rows by start, then page. Rows with the same start and page are one row: keep the longest.
3. Idle cap: each row counts `min(duration, 120)` seconds from its start. Time already counted by an earlier row of the same session is not counted again. Owner's rule: a pause over 2 minutes doesn't count, as in KOReader.
4. Gap rule: a row that starts more than 300 seconds after the end of the counted time so far starts a new session. Exactly 300 continues.
5. A session's fields:
   - `seconds`: its counted time;
   - `pages`: its distinct pages;
   - `from`: `(first page − 1) / first row's total`;
   - `to`: `last page / last row's total`;
   - `read`: adds `1 / total` for each distinct page, the first time it is seen with a total over 0.
   - All three are clamped to 0..1 and rounded as `floor(x × 1e6 + 0.5) / 1e6`. They are null when no row had a total.
6. Drop sessions of 0 seconds. Sort the result by start, then MD5.

**The watermark (incremental sync):**
- The phone keeps the watermark, so a reinstall or a restore just sends more. For each e-reader it is `until`: the largest `start + seconds` (epoch seconds) of that e-reader's stored KINDLE sessions, 0 when none (`KoreaderSessions.until`).
- The plugin asks for it (a4), then derives from `cut = max(0, until − 86,400)` (`KoreaderSessions.cut`). The one-day look-back finishes sessions that were still growing at the last sync. They come back with the same id and replace the shorter copy.
- `before`: for each book, the latest row before the cut (same filters):
  ```sql
  SELECT b.md5, p.page, MAX(p.start_time), p.duration, 0
  FROM page_stat_data p JOIN book b ON b.id = p.id_book
  WHERE p.start_time < <cut> AND p.duration > 0 AND b.md5 IS NOT NULL GROUP BY b.md5;
  ```
  A book's first session that continues from that row (gap ≤ 300 s) began before the cut. It is dropped, never sent cut short.
- The first sync (`until` = 0) sends the full history.

**Known limits** (say so; don't fix):
- A session longer than a day without a 5-minute gap loses its growth after the first send.
- Rows that KOReader's own statistics sync later merges in, dated before the cut, are never sent.
- A wrong e-reader clock gives wrong days.

**Reading the database on a Kindle:**
- Read only: `PRAGMA query_only=ON`, inside one transaction.
- Read in time windows of 30 days (`WINDOW = 30 × 86,400`), feeding rows to a per-book state that carries over from window to window. This gives the same result as one pass without holding every row in memory.
- Columns from lua-ljsqlite3 can be `int64` cdata: pass every number through `tonumber`.

### a4. Wire format: statistics version 2

Version 1 (`POST /folio/v2/statistics`, totals per day) stays as it is. Settings → Reading time keeps using it. Version 2 adds sessions.

**`GET /folio/v2/statistics`** with header `x-folio-device: <device id>`. The phone answers:
```json
{"format": "folio-statistics", "versions": [1, 2], "until": 1791750000}
```
- `until` is the watermark for that device (epoch seconds; 0 when none).
- A missing or empty header, or one over 128 characters, gets 403 `{"code":2003}`.
- A phone without version 2 answers 404. The plugin then sends version 1 only.

**`POST /folio/v2/statistics`, version 2:**
```json
{"device": {"id": "kindle-7f3a", "name": "Kindle Paperwhite"},
 "format": "folio-statistics", "version": 2, "exportedAt": 1791750000,
 "sessions": [{"book": "0123456789abcdef0123456789abcdef", "start": 1791740000, "seconds": 1260,
               "pages": 14, "from": 0.4012, "to": 0.4421, "read": 0.0409}]}
```
- At most 500 sessions per request (`KoreaderSessions.MAX_PER_REQUEST`). They are sorted by start and sent in requests of 500, at most 40 a sync (`MAX_REQUESTS`). The rest go at the next sync.
- `from`, `to` and `read` may be missing or null: both mean unknown. Lua drops nil keys.
- The phone checks the shape: `format`, `version` 2, `device.id` 1 to 128 characters, `exportedAt` > 0, `sessions` an array of at most 500 objects with the right types. Anything else is 403 `{"code":2003}`, and nothing is kept.
- Then each session is checked on its own (`KoreaderSessions.accepts`). A session that fails is skipped; the rest are kept.
- Answer: `{"ok": true, "kept": 498, "skipped": 2, "until": 1791750000}`.
- Kept sessions are upserted by id (`KoreaderSessions.toSession`). A stored session is replaced only by a longer or equal one (never discard reading data). Nothing is ever deleted: a session missing from a later send is not a deletion.

**Version combinations:**

| Plugin | Phone | What happens |
|---|---|---|
| old (v1) | new | v1 totals as today. |
| new | old | GET is 404, so the plugin sends v1 only. |
| new | new | v1 totals, then GET `until`, then v2 sessions. |

### a5. Phone sessions

`SessionTracker` (core) holds the rules; the app only reports events and saves what it returns (upsert by id).
- **Reading (PHONE):**
  - `start` when a book's pages show (the reader resumes or opens, or read-aloud stops while the reader is visible);
  - `mark` at each settled page, with `jump = true` after contents, search, a link, the slider or a bookmark;
  - `stop` on pause, close, or when read-aloud starts playing.
  - Each page counts at most 2 minutes. A page held for more than 2 + 5 minutes ends the session there.
- **Listening (LISTEN):** `start` when playback plays, `mark` at each new sentence, `stop` when it pauses or stops. All playing time counts.
- **Both:**
  - Coming back to the same book within 5 minutes continues the session (same id; the pause is not counted).
  - Under a second is never saved.
  - Reading never runs while read-aloud plays, so the two never double-count.

### a6. Word counts and digests

- **Words:** `EpubWords.count(file)` over every spine item:
  - `head`, `script` and `style` are left out;
  - a word is a run of letters or digits, joined by an apostrophe or a hyphen;
  - each Chinese or Japanese character is one word.
  - The count is null when the file can't be read (protected, damaged).
  - It is counted once per file revision (`book_words.revisionHash` = the book's `currentHash`) and recounted when the file changes.
- **Digests:** `koreaderDigest(file)` of the book's original and current files, kept in `koreader_digests` with their revision hash. Kindle sessions find their book through these.

### a7. Finishes

A book counts as finished on its finished date (local), from two sources:
- **Library:** a book with status Finished and `statusAt` > 0, on the local date of `statusAt`. A catalogue entry without a file counts the same way, by its own status, under its own id. An entry with a file uses its book's status, so it is never counted twice.
- **Hardcover:** each read with a `finished_at` date (a8, `hardcover_reads`), keyed as in a2.
- **Undated finishes are not counted:** `statusAt` 0, or a Hardcover read without a date.

The core then:
1. Doesn't trust a library finish next to Hardcover's history. A book's status that came down from Hardcover carries the time it changed there, not when it was read. So when Hardcover has a dated finish for the book, a library finish counts only if it is within 7 days of a Hardcover finish, or the book has a session in the 60 days up to it (`ReadingStats.trustedFinishes`).
2. Merges: one finish per book per 7 days, keeping the earliest (`ReadingStats.mergeFinishes`). Librillo's own finish and the one it sent to Hardcover become one. A re-read months later is a second finish.

### a8. Storage: Room schema 14

Additive only. Four new tables. Exact SQL for `MIGRATION_13_14`:
```sql
CREATE TABLE IF NOT EXISTS reading_sessions (id TEXT NOT NULL, source TEXT NOT NULL, deviceKey TEXT NOT NULL, bookId TEXT, document TEXT, startMs INTEGER NOT NULL, durationSec INTEGER NOT NULL, progressStart REAL, progressEnd REAL, progressRead REAL, pages INTEGER, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))
CREATE INDEX IF NOT EXISTS index_reading_sessions_startMs ON reading_sessions (startMs)
CREATE INDEX IF NOT EXISTS index_reading_sessions_bookId ON reading_sessions (bookId)
CREATE INDEX IF NOT EXISTS index_reading_sessions_document ON reading_sessions (document)
CREATE INDEX IF NOT EXISTS index_reading_sessions_deviceKey ON reading_sessions (deviceKey)
CREATE TABLE IF NOT EXISTS koreader_digests (digest TEXT NOT NULL, bookId TEXT NOT NULL, revisionHash TEXT NOT NULL, computedAt INTEGER NOT NULL, PRIMARY KEY(digest))
CREATE INDEX IF NOT EXISTS index_koreader_digests_bookId ON koreader_digests (bookId)
CREATE TABLE IF NOT EXISTS book_words (bookId TEXT NOT NULL, revisionHash TEXT NOT NULL, words INTEGER, countedAt INTEGER NOT NULL, PRIMARY KEY(bookId))
CREATE TABLE IF NOT EXISTS hardcover_reads (accountId INTEGER NOT NULL, readId INTEGER NOT NULL, hardcoverId INTEGER NOT NULL, startedOn TEXT, finishedOn TEXT, fetchedAt INTEGER NOT NULL, PRIMARY KEY(accountId, readId))
CREATE INDEX IF NOT EXISTS index_hardcover_reads_hardcoverId ON hardcover_reads (hardcoverId)
```
- No foreign keys. Sessions outlive their book: a removed or purged book never takes its reading data with it.
- `source` holds the enum name. `startedOn` and `finishedOn` are `YYYY-MM-DD`.

**Backups (format `folio-backup`, version stays 4; new optional keys):**
- `readingSessions`: every row, fields as in the table.
  - On restore, `bookId` goes through the restore's id mapping.
  - A row that can't be read is skipped.
  - A row whose id exists is kept only when it is longer.
- `bookWords`: every row. Restored only when the restored book's `currentHash` equals `revisionHash`.
- Older backups have neither key and restore as before.
- `koreader_digests` and `hardcover_reads` are not backed up. They are recomputed from the files and pulled again from Hardcover.

**Goals** live in SharedPreferences `stats`: `goalBooksPerYear` and `goalMinutesPerDay`. Each is absent or 0 when off. They are not in backups, like other settings.

### a9. Privacy
- Only sessions leave the e-reader. A session carries an MD5, numbers and fractions: no titles, no text.
- Word counting reads book text in memory only. It never logs or keeps it.
- Nothing in M12 logs a session's book, a title or a digest.

---

## (b) Aggregation rules

All of this is in `ReadingStats`. Build it from all sessions, books and finishes, with `zone` (the phone's current time zone) and `today`.

**b1. Cleaning, before anything:**
1. Drop sessions that have no book, a duration outside 1..86,400 s, or a start ≤ 0.
2. One per id: the longest.
3. **Overlap:**
   - Reading (KINDLE + PHONE) and listening are handled separately.
   - In start order, the part of a session that an earlier session *from another device* still covers is cut from its start. A session left with nothing is dropped. So time read on the Kindle and the phone at once counts once, and so does one e-reader known under two ids.
   - A device's own sessions are never cut: it reads one book at a time.
   - A session's time is the stretch `[start, start + duration)`.

**b2. Days and time zones:**
- Everything is in local time in `zone`.
- A session's counted stretch is cut at each local midnight. Each day gets its own part, so a session from 23:30 to 00:30 gives 30 minutes to each day.
- Hours are cut the same way, by local hour: daylight-saving days have 23 or 25 hours.
- A trip abroad moves past sessions to the new zone's days. That is accepted.

**b3. Calendar (`month`, `day`):**
- Every day of the month: reading seconds, listening seconds, its books, and the books finished that day.
- **Colours:** each book gets a slot for the month, in the order of its first counted moment that month (reading or listening; ties by key). Slots 0..7 (`BOOK_SLOTS` = 8) are the eight colours. Books past the eighth get `OTHER_SLOT` (8).
  - A book keeps its slot on every day of the month.
  - A day lists its books in slot order.
  - A tapped day uses the same slots, and lists its sessions in time order with their part of that day.
- A reading day is a day with at least 1 minute of reading (`MIN_READING_DAY_MS`).

**b4. Streaks:**
- A streak is consecutive reading days. Listening doesn't count.
- The **current** streak ends today, or yesterday when today has no reading yet; otherwise there is none.
- The **longest** is the longest streak ever. On a tie, the earliest wins.

**b5. Habits (`habits(range)`), reading only except the listening total:**
- Reading seconds, reading days, and the average per reading day.
- 24 hourly totals.
- 7 weekday totals, Monday first, with how many of that weekday the range holds, so the average is `seconds / occurrences`.
- Session lengths and speed for sessions starting in the range.

**b6. Session lengths:**
- Sessions under 1 minute are glances: left out.
- Count, median (the mean of the middle two when even), mean, the longest, and 5 buckets: under 5 minutes, 5–15, 15–30, 30–60, and 60 or more.
- A session's length here is its counted time after b1.

**b7. Words per minute:**
- A session qualifies when all of these hold:
  - it lasts at least 3 minutes;
  - its book's words are known;
  - `progressRead` > 0;
  - its own pace, `progressRead × words / minutes`, is between 30 and 1,200.
- Speed is total words over total minutes of the qualifying sessions: time-weighted, not an average of ratios.
- Shown only from 10 qualifying minutes.
- Reported overall (reading = Kindle + phone), per source, per book, per range, and for listening on its own.
- It uses each session's own duration: `progressRead` was measured over it.

**b8. Per book (`book(key)`):**
- Reading and listening seconds, sessions of each, first and last day, reading days, finishes, speed, and a timeline: one row per day with seconds and the day's last position.
- **Days to finish:** from the first day with a session after the previous finish (or ever) to the latest finish, both days counted. Only when that first session started in the first 5% of the book, so a book begun before tracking never looks fast. Otherwise null.
- **Time left:** null when the book was finished on or after its last session, or when its position (the given progress, else the last session's end) is unknown or ≥ 99.5%. Otherwise:
  - with words: remaining words over the pace, where the pace is the book's speed, else the owner's reading speed;
  - without words: remaining share × seconds per share, from this book's sessions with `progressRead`, once they cover at least 2% of the book;
  - else null.
- `books()`: every book with a session or a finish, most recent activity first.

**b9. Trends:**
- `weeks(n)`: the last n weeks, each starting on `firstDayOfWeek` (Monday by default; pass the locale's), oldest first.
- `months(n)`: the last n months, oldest first.
- Each has reading seconds, listening seconds and books finished.

**b10. Milestones** (date = the day it was reached):

| Kind | Thresholds | Rule |
|---|---|---|
| `FIRST_READ` | 1 | The first reading day (≥ 1 minute), with its first book. |
| `HOURS` | 1, 10, 50, 100, 250, 500, 1000 | The day cumulative reading time reached the threshold. Reading only. |
| `BOOKS` | 1, 10, 25, 50, 100, 250, 500 | The date of the n-th finish, after merging (a7), with that book. |
| `STREAK` | 7, 30, 100, 365 | The first day any streak reached n days. |

`next` gives, for HOURS, BOOKS and STREAK, the first threshold not reached and the current value: whole hours, books, or the current streak's days.

**b11. Records (ties: the earliest):**
- Longest streak.
- Most reading in one day.
- Longest single session.
- Longest book finished (by words).
- Fastest finish (fewest days to finish, b8).
- Most books in a year.
- Most books in a month.
- Books per year: every year from the first finish to this year, with zeros.

**b12. Year in review (`yearInReview(year)`):**
- Reading and listening seconds, and reading days.
- Books finished, in date order.
- Top 5 books by reading time.
- 12 monthly totals.
- The longest streak within the year (streaks cut at the year's edges).
- The most in a day, and the longest session.
- The busiest month, the busiest weekday, and the peak hour.
- Speed.
- Words read: the sum of `progressRead × words` over the year's reading sessions, leaving out any over 1,200 words a minute.
- Milestones reached that year.
- Last year's reading seconds.
- `isEmpty` when there is no reading, listening or finish.

**b13. Goals (`goals(settings)`)**, each null while off:
- **Books per year:**
  - finished = finishes this calendar year (after a7);
  - steady pace = `target × dayOfYear / daysInYear`;
  - `ahead` = finished − steady pace;
  - met when finished ≥ target.
- **Minutes per day**, reading only:
  - today's seconds, and met when ≥ target × 60;
  - the last 7 days, each with met or not;
  - days met this week (from `firstDayOfWeek`) and this month;
  - the streak of met days, ending today or yesterday.

**b14. How sources combine:**
- Reading time is Kindle + phone. Listening is always its own number.
- A screen that says "Reading + listening" adds the two fields itself. The core gives them separately everywhere.

---

## (c) What the UI can ask for

The screens get one `StatsSnapshot` (app, `data/StatsSnapshot.kt`, task D3) from `StatsRepository.snapshot()` (task D9). Until the first one arrives, they show loading.

```kotlin
/** How a book key is shown. [book] is set for a library book (for Cover()); null otherwise. */
data class StatsLabel(val title: String, val authors: String, val book: Book?, val coverPath: String?)

data class StatsSnapshot(
    val stats: ReadingStats,
    /** By book key. A key missing here (an unmatched `doc:` key) shows R.string.stats_unknown_book. */
    val labels: Map<String, StatsLabel>,
    /** By device key, as the device roster names them. `phone:` keys are absent: "This phone". */
    val deviceNames: Map<String, String>,
    val goals: GoalSettings,
    /** An e-reader syncs, but none of its sessions has arrived yet: "first sync pending". */
    val waitingForEreader: Boolean,
)
```

Every call on `snapshot.stats`, and what it returns when there is no data or only some:

| Call | Returns | With no data / partial data |
|---|---|---|
| `isEmpty` | Boolean | true: no session and no finish. Show the "no data yet" state. |
| `overview()` | `Overview` | All seconds 0. Streaks, `speed`, `firstDay` and `lastActivityMs` null. |
| `month(YearMonth)` | `CalendarMonth` | `days` always has every day of the month, with zeros and empty `books`. |
| `day(LocalDate)` | `DayDetail` | Zeros; empty `books`, `sessions` and `finished`. |
| `habits(DateRange)` | `Habits` | 24 zero hours, 7 weekdays with occurrences counted, `sessions.count` 0, `speed` null. |
| `speed(range?)` | `Speed` | Each part null under 10 qualifying minutes, or without word counts. |
| `book(key)` | `BookStats?` | null when the book has no session and no finish. `speed`, `daysToFinish` and `timeLeftSeconds` are null when unknown (b8). Finishes only (Hardcover history): seconds 0, `firstDay` null. |
| `books()` | `List<BookStats>` | Empty. |
| `weeks(n)`, `months(n)` | `List<PeriodTotal>` | Always n rows, zeros included. |
| `milestones()` | `Milestones` | `reached` empty. `next` = HOURS 1, BOOKS 1, STREAK 7, all at 0. |
| `records()` | `Records` | Every record null; `booksPerYear` empty. |
| `yearInReview(year)` | `YearInReview` | `isEmpty` true; lists empty; the "busiest" fields null. |
| `goals(GoalSettings)` | `GoalProgress` | Both parts null while their goal is off. A goal that is on always has a value. |
| `finishes` | `List<BookFinish>` | Empty. |

- **Units:** every duration is whole seconds (`Long`), and every date is a `LocalDate` in the phone's zone. Formatting (minutes, hours, "1 h 20 min") is the UI's job.
- **Colours:** colour slots (`slot` 0..7, `OTHER_SLOT`) are indices. `docs/STATS_UI.md` maps them to colours.
- **Previews:** build a real `ReadingStats` from fake sessions. No mocks needed. Example:
  ```kotlin
  val zone = ZoneId.of("Europe/Madrid")
  val today = LocalDate.of(2026, 10, 17)
  fun s(book: String, day: Int, hour: Int, minutes: Int, source: SessionSource = SessionSource.KINDLE) =
      ReadingSession("$book-$day-$hour", source, "ereader:k", book, null,
          today.withDayOfMonth(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(),
          minutes * 60L, progressRead = minutes / 600.0)
  val stats = ReadingStats(listOf(s("a", 1, 21, 40), s("b", 2, 8, 25, SessionSource.PHONE)),
      books = listOf(StatsBook("a", words = 90_000)), finishes = emptyList(), zone = zone, today = today)
  ```

---

## (d) Build plan (phase 2)

Small tasks, one worker each. Rules for every task:
- Read CLAUDE.md, this file's sections named in the task, and every file you change before changing it.
- Change only the files named. If another file must change, stop and report.
- No new dependency.
- UI copy goes in `strings.xml`.
- Commit as the owner. Never push.
- Done means the checks listed pass. Report which ran, at which level (JVM, compiled only, or not run).
- The standard check:
  ```sh
  ./gradlew --console=plain -q -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false formatKotlin checkFormatting :core:test :transport:test :app:testDebugUnitTest :app:assembleDebug
  ```

**Order:**
```
D1 (transport) ─┐
D2 (plugin)     │   independent of everything; can start at once
D3 (Room 14) ───┼─> D4 (sync saves sessions; also needs D1)
                ├─> D5 (phone + listening tracking)
                ├─> D6 (words + digests)
                ├─> D7 (Hardcover read dates)
                ├─> D8 (backup)
                └─> D9 (StatsRepository; best after D6 and D7, works without them)
U0..U7 (UI): after docs/STATS_UI.md. U0 needs only D3 (types); the screens run on fake data until D9.
```
D1, D2 and D3 run in parallel. D4–D8 run in parallel after D3. They touch different files, except where noted.

### D1. Transport: statistics version 2
- **Files:**
  - `transport/src/main/kotlin/dev/sadking/folio/link/SyncLibrary.kt`
  - `transport/src/main/kotlin/dev/sadking/folio/link/SyncRoutes.kt`
  - `transport/src/test/kotlin/dev/sadking/folio/link/SyncRoutesTest.kt`
  - `docs/SYNC_PROTOCOL.md`
- **Build:**
  1. In `SyncLibrary`, add two methods with defaults:
     ```kotlin
     /** The latest `start + seconds` (epoch s) of the KOReader sessions kept from [ereaderId]; 0 when none. */
     fun sessionsUntil(ereaderId: String): Long = 0
     /** Keeps [sessions] from [ereaderId] (a4); returns how many were kept. Throws IllegalArgumentException to refuse all. */
     fun saveSessions(ereaderId: String, sessions: List<KoreaderSessions.WireSession>): Int = 0
     ```
  2. In `SyncRoutes.handle`, add `request.method == "GET" && route == "/statistics" -> statisticsUntil(request)`. It reads `x-folio-device` (trimmed). Empty or over `MAX_ID` gives `invalid()`. Otherwise it answers `{"format":"folio-statistics","versions":[1,2],"until":<library.sessionsUntil(device)>}`.
  3. In `statistics(text)`, read `version` first:
     - 1: exactly as today.
     - 2: the new `sessions(body)`.
     - anything else: `invalid()`.
  4. `sessions(body)`, per a4:
     - Shape checks. `sessions` must be a `JSONArray` with ≤ `KoreaderSessions.MAX_PER_REQUEST` items, else `invalid()`.
     - Each item is a `JSONObject` with `book` (String), `start` (Long), `seconds` (Long), `pages` (Int), and `from`/`to`/`read`, where a missing key or `isNull` gives null and anything else is `getDouble`. A `JSONException` gives `invalid()`.
     - Keep the items with `KoreaderSessions.accepts(wire, now() / 1000)`.
     - `kept = library.saveSessions(device, accepted)`. `IllegalArgumentException` gives `invalid()`.
     - Answer `{"ok":true,"kept":kept,"skipped":<items − accepted>,"until":library.sessionsUntil(device)}`.
  5. Update the class KDoc step 4 to mention version 2.
  6. In `docs/SYNC_PROTOCOL.md`:
     - at the top, change "KOReader's reading time (its statistics) follows…" to name both versions;
     - under `### POST /folio/v2/statistics`, add the GET route, the version 2 body and its checks, and the version table, copied from a4;
     - under "What the app implements", add `sessionsUntil` and `saveSessions`.
- **Tests (in `SyncRoutesTest`; extend its fake library with a `sessions` map and `until`):**
  - GET with a device header answers `until`, and `versions` [1,2].
  - GET without the header is 403.
  - A version 2 body with 2 good sessions and 1 bad one (book "abc") gives kept 2, skipped 1, and the library got 2.
  - `from`/`read` missing and null are both read as null.
  - 501 sessions is 403.
  - `sessions` not an array is 403.
  - Version 3 is 403.
  - All version 1 tests still pass, unchanged.
- **Done:** `./gradlew … checkFormatting :transport:test` passes.

### D2. Plugin: send sessions
- **Files:**
  - `koreader/folio.koplugin/stats.lua`
  - `scripts/check-plugin.py`
  - `docs/SYNC_PROTOCOL.md` (the plugin's "Reading time" bullet only)
  - `docs/PLUGIN.md`, if it describes reading time
- **Build** (Lua 5.1 / LuaJIT; no new modules):
  1. Constants on `Stats`: `MAX_PAGE_SECONDS = 120`, `GAP_SECONDS = 300`, `LOOKBACK = 86400`, `WINDOW = 30 * 86400`, `PER_REQUEST = 500`, `MAX_REQUESTS = 40`.
  2. `Stats.Deriver`, a port of `KoreaderSessions.derive` (a3 rules), fed rows in `(start, page, md5, duration desc)` order:
     - `Stats.Deriver.new(before)` takes rows `{md5, page, start, duration, total}`; a book's `before` row sets its `beforeEnd = start + min(duration, 120)`;
     - `deriver:add(row)`: clean as rule 1. Skip a row equal in start and page to that book's previous row. Then rules 3–4: if `row.start - state.end > 300` or there is no state, finish the old session and open a new one, marked `truncated` when `beforeEnd` is set and `row.start - beforeEnd <= 300`; clear `beforeEnd`. Then add `max(0, row.start + counted - max(row.start, state.end))`, `state.end = max(state.end, row.start + counted)`, the page into a set, and `1/total` the first time a page is seen with `total > 0`;
     - `deriver:finish()` returns sessions `{book, start, seconds, pages, from, to, read}` sorted by start then book. It drops truncated and 0-second ones. `round6(x) = math.floor(math.max(0, math.min(1, x)) * 1e6 + 0.5) / 1e6`. Fractions are nil when no total was seen.
  3. `Stats.derive(events, before)`: sorts `events` by start, page, md5, duration (desc), feeds a `Deriver`, and returns `finish()`. This is what the shared cases call.
  4. `Stats.readSessions(db, cut, now)`:
     - `PRAGMA query_only=ON;` and `BEGIN;`;
     - the `before` query (a3) when `cut > 0`;
     - `SELECT MIN(start_time) FROM page_stat_data WHERE start_time >= cut AND duration > 0`;
     - then windows `[from, from + WINDOW)` until `from > now + 86400`, each with the events query (a3), fed to one `Deriver`;
     - `COMMIT;` and return `deriver:finish()`.
     - Every number goes through `tonumber`. MD5s go through `string.lower`.
  5. `Stats.sendSessions(cfg, d, headers)`:
     - GET `<server>/folio/v2/statistics` (nil body). Stop unless status is 200 and `body.until` is a number.
     - `cut = until > 0 and math.max(0, until - LOOKBACK) or 0`.
     - Open the database with `d.open_statistics()` and read with `pcall`: on failure `ROLLBACK;`, then always close.
     - POST chunks of 500 as `{device = {id, name}, format = "folio-statistics", version = 2, exportedAt = now, sessions = d.array(chunk)}`. Stop at the first status that is not 200; at most 40 chunks.
     - Return true when all were sent.
  6. In `Stats.send`, after the version 1 POST answers 200, call `pcall(Stats.sendSessions, cfg, d, headers)`. `Stats.send` still returns the version 1 result. `main.lua` doesn't change.
- **Tests (in `scripts/check-plugin.py`, in the reading-time block):**
  - **Shared cases:** load `core/src/test/resources/stats/koreader-sessions.json`. For each case, call `Stats.derive(events, before)` (as Lua tables) and compare with `sessions`: same count; same `book`, `start`, `seconds`, `pages`; `from`/`to`/`read` equal within 1e-9, or both nil/None.
  - **Database:**
    - build a SQLite file with `book (id INTEGER, md5 TEXT, pages INTEGER)` and `page_stat_data` holding rows over 70 days;
    - set `Stats.WINDOW` to 86400 for one run and back to the default for another; both runs must send the same sessions;
    - with `until` = 0, every session is sent;
    - with `until` set, only sessions from `until - 86400` on, and a session crossing the cut is not sent;
    - 1,200 sessions go as 3 POSTs of 500, 500 and 200;
    - a 403 on the second POST stops the sending;
    - the database bytes are unchanged afterwards;
    - only `PRAGMA`, `BEGIN;`, `SELECT…` and `COMMIT;` statements run.
  - **Old phone:** a GET answering 404 gives no session POST, and the version 1 POST is still sent.
  - **Keep the existing test passing:** the version 1 test records the last request. Change it to keep the first one only: `stats_request = stats_request or {...}`.
- **Done:** `python3 scripts/check-plugin.py && FOLIO_LUA=luajit21 python3 scripts/check-plugin.py` pass (`pip install lupa==2.8` if missing). Report that this is JVM/Python level, not a Kindle.

### D3. Room schema 14 and the shared app types
- **Files:**
  - `app/src/main/java/dev/sadking/folio/data/Database.kt`
  - new `app/src/main/java/dev/sadking/folio/data/StatsRows.kt`
  - new `app/src/main/java/dev/sadking/folio/data/StatsSnapshot.kt`
  - `app/src/main/java/dev/sadking/folio/data/Devices.kt`
  - `app/src/main/java/dev/sadking/folio/FolioApplication.kt`
  - `app/schemas/dev.sadking.folio.data.LibraryDatabase/14.json` (generated)
  - `app/src/androidTest/java/dev/sadking/folio/MigrationTest.kt`
  - `scripts/check-migration.py`
  - new `app/src/test/java/dev/sadking/folio/data/StatsRowsTest.kt`
- **Build:**
  1. Entities in `Database.kt`:
     ```kotlin
     @Entity(tableName = "reading_sessions", indices = [Index("startMs"), Index("bookId"), Index("document"), Index("deviceKey")])
     data class ReadingSessionRow(@PrimaryKey val id: String, val source: String, val deviceKey: String, val bookId: String?, val document: String?, val startMs: Long, val durationSec: Long, val progressStart: Double?, val progressEnd: Double?, val progressRead: Double?, val pages: Int?, val updatedAt: Long)
     @Entity(tableName = "koreader_digests", indices = [Index("bookId")])
     data class KoreaderDigestRow(@PrimaryKey val digest: String, val bookId: String, val revisionHash: String, val computedAt: Long)
     @Entity(tableName = "book_words")
     data class BookWordsRow(@PrimaryKey val bookId: String, val revisionHash: String, val words: Long?, val countedAt: Long)
     @Entity(tableName = "hardcover_reads", primaryKeys = ["accountId", "readId"], indices = [Index("hardcoverId")])
     data class HardcoverReadRow(val accountId: Int, val readId: Int, val hardcoverId: Int, val startedOn: String?, val finishedOn: String?, val fetchedAt: Long)
     ```
     Add all four to `@Database(entities)`, and set `version = 14`.
  2. `MIGRATION_13_14`: the a8 statements, one `db.execSQL("…")` per statement, each on one line (`check-migration.py` reads them with a regex). Add a KDoc in the style of `MIGRATION_12_13`. Append it to `ALL_MIGRATIONS`.
  3. `StatsDao`, and `abstract fun stats(): StatsDao` on `LibraryDatabase`:
     ```kotlin
     @Dao interface StatsDao {
         @Query("SELECT * FROM reading_sessions") fun sessions(): Flow<List<ReadingSessionRow>>
         @Query("SELECT * FROM reading_sessions") suspend fun allSessions(): List<ReadingSessionRow>
         @Query("SELECT * FROM reading_sessions WHERE id IN (:ids)") suspend fun sessionsById(ids: List<String>): List<ReadingSessionRow>
         @Upsert suspend fun upsertSessions(rows: List<ReadingSessionRow>)
         @Query("SELECT MAX(startMs / 1000 + durationSec) FROM reading_sessions WHERE source = 'KINDLE' AND deviceKey = :deviceKey") suspend fun kindleUntil(deviceKey: String): Long?
         @Query("SELECT * FROM koreader_digests") fun digests(): Flow<List<KoreaderDigestRow>>
         @Query("SELECT * FROM koreader_digests WHERE bookId = :bookId") suspend fun digestsOf(bookId: String): List<KoreaderDigestRow>
         @Upsert suspend fun upsertDigests(rows: List<KoreaderDigestRow>)
         @Query("SELECT * FROM book_words") fun words(): Flow<List<BookWordsRow>>
         @Query("SELECT * FROM book_words") suspend fun allWords(): List<BookWordsRow>
         @Upsert suspend fun upsertWords(row: BookWordsRow)
         @Query("SELECT * FROM hardcover_reads") fun reads(): Flow<List<HardcoverReadRow>>
         @Upsert suspend fun upsertReads(rows: List<HardcoverReadRow>)
         /** Every book, Recently removed included: a removed book's sessions still have a title. */
         @Query("SELECT * FROM books") fun everyBook(): Flow<List<Book>>
     }
     ```
  4. `StatsRows.kt`:
     - `fun ReadingSessionRow.toSession(): ReadingSession?`: null when `source` is not a `SessionSource` name;
     - `fun ReadingSession.toRow(updatedAt: Long): ReadingSessionRow`;
     - `suspend fun StatsDao.keepSessions(sessions: List<ReadingSession>, now: Long): Int`: upserts each one unless a stored row with that id has a larger `durationSec`; returns how many it wrote.
  5. `StatsSnapshot.kt`: exactly the two data classes in (c).
  6. `DeviceKeys.phone(id: String) = "phone:$id"`, with KDoc: "This phone's own sessions, by its Progress sync device id".
  7. In `FolioApplication`, add `val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)` with KDoc: "App-wide work that must outlive a screen (statistics writes and indexing)".
  8. Build once so Room exports `14.json` (`./gradlew :app:kspDebugKotlin`), and commit it.
- **Tests:**
  - `MigrationTest`: `schema14AddsStatisticsTablesAndKeepsEverything`. Create version 13 with one book, annotation, `device_statistics` row and `kosync_progress` row. Migrate with `MIGRATION_13_14` (validate = true). Assert the four tables exist and are empty, and the old rows are unchanged. Plus `roomOpensSchema14`: build Room with `ALL_MIGRATIONS` on a 13 file, then upsert and read back a session.
  - `check-migration.py`: a block after 12→13 in its style. Load `14.json`, run the `MIGRATION_13_14` statements (assert there are 10, all `CREATE TABLE IF NOT EXISTS` or `CREATE INDEX IF NOT EXISTS`), compare `table_info`, `index_list` and `foreign_key_list` for every table, and assert the old rows survive.
  - `StatsRowsTest` (Robolectric, in-memory Room like the other data tests): round trip of a session; an unknown source gives null; `keepSessions` doesn't shrink a stored session and does grow it.
- **Done:**
  - the standard check passes;
  - `python3 scripts/check-migration.py` passes;
  - `MigrationTest` compiles (`./gradlew :app:assembleDebugAndroidTest`). It runs on CI's emulator: report it as compiled only.

### D4. The phone keeps the e-reader's sessions
- **Needs:** D1, D3.
- **Files:**
  - `app/src/main/java/dev/sadking/folio/sync/RoomSyncLibrary.kt`
  - `app/src/test/java/dev/sadking/folio/sync/RoomSyncLibraryTest.kt`
- **Build:**
  - `override fun sessionsUntil(ereaderId)` = `io { stats.kindleUntil(DeviceKeys.ereader(ereaderId)) ?: 0L }`.
  - `override fun saveSessions(ereaderId, sessions)` = `io { stats.keepSessions(sessions.map { KoreaderSessions.toSession(ereaderId, DeviceKeys.ereader(ereaderId), it) }, now()) }`.
  - `stats = repo.database.stats()`.
  - KDoc each in the style of `saveStatistics`.
- **Tests:**
  - saving 2 sessions stores 2 KINDLE rows with `document` set and `bookId` null;
  - `sessionsUntil` is the latest end;
  - saving a grown copy replaces it;
  - a shorter copy doesn't;
  - another e-reader's `until` is 0.
- **Done:** the standard check passes.

### D5. Phone reading and listening sessions
- **Needs:** D3.
- **Files:**
  - new `app/src/main/java/dev/sadking/folio/reader/SessionRecorder.kt`
  - `reader/ReaderViewModel.kt`
  - `reader/ReaderActivity.kt`
  - `reader/ReadAloud.kt`
  - new `app/src/test/java/dev/sadking/folio/reader/SessionRecorderTest.kt`
- **Build:**
  1. `SessionRecorder(dao: StatsDao, scope: CoroutineScope, deviceKey: String, clock: () -> Long = System::currentTimeMillis)`:
     - It holds `SessionTracker(PHONE, deviceKey) { UUID.randomUUID().toString() }` for reading and one for LISTEN.
     - It writes each returned session with `dao.upsertSessions(listOf(it.toRow(clock())))` on `scope`, using `Dispatchers.IO.limitedParallelism(1)` so writes land in order.
     - Its state: `visible` (Boolean), `bookId` (String?), `lastProgress` (Double?), `jumpPending` (Boolean), `playing` (Boolean).
     - Methods:
       - `shown(bookId, progress)`: sets `visible` and the book; when not playing, saves `reading.start`.
       - `hidden()`: clears `visible` and saves `reading.stop`.
       - `page(progress)`: stores `lastProgress`; when not playing, saves `reading.mark(now, progress, jumpPending)`, then clears `jumpPending`.
       - `jumped()`: sets `jumpPending`.
       - `playing(bookId, progress)`: sets `playing`, saves `reading.stop`, saves `listening.start`.
       - `utterance(progress)`: saves `listening.mark`.
       - `stopped()`: clears `playing`, saves `listening.stop`, and when `visible`, saves `reading.start(book, now, lastProgress)`.
  2. `ReaderViewModel`: `internal val sessions = SessionRecorder(app.database.stats(), app.background, DeviceKeys.phone(app.link.settings.phoneDeviceId))`. Hand it to `ReadAloud`.
  3. `ReaderActivity`:
     - Override `onResume` (or extend it, if present) to call `model.sessions.shown(bookId, progress)` once the book is loaded, and also when it first loads. Progress is `nav.currentLocator.value.locations.totalProgression`.
     - `onPause` calls `model.sessions.hidden()` next to `model.speed.interrupt()`.
     - In the `currentLocator.collectLatest` block, after `delay(SAVE_DEBOUNCE_MS)`, call `model.sessions.page(locator.locations.totalProgression)`.
     - Next to every other `model.speed.interrupt()` call (the jumps), call `model.sessions.jumped()`.
  4. `ReadAloud`:
     - Add `internal var sessions: SessionRecorder? = null`.
     - Route every assignment to `state` through a private setter. Going into `PLAYING` calls `sessions?.playing(reading.bookId, progress of utterance)`. Leaving `PLAYING` (any other state) calls `sessions?.stopped()`.
     - In `created.location.collect`, call `sessions?.utterance(it.utteranceLocator.locations.totalProgression)`.
- **Tests (`SessionRecorderTest`, Robolectric with in-memory Room and a fake clock, `runTest`):**
  - shown, then 3 pages, then hidden gives one PHONE row with the expected seconds;
  - a jump doesn't add to `progressRead`;
  - playing stops reading and starts a LISTEN row;
  - stopped while visible starts a new PHONE session;
  - nothing under a second is written.
- **Done:** the standard check passes. The reader hooks are compiled only; say so. A device check is the owner's: read 10 pages, then the Stats tab shows the time (after U-tasks).

### D6. Word counts and KOReader digests
- **Needs:** D3.
- **Files:**
  - new `app/src/main/java/dev/sadking/folio/data/StatsIndexer.kt`
  - `FolioApplication.kt` (one line in `onCreate`)
  - new `app/src/test/java/dev/sadking/folio/data/StatsIndexerTest.kt`
- **Build:** `class StatsIndexer(private val library: LibraryDao, private val stats: StatsDao, private val now: () -> Long = System::currentTimeMillis)` with `suspend fun run(): Int`. For every book in `library.allBooks()`:
  - **Words:** when `format == "epub"`, `currentPath` is a file that exists, and `book_words` has no row with `revisionHash == currentHash`: `EpubWords.count(file)`, then upsert `BookWordsRow(id, currentHash, words, now())`. A null count is stored too, so it isn't retried until the file changes.
  - **Digests:** for each of (`originalPath`, `originalHash`) and (`currentPath`, `currentHash`) whose file exists and has no digest row for this book and hash: `koreaderDigest(file)`, then upsert `KoreaderDigestRow(digest, id, hash, now())`. All formats.
  - Catch per book. One bad file never stops the run.
  - Return how many books changed.
  - In `FolioApplication.onCreate`: `background.launch { database.library().books().debounce(2_000).collect { StatsIndexer(database.library(), database.stats()).run() } }`.
- **Tests:**
  - an EPUB (build one as `EpubWordsTest` does) gets words and 1 digest (original equals current);
  - a second run does nothing;
  - a new current file recounts;
  - a PDF gets a digest but no words;
  - a missing file is skipped.
- **Done:** the standard check passes.

### D7. Hardcover read dates
- **Needs:** D3.
- **Files:**
  - `app/src/main/java/dev/sadking/folio/data/HardcoverClient.kt`
  - `app/src/main/java/dev/sadking/folio/sync/HardcoverContract.kt`
  - `app/src/main/java/dev/sadking/folio/sync/HardcoverSyncWorker.kt`
  - `app/src/test/java/dev/sadking/folio/data/HardcoverClientTest.kt`
  - `app/src/test/java/dev/sadking/folio/sync/HardcoverRunTest.kt`
  - `docs/HARDCOVER_SYNC.md` (A4 costs, unchanged; add one line under the pull)
- **Build:**
  1. In `libraryPage`'s query, add inside `user_books { … }`:
     ```
     finished: user_book_reads(where:{finished_at:{_is_null:false}},order_by:{id:asc},limit:50) { id started_at finished_at }
     ```
     No extra request. The operation count in `scripts/check-hardcover-schema.py` stays 14.
  2. Add `data class HardcoverRead(val id: Int, val startedOn: LocalDate?, val finishedOn: LocalDate)` and `val reads: List<HardcoverRead> = emptyList()` on `HardcoverLibraryBook` and on `RemoteUserBook`.
     - Parse with `.take(10)` and `LocalDate.parse`.
     - Drop a read whose date doesn't parse.
     - `require` ids > 0.
  3. In `HardcoverRunSteps`, add `suspend fun saveReads(library: List<RemoteUserBook>)`, called in `round()` right after `steps.fromLibrary(library)`. `AppRunSteps` upserts a `HardcoverReadRow(accountId, read.id, hardcoverId, startedOn?.toString(), finishedOn.toString(), now)` per read. Never delete.
  4. In `ClientHardcoverApi.libraryPage`, map `reads`.
- **Tests:**
  - client: a page with two finished reads parses both; a bad date is dropped;
  - run: after a pull, `hardcover_reads` holds the rows; a second pull with one read missing keeps it.
- **Done:**
  - the standard check passes;
  - if network allows, `python3 scripts/check-hardcover-schema.py` passes; else report it as not run.

### D8. Backups carry sessions and word counts
- **Needs:** D3.
- **Files:**
  - `app/src/main/java/dev/sadking/folio/data/LibraryBackup.kt`
  - the backup test in `app/src/test/java/dev/sadking/folio/data/` that covers export and restore (`DeviceBackupTest.kt`; add a new `StatsBackupTest.kt` beside it if cleaner)
- **Build:**
  - **Export:** `readingSessions` (every row: all fields) and `bookWords`.
  - **Restore:**
    - optional keys;
    - `runCatching` per row, skipping unreadable ones;
    - session `bookId` through `mappings`;
    - write with `keepSessions`;
    - words only when the restored book's `currentHash` equals `revisionHash` and no row exists.
  - Version stays 4.
- **Tests:**
  - export then restore into an empty database gives the same sessions;
  - restore over a longer existing session keeps the longer one;
  - a backup without the keys (an older one) restores;
  - a session of a book whose id was remapped points at the new id.
- **Done:** the standard check passes.

### D9. StatsRepository
- **Needs:** D3. Best after D6 and D7, but it works without them.
- **Files:**
  - new `app/src/main/java/dev/sadking/folio/data/StatsRepository.kt`
  - new `app/src/main/java/dev/sadking/folio/data/StatsGoals.kt`
  - new `app/src/test/java/dev/sadking/folio/data/StatsRepositoryTest.kt`
- **Build:**
  1. `StatsGoals(prefs: SharedPreferences)`:
     - `val goals: StateFlow<GoalSettings>`;
     - `fun save(settings: GoalSettings)`, where a null or ≤ 0 value removes the key.
     - Keys as in a8; prefs file `stats`.
  2. `StatsRepository`, taking:
     - `stats: StatsDao`, `library: LibraryDao`;
     - `roster: Flow<DeviceRoster>`: built the way `DeviceViewModel` builds it. Move that construction into a shared function if needed, and report it;
     - `ereaders: Flow<List<EreaderSync>>`;
     - `goals: StatsGoals`;
     - `zone: () -> ZoneId = ZoneId::systemDefault`, `clock: () -> Long`.
  3. `fun snapshot(): Flow<StatsSnapshot>`: `combine` the flows of sessions, digests, words, reads, `everyBook()`, `wishes()`, `phoneReadingFlow()`, `kosyncFlow()`, roster, ereaders and goals, plus a day ticker (emit `LocalDate.now(zone())` every 60 s, `distinctUntilChanged`). Then `mapLatest` on `Dispatchers.Default`:
     - **Sessions:** `toSession()`. A KINDLE session with a `document` in the digest map gets `bookId = digests[document].bookId`.
     - **Books:** one `StatsBook` per book:
       - `words` from `book_words` when `revisionHash == currentHash`;
       - `progress`: the phone's `PhoneReading.percent` for `currentHash` when present (a 0..1 fraction; check how `ReaderViewModel.save` writes it), else the newest `KosyncReport.percentage` for any of the book's digests, else null.
     - **Finishes** (a7):
       - books with status `Finished` and `statusAt` > 0 (LIBRARY, `bookId`);
       - wishes with status `Finished`, `statusAt` > 0 and no existing `linkedBookId` book (LIBRARY, `wish.id`);
       - each `hardcover_reads` row with `finishedOn` (HARDCOVER), keyed as in a2 through `WishBook.hardcoverId` and `linkedBookId`.
       - Dates: `Instant.ofEpochMilli(statusAt).atZone(zone()).toLocalDate()`.
     - **Labels:** books (title, `authorLabel`, the `Book`, `coverPath`) and wishes (title, `authorLabel`, null, `coverPath`).
     - `deviceNames`: `roster.name(key)` for each `ereader:` key that sessions use, when not null.
     - `waitingForEreader`: `ereaders` not empty and no KINDLE session.
     - `ReadingStats(…, zone = zone(), today, firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek)`.
- **Tests (Robolectric, in-memory Room):**
  - a KINDLE session with a known digest is grouped under the book;
  - one with an unknown digest stays `doc:`;
  - a Finished book gives a finish on its local date;
  - a Hardcover read of a linked entry is keyed by the book;
  - goals round-trip;
  - `waitingForEreader` true, then false after a KINDLE session.
- **Done:** the standard check passes.

### U-tasks (finalised after `docs/STATS_UI.md`)

Placeholders. Each will name its files, components and strings from `STATS_UI.md`. Every one uses only the `StatsSnapshot` API in (c), never Room. Every one adds previews with fake `ReadingStats` data (c) and renders them with the screenshot check (`./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest' -Pscreenshots`) in light, dark and 200% text.

| Task | Builds | Needs |
|---|---|---|
| U0 | The Stats tab in the bar and the rail; a `StatsViewModel` exposing `StatsSnapshot?` (null while loading); loading, "no data yet" and "first sync pending" states; the preview fake-data builder. **Keep saved tab indices:** add `FolioNav.STATS = 4` and `TABS = 5`, and place it visually where `STATS_UI.md` says. Existing indices never change, because the saved state stores the index. | D3 (types). D9 to show real data. |
| U1 | Overview at the top. | U0 |
| U2 | Calendar month grid and the day sheet (`FolioSheet`). | U0 |
| U3 | Habits and trends (charts per `STATS_UI.md`). | U0 |
| U4 | Per-book list, and the card on the book page (`book(key)`). | U0 |
| U5 | Milestones, records, year in review, and the shareable image. | U0 |
| U6 | Goals sign-up and settings (`StatsGoals`). | U0, D9 |
| U7 | Tablet two-pane layout of the tab (`Panes`). | U1–U5 |

---

## Owner's answers (2026-10-17)

1. **Kindle books never in Librillo: yes, send KOReader's title and author, but only for books the phone can't match.** This applies as amendment A1 below.
2. **Listening and goals: no.** Listening doesn't count toward the daily minutes goal or keep a streak going, as planned.
3. **Forgotten devices: keep counting them**, as planned.
4. **Bottom bar slots:** left to `STATS_UI.md`.
5. **Thresholds: keep 1 minute**, as planned.

## Owner's answers on the design (2026-10-18)

1. **Bar:** Lists moves into Library as a third top tab (Library · Want to Read · Lists). The bar is Library, Stats · Now Reading tile · Device, Settings.
2. **Calendar:** a book's consecutive days join into one bar, KOReader style, with the title inside (`docs/STATS_UI.md` § 6.2).
3. **Share card:** follows the phone's theme.
4. **Year goal pace:** target × the fraction of the year gone.
5. **Milestones:** books 10/25/50/100; hours 100/500/1000.
6. **Dates** read DD/MM/YYYY everywhere in the app.

## Amendment A1: names for unmatched Kindle books
This amends D1, D2, D3, D4, D8 and D9. Workers on those tasks must apply it.

- **Wire (D1):**
  - **GET `/folio/v2/statistics`** also answers `"known": [<lowercase 32-hex digest>, …]`: every KOReader digest the phone can match to a library book (`SyncLibrary.knownDigests(): List<String> = emptyList()`).
  - **POST version 2** may carry an optional `"books"` object: `{ "<digest>": {"title": "<text>", "authors": "<text>"} }`.
    - At most `KoreaderSessions.MAX_PER_REQUEST` entries.
    - Each digest is 32 lowercase hex characters.
    - Title and authors are strings of at most 512 characters each, with no control characters; authors may be empty. An entry with a blank title is skipped, not refused, and the plugin never sends one.
    - Anything else gives `invalid()`.
    - Valid names go to `SyncLibrary.saveBookNames(ereaderId, names: Map<String, Pair<String, String>>)`, which defaults to a no-op.
  - **Tests:** GET answers `known`; names are saved; a bad digest, an over-long title and a control character each give 403; a body without `books` still works.
- **Plugin (D2):**
  - After the GET, read `known` into a set; a missing `known` means an empty set.
  - For each chunk, the `books` object holds an entry for every distinct session book not in `known`, from `SELECT md5, title, authors FROM book` (read once in the same read-only transaction; md5 lowercased). Titles and authors are cut to 512 characters, with control characters removed.
  - Never send names for known digests.
  - **Tests:** names go only for unknown digests; a phone answering without `known` gets names for every book; nothing other than title and authors is ever sent.
- **Room (D3):** schema 14 also gets the table `stats_book_names(digest TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, authors TEXT NOT NULL, updatedAt INTEGER NOT NULL)`, in the same additive migration and the same migration test.
- **Storage (D4):** `saveBookNames` upserts into `stats_book_names`, newest wins. `knownDigests` returns the digests of library books that have one (from D6's digest store; until D6 lands, those already known).
- **Backups (D8):** `stats_book_names` is carried like the sessions. Optional, so older backups restore.
- **Repository (D9):** a session whose book isn't in the library takes its name from `stats_book_names`, else "A book on your e-reader".
