# Third-party notices and incorporation decisions

Folio Android/JVM source is independently written and licensed Apache-2.0. The optional, separately distributed KOReader plugin under `koreader/folio.koplugin` is independently written and licensed AGPL-3.0-or-later; its source and licence travel together. The APK contains no plugin or KOReader source. This is not a derived implementation of any researched library manager.

| Used component | Licence | Treatment |
|---|---|---|
| AndroidX Compose/Material3, Room, Lifecycle, Activity | Apache-2.0 | Runtime dependencies and official Material icon vectors. Preserve AOSP notices in distribution. |
| Kotlin standard library / coroutines | Apache-2.0 | Runtime dependencies. |
| mwiede JSch 2.28.8 | BSD-style (JSch); JZlib BSD-style notice | SSH/SFTP dependency. Complete upstream notices in `docs/licenses`. |
| JUnit 4 | EPL-1.0 | Test dependency only; not in app runtime. |
| Gradle wrapper | Apache-2.0 | Build bootstrap, distribution SHA256 pinned. |
| org.json 20250517 | Public-domain licence | JVM unit-test implementation only; Android uses platform org.json. |
| Lupa 2.8 | MIT | Lua plugin test dependency only; installed separately, not copied into APK/source. |
| Paramiko 5.0.0 | LGPL-2.1 | Optional Python test fixture dependency; installed separately, not copied into source or APK. |

Research-only: Reliure had no top-level licence in the inspected commit; copying is not permitted by this project. Calibre GPL-3.0, KOReader/KOSync AGPL-3.0 and Librera GPL-3.0 implementations are not linked into the Android APK or copied. The separately licensed plugin calls installed KOReader APIs. Calibre Companion/KOReaderSync are researched through publisher descriptions; no source rights established. No protected covers, screenshots or brand assets are included.

Before public distribution, audit the resolved dependency graph, generate a complete notices/SBOM package, and preserve transitive licences. The present notices are not a claim of a completed release/legal audit.

0.3.0 adds Google ML Kit Text Recognition (`com.google.mlkit:text-recognition:16.0.1`) and its bundled Latin OCR model. It is distributed under Google SDK/service terms; it is not an Apache-licensed model. The app’s independent source licence is unchanged. Resolve and preserve the SDK’s own and transitive notices before public distribution. The Hardcover API client is independently implemented from the published schema/API patterns; neither the MIT Hardcover docs repository nor the MIT KOReader Hardcover plugin implementation is copied into the APK. The default system camera and Android photo picker are platform flows.
