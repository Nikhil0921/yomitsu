# Full OCR System Resiliency Audit (Read-Only)

Date: 2026-09-26. **Read-only audit — no source, schema, or git changes made.**
Scope: OCR + Read-Aloud (TTS) path on SM_M066B (Android 16, arm64).
Evidence: `.device-pass/` captures (see §0), current source at `main` working tree.

---

## 0. Evidence base and two premise corrections

Captures analyzed:

| File | Size | Window | Use |
|---|---|---|---|
| `.device-pass/on-device-capture.log` | 78.5 MB | 08-25 22:52–23:18 | primary contention evidence (193 OCR queue tasks) |
| `.device-pass/logcat-20260925-200614.log` | 44.6 MB | 08-26 00:40–01:42 | ART GC timeline, 197 TTS dispatches |
| `.device-pass/logcat-20260926-133534.log` | 4.5 MB | 08-26 19:05–19:06 | post-fix A–D cold uncached verification |
| `.device-pass/logcat-leakcanary.log` | 37.1 MB | 08-28 19:59–20:54 | LeakCanary session |
| `.device-pass/leak-full.log` | 33.6 MB | 08-29 | meminfo/audio |
| `.device-pass/leak-verify.log` | 875 KB | 08-29 | meminfo |

### Premise correction 1 — there are NO LeakCanary heap dumps, and the verdict was CLEAN

LeakCanary is **not a project dependency** (no `leakcanary` entry in any `build.gradle.kts`
or `libs.versions.toml`). It was side-loaded on 08-28 as a separate app package
`com.squareup.leakcanary.app.yomihon.dev`. The complete LeakCanary output in the capture is
four lines:

```
20:54:10.203 D LeakCanary: Watching instance of eu.kanade.tachiyomi.ui.reader.ReaderActivity
                 (… received Activity#onDestroy() callback) with key 4e8795bc-…
20:54:10.203 D LeakCanary: Watching instance of androidx.lifecycle.ReportFragment …
20:54:11.792 D LeakCanary: All retained objects have been garbage collected      ← CLEAN
20:54:15.408 D LeakCanary: Found 2 objects retained, not dumping heap yet
                 (app is visible & < 5 threshold)                                ← no dump written
```

The 2 retained objects were **collected** before the next check, and the retained count never
reached LeakCanary's 5-object dump threshold. `find . -name '*.hprof'` returns nothing. There is
no heap dump in the repository, so no retained-reference analysis against a dump is possible —
and the one run that did happen exonerated `ReaderActivity`.

**Conclusion: no evidence of a leak in `TtsPlaybackController`, `ReaderActivity`, or
`GlensOcrEngine`.** See §2 for the memory evidence that does exist.

### Premise correction 2 — the 10–30s stalls are not a memory problem and not the 30s guard

Across all three OCR/TTS captures: `OcrError` = 0, `OCR acquisition null` = 0,
`page advance to N timed out` = 0. The stalls are **queue-slot starvation**, and the dominant
waste is **duplicate scans of the same page** (§1).

---

## 1. Root cause of the 10–30s buffering stalls

### 1.1 Measured queue behaviour (from `on-device-capture.log`)

Parsed from the queue's own `activeSlots=` DEBUG snapshots and `OCR queue start … waitMs=`
records (193 OCR tasks total):

| Metric | Value |
|---|---|
| Queue enqueues | 193 |
| Distinct `(chapter, page)` pairs | 137 |
| **Duplicate enqueues (same page enqueued again)** | **56 (29%)** |
| Pages enqueued more than once | 45 |
| Enqueues while cap-3 already full | 70 (36%) |
| HIGH enqueued while cap-3 full | 2 |
| Task-start queue wait — median | 1 ms |
| Task-start queue wait — **p90** | **9 233 ms** |
| Task-start queue wait — **p99** | **15 806 ms** |
| Task-start queue wait — **max** | **17 169 ms** |
| Tasks waiting > 8 s | 25 (13.0%) |
| Tasks waiting > 10 s | 16 (8.3%) |
| HIGH-priority waits observed | 0, 0, **6 661**, 818 ms |

The 6 661 ms HIGH wait is exactly the figure cited in
`docs/audits/uncached-cold-start-diagnostic.md` §2 — that diagnostic's mechanism is confirmed.
Worst offenders by duplicate count: `ch8725 p2` (4 enqueues), `ch8725 p19/p22/p31/p34/p36`
(3 each), `ch8724 p4/p16/p18/p19` (3 each).

Speech-gap distribution (`logcat-20260925-200614.log`, 197 dispatches, 45 page-change gaps):
median 1.74 s, p90 4.80 s, max 23.3 s; 4 gaps > 5 s, 1 gap > 10 s. (Two far larger outliers —
490 s and 357 s — are session boundaries/user pauses, not stalls.)

### 1.2 Mechanism — three compounding defects

**(a) The prefetch dedup guard can never fire.** `schedulePrefetch` slides its window by one
page on every page turn, so the exact-range equality check misses every time:

```kotlin
// TtsPlaybackController.kt:836-844
val range = targetPages.first()..targetPages.last()
if (prefetchPages == range && prefetchJob?.isActive == true) { … return }   // never true in practice
```

Measured: 51 `TTS prefetch start` events, **1** `TTS prefetch skip`. Observed ranges slide
`26..31 → 27..32 → 28..33`, so each page turn re-launches a fresh batch.

**(b) Cancelling the prefetch coroutine does not cancel queued work.** Each re-arm does
`prefetchJob?.cancel()` (`:846`) and launches a new `prefetchJob`, but tasks already handed to
the queue are deliberately detached and run to completion:

```kotlin
// PrioritizedTaskQueue.kt:141-147
// Launch instead of running inline: … The queue task itself owns its bitmap lifecycle,
// so an abandoned await never cancels the running scan.
scope.launch { try { task() } finally { … activeTasks-- … } }
```

Orphaned scans from the previous window therefore keep occupying slots while the new window
re-enqueues the overlapping pages. That is the source of the 56 duplicate enqueues: the
in-flight dedup in `OcrRepositoryImpl.scanPage` (`:234-245`) only collapses *concurrently
running* scans (32 joins observed), never *already-queued* ones.

**(c) The cap-3 queue is non-preemptive by design.** `processQueue` (`:127-139`) refuses to
start anything when `activeTasks >= maxConcurrentTasks`, and HIGH is only consulted when a slot
is free. A HIGH scan's wait equals the remaining runtime of the slowest occupant — which, per
(a)+(b), is frequently a *redundant* scan.

### 1.3 Worked example — the 24.3 s silence on `ch8725` p22

```
22:56:03.480  advance confirmed page=21
22:56:03.560  dispatch p21_s0                      ← speech running
22:56:06.800  dispatch p21_s2
22:56:08.800  advance confirmed page=22
22:56:08.810  TTS on-demand scan start page=22     ← active page acquire begins
22:56:02.430  ┐ (earlier) p22 enqueued  task=21447964
22:56:04.010  ┘ (earlier) p22 enqueued  task=16142329
22:56:09.330  OCR scan joining in-flight scan page=22
22:56:14.470  OCR cache write page=22  elapsedMs=3   ← duplicate #1 completes
22:56:19.810  OCR cache write page=22  elapsedMs=2   ← duplicate #2 completes
22:56:21.180  OCR queue start p22 task=16142329  waitMs=17169   ← duplicate #3 finally starts
22:56:33.090  OCR cache write page=22
22:56:33.130  dispatch p22_s0                      ← 24.3 s of silence
```

The page's text was written to cache three times; the user waited 24.3 s. Two of the three
queue slots were occupied by redundant work for the page being spoken.

### 1.4 Fix 3 (sentence-budget prefetch) saturation — why N+6 always runs

```kotlin
// TtsPlaybackController.kt:982-986
const val TARGET_SENTENCE_BUFFER = 40
const val MIN_SENTENCES_PER_PAGE = 5
const val MAX_PREFETCH_DEPTH_EXTENDED = 6
```

On **uncached** manga every page contributes the `MIN_SENTENCES_PER_PAGE` estimate of 5, so
6 pages × 5 = 30 < 40. The budget condition can never be satisfied and the loop always runs to
the depth cap: **uncached content always enqueues a full 6-page parallel batch**. All six
launch concurrently (`coroutineScope { targetPages.map { async { … } } }`, `:855-857`) at
NORMAL priority. Add the active page's HIGH task and any background `OcrScanJob` NORMAL flood,
and 3 slots are oversubscribed 2–3×. On **cached** manga the budget does bind (291
`TTS prefetch cache hit` vs 0 `TTS prefetch complete` in the warm session), which is why the
warm captures looked healthy.

### 1.5 Can a page get stuck in `LoadingPage` indefinitely?

Per-page, **no** — `OCR_ACQUIRE_TIMEOUT_MS = 30_000` (`:981`) bounds every acquire, and
`awaitAdvanceConfirmation` has `ADVANCE_CONFIRM_TIMEOUT_MS = 10_000` (`:979`) which
transitions to `Paused` on expiry (`:316-326`). `fail()` (`:968`) always sets a terminal phase.

Session-level, **yes, in this shape**: `runPlayback` is a `while (true)` loop (`:370`) and a page
returning **zero sentences** falls through to `advanceFromPolicy(..., pageHasText = false)`
(`:376-381`) and re-enters `acquireSentences` on the next page, setting `LoadingPage` again
(`:549`). A run of textless pages therefore produces unbounded wall-clock silence composed of
≤30 s segments, with no error and no speech. Fix A/B target the *contention* cause, not this
one.

**UX regression introduced by Fix A:** the guard going 8 s → 30 s converts a prompt 8 s error
into a 30 s silent spinner. `TtsPlaybackBar` (`:96-104`) renders a bare
`CircularProgressIndicator` with `tts_loading_page` = "Loading page text…" — the diagnostic's
proposed "Scanning page N…" progress text was **never implemented**, and there is no elapsed-time
or page-number feedback.

---

## 2. Memory / leak analysis

No heap dump exists (§0), so this rests on ART GC telemetry in
`logcat-20260925-200614.log` — the only real memory signal in the repository.

| Metric | Value |
|---|---|
| GC log lines | 224 (176 background, 40 explicit/native-alloc) |
| Post-GC sample points parsed | 65 |
| Post-GC live set — min / mean / max | 11 MB / 36.5 MB / 55 MB |
| **`Clamp target` / `Grow heap` / growth-limit events** | **0** |
| Post-GC baseline, first 8 samples | 11, 11, 32, 20, 20, 21, 40, 44 MB |
| Post-GC baseline, last 8 samples | 38, 38, 41, 40, 52, 40, 21, 23 MB |
| Large-object-space freed per GC | 17–30 MB typical |

**Finding: no unbounded growth and no leak signature.** The live set is a sawtooth between
~11 MB and ~55 MB that returns to a **flat ~36–38 MB baseline**; the baseline does not trend
upward across the session, and the heap never hit a growth limit (0 clamp events). The
high-frequency GC cadence and 17–30 MB LOS frees are consistent with bitmap decode + OCR
buffer churn being collected normally, not retained.

Multi-page prefetching (Fix 3 lookahead) shows **no** cumulative memory cost in this data: the
busiest prefetch window (`pages=26..36`) shows no step change in the baseline.

**Evidence gap (not a finding):** these captures contain no `dumpsys meminfo` for
`app.yomihon.dev` (0 `TOTAL PSS` for the app; the single `Total PSS : 268813` in `leak-full.log`
is a Samsung `MemoryMonitor` system-wide line). Bitmaps live in the **native/graphics** heap,
which this telemetry does not cover. A native-bitmap growth measurement would require
`dumpsys meminfo <pid>` sampling across a long prefetch session; that data does not exist here.

---

## 3. Local Fast OCR engine (Task S4) feasibility

### 3.1 Current state — the recognizer exists, the *detector* does not

| Component | Status |
|---|---|
| `FastOcrEngine.kt` (507 lines) | **Fully implemented**, not a stub — LiteRT CPU, 224×224, KV-cache autoregressive decoder, pre-allocated buffers, `inferenceMutex` |
| `FastVocab.kt` (`vocabFast`, 9 415 tokens) | Present |
| `litert` dependency | Present — `libs.versions.toml:161` (`litert = "2.1.6"`), `data/build.gradle.kts:47` |
| `fastEngine` instantiation | Present but lazy — `OcrRepositoryImpl.kt:137-141` (`engineFor(EngineType.FAST)`) |
| `DetOcrEngine` | **Stub only** — `DetOcrEngine.kt:13` `UnavailableDetOcrEngine` throws `OcrException.DetectionUnavailable()` |
| `engineFor(EngineType.LEGACY)` | Returns `glensEngine` (`:134`) — not a real local engine |

`scanLocally` (`OcrRepositoryImpl.kt:554-590`) requires *both* halves:

```kotlin
val boxes = engineLocks.withDetectionLock { detectionEngine().detectTextRegions(bitmap) }
    .filter(OcrBoundingBox::isValid)
val regions = boxes.mapIndexedNotNull { index, box -> … recognizeWithEngine(type, crop) … }
```

`detectionEngine()` (`:155-161`) always returns the throwing stub, so every `OcrModel.FAST`
scan is caught by `scanLocalOrFallback` and redirected to GLENS. That redirect is the observed
production behaviour.

### 3.2 The `panel_detector` model cannot serve as the text detector

CI restores exactly three models (`.github/workflows/build.yml`, "Download ML models"):

| Model | Destination | Purpose |
|---|---|---|
| `bluolightning/manga-ocr-mobile:v1_fp16/encoder.tflite` | `app/…/assets/ocr_fast/encoder.tflite` | FAST **recognizer** |
| `bluolightning/manga-ocr-mobile:v1_fp16/decoder.tflite` | `app/…/assets/ocr_fast/decoder.tflite` | FAST **recognizer** |
| `leoxs22/manga-panel-detector-yolo26n:manga_panel_detector_int8.tflite` | `data/…/assets/panel_detector/model.tflite` | YOLO **comic-panel** detector |

`PanelDetectionRepositoryImpl` (`:82` `YoloPanelDetectionEngine`, `:48` `detectPanels`) returns
`tachiyomi.core.common.util.system.Panel` objects and does bubble grouping — it segments comic
panels for webtoon paging (`PagerPageHolder.kt:256` `maybeStartPanelDetection`), **not** text
lines. It is not adaptable to `DetOcrEngine.detectTextRegions(): List<OcrBoundingBox>`.

**There is no text-region detection model anywhere in the repository or the CI manifest.**

### 3.3 Compatibility requirements if S4 were re-enabled

- **Input contract:** fixed 224×224 RGB, single crop, single text line. Not a page.
- **Decoder:** two TFLite signatures `"init"` and `"step"`; `MAX_SEQUENCE_LENGTH=256`,
  `VOCAB_SIZE=9415`, `START=2`, `END=3`, tokens `< 5` skipped; KV cache shaped
  `[4 layers, 4 heads, 256 seq, 64 dim]`.
- **Accelerator:** CPU only (`Accelerator.CPU`, `cpuThreads` 2–4). No GPU/NPU path.
- **Concurrency:** one `inferenceMutex` serializes all inference. A page with 30 text regions
  costs 30 serialized encoder+decode passes. **No FAST timing exists in any capture** (zero
  `OCR(fast) Runtime:` lines), so the per-page cost is unmeasured — this is the single largest
  unknown for a TTS latency path.
- **Accuracy:** `OcrRepositoryImpl.kt:203-211` redirects `LEGACY`/`FAST` → GLENS with the note
  that these are JP-vocab models that "garble arbitrary English crops into kana/kanji". All
  captured content is English (Asura Scans, ch8721–8725, ch3285). FAST output on that content
  would be *worse* than GLENS, not a fallback.
- **Cache semantics:** `OcrPageResult` stores `ocrModel`, and `getCachedPage` filters on
  `ocrModelPref` (`:313`). Mixing FAST and GLENS results for the same page needs an explicit
  invalidation story.

### 3.4 APK size and memory impact

**APK size cost of restoring FAST: zero.** The models are already present on this machine and
already packaged in the built debug APK:

```
app/build/outputs/apk/debug/app-arm64-v8a-debug.apk  95 351 037 bytes
  assets/ocr_fast/encoder.tflite        8 454 520
  assets/ocr_fast/decoder.tflite       12 806 008
  assets/panel_detector/model.tflite    2 837 823
```

S4 is therefore **not** an asset-repackaging job — the assets and the dependency are already
shipped. The gap is code + a missing model.

**Memory cost if both FAST models stay resident** (computed from `FastOcrEngine` field sizes):

| Buffer | Size |
|---|---|
| `selfKCache` / `selfVCache` | 1.0 MB + 1.0 MB |
| `crossKCache` / `crossVCache` | 784 KB + 784 KB |
| `nchwBuffer` (FloatArray 224·224·3) | 588 KB |
| `pixelsBuffer` (IntArray 224·224) | 196 KB |
| `scratchBitmap` (224×224 ARGB_8888, native) | 196 KB |
| **persistent total** | **≈ 4.5 MB** |
| plus per-call `readFloat()` copies + LiteRT tensor arena + mmap'd weights (23 MB on disk) | — |

Expect roughly **25–40 MB additional RSS** with both models compiled, against a process whose
Java heap currently peaks at 36–55 MB. That is a material footprint increase for a fallback
path whose accuracy is unproven on the user's actual content.

### 3.5 Verdict on S4

**Not viable as an offline GLENS fallback, and not a repackaging job.**

1. It is blocked on a **text-detection model that does not exist** in the repo or the CI
   manifest — `DetOcrEngine` has only the throwing stub, and the YOLO panel model detects
   panels, not text.
2. Restoring it on JP-vocab recognizers would **degrade** output on the English content in
   every capture, making it worse than the GLENS path it would supposedly back up.
3. APK cost is already paid (0 bytes); the real costs are a new model, a new `DetOcrEngine`
   adapter, cache-invalidation semantics, and ~25–40 MB RSS.
4. **Scope lock:** `docs/state.md` lists *"local OCR engine reinstatement"* under
   **REJECTED**. This analysis is documentation only; any S4 work requires explicit user
   authorization to lift that lock.

A cheaper mitigation for the same GLENS-hang concern is in §5: reserve queue capacity and stop
duplicating work, rather than adding a second OCR stack.

---

## 4. Docker test setup corrections

1. **Two volumes are mandatory, not one.**
   - host `~/.gradle` → `/home/vscode/.gradle` — `GRADLE_USER_HOME` points there and that is
     where the real dependency cache lives. Mounting `~/.gradle/caches` to `/root/.gradle` (or
     `/home/user/…`) fails dependency resolution, e.g. JitPack
     `com.github.arkon.FlexibleAdapter:flexible-adapter:c8013533` not found.
   - `yomihon-android-home` → `/home/vscode/.android` — debug signing reads
     `/home/vscode/.android/debug.keystore`; there is no `keystore.properties` in the repo.
2. **Omitting the android-home volume silently mis-signs the APK.** Debug builds then sign with
   a stale key (SHA-256 `1a6fbe75…3881`) instead of the known-good `e486ea51…8968`, and
   `install -r` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. This reconciles the long-standing
   contradiction in `docs/memory.md` known-issue #7 (previously "volume keeps the key stable" vs
   "key rotated anyway"). Always `apksigner verify --print-certs` the APK against the installed
   package before installing, and never uninstall `app.yomihon.dev` without a current `.tachibk`.
3. **Always `-Xmx4g`.** The repo default `2560m` OOMs during packaging in the 7.4 GiB container.
4. **Test-execution hang correction.** `acquireSentences` runs inside `withIOContext` → real
   `Dispatchers.IO`; `runTest` / `advanceUntilIdle` virtual time cannot advance across that
   boundary, and the test hangs in `Preparing`/`LoadingPage`. `TtsOcrTimeoutGuardTest` instead
   drives a real `CoroutineScope(SupervisorJob())` + `runBlocking` + a bounded
   `withTimeout(10_000)`, polling `state.value.phase`. Required stubs: `mockkStatic` for `Log`
   **and** `SystemClock.elapsedRealtime() = 0L`; `Manga.id` / `Manga.source`;
   `Bitmap.isRecycled` / `.recycle()`; `TtsPreferences.speechRegionFilterConfig()` and
   `speechCleanupOptions()` directly rather than the nine individual pref getters.
   `TtsReaderOpenPrefetchTest` still uses `runTest` safely because its `scanOnDemand` is stubbed,
   so no real `withIOContext` hop occurs.

---

## 5. Findings and recommended remediations

No code was changed. Recommendations are ordered by measured impact.

| # | Severity | Finding | Evidence | Recommended fix |
|---|---|---|---|---|
| F1 | **High** | Prefetch dedup guard compares exact ranges, but the window slides by 1 each page → never dedups; 51 starts / 1 skip | §1.2(a) | Compare **overlap**, not equality; skip pages already in flight or cached |
| F2 | **High** | Cancelling `prefetchJob` does not cancel queued scans; 56/193 (29%) enqueues are duplicate pages | §1.2(b) | Add queue-level cancellation (or a staleness/drop signal keyed by page) so superseded prefetch tasks are discarded, not run to completion |
| F3 | **High** | Non-preemptive cap-3 lets redundant work starve the active page; p90 wait 9.2 s, max 17.2 s | §1.1, §1.3 | Reserve capacity for HIGH (cap 4, or hold 1 slot for HIGH) — **note: this changes documented RC-3 non-preemption semantics, so it is an architecture decision needing approval** |
| F4 | Medium | On uncached manga the sentence budget can never bind (6 × 5 = 30 < 40), so every page turn enqueues a full 6-page parallel batch | §1.4 | Raise `MIN_SENTENCES_PER_PAGE` or lower `TARGET_SENTENCE_BUFFER` so depth adapts to uncached content |
| F5 | Medium | Fix A turned an 8 s error into a 30 s silent spinner; Fix C's "Scanning page N…" text was never implemented (bare `CircularProgressIndicator`) | §1.5 | Add elapsed/page progress text after ~5 s in `LoadingPage`; keep the 30 s guard |
| F6 | Medium | A run of textless pages loops `acquireSentences` with no speech and no error, unbounded in wall-clock | §1.5 | Cap consecutive zero-sentence advances, or surface the page-skip in the UI |
| F7 | Low | 10 s `ADVANCE_CONFIRM_TIMEOUT_MS` is a silent stall if the viewer never confirms (0 occurrences observed) | §1.5 | Add progress feedback; behaviour is otherwise correct (transitions to `Paused`) |
| F8 | Info | Native/graphics heap growth during prefetch is unmeasured (no `dumpsys meminfo` for the app in any capture) | §2 | Sample `dumpsys meminfo <pid>` across a long prefetch session before claiming bitmap stability |
| F9 | Info | `engineFor(EngineType.LEGACY)` returns `glensEngine` | §3.1 | Dead branch; delete when convenient (out of scope for this audit) |

### Not problems

- **No memory leak.** LeakCanary verdict clean; ART live set flat at ~36–38 MB with 0 growth-limit
  events across 65 GC samples.
- **No app crash, no OCR error, no advance timeout** in any of the three OCR/TTS captures.
- **GLENS reliability is good:** 12/12 HTTP 200 with `postUploadWaitMs=0` in the post-fix cold
  capture; 183/183 tiles HTTP 200 in the earlier session.
- **The 8s `OcrError` false negative is fixed** for the captured cold path: HIGH `waitMs=1`,
  join-in-flight, `acquireMs=3142`, `OcrError=0`, cold-start→first-speech ≈ 5.97 s.

### Suggested verification for any F1/F2/F3 change

Re-run the contended shape, which none of the existing captures reproduce cleanly: open a
**fresh** chapter, let a background `OcrScanJob` start, then immediately start TTS. Success
criteria: HIGH enqueued with `waitMs < 100`, zero duplicate enqueues for the active page, and
cold-start→first-speech < 8 s. Capture must run long enough to cover a full chapter (the
09-26 capture died after ~26 s).
