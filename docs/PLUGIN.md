# Optional KOReader Folio bridge

This plugin adds reviewed collection additions and a safe reading-statistics export. SFTP book transfers, phone collections, backups, metadata lookup and adjacent-sidecar reading retrieval work without it.

**Hardware compatibility remains unverified.** Host tests execute Lua validation and statistics SQL with API fixtures, not an actual KOReader installation. Start with disposable books and a new collection.

## Install once

1. Close any open book and back up your KOReader settings.
2. Extract the bridge ZIP; copy the complete `folio.koplugin` directory into the installed KOReader `plugins` directory. Typical Kindle path: `/mnt/us/koreader/plugins/folio.koplugin`. Use an authenticated SFTP client or USB once for installation; Folio does not yet install plugins automatically.
3. Restart KOReader. Find **Folio bridge** in its main tools menu. If your plugin manager disables it, enable it there and restart.
4. Choose **Configure Folio book folder** and select the same existing folder used by Folio on the phone, usually `/mnt/us/Books/Folio`.
5. Keep KOReader's SSH server enabled while using the phone's transfer/retrieval actions. A hotspot connection works like any other local network.

## Matching collections

1. On the phone, create collections/rules and enable **Include in Sync** on the ones you want to deliver.
2. Use **Sync missing books** or an explicit preview/transfer to ensure every selected current EPUB is verified on the Kindle.
3. Tap **Send collection additions to KOReader**. The phone verifies remote files and publishes a versioned request; this is not yet an applied change.
4. In KOReader → Folio bridge → **Review collection additions**, inspect the listed names and confirm **Apply additions**.

Collections are named `Folio: <phone name>`. Membership updates only add: unrelated collections, existing members and book files are never deleted. Reapplying the same request is idempotent. Renaming/removing a phone collection or removing a member does not remove anything on the Kindle. A smart collection sends its calculated membership at request time. This is intentionally additive delivery rather than exact mirroring.

The plugin selects the request with the greatest phone `createdAt` timestamp. It retains request JSON files. After many updates, remove old `.folio-collections-*.json` requests with an authenticated file manager, leaving the newest; the plugin limits scanning to 100 requests. No acknowledgement exchange exists yet.

## Reading statistics

1. Enable KOReader's Statistics plugin and read normally.
2. Close the current book so pending statistics are saved.
3. In KOReader → Folio bridge → **Export reading statistics**.
4. On the phone → Reading → **Retrieve exported statistics**.

The bridge uses a query-only SQLite read transaction and publishes `.folio-statistics.json` by same-directory rename. Folio does not download an open SQLite database or its WAL files. The snapshot includes total recorded seconds and up to 366 daily duration aggregates; the phone displays the latest 30 dates and the snapshot time. Day boundaries use the Kindle's local timezone. Per-book statistics, heatmaps, weekday/hour analysis and multi-device deduplication are deferred.

## Safety and compatibility

- Collection paths are resolved and constrained to the configured book folder; only existing EPUB files are accepted.
- Requests and statistics are bounded to 4 MiB. Collection membership is bounded to 10,000 paths and 1,000 collections. Namespace duplication and control characters are rejected.
- The plugin performs no shell commands and never parses downloaded Lua as executable input. The sidecar parser lives in the Android/JVM engine and accepts literal data only.
- The plugin calls KOReader APIs in its UI process; it never hand-edits `collection.lua` or document settings. Partial API failures can leave some additions applied, so retry safely; this is not an atomic collection transaction.
- Errors appear on-device without logging book text or credentials. Check your KOReader version if an API is unavailable. Capability negotiation and broader installed-version testing are future work.
- Central or hashed sidecars are still outside the phone's adjacent-sidecar adapter. Plugin-based normalized progress/annotation snapshots are a future extension.

## Licence

This optional plugin is independently authored and licensed AGPL-3.0-or-later. The included `licenses/AGPL-3.0.txt` provides the full licence. Distribute its corresponding source when redistributing modified versions. The separately communicating Android app is Apache-2.0 and contains neither this plugin nor KOReader code.
