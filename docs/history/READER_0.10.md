# Reader hotfix 0.10.1

The startup container timing fault is fixed by waiting for attachment and a resumed Activity before the fragment transaction. The host uses FragmentContainerView. Recreation uses the ViewModel’s current locator instead of the initial opening locator. Previously, AndroidView factory immediately called replace(container.id), although the Activity could not yet find that view. Native AndroidX behavior is reproduced under Robolectric; these framework regressions are separate from unexecuted Android/WebView device tests. Malformed EPUBs with an empty/unlocatable reading order now produce an opening error. No library file or schema change is involved.

# Folio 0.10: reader and paired-folder integration

## Decisions

Phone: library, metadata, discovery, lists, Hardcover and reading. Kindle: another reader in the same collection. A folder path, bibliographic UUID, EPUB revision SHA-256 and reading anchor have separate jobs. SFTP remains the pinned, credential-protected transport. No cloud account is required.

Readium Kotlin 3.4.0 is the stable EPUB navigator (BSD-3-Clause). The newer Compose rendition is experimental and was not adopted. Native Compose controls host its FragmentActivity navigator; only EPUB content uses WebView, an explicitly approved exception. Core library desugaring 2.1.5 supports the app's Android 26 minimum. No PDF or DRM dependency was added. Remote publication resource requests are denied; the WebView blocks network loads, file/content access, mixed content, third-party cookies and automatic windows. The reader exposes no new JavaScript-native interfaces or SSH credentials. Android WebView security settings need device verification.

Room migration 6→7 adds phone_reading and phone_bookmarks, keyed by book/revision with permanent-removal cascades. Soft removal retains anchors. Migration SQL is compared against generated schema 7 in SQLite. Backup format 4 retains these anchors and accepts older versions 1–3. Existing signing identity/package and library UUIDs are preserved.

## Position interoperability

Native Readium locator JSON is the primary phone position. The bundled, pinned MIT Prose KOReader DOM converter translates visible text nodes to KOReader V2 pointers and resolves incoming pointers to Readium CSS/text locators. UTF-16 browser offsets and KOReader Unicode/whitespace conventions are handled by that converter. Full SHA-256 verifies the edition; KOReader's sampled MD5 supplements plugin identity checks. Percentages are display/Hardcover estimates, never anchors or conflict winners.

PositionReconciliation compares observed phone/Kindle anchors with an accepted baseline. Both changed: conflict. One changed: queue. No shared baseline and two different anchors: conflict. Fetch time is not reading time. Conflicts and pending operations do not advance accepted baselines. A phone incoming request checks the expected native locator before automatic application. A KOReader outgoing request checks its current saved last_xpointer before automatic application. The bridge applies through GotoXPointer, verifies ReaderRolling's accepted pointer and remembers applied IDs. It does not rewrite sidecars. User can disable automatic continuation and review manually. KOReader must reopen a book to consume a request.

This is implemented protocol logic, not a claim of exact real-device passage validation. DOM normalization, publisher styling, older engine DOM versions and WebView differences remain acceptance risks. Browser DOM fixtures cover Unicode, whitespace and nested markup, but do not emulate crengine.

## Paired folder implementation

SAF selects only the phone tree; recursive scans skip hidden/internal sidecar directories, reject duplicate names/traversal, bound depth/count and stage bounded EPUBs. Hash-based planning detects additions and conflicts. New Kindle books are downloaded, parsed and published to the phone tree, then imported through the existing repository. Uploads reuse SyncEngine's staging/readback/retry logic and preserve subfolders. Room transfer records are written only after remote byte verification. Operation-level journal records survive interruptions.

The initial implementation deliberately does not replace editions or infer deletions. It also does not continuously watch providers or maintain a background mirror. Sources outside the paired phone tree remain standalone library files. Large runs hash/read both ends and reconnect for reading data; performance optimization is follow-up work. Generic document providers cannot promise a filesystem-level atomic compare-and-swap; publication checks destinations and uses provider rename, but concurrent third-party changes require acceptance testing.

Companion installation is available inside Settings using the saved SSH pairing and a supplied existing KOReader plugin folder. The transport stages known Folio files, checks bytes and publishes a whole directory; any previous Folio plugin is archived for rollback. Other plugins are untouched. The separately bundled companion is AGPL-3.0-or-later; the Android app remains Apache-2.0, Readium BSD-3-Clause and Prose MIT. Notices are included in APK assets and source.

## Acceptance remaining

1. Android: actual rendering, WebView offline behavior, rotation/process death, accessibility, large EPUB and font changes.
2. Phone SAF: local provider create/rename, revoked grant, low storage, interruptions and folder conflicts.
3. Kindle: bundled installer, correct plugin path, restart, expected-position guard, old DOM refusal, exact passage in both directions, bookmarks retained.
4. Paired-folder foreground/background behavior under OS process death and real Wi-Fi outages.
5. Replacement workflow preserving annotations, rename reconciliation, explicit deletions, list reconciliation and continuous/background scans.

No usable emulator or physical Kindle is available in this workspace. Android test compilation does not count as test execution or visual validation.

## Primary implementation references

- Readium Kotlin toolkit release/source: https://github.com/readium/kotlin-toolkit/releases/tag/3.4.0 and https://github.com/readium/kotlin-toolkit/blob/3.4.0/docs/guides/getting-started.md
- Stable EPUB navigator source: https://github.com/readium/kotlin-toolkit/blob/3.4.0/readium/navigator/src/main/java/org/readium/r2/navigator/epub/EpubNavigatorFactory.kt
- KOReader native sync/event and position handling: https://github.com/koreader/koreader/blob/master/plugins/kosync.koplugin/main.lua and https://github.com/koreader/koreader/blob/master/frontend/apps/reader/modules/readerrolling.lua
- Prose DOM converter pinned npm package: @prose-reader/koreader 25.1.0 (MIT), https://www.npmjs.com/package/@prose-reader/koreader; lockfile and reproducible build/test scripts are in tools/reader-interop.
- Android core desugaring artifact metadata: https://dl.google.com/dl/android/maven2/com/android/tools/desugar_jdk_libs/maven-metadata.xml (verified 2.1.5 release).
