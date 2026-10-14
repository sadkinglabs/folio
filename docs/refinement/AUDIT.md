# Folio product audit — 0.8.3 baseline

## Principal issues (source verified)

1. `ui/FolioApp.kt` is 1,356 lines with duplicated library/list browsing, cross-cutting modal IDs and derived book/sync state. Record-only details use a bottom sheet while EPUB details use a page, so back, editing and actions differ with file ownership.
2. Library search parses tags and reconstructs search text per query on the UI thread. Covers decode on every return and crop artwork. No bounded shared thumbnail cache. Selection competes with navigation and a dense horizontal toolbar.
3. Book details have four local statuses while batch management supports six, and list management/retrieval navigates away from the book. Full source/debug prose and every list checkbox dominate content.
4. Kindle pairing is not reachability; cached transfer presence is not live inventory. Labels such as Sync hide that file delivery is verified, one-way new-file upload. Existing-edition replacement safely conflicts but is not prominently explained.
5. Shared section primitives exist, but dimensions/shape/type are scattered. Details, catalogue, discovery and settings have different action hierarchies and repetitive explanatory text.
6. Reading filters omit catalogue-only Hardcover activity. Reading-data retrieval and posting to Hardcover remain independent, as they must; UI should say which observation is cached.
7. Discovery save invokes the UI callback inside file-cleanup try/catch after database insertion: cancellation during that callback can remove an already persisted cover folder. Global and title-lookup sheets can both collect the same notice.

8. Reading activity routes all Hardcover actions through the EPUB detail ID; a record-only book therefore cannot open the tracking sheet. Some root reading consumers do not isolate the configured device, despite the presentation projection doing so.

## Good foundations to retain

Three modules (core/transport/app), bounded EPUB ZIP/XML parsing and staged immutable originals, UUID identity, revision hashes distinct from identity, Room migrations without destructive fallback, additive restore/Trash, strict host-key pinning and Keystore credentials, journaled transfer queue/foreground service, full readback verification and explicit conflict handling. Sidecars are bounded/read-only and annotations append-only. Hardcover writes are reviewed and reconciled. Anna’s Archive is an external title/author search, not embedded results or downloads. Preserve these boundaries.

## Decision

Keep the stack and schema. Extract a single tested library presentation/filter model and reusable browser workspace. Use the same book hero, status summary, primary action row and progressive sections for file-backed and record-only details. Keep Library/Lists/Kindle/Reading, rename Collection to Library, and use explicit transfer/retrieval/update terms. Introduce shared size/motion tokens, preserve cover aspect without cropping, cache bounded bitmaps, and validate native rendered layouts. Do not implement unsafe remote replacement or imply automatic/bidirectional progress publication.

## Reference evidence

Reliure: actual `frontend/src/App.svelte` and `lib/BookGrid.svelte` retrieved from github.com/agrison/reliure. Source places count/search/view controls together and device/progress overlays on covers; adopt hierarchy, independently implement native UI. No source/assets copied; no verified redistribution licence. Calibre official GUI documentation distinguishes empty records from files and provides contextual metadata/list actions. Android official Compose samples/Jetsnack and Androidify guidance support content-led hierarchy, scoped state, reusable design tokens and purposeful Material motion. m3 typography site returned only a JavaScript shell; no invented visual observation.

Reference URLs: https://manual.calibre-ebook.com/gui.html ; https://github.com/agrison/reliure ; https://github.com/android/compose-samples ; https://android-developers.googleblog.com/2025/05/androidify-building-delightful-ui-with-compose.html ; https://developer.android.com/develop/ui/compose/animation/shared-elements .

## Scope still consequential

Phone records and file records remain separate storage aggregates joined by stable IDs. Do not replace this model merely for UI purity. True remote EPUB replacement needs reader cooperation and device validation. Automatic Hardcover queue and finished/reread dates are follow-up work. Authentication/device-specific behavior cannot be proved by emulator/fixtures.
