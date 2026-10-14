# Changelog

Release notes moved out of the README. Newest first. Detailed per-release design notes and validation records are in [docs/history](docs/history/).

## 0.10.1 — Reader startup fix

The 0.10.0 reader tried to mount its fragment by container ID from AndroidView's factory, before that container entered the Activity view tree. This can throw `IllegalArgumentException: No view found for id … reader_container`. The hotfix uses FragmentContainerView and waits for an attached, resumed host before mounting. It also declares the directly used Fragment KTX dependency explicitly and reports EPUBs without readable chapters as an opening error. Room schema 7 and the signing key are unchanged.

Robolectric regression tests reproduce the old failed ID lookup, verify mounting after attachment and verify waiting through background loading without mounting twice. Android UI instrumentation additionally waits for the actual navigator view, rather than accepting toolbar text as proof of a mounted reader. Robolectric is test-only; it does not validate real EPUB rendering in Android WebView.

## 0.10.0 — Reader and paired folders

Open an EPUB-backed book → **Read on phone**. Readium 3.4.0 renders EPUB pages; navigation, contents, bookmarks and appearance controls are native Compose. Your full locator is saved per SHA-256 EPUB revision in Room 7. Light/paper/dark/system appearance, serif/sans text size and scrolling preferences persist. Phone progress is an explicit selectable source in Hardcover's existing review-and-publish flow. Readium needs a WebView solely for book content; this exception was approved for the reader. Local reading needs no account or network.

**Settings → Paired folders → Choose phone folder** grants access through Android's system picker and imports EPUBs recursively. Use the library's top **Sync** icon to compare that tree with the configured Kindle folder. New EPUBs travel in either direction, retaining subfolders; downloaded books enter the same library. Hashes verify files and retries reconcile completed copies. Same-path different editions are kept and reported for review. Android 11+ restricts choosing the Downloads root; choose a dedicated Books subfolder.

**Settings → Install KOReader companion** installs the bundled `folio.koplugin` over the existing pinned SSH connection. The default assumes KOReader's existing `/mnt/us/koreader/plugins`; change it for your installation. Installation stages the whole directory and retains the old version. Restart KOReader once, configure **Folio bridge → Configure Folio book folder** to the same Kindle folder, and enable **Continue from Folio automatically**. No computer or terminal is required. Manual review remains available.

Folder Sync also retrieves saved adjacent KOReader sidecars and reconciles positions against observed baselines. One changed side can be queued for the other; both changed sides require review. Incoming Kindle anchors resolve when you next open the phone reader. Outgoing requests apply when KOReader opens that book, only if its saved position still matches the request's expected state. Pending is not the same as applied. Close books in KOReader before syncing so current positions are saved.

**Current limits:** this is an additive paired-folder release, not a completed general-purpose mirror. Folder scanning happens on Refresh/Sync, not continuously in the background. EPUBs outside the selected phone tree use the existing Send action. Edited/replaced editions require review and are not overwritten automatically; export an edited revision to a new filename for now. Deletions, renames, empty folders, highlights and list memberships are not bidirectionally mirrored. Folder operations are lifecycle-owned and interrupted work is reconciled on retry; the existing standalone upload queue retains its foreground-service behavior. Staging is limited to 1 GiB per run and 10,000 entries/12 folder levels. SAF publishing depends on provider rename behavior, so a local folder is preferred. A source disappearing is not a deletion instruction.

The DOM converter supports classical/explicit KOReader V2 pointers; unsupported DOM versions, boxing wrappers, fixed layout and unresolved anchors fail without percentage guesses. The same DRM-free EPUB bytes are required. Cross-device passage accuracy and Android rendering need physical acceptance tests; fixture tests do not establish that validation. See [reader and sync implementation](docs/history/READER_0.10.md).

## 0.7 — Unified collection

Collection → Add books → Import Hardcover library → select loaded books → optionally review EPUB matches → Review import → Import to phone. Unconnected users can connect through the same account dialog and retry import. Pages load in groups of 50. No profile import publishes changes.

Collection → Refine → Needs EPUB → book → Search Anna’s Archive (external browser) → Attach EPUB from phone → inspect title/author → Confirm attachment → Send to device. Choose an EPUB already in Folio to attach an existing edition. Cancelled attachment leaves an imported EPUB separate. Named manual list membership follows the confirmed file association. See [0.7 scope and acceptance](docs/history/UNIFIED_LIBRARY_0.7.md).

## 0.6.0

Four destinations now match the core jobs: Collection, Lists, Kindle and Reading, with Settings behind the gear. Book details exposes Send to device and Update progress immediately. A safe single-book send compares and queues the file without requiring navigation to a separate preview; pairing can resume that request. Progress review includes a per-book Kindle refresh in the same Hardcover sheet. Collection tiles/rows show derived file, reading and confirmed-link signals. On Kindle means last verified matching revision; Hardcover linked does not claim cloud progress was published. This historical navigation release is extended by the unified Collection, reviewed Hardcover library import and catalogue-only list memberships in 0.7. See [the product flow](docs/history/PRODUCT_FLOW_0.6.md).

## 0.5.0

A cohesive usability pass preserves the existing engines while improving navigation state, library search recovery, focused collection creation, sticky metadata save and draft protection, contextual book actions, phone-only reading activity, shared Hardcover connection screens, pairing step order, matched device-book titles, collapsed advanced sync options and consistent colours/components. The KOReader percentage shortcut now converts its stored 0–1 fraction to a 0–100 percentage before estimating Hardcover pages. See [the UX audit](docs/history/UX_PASS_0.5.md). No physical visual acceptance or authenticated Hardcover test is claimed.

## 0.4.0

**Hardcover profile:** Open a book → Update Hardcover reading. Connect using a personal token in the app, reload tracking, and confirm its Hardcover match if needed. Tap Mark Currently Reading, or choose a page-count edition and enter a page. Percentage estimate converts against that chosen edition; it is not exact EPUB pagination. Use KOReader snapshot offers previously fetched device progress with its retrieval time and revision warning. Review and confirm before publishing. New reading sessions and progress decreases need explicit consent. Conflicting remote changes require reload. Ratings, reviews, privacy and completed read history are retained. No automatic publishing or collection/list mapping is implemented. Authenticated acceptance still requires your account on a phone; never send a token in chat.

**Transfers:** After comparison, uploading persists a job and starts an Android dataSync foreground service. You can leave the activity while Android permits that service; the app shows book/byte progress and Cancel. Cancellation finishes an in-progress publication before stopping. An interrupted or failed job offers explicit Resume for remaining files, rechecking current local revisions and remote bytes. Completed books are retained. Jobs are not automatically restarted after process death or force-stop. Each run is bounded to 30 minutes; Android may impose additional quotas/OEM restrictions. Notification permission is optional; denying it does not disable a user-started transfer. Preview, metadata lookups, reading retrieval and collection delivery remain screen-scoped operations. Backups exclude transfer queues and credentials.

Install this APK over 0.3.1 to preserve app data. Export a backup first for important libraries. The user confirmed the 0.3.1 phone-to-Kindle workflow; 0.4 service lifecycle, folder UI, migration and authenticated Hardcover updates need physical-device acceptance.

## 0.3.1 — Phone-only Kindle setup

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

## 0.3.0 — Photographed books and Hardcover Want to Read

In **Library → Discover · Want to Read**, take a cover photo using your installed camera app or choose an existing photo. Folio runs bundled Latin-script OCR on the phone (no photo upload), corrects camera EXIF orientation, and lets you edit the recognised title/author before searching. Stylised covers, glare, unusual scripts and cover blur can reduce OCR accuracy; enter text manually when needed. OCR is not visual artwork recognition or an ISBN barcode scanner.

Confirm an Open Library candidate to save a local Want to Read entry, with a downloaded catalogue cover or your photo when available. It appears alongside EPUBs in the unified Collection and cannot be uploaded to Kindle until an EPUB is attached. Same-catalogue duplicate additions are ignored. Saved entries remain offline and are included in backups.

Tap **Connect Hardcover** and obtain a personal API token at <https://hardcover.app/account/api>. Enter it in the app, not in chat or a source file. Folio verifies your account before encrypting the token using Android Keystore. Tokens are excluded from Folio backups and Android backup is disabled. Disconnect removes the token and retains local entries.

Once connected, you can search directly on Hardcover from the recognised text, or choose **Match on Hardcover** on an existing local entry. Confirm the title and authors yourself: this release links the Hardcover **book/work**, not a specific edition. Matching only saves a local link. **Add to Hardcover Want to Read** is a separate explicit online action. New entries use your Hardcover account privacy setting. If that book is already in your Hardcover library, its status, progress and privacy are retained. Retry after a failed/lost response reads the remote entry before attempting insertion. There is no automatic background publisher or offline mutation queue in this release.

**Search Anna’s Archive** opens a URL-encoded title-and-author query at `https://annas-archive.pk/search?q=...` in your external browser. Folio does not scrape this website, follow its download links, or retrieve EPUBs. Website availability is external to Folio. Files obtained separately can be imported through the existing picker/share sheet. On a saved Want to Read entry, **Attach an imported EPUB** lets you confirm an existing local book; matching title and author is only a suggestion. Attachment does not change the EPUB’s metadata or reading status.

### Verification boundary

The 0.3.0 API uses independently written GraphQL requests checked against Hardcover’s published schema. Automated response fixtures cover book search, account verification, existing-entry preservation, API errors and retries. No personal Hardcover token, Android phone, emulator or physical Kindle was available for live end-to-end validation. Camera/OCR, Keystore, browser launch and authenticated Hardcover writes require acceptance testing on your phone. Existing SFTP and library test fixtures are retained. Hardcover progress publishing and Folio collection → Hardcover list mapping remain planned; this release implements **Want to Read**.
