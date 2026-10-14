# Folio 0.8.1 — browsing takes the screen

The default library body has one compact toolbar: result count/list scope, Refine with an active-filter count, and grid/list toggle. The search field, status/file chips, tags, sorting and separate add/import buttons no longer consume vertical space above books. The grid/list fills the remaining bounded space. Existing cover cards, independent status badges, long press and batch selection remain available.

Refine opens a native bottom sheet containing text search, reading status, file ownership, sorting, exact-tag input/suggestions, and an optional current-list scope. Results update as controls change; Show books closes the sheet and keyboard. Reset clears all refinements including the list scope. Settings are saveable in the existing tab state holder and remain when opening/returning from details. Active filter count remains visible so a narrowed view is identifiable without displaying all chips. Sorting does not count as a filter.

The compact Add books FAB occupies the previous Import books location. Its native menu routes to Import EPUBs, Add book manually, Scan or find a book, Look up on Hardcover and Import Hardcover library. Empty-library Add books uses the same menu. When Hardcover is disconnected, that route continues through account connection into profile import after success; cancelling account connection does not import anything. Other account-dialog contexts keep their existing behavior.

Main library browsing no longer exposes camera/manual/Hardcover additions as header rows. Discovery remains available in Lists as an existing secondary route. Existing entry workflows and repository/security policies remain available. Addition remains unavailable during active library/sync operations, as before. No schema/dependency migration is needed; Room stays at 6.

Compose previews compile for light/dark with the revised toolbar. Updated/new UI tests cover hidden default search/addition controls, opening/applying/resetting Refine, retained search, file filtering and the five Add routes. These tests need emulator/phone execution; compilation is not visual or gesture acceptance. Font-scale/keyboard/TalkBack checks and a real cover-grid viewport inspection are still pending.

## Find → attach → read

Add books → Look up on Hardcover searches by title/author using the connected account token. Select a result to fetch canonical title, authors, description, series, tags and default-edition ISBN/language/publisher. Review before adding; this creates a phone-library entry with a confirmed Hardcover work ID, without publishing a reading status. An existing active work entry is reused without overwriting its metadata; ambiguous/removed matches require resolution. Optional artwork is fetched without credentials from bounded, allowlisted HTTPS cover hosts. No account/token is needed for local EPUB/manual entry.

After saving, the same book opens in details. Add EPUB is prominent beneath its cover and starts the existing file-picker and edition-confirmation workflow. Catalogue details now show description, series and publisher. Once attached, owned-book details expose Send to device and Update progress. Reading updates remain reviewed, separate operations. This release does not automatically post progress or download EPUBs from external sites.

Seven new host tests verify metadata queries/defaults/identity checks and cover host constraints. Official GraphQL schema validation passes; real authenticated API results, artwork and phone gestures remain unverified in this environment.
