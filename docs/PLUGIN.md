# Librillo plugin for KOReader

The plugin keeps KOReader on your e-reader in sync with Librillo on your phone: the same books on both, with their reading status, highlights, notes, bookmarks and collections. Your place in a book goes through KOReader's Progress sync, which Librillo also serves.

It only talks to the phone over your local network: home Wi-Fi or the phone's hotspot. Nothing goes through the internet.

**Not yet checked on a real e-reader.** The plugin's logic is tested on a computer against stand-ins for KOReader, real files and a fake phone (`scripts/check-plugin.py`). KOReader itself has not run it yet. Back up your KOReader folder before the first sync.

## Set up (once)

1. On the e-reader, turn on KOReader's SSH server (Network → SSH server).
2. On the phone, open Librillo, go to the Device tab and tap **Set up**. Choose the name of the library folder on the e-reader (default `Librillo`). Librillo installs the plugin over SSH and leaves a one-time setup file beside it.
3. Restart KOReader. The plugin reads the setup file, then deletes it:
   - Progress sync is pointed at Librillo, with your sign-in, set to sync automatically and to ask before jumping to another place ("Continue from…?"). Choices you already made in Progress sync are kept.
   - Librillo's library folder and **Sync automatically** are set.
4. KOReader says "Librillo is set up". Restart KOReader once more so Progress sync picks it up, then tap **Librillo → Sync now**.
5. Turn KOReader's SSH server off again. SSH is only needed for setup.

To update the plugin, tap **Reinstall** on the phone's Device tab the same way.

The plugin also reads setup files from older Librillo versions (format version 1). Those only set up Progress sync.

## The Librillo menu

In KOReader's main menu (the tools tab), **Librillo**:

- **Sync now**: syncs right away. If Wi-Fi is off, KOReader may offer to turn it on.
- **Sync automatically**: on after setup. See below.
- **Library folder**: the folder inside KOReader's home folder where your books live. Default: `Librillo`.
- **Questions (n)**: only there when the last sync found something only you can decide. Each question shows its choices; pick one, or **Later**. Your answers are sent with the next sync, which starts as soon as you've answered. You can also answer on the phone.
- **About**: one line on what the plugin does.

That is the whole menu. If the phone's address changed, a sync finds it on the network by itself.

The older items (Get books from Librillo, Find Librillo on this network, Continue from Librillo automatically, Review phone reading position, Review collection additions and Export reading statistics) are gone. Sync does what they did. Settings they left behind (`folio_folder`, `folio_auto_fetch`, `folio_auto_positions`, `folio_applied_positions`) are no longer read.

## What a sync does

- **Books.** The whole Librillo library is on the e-reader, in `Librillo/Author/Title.epub` (or `.pdf`, `.cbz`, `.cbr`). Books you already had anywhere under KOReader's home folder are sent to Librillo, then moved into the library folder with their notes, history and collections.
- **Deletions.** Deleting a book on the e-reader sends it to Recently removed on the phone, where you can restore it. Removing a book on the phone deletes it from the e-reader.
- **Edits.** A new title or author on the phone renames the file on the e-reader. A new version of a book replaces the file in place, so its notes stay.
- **Status.** Reading, Finished and On hold, both ways.
- **Highlights, notes and bookmarks.** Merged both ways. Nothing is ever deleted.
- **Collections.** KOReader collections and Librillo lists match by name.

When both sides changed the same thing, nothing changes until you answer a question, on either device.

After a sync you tap, a short message says what happened, for example "3 books sent, 1 received, 2 updated."

## When it syncs by itself

With **Sync automatically** on, the plugin checks with the phone:

- when Wi-Fi connects,
- when you close a book,
- every 5 minutes while Wi-Fi is on.

Each check is one small request. A full sync runs only when something changed on either side, or when the e-reader has never synced. The plugin never turns Wi-Fi on by itself. Automatic syncs stay quiet unless something arrived or changed, or a new question needs your answer. If the phone can't be reached, the checks space out, up to every 80 minutes, until Wi-Fi reconnects or you close a book.

On the phone, sync works while Librillo is open and for 30 minutes after you leave it.

Progress sync's own "Auto sync" needs KOReader's "Action when Wi-Fi is off" set to "turn on" on some devices; KOReader switches Auto sync off otherwise. Librillo doesn't change that setting.

## Safety

- The book open in the reader is never changed. Anything for it waits for the next sync ("Close the book you're reading, then sync again").
- A file is never overwritten. A new book only goes to a free name. A new version replaces a file only after the plugin checks the download's SHA-256 and that the file there is the one the phone expects.
- Deleted books first go to a hidden folder (`.librillo/trash` in the home folder). They are removed only after the phone confirms the sync; otherwise they are put back, with their notes.
- If a folder or a book can't be read, the sync stops instead of sending an incomplete list, which would look like deletions.
- Files with names the phone can't take (a backslash or control characters) are left out and never touched. Folders starting with a dot, KOReader's `.sdr` folders and KOReader's own folder are skipped.
- Books are hashed once; the plugin keeps the hashes in `settings/folio-hashes.lua`, by path, size and time. The first sync of a large library can take a few minutes.
- The plugin keeps its settings in KOReader's reader settings under `folio_sync`. It logs nothing: no sign-in, no keys, no book text.

## Formats

EPUB, PDF, CBZ and CBR. Other files are ignored and never touched.

Highlights from the phone appear as highlights in EPUB books. In PDFs and comics they appear as page bookmarks with the highlighted text and your note. A note the plugin can't place in KOReader is still kept and sent back, so it is never lost.

## Licence

This plugin is independently written and licensed AGPL-3.0-or-later. `licenses/AGPL-3.0.txt` has the full licence. If you share a modified version, share its source too. The Android app is Apache-2.0. It carries this plugin's files only to install them, and contains no KOReader code.
