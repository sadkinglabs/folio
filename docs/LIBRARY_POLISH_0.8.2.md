# Folio 0.8.2 — search anchored, covers carry status

## Reference inspected

[agrison/reliure](https://github.com/agrison/reliure), checked-out commit `17fd0578e1ee61677bc94ae755ea954420f84dee`. Inspected `frontend/src/App.svelte` toolbar/search and `frontend/src/lib/BookGrid.svelte` grid/list rendering, plus the repository's `screenshots/home.png`. The source confirms persistent toolbar search, visible count, paired grid/list controls, cover-overlay device/annotation/rating labels, and a cover-edge reading progress indicator. The screenshot confirms compact toolbar and cover-led density. The author's [product article](https://grison.me/2026/07/16/reliure-an-epub-manager-for-my-kindle/) describes wider capabilities, which are not claims that every feature was executed here.

Adopted interaction hierarchy through an independent native Compose implementation. No Reliure source, CSS, branding, cover art or protected assets copied. No project-level redistribution licence was found in the checked-out repository, so no code was incorporated.

## Implemented layout

- Persistent rounded search field above scrollable grid/list. Instant title/author/series/tag matching, inline clear and keyboard Search dismissal. Refine is the trailing control within the field, with active non-search filter count.
- Refine contains status, file ownership, sort, tags and list scope. The duplicate search field was removed. Reset clears all refinements including search and list scope.
- Library/list name and total count sit underneath. Filtering displays matched count out of the available total, avoiding an apparently smaller library. An explicit native single-choice segmented grid/list toggle shows current selection.
- Grid cover overlays show reading state and device/file availability as independent labels. Finished and verified On Kindle use a positive Material colour; neutral/pending states use opaque neutral surfaces. Arbitrary artwork remains untinted and labels remain readable in either theme. Reading percentage is a thin bottom-edge progress indicator.
- Grid captions now contain title and author only; status stacks no longer extend cards below the artwork. Selection remains top-right, separate from lower status badges. Long press and contextual actions are preserved.
- List thumbnails show a compact Finished/On Kindle icon when applicable, while adjacent text retains full source/device/reading details for small-cover readability.
- The Add FAB retains all five routes introduced in 0.8.1. Hardcover lookup, details → Add EPUB, send and reviewed progress workflows remain intact.

## Acceptance still required

Light/dark previews and updated Compose UI tests compile, but no preview screenshot was rendered and no phone/emulator was attached. Check a narrow viewport, large font, keyboard, TalkBack, contrasting covers, selection overlays and grid/list persistence on a real device. This release does not change data schema, transfer or account synchronization policy.
