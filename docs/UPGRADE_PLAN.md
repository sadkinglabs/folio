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
| "Preserve existing technology unless a demonstrated problem" (which locks in SSH push/pull) | Treat the **sync transport as an open product decision** (decided in §5: kosync with the phone as the server) | The manual SSH model is the root cause of the friction, and the reviews demonstrate the problem |
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

**Sync** (see §5.1 for the Folio Link design)
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

## 5. Decisions (2026-10-14)

| Topic | Decision |
|---|---|
| Progress sync | **kosync, with the phone as the server.** Folio runs a small LAN server ("Folio Link"), and KOReader's built-in Progress sync points at it. |
| Devices | **Device-agnostic: any device running KOReader** (Kindle, Kobo, PocketBook, Android e-ink). The Kindle-specific paths become a device profile. Stock Kobo and Kindle firmware is out of scope, since only KOReader can sync. |
| Login | **A key generated on the phone is the default.** Password-free mode is used only once, to install the key. |
| Audience | Personal for now, but built to release grade. |
| Book search | An **in-app WebView** that opens a query on a configurable site (a URL template). No scraping. |

### 5.1 Folio Link: how the phone acts as the server

**Can the phone be the kosync server? Yes, within limits.** kosync is a small HTTP+JSON protocol.
- KOReader endpoints:
  - `POST /users/create`
  - `GET /users/auth`
  - `PUT /syncs/progress`
  - `GET /syncs/progress/:document`
- Auth uses the `x-auth-user` and `x-auth-key` headers.
- The document key is KOReader's partial MD5. Folio already computes it (`koreaderDigest`).
- KOReader accepts a custom server URL, so `http://<phone-ip>:<port>` works.

The same server can also expose an **OPDS catalog**. KOReader has an OPDS browser built in, so book delivery needs neither SSH nor a custom plugin.

```
Phone (Folio)                                     E-reader (KOReader)
┌───────────────────────────────┐   LAN / hotspot   ┌───────────────────────────┐
│ Folio Link (foreground svc)   │◄──────────────────┤ Progress sync (built-in)  │
│  • kosync API  /syncs/...     │   HTTP + auth     │   push on page/close/sleep│
│  • OPDS feed   /opds/...      │◄──────────────────┤ OPDS catalog (built-in)   │
│  • /folio/...  (plugin API)   │◄──────────────────┤ Folio plugin (slim):      │
│ Room: progress per device     │                   │   discovery, auto-download│
│ Reader ⇄ kosync xpointer      │                   │   "Send to device" queue  │
│ Hardcover outbox ─────► HTTPS │                   └───────────────────────────┘
└───────────────────────────────┘
```

**Constraints, which are part of the design:**
1. **Same network.** The e-reader must reach the phone: the same Wi-Fi, or the e-reader on the phone's hotspot. Sync happens whenever they're together, not over the internet. That fits a personal setup; a later optional relay could cover away-from-phone sync.
2. **Android background rules.** A listening server needs a **foreground service** with a notification.
   - It uses the `connectedDevice` type, because the `dataSync` type is capped at 6 h per 24 h on Android 15+.
   - Modes:
     - *Always on while on Wi-Fi or hotspot* (the default, with a quiet notification).
     - *Only while Folio is open*.
   - Whether incoming LAN connections survive Doze in this mode must be **measured on your phone**. That is the first thing M3 checks.
3. **Changing addresses.** On home Wi-Fi the phone's IP changes. Mitigations:
   - The slim Folio plugin finds the phone with a UDP broadcast (LuaSocket is bundled with KOReader) and updates kosync's server URL itself.
   - Fallback: a stable hotspot gateway, or the URL shown in Folio for manual entry.
4. **Conflicts.**
   - KOReader already handles them on its side: it can be set to jump forward automatically and to ask before going backward.
   - Folio stores *every device's* latest report with its timestamp, never only "the furthest". So the book's Progress panel can show divergence, and the phone reader asks before jumping backward.
   - Hardcover is updated from the outbox after a progress change, either reviewed or automatically by setting.
5. **Security.**
   - Bind only to the Wi-Fi/hotspot interface.
   - Generate random per-device credentials, which the plugin installs, so there's no typing.
   - Require auth on every route, rate-limit it, and never serve outside the library.
   - kosync's auth is weak (an MD5 key over HTTP). That's acceptable on a trusted LAN and documented as such.
6. **Fidelity.** kosync carries KOReader's xpointer plus a percentage. The phone reader turns the xpointer into a Readium locator using the existing converter, once the M1 fixes (spine href mapping, caret capture) are in.

**What happens to SSH:** it becomes **bootstrap and advanced only**.
- First-time setup: generate a key on the phone, install it, install the plugin and its config.
- Optional bulk folder mirroring.
- USB copy instructions are the device-agnostic fallback for installing the plugin.

**Explicitly not doing:**
- Mirroring `.sdr` sidecars.
- Writing live KOReader settings from the phone. Only the plugin, running inside KOReader, changes KOReader state.

### 5.2 Book search
Settings hold a list of search sources, each a name and a URL template with `{query}`, `{title}` and `{author}` placeholders. Want to Read → **Find EPUB** opens the chosen source in an in-app WebView.
- **A `DownloadListener` catches EPUB downloads.** Each file streams into the normal import pipeline (validated and hashed) and is offered as a one-tap **Attach to "<book>"**, then **Send to device?**
- **Out of scope:** scraping, link-following and background downloads. The user drives the page.

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

**M0 status (2026-10-14):**
- **Done:** the repo baseline and executable `gradlew`; CI rewritten into a checks job and an emulator job, with signing from a secret, every check script included, and the Hardcover schema check; `MigrationTest` covering every schema → 7; a shared `ALL_MIGRATIONS` list; removal of the duplicate lifecycle alias and the unused plugin; docs consolidated into README, CHANGELOG and `docs/history/`.
- **Pending:** a green CI run once the GitHub repo exists. `:app` could not be built in this container.
- **Material3 beta:** kept for now (`MaterialExpressiveTheme` needs it). Revisit when 1.5.0 is stable.

### M1: A reader you can actually use
- Fix the overlay resize, edge-tap and volume-key turns, immersive mode, state restore, debounced durable saves, and the spine href mapping.
- Expose the typography controls, and make preferences per book.
- Add a test corpus on a real WebView in the emulator: malformed, image-heavy, non-linear spine, RTL, huge chapter.
- **Device check:** open 5 varied EPUBs; rotate, background and kill the app; confirm the position is restored to the passage.

**M1 status (2026-10-14):** done, apart from the device check, which needs your phone.
- **Stable page area:** the controls overlay the page and the system bars hide with them, so the WebView never resizes and the reading position doesn't move. The emulator shows full-screen reading, and text stays in place when the controls toggle.
- **Page turns:** edge taps, arrow and page keys (Readium's `DirectionalNavigationAdapter`), and volume buttons (a setting). Any tap the adapter doesn't claim toggles the controls.
- **Spine-correct KOReader positions:** `Epub.spine` includes `linear="no"` items and maps them by path. The capture starts from a hit-test, so it works deep inside long chapters.
- **Saving:** one debounced save per settled page, and the pause save can't be cancelled.
- **Typography:** line spacing, margins and "the book's own formatting". The text-size slider applies on release. **Per-book settings** sit on top of the global defaults.
- **Navigation:** page X of Y, the current chapter highlighted in the contents, and clearer bookmark names. Reader copy is in `strings.xml`.
- **Tests:** an emulator corpus (non-linear spine, a 12,000-paragraph chapter, RTL, image-only, malformed, no chapters); recreation keeps the place; centre taps hide and show the controls; and CI screenshots.
- **Deferred:** restoring the navigator fragment through `fragmentFactory`. Recreation already restores the place from Room, and the emulator verifies it.

### M2: App shell and library UX
- Navigation-Compose with a back stack. Split the ViewModels (Library, Book, Lists, Kindle, Pairing, Settings, Discovery).
- Remove the global busy lock, using an operations banner instead.
- Book hub: a primary **Read/Continue** button; **Send to Kindle** and **Progress** as secondary; everything else grouped under ⋯.
- Library: persisted search, filter and view settings; Coil covers; selection survives rotation; batch "Send N to Kindle" without a tab jump.
- `strings.xml` and the copy pass; tokens applied everywhere; Roborazzi screenshot tests (light, dark, 200% font).
- **Device check:** run add → attach → edit → organise → read without dead ends, with back behaving as expected everywhere.

**M2 status (2026-10-14):** done, apart from the device check, which needs your phone.
- **Navigation:** a small saveable back stack (`FolioNav`: tab plus Book, Record, Edit and Settings routes) instead of Navigation-Compose. With four route types and no deep links, the library would add a dependency without a concrete benefit; `FolioNavTest` covers book → book → back, the editor, rotation and process-death restore.
- **ViewModels:** `LibraryViewModel` (books, lists, editing, lookup, backups), `DeviceViewModel` (pairing, comparison, transfers, reading data, phone folder) and the existing `DiscoveryViewModel`. Book, Lists and Settings did not get their own: they would be thin wrappers over the same repository. A shared `OperationRunner` keeps one queue and one status line.
- **Busy lock:** library work no longer waits for a transfer, device work does. A transfer banner shows on every other screen. Adding books and editing stay available while work runs; only double-submit guards remain.
- **Book hub:** primary Read/Continue, secondary Send and Progress, the rest under ⋯; a progress card per source.
- **Library:** grid or list and sort are remembered, filters last for the session, search survives opening a book, selection survives rotation, and sending a selection or list no longer jumps tabs. Covers stay on Folio's own decoder with a cache sized to the heap rather than adding Coil.
- **Copy:** every screen's text is in string resources (about 830 strings and plurals), in plain, device-neutral language. Pairing (`ConnectionForm`) and the SSH reader handoff keep their wording until M4 and M3.
- **Screenshots:** emulator captures in light, dark and 200% font instead of Roborazzi, so they come from real rendering. They are reviewed, not diffed automatically.
- **Not audited:** that design tokens are applied on every screen.

### M3: Folio Link and progress that converges
- **First, a spike on your phone:** a foreground-service HTTP server reached from KOReader over Wi-Fi and over the hotspot, with screen off for 30 minutes. If Doze breaks incoming connections, choose "only while open" plus a wake-on-open prompt, and record the result here.
- A kosync-compatible server, using Ktor server CIO. It has a fake-client JVM test suite that replays real KOReader request and response captures.
- Phone reader as a kosync client: push on pause and close, and pull on open, with "Continue from Kindle at 43%?" when the device is ahead.
- Progress panel per book showing phone, each device and Hardcover; a Hardcover outbox (offline, retried, reviewed or automatic); and a cached `me`.
- Fix the existing SSH handoff path too (tolerance reconciliation, plugin apply check), or retire it once kosync is verified.
- **Device check:** read on phone → open on Kindle (auto-jumps) → read → close → open on phone, which offers the Kindle position → Hardcover shows the right page. Repeat with the screen off for 30 minutes.

**M3 status (2026-10-14):** built; waits on the phone test in [FOLIO_LINK_TEST.md](FOLIO_LINK_TEST.md).
- **Server:** kosync on Ktor CIO in `transport`, answering KOReader's routes, codes and JSON like the reference server. Single account (`folio` plus a password made on the phone), throttled logins, newest report wins, every device kept. JVM tests cover the protocol and real HTTP; they are written from the protocol, not captured from a real KOReader yet.
- **Phone:** a `connectedDevice` foreground service with a Wi-Fi lock and an optional CPU wake lock; Settings shows the address, login, last contact and a connection log with screen on/off. Room 8 adds `kosync_progress`.
- **Reader:** each saved page is published as "Folio on <phone>"; opening a book offers a newer e-reader position once.
- **Book page and Hardcover:** each e-reader's position shows on the progress card and in the Update Hardcover sheet. Automatic Hardcover updates go through a WorkManager queue that only moves an open "Currently Reading" read forward.
- **Not done:** the decision on Doze (needs the phone test), retiring or fixing the SSH handoff (after kosync is proven on a device), and real KOReader captures for the test suite.
- **Schema note:** `8.json` was written by hand; Room accepts its structure but its identity hash is a placeholder. Room does not rewrite a schema file whose structure matches.

### M4: Device pairing and delivery (device-agnostic)
- Device profiles: Kindle, Kobo, PocketBook and generic KOReader, with the default paths for each.
- Pairing wizard:
  1. Connect to the same network or hotspot.
  2. Reachability test.
  3. SSH one-time bootstrap: read the fingerprint, password-free once, install the phone-generated key, prompt to turn password-free off.
  4. Install the plugin and its config (Folio Link URL and credentials) with a version check.
  5. Restart KOReader.
  - USB fallback with on-screen steps.
- Slim plugin v2:
  - discovery;
  - auto-download of the "Send to device" queue over HTTP into the configured folder, with readable `Author - Title.epub` filenames (becomes `Author/Title.epub` in M8);
  - acks;
  - the version handshake;
  - KOReader `PathChooser`;
  - real error messages.
- An OPDS feed: "Sent to this device", lists, recently added.
- The SSH folder mirror stays as an advanced feature, with a hash cache, case-insensitive paths and a `WifiLock`.
- **Device check:** fresh pairing of a Kindle (and a Kobo, if available) in under 3 minutes from zero; "Send to device" lands the book on the next Wi-Fi connect with no Kindle-side steps.

**M4 status (2026-10-15):**
- **Already in place from M3:** the plugin finds the phone (discovery), downloads the send queue into its folder with readable names, and acknowledges each book.
- **Slice 1, install on the e-reader (built):** installing over SSH already existed, Kindle-only and with the plugins folder typed by hand. Now Settings → Install KOReader plugin finds KOReader's plugins folder on a Kindle, Kobo or PocketBook, installs the plugin with each file's hash checked and the previous version kept, and leaves a one-time `link-setup.json` (the Link address on the e-reader's network, the username and the key KOReader already stores, never the password). On its first start the plugin applies it to Progress sync, turns on getting books when Wi-Fi connects, and deletes it. Tested: Lua parsing (good, hostile and broken input), the address choice, the setup file, and the folder probe against a real SFTP server in CI. Not tried on a device.
- **Next:** the pairing wizard (reachability test, key bootstrap) and the OPDS feed.

### M5: Acquisition loop
- Search sources in Settings; the in-app WebView with download interception; the pending-acquisition → attach → send chain; a broader VIEW intent filter for `.epub` from other apps.
- **Device check:** Want to Read → EPUB on the e-reader in 5 taps or fewer, excluding the steps inside the web page.

### M6: Reader depth and polish
- Search, highlights and notes (round-tripping KOReader), dictionary, time left. Read-aloud is its own milestone, M7.
- Adaptive tablet layout and an accessibility audit (TalkBack, 200% font).
- Data hardening: foreign keys and cascades, relative paths, revision pruning, typed errors, DAO split.

### M7: Read aloud
Librillo reads the open book aloud with the voice engine already on the phone, highlights the sentence being read and turns the page with it. Listening moves your place like reading does.

**Approach (decided 2026-10-15):**
- Use Readium's own read-aloud module, `readium-navigator-media-tts` 3.4.0 (`TtsNavigatorFactory`, `AndroidTtsEngineProvider`). It walks the book sentence by sentence, gives a locator for each one and runs a Media3 session for lock-screen and headphone controls. Writing this ourselves would repeat Readium's work for no gain.
- Speak through Android's `TextToSpeech`, so any installed engine works. Google's voices work offline once downloaded.
- For the most natural voice, recommend the **sherpa-onnx TTS engine with Kokoro-82M** (Apache 2.0). It installs as a normal Android engine, so Librillo needs no model of its own.
- **Changed after the phone test:** the system voice was not good enough, so Kokoro now runs inside Librillo as a Readium engine, with the model as a one-time download (see the status below). The system-engine route stays as the fallback.

**New dependencies:** `readium-navigator-media-tts` and, through it, `androidx.media3:media3-session` 1.11.0. The benefit: background playback, lock-screen controls and audio focus without our own player.

**Slices:**
1. **Spike on the phone (first).**
   - Install the sherpa-onnx engine with Kokoro.
   - Time how long it takes to make 1 minute of speech. Check whether it stalls between sentences.
   - Record the result here.
   - Decide whether Kokoro is the recommendation or just an option next to Google's voices.
2. **Play from the reader.**
   - A Listen button in the reader bar starts reading from the first sentence on screen.
   - Play/pause, previous and next sentence, and speed (0.75×–2×).
   - The current sentence is highlighted with a Readium decoration, and the page follows it.
   - Tapping the page while listening shows the controls. It doesn't stop playback.
3. **Keep your place.**
   - Each spoken sentence's locator goes through the existing debounced save.
   - Stopping saves at once.
   - The position is published to Librillo Link like a read page, so the e-reader opens where listening stopped.
   - Hardcover progress only moves forward, as now.
4. **Background and lock screen.**
   - A `mediaPlayback` foreground service. This adds `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
   - A media notification with play/pause and next.
   - Pause when headphones disconnect or another app takes audio focus.
   - A sleep timer: 15, 30 or 60 minutes, or the end of the chapter.
5. **Voice settings.**
   - Pick the voice from those that match the book's language. Remember it per language.
   - Speed is remembered globally, and per book if changed in the reader.
   - When an engine is missing or has no voice for the language, link to Android's text-to-speech settings. A short line explains that better voices can be installed there.
   - Copy goes in `strings.xml`.

**Tests:**
- Emulator: start, pause and stop on the corpus books. The highlight follows the page. Stopping saves a position inside the right chapter. Skips images and empty chapters without stalling.
- Robolectric screenshots of the player bar and voice sheet in light, dark and 200% text.
- The emulator's engine is not the phone's. Voice quality and speed are only checked in the device check.

**Device check:**
- Listen for 10 minutes with the screen off.
- Pause from the lock screen, then unplug the headphones.
- Reopen the book: it opens at the last spoken sentence.
- Open it in KOReader: it's at the same passage.
- Do it once with Google's voice and once with Kokoro.

**Risks:**
- Kokoro's speed on your phone is unknown, so the spike decides it. Reported figures are 20–40 s of compute per minute of speech on recent flagships, and several minutes on budget chips.
- Some EPUBs split sentences badly, for example with drop caps or inline footnotes. Check them in the corpus.
- Long listening sessions use battery. Measure it during the device check.

**Not in this milestone:** making audiobook files, cloud voices, and reading aloud on the e-reader.

**M7 status (2026-10-15):** first simple version built; the speed spike is skipped for now.
- **Built:** a Listen button in the reader bar starts reading from the top of the screen with the phone's default voice engine. A floating player has speed (1×, 1.25×, 1.5×, 2×, 0.75×, remembered), previous and next sentence, play/pause and stop. The sentence being read is highlighted and its page follows, which saves the place and publishes it to Librillo Link through the usual page save.
- **Tried on the phone (owner, 2026-10-15):** works. Volume keys turned pages while listening; they now set the volume instead.
- **Slice 4 built:** a `mediaPlayback` Media3 service (`ListenService`) wraps Readium's player, so reading continues with the screen off and shows on the lock screen and in the notification shade. Headphone unplugging and audio focus are Readium's. A sleep timer (15, 30, 60 minutes or end of chapter) sits on the player. With the reader in the background, the spoken sentence is saved at most every 15 seconds; the page save adds the KOReader pointer when it is back on screen. Closing the book or swiping Librillo away stops reading.
- **Not verified on a device:** the lock screen, notification and screen-off playback.
- **Slice 5 built:** a Listening sheet with the sleep timer and the phone engine's voices for the book's language.
- **Natural voice (owner: the phone voice is not good enough):** Kokoro 1.0 (8-bit, Apache 2.0) through sherpa-onnx v1.13.8, plugged into Readium as its own `TtsEngine`, so highlighting, place saving, lock screen and sleep timer are unchanged. The model is a one-time 132 MB download from sherpa-onnx's release, checked against its size and SHA-256, unpacked to app storage (English files only). 12 English voices; Heart is the default. Non-English books, or a failure to start, use the phone voice.
  - Look-ahead: Readium asks for one sentence at a time, so the engine walks the book with Readium's content API and the same sentence splitter and generates the next 3 sentences while one plays.
  - Measured on the JVM here (4-core Xeon, not the phone): 5.2 s of speech in 6.2 s, about 0.83× real time; the model loads in 2.5 s. On the phone it must beat 1× or there will be pauses. Not measured on a device yet.
  - APK grows from 45 to 70 MB (ONNX Runtime for arm64 and the emulator's x86_64).
- **Next:** the phone test of the natural voice: speed, pauses between sentences, battery.

### M8: One Books folder, the same on the phone and the e-reader
Your books live in one visible folder on the phone, `Books/Author/Title.epub`. That folder holds the only copy of each EPUB, and Librillo's library mirrors what it finds there. The e-reader's Librillo folder gets the same tree. A `Books/Inbox` folder takes any EPUB you drop in, and Librillo files it when it opens.

**Decided (owner, 2026-10-16):**
- The Books folder holds the only copy. There is no private master copy any more.
- The library mirrors the folder: what is in the folder is in the library.
- Folders by author: `Author/Title.epub`.
- `Books/Inbox` is a drop folder, processed when the app opens.
- An imported book does not need its source file; the original can be deleted from Downloads.

**Folder access:** Librillo asks for Android's "All files access" (`MANAGE_EXTERNAL_STORAGE`), once, with a screen that says why.
- **Why:** the reader, the KOReader fingerprint, SSH sends and backups all work on plain file paths. Android's folder picker would mean rewriting each of them for content URIs, and scanning through it is slow.
- **Cost:** it is a broad permission. Google Play restricts it, but this app is installed directly, so that doesn't apply.
- Without the permission, the library still opens and reads what it already knows, and Settings says what is missing.

**Layout:**
- `Books/` is in the phone's shared storage, next to Downloads and Documents. You can change where it is in Settings; Librillo moves the whole tree.
- `Books/Author/Title.epub`: every book. Folders and files are named by the path rule below.
- `Books/Inbox/`: drop EPUBs here. `Books/Inbox/Not imported/` keeps files that couldn't be added, with the reason in the app.
- `Books/.librillo/`: Librillo's own working space, hidden in most file managers and never sent to the e-reader. It holds:
  - `removed/`: books in Recently removed, so Undo works;
  - `previous/`: the file an edit replaced, kept for 30 days;
  - `originals/`: the original download of each book, moved here from today's private storage.

**The path rule** (pure Kotlin in `core`, unit-tested):
- The author folder is the book's first author as shown in the app; with no author, "Unknown author". The file is the title.
- Names are safe on FAT and exFAT, which e-readers use: no `\ / : * ? " < > |` or control characters, no trailing dots or spaces, and parts kept under 120 characters.
- Names that differ only by case count as the same, since FAT ignores case. A clash gets `Title (2).epub`.
- Librillo applies the rule to the books it files: Inbox, imports from the app, and edits. A book you placed elsewhere in the tree yourself keeps its name.

**How books come in:**
- **Inbox, on open.** Each time Librillo comes to the foreground, and on pull-to-refresh, it processes `Inbox`:
  - a readable EPUB is **moved** (not copied) to `Author/Title.epub` and added to the library;
  - a file whose content is already in the library goes to `Not imported` as a duplicate. Nothing is deleted;
  - a broken, protected or non-EPUB file goes to `Not imported`, with the reason;
  - a file still being written (its size is changing) waits for the next pass.
- **Import in the app** (Add books, Open with, Share) copies the file into the tree under the rule. The source is left alone and can be deleted.
- **A file put straight into the tree,** outside Inbox, is added as it is, in place.

**How the library mirrors the folder.** On every open and refresh, Librillo scans the tree (not Inbox or `.librillo`) and compares it with what it last saw:
- **New file:** added as a book.
- **Same content at a new path** (you moved or renamed it): the book follows it and keeps its progress, notes and lists.
- **Same path, new content** (edited elsewhere, for example in Calibre): accepted as a new revision of that book. The book page says so, because the saved reading position may land a little off.
- **Gone:** the book stays in the library, with its progress, notes and lists, as "EPUB missing". If the same content shows up again anywhere in the tree, it is linked back. A missing file is never treated as a deletion.
- **Speed:** hashing every file on every open is too slow, so the record keeps each file's size and modified time and only re-hashes what changed. The record is a new Room table, an additive migration to schema 9 with a `MigrationTestHelper` test.

**What Librillo itself writes to the folder:**
- **Edits** that rewrite the EPUB (metadata, cover) write the new file next to the old one, then swap them. The replaced file goes to `.librillo/previous/` for 30 days. If the file's content changed since Librillo last saw it, nothing is written; the book is listed for review instead.
- **Title or author edits** move the file to its new path, and the e-reader copy follows (see renames below).
- **Remove in the app:** the file moves to `.librillo/removed/` and the book goes to Recently removed. Undo moves it back. Purging Recently removed deletes the file for good, after a confirmation that says so. This is the only way Librillo deletes a book file.
- Files Librillo didn't write are never renamed or deleted, except Inbox files, which it files as above.

**The e-reader gets the same tree:**
- Link downloads and SSH sends use the book's path under the Librillo folder (`/mnt/us/Books/Folio` on a Kindle by default). The old `<id>.epub` and flat `Author - Title.epub` names go away.
- Only books you send go to the e-reader, as now. Inbox and `.librillo` never do.
- Removing a book on the phone leaves the e-reader copy alone, as now. Removing it there stays a separate, explicit action.

**Renames on the e-reader keep your notes:**
- KOReader keeps highlights, notes and position in a `Title.sdr` folder next to the book, and its history and collections refer to the book by path. Moving only the `.epub` would lose all of that.
- So on the e-reader, moves are done by the plugin, inside KOReader, using KOReader's own calls. It moves the book with its `.sdr` folder and updates history, collections and Librillo's list collections. It moves nothing while that book is open.
- Over SSH alone (no plugin running), a move takes the `.epub` and `.sdr` together and leaves history to KOReader, which drops stale entries.
- Link progress keeps matching after a move: KOReader's default progress key is a fingerprint of the file's content, not its name. If KOReader is set to match by file name, a moved book shows as new; the Link settings will warn about that setting.

**Moving today's library (one time, on the first start of this version):**
- Each book's current EPUB moves from private storage to `Books/Author/Title.epub`; its original download moves to `.librillo/originals/`.
- Each file is checked by hash after the move, before the private copy is removed. If anything fails, the private copy stays and the book keeps working from it, and the app says which books didn't move.
- Covers and other app data stay private.

**Backups:** a backup still contains every EPUB, read from the folder; it's now the only copy, so this matters more. Restore writes the books into the tree under the path rule. Older backup formats still restore.

**Slices:**
1. **Path rule and folder access.** The `core` path function and its tests; the "All files access" screen; choosing where `Books` lives.
2. **Move the library into the folder.** The one-time move with hash checks; the reader, Link fingerprint, sending and backups working from folder paths; schema 9.
3. **Mirror the folder.** The scan on open and refresh: new, moved, edited and missing files, with the hash cache.
4. **Inbox.** Processing on open, `Not imported` with reasons, and imports from the app writing into the tree.
5. **Edits and removal.** Swap-and-keep for rewritten EPUBs, moves on title or author edits, `.librillo/removed/` for Undo, and delete on purge only.
6. **Same tree on the e-reader.** Link downloads and SSH sends use the readable path; the plugin creates author folders and reports what it wrote.
7. **Renames and the one-time tidy on the e-reader.**
   - Edits queue moves, which the plugin performs with `.sdr`, history and collections.
   - A preview lists every Librillo book on the e-reader still under an old name and where it will go. It moves them on confirm. Books that aren't Librillo's are not touched.
8. **Retire the old two-way folder mirror** (`PairedFolder`, `FolderReconciliation`) and the Phone folder setting, which the Books folder replaces.

**Tests:**
- JVM:
  - the path rule;
  - each mirror decision (new, moved, edited, missing, back again);
  - Inbox outcomes (filed, duplicate, broken, still being written);
  - the one-time move, including a failure halfway;
  - the schema 9 migration.
- Emulator:
  - import from Downloads, delete the source, then reopen, read, send and back up the book;
  - drop a file in Inbox, reopen the app: it's filed and readable;
  - delete a book's file in the Files app: the book shows "EPUB missing" with its progress, and putting the file back relinks it.
- Lua: plugin moves with `.sdr`, history and collection updates, refusal while the book is open, and hostile paths.
- SFTP in CI: SSH sends land at readable paths; a move carries the `.sdr`.

**Device check:**
- Install over the current app: every book is in `Books/Author/` and opens; progress and notes are intact.
- Download an EPUB, move it to `Books/Inbox` in the Files app, open Librillo: it is filed and in the library. Delete the download: nothing changes.
- Rename a book file in the Files app: the book follows it, with its progress.
- Send to the Kindle: KOReader shows the same `Author/Title.epub` tree.
- Highlight a passage on the Kindle, then edit the book's title on the phone and sync. The Kindle file is renamed, and the highlight and position are still there.
- Run the one-time tidy on a Kindle with old-name books. Same check.

**Risks:**
- **One copy means a deletion in the Files app is real.** Librillo keeps the book's record, notes and progress, but not the file. Backups and `.librillo/previous/` are the only safety net, so the backup reminder becomes more prominent.
- **Other apps can touch the folder** (file managers, sync apps, cleaners). The mirror handles moves and edits, but a cleaner that empties hidden folders would take `.librillo/` with it.
- **KOReader settings vary:** metadata can live next to the book, in a central folder, or by hash. The plugin must read which one is used before moving.
- **E-reader indexers:** the Kindle's own home screen indexes `/mnt/us/documents`, not `Books`, so it is not affected. A Kobo's library may re-index after moves.
- **Large libraries:** the first scan of a few thousand books takes a while. It runs in the background with progress, and later scans only re-hash what changed.

**M8 status (2026-10-16):**
- **Slice 1 built:**
  - `BookPaths` in `core` gives `Author/Title.epub`: FAT-safe names, a 120-character and 240-byte limit per part, case-insensitive clashes, reserved Windows names, and composed and decomposed accents treated as one name. Tested on the JVM.
  - Settings → Library → Books folder shows where the folder will be (Phone storage/Books by default). It asks for All files access with a plain reason, and lets you choose another folder in phone storage. Nothing is moved there yet.
- **Slice 2 built:**
  - Settings → Books folder shows how many books are still in Librillo's storage, with a "Move N books here" button. It's a button, not automatic, so you can pick the folder's location first.
  - Each book's current file goes to `Author/Title.epub`, and an edited book's original download goes to `.librillo/originals/<id>.epub`. Each copy is flushed and checked against the book's hash before the database points at it, and Librillo's private copy is removed last.
  - A book that fails stays private and keeps working. Running it again moves what's left and reuses a file an interrupted run already placed. Recently removed books stay private until restored.
  - The reader, Link fingerprint, SSH sends, export, "Open in another app" and backups accept files in the Books folder. Backups name them `folder-original.epub` and `folder-current.epub`, and they restore into an empty library (tested).
  - Edits to a moved book stay in the folder:
    - the new file is written next to the old one;
    - the old one is kept in `.librillo/originals` if it was the original, else in `.librillo/previous`;
    - the file follows a new title or author;
    - nothing is written if the file changed outside Librillo.
  - Purging a book from Recently removed deletes its folder file and an empty author folder; removing alone keeps it, so Undo works.
  - "Change location" is hidden once any book is in the folder, until moving the folder is built.
  - **Changed from the plan:** the Room table that records files (schema 9) moves to slice 3, where the folder scan needs it. Slice 2 reads each book's path from the existing `books` table.
  - Tested: 10 move, edit, purge and backup tests (Room in Robolectric on the JVM), the Settings sheet on the JVM, screenshots and the touch-target check.
- **Not verified on a device:** the All files access page, the folder picker, and the move on real phone storage.

**Not in this milestone:** an Inbox on the e-reader, series subfolders, other layouts (flat, by series), and mirroring anything other than EPUBs (covers, notes as files).

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
