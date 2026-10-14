# Folio 0.5 usability pass

A code and interaction-flow audit of the existing native app. No user study, emulator screenshots or physical-device visual acceptance is claimed.

| Friction found | Change |
| --- | --- |
| Search, sort, layout and scroll could reset on detail/tab navigation | A SaveableStateHolder retains each main destination's UI state |
| Discovery and saved reads were hidden behind one ambiguous button | Separate Discover and Want to Read entry points into the same catalogue flow |
| Library search had no clear action or keyboard completion | Clear-search control, IME Search handling, complete reading-status filters and Clear filters recovery |
| Collection creation permanently occupied browsing space | Dedicated creation sheet, duplicate-name guidance and a focused collection list |
| Metadata save was at the end of a long form; Back discarded a draft | Fixed save surface, dirty-state reporting, discard review, disabled fields during saving and progressive disclosure of advanced fields |
| Hardcover credentials had multiple inconsistent entry flows | One account dialog shared by Settings, discovery and book tracking; failed connection retains the form |
| Book details presented unrelated online/device actions as equal hero buttons | Contextual reading/Hardcover section, expandable metadata and direct links to reading activity and collections |
| Reading began with long setup/plugin instructions and omitted phone-only currently-reading books | Actual reading books first, source-labelled progress, filters, direct Hardcover updates and collapsed optional bridge/help sections |
| Pairing exposed folder browsing before trust/login prerequisites | Address → trust → login → folder order, optional manual fingerprint disclosure and inline validation |
| A completed selected transfer claimed the entire Kindle was up to date | Scope-accurate “Transfer complete” wording |
| Remote inventory showed UUID filenames even for matched books | Known library titles/authors and direct book navigation; paths remain for unmatched books |
| History, inventory and plugin options crowded the daily sync action | Collapsed secondary sections; active/recoverable job and normal sync stay visible |
| Custom-theme containers inherited unrelated default colours | Cohesive light/dark colour roles, shared shapes and section rhythm; wallpaper dynamic colour retained |
| Operation messages behaved differently across feature screens | Shared timed/dismissible snackbar behavior |
| KOReader fraction was mistaken for a percentage in the Hardcover shortcut | Explicit validated fraction-to-percentage conversion with a regression test |

The database, EPUB engine, host-key verification, transfer publication, sync policies and explicit Hardcover publication strategy remain intact. This release does not introduce automatic publishing, EPUB replacement, remote deletion or a new library identity model.

## Acceptance to perform on a phone

Install as an update. Verify existing data and pairing. Search/filter/scroll → open book → Back → change tabs; rotate and recreate. Edit metadata, open keyboard, change cover, cancel Back, discard, save and simulate failure. Create manual/smart collections, reject duplicate names, filter a collection and clear it. Discover → Want to Read → open attached EPUB. Connect/replace/disconnect Hardcover from Settings and from tracking. Confirm 50% KOReader progress proposes 150 pages for a 300-page edition. Check connection prerequisites, service cancellation/resume, stale inventory wording and transfer scope labels. Check TalkBack, 200% fonts, narrow/wide screens, dark/light/dynamic themes and reduced-motion system settings.

Automated Compose flow tests are supplied for retained search, metadata dirty/save behavior, phone-only reading and focused collection creation. Execution requires Android; compilation alone is not a passing UI result.

## Static palette check

Computed contrast for seven actual text/background role pairs in each custom theme: onSurface/surface, onSurfaceVariant/surfaceContainer, primary/surface, onPrimary/primary, and on-container text for primary/secondary/tertiary containers. Minimum: 6.62:1 light, 7.18:1 dark. This checks those fallback colour pairs only; it does not replace device contrast, dynamic-colour, large-font or TalkBack acceptance.
