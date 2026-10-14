# Photo discovery and Hardcover integration — 0.3.0

## Identity and ownership

Room schema 3 introduces `wishlist`, independent of `books`. Each entry has a random UUID, a unique catalogue key (`/works/OL…W` or `hardcover:<positive ID>`), canonical title/authors, optional private local cover, optional confirmed Hardcover book ID/title, cached publication confirmation, creation time and optional local EPUB UUID. Wish entries have no fake file fingerprint and are never included in the sync planner. Open Library and Hardcover keys are different namespaces; linking does not automatically merge entries saved through different catalogues.

Photo text is a mutable search hint, not an identity. Photo input is copied into one bounded 25 MiB snapshot before parsing; pixel dimensions are checked and decoding is sampled to at most approximately 2,000 pixels per side. Text recognition runs on an EXIF-oriented bitmap with the bundled ML Kit Latin model. Images are transient private cache files until a match is saved; saved covers live in an immutable per-entry UUID folder. Photos are not sent to catalogues. Search text is sent to the selected provider only after a tap. The bundled model works without a first-run model download; recognition still needs physical-device validation.

## Flow and native UI

The Library entry point opens a full-screen native surface with Discover and Saved tabs. Photo capture delegates to the system camera using a narrowly scoped FileProvider and `TakePicture`; selecting a photo uses Android’s photo picker. The app requests no camera or broad photo-library permission. A editable title/author/ISBN field and manual search are always available. Search candidates are confirmed before saving. Saved cover cards expose external search, Hardcover match/publish, explicit EPUB attachment and confirmed local removal. Removal retains imported EPUBs and remote Hardcover entries.

Hardcover can be used as the first catalogue after connecting, or as an enrichment/matching provider after Open Library discovery. Work-level matching is deliberate: the schema supports edition IDs but this release does not guess them. A title-and-author suggestion can assist EPUB attachment; no automatic file-to-work identity assertion is made.

## Hardcover transport

Endpoint: `https://api.hardcover.app/v1/graphql`. Account verification uses `me`; search reads ranked `search.ids` then hydrates `books`; publication reads the authenticated user’s `user_books`, and inserts a `UserBookCreateInput` only when absent. Status 1 is Want to Read in the inspected ecosystem. Existing entries are retained, including finished/in-progress books, so an ordinary scan does not downgrade them. New insertions use the account’s returned privacy setting. An external client writing in the short read/insert gap remains a race the service must resolve; no transactional cross-client lock is claimed.

Client limits: fixed HTTPS origin, no credential-bearing redirects, 15-second connect / 20-second read timeouts, 2 MiB response bounds, 12 search results, >1 second request spacing per client, explicit GraphQL/application error checks, no token or response body in errors/logs. No automatic retries, destructive API mutations or arbitrary URLs. Tokens are encrypted with a dedicated AES-GCM Keystore alias and persisted only after successful account verification. UI tokens are not saved in instance state. Switching accounts/disconnecting clears publication confirmations; offline local matches remain. Account privacy is shown before publication. A completed write is only marked confirmed after an acknowledged result; network loss retains the retry option.

## Persistence and backup

Migration 2→3 adds the table and catalogue-key unique index, leaving existing books and reading data intact. Backup format version 1 gains an optional `wishlist` array; old backups are accepted. Covers use the existing bounded/hash-checked resource archive. Restore validates UUIDs/catalogue keys/text bounds/cover paths, remaps attached EPUB UUIDs, preserves existing catalogue entries, resets remote publication flags and excludes account tokens. Restoring v3 data through an older app drops wishlist records; use 0.3 or later for these backups. No account data or remote progress is restored/published automatically.

## External browser query

A fixed `https://annas-archive.pk/search` origin and a UTF-8 percent-encoded `q` parameter prevent titles from adding URL parameters, fragments or a different destination. It opens through `ACTION_VIEW`; there is no WebView, crawler or in-app downloader. A missing browser produces a visible error. Reachability and site terms/content are external concerns, not tested or asserted by Folio.

## Sources and licensing

- Official Hardcover schema: <https://github.com/hardcoverapp/hardcover-docs/blob/main/schema.graphql>, fetched during this session. All 11 distinct client operations checked against it in 0.4.0.
- Official API entry point: <https://api.hardcover.app/> and account token settings: <https://hardcover.app/account/api>.
- Existing KOReader integration inspected for documented API patterns: <https://github.com/Billiam/hardcoverapp.koplugin> (MIT). No plugin implementation copied or bundled.
- Google Android OCR guidance: <https://developers.google.com/ml-kit/vision/text-recognition/v2/android>. Bundled `com.google.mlkit:text-recognition:16.0.1`, min API 23, compatible with Folio’s min 26. This is a Google-distributed SDK/model under its terms, not an Apache-licensed model. Preserve SDK/transitive notices; do not describe Folio as completely free of proprietary dependencies.

## Acceptance checks still required

On Android: install as an update, check old books/collections; capture portrait/landscape/rotated images; select JPEG/PNG/HEIF; try glare/unreadable covers; cancel capture and deny picker; rotate/background during OCR; verify no crash and edit text. Search both catalogues, confirm incorrect-candidate recovery and duplicate handling. Disconnect/reconnect/switch Hardcover accounts; verify tokens unavailable from app backups. With a real token, add a new Want to Read entry and retry after network loss; ensure existing finished/reading books remain unchanged. Confirm the browser query, import via share sheet, attach the EPUB, export/restore the wishlist, and sync the EPUB to Kindle. Run TalkBack, font scaling, dark/light and large-library checks. None of these physical-device checks have been claimed as passed.

## 0.4.0 reading profile

Book details includes a separate tracking sheet. Confirmed matching is local, while publishing status/progress always requires a button and review. Currently Reading updates only status_id. Page progress selects a matching page-count edition, updates the active unfinished user_book_read or explicitly starts a new session, and preserves completed read history. Percentage input and retrieved KOReader snapshots are edition-based estimates; retrieval age/revision are visible. Read/user-book identity, pages and edition detect remote conflicts before writes; retries reconcile already-recorded target progress. Multiple unfinished reads are rejected. Dedicated many-edition mappings, list/collection publishing, finished-date workflows and background publication remain deferred. Tests use a fake API, plus official schema validation; no real account token was available in the build environment.
