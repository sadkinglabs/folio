# Validation record

This file records what was actually checked for the current state of `main`, newest first. Each check is listed under the level it reached.

The earlier append-only log is in [history/VALIDATION.md](history/VALIDATION.md).

## 2026-10-14: M1 reader (CI, emulator API 34, Pixel 6 profile)

| Check | Level | Result |
|---|---|---|
| Unit, lint, `assembleDebug`, SFTP fixture, check scripts, DOM interop | Executed (CI) | Passed |
| Instrumentation suite, including `MigrationTest`, `ReaderSpineTest`, `ReaderCorpusTest` and `ProductJourneyTest` | Executed (emulator) | 46 / 46 passed (run 15) |
| Reader immersive toggle and text stability | Executed (emulator screenshot) | Controls and system bars hide on a centre tap; text does not move |
| Per-book typography | Executed (JVM: serialisation and clamping). The UI path is compiled and run on the emulator | Passed |
| Real phone: rendering, page turns, rotation, kill and restore | **Not run** | Owner device check |
| KOReader round trip of a phone-captured pointer on a Kindle | **Not run** | M3 |

## 2026-10-14: M0 foundation, in a cloud container (no Android SDK)

Google's Android download host (`dl.google.com`) was blocked by the environment's network policy. So the Android Gradle plugin and SDK were unavailable, and the `:app` module could not be configured.

| Check | Level | Result |
|---|---|---|
| `:core:test` | Executed (JVM, JDK 21 instead of the pinned 17) | 61 / 61 passed |
| `:transport:test`, including the real localhost SFTP fixture | Executed (JVM, JDK 21; fixture keys generated with Python `cryptography`, equivalent to `ssh-keygen -t ecdsa -m PEM`) | 17 / 17 passed, 0 skipped |
| `scripts/check-migration.py` | Executed (host SQLite) | Passed, migrations 1→7 |
| `scripts/check-library-basics.py`, `scripts/check-local-removal.py` | Executed (host SQLite) | Passed |
| `scripts/check-plugin.py` | Executed (Lua via lupa, with KOReader mocked) | Passed. The mock sets `rolling.xpointer` to exactly the requested value, so it hides the page-mode apply bug described in UPGRADE_PLAN §1 |
| `scripts/check-hardcover-schema.py` | Executed (online, against the published schema) | 13 operations valid |
| ktfmt `--kotlinlang-style` check | Executed | Passed |
| `:app` unit tests, lint, `assembleDebug` | **Not run**: no Android SDK | — |
| New `MigrationTest` (every schema 1–6 → 7) | **Not compiled**: no Android SDK | Runs in the new CI emulator job |
| Instrumentation suite | **Not run** | Runs in the new CI emulator job |
| Physical phone, Kindle, authenticated Hardcover | **Not run** | — |
