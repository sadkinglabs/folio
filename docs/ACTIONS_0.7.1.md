# Folio 0.7.1 — contextual book actions and local removal

Long press on a cover/grid tile or list row opens one native contextual bottom sheet. TalkBack exposes Book actions as a custom accessibility action. A tap still opens details. When selection is already active, tap/long press toggles membership instead of opening another menu.

EPUB actions: Open details, Select for batch actions, Send to device (pairing/recovery reused), Update progress (existing reviewed Hardcover sheet), Edit metadata and cover, Manage lists, Change phone reading status, Export highlights and notes when cached, and Remove from library.

Catalogue-only actions: Open details, Select, Attach EPUB from phone with edition confirmation, external Anna’s Archive title/author search, Update progress, Manage lists and Remove. Transfer and EPUB editing controls are absent until a file is attached. Phone status changes remain separate from KOReader observations and Hardcover updates.

Mixed selections support adding EPUB/catalogue books to named lists and removing them. Kindle comparison includes only actual EPUBs and is disabled if none are selected. List assignment runs through the existing per-book operations; it is not an atomic bulk transaction. Batch progress publishing remains deferred.

Remove from library is visible directly in both kinds of book details and in the context menu, plus the batch toolbar. Confirmation names the local scope: managed private EPUB copies, artwork, memberships, cached reading and notes. Original Downloads/SAF sources, Kindle books/sidecars and Hardcover entries remain untouched. Cached-note counts are shown with a reminder to export them if needed. No remote SSH/API delete is issued. This release permanently removes local entries; an undo/recently-removed workflow is not implemented.

The repository re-resolves selected stable IDs under its mutex and one Room transaction. Removing an owned book also removes its linked catalogue entries so they cannot immediately reappear as No EPUB duplicates. Local annotation, reading, transfer and journal rows are removed; book/wish memberships cascade. Unselected editions are retained. Queued, running, failed or paused unfinished transfers that reference a target block removal; finish or cancel the job first. Active transfers also remain guarded by the application operation coordinator.

Only after database commit are corresponding app-owned UUID folders cleaned up. Database failures therefore leave files and rows intact. Cleanup failure is reported; process death after commit can leave inaccessible orphan private files requiring later storage cleanup. Credentials, cloud observations and completed queue audit records are not removed. Duplicate-file import can add the book again after removal.

No schema migration is needed: this release adds DAO queries, repository removal and UI actions to schema 5. SQL cleanup, action availability and mixed-selection planning have host tests. Android gestures/dialogs and filesystem cleanup still require actual Android acceptance; compiled UI tests do not establish device validation.

Acceptance: upgrade without uninstalling, exercise long press in grid/list/TalkBack, test EPUB and No EPUB menus, cancel then confirm single and mixed removal, export notes first when needed, verify Kindle and Hardcover copies still exist, and check pending-sync removal is blocked with a meaningful message.
