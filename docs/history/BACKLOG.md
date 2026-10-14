# Next priorities after 0.10.0

1. Run reader + SAF + companion end-to-end acceptance on Android and a physical Kindle; verify actual passage accuracy and screenshots.
2. Complete the one-Sync managed mirror: changed-edition replacement with reading-data preservation, rename detection, reviewed deletions and a persistent foreground queue for folder operations.
3. Bring attached/imported library EPUBs into the paired phone tree automatically with explicit storage policy; reconcile list/folder memberships.
4. Companion receipts and active-book export so the phone distinguishes queued, applied and verified reading positions; prune acknowledged requests safely.
5. Lifecycle-safe folder refresh on open and supported scheduled scans, document-provider change handling, large-library scan caching.
6. Unified reading activity including phone positions, local highlighting and reviewed annotation interoperability.

Earlier backlog and rationale follow below.

# Remaining work after Folio 0.9.0

Implemented behavior is recorded in README and docs/refinement. This is remaining work, ordered by risk and everyday value.

## P0 — Release acceptance and data reliability

1. Physical Android + KOReader acceptance: hotspot and shared Wi-Fi, all supported authentication modes, device inventory, interrupted/cancelled uploads, foreground notification denial, sleeping reader and manual resume. Test preserved-key upgrade over the delivered app with a populated library. Emulator and localhost SFTP tests do not validate Kindle hardware.
2. Authenticated Hardcover acceptance: search/profile pagination, edition matching, Currently Reading/page updates, retries and concurrent clients. Keep account identity, matched work and last published reading state separate.
3. TalkBack, 200% text, landscape/tablet, keyboard/back, motion-scale zero, contrast and performance measurements with thousands of actual covers. Add adaptive rail/two-pane layouts only after measured small-screen behavior is sound.
4. Recovery from process death/storage exhaustion during import, EPUB publication and additive restore. Reconcile orphan directories/revisions and abandoned remote staging without deleting active operations.
5. Validate the optional KOReader bridge on installed versions: additive collection updates, repeated requests, acknowledgement lifecycle and consistent statistics exports. Do not describe exported snapshots as live totals.

## P1 — Complete the reading loop

6. Reviewed, durable Hardcover publication outbox with offline pending state, reconciliation and explicit conflict handling. Finished/reread sessions and dates; map selected app lists to Hardcover lists with a preview.
7. Normalized KOReader progress/annotation export with capability/version negotiation, central/hashed sidecars, atomic generations and document provenance. Preserve exact anchors and earlier annotation observations.
8. Explicit EPUB replacement that preserves sidecars, notes and content anchors through reader cooperation. Existing edited EPUB transfers remain blocked conflicts. Remote deletion always requires a separate preview.
9. Canonical work/edition association, cross-provider duplicate suggestions and richer edition comparison. Preserve existing record/file identities and migration safety.
10. Investigate Kotlin incremental cache invalidation (cached rebuilds reported duplicate overloads that clean builds resolve). Root navigation/modal coordination refactor with saved destinations and measured cover transitions; cancel stale metadata searches and centralize async operation ownership. The extracted library coordinator is a step, not a complete navigation rewrite.
11. Richer all/any smart rules and dedicated author/series/tag browsing. Evaluate Room FTS/Paging when profiling justifies them; existing background indexing and bounded covers remain the default.

## P2 — Enrichment and optional automation

12. Barcode/crop/retake and broader OCR scripts; public-distribution audit of OCR/transitive terms. Anna’s Archive remains an external browser query handoff.
13. Metadata revision/undo UI, additional open providers and EPUB full-text search. Optional public-domain discovery from Standard Ebooks/Project Gutenberg.
14. Multiple Kindles, verified inventory generations, device-only EPUB import and lost-device handling.
15. Reading-time visualizations with timezone and session deduplication from genuine source observations.
16. Optional constrained automatic synchronization with journaled retries and visible notifications; OPDS/discovery/QR pairing only if it reduces proven friction. No cloud account requirement for the core library.
