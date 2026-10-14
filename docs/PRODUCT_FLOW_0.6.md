# Folio 0.6: four core jobs

The user reports the current functions working but having to search for them. This release reorganises the existing product rather than replacing its engines.

| Destination | Job and entry points |
| --- | --- |
| Collection | Organise imported EPUBs, search/filter/grid/list, view device and reading badges, open a book or import a file |
| Lists | Build Want to Read from title/author/ISBN or cover photo; browse saved reads; create/manage manual or smart EPUB lists |
| Kindle | Pair/configure, review comparisons/conflicts, bulk transfer, recover/cancel jobs, inventory/history and optional bridge operations |
| Reading | Browse current/finished reads, refresh KOReader progress/notes, open a book or update Hardcover |

Settings is available from the gear in every main destination. Main headings are presented once. Search/layout/scroll state remains retained across destinations and book details.

## Two actions in a book

**Send to device** checks the selected book against the pinned Kindle, identifies existing identical files, and queues a verified upload only when safe. It stays in book context. If not paired, pairing resumes the requested send after saving. A failed/paused existing job routes to Kindle recovery rather than silently resuming other queued books. Revision conflicts retain the remote EPUB/annotations; review is in Kindle.

**Update progress** opens one source-labelled workflow: get the latest reading snapshot for this book from Kindle, select/confirm the Hardcover entry and edition, review estimated pages or enter a value manually, then explicitly publish. It no longer requires visiting Reading first. Closing a book in KOReader is still needed to flush its sidecar. Retrieving never writes KOReader state. Existing cached snapshots show their age/revision; cloud writes retain the existing optimistic checks and consent for new reads/decreases.

## Honest collection signals

File state: Phone only / Not sent / Queued / Sending / Sync paused / Needs update / On Kindle. On Kindle means a previously hash-verified matching revision, not live presence. Editing a transferred EPUB produces Needs update rather than a false sync claim. Reader completion is labelled Finished · Kindle; phone status is labelled separately. Progress is a cached KOReader fraction. Hardcover linked means a confirmed local match, not confirmed current cloud progress. Badges are derived observations; they do not overwrite either authority.

## What remains separate

Owned EPUBs and metadata-only Want to Read records still have separate persistence. Arbitrary named lists currently hold EPUBs; metadata-only saved reads use Want to Read. Hardcover profile import, cached account-wide progress, automatic publication and collection-to-Hardcover list mapping are not delivered by this navigation pass. Existing notes, backup, metadata lookup/editing, pairing and transfer integrity features remain available.

## Next development order

1. A persistent catalogue entry / EPUB edition / account-specific Hardcover link model. Migrate existing EPUBs and saved reads without merging solely by title or hash. Allow metadata-only books and multiple EPUB editions to live in the same collection and named lists.
2. Read-only paginated Hardcover profile import with an explicit match/merge preview; preserve local edits, ownership, privacy and edition provenance. Cache remote status/progress separately from KOReader snapshots and phone organisation.
3. A reviewed progress outbox, reconciled retries and optional user-authorised update-after-refresh. No periodic SSH reconnection or background cloud publication without opt-in; decreases/new reads remain review points.
4. Collection-to-Hardcover list mapping, previewed membership changes and acknowledgements.
5. Annotation search/version grouping and cooperative EPUB replacement preserving KOReader state. These follow the identity foundation, rather than expanding navigation further.

## Physical acceptance

Install as an update. Test four destinations and gear/back; photo/text → Want to Read → attach EPUB → Collection. In a book, Send to device with existing/new/changed files; pair when missing; reject wrong pin; resume a paused job explicitly. Check badges during sending and after verified completion, local metadata edits, refreshed reader completion and device IP changes. Update progress without leaving book context; try Kindle offline, missing sidecar, old revision, wrong Hardcover match, edition selection, manual and estimated pages, remote progress changes and token failures. Test TalkBack, large fonts, keyboard and rotation. Automated tests and compilation do not imply these physical checks have passed.
