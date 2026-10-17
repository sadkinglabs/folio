# Third-party notices and incorporation decisions

Folio Android/JVM source is independently written and licensed Apache-2.0. The KOReader plugin under `koreader/folio.koplugin` is independently written and licensed AGPL-3.0-or-later. The APK carries it as an asset (`app/build.gradle.kts` adds `koreader/` to the assets) so Librillo can install it on the e-reader; its source and licence travel together with it. The APK contains no KOReader source. This is not a derived implementation of any researched library manager.

| Used component | Licence | Treatment |
|---|---|---|
| AndroidX Compose/Material3, Room, Lifecycle, Activity | Apache-2.0 | Runtime dependencies and official Material icon vectors. Preserve AOSP notices in distribution. |
| Kotlin standard library / coroutines | Apache-2.0 | Runtime dependencies. |
| mwiede JSch 2.28.8 | BSD-style (JSch); JZlib BSD-style notice | SSH/SFTP dependency. Complete upstream notices in `docs/licenses`. |
| Readium Pdfium adapter 3.4.0 (`readium-adapter-pdfium`) | BSD-3-Clause | PDF reading on the phone (M9). |
| marain87 PdfiumAndroid 1.9.8 and AndroidPdfViewer 3.2.8 (JitPack) | Apache-2.0; the bundled PDFium is BSD-3-Clause, with its own third-party notices (not yet audited) | Pulled in by the Readium Pdfium adapter. JitPack is limited to the `com.github.marain87` group. |
| zhanghai libarchive-android 1.1.6 | Apache-2.0 wrapper; bundled libarchive 3.8.1 BSD-2-Clause | Reads RAR comics (CBR) on the phone (M9). libarchive's RAR and RAR5 readers are its own code, not UnRAR's. junrar was rejected: it is under the UnRAR licence. |
| Prose (prose-reader) scripts | MIT | Parts of the reader's page scripts in `app/src/main/assets/reader/`; licence texts in `app/src/main/assets/reader/licenses/`. |
| Core library desugaring (`desugar_jdk_libs`) | GPL-2.0 with the Classpath Exception | Java library backports compiled into the APK by the Android build. |
| Readium Kotlin toolkit 3.4.0 (`readium-shared`, `readium-streamer`, `readium-navigator`, `readium-navigator-media-tts`) | BSD-3-Clause | The phone reader (EPUB parsing and display, in-book search, read-aloud navigation). |
| AndroidX Media3 1.11.0 (`media3-session`) | Apache-2.0 | Read-aloud playback service; the version Readium's TTS module brings, declared because Librillo uses it directly. |
| AndroidX WorkManager 2.10.0 (`work-runtime-ktx`) | Apache-2.0 | Background Hardcover sync queue (M10). |
| AndroidX Window 1.5.0 (`androidx.window:window`) | Apache-2.0 | Window size and fold posture for the tablet and foldable layouts (M6). Declared explicitly; it was already on the runtime classpath through Compose. |
| Haze 2.0.1 (`dev.chrisbanes.haze:haze`, `haze-blur`) | Apache-2.0 | Backdrop blur for the glass bars. |
| Apache Commons Compress 1.28.0 | Apache-2.0, with the Apache Commons libraries it pulls in (Apache-2.0) | Unpacks the downloaded voice model (`.tar.bz2`). |
| k2-fsa sherpa-onnx v1.13.8 (JitPack AAR) | Apache-2.0; bundles ONNX Runtime (MIT). Its native library also contains piper-phonemize and espeak-ng code (their symbols are in `libsherpa-onnx-c-api.so`); espeak-ng is GPL-3.0-or-later | On-device neural voice for read-aloud (Kokoro). **Audit before any public distribution:** the espeak-ng code in the native library may put GPL-3.0 terms on the APK. Fine for the owner's own build. |
| Kokoro-82M voice model, sherpa-onnx packaging (`kokoro-int8-multi-lang-v1_0`, `kokoro-multi-lang-v1_0`) | Apache-2.0 (its `LICENSE`); the package's `espeak-ng-data/` is espeak-ng data, GPL-3.0-or-later | Downloaded at runtime from the sherpa-onnx GitHub release when the owner chooses; size and SHA-256 checked; not in the APK. |
| Open English WordNet 2025+ (WNDB files from `english-wordnet-2025-plus.zip`, GitHub release `2025-edition`) | CC BY 4.0; derived from Princeton WordNet 3.1 under the WordNet licence | Offline dictionary (M6). Downloaded at runtime from GitHub when the owner chooses (10,982,041 bytes, SHA-256 checked); not in the APK; not changed by Librillo. Full licence texts in `docs/licenses/OpenEnglishWordNet.txt`. Attribution shown in the app (Settings → Licences, and the Define sheet): "Definitions: Open English WordNet 2025+, by the Open English WordNet team, licensed CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). Based on Princeton WordNet 3.1, © 2011 Princeton University, under the WordNet licence. Downloaded from GitHub when you choose to; not changed by Librillo." |
| JUnit 4 | EPL-1.0 | Test dependency only; not in app runtime. |
| Gradle wrapper | Apache-2.0 | Build bootstrap, distribution SHA256 pinned. |
| org.json 20250517 | Public-domain licence | JVM unit-test implementation only; Android uses platform org.json. |
| Lupa 2.8 | MIT | Lua plugin test dependency only; installed separately, not copied into APK/source. |
| Paramiko 5.0.0 | LGPL-2.1 | Optional Python test fixture dependency; installed separately, not copied into source or APK. |

Research-only: Reliure had no top-level licence in the inspected commit; copying is not permitted by this project. Calibre GPL-3.0, KOReader/KOSync AGPL-3.0 and Librera GPL-3.0 implementations are not linked into the Android APK or copied. The separately licensed plugin calls installed KOReader APIs. Calibre Companion/KOReaderSync are researched through publisher descriptions; no source rights established. No protected covers, screenshots or brand assets are included.

Before public distribution, audit the resolved dependency graph, generate a complete notices/SBOM package, and preserve transitive licences. The present notices are not a claim of a completed release/legal audit.

0.3.0 adds Google ML Kit Text Recognition (`com.google.mlkit:text-recognition:16.0.1`) and its bundled Latin OCR model. It is distributed under Google SDK/service terms; it is not an Apache-licensed model. The app’s independent source licence is unchanged. Resolve and preserve the SDK’s own and transitive notices before public distribution. The Hardcover API client is independently implemented from the published schema/API patterns; neither the MIT Hardcover docs repository nor the MIT KOReader Hardcover plugin implementation is copied into the APK. The default system camera and Android photo picker are platform flows.
