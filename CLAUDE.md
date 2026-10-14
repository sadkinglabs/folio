# Folio — working rules

Folio is a personal Android EPUB library and reading companion. It covers the phone, KOReader on a Kindle, and Hardcover.
Modules:
- `core`: pure JVM code for EPUB, sync planning, backup and KOReader data.
- `transport`: SFTP using JSch.
- `app`: Compose, Room and Readium.
- `koreader/folio.koplugin`: the KOReader plugin, written in Lua and licensed AGPL.

The current plan is in `docs/UPGRADE_PLAN.md`. Work one milestone at a time.

## Invariants (never break)
- The package is `dev.sadking.folio`. Updates must install over the existing app, signed with the same development key.
- Room: only additive migrations. Never use `fallbackToDestructiveMigration`. Every schema bump needs an exported schema and a `MigrationTestHelper` test.
- Backups: older backup formats must still restore.
- Never silently overwrite an EPUB.
- Never discard annotations or reading data.
- Never treat a file that disappeared as a deletion.
- A book record can exist without an EPUB.
- Never log or commit credentials, tokens or book content.

## Honesty about verification
When reporting, separate these levels:
- executed on a real device;
- executed in the emulator or JVM;
- compiled only;
- not verified.

Never describe a mocked response or a Robolectric test as working on a device.

## Checks
```sh
./gradlew checkFormatting :core:test :transport:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```
Format the code with `./gradlew formatKotlin` (ktfmt).

## Style
- No new layer or dependency without a concrete benefit.
- UI copy goes in `strings.xml`, in plain language.
- Prefer finishing one complete slice over starting several.

## Git
- Commit as the repository owner (`sadkinglabs <jessonio.home@gmail.com>`). Never author, sign or attribute commits, PRs or comments to Claude: no Claude author, no `Co-Authored-By: Claude` or session trailers.

## Communication
- Write to the owner in succinct, plain, direct English: short sentences, no filler, no jargon where a plain word works.
