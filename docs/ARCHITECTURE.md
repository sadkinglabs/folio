# Architecture and sync strategy

## Modules

* `core`: Android-independent Kotlin EPUB processing, identity utilities, sync planning and execution. JVM unit tests cover hostile EPUB input and fake-device failures.
* `transport`: JVM SSH/SFTP adapter, configuration and credentials model. Real localhost SFTP integration tests are opt-in through the supplied script.
* `app`: Android SAF/share intents, private managed storage, Room persistence, Android Keystore, ViewModel/Flow and native Compose screens.

Composition is explicit in `FolioApplication`; repositories do not depend on composables. IO is dispatched away from the main thread. Library mutations use a repository mutex; operations and connection changes use a ViewModel mutex. The journal is committed before each transfer transition.

## Storage

| Table | Identity and purpose |
|---|---|
| books | UUID internal identity; canonical metadata; original/current locations and SHA256; cover location; date added; local reading status |
| collections | UUID + unique user name; smart predicate field/value; inclusion in transfer scope |
| memberships | Book/collection composite key with cascading foreign keys |
| transfers | Book/device composite key; actual remote path, verified revision hash, completion time |
| sync_jobs / sync_tasks | Persistent user-started batches and ordered immutable revision/path snapshots, verified completion counts and recoverable state |
| wishlist | Catalogue UUID, confirmed Hardcover work link and optional attached EPUB UUID; not a transfer source |
| sync_journal | Operation UUID, book/device IDs, state, time and safe error text |
| reading | Book/device composite identity; exact pointer, percentage, device status/rating, source sidecar and document checksums, retrieval time |
| annotations | Hash identity of book/device and length-prefixed anchor/text/note/date; immutable observations retained across refreshes |
| device_statistics | Device identity, consistent export timestamp, total seconds, bounded daily aggregates and retrieval time |

Authors, identifiers and tags are JSON string lists in schema v1. Series has name and validated ordinal. A normalized author/series/tag model and FTS index are prioritized before very large library support. Room schemas v1 and v2 are exported. MIGRATION_1_2 adds collection rule/policy columns and reading, annotation and statistics tables, preserving v1 books, memberships and transfers. No destructive fallback is enabled. A host SQLite check compares the migrated schema against exported v2; Android migration tests are compiled and still require device execution.

`files/library/<UUID>/original.epub` is immutable. Each edit produces a new revision and optional separate cover. Import streams to a staging file, verifies/extracts, moves to its book directory, then inserts the row. A database insert failure removes the import. Editing writes and parses a staged ZIP, renames it, then updates the row. A crash between file publication and DB commit can leave an orphan revision, but the database still references the previous valid file. Startup orphan reconciliation and disk fsync durability remain release-hardening tasks.

Exact file hashes detect duplicates; they are not book identities. Different files with equal title/author remain distinct editions. Package unique-identifier is preserved during metadata edits. Secondary identifiers can be edited. No DRM removal is supported; encrypted/obfuscated EPUB modification is rejected.

## Independent sync domains

1. **Files, implemented:** bounded remote inventory, exact-byte comparison and new-file verified upload. Stable phone UUID and device path are separate values. Existing identical files under arbitrary filenames can be recognized using size then full hash. Cached transfer records represent verified historical presence, not a continuously live connection.
2. **Library metadata, partially implemented:** phone canonical fields are edited into a new EPUB revision. Phone collection membership is persistent. The optional bridge accepts versioned additive collection requests and uses KOReader ReadCollection APIs after Kindle confirmation. Existing membership is never removed. Replacement of transferred EPUBs remains deferred.
3. **Reading data, implemented read-only:** adjacent sidecars are read twice with bounds and accepted only if bytes agree. A restricted Lua literal parser rejects executable expressions, deep tables, duplicate keys and oversized input. Reading pointers and source-document hashes stay attached to their transferred revision; no position writes occur. Latest snapshots can move backwards; phone status is independent. Annotation changes add observations and missing annotations never imply deletion. Statistics arrive as JSON exported inside a consistent reader-side SQLite read transaction, not a raw live database copy.

## Manual sync sequence

One-tap **Sync missing books** compares the library first, evaluates enabled shelf rules using fresh presence and sends only safe new-file actions in that scope. Null scope means all books; empty scope means no actions. Collection browsing uses cached verification. Conflicts remain visible and untouched. The separate preview path lets users review every action before pressing Sync.

`Preview → connect with pinned key → inventory → compare → show upload/present/conflict → explicit Sync → stage → readback hash → publish → readback hash → record transfer`

Device identity uses host fingerprint + SSH user + configured folder, independently of changing IP/port/authentication mode. Existing v1 transfer records are rekeyed for the saved pairing at startup. The preview is bound to that identity; every connection still checks the current pin and credentials. Execution checks local revision hash and remote destination again. A retry after a lost completion response compares destination bytes and completes without another upload. A partial temporary file is removed best-effort; completed books remain. Conflicts block only their affected books; other uploads may proceed. No remote deletion or general overwrite operation is exposed.

The journal states are PLANNED, TRANSFERRING, VERIFYING, PUBLISHING, COMPLETE, FAILED and INTERRUPTED. Startup marks unfinished operations interrupted. Preview reconciles actual remote bytes, so an interrupted publish can be recovered. The journal currently lacks remote temporary filenames; process-death leftovers may require manual cleanup of `.folio-*.part` after ensuring no transfer is running. Automatic safe cleanup is backlog work.

SFTP rename behavior varies. New destination checks and SFTP v3 rename provide the initial safety strategy, but no cross-client transaction lock is claimed. A concurrent external writer could race the final check on servers whose rename overwrites; a cooperative device plugin can provide stronger no-replace publication. Do not use this build to manage the same destination concurrently from multiple tools.

## Security

* No shell commands from the app. Explicit configured root; reject traversal, final and parent symlinks and excessive remote depth/count/size.
* Validate ZIP paths, duplicate entries, expanded size/count and XML limits. Reject DOCTYPE and external entities; never render EPUB HTML in a WebView or execute Lua.
* Host fingerprint is supplied through an independently trusted route. A custom host-key repository rejects every other key before auth.
* Android Keystore AES-GCM protects passwords/private keys at rest; private app preferences contain encrypted ciphertext. Android automatic backups are disabled; portable library backups deliberately omit credentials and device transfer claims.
* No analytics or content/credential logging. Error messages are shown in-app; SSH exception text is sanitized.
* Dynamic image sampling bounds decode cost; replacement images are validated for dimensions/pixels. A full image-format fuzzing audit remains pending.

## Supported background behavior

Uploads use an app-scoped coordinator and user-started `dataSync` foreground service. Room schema 4 stores ordered revision snapshots in `sync_tasks` under `sync_jobs`. Enqueue is atomic; only one unfinished job is accepted. State moves QUEUED → RUNNING → COMPLETE, FAILED or PAUSED; cancellation moves through CANCELLING to CANCELLED. On process recovery queued/running jobs become PAUSED and cancelling jobs become CANCELLED. Verified task records survive. Only an explicit user Resume reconnects, and each pending task checks the library's current revision and destination bytes. The repository mutex prevents changing source files during execution. The independent JVM BatchTransfer exercises cancellation and revision checks.

Cancellation is observed while hashing/uploading/verifying and between files; after entering publication the current file rename, readback and persistence finish before cancellation is observed again. Interrupted rename responses reconcile by exact destination hash on retry. The current app does not journal remote temporary filenames, so process death may leave hidden staging files. No deletion, forced overwrite, background reconnect or guaranteed OS survival is claimed.

A run has a 30-minute bound, while Android 15+ can limit dataSync service time to six hours per 24 hours. Service timeout stops execution; process-death recovery pauses. Android notification permission is requested at the first user upload, but denial still permits foreground execution under platform rules. Activity recreation does not run recovery against a still-live service. Reading retrieval/preview/lookups remain ViewModel operations.

WorkManager 2.11.2 was evaluated. Android 16 long-running foreground workers consume JobScheduler quota; official Android guidance suggests a direct foreground service for appropriate user-triggered operations. This avoids adding a scheduler for transfers that must never reconnect without a user action. References: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running and https://developer.android.com/develop/background-work/services/fgs/timeout.

## Cooperative KOReader bridge, implemented but awaiting hardware acceptance

`koreader/folio.koplugin` is independently implemented and separately licensed AGPL-3.0-or-later. The Android APK exchanges only versioned JSON over pinned SFTP. It does not link or include the plugin or KOReader implementation.

Collections: the phone verifies every selected book's actual remote hash before publishing a unique `.folio-collections-<UUID>.json` request. The plugin reads the newest request from its user-configured folder, validates paths through realpath against that root and builds a full plan before showing confirmation. It adds members to `Folio: <name>` collections through ReadCollection and writes using its API. Reapplying is idempotent; existing memberships and unrelated collections are retained. This is additive collection delivery, not exact mirroring or automatic bidirectional conflict resolution. There is no application acknowledgement channel yet; the phone reports that application is pending in KOReader.

Statistics: a device-side user action opens the existing statistics database, enables query_only, starts a read transaction, queries total duration and up to 366 daily aggregates, closes the connection and publishes JSON by same-directory rename. The phone double-reads, validates and caches that export. Current-book buffered data must be flushed by closing the book first. The plugin has independent Lua/SQLite tests with mocks for host APIs, but installed-version API compatibility still needs KOReader testing.

Central/hashed sidecar retrieval, stronger snapshot generations, automatic request processing, per-device capability negotiation, exact collection mirroring and safe EPUB replacement are deferred.

## Portable backups

A versioned JSON manifest and SHA256 ledger cover metadata and every archived resource. Archive extraction is staged under a unique private folder, with path/count/expanded-size bounds and no symlinks generated. EPUB validation and identity conflict checks precede publication. The repository mutex and Room transaction provide a coherent export and additive restore. Original/current paths are remapped, identical originals can map to existing UUIDs, and existing metadata is retained. Historical revision resources are included. Restored new shelves have sync disabled. Transfer claims, sync journal and credentials are intentionally excluded. A hard process kill during directory publication can leave an orphan folder; startup recovery is still a P0 task.

### Schema 3 / discovery extension

`wishlist` is a separate aggregate, not a file-less `books` row. No sync planner, reading pointer or EPUB revision code handles wish entries. The confirmed catalogue key and optional Hardcover book ID are distinct from UUID and optional imported-book UUID. `DiscoveryViewModel` owns the user-triggered photo/catalogue/account flow; `HardcoverClient` encapsulates bounded authenticated GraphQL and is fixture-injectable; a separate Keystore-backed credential store holds the account token. Existing library modules and file sync policy remain intact. Backup format 1 accepts an optional wishlist section and retains old backups. See `DISCOVERY.md` for protocol details, identity boundaries and hardware acceptance checks.


### Phone-only pairing (0.3.1)

`HostFingerprint.read` performs SSH key exchange with a temporary HostKeyRepository that records the negotiated host key and deliberately returns CHANGED under StrictHostKeyChecking=yes. It disconnects before user authentication; no credential, SFTP channel or filesystem access is involved. The observed address, port, algorithm and SHA256 fingerprint are surfaced in a confirmation dialog. Observation never writes pairing state. Address/port must still match for acceptance; a differing existing pin requires explicit replacement. Generation tracking discards results of a dismissed/superseded probe.

Transport shares the exact fingerprint calculation between probe and authenticated SFTP. All three authentication modes require the same strict host-key match. KOReader password-free maps to ordinary SSH password authentication with a public dummy value understood by KOReader’s patched `-n` server; it is not SSH `none` authentication or an authentication bypass. Choosing it removes old encrypted credential material from DeviceSettings, and credential loading needs no key/password. Normal Password and Key modes are retained and no failure causes an automatic downgrade. Fresh Kindle setup defaults to the existing documents directory, without creating remote directories. Room schema remains 3; previously stored configuration is preserved.

## Hardcover updates

Only the authenticated account's library rows are read. Status writes touch only status_id=2; new rows use account privacy. Progress uses the documented DatesReadInput progress_pages and a user-selected page-count edition. There is no invented percentage setter. A reviewed conversion from KOReader's cached percentage is explicitly an estimate, retains source revision/time in the UI, and never writes back to KOReader. Read ID, user-book ID, pages and edition are checked again before mutation. Multiple unfinished reads are rejected; a new session/decrease needs consent. Mutation errors and missing acknowledgements are failures; target state is reconciled on retry. Remote races remain possible between API calls because the service offers no compare-and-swap transaction for this workflow. All 11 distinct operations validate against the published schema; authenticated authorization remains an acceptance task.

A confirmed local work mapping currently reuses the attached wishlist entry. The same catalogue entry cannot silently be reassigned to a second EPUB; the app asks users to use the linked EPUB's details. Dedicated per-edition tracking mappings are backlog work. Automatic publication, finished-date handling, list/collection mapping and an offline publication queue are deferred.

## 0.6 flow adapters

BookSignals derives presentation from independent file transfer, queue, KOReader and local book observations. It never persists a merged reading status. An unfinished-task Room query feeds queue badges; schema 4 is unchanged. Single-book send reuses the existing selected-book comparison and persistent queue. Per-book reading retrieval scopes the existing checksum/sidecar/annotation pipeline. Four destinations separate catalogue/list creation, owned EPUB organisation, device operations and reading; settings is a contextual route. Full catalogue/edition/link consolidation and profile import remain designed next steps in PRODUCT_FLOW_0.6.md, not implicit behavior.

## 0.7 extension

See [Unified library](UNIFIED_LIBRARY_0.7.md) for schema 5, account-isolated observations, catalogue-only list memberships, attachment invariants and backup format 2. Transfer inputs still come exclusively from actual EPUB records.

## 0.7.1 local removal

Schema remains 5. Local deletion uses a fresh identity plan under the repository mutex and one database transaction; app-owned files are cleaned after commit. Pending sync tasks block deletion. No SFTP or Hardcover delete is issued. See [actions/removal](ACTIONS_0.7.1.md).

## 0.8 extension

Schema 6 introduces removal markers and rich catalogue metadata/local status. Active queries exclude removal markers; files and reader observations remain for restore. Mixed batch changes and attachment correction share the repository mutex and Room transactions. See [everyday workflows](BASICS_0.8.md) for backup format 3 and FileProvider/SAF boundaries.
