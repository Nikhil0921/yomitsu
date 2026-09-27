# PP-OCRv5 Local Engine, Adaptive Hybrid Routing & Skipped-Word Root-Cause Audit

Date: 2026-09-26 · **Read-only inspection — zero source, schema, asset or git changes.**
Scope: local PP-OCRv5 Mobile feasibility (ONNX/LiteRT), Adaptive Hybrid (local + GLENS) design,
and the root cause of words/phrases skipped during Read-Aloud playback.
Build under audit: `v0.5.4.2 / versionCode 32`, `main @ 8732ed49d` + uncommitted 09-25/09-26 batches.
Prior art read: `docs/audits/full-ocr-system-resiliency-audit.md` (§3 S4 verdict), `docs/state.md`,
`docs/rules.md`, `docs/memory.md`.

No gates were run (no code changed). No device session was run; every device-shaped claim below is
labelled **HYPOTHESIS** and carries the exact test that would settle it.

---

## 0. Premise corrections (read these before the plans)

**P1 — `OcrPostProcessor.kt` does not exist.** The task names it; the real file is
`data/src/main/java/mihon/data/ocr/TextPostprocessor.kt` (243 lines, class `TextPostprocessor`).
There is no other post-processor. The speech-layer cleanup the task also names
(`SpeechCleaner.kt`, `SpeechPipeline.kt`) does exist, in `:domain`.

**P2 — The 09-26 S4 rejection is *not* a blanket prohibition on local OCR; it is a verdict about
one specific stack.** Its §3.5 reasoning was: (a) blocked on a text-**detection** model that exists
nowhere in the repo or CI manifest, (b) `manga-ocr-mobile` is a JP-vocab recognizer that garbles
English, (c) ~25–40 MB RSS. PP-OCRv5 Mobile invalidates (a) — it ships a **DB text detector** —
and (b) is fixable with the *English* recognition model + latin dictionary. (c) stands.
`docs/state.md` still lists *"local OCR engine reinstatement"* under **REJECTED**, so any
implementation in §5 needs explicit authorization to lift that lock (§8).

**P3 — The OCR data model has no confidence anywhere.** `OcrBoundingBox`
(`domain/.../ocr/model/OcrModels.kt:16-31`) carries only `left/top/right/bottom`;
`OcrRegion` (`:45-50`) carries `order/text/boundingBox/textOrientation`. The GLENS protobuf parser
(`GlensOcrEngine.kt:854+`, `ParsedWord` `:954-961`, `ParsedLine` `:963-974`) extracts **no score,
probability or confidence field** — `rg -ni "score|prob|conf"` over the file returns only
`postprocess`/`process` log strings. The OCR cache (`OcrCacheStore.kt:31-59`) persists no score.
**Consequence: a "cloud confidence < threshold" gate is not implementable today.** The adaptive
decision must be driven by signals the local model produces (§3.3). This is the single most
important design constraint in this report.

**P4 — "Skipped words" is very probably NOT an OCR-side drop.** GLENS's OCR pipeline drops text
only in three narrow places (§4.4–4.6), none of which is length- or word-count-based. Every
length-sensitive drop in the system lives in the **speech** layer, and two of them are
**on by default with no user-visible switch** (§4.1–4.2). This inverts the task's working
hypothesis.

**P5 — "~200 ms local inference" is a target, not a measurement.** The 09-26 audit established
there are **zero** `OCR(fast) Runtime:` lines in any capture, so no local engine has ever been timed
on this device. Treat 200 ms as an acceptance criterion to be measured, not a design input.

---

## 1. Existing local-OCR bindings (what we must build on, not around)

| Component | Path | State |
|---|---|---|
| Recognizer interface | `data/src/main/java/mihon/data/ocr/OcrEngine.kt:9-21` | `recognizeText(Bitmap): String` + `close()` — **page-agnostic, string-only** |
| Detector interface | `data/src/main/java/mihon/data/ocr/DetOcrEngine.kt:7-11` | `detectTextRegions(Bitmap): List<OcrBoundingBox>` + `close()` |
| Detector impl | `DetOcrEngine.kt:13-19` `UnavailableDetOcrEngine` | **throws** `OcrException.DetectionUnavailable()` |
| Recognizer impl | `FastOcrEngine.kt` (507 lines) | Implemented: LiteRT CPU, 224×224, KV-cache AR decoder, pre-allocated buffers, `inferenceMutex` (`:88`) |
| Vocab | `FastVocab.kt` + `FastVocabPart000/001.kt` | 9 415 tokens, compiled into the APK |
| Runtime dep | `libs.versions.toml:55,161` (`litert = "2.1.6"`), `data/build.gradle.kts:47` | **already shipped** |
| Local scan path | `OcrRepositoryImpl.kt:546-591` `scanLocally` | detect → `filter(isValid)` → crop → recognize → drop blank |
| Engine routing | `OcrRepositoryImpl.kt:132-161` `engineFor` / `detectionEngine` | `detectionEngine()` returns the throwing stub ⇒ **every** `FAST` scan is caught by `scanLocalOrFallback` (`:350-382`) and redirected to GLENS |
| Hard redirect | `OcrRepositoryImpl.kt:203-211` | `LEGACY`/`FAST` → `GLENS` for `recognizeText`, with the `ponytail:` note "drop this redirect when a real `DetOcrEngine` lands" |
| Engine locks | `OcrEngineLocks.kt:16-31` | GLENS/LEGACY run **unlocked**; FAST/OWOCR/detection serialized per-engine |

Assets present on this machine (gitignored, restored by CI "Download ML models"):

```
app/src/main/assets/ocr_fast/encoder.tflite   8 454 520
app/src/main/assets/ocr_fast/decoder.tflite  12 806 008
data/src/main/assets/panel_detector/model.tflite 2 837 823   (YOLO comic-PANEL detector — NOT text)
```

### 1.1 LiteRT capability, verified from the shipped AAR

`javap` against the exact artifact the app compiles against
(`~/.gradle/caches/…/litert-api-2.1.6/jars/classes.jar`):

```
CompiledModel$Companion:
  create(AssetManager, String, Options, Environment)
  create(String modelPath, Options, Environment)      ← absolute filesystem path
  createFromAsset$default / createFromFile$default
Accelerator: NONE | CPU | GPU | NPU
CompiledModel: run(Map<String,TensorBuffer>, Map<String,TensorBuffer>, String signature)
               createInputBuffer(signature, name) / createOutputBuffer(signature, name)
```

Three facts that decide the whole design:

1. **`create(String modelPath, …)` loads from an absolute path.** The on-demand downloader writes
   to app-internal storage and passes that path — no new dependency, no asset repackaging, no
   `AssetManager` gymnastics. `FastOcrEngine.kt:156-168` currently uses the `AssetManager` overload.
2. **`Accelerator.NPU` and `.GPU` are available** in 2.1.6, but `FastOcrEngine.kt:149` pins
   `Accelerator.CPU` with `cpuThreads = availableProcessors().coerceIn(2,4)` (`:148`). PP-OCRv5
   Mobile int8 is NPU-shaped; CPU is the portable default, NPU is an opportunistic upgrade
   (see R4 in §7).
3. **Signature-addressed I/O** (`run(map, map, "s3")`) — PP-OCRv5's DB + CTC graphs are
   single-signature, so buffer plumbing is simpler than the two-signature Fast decoder.

### 1.2 Why PP-OCRv5 Mobile specifically closes the S4 gap

| S4 blocker (09-26 audit §3) | PP-OCRv5 status |
|---|---|
| No text-**detection** model in repo or CI manifest | **DB detection model** is part of the standard PP-OCRv5 release ⇒ the missing half now exists, as a downloadable file |
| JP-vocab recognizer garbles English | PP-OCRv5 ships a dedicated **`en` recognition model** + latin char dict; drop `ocr_fast/*` from the path entirely |
| ~25–40 MB RSS for a fallback | DB det (~4–5 MB int8) + en rec (~10 MB int8) ≈ **14 MB on disk**, per the task brief. **Verify real sizes from the chosen release; do not hardcode.** Weights are mmap'd, so RSS ≈ resident pages, and only while the engine is live |
| 0-byte APK cost today | Still 0 bytes — on-demand download (§2) |

Cost of the recognizer contract mismatch: `OcrEngine.recognizeText(Bitmap): String` takes a
**single line crop** and returns a raw string. PP-OCRv5's rec head is CTC over a 48×320 strip
with its own dict + a trailing blank-id. The strip preprocessing, dict decode, and
confidence extraction are ~120 lines. No interface change needed; do **not** widen the interface
for this.

---

## 2. On-Demand Model Downloader — design

### 2.1 Constraints found in the repo

- **WorkManager is already a dependency**: `libs.versions.toml:27,122` (`androidx-work = "2.11.2"`),
  `app/build.gradle.kts:263`. Precedent to copy verbatim: `app/src/main/java/eu/kanade/tachiyomi/data/ocr/OcrScanJob.kt`
  — `CoroutineWorker` + `getForegroundInfo()` (`:34-49`) + `enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, …)` (`:71-74`)
  + `isRunningFlow()` off `getWorkInfosForUniqueWorkLiveData` (`:86-91`) and `Notifications.CHANNEL_OCR_PROGRESS`
  + `FOREGROUND_SERVICE_TYPE_DATA_SYNC`.
- **OkHttp is already in use for OCR**: `GlensOcrEngine.glensHttpClient` (per memory §S5, a
  dedicated `OkHttpClient`). `NetworkHelper.client` is the shared alternative
  (`core/common/.../network/Requests.kt` provides `GET`/`POST` builders).
- **No `DiskLruCache` class exists** in the repo (the reader's `ChapterCache` is a
  `DiskLruCache` from OkHttp's sibling lib under a different name). Nothing to reuse for model
  storage; app-internal files are the right answer.
- **Zero new permissions required** — internal storage, no `MANAGE_EXTERNAL_STORAGE`
  (rules §8: add no permission without approval).

### 2.2 Files (exact paths)

```text
domain/src/main/java/mihon/domain/ocr/model/OcrModel.kt          (edit)  + PPOCR, + ADAPTIVE
domain/src/main/java/mihon/domain/ocr/service/OcrPreferences.kt   (edit)  + 4 prefs (§2.5)
app/src/main/java/eu/kanade/tachiyomi/data/ocr/OcrModelDownloadJob.kt  (new)  worker
app/src/main/java/eu/kanade/tachiyomi/ui/download/OcrQueueScreen.kt   (edit) entry + progress row
```

Model storage resolution belongs next to the engines that read it (`mihon.data.ocr`), not in
`:app`: the worker writes, the engines read, and both must agree on the path.

```text
data/src/main/java/mihon/data/ocr/PpOcrModelStore.kt   (new)  path + integrity + version stamp
data/src/main/java/mihon/data/ocr/PpOcrDetEngine.kt     (new)  DetOcrEngine impl
data/src/main/java/mihon/data/ocr/PpOcrRecEngine.kt     (new)  OcrEngine impl
data/src/main/java/mihon/data/ocr/PpOcrAssets.kt        (new)  URLs + sha256 manifest
```

### 2.3 Storage layout

```text
context.filesDir/ocr_models/ppocrv5/<manifestVersion>/
    det_mobile_int8.onnx(or .tflite)
    rec_en_mobile_int8.onnx(or .tflite)
    dict_en.txt
    MANIFEST.json      { version, files[], sha256[], bytes, installedAt }
```

- Version directory ⇒ a model upgrade is a new dir + atomic `MANIFEST.json` rename; the old dir is
  deleted after the swap. No partially-upgraded state, no cache invalidation coupling.
- Download to `*.part`, verify sha256 over the **whole file** (the CI "Download ML models" step
  already establishes the sha256-pinning precedent for ML assets), then rename.
- `PpOcrModelStore.available()` = `MANIFEST.json` parses **and** both files hash-match the
  manifest. A truncated `filesDir` copy (restored backup, interrupted write) therefore reads as
  "absent" and the UI offers the download again — the failure mode we want.

### 2.4 Worker

`OcrModelDownloadJob` mirrors `OcrScanJob` exactly:
`CoroutineWorker`, `setForegroundSafely()`, `getForegroundInfo()` on a new
`Notifications.CHANNEL_OCR_MODEL` (or reuse `CHANNEL_OCR_PROGRESS` if adding a channel is not
wanted — one fewer resource is lazier), `enqueueUniqueWork("OcrModelDownload", KEEP, …)`.
`Result.retry()` on IOException/5xx, `Result.failure()` on sha256 mismatch (a corrupt mirror is
not a transient error — retrying it forever is the bug we are avoiding).
`setProgress` per file for the UI row; `isRunningFlow` copied from `OcrScanJob.isRunningFlow`.

### 2.5 Preferences (`OcrPreferences.kt`, existing style: `preferenceStore.get*`)

```kotlin
fun ppOcrInstalledVersion() = preferenceStore.getString("pref_ppocr_installed_version", "")
fun ppOcrAutoDownload()    = preferenceStore.getBoolean("pref_ppocr_auto_download", true)
fun adaptiveLocalFirst()   = preferenceStore.getBoolean("pref_ocr_adaptive_local_first", true)
fun adaptiveConfidenceFloor() = preferenceStore.getFloat("pref_ocr_adaptive_confidence", 0.80f)
```

`ppOcrInstalledVersion` is the durable truth; the filesystem is only a cache of it. i18n keys go
in `i18n/src/commonMain/moko-resources/base/` only (rules: never hand-edit other locales).

### 2.6 UI

`OcrQueueScreen.kt:202-206` currently offers `FAST | GLENS | OWOCR`. Add `PPOCR` and
`ADAPTIVE`, and a download row that appears only when `PpOcrModelStore.available()` is false
(with bytes + progress + retry). Reuse the existing `ListPreferenceWidget` /
`SwitchPreferenceWidget` / `PreferenceGroupCard` primitives; **do not** build a new
download-management screen for one model.

---

## 3. Adaptive Hybrid (local-first, GLENS fallback) — design

### 3.1 The routing seam already exists and is small

```kotlin
// OcrRepositoryImpl.kt:268-304  dispatchScan()
when (val selectedModel = ocrModelPref.get()) {
    OcrModel.GLENS -> scanWithGlens(…)
    OcrModel.LEGACY -> scanLocalOrFallback(…, type = LEGACY, …)   // :350-382
    OcrModel.FAST  -> scanLocalOrFallback(…, type = FAST,  …)
    OcrModel.OWOCR -> scanOwOcrOrFallback(…)                        // :513-544
}
```

`scanOwOcrOrFallback` (`:513-544`) is the **existing precedent for a two-engine path**: try
primary, on any non-cancellation throwable, honour `useFallbackModelsPref` (`:530`), log, then run
GLENS. An adaptive path mirrors it:

```kotlin
OcrModel.ADAPTIVE -> scanAdaptive(…)  // try scanLocally(PPOCR); on LowConfidenceException -> scanWithGlens
```

`scanLocally` (`:546-591`) is reusable **as-is** for the page→regions shape — it detects, crops,
recognizes, drops blank crops, and returns an `OcrPageResult`. Two changes it needs:

- `recognitionConfidence` is currently dropped at `:563` (`val text = recognizeWithEngine(type, crop).trim()`).
  The PP-OCRv5 rec engine must return text **+ mean CTC prob**, so the decision can be made after
  the page is recognized, not per-crop mid-loop.
- The `OcrException.DetectionUnavailable` catch at `:367` must not swallow the new
  `LowConfidence` signal — use a distinct exception type, never reuse `DetectionUnavailable`
  (that one means "no detector installed" and is already load-bearing for the FAST redirect).

### 3.2 Confidence is one-directional (P3)

| Signal | Available today? |
|---|---|
| Local PP-OCRv5 per-char CTC probability | **Yes** — free from the rec head |
| Local detector score map (DB probability) | **Yes** — the det output map is a probability field |
| GLENS per-word/per-region confidence | **No** — never parsed (`GlensOcrEngine.kt:854+`) |
| Cached-region confidence | **No** — `OcrCacheStore.upsert` (`:31-59`) stores no score |

So the gate can only be **local-score → cloud escalation**. It cannot be "cloud says local was
wrong". Design accordingly: local is the proposer, GLENS is the escalation, and the winner's
regions are the only thing persisted. Do not build a two-engine agreement check.

### 3.3 The decision function — pure, in `:domain`

New `domain/src/main/java/mihon/domain/ocr/model/OcrQuality.kt`, pure Kotlin, no Android, so it is
covered by `testDebugUnitTest` (the only test task CI runs — §6):

```kotlin
data class OcrPageSignals(
    val regionCount: Int,
    val meanCharConfidence: Float,   // 0f when unavailable
    val confidenceAvailable: Boolean,
    val meanCharsPerRegion: Float,
    val meanBoxHeightNorm: Float,    // relative to page height
    val nonLatinCharRatio: Float,    // full-width / CJK / rare unicode
    val blankOrGarbageRatio: Float,
)

enum class OcrRoute { ACCEPT_LOCAL, ESCALATE_CLOUD }

object OcrQualityRouter {
    fun route(s: OcrPageSignals, floor: Float): OcrRoute
}
```

Initial policy (all constants named, one place, no magic numbers scattered in `:data`):

| Signal | Escalate when |
|---|---|
| `confidenceAvailable` | false → always escalate (fail-safe) |
| `meanCharConfidence` | `< floor` (default 0.80) |
| `regionCount` | `0` — the "p0 = cover" case the 09-26 capture already exercises |
| `meanCharsPerRegion` | `< 1.5` — text detected but nothing readable: the classic "bubbles found, words missing" shape |
| `meanBoxHeightNorm` | `< 0.008` — boxes so small they are noise, not text |
| `nonLatinCharRatio` | `> 0.15` on an English page — see §4.3, this is the *same* signal that corrupts GLENS output |
| `blankOrGarbageRatio` | `> 0.30` — reuse the exact `SpeechCleaner.isOcrGarbage` predicate so the two layers agree on what "garbage" means (currently duplicated knowledge in two modules) |

Escalation is **per page**, never per region. Per-region escalation would multiply GLENS
round-trips (`TILE_CONCURRENCY = 4`, `GlensOcrEngine.kt:1051`) and re-introduce the 5–12 s
uncached latency the 09-26 pass measured. If a page is escalated, the **whole** page goes to
GLENS and the local result is discarded — no region merging, no `OcrBoundingBox` reconciliation,
no cache key ambiguity. `ponytail: whole-page escalation only; per-region merge if measurement
ever shows mixed-quality pages are common.`

### 3.4 Preference routing across prefetch and background scans — already free

`dispatchScan` reads `ocrModelPref.get()` **at scan time** (`OcrRepositoryImpl.kt:273`), inside
`submitTask`. Consequences:

- `OcrScanJob` → `OcrChapterScanner` → `ScanPageOcr` (NORMAL) and
  `TtsPlaybackController.prefetchReaderOpenPage` (HIGH, post-Fix-B) both route through the same
  read. Changing the pref re-routes prefetch, background chapter scans, reader-open p0, and
  TTS on-demand scans with **no extra wiring**.
- Do **not** introduce a separate "hybrid" flag consulted by the TTS controller. That is a second
  source of truth that can disagree with `OcrModel` and it is the class of bug the 09-26 F1/F3/F4
  work just removed.
- `getCachedPage` filters on `ocrModelPref.get()` (`:306-315`), so switching models naturally
  misses the cache and re-scans. Correct, and free.

### 3.5 Cache-key story (must be decided, not drifted into)

`OcrPageResult.ocrModel` is persisted as a **string** (`OcrCacheStore.kt:39`) and read back with
`OcrModel.valueOf(_ocrModel)` (`:87`). Adding enum values is therefore **free**: no `.sq`
change, no `.sqm` migration, no `verifySqlDelightMigration` run.

But: `deleteDatabaseIfSchemaOutdated()` (`OcrCacheStore.kt:193-227`) probes `ocr_regions` for an
`orientation` column and **deletes the whole OCR cache DB** if the probe fails. Any future column
change (e.g. persisting a confidence) silently wipes all cached OCR. Choose now:

- **Option A (recommended, lazier):** persist no confidence. Route at scan time; store only the
  winner's regions under the enum value that produced them. Zero schema risk.
- Option B: add `confidence REAL` to `ocr_regions` **and** extend the probe at `:222-227` in the
  same change, or the next app start deletes every cached page.

Record the winner as `OcrModel.ADAPTIVE` (not `PPOCR`) so a later decision to change the
threshold does not silently re-key the cache; if the user later switches the pref to pure
`PPOCR`, they get a cache miss and a re-scan, which is the desired semantic.

### 3.6 Concurrency

- `OcrEngineLocks.withDetectionLock` (`:27-31`) and `withTextEngineLock(FAST)` (`:12-25`)
  already serialize local interpreters. PP-OCRv5 engines go under the **same** locks; do not add
  a new mutex tier. Note the consequence: while a local page scan runs, a GLENS scan is *not*
  blocked (GLENS is unlocked, `:22`), so hybrid pages degrade gracefully to "GLENS is just
  faster" rather than to a stall.
- `FastOcrEngine.inferenceMutex` (`:88`) serializes everything inside one engine. Two PP-OCRv5
  engines (det + rec) are two objects ⇒ det of page *n+1* can overlap rec of page *n* only if
  they use different locks. Keep one mutex per engine; that is sufficient and lazier than a
  shared pipeline lock.
- The 09-26 Fix-2 slot reservation (`backgroundSlotCeiling = maxConcurrentTasks - 1`) means a
  local scan still occupies a queue slot. Local inference at ~200 ms makes that slot *cheaper*,
  not more contended. No queue change needed.

### 3.7 What "adaptive" must NOT do

- Do not run local and cloud concurrently and pick the better answer (P3: no cloud score).
- Do not merge local + cloud regions on one page (box reconciliation, ordering, and tap-highlight
  all assume one region's order; `SentenceSegmenter`/`SpeechPipeline` consume stored order).
- Do not re-enter `scanWithGlens` while a local scan is still holding a bitmap — `OcrImage`
  bitmap ownership is `useBitmap`-scoped (`OcrRepositoryImpl.kt:433`) and recycling is the
  caller's job.

---

## 4. Skipped words & phrases — root causes, ranked

Nothing in this section is speculative about *where* the code is; every row is code-verified.
Rows marked **HYPOTHESIS** need a device capture to confirm they fire on the user's content.

### 4.1 D1 — `EXPRESSION` / `SOUND_EFFECT` classification, **both silent by default** (PRIMARY)

`domain/src/main/java/mihon/domain/tts/speech/SpeechRegionClassifier.kt`:

```kotlin
:27  private val INTERJECTION = Regex("^[A-Z' ]{2,6}$")
:54  if (isUpper && letters.length <= 8 && hasEmphasis) return SOUND_EFFECT   // e.g. "WHAT??", "STOP!!", "OKAY!"
:55  if (isUpper && INTERJECTION.matches(text))          return EXPRESSION      // e.g. "OK", "NO", "HI", "MEH", "RUN"
:56  if (INTERJECTION.matches(text))                      return EXPRESSION      // also lower-case "ok", "no", "hi"
```

`domain/src/main/java/mihon/domain/tts/service/TtsPreferences.kt`:

```kotlin
:35  fun ttsSpeakSoundEffects()  = …getBoolean("pref_tts_speak_sfx", false)
:37  fun ttsSpeakExpressions()   = …getBoolean("pref_tts_speak_expressions", false)
```

`SpeechRegionFilter.kt:36-44` then drops the region, because `typeAllowed` is false.

**This is the dominant cause and it is a default, not a bug in a heuristic.** Concretely
unvoiced, with shipping defaults and no UI hint:

| Region text | Classified | Spoken? |
|---|---|---|
| `OK` / `NO` / `YES` / `HI` / `MEH` / `RUN` / `HELP` (2–6 upper, no `!/?`) | EXPRESSION | **no** |
| `ok` / `no` / `hi` (lower-case, same regex) | EXPRESSION | **no** |
| `WHAT??` / `STOP!!` / `OKAY!` / `NO!!!` (upper ≤8 letters + emphasis) | SOUND_EFFECT | **no** |
| `I'M READY` (9 letters → `INTERJECTION` fails, emphasis absent) | DIALOGUE | yes |
| `Okay, I'm ready` | DIALOGUE | yes |

The exact shape the task describes — *"okay"*, *"I'm ready"*, single-word bubbles — splits
cleanly: **multi-word bubbles survive, isolated short all-caps interjections do not.** That is
the signature of this bug, and it explains why the user perceives it as random word loss: the
missing text is always the short emphatic bubble.

Secondary defect in the same block: `:56` classifies a **lower-case** `^[A-Z' ]{2,6}$` match as
EXPRESSION (the regex is upper-only, so `:56` only ever fires for upper text — it is dead code
that looks like it covers the lower-case case, and reviewers assume it does).

### 4.2 D2 — full-width conversion + `skipForeignScript` deletes mixed-script bubbles

`data/src/main/java/mihon/data/ocr/TextPostprocessor.kt:205-215`: if a single line contains any
Japanese codepoint (`:122` `hasJapaneseText`, `:224-230` `isJapaneseScript`), **every** ASCII
char in that line is mapped half→full-width through `HALF_TO_FULL_TABLE` (`:4-99`).

`domain/.../speech/SpeechRegionFilter.kt:45-50` + `SpeechRegionClassifier.kt:65-93`
(`dominantScript`) then run on the mutated text. Full-width Latin (`Ａ`, `0xFF21`) is in
`HALFWIDTH_AND_FULLWIDTH_FORMS`, which `dominantScript` counts as `other`. For an all-full-width
region: `latin = 0, cjk = 0, other = n` ⇒ `:91` `latin >= other` false ⇒ returns `OTHER` ⇒
`script == config.speechScript` (default `LATIN`) is false ⇒ **region dropped**.

Net: **one stray kana/kanji glyph anywhere in an English bubble silently deletes the whole
bubble.** This is also why D1's damage is understated — the same content that gets dropped here
would also have been mis-classified there.

Also note `SpeechRegionClassifier.kt:46`: `script == CJK ⇒ DECORATIVE`, and DECORATIVE is
`false` in `SpeechRegionFilterConfig:16` — correct for Japanese pages, and for English pages it
means any kana-recognizing OCR output is thrown away wholesale.

### 4.3 D3 — GLENS is hard-hinted to Japanese, which flips English pages into the JP pipeline

```kotlin
// GlensOcrEngine.kt
:373   localeContext.writeString(fieldNumber = LOCALE_LANGUAGE, value = DEFAULT_CLIENT_LANGUAGE)
:1044  private const val DEFAULT_CLIENT_LANGUAGE = "ja"
:1045  private const val DEFAULT_CLIENT_REGION = "Asia/Tokyo"
```

Not configurable — no pref, no constant override, no per-request variation anywhere in the file.

Consequence chain on an English manhwa page:

```kotlin
:467  val hasJapaneseContent = allLines.any { it.hasJpText }      // hasJpText = containsJapanese (:629/:839)
:468  if (!hasJapaneseContent) allLines                            // English fast path
:471-473  else verticalLines.filter{hasJpText} + horizontalLines.filter{hasJpText} + nonJpLines
:475-482  filterRuby(vertical…), filterRuby(horizontal…)
:483  filteredVertical + filteredHorizontal + nonJpLines          // ← ORDER REWRITTEN
```

1. **A single line that Lens labels as JP** (`hasJpText` true) sends the *whole page* down the
   Japanese branch.
2. **Region order is rewritten** (`:483`): all vertical-JP first, then horizontal-JP, then the
   rest. TTS speaks in stored region order (`SpeechPipeline` KDoc `:16-18`,
   `SentenceSegmenter.kt:17-19`) and tap-highlight follows the same order. Result: text spoken
   **out of reading order** — which a user reports as "words are missing", not "words are
   reordered". This is the second-most-likely cause and it is invisible in the current logs.
3. **`filterRuby` can DELETE a line** (`:668-715`): a line that is `hasJpText`, has no kanji,
   is followed by a kanji line, is aligned within `|Δ| ∈ (|sizeDiff|/2, base+half)` with
   overlap > 0.4, and is `< 0.85 ×` the base size, is consumed as furigana (`:706-708` — `next`
   is kept, `current` is **discarded**, `index += 2`). Two English lines of unequal size that
   Lens marks as JP lose one of them. **HYPOTHESIS** — needs a capture; it is the only code path
   in the OCR layer that deletes whole lines by a size heuristic.
4. `filterRuby` is skipped entirely on a pure-English page (`:469`), which is why the bug is
   page-dependent and looks random.

### 4.4 D4 — IoU dedupe can delete a small region nested in a larger one

```kotlin
// GlensOcrEngine.kt
:112   val ordered = dedupeOverlapping(regions)…        // runs on BOTH single and tiled paths
:305-316  kept.any { IoU(existing, region) >= DUPLICATE_IOU_THRESHOLD } → dropped
:1052  private const val DUPLICATE_IOU_THRESHOLD = 0.45f
```

Dedupe exists for tile seams (tall strips tile with `TILE_OVERLAP_RATIO = 0.2`, `:1050`), where
the same physical text is seen twice. But the test is **position-only** — it never compares text.
A short bubble that sits ≥45 % inside a larger box (a word in a caption, a short line inside a
merged cluster, two bubbles that abut) has IoU ≥ 0.45 against the larger box and is deleted
regardless of content. The larger region is kept because `sortedBy { top }` puts it first.
Region count on such a page drops by 1 and the log line `:117` `in=… out=…` records it.

### 4.5 D5 — words with no position are dropped

`GlensOcrEngine.kt:603` and `:823` both do `words.filter { it.centerX > 0f || it.centerY > 0f }`
before line assembly. Any word Lens returns with absent coordinates (0,0) is silently discarded
before it can reach a bubble. Rare, but it is a whole-word drop with no log.

### 4.6 D6 — the local path drops blank crops, and that is the only length-sensitive OCR drop

`OcrRepositoryImpl.kt:564-566`: `if (text.isBlank()) null`. No minimum length, no minimum
confidence, no minimum box size anywhere in `scanLocally` (`:546-591`) beyond
`OcrBoundingBox.isValid()` (`OcrModels.kt:28-30` — geometry only). `GlensOcrEngine`'s only
equivalent is `:489 .filter { it.text.isNotBlank() }` and `toRegion`'s `:982`. **There is no
word-count or text-length filter in either OCR path.** That is the proof for P4.

### 4.7 D7 — speech-layer drops that are *mostly* correct (do not "fix" these blindly)

| Site | Rule | Verdict |
|---|---|---|
| `SpeechCleaner.kt:45` | `skipPunctuationOnly` — no letter/digit ⇒ drop | correct; kills `"!!!"` SFX on purpose |
| `SpeechCleaner.kt:56-61` | `isOcrGarbage`: `length < 4` short-circuit, then `meaningful < 0.4 × nonWhitespace` | **cannot** drop short dialogue — the `< 4` guard means "okay"/"no"/"run" are exempt by construction. Keeps symbol soup out |
| `SpeechPipeline.kt:40` | final `filter { it.text.any { isLetterOrDigit() } }` | correct; removes punctuation-only slices left by terminal splitting |
| `SpeechPipeline.kt:47-61` | `dedupeOverlappingDuplicates` — same normalized text **and** strict AABB overlap | correct; disjoint duplicates (same word in two bubbles) survive by design (`:42-45`) |
| `TextPostprocessor.kt:113-114` | `dropWhile(isEmpty).dropLastWhile(isEmpty)` | drops blank lines only, not words |
| `TextPostprocessor.kt:142-149` | `shouldKeepSpace` requires both neighbours non-Japanese | on pure English, spaces are kept. Only bites mixed lines — same trigger as D2 |
| `mergeSpacedSingleLetters` (`OcrTextSanitizer.kt`) | collapses `I a m` → `Iam` | known, documented 09-26 limitation; shape-only, no NLP |

### 4.8 Ruled out

- **Exclusion zones** (`OcrExclusionMatcher`, applied at `TtsPlaybackController.kt:621`): user-created
  only; no seeded/default rules exist (`rg "seed|DEFAULT_ZONE"` over
  `OcrExclusionZone.kt` + `OcrExclusionZoneRepositoryImpl.kt` returns nothing). The 09-26 fix made
  matching 0.1 % of acquire time. Not a cause unless the user authored zones.
- **`SentenceSegmenter`** (`SentenceSegmenter.kt:28-49`): splits only, never drops; ASCII `.` is
  terminal only as a single dot before whitespace/EOL (`:51-57`), so `3.14` and `...` are safe.
- **Queue starvation / duplicate scans** (09-26 F1/F2/F3, all fixed and device-verified): produces
  *silence*, not missing words. `queue wait p90 = 1 ms`, duplicates `0/17`.
- **TTS engine**: `TtsEngine.speak` failures `fail()` the session (`TtsError`), they do not skip.

### 4.9 Cheapest fix, ranked by (words recovered ÷ diff size)

1. **Flip the two defaults, or scope them.** D1 is the whole bug for the reported symptom and is
   ~2 lines: `TtsPreferences.kt:35,37` default `false` → `true`, **or** narrow
   `SpeechRegionClassifier.kt:54` so SOUND_EFFECT requires a *non-alphabetic* component (real
   SFX are `GRRR`, `THUD`, `!!`, `——`) and never fires on `WHAT??` / `STOP!!`. Narrowing is the
   better fix: it recovers the words without re-enabling `GRRR`-style noise. Either way the
   setting must be **visible in settings**, not a hidden pref.
2. **Make D2 impossible**: gate `TextPostprocessor`'s full-width conversion on *per-character*
   script, not `hasJapaneseText` for the whole line (`:205`), or gate
   `SpeechRegionFilterConfig.skipForeignScript` on `script == OTHER` only (already is) **and** run
   `dominantScript` on the *pre*-full-width text. One-line-ish, but it changes cached text
   semantics — decide whether it applies at scan time (needs cache invalidation) or in
   `SpeechPipeline` (speech-only, no invalidation). Speech-only is the lazier and safer choice.
3. **Make D3 impossible for English**: parameterize `DEFAULT_CLIENT_LANGUAGE`/`DEFAULT_CLIENT_REGION`
   off a pref (`"en"`/`"US"` default) instead of the hard-coded `ja`/`Asia/Tokyo`. Small diff,
   large accuracy effect — and it is the correct fix regardless of the hybrid engine.
4. **Order-stability guard for D3's reorder**: after `parseResponsePage:483`, re-sort by reading
   order before `toRegion(order = index)` so stored order always matches the page. Cheap, and it
   also protects tap-highlight.
5. **D4**: add a text-equality precondition to `dedupeOverlapping` (`:309-312`) so it only drops
   genuine seam duplicates; or require `IoU >= 0.8`. Do not remove it — it is load-bearing for
   tiled webtoons.
6. **D5/D6**: log, do not change behaviour.

### 4.10 Proof plan for D3 (the only one that needs a device)

The engine already logs the counters needed — no new instrumentation required for the reorder
half. Capture one cold uncached English page with TTS running and check:

```bash
adb logcat -d | rg "OCR\(glens\) page postprocess|OCR\(glens\) page result|OCR scan glens"
```

Then add **one** temporary `logcat(DEBUG)` in `parseResponsePage` printing
`hasJapaneseContent` and the pre/post line counts of `:468-484`. If `hasJapaneseContent == true`
on an English page, D3 is confirmed and the fix is the locale pref. Until that capture exists,
D3 stays **HYPOTHESIS**; D1 does not — it is provable by reading three files.

---

## 5. Implementation plan (phased; nothing here is authorized yet — §8)

**Phase 0 — unblock the words bug (no new engine, no new deps).**
Smallest diff that recovers the reported symptom, and it is independent of PP-OCRv5.
`TtsPreferences.kt:35,37` / `SpeechRegionClassifier.kt:54-56` / `TextPostprocessor.kt:205` /
`GlensOcrEngine.kt:1044-1045` (locale pref) / `GlensOcrEngine.kt:483` (order re-sort).
Tests: `SpeechRegionClassifierTest` (new, table-driven over the §4.1 table),
`SpeechCleanerTest` (existing, must stay green — proves no regression on the correct drops),
`GlensOcrEngine` order + locale unit tests if the parse is JVM-testable (it is: proto parsing is
pure `ProtoReader` over bytes).

**Phase 1 — PP-OCRv5 detector only (smallest slice that proves the thesis).**
`PpOcrAssets.kt` (manifest with sha256), `PpOcrModelStore.kt`, `PpOcrDetEngine.kt`,
`OcrModelDownloadJob.kt`, `OcrPreferences` +2 prefs, `OcrQueueScreen` download row,
`OcrModel.PPOCR`. Wire `detectionEngine()` (`:155-161`) to return it **only** when installed and
selected; keep `UnavailableDetOcrEngine` otherwise so every existing redirect keeps working.
Measure: detection-only ms/page on SM_M066B. This is the number that decides Phase 2 — if
detection alone is > 400 ms/page, the local-first thesis dies and this report's §3 plan is
abandoned in favour of keeping GLENS plus Phase 0.

**Phase 2 — PP-OCRv5 recognizer + `OcrModel.ADAPTIVE` + `OcrQuality.kt` router.**
`PpOcrRecEngine.kt` (CTC greedy, 48×320 strip, en dict, mean prob), `scanAdaptive` in
`OcrRepositoryImpl` mirroring `scanOwOcrOrFallback` (`:513-544`), `EngineType.PPOCR` +
`OcrEngineLocks` (reuse `fastMutex`), remove the `:203-211` redirect **only** for the new types.
Decide §3.5 Option A vs B here, in writing, before touching the cache.

**Phase 3 — delete the dead local stack (only after Phase 2 is device-verified).**
`FastOcrEngine.kt` (507), `FastVocab*.kt` (3 files, 9 415 tokens), `ocr_fast/*.tflite` (21 MB of
APK), the `panel_detector` YOLO model if the panel feature is confirmed unused, and the
`ponytail:` redirect at `:203-211`. This is the only phase with a **net APK reduction**; leaving
it undone means shipping two local OCR stacks forever.

---

## 6. Test strategy

**CI runs `spotlessCheck`, `testDebugUnitTest`, `verifySqlDelightMigration`, `assembleRelease`
(`.github/workflows/build.yml:61,65,68,78`). It does NOT run `androidTest`** — yet `data/src/androidTest`
and `app/src/androidTest` exist. Therefore:

- **Everything CI-checkable must be pure and in `:domain`** (rules §2). `OcrQualityRouter`,
  `OcrTextSanitizer`-style preprocessing, NMS, the DB/CTC post-decode, and the `INTERJECTION`
  classification table are all pure and belong there.
- **`data/src/androidTest`** is where a real `Bitmap` → detector → boxes test belongs
  (precedent: `app/src/androidTest/java/mihon/data/ocr/OcrRepositoryImplTest.kt`). It will not
  gate PRs; say so in the plan rather than pretending it does.
- **Inference itself cannot be unit-tested.** Gate it on a device benchmark instead: a debug
  counter in `PpOcrDetEngine`/`PpOcrRecEngine` logging
  `OCR(ppocr) Runtime: det=…ms rec=…ms regions=… meanConf=…`, collected over ≥ 20 pages of the
  user's own content, comparing local vs GLENS text on the same bitmaps.
- **Acceptance for Phase 1/2** (all device-measured, no estimates):
  1. det-only p50 ≤ 250 ms/page on SM_M066B;
  2. full local page p50 ≤ 400 ms (the task's "~200 ms" target is a stretch goal, P5);
  3. ≥ 95 % of pages route `ACCEPT_LOCAL` (an escalation rate above ~20 % means the local model is
     not earning its place and the hybrid is a pessimization);
  4. zero `OcrError`, zero regressions in `speech dispatch` count on a fixed chapter;
  5. Phase 0 fixes verified as: for a fixed page, the spoken-sentence list from
     `toSpeakableSentences` contains `OK`, `NO`, `WHAT??` where GLENS produced them.
- Local gates after every phase: `spotlessCheck testDebugUnitTest :app:assembleDebug` in the
  `vsc-yomihon-*` container with `-Xmx4g` **and both volumes** (`~/.gradle` →
  `/home/vscode/.gradle`, `yomihon-android-home` → `/home/vscode/.android`, else the APK is
  mis-signed `1a6fbe75…` and `install -r` fails).
- The 09-26 gotcha still stands: never wrap a real-`Dispatchers.IO` join in `withTimeout` inside
  `runTest`; `acquireSentences` hops to real IO.

---

## 7. Risk register

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| R1 | Hybrid becomes a **pessimization**: every page runs local inference *and* a GLENS round trip | High | `OcrQualityRouter` escalates **before** any network call; measure the accept rate (acceptance #3). `fallbackFor(GLENS)` already returns `FAST` (`:117-124`) — a cycle to audit when `ADAPTIVE` is added |
| R2 | Local det+rec RSS (~25–40 MB class, per the 09-26 §3.4 computation method) on top of a 36–55 MB live set | Medium | mmap'd weights; `close()` on `OcrRepositoryImpl.cleanup()`; measure `dumpsys meminfo <pid>` (the 09-26 evidence gap F8, still open) |
| R3 | `OcrCacheStore.deleteDatabaseIfSchemaOutdated` (`:209-227`) wipes all cached OCR on any column change | Medium | §3.5 Option A (persist nothing new) |
| R4 | `Accelerator.NPU` behaviour on SM_M066B is unproven; LiteRT 2.1.6 exposes it but PP-OCRv5 int8 op coverage on that NPU is unknown | Medium | Ship `Accelerator.CPU` first (matches `FastOcrEngine.kt:149`); NPU behind a pref, measured separately |
| R5 | `OCR_ACQUIRE_TIMEOUT_MS = 30_000` (post-Fix-A) would silently absorb a 30 s local stall as a spinner | Medium | Instrument `det=`/`rec=` ms so a local stall is visible instead of inferred from silence |
| R6 | Model download is a new outbound service (§8 rules: needs PRD-level justification) | Medium | Pinned sha256 manifest, no analytics, no new domain; record under `docs/memory.md §Dependencies` |
| R7 | Full-width + `skipForeignScript` (D2) means **any** bubble containing a CJK glyph is deleted; PP-OCRv5's en model reduces but does not eliminate stray CJK | Medium | Phase 0 item 2 removes the mechanism, not just the instance |
| R8 | `OcrModel.valueOf` on a cache row (`:87`) throws for an unknown name — a downgrade after a future enum removal | Low | Never remove enum values; add, don't replace |
| R9 | ML assets are gitignored and absent on a fresh clone; a downloaded model is *not* gitignored-managed, so a stray file could be committed | Low | `filesDir` is outside the repo by construction; verify `git status` before any commit |
| R10 | Two local stacks shipped simultaneously (Phase 3 not done) | Low | APK +21 MB of dead weights; schedule Phase 3 in the same roadmap entry |

---

## 8. Authorization required before any code is written

1. **Scope lock.** `docs/state.md` §Scope locks lists *"local OCR engine reinstatement"* as
   **REJECTED** and `docs/memory.md` records S4 as user-HALTED. This report is the evidence
   needed to lift it — specifically, PP-OCRv5 supplies the text **detector** that the 09-26
   verdict identified as the hard blocker. Implementation still needs an explicit go.
2. **Phase 0 is separable and cheap.** It needs no new dependency, no model, no engine, and it
   addresses the *reported* symptom (skipped words) rather than the architectural goal. It is the
   highest value-per-diff item in this document and can be authorized on its own.
3. **New outbound service.** The model download is a new external fetch; rules §8 requires
   PRD-level justification and a `docs/memory.md §Dependencies` entry.
4. **Scope of the hybrid.** Confirm whether `ADAPTIVE` is user-selectable or becomes the default
   once Phase 2 is verified. Default-flip is a product decision, not an engineering one.
