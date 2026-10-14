# Implemented refinement — Folio 0.9.0

This refines the existing three-module application. No dependency, Room schema, book identity, credential format or remote storage format changes.

## Library and lists

* Library is named consistently and its redundant top app bar is removed. Anchored search/Refine stays above compact count/view/Settings controls. The Add books FAB retains every supported acquisition route.
* Full cover artwork is fitted rather than cropped. Selected covers receive an outline; reading and historical device status remain on-cover badges. A 24MiB native bitmap cache is bounded and keyed by path, length and modification time.
* Search indexes decode/normalize metadata once per library update. Query/filter/sort work runs on Dispatchers.Default. Grid and list preserve lazy state and map the first visible item on toggle, with restrained duration-scale-aware fades.
* LibraryArea owns list browsing/selection scope, with one browser and one batch-action contract. Custom lists show cover previews and real counts. Want to Read remains a pinned list. Batch operations stay in the current list.

## Individual book lifecycle

* Both catalogue-only and EPUB-backed records open a full details page with common bibliography, source-aware state summary and in-place list management.
* Primary actions are Add EPUB/Send to device and Update progress. Narrow/large-text layouts stack them. Editing, file/record actions, edition-confirmed attachment and external discovery remain available.
* EPUB details expose all six supported phone statuses consistently and can refresh one book’s supported KOReader reading data without leaving details. Long metadata, annotations and advanced options are progressively disclosed.
* Kindle availability and reading observations carry timestamps. A Hardcover match is called matched, independently of publication. Configuration never claims reachability.

## Integrations and state

* presentLibrary creates one non-persisted projection for Library, Lists and Reading. It isolates account observations and the selected device’s transfer/reading observations, retaining file identity and canonical phone state separately.
* Reading includes catalogue-only activity and opens the same book hub. Its Hardcover action now routes correctly for record-only books; all root reading views and page estimates use the configured device’s observations.
* Kindle labels distinguish Check Kindle & compare, Transfer preview and Send EPUBs. Successful inventory checks set an in-memory check time; stale configuration does not retain it. Existing conflict protection is unchanged.
* Discovery feedback has one active owner instead of root and lookup sheets collecting the same event. Save callbacks execute outside committed-artwork rollback scopes. Cancellation clears the discovery busy state.

## Engineering boundaries

Pure presentation/search functions have eight regression cases, including 5,000-record indexing, device/account isolation and attachment deduplication. Native tests cover the post-commit cancellation regression and an actual Room/metadata/SAF book journey with synthetic cover fixtures. ProductJourneyTest refuses physical devices and non-fixture library contents before replacing fixture records.

The root coordinator is reduced from 1,356 to 1,218 lines in this revision; it still owns several modal IDs and integration orchestration. This is meaningful consolidation, not a claim that all navigation debt is gone. The search cache is an in-memory index, not Room FTS/Paging or a measured large-library performance guarantee.

No automatic Hardcover publication, remote EPUB replacement/deletion, reader-side collection mirroring or fabricated live sync status is introduced. Supported Anna’s Archive behavior remains an external browser query. Existing safe imports, original/revision retention, Trash, backups, host-key pinning, journaled foreground delivery and read-only sidecars remain intact.

Validation and native evidence are recorded separately in VALIDATION.md after execution. Compilation alone does not certify visual quality or physical Kindle operation.
