# Folio redesign

Design spec, 2026-10-15. Design only: no code changed. Mockups: https://claude.ai/artifact/Bo91q8V7HpyWvtYJ8qKUan (private; 17 phone frames, light and dark; source file `mockups.html` in the session scratchpad). Reviewed against the 0.10.x code in `app/src/main/java/dev/sadking/folio/ui` and `reader/ReaderActivity.kt`, and the Pixel 6 emulator screenshots (`shots45`).

Material 3 1.5.0-beta01 with `MaterialExpressiveTheme`, Compose BOM 2026.09, minSdk 26, targetSdk 36.

---

## 1. Critique

| # | Problem | Where |
|---|---|---|
| 1 | Four tabs, and the one that matters most (the book you are reading) is a list behind the last tab. | `library-*`, `reading-dark` |
| 2 | Every screen opens with a sentence of explanation under the title. Subtitles, hints and footers add ~30% of the vertical space and say the same thing twice. | `reading-dark`, `kindle-dark`, `catalogue-light`, `hardcover-record-dark` |
| 3 | Device tab is an SSH wizard with no state: no "connected", no "last seen", no queue. The one real-time thing in the app (Folio Link) is buried in Settings as text and a monospace address. | `kindle-dark`, `FolioLinkSection.kt` |
| 4 | Book page is a column of outlined cards and expandable sections; "Progress" needs four lines to say "not started". Removal is a red text link at the bottom of a scroll. | `book-light`, `BookDetails.kt` |
| 5 | Lists are three identical pill buttons plus one card with a lonely cover and a "Transfer options" text button. Nothing says what is inside. | `lists-light` |
| 6 | Want to Read is hidden in Lists, as a list that is also a filter ("Refine · 1"), with a back link inside a tab. | `want-to-read-light` |
| 7 | Settings is a vertical essay: 13 text hints, 8 outlined buttons, no grouping a user would predict. The settings screen opens as a Dialog window, so it has no shared back stack with the rest. | `SettingsScreen.kt`, `FolioApp.kt:720` |
| 8 | Book actions sheet is a flat list of 12 sentence-long text buttons ("Change reading status on this phone"). | `BookActionsSheet.kt` |
| 9 | Hardcover sheet has numbered steps, a paragraph intro and a nested "Connect" feature row. It reads like a form from a bank. | `hardcover-record-dark` |
| 10 | Reader controls are a stock `TopAppBar` + a flat bottom row; the appearance sheet mixes a two-line toggle, a stepper and six chips with no rhythm. | `reader-controls`, `reader-appearance` |
| 11 | Snackbars say too much ("Changes saved. Your original EPUB is kept") and sit on every tab for 10 seconds. | `library-dark`, `lists-light` |
| 12 | Everything is flat: no depth cues, no surface tiers, outlined cards everywhere, one solid green for anything primary. Nothing moves. | all |
| 13 | Covers are the best thing on screen and get the least room: 3-column grid with 2-line captions under each cover repeats the title that is already on the cover. | `library-light` |

## 2. Principles

- **The book is the interface.** Covers carry identity; chrome stays quiet. Text under a cover only when the cover has none.
- **One primary action per screen.** Read, Send, Save. Everything else is an icon or lives one tap away in a sheet.
- **State over instructions.** Show "On · 2 e-readers · last seen 3 min ago" instead of telling the user how sync works.
- **Say it once, in four words.** No subtitles under titles. Hints only on an empty state or a destructive confirm.
- **Depth, lightly.** Three surface tiers, soft shadows under things you can lift (covers, the centre item, sheets), glass on bars that float over content. Never on content itself.
- **Motion explains the structure.** Containers grow from the thing you tapped; sheets slide; tabs crossfade. 200–350 ms, never a loop.
- **Nothing is lost.** Every destructive step is a two-step with an undo. Removal keeps data (invariant). The design never hides a book because of a filter the user forgot.

## 3. Navigation model

### Bottom bar

Five slots. Slots 1, 2, 4, 5 are tabs. Slot 3 is the **Now Reading** item: a raised 56 dp cover tile (or a book glyph on a tonal disc when nothing is open), 10 dp above the bar, with a thin progress ring.

| Slot | Label | Icon | Content |
|---|---|---|---|
| 1 | Library | `AutoStories` | Library and Want to Read tabs |
| 2 | Lists | `CollectionsBookmark` | Lists overview |
| 3 | (no label; a11y: "Continue reading Frankenstein, 37%") | cover | see below |
| 4 | Device | `Devices` | Folio Link, e-readers, send queue |
| 5 | Settings | `Tune` | Settings |

The bar is a glass surface (§4) that spans the width; the centre tile breaks its top edge. Component: `NavigationBar` with a custom centre slot composable, or `FlexibleBottomAppBar` if the beta's API fits. Mockups assume a custom slot inside the standard bar.

### Centre item behaviour

- **Tap** = continue reading. Opens `ReaderActivity` for the current book at its saved place. The cover scales up into the reader (container transform, 350 ms). This is the only thing most sessions need, so it gets the single tap.
- **Long-press** (and TalkBack action "Book options") = the **Now Reading sheet**: cover, title, author, progress ring, a full-width **Continue** button, then a 2×3 icon grid: Send, Edit, Progress, Hardcover, Share, Remove, and a "Open book page" row. One haptic tick on open.
- **No current book**: the tile shows a book glyph. Tap opens the Now Reading sheet in its empty form: "Pick a book to read" with the three most recent reading-status books as covers, and "Go to Library".

Why not swipe-up: a swipe that starts on the bottom bar collides with gesture navigation (home swipe) on most phones. Why not "tap opens the sheet with a big Continue": it adds one tap to the most frequent action. Long-press is discoverable because the sheet is also reachable from the book page overflow, and a one-time tooltip ("Hold for options") shows under the tile the first three times it appears with a book.

"Current book" = the book with the most recent phone reading position whose status is not Finished or Abandoned; falling back to the most recent e-reader report through Folio Link. Changing it is implicit: opening another book makes that the current book.

### What moves where

| Today | New home |
|---|---|
| Reading tab | Gone. Current book: centre item. Others in progress: Library tab, "Reading" shelf pinned at top. Finished: Lists → Finished. Reading time and KOReader stats: Settings → Reading. |
| Want to Read (pinned list) | Library → Want to Read tab |
| Currently Reading (pinned list) | Library → Reading shelf (first row) |
| Finished (pinned list) | Lists, as a system list card |
| Discover (camera) | Add sheet → Scan a cover; also search field in Want to Read |
| Settings (Dialog) | Tab 5, a normal route |
| Folio Link section (Settings) | Device tab hero. Settings keeps only the on/off and advanced switches. |
| SSH pairing (Device) | Settings → Advanced → E-reader over SSH |
| Phone folder, plugin install | Settings → Advanced |
| Book overflow (12 rows) | Book page: 6 icon actions + 3-row overflow menu |
| Transfer banner | Unchanged idea, restyled as a glass pill above the bar |

### Back behaviour

- Tabs are roots. Back on a root tab exits (Android convention), with predictive back showing the home animation.
- Routes above a tab: Book, Record (merges with Book, see §5.6), Edit, List detail, Search source (WebView). Back pops one.
- Sheets and dialogs are overlays. Back dismisses; `ModalBottomSheet` already supports predictive back in material3 1.4+, so the sheet shrinks with the gesture.
- Reader: back closes the reader to the screen that opened it (book page, or the tab if opened from the centre item). Predictive back shows the cover shrinking back to the tile.
- Selection mode: back clears the selection first.
- `FolioNav.TABS` becomes 4 real tabs plus the centre action; `Route` gains `List(id)` and `Search(query)`.

### Transitions between levels

| From → to | Transition | Duration |
|---|---|---|
| Tab → tab | Crossfade + 8 dp vertical slide of content; bar indicator slides | 200 ms |
| Cover → book page | Shared element (cover) + container transform of the tile into the page surface (`SharedTransitionLayout`) | 350 ms, `EmphasizedDecelerate` |
| Cover / centre tile → reader | Container transform to full screen, then reader fades in over the cover | 350 ms + 150 ms |
| Anything → sheet | Standard sheet slide, scrim 32% | 300 ms |
| List card → list detail | Container transform; cover mosaic expands into the detail header | 350 ms |
| Selection mode on | Bar swaps to the selection bar with a vertical slide; tiles get a 2 dp inset and a check | 200 ms |

## 4. Visual language

### Colour roles

Dynamic colour stays the default (API 31+). The fallback and the dark theme keep the current olive/wheat scheme but move from the flat green to a layered palette.

| Role | Light | Dark | Use |
|---|---|---|---|
| `surface` | #F7F6F0 (warm paper) | #121410 | page ground |
| `surfaceContainerLow` | #F0EFE7 | #1A1D17 | large panels |
| `surfaceContainer` | #EAE9E0 | #20241D | cards |
| `surfaceContainerHigh` | #E2E2D8 | #2A2E26 | sheets, menus |
| `primary` | #4B6530 | #C4D3A7 | one primary button, progress rings, nav indicator |
| `primaryContainer` | #D6E8BD | #2E4419 | tonal buttons, selected chips |
| `tertiary` | #7A5E34 | #DDC49F | Hardcover and "want to read" accents |
| `error` | M3 default | M3 default | Remove, failed sends |
| `outlineVariant` | #CBCBBF | #3E4237 | hairlines only; no outlined cards |
| glass tint | surface @ 72% | surface @ 64% | bars and floating pills |

Semantic status colours for Folio Link: on = `primary`, waiting = `tertiary`, off = `outline`. Never red for "off".

### Type scale

Display: a humanist serif for titles of books and screens (`FontFamily.Serif` on the system, Noto Serif on most devices; no bundled font). Body and labels: system sans (Roboto/Google Sans). No third face.

| Token | Size / line | Use |
|---|---|---|
| `displaySmall` serif | 32 / 38 | Book title on the book page |
| `headlineMedium` serif | 26 / 32 | Screen titles (Library, Lists, Device, Settings) |
| `titleLarge` sans 600 | 20 / 26 | Sheet titles, list names |
| `titleMedium` sans 600 | 16 / 22 | Row titles |
| `bodyLarge` | 16 / 24 | Body |
| `bodyMedium` | 14 / 20 | Secondary rows |
| `labelLarge` | 14 / 20, +0.1 tracking | Buttons, chips |
| `labelSmall` caps | 11 / 16, +0.8 tracking | Section eyebrows, status pills |

At 200% font scale: screen titles drop to `headlineSmall`, grids drop to 2 columns, hero rows stack (the `BoxWithConstraints` rule in `FolioDesign.kt` stays), and the bottom bar hides labels, keeping the centre tile at 56 dp.

### Shape

- Covers: 6 dp radius, 2:3 ratio, never cropped. A 1 px inner highlight on the left edge (the "spine", see cover treatment).
- Cards and panels: 20 dp. Sheets: 28 dp top corners. Buttons: full pill. Chips: 8 dp (M3 expressive default).
- Centre tile: 12 dp. Bar: 28 dp top corners, flush bottom.

### Elevation and glass

Three tiers, no more:

1. **Ground**: `surface`. No shadow.
2. **Panels**: `surfaceContainer`/`Low`. No shadow, no outline. Separation by tone only.
3. **Lifted**: covers, the centre tile, sheets, menus, FAB. Shadow: `ambient 0 1 2 @ 6%` + `spot 0 6 16 @ 14%` light; dark uses `0 8 24 @ 40%` plus a 1 px top highlight at 8% white.

Glass (bars, floating pills, the reader's controls overlay):

```
API ≥ 31: backdrop blur 24 dp of the content behind, then a tint layer of `surface` at 72% (light) / 64% (dark),
          then a 1 px hairline of onSurface @ 8% on the edge facing content.
API 26–30: no blur. Tint at 92%, same hairline. Identical layout and copy.
```

Compose cannot blur *what is behind* a layer on its own (`Modifier.blur` blurs the layer itself). Two options: (a) the Haze library (`dev.chrisbanes.haze`, Apache 2, ~60 KB), which does backdrop blur on API 31+ and a tinted scrim below that; (b) hand-rolled: draw the scrolled content into a `GraphicsLayer`, draw it again under the bar with `RenderEffect.createBlurEffect`. (b) costs about a day and a frame of lag on scroll. Recommendation: **Haze**, as the one new dependency, with the concrete benefit that it is the only practical way to get real glass on bars without re-rendering content. If the owner declines, ship the API<31 recipe on every API level. Decision in §8.

### Cover treatment

- Real covers are shown as-is with the 6 dp radius and the lift shadow.
- A 1 px vertical highlight 3 dp from the left edge (white @ 25% light, @ 12% dark) and a 2 px darker band under it suggest a spine. This is the skeuomorphic touch, and the only one on covers.
- Generated covers (no image): keep the current typographic cover, but derive the ground colour from a hash of the title into a 12-hue palette, with the title in serif at 2 sizes; drop the "FOLIO · QA FIXTURE" line.
- Progress is a 3 dp bar flush to the bottom edge of the cover, `primary` on `primaryContainer`, only for books in progress.
- Badges on covers: one, at top-right, 20 dp circle: e-reader (sent), queued (clock), no EPUB (dashed outline cover instead of a badge).

### Iconography

`Icons.Outlined.*` from the extended set (already a dependency). Filled variant for the selected tab. Sizes: 24 dp in bars and rows, 20 dp in chips, 18 dp inline. No icon without a content description.

### Motion

| Spec | Value |
|---|---|
| Standard | 200 ms, `FastOutSlowIn` |
| Emphasized (containers, sheets) | 350 ms, M3 `EmphasizedDecelerate` in, 250 ms `EmphasizedAccelerate` out |
| Micro (press, chip select, switch) | 120 ms |
| Progress ring on the centre tile | animates to the new value over 600 ms when the reader closes |
| Folio Link status dot | 1.2 s breathe while "waiting", static when on |
| Text size stepper | the "Aa" sample scales with a spring (stiffness medium) |
| Reduced motion (`Settings.Global.ANIMATOR_DURATION_SCALE` = 0 or a11y "remove animations") | crossfades only, no scale, no breathe |

What animates: tab content, bar indicator, covers into pages, sheets, selection check marks, progress rings and bars, the send queue row when a book is delivered (slide out, 250 ms), snackbars (slide up from the bar). What never animates: text, list reorders on data refresh, the reader page itself.

### Haptics

`HapticFeedbackType.LongPress` on the centre tile long-press and on entering selection. `TextHandleMove`-class tick on each stepper step and on each chip in the appearance sheet. `Confirm` on a send queued, `Reject` on a failed send. No haptics on navigation.

## 5. Screen-by-screen

Component names are Material 3 composables unless marked (F) for a Folio composable from §6. "Removed text" lists copy that goes away; replacement copy is in the layout column. All strings stay in `strings*.xml`.

### 5.1 Shell

| | |
|---|---|
| Purpose | Tabs, centre item, overlays, transfer pill, snackbars. |
| Layout | `Scaffold` with a transparent content ground; glass `NavigationBar` (F `FolioBar`) with the raised centre tile (F `NowReadingTile`). Snackbars sit 8 dp above the bar. The transfer pill (F `TransferPill`) sits above the snackbar host. |
| Components | `NavigationBar`, `NavigationBarItem` ×4, F `NowReadingTile`, `SnackbarHost`, `SharedTransitionLayout` wrapping tab content and routes. |
| States | Transfer active: the Device item shows a small ring instead of a badge. No current book: glyph tile. |
| Removed | Top app bars on Lists and Device (title moves into the content header). Settings gear on every tab (now a tab). |
| Interactions | Tab tap: `nav.root`. Re-tap the current tab: scroll to top. Centre: §3. |

### 5.2 Library tab

| | |
|---|---|
| Purpose | Find and open books you own. |
| Layout | Screen title "Library" (serif) with a count chip ("5"). Right: Add (`FilledTonalIconButton`), search icon. Below: `PrimaryTabRow` with "Library" and "Want to Read". **Library tab**: a "Reading" shelf, a horizontal row of 112 dp covers with progress bars and a tiny "37%" label, for every book in progress (current book first). Below it the grid, 3 columns of 2:3 covers with no captions (caption line only for generated covers, 1 line). A `LazyVerticalGrid` with a sticky one-line toolbar: sort (`SplitButton`: "Recent ▾"), grid/list `ButtonGroup`, filter icon with a count dot. |
| Components | `PrimaryTabRow`, `LazyRow`, `LazyVerticalGrid`, `SplitButton`, `ButtonGroup`, F `CoverTile`, F `ReadingShelf`, `SearchBar` (expands from the search icon, full-width, with recent queries). |
| States | Loading: 6 shimmering cover placeholders. Empty: F `EmptyState` with a stacked-books illustration (drawn vectors), "Your shelf is empty", one button "Add books". Filtered-empty: "Nothing matches", "Clear filters". Busy: library never blocks; an in-flight import shows as a tile with a spinner at the end of the grid. |
| Removed | "Search library" field always visible; "Refine" word and count; the "5 books" subtitle (becomes a chip); captions under covers that have real covers; "Sync paired folders" (Settings → Advanced). |
| Interactions | Tap cover: book page (shared element). Long-press: selection mode. Pull down: nothing (no refresh needed). Sort menu: Recent, Title, Author. Filter sheet: §5.4. |

### 5.3 Want to Read tab

| | |
|---|---|
| Purpose | Books you intend to get, with or without an EPUB, newest first. |
| Layout | Same grid. Entries without an EPUB show a dashed-outline cover (generated or Hardcover cover at 80% with a dashed frame) and a "Find" label chip in the corner. A trailing "Find" bar at the top: `SearchBar` placeholder "Find a book…" that searches Hardcover and Open Library (M5 search sources) and offers "Add to Want to Read". |
| Components | `LazyVerticalGrid`, F `CoverTile(variant = Wanted)`, `SearchBar`, `AssistChip`. |
| States | Empty: "Nothing on the list yet", buttons "Find a book", "Scan a cover", "Import from Hardcover". |
| Removed | Pinned list card, "All lists" back link, Refine filter semantics. |
| Interactions | Tap: book page (record variant). The "Find" chip opens the M5 WebView search for that title. Attaching an EPUB keeps the tile in place and removes the dashed frame (200 ms crossfade). |

### 5.4 Filter sheet (replaces Refine)

`ModalBottomSheet`, title "Filter". Three chip rows: Status (All, Unread, Reading, Finished, Paused, Abandoned), Files (All, With EPUB, Needs EPUB), Tag (a single `SuggestionChip` row, scrollable). Footer: "Reset" text button and "Show 12 books" filled button. Filters last for the session (unchanged). Removed: "Refine library" title, the rule explanations, "Exact tag / Any tag" radio.

### 5.5 Selection mode

Top: a `TopAppBar` replaced by a selection bar (F `SelectionBar`): "3 selected", Close. Bottom: the nav bar swaps to an action bar with Send, Add to list, Status, Remove as icon+label buttons. Removed: the "Tap books to select…" hint (a11y announcement instead). Batch sheet (`BatchManageSheet`) becomes two sheets: "Add to list" (checkbox list) and "Status" (radio list). Removed: "Changes to lists and status stay on this phone. They are not sent to Hardcover." (a one-line footnote in the status sheet only).

### 5.6 Book page (one screen for books with and without an EPUB)

| | |
|---|---|
| Purpose | Decide what to do with this book. |
| Layout | Full-bleed header: the cover's dominant colour as a soft radial glow behind a 160 dp lifted cover, centred; the glass top bar over it carries Back and Overflow only. Below: title (serif `displaySmall`), author, series line in `primary`. **Primary**: full-width "Continue · 37%" (or "Read", or "Find EPUB" for records). **Action row**: six 56 dp icon buttons with 11 sp labels: Send, Edit, Progress, Hardcover, Share, Remove. Then a **status strip** (F `StatusStrip`): three compact tiles: Phone (37%), E-reader (Kindle · 41% · 2 h ago), Hardcover (p. 112). Tapping a tile opens the Progress sheet. Then **Lists** as chips (`AssistChip` per list, trailing "+"). Then description, 4 lines with "More". Then an `ExpandableSection` "Details" (publisher, language, identifiers, tags) and "Notes · 12" (list, export icon). |
| Components | `LargeFlexibleTopAppBar` collapsing into glass, F `CoverHero`, `Button`, F `ActionRow`, F `StatusStrip`, `AssistChip`, `ExpandableSection`, `DropdownMenu` (overflow: Open in another app, Save a copy, Detach EPUB, Export notes). |
| States | Record (no EPUB): primary = "Find EPUB" (opens search source), the status strip shows Hardcover only, action row loses Send and Share, gains "Attach" (pick a file or an EPUB already in Folio). Transfer in progress: Send icon becomes a ring. Different revision on e-reader: a one-line `tertiary` note under the status strip. |
| Removed | "Book details" title; the Progress card with four lines; "Manage lists" link; "Not on your e-reader yet" sentence (status tile says "Not sent"); "Search Anna's Archive" paragraph; "Choose an EPUB already in Folio" link; the red "Remove from library" link; "All reading activity" link. |
| Interactions | Continue: reader via container transform. Send: queues to Folio Link (haptic confirm, the Send tile flips to a check for 1.5 s, snackbar "Queued for Kindle · Undo"). Progress: Progress sheet (§5.8). Hardcover: Hardcover sheet (§5.12). Share: system share. Remove: removal dialog (§5.16). Lists chip "+": list picker sheet. |

### 5.7 Now Reading sheet (centre item long-press)

`ModalBottomSheet`, `surfaceContainerHigh`, 28 dp. Row: 72 dp cover, title (serif), author, a 40 dp progress ring with "37%". Full-width "Continue". Then the same `ActionRow` as the book page. Last row: "Open book page ›". Empty variant: "Pick a book to read", three recent covers in a row, "Go to Library". Haptic on open.

### 5.8 Progress sheet

Title "Progress". Three rows with a left icon and a trailing value: Phone (37% · yesterday), each e-reader seen through Folio Link (Kindle · 41% · 2 h ago), Hardcover (page 112 of 320). A row is tappable when it is ahead: "Continue from Kindle at 41%". Footer: a `SegmentedButton` for status (Unread, Reading, Finished, Paused, Abandoned) with the label "Status on this phone". Removed: "Reading status on this phone. This doesn't change your e-reader or Hardcover." (becomes a tooltip on the info icon).

### 5.9 Add sheet

Title "Add". A 2×2 grid of large tonal tiles with an icon and two words: "Import EPUB", "Find online" (M5 search sources / Hardcover lookup), "Scan cover", "Type it in". Below, one row: "Import your Hardcover library" with the username, only when connected. Removed: all five body lines ("Choose files from Downloads or your cloud storage", etc.).

### 5.10 Metadata editor

A route, not a dialog. Top bar: Back, "Edit", Save (filled, disabled until dirty). Cover at the top, 120 dp, tappable with a camera-overlay chip "Change". Fields as `OutlinedTextField`s in this order: Title, Authors, Series + number (one row), Description. "More" expandable: Publisher, Language, Identifiers, Tags. Footer: a single line "Original file is kept" (EPUB) or nothing (record). "Find details online" as a `TextButton` with an icon under the cover. Removed: "Book information" heading, the "Saved on this phone without an EPUB. Your Hardcover link is kept." note, the two long footers. Discard dialog unchanged in behaviour, copy: "Discard changes?" / "Discard" / "Keep editing".

### 5.11 Lookup sheet (Open Library / Hardcover details)

Search field at the top, results as rows with a 40 dp cover. Choosing a result shows a diff list: each field as a row with the current value struck through and the suggested value, a checkbox per row, "Use this cover" switch. "Apply" filled. Removed: the privacy paragraph (an info icon opens it), "General details · no specific edition".

### 5.12 Hardcover sheets

**Reading sheet**: title "Hardcover", subtitle is the status chip ("Currently Reading"). If not connected: one tonal tile "Connect Hardcover" with a key icon. If connected and unmatched: search field prefilled, result rows, "Link". If matched: a page `Slider` (0..pages, with the current page as a large number above it) and a "Percent" toggle, two source chips "Use phone · 37%", "Use Kindle · 41%", and a filled "Send to Hardcover" button. Status change via `SegmentedButton`. Confirm dialog: "Send page 118 of 320?" with the two existing checkboxes. Removed: the intro paragraph, numbered steps, "Close the book on your e-reader…", "Refresh from Hardcover" (an icon in the title row).

**Account dialog**: `AlertDialog` → a sheet: "Connect Hardcover", token field, "Get a token ↗", "Connect". Privacy as one line in `labelSmall`.

**Import sheet**: full-height sheet with a search field, a list of rows (cover, title, status chip, trailing checkbox), a sticky footer "Import 12". Matching an EPUB opens a nested picker sheet. Removed: the intro paragraph, "Select all loaded" wording (becomes a checkbox in the footer), "Load next 50" (infinite scroll).

**Book search sheet (lookup to add)**: same row list; "Add" per row.

### 5.13 Lists tab

| | |
|---|---|
| Purpose | Reading plans, collections, and what to send. |
| Layout | Title "Lists", "+" tonal icon button. A 2-column grid of list cards (F `ListCard`): a cover mosaic (up to 4 covers, fanned 6° with lift shadows, or a stack of 3 when fewer), the card ground tinted from the first cover's colour at 12%, name (serif `titleLarge`), "12 books", and small trailing marks: a sync icon when included in sends, a rule icon for smart lists. **System lists** first: Finished, Not on e-reader (smart), then user lists. |
| Components | `LazyVerticalGrid`, F `ListCard`, F `CoverMosaic`, `DropdownMenu` (long-press: Edit, Send to e-reader, Include in sends toggle, Delete). |
| States | Empty: "Make your first list", "+ New list". Loading: 4 tinted blanks. |
| Removed | "Reading plans and collections", "Pinned" heading, "Your lists" heading, "Transfer options" button, "Discover" link, the pill buttons. |
| Interactions | Tap: list detail (container transform). Long-press: menu. "+": list editor sheet. |

### 5.14 List detail

A route. Header: the mosaic expands into a 120 dp banner with the name over it (serif), count, and two icon buttons: Send, Edit. Grid of covers as in Library with a sort menu. Rule lists show a `SuggestionChip` with the rule ("Language: es"). Empty: "No books yet", "Add from Library" (opens Library in selection mode with the target list preselected).

### 5.15 List editor sheet

Name field, a `SegmentedButton` "Manual / Rule", when Rule: a `DropdownMenu` field for the rule and a value field. Toggle row "Include in sends" with a one-line hint. Save filled; Delete as an icon in the title row. Removed: "Pick books yourself, or let a rule fill the list.", the rule-change note (shown inline only when switching from Rule to Manual).

### 5.16 Removal

`AlertDialog` → a small sheet with the cover(s) at the top: "Remove Frankenstein?" / "It goes to Recently removed. Files, notes and progress are kept." Buttons: "Remove" (tonal error) and "Keep". After removal: snackbar "Removed · Undo" (7 s). Permanent delete lives only in Recently removed (Settings → Library) with its own red confirm. Detach dialog: same shape, "Detach EPUB?" / "The book keeps its details and lists."

### 5.17 Device tab (Folio Link)

| | |
|---|---|
| Purpose | Know whether the e-reader can reach the phone, what is waiting, and what happened. |
| Layout | Title "Device". **Link card** (F `LinkCard`, `surfaceContainer`, 20 dp): a 12 dp status dot and a word: "On", "Waiting for Wi-Fi", "Off"; under it the address in `labelLarge` with a Copy icon; a trailing `Switch`. When on and no e-reader has connected: a second line "Waiting for your e-reader" with a breathing dot. **E-readers** section: one row per device seen (`ListItem`: device icon, name from the kosync user agent or "Kindle", "Seen 3 min ago · 41% of Frankenstein"). **Send queue**: a horizontal row of 72 dp covers with a status chip under each (Waiting, Sent ✓, Failed ↻); a "Send all missing" tonal button when any book is not on the device. **Activity**: the last 5 log lines in `bodyMedium`, "See all" opens the log sheet. |
| Components | `Switch`, F `LinkCard`, `ListItem`, `LazyRow`, F `CoverTile(variant = Queued)`, `TextButton`, F `ActivityList`. |
| States | Off: the card is `surfaceContainerLow` with "Off" and the switch; everything else collapses to one line "Turn on Folio Link to sync and send." No network: "Waiting for Wi-Fi" in `tertiary`, address hidden, "Open Wi-Fi settings" text button. No e-reader seen yet: an `OutlinedCard` with three short steps and a "Save the plugin" button (the only instructional text on the tab). Transfer running (SSH legacy): the queue row shows a progress bar under the cover. |
| Removed | "Connect once, then send books over Wi-Fi.", "Start the SSH server…", "Set up your e-reader" feature row (→ Settings → Advanced), "Recent transfers" expandable, "KOReader collections", "Before you send" preview list (the queue is the preview), "Check your e-reader". |
| Interactions | Switch: start/stop the service. Copy: clipboard + snackbar "Copied". E-reader row: a sheet with its details, "Forget", and its last positions. Queue cover: book page. Failed chip: retry. "Send all missing": confirm sheet "Send 4 books?" with the covers. |

Legacy SSH job card (`KindleScreen` job card) survives as a row inside the queue section while a job runs, with Pause/Cancel icons.

### 5.18 Settings tab

Groups as `ListItem` rows with leading icons, section eyebrows in `labelSmall` caps, no hints unless listed.

| Group | Rows |
|---|---|
| **Appearance** | Theme (`SegmentedButton` System / Light / Dark); Wallpaper colours (switch) |
| **Reading** | Default text size, font, spacing, margins (opens the same appearance sheet as the reader, applied as defaults); Turn pages with volume buttons (switch); Reading time (opens a sheet with KOReader stats and "Get from e-reader") |
| **Folio Link** | Folio Link (switch, with the address as supporting text); Keep phone awake (switch, hint one line); Update Hardcover automatically (switch) |
| **Hardcover** | Account (Connected as @name / Connect); Import library |
| **Library** | Storage (12 books · 48 MB); Recently removed (3); Back up (sheet: Export, Restore, one line about what is included); Last import |
| **Advanced** | E-reader over SSH (the pairing form, "Legacy"); Phone folder sync; Save the KOReader plugin; Connection log |
| **About** | Version, licences |

Removed: all `settings_*_hint` strings except Keep awake and Backup, the subtitle, "Forget e-reader" (inside the SSH page), "Install the KOReader plugin" button text (now a row). Settings becomes a normal tab, so the `Dialog` window and its own back handling go away.

### 5.19 SSH pairing (Settings → Advanced → E-reader over SSH)

Keeps `ConnectionForm` fields, restyled: a `Stepper`-style header (1 Network, 2 Address, 3 Login, 4 Folder) with the current step highlighted; fingerprint shown as a monospace chip with a copy icon; Test connection as a tonal button with an inline result pill. Copy is moved to `strings.xml` (it is hard-coded today). Hint text cut to one line per step.

### 5.20 Discovery (scan a cover)

A full-screen route from the Add sheet. Camera/photo buttons as two large tonal tiles, the recognised text in a field, results as rows. Removed: "A book caught your eye?", the intro and privacy paragraphs (info icon). Save dialog becomes a sheet with the cover and "Add to Want to Read".

### 5.21 Search source (M5 WebView)

A route. Glass top bar: Back, the source name as a `DropdownMenu`, the query. The WebView fills the rest. A download caught by the listener shows a bottom pill "Frankenstein.epub · Attach" (F `TransferPill` variant). Attach confirm: "Attach to Frankenstein?" sheet with both covers.

### 5.22 Reader

| | |
|---|---|
| Purpose | Read. |
| Layout | Immersive by default. Tap centre: controls fade in (200 ms): **top glass bar** with Back, title (one line), and three icons: Contents, Appearance, Devices. **Bottom glass bar**: a `Slider` of the book (chapter ticks), "Page 12 of 340 · 4%", previous/next chevrons, bookmark. The page area never resizes (M1 rule): bars overlay it. |
| Components | F `GlassBar` ×2, `Slider`, `IconButton`, `ModalBottomSheet` for the three panels. |
| States | Opening: the cover from the shared element stays for 150 ms, then the page fades in. Failed: F `EmptyState` "Couldn't open this book" with "Back". Folio Link offer: a card at the bottom "Continue from Kindle at 41%?" with Continue/Stay, auto-dismiss 8 s. |
| Removed | Author line in the bar; "Return to book" (Back); the `TopAppBar` colour block. |
| Interactions | Edge taps and volume keys unchanged. Slider drag scrubs with a chapter label tooltip; release jumps. |

**Appearance sheet** ("Text"): row 1: the Aa sample (serif, 28 sp, scales live) between A− and A+ (`FilledTonalIconButton`s), the percentage under the sample; row 2: theme `ButtonGroup` with four swatches (System, Light, Paper, Dark) as coloured circles with a label; row 3: Serif / Sans `SegmentedButton`; row 4: two `Slider`s, Spacing and Margins, with three ticks each; row 5: switches "Book's own formatting", "Scroll", "Only for this book". Removed: "Make yourself comfortable" title (becomes "Text"), the two-line toggle hints, the footer paragraph.

**Contents sheet**: `PrimaryTabRow` Contents / Bookmarks; rows with the current chapter in `primary` and a dot; bookmarks with swipe-to-delete. Removed: "Your place in the book".

**Devices sheet**: "Continue elsewhere": each e-reader with its position as a row ("Kindle · 41% · 2 h ago" → "Jump"), and "Send my place to Kindle" (through Folio Link). Removed: the two body paragraphs and "Phone → Kindle" heading.

### 5.23 Transfer pill, snackbars, empty states

- **Transfer pill** (F `TransferPill`): a glass pill above the bar, 44 dp: a 20 dp ring, "Sending 2 of 4", a Stop icon. Tap opens Device. Replaces `TransferBanner` and `StatusSurface`.
- **Snackbars**: max 6 words plus an optional action. "Saved", "Queued for Kindle · Undo", "Removed · Undo", "Copied". Long messages only for failures, with "Details". Never shown on a tab the action did not happen on except Undo-able ones.
- **Empty states** (F `EmptyState`): a 96 dp drawn vector (stacked books, a bookmark, an e-reader outline), a 4-word title, one line, one button. Same component everywhere: Library, Want to Read, Lists, list detail, Device queue, Recently removed, Notes.
- **Recently removed sheet**: rows with cover, title, "Restore" tonal and a trash icon. Permanent delete confirm: "Delete for good?" / "Files and notes on this phone are deleted." / "Delete" (error).
- **Import results sheet**: rows with a check or an error icon, "Retry" per failed row, "Open" per success. Removed: "Choose files again".

## 6. Component inventory

| New composable (F) | Replaces | Notes |
|---|---|---|
| `FolioBar` | `NavigationBar` block in `FolioApp` | Glass, 4 items + centre slot, selection variant |
| `NowReadingTile` | — (new) | 56 dp cover, ring, long-press, a11y actions |
| `NowReadingSheet` | `ReadingScreen` | Continue + `ActionRow` |
| `GlassSurface` | — | The glass recipe; takes `hazeState` or falls back |
| `GlassBar` | reader `TopBar`, `BottomBar` | Top and bottom variants |
| `CoverTile` | `BookTile`, `LibraryCover`, `Cover` | Variants: Grid, Shelf, Wanted, Queued; progress bar; one badge |
| `CoverHero` | `BookHero` | Glow, 160 dp cover, shared element key |
| `ActionRow` | `BookPrimaryActions`, `BookActionsSheet` | 6 icon actions, overflow menu |
| `StatusStrip` | `BookProgressCard`, `BookStateSummary` | Three tiles |
| `ReadingShelf` | `ReadingScreen` list | Horizontal covers with progress |
| `ListCard` + `CoverMosaic` | `CollectionsScreen` cards and pills | Tinted ground, fanned covers |
| `LinkCard` | `FolioLinkSection` | Status, address, switch |
| `ActivityList` | `LinkLogList` | 5 lines + sheet |
| `TransferPill` | `TransferBanner`, `StatusSurface` | Glass pill |
| `EmptyState` | `EmptyState` | Keeps the name; adds the vector |
| `SettingsRow` / `SettingsGroup` | `SectionHeading`, `FeatureRow`, the ad-hoc columns | `ListItem`-based |
| `ConfirmSheet` | `RemoveBooksDialog`, detach/attach/delete `AlertDialog`s | Cover(s) + two buttons |
| `TextSizeStepper` | the existing one | Keeps logic, restyled |
| `FolioMotion` | — | Durations, easings, `SharedTransitionScope` keys |

Kept as-is in logic, restyled: `MetadataEditor`, `MetadataLookupSheet`, `HardcoverReadingSheet`, `HardcoverImportSheet`, `HardcoverBookSearchSheet`, `ShelfEditorSheet`, `LibraryRefineSheet` (→ Filter), `ConnectionForm`, `DiscoveryScreen`, `ContentsPanel`, `AppearancePanel`.

Deleted: `ReadingScreen`, `KindleScreen` (its job card moves into the Device queue), `PageHeading`, `DetailLine` (use `ListItem`), `FeatureRow`.

## 7. Implementation plan

Each slice ships on its own, passes the checks in `CLAUDE.md`, and gets a 5-minute phone check. UI tests match on text today (`onNodeWithText`), so every slice updates the strings it changes in the tests it touches; add `testTag`s on the new components as you go.

| # | Slice | Touches | Verify | Risks |
|---|---|---|---|---|
| 1 | **Tokens and tiers.** New colour scheme fallback, type scale, shapes, `FolioMotion`, `GlassSurface` with the API<31 fallback (and Haze if approved), `CoverTile` with spine and progress, `EmptyState` vector. No layout changes. | `Theme.kt`, `FolioDesign.kt`, `Components.kt` | Screenshots light/dark/200% unchanged in structure | Haze decision; dynamic colour overriding the fallback palette |
| 2 | **Shell.** `FolioBar` with 5 slots, `NowReadingTile`, `NowReadingSheet`, Settings as tab 5, `FolioNav` with 4 tabs + routes, predictive back enabled in the manifest, `SharedTransitionLayout` around content. Reading tab removed; its data wiring moves to the sheet. | `FolioApp.kt`, `FolioNav.kt`, `ReadingScreen.kt` (delete), `SettingsScreen.kt` (host only), manifest | `FolioNavTest` updated; `ProductJourneyTest` for tap → reader, long-press → sheet; phone: gesture nav does not fight the tile | Which book is "current" when positions tie; `ReaderActivity` is a separate Activity so the container transform ends at the activity boundary (use an activity transition with the cover bitmap, or accept a fade) |
| 3 | **Library + Want to Read.** Tabs, Reading shelf, captionless grid, Filter sheet, selection bar, Add sheet tiles. | `LibraryArea.kt`, `LibraryScreen.kt`, `LibraryRefineSheet.kt`, `AddBooksSheet.kt`, `CatalogueBookDetails` entry | `LibraryUiTest`, `LargeFontUiTest` at 200% | Grid without captions needs TalkBack descriptions on every tile; generated covers must carry the title |
| 4 | **Book page.** `CoverHero`, `ActionRow`, `StatusStrip`, Progress sheet, overflow menu, `ConfirmSheet` for remove/detach/attach; Record merges into the same screen. | `BookDetails.kt`, `CatalogueBookDetails.kt`, `BookActionsSheet.kt`, `RemoveBooksDialog.kt` | `BookActionsUiTest`, `BookDetailsBusyUiTest`, `CatalogueAttachmentTest` | Dominant-colour extraction from covers (Palette is already in AndroidX? If not, a 16-pixel average suffices); busy-state rules per action |
| 5 | **Device tab.** `LinkCard`, e-readers seen, send queue, `ActivityList`, legacy job row; `FolioLinkSection` reduced to switches in Settings. | `KindleScreen.kt` (replace), `FolioLinkSection.kt`, `DeviceViewModel` read-only additions | `FolioLinkServiceTest`, `DeviceSettingsTest`; phone: switch on/off, copy address, queue a book | Needs a per-device "last seen" and a queue state flow; the kosync store has user, not device name, so names come from the plugin handshake (M4) or default to "E-reader" |
| 6 | **Lists.** `ListCard`, `CoverMosaic`, list detail route, list editor sheet, system lists. | `CollectionsScreen.kt`, `LibraryBasicsSheets.kt`, `LibraryArea.kt`, `FolioNav` | `LibraryBasicsRepositoryTest`; screenshots | Mosaic performance with the in-house cover decoder (cache the 4 thumbs per list) |
| 7 | **Settings.** Groups, rows, Reading defaults sheet, Backup sheet, Advanced with SSH and folder, About. | `SettingsScreen.kt`, `ConnectionForm.kt` (strings to resources) | `ConnectionUiTest`, `DeviceSettingsTest` | ConnectionForm copy is hard-coded and tested by text |
| 8 | **Reader.** `GlassBar`s, book slider, restyled Text/Contents/Devices sheets, Link offer card, cover-to-page opening. | `ReaderActivity.kt` | `ReaderUiTest`; phone: text stays in place when bars toggle (M1 rule), slider scrub on a 12,000-paragraph chapter | Slider needs a positions list from Readium (`publication.positions`), which can be slow on huge books: compute once per book and cache |
| 9 | **Hardcover, lookup, discovery, import results, removed.** Restyle the remaining sheets to the new rows and copy. | `Hardcover*.kt`, `MetadataLookupSheet.kt`, `DiscoveryScreen.kt`, `LibraryBasicsSheets.kt` | `DiscoveryCommitTest`; screenshots | Hardcover schema check must keep passing; no API changes |
| 10 | **Polish pass.** Haptics, reduced-motion, 200% audit on every screen, TalkBack walk-through, string sweep (delete unused `*_hint`/`*_body`). | all | lint `UnusedResources`; a11y scanner | — |

Slices 1–2 unblock everything; 3–7 can go in any order; 8–9 are independent; 10 is last.

## 8. Open questions

1. **Haze for glass?** One new dependency (`dev.chrisbanes.haze`) gives real backdrop blur on API 31+. Without it, every API level gets the tinted fallback. Yes or no?
2. **Centre item: long-press for the sheet, or tap for the sheet with a big Continue?** The spec picks long-press (one tap to read). Confirm.
3. **"Current book" when the Kindle is ahead:** should the tile follow the latest *phone* position only, or the latest position from any device through Folio Link? The spec uses phone first, then Folio Link.
4. **Finished as a system list on the Lists tab, or a filter on the Library tab?** The spec puts it on Lists so the Library stays "what I can read next".
5. **Keep SSH pairing in Settings → Advanced, or drop it once Folio Link delivery (M4) is proven on your Kindle?** The spec keeps it, labelled "Legacy".
