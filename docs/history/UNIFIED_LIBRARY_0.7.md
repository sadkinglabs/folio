# Folio 0.7 — one library, with optional EPUBs

## Shipped flows

Collection combines actual EPUB records with unattached catalogue records from cover/text discovery and reviewed Hardcover profile import. Search, grid/list layouts and reading filters apply to both. All books / With EPUB / Needs EPUB filters distinguish ownership. A linked catalogue record appears once, using the EPUB's title, cover, metadata and stable file identity. Distinct EPUB editions remain distinct until explicitly reviewed; matching filenames, titles or hashes never imply a catalogue association.

Open a catalogue book to search Anna’s Archive in the external browser, choose a phone EPUB, review its parsed title/author, confirm attachment and open its normal book details. Existing Folio EPUBs can be chosen with title/author suggestions. Import cancellation after file selection keeps the already imported EPUB separate; failure never removes existing files. Attaching does not rewrite the EPUB or reset its reading data. Send to device and Update progress remain immediate actions for EPUB details. Catalogue books can update Hardcover progress manually before owning an EPUB; Kindle refresh is disabled until a file is attached.

Named manual lists accept catalogue books. Attaching copies their list memberships to the EPUB in the same Room transaction as the association. Existing EPUB lists and device records remain intact. Kindle list sync includes only actual verified files; catalogue-only entries are not transfer tasks. Status smart lists also include unowned catalogue books using their labelled Hardcover observation or local Want to Read default; language/series/tag/missing-device rules remain EPUB-oriented.

Hardcover import is a read-only account-scoped query with an ascending user-book ID cursor, 50 rows per page and a 10,000-row session limit. Choose loaded books, optionally confirm existing EPUB editions, review a summary, then save. Exact confirmed Hardcover work IDs reconcile with local entries; title similarity does not merge books automatically. Multiple local or remote associations stop ambiguous imports. Repeating an import updates an observation without creating a second catalogue record. Local edits and Kindle reading positions are never overwritten. Import does not fetch arbitrary public profiles, delete absent local books, publish progress or import Hardcover custom lists.

## Storage and identity

Room schema 5 adds `wish_memberships` with cascading catalogue/list foreign keys and `hardcover_observations` keyed by `(accountId, hardcoverId)`. Existing UUIDs, EPUB revisions, wishlist catalogue keys, transfers and sidecars survive migration. Presentation-only Book projections have no path/hash and must never reach transfer/edit operations. Root selection admits only actual file IDs. Repository attachment rechecks claims under its mutex and a Room transaction, accepts retrying the same association and rejects replacing another file or claiming an already-associated EPUB.

Hardcover observations retain status, nullable page count, selected book edition and fetch time separately from KOReader snapshots. They are shown only for the connected numeric account, stored locally for offline use, refreshed on reviewed import and reading sheet reload/update, and excluded from portable backups. Account disconnect hides them without destroying offline book records. An observed page count is not a KOReader position or a normalized progress estimate.

Portable backup format 2 includes catalogue list memberships; this release accepts format 1 and 2. Restore maps catalogue keys and shelf names to existing records, merges memberships and retains existing attachments. Credentials, live transfer records and account-specific cloud observations remain excluded. Older apps reject format 2 rather than silently dropping new list data.

## Limits and next steps

Authenticated profile import and the new interaction flows need acceptance testing on the user's account/phone; schema conformance and fixtures do not establish production API behavior. Imported profile books use existing local cover artwork when available; remote Hardcover cover download remains pending. Cover/photo discovery still uses the existing image extraction and OCR implementation.

Automatic or batch publishing is not shipped. Reading updates remain per book with edition selection and explicit review. Finished dates/rereading, account-specific edition mapping, Hardcover custom-list import, annotations search, safe EPUB replacement, pending outbound update journal and reading conflict review are next. The existing cloud-linked badge confirms an association, not that all activity has been published. Remote deletion and unreviewed file replacement remain unsupported.

## Acceptance checklist

1. Upgrade over 0.6 without uninstalling; verify books, covers, lists, pairing and encrypted credentials remain.
2. Import Hardcover library, select a sample of Want to Read/Reading/Finished, match one EPUB manually, cancel another match, and repeat import. Confirm no duplicate catalogue records and no changed EPUB metadata.
3. In Needs EPUB, open a catalogue entry; search externally, return, import an EPUB, inspect parsed metadata and confirm attachment. Verify its named lists and Hardcover link follow it and no duplicate remains in Collection.
4. Send the attached book from details and find it in KOReader. Close it in KOReader, refresh progress from the same book's Update progress sheet, select an edition, review and publish.
5. Switch/disconnect Hardcover accounts and verify cached observations from another account are hidden. Library books remain offline.
6. Export/restore a format-2 backup into an empty install. Verify catalogue-only list membership and EPUB reading data survive. Repeated restore remains additive.
7. Test dark/light, large text, TalkBack, rotation, interrupted network, pagination and attachment conflict recovery.
