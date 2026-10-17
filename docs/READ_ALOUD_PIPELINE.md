# Read-aloud pipeline (Kokoro): root cause, design and build contract

Status of this document: design contract and as-built record. The design is implemented (sections 19–21 list what differs from the contract, the measurements and the device steps). Nothing in it has run on a phone or an emulator. Evidence levels:

- **Code trace** (Investigator A): read only, nothing executed.
- **Host measurement** (Investigator B): executed on the JVM/host, x86 Xeon 4 vCPU, Python sherpa-onnx 1.13.8 (the same version as the app's AAR), the exact model package the app downloads. Not a phone.
- **Pixel 9 Pro XL**: no measurement exists yet. Every Pixel figure below is an estimate and is marked so.

Branch `redesign`, HEAD `8791d29`. Readium 3.4.0, sherpa-onnx v1.13.8 (ORT 1.28.2 inside), Media3 1.11.0.

---

## 1. Root cause

Narration stops at full stops for two independent reasons. Either alone produces the symptom.

### R1. Every sentence ends with a deliberate underrun (code trace, confirmed)

`KokoroEngine.speak()` writes the last clause of a sentence and then polls until `track.playbackHeadPosition >= queued` before calling `listener.onDone()` (`KokoroEngine.kt:377-381`). The AudioTrack is therefore empty at every utterance end. The next audio can only arrive after Readium's round trip:

1. `onDone` resumes the facade's continuation: a post to Main (`TtsEngineFacade.kt:83-85`, scope is `Dispatchers.Main`).
2. `TtsPlayer.playContinuous` takes its mutex and calls `tryLoadNextContext()`, which calls `contentIterator.next()` for the utterance after next. At a paragraph or resource boundary that parses the next element or chapter with Jsoup (`TtsPlayer.kt:486-510`).
3. `facade.speak(N+1)` → `KokoroEngine.speak` → hop to the output thread → first write.
4. AudioFlinger restarts the starved track: start threshold, then the full output latency again.

Estimated added silence per boundary: 50–300 ms on an idle phone, unbounded at chapter edges, on top of the model's own 0.16 s gap. "Mr." becomes its own 3-character utterance 9 times in one chapter of the host passage, each one a full drain mid-name. (Correction from the build: Readium 3.4.0 does not split a sentence at `<i>` or `<a>`; only a language change such as `<span lang="fr">` splits it. `UtterancePlannerTest` asserts this.)

### R2. The shipped int8 model is probably slower than real time (host measurement; Pixel unknown)

On the host, `kokoro-int8-multi-lang-v1_0` through sherpa's bundled ORT 1.28.2 synthesises at RTF 1.41–1.56 (synthesis seconds per audio second; above 1 means slower than playback), p95 2.15 for single sentences. 51% of kernel time is `ConvInteger`. The fp32 model of the same voice runs at RTF 0.43 on the same host. The 5-utterance lookahead (`LOOKAHEAD = 5`) empties in about a minute at RTF 1.45; after that every boundary waits for a whole synthesis, which is multi-second pauses exactly at full stops.

Investigator B's Pixel estimate for int8 is RTF 1.2–1.8, but ARM int8 kernels differ from x86 VNNI and the real value could be anywhere from 0.5 to 2. Only the on-device benchmark (section 12) settles this.

### Confirmed aggravating factors (code trace)

- Nothing is prefetched for the first sentence, for the sentence after a speed or voice change (`submitPreferences` clears the cache and no re-prefetch runs until the next location change: two full waits in a row), or for skip-previous.
- Lookahead counts utterances, not seconds.
- `prefetchFrom` re-parses the whole chapter with Jsoup on `Dispatchers.Default` and re-tokenises up to 400 utterances on Main at every sentence, competing with ORT's 4 threads.
- The model reloads on every start and every engine switch (2.3–3.6 s on the host). No warm-up.
- No wake lock while synthesising with no audio playing.
- One sentence that produces no audio stops reading altogether (`onError` → Failure → "Reading aloud stopped.").
- `stop()` runs `pause()+flush()` on Main while the output thread may be inside `WRITE_BLOCKING`: the rest of a chunk can land after the flush and `queued` gets a non-atomic increment after the reset.
- A missed `speak()` waits FIFO behind up to five lookahead jobs; nothing gives it priority.

### Refuted

- **Synthesis blocked by playback.** Separate threads; the write happens on the output thread.
- **Player recreated between sentences.** `track.play()` is conditional; no stop/flush/recreate in normal flow. The restart is AudioFlinger's, caused by the drain (R1).
- **Excessive embedded silence.** Host: the sentence gap is 0.163 s whether a passage is rendered whole in one call, by paragraph, or as separate sentences laid end to end. Leading silence per chunk is 0.06 s, trailing 0.10 s. sherpa's `ScaleSilence` already cuts every pause of 200 ms or more to 20% at `silenceScale` 0.2. Chunking adds no silence. The device benchmark repeats this comparison (section 12, step 3).

### Facts the design depends on (sherpa-onnx v1.13.8, from source and `javap` on the AAR)

- `generateWithConfigAndCallback(text, GenerationConfig, callback)`: the callback fires once per espeak sentence (pieces of 12 tokens or fewer are merged into the previous one), synchronously on the calling thread. The callback's samples summed equal the returned samples exactly. Returning 0 stops before the next piece; a running ORT pass cannot be interrupted.
- `GenerationConfig.silenceScale` of exactly 0.2 falls back to the `OfflineTtsConfig` value.
- Input limit: 510 tokens (`style_dim[0]` of voices.bin), about 1.08 tokens per character. sherpa hard-cuts longer espeak sentences mid-phrase; a 510-token piece is about 22 s of audio and took 32 s to synthesise on the host with int8.
- No internal lock. One inference thread per instance. `release()` deletes the native pointer with no lock.
- Kokoro's `speed` parameter scales the audio length and the synthesis time together: RTF is the same at 1.0× and 1.5× (host: int8 1.41 vs 1.43).
- Audio is about 0.051 s per character at speed 1.0. Per-call overhead on the host: int8 0.29–0.55 s, fp32 0.10–0.19 s. Chunks under 40 chars run at RTF 1.75–1.86; chunks of 150+ chars at 1.38–1.42.
- Threads: best at the core count (host: 4); above it is much worse.

---

## 2. Decisions

Both designs agree on the spine: route B (engine-owned continuous playlist, `onDone` from the playback head), Kokoro's own `speed` parameter, one warm native instance per process, a bounded queue in listening seconds, an fp32 variant, a disk cache, an on-device benchmark. Where they differ:

| Topic | Chosen | From | Why |
|---|---|---|---|
| Sample format | 16-bit PCM everywhere: queue, track, cache | Design 2 | Half the memory and disk of float; `AudioTrack` plays 16-bit natively. The 5 ms edge fade and NaN guard move into the float→16-bit conversion. |
| Sentence splitting | Replace Readium's tokenizer with `kokoroSentenceTokenizer` (ICU + abbreviation backstop), used by both Readium and the planner | Design 2 | Fixes "Mr." at the source, so Readium's utterances and the planner's match by construction. Design 1's merge-after-the-fact leaves Readium with a stray utterance and needs estimated spans. |
| Synthesis unit | One spoken sentence: fragments of one sentence (italics) merged, tiny units (< 40 chars) merged with the next in the same paragraph, cap 300 chars. No multi-sentence grouping. | Design 2 | Host: grouping to 300 chars lowers aggregate RTF only 3.6% but makes highlight boundaries estimates. Variance reduction is covered by the gate's horizon sum instead. |
| Start gate | Horizon deficit with noise summed in quadrature (`z·cv·√Σμ²`), 20 s budget from the tap, TooSlow predicted early | Design 2 | Design 1's `r95·dNext` inflates every unit to p95 and would start later than needed; it also has no horizon so it cannot predict TooSlow before the 20 s cap. Design 1's `0.5 s` fast path falls out of the formula anyway. |
| Throughput model | Linear fit `synth ≈ a + b1·chars/speed` with EW least squares, speed-normalised | Design 2 | Host per-call overhead (0.3–0.55 s int8) is a large share of a short unit; a pure ratio model mispredicts short first units. |
| Pause | `stop()` pauses without flushing; `speak(same)` resumes mid-sentence | Design 2 | Cheaper and better than Readium's restart-from-start. Design 1's "move chunks back to the front" is a special case of the same queue state. |
| Streaming callback | Cancellation probe only; samples from the return value | Design 2 | Exactly-once by construction. The callback gives no sub-sentence latency gain anyway. |
| Silence | `silenceScale` 0.4; trim unit edges to ≤ 50 ms; pad to a target gap by punctuation kind (sentence 0.32 s, paragraph 0.60 s, clause 0.12 s, fragment 0), ÷ speed | Design 2 | Consistent pauses whether or not a unit boundary falls at the punctuation; longer paragraph pauses. Speech is never trimmed. Design 1's "no trimming at all" leaves double edges at merged fragments. |
| Memory bound | No spill-to-disk. Queue never exceeds HIGH water + one unit; chapter preparation writes to the disk cache beyond that | Simplification of both | Removes Design 2's spill/reload machinery. Gate counts cached-ahead units as ready. |
| Disk cache | `filesDir/narration/cache`, 256 MB, LRU | Design 1 location, Design 2 format | `cacheDir` can be purged by the system; the owner asked for reuse to cut startup on later listens. 256 MB ≈ 89 min of 16-bit 24 kHz speech. |
| Runtime idle release | 3 min, plus `onTrimMemory` | Design 2 | A reader pause rarely lasts longer; 10 min holds 114–325 MB for little benefit. |
| TooSlow while screen off | Switch to "with breaks" automatically; offer the choice next time the reader is on screen | Design 2 | A listener in the car must not get silence. |
| Benchmark sustained step | Synthesis loop with a wake lock (no test EPUB, no extra service); the live pipeline is measured by the "Record measurements" switch during a real listen | Simplification of Design 2 | A bundled test EPUB through the real navigator needs library plumbing and a foreground service type; the recorder gives the same numbers on real books. |
| Rebuffer policy | Gate re-evaluated from scratch on a stall (Design 2) and 3 stalls in 5 min → TooSlow sheet (Design 1) | Both | One stall should not open the sheet; a stalling pipeline must not stall forever. |
| Engine package | Pure logic in `dev.sadking.folio.reader.narration`; Android glue stays in `dev.sadking.folio.reader` | Owner's workstream split | Pure classes are JVM-tested with no Robolectric. |

Dropped from both: Design 1's adaptive grouping above r95 0.85; Design 2's spill/reload and the bundled test EPUB; `AudioTrack.setPlaybackParams` (time-stretching would require r < 1/speed).

---

## 3. Architecture

```
ReaderActivity / ReaderChrome (ui)      ListenService (unchanged: wraps the same Readium player)
        │ reads ReadAloud state                     │
        ▼                                           ▼
ReadAloud (Main) ──location──▶ KokoroEngine.onLocation        TtsNavigator / TtsPlayer (Main)
                                     │                                  │ speak / stop / submitPreferences
                                     ▼                                  ▼
                     KokoroEngine : TtsEngine  (Main-confined façade; owns the session)
        UtterancePlanner ─▶ UnitBuilder ─▶ jobs ─▶ KokoroRuntime.SynthesisWorker ("kokoro-synth")
        UtteranceAligner        ▲                           │ UnitReady (posted to Main)
        ThroughputModel         │                           ▼
        StartGate ◀─────── PcmQueue (locked) ◀──────── NarrationCache ("narration-io")
                                │ nextToWrite / onHead
                                ▼
                     AudioTrackSink ("kokoro-audio": one AudioTrack, MODE_STREAM, never flushed at boundaries)
                                │ HeardEvent (posted to Main) ─▶ listener.onStart / onDone
```

### Threads

| Thread | Owns | Never does |
|---|---|---|
| Main | `TtsEngine` calls, all listener callbacks (Readium's facade is not thread-safe), planner state, gate decisions, state flows | native calls, `AudioTrack` calls, Jsoup |
| `kokoro-synth` (one thread, in `KokoroRuntime`) | every `OfflineTts` call: construct, generate, release | waiting on audio, blocking on Main |
| `kokoro-audio` (one thread, `THREAD_PRIORITY_AUDIO`) | `AudioTrack` create/play/pause/flush/write/release; head and underrun reads | synthesis |
| `narration-io` = `Dispatchers.IO.limitedParallelism(1)` | disk cache reads/writes, metrics CSV | — |
| `Dispatchers.Default` | Readium content iteration and Jsoup, driven by the planner | — |

Handoff: `PcmQueue` is guarded by one `ReentrantLock` (writer thread, synth thread and Main touch it). Worker and writer post to Main through `Handler(Looper.getMainLooper())`. Main sends writer commands through a `ConcurrentLinkedQueue<SinkCommand>` followed by `LockSupport.unpark(writerThread)`.

### Session model

`KokoroEngine` holds one `gen: Long` (monotonic) per engine instance. `invalidate()` bumps it on:

- `submitPreferences` with a changed speaker or speed (this arrives before Readium's `restartUtterance` → `stop()` → `speak(current)`, so the engine re-anchors the planner at the current utterance and starts synthesising it at once; both the current and the next unit are in flight before `speak` arrives);
- a `speak(text)` that matches nothing in the plan (seek, skip beyond the window, planner misalignment);
- `onLocation` that rebases to a different place (`go()`, media-session seek);
- `close()` (book change, Kokoro↔phone switch, swipe-away, stop).

Every job, ready unit, written range and posted event carries `gen`. A result or event with a stale `gen` is dropped at every hop: the worker before starting a job, the sherpa callback (returns 0 at the next piece), `PcmQueue.offer`, the Main handler before any listener call. A stale result is still written to the disk cache: its key includes voice and speed, so it is valid audio for a later listen.

`stop()` carries no reason, so it does the cheap reversible thing: `SinkCommand.Pause` (no flush), `held = request`, `listener.onInterrupted(id)` synchronously, `request = null`. The next `speak(text)` decides:

| Next `speak` | Action |
|---|---|
| same utterance as `held`, gen unchanged | `Resume`: the track continues mid-sentence |
| next planned utterance (skip next) | `Flush`, then write from that unit's PCM, already in the queue |
| an utterance in the recent ring (skip previous) | `Flush`, write from the ring |
| gen changed (voice/speed) | `Flush`, synthesise with priority, gate |
| anything else | miss: ad hoc P0 unit from the requested text, invalidate speculative units, wait for `onLocation` to rebase |

Synthesis keeps running while held, under normal water marks, for 30 s; then it idles.

### Native lifetime

`KokoroRuntime` is a process singleton. It keeps the `OfflineTts` loaded across engines, pause, book change and voice change, so the 2.3–3.6 s load is paid once per process. It frees the model only through `synthExecutor.execute { tts.release() }`, which runs after any in-flight `generate` returns, and only when the reference count is 0 and either the 3-minute idle timer fired or `onTrimMemory(TRIM_MEMORY_UI_HIDDEN or worse)` arrived while idle. A variant or thread-count change releases and reloads the same way. Nothing else ever touches the `OfflineTts`.

---

## 4. Numbers (`NarrationTuning`)

All in one `data class` with a `DEFAULT`, so tests can vary them and the owner can tune by ear.

| Name | Value | Note |
|---|---|---|
| `sampleRate` | `tts.sampleRate()` (24 000) | not in the tuning; passed where needed |
| `trackBufferSeconds` | 0.5 | `AudioTrack` buffer |
| `trackStartThresholdSeconds` | 0.05 | `setStartThresholdInFrames` |
| `writerTickMs` | 15 | park between non-blocking writes / head polls |
| `speculativeUtterances` | 2 | write at most up to `lastRequested + 2` … |
| `speculativeSeconds` | 3.0 | … and ≤ 3 s of audio past the end of the last requested utterance |
| `lowWaterSeconds` | 30 | resume synthesis below this |
| `highWaterSeconds` | 60 | stop synthesis at or above this |
| `highWaterStrainedSeconds` | 120 | used while `rEff > 0.8` or thermal ≥ MODERATE |
| `recentRingUnits` / `recentRingSeconds` | 3 / 30 | heard units kept for skip-previous |
| `maxUnitChars` | 300 | split above |
| `hardSplitChars` | 400 | split at whitespace when no punctuation (510-token limit ≈ 470 chars) |
| `firstUnitMaxChars` | 120 | first unit after start / seek / invalidation; split at a clause break ≥ 40 chars |
| `tinyUnitChars` / `tinyMergeMaxChars` | 40 / 200 | merge a tiny unit with the next in the same element if the result ≤ 200 |
| `startBudgetSeconds` | 20 | absolute ceiling from the Listen tap, never a target |
| `gateZ` / `gateGuardSeconds` | 2.0 / 0.4 | noise multiplier; fixed guard |
| `gateDeltaNormal` / `gateDeltaThermal` | 0.05 / 0.15 | synthesis-time inflation |
| `tooSlowRatio` | 0.97 | `rEff` at or above this is TooSlow (with ≥ 1 unit measured this session, or a prior with n ≥ 20; the warm-up does not count) |
| `starvationSeconds` | 0.02 | written-ahead below this with nothing ready = stall |
| `preparingDebounceMs` | 300 | before `Preparing` shows after a stall |
| `stallsForTooSlow` / `stallWindowSeconds` | 3 / 300 | 3 stalls in 5 min opens the TooSlow sheet |
| `holdSynthesisSeconds` | 30 | synthesis continues this long after `stop()` |
| `silenceScale` | 0.4 | per call and in the config (never exactly 0.2 per call) |
| `edgeSilenceThreshold` | 0.005 | about −46 dBFS |
| `edgeKeepSeconds` | 0.05 | trim edge silence to at most this on each side |
| `gapSentence` / `gapParagraph` / `gapClause` / `gapFragment` | 0.32 / 0.60 / 0.12 / 0.0 s | inserted after a unit, ÷ speed |
| `fadeSamples` | 120 | 5 ms at 24 kHz, each edge |
| `cacheBudgetBytes` / `cacheEvictToFraction` | 256 MiB / 0.9 | |
| `runtimeIdleReleaseMs` | 180 000 | |
| `plannerAheadSeconds` | `highWater + 30` | plan while predicted audio ahead of the write cursor is below this |
| `plannerCapUtterances` / `rebaseScanLimit` | 600 / 400 | |
| `alignWindow` | 3 | `speak` matched at `lastRequested+1..+3` |
| `metricsRing` | 200 | unit records kept in memory |
| `thermalSampleMs` | 10 000 | |
| `wakeLockTimeoutMs` | 600 000 | renewed per unit |
| `audioSecondsPerCharPrior` | 0.051 | host measurement |
| default prior int8 | a 0.40 s, b1 0.0765 s/char (RTF 1.5), cv 0.25, n 0 | a prior with n 0 can never say TooSlow by itself |
| default prior fp32 | a 0.15 s, b1 0.0357 s/char (RTF 0.7), cv 0.25, n 0 | Pixel estimate, not the host 0.43 |
| default threads | 4 | `availableProcessors().coerceIn(2, 4)` is 4 on the Tensor G4; the benchmark may store a better value |

Memory bound of the queue: `highWaterStrainedSeconds + one unit (≤ 15 s) + recent ring 30 s` ≈ 165 s × 48 KB/s ≈ 7.9 MB.

---

## 5. Text pipeline

### 5.1 `kokoroSentenceTokenizer` (engine file, pure backstop in pipeline)

`internal val kokoroSentenceTokenizer: (Language?) -> Tokenizer<String, IntRange>` replaces `sentenceTokenizer` and is passed to `TtsNavigatorFactory`. It wraps `DefaultTextContentTokenizer(TextUnit.Sentence, Language("en-US"))` (the language argument is ignored: Kokoro is English-only, so Readium's and the planner's calls produce the same ranges whatever language each passes) and post-processes its ranges with the pure `AbbreviationJoiner.join(text, ranges)`, which merges range *i* into *i+1* when range *i*, trimmed, ends with:

- `\b(Mr|Mrs|Ms|Dr|Prof|St|Sr|Jr|Mt|vs|etc)\.` ;
- `\bNo\.` only when the next range starts with a digit;
- `\b(e\.g|i\.e)\.` ;
- a single capital initial `\b[A-Z]\.` .

Readium's own `sanitizeRange` (trim end, keep ranges with a letter or digit) runs after, inside Readium.

### 5.2 `UtterancePlanner` (engine) → `PlannedUtterance` (pipeline data)

The planner mirrors `TtsUtteranceIterator` exactly: `publication.content(anchor).iterator()`; per element `TextContentTokenizer(language = settings.language, overrideContentLanguage = false, textTokenizerFactory = kokoroSentenceTokenizer).tokenize(element)`; one utterance per `TextElement` segment, one per non-blank `TextualElement`; keep only `text.any { it.isLetterOrDigit() }`. One forward walk per anchor, extended lazily on `Dispatchers.Default` while predicted audio ahead of the write cursor is below `plannerAheadSeconds`, capped at `plannerCapUtterances`. It crosses resources, so chapter transitions have audio ready. The planner keeps `locators: Map<Long, Locator>` for its own use; the pure data carries `href: String`.

Anchor: `ReadAloud.start` passes the start locator to `KokoroEngineProvider(context, startAt, tappedAtMs)`; the engine anchors the planner and starts synthesising unit 1 at construction, in parallel with Readium's own first-resource parse. `null` means the start of the publication, as in Readium.

`onLocation(locator, text)` is called from `ReadAloud.attach`'s location collect and replaces `prefetchFrom`: if `normalize(text)` equals the key at `lastRequestedIndex`, nothing; otherwise `rebase(locator)`: `invalidate`, new walk from `locator`, skip until the key matches within `rebaseScanLimit`. Not found: lookahead off until the next location, every `speak` is a miss, metric `alignMiss++` (today's silent failure, now counted).

### 5.3 `SpeechText` (pipeline)

Only what Kokoro hears. Matching, highlighting and alignment use the original text.

- `Mr.`→`Mister`, `Mrs.`→`Missus`, `Ms.`→`Miz`, `Dr.`→`Doctor` (when followed by a capitalised word), `Prof.`→`Professor`, `e.g.`→`for example`, `i.e.`→`that is`, `vs.`→`versus`, `etc.`→`et cetera`. `St.` is left alone (Saint/Street ambiguity).
- Strip U+00AD and U+200B–U+200D, U+FEFF. Collapse whitespace. Preserve all punctuation.
- `SpeechText.VERSION = 1` is part of the cache key.

### 5.4 `UnitBuilder` (pipeline): utterances → `SynthesisUnit`

Rules in order, applied incrementally (`push` emits units once the following utterance is known; `flush` emits the rest):

1. **Fragment merge.** Join consecutive utterances of the same element while the previous one does not end a sentence (`endsSentence` = trimmed text ends in `.!?…` optionally followed by `"”’)]`). Covers italics and links.
2. **Tiny merge.** A unit under `tinyUnitChars` joins the next unit of the same element if the result ≤ `tinyMergeMaxChars`.
3. **Long split.** Over `maxUnitChars`: split at `[;:—–]\s`, then `,\s`, then whitespace, into parts of 80–300 chars; over `hardSplitChars` with no break: last whitespace before 400. Mid-sentence splits get `GapKind.CLAUSE`. An utterance can span two units; `Done` fires after the last.
4. **First unit.** After `restart()`: if over `firstUnitMaxChars`, split at the first clause break at ≥ 40 chars. Cuts time to first audio; one intonation reset in one sentence is the cost.

`gapAfter`: `PARAGRAPH` when the unit ends its element, `SENTENCE` when it ends a sentence, `CLAUSE` after a long split, `FRAGMENT` otherwise.

### 5.5 `UtteranceSpans` (pipeline)

Single-utterance units (the common case) have exact boundaries. For multi-utterance units, boundary *k* is estimated by cumulative speech-char share of the voiced frames and snapped to the centre of the quietest 20 ms window within ±0.4 s. Highlight error inside merged fragments is a fraction of a second; documented trade-off.

### 5.6 `UtteranceAligner` (pipeline)

`speak(text)` is matched by `normalize(text)` against `held` first, then the plan at `lastRequested+1..+alignWindow`, then the recent ring (newest first). Held comes first so a repeated short sentence ("No.") resumes after a pause instead of skipping ahead. Hit sets `lastRequested`. Miss → section 3 table.

---

## 6. Synthesis worker (`KokoroRuntime`, engine)

```
loop on kokoro-synth:
  job = jobs.take()                              // PriorityBlockingQueue by (priority, seq); P0 = unit Readium waits on
  if job.gen != currentGen(): continue
  pcm = synthesizer.synthesize(job.speechText, sid, speed) { currentGen() == job.gen }
  post to Main: UnitReady(job, pcm, synthMs, audioMs, cacheHit = false)
```

- `SherpaSynthesizer` calls `tts.generateWithConfigAndCallback(text, GenerationConfig(sid = sid, speed = speed, silenceScale = tuning.silenceScale)) { if (keepGoing()) 1 else 0 }` and converts `GeneratedAudio.samples` with `Pcm16.fromFloat` (NaN/Inf → 0, clamp, 5 ms fades). The callback never reads its samples. **Each sample plays exactly once.**
- Scheduling is done on Main by `KokoroEngine`: P0 for the unit holding Readium's request if not ready; P1 for following units in plan order while `WaterMarks.shouldSynthesize(reserveSeconds)`; at most 2 jobs queued at any time, so invalidation is cheap. Before a P1 job is queued, `NarrationCache.read(key)` runs on `narration-io`; a hit becomes `UnitReady(cacheHit = true)` without touching the synth thread.
- Failure: retry once; on a second failure, `onError` if Readium waits on that unit, else mark failed and retry when requested. Empty audio: 0.2 s of silence so the utterance is heard and Readium moves on; 3 consecutive empties → `onError`.
- After load, the runtime runs one warm-up job (`"Ready."`, default speaker) only when no request is waiting, and the engine records it as a priming observation (weight 0.5). It moves the fit but is not a measured unit: it never counts as evidence for TooSlow.

---

## 7. Queue, gate and throughput

### `PcmQueue` (pipeline, locked)

Holds ready units (not yet written, plan order), written ranges (track-frame coordinates since the last flush), the recent ring, and per-unit write offsets for partial writes. `reserveSeconds(head)` = contiguous ready frames from the head plus written-unheard frames, ÷ sampleRate. Audio is generated at the chosen speed, so audio seconds are listening seconds; the API takes `listeningSecondsPerFrame` so a later time-stretch needs no other change. Gaps belong to the preceding unit.

### `ThroughputModel` (pipeline)

Per observation (synthesised units only, not cache hits): chars `c`, speed `v`, synth seconds `s`, audio seconds `d` (without the inserted gap). Fits `s ≈ a + b1·c/v` with exponentially weighted least squares (λ 0.9), `a ∈ [0, 2]`, `b1 > 0`; `α1` (audio s per char at 1×) as an EWMA; `cv` = EW sd of `s/ŝ − 1`, floor 0.05, prior 0.25 until 5 observations. Saved priors per `(variant, threads)` in `SharedPreferences("narration_priors")`: `a, b1, alpha1, cv, n, updatedAt`; loaded with `cv × 1.5`; blended with session observations by count until 3 observations.

`rEff(units, v)` = Σ predicted synth ÷ Σ predicted audio over the next known pending units, inflated by `(1 + δ)`. `pace = 1 / rEff` is what the sheet shows.

### `StartGate` (pipeline)

Inputs: `B` = reserve seconds (ready + cached-ahead + written-unheard), `R` = remaining predicted seconds of the in-flight unit, pending units with predicted `μᵢ` and `dᵢ`, budget remaining, thermal flag.

```
ready_j  = R + Σ_{i=2..j} μ_i + z·cv·√(Σ_{i≤j} μ_i²) + G
needed_j = B + Σ_{i<j} d_i
eta      = max(0, max_j (ready_j − needed_j))        j over the known horizon (≤ 400 units / 30 min)
Start    ⇔ at least one whole unit ready and eta == 0
TooSlow  ⇔ rEff ≥ tooSlowRatio (with ≥ 1 observation this session or a prior with n ≥ 20)
           or eta > remaining budget
Wait(eta) otherwise
```

If `rEff < 1` the maximum deficit lies inside the known units, so no tail extrapolation is needed; if `rEff ≥ 1` the deficit grows without limit. When every `μᵢ < dᵢ₋₁` the gate returns Start as soon as unit 1 is ready: on a fast phone the first sound comes after one short unit's synthesis, nothing more. Evaluated on Main at every `UnitReady`, every `speak`/resume, and every 250 ms while `Preparing` so the ETA counts down.

Simulated waits from the tap with host-like parameters (Design 2's `gate.py`; first unit 3 s of audio, then 4 s units; add 2.3–3.6 s if the runtime is cold): fp32-like (RTF 0.4, cv 0.07) starts at about 2.0 s; r 0.7 at 4.0 s; r 0.9 at 8.7 s with "Preparing… about 6 s"; r ≥ 0.97 is TooSlow within 4–8 s. These are model numbers.

**Rebuffer.** The writer reports starvation when written-ahead < `starvationSeconds`, the next unit is not ready and the track is playing. It pauses at the unit boundary (stalls never happen mid-word), posts `Starved`; Main shows `Preparing(eta)` after `preparingDebounceMs` and re-evaluates the gate with a fresh budget; resumes only when the whole horizon is safe. `stallsForTooSlow` stalls within `stallWindowSeconds` → TooSlow.

### `WaterMarks` (pipeline)

Hysteresis: synthesis stops at ≥ HIGH and resumes below LOW; HIGH = `highWaterStrainedSeconds` while strained.

---

## 8. Continuous output (`AudioTrackSink`, engine)

One `AudioTrack` per engine: `USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`, `ENCODING_PCM_16BIT`, mono, `MODE_STREAM`, buffer `trackBufferSeconds`, `setStartThresholdInFrames(sampleRate × trackStartThresholdSeconds)`. Created, used and released only on `kokoro-audio`. Never stopped, flushed or recreated at a unit or utterance boundary.

```
while running:
  drain commands (Play, Pause, Resume, Flush, Release)          // single owner: fixes the stop()/WRITE_BLOCKING race
  if playing:
    while track has room:
      item = queue.nextToWrite(maxIndex = lastRequested + speculativeUtterances,
                               maxFramesPastRequest = speculativeSeconds·sampleRate, head) ?: break
      n = track.write(item.pcm, item.offset, item.count, WRITE_NON_BLOCKING)   // then the gap as zeros
      n < 0: ERROR_DEAD_OBJECT → recreate once and rewrite from the current utterance start; else post Error
      queue.markWritten(item.seq, n)
    head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    post HeardEvents from queue.onHead(head); metrics ← track.underrunCount delta
    starvation check
  parkNanos(writerTickMs) or until a command arrives
```

On Main, `KokoroEngine` maps events to Readium: `HeardEvent.Started(i)` with a bound request → `onStart(id)`; `HeardEvent.Done(i)` with a bound request → `onDone(id)`, `request = null`; `Done(i)` with no request yet (Readium's round trip is slower than the utterance, e.g. a chapter parse) → remember `heardAhead = i`; when `speak` for *i* arrives, `onStart` then `onDone` fire at once. The highlight catches up; the audio never waited. This is what ties highlight, page turn, `model.listened` saves and the end-of-chapter sleep timer to audio actually heard.

Speculation bound: at most `speculativeUtterances` and `speculativeSeconds` past the last requested utterance, plus the 0.5 s track buffer, so a mismatched `speak` flushes ≤ 0.5 s and replays from retained PCM.

Wake lock: `PARTIAL_WAKE_LOCK "librillo:narration"` (permission already declared), held while status is `Loading` or `Preparing`, or while the worker is busy in a non-held session; timeout `wakeLockTimeoutMs`, renewed per unit; released when paused (after the in-flight unit), idle or closed.

---

## 9. Silence

`silenceScale` 0.4 in `OfflineTtsConfig` and in every `GenerationConfig`. Edge silence of each unit (|x| ≤ `edgeSilenceThreshold`) trimmed to ≤ `edgeKeepSeconds` per side; then zeros appended to make the boundary pause equal `gapAfter` ÷ speed. Speech is never trimmed. Host reference: boundary gap 0.16 s at scale 0.2, 0.42 s at 0.5, 0.83 s at 1.0; synthesis time does not change with the scale, so a higher scale is more audio per compute second. These are listening choices tuned by ear on the device.

---

## 10. Disk cache (`NarrationCache`, engine; key in pipeline)

`filesDir/narration/cache/<sha256>.pcm`: 16-byte header (magic `NAR1`, sampleRate `Int`, frames `Int`, reserved `Int`), body 16-bit little-endian mono, edge-trimmed, without the inserted gap. Filenames are hashes; no text on disk. Key = SHA-256 of `"v1|$modelId|sid=$sid|speed=$speed|silence=$silenceScale|en-us|${SpeechText.VERSION}|$speechText"` where `modelId = "$variant:$packageSha256"`. Threads are not in the key. Budget `cacheBudgetBytes`, LRU by `lastModified` (touched on read), evict after each write to `cacheEvictToFraction`, index built by one directory scan at runtime start, atomic writes (`.tmp` + rename) on `narration-io`, corrupt entries deleted and treated as misses. Units of the chapter being prepared or played are pinned. `KokoroModel.remove(variant)` clears that variant's entries. Not part of Folio backups.

---

## 11. Speed, variants, startup

**Speed** stays Kokoro's `speed` argument. A change → `invalidate`; the queue is dropped; the disk cache keeps the old speed's audio; the planner re-primes from the current utterance at once; Readium's `restartUtterance` → `stop` → `speak(N)` goes through the gate with speed-normalised priors. `ReadAloud.speed` follows `engine.settings`, so a media-controller change updates the label (today it goes stale). `getPlaybackParameters` keeps reporting the speed; Readium never applies it.

**Variants.** `KokoroModel.Variant.INT8` = today's `voices/kokoro-v1_0`, 132 303 094 B, sha `4c3052ab…`, `model.int8.onnx` (unchanged, so existing installs keep working). `Variant.FP32` = `voices/kokoro-v1_0-fp32` from `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2`, 349 906 910 B, SHA-256 `c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298` (computed by Design 2 from the asset Investigator B downloaded; **re-verify when implementing**), keep `model.onnx`, `voices.bin`, `tokens.txt`, `lexicon-us-en.txt`, `LICENSE`, `espeak-ng-data/`. Variant used: the installed one with the best saved `rEff` prior; with none, fp32 if installed, else int8; a manual override in the Listening sheet.

**Startup sequence.** `ReadAloud.start` records `tappedAtMs`, calls `KokoroRuntime.preload(context, config)` (no-op when warm), then creates the navigator with `KokoroEngineProvider(app, startAt = from, tappedAtMs)`. `createEngine` acquires the runtime, builds the engine, which anchors the planner and queues unit 1 (capped at `firstUnitMaxChars`) as P0 immediately. Status `Loading` until the runtime is ready, then `Preparing`. Readium's `speak(id1, text1)` binds to planned utterance 0 (or within the window). The gate opens → `Play` → first head movement = time to first audible speech, measured from `tappedAtMs`.

---

## 12. Measurement

### `NarrationMetrics` (pipeline math) and `NarrationRecorder` (engine capture)

Always in memory: counters, reserve and pace flows, last `metricsRing` unit records. CSV files only when "Record read-aloud measurements" is on, under `filesDir/narration/sessions/<start-ms>/`, with a Share action and an `adb` path. Lengths and timings only; never text (CLAUDE.md).

- `units.csv`: `t_ms, gen, seq, utterances, chars, cache_hit, queue_wait_ms, synth_ms, audio_ms, rtf, edge_lead_ms, edge_trail_ms, gap_ms, reserve_ms_after, thermal_status, thermal_headroom`.
- `boundaries.csv`: `t_ms, from_seq, to_seq, readium_roundtrip_ms, heard_ahead, stall_ms, underruns_delta`.
- `summary.json`: `tap_to_first_audio_ms, model_load_ms, first_unit_synth_ms, gate_predicted_wait_ms, gate_actual_wait_ms, listen_s, stalls, stall_ms_total, underruns_total, rtf_mean, rtf_p95, rtf_first_5min, rtf_last_5min, reserve_min_s, reserve_mean_s, reserve_max_s, thermal_max, screen_off_rtf_mean, screen_on_rtf_mean, variant, threads, speed, silence_scale, align_miss, readium_late`.
- `Log.d("Narration", …)`: numbers only, debug builds only.

### `NarrationBenchmark` (engine runner, ui screen): "Test read-aloud speed"

Entry: the Listening sheet. Material: `res/raw/narration_passage.txt` = Investigator B's public-domain passage (Pride and Prejudice ch. 1 + the "e.g./i.e." sentence + the Tale of Two Cities opener), so device results compare directly with the host CSVs. No book content is used. The screen keeps itself on; a wake lock covers the sustained step.

1. **Load**: cold `OfflineTts` construction per installed variant.
2. **Sweep**: variant × threads {2, 3, 4, 5, 6}, the passage through the real `UnitBuilder`, 60 s of audio each: per-unit RTF, mean, p95, cv, first-unit seconds, fitted `a`/`b1`. One experimental row each for provider `nnapi` and `xnnpack` at the best thread count (sherpa falls back to CPU with a log line; record "fell back"). About 10–14 min with two variants.
3. **Diagnostic render** at the best config: (a) the whole passage in one call, (b) each unit separately. Save both WAVs and silence statistics (boundary gap p50/p95, lead/trail). This is the owner's item 1 on the device.
4. **Sustained**: 5 / 15 / 30 min synthesis loop at the best config with the screen off allowed, logging RTF per unit and thermal status/headroom every 10 s; reports drift (last 2 min vs first 2 min).
5. **Result**: plain-language card and a table; writes `narration_priors` and `bestThreads`; exports `filesDir/narration/bench-<start-ms>/` (CSV, JSON, WAV) through Share.

---

## 13. Workstreams

Three parallel workstreams with disjoint file lists. Only the owner of a file edits it. Everything that crosses a boundary is specified below; a workstream that needs a change in another's file asks the lead instead of editing.

### 13.1 Pipeline (pure Kotlin, Android-free, JVM-tested)

**Owns** `app/src/main/java/dev/sadking/folio/reader/narration/` and `app/src/test/java/dev/sadking/folio/reader/narration/`. No `android.*`, `androidx.*`, Readium or sherpa imports. `java.*` and kotlin stdlib only. All types `internal`.

Files: `NarrationTuning.kt`, `Pcm16.kt`, `EdgeSilence.kt`, `SpeechText.kt`, `AbbreviationJoiner.kt`, `UtterancePlan.kt` (`PlannedUtterance`, `GapKind`, `SynthesisUnit`, `UnitBuilder`), `UtteranceSpans.kt`, `UtteranceAligner.kt`, `PcmQueue.kt` (`ReadyUnit`, `Span`, `WriteItem`, `HeardEvent`, `QueueSnapshot`, `PcmQueue`), `WaterMarks.kt`, `ThroughputModel.kt` (`ThroughputPrior`, `ThroughputModel`), `StartGate.kt` (`PendingUnit`, `GateInput`, `GateDecision`, `StartGate`), `CacheKey.kt`, `NarrationStatus.kt` (`NarrationStatus`, `SlowChoice`), `NarrationMetrics.kt` (`UnitRecord`, `BoundaryRecord`, `SessionSummary`, `NarrationMetrics`), `BenchmarkReport.kt` (`BenchmarkRow`, `BenchmarkReport`, `BenchmarkStats`).

### 13.2 Engine (KokoroEngine rework and Android glue)

**Owns** in `app/src/main/java/dev/sadking/folio/reader/`: `KokoroEngine.kt`, `KokoroRuntime.kt` (new), `AudioTrackSink.kt` (new), `UtterancePlanner.kt` (new), `KokoroSentenceTokenizer.kt` (new), `NarrationCache.kt` (new), `NarrationRecorder.kt` (new), `NarrationBenchmark.kt` (new), `KokoroModel.kt`, `ReadAloud.kt`, `ListenService.kt` (only if needed). Also `app/src/main/res/raw/narration_passage.txt` (new), `app/src/androidTest/java/dev/sadking/folio/NarrationSinkTest.kt` (new), `app/src/androidTest/java/dev/sadking/folio/NarrationBenchmarkTest.kt` (new), `app/src/test/java/dev/sadking/folio/reader/KokoroModelTest.kt`, `app/src/test/java/dev/sadking/folio/reader/ReadAloudTest.kt`, and new Robolectric tests `app/src/test/java/dev/sadking/folio/reader/KokoroSentenceTokenizerTest.kt`, `UtterancePlannerTest.kt`, `NarrationSessionTest.kt`, `NarrationCacheTest.kt`. Deletes from `KokoroEngine.kt`: `sentenceTokenizer`, `sentencesAfter`, `utteranceTexts`, `clauses`, `cleanAudio`, `prefetchFrom`, `dropAhead`, `audio`, `queued`, the drain loop. Keeps `KokoroVoice`, `KOKORO_VOICES`, `KokoroSettings`, `KokoroPreferences`, `KokoroPreferencesEditor`, `KokoroError`, `normalize`.

### 13.3 UI

**Owns** `app/src/main/java/dev/sadking/folio/reader/ReaderChrome.kt`, `ReaderActivity.kt`, `ReaderPreviews.kt`, `NarrationSheets.kt` (new: TooSlow sheet, benchmark sheet, chapter-preparation progress), `app/src/main/res/values/strings_narration.xml` (new; nothing added to `strings.xml`), `app/src/test/java/dev/sadking/folio/ui/ScreenshotTest.kt`, `app/src/test/java/dev/sadking/folio/reader/ReaderChromeNarrationTest.kt` (new), and `ReaderViewModel.kt` only if a change is unavoidable (say so).

Nobody edits `AndroidManifest.xml`, `gradle/`, `ListenService.kt` (unless engine says so) or `docs/` except the lead.

---

## 14. Boundary contracts: exact signatures

### 14.1 Pipeline → Engine (package `dev.sadking.folio.reader.narration`)

Threading rule for every pipeline type: **`PcmQueue` is thread-safe (internally locked). Every other class is confined to the thread that created it (Main in the engine) and is not synchronised.** Functions are pure or mutate only their own object.

```kotlin
package dev.sadking.folio.reader.narration

internal data class NarrationTuning(
    val trackBufferSeconds: Double = 0.5,
    val trackStartThresholdSeconds: Double = 0.05,
    val writerTickMs: Long = 15,
    val speculativeUtterances: Int = 2,
    val speculativeSeconds: Double = 3.0,
    val lowWaterSeconds: Double = 30.0,
    val highWaterSeconds: Double = 60.0,
    val highWaterStrainedSeconds: Double = 120.0,
    val strainedRatio: Double = 0.8,
    val recentRingUnits: Int = 3,
    val recentRingSeconds: Double = 30.0,
    val maxUnitChars: Int = 300,
    val hardSplitChars: Int = 400,
    val minSplitChars: Int = 80,
    val firstUnitMaxChars: Int = 120,
    val firstUnitMinChars: Int = 40,
    val tinyUnitChars: Int = 40,
    val tinyMergeMaxChars: Int = 200,
    val startBudgetSeconds: Double = 20.0,
    val gateZ: Double = 2.0,
    val gateGuardSeconds: Double = 0.4,
    val gateDeltaNormal: Double = 0.05,
    val gateDeltaThermal: Double = 0.15,
    val tooSlowRatio: Double = 0.97,
    val priorCountForTooSlow: Int = 20,
    val horizonUnits: Int = 400,
    val horizonSeconds: Double = 1800.0,
    val starvationSeconds: Double = 0.02,
    val preparingDebounceMs: Long = 300,
    val stallsForTooSlow: Int = 3,
    val stallWindowSeconds: Double = 300.0,
    val holdSynthesisSeconds: Double = 30.0,
    val silenceScale: Float = 0.4f,
    val edgeSilenceThreshold: Double = 0.005,
    val edgeKeepSeconds: Double = 0.05,
    val gapSentenceSeconds: Double = 0.32,
    val gapParagraphSeconds: Double = 0.60,
    val gapClauseSeconds: Double = 0.12,
    val gapFragmentSeconds: Double = 0.0,
    val fadeSamples: Int = 120,
    val cacheBudgetBytes: Long = 256L * 1024 * 1024,
    val cacheEvictToFraction: Double = 0.9,
    val runtimeIdleReleaseMs: Long = 180_000,
    val plannerAheadSeconds: Double = 90.0,
    val plannerCapUtterances: Int = 600,
    val rebaseScanLimit: Int = 400,
    val alignWindow: Int = 3,
    val metricsRing: Int = 200,
    val thermalSampleMs: Long = 10_000,
    val wakeLockTimeoutMs: Long = 600_000,
    val audioSecondsPerCharPrior: Double = 0.051,
) {
    companion object {
        val DEFAULT = NarrationTuning()
    }
}

/** 16-bit mono PCM helpers. Pure functions. */
internal object Pcm16 {
    /** NaN/Inf → 0, clamp to ±1, [fadeSamples] linear fade at both ends, then scale to Short. */
    fun fromFloat(samples: FloatArray, fadeSamples: Int): ShortArray
    fun toBytesLittleEndian(pcm: ShortArray): ByteArray
    fun fromBytesLittleEndian(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ShortArray
    fun seconds(frames: Int, sampleRate: Int): Double
}

internal data class EdgeTrim(val pcm: ShortArray, val leadTrimmedFrames: Int, val trailTrimmedFrames: Int)

internal object EdgeSilence {
    /** Trims leading/trailing runs with |x| ≤ threshold (in Short units: threshold × 32767) to ≤ keepFrames each. Never trims inside speech. */
    fun trim(pcm: ShortArray, threshold: Double, keepFrames: Int): EdgeTrim
    /** Frames of zeros to append for [gap] at [speed]. */
    fun gapFrames(gap: GapKind, speed: Double, sampleRate: Int, tuning: NarrationTuning): Int
    /** Leading and trailing silence in frames, for metrics (threshold 0.01 like the host analysis). */
    fun measureEdges(pcm: ShortArray, threshold: Double = 0.01): Pair<Int, Int>
}

internal object SpeechText {
    const val VERSION: Int = 1
    /** What Kokoro hears: expansions, invisible characters removed, whitespace collapsed, punctuation kept. */
    fun forSynthesis(text: String): String
}

/** Comparison key for matching Readium's utterance text with the plan: trim + collapse whitespace. Same as reader.normalize. */
internal fun normalizeKey(text: String): String

internal object AbbreviationJoiner {
    /** Merges a range into the next when it ends with a known abbreviation (section 5.1). Ranges must be ascending. */
    fun join(text: String, ranges: List<IntRange>): List<IntRange>
}

internal enum class GapKind { FRAGMENT, CLAUSE, SENTENCE, PARAGRAPH }

internal data class PlannedUtterance(
    /** Monotonic within one planner anchor. */
    val index: Long,
    /** normalizeKey(text). */
    val key: String,
    val text: String,
    /** Resource href as a string; the engine keeps the Locator. */
    val href: String,
    /** Same Readium Content element ⇒ same paragraph. */
    val elementOrdinal: Long,
    val endsSentence: Boolean,
    val endsElement: Boolean,
)

internal data class SynthesisUnit(
    val seq: Long,
    val gen: Long,
    /** ≥ 1. Each entry: utterance and the [charStart, charEnd) of its text inside speechText's source. */
    val parts: List<UnitPart>,
    val speechText: String,
    val gapAfter: GapKind,
    /** True for a piece of a split utterance that is not its last piece. */
    val continues: Boolean,
) {
    val chars: Int get() = speechText.length
    val firstUtterance: Long get() = parts.first().utterance.index
    val lastUtterance: Long get() = parts.last().utterance.index
}

internal data class UnitPart(val utterance: PlannedUtterance, val charStart: Int, val charEnd: Int)

/** Incremental: push utterances in plan order; units are emitted once decidable. Main-confined. */
internal class UnitBuilder(private val tuning: NarrationTuning = NarrationTuning.DEFAULT) {
    /** Starts a fresh plan at [gen]; the next unit gets the first-unit cap. */
    fun restart(gen: Long, firstSeq: Long = 0)
    fun push(utterance: PlannedUtterance): List<SynthesisUnit>
    /** Emits what is still held (end of book). */
    fun flush(): List<SynthesisUnit>
    val nextSeq: Long
}

internal object UtteranceSpans {
    /** Frame boundaries for each part of [unit] over [voicedFrames] (pcm before the gap). Exact for one part; char-share snapped to the quietest 20 ms window within ±0.4 s otherwise. Returns parts.size ranges covering [0, voicedFrames). */
    fun split(unit: SynthesisUnit, pcm: ShortArray, sampleRate: Int): List<IntRange>
}

internal sealed interface Match {
    data class Planned(val index: Long) : Match
    data object Held : Match
    data class Recent(val index: Long) : Match
    data object Miss : Match
}

internal object UtteranceAligner {
    fun match(
        key: String,
        plan: List<PlannedUtterance>,
        lastRequested: Long,
        heldIndex: Long?,
        recentIndices: List<Long>,
        window: Int,
    ): Match
}

internal data class Span(val utteranceIndex: Long, val key: String, val startFrame: Int, val endFrame: Int)

internal class ReadyUnit(
    val seq: Long,
    val gen: Long,
    val firstUtterance: Long,
    val lastUtterance: Long,
    /** Edge-trimmed speech, no gap. */
    val pcm: ShortArray,
    /** Zeros to append after pcm. */
    val gapFrames: Int,
    /** Within pcm; last span's endFrame == pcm.size. */
    val spans: List<Span>,
    val cacheKey: String,
    val cacheHit: Boolean,
    /** SynthesisUnit.continues: the last span's utterance goes on in the next unit (Done held back). */
    val continues: Boolean = false,
) {
    val frames: Int get() = pcm.size + gapFrames
}

/** One write: [pcm] from [offset] for [count] frames; when pcm is exhausted, [gapFrames] zeros follow. */
internal data class WriteItem(val seq: Long, val pcm: ShortArray, val offset: Int, val count: Int, val gapRemaining: Int)

internal sealed interface HeardEvent {
    val gen: Long
    val utteranceIndex: Long
    data class Started(override val gen: Long, override val utteranceIndex: Long) : HeardEvent
    data class Done(override val gen: Long, override val utteranceIndex: Long) : HeardEvent
}

internal data class QueueSnapshot(
    val readyUnits: Int,
    val readySeconds: Double,
    val writtenUnheardSeconds: Double,
    val reserveSeconds: Double,
    val recentUnits: Int,
    val nextReadySeq: Long?,
)

/** Thread-safe. Frames in track coordinates since the last flush. */
internal class PcmQueue(
    val sampleRate: Int,
    private val tuning: NarrationTuning = NarrationTuning.DEFAULT,
    private val listeningSecondsPerFrame: Double = 1.0 / sampleRate,
) {
    /** False when gen is stale or seq is out of plan order (duplicates). */
    fun offer(unit: ReadyUnit, currentGen: Long): Boolean
    /** Next contiguous piece to write, or null. Honour the speculation limits: nothing beyond utterance [maxUtteranceIndex] and nothing whose start is more than [maxFramesPastRequestEnd] past the end of [requestEndUtterance]. */
    fun nextToWrite(maxUtteranceIndex: Long, requestEndUtterance: Long, maxFramesPastRequestEnd: Long): WriteItem?
    /** [frames] of [seq] were written (partial writes allowed); the unit's write offset advances. */
    fun markWritten(seq: Long, frames: Int)
    /** Heard events for utterances whose end (or start) frame ≤ [headFrame], in order, each at most once. */
    fun onHead(headFrame: Long): List<HeardEvent>
    /** Track flushed: written-unheard units return to ready with their PCM; frame counters reset. */
    fun onFlush()
    /** Rewind so [utteranceIndex] is written next (from ready, written-unheard or the recent ring). False if it is no longer held. Caller flushes the track first. */
    fun rewindTo(utteranceIndex: Long): Boolean
    /** Drops ready and written-unheard units of any other gen; the recent ring keeps only matching gen. */
    fun invalidate(currentGen: Long)
    fun reserveSeconds(headFrame: Long): Double
    fun isReady(seq: Long): Boolean
    fun hasUtterance(utteranceIndex: Long): Boolean
    fun snapshot(headFrame: Long): QueueSnapshot
    val writtenFrames: Long
}

internal class WaterMarks(private val tuning: NarrationTuning = NarrationTuning.DEFAULT) {
    /** Hysteresis: false at ≥ high until the reserve drops below low. */
    fun shouldSynthesize(reserveSeconds: Double, strained: Boolean): Boolean
}

internal data class ThroughputPrior(
    val a: Double,
    val b1: Double,
    val alpha1: Double,
    val cv: Double,
    val n: Int,
    val updatedAtMs: Long,
) {
    companion object {
        val INT8_DEFAULT: ThroughputPrior   // a 0.40, b1 0.0765, alpha1 0.051, cv 0.25, n 0
        val FP32_DEFAULT: ThroughputPrior   // a 0.15, b1 0.0357, alpha1 0.051, cv 0.25, n 0
    }
}

internal class ThroughputModel(prior: ThroughputPrior, private val tuning: NarrationTuning = NarrationTuning.DEFAULT) {
    /** One synthesised unit (never a cache hit). [weight] 0.5 for the warm-up observation. */
    fun observe(chars: Int, speed: Double, synthSeconds: Double, audioSeconds: Double, weight: Double = 1.0)
    fun predictSynthSeconds(chars: Int, speed: Double, thermal: Boolean): Double
    fun predictAudioSeconds(chars: Int, speed: Double): Double
    /** Σ predicted synth ÷ Σ predicted audio over [units] (chars per unit), inflated by δ. */
    fun effectiveRatio(unitChars: List<Int>, speed: Double, thermal: Boolean): Double
    val cv: Double
    val observations: Int
    fun toPrior(nowMs: Long): ThroughputPrior
}

internal data class PendingUnit(val seq: Long, val chars: Int, val inFlightElapsedSeconds: Double?)

internal data class GateInput(
    /** Ready + cached-ahead + written-unheard listening seconds, contiguous from the head. */
    val reserveSeconds: Double,
    /** At least one whole unit is ready to write. */
    val unitReady: Boolean,
    /** Units not yet ready, plan order; the first may be in flight. */
    val pending: List<PendingUnit>,
    val speed: Double,
    val thermal: Boolean,
    /** Seconds left of the 20 s budget, or null when the budget does not apply (chapter preparation). */
    val budgetRemainingSeconds: Double?,
    /** True when the horizon ends at the end of the chapter (chapter-preparation mode). */
    val chapterHorizon: Boolean,
)

internal sealed interface GateDecision {
    data object Start : GateDecision
    data class Wait(val etaSeconds: Double) : GateDecision
    data class TooSlow(
        val effectiveRatio: Double,
        /** 20 / (r − 1): seconds of listening a 20 s reserve sustains. */
        val sustainSeconds: Double,
        /** Seconds to prepare the rest of the horizon. */
        val prepareSeconds: Double,
    ) : GateDecision
}

internal class StartGate(private val model: ThroughputModel, private val tuning: NarrationTuning = NarrationTuning.DEFAULT) {
    fun evaluate(input: GateInput, priorCount: Int): GateDecision
}

internal object CacheKey {
    /** SHA-256 hex of "v1|modelId|sid=..|speed=..|silence=..|en-us|SpeechText.VERSION|speechText". */
    fun of(modelId: String, sid: Int, speed: Double, silenceScale: Float, speechText: String): String
}

internal enum class SlowChoice { PREPARE_CHAPTER, WITH_BREAKS, PHONE_VOICE, FASTER_MODEL }

internal sealed interface NarrationStatus {
    data object Idle : NarrationStatus
    data object Loading : NarrationStatus
    data class Preparing(val reserveSeconds: Double, val etaSeconds: Int?) : NarrationStatus
    data class Playing(val reserveSeconds: Double, val pace: Double?) : NarrationStatus
    data class Paused(val reserveSeconds: Double) : NarrationStatus
    data class PreparingChapter(val fraction: Float, val etaSeconds: Int) : NarrationStatus
    data class TooSlow(
        /** 1 / rEff: speech seconds made per second. */
        val pace: Double,
        val prepareChapterSeconds: Int,
        val breakEverySeconds: Int,
        val breakSeconds: Int,
        val offerFasterModel: Boolean,
        val fasterModelMb: Int,
    ) : NarrationStatus
    data class Failed(val message: String) : NarrationStatus
}

internal data class UnitRecord(
    val tMs: Long, val gen: Long, val seq: Long, val utterances: Int, val chars: Int, val cacheHit: Boolean,
    val queueWaitMs: Long, val synthMs: Long, val audioMs: Long, val edgeLeadMs: Int, val edgeTrailMs: Int,
    val gapMs: Int, val reserveMsAfter: Long, val thermalStatus: Int, val thermalHeadroom: Float?,
) { val rtf: Double get() = synthMs.toDouble() / audioMs }

internal data class BoundaryRecord(
    val tMs: Long, val fromSeq: Long, val toSeq: Long, val readiumRoundtripMs: Long?, val heardAhead: Boolean,
    val stallMs: Long, val underrunsDelta: Int,
)

internal data class SessionSummary(
    val tapToFirstAudioMs: Long?, val modelLoadMs: Long, val firstUnitSynthMs: Long?, val gatePredictedWaitMs: Long?,
    val gateActualWaitMs: Long?, val listenSeconds: Double, val stalls: Int, val stallMsTotal: Long, val underrunsTotal: Int,
    val rtfMean: Double?, val rtfP95: Double?, val rtfFirst5Min: Double?, val rtfLast5Min: Double?,
    val reserveMinS: Double?, val reserveMeanS: Double?, val reserveMaxS: Double?, val thermalMax: Int,
    val screenOffRtfMean: Double?, val screenOnRtfMean: Double?, val variant: String, val threads: Int,
    val speed: Double, val silenceScale: Float, val alignMiss: Int, val readiumLate: Int,
) { fun toJson(): String }

/** Ring of records plus the math. Main-confined. */
internal class NarrationMetrics(private val tuning: NarrationTuning = NarrationTuning.DEFAULT) {
    fun unit(record: UnitRecord)
    fun boundary(record: BoundaryRecord)
    fun reserve(tMs: Long, seconds: Double, screenOn: Boolean)
    fun stall(tMs: Long, ms: Long)
    /** True when stallsForTooSlow stalls fell inside stallWindowSeconds. */
    fun stallsExceeded(nowMs: Long): Boolean
    val units: List<UnitRecord>
    val boundaries: List<BoundaryRecord>
    fun summary(nowMs: Long, fixed: SessionSummaryFixed): SessionSummary
    companion object {
        fun unitsCsvHeader(): String
        fun unitsCsvRow(r: UnitRecord): String
        fun boundariesCsvHeader(): String
        fun boundariesCsvRow(r: BoundaryRecord): String
        fun p95(values: List<Double>): Double?
    }
}

internal data class SessionSummaryFixed(
    val tapToFirstAudioMs: Long?, val modelLoadMs: Long, val firstUnitSynthMs: Long?, val gatePredictedWaitMs: Long?,
    val gateActualWaitMs: Long?, val variant: String, val threads: Int, val speed: Double, val silenceScale: Float,
    val alignMiss: Int, val readiumLate: Int,
)

internal data class BenchmarkRow(
    val phase: String, val variant: String, val threads: Int, val provider: String, val fellBack: Boolean,
    val seq: Int, val chars: Int, val synthMs: Long, val audioMs: Long, val edgeLeadMs: Int, val edgeTrailMs: Int,
    val thermalStatus: Int, val thermalHeadroom: Float?, val tMs: Long,
) { val rtf: Double get() = synthMs.toDouble() / audioMs }

internal data class ConfigResult(
    val variant: String, val threads: Int, val provider: String, val fellBack: Boolean,
    val rtfMean: Double, val rtfP95: Double, val cv: Double, val firstUnitMs: Long, val overheadA: Double, val perCharB1: Double,
)

internal data class BenchmarkReport(
    val loadMs: Map<String, Long>,
    val configs: List<ConfigResult>,
    val best: ConfigResult?,
    /** Whole render vs separate units: boundary gap p50/p95 in ms, lead/trail ms. */
    val wholeGapP50Ms: Int?, val wholeGapP95Ms: Int?, val separateGapP50Ms: Int?, val separateGapP95Ms: Int?,
    val sustainedMinutes: Int?, val sustainedRtfFirst: Double?, val sustainedRtfLast: Double?, val thermalMax: Int?,
    /** Predicted start wait from the gate with the best config's prior and a 3 s first unit. */
    val predictedStartSeconds: Double?,
    val tooSlow: Boolean,
) { fun toJson(): String }

internal object BenchmarkStats {
    fun summarize(rows: List<BenchmarkRow>): ConfigResult
    fun prior(rows: List<BenchmarkRow>, nowMs: Long): ThroughputPrior
    fun csvHeader(): String
    fun csvRow(r: BenchmarkRow): String
    /** Silence gaps between consecutive segments (for the diagnostic render). */
    fun gaps(segments: List<ShortArray>, sampleRate: Int, threshold: Double = 0.01): List<Int>
}
```

### 14.2 Engine → UI (package `dev.sadking.folio.reader`)

UI reads only these. All are Main-thread Compose state or `StateFlow`s.

```kotlin
package dev.sadking.folio.reader

internal enum class ListenState { OFF, STARTING, PREPARING, PLAYING, PAUSED }

internal class ReadAloud(...) {                       // existing; additions:
    var state: ListenState                           // PREPARING while status is Loading/Preparing and play is wanted
    var speed: Double                                // now also follows engine settings (media-controller changes)
    var natural: Boolean
    var naturalPace: Double?                         // 1 / rEff; null until known
    /** Listening seconds ready ahead of the voice; null when not natural. */
    var readyAheadSeconds: Int?
    /** The engine's status; Idle when not natural. */
    var narration: dev.sadking.folio.reader.narration.NarrationStatus
    /** Act on the TooSlow sheet. PHONE_VOICE = restartHere() with the phone engine; FASTER_MODEL = KokoroModel.install(FP32) then continue with int8 "with breaks" for now. */
    fun chooseSlow(choice: dev.sadking.folio.reader.narration.SlowChoice)
    /** Close the sheet and keep paused. */
    fun dismissSlow()
    /** Which Kokoro variant plays and which are installed. */
    var naturalVariant: KokoroModel.Variant
    fun chooseVariant(variant: KokoroModel.Variant)
    var recordMeasurements: Boolean                  // persisted in settings "narrationRecord"
    fun setRecordMeasurements(on: Boolean)
    /** Directory of the last finished recording, for Share; null when none. */
    var lastRecording: java.io.File?
    // unchanged: toggle, pause, previous, next, cycleSpeed, chooseVoice, sleepIn, stop, utterance, sleep, voices, voice, naturalFits
}

internal object KokoroModel {                        // existing; additions:
    enum class Variant(val dirName: String, val downloadBytes: Long, val modelFile: String) { INT8(...), FP32(...) }
    fun dir(context: Context, variant: Variant = Variant.INT8): File
    fun ready(context: Context, variant: Variant = Variant.INT8): Boolean
    fun state(context: Context, variant: Variant = Variant.INT8): StateFlow<NaturalVoice?>
    fun install(context: Context, variant: Variant = Variant.INT8)
    fun remove(context: Context, variant: Variant = Variant.INT8)
    const val DOWNLOAD_BYTES: Long                   // kept: INT8 size
}

/** On-device benchmark; one run at a time per process. Called from Main; work runs on its own threads. */
internal object NarrationBenchmark {
    sealed interface State {
        data object Idle : State
        data class Running(val step: Step, val stepIndex: Int, val stepCount: Int, val fraction: Float, val detail: String?) : State
        data class Done(val report: dev.sadking.folio.reader.narration.BenchmarkReport, val exportDir: File) : State
        data class Failed(val message: String) : State
    }
    enum class Step { LOAD, SWEEP, DIAGNOSTIC, SUSTAINED, RESULT }
    data class Plan(val sustainedMinutes: Int = 5, val threads: List<Int> = listOf(2, 3, 4, 5, 6), val tryProviders: Boolean = true)
    val state: StateFlow<State>
    fun start(context: Context, plan: Plan)
    fun cancel()
    /** The variants that can be benchmarked (installed). */
    fun installedVariants(context: Context): List<KokoroModel.Variant>
}
```

`ReaderChrome` calls `KokoroModel.install(context, variant)` for the fp32 download button and `NarrationBenchmark.start/cancel` for the test sheet; everything else goes through `ReadAloud`.

### 14.3 Engine internals that other engine files share (engine-owned, listed so the pieces fit)

```kotlin
package dev.sadking.folio.reader

internal class KokoroEngineProvider(
    private val context: Context,
    private val startAt: Locator?,
    private val tappedAtMs: Long,
    private val variant: KokoroModel.Variant,
    private val recorder: NarrationRecorder?,
) : TtsEngineProvider<KokoroSettings, KokoroPreferences, KokoroPreferencesEditor, KokoroError, KokoroVoice> {
    var engine: KokoroEngine?
}

internal class KokoroEngine(...) : TtsEngine<KokoroSettings, KokoroPreferences, KokoroError, KokoroVoice> {
    val status: StateFlow<NarrationStatus>
    val pace: StateFlow<Double?>
    val readyAheadSeconds: StateFlow<Int?>
    /** From ReadAloud's location collect. Main. */
    fun onLocation(locator: Locator, text: String)
    /** TooSlow choices that stay inside the engine. Main. */
    fun prepareChapter()
    fun playWithBreaks()
    // TtsEngine: settings, voices, submitPreferences, setListener, speak, stop, close
}

internal interface Synthesizer {
    val sampleRate: Int
    /** Blocking; [keepGoing] polled at each espeak piece. Throws on failure. */
    fun synthesize(speechText: String, sid: Int, speed: Float, silenceScale: Float, keepGoing: () -> Boolean): FloatArray
}

internal object KokoroRuntime {
    data class Config(val variant: KokoroModel.Variant, val threads: Int, val provider: String = "cpu", val silenceScale: Float)
    class Handle internal constructor(...) { val sampleRate: Int; val config: Config; fun release() }
    fun preload(context: Context, config: Config)
    suspend fun acquire(context: Context, config: Config): Handle
    /** Runs [job] on kokoro-synth; the only way to call the Synthesizer. */
    fun submit(priority: Int, seq: Long, gen: Long, isCurrent: () -> Boolean, job: (Synthesizer) -> Unit)
    fun onTrimMemory(level: Int)
}

internal interface AudioSink {
    fun send(command: SinkCommand)
    val headFrame: Long
    val underruns: Int
}
internal sealed interface SinkCommand { Play; Pause; Resume; Flush(rewindTo: Long?, gen: Long); Release }
internal class WriteLimits { gen; requestStart; requestEnd; maxUtterance; maxFramesPastRequestEnd; planEnded }   // @Volatile, set on Main
internal interface SinkEvents { onHeard(flushes, events); onStarved(gen); onDrained(gen); onFirstAudio(); onRewindFailed(gen, index); onError(message) }
```

As built, the queue's index space is the engine's piece ids, not utterance indices (section 19).

---

## 15. Tests

### Pipeline (JVM, plain JUnit, `app/src/test/java/dev/sadking/folio/reader/narration/`)

- `Pcm16Test`: NaN/Inf → 0; clamp; fades 120 samples both ends; round trip through bytes.
- `EdgeSilenceTest`: trims only edges to ≤ keep; never trims inside speech; gap frames by kind ÷ speed; edge measurement matches a synthetic signal.
- `SpeechTextTest`: each expansion; "St." untouched; "Dr. who" (lowercase) untouched; invisible characters stripped; punctuation preserved; `normalizeKey` ignores whitespace only.
- `AbbreviationJoinerTest`: "My dear Mr. Bennet, said his lady." → 1 range; "e.g. this"; "J. Austen wrote"; "No. 5" joins, "No. He left" does not; dialogue `"No," said she. "Yes."` → 2 ranges.
- `UnitBuilderTest`: fragment merge across italics; tiny merge ≤ 200 only in the same element; split > 300 at `;` then `,` then space with parts 80–300; hard split at 400 never inside a word; first-unit cap after `restart`; `gapAfter` kinds; an utterance spanning two units has `continues` on the first; parts cover the text exactly.
- `UtteranceSpansTest`: single part exact; two parts snap to a synthetic silence dip within ±0.4 s.
- `UtteranceAlignerTest`: hit at +1, +2, +3; held; recent; miss; window respected.
- `PcmQueueTest`: reserve counts only contiguous frames; `offer` rejects stale gen and out-of-order seq; `nextToWrite` honours both speculation limits and partial-write offsets; `markWritten` partial then rest; `onHead` emits Started/Done once each in order; `onFlush` returns written-unheard to ready with PCM and resets frames; `rewindTo` from ready, written and ring; ring bound 3 units / 30 s; `invalidate` keeps only current gen; thread-safety smoke test (writer + offer + head from 3 threads, no exceptions, invariants hold).
- `WaterMarksTest`: stop at ≥ 60, resume below 30, strained uses 120.
- `ThroughputModelTest`: recovers a and b1 within 5% from synthetic data; speed normalisation (observations at 1.5× predict 1× correctly); cv floor and prior blending; thermal δ; warm-up weight.
- `StartGateTest`: fp32-like starts right after unit 1 (wait ≈ first unit synth); r 0.7 → Start by ~4 s; r 0.9 → Wait with eta 4–10 s; rEff ≥ 0.97 with one observation → TooSlow before 20 s; default prior (n 0) alone never TooSlow; budget exceeded → TooSlow; chapter horizon with no budget → Wait with prepareSeconds; eta never negative; monotone non-increasing in reserve; `sustainSeconds = 20/(r−1)`.
- `CacheKeyTest`: changes with each field and `SpeechText.VERSION`; stable across runs; hex length 64.
- `NarrationMetricsTest`: p95; CSV header/row column counts match; `stallsExceeded` window; summary first/last 5 min; ring bound 200.
- `BenchmarkStatsTest`: summarize mean/p95/cv/fit on synthetic rows; gaps on synthetic segments; prior derivation.

### Engine

JVM/Robolectric (`app/src/test/java/dev/sadking/folio/reader/`):
- `KokoroSentenceTokenizerTest`: ranges equal Readium's default tokenizer plus the joiner; language argument ignored.
- `UtterancePlannerTest`: on a small EPUB fixture (italics, links, dialogue, two chapters) the planner's key sequence equals the location sequence of a real `TtsNavigator` driven by a fake engine that calls `onDone` immediately; rebase finds the anchor; `isLetterOrDigit` filter matches.
- `NarrationSessionTest` (engine wired with `FakeSynthesizer` — configurable seconds per char, overhead, jitter, latch — and `FakeAudioSink` — head advances with a virtual clock; Robolectric main looper): `onDone(N)` only after the fake head passes N's end; zero underruns between units when synthesis keeps up; `heardAhead`: late `speak` gets `onStart`+`onDone` at once; stop → speak(same) resumes with no flush and no synthesis; stop → speak(next) flushes and plays queued PCM; stop → speak(previous) served from the ring; `submitPreferences` speed change: a late result from the old gen never reaches the sink, the new gen re-primes at once; a miss synthesises at P0 ahead of queued P1; empty unit → silence, 3 in a row → `onError`; `close()` while the synthesizer is blocked → `release()` not called until the latch opens, then exactly once on the synth thread; every listener call on the main looper; gate opens with `Preparing` shown only while waiting; 3 stalls in 5 min → `TooSlow`; wake lock acquired in Preparing and released on pause.
- `NarrationCacheTest`: header/body round trip; LRU to 90%; pinned entries survive; corrupt file → miss and deleted; atomic write; `remove(variant)` clears only that variant.
- `KokoroModelTest`: `Variant` dirs, `keep()` per variant.
- `ReadAloudTest` additions: `PREPARING` mapping; `chooseSlow` routes each choice; `speed` follows engine settings.

Instrumented (`app/src/androidTest/`, emulator, reported as "emulator"):
- `NarrationSinkTest`: synthetic tone units through the real `AudioTrackSink`; asserts heard order and that `underrunCount` does not grow between units; `Assume` skip if the head does not advance under `-noaudio`.
- `NarrationBenchmarkTest`: runs `NarrationBenchmark` LOAD + SWEEP (threads {2, 4}) + DIAGNOSTIC and a 2-min SUSTAINED; gated by `-Pandroid.testInstrumentationRunnerArguments.narrationBench=true`; model cached in CI; asserts invariants only (callback-probe never duplicates samples: whole-render frames == Σ separate frames ± gaps; diagnostic gaps within 0.1–0.6 s; report JSON parses; priors written). Emulator numbers are informative, never device claims.
- `KokoroNativeTest` unchanged.

### UI

- `ReaderChromeNarrationTest` (Robolectric Compose): `ListenBar` shows the spinner and "Preparing narration…" only in `PREPARING`; the ETA string when `etaSeconds != null`; the TooSlow sheet shows the four actions with the numbers substituted and hides "Get the faster voice" when `offerFasterModel` is false; the benchmark sheet renders Idle/Running/Done/Failed; strings have no stray format args.
- `ScreenshotTest` additions: `listen-preparing`, `listen-too-slow`, `listen-benchmark-running`, `listen-benchmark-result` (light, dark, 200%).
- `ReaderPreviews` gains previews for each of those.

---

## 16. UI copy (`strings_narration.xml`, plain language)

```
listen_preparing              Preparing narration…
listen_preparing_eta          Preparing narration… about %1$d s
listen_ready_ahead            Ready ahead: %1$d s
listen_pace                   Makes speech %1$s× faster than it reads
listen_pace_slow              Makes speech at %1$s× its reading speed
listen_slow_title             This phone can't make the natural voice fast enough
listen_slow_body              It makes speech at %1$s× the speed it reads, so reading would keep stopping.
listen_slow_prepare           Prepare this chapter first (about %1$d min)
listen_slow_breaks            Start now, with a pause of about %1$d s every %2$d s
listen_slow_phone             Use the phone voice
listen_slow_faster            Get the faster natural voice (%1$d MB)
listen_slow_dismiss           Not now
listen_chapter_progress       Preparing chapter… %1$d%%
listen_variant                Natural voice model
listen_variant_standard       Standard
listen_variant_faster         Faster (larger download)
listen_record                 Record read-aloud measurements
listen_record_share           Share last measurements
listen_test                   Test read-aloud speed
listen_test_running           Testing… step %1$d of %2$d
listen_test_cancel            Stop test
listen_test_minutes           Sustained run: %1$d min
listen_test_result_ok         Best: %1$s, %2$d threads. Makes speech %3$s× faster than it reads. Starts in about %4$d s.
listen_test_result_slow       %1$s makes speech at %2$s× its reading speed: it can't keep up.
listen_test_share             Share results
listen_test_failed            The test did not finish.
```

`ListenBar`: the spinner slot shows during `PREPARING` with the text; the play button stays enabled so the owner can cancel. Listening sheet: "Ready ahead", pace line, model choice (when both variants fit), "Test read-aloud speed", "Record read-aloud measurements", "Share last measurements". TooSlow: `ModalBottomSheet` over the reader; the listener is paused while it is shown; the choice is remembered per phone (`narrationSlowChoice`). The media session keeps showing "playing" during Preparing because Readium's `TtsPlayer` has no buffering state; documented, not hidden.

---

## 17. Build order and verification

Each step can ship alone. 1 and 2 answer the owner's complaint.

1. Pipeline pure classes with tests; `KokoroRuntime` (warm instance, ordered release); `Variant.FP32`; `NarrationRecorder`; `NarrationBenchmark` + sheet. Behaviour otherwise unchanged. The owner runs the benchmark on the Pixel.
2. Continuous output: `AudioTrackSink`, `PcmQueue`, gens, `UtterancePlanner`, head-driven `onDone`, resume mid-sentence, `kokoroSentenceTokenizer`, `SpeechText`, `UnitBuilder`.
3. Adaptive start: `ThroughputModel`, `StartGate`, water marks, `PREPARING`, wake lock, TooSlow sheet.
4. Silence: scale 0.4, edge trim and gaps; tune by ear.
5. Disk cache and "Prepare this chapter first".

Checks: `./gradlew checkFormatting :core:test :transport:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`; `./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest' -Pscreenshots` for the new shots. Reports must state the level: device / emulator or JVM / compiled only / not verified. Nobody here can run the Pixel; the device steps are for the owner.

The device steps are in section 21.

## 18. Open risks (all need the device)

- ARM int8 vs fp32 ratio. If int8 is already fast enough (`rEff < 0.85`) the fp32 download is optional.
- Screen-off CPU placement and thermal throttling over 30 min. If RTF drifts above 1, the strained high-water mark and chapter preparation become the main path.
- `getPlaybackHeadPosition` lead over audibility is the output latency (tens of ms); good enough for sentence highlighting, verified by ear.
- Planner/Readium alignment must hold on the corpus (`UtterancePlannerTest`) before shipping; a mismatch costs ≤ 0.5 s of flushed audio and a resync.
- Readium's `fakePlayingAudio` creates an unreleased `AudioTrack` per `play()`; cannot be fixed from the app; watch the sustained run.
- NNAPI/XNNPACK rows are experimental; a rebuilt sherpa-onnx with ORT ≥ 1.31 (int8 RTF 0.45 on x86) is a separate project and unproven on ARM.

---

## 19. As built: where the code differs from the contract

Everything in sections 3–16 is implemented. These are the differences and the decisions made while building, so the document matches the code.

**Engine and queue**

- **Piece ids.** The engine gives every part of every unit a unique, increasing piece id and passes piece ids to `PcmQueue` (`Span.utteranceIndex`, `ReadyUnit.firstUtterance/lastUtterance`, the write limits, `rewindTo`, `hasUtterance`). It maps events back to utterances: `onStart` only for an utterance's first piece, `onDone` only for its last. A sentence split across two units therefore starts and ends once. `ReadyUnit.continues` (added by the pipeline for the same purpose) stays `false` in the engine; the queue's own split handling is tested but unused. Backstop: at a stall, if every unit of the request was offered and everything written was heard, the engine completes the request itself.
- **Flush epoch.** `SinkEvents.onHeard(flushes, events)` carries how many flushes the writer has applied. Main drops events from before its latest `Flush`, so audio still draining from before a rewind never marks a sentence as heard.
- **`SinkCommand.Flush(rewindTo, gen)`.** Flush and rewind run together on `kokoro-audio`. `WriteLimits.requestStart` replays the request after a dead output is rebuilt; `WriteLimits.planEnded` makes running dry at the end of the book a drain, not a stall.
- **Seek or a request outside the plan.** The requested sentence is made at once on its own (P0). The plan is rebased only when `onLocation` reports that sentence, and the audio already made is kept. Readium's `go()` updates the location only after the first sentence after the seek is done.
- **Pause between sentences.** Readium never calls `stop()` when it is paused between two utterances, so `ReadAloud` calls `engine.pauseNow()` when `playWhenReady` turns false.
- **"A unit is ready"** for the gate means: at least one unit ready, or at least 0.25 s still buffered in the output, or the plan ended. Without this, the last 20 ms left after a stall counted as ready and caused restart-and-stall loops.
- **Reading with breaks.** Gathers 20 s of listening before each stretch; a break lasts about 20·r s and a stretch about 20·r/(r−1) s. TooSlow with the screen off switches to it automatically.
- **Failures.** A unit that fails twice, or three empty results in a row, sets `Failed`; every later request gets the same error. One empty result plays as 0.2 s of silence.
- **Startup overlap.** `KokoroEngineProvider` still waits for the model in `createEngine`, so a model that fails to load falls back to the phone voice as before. `PrefetchingPlanSource` parses the first 24 utterances while the model loads. The warm-up runs only when nobody waits.
- **Cache header.** The reserved field holds the variant (1 = int8, 2 = fp32), so removing one model clears only its audio.
- **`ReadAloud.setRecordMeasurements`** is annotated `@JvmName("turnRecordMeasurements")`: a `var` with a private setter and a function of the same JVM name clash. Kotlin callers are unaffected.
- **`ListenService`** is unchanged. The media notification says "playing" while preparing, because Readium's player has no buffering state.

**Fixes made during integration**

- **The warm-up is not evidence.** `ThroughputModel.observations` now counts full-weight units only. Before, the 6-character warm-up counted as a measured unit, so the unmeasured int8 guess (RTF 1.5) could open the "too slow" sheet before a single real sentence was made. Tests: `StartGateTest.theWarmUpAloneIsNotEvidence`, `ThroughputModelTest.theWarmUpIsNotAMeasuredUnit`, `NarrationSessionTest.aGuessAndAWarmUpNeverAskWhatToDo`.
- **Saved priors are loaded as measured.** `NarrationPriors.load` widened the saved cv ×1.5 and `ThroughputModel` widened it again; a short session then saved the widened value, so the spread grew on every save. Only `ThroughputModel` widens now.
- **The speed test stops read-aloud first.** Read-aloud holds the voice for as long as it is on (even paused), so the test always failed with "Read-aloud is using the voice". Starting the test now ends read-aloud, and the benchmark waits up to 5 s for the engine to let go of the voice.

**Text**

- `kokoroSentenceTokenizer` = Readium's ICU sentence tokenizer for en-US plus `AbbreviationJoiner`; the language argument is ignored. "Mr. Bennet" stays one utterance for both Readium and the planner.
- `SpeechText` writes invisible characters as regex escapes (`\x{FEFF}`); a literal BOM in source fails lint.
- `UnitBuilder` closes a fragment group that reaches 400 characters without a full stop with gap `FRAGMENT`, so a run-on list of links is never held back.

**UI**

- Listen bar: "Preparing narration…" (with "about N s" when the engine has an ETA) shows only in `PREPARING`; play/pause shows Pause and stays enabled, so preparation can always be paused. Chapter preparation shows "Preparing chapter… N%".
- The "too slow" sheet opens over any other panel, pauses the reader, and offers: prepare the chapter, start with breaks, the phone voice, and the faster model (hidden when it is installed or not applicable).
- Listening sheet: pace line, "Ready ahead: N s", the model choice (Standard / Faster, with the faster model's download), then "Test read-aloud speed", "Record read-aloud measurements" and "Share last measurements". The faster model is offered as soon as the standard one is installed.
- Shares are zipped to `cacheDir/epub-share/` and sent through the existing file provider; no manifest change.

---

## 20. Measurements

### Host measurements (executed; x86 VM, not a phone)

Intel Xeon (Cascade Lake, AVX-512 VNNI), 4 vCPU, Python sherpa-onnx 1.13.8 (the AAR's version, ORT 1.28.2 inside), the exact packages the app downloads, speaker 3, 4 threads, `silence_scale` 0.2 unless noted. Passage: Pride and Prejudice ch. 1 + an "e.g./i.e." sentence + the 612-character opening sentence of A Tale of Two Cities (931 words, about 252 s of audio; the same text is `res/raw/narration_passage.txt`). RTF = synthesis seconds ÷ audio seconds.

| What | int8 (shipped) | fp32 |
|---|---|---|
| Aggregate RTF, one sentence per call | 1.446 (mean 1.56, sd 0.29, p95 2.15) | 0.428 (p95 0.62) |
| Aggregate RTF, ≤ 300-character groups | 1.412 (sd 0.07, p95 1.52) | 0.431 (sd 0.028, p95 0.476) |
| Whole passage in one call | 1.436, first callback after 9.57 s | – |
| First sentence (117 chars, 6.6 s of audio) | 8.9–9.1 s to make | 2.9 s to make |
| Per-call overhead `a` (fit) | 0.29–0.55 s | 0.10–0.19 s |
| Model load | 2.3–3.6 s | 2.3–2.5 s |
| Best thread count (of 1, 2, 3, 4, 6, 8) | 4 (RTF 1.49; 2.26 at 1 thread) | 4 (RTF 0.43; 1.13 at 1 thread) |
| Speed 1.5× (Kokoro's own parameter) | RTF 1.43 | RTF 0.44 |
| Sustained (3 passes, 5.2 min) | – | 0.413, 0.412, 0.409 (no drift; a VM has no thermal behaviour) |

- **Silence.** At scale 0.2 the gap at a sentence boundary is 0.163 s (p95 about 0.19 s) whether the passage is rendered in one call, by paragraph, or sentence by sentence laid end to end: separate rendering adds no silence, and the model does not produce excessive silence. Scale 0.5 gives 0.42 s, 1.0 gives 0.83 s; synthesis time does not change with the scale. The app uses 0.4 plus its own gap padding (section 9).
- **Callback streaming.** One callback per espeak sentence; the summed callback samples equal the returned samples in every run. No sub-sentence streaming.
- **Why int8 is slow on x86.** 51% of kernel time is `ConvInteger`. The same int8 graph runs at about RTF 0.45 under ORT 1.31 (4.3–4.7 s vs 12.0 s for the same 179 tokens); the AAR's JNI library requires ORT 1.28.2's symbols, so using it means rebuilding sherpa-onnx.
- **Model packages verified.** int8 package: 132 303 094 bytes, SHA-256 `4c3052ab…247e4e`. fp32 package: 349 906 910 bytes, SHA-256 `c5f7e2d2…ea3298` (re-checked against the downloaded file during integration).

### Host timings replayed through the real gate (executed on the JVM)

`HostReplayTest` feeds the 57 per-sentence host timings above through the real `StartGate`, `ThroughputModel` and `WaterMarks`, with playback consuming audio in real time and the gate asked every 10 ms. Model already loaded; add the load time (2.3–3.6 s) for a cold start.

| Case | Playback starts | Earliest start that would not have stalled (hindsight) | Result |
|---|---|---|---|
| fp32, first session (unmeasured fp32 prior, RTF 0.7) | 7.8 s | 2.9 s (the first sentence) | 3 passes (12.7 min): 0 stalls; reserve grows to the 60 s high water mark (76 s peak, one long unit over it) |
| fp32, later session (prior fitted from these timings: a 0.18 s, b1 0.0203 s/char, cv 0.19, n 57) | 5.6 s | 2.9 s | 3 passes: 0 stalls |
| int8, first session (unmeasured int8 prior) | never | 142 s | "Too slow" at 8.9 s, as soon as the first sentence is measured |
| int8, later session (measured prior, n 57) | never | 142 s | "Too slow" at once |

Reading the numbers:

- The gate never started a pipeline that later stalled, and int8 on this host (RTF 1.45) is refused within the 20 s ceiling instead of stalling at every full stop.
- The fp32 start is later than the hindsight minimum. In the first session the unmeasured fp32 prior predicts the 257-character second sentence at about 9 s against 6.7 s of audio, so the gate waits for it. With a saved measurement it waits for the noise margin: the host's per-sentence cv is 0.19, and the gate uses z = 2 and widens a saved cv ×1.5 until 5 units of the session are measured. Trying levers on the same data: z 1.5 starts at 4.8 s, no widening at 4.5 s, both at 4.0 s; none stalled on these timings. They are not changed: a start that hindsight shows was safe on one run would stall on a slower sentence about one time in ten at this spread. Tune them from the phone's own cv.
- Add the model load (2.3–3.6 s on the host) for a cold start. The engine overlaps it with parsing the first sentences.

### Model numbers (simulation only)

From the pipeline workstream's gate simulation (first unit 70 characters, then 150-character units, gate checked every 50 ms, no per-call overhead): with cv 0.1 playback starts at 1.1 s for RTF 0.3, 2.2 s for 0.4, 3.5 s for 0.5, 6.1 s for 0.7, 9.0 s for 0.9; TooSlow from about 0.95. With cv 0.25: 1.6, 3.1, 4.7, 7.8 and 14.7 s. A per-call overhead of 0.3 s already makes RTF 0.9 TooSlow on 150-character units.

### Verification of the build (JVM and compile only)

- `./gradlew checkFormatting :core:test :transport:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`: passes; 771 app unit tests, 0 failures, 58 skipped (the screenshot-gated ones). No lint errors.
- New narration tests on the JVM: pipeline (pure JUnit, 15 classes including `HostReplayTest`), engine (`NarrationSessionTest` with the real synthesis thread, a fake model and a simulated output; `AudioTrackSinkTest` with the real writer thread; planner, tokenizer, cache, benchmark runner), UI (`ReaderChromeNarrationTest`).
- Screenshots rendered on the JVM and checked in light, dark and 200% text: `listen-preparing`, `listen-preparing-chapter`, `listen-too-slow`, `listen-benchmark-running`, `listen-benchmark-result`, `reader-listen-natural`.
- `NarrationSinkTest` and `NarrationBenchmarkTest` (instrumented) are compiled only. Nothing has run on an emulator or a phone.

### Still needs the Pixel 9 Pro XL (not measured)

No phone or emulator measurement exists. Investigator B's extrapolation (not a measurement) puts the Pixel at RTF 0.55–0.8 for fp32 (0.7–1.1 throttled) and 1.2–1.8 for int8; ARM int8 kernels may compare quite differently with x86, so int8 could be anywhere from 0.5 to 2. The phone must answer:

1. RTF per unit for int8 and fp32 at 2–6 threads (the speed test's sweep), and which thread count wins on the Tensor G4's 1+3+4 cores.
2. Time from tapping Listen to the first word, cold and warm (`tap_to_first_audio_ms`).
3. Sustained RTF over 15–30 min with the screen off, with thermal status and headroom, and whether it drifts.
4. Underruns and stalls at sentence and chapter boundaries during real listening.
5. Whether `generateWithCallback` splits the same way on Android (the instrumented `NarrationBenchmarkTest` checks the sample totals).
6. Boundary pauses by ear at `silenceScale` 0.4 and the gap padding.
7. RAM use with fp32 loaded.

---

## 21. Device test steps (Pixel 9 Pro XL, owner)

Build and install:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. **Speed test.** Open an English book, start Listen, open the Listening sheet → "Test read-aloud speed", sustained run 15 min, Start. It stops read-aloud first. Turn the screen off during "Long run". Read the result card. Then fetch the files: Share results, or `adb exec-out run-as dev.sadking.folio tar c files/narration | tar x`. If Standard "can't keep up", download Faster from the Listening sheet and run the test again.
2. **First word.** Turn on "Record read-aloud measurements". Close and reopen the book, tap Listen on a page of prose with dialogue, and time the first word. Compare with `tap_to_first_audio_ms` in `files/narration/sessions/<ms>/summary.json`. "Preparing narration…" must show only until speech starts. Repeat once more (warm model).
3. **Long listen.** 30 min at 1× and 30 min at 1.5×, screen off most of the time, across a chapter boundary. Pass: `stalls` 0; `underruns_delta` 0 in `boundaries.csv`; reserve in `units.csv` rising toward 60 s; `rtf_last_5min ÷ rtf_first_5min` under 1.15; the highlight moves as each sentence is heard; no pause at full stops beyond the natural one.
4. **Controls.** Pause and resume mid-sentence (continues where it stopped); next and previous sentence; change voice; change speed (one short wait each); end-of-chapter sleep timer; swipe the app away while it is preparing (no crash).
5. **Too slow.** If the sheet appears, it should appear soon after the first sentence is made (host: 8.9 s plus the model load), and each choice should do what it says. With the screen off, reading should continue with breaks instead.
6. **Logs.** `adb logcat -s Narration` shows numbers only, never text.
7. **Instrumented tests (optional).** `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.sadking.folio.NarrationSinkTest`; with a voice installed, add `-Pandroid.testInstrumentationRunnerArguments.narrationBench=true` and run `dev.sadking.folio.NarrationBenchmarkTest`.

Send back the speed-test folder and the session folders from steps 2 and 3.
