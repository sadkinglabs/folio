# M12 Stats: the user interface

Design spec, 2026-10-17. The owner's choices are in `UPGRADE_PLAN.md` § M12; the data contract and the pure core are in `STATS_DESIGN.md` (written in parallel). This file says what the screens look like, which Material component each element is, and what every string says. Workers build it literally.

Mockups in code: `app/src/main/java/dev/sadking/folio/ui/StatsPreviews.kt` and `ui/stats/` (`StatsModels.kt` placeholders, `StatsCharts.kt`, `StatsScreen.kt`). Rendered PNGs come from `./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest.stats*' -Pscreenshots` into `app/build/screenshots/` (`stats-*.png`, `details-stats-*.png`, `year-*.png`, `tablet-stats-*.png`). Match them.

Material 3 `1.5.0-beta01` (`gradle/libs.versions.toml`, `material`). Every Expressive API named below was checked in that aar (`material3-android-1.5.0-beta01/classes.jar`): `MaterialExpressiveTheme` (already used by `FolioTheme`), `ToggleButton` + `ButtonGroupDefaults.connected*ButtonShapes()` + `ButtonGroupDefaults.ConnectedSpaceBetween`, `LoadingIndicator`, `LinearWavyProgressIndicator`, `CircularWavyProgressIndicator`, `MaterialShapes.*` with `RoundedPolygon.toShape()` and `Morph.toPath()`, `HorizontalMultiBrowseCarousel`, `HorizontalUncontainedCarousel`, `CarouselItemScope.maskClip`, `rememberCarouselState`, `MaterialTheme.motionScheme` (`defaultSpatialSpec`, `fastSpatialSpec`, `slowSpatialSpec`, `*EffectsSpec`), `Typography.*Emphasized`, `LargeFlexibleTopAppBar`, `HorizontalFloatingToolbar` (already used by the selection bar). `SplitButton` exists but is not used here.

---

## 1. Principles for this milestone

- **The number first, then the picture.** Every panel leads with one figure in words a reader would say ("14 h 20 min", "6-day streak"), and the chart under it is evidence, not decoration.
- **Charts follow the dataviz method.** Form by the data's job; one hue for magnitude, the fixed eight-slot palette for book identity, never a rainbow; thin marks, hairline grid, a legend whenever two series share a chart, text never in a series colour; every value a chart shows is also in text somewhere (a tile, a sheet row, a TalkBack description).
- **Reuse before inventing.** `StatusStrip` tiles, `SectionHeading`, `SheetHeading`, `FolioSheet`, `EmptyState`, `ResultRow`, `CoverTile`, `Tooltipped`, `cappedTextStyle`, `FolioSpace`, `Panes`. New composables are only the charts, the badge, and the panels that compose those.
- **Expressive where it carries meaning:** the connected toggle group for the range, shape badges for milestones and the streak, wavy progress for goals, the loading indicator, carousels for books and milestones, emphasized type for the hero figure, motion-scheme springs for everything that moves.

## 2. Information architecture

### 2.1 Where Stats lives

- A new tab, **Stats**, `FolioNav.STATS`. Order in `FolioNav`: `LIBRARY = 0, LISTS = 1, STATS = 2, DEVICE = 3, SETTINGS = 4`, `TABS = 5`. Icon `Icons.Outlined.Insights` / `Icons.Filled.Insights`. Label `stats_tab` "Stats". Rail: the same item in `FolioRail`, between Lists and Device.
- **The phone bar has a conflict.** The Now Reading tile is centred over the bar and takes one slot's width. Five tabs plus the tile make six slots, and six equal slots cannot put the tile at the centre. The mockup `stats-bar-light.png` shows the recommended compromise: six equal slots, tabs Library, Lists · tile · Stats, Device, Settings, the tile over the third slot (34 dp left of centre). The alternative is in § 10 for the owner. `FolioBar` keeps its glass and shape; only the `Spacer` moves before `STATS`, and `NowReadingTile` aligns to the spacer's slot (`BoxWithConstraints`, `offset(x = slot * 2.5f - nowReadingTileSize / 2)`) instead of `Alignment.TopCenter`.
- Stats is a root tab: back exits the app, like the others. Re-tapping the tab scrolls to the top.
- The tab's content scrolls under the glass bar and pads its end by `LocalContentBottomPadding` (like Library and Lists). `FolioApp` must not add the bar's height as padding for `STATS` (the `DEVICE || SETTINGS` branch stays as it is).

### 2.2 Levels

| Level | What | How you get there | Back |
|---|---|---|---|
| Tab | **Stats dashboard**: title, range, hero, calendar, habits, books, milestones, years, goal offer, sources line. One `LazyColumn`. | Bar / rail | Exits |
| Route | **Year in review** (`Route.Year(year)`): the share card, Share, top books, books by month, records, firsts. | Tap a year row, or its Share icon (opens the route and starts the share) | Pops to the tab |
| Route | **Book page** (existing `Route.Book`) now has a **Reading stats card** under the status strip. | Tap a book anywhere in Stats (legend, Books row, Day sheet, top books) | As today |
| Sheet | **Day** (`FolioSheet`): one day's total, its sessions through the day, a row per book. | Tap a calendar day, or a trend column in Week/Month | Dismisses |
| Sheet | **Goals** (`FolioSheet`): two goals with switches and steppers, Save. | The flag icon in the title row; the "Set a goal" button | Dismisses |
| Sheet | **Book stats** (`FolioSheet`): the book's full numbers, a taller timeline, by device, recent sessions. | Tap the Reading stats card on the book page | Dismisses |

Calendar month paging and the range group are state inside the tab, not routes: back never undoes them. Both survive rotation (`rememberSaveable`). The range defaults to **Month**; the calendar opens on the current month.

### 2.3 Tablet and foldable (`FolioLayout.TWO_PANE`, 840 dp or a hinge)

`StatsTab` uses `Panes(twoPane = true, maxWidth = null)`:

- **Left pane** (`listPaneWidthDp`, 360–400 dp, or up to the hinge): the title row, then the **Calendar card**, then, once a day is tapped, that day's details inline in a `surfaceContainerLow` panel (the Day sheet's content, same composable `DayDetailsContent`). No Day sheet on a tablet.
- **Right pane** (the rest, centred at most 840 dp): the dashboard without the title row and without the calendar section (`StatsScreen(calendarInline = false, showTitle = false)`).
- Rail (600–839 dp, no hinge): one pane, the phone layout, centred at `PANE_MAX_WIDTH_DP` (640 dp).
- The Year in review route fills the window's content area on every layout (like the scan screen), with its own `TopAppBar`.

Mockups: `tablet-stats-light.png`, `tablet-stats-landscape-light.png`, `foldable-stats-light.png`.

## 3. The Stats dashboard (phone, 412 dp)

Composable: `StatsScreen(state, range, actions, selectedTrend, calendarInline, showTitle)` inside `StatsTab`. `LazyColumn`, `contentPadding(top = 12.dp, bottom = LocalContentBottomPadding + FolioSpace.section)`, `verticalArrangement = spacedBy(FolioSpace.section)`. Sections sit inside `FolioSpace.page` (20 dp) side padding; the two carousels bleed to the edges and use `contentPadding(horizontal = FolioSpace.page)` instead.

Panels: `Surface(shape = FolioSpace.panelShape, color = surfaceContainerLow)` with `FolioSpace.item` (16 dp) inner padding. No outlines, no shadows (tier 2).

### 3.1 Title row

Same pattern as Lists: `Text(stats_title, headlineMedium; headlineSmall when fontScale > 1.5)` with `semantics { heading() }`, weight 1; trailing `Tooltipped(stats_goals) { IconButton { Icon(Icons.Outlined.Flag, stats_goals) } }`. Padding `start = page, end = gap`.

### 3.2 Range group

`RangeGroup`: a `Row(horizontalArrangement = spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween))` of four `ToggleButton`s, each `Modifier.weight(1f).semantics { role = Role.RadioButton }`, shapes `ButtonGroupDefaults.connectedLeadingButtonShapes()` for the first, `connectedTrailingButtonShapes()` for the last, `connectedMiddleButtonShapes()` otherwise. Labels `stats_range_week` "Week", `stats_range_month` "Month", `stats_range_year` "Year", `stats_range_all` "All" in `cappedTextStyle(labelLarge, 1.3f)`, one line; `contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)`, so all four words fit a 412 dp phone at 200% text. Exactly one is checked; `onCheckedChange` with `false` is ignored. The group scopes the hero, the Books row and the Habits panel. The calendar, milestones and years ignore it.

### 3.3 Hero panel (`HeroPanel`)

Inner column, `spacedBy(FolioSpace.gap)`:

1. Eyebrow: `labelSmall` uppercase `onSurfaceVariant`: `stats_eyebrow_week` "This week", `stats_eyebrow_month` "This month", `stats_eyebrow_year` "This year", `stats_eyebrow_all` "All time".
2. **Hero figure**: `durationText(total)` ("14 h 20 min") in `cappedTextStyle(MaterialTheme.typography.displayMediumEmphasized, 1.3f)`, one line. Sans, not serif: numbers stay in the UI face; serif is for titles. Counts reading and listening together; the legend under the chart separates them.
3. Facts line, `bodyMedium onSurfaceVariant`: `stats_joined("%1$s · %2$s")` of `stats_days_of` "18 of 31 days" and the plural `stats_sessions_count` "42 sessions".
4. **Trend chart** (§ 6.1), then its legend (`ChartLegend`): "Reading" dot in `primary`, "Listening" dot in the listening colour.
5. **Trend line** (when there is a previous range to compare): `Icons.Outlined.TrendingUp` or `TrendingDown` at 18 dp in `primary`, then `labelLarge`: `stats_trend_up` "Up 12% on September", `stats_trend_down` "Down 8% on September", `stats_trend_same` "Same as September".
6. **Tiles**: `StatusStrip` (reused from the book page) with three `StatusTile`s: `Icons.Outlined.CalendarToday` `stats_tile_days` "Days" · value "15" · `stats_tile_days_detail` "of 31"; `Icons.Outlined.Timer` `stats_tile_sessions` "Sessions" · "42" · `stats_tile_sessions_detail` "26 min each"; `Icons.Outlined.Speed` `stats_tile_pace` "Pace" · `stats_wpm` "248 wpm" · `stats_tile_pace_detail` "both devices" (`stats_tile_pace_phone` "phone" while phone-only). Details are kept to about twelve characters: a tile is 108 dp wide on a phone and 80 dp in a foldable's pane, and `StatusStrip` shows one line. It stacks the tiles at fontScale > 1.3.
7. **Goals progress** (only when a goal is set), § 3.9.
8. Source note, `bodySmall onSurfaceVariant`, only in these states: `stats_sync_pending` "Your e-reader sends its history at the next sync." (paired, nothing received yet) or `stats_phone_only` "Phone only so far. Your e-reader joins at the next sync." (no e-reader data in the range).

### 3.4 Calendar section

`SectionHeading(stats_calendar "Calendar")` then `CalendarCard` (a panel with `FolioSpace.gap` padding):

- Header row: `Tooltipped(stats_previous_month) { IconButton(Icons.Outlined.ChevronLeft) }`, `Text(month title, titleMedium, centred, weight 1, heading)`, the next-month button (`stats_next_month`). Month title comes from the core, locale-formatted ("October 2026"). Next is disabled on the current month.
- Weekday letters `M T W T F S S` in `labelSmall onSurfaceVariant`, centred per column (Monday first; use the locale's first day when the core supplies it).
- **Month grid** (§ 6.2). Tap a day → Day sheet (phone) or the inline day panel (tablet).
- Under the grid: `CalendarLegend` (§ 6.2) or, with nothing read, `stats_calendar_empty` "Nothing read this month." in `bodyMedium onSurfaceVariant`.
- Month change animates with `AnimatedContent` (slide 24 dp towards the new month + fade, `MaterialTheme.motionScheme.fastSpatialSpec()`; fade only under reduced motion).

### 3.5 Habits section

`SectionHeading(stats_habits "Habits")` then `HabitsCard` (a panel, `spacedBy(FolioSpace.item)`):

1. **Streak row**: `ShapeBadge(MaterialShapes.Cookie9Sided, 64.dp, primaryContainer)` holding the streak number in `headlineSmallEmphasized` (sans, `onPrimaryContainer`), then a column: `stats_streak_days` plural "6-day streak" (`titleMedium`) and `stats_streak_longest` "Longest 23 days · March 2026" (`bodyMedium onSurfaceVariant`). With no streak: `stats_streak_none` "No streak yet" and `stats_streak_none_body` "Read two days in a row to start one.", the badge showing "0".
2. `stats_when_you_read` "When you read" (`titleSmall`) over the **hour chart** (§ 6.3).
3. `stats_which_days` "Which days" (`titleSmall`) over the **weekday chart** (§ 6.4).
4. `StatusStrip` with three tiles: `Icons.Outlined.Timer` `stats_tile_session` "Session" · "24 min" · `stats_tile_session_detail` "longest 1 h 50" (`compactDurationText`: "1 h 50", "48 min"); `Icons.Outlined.TrendingUp` `stats_tile_trend` "Trend" · "+12%" / "−8%" / "—" · the previous range's name ("September"); `Icons.Outlined.Headphones` `stats_tile_listening` "Listening" · "1 h 31 min" · `stats_share_of_time` "12% of total".

A streak counts days with at least 1 minute of reading, in the phone's time zone; listening does not count (owner, 2026-10-17). The core defines it.

### 3.6 Books row

`SectionHeading(stats_books "Books", stats_books_line "In this range")`, then `HorizontalUncontainedCarousel(state = rememberCarouselState { n }, itemWidth = 104.dp, itemSpacing = FolioSpace.gap, contentPadding = horizontal page)`. Each item: a `Column(width 104 dp, clip tileShape, clickable Button, padding 4 dp inside the clip so the rounded corner never cuts the label)` with `CoverTile(book, variant = CoverVariant.Shelf)` and the time under it (`labelMedium`, one line). Longest first. `semantics(mergeDescendants)` with `stats_book_a11y` "%1$s, %2$s" (title, time). Tap → the book page. Empty: `stats_books_empty` "No books in this range yet." (`bodyMedium onSurfaceVariant`).

### 3.7 Milestones row

`SectionHeading(stats_milestones "Milestones")`, then `HorizontalMultiBrowseCarousel(state, preferredItemWidth = 156.dp, itemSpacing = FolioSpace.small, contentPadding = horizontal page)`. Each item: `Surface(height 176 dp, Modifier.maskClip(FolioSpace.panelShape), color = surfaceContainer)`; inside, `FolioSpace.item` padding: `ShapeBadge(shape by kind, 56.dp, tertiaryContainer)` with the kind's icon in `onTertiaryContainer`, then title (`titleSmall`, 2 lines), detail (`labelMedium`, 1 line), date (`labelSmall onSurfaceVariant`). The text column has `Modifier.graphicsLayer { alpha = if (carouselItemDrawInfo.size < 120 dp in px) 0f else 1f }`, so the small item at the edge of the multi-browse layout shows its badge only instead of text cut mid-word. Newest first. Not tappable in this milestone. Empty: `stats_milestones_empty` "Finish a book or read a week in a row and the first ones show here."

| Kind | Title | Detail | Shape (`MaterialShapes`) | Icon (`Icons.Outlined`) |
|---|---|---|---|---|
| First book finished | `stats_milestone_first_finish` | the title | `Flower` | `AutoStories` |
| 10 / 25 / 50 / 100 books | `stats_milestone_books` "%1$d books" | "This year" or "All time" | `Cookie9Sided` | `LibraryBooks` |
| 100 / 500 / 1000 hours | `stats_milestone_hours` "%1$d hours" | "Reading time" | `Sunny` | `Schedule` |
| Longest streak | `stats_milestone_streak` | "23 days" | `SoftBurst` | `LocalFireDepartment` |
| Fastest finish | `stats_milestone_fastest` | "Salt and Iron in 3 days" | `Gem` | `Bolt` |
| Biggest day | `stats_milestone_biggest_day` | "4 h 10 min" | `VerySunny` | `WbSunny` |
| First listen | `stats_milestone_first_listen` | the title | `Clover4Leaf` | `Headphones` |
| A full year | `stats_milestone_year` | "2026" | `Cookie12Sided` | `CalendarMonth` |

### 3.8 Years

`SectionHeading(stats_years "Years")`, then one `YearRow` panel per year, newest first: the year in `titleLarge` serif (`FontFamily.Serif`), a column with `stats_year_line` "17 books · 142 h" (or `stats_year_hardcover` "31 books · from Hardcover" for years that only Hardcover knows) and, when there is one, `stats_streak_longest` as a `labelSmall` second line; trailing `Tooltipped(stats_share) { FilledTonalIconButton { Icon(Icons.Outlined.Share) } }`. The row is `clickable(role = Button, onClickLabel = stats_open_year "Open 2026 in review")`. Hardcover-only years open a review with the counts and the covers it has, no hours, no habits.

### 3.9 Goals

- **Off** (default): a `GoalsOffer` panel at the end of the dashboard: `Icon(Icons.Outlined.Flag, tint primary)`, `stats_goal_set_title` "Set a goal" (`titleMedium`), `stats_goal_set_body` "Books this year, minutes a day, or both." (`bodyMedium onSurfaceVariant`), `FilledTonalButton(stats_goal_set "Set a goal")`. Also shown under the empty state.
- **On**: the panel goes; `GoalsProgress` appears inside the hero under the tiles:
  - Minutes a day: `CircularWavyProgressIndicator(progress = { today / target }, Modifier.size(44.dp))` beside `stats_goal_minutes_progress` "14 of 30 min today" (`titleMedium`) and `stats_goal_left_today` "16 min to go" or `stats_goal_done_today` "Done for today" (`bodySmall`).
  - Books a year: `stats_goal_books_progress` "17 of 24 books" (`titleMedium`) with, right-aligned in `labelMedium onSurfaceVariant`, `stats_goal_on_pace` "On pace" / plural `stats_goal_ahead` "2 books ahead of pace" / plural `stats_goal_behind` "2 books behind pace"; under it `LinearWavyProgressIndicator(progress = { soFar / target }, fillMaxWidth)`.
  - Pace = target × (day of year ÷ days in year), rounded down. The wavy indicators use their default colours (`primary` on the track).
- **Goals sheet** (`GoalsSheetContent` in a `FolioSheet` with `surfaceContainerHigh`, 28 dp): `SheetHeading(stats_goals "Goals", stats_goals_body "Only on this phone. Nothing is sent anywhere.")`; two `GoalRow`s: a `Switch` row (`titleMedium` title: `stats_goal_books_year` "Books this year", `stats_goal_minutes_day` "Minutes a day"), and while on, a stepper row: `FilledTonalIconButton(Icons.Outlined.Remove, stats_fewer "Fewer")`, the value in `headlineSmallEmphasized` (72 dp wide, centred), `FilledTonalIconButton(Icons.Outlined.Add, stats_more "More")`. Defaults 12 books and 20 minutes; steps 1 book and 5 minutes; minimums 1 and 5. Then `Button(stats_save "Save")` full width and, when a goal is on, `TextButton(stats_goals_off "Turn goals off")`. Haptic tick on each step (`FolioHaptics.tick`). Stored in the app's preferences; never synced.

### 3.10 Sources line

`stats_sources_line` "Kindle, phone and listening. Earlier years from Hardcover." in `bodySmall onSurfaceVariant` at the very end.

## 4. States

| State | What shows |
|---|---|
| **Loading** (first open, computing) | Title row, then 96 dp below it a centred `LoadingIndicator()` (Expressive, default colours) with `stats_loading` "Counting…" in `bodyMedium onSurfaceVariant`. Sections fade in when ready (`defaultEffectsSpec`). Later recomputations keep the old content; nothing flashes. |
| **Empty** (no sessions at all, no Hardcover history) | Title row; `EmptyState(title = stats_empty_title "No reading yet", message = stats_empty_body "Read on your phone or sync your e-reader, and your stats start here.", illustration = FolioIllustration.Books, action = go to Library, actionLabel = stats_empty_action "Go to Library")`; then the `GoalsOffer` panel. |
| **First sync pending** | Full dashboard from phone data; the hero ends with `stats_sync_pending`. |
| **Phone only so far** | Full dashboard; the hero ends with `stats_phone_only`. The pace tile's detail reads "Phone" instead of "Kindle and phone". |
| **Hardcover years only** (no sessions yet, but a Hardcover history) | Empty state as above, followed by the **Years** section with the Hardcover years, then the goal offer. |
| **Error** (the session store could not be read) | `EmptyState(title = stats_error "Couldn’t read your stats.", message = "", icon = Icons.Outlined.ErrorOutline, action = retry, actionLabel = stats_retry "Try again")`. |
| **Range with nothing in it** (e.g. Week with no reading) | Hero figure "0 min", facts "0 of 7 days · 0 sessions", chart with its two hairlines and labels but no bars, tiles with "0", "0" and "—". Never hidden. |

## 5. Sheets and the book page

### 5.1 Day sheet (`DayDetailsContent`)

`FolioSheet` (`surfaceContainerHigh`). `SheetHeading(title, line)`: title is the day in words from the core ("Tuesday 7 October"; "Today" for today), line is `stats_joined` of the total and the sessions plural ("1 h 24 min · 3 sessions"). Then the **day strip** (§ 6.5) with `FolioSpace.section` side padding. Then one `ResultRow` per book (the existing row: 40 dp cover, title, authors, detail), longest first: detail = "48 min · Kindle · 22 pages" (`stats_joined` nested with `stats_day_pages` "%1$d pages"; sources joined with " · " when a book was read on more than one), trailing a 12 dp dot in the book's palette colour. Tap a row → the book page. Session sources are the device's name from the roster ("Kindle"), `stats_source_phone` "Phone", or `stats_source_listening` "Listening".

### 5.2 Reading stats card on the book page (`BookStatsCard`)

Placed in `BookDetails` right after `StatusStrip` (the `extra()` slot position), only when the book has at least one session; a book without sessions shows nothing (no empty card). Panel `surfaceContainerLow`, clickable as one button; `semantics(mergeDescendants)` with `stats_book_card_a11y` "Reading stats: 4 h 37 min, 11 sessions, 248 wpm".

- Row: eyebrow `stats_book_stats` "Reading stats" (`labelSmall` uppercase) + `Icons.Outlined.ChevronRight` in `onSurfaceVariant`.
- Three `MiniStat`s (value `titleMedium` over an eyebrow label), in a row with weights, stacked at fontScale > 1.3: total time / `stats_book_tile_time` "Time"; sessions / `stats_book_tile_sessions` "Sessions"; `stats_wpm` / `stats_book_tile_pace` "Pace".
- `BookTimeline` (§ 6.6) at 56 dp plot height, `startLabel` = `startedText` ("3 Oct"), `endLabel` = the finish date or `stats_calendar_today` "Today".
- One line, `bodyMedium onSurfaceVariant`: `stats_book_time_left` "About 2 h 30 min left at your pace" (reading, not finished); `stats_book_finished_in` "Finished in 9 days" (finished); `stats_book_days_so_far` "9 days so far" (no pace yet, e.g. no word count).

Mockup: `details-stats-light.png`.

### 5.3 Book stats sheet (`BookStatsContent`)

`FolioSheet`. `SheetHeading(book title, "4 h 37 min · 9 days so far")`. `StatusStrip`: `Icons.Outlined.Timer` `stats_book_tile_time` · reading time (listening is in the By device list); `Icons.Outlined.CalendarToday` `stats_book_tile_sessions` · "11" · `stats_book_started` "Started 3 Oct"; `Icons.Outlined.Speed` `stats_book_tile_pace` · "248 wpm" · `stats_book_pace_detail` "average 240". Then `stats_book_timeline` "Progress over time" (`titleSmall`) over `BookTimeline` at 128 dp. Then `stats_book_time_left` as a line when not finished; `stats_book_by_device` "By device" (`titleSmall`) with a `FactLine` (label left, value right, `bodyMedium`) per device and one for Listening. Then `stats_book_recent_sessions` "Recent sessions" (`titleSmall`) and up to ten rows: `stats_book_session_row` "Kindle · 48 min · 22 pages" (`bodyMedium`) with the start hour right-aligned (`labelMedium onSurfaceVariant`, `hourLabel`: "10 pm"); `stats_book_no_sessions` "No sessions yet. Open the book to start." when none. Date grouping ("Tuesday 7 October") as a `labelSmall` line above each day's rows is the core's call once it supplies dates.

### 5.4 Year in review (`YearReviewScreen`, `Route.Year`)

Plain `TopAppBar` (as the editor uses): title `stats_year_review_title` "2026 in review", navigation `Icons.AutoMirrored.Outlined.ArrowBack` (`shell_back`), action `Tooltipped(stats_share) { IconButton(Icons.Outlined.Share) }`. Content, `verticalScroll`, `FolioSpace.page` sides, `spacedBy(FolioSpace.item)`:

1. The share card, centred, at 72% of the width (max 300 dp): `ScaledShareCard(review, width)`, clipped to `FolioSpace.panelShape`, with `lift(FolioSpace.panelShape)` so it reads as the object you will share.
2. `Button(fillMaxWidth) { Icon(Share); Text(stats_share_image "Share image") }`.
3. `ReviewPanel(stats_year_top_books "Top books")`: up to three `CoverTile`s in a row (weights), the time under each (`labelMedium`), each tappable to its book page.
4. `ReviewPanel(stats_year_by_month "Books by month")`: `MonthBars` (§ 6.7).
5. `ReviewPanel(stats_year_records "Records")`: `FactLine`s: "Longest session" · "2 h 40 min", "Biggest day" · "4 h 10 min", "Longest streak" · "23 days", "Fastest finish" · "Salt and Iron · 3 days".
6. `ReviewPanel(stats_year_firsts "Firsts")`: "First book finished" · "The Orchard · 9 Feb", "First listen" · "…", "First 100 hours" · "12 Aug".

Record and first labels are strings the core emits with its own string ids; the mockup hardcodes them.

## 6. Charts (Compose `Canvas`, no library)

Shared specs, in `StatsCharts.kt`: bars at most 24 dp thick, 4 dp rounded data end, square at the baseline; 2 dp lines with round caps; 8 dp dots (r = 4 dp) with a 2 dp ring in the panel colour; 2 dp gap in the panel colour between touching fills; hairline (1 px) solid gridlines in `outlineVariant`; axis text `labelSmall onSurfaceVariant`; a direct label on the extreme in `labelSmall onSurface`; the tap bubble `labelMedium` on `inverseSurface` with `inverseOnSurface` text, 8 dp corners, 6 dp padding. Text never takes a series colour.

**Colours** (`StatsPalette`):

- Reading: `colorScheme.primary`. Listening: `primary` at 50% composited over `surface` (a lighter step of the same hue). The pair passes the dataviz ordinal checks on the fallback theme (light: monotone L, ΔL ≥ 0.06, light end 2.18:1; dark: 3.73:1). Under dynamic colour the relation holds by construction (same hue, half strength). The legend and the 2 dp gap carry identity as well.
- Books (calendar, day strip): the fixed eight-slot palette below. Light `#2A78D6 #EB6834 #1BAF7A #EDA100 #E87BA4 #008300 #6250D6 #E34948`; dark `#3987E5 #D95926 #199E70 #C98500 #D55181 #008300 #9085E9 #E66767`. Validated with `dataviz/scripts/validate_palette.js` against `#F7F6F0` (light) and `#121410` (dark): lightness band pass, chroma pass, adjacent CVD ΔE 9.1 / 8.4 (≥ 8 target), normal-vision ΔE 19.6 / 19.3 (≥ 15), contrast pass in dark; in light four slots sit under 3:1, so the **legend with titles is mandatory** and the Day sheet names every book. The palette does not follow dynamic colour: the chrome does, the data identity must stay the same in every theme. A book's slot is assigned once by the core (the n-th book ever read takes slot n mod 8) and never changes; two books in one month can share a slot, and then the legend and the Day sheet tell them apart.
- Fold ("more books"): `colorScheme.outline`. Selected day: `secondaryContainer`. Today: `primary` disc, `onPrimary` number.

### 6.1 Trend (`TrendChart`)

- Form: stacked columns, two series (reading, listening). Week: 7 columns labelled by weekday initial; Month: 28–31 columns, labels on 1, 8, 15, 22, 29; Year: 12 columns, month initials; All: one column per year, labelled.
- Size: full panel width, 96 dp plot + 18 dp axis band + 20 dp bubble room above.
- Marks: column width `min(24 dp, 0.62 × slot)`; reading grows from the baseline; listening stacks above behind a 2 dp gap; only the top segment is rounded. Hairlines at the baseline and at the top (the scale's maximum), the maximum written at the top-left as "2 h" / "48 m" in a gutter of its own width + 8 dp; the columns share the width to the right of it, so the first column never covers the label.
- Touch: tap or drag picks the nearest column (the whole slot is the target, not the bar); the bubble reads "Tue · 48 m" / "7 · 48 m". In Week and Month, a tap also opens the Day sheet for that day; in Year a tap sets the calendar to that month and scrolls to it; in All it switches the range to Year for that year.
- TalkBack: `stats_trend_a11y` "14 h 20 min of reading and 1 h 31 min of listening. Most on Saturday 11."
- Sparse data: one column shows one bar; the scale minimum is 1 minute, so a short day is visible but tiny. Nothing is interpolated.

### 6.2 Calendar grid (`CalendarGrid`, `CalendarLegend`)

- Form: a month grid, KOReader style. 7 columns, as many rows as the month needs (5 or 6). Each week row is one `Canvas` under seven tap cells, so a bar can run across cells.
- Cell: the day number at the top-left (`labelMedium`: `onSurface` on days with reading, `onSurfaceVariant` on days without, 38% on future days; on today a `primary` disc, 20 dp or the number's height + 4 dp, with the number in `onPrimary`). At the foot of the cell, a 3 dp `primary` line: that day's total time ÷ the month's biggest day × the cell's width.
- **Joined bars** (owner, 2026-10-18: "the bars are continuous across days where you read the book, so it looks like a long joined line"): a book read on consecutive days is **one bar** across those days, in the book's slot colour, 14 dp tall (the title's height + 2 dp under large text), fully rounded at its real ends and square where it runs on into the row above or below. Up to **three lanes** per week row (two at fontScale > 1.3), 2 dp apart, starting 4 dp under the number. A run that goes on from the row above keeps its lane; then earlier, longer runs come first (`calendarWeeks`, unit-tested). Books that don't fit a lane show as "+2" (`labelSmall onSurfaceVariant`) at the top-right of each of their days, when it fits beside the number (not at 200% text; the Day sheet lists every book).
- The book's **title sits inside its bar** (`labelSmall`, one line, ellipsised) when the bar has 24 dp of room; ink is black 87% or white by the colour's luminance. **Listening-only days are hollow** (1 dp outline in the book's colour, title in `onSurface`); the bar stays joined across them.
- The whole cell is `clickable(role = Button)`; content description `stats_calendar_day_a11y` "7: 1 h 24 min, 3 books" or `stats_calendar_day_none_a11y` "5: no reading". Selected day (tablet) gets a `secondaryContainer` fill.
- Legend under the grid: `FlowRow`, up to six books by time, each an 8 dp dot and the title (`labelMedium onSurfaceVariant`, max 200 dp, one line); then `stats_legend_more` "3 more". Each legend entry is a button to the book page.
- Sparse: a month with one book shows one colour; an empty month shows numbers only and the empty line.
- Why no titles in the cells, unlike KOReader: at 50 dp a title is three letters. The legend and the Day sheet name the books; the bars show rhythm.

### 6.3 Hours (`HourChart`)

24 bars in `primary`, width `slot − 2 dp` (≤ 24 dp), 72 dp plot; baseline hairline; axis labels left-aligned under 0, 6, 12, 18: "Midnight", "6 am", "Noon", "6 pm" (`hourAxisLabels`: only "Midnight" and "Noon" when six hours span less than the measured width of "Midnight" + 8 dp, as in a narrow pane or at 200% text); the busiest hour's label ("10 pm", from `hourLabel`) over its bar in `labelSmall onSurface`. No bubble (the peak is labelled and the values are in the core's table). TalkBack `stats_hours_a11y` "Most reading around 10 pm." Sparse: a single bar still gets its label.

### 6.4 Weekdays (`WeekdayChart`)

7 bars in `primary`, width `0.5 × slot` (≤ 24 dp), 72 dp plot, letters M T W T F S S under them, the busiest bar's value ("3 h 30 m") above it. TalkBack `stats_weekdays_a11y` "Most reading on Sunday."

### 6.5 Day strip (`DayStrip`)

A 20 dp track (`onSurface` at 8%, so it shows on a sheet and on a panel alike; 4 dp corners) for 24 hours; each session a block from its start for its length (minimum 3 dp), in the book's slot colour, hollow when listening; the same four labels as the hour chart. Content description `stats_day_sessions_a11y` "Sessions through the day" (the rows carry the values).

### 6.6 Book timeline (`BookTimeline`)

- Form: a line over time (x = days since the first session, y = 0–100%). Hairlines at 0% and 100%. A 2 dp `primary` line through the session points with a 10% wash under it; 8 dp dots with a ring in the panel colour; **listening sessions hollow**. The last value ("64%") at the line's end in `labelSmall onSurface`; `startLabel` under the left end, `endLabel` under the right.
- Sizes: 56 dp plot on the card, 128 dp in the sheet.
- Touch (sheet only): drag shows a bubble "Day 5 · 46%" at the nearest point; the card is one tap target for the sheet.
- TalkBack `stats_book_timeline_a11y` "From 4% to 64% over 9 days, 11 sessions."
- Sparse: one session is one dot and its label; no line.

### 6.7 Books by month (`MonthBars`)

12 columns in `primary`, letters J … D, the peak's count over its bar. TalkBack `stats_year_by_month_a11y` "17 books this year, most in October." Used in the review and in the share card (56 dp plot).

### 6.8 Goal progress

Not a chart: the wavy indicators of § 3.9 with the number next to them. One ratio against a limit → a meter.

## 7. Share card (`YearShareCard`, `ScaledShareCard`)

- **Size:** designed at 360 × 640 dp; rendered at density 3 → **1080 × 1920** px. `ScaledShareCard(review, width)` wraps the card in `CompositionLocalProvider(LocalDensity provides Density(base.density × width / 360.dp, fontScale = 1f))`, so the same composable is the on-screen preview and the image. Font scale is pinned to 1 in the image.
- **Content and layout** (28 dp padding, `Arrangement.SpaceBetween`): eyebrow `stats_share_eyebrow` "Librillo · year in review"; the year in `displayLarge` with `FontFamily.Serif`; `stats_year_hero_line` "17 books · 142 hours" (`titleLarge`); the top three covers fanned (104 dp wide `CoverTile`s at −10°, 0°, +10°, offsets −72/0/+72 dp, the middle one in front); a 2 × 2 of `MiniStat`s: `stats_share_streak` "Longest streak" · "23 days", `stats_share_hour` "Favourite hour" · "10 pm", `stats_share_pace` "Pace" · "248 wpm", `stats_share_listening` "Listening" · "12 h"; `stats_year_by_month` over `MonthBars`; the wordmark `stats_share_footer` "Librillo" in `titleMedium` serif `onSurfaceVariant`.
- **Colours:** the current colour scheme (dynamic or fallback, light or dark as the phone is): `surface` ground, a radial glow of `primaryContainer` (85% → 0%) centred at (20%, 12%) with radius 90% of the width, text in `onSurface`/`onSurfaceVariant`, bars in `primary`. On-brand because it is the owner's own theme.
- **Rendering:** compose `ScaledShareCard(review, 360.dp)` inside `CompositionLocalProvider(LocalDensity provides Density(3f, 1f))` in an offscreen `Box(Modifier.requiredSize(360.dp, 640.dp).alpha(0f))` with `val layer = rememberGraphicsLayer()` and `Modifier.drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }`. On Share: `layer.toImageBitmap().asAndroidBitmap()` → PNG into `cacheDir/share/year-2026.png` (`Dispatchers.IO`) → `FileProvider.getUriForFile(context, "${applicationId}.photos", file)` → `Intent.ACTION_SEND` with `type = "image/png"`, `EXTRA_STREAM`, `FLAG_GRANT_READ_URI_PERMISSION`, `Intent.createChooser`. Add `<cache-path name="share" path="share/" />` to `res/xml/photo_paths.xml`. While rendering, the Share button shows a `LoadingIndicator` (20 dp) in place of its icon and `stats_share_preparing` "Preparing the image…" as a snackbar; on failure `stats_share_failed` "Couldn’t make the image. Try again." The image carries no book text and no account data.

## 8. Motion

All springs come from `MaterialTheme.motionScheme`; `FolioMotion.reduced` (the system's remove-animations setting) turns every spring below into a snap or a plain fade.

| What | Spring | Reduced motion |
|---|---|---|
| Sections entering when data is ready | fade + 8 dp rise, `defaultSpatialSpec()`, staggered 40 ms per section (first six) | fade only |
| Hero figure on range change | counts from the old value to the new (`animateIntAsState` on total minutes, `slowSpatialSpec()`), text re-layouts each frame | snaps |
| Bars on range change | heights animate from the previous values, `defaultSpatialSpec()` (one `Animatable` per column, keyed by position) | snap |
| Range toggle | the `ToggleButton` checked-shape morph is built in | built in (the component honours the scheme) |
| Calendar month change | `AnimatedContent`, slide 24 dp + fade, `fastSpatialSpec()` | fade |
| Milestone badges first shown | `Morph(MaterialShapes.Circle, kindShape)` drawn with `Morph.toPath(progress)`; progress 0 → 1 with `defaultSpatialSpec()` as the card scrolls into view the first time; never again, never looping | the final shape |
| Streak badge | the number counts up on first show (`slowSpatialSpec()`) | static |
| Goal ring and bar | the wavy indicators animate to the new value themselves; the ring's amplitude follows the component's defaults | the indicators still move only on value change |
| Day sheet, Goals sheet | `FolioSheet` as everywhere | as everywhere |
| Tap bubble on a chart | appears with `fastEffectsSpec()` fade; moves with the finger | appears without fade |

No haptics on charts or navigation. Haptic tick on the goal stepper; confirm on Save.

## 9. Typography and colour roles, in one place

| Element | Role |
|---|---|
| Screen title | `headlineMedium` (serif); `headlineSmall` at fontScale > 1.5 |
| Section titles | `titleLarge` via `SectionHeading` |
| Hero figure | `displayMediumEmphasized`, capped at 1.3× via `cappedTextStyle` |
| Streak number, stepper value | `headlineSmallEmphasized` (sans) |
| Year numerals | `titleLarge` + `FontFamily.Serif` (rows); `displayLarge` + serif (share card) |
| Panel sub-titles ("When you read") | `titleSmall` |
| Tile values | `titleMedium` (as `StatusStrip`) |
| Eyebrows, axis text, legend counts | `labelSmall` uppercase / `labelSmall` |
| Legend labels, bubbles | `labelMedium` |
| Body lines | `bodyMedium`; notes `bodySmall` |

Colours: ground `surface`; panels `surfaceContainerLow`; carousel cards `surfaceContainer`; sheets `surfaceContainerHigh`; badges `primaryContainer` (streak) and `tertiaryContainer` (milestones); marks `primary`; book identity the fixed palette; grid `outlineVariant`; selected day `secondaryContainer`. All from `MaterialTheme.colorScheme`, so dynamic colour and dark mode work without a special case.

## 10. The mockups, by file

All in `app/build/screenshots/` after the render command at the top; each exists as `-light`, `-dark` and `-font200` (200% text).

| File | Shows |
|---|---|
| `stats` (tall) | The whole dashboard on a phone: range, hero, calendar, habits, books, milestones, years, goal offer. |
| `stats-goals` | Goals on: the wavy ring and bar in the hero; a trend column picked, with its bubble. |
| `stats-phone-only` | Week range with weekday labels, and the phone-only note. |
| `stats-empty`, `stats-loading` | The two states. |
| `stats-day` | The Day sheet. |
| `stats-goals-sheet` | The Goals sheet with both goals on. |
| `details-stats` (tall) | The book page with the Reading stats card under the status strip. |
| `stats-book-sheet` (tall) | The book's stats sheet. |
| `year-review` (tall) | The year in review route. |
| `year-share-card` | The share image at design size (360 × 640 dp; the PNG at 3× is the real 1080 × 1920). |
| `tablet-stats`, `tablet-stats-landscape`, `foldable-stats` | The two-pane layout at 840 × 1280, 1280 × 800 and 700 × 840 (half folded, with the hinge). |
| `stats-bar` | The phone bar with six slots and the tile over the third. |

Known limits seen in the renders: in a 360 dp right pane (a tablet in portrait with the list pane at 400 dp) `StatusStrip` tiles are 90 dp wide and "SESSIONS" / "LISTENING" lose their last letters to an ellipsis; the value and the detail stay whole. On a phone every tile fits. If the owner minds, the fix is in `StatusStrip` (stack under 100 dp), not in the Stats screens.

## 11. Open questions for the owner

1. **The bar.** Five tabs plus the centred tile do not fit six equal slots with the tile at the centre. Recommended: tile over the third slot (mockup `stats-bar`), 34 dp left of centre. Alternative: Lists becomes a third top tab inside Library (Library · Want to Read · Lists), the bar stays Library, Stats · tile · Device, Settings, and the tile stays centred. Which?
2. **Streak rule.** Answered: only reading keeps a streak; listening is shown but never counts toward streaks or goals.
3. **Calendar bars.** Answered: joined across consecutive days, KOReader style, with titles inside when they fit (§ 6.2).
4. **Share card theme.** Rendered in the phone's current theme (light or dark, dynamic). Or always light, so it looks the same wherever it is posted?
5. **Goals pace.** "On pace" compares books finished with target × year fraction. Fine, or should the year goal stay a plain count?
6. **Milestone thresholds.** Books at 10/25/50/100 and hours at 100/500/1000. Change any?
