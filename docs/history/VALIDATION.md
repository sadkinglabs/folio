# Validation record — Folio 0.10.1 reader startup hotfix

14 October 2026. Formatting/check, app unit tests, debug APK, Android test APK and lint completed successfully after the final source changes. No Kotlin edits followed the passing run.

- 95 app tests passed, no failures/errors/skips. Includes three executed Robolectric/AndroidX framework regressions: old unattached-container ID lookup reproduces the startup exception; the new host mounts after attachment; background loading waits for resume and mounts once.
- Robolectric is test-only. These tests use actual AndroidX FragmentManager with Android framework shadows, not a physical Android WebView renderer.
- 38 Android instrumentation methods compiled; zero executed. The reader UI test now waits for an actual attached navigator view. Physical phone/Kindle, EPUB page rendering and rotation acceptance remain unverified.
- Lint: zero errors, 23 warnings. Existing warnings retained.
- APK version 0.10.1/code16; unchanged signer `ed12b075d6dba2b09b8246978903f679809725b43f24821a01e6833c85011d39`; APK SHA256 `41a7763f454a8f35db4023f797c6062255a3850af9031b681ab468809f936333`.
- Exported Room schema 7 was byte-compared to 0.10.0 and is unchanged. Existing data/signing identity retained. Core/transport implementations unchanged; their previous 61/17 passing tests were not rerun for this app-only hotfix.
- Readable-EPUB spine validation now prevents the navigator's first-link assertion from crashing on a malformed imported book. Activity recreation starts from the current ViewModel locator. These guards compile; their full real-device acceptance remains pending.

## Historical validation

# Validation record — Folio 0.10.0

Executed 14 October 2026. Final source was formatted, the app rebuilt, and the localhost SFTP check script completed with exit 0. Debug and Android-test APKs assembled. Kotlin compilation, unit tests and Android lint passed. No app source edits followed that check; documentation and packaging only.

- 170 JVM tests passed, zero failures/errors/skips: core 61, transport 17, app 92. Includes 12 real local SSH/SFTP scenarios, the staged companion installer/backup scenario, folder/position conflict decisions and phone digest/anchor handling.
- Eight DOM interop fixtures passed. Lua syntax, 20 validation cases, actual companion auto-apply control flow with runtime stubs and a WAL statistics export fixture passed. These do not establish real crengine/Kindle behavior.
- SQL migrations 1→7 match exported Room schemas; revision-scoped phone anchors and permanent-removal cascades verified in SQLite. Actual Android Room upgrade and SAF integration remain unexecuted.
- 38 Android test methods compiled into the test APK; zero executed. No usable Android emulator or physical Kindle is available. No rendered Android screenshots, device accessibility, exact cross-device passage accuracy, authenticated Hardcover calls or real Kindle installation are claimed.
- Lint: 0 errors, 23 warnings, 0 hints. Existing toolchain/component/platform-target warnings remain; reader rendering/security controls require device acceptance.
- Version code 15/name 0.10.0; unchanged signer `ed12b075d6dba2b09b8246978903f679809725b43f24821a01e6833c85011d39`; APK SHA-256 `4f47c0ed1bd4cb86204fd7dc40de162889fcab947d6d6cc1224461ed80fc58e5`.
- APK assets were byte-compared with current Readium interoperability bundle and all four companion source/license files.

[Implementation and limitations](READER_0.10.md). Structured results: [validation-results.json](validation-results.json).

## Historical records

# Validation record — Folio 0.9.0

Release record: 13 October 2026, Europe/Madrid (build/checks ran 12 October UTC).

* Final clean app build, formatting, full host test harness, debug APK, Android test APK and Android lint passed. Final full check: 2m04s. Only documentation/packaging changed after that passing run.
* 156 JVM tests passed with zero failures, errors or skips: core 52, transport 16, app 88. Includes real localhost SSH/SFTP adapter tests and eight new presentation/search cases. The 5,000-record case checks search correctness; it is not a device performance benchmark.
* All 36 Android instrumentation/UI methods compile. **Zero executed.** Native validation was attempted, including installed API 36 and API 30 images. No /dev/kvm was available. API 36 did not become usable; API 30 booted slowly, an input command timed out, and APK installation failed with `cmd: Failure calling service package: Broken pipe (32)`. A legacy-install retry was blocked by a pending emulator snapshot. A preflight screenshot showed Android's Pixel is starting screen. No Folio screen was rendered/visually accepted, and no app screenshots are presented.
* Android lint: zero errors, 18 warnings (existing ExifInterface/KTX/target-level recommendations). Existing component/test API and toolchain deprecations remain. Cached Kotlin builds reported duplicate overload diagnostics; unchanged sources passed clean builds. Incremental cache invalidation is recorded as tooling debt.
* Actual SQLite migration SQL 1→6 matches exported Room schemas and retains identities/defaults/FKs/indexes. Active/removed queries, restore and permanent-removal boundary scripts passed. These host checks do not establish Android Room runtime or filesystem acceptance.
* KOReader bridge: Lua syntax plus nine fixture scenarios and read-only WAL SQLite statistics export passed. No physical KOReader runtime validation.
* All 13 distinct Hardcover client operations (16 occurrences) match the official published schema, SHA256 `b1f4ea98576f5f8a91b6545533cda8f5be2cd33114c8df1e133e1d898fa5cd25`. No authenticated API call or progress publication was made.
* APK version 0.9.0/code 14, signature verified; unchanged signer SHA256 `ed12b075d6dba2b09b8246978903f679809725b43f24821a01e6833c85011d39`. APK SHA256 `2d22c641f121b21101cf180a28fe5bbbdacfbeacd0eaf8a9b7096e1f0665fe2c`.
* Room schema stays at 6. Package ID, development signing identity, book IDs, storage paths, credential format and integration safety policies are preserved. No physical populated-library upgrade, Kindle connection/hotspot transfer, authenticated Hardcover update, TalkBack, large-font or tablet acceptance is claimed.

The emulator-only ProductJourneyTest supplies a real MainActivity/Room/metadata-revision/SAF journey and captures synthetic-book native screens when run in a working disposable Android 10+ emulator. Its guard refuses physical hardware and non-fixture library records. DiscoveryCommitTest covers post-commit callback cancellation preserving artwork. Compilation of those tests is distinct from their execution.

See [implementation](refinement/IMPLEMENTATION.md), [audit](refinement/AUDIT.md), [design](refinement/DIRECTION.md), [remaining acceptance](BACKLOG.md) and machine-readable validation-results.json.

## Historical records

# Validation record — Folio 0.8.3

Executed 12 October 2026. Final clean app build, formatting/check, complete host tests, debug/test APK assembly and Android lint passed. Full check build took 13m27s with cold Android test/lint dependencies. No source edits followed the passing run; documentation and packaging only.

- 148 JVM tests passed, zero failures/errors/skips: core 52, transport 16, app 80. Five new cases cover owned/catalogue pinned-list membership, observed-state precedence, attachment retaining reading intent and linked-entry deduplication. Transport includes localhost SFTP integration.
- 34 Android test methods compile; none executed. New cases exercise pinned Want to Read and transfer settings in a sheet. List drilldown/back, independent search state, discovery/Hardcover handoff and visual accessibility require phone/emulator acceptance.
- Formatting passed. Lint: {'Warning': 18}; zero errors. Existing component/toolchain deprecations remain.
- APK version code 13/name 0.8.3; unchanged signer `ed12b075d6dba2b09b8246978903f679809725b43f24821a01e6833c85011d39`; APK SHA256 `82479c442437f2d567871451b8d3ed8d21664a1b4512b081eb7a71c74baab9f2`.
- Room schema 6 unchanged. Previously verified SQL migrations and GraphQL schema operations were not rerun for this UI change; query/database code remains unchanged. No real authenticated Hardcover call, physical phone/Kindle validation, emulator gestures or rendered-preview inspection is claimed.

See [Lists behavior and remaining acceptance](LISTS_0.8.3.md).

## Historical validation records

# Validation record — Folio 0.8.2

Executed 12 October 2026. Clean app build, formatting/check, full host tests, debug/test APK builds and Android lint passed in 2m05s. No source edits followed this successful run; documentation and packaging only.

- 143 JVM tests passed, zero failures/errors/skips: core 52, transport 16, app 75. Includes localhost SFTP integration.
- 32 Android test methods compile, none executed. Updated search/refine and retained-search tests target anchored search. Existing long-press list tests target the explicit segmented view control.
- Formatting passed. Lint: {'Warning': 19}; zero errors. Existing toolchain/component deprecations remain.
- Version code 12/name 0.8.2; unchanged signer `ed12b075d6dba2b09b8246978903f679809725b43f24821a01e6833c85011d39`; APK SHA256 `fa69a14d6d57d42ff2e6c08ce645aa9ea6fdb2a0c937c40a5168d342ed0c95c2`.
- Room schema and all integration policies unchanged. No authenticated Hardcover API calls, physical Android/Kindle checks, emulator gestures or Compose preview rendering were performed.
- Reliure reference screenshot was inspected along with the checked-out actual toolbar/grid source. Folio's new layout still needs visual acceptance on phone; compiling previews does not prove pixel or accessibility acceptance.

See [layout and reference audit](LIBRARY_POLISH_0.8.2.md).

## Historical validation records

# Validation record — Folio 0.8.1

Executed 12 October 2026. Clean app build, formatting/check, full host tests, debug/test APK builds and Android lint passed in 2m14s. No source edits followed the successful run; documentation and packaging only.

- 143 JVM tests passed, zero failures/errors/skips: core 52, transport 16, app 75. Includes localhost SFTP integration and seven new Hardcover metadata/cover safety cases.
- 32 Android test methods compile; none executed. New cases cover compact library controls, Refine/apply/reset and the Add menu. Real layout, keyboard, TalkBack, font scale and navigation acceptance remain pending.
- Formatting passed; lint has 19 warnings and zero errors. Existing deprecations remain. Room schema stays at 6; previous migration/host SQL verification applies to unchanged schema and DAO code.
- 13 distinct GraphQL operations (16 occurrences) conform to the official Hardcover schema SHA256 `b1f4ea98576f5f8a91b6545533cda8f5be2cd33114c8df1e133e1d898fa5cd25`. No authenticated API call or live cover download is claimed.
- APK version code 11/name 0.8.1; unchanged signer `ed12b075d6dba2b09b8246978903f679809725b43f24821a01e6833c85011d39`; APK SHA256 `3a36b282fbeade1ddc51509fdde598aa979f5b5f8d85f87ab3c6b1f024d3eb30`.
- No emulator, physical Android/Kindle validation or rendered-preview visual inspection was available. Title lookup and EPUB attachment require phone acceptance.

See [current flows and limits](LIBRARY_SPACE_0.8.1.md).

## Historical validation records

# Validation record — Folio 0.8.0

Executed 12 October 2026. Clean app build, formatter/check, complete host tests, debug/test APK builds and Android lint passed. Final check run: 2m06s. No Kotlin/source edits followed the passing run; documentation and packaging only.

- 136 JVM tests passed, zero failures/errors/skips: core 52, transport 16, app 68. Includes localhost SFTP fixture coverage and new manual metadata/legacy backup round trips, actionable import error mapping, catalogue edit/share action availability and explicit phone-status precedence.
- 29 Android test methods compile; none executed here. New repository tests cover remove/restore identity/files/notes, permanent-delete boundaries, detach plus mixed batch membership/status, queued-task rejection and offline creation/editing. New UI tests cover removed-book restore, per-file retry and list edits. Physical SDK/emulator/recipient-reader/SAF acceptance remains outstanding.
- Actual migration SQL 1→2→3→4→5→6 matches exported Room schemas including FKs/indexes/defaults, preserving original book UUIDs and records. Host SQLite executes shipped active queries to verify hidden/restore behavior, cached-note preservation and list deletion retaining books. Permanent-removal SQL still passes target/unselected/FK checks. These checks do not establish Android filesystem/runtime/UI acceptance.
- Formatting passed. Lint: {'Warning': 19}; no errors. Existing component/toolchain deprecations remain.
- APK signer verified unchanged: `ed12b075d6dba2b09b8246978903f679809725b43f24821a01e6833c85011d39`; version code 10/name 0.8.0; SHA256 `41fdca3da8bd7d542460dd29ae61c16e92aa8b4927960fd32ad62a5a0f9761e0`.
- SSH transfer and Hardcover operation documents are unchanged. No real authenticated Hardcover call, new physical Kindle/phone flow, instrumented test execution or preview rendering is claimed. All audited basic gaps have implementations; their actual device acceptance is distinct from this build/test result.

[Everyday workflows and limitations](BASICS_0.8.md) records portable backup format 3, recently removed scope, share-cache permissions, retry retention and pending cleanup work. Permanently deleted 0.7.1 records are not reconstructed by this update.

## Historical validation records

# Validation record — Folio 0.7.1

Executed 12 October 2026. Clean application build, formatting, complete host test run and Android lint passed. Final full check build: 1m52s. No source edits after the passing build; documentation and packaging only.

- 129 JVM tests passed with no failures/errors/skips: core 52, transport 16, app 61. Transport includes the 11 localhost SSH/SFTP fixture cases. Six new tests cover action availability, ownership-aware removal planning, linked catalogue cleanup, mixed selections and idempotent retry plans.
- Debug APK and Android test APK built. 21 Android test methods compile, including four new grid/list long-press, catalogue menu and confirmation UI cases; none executed here. Existing Android upgrade/backup/attachment tests remain compiled rather than device-validated.
- Formatting passed. Lint: {'Warning': 17}. No lint errors; existing native component/toolchain deprecations remain.
- Host SQLite executes the actual shipped local-removal DAO SQL, verifies target reading/annotation/transfer/journal cleanup, cascading memberships, preservation of unselected books and no dangling FKs. Full 1→2→3→4→5 migration checks also pass. Schema remains 5; Android runtime deletion and private-file cleanup require device acceptance.
- APK signing key verified unchanged: `ed12b075d6dba2b09b8246978903f679809725b43f24821a01e6833c85011d39`; version code 9/name 0.7.1; SHA256 `8196b51dac7b5f417cfedeaa1622d5a9deb0247febc49a7c0fd04d22392a78f1`.
- No physical Kindle, emulator gesture execution, authenticated Hardcover call or visual preview rendering is claimed. The removal subsystem does not call SSH/SFTP or Hardcover; root dialogs explicitly describe its local-only scope. See [action/removal behavior and limitations](ACTIONS_0.7.1.md).

## Historical validation records

# Validation record — Folio 0.7.0

Executed 12 October 2026. Clean app build and final full check run completed successfully in 2m41s after resolving stale incremental compiler outputs. No source edits followed this passing check run; documentation/packaging updates only.

- 123 JVM tests passed, zero failures/errors/skips: core 52, transport 16, app 55. Includes 11 localhost SSH/SFTP integration cases and 16 new unified-library/attachment/profile-query fixtures. Core unchanged outputs reused where Gradle found them current; app rebuilt from clean outputs.
- Debug APK and Android test APK built. All 17 Android test methods compile; none executed. No emulator or phone is attached (`adb devices` empty). New attachment/list/cache tests and backup/upgrade UI tests require device execution.
- Formatting passed. Android lint: {'Warning': 16}. Existing deprecation warnings remain; no lint errors.
- Actual migration SQL 1→2→3→4→5 matches exported Room schemas including columns/indexes/FKs, preserves existing books and membership records, rejects dangling catalogue memberships, verifies catalogue cascade behavior and account-isolated cloud records. This is host SQLite verification; Android Room runtime upgrade validation is still pending.
- 12 distinct GraphQL operations (15 occurrences) conform to official Hardcover schema SHA256 `b1f4ea98576f5f8a91b6545533cda8f5be2cd33114c8df1e133e1d898fa5cd25`. Account-scoped cursor pagination, bounds, nullable fields and reading-edition page association are fixture tested. No real token or authenticated API calls were used.
- Unchanged bridge Lua syntax and nine mock scenarios passed, plus statistics export against a real WAL SQLite fixture. KOReader runtime and new phone/Kindle flows are not validated here.
- APK signature verified and unchanged: `ed12b075d6dba2b09b8246978903f679809725b43f24821a01e6833c85011d39`; version code 8, version name 0.7.0; SHA256 `bbae99ad386d0a76e2f43d56061ad43514475fecf9160fc7faef87764f7565a7`.

[Unified-library scope and acceptance](UNIFIED_LIBRARY_0.7.md) distinguishes shipped behavior from authenticated/device validation and deferred batch publishing/finished dates/remote cover downloads. Light/dark Compose preview definitions compile; they were not rendered or visually inspected in this environment.

## Historical validation records

# Validation record — Folio 0.6.0

Executed 12 October 2026. The user reports all current functions working before this revision. New 0.6 navigation/action behavior was not physically executed in this environment.

- **107 JVM tests passed, no failures/errors/skips**: 52 core, 16 transport, 39 app. Six new signal cases distinguish verified revision, modified revision, missing/unpaired device, source-labelled completion, confirmed local Hardcover link and queued/sending/paused states. Final rerun reused valid unchanged test outputs except the live transport fixture suite, which executed again; the preceding run executed the new app tests.
- checkFormatting, debug APK build, Android test APK compilation and lint passed. Lint: zero errors, 14 warnings. A lint dependency download was initially blocked; the identical official Maven artifact was restored with a verified checksum, then the full checks completed. Compiler deprecation notices remain.
- **15 Android tests compiled but were not executed.** A new Compose case checks that Send to device and Update progress are immediately visible/clickable in book details. Physical single-book sending, pair-and-resume, settings/back navigation, per-book sidecar refresh, badge transitions and progress review still need acceptance.
- Room schema remains 4. Actual migration SQL still matches exported schemas and preserves prior records. The unfinished-task query changes no tables. Bridge syntax, nine scenarios and WAL SQLite export checks passed; 11 distinct Hardcover GraphQL operations validate against the official schema. No real Hardcover token was used here.
- APK signature verified, same signer as previous releases: `ed12b075d6dba2b09b8246978903f679809725b43f24821a01e6833c85011d39`. Version code 7, version name 0.6.0.
- APK SHA256: `9c7bdb4864a7be308d33d6afacb3b1f7467481c269fd4acf36b200822679aab3`; 65,181,248 bytes.

PRODUCT_FLOW_0.6.md defines shipped functions, limits and the ordered catalogue/Hardcover roadmap. No profile import, automatic publishing, EPUB replacement or deletion is claimed. No emulator screenshots or new-device validation were performed.

# Previous validation — Folio 0.5.0

Executed 12 October 2026. This is a code and interaction-flow usability pass with a real native APK. No physical UI acceptance, emulator screenshots, user study or authenticated Hardcover publishing is claimed.

- **101 JVM tests passed, no failures/errors/skips**: 52 core, 16 transport, 33 app. The final run reused valid unchanged core outputs and executed app/transport suites. A new regression verifies that KOReader fraction 0.5 becomes 50%, then page 150 for a selected 300-page edition; invalid fractions are rejected.
- checkFormatting, debug APK build, Android test APK compilation and lint passed. Lint: zero errors, 14 warnings. Existing/new Material ListItem and icon deprecation compiler notices remain.
- **14 Android tests compiled, not executed.** Four new Compose flow checks cover search retention with a destination-state holder, metadata dirty/save visibility, phone-only currently-reading books, and dedicated collection creation. Discard review, keyboards, rotation, TalkBack, dynamic colour and actual visual presentation still require device acceptance.
- Actual migration SQL 1→2→3→4 still matches exported Room schemas with preserved records, indexes and foreign keys. This release keeps schema 4; it adds no database migration.
- All 11 distinct GraphQL operations still match the official schema. No real account credentials or publishing calls were used. Bridge syntax, nine scenarios and WAL SQLite export checks passed; plugin runtime remains unvalidated.
- Static contrast calculation for seven real fallback-theme text/background role pairs: minimum 6.62:1 light, 7.18:1 dark. This is limited to those pairs; it is not dynamic-colour or screen-level accessibility acceptance.
- APK signer verified and unchanged: `ed12b075d6dba2b09b8246978903f679809725b43f24821a01e6833c85011d39`. Version code 6, version name 0.5.0.
- APK SHA256: `25c80a8534a014d56af8940084b8d6afe70b5636605f519044c8fca1f709e624`; 65,148,480 bytes.

See UX_PASS_0.5.md for findings, changes and the phone acceptance checklist. The user confirmed the earlier 0.3.1 phone/Kindle workflow; new 0.4/0.5 behavior still needs hardware testing. Install as an update using the matching signer to retain data.

# Previous validation — Folio 0.4.0

Executed 12 October 2026. The user confirmed the earlier 0.3.1 phone-to-Kindle workflow. No physical-device execution or authenticated Hardcover publishing for 0.4.0 occurred in this environment.

- **100 JVM tests passed, zero failures/errors/skips**: 52 core, 16 transport, 32 app. The final run reused already-passing unchanged core outputs; transport and app suites executed. Transport includes 11 real localhost SSH/SFTP tests. Added cases cover folder creation/idempotency/traversal/symlinks/collisions, cancellation/staging cleanup, completion during publication, retry without duplication, queued revision changes and single-read comparison hash caching.
- Nine new Hardcover fixtures cover status-only updates, account privacy on insert, active-read progress, idempotent retry, new-read consent, preserved completed-history fields, changed remote progress, decreases, invalid editions/pages, ambiguous unfinished reads and percentage estimates. These are fixture checks, not authenticated service acceptance.
- All 11 distinct GraphQL operations (14 occurrences) validated against the official published schema, SHA256 `b1f4ea98576f5f8a91b6545533cda8f5be2cd33114c8df1e133e1d898fa5cd25`. No token or authenticated API call was used.
- checkFormatting, debug APK build, instrumentation APK compilation and lint passed. Lint: zero errors, 14 warnings (11 UseKtx, 2 ExifInterface, 1 OldTargetApi). Existing bitmap assertion and Compose test API compiler notices remain. All 10 Android tests compiled but **were not executed**, including durable queue recovery and migration coverage.
- Actual migration SQL 1→2→3→4 matches exported Room schemas using host SQLite, including indexes/foreign keys, preserving books, shelves, memberships, transfers and wishlist entries. Android Room validation is compiled but awaits device execution.
- Optional bridge Lua syntax, nine mock scenarios and real WAL SQLite statistics-export fixture passed. KOReader plugin hardware compatibility remains unvalidated.
- APK signer verified and matches prior releases: `ed12b075d6dba2b09b8246978903f679809725b43f24821a01e6833c85011d39`. Version code 5, version name 0.4.0; Room schema 4.
- APK SHA256: `0884db68df7878923883aebb8d3d334bb06e8a1a6d83ca39f4fcc8a63a62d830`; 65,082,948 bytes.

Acceptance still required: install over 0.3.1, Android migrations/Room/Keystore, service continuation/cancel/resume, notification denial, process death/force-stop/timeout, physical folder browser, sleep/network interruption, TalkBack/large fonts/dark/light, and authenticated Hardcover updates preserving real ratings/reviews/privacy/completed history. No emulator or attached Kindle is available. No UI previews were rendered.

# Previous validation — Folio 0.3.1

Executed 12 October 2026. Phone-only pairing is implemented and tested against a disposable localhost SSH/SFTP server; Android/Kindle hardware validation remains outstanding.

- **86 passing JVM tests, no failures/errors/skips** across the complete suite. App unit tests and all 15 transport tests ran in this session; unchanged core tests reused their prior passing outputs. The transport suite now includes 10 live SFTP tests and 5 configuration tests.
- New live tests verified: negotiated SHA256 host fingerprint equals the independently computed fixture key fingerprint; probe sends no authentication request; password-free mode uploads/readback-verifies an EPUB and retries idempotently; wrong pins fail before authentication even in password-free mode; a key-only server cannot be bypassed; normal password authentication succeeds and rejects incorrect passwords. Original key, inventory, symlink and sidecar checks passed.
- New configuration tests require a pin for every mode, reject invalid endpoints/modes/traversal, preserve device identity across mode/IP changes, and ensure password-free mode never sends an old stored secret.
- Format/checkFormatting, debug build, instrumentation APK compilation and Android lint passed. Lint: 0 errors, 12 warnings. Existing Material API deprecations and bitmap assertion compiler notices remain. All 9 Android instrumentation tests compiled but were not executed, including 2 trust UI tests and credential-removal/Keystore checks.
- Migration SQL 1→2→3 matched exported Room schemas and preserved old library records. Plugin syntax, 9 bridge scenarios and its WAL SQLite statistics fixture passed again; these subsystems are unchanged.
- APK signature verified (v2) and certificate matches prior Folio releases: `ed12b075d6dba2b09b8246978903f679809725b43f24821a01e6833c85011d39`.
- APK SHA256: `b7956e4f4c8dc669246950b046f3b2772c9057aa90b29c60597d84ed86260319`; 64,951,637 bytes. Version code 4, version name 0.3.1; Room schema remains 3.

Temporary build-tool installations were cleared between sessions and restored (JDK17, Gradle9.6.0, SDK37.0/build-tools36.0.0). SDK installation emitted a temporary duplicate-path warning before completing; compilation succeeded against the installed platform. This is a hosted setup detail, not an app/device acceptance result.

No connected Android device/emulator or physical Kindle was available. Pairing UI, Android Keystore, hotspot routing, actual KOReader Dropbear compatibility and end-to-end EPUB appearance still need testing on the user's hardware. A network-observed key is trust on first use, not independent device verification. No claim of physical validation, rendered UI previews, camera OCR acceptance or authenticated Hardcover success is made. See README for the phone-only steps; original library, discovery and reading-data limitations remain.

---

## Earlier validation records (historical)

# Validation record — Folio 0.3.0

Executed in this session, 11 October 2026. Engineering release; no Android, Kindle or authenticated Hardcover account acceptance is claimed.

## Current checks

- **76 JVM tests passed**, 0 failures/errors/skips: original 60 library/EPUB/backup/reading/sync/SFTP/provider tests, plus 9 Hardcover client and 7 discovery/URL/backup-record tests. SFTP tests used the real disposable localhost Paramiko fixture. The final photo-bound change was followed by app unit tests, compilation, formatting and lint; untouched core/transport checks were not needlessly repeated.
- Debug APK and instrumentation APK built successfully. The 6 Android instrumentation tests compiled but were not executed. Existing Compose previews compiled; no preview/device rendering performed.
- Migration 1→2 and 2→3 SQL applied to host SQLite, compared against exported Room v2/v3 schemas and retained old books/collections/memberships/transfers. This is not Android Room instrumentation validation.
- All **5 actual Hardcover GraphQL documents** validated against the official fetched schema (`b1f4ea98576f5f8a91b6545533cda8f5be2cd33114c8df1e133e1d898fa5cd25`). No personal token or authenticated network request was used. Client fixtures cover account verification, ranked search hydration, existing-status preservation, private/default-account privacy mapping, ambiguous-response retry reconciliation, response errors and token-header injection rejection.
- Lua plugin syntax, 9 bridge scenarios and actual statistics exporter against a real WAL SQLite fixture passed again. Plugin source unchanged from 0.2.
- Kotlin formatting passed. Android lint: **0 errors, 12 warnings** (platform ExifInterface advice, deliberate older target API, optional KTX conveniences). Compiler notices include the existing Compose test API deprecation and a harmless non-null assertion in bitmap handling.
- APK signature verified (v2) and signer matches previous release: `ed12b075d6dba2b09b8246978903f679809725b43f24821a01e6833c85011d39`.
- APK SHA256: `5f82b350856707c2b55aa1f3352b353e4a9110bc0028e601446f05f07539c559`; 65,002,890 bytes. Bundled OCR model/native libraries account for the increased package size.

An initial incremental KSP snapshot failure was resolved with `:app:clean`. A missing import during photo input hardening was corrected; the final build/checks above passed afterward. Neither failed attempt is represented as a successful check.

## Reproduce

Use README SDK/JDK setup. Run `scripts/check-with-sftp.py`, `scripts/check-migration.py`, and `scripts/check-plugin.py` as documented. Optional API schema conformance: `python3 -m pip install graphql-core==3.2.7` then `python3 scripts/check-hardcover-schema.py` (internet, no account). This validates request shapes, not service permissions or actual mutations. Android instrumentation needs a device/emulator.

## Remaining acceptance

No connected Android device (adb empty), emulator, or physical Kindle. Camera capture, photo picker, image decoders/EXIF/OCR, Android Keystore, accessibility/rotation/lifecycle and browser launch require physical-device checks. Hardcover search and publishing require the user's own token entered in the app; no credentials belong in source/chat/backups. No claim of live API success, real-world recognition accuracy, device-side Kindle validation or fully polished new-screen visual QA is made. Hardcover progress and general list/collection sync are planned, not implemented by 0.3.0. `DISCOVERY.md` has the acceptance checklist.

---

## Earlier 0.2.0 validation (historical)

# Validation record — Folio 0.2.0

Executed in this session, 11 October 2026. This is an engineering release; Android and Kindle hardware acceptance remains outstanding.

## Passed

| Check | Result |
|---|---|
| EPUB JVM tests | 17 passed |
| Sync/fake-filesystem JVM tests | 13 passed, including collection scoping and empty-scope protection |
| Reading/Lua-data JVM tests | 9 passed: modern/legacy annotations, exact pointer, executable/deep/oversize/duplicate input rejection, changed snapshots and Markdown |
| Backup archive JVM tests | 6 passed: portable round trip, unsafe paths, content and manifest corruption, unchecked resources and existing extraction targets |
| Shelf/series JVM tests | 3 passed |
| Real localhost SSH/SFTP tests | 5 passed: pinned key, upload/readback/inventory/retry, bad pin, final and parent symlinks, actual adjacent-sidecar retrieval |
| Open Library JVM fixture tests | 7 passed: work/edition resolution, incomplete data, path validation, cover bounds, malformed responses and restricted HTTPS redirects |
| Total JUnit | **60 passed; zero failures, errors or skips** |
| SQLite v1→v2 migration script | Actual migration SQL matches exported Room v2 tables/indexes and preserves existing book/shelf/membership/transfer rows |
| KOReader plugin host tests | All Lua files compile; nine collection validation/idempotency scenarios pass; actual statistics SQL/export passes against a real WAL SQLite fixture with mocked KOReader interfaces |
| Live Kotlin provider client on JVM | Search, edition resolution and an Internet Archive-redirected JPEG cover download passed; actual compiled application client used |
| Kotlin formatting | checkFormatting passes |
| Android lint | Zero errors; 6 warnings (target SDK choice and preference/URI KTX suggestions) |
| APKs | Application debug APK and Android instrumentation APK built successfully |
| Signing | apksigner verifies v2 signature; certificate matches delivered 0.1.0 APK |

Final provider build completed successfully after redirect handling and the version-catalog change. Core and transport sources are unchanged since the passing full SFTP suite. Later checks only rebuilt the affected app and tests. Gradle 9.6.0, AGP 9.4.0, JDK 17, compile platform 37.0 and build-tools 36.0.0 were used. Source/build deprecation warnings remain; Gradle 10 compatibility is not claimed.

APK SHA-256: `25b033a9767cccc3afc9c5196385cfacd40a54ae993361e388fbbf1a0b2f620e`

Signer certificate SHA-256: `ed12b075d6dba2b09b8246978903f679809725b43f24821a01e6833c85011d39`

## Compiled, not executed or rendered

Android instrumentation includes Room persistence, migration validation, backup round-trip/additive restore, empty-library restore with reading provenance and Compose import UI checks. These test APKs compile, but **were not run**. Host SQLite checking does not replace Android Room migration validation.

Sixteen Compose preview variants cover eight surfaces in light/dark themes; they compile, but were not rendered or visually approved. No screenshots are presented as evidence of actual device UI. Keystore, SAF/share grants, app lifecycle, TalkBack, font scaling, motion reduction, grid performance and provider-write behavior require phone testing.

## Hardware and product boundaries

- No Android device was attached, no emulator was running and `/dev/kvm` was unavailable. No physical Kindle or installed KOReader runtime was accessible. The complete phone-hotspot-to-Kindle workflow remains unverified on hardware.
- The optional plugin is source/API-grounded and host-tested, but its KOReader menu integration, installed-version compatibility and actual collection/statistics behavior are not device-validated. Installation and Kindle-side review/export are manual. There is no automatic acknowledgement/capability negotiation yet.
- Adjacent sidecars only; central/hashed metadata directories are not handled. Two equal reads detect a change but are not an atomic reader-wide snapshot. Close the current book before refresh. Original pointers and document hashes are retained; no position writes or automatic cross-edition mapping occur.
- Phone status remains separate from device status. Missing annotations are retained; modified notes can appear as multiple observations until version grouping is added.
- Collections on the Kindle are additive through the optional plugin, not exact mirroring. Renames/removals and deletions are not propagated. The phone reports pending application, not a fake success acknowledgement.
- Statistics cover total recorded duration and bounded daily aggregates; they depend on KOReader's saved Statistics data. Per-book/time-of-day dashboards and multi-device deduplication are deferred.
- Backup restore validates before additive commit and cleans up on ordinary exceptions. A process kill during directory publication may leave an orphan. External document providers do not offer guaranteed atomic writes; check completed archives before relying on them. Backups omit SSH credentials, transfer records and sync history.
- Automatic sync, general remote downloads, EPUB replacement/deletion, full-text search, discovery imports, extra metadata providers and multi-device management remain deferred.
- SFTP publication cannot promise a cross-client transaction on every daemon. Device acceptance must exercise rename semantics, partial transfer, sleep/Wi-Fi interruption and concurrent writers. No fsync durability or automated orphan/staging cleanup is claimed.
- Material3 1.5.0-beta01 remains a deliberate public-Expressive-API exception. Target SDK 36 is intentional pending Android 37 local-network permission testing.

See PLUGIN.md for optional device setup, README.md for normal use, and BACKLOG.md for ordered acceptance work. Do not uninstall an existing app before exporting its library; the update uses the same development signer, and the migration preserves data by design, but actual Android acceptance still needs execution.
