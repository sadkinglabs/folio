# Folio 0.10.1

A native Kotlin Android library for managing EPUBs, delivering them to KOReader on a jailbroken Kindle, and reviewing reading updates on Hardcover. No account is required for local books or Kindle transfer.

The library is the home surface: anchored search with Refine, compact count/view controls, cover badges and contextual long-press actions. Add books retains EPUB import, manual records, camera/discovery, Hardcover lookup and profile import. Want to Read is a pinned list alongside Currently Reading, Finished and custom lists.

A book can exist without an EPUB. Both record-only and EPUB-backed books use the same full-page hub: **Add EPUB / Send to device**, **Update progress**, source-aware status and in-place list management. Attachment preserves associations and returns to the book hub. EPUB edits preserve originals and publish retained revisions.

**Debug engineering release, 0.10.1.** The existing Room schema and development signing identity are preserved. See the [product audit](docs/refinement/AUDIT.md), [experience/design direction](docs/refinement/DIRECTION.md), [implemented changes](docs/refinement/IMPLEMENTATION.md), [validation record](docs/VALIDATION.md) and [remaining work](docs/BACKLOG.md). Host and emulator fixtures do not establish physical Kindle or authenticated Hardcover acceptance.

## Reader startup fix (0.10.1)

The 0.10.0 reader tried to mount its fragment by container ID from AndroidView's factory, before that container entered the Activity view tree. This can throw `IllegalArgumentException: No view found for id … reader_container`. The hotfix uses FragmentContainerView and waits for an attached, resumed host before mounting. It also declares the directly used Fragment KTX dependency explicitly and reports EPUBs without readable chapters as an opening error. Room schema 7 and the signing key are unchanged.

Robolectric regression tests reproduce the old failed ID lookup, verify mounting after attachment and verify waiting through background loading without mounting twice. Android UI instrumentation additionally waits for the actual navigator view, rather than accepting toolbar text as proof of a mounted reader. Robolectric is test-only; it does not validate real EPUB rendering in Android WebView.

## Reader and paired folders (0.10.0)

Open an EPUB-backed book → **Read on phone**. Readium 3.4.0 renders EPUB pages; navigation, contents, bookmarks and appearance controls are native Compose. Your full locator is saved per SHA-256 EPUB revision in Room 7. Light/paper/dark/system appearance, serif/sans text size and scrolling preferences persist. Phone progress is an explicit selectable source in Hardcover's existing review-and-publish flow. Readium needs a WebView solely for book content; this exception was approved for the reader. Local reading needs no account or network.

**Settings → Paired folders → Choose phone folder** grants access through Android's system picker and imports EPUBs recursively. Use the library's top **Sync** icon to compare that tree with the configured Kindle folder. New EPUBs travel in either direction, retaining subfolders; downloaded books enter the same library. Hashes verify files and retries reconcile completed copies. Same-path different editions are kept and reported for review. Android 11+ restricts choosing the Downloads root; choose a dedicated Books subfolder.

**Settings → Install KOReader companion** installs the bundled `folio.koplugin` over the existing pinned SSH connection. The default assumes KOReader's existing `/mnt/us/koreader/plugins`; change it for your installation. Installation stages the whole directory and retains the old version. Restart KOReader once, configure **Folio bridge → Configure Folio book folder** to the same Kindle folder, and enable **Continue from Folio automatically**. No computer or terminal is required. Manual review remains available.

Folder Sync also retrieves saved adjacent KOReader sidecars and reconciles positions against observed baselines. One changed side can be queued for the other; both changed sides require review. Incoming Kindle anchors resolve when you next open the phone reader. Outgoing requests apply when KOReader opens that book, only if its saved position still matches the request's expected state. Pending is not the same as applied. Close books in KOReader before syncing so current positions are saved.

**Current limits:** this is an additive paired-folder release, not a completed general-purpose mirror. Folder scanning happens on Refresh/Sync, not continuously in the background. EPUBs outside the selected phone tree use the existing Send action. Edited/replaced editions require review and are not overwritten automatically; export an edited revision to a new filename for now. Deletions, renames, empty folders, highlights and list memberships are not bidirectionally mirrored. Folder operations are lifecycle-owned and interrupted work is reconciled on retry; the existing standalone upload queue retains its foreground-service behavior. Staging is limited to 1 GiB per run and 10,000 entries/12 folder levels. SAF publishing depends on provider rename behavior, so a local folder is preferred. A source disappearing is not a deletion instruction.

The DOM converter supports classical/explicit KOReader V2 pointers; unsupported DOM versions, boxing wrappers, fixed layout and unresolved anchors fail without percentage guesses. The same DRM-free EPUB bytes are required. Cross-device passage accuracy and Android rendering need physical acceptance tests; fixture tests do not establish that validation. See [reader and sync implementation](docs/READER_0.10.md).

## Implemented

* Add books → Look up on Hardcover → search title/author → review metadata → Add to library. The saved entry opens in details with Add EPUB; once attached, use Send to device and Update progress. Saving locally does not publish a Hardcover status. Optional cover retrieval is bounded and host-restricted.

* Unified owned/catalogue collection with file and reading filters; reviewed Hardcover profile import and manual edition attachments.
* Named lists for books without EPUBs; transactional attachment retains their lists, Hardcover match and existing file reading/device history.

* System file picker, EPUB share sheet and content-URI import.
* Persistent Room library, stable UUIDs, duplicate-file recognition, OPF metadata and cover extraction.
* Cover-first adaptive grid, list view, search, sorting and local reading-status filters.
* Book details, collection creation/membership and batch collection selection.
* Metadata editor: title, authors, description, publisher, language, secondary identifiers, series/number, tags and cover replacement. Preserves original EPUBs and unknown metadata/resources; writes new revisions.
* Phone-only SSH pairing: read the host fingerprint in-app and explicitly trust it; pinned keys remain mandatory. Choose private key, password, or temporary KOReader password-free login. Private credentials use Android Keystore.
* Remote EPUB inventory, full-hash existing-file recognition and explicit sync preview.
* New-book SFTP uploads with a persistent Room queue, user-started foreground service, progress notification, cancel and explicit Resume; temporary staging, readback SHA256 verification, publication and idempotent reconciliation.
* Book details → Update Hardcover reading: confirmed book matching, Currently Reading, edition/page progress, and reviewed estimates from retrieved KOReader percentages.
* Timed, dismissible completion/error snackbars; active operation progress remains visible.
* Light/dark/system themes and optional Material You wallpaper colour.
* Checksum-verified ZIP backups of originals, retained revisions, covers, canonical metadata, collections, and imported reading data/statistics; additive restore with transactional database commit. Credentials and device transfer records are excluded.
* Manual and smart collections (status, language, series, tag, last verified Kindle presence); per-collection transfer previews and opt-in inclusion in one-tap sync.
* Guided KOReader SSH/hotspot pairing and credential reuse when the IP address changes; device identity uses the pinned host key, user and book-folder scope.
* User-triggered Open Library metadata and cover lookup, edition details, cover preview and per-field merge review.
* Read-only adjacent KOReader sidecar retrieval: exact reading pointer, progress, status, ratings, highlights, notes and bookmarks. Append-only annotation observations preserve earlier notes. Phone status is separate.
* Reading tab with cached progress and optional cooperative reading-time statistics; Markdown annotation export through SAF for Obsidian.
* Next unread volume in a series with cached device availability.
* Optional KOReader Folio bridge plugin: reviewed, additive collection updates through ReadCollection and SQLite-transaction statistics export. See [docs/PLUGIN.md](docs/PLUGIN.md).

## Deliberately not implemented

Automatic sync, multi-device management, existing EPUB replacement/deletion, general remote downloads, central/hashed sidecar retrieval, automatic plugin installation, collection removals/renames on the reader, full-text search, watched folders, additional metadata-editing providers remain deferred. A transferred book edited on the phone becomes a visible conflict. It is not silently replaced. See [docs/BACKLOG.md](docs/BACKLOG.md).

## Build

Material3 1.5.0-beta01 is a documented beta exception needed for the public MaterialExpressiveTheme API; stable 1.4.0 keeps that theme internal. Other foundational dependencies are stable.

Open this directory in Android Studio with a **JDK 17** and Android SDK 37.0. Install Android platform 37.0, Build Tools 36.0.0 and platform-tools. SDK root goes in your local `local.properties` or `ANDROID_HOME`; the environment-specific file generated in the build workspace is intentionally excluded from the source archive.

```sh
./gradlew checkFormatting :core:test :transport:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Format source with `./gradlew formatKotlin`; `checkFormatting` verifies the pinned ktfmt 0.63 style. Android lint is the native static-analysis check.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

The private source bundle retains the development debug signing key so subsequent builds can update this APK without uninstalling the library. `development-signing/` is excluded from git. This is a generic Android debug key, not a production release key; use your own release signing setup for public distribution.

Instrumentation/UI tests need an emulator or phone. The complete suite includes a product journey that seeds fixture books, captures native screens and uses the real document picker. Run the complete suite only on a disposable Android 10+ emulator: ProductJourneyTest refuses physical hardware and non-fixture library records before replacing fixture data.

```sh
./gradlew :app:connectedDebugAndroidTest
```

For the regression suite on a phone, exclude the emulator-only product fixture:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=dev.sadking.folio.ProductJourneyTest
```

If a cached Kotlin rebuild reports duplicate overloads after a broad refactor, run `./gradlew :app:clean` before retrying. Release validation uses a clean app build; incremental compiler invalidation remains a tooling follow-up.

Compile test APK without a device:

```sh
./gradlew :app:assembleDebugAndroidTest
```

Real local SSH/SFTP adapter tests (test-only Python dependency, no Kindle required):

```sh
python3 -m pip install paramiko==5.0.0 lupa==2.8
python3 scripts/check-with-sftp.py
python3 scripts/check-migration.py
python3 scripts/check-plugin.py
```

The script creates disposable ECDSA keys and a localhost-only SFTP server, tests key authentication, pin rejection, inventory, byte-verified upload, retry and symlink handling, then closes and deletes everything. Default `:transport:test` skips these integration cases if the fixture environment is absent. Core tests always run. No passing-but-skipped integration tests should be counted as validated.

## Kindle setup

1. Use an already jailbroken Kindle with KOReader. Folio does not perform the jailbreak.
2. Enable your phone hotspot and connect the Kindle to it, or connect both devices to the same trusted Wi-Fi network. The SFTP book transfer uses local traffic and needs no internet access. Guest-network client isolation can block connections. Keep the Kindle awake during transfer.
3. In KOReader, look under **Tools → Network → SSH server** (labels vary by release). Confirm the displayed address and port; built-in setups often use **2222**, whereas another jailbreak SSH server may use 22.
4. Ensure the server offers SFTP. An SSH shell alone is insufficient. KOReader's documented package supplies Dropbear and an SFTP subsystem, but verify your installed build.
5. Use the existing `/mnt/us/documents` folder, or choose **Choose Kindle folder** after trusting the host key and selecting a login mode. The native browser lists real folders under `/mnt/us`, lets you create a named subfolder, and never follows symlinks. No terminal is required.
6. Prefer a public key: generate an ECDSA key pair (`ssh-keygen -t ecdsa -m PEM`), then place the public key in the authorized-keys location displayed by KOReader's SSH settings. Transfer your private key securely to your phone, select it using the app's key picker and enter its passphrase if required. The app encrypts the imported key in private storage. A password configured in your SSH server is also supported. For temporary phone-only access, the separate KOReader password-free option below is also supported; it requires enabling that setting in KOReader.
7. Use **Read Kindle fingerprint** in Folio, confirm the IP shown on the Kindle, and explicitly trust its observed key. This is trust on first use. For independent verification, obtain the server's **SHA256 host fingerprint through a trusted route**, such as the device terminal or an independently verified SSH connection. If a trusted host public key file is available, use `ssh-keygen -lf <host-public-key> -E sha256`. A network `ssh-keyscan` alone does not authenticate the server; independently confirm its output before trusting it. Host-key paths vary by installation.
8. In Folio → Kindle → setup, enter host, port, username, folder and verified fingerprint. Save the key/password. Then tap **Check Kindle & compare** to authenticate and compare.

See the [official KOReader SSH instructions](https://github.com/koreader/koreader/wiki/SSH) for installation-specific authorized-key details. On many Kindles the user is `root`; do not assume this for every server.

## Daily workflow

Download EPUB → share to Folio or Add books → Import EPUBs → open details → Edit metadata → Manage lists → Send to device. The Kindle page also offers **Check Kindle & compare** and **Send missing EPUBs** for reviewed batch delivery. In KOReader, browse the configured folder and open the EPUB. File names use stable UUIDs; book titles come from EPUB metadata. If your file manager displays filenames rather than metadata, enable its book-info/cover view where supported.

Existing byte-identical books are recognized even under another filename within the configured root. A title match with different bytes is not treated as the same edition. The comparison may download candidate EPUBs to hash them, which costs local Wi-Fi traffic but avoids incorrect matches.

## Added workflows

**Lists:** create a named manual list, or choose a smart rule and exact rule value. Tags, series and statuses compare without case sensitivity; language values must match the stored code (for example `es` or `spa`). Manual books can be added from details or batch selection. Smart membership is calculated. Enable **Include in one-tap sync** on the desired collections. With no collection policies enabled, Sync sends all missing library books; with policies enabled it sends only that union. Explicit previews do not depend on those policies. Device-presence browsing is cached; one-tap sync recalculates presence before applying its scope.

**Backup:** Settings → Export library backup creates a ZIP and writes it to your selected provider. Restore validates paths, metadata/resource checksums, bounds and original/current EPUBs before additive changes. Existing books retain their files and metadata; memberships and missing annotations are merged. New restored shelves have sync disabled. Export snapshots omit credentials, transfers and sync history. Files moved before a database transaction commit are removed on ordinary failures; process death may leave an orphan folder requiring recovery. Check a completed external-provider backup before relying on it; provider writes are not atomic.

**Metadata:** book details → Find metadata & covers online → explicit search → edition → tick only the fields/cover you want → Apply. Open Library receives the query; EPUBs and reading data are never uploaded. Provider metadata can be incomplete or refer to a different edition. Original files and the EPUB primary identifier are retained. Online lookups use HTTPS, timeouts, response bounds and sequential requests.

**Reading:** transfer/verify books, close the open book in KOReader, then Reading → Retrieve KOReader progress & notes. Adjacent `.sdr/metadata.epub.lua` files are parsed as restricted Lua literal data without execution. Two equal reads detect changes during retrieval, but do not promise an atomic KOReader-wide snapshot. Reading positions retain source-document hashes and pointers; they are never applied to a changed EPUB. Missing notes are retained rather than treated as deletions; edited notes can appear as retained versions. Details → Export Markdown saves highlights/notes/bookmarks for Obsidian. Latest device progress can move backwards during rereading.

**KOReader collections/statistics:** see [plugin setup](docs/PLUGIN.md). These require the optional bridge and explicit Kindle-side actions, and are not validated on physical hardware.

## Safety and troubleshooting

* Fingerprint mismatch: verify the device identity independently. Never replace the pin merely to dismiss an error.
* Connection fails: confirm Wi-Fi, displayed SSH port, host, authorized key/password and server enabled. No obsolete SSH algorithms are automatically enabled.
* SFTP failure: verify subsystem, existing folder, permissions, free storage and Kindle awake.
* Interrupted upload: reconnect and preview again. Completed destination bytes are reconciled; partially staged files are not valid books. Process death may leave `.folio-*.part` files; remove these manually only after confirming there is no active transfer.
* Edited transferred book: blocked conflict is expected. Safe reading-state-preserving replacements are a later feature.
* Use Settings → Export library backup, then test restore with a disposable library. Uninstalling removes private storage. Android auto-backup remains disabled; portable exports omit Keystore-bound SSH credentials.
* Manual sync is foreground ViewModel work, not a guaranteed background job. Process termination may interrupt it. The Android app never writes live KOReader sidecars, collection.lua or statistics.sqlite3. Optional collection changes run inside KOReader only after plugin confirmation.
* Do not run another tool that modifies the same managed destination while Folio publishes files; cross-client rename locking is not implemented.

## Project guides

[Research](docs/RESEARCH.md) · [Architecture and sync](docs/ARCHITECTURE.md) · [Design system](docs/DESIGN.md) · [Validation](docs/VALIDATION.md) · [Backlog](docs/BACKLOG.md) · [Licensing](THIRD_PARTY.md)

Independent Android/JVM code is provided under Apache-2.0. The separately distributed optional KOReader plugin is AGPL-3.0-or-later; its licence is included in its directory. Runtime dependency licences remain their own. No Reliure, Calibre, KOReader, Calibre Companion or KOReaderSync source/artwork is included.

## New in 0.3.0: photographed books and Hardcover Want to Read

In **Library → Discover · Want to Read**, take a cover photo using your installed camera app or choose an existing photo. Folio runs bundled Latin-script OCR on the phone (no photo upload), corrects camera EXIF orientation, and lets you edit the recognised title/author before searching. Stylised covers, glare, unusual scripts and cover blur can reduce OCR accuracy; enter text manually when needed. OCR is not visual artwork recognition or an ISBN barcode scanner.

Confirm an Open Library candidate to save a local Want to Read entry, with a downloaded catalogue cover or your photo when available. It appears alongside EPUBs in the unified Collection and cannot be uploaded to Kindle until an EPUB is attached. Same-catalogue duplicate additions are ignored. Saved entries remain offline and are included in backups.

Tap **Connect Hardcover** and obtain a personal API token at <https://hardcover.app/account/api>. Enter it in the app, not in chat or a source file. Folio verifies your account before encrypting the token using Android Keystore. Tokens are excluded from Folio backups and Android backup is disabled. Disconnect removes the token and retains local entries.

Once connected, you can search directly on Hardcover from the recognised text, or choose **Match on Hardcover** on an existing local entry. Confirm the title and authors yourself: this release links the Hardcover **book/work**, not a specific edition. Matching only saves a local link. **Add to Hardcover Want to Read** is a separate explicit online action. New entries use your Hardcover account privacy setting. If that book is already in your Hardcover library, its status, progress and privacy are retained. Retry after a failed/lost response reads the remote entry before attempting insertion. There is no automatic background publisher or offline mutation queue in this release.

**Search Anna’s Archive** opens a URL-encoded title-and-author query at `https://annas-archive.pk/search?q=...` in your external browser. Folio does not scrape this website, follow its download links, or retrieve EPUBs. Website availability is external to Folio. Files obtained separately can be imported through the existing picker/share sheet. On a saved Want to Read entry, **Attach an imported EPUB** lets you confirm an existing local book; matching title and author is only a suggestion. Attachment does not change the EPUB’s metadata or reading status.

### Verification boundary

The 0.3.0 API uses independently written GraphQL requests checked against Hardcover’s published schema. Automated response fixtures cover book search, account verification, existing-entry preservation, API errors and retries. No personal Hardcover token, Android phone, emulator or physical Kindle was available for live end-to-end validation. Camera/OCR, Keystore, browser launch and authenticated Hardcover writes require acceptance testing on your phone. Existing SFTP and library test fixtures are retained. Hardcover progress publishing and Folio collection → Hardcover list mapping remain planned; this release implements **Want to Read**.


## New in 0.3.1: setup using just your phone and Kindle

No computer, terminal, Termux, USB cable or generated login key is needed for temporary access:

1. Connect the Kindle to your phone hotspot or the same Wi-Fi as your phone.
2. In KOReader → Tools → Network → SSH server, stop the server if running, turn off **Login with key only**, enable **Login without password (DANGEROUS)**, then start SSH. Note its displayed IP and port (normally 2222).
3. In Folio → Kindle → Configure, enter that IP. Keep port 2222 and username root unless your device shows otherwise.
4. Select **KOReader password-free**. Fresh setup selects it and uses `/mnt/us/documents`, the normal existing Kindle documents folder. Existing saved directories/authentication are retained on app update.
5. Tap **Read Kindle fingerprint**. Confirm the dialog’s address matches KOReader and tap **Trust this Kindle**. No password is sent, no login is attempted and no files are accessed during this handshake. The network-observed key is not independently verified; confirming it is your initial trust decision.
6. **Save pairing**, then **Connect & preview sync**. Review the proposed uploads and **Sync**. No password/key file entry is required. The optional KOReader bridge plugin is not needed for EPUB uploads.
7. Switch SSH off in KOReader when done. Password-free access exposes your device to other clients on that network while the server runs, even though Folio’s own connection still pins the host key and uses encrypted SSH/SFTP.

Folio also retains normal **Password** and **Key** modes. Switching a saved pair to password-free removes its stored SSH password/private-key material; enter the password or select your original private key to switch back. This setting does not remotely change KOReader’s authentication configuration. There is no automatic fallback from a rejected key/password to password-free mode.

If the host key changes, transfers fail. Check the Kindle IP and use **Check Kindle fingerprint**; Folio shows the changed key for explicit replacement confirmation. Changing the host/port while reviewing a key disables trust of that stale observation. Dismissing setup discards pending results. Host/port changes alone do not change your device identity when its trusted key, username and book folder stay the same.

If KOReader rejects password-free authentication, confirm key-only login is off and restart its server after enabling Login without password. The app uses a non-secret dummy SSH password because KOReader’s patched mode accepts any password. It cannot bypass a normally authenticated SSH server.

If you use the optional Folio KOReader bridge, configure its folder to match Folio’s selected book folder (for fresh setup, `/mnt/us/documents`); the plugin’s older default is `Books/Folio`.

## New in 0.4.0

**Hardcover profile:** Open a book → Update Hardcover reading. Connect using a personal token in the app, reload tracking, and confirm its Hardcover match if needed. Tap Mark Currently Reading, or choose a page-count edition and enter a page. Percentage estimate converts against that chosen edition; it is not exact EPUB pagination. Use KOReader snapshot offers previously fetched device progress with its retrieval time and revision warning. Review and confirm before publishing. New reading sessions and progress decreases need explicit consent. Conflicting remote changes require reload. Ratings, reviews, privacy and completed read history are retained. No automatic publishing or collection/list mapping is implemented. Authenticated acceptance still requires your account on a phone; never send a token in chat.

**Transfers:** After comparison, uploading persists a job and starts an Android dataSync foreground service. You can leave the activity while Android permits that service; the app shows book/byte progress and Cancel. Cancellation finishes an in-progress publication before stopping. An interrupted or failed job offers explicit Resume for remaining files, rechecking current local revisions and remote bytes. Completed books are retained. Jobs are not automatically restarted after process death or force-stop. Each run is bounded to 30 minutes; Android may impose additional quotas/OEM restrictions. Notification permission is optional; denying it does not disable a user-started transfer. Preview, metadata lookups, reading retrieval and collection delivery remain screen-scoped operations. Backups exclude transfer queues and credentials.

Install this APK over 0.3.1 to preserve app data. Export a backup first for important libraries. The user confirmed the 0.3.1 phone-to-Kindle workflow; 0.4 service lifecycle, folder UI, migration and authenticated Hardcover updates need physical-device acceptance.

## New in 0.5.0

A cohesive usability pass preserves the existing engines while improving navigation state, library search recovery, focused collection creation, sticky metadata save and draft protection, contextual book actions, phone-only reading activity, shared Hardcover connection screens, pairing step order, matched device-book titles, collapsed advanced sync options and consistent colours/components. The KOReader percentage shortcut now converts its stored 0–1 fraction to a 0–100 percentage before estimating Hardcover pages. See [the UX audit](docs/UX_PASS_0.5.md). No physical visual acceptance or authenticated Hardcover test is claimed.

## New in 0.6.0

Four destinations now match the core jobs: Collection, Lists, Kindle and Reading, with Settings behind the gear. Book details exposes Send to device and Update progress immediately. A safe single-book send compares and queues the file without requiring navigation to a separate preview; pairing can resume that request. Progress review includes a per-book Kindle refresh in the same Hardcover sheet. Collection tiles/rows show derived file, reading and confirmed-link signals. On Kindle means last verified matching revision; Hardcover linked does not claim cloud progress was published. This historical navigation release is extended by the unified Collection, reviewed Hardcover library import and catalogue-only list memberships in 0.7. See [the product flow](docs/PRODUCT_FLOW_0.6.md).

## Unified collection workflow

Collection → Add books → Import Hardcover library → select loaded books → optionally review EPUB matches → Review import → Import to phone. Unconnected users can connect through the same account dialog and retry import. Pages load in groups of 50. No profile import publishes changes.

Collection → Refine → Needs EPUB → book → Search Anna’s Archive (external browser) → Attach EPUB from phone → inspect title/author → Confirm attachment → Send to device. Choose an EPUB already in Folio to attach an existing edition. Cancelled attachment leaves an imported EPUB separate. Named manual list membership follows the confirmed file association. See [0.7 scope and acceptance](docs/UNIFIED_LIBRARY_0.7.md).