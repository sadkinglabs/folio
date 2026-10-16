# Sync protocol v2

How Librillo on the phone and the Librillo plugin in KOReader keep one library on both sides (M9 step 3). The e-reader is called "the Kindle" here, but nothing is Kindle-specific.

Three parts:
- **The planner** (`core`, package `dev.sadking.folio.core.sync`): pure Kotlin. It compares the Kindle's files, the phone's books and what both agreed at the last sync, and returns what each side must do.
- **The server** (`transport`, `LinkRoutes`): the `/folio/v2` routes on the Link server. It runs the planner, hands out files, takes uploads, and records the result.
- **The plugin** (`koreader/folio.koplugin`): sends the Kindle's manifest, runs the Kindle's actions in order, and reports each result.

Reading positions are not part of this protocol. KOReader's Progress sync (kosync) already handles them.

## What both sides keep in step

- **The files.** The whole library is on both sides. On the Kindle it lives in `<KOReader home>/Librillo/Author/Title.ext` (the folder name is a setting). Books found anywhere else under KOReader's home folder are brought into Librillo, then moved into the library folder.
- **Deletions, both ways.** Deleting on the Kindle sends the phone's book to Recently removed, where it can be restored. Removing on the phone deletes it from the Kindle. Restoring on the phone sends it back.
- **Formats:** EPUB, PDF, CBZ and CBR. Other files are ignored and never touched.
- **Status:** unread, reading, finished, on hold.
- **Highlights, notes and bookmarks:** merged. Nothing is ever deleted.
- **Lists ⇄ KOReader collections**, matched by name.
- **Edits:** a new title or author renames the file on the Kindle. A new version of the file on one side replaces it on the other.

When both sides changed the same thing since the last sync, nothing changes for it. A **question** is asked on whichever device is in use, and the answer settles it at the next sync.

## The data

All paths are relative to KOReader's home folder and use `/`. Hashes are lowercase SHA-256 in hex. Times are epoch milliseconds.

### KindleFile: one book file on the Kindle

| Field | Meaning |
|---|---|
| `path` | Where it is, for example `Librillo/Ann Lee/Home.epub` or `Downloads/home.epub`. |
| `size` | Bytes. |
| `sha256` | Hash of the file. |
| `format` | `epub`, `pdf`, `cbz` or `cbr`. Anything else is ignored. |
| `status` | `unread`, `reading`, `finished`, `abandoned`, or `null` when KOReader has no record of the book. |
| `statusAt` | When the status last changed, or `null`. |
| `annotations` | List of annotations (below). |
| `collections` | Names of the KOReader collections the book is in. |
| `title`, `authors` | KOReader's metadata for the file. Used to name a book the phone doesn't have yet. When the title is missing, the file name is used. |

### SyncAnnotation

| Field | Meaning |
|---|---|
| `id` | Its identity on every device. Two annotations with the same id are the same one. |
| `text`, `note`, `chapter` | The highlighted text, the owner's note, the chapter title. |
| `datetime` | KOReader's creation time, `YYYY-MM-DD HH:MM:SS`. |
| `pos0`, `pos1` | Start and end: an xpointer for EPUB, a page for PDF and comics. |
| `kind` | `highlight`, `note` or `bookmark`. |

The side that creates an annotation gives it its id, and every side keeps that id from then on. The recipe: the first 32 hex characters of SHA-256 of `device id + "\n" + datetime + "\n" + pos0`. The planner treats ids as opaque text. The plugin stores a received id with the annotation (field `librillo_id`) so it can send the same id back.

### PhoneBook: one book in Librillo

| Field | Meaning |
|---|---|
| `id` | The book's id. |
| `sha256`, `format`, `size` | Its current file. |
| `title`, `authors` | Librillo's metadata. These decide the file's name on the Kindle. |
| `status`, `statusAt` | In the phone's words: Unread, Want to Read, Reading, Finished, Paused, Abandoned. |
| `removed` | In Recently removed. |
| `hasFile` | False for a record without a file. |
| `annotations`, `lists` | As above; `lists` are list names. |

A book purged from Recently removed is simply absent.

### Baseline: what both sides agreed at the last sync

One row per book per e-reader: `bookId`, `path` on the Kindle, `sha256`, `status` (in the Kindle's words, or empty when the sides did not agree), `statusAt`, `annotationIds`, `collections`. The app stores it in the `sync_baseline` table.

Without a baseline, "deleted on one side" can't be told apart from "never there". With one, a book missing on one side since the last sync was deleted there.

### Status words

| Phone | Kindle | Kindle shows |
|---|---|---|
| Unread, Want to Read, blank | `unread` | (no status) |
| Reading | `reading` | Reading |
| Finished | `finished` | Finished |
| Paused, Abandoned | `abandoned` | On hold |

Going the other way: `unread` → Unread, `reading` → Reading, `finished` → Finished, `abandoned` → Paused. KOReader's own words `complete` and `on hold` are also understood. A phone status outside this table is left alone.

## The rules

The planner is `SyncPlanner.plan(home, root, kindle, phone, baselines, answers, ignored)`. `ignored` (default empty) holds the hashes of Kindle files the phone refused before (rule 13).

### 1. Which file is which book
1. Same hash: the file is that book, wherever it is.
2. Otherwise, a file at the path in the book's baseline: the book, edited on one side.
3. Otherwise, a file with the baseline's hash: the book, moved on the Kindle and edited on the phone.

Each file and each book pairs once. Books on the phone pair before books in Recently removed, so a removed copy never takes an active book's file. When several files hold the same content, the one at the book's baseline path wins, then one inside the library folder, then the first by path. Files left over that hold the content of an active book are extra copies: they are left where they are and not imported again.

Then, a file at the path or with the hash of a baseline whose book is no longer on the phone at all belongs to that purged book.

### 2. A book only on the phone
(Not removed, and with a file.)
- **No baseline:** it is sent. Download to `Librillo/<BookPaths.path(authors, title, format)>`, then its status (when not unread), its annotations and its lists.
- **Baseline, file unchanged since:** it was deleted on the Kindle. It goes to Recently removed.
- **Baseline, file changed on the phone since:** question `delete-vs-edit`, "Home was deleted on the Kindle but changed on the phone. Remove it from the phone, or send it back?" Options: **Remove it** (Kindle's side), **Send it back** (phone's side).

### 3. A file only on the Kindle
- **It belongs to a purged book** and is unchanged since the baseline (same hash, no new annotations): deleted from the Kindle.
- **It belongs to a purged book but changed on the Kindle:** question `delete-vs-edit`, "Home was deleted from Librillo but changed on the Kindle. Delete it from the Kindle, or keep it?" **Keep it** imports it as a new book. **Delete it** deletes it.
- **Same content as a book in Recently removed:** its annotations are added to that book, then it is deleted from the Kindle.
- **Same content as an active book** (an extra copy): left alone.
- **Anything else is new:** the Kindle uploads it, the phone imports it with the Kindle's status, annotations and collections (as lists), then the Kindle moves it to its tidy path, named from its own title and authors.

### 4. A book in Recently removed that is still on the Kindle
- **No baseline, or the file is unchanged since the baseline:** the Kindle's annotations are added to the phone's book, then the file is deleted from the Kindle.
- **The file changed on the Kindle since the baseline:** question `delete-vs-edit`, "Home was removed on the phone but changed on the Kindle. Delete it from the Kindle, or keep it?" **Keep it** restores the book on the phone and takes the Kindle's file as a new revision. **Delete it** deletes it.

### 5. A book on both sides
**Where it lives.** The tidy path is `Librillo/<BookPaths.path(phone authors, phone title, format)>`. A file is in place when its path matches the tidy path ignoring case, or a numbered version of it (`Home (2).epub`), which a clash gave it once and it keeps. Otherwise it is moved, with its `.sdr`. So an edited title or author renames the file on the Kindle.

**Its content**, when the hashes differ:
- The Kindle still has the baseline's file, so the phone changed it. The Kindle replaces its file in place with the phone's (`download` with `replaces`), after any move, so the `.sdr` and its notes stay.
- The phone still has the baseline's file, so the Kindle changed it. The Kindle uploads it and the phone takes it as a new revision.
- Both changed: question `file`, "Home was changed on both the phone and the Kindle. Which copy should both keep?" Options: **Kindle copy**, **Phone copy**. Until it is answered, nothing at all happens to this book.
- The phone has a record but no file: the phone takes the Kindle's file.

Then status, annotations and lists, below.

### 6. Status
Both sides are put in the Kindle's words first.
- The Kindle has no status (`null`): the Kindle never changes the phone. If the phone's status isn't unread, it is written to the Kindle.
- The same on both sides: nothing to do.
- One side differs from the baseline: that side's status is copied to the other.
- Both differ from the baseline and from each other: question `status`, "Home: Kindle says Finished, phone says Reading. Which is right?" Options are the two statuses, the Kindle's first. Only the status waits; annotations and lists still sync.
- No baseline: a status beats unread; otherwise the newer `statusAt` wins, and a tie goes to the phone.

A status copied to the phone carries the Kindle's `statusAt` (or the phone's own when the Kindle has none).

### 7. Annotations
Union by id. Each side gets the ones it lacks. Nothing is ever deleted, so an annotation deleted on one side comes back from the other.

### 8. Lists and collections
Per list name, against the baseline's collections:
- both sides agree: kept as is;
- one side added it: added on the other;
- one side removed it (it was in the baseline): removed on the other;
- no baseline: the union.

The result is sent as the book's full set (`SetLists`, `collections`), only to a side whose set changes.

### 9. Questions and answers
A question's id is `q-` plus 16 hex characters of SHA-256 of `kind + "\n" + bookId`. It stays the same from one sync to the next while the conflict lasts. An answer is the text of one option. When `answers` holds an id with one of the question's current options, that side wins and nothing is asked. An answer that is no longer an option (the conflict changed) is ignored and the question is asked again.

### 10. Names never clash
Every path the Kindle uses counts as taken, compared ignoring case and accents' encoding (`BookPaths.key`), including files about to move away. Each new download or move target is taken as it is chosen. A clash gets `BookPaths`' " (2)", " (3)" rule. Two books never get the same target.

### 11. Everything ends up in the library folder
Files outside the library folder are moved in after they are imported. Files inside it at the wrong path are moved to the right one.

### 12. Order
The plan doesn't depend on the order of its input. Kindle actions run in phases: uploads, moves, downloads, deletes, statuses, annotations, collections. Within a phase they are sorted by path, ignoring case. Phone actions: imports, restores, revisions, removals, statuses, annotations, lists, each sorted by book id (imports by path). Questions are sorted by path.

### 13. Files the phone refused
A Kindle file the phone would not take (not a book Librillo can read, larger than it takes, or a new version in another format than the book) is remembered by its hash, per e-reader (`SyncLibrary.refused`). The planner leaves a file with that content alone:
- **New on the Kindle:** not uploaded, not imported, not moved into the library folder.
- **A new version of a book on both sides:** not uploaded; the phone keeps its file and the Kindle keeps its own. Moves, status, annotations and lists still sync. The phone's file is never sent over the Kindle's.
- **A book with no file on the phone:** the Kindle's file is not taken back.
- **A purged book changed on the Kindle:** left alone, without a question ("Keep it" could not import it). An unchanged one is still deleted.
- **"Keep it" for a book removed on the phone:** the book is restored; the refused file is not sent.

A file whose content changes is new content and is offered again. A refusal is forgotten once no Kindle file has that content (see `/finish`). A file can be refused only after one upload, so it may have been moved into the library folder by that first sync.

### Input that is refused
A path that is empty, starts with `/`, contains `\`, an empty part, `.` or `..`; a hash that is not 64 lowercase hex characters; the same path twice; the same book id twice; a library folder that is not a plain relative path. The server answers 403 (`{"code":2003}`) and plans nothing. It refuses the whole manifest rather than skip one file, because a skipped file would look like a Kindle deletion.

## After the Kindle reports: baselines

`SyncPlanner` has three helpers for the end of a sync:
- `kindleAfter(kindle, actions, phone, results)`: the Kindle's files after the actions that succeeded, at the paths the Kindle reported.
- `phoneActionsAfter(plan, results)`: the phone actions to apply. An import or revision is dropped when its upload failed.
- `baselines(kindleAfter, phoneAfter, previous, root)`: the complete new baseline set for this e-reader.

`baselines` gives a fresh row to each book whose file both sides now hold. A field the two sides still disagree on (a failed action, an open question) keeps its old value, so the difference still counts as a change next time. A row for a book no longer held by both stays while either side still has the book, and is dropped once both have deleted it. Dropping it matters: it lets a book restored from Recently removed be sent again instead of being taken for a deletion.

## Wire format

JSON over the Link server, with the same login and the same throttle as `/folio/v1`: the `x-auth-user` and `x-auth-key` headers, or HTTP Basic. Every `/folio/v2` route is answered 404 when the app has no sync library; `/folio/v1`, OPDS and kosync are unchanged. Request bodies may be up to 16 MiB (a whole manifest with its notes); uploads up to 512 MiB. Refusals are JSON `{"code", "message"}`: 403 code 2003 for a malformed or unlisted request, 410 code 2005 for a session that has ended, 413 for a body too large, 500 code 2006 when the phone could not store an upload.

### GET /folio/v2/hello
Header `x-folio-device: <device id>`.
```json
{"api": 2, "name": "Librillo", "pending": true}
```
`pending` is true when the phone has changes this device hasn't received (`SyncLibrary.pending`), or when the owner tapped Sync on the phone since this device's last finished sync; it is false without the header. The plugin polls this every few minutes and syncs when it is true.

### POST /folio/v2/sync
```json
{
  "device": {"id": "kindle-7f3a", "name": "Kindle Paperwhite"},
  "home": "/mnt/us/koreader/files",
  "root": "Librillo",
  "files": [
    {"path": "Librillo/Ann Lee/Home.epub", "size": 412345, "sha256": "…", "format": "epub",
     "status": "reading", "statusAt": 1760000000000,
     "annotations": [{"id": "…", "text": "…", "note": "", "chapter": "One",
                      "datetime": "2026-10-01 21:14:03", "pos0": "/body/DocFragment[3]/…",
                      "pos1": "/body/DocFragment[3]/…", "kind": "highlight"}],
     "collections": ["Sundays"], "title": "Home", "authors": ["Ann Lee"]}
  ],
  "answers": {"q-1a2b3c4d5e6f7a8b": "Finished"}
}
```
Answer:
```json
{
  "session": "2f6c…",
  "kindle": [
    {"index": 0, "op": "upload", "path": "Downloads/dune.epub",
     "url": "/folio/v2/upload?session=2f6c…&path=Downloads%2Fdune.epub"},
    {"index": 1, "op": "move", "from": "Downloads/dune.epub", "to": "Librillo/Frank Herbert/Dune.epub"},
    {"index": 2, "op": "download", "bookId": "b1", "sha256": "…", "size": 412345,
     "to": "Librillo/Ann Lee/Home.epub", "replaces": null,
     "url": "/folio/v2/books/b1?session=2f6c…"},
    {"index": 3, "op": "delete", "path": "Librillo/Old/Gone.pdf"},
    {"index": 4, "op": "status", "path": "Librillo/Ann Lee/Home.epub", "status": "finished"},
    {"index": 5, "op": "annotations", "path": "Librillo/Ann Lee/Home.epub", "annotations": [ … ]},
    {"index": 6, "op": "collections", "path": "Librillo/Ann Lee/Home.epub", "collections": ["Sundays"]}
  ],
  "questions": [
    {"id": "q-1a2b3c4d5e6f7a8b", "bookId": "b1", "path": "Librillo/Ann Lee/Home.epub",
     "kind": "status", "phoneSide": "Reading", "kindleSide": "Finished",
     "text": "Home: Kindle says Finished, phone says Reading. Which is right?",
     "options": ["Finished", "Reading"]}
  ],
  "phoneQuestions": 0
}
```
Actions, one object each, by `op`:

| `op` | Fields | The plugin |
|---|---|---|
| `upload` | `path`, `url` | PUTs the file to `url`, with `Content-Length`. |
| `move` | `from`, `to` | Renames the file with its `.sdr`, history and collections. `to` is free; if it isn't, it picks the next " (n)" name and reports it. |
| `download` | `bookId`, `sha256`, `size`, `to`, `replaces`, `url` | GETs `url` to a temporary file and checks its hash. With `replaces` null, saves it at `to`, which must be free. With `replaces` set, `to` must hold a file with that hash, which is replaced in place, keeping its `.sdr`; anything else at `to` is a failure, never an overwrite. |
| `delete` | `path` | Deletes the file and its `.sdr`. |
| `status` | `path`, `status` | Sets KOReader's status (`finished` is KOReader's `complete`). |
| `annotations` | `path`, `annotations` | Adds the ones whose id isn't there yet. |
| `collections` | `path`, `collections` | Makes the book's collections exactly these. |

The plugin runs them in the order given. Every action has `index`, its position in `kindle` from 0. Within one plan no action depends on another's new name except `download` with `replaces`, which names the `to` of an earlier `move`; actions that name a path a `move` has just renamed (status, annotations, collections) already use the new path. When the plugin had to pick another name than `to`, it reports it in `/finish` and uses it for the rest of the plan.

`phoneQuestions` counts questions waiting on the phone (read before this sync), so the Kindle can say "1 question on your phone".

**Manifest checks.** `sha256` is lowercased, then must be 64 hex characters. `format` must be `epub`, `pdf`, `cbz` or `cbr` (the plugin leaves other files out). `status` must be one of the four Kindle words or `null`; KOReader's `complete` and `on hold` are converted by the plugin. `statusAt` is epoch milliseconds or `null`. An annotation `kind` defaults to `highlight`; `pos0` and `pos1` that are numbers or tables arrive as their text. A path may be at most 1024 characters, with no control characters. `root` defaults to `Librillo`. Any malformed file, or the same path twice, refuses the whole request (403).

**Answers.** The answers used for planning are the ones given on the phone (`SyncLibrary.phoneAnswers`) plus the ones in this request; the Kindle's win on the same id. The plugin keeps its answers until a `/finish` succeeds.

### GET /folio/v2/books/&lt;bookId&gt;?session=&lt;id&gt;
The file's bytes, typed by format and named after the last part of `to`. Only for books in that session's downloads (403 otherwise). 404 when the phone has no file; 410 when the session has ended. The phone does not hash the file on the way out: the plugin checks `sha256` before it keeps a download.

### PUT /folio/v2/upload?session=&lt;id&gt;&path=&lt;URL-encoded Kindle path&gt;
The body is the file. `path` is the path as the manifest listed it, even when a `move` has run since. Only paths in that session's uploads (403 otherwise). A `Content-Length` over 512 MiB is refused before reading (413); a body without one is cut off at 512 MiB (413). The server streams it to a temporary file in `SyncLibrary.uploadDir()` while hashing it; a hash other than the manifest's is 409 `{"ok":false}`. Success:
```json
{"ok": true, "path": "Downloads/dune.epub", "size": 412345, "sha256": "…"}
```
Uploading the same path again replaces the earlier upload. Nothing is handed to the library until `/finish`. A 413 is remembered: at `/finish` that file is recorded as refused, too large (rule 13).

### POST /folio/v2/finish
```json
{"session": "2f6c…", "results": [
  {"index": 0, "ok": true},
  {"index": 1, "ok": true, "path": "Librillo/Frank Herbert/Dune.epub"},
  {"index": 2, "ok": false, "error": "No space left"}
]}
```
Answer `{"ok": true}`. `path` is where the file ended up, for moves and downloads; an invalid one is ignored and the planned path used. A missing result counts as a failure. The session ends here, so a second finish is 410.

Only now does the phone change anything, in this order:
1. **Uploads.** For each `upload` reported ok whose file arrived: `SyncLibrary.revise(device, bookId, path, file)` when the plan has a `Revise` for that path, otherwise `SyncLibrary.receive(device, path, file)` for its `Import`. An upload counts as done only when the library took the file. When either throws `SyncRefusal`, the file is refused for good (with its reason); returning null means "not this time".
2. **Phone actions.** `SyncPlanner.phoneActionsAfter(plan, results)` (an import or revision only when its upload was taken), applied through `SyncLibrary.apply` with the uploaded paths mapped to their book ids.
3. **The Kindle's new state.** `SyncPlanner.kindleAfter(manifest, plan.kindle, phone books at plan time, results)`.
4. **Baselines.** The phone's books are read again, and `SyncPlanner.baselines(kindleAfter, phoneAfter, previous, root)` is passed to `SyncLibrary.commit`, which **replaces** this e-reader's whole baseline set.
5. **Questions.** `SyncLibrary.saveQuestions(device, plan.questions)`, which replaces the device's questions and drops stored answers to other ids.
6. **Refused files.** `SyncLibrary.saveRefused(device, list)`: the files refused in this sync (`RefusedFile`: hash, Kindle path, reason, time), plus the earlier ones whose hash is still in this manifest. A refusal for content the Kindle no longer has is dropped.
7. `SyncLibrary.synced(device, name, now)`, and the temporary uploads are deleted.

### Sessions
Once `/sync` has planned, the server calls `SyncLibrary.started(device, name, now)`, so the phone can say "Syncing…". A session lasts 30 minutes after its last request, so a long transfer can't expire half way. Each device has one at a time: a new `/sync` replaces the old one and deletes its uploads. At most 16 sessions exist; the oldest is dropped. An unknown or ended session is 410 (`{"code":2005}`) on every route that takes one. Planning and finishing run one at a time; uploads stream in parallel.

## SyncLibrary: what the app provides

The server talks to the library through `SyncLibrary` (in `transport`), which the app implements. `PhoneBook.status` and the status in `Import` and phone `SetStatus` are in the phone's words; `Baseline.status` is in the Kindle's words and is stored as is.
- `phoneBooks()`: every book, Recently removed and records without a file included, as `PhoneBook`.
- `baselines(ereaderId)`, `commit(ereaderId, baselines)`: read the baseline set; replace it whole (never an upsert).
- `bookFile(bookId)`: the file to serve.
- `receive(ereaderId, kindlePath, file)`: takes an upload as a new book; returns its id, or null for "not this time". Throws `SyncRefusal(reason)` when the file can never be taken: `NOT_A_BOOK` or `TOO_LARGE`.
- `revise(ereaderId, bookId, kindlePath, file)`: takes an upload as a new revision of `bookId`, beside the old file; returns the id, or null. The book may be in Recently removed when a `Restore` follows. Throws `SyncRefusal` with `NOT_A_BOOK`, `TOO_LARGE` or `OTHER_FORMAT` (another kind of file than the book).
- `apply(ereaderId, actions, received)`: runs the phone actions in order. `received` maps Kindle paths to the book ids from `receive` or `revise`, so an `Import` finds its book and applies the status, notes and lists it carries.
- `pending(ereaderId)`, `phoneQuestions(ereaderId)`, `saveQuestions(ereaderId, questions)`, `phoneAnswers(ereaderId)`.
- `uploadDir()`: where uploads land while they arrive (default: the system temp folder).
- `refused(ereaderId)`, `saveRefused(ereaderId, refused)`: the e-reader's refused files (rule 13); the planner gets their hashes as `ignored`.
- `started(ereaderId, deviceName, at)`: a sync was planned.
- `synced(ereaderId, deviceName, at)`: a sync finished.

`receive` and `revise` get a temporary file the server deletes afterwards, so they copy or move it.

### The app's SyncLibrary (`RoomSyncLibrary`, step 3b)
- `phoneBooks()` lists every row of the `books` table, Recently removed included. `hasFile` is false when the current file is gone, except for a Books folder path while All files access is off (the folder can't be seen, so it is not taken as missing). `statusAt` is the `books.statusAt` column (schema 10), set whenever the status changes; 0 means unknown. Lists are the manual lists the book is in; smart lists are rules, not memberships.
- Annotations map `BookAnnotation.id` → `id`, `position` → `pos0`, `endPosition` → `pos1`, `date` → `datetime`. Rows added by sync get the device id `link:<e-reader id>`. Rows imported over SSH before sync have other ids; once a synced row with the same `datetime` and `pos0` exists, the older row is hidden from sync (kept in Room, never deleted). The plugin skips an incoming annotation with the same `datetime` and start as one it already has, so such a note is never added twice on the e-reader.
- `receive` refuses a file over the import limit (256 MiB, `TOO_LARGE`) and one the import can't read (`NOT_A_BOOK`: not an EPUB, PDF, CBZ or CBR, or damaged). `revise` refuses the same, and another format than the book's (`OTHER_FORMAT`). A file that vanished or a storage error is not a refusal: the next sync tries again.
- `receive` uses the normal import path (format from the file's bytes, `Books/Author/Title.ext` when the Books folder is ready, else private storage, " (2)" on a clash). Content the library already has returns that book without restoring it. `revise` refuses another format; in the Books folder the old file moves to `.librillo/originals/<id>.<ext>` (the original) or `.librillo/previous/<id>-<ms>.<ext>`, and the new file takes its path.
- `apply` runs each action on its own; one that fails is skipped and the next sync tries again. `Remove` goes to Recently removed. `SetLists` creates missing manual lists by exact name and skips a name a smart list already uses.
- `pending(ereaderId)` is true when the owner tapped Sync since that e-reader's last finished sync (`SyncState.requestSync`), or when an answer given on the phone is waiting, else `SyncPending.pending(phoneBooks, baselines, root = null, waiting)` (core). `root = null` accepts any library folder, because hello doesn't say which one the e-reader uses. Books with an open question, a blank baseline status, and a missing phone file don't count, so the e-reader doesn't sync every 5 minutes for nothing.
- Questions, answers, refused files, the last sync time, the number of books both sides held after it and the device name are kept per e-reader in `SyncState` (SharedPreferences `librillo-sync`, JSON), with a StateFlow for the UI (`ereaders`). State saved before step 4 has no refused files and loads as such. "Syncing since" is kept in memory only, from `started` until `synced`, and stops counting after 30 minutes. The Sync button's time is `requestedAt` (`syncRequestedAt`).
- After `apply`, the books whose status sync set (a `SetStatus`, or an `Import` with a status) are passed to `HardcoverFinishPrompts.statusChanged`.
- `uploadDir()` is `Books/.librillo/uploads` when the Books folder is ready, else `cacheDir/sync-uploads`; uploads left over for more than a day are deleted.

## Hardcover finish prompt (step 4)

**Marked Finished on the phone (2026-10-16, owner's request):** that tap is the owner's go-ahead. `LibraryViewModel` queues the book in `HardcoverOutbox.queueFinish`, and the outbox worker calls `HardcoverClient.markRead`. A book that was Reading or Paused is finished that day; a book that goes straight to Finished (read long ago) is marked Read with no dates. With a date: a read in progress is finished at its last page; a book with no read in progress (Want to Read, or Read before) or not on Hardcover yet gets a new finished read; a book already Read with no read in progress is left alone. It follows "Update Hardcover automatically" and retries offline. No prompt is left for that finish. Books already Finished before this change are sent only on request: Settings → Hardcover → Send Finished books to Hardcover (matched books only, no dates, sent even with automatic updates off). The prompt below is for finishes that arrive from the e-reader.
Reading progress goes to Hardcover on its own (`HardcoverOutbox`), and only ever moves an open read forward. Finishing a read on Hardcover is never automatic.

When a book becomes Finished, by a sync (`SetStatus`, or an `Import` with that status) or on the phone (the book page or the Library's selection), `HardcoverFinishPrompts.statusChanged(ids)` checks it. A prompt is kept when:
- a Hardcover account is linked (`HardcoverSettings.connected` and its account id);
- the book is linked to a Hardcover book (a catalogue entry with `hardcoverId` whose `linkedBookId` is the book);
- the phone last saw that read as Currently Reading (status 2) for that account, in `hardcover_observations`. The reading sheet, the Hardcover import and now the outbox, after every progress update, keep that table current. Nothing goes online to decide.

There is one prompt per book, in SharedPreferences `hardcover-finish-prompts`, with the book's `statusAt` when it became Finished. `dismiss(bookId)` (Mark as read done, or Not now) records that finish as answered, so it isn't asked again until the book is finished again (a new `statusAt`). A prompt is hidden as soon as its book is no longer Finished or is in Recently removed, and dropped at the next `statusChanged`.

The phone shows prompts in the "Needs your decision" sheet: "You finished <title>. Mark it as read on Hardcover?" with Mark as read and Not now. Not now calls `dismiss`. Mark as read opens that book's Hardcover reading sheet with its Finish dialog already open and today's date filled in; Hardcover changes only when the owner taps Finish there. The prompt goes once the phone sees the read is no longer Currently Reading (`settledPrompts` in `DecisionsSheet.kt`), so a finish that failed keeps it waiting.

## Highlights made on the phone (step 4c)
EPUB only. The reader draws every annotation of the book whose `pos0` and `pos1` are xpointers in the same chapter, from the e-reader or the phone, as Readium decorations. The page script `assets/reader/highlights.js` turns the xpointers into a DOM range with the same resolver as reading positions (`positions.js`) and returns a quote with 32 characters of context on each side, which Readium anchors. A highlight that can't be placed is not drawn, but it stays in the database and on the book page.

Selecting text offers Highlight, Add note and Copy. The phone stores a `BookAnnotation` with device id `phone`, `pos0`/`pos1` as KOReader xpointers built from the selection, the selected text, the chapter title from the table of contents, `datetime` in local time (`YYYY-MM-DD HH:MM:SS`), kind `highlight` (or `note` with a note), and the id from the recipe above with device id `phone`. When another annotation already has that id (same second and start), the id is the first 32 hex of SHA-256 of `phone\n<datetime>\n<pos0>\n<pos1>\nhighlight`, as the plugin does. Phone rows have no `link:` prefix, so `SyncAnnotations.forSync` sends them to the e-reader at the next sync.

Tapping a highlight shows its passage, its note and where it was made, read-only, with Copy. There is no delete: sync never deletes annotations (rule 7), so a deleted one would come back.

The end of a KOReader range is taken as exclusive, like a DOM range end. That is not checked on a device yet.

## The plugin (step 3b)
- **Manifest:** every EPUB, PDF, CBZ and CBR under KOReader's home folder, except hidden folders, `.sdr` folders, symbolic links and KOReader's own data folder. Hashes are cached by path, size and modification time in `settings/folio-hashes.lua`. An unreadable folder or a failed hash stops the sync, so nothing looks deleted.
- **Status:** KOReader's `summary.status` (`reading`, `complete` → `finished`, `abandoned`), a sidecar without one → `unread`, no sidecar → `null`. `statusAt` is the plugin's own exact time when it matches `summary.modified`, else that date at noon, else the sidecar's time.
- **Annotation ids:** the stored `librillo_id`, else the recipe above (for a bookmark, `page` stands for `pos0`). Ids are written back once. Notes from the phone KOReader can't show (a PDF highlight without coordinates, a missing position) wait in `librillo_pending` and are reported back, so they are never lost.
- **Delete** moves the file and its `.sdr` to `<home>/.librillo/trash`. The trash is emptied after `/finish` answers ok; otherwise the books are put back, and leftovers are put back at the start of the next sync.
- **Download** lands in `<home>/.librillo/parts/<sha256>.part`, is checked for size and hash, checked again just before it is renamed into place.
- **The open book** is never moved, replaced, deleted or written: those actions fail with "open on the e-reader" and the next sync tries again.
- **Automatic sync:** when Wi-Fi connects, when a book closes, and every 5 minutes (spaced out to 80 minutes while the phone can't be reached). It syncs only when it never synced, when hello says `pending`, or when something changed on the e-reader since the last confirmed sync. It never turns Wi-Fi on.

## Setup (version 2)
Librillo's SSH installer leaves `link-setup.json` beside the plugin. The plugin applies it on its next start and deletes it:
```json
{"format": "librillo-link-setup", "version": 2, "server": "http://192.168.1.42:8765",
 "username": "folio", "userkey": "…", "root": "Librillo", "auto_sync": true,
 "sync_forward": "prompt", "device_name": "Kindle Paperwhite"}
```
`device_name` is optional. Progress sync's settings are written to both places KOReader reads: `settings/kosync.lua` (key `settings`) and the older reader setting `kosync`: `custom_server`, `username`, `userkey`, `auto_sync = true`, and `sync_forward` and `sync_backward` = 1 (ask first) unless already set; `checksum_method` is left alone. The plugin keeps `root`, `auto_sync` and `device_name` in its reader setting `folio_sync`. Version 1 (without `root`, `auto_sync` and `sync_forward`) is still read; it sets up Progress sync only and leaves two-way sync off until it is switched on. Neither version writes `folio_auto_fetch` any more (step 5 removed Get books).

On the phone, Link runs while Librillo is in the foreground and for 30 minutes after it leaves, longer while an e-reader is in the middle of a sync (from a successful `/sync` until `/finish`, or 30 minutes of silence). It starts only once Link is set up (its password exists) and "Sync automatically" (the old on/off setting) is on.

## Safety
- Nothing is changed on either side until the Kindle reports at `/finish`, and then only what succeeded.
- A file that is missing is a deletion only when a baseline says both sides had it. Otherwise it is sent.
- Deleting on the phone always means Recently removed. Only purging is permanent.
- Annotations are only ever added. Before the Kindle deletes a book, its annotations are copied to the phone.
- The Kindle never overwrites a file it was not told to replace, and checks the hash before replacing.
- No credentials, tokens or book content are logged.
