# Folio 0.8 — everyday library management

## Delivered workflows

| Everyday task | Where to find it | Result |
| --- | --- | --- |
| Rename or delete a list | Lists → list overflow, or long press | Rename keeps the stable list ID; delete removes memberships, retains books and reader collections |
| Edit a smart rule | Lists → Edit list | Change rule field/value; stored manual memberships remain available |
| Add a book offline | Collection → Add book manually | Save title, authors, description, series/number, publisher, language, identifiers, tags and optional cover without a file or online search |
| Edit a catalogue-only book | Long press → Edit metadata & cover; details → Book actions | Same native editor, with catalogue copy and validation; retains Hardcover match |
| Fix an EPUB attachment | EPUB actions → Detach catalogue entry | Retains EPUB, Kindle path and reading data; catalogue entry keeps its match, artwork and lists for another confirmed attachment |
| Recover removal | Settings → Recently removed → Restore | Stable book UUIDs, private originals/revisions, notes, transfers and memberships return |
| Permanently delete | Recently removed → Delete permanently → confirm | Removes only app-owned local copies/cache; no Kindle or Hardcover delete |
| Export/share/open EPUB | EPUB actions | Export current revision through SAF, or read-only FileProvider cache copy via Android chooser |
| Batch list/status changes | Select books → Manage selected books | Add/remove manual-list memberships or set phone reading status across EPUB and catalogue records in one transaction |
| Understand/retry import failures | Import results; Settings → Last import results | Per-file name, outcome, specific bounded reason, individual/all-failed retry or choose files again |

The two immediate book actions remain Send to device and Update progress. Book details now also has a visible Book actions overflow, so secondary actions do not require scrolling through notes. Core transfer/SSH policies are unchanged. Directly opening/share-importing an EPUB produces the same import-result sheet as the file picker.

## Identity, restore and storage

Room schema 6 adds `removed_entries` and rich metadata/local-status fields to `wishlist`. Soft removal marks the actual EPUB plus linked catalogue UUIDs with a shared group ID and hides them from active queries, activity, memberships and backups. Files are not moved or deleted; restoring the group preserves original identities, paths and cached reader anchors. Duplicate EPUB import restores an existing removed UUID instead of failing a unique-hash constraint or inventing another identity.

Recently removed has no automatic expiry. Files remain private until explicit permanent deletion; the UI states this. Permanent deletion commits row cleanup before private-folder cleanup. An interrupted/failed cleanup can leave orphan private folders, reported on ordinary failures; it does not break retained books. Previously permanently deleted 0.7.1 records cannot be recovered by this migration—use a prior backup or the retained Kindle/source copy.

Queued/failed/paused transfer references still block removal. Active sync guards prevent concurrent library mutation. Attachment corrections never rename remote files, reset percentages, overwrite positions or write reader sidecars. List rename/deletion remains phone-only; automatic Kindle collection removal is not supported.

Explicit catalogue phone status is stored separately from remote observations. An unset local status follows an available account-scoped Hardcover status, otherwise Want to Read; an explicitly selected Want to Read is a real override. Phone status changes do not publish cloud progress or rewrite KOReader observations.

## Portable files and backup

Export saves the actual current EPUB revision to the chosen document provider; original source Downloads files remain unchanged. Share/Open uses a dedicated private cache directory exposed through a narrow FileProvider path with temporary read permission. The Folio activity is excluded from the chooser to avoid reimport loops. Recipient reader availability and SAF provider behavior require device testing. Shared copies remain in app cache; explicit cache expiry/storage cleanup remains a follow-up.

Backup format 3 includes manual catalogue keys, rich metadata and local statuses; formats 1–3 are accepted. Recently removed entries are excluded from new backups. Restore can reactivate an existing removed record when its retained identity/catalogue key matches a backup, preserving existing local revisions instead of overwriting them. Credentials, cloud-account observations and live transfer records remain excluded. Older apps reject format 3 rather than silently dropping metadata.

Import result/retry state is kept in the ViewModel, retained across rotation but not persisted across process death. Where a provider permits it, read grants are persisted; share-sheet grants may expire. Recovery then offers Choose files again. A successful provider write is not claimed atomic, and failed exports may leave a partial destination requiring deletion/retry.

## Validation boundary and follow-up

Host tests verify metadata round trips, legacy defaults, action availability and import error mapping; SQLite tests execute shipped queries for migrations, soft removal/restore, preservation of notes and list deletion. Android tests cover repository file/identity retention, purge boundaries, detach/batch membership changes, pending transfer rejection and UI paths. They compile here but need execution on a phone/emulator. No real authenticated Hardcover or new Kindle/device flow is claimed validated in this build.

Follow-up: phone acceptance, outbound batch progress, orphan/cache cleanup, a retained import history, optional bulk metadata editing, backup include-Trash option and remote collection policies. Cover replacement is supported; removal of embedded EPUB artwork remains outside this pass.
