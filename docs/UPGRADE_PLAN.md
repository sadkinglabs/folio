# Folio upgrade plan (from 0.10.1)

Review date: 2026-10-14. This is a static source review: four parallel deep-dives (UI/UX, reader, sync/plugin, data/architecture), with the most serious claims spot-checked against the code. Nothing was built or run on a device.

Verified handover facts: package `dev.sadking.folio`, versionCode 16 / 0.10.1, Room schema 7, backup formats 1–4 accepted, minSdk 26 / targetSdk 36. About 20k lines of Kotlin, Lua and JS, plus about 19k words of docs.

---

## 1. Verdict

Folio's **engine is careful**. The weak parts are the **product shell** and the **sync model**.

**What is genuinely good, and should be kept:**
- The `core` and `transport` modules are pure JVM code with good tests, including real localhost SFTP integration tests.
- SSH host-key pinning is strict, and secrets are stored with Keystore AES-GCM.
- EPUB and ZIP input is handled with hostile files in mind.
- The sidecar parser never executes Lua.
- The upload queue is journalled in Room and can be resumed.
- Room schemas are exported and every migration is additive.
- Backups are verified with checksums.

**What blocks "production ready":**

1. **Two-way progress sync can never converge.** I verified this in the code.
   - `PositionReconciliation.plan` compares exact xpointer strings.
   - `PairedFolder` only stores the "last agreed" baseline when the decision is `UNCHANGED`.
   - Phone and Kindle pointers almost never match character for character, because the first visible character moves with font size, rotation and page layout.
   - So any book with a position on both sides becomes a `CONFLICT` on every sync, permanently.
2. **The plugin's position apply reports failure even when it worked.** I verified this in the code.
   - `main.lua:122` asserts that `rolling.xpointer == request.position` after `GotoXPointer`.
   - In page mode KOReader stores the start of the page instead, so the assertion fails.
   - The request is never recorded as applied, and it replays every time the book is opened.
3. **The sync model is entirely manual.** Every sync needs a phone hotspot, the Kindle's SSH server running, the Kindle awake, and a known IP address. Each sync also downloads and hashes the whole Kindle library again, because there is no hash cache.
4. **The phone reader has never rendered a page on a real device.**
   - Hiding the controls resizes the WebView, which re-paginates the book and moves the reading position.
   - Tapping the screen edges or pressing the volume keys does not turn pages.
   - It saves the position twice per page turn, and the first save briefly blanks the xpointer.
   - The reader throws away its saved state on recreation: `super.onCreate(null)`.
5. **The UI shell is a hand-rolled state machine.**
   - `FolioApp.kt` is 1,300 lines and switches screens with about 40 boolean and ID flags. There is no back stack.
   - About 20 sheets are declared at the root of the app.
   - `LibraryViewModel` (1,019 lines) and `DiscoveryViewModel` (733 lines) are god objects.
   - A global `busy` flag disables reading, editing and adding books while any transfer runs.
6. **Copy is written by and for the implementer.** Words like "sidecars", "catalogue entry" and "provenance" appear, with paragraphs of caveats on each surface. There is no `strings.xml`.
7. **Verification is thin where it matters.**
   - 38 instrumentation tests have never been executed.
   - Migrations are checked by Python scripts that regex SQL out of `Database.kt`.
   - The Hardcover schema check is not in CI.
   - The CI APK is signed with a random key, so it cannot install over your build.

### Why it ended up here

The Codex brief tried to cover everything in one prompt and demanded heavy caution. That produced breadth, defensive hedging and documentation (about one word of docs per line of code) rather than finished vertical slices. The rest of this plan works in **milestones**. Each milestone has a short brief, a definition of done, and a **device check that you run**.

---

## 2. Principles carried over from the Codex brief (kept)

These go into `CLAUDE.md` as standing rules for every future session:

- No destructive migrations.
- Keep the package id and signer.
- Updates must install over the existing app; never wipe the library.
- Never silently overwrite EPUBs or drop annotations.
- Never infer a deletion from a file disappearing.
- Be explicit about what was *executed on device*, what was *executed in the JVM or emulator*, and what was only *compiled*.
- Readium plus WebView remains the reader engine.
- Book records can exist without an EPUB.

## 3. What I'm deliberately changing from the Codex brief

| Codex said | Change | Why |
|---|---|---|
| "Preserve existing technology unless a demonstrated problem" (which locks in SSH push/pull) | Treat the **sync transport as an open product decision** (see §5, Decision A) | The manual SSH model is the root cause of the friction, and the reviews demonstrate the problem |
| One mega-brief: "Do not stop at a plan" | Milestones of 1–2 weeks, each with its own brief and acceptance check | Big-bang briefs produce wide, unfinished work |
| Hedging copy and per-release docs | Plain-language UI and one `CHANGELOG.md`; fold the 0.x design notes into `ARCHITECTURE.md` | Sprawl hides what's true *now* |
| Implied: perfect bit-exact positions | Reconcile positions by **resolved progression with tolerance**; xpointer is used for *navigation*, not *equality* | Exact strings can't converge |

---

## 4. Target architecture (incremental, no rewrite)

The approach is to replace parts one at a time while the app keeps working. The engine (`core`, `transport`, Room data, backup) stays.

**App shell**
- Navigation-Compose with type-safe routes:
  - `Library`
  - `Lists/{id}` (Want to Read pinned)
  - `Book/{id}`, one hub for both record-only and EPUB books
  - `Book/{id}/edit`
  - `Reader/{id}`
  - `Kindle`
  - `Kindle/pair`, a step-by-step wizard
  - `Settings/*`
- Sheets become destinations, and results return through `SavedStateHandle`.
- One ViewModel per screen. Each exposes a single immutable `UiState` built with `combine(...).stateIn` plus an `onAction(Action)` entry point. Screens are stateless composables, so they can be previewed and tested.
- **No global busy lock.** Long work runs in an app-scoped operations layer and shows as a non-blocking progress banner. Only actions that genuinely conflict are disabled, and each says why.
- DI: keep it manual, with one `AppGraph` interface and `ViewModelProvider.Factory`. Hilt isn't worth it for a single-developer app, but no ViewModel should construct its own collaborators.

**Design system**
- `FolioTokens` (spacing, shape, motion) applied everywhere. Today they exist but are bypassed.
- A bundled reading serif (Literata).
- Coil for covers, with aspect-aware crop and placeholders. This replaces the 24 MB bitmap cache that thrashes.
- All copy moves to `strings.xml`, with a plain-language glossary pass.
- A book action sheet grouped into Read / Send / Progress / Organise / Advanced. Today it is 16 flat buttons.
- Adaptive layout via `NavigationSuiteScaffold` and a list-detail pane, after the phone layout is right.

**Reader**
- Keep `EpubNavigatorFragment`.
  - Set its fragment factory before `super.onCreate`, and use normal state restore.
  - Add `DirectionalNavigationAdapter` for edge taps and volume keys.
  - Draw the controls as an overlay with immersive system bars, so the WebView never resizes.
- `PreferencesManager` per book on top of global defaults.
  - Controls for line height, margins and publisher styles.
  - Apply the slider only when the drag ends.
- Locator persistence:
  - Debounce to about 1 s and write the native locator.
  - Capture the xpointer only on pause or handoff, using `caretPositionFromPoint`.
  - Write in an app-scoped or `NonCancellable` coroutine.
- Map xpointer `DocFragment[N]` through the **manifest spine href**, not `readingOrder` index. Today non-linear spine items probably shift the chapter.
- Later features: search (`SearchService`), highlights via the Decorator API (round-tripping KOReader `pos0`/`pos1`, which are already parsed), dictionary through `ACTION_PROCESS_TEXT`, TTS, a scrubber with time left.

**Sync** (shape depends on Decision A)
- Positions: reconcile on a normalised `(spine href, progression)` with a tolerance of about half a screen. Store the baseline after **every** successful apply or observation.
- Files:
  - Keep a `(path, size, mtime) → sha256` cache so a sync doesn't download the library again.
  - Compare paths case-insensitively, because the Kindle's storage is vfat.
  - Use human-readable `Author - Title.epub` filenames, with the UUID kept in the database.
- Long-running work (folder reconcile, handoff) moves out of ViewModels into the same service or user-initiated job as uploads, holding a `WifiLock`.
- Plugin:
  - Add a version and capability handshake file.
  - Write explicit **ack files** that the phone reads, then prunes the request.
  - The phone pushes the plugin's folder config, so the two never disagree.
  - Use the `PathChooser` widget.
  - Replace the success assertion with "the page now contains the requested xpointer".
  - Show real error messages.
  - Flush `G_reader_settings`.
  - Gate on the ReadCollection API version.

**Data** (no remodel yet)
- Add `MigrationTestHelper` tests for 1→7 now. Future additive migrations use `@AutoMigration`.
- Add foreign keys and cascades in a careful migration. Move absolute paths to paths relative to `filesDir`.
- Prune old metadata-edit revisions: keep the original and the last N.
- Typed errors (a sealed `FolioError`) instead of showing `require`/`check` messages to users.
- Split the 90-method DAO by aggregate.
- **Defer** the big `Work`/`book_files` unification of `books` and `wishlist`. It's the right long-term model, but it is a high-risk migration with little benefit to users today.

---

## 5. Decisions only you can make

**A. Progress sync transport.** This is the biggest lever.
- *Option 1, recommended:* adopt **KOReader's kosync protocol**.
  - Self-host `koreader-sync-server` (a small Docker container on a home server or VPS), or a Folio-compatible equivalent.
  - The Kindle uses KOReader's **built-in** Progress sync plugin, which syncs automatically on open, close and suspend. No hotspot, no SSH, and no custom plugin needed for progress.
  - The phone reader pushes and pulls through the same API. Folio already computes KOReader's partial-MD5 document key.
  - Steady state: zero manual steps for progress.
- *Option 2:* keep everything over SSH and fix the bugs above. This needs no server, but it stays manual.

**B. Book delivery.** Keep SFTP for now; it works, and you confirmed the 0.3.1 flow. The longer-term option is a slim plugin that pulls new books over HTTP when Wi-Fi connects, if a server exists from Decision A.

**C. Default Kindle authentication.** Fresh setup currently defaults to the KOReader **password-free root SSH** mode. I recommend making a phone-generated key the default: Folio generates the key pair and installs `authorized_keys` once over the password-free session, then the user switches password-free off.

**D. Distribution.** Is this personal-only, or ever Play/F-Droid? This affects how the Anna's Archive handoff is presented and how much release hardening matters.

---

## 6. Milestones

Each milestone ends with: CI green, an APK signed with **your** preserved debug key, and a 10-minute **device check that you run** on the phone and Kindle.

### M0: Foundation (first)
- Repo on GitHub with a `CLAUDE.md` holding the invariants from §2.
- CI:
  - unit tests;
  - an **emulator job** (API 34, using reactivecircus/android-emulator-runner) running instrumentation tests;
  - `MigrationTestHelper` 1→7;
  - the Hardcover schema check;
  - the Lua plugin tests.
- Signing: keystore as a CI secret, so CI APKs install over yours.
- Build hygiene:
  - decide on material3 beta vs stable;
  - remove the duplicate lifecycle alias and the unused plugin;
  - align SDK and build-tools versions;
  - move `ui-tooling-preview` to `debugImplementation`.
- Docs: collapse the per-version notes into `ARCHITECTURE.md` and `CHANGELOG.md`, and slim the README.
- **Done when:** every existing test has actually executed in CI, and the migration chain is verified at runtime.

### M1: A reader you can actually use
- Fix the overlay resize, edge-tap and volume-key turns, immersive mode, state restore, debounced durable saves, and the spine href mapping.
- Expose the typography controls, and make preferences per book.
- Add a test corpus on a real WebView in the emulator: malformed, image-heavy, non-linear spine, RTL, huge chapter.
- **Device check:** open 5 varied EPUBs; rotate, background and kill the app; confirm the position is restored to the passage.

### M2: App shell and library UX
- Navigation-Compose with a back stack. Split the ViewModels (Library, Book, Lists, Kindle, Pairing, Settings, Discovery).
- Remove the global busy lock, using an operations banner instead.
- Book hub: a primary **Read/Continue** button; **Send to Kindle** and **Progress** as secondary; everything else grouped under ⋯.
- Library: persisted search, filter and view settings; Coil covers; selection survives rotation; batch "Send N to Kindle" without a tab jump.
- `strings.xml` and the copy pass; tokens applied everywhere; Roborazzi screenshot tests (light, dark, 200% font).
- **Device check:** run add → attach → edit → organise → read without dead ends, with back behaving as expected everywhere.

### M3: Progress that converges
- Position reconciliation by tolerance-based progression, with a baseline after every apply.
- Plugin fixes: the apply check, acks and pruning, the version handshake, the config pushed from the phone, real error messages.
- **If Decision A = kosync:** a kosync client in the phone reader, plus a pairing screen with server URL and account.
- One **Progress** panel per book showing phone, Kindle and Hardcover side by side, with one "Sync progress" action and an explicit conflict prompt.
- Hardcover: a durable outbox with offline pending state; cache `me`.
- **Device check:** read on the phone → continue on the Kindle → back to the phone with no conflict prompt; then Hardcover shows the right page.

### M4: Kindle pairing and delivery
- Pairing wizard: reachability test, fingerprint, auth (key generated on the phone), folder picker, then plugin install and version check, each a step.
- Sync speed: hash cache, case-insensitive paths, readable filenames, `WifiLock`, folder operations in the service.
- Phone-folder enrolment visible from the book hub ("Keep in synced folder").
- **Device check:** fresh pair from zero in under 3 minutes; sending 20 books takes one tap; a second sync with no changes finishes in seconds.

### M5: Acquisition loop
- The Anna's Archive domain becomes a setting. Broaden the VIEW intent filter (`application/octet-stream` plus `.epub`).
- **Pending acquisition:** "Find EPUB" on a Want to Read entry remembers that entry; the next imported EPUB that matches is offered as a one-tap attach, then "Send to Kindle?".
- **Device check:** Want to Read → EPUB on the Kindle in 5 taps or fewer, excluding browser steps.

### M6: Reader depth and polish
- Search, highlights and notes (round-tripping KOReader), dictionary, TTS, time left.
- Adaptive tablet layout and an accessibility audit (TalkBack, 200% font).
- Data hardening: foreign keys and cascades, relative paths, revision pruning, typed errors, DAO split.

---

## 7. Device acceptance script (use for every milestone)

1. Install the CI APK over the current app; the library is intact.
2. Add a book from a Hardcover search → attach an EPUB → edit the title → add it to a list.
3. Read 10 pages on the phone; rotate; background; force-stop; reopen, and it's at the same passage.
4. Send to Kindle → open it in KOReader at the phone's passage → read 10 pages → close.
5. Sync → the phone opens at the Kindle's passage, with no conflict prompt.
6. Update Hardcover → the page shown on hardcover.app matches.
7. Export a backup → restore it into a disposable install → the books, lists and positions are present.

Report pass or fail per step. Each failure becomes the top item of the next milestone.
