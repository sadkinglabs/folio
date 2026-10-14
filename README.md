# Folio

Folio is a personal Android library and reading companion for EPUBs:

- **One library:** book records with or without an EPUB, lists (Want to Read pinned), covers and metadata editing.
- **Discovery:** Hardcover search, cover-photo text recognition and Hardcover profile import, plus a configurable external book search.
- **Reading:** an in-app reader (Readium) that remembers your position for each EPUB revision.
- **Reading devices:** send EPUBs to KOReader (currently a Kindle over pinned-key SFTP), read progress and notes back, and keep a phone folder paired with the device folder.
- **Hardcover:** reviewed status and page-progress updates.

No account is needed for local books or device transfer.

**Status:** 0.10.1 is a debug engineering build. Physical-device and authenticated-Hardcover acceptance is outstanding. The current plan is [docs/UPGRADE_PLAN.md](docs/UPGRADE_PLAN.md), and release notes are in [CHANGELOG.md](CHANGELOG.md).

## Build

Material3 1.5.0-beta01 is a documented beta exception needed for the public MaterialExpressiveTheme API; stable 1.4.0 keeps that theme internal. Other foundational dependencies are stable.

Open this directory in Android Studio with a **JDK 17** and Android SDK 37.0. Install Android platform 37.0, Build Tools 36.0.0 and platform-tools. SDK root goes in your local `local.properties` or `ANDROID_HOME`; the environment-specific file generated in the build workspace is intentionally excluded from the source archive.

```sh
./gradlew checkFormatting :core:test :transport:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Format source with `./gradlew formatKotlin`; `checkFormatting` verifies the pinned ktfmt 0.63 style. Android lint is the native static-analysis check.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

**Signing continuity.** Builds are signed with the preserved development key in `development-signing/debug.keystore`. That folder is excluded from git, so keep a backup of the key. Without it, new APKs cannot update the installed app without uninstalling it. For CI APKs to install over your app, store the key as a repository secret:

```sh
base64 -w0 development-signing/debug.keystore   # paste the output into the FOLIO_DEBUG_KEYSTORE_B64 secret
```

This is a debug key, not a production release key. Release signing is a separate, deliberate decision.

Instrumentation/UI tests need an emulator or phone. The complete suite includes a product journey that seeds fixture books, captures native screens and uses the real document picker. Run the complete suite only on a disposable Android 10+ emulator: ProductJourneyTest refuses physical hardware and non-fixture library records before replacing fixture data.

```sh
./gradlew :app:connectedDebugAndroidTest
```

For the regression suite on a phone, exclude the emulator-only product fixture:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=dev.sadking.folio.ProductJourneyTest
```

If a cached Kotlin rebuild reports duplicate overloads after a broad refactor, run `./gradlew :app:clean` before retrying. Release validation uses a clean app build; incremental compiler invalidation remains a tooling follow-up.

Compile test APK without a device:

```sh
./gradlew :app:assembleDebugAndroidTest
```

Real local SSH/SFTP adapter tests (test-only Python dependency, no Kindle required):

```sh
python3 -m pip install paramiko==5.0.0 lupa==2.8
python3 scripts/check-with-sftp.py
python3 scripts/check-migration.py
python3 scripts/check-plugin.py
```

The script creates disposable ECDSA keys and a localhost-only SFTP server, tests key authentication, pin rejection, inventory, byte-verified upload, retry and symlink handling, then closes and deletes everything. Default `:transport:test` skips these integration cases if the fixture environment is absent. Core tests always run. No passing-but-skipped integration tests should be counted as validated.

## Kindle setup

1. Use an already jailbroken Kindle with KOReader. Folio does not perform the jailbreak.
2. Enable your phone hotspot and connect the Kindle to it, or connect both devices to the same trusted Wi-Fi network. The SFTP book transfer uses local traffic and needs no internet access. Guest-network client isolation can block connections. Keep the Kindle awake during transfer.
3. In KOReader, look under **Tools → Network → SSH server** (labels vary by release). Confirm the displayed address and port; built-in setups often use **2222**, whereas another jailbreak SSH server may use 22.
4. Ensure the server offers SFTP. An SSH shell alone is insufficient. KOReader's documented package supplies Dropbear and an SFTP subsystem, but verify your installed build.
5. Use the existing `/mnt/us/documents` folder, or choose **Choose Kindle folder** after trusting the host key and selecting a login mode. The native browser lists real folders under `/mnt/us`, lets you create a named subfolder, and never follows symlinks. No terminal is required.
6. Prefer a public key: generate an ECDSA key pair (`ssh-keygen -t ecdsa -m PEM`), then place the public key in the authorized-keys location displayed by KOReader's SSH settings. Transfer your private key securely to your phone, select it using the app's key picker and enter its passphrase if required. The app encrypts the imported key in private storage. A password configured in your SSH server is also supported. For temporary phone-only access, the separate KOReader password-free option below is also supported; it requires enabling that setting in KOReader.
7. Use **Read Kindle fingerprint** in Folio, confirm the IP shown on the Kindle, and explicitly trust its observed key. This is trust on first use. For independent verification, obtain the server's **SHA256 host fingerprint through a trusted route**, such as the device terminal or an independently verified SSH connection. If a trusted host public key file is available, use `ssh-keygen -lf <host-public-key> -E sha256`. A network `ssh-keyscan` alone does not authenticate the server; independently confirm its output before trusting it. Host-key paths vary by installation.
8. In Folio → Kindle → setup, enter host, port, username, folder and verified fingerprint. Save the key/password. Then tap **Check Kindle & compare** to authenticate and compare.

See the [official KOReader SSH instructions](https://github.com/koreader/koreader/wiki/SSH) for installation-specific authorized-key details. On many Kindles the user is `root`; do not assume this for every server.

More setup detail, including the phone-only KOReader password-free option and its risks, is in [CHANGELOG.md](CHANGELOG.md) under 0.3.1.

## Safety and troubleshooting

* Fingerprint mismatch: verify the device identity independently. Never replace the pin merely to dismiss an error.
* Connection fails: confirm Wi-Fi, displayed SSH port, host, authorized key/password and server enabled. No obsolete SSH algorithms are automatically enabled.
* SFTP failure: verify subsystem, existing folder, permissions, free storage and Kindle awake.
* Interrupted upload: reconnect and preview again. Completed destination bytes are reconciled; partially staged files are not valid books. Process death may leave `.folio-*.part` files; remove these manually only after confirming there is no active transfer.
* Edited transferred book: blocked conflict is expected. Safe reading-state-preserving replacements are a later feature.
* Use Settings → Export library backup, then test restore with a disposable library. Uninstalling removes private storage. Android auto-backup remains disabled; portable exports omit Keystore-bound SSH credentials.
* Manual sync is foreground ViewModel work, not a guaranteed background job. Process termination may interrupt it. The Android app never writes live KOReader sidecars, collection.lua or statistics.sqlite3. Optional collection changes run inside KOReader only after plugin confirmation.
* Do not run another tool that modifies the same managed destination while Folio publishes files; cross-client rename locking is not implemented.

## Project guides

- [Upgrade plan](docs/UPGRADE_PLAN.md)
- [Architecture and sync](docs/ARCHITECTURE.md)
- [Design system](docs/DESIGN.md)
- [KOReader plugin](docs/PLUGIN.md)
- [Discovery and Hardcover](docs/DISCOVERY.md)
- [Validation record](docs/VALIDATION.md)
- [Research](docs/RESEARCH.md)
- [History: per-release notes and validation records](docs/history/)
- [Licensing](THIRD_PARTY.md)

## Licence

- The Android and JVM code is licensed under Apache-2.0.
- The optional KOReader plugin is licensed under AGPL-3.0-or-later; its licence is in its directory.
- Runtime dependencies keep their own licences.
