# Folio design system

Working name: **Folio**. An understated native digital library; book covers provide the colour and texture. No external branding, fantasy symbols or imitation bookshelves.

## Foundation

* Authentic Material3 components throughout, inside MaterialExpressiveTheme. Wallpaper-derived light/dark schemes on Android 12+, restrained olive/ivory fallback and dark near-black surface. Settings let users choose system/light/dark and turn off dynamic colour.
* Reading-oriented serif display/headline roles; platform sans body/labels; medium titles for book names. Use Material typography roles, never pixel text sizes. Keep descriptive text distinct from action labels.
* 4dp spacing rhythm; 24dp page gutters, 18dp cover gaps, 24dp grid-row gap. Covers use a 0.68 aspect ratio and 8dp corners; controls use 16–28dp shapes, collection rows 24dp.
* Standard navigation bar, FAB, chips, text fields, bottom sheet and dialogs. Selection uses visible check marks and a compact action surface.
* MaterialExpressiveTheme provides expressive motion physics and component behavior. Additional content-size transitions use springs, respecting platform animator scale through Compose. Shared-element transitions and expressive floating toolbars are planned after measured device/UI validation.

## Screen and navigation plan

| Screen | Implemented interaction |
|---|---|
| Library | Adaptive cover grid/list, search across title/author/series/tags, recent/title/author sorting, reading-status filters, collection filter, long-press selection, batch collection/send |
| Details | Cover/title/author, description, metadata, manual membership, local and device reading states, highlights/Markdown export, next series volume and cached device availability |
| Editor | Full field editing, validation, SAF cover replacement, preserved originals and identifier explanation |
| Collections | Manual/smart rules, calculated counts, open filtered library, per-shelf transfer preview and Include in Sync switch |
| Kindle / sync centre | Pairing sheet, preview, verified/present/conflict explanations, explicit upload action, byte progress, inventory and journal history |
| Settings | Appearance, private storage, backup export/additive restore, pairing and credential removal |
| Reading activity | Cached device progress, annotations, retrieval actions and optional exported total/daily reading time |
| Metadata lookup | Explicit query, edition selection, current/proposed field review, real cover preview and selective application |

The sync centre is part of the Kindle destination so device setup and transfer results stay together. Details/editor are nested native flows with BackHandler and saveable state. No nonfunctional feature buttons are shown.

## States and feedback

* First-run library explains importing from Downloads/Files/share and provides a working action.
* No results distinguishes search/filter misses from an empty library.
* Every async operation has a clear progress/error surface with a polite accessibility live region. Transfer progress reports actual bytes; it never simulates progress.
* No cover uses a typographic title/author fallback, not a generic empty rectangle.
* Conflict explanations tell the user why the book is untouched. Present books were hashed, not guessed by name.
* Pairing retains a credential when the verified host key, user and authentication mode match, even if the address changes; a new pairing needs a password or key. Host-key checks are mandatory.

## Accessibility and responsive behavior

Use Material touch targets (at least 48dp), content labels on standalone icon actions, merged card semantics, selection states, checkbox roles, scalable text and scrollable forms. Adaptive grid columns follow width; wide metadata/details layouts and navigation rail are future refinement. No decorative charts or fake statistics. Reduced-motion behavior relies on system animation duration scale; an explicit app preference is planned.

`ui/Previews.kt` defines sixteen light/dark previews for library, details, editor, collections, Kindle, pairing, reading and metadata lookup using fictional but realistic metadata. Preview entries are never inserted into the real database. These previews compile with the app; visual rendering and device accessibility checks must be separately recorded in `VALIDATION.md`. The current mock book examples intentionally exercise missing-cover fallback; real EPUB covers use sampled bitmap decoding.

Material references: [Google motion guidance](https://m3.material.io/styles/motion), [Material3 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3), [Expressive theme API](https://developer.android.com/reference/kotlin/androidx/compose/material3/MaterialExpressiveTheme.composable). Material3 1.5.0-beta01 is a deliberate exception: 1.4.0 stable makes the expressive theme internal. The version is pinned and the beta integration is isolated in Theme.kt. Other libraries use stable versions; no unsupported internal API is used.


## New-flow interaction decisions

Use a fifth native navigation destination for Reading. Device progress is labelled separately from phone reading status; retrieving a snapshot never silently changes the user's status. Old revision provenance is explained in details. Retained note versions are intentionally visible rather than merged destructively.

Metadata enrichment uses a bottom sheet with explicit network disclosure, a deliberate Search action and unchecked field choices. Edition-specific metadata is fetched before review; known fields stay unchanged unless selected. Covers are shown from the actual chosen result when available; failures can still leave useful text metadata.

Collection cards expose a scoped preview and a switch for inclusion in the one-tap transfer action. An empty union cannot become whole-library sync. Device collection application is a separate action with clear plugin prerequisites and pending-on-Kindle messaging.

Backup export uses Android CreateDocument; restore uses OpenDocument followed by an additive-restore confirmation. The result reports the count added and leaves current data intact. Reading statistics are actual imported values with a visible export timestamp, never fabricated dashboard metrics.

### Discovery and Want to Read (0.3.0)

A labelled Library action opens a full-screen native Discover/Saved surface. It inherits the existing dynamic light/dark Material Expressive theme. Discover leads with camera/photo actions, followed by editable recognised text and candidate selection. Saved uses covers and book titles as the primary hierarchy. Hardcover authentication is an explicit settings dialog, book matching a candidate bottom sheet, and publication a separate labelled action. No photos upload, no automatic publication, no fake EPUB rows. Provider/network errors appear inline and retain local entries. Exact implementation is compiled, but new surfaces have not been rendered or physically inspected; accessibility and adaptive-layout QA is still pending.


### Phone-only pairing (0.3.1)

The existing native pairing sheet now defaults fresh setup to temporary KOReader password-free mode and the existing Kindle documents folder. Host/port entry leads to a **Read Kindle fingerprint** action; an explicit trust dialog shows address, port, negotiated algorithm and selectable SHA256 pin. A changed key has a distinct replacement title/action. A stale address cannot be trusted. Inline probe feedback keeps network errors visible in the sheet. Key/password options remain alongside password-free with accurate setup instructions and a brief open-server notice. Password/key controls are hidden for password-free setup. Light/dark pairing previews and additional trust UI tests compile with the app; physical rendering, accessibility and instrumentation execution remain pending.

## 0.4.0 interaction refinement

Completion/error notices use transient dismissible Material snackbars rather than persistent operation banners. Active work retains progress. Kindle displays a recoverable batch with verified book count, uploaded bytes, phase, current title and explicit Cancel/Resume. Notification content excludes book titles. Pairing offers native real-folder browsing and bounded subfolder creation. Hardcover tracking stays contextual in book details: account → match → status → selected edition → reviewed page update, with source age and estimate wording; no dashboard clutter or automatic account writes.

## 0.5.0 consolidation

Use shared SectionHeading, FeatureRow and ExpandableSection for hierarchy, contextual navigation and progressive disclosure. Outlined groups share Material shapes. The custom sage palette now defines container/on-container/surface/outline roles in both themes; dynamic system colours remain intact. Main route state is retained with SaveableStateHolder. Creation and editing use focused forms with clear confirmation points. Reading sources are labelled rather than silently merged. See UX_PASS_0.5.md for the audit and physical acceptance checklist.

## Library space in 0.8.1

The book viewport has priority: one compact count/refine/layout strip and a compact Add FAB. Search/status/files/tags/sort live in a sheet; additions live in a separate Add sheet. No persistent chip bank or provider buttons sit above covers. See [implementation and acceptance](LIBRARY_SPACE_0.8.1.md).


## Library polish 0.8.2

Persistent search with inline Refine; separate compact count and explicit segmented layout toggle. Cover overlays keep reading/device states independent; opaque semantic Material surfaces avoid relying on the image for contrast. Selection occupies top-right, status bottom-left and progress the bottom edge. Title/author remain outside the cover. Reference inspection and mobile acceptance limits are recorded in [LIBRARY_POLISH_0.8.2.md](LIBRARY_POLISH_0.8.2.md).


## Lists 0.8.3

One scroll surface: compact pinned reading lists, then user lists. No separate wishlist destination. List contents reuse the library browser inside Lists; search/view state is scoped by list. Transfer controls move to a contextual bottom sheet. See [LISTS_0.8.3.md](LISTS_0.8.3.md).
