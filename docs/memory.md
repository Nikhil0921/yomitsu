# Yomitsu — Active Memory (Recent Sessions)

> COMPRESSED ACTIVE MEMORY. Contains recent sessions + durable decisions/gotchas.
> Do NOT read this during normal startup — use `state.md` + `rules.md` first.
> Full historical records in `history/session-logs.md`.

---

## Recent sessions (most recent first)

### 2026-09-27 — Adaptive Hybrid measured: 89.7% escalation, caused by one unvalidated constant

Final verification of the PP-OCRv5 feature on SM_M066B. No commit. **GATES: `spotlessCheck
testDebugUnitTest` = BUILD SUCCESSFUL 3m16s — 512 tests, 0 failures.** GLENS remains the code
default; nothing about `OcrPreferences` was changed.

**MEASURED (68 real pages, post-fix window only — the capture's first 16 MB is the retained ring
buffer from the pre-fix process, so metrics must be cut at the first post-fix `init ok`):**
pages 68 · **local accepted 7 (10.3%)** · **escalated 61 (89.7%)** · **local engine failures 0**.
`init ok … classes=438` appears 3x, once per process, so the sticky-failure fix also removed the
re-initialization storm. **1094 recognition calls, every one with height exactly 48** (27 distinct
widths) — the `OcrTensorSize` fix verified on hardware.

**LATENCY: detection n=68 p50 393 ms / p90 883 ms / p99 1481 ms; recognition n=1094 p50 61 ms /
p90 202 ms / mean 100 ms.** Isolated benchmark (2 runs): det p50 80–110 ms, p90 548–552 ms; rec p50
47–48 ms, p90 57–59 ms. **Detection misses 250 ms at p90 in every configuration; recognition never
does.**

**THE 89.7% IS ONE CONSTANT, NOT A BUG.** `meanCharConfidence < 0.80` tripped on **61 of 61**
escalated pages, median confidence **0.557**. chars/region < 1.5 tripped on 0 pages, nonLatin on 0,
garbage on 0, boxH on 5. The 0.80 floor came from the audit's *unmeasured* table; the `en`
recognizer does not reach it on real manga crops. **Do not lower the constant to make the metric
look good** — set it from a measured local-vs-GLENS text comparison, which does not exist yet.

**FINAL DECISION: GLENS stays default; PPOCR and ADAPTIVE stay opt-in.** Two independent measured
reasons, both pre-registered by the audit: escalation 89.7% vs R1's ~20% pessimization threshold, and
detection p50 393–788 ms vs §5 Phase 1's 400 ms kill-switch. The engine is correct and stays as the
only no-network OCR path; what is rejected is defaulting to it.

---

## Three durable rules from this feature (each cost a production incident)

**RULE 1 — A library that ships a startup `ContentProvider` can only be neutralized by manifest
removal, never by a runtime API call.** `ai.onnxruntime.TelemetryInitializer` is installed by
`ActivityThread.installContentProviders`, which runs **before `Application.onCreate`**, so
`OrtEnvironment.setTelemetry(false)` in an engine constructor cannot prevent it (and only runs at
all if a scan is ever attempted). It builds a device id from `android_id` + `Build.MANUFACTURER` +
`Build.MODEL`, registers a default network callback and a `battery_low` receiver, and hands the
native 1DS stack an HTTP client. **Fix: `tools:node="remove"` on the provider in
`app/src/main/AndroidManifest.xml`**, then verify the merged manifest *and* the shipped APK
(`aapt2 dump xmltree`) — the intermediates live under `…/processDebugManifest/<abi>/`, not directly
under `merged_manifests/debug/`. Keep the runtime toggle as a second layer, never as the fix. This is
the mirror image of why this app keeps LeakCanary's installers deliberately.

**RULE 2 — Never positionally destructure across a language or API boundary; use a named type.**
Twice in this feature a `Pair<Int, Int>` cost a production failure, because two functions returned
the *same* type with the *opposite* element order (`detectionInputSize` = width,height;
`recognitionInputSize` = height,width) and the caller destructured both the same way. The result was
that **every** recognition tensor was transposed — `index: 2 Got: 192 Expected: 48` — and it hid
perfectly because square crops are immune to transposition, so a test corpus of square images would
never have caught it. `OcrTensorSize(width, height)` makes the mistake inexpressible. The sibling
failure mode is a Kotlin `as?` against a **Java platform type**: it compiles, and is silently `null`
at runtime (`NodeInfo` → `ValueInfo` → `TensorInfo`). **Verify cross-language casts under plain
`javac`, which rejects what Kotlin accepts.**

**RULE 3 — A benchmark that skips is worse than no benchmark: it reads green.** The hardware
benchmark gated on `assumeTrue(pages.size >= 20)` against externally pushed fixtures; Android's PNG
decoder rejected the files, so the run reported `OK (2 tests)` in **0.104 s** having measured
nothing. **Always check elapsed time, print the numbers into the assertion message** (not only into
logcat), and make the harness self-sufficient — this one now draws its own pages with
`Canvas.drawText`, so it has no fixtures to manage and exercises real glyphs. Related: an on-device
`setsid logcat` capture can die while still appearing in `ps`; **compare the capture's last timestamp
against current device time before trusting a log you are reading**, and cut metrics at a known-good
boundary (here the first post-fix `init ok`) when the file also contains earlier ring-buffer history.


### 2026-09-27 — Hard numbers: PP-OCRv5 on SM_M066B, and the audit's kill-switch fires

Latency metrics pulled from the device log, the androidTest benchmark run on hardware, and a second
crash found on the way. No commit. **GATES: `spotlessCheck testDebugUnitTest` = BUILD SUCCESSFUL 3m —
512 tests, 0 failures. `:app:assembleDebug` 2m27s, `install -r` Success, models intact.**

**CRASH #2, exposed by the metric pull: the recognizer tensor was transposed on every call.**
`ORT_INVALID_ARGUMENT … index: 2 Got: 192 Expected: 48` (and 56/64/72/…/320) on 19 pages. Index 2
is the recognizer's **height**, which the model pins at 48; the values fed were the **widths**.
`recognitionInputSize` returned `Pair(height, width)` while `detectionInputSize` returned
`Pair(width, height)`, and the engine destructured both as `(width, height)`. **A `Pair` cannot
express which element is which, so the ambiguity itself was the defect** — both functions now return
a named `OcrTensorSize(width, height)`. This is the second time in this feature that a positional
assumption cost a production failure (the first was the `NodeInfo`→`TensorInfo` cast). **Prefer
named types over positional ones at every API boundary, especially across language boundaries.**
Detection-only square crops masked it: every rec sample was `in=48x48`, because a square crop has
width == height == 48 and is therefore immune to the transposition. **A bug that only shows up on
*wide* inputs will hide perfectly on your test data if your test data is square.**

**MEASURED (real manga pages, app session, 3-4 scans concurrent): detection p50 788 ms, p90 1926 ms,
p99 4009 ms; recognition p50 243 ms, p90 380 ms, p99 569 ms.** Detection cost tracks input area
(64x960 → 506 ms p50; 224x960 → 788 ms; 960x640 → 2302 ms) and the concurrent scans contend, so
real-world p50 is ~5x the isolated number.

**MEASURED (isolated, androidTest, one page at a time, two consecutive runs):**
`pages=20 boxes=47 det p50=110ms p90=548ms | rec n=47 p50=47ms p90=59ms` and
`det p50=80ms p90=552ms | rec n=47 p50=48ms p90=57ms`. Recognition is comfortably inside 250 ms;
**detection p90 is reproducibly ~2.2x over it** (`detection p90 552ms exceeds the 250ms budget`).
47 recognition calls on real glyphs also confirm the tensor fix on hardware.

**ESCALATION RATE 19/19 = 100%**, every one of them "local engine unavailable … index: 2 Got: …" —
i.e. all caused by the transposed tensor, none by the quality router (router accepts 0, quality
escalations 0). **The true rate is unknown until a re-test.**

**THE AUDIT'S OWN KILL-SWITCH FIRES.** `docs/audits/ppocrv5-hybrid-audit.md` §5 Phase 1 pre-registered:
*"if detection alone is > 400 ms/page, the local-first thesis dies and this report's §3 plan is
abandoned in favour of keeping GLENS plus Phase 0."* Detection p50 under real app conditions is
**788 ms, 2x the kill-switch**, and even isolated p90 is 548 ms. **Local-first does not hold at
250 ms/page on SM_M066B.** The code is now *correct*, so keeping it as an opt-in offline path is
defensible — but **ADAPTIVE must not become the default**, and any future work on this should be
NNAPI (audit R4), a smaller detector, or accepting a multi-second page budget, not more tuning of
what is already measured.

**Benchmark harness lesson:** it required 20+ fixture PNGs pushed to
`externalFilesDir/ocr_benchmark/`, and Android's PNG decoder rejected the hand-rolled files, so the
gate **silently skipped** via `assumeTrue` and reported "OK (2 tests)" in 0.1 s. It now draws its own
pages with `Canvas.drawText`, so there are no fixtures to manage, the pages contain real glyphs, and
recognition is exercised too. **A benchmark that skips is worse than no benchmark: it reads green.**
Always check the elapsed time, and print the numbers into the result rather than only into logcat.

**Log capture lesson:** the on-device `setsid logcat` capture died silently mid-session (file stopped
growing at 16:25 while the process still showed in `ps`). Always compare the file's last timestamp
against the current device time before trusting a log you are reading.


### 2026-09-27 — PP-OCRv5 crash: the same unchecked-cast mistake three times over

Device run of the 09-26 build failed **every** local page with `recognizer exposes 0 classes`.
**GATES: `spotlessCheck testDebugUnitTest :app:assembleDebug` = BUILD SUCCESSFUL 2m47s — 511 tests,
0 failures (was 499, +12).** Installed on SM_M066B.

**THE LESSON (this is the reusable part): a Kotlin `as?` against a Java library is a silent null.**
ORT 1.30.0 has `public class NodeInfo { ValueInfo getInfo(); }` and `class TensorInfo implements
ValueInfo`. The correct chain is `outputInfo[name]?.info as? TensorInfo`; the wrong one
(`as? TensorInfo` straight off the map value) **compiles** because Java types are platform types,
and is **always null at runtime**. That null fed my own class-count guard, which threw
`InitializationError` on all 23 scanned pages. Debugging trick worth repeating: a 40-line host-side
probe using the *same* ORT version and the *same* .onnx files settled it in one run — and plain
`javac` **refused to compile the bad cast** ("NodeInfo cannot be converted to TensorInfo"), which is
the proof the Kotlin version should never have been allowed to compile. **Before shipping Kotlin
against an untyped Java API, verify the casts under `javac`.**

**The same mistake appeared twice more, hidden behind the first:**
1. ORT returns the det output `[1,1,H,W]` as `float[][][][]` and the rec output `[1,40,438]` as
   `float[][][]`; the code assumed 2-D and 1-D. Both `as?` casts erase to `Object[]` and *succeed*
   on the wrong shape, so the detector would have returned an all-zero probability map — zero
   boxes, **no error at all**. Fixed with a pure `OcrTensorReader` (:domain) that walks both
   nestings positionally via `is Array<*>` and returns null on any mismatch. 9 tests, including
   ragged-row and wrong-rank cases.
2. **PaddleOCR's `CTCLabelDecode` builds `['blank'] + dict + [' ']`**, so a 436-character dictionary
   means **438** output classes, not 437 — class 437 is a literal space. Verified from the model's own
   output shape `[-1,-1,438]`. Unhandled, spaces were dropped and words ran together
   ("Helloworld") even after a successful init. `CLASS_COUNT` is now 438 with `SPACE_CLASS = 437`.

**Self-inflicted stall found in the same log: initialization failure was not sticky.** 23 failed
pages produced 649 `CleanUnusedInitializersAndNodeArgs` lines — i.e. ORT re-ran full graph
optimization, and re-created both sessions, on every single page, each time under the shared
`fastMutex` while the caller waited. A broken engine must remember it is broken. The failure is now
cached in `initializationFailure` and rethrown immediately; `close()` clears it.

**Robustness changes (both requested, both matching existing precedent):** a local failure is now an
*escalation trigger* rather than a page failure — `HybridOcrEngine` catches any non-cancellation
throwable from the local pass and hands the page to the cloud, and `scanLocalOrFallback` now catches
any non-cancellation throwable to fall back to GLENS, exactly like the existing
`scanOwOcrOrFallback`. A raw `ClassCastException` from a bad tensor shape can no longer reach
speech acquisition.

**The GLENS stall is neither a lock leak nor mine.** `PrioritizedTaskQueue` decrements its slot in a
`finally` and both the engine mutex and `OcrEngineLocks` are exception-safe, so nothing leaks.
Measured on the day: 8.8–10.1 s per tile upload and **57–68 s per page** against
`OCR_ACQUIRE_TIMEOUT_MS = 30_000`, so timeouts are *expected*; the 09-26 pass had a 2.67 s median
upload, so the network is roughly 4x slower now. Nothing about network behaviour was changed. The 9
chapters sitting in `ocr_preprocess_queue` with `state=ERROR` are those failed runs.

**Verified on device without any UI:** both models present under
`files/app_ocr_models/pp_ocr_v5/v1/`, and their on-device SHA-256 matches the pinned manifest
exactly (`a4319856…6e61d`, `b5f833df…0557f`) — the on-demand download plus its integrity check are
proven. The installed package contains 0 occurrences of the ORT telemetry authority.
**Still pending: the <=250 ms number and the three-mode end-to-end run.** Both need a chapter read,
which is the user's manual test — and the container's `adb` cannot pair with the wireless device
(`unauthorized`), so `connectedDebugAndroidTest` is not available as a substitute.


### 2026-09-26 — PHASES 1-3: PP-OCRv5 local engine, adaptive hybrid, model downloader, settings

Implements Phases 1-3 of the PP-OCRv5 master task (audit `docs/audits/ppocrv5-hybrid-audit.md`
§5, authorization = this task). No commit. **GATES (docker `vsc-yomihon-e24e3bd7`, `-Xmx4g`, both
volumes): `spotlessCheck testDebugUnitTest :app:assembleDebug verifySqlDelightMigration` = BUILD
SUCCESSFUL 4m18s — 499 tests, 0 failures (was 417, +82).** No DB change.

**THE LOAD-BEARING FACT: LiteRT cannot load PP-OCRv5, and no TFLite PP-OCRv5 exists.** Verified by
`strings` on the shipped `libLiteRt.so` (litert 2.1.6): only `TfLite*` symbols and
`ml_drift/tflite/object_reader`, i.e. a TensorFlow Lite build with no ONNX parser.
`CompiledModel.create(path)` therefore accepts `.tflite` only, and PaddlePaddle publishes only
`inference.json`+`inference.pdiparams` and ONNX. The audit's "zero new dependencies" conclusion was
wrong for exactly this reason, so `com.microsoft.onnxruntime:onnxruntime-android:1.30.0` was added
(Apache-2.0). **APK cost: `libonnxruntime.so` is 32 332 128 B on arm64-v8a** (23.4 MB armeabi-v7a,
39.5 MB x86/x86_64) — +32MB per arm64 APK, models still 0 bytes via on-demand download. ORT
telemetry is explicitly disabled; the app sends nothing about OCR usage.

**The models are real, official and pinned** (independently confirmed against the HF LFS oids and
against a local download, so the manifest cannot drift silently):
`PaddlePaddle/PP-OCRv5_mobile_det_onnx` 4 826 518 B / sha256 `a4319856…6e61d`, and
`PaddlePaddle/en_PP-OCRv5_mobile_rec_onnx` 7 848 423 B / sha256 `b5f833df…557f` = 12 674 941 B
(12.1 MiB). Graph I/O read straight out of the ONNX: input `x`, output `fetch_name_0`; the det tail
is `ConvTranspose.3` and the rec tail is `Softmax.2` — **the recognizer already emits
probabilities**, which is why no softmax is applied on our side and why the mean peak probability
is a usable confidence.

**Two durable gotchas from this build:**
1. **The en dict has two astral-plane entries** (`𝑢` U+1D462, `𝜓` U+1D4D3). A Kotlin `String`
   indexes UTF-16 code units, so `CHARSET[i - 1]` would hand the CTC decoder half a surrogate and
   `CHARSET.length` reads 438, not 436. The vocabulary is therefore a code-point `List<String>`
   (`PpOcrCharset.CHARACTERS`, 436 entries, `CLASS_COUNT = 437`) and every count is asserted in
   code. `CLASS_COUNT` is also asserted against the model's real output width at engine init, so a
   model/dict mismatch throws `InitializationError` instead of decoding to silent garbage.
2. **A wall-clock perf guard is flaky under full-suite load.**
   `OcrExclusionMatcherTest > word matching stays linear` (09-26 batch) asserts < 5ms; it printed
   7.12ms in one full-suite run and **2.45ms in isolation**, passing. Same class of transient as
   the 09-25/09-26 container SDK churn. Re-run in isolation before believing it; do NOT loosen the
   bound without evidence.

**Everything testable is pure in `:domain`** (+82 tests, all deterministic, no device, no models):
`PpOcrAssets` manifest, `PpOcrCharset`, `OcrModelDownloader` (whole-file SHA-256, `.part` staging,
atomic `MANIFEST.json` rename, four states, `errorRetryable` to drive retry-vs-fail),
`PpOcrDbPostprocess` (8-connected flood fill + PaddleOCR isotropic unclip, **no OpenCV**),
`PpOcrCtcDecode`, `PpOcrPreprocess`, `OcrQuality`/`OcrQualityRouter`. `SpeechCleaner.isOcrGarbage`
went from `private` to `internal` so the OCR router and the speech layer share one garbage rule
instead of keeping two copies (audit §3.3 asked for exactly this).

**`UnavailableDetOcrEngine` is NOT an obsolete stub — it is load-bearing.** Its
`OcrException.DetectionUnavailable` throw is the mechanism that redirects every local scan to
GLENS. `FastOcrEngine` (507 lines) is a working LiteRT implementation that is merely unreachable.
Phase 3's "remove obsolete FastOcrEngine panel detector stubs" would have broken routing; the real
dead weight is `FastOcrEngine` + `FastVocab*` + 21MB of `ocr_fast` assets, which audit §5 Phase 3
gates on device verification of PP-OCRv5 first.

**Two deliberate deviations, both user-approved:** (a) whole-page escalation, **no local+cloud
box merge** — `OcrRegion.order` is reading-order truth for `SentenceSegmenter`, `SpeechPipeline`
and tap-highlight, and two detectors cannot produce one coherent order (audit §3.7, R1);
(b) the **≤250ms gate is a device harness, not a unit assertion** —
`data/src/androidTest/.../PpOcrV5EngineBenchmarkTest` (downloads, warm-up, p90 over ≥20 pages,
asserts 250ms, `assumeTrue`-skips instead of lying) plus the in-engine
`OCR(ppocr) Runtime: det=…ms rec=…ms` log. It cannot be a `testDebugUnitTest` assertion:
onnxruntime-android has no JVM artifact, the weights are an on-demand download absent in CI, and
container-x86 is not phone-ARM. CI never runs `androidTest`.

**THE NUMBER DOES NOT EXIST YET — device pass on SM_M066B is PENDING.** Until it does, the
local-first thesis is unproven and the det/rec normalization constants (upstream ImageNet
mean/std for det, 0.5/0.5 for rec) plus the 960px long-side cap on tall webtoon strips are all
unverified on hardware.


### 2026-09-26 — PHASE 0: skipped words fixed (speech classification + script flipping)

Implements Phase 0 of `docs/audits/ppocrv5-hybrid-audit.md` §5. No commit. 2 source files,
3 test files (2 modified, 1 new), no DB change.
**Gates (docker `vsc-yomihon-e24e3bd7`, `-Xmx4g`, BOTH volumes): `spotlessCheck
testDebugUnitTest :app:assembleDebug` = BUILD SUCCESSFUL 3m23s — 417 tests, 0 failures
(was 405).** spotlessCheck green with no `spotlessApply`. One intermediate combined run failed
with no captured cause (only the tail was kept); the identical command re-ran green and each
task passes individually.

**The durable lesson: shape cannot separate sound from speech, so the boundary is lexical.**
`"BOOM!!"` and `"STOP!!"` are both short, uppercase, emphatic — every shape heuristic that
misfiles one misfiles the other, and the project already had a green test pinning `BOOM!!` as
SOUND_EFFECT. So `SpeechRegionClassifier` now holds two explicit token sets
(`SOUND_EFFECT_TOKENS` 24 onomatopoeia, `EXPRESSION_TOKENS` 13 vocalizations) consulted only
for upper-case text, and **everything not listed is DIALOGUE, i.e. spoken**. The bias is
deliberate and one-directional: a missed sound effect gets read aloud, a misfiled word is
silently skipped. Do not "optimize" this back into shape rules. Both `speakSoundEffects` /
`speakExpressions` prefs stay `false`, so no new noise — only the wrongly-muted words return.
Also deleted a dead branch (`if (INTERJECTION.matches(text))` under an upper-only regex) that
looked like it handled lower-case interjections; it never did.

**`TextPostprocessor`: "contains any Japanese glyph" was the bug, "Japanese outnumbers the other
letters" is the fix.** `hasJapaneseText = text.any { isJapaneseScript() }` full-width-converted
an entire English line because of ONE stray kana; the full-width Latin then read as
`dominantScript == OTHER` and `skipForeignScript` deleted the whole bubble. Now
`isJapaneseDominant(text)` compares JP letters against other *letters*, and the same flag drives
the space rule so a Latin-dominant line keeps spaces next to a stray kana
(`"I'm ready こんにちは"` survives instead of degrading to `"I'mready こ"`).

**`dominantScript` counts full-width `Ａ-Ｚ`/`ａ-ｚ` as LATIN**, by codepoint range
(0xFF21-0xFF3A / 0xFF41-0xFF5A) and **not** by UnicodeBlock — the block test would also capture
full-width katakana and re-break the CJK decorative path. This is the only part of Phase 0 that
heals **already-cached** pages, because the postprocessor fix only affects newly scanned text.

**Still open from the audit (deliberately out of Phase 0 scope):** D3 — GLENS hard-codes
`DEFAULT_CLIENT_LANGUAGE = "ja"` / `"Asia/Tokyo"` (`GlensOcrEngine.kt:1044-1045`) and
`parseResponsePage:467-484` rewrites region order + can delete a line via `filterRuby`. Needs a
device capture before touching. D4 (IoU dedupe has no text comparison) untouched.
**Device verification of the words fix: PENDING.**

### 2026-09-26 — READ-ONLY audit: PP-OCRv5 + Adaptive Hybrid + skipped-words (no source changes)

Report: `docs/audits/ppocrv5-hybrid-audit.md`. No gates run (docs-only).

**Durable corrections that change how earlier notes should be read:**
- The 09-26 "S4 NOT VIABLE" verdict named TWO blockers (no text-detection model; JP-vocab
  recognizer garbles English). PP-OCRv5 Mobile resolves both in principle (DB detector + `en`
  rec model). The scope lock (`state.md` REJECTED: "local OCR engine reinstatement") is still in
  force — do not treat this audit as authorization.
- **`OcrPostProcessor.kt` does not exist**; the real class is `TextPostprocessor`
  (`data/src/main/java/mihon/data/ocr/TextPostprocessor.kt`). Documents/grep references to
  "OcrPostProcessor" are wrong.
- **There is no confidence anywhere in the OCR stack**: not in `OcrBoundingBox`/`OcrRegion`
  (`OcrModels.kt:16-50`), not parsed from the GLENS proto (`GlensOcrEngine.kt:854+`,
  `ParsedWord` `:954`), not persisted (`OcrCacheStore.upsert:31-59`). Any "escalate when the
  cloud is unsure" design is unimplementable without new plumbing; only local→cloud escalation is.
- LiteRT 2.1.6 (verified by `javap` on the shipped `litert-api-2.1.6` jar, not by docs) exposes
  `CompiledModel.create(String modelPath, Options, Environment)` — **absolute filesystem path** —
  plus `Accelerator.{CPU,GPU,NPU}`. So an on-demand-downloaded model in `filesDir` needs **zero
  new dependencies**; `FastOcrEngine.kt:156` uses the `AssetManager` overload instead.

**Skipped-words root cause = the SPEECH layer, not OCR (biggest durable takeaway).**
`SpeechRegionClassifier.kt:54-56` labels upper-case ≤8 letters with `!?` as SOUND_EFFECT and
`^[A-Z' ]{2,6}$` as EXPRESSION; `TtsPreferences.kt:35,37` default BOTH to `false`, and
`SpeechRegionFilter.kt:36-44` drops them. So `OK`, `NO`, `YES`, `HI`, `MEH`, `RUN`, and
`WHAT??` / `STOP!!` / `OKAY!` are never spoken, while `Okay, I'm ready` is — exactly the
"short tokens skipped, longer phrases fine" symptom. (`SpeechRegionClassifier.kt:56` is dead
code: the regex is upper-only, so the "lower-case interjection" case it appears to cover is not
covered.) Two amplifiers: `TextPostprocessor.kt:205` full-width-converts an entire line when it
contains ONE Japanese codepoint, after which `dominantScript` returns `OTHER` and
`skipForeignScript` deletes the whole bubble; and GLENS hard-codes
`DEFAULT_CLIENT_LANGUAGE = "ja"` / `"Asia/Tokyo"` (`:1044-1045`), so a single stray JP line flips
an English page into the JP branch (`:467-484`) which REORDERS regions (vertical-JP first) and can
delete a line through `filterRuby` (`:668-715`, needs a device capture to confirm — HYPOTHESIS).
`GlensOcrEngine.dedupeOverlapping` (`:305-316`, IoU ≥ 0.45, position-only, no text comparison)
can also delete a small bubble nested in a larger box. **Proof that OCR is not length-filtering
anything: `scanLocally` (`OcrRepositoryImpl.kt:546-591`) has no minimum length/confidence and
`OcrBoundingBox.isValid()` is geometry-only.**

**Ruled out for skipped words:** exclusion zones (user-authored only, no seeds), `SentenceSegmenter`
(splits, never drops), queue starvation (fixed 09-26, p90 wait 1 ms, 0 duplicates).

**ON-DEVICE VERIFIED (SM_M066B, `.device-pass/logcat-20260926-192424.log`, cold uncached
ch8807 with OcrScanJob scanning ch8806 throughout — genuine contention):** HIGH queue wait
**p90 = 1 ms** (baseline 9233 ms), HIGH worst **1 ms** (baseline max 17169 ms); duplicate
enqueues **0/17 = 0%** (baseline 56/193 = 29%); cold prefetch lookahead **1 page** (baseline 6)
— `pages=1..1` then `pages=2..2`; prefetch starts/skip **2/1** (baseline 51/1 — the dedup guard
now actually fires); cold start→first speech **1.64s**; `OcrError` 0, `postUploadWaitMs=0`,
0 crashes. Speech smooth after p0 (median inter-dispatch gap 2.48s, 30 sentences on p1).
The `maxConcurrentTasks == 1` no-reservation path and the 29%→0% duplicate collapse together
confirm the F1/F3/F4 changes landed as intended.

### 2026-09-26 — Single-letter OCR sanitizer + OCR model menu cleanup

**Sanitizer:** `mergeSpacedSingleLetters(text)` in `domain/…/mihon/domain/ocr/model/OcrTextSanitizer.kt`
(new pure util). Regex constant `SPACED_LETTER_RUN` = `(?<![A-Za-z])[A-Za-z](?:[ ]+[A-Za-z])+(?![A-Za-z])`.

**The trailing lookahead `(?![A-Za-z])` is load-bearing — do not drop it.** It is what keeps
prose intact. In `"This is a test"` the engine *can* match `a t`, but the following `e` fails
the lookahead so `test` is never truncated; `I am here` is protected the same way. Lookbehind
stops a match *starting* mid-word; the lookahead stops it *ending* on the first letter of a
longer word. A single letter never matches (`+` needs a separator), so `A`/`I` are safe.

**Deliberate limits (documented in KDoc):** ASCII letters only → `1 2 3`, `5 x 3`, `U.S.A`
untouched; spaces are the only separator (newlines/tabs NOT merged, a line break may separate
distinct text); `length < 3` short-circuits since the shortest mergeable input is `"A B"`.
**Known limitation:** shape-only, no semantics, so letter-spaced English (`I a m`) also
collapses to `Iam` — indistinguishable from tracked type without NLP, and a predictable shape
rule beats a guess.

**Wiring (one line):** `SpeechPipeline.toSpeakableSentences`, applied AFTER
`SpeechCleaner.cleanRegionForSpeech` so the cleaner's punctuation-only / OCR-garbage /
whitespace heuristics still see the raw text they were tuned for. Deliberately NOT at scan
time — that would mutate cached OCR text, need cache invalidation, and miss already-cached
pages. Speech-only placement applies on every playback.

**Menu cleanup:** `OcrModel.LEGACY` removed from the `entries` map in `OcrQueueScreen` (hidden).
`ListPreferenceWidget` has no per-entry enable support and adding one would touch a shared
widget used by many screens, so hiding at the call site is the isolated fix — with a comment so
it is not "fixed" back. `OcrPreferences.ocrModel()` default `LEGACY` → **`GLENS`** so fresh
installs don't land on a value absent from the list; safe (no test depends on the default) and
behaviour-neutral (`engineFor(LEGACY)` already returns the GLENS engine). Strings (base only):
`ocr_model_fast` → "Fast (local model, in development)", `ocr_model_legacy` → "Legacy
(deprecated)". GLENS + OWOCR untouched. Per the 09-26 audit FAST cannot run standalone (no
text-detection model), so labelling is the honest minimum the task allowed.

**Gates (docker, `-Xmx4g`, both volumes):** `spotlessCheck testDebugUnitTest :app:assembleDebug`
= **BUILD SUCCESSFUL in 4m 5s**; **405 tests, 0 failures** (was 394, +11). `spotlessCheck` green
with no `spotlessApply` needed. New: `OcrTextSanitizerTest` (9) + `SpeechPipelineLetterMergeTest`
(2, pins the utility is actually wired in, not merely defined).

### 2026-09-26 — OcrExclusionMatcher O(n³) → linear (12.3s stall eliminated)

**Path note:** the matcher lives at `domain/src/main/java/mihon/domain/ocr/model/OcrExclusionMatcher.kt`
(NOT `.../ui/reader/ocr/...`).

**Measured (TDD, test first):** 16 WORD zones × 16 regions × 60 tokens = **427 ms** on JVM,
**12 258 ms** on the device inside `acquireSentences` (JVM ~29x faster than the phone). Now
**2.04 ms** — 209x. `phraseMatches` fast path untouched.

**REJECTED the "obvious" single-token fast path — it silently loses matches.** The suggestion
`regionTokens.any { it == needleConcat }` for 1-token rules is NOT equivalent: a 1-token rule
can still be satisfied by a **multi-token region run**. Existing green tests depend on it —
region `"Key Manga"` → `[key, manga]` vs rule `"KeyManga"`, and region `"Dis\ncord"` →
`[dis, cord]` vs rule `"discord"` (that test says "excluded by design"). Behavior preservation
wins; pinned by the new test `single-token rule matches a multi-token region run` so the
shortcut can never come back.

**Durable insight (the actual fix):** a candidate run's character length is pinned by the
needle, so every start offset has exactly ONE possible end offset, and a run is valid only when
that offset lands on a token boundary. Build the concat once + a `BooleanArray` of token-end
offsets, then test each start in O(1) + one `regionMatches` → **O(L + n·m)**, exactly
equivalent to the old windowed search. Never window every length and re-join.

**Second, separate win:** `applyExclusions` normalized each region's text once **per zone**
(16 zones = 16× redundant NFKC on the same bubble). Hoisted to once per region; `matchesRegion`
/`wordMatches`/`phraseMatches` now take the precomputed forms. Needed to hit <5 ms — the
`containsTokenRun` fix alone left 6.64 ms.

**Gates (docker, `-Xmx4g`, both volumes):** `spotlessCheck testDebugUnitTest :app:assembleDebug`
= **BUILD SUCCESSFUL in 3m 30s**; **394 tests, 0 failures**; matcher suite 36/36. First attempt
failed transiently because the container installed Android SDK Build-Tools 36 mid-run.

**ON-DEVICE VERIFIED (SM_M066B, `.device-pass/logcat-20260926-202430.log`, ch8805, 43 pages,
16 exclusion zones/page, 173 dispatches):** exclusion match **median 7.6 ms, p90 17.8 ms,
max 74.0 ms, min 0.5 ms** (n=30). The same heavy page that cost **12 258 ms** on the previous
build now costs **5.6 ms**; share of `acquireSentences` **76.9% → 0.1%**. Unit benchmark
427 ms → 2.04 ms (209x). Cold start→first speech **2.92s**; OcrError/acquisition-null **0**;
0 crashes. Install note: `:domain` classes land in `classes10.dex`, NOT `classes.dex` — check
the right dex (`unzip -p APK classes10.dex | strings | grep containsTokenRun`) before trusting
a device run, or you can silently re-test the old code.
**Remaining bottleneck is network, not code:** median acquire is 91.2% `scanPageOcr await`
(7229.6 ms) + 6.0% image download (476 ms). GLENS healthy: 510 uploads, median 2670 ms, max
6619 ms, postUploadWaitMs=0. Uncached-page `acquireMs` stays 5-12s — that is real remote
inference, not a code defect. Open follow-ups (NOT implemented, user not yet scoped): letting
the cold throttle widen 1→2 pages after first speech, and a "Scanning page N…" progress line so
a 7s wait does not read as a hang (30s guard survives 12.2s but the margin is thinner than it
looks at high speech rates).

### 2026-09-26 — Read-Aloud architecture recovery (queue saturation + prefetch throttle)

Implements audit F1/F3/F4 from `docs/audits/full-ocr-system-resiliency-audit.md`. **No commit.**

**Fix 1 — `schedulePrefetch` throttles cold lookahead** (TtsPlaybackController.kt):
- `if (cached == null && !speaking) break` — on an UNCACHED page, while the active page has
  not started speaking, only N+1 is queued. `speaking = phase == TtsPhase.Playing`. Fixes F4:
  the uncached budget could never bind (6 pages × MIN_SENTENCES_PER_PAGE 5 = 30 < 40), so every
  page turn queued all 6 pages in parallel → 2-3x queue oversubscription.
- Dedup is now **coverage-based** (`targetPages.filter { it !in covered }`) instead of the
  exact-range equality that never matched because the window slides 1/page. Fixes F1
  (measured 51 prefetch starts vs 1 skip; 29% of queue enqueues were duplicate pages).
- **Coverage deliberately outlives `prefetchJob`** (no `isActive` condition) — with it, a
  finished-but-uncached page got re-queued on the next re-arm (test recorded `[1,1]`).
- `prefetchPages = null` added on `NextChapter`; without it stale coverage from the old chapter
  suppresses the new chapter's lookahead (page indices restart at 0). `resetSession` already
  covered start/navigation.

**Fix 2 — HIGH slot reservation** (PrioritizedTaskQueue.kt): `backgroundSlotCeiling =
maxConcurrentTasks - 1` (floor: no reservation when maxConcurrentTasks == 1). `processQueue`
admits HIGH *before* applying the ceiling, so the active page always finds the reserved slot
free; the drain loop parks when only background work is left. Hard cap unchanged — still
`maxConcurrentTasks` total, no oversubscription. **This changes the documented RC-3
non-preemption semantics** (audit F3 flagged it as needing approval; user authorized).
Background concurrency 3→2, which the 29%-duplicate measurement says costs ~0 real throughput.

**Fix 3 (LeakCanary/memory) — NO CODE CHANGE, and that is the correct outcome.** The retention
was already fixed at `ReaderViewModel.onCleared()` (:367-378): `controller.stop()` (→
`resetSession` cancels+nulls both jobs) + `ttsEngine.shutdown()` + `ttsEngine.onFocusEvent = null`,
with a comment naming the exact chain (engine → dead controller → ViewModel → destroyed
ReaderActivity, "LeakCanary 2026-08-28"). The audit proved the verdict was CLEAN with no heap
dump ever written. There is no `TtsPlaybackController.onDestroy()` to fix. Do not "fix" this again.

**Fix 4 (test dispatchers) — NO CODE CHANGE.** `TtsPlaybackControllerTest.kt` does not exist.
**Durable gotcha learned the hard way: never wrap a real-`Dispatchers.IO` join in `withTimeout`
inside `runTest`** — the timeout is measured on the virtual clock, which fast-forwards while
the real IO work is in flight, so healthy tests abort with `TimeoutCancellationException`
(3 tests failed this way). The real bound is `runTest`'s own dispatch timeout (fails, never
hangs). A real-time bound requires a real scope via `runBlocking` (TtsOcrTimeoutGuardTest
pattern). Documented in the TtsReaderOpenPrefetchTest KDoc.

**Tests:** replaced `tasksRunConcurrentlyUpToCapacity` → `backgroundTasksLeaveOneSlotReservedForHigh`
and updated `queuedTaskStartsWhenCapacityFrees` to the cap-2 ceiling of 1 (both encoded the
old "background fills every slot" contract that Fix 2 deliberately changes). New
`highPriorityTaskBypassesSaturatedBackgroundQueue` and
`uncachedChapterQueuesOnlyOneLookaheadPageBeforeSpeech`.

**Gates (docker `vsc-yomihon-e24e3bd7...`, `-Xmx4g`, BOTH volumes):**
`spotlessCheck testDebugUnitTest :app:assembleDebug` = **BUILD SUCCESSFUL in 3m 40s**;
**391 tests, 0 failures**; queue 7/7, TTS 5/5. One ktlint fix via `spotlessApply` and one
compile fix (`covered?.last` does not resolve on `ClosedRange<Int>?` → use `endInclusive`).

### 2026-09-26 — READ-ONLY OCR/TTS resiliency audit (no source changes)

Report: `docs/audits/full-ocr-system-resiliency-audit.md`. Evidence: `.device-pass/`
captures (on-device-capture.log 78.5MB, logcat-20260925-200614.log 44.6MB,
logcat-20260926-133534.log 4.5MB, logcat-leakcanary.log 37.1MB, leak-full.log 33.6MB).

**Durable findings (correct two standing misconceptions):**
- **LeakCanary found NO leak and wrote NO heap dump.** It is not a project dependency; it was
  side-loaded 08-28 as `com.squareup.leakcanary.app.yomihon.dev`. Full output is 4 lines:
  watched ReaderActivity + ReportFragment on destroy → "All retained objects have been
  garbage collected" (clean) → "Found 2 objects retained, not dumping heap yet (app is
  visible & < 5 threshold)". Never hit the 5-object dump threshold. No `*.hprof` in repo.
  Any future "LeakCanary heap dump" claim must be sourced elsewhere.
- **Memory is fine.** ART telemetry only: 65 post-GC samples, live set sawtooth 11→55MB
  returning to a FLAT ~36-38MB baseline, **0** `Clamp target`/`Grow heap` growth-limit events,
  no upward trend, no cost attributable to Fix 3 lookahead. Evidence gap: no
  `dumpsys meminfo` for the app in any capture → native/graphics (bitmap) heap UNMEASURED.
- **10-30s stalls = duplicate-scan queue starvation, NOT memory and NOT the 30s guard**
  (OcrError=0, `OCR acquisition null`=0, advance-timeout=0 in all 3 OCR/TTS captures).
  Measured from the queue's own `activeSlots=`/`waitMs=` instrumentation (193 tasks):
  56/193 (29%) enqueues were DUPLICATE scans of an already-enqueued (chapter,page);
  45 pages enqueued >1× (worst ch8725 p2 = 4×); 70/193 enqueued while cap-3 full;
  task-start wait median 1ms but p90 9233ms / p99 15806ms / max 17169ms; 25 tasks >8s.
  HIGH waits observed 0/0/6661/818ms (6661ms = the cold-start diagnostic's figure).

**Three compounding defects (mechanism, all code-verified):**
1. `schedulePrefetch` dedup guard `prefetchPages == range` (TtsPlaybackController.kt:836-844)
   compares EXACT ranges but the window slides 1/page (26..31→27..32), so it never fires —
   measured 51 `TTS prefetch start` vs 1 `TTS prefetch skip`.
2. `prefetchJob?.cancel()` does NOT cancel already-submitted queue tasks; `PrioritizedTaskQueue`
   detaches each task into `scope.launch` and runs it to completion BY DESIGN (:141-147).
   Orphaned scans keep holding slots while the new window re-enqueues overlapping pages.
   In-flight dedup (OcrRepositoryImpl:234-245) only collapses *running* scans, never
   *queued* ones (32 joins observed).
3. `processQueue` (:127-139) is non-preemptive by design (RC-3) → HIGH waits for the slowest
   occupant, which per (1)+(2) is often a redundant scan.
   Worked 24.3s example: ch8725 p22 scanned 3× (queue wait on the 3rd = 17169ms).
4. Bonus: on UNCACHED manga the sentence budget can never bind (6 pages × MIN 5 = 30 < 40),
   so every page turn always enqueues a full 6-page parallel NORMAL batch.

**S4 (local Fast OCR) feasibility = NOT VIABLE as offline GLENS fallback.** `FastOcrEngine`
(507 lines) is fully implemented (LiteRT CPU 224×224, KV-cache decoder, vocab) and litert
2.1.6 + the models are ALREADY in the APK → 0-byte APK cost, ~4.5MB persistent buffers
(~25-40MB RSS). Blocker: `DetOcrEngine` has ONLY `UnavailableDetOcrEngine` (throws), and the
CI-restored `panel_detector/model.tflite` is a YOLO comic-PANEL detector
(`PanelDetectionRepositoryImpl.detectPanels`, used by PagerPageHolder) — NOT a text-region
detector. No text-detection model exists in the repo or the CI manifest. Also JP-vocab
garbles English (see the existing `ponytail:` redirect at OcrRepositoryImpl:203-211), so it
would be worse than GLENS on the user's actual content. S4 stays user-HALTED / REJECTED.

**Other code notes:** `awaitAdvanceConfirmation` bounded at 10s → Paused (0 occurrences);
a run of ZERO-sentence pages loops acquire with no speech/error and no wall-clock bound
(the only true "indefinite LoadingPage" shape); Fix A turned an 8s error into a 30s silent
spinner and Fix C's "Scanning page N…" text was NEVER implemented (bare CircularProgressIndicator).

**Docker/test corrections:** two volumes mandatory — host `~/.gradle`→`/home/vscode/.gradle`
(GRADLE_USER_HOME, real dep cache) AND `yomihon-android-home`→`/home/vscode/.android`
(debug keystore; omitting it signs stale `1a6fbe75...` vs good `e486ea51...8968` →
INSTALL_FAILED_UPDATE_INCOMPATIBLE; reconciles old issue #7). Always `-Xmx4g`. Test-hang:
`acquireSentences` hops to real Dispatchers.IO so runTest virtual time can't advance —
drive a real SupervisorJob scope + runBlocking + bounded withTimeout (see A–D entry below).

### 2026-09-26 — Uncached cold-start OCR false-negative fixes (A–D)

Diagnosed in `docs/audits/uncached-cold-start-diagnostic.md`: the `tts_error_ocr`
toast on a freshly opened (uncached) chapter while the background `OcrScanJob`
scan is running. Root cause = **`PrioritizedTaskQueue` non-preemption** (cap 3):
Fix-2's `withTimeoutOrNull(8000)` in `acquireSentences` cancelled a *succeeding*
HIGH scan merely queued behind 3 in-flight NORMAL scans, then permanently failed
the session (`fail(TtsError.OcrError)`) → toast → error phase. Cold worst case
observed 6.7s queue wait + 5.8s scan = 17.6s.

**Fixes (user-authorized, all executed):**
- **A** `TtsPlaybackController.OCR_ACQUIRE_TIMEOUT_MS` 8000 → **30000** (fires only
  on genuine hang now).
- **B** `prefetchReaderOpenPage` → `scanOnDemand(ctx, pageIndex, priority = HIGH,
  reportFailure = false)`; reader-open p0 grabs a slot ahead of background NORMAL
  scans. `scanOnDemand` signature: `priority: OcrScanPriority = HIGH` and
  `reportFailure: Boolean = true` now independent (previously priority derived
  from reportFailure). `ReaderViewModel.maybePrefetchReaderOpenOcr` KDoc updated.
- **C** LoadingPage UX already rendered (`tts_preparing`/`tts_loading_page` in
  `TtsPlaybackBar`); 30s guard keeps it visible instead of erroring. No code change.
- **D** new `TtsOcrTimeoutGuardTest` (`successfulOcrScanDoesNotFailWithOcrError`) —
  a successful scan must reach a spoken terminal phase (Paused at page end,
  autoTurn off), never `Failed`. `TtsReaderOpenPrefetchTest.prefetchScansWithHighPriority`
  now expects HIGH (3/3 pass).

**Gates (docker `vsc-yomihon-e24e3bd7...`, `-Xmx4g`, host `~/.gradle` → `/home/vscode/.gradle`):**
spotlessApply + spotlessCheck + `testDebugUnitTest` + `:app:assembleDebug` all
BUILD SUCCESSFUL. No DB change. No commit.

**Build-env correction (durable):** container `GRADLE_USER_HOME=/home/vscode/.gradle`;
mount host `~/.gradle` there. Mounting `~/.gradle/caches` to `/root/.gradle` does NOT
resolve deps (JitPack `flexible-adapter:c8013533` missing → build fails).
**AND** mount `yomihon-android-home:/home/vscode/.android` — debug signing reads
`/home/vscode/.android/debug.keystore`; omitting it signs with a stale key
(`1a6fbe75...`) instead of the known-good `e486ea51...8968`, breaking `install -r`.

**DEVICE VERIFICATION 2026-09-26 (SM_M066B, `.device-pass/logcat-20260926-133534.log`,
cold uncached ch3285 p0, ~26s capture):** installed `install -r` Success (data
preserved, cert verified). **Fix B PASS**: reader-open p0 `scanPageOcr await priority=HIGH`
at 19:06:14.024 → `OCR queue enqueue priority=HIGH activeSlots=none` → `OCR queue start
waitMs=1` (got slot ahead of background NORMAL flood). **Join-in-flight PASS**: TTS start
21.102 cache miss → `OCR scan joining in-flight scan chapter=3285 page=0` 23.075 (no
duplicate GLENS). **Fix A PASS**: `acquireMs=3142`, GLENS HTTP200 `uploadMs=4875
postUploadWaitMs=0`, `startup open->first page ready in 3169ms`; **OcrError=0, toast=0**;
p0=0 regions (cover) → graceful advance to p1. **Cold start→first speech ≈5.97s**
(start 21.072 → p1 dispatch 27.040). 12/12 GLENS HTTP200; 0 Cancellation/IOException
app-wide; 0 crashes; no buffering loop. Queue waits on NORMAL N+1..N+6 only
(1/2/1190/1939/3873/3915/3972/5540ms) — none blocked the active page.
**CAVEAT**: original contended shape (HIGH *behind* 3 NORMAL, 17.6s) NOT reproduced —
Fix B prevents that ordering (p0 enqueued before NORMAL flood); 30s-guard boundary
unexercised; capture died ~26s in. Stronger pass = longer capture, background OcrScanJob
running before reader-open to force contention.

**Test gotchas (durable):**
- `acquireSentences` runs inside `withIOContext` → real `Dispatchers.IO`;
  `runTest`/`advanceUntilIdle` virtual time CANNOT advance across it (test hangs in
  Preparing/LoadingPage). Drive the controller from a **real**
  `CoroutineScope(SupervisorJob())` + `runBlocking` + bounded `withTimeout`, polling
  `state.value.phase`.
- Required stubs: `mockkStatic` `Log` AND `SystemClock.elapsedRealtime()=0L`;
  `Manga.id`/`Manga.source` (acquireSentences reads both); `Bitmap.isRecycled`/
  `.recycle()` (scanOnDemand recycles); `TtsPreferences.speechRegionFilterConfig()`
  + `speechCleanupOptions()` directly (not the 9 individual pref getters).

### 2026-09-25 — S1+S2+S3+S5 latency optimization batch (user-authorized; S4 halted)

User master prompt: execute S1/S2/S3/S5 in exact sequential order; S4 (FAST local
first-pass / asset repackaging) explicitly halted — do NOT restore ocr_fast
TFLite assets, do NOT modify FastOcrEngine.kt, do NOT touch APK dependencies or
binary assets.

**Files changed (7 source + 1 new + 1 test):**
- `PrioritizedTaskQueue.kt`: +Priority.LOW, +lowPriorityTasks deque, drain order
  HIGH→NORMAL→LOW, isIdle + restart-guard include LOW.
- `OcrModels.kt`: +OcrScanPriority.LOW.
- `OcrRepositoryImpl.kt`: +LOW mapping in `toQueuePriority()`.
- `PrioritizedTaskQueueTest.kt`: +`lowPriorityTaskDrainsAfterNormalAndHigh`
  test (3-tier drain order: LOW-1 runs, then HIGH, then NORMAL, then LOW-2).
- `ReaderViewModel.kt`: +`ocrScanManager` (19th ctor param, Injekt.get()),
  +`getCachedChapterIdsOcr` (injectLazy), +`nextChapterOcrPrefetchGate` field,
  +`maybePrefetchNextChapterOcr(chapters)` called at `loadChapter` success (next
  to `maybePrefetchNextChapter`); guards: `ttsAutoNextChapter` pref on +
  `nextChapter != null` + non-cellular TRANSPORT_CELLULAR + uncached check via
  `GetCachedChapterIdsOcr` + one-shot gate; `OcrScanManager.enqueue` runs on
  `viewModelScope.launchIO`. FAB stays idempotent manual override (queue
  dedupes by chapter id).
- `NextChapterOcrPrefetchGate.kt` (new): one-shot guard per next-chapter id,
  mirrors `NextChapterPrefetchGate`.
- `AndroidTtsEngine.kt`: +`lastAppliedEngine`/`lastAppliedVoice`/
  `lastAppliedLanguage` cache; `initialize()` warm path compares current prefs
  vs last-applied; if unchanged, skips `applyVoiceConfig` (~250ms/resume cut);
  if changed or cold, runs full apply and updates cache.
- `GlensOcrEngine.kt`: removed `java.net.HttpURLConnection` + `java.net.URL`
  imports; +`glensHttpClient` lazy `OkHttpClient` field (connectTimeout 10s,
  readTimeout 60s); `executeRequest` now uses OkHttp `Request` + `Call`
  (`payload.toRequestBody(CONTENT_TYPE_PROTOBUF.toMediaType())`); `disconnect()`
  removed (OkHttp pool handles keep-alive reuse); `isTransientHttpFailure`
  retry path in `OcrRepositoryImpl` unchanged.

**Gates (docker vsc-yomihon image, -Xmx4g, both volumes):** spotlessApply +
spotlessCheck + `testDebugUnitTest` + `:app:assembleDebug` — BUILD SUCCESSFUL.
No DB schema change. No commit (user decides).

**DEVICE VERIFICATION (SM_M066B, .device-pass/on-device-capture.log 78.5 MB,
solo-farming-in-the-tower ch8722→8723→8724→8725, TTS active, uncached):**
- **S2 PASS**: 3× `OcrScanJob` WorkManager starts (22:52:27.210 / 23:07:09.656 /
  23:18:24.186), each 120–400 ms after the matching `Next-chapter image prefetch`
  log. Worker SUCCESS ×2 (4f21b33f @22:53:30, 74f3164e @23:14:40). One-shot gate
  confirmed: 3 prefetched chapters = exactly 3 OcrScanJob starts, zero duplicates.
  Background scan of chapter 8723 (p0–p17, 18 pages) ran 7 min before any TTS
  advance to it.
- **S3 PASS**: 8× `TTS voice config unchanged; skipping re-apply` on the main
  thread (0 ms, no `voicesMs`/`enginesMs` enumeration) vs 1× full apply = 257 ms
  (cold path, 22:52:30.442). ~250 ms saved per warm resume, as designed.
- **S5 PASS**: 759/760 tile uploads = HTTP 200; 1× HTTP 500 (23:18:10,
  `scan=86498339 tile=3600`, 32 s server stall — WATCH, see below).
  `postUploadWaitMs=0` in all 760 upload lines (no client-side post-upload wait).
  Cold first-batch (first 10 HTTP 200s, time-ordered): p50=546 ms, max=607 ms
  — vs Stage 4M baseline first-batch p90 ≈ 4,900 ms = ~8× handshake-tail
  reduction. All 759×HTTP200 (sorted): p50=5512, p90=9592, max=15640, avg=5666 ms
  (includes GLENS server compute).
- **S1 PASS (partial — LOW tier not in prod traffic yet)**: HIGH drained ahead of
  NORMAL with `waitMs=0` ×2 (22:53:23.516 ch8725 p2, 22:53:36.088 ch8725 p2,
  while a NORMAL held a slot). 1× HIGH `waitMs=6661` at 23:07:11 (cap-full: 3
  NORMALs running — documented non-preemptive slot-wait, not starvation).
  `low=` depth = 0 in all 193 queue-depth log lines: S2 routes through the
  `OcrScanJob` WorkManager service (NORMAL via `scanPageOcr.await` default), NOT
  through `PrioritizedTaskQueue.LOW`. LOW tier is implemented + unit-tested but
  unused by any production caller — by design, documented gap.

**WATCH items (new known-issue candidate):**
- **HTTP 500 no-retry** (23:18:10, scan=86498339 tile=3600): single transient 500
  (32 s server stall) did NOT trigger `isTransientHttpFailure` single-retry in
  `OcrRepositoryImpl.scanWithGlens`. Likely cause: 500 was the last of 4
  concurrent tiles; 3 sibling tiles returned 200, so the page result completed
  with partial regions and the 500 exception was absorbed by the tile-concurrency
  `awaitAll` path rather than propagating to `scanWithGlens`. Fix candidate:
  per-tile retry inside `GlensOcrEngine.recognizeTiled` (tile-level, not
  page-level).
- **S1 LOW tier unused in prod**: future S2 follow-up could wire the background
  OCR scan through `PrioritizedTaskQueue.LOW` directly (bypassing the service)
  — new scope decision, not in this batch.

**Design notes:**
- S2 does NOT use `OcrScanPriority.LOW` — the `OcrChapterScanner` (run by
  `OcrScanManager`/`OcrScanJob` service) calls `scanPageOcr.await` with the
  default `OcrScanPriority.NORMAL` (ScanPageOcr.kt:15). The `OcrScanManager`
  service path is independent of `PrioritizedTaskQueue` (it runs in a
  WorkManager background service), so S2's enqueue does NOT go through the
  queue; it goes through `OcrScanJob` → `OcrChapterScanner.scanChapter` →
  `scanPageOcr` (NORMAL priority). S1's LOW tier is available for future
  direct-queue callers; S2's background scan runs in its own service
  context. This is consistent with the audit's "S2 depends on S1" note —
  S1's LOW tier is a prerequisite for the LOW-priority path to exist in the
  queue enum, even though S2's specific enqueue path uses the service
  (NORMAL).
- S5: `glensHttpClient` is a per-engine-instance `OkHttpClient` (not the
  shared `NetworkHelper.client`). This is intentional: the GLENS endpoint is
  a single fixed URL; a dedicated client isolates its connection pool and
  keeps the engine self-contained. `NetworkHelper.client` is available via
  Injekt if a shared client is preferred in a future refactor.

### 2026-09-25 — Full OCR architecture, prefetch & latency system audit (read-only, docs-only)

User master prompt: end-to-end audit of the OCR/prefetch/TTS latency system from page load through speech dispatch, dual-engine topology, queue priorities, prefetch boundaries, and FAB automation. Zero source changes; report + doc updates only.

**Report**: `docs/audits/ocr-prefetch-latency-audit-report.md` (484 lines, file:line-cited, root-cause matrix RC-1..RC-8, step-by-step latency table, current vs proposed mermaid diagrams, S1–S5 actionable checklist + SHOULD-NOT list).

**Verdicts (all code-verified, no new device session)**:
1. **RC-1 (dominant)**: residual N→N+1 transition latency = GLENS post-upload service wait (med ~9.6s / p90 ~17.9s, Stage 4M ch8475), irreducible client-side. Everything else on the transition path is already <1s via shipped machinery: N+1 image prefetch p0..p3 (09-20, device-verified), reader-open p0 OCR prefetch (Stage 4P), `PrioritizedTaskQueue` (cap 3, HIGH/NORMAL), `OcrCacheStore` (5000-page cap, per-model predicate), eager TTS init (Stage 2D: 5381ms cold → 81ms warm reuse).
2. **RC-6**: `FastOcrEngine` (local TFLite, `ocr_fast/*.tflite`) is effectively dead on the scan path — `detectionEngine()` is the `UnavailableDetOcrEngine` stub (throws `DetectionUnavailable`) → `scanLocalOrFallback` always redirects to GLENS; assets are gitignored/absent on fresh clones. A full-page `FastOcrEngine.recognizeText` entry point DOES exist and could serve as a <100ms interim-text stage — but that hybrid dual-stage design conflicts with the shipped "local OCR engine reinstatement REJECTED" decision (roadmap §F, −133MB asset removal) and needs an explicit new user scope decision (S4), not silent implementation.
3. **RC-7 (FAB automation)**: the "Scan Next Chapter" FAB (`MangaScreenModel.scanNextUnreadChapter`) is user-gated only; the smallest architectural hook to automate it = reader-entry auto-enqueue of the next-unread chapter id through `OcrScanManager.enqueue` (~15 lines in `ReaderViewModel`, reusing the `OcrScanJob` service path + idempotent dedupe, so the FAB becomes a harmless manual override). Zero new UI — `observeOcrQueue`/`isNextOcrScanning` spinner already reflects queue state (design.md §15). Gated on S1 (`PrioritizedTaskQueue` +LOW tier) so N+1 background OCR prefetch can't starve TTS.
4. **RC-5**: `applyVoiceConfig` re-enumerates voices/engines (~66/227 entries) + re-applies `setVoice` on every warm `initialize()` even when prefs unchanged — ~250–305ms/resume (Stage 4N seam; S3).
5. **S5 gap found**: `GlensOcrEngine.executeRequest` uses raw `HttpURLConnection` per tile, `disconnect()` in `finally` — no connection pooling / keep-alive reuse despite the `Connection: keep-alive` header being set; the 4.9s first-batch upload p90 (Stage 4M) is per-tile handshakes. Fix candidate = shared OkHttp client (already in-app).
6. **No N+1 OCR prefetch gap explained**: `maybePrefetchReaderOpenOcr` is deliberately skipped while a TTS session is active (ReaderViewModel.kt:416-420) — correct for the ACTIVE chapter (TTS's own lookahead covers it) but leaves N+1 uncovered during auto-advance, which is exactly the auto-advance case TTS hits next. That's the S1+S2 gap.

**Roadmap state after this session**: `implementation-roadmap.md` §B now "NONE EXECUTABLE" — S1–S5 all UNAUTHORIZED pending user scope sign-off (S4 explicitly needs the §F rejection lifted for the NEW dual-stage architecture). `design.md` §15 new (reader loading states / prefetch feedback / manual scan override semantics). `architecture.md` §3.1 (transition prefetch pipeline) + §4.3 (hybrid dual-stage strategy, "NOT shipped") + §5.4 (TTS warm engine lifecycle, device-measured numbers) added. No gates run (docs-only session, zero source changes).

### 2026-09-21 — Recent tab display sheet & sub-tab toggle (revert of Phase 2 M2 category chips)

Phase 2 M2 (category chip filtering on Updates/History) was the wrong interpretation of "recent tab category toggle" — the user wanted the Library-tab **display-sheet pattern**: a top-bar filter icon opening a display sheet with a "Show tabs" switch. Revert + replace, all in one commit `8732ed49d`:

- DELETED `RecentCategoryFilterRow.kt`; reverted chip-row rendering from `UpdatesScreen.kt`/`HistoryScreen.kt`; reverted category-filter logic from `UpdatesScreenModel` (removed `GetCategories` injection, `mangaInCategory` cache, `categoryFilter*` state fields + the pref-combine launch) and `HistoryScreenModel` (removed `filterHistoryByCategory`/`primeCategoryCache`/`mangaCategoryCache` + the second launch block); removed the 4 Phase-2 keys (`displayRecentUpdates/HistoryCategoryFilter`, `recentUpdates/HistoryCategoryId`) from `LibraryPreferences`; dropped now-unused i18n `label_all_categories`/`category_filter`.
- NEW `LibraryPreferences.showRecentTabs` = `getBoolean("display_recent_tabs", true)`.
- NEW `RecentDisplaySheet.kt` (recent package): `AdaptiveSheet` + `PreferenceGroupCard("Display")` + `SwitchPreferenceWidget("Show tabs")` live-collecting `showRecentTabs` (`collectAsState` from presentation-core util).
- `RecentTab.kt`: filter_list (`Icons.Outlined.FilterList`) top-bar action opens the sheet; `PrimaryTabRow` (Continue|History|Updates) now gated on `showRecentTabs`; when off, the `HorizontalPager` sits directly under the top bar (swiping still works — pager state retained). Frozen 3-page structure untouched.
- i18n base: `show_recent_tabs` ("Show tabs"). Reused existing `action_display` for the group header — no new duplicate.

Gates green (docker -Xmx4g both volumes): spotlessApply+Check + :app:testDebugUnitTest + :app:assembleDebug BUILD SUCCESSFUL 3m48s. No DB change. NOTE: M1/M3/M4 from the Phase 2 commit remain shipped & correct — only M2 was reverted/replaced.

### 2026-09-21 — Phase 2 implementation: Yomitsu UI refinement & bug fixes (4 modules)

Implemented all 4 modules from Phase 1 audit (docs/audits/yomitsu-ui-refinement-phase1-audit.md). **Gates GREEN** (docker vsc-yomihon-e24e3bd7…, -Xmx4g, both volumes): spotlessCheck + :app:testDebugUnitTest + :app:assembleDebug BUILD SUCCESSFUL 3m01s single-pass.

M1 — Compact Library list item: new `LibraryListItem.kt` (app/…/ui/library/component/) = M3 Card (shapes.large) + 52dp Book-ratio `MangaCover.Book` + titleSmall 1-line title + bodySmall badge row (Unread/Downloads/Language + DotSeparatorText), reusing `ContinueReadingButton` (made internal in CommonMangaItem.kt). `LibraryList.kt` swapped `MangaListItem` → `LibraryListItem` only (click/long-click/continue-reading semantics identical); `MangaListItem` untouched (browse-shared). No new display-mode enum value.

M2 — Recent-tab category filter: 4 new `LibraryPreferences` keys (displayRecentUpdates/HistoryCategoryFilter default true + recentUpdates/HistoryCategoryId default 0; no DB/`.sqm` change). New `RecentCategoryFilterRow` (RecentTab host package): "All" + per-category FilterChips, persisted selection via prefs. UpdatesScreen/HistoryScreen render the row gated on the toggle; screen models filter items (Updates: `categoryFilteredMangaIds` set via `GetCategories.await(mangaId)`; History: sync-primed `mangaCategoryCache` map + non-suspend filter in collect). Frozen 3-page RecentTab structure untouched.

M3 — Support/About link cleanup: `SupportUsScreen` dropped Patreon + Open Collective rows, added GitHub profile card (`CustomIcons.Github` → github.com/Nikhil0921/yomitsu). `AboutScreen`: Website + Discord footer icons + Privacy Policy row now toast `link_not_configured` instead of opening external URLs; GitHub repo link stays live. `MoreScreen` Help row → same toast.

M4 — In-app changelog: new `WhatsNewSheet.kt` (AdaptiveSheet + existing `MarkdownRender(GFM)` + "Open on GitHub" escape hatch). `AppUpdateChecker.fetchLatestRelease()` = side-effect-free `ReleaseService.latest()` path (no notification/throttle write). AboutScreen "What's new" row (release builds) now opens the sheet; on fetch failure shows `release_notes_unavailable` fallback. ABI fix: `ReleaseServiceImpl.getDownloadLink` now maps all 5 shipped ABIs (arm64-v8a, armeabi-v7a, x86_64, x86, universal) via `ABI_TO_BUILD_TYPE` walk over `Build.SUPPORTED_ABIS` with universal fallback (old code matched only 3 suffixes, v7a/x86 fell to `map[null]` universal-or-null).

i18n base strings added (base only): label_all_categories, category_filter, link_not_configured, release_notes_unavailable, supportUsScreen.githubProfile, supportUsScreen.githubProfileTitle. Not committed yet (user decides, per repo convention). Skipped: notification "What's new" action still opens browser link (in-app sheet from a notification action needs an Activity host — larger, out of scope this batch).

### 2026-09-21 — Phase 1 read-only audit: Yomitsu UI Refinement & Feature Audit (4 modules)

Read-only (zero source changes; report = docs/audits/yomitsu-ui-refinement-phase1-audit.md). (1) Library list: `LibraryDisplayMode.List` → `LibraryList.kt` → `MangaListItem` (56dp Square cover); plan = NEW sibling `LibraryListItem` adopting `UpdatesCompactCardHeader` card language (52dp Book cover, `shapes.large` Card, `titleSmall`/`bodySmall` + `DotSeparatorText` metadata), swap inside `LibraryList.kt:46` only; `MangaListItem` untouched (browse-shared); no 5th display-mode enum value. (2) Recent-tab category toggle: `RecentTab.kt` = hard-coded 3 pages, `RecentTabContent` plain data class; frozen IA → within-page toggles only; proposed `LibraryPreferences` keys `display_recent_updates_category_filter`/`recent_updates_category_id` (+history) mirroring `categoryTabs`/`categoryNumberOfItems`, wired via `UpdatesScreenModel`/`HistoryScreenModel` + `FeedFilterBar` chip-row pattern. (3) More/About: `MoreScreen.kt` rows = pure `navigator.push`/`uriHandler.openUri`, NO `SnackbarHostState` anywhere in `MoreScreen`/`MoreTab`/`AboutScreen`; support = `SupportUsScreen.kt` `Card`+`TextPreferenceWidget` → `Constants.URL_DONATE_PATREON/OPENCOLLECTIVE/DISCORD`; About "What's new" = plain browser link `RELEASE_URL`. (4) Update/changelog: full chain verified `ReleaseServiceImpl.latest()` (GitHub REST, `GithubRelease` DTO, `<!-->` sentinel strip) → `GetApplicationRelease` (3-day throttle + semver/commit-count compare) → `AppUpdateChecker`/`AppUpdateNotifier` (notification "Download"/"What's new"-browser-link) → `NewUpdateScreen` (`InfoScreen` + `MarkdownRender(GFM)`); plan = NEW `WhatsNewSheet` over existing `AdaptiveSheet` + `MarkdownRender` + `Release.info`, no new network/parsing/deps; 2 wiring points (`AboutScreen.kt:148-152` row swap + notification action documented limitation); latent ABI-matcher gap flagged (`getDownloadLink` matches only 3 of 5 ABIs, `?: map[null]` universal fallback). No gates run (docs-only session).

### 2026-09-21 — Prefetch pipeline detailed logcat capture + verification report

Started full logcat capture (threadtime, ~36 MB / 266,942 lines) on SM_M066B, opened Villain To Kill ch10 (Asura Scans, remote, Wi-Fi) 3×. Captured 3 prefetch firings (00:46/01:01/01:05). N+1 (ch4434) p0–p4 `internalLoadPage` all Ready in **1–20 ms = DiskLruCache reads, ZERO image GETs in prefetch windows** (bytes cached from 09-20 verify session). Only network cost: 1 cold page-list fetch (302+302+200, ~334 ms). Active ch10 p6 uncached page took 2605 ms network while prefetch ran on separate worker thread — no starvation. OCR co-located scan waitMs=0. Worker isolation confirmed by distinct TIDs (ch10: 3051/3084/14691, ch11: 3077/6315/11865). Cellular guard still code-reviewed only. Full report: docs/audits/next-chapter-prefetch-verification-report.md; raw log .device-pass/prefetch-detail-capture.log (gitignored). Docs-only session; no source changes; no gates needed.

### 2026-09-20 — Phase 2/3 implementation + device verification: next-chapter image prefetch pipeline (COMMITTED 17773e92c, not pushed)

Implemented per Phase 1 audit (docs/audits/reader-prefetch-phase1-audit.md §4). **DEVICE VERIFIED 2026-09-20 (SM_M066B, .device-pass/prefetch-verify.log):** 3× `Next-chapter image prefetch` logcat firings across 3 reader opens on remote source (Asura Scans: A Dragonslayer ch3→ch4, Villain To Kill ch8→ch9, + 1 reader-open via uuid URL); N+1 p0..p3 all `internalLoadPage ... status=Ready` in N+1's own HttpPageLoader worker (isolated from N's worker); fresh force-stopped process re-fired prefetch correctly; disk-cache hits confirmed on reopen. Guard observed live: prefetch ran under active Wi-Fi (dumpsys: WiFi active 118, Cellular empty). **Cellular short-circuit NOT exercised live** (can't force network-class on unlocked device; `cmd wifi stop-network` unavailable) — code path reviewed, ponytail note on TRANSPORT_CELLULAR ceiling recorded in session log.

Changes (8 files + 2 new):
(1) `ReaderPreferences.prefetchNextChapter` (key `reader_prefetch_next_chapter`, default true) + SettingsReaderScreen Reading-group toggle + 2 i18n base strings.
(2) `NextChapterPrefetchGate` (new, mirrors ReaderOpenPrefetchGate) — one-shot per ACTIVE chapter id; re-arms on chapter change. 2 unit tests (NextChapterPrefetchGateTest, 2/2).
(3) `ChapterLoader.prefetchFirstPages(chapter, pageCount=4)` — no-op for local/downloaded (isLocal); for remote, `loader.loadPage(p)` on first 4 pages (ADJACENT auto-enqueue via preloadNextPages; per-chapter worker isolated from N).
(4) `ReaderViewModel.maybePrefetchNextChapter(newChapters, chapter)` — called at `loadChapter` success (:582): guards = pref on + nextChapter != null + source is HttpSource + NOT cellular (TRANSPORT_CELLULAR, ponytail: metered-capability API21+ but kept simple; cellular is the real data-cost case) + gate. Job = viewModelScope.launchIO { loader.loadChapter(next); loader.prefetchFirstPages(next) } — cancellable, best-effort, errors logged DEBUG-only. Cancel points: `loadNewChapter` (:634) + `onCleared` (:355). TtsPlaybackController: ZERO changes (per task constraint).

Gates GREEN docker (-Xmx4g both volumes): spotlessCheck + :app:testDebugUnitTest + :app:assembleDebug BUILD SUCCESSFUL 5m46s. Note: spotlessApply twice mangled ReaderViewModel imports (removed in-use `Immutable`; my `toConnectivityManager` import added then dropped as unused) — final state hand-fixed + spotless-clean. **Committed 2026-09-20 as 17773e92c (`feat(reader): implement background next-chapter image prefetch pipeline`, 12 files) per task handoff; NOT pushed.** Device VERIFIED same day (SM_M066B, .device-pass/prefetch-verify.log, gitignored): 3× logcat firings on remote source (Asura Scans), N+1 p0..p3 Ready + disk-cache hits on reopen, fresh-process re-fire; cellular short-circuit NOT exercised live (no root) — code-reviewed only.
### 2026-09-20 — Phase 1 read-only audit: reader/loader/queue for next-chapter prefetch pipeline

Read-only (zero source changes; report = docs/audits/reader-prefetch-phase1-audit.md). Ground truth: (1) N+1 transition latency = uncached page-list HTTP + p0 image HTTP serial on transition + GLENS p0 round-trip when TTS active — image prefetch of N+1 p0..p3 via the EXISTING per-chapter HttpPageLoader (separate worker/queue per chapter → no N contention in-thread; contention only at OkHttp per-host pool + DiskLruCache flushes). (2) PrioritizedTaskQueue has HIGH/NORMAL only (cap 3); TTS prefetch depth 2-3 (rate-aware) can fill all 3 slots → 0.615s p0 HIGH wait (Stage 4P). LOW tier = ~15-line add, DEFERRED (no N+1 OCR prefetch Phase 1 — TTS re-schedules on advance; reader-open prefetch already covers N+1 p0 at loadNewChapter:594). (3) ChapterCache = DiskLruCache 100 MiB cap (ChapterCache.kt:213), per-image flush() — N+1 prefetch evicts ~5-10 oldest N images, safe/re-fetchable. (4) Hooks: trigger at ReaderViewModel loadChapter success (only when nextChapter != null, remote, non-metered, one-shot gate like ReaderOpenPrefetchGate), cancel in loadNewChapter (:585) + onCleared; ChapterLoader.prefetchFirstPages ~5 lines reusing loadPage (ADJACENT preloads auto-follow, HttpPageLoader.kt:102-106). Gates not run (docs-only session).

### 2026-09-20 — v0.5.4.2 release (published to GitHub)

Version bump 0.5.4.1/vc31 → 0.5.4.2/vc32, committed + tagged v0.5.4.2 (20f4eb746), pushed main + tag to origin. Gates green in docker (-Xmx4g, both volumes): spotlessCheck + testDebugUnitTest + verifySqlDelightMigration 5m08s; `:app:assembleRelease -Pinclude-telemetry -Penable-updater` 19m36s (281 tasks). Signing pre-build gate PASSED: host `~/.android/debug.keystore` SHA-256 = `e486ea51...8968` (known-good), container (yomihon-android-home mounted) same fingerprint; apksigner-verified `app-arm64-v8a-release.apk` cert = same. 5 ABI APKs (arm64-v8a 63M, armeabi-v7a 57M, universal 123M, x86 56M, x86_64 68M) attached to GitHub release Nikhil0921/yomitsu v0.5.4.2 (Latest). Content: frosted theme, immersive mode, nav reorder, feed drag reorder, translucent nav + background gradient (09-13/09-15 batches); OCR per-page resilience + "Scan Next Chapter" FAB + progress sync (09-19); OCR prefetch-on-chapter-open timing (Stage 4P); GLENS tile concurrency 4; Recent→Updates collapsible group cards; Feed auto-pagination + compact source headers (user-verified 09-19). Note: public release notes deliberately omit upstream-project attribution (user instruction 09-20).

### 2026-09-19 — OCR per-page loop resilience + "Scan Next Chapter" FAB (user master prompt)

Two deliverables, uncommitted. (1) `OcrChapterScanner.kt` page loop: per-page try/catch (skip+continue on page decode/scan failure or timeout; CancellationException rethrown), `withTimeoutOrNull(pageScanTimeout)` around `scanPageOcr.await` (new ctor param `pageScanTimeout: Duration = PAGE_SCAN_TIMEOUT` = 90s, ctor default keeps DomainModule 8-arg call), progress now counts only scanned pages (`processedPages` counter), skip warn-log. New `OcrChapterScannerTest` (3 tests: all-succeed, one-page-exception skip, one-page-timeout skip) — key gotchas: relaxed-mock Context needs BOTH `getSystemService(Class)` AND `getSystemService(String)` stubs (androidx uses javaName); `Preference<Boolean>` relaxed mock returns raw Object for generic `get()` → stub explicitly; `OcrImage` validates positive dims → stub Bitmap width/height; nested MockK-stubbed suspends inside a mocked generic `WithOcrScanSession.await` leak COROUTINE_SUSPENDED → fixture uses REAL `WithOcrScanSession` over an object-expression `OcrRepository` instead. (2) FAB: `MangaScreenModel.scanNextUnreadChapter()` + `observeOcrQueue()` (collects `ocrScanManager.queueState`, sets `State.Success.isNextOcrScanning` when next-unread id queued non-ERROR); presentation `MangaScreen.kt` new `MangaFloatingActionButtons` composable (Row: FilledIconButton DocumentScanner + spinner when scanning, then existing SmallExtendedFAB, both impls); ui/manga/MangaScreen.kt wires `onScanNextOcrClicked = screenModel::scanNextUnreadChapter`; i18n `action_scan_next_chapter_ocr`. `MangaScreenModelErrorStateTest` needed `queueState` stub on its OcrScanManager mock. Gates: spotlessCheck + testDebugUnitTest + :app:assembleDebug all green 3m42s. No commit.

### 2026-09-19 — STAGE 4P implementation: OCR prefetch timing optimization (integrated)

One-line change: `maybePrefetchReaderOpenOcr(0)` added inside `loadNewChapter()` at `ReaderViewModel.kt:594` (after `loadChapter` succeeds), eliminating p0 HIGH queue wait (~0.615s) by starting page-0 OCR immediately on chapter load instead of waiting for `onPageSelected`. Existing `onPageSelected` trigger (same-chapter navigation) preserved. Cancellation/lifecycle intact (`readerOpenPrefetchJob?.cancel()` unchanged in `loadNewChapter`; `onCleared()` cleanup untouched). Gates green: spotlessCheck + :app:testDebugUnitTest + :app:assembleDebug BUILD SUCCESSFUL 3m38s. ReaderOpenPrefetchGateTest 2/2, TtsReaderOpenPrefetchTest 3/3, FeedScreenModelStateTest 12/12. No commit. Stage 4P client prefetch timing optimization fully integrated.

### 2026-09-19 — STAGE 4P OCR preload/prefetch forensic audit

Read-only (no code/device/commit). Classification MIXED (client-scheduling dominant): p0 HIGH queue wait 0.615s when TTS prefetch p1/p2/p3 occupy all 3 slots. GLENS service wait irreducible client-side. Reader-open prefetch starts too late (after TTS start) for first-page latency optimization. TTS prefetch depth=3–4 at rate≥1.5x fills all 3 queue slots. Quantified: 0.615s p0 HIGH wait observed. Existing prefetch hides ~14s of ~27s total scan latency for settled reading (ch8222). Smallest experiment = move reader-open prefetch trigger from onPageSelected() to loadNewChapter() (NOW EXECUTED in Stage 4P implementation above). Branch closed.

### 2026-09-19 — STAGE 4O next-task forensic reconciliation

Read-only (no code/device/commit). Roadmap §B lists exactly ONE current authorized task: Q8 Feed auto-pagination (user-authorized 09-16; implementation complete, gates green, device verification PENDING). No other implementation task is authorized. Q8 Feed auto-pagination device verification = only outstanding item under current authorized task. All other OCR TTS stages 0–4N closed. Zero source changes.

### 2026-09-19 — STAGE 4N voice-init forensic audit: engine-owned, HIDE shipped, closed

Read-only (no code/device/commit). Cold init split (ch7877): awaitMs=5381 (onInit) + voicecfg 305ms (voices 66/engines 227/apply 9). Enumeration (B) + setVoice (C) refuted; repeats (D) refuted — single Create, idempotent fast-path; re-apply ~250ms/resume only. Artifact (E) refuted — await is genuine Google TTS service latency (28.9s anomalous boot observed). HIDE (F) already shipped via 2D eager init (43s pre-tap → 81ms reuse). Only REDUCE seam: skip redundant voicecfg re-apply on unchanged prefs (~250ms/resume) — NOT first-speech path, needs authorization. Branch closed.

### 2026-09-19 — STAGE 4M split: SERVICE-DOMINANT, transport closed

ch8475 fresh uncached (pid-pure: 5 scans, 29 uploads, 29×HTTP200, zero failures). Upload med 3ms / p90 4.9s (first-batch handshakes only) vs post-upload wait med 9.6s / p90 17.9s. Steady-state upload ~0.03% of tile time; per-page client cost ≈ one handshake vs 18–29s page totals. TTS smoke OK (18 dispatches). No code change (4E seam reused). No commit.

### 2026-09-19 — STAGE 4L device validation PASS (production C=4, 4K/4L closed)

SM_M066B, cert e486ea, install -r, cold. ch8447 (Reborn Ranker ch1) p0–15 scanned: 160×HTTP 200, zero non-2xx/exceptions (3 FATALs stale 09-15/16 ring buffer); 11-tile scan ran max 4 concurrent; regions 0–19/page healthy; TTS start→speech p0→p12 (167 dispatches, 26 advances) to user p13, no crashes. Gates re-green 3m24s. No commit.

### 2026-09-19 — STAGE 4K shipped (TILE_CONCURRENCY 3→4, uncommitted)

One-line production change (`GlensOcrEngine.kt:1023`). Gates green in one container run (3m48s: spotlessCheck + testDebugUnitTest + :app:assembleDebug, docker -Xmx4g both volumes). No test asserts the constant; no test modified. Prior-stage test-only seam hunks in same file untouched. No device install, no commit per task.

### 2026-09-16 — Feed auto-pagination + All Sources source headers (uncommitted)

User-authorized Q8: single-source auto-pagination (near-end threshold=5) + compact source headers in All Sources mode. FeedScreen.kt only — FeedScreenModel.kt untouched. `selectedSourceId != null` gates auto-pagination (not visibleFeeds count — correct for single-enabled-source edge case). `lastOrNull()` triggers for the last visible feed section. Source headers: `labelMedium`, 8dp/4dp padding, full-span, no divider. Tests: 2 new in FeedScreenModelStateTest (9/9 total). Gates green: spotlessCheck 35s, testDebugUnitTest 3m45s, :app:assembleDebug 4m4s. **Device verification COMPLETED (user-verified 09-19)**: auto-pagination functions correctly for single-source listings and popular/latest feeds. Q8 CLOSED.

### 2026-09-16 — Single-chapter Updates unified with compact card (uncommitted)

Follow-up refinement: flat single-chapter rows now render via shared `UpdatesCompactCardHeader` (same Card/cover/type/padding as groups) with existing `ChapterDownloadIndicator` trailing and no expand arrow; selection/long-press preserved. Group behavior untouched. Gates green 2026-09-16: spotlessCheck 53s, testDebugUnitTest 3m39s, :app:assembleDebug 3m32s (UpdatesGroupingTest 7/7).

### 2026-09-16 — Debug-key reinstall (install only, no verification)

`yomihon-android-home` `debug.keystore` (2026-08-29, SHA-256 `E4:86:…:89:68`) matches both the installed `app.yomihon.dev` cert and the fresh `app-arm64-v8a-debug.apk`; `adb install -r` succeeded with data preserved. Prior mismatch came from an APK signed elsewhere, not volume rotation. No uninstall, no wipe, no new key.

### 2026-09-16 — Recent→Updates compact group card (uncommitted)

User-authorized one-off visual refinement (not a roadmap queue item): `UpdatesMangaGroupItem` now one M3 Card (`shapes.large`) with `MangaCover.Book` 52dp (~78dp tall), titleSmall title + first-row chapterName/relative dateFetch metadata, exclusive 48dp trailing ExpandLess/More IconButton toggle. Group model/order/state/callbacks untouched; UpdatesGroupingTest 7/7. Gates green: spotlessCheck 41s, testDebugUnitTest 2m51s, :app:assembleDebug 2m39s. Device/visual pass PENDING (issue #7).

### 2026-09-15 — YOMUCHU UI BATCH 2 (committed 3dbf474ba)

User-authorized 3-fix batch: (1) Recent→Updates collapsible per-manga groups — pure fold `groupConsecutiveUpdates` (≥2 consecutive same-manga → `UpdatesUiModel.Group`; date headers break runs; default collapsed; TDD 7/7 UpdatesGroupingTest). (2) Gradient wash-out fix — PreferenceGroupCard +1dp outlineVariant hairline (bottom stop == card fill made cards invisible; all themes). (3) RecentTab un-clipped, contentPadding threaded → 3 pages scroll under floating pill. Gates green (2m30s + assembleDebug 4m5s); 1 i18n key (action_collapse). DEVICE PASS SKIPPED — keystore signature mismatch (issue #7).

### 2026-09-15 — Adaptive UI corrective fix batch

User-directed refinement of 2026-09-13 adaptive-UI batch (nav controls explicitly SKIPPED). Fixed 3 batch-introduced defects:

1. **Nav translucency OFF rendered 55% alpha**: TachiyomiTheme navTranslucencyAlpha(0)=0.55f (MIN bound) when toggle OFF. FIX: gate on boolean — OFF → 0f (opaque), ON → bounded 0.55..0.92 via unchanged function.

2. **ManageFeeds LazyColumn key = FeedItem data class**: contains mutable `enabled`; toggle mid-drag = key change. FIX: key = "sourceId:listing" (stable immutable identity; same source can have POPULAR+LATEST pair).

3. **FrostedColorScheme missing slots**: added error/onError/errorContainer/onErrorContainer, outlineVariant, scrim, surfaceDim, surfaceBright (values from XML).

**Gates**: GREEN (spotlessCheck + testDebugUnitTest + verifySqlDelightMigration + :app:assembleDebug, 5m49s). Device verification PENDING (same SM_M066B matrix as master batch).

Files: `TachiyomiTheme.kt`, `ManageFeedsScreen.kt`, `FrostedColorScheme.kt`.

### 2026-09-15 — Nav-pill scroll-behind + gradient/live-apply

Root causes (source-proven, batch-introduced):

1. **Gradient + nav translucency appeared static**: TachiyomiTheme read prefs via plain Preference.get() — non-observable. FIX: collectAsState() on 4 prefs in TachiyomiTheme → remember-keys re-fire → brush + pill alpha recompute live.

2. **Content clipped above pill**: HomeScreen Box applied outer Scaffold contentPadding as LAYOUT padding → viewport bottom cut. FIX:
   - NavigationBar.kt: LocalNavPillBottomInset (staticCompositionLocalOf, default 0.dp)
   - HomeScreen: pads top/start/end only, consumes all insets, provides LocalNavPillBottomInset
   - Forked Scaffold.kt: reads local, clears to 0.dp for own content, folds into innerPadding bottom (resting padding) + bottomBar slot + FAB/snackbar anchors

**Gates**: GREEN (spotlessCheck + testDebugUnitTest, 3m12s). Device VERIFIED on SM_M066B:
- Gradient live ✓ (Δ≈9-11/255, pixel-max ~99)
- Nav alpha live ✓ (OFF=0.0 exact, intensity 3=16.9-20.9, 100=5.9-7.4)
- Scroll-behind ✓ (Library+Feed pass under pill, pill height 80dp, selection hides on long-press)
- No regressions (crash unrelated to batch)

Files: `presentation-core NavigationBar.kt`, `Scaffold.kt`, `TachiyomiTheme.kt`, `HomeScreen.kt`, `AppBackgroundGradientTest.kt` (new).

### 2026-09-13 — Adaptive UI & Personalization master batch

Implemented 10 features (user-authorized via master prompt; supersedes nav-reorder/background locks for this scope):

A. **Immersive mode**: pref_immersive_mode (default false); MainActivity hides/shows systemBars via WindowInsetsControllerCompat with BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE.

B. **Bottom nav reorder**: NavTab enum = identity (NOT visual position); pref_nav_tab_order CSV; parseOrder() never loses/dupes; HomeScreen orderedTabs() from pref (live apply); NavigationBar + NavigationRail iterate ordered list; TabNavigator start = first ordered tab; SettingsNavigationScreen (ReorderableItem + draggableHandle + a11y); 5 tabs kept, reselect semantics untouched.

C. **Feed drag reorder**: ManageFeedsScreen rebuilt as reorderable ElevatedCard rows; drag handle + a11y move actions; enable/delete/toggle/identity preserved; FeedScreenModel.moveFeedTo = same pref-list mutation.

D/E. **Translucent nav + intensity**: pref_nav_bar_translucent (default false) + pref_nav_bar_translucency (0..100, default 60 → bounded alpha 0.55..0.92 via navTranslucencyAlpha()); NavigationBar pill uses new Color.asNavContainer() (real bounded alpha, floats over gradient only).

F/G. **Background gradient**: BackgroundStyle enum (SOLID/GRADIENT) + pref_background_style + pref_background_gradient_intensity (0..100, default 35); Brush = theme-derived background→surfaceContainerLow vertical lerp; LocalAppBackground via TachiyomiTheme.

H. **FROSTED THEME**: AppTheme.FROSTED + FrostedColorScheme (cool blue-grey editorial, light+dark) + colors_frosted.xml ×2; auto-enables translucent chrome while active; appears in theme picker after Monochrome.

I. **Dialog/Sheet adaptation**: audited AdaptiveSheet/ResizableSheet/TabbedDialog route asChromeContainer/asFrostedModal; AlertDialogs = M3 defaults; Frosted = colorscheme swap → all surfaces follow.

J. **Download queue**: already grouped (FlexibleAdapter sections + headers + drag enabled); OcrQueueScreen already PreferenceGroupCard.

**PREF KEYS**: pref_immersive_mode, pref_nav_tab_order, pref_nav_bar_translucent, pref_nav_bar_translucency, pref_background_style, pref_background_gradient_intensity. NO DB change. i18n: +13 base strings.

Files: 20 files changed.

Device verification PENDING (needs SM_M066B session).

### 2026-09-13 — Master session: Q9 a11y + BUG fixes + Tscan + OCR/TTS latency + Feed genre + AnymeX micro-passes

Executed 6 commits (user-authorized; Q3–Q8 HARD HALTED, Q9 + AnymeX micro-track authorized):

**Q9 a11y**:
- CategoryListItem customActions move-up/down (+ CategoryScreen wiring)
- BaseSliderItem stateDescription=valueString (~20 callers)
- SourceSelectorDropdown menu stateDescription selected/not_selected
- TtsPlaybackBar speed menu stateDescription
- Large-font heightIn sweep ×4 files

**Bugs**:
- BUG-003: resumeIndex=0 on page-change-while-Paused (FIXED)
- BUG-004: ocr_cache getPage model predicate (FIXED — NO migration needed)
- BUG-005: prefetch cancel on NextChapter (FIXED)
- BUG-006: setVoice SUCCESS check + DEBUG log (FIXED)
- BUG-007: verified UNREACHABLE (no action)
- BUG-008: no-op clickable removed (FIXED)
- BUG-009: loadSectionsOnStart=false from ManageFeeds (FIXED)
- BUG-010: synchronous remember + 2 screens registered (FIXED)

**Tscan Feed NetworkOnMainThreadException**:
Root cause: fetchSection body ran source calls on Main via screenModelScope → withIOContext wrap (all 3 callers covered).

**OCR/TTS latency**:
ChapterCache injected into OcrPageSourceResolver: getPageListFromCache-first, image from cache when present (decode-fail → refetch, CancellationException rethrown). Residual 30–60s first-page latency = mostly GLENS service round-trip, app-side duplicate fetches now deduped via cache.

**Feed genre filtering**:
FeedScreenModel genreToggles state + getSearchManga routing when chips active, reuses BrowseSourceScreenModel helpers; FilterBar rebuilt as stacked two-row layout.

**AnymeX micro-passes**:
MoreScreen Studies card, OcrQueueScreen PreferenceGroupCard regroup, DownloadsScreen PreferenceGroupCard regroup.

Gates GREEN. Device verification DONE (SM_M066B full matrix PASS, 5 tabs + genre + genres -> Find manga + AnymeX micro-passes).

### 2026-09-12 — Q2 genre-chip search

Genre chip row in BrowseSourceScreen derived from source's OWN Filter leaves (TriState/CheckBox inside Group or top-level) via pure helpers genreToggles()/isGenreSelected()/toggleGenreSelection(). ToggleGenreChip flips INCLUDE↔IGNORE then search(filters=) re-runs. No new architecture (option B: source-supported filtering). M3 FilterChip + leading check icon. Sources without genre filter leaves honestly show no row. Multi-genre = source's own semantics.

**10 unit tests green**. Gates GREEN. Device SM_M066B Q2-01..09 PASS.

### 2026-09-12 — RM-01: pause-guard + chapter-advance-failure + dead-code sweep

BUG-001: pause() no-op during LoadingPage/Preparing → pause guard added (if phase != Playing && !paused return). BUG-002: NextChapter host-load failure → controller.fail(ChapterLoadFailed) when Preparing/LoadingPage → Error + Retry. Dead code swept (badgeNumber param + RecentTab badgeCount, ReaderBottomBar pointerInput no-op, detectionEngine identical branch + orphaned localOcrAvailable). Docs corrected (Known-issue #2 → RESOLVED).

Gates all GREEN. Device VERIFIED (SM_M066B: pause while loading, chapter-advance failure + retry, artwork-tone log OK).

### 2026-09-11 — Batch 7: reader toolbar customization

Drag-reorder toolbar actions (13 tests), persistence, upgrade-safe defaults, OCR/Read-Aloud visibility independence, Settings pinned last. USER-VERIFIED on device.

### 2026-09-10/11 — v0.5.3 release

All gates green + device smoke PASS. Baseline: Yomitsu rebrand, legacy-OCR removal (−133MB), stabilization batches, UI audit Batches 1–5.

---

## Durable architecture decisions (full records: history/session-logs.md)

1. **TTS abstraction**: system `TextToSpeech` behind framework-free `TtsEngine` (:domain); `AndroidTtsEngine` (:app) isolated. Future engines = Injekt binding swap, zero reader changes.
2. **Reader-bound playback**: pause on onStop; NO FGS/MediaSession in v1 (targetSdk 36 deliberately avoided). No on-image bbox highlight v1. No TTS audio caching (system latency tens of ms).
3. **Pure logic in :domain**: SentenceSegmenter/TtsAdvancePolicy unit-tested (JUnit5+Kotest); controller thin + untested by design (no Robolectric).
4. **English-primary pivot (2026-08-25)**: no language preflight, no `setLanguage` pinning; system-default voice. JP TTS → Phase 10B. Rejected: JP-voice gate + Locale.JAPAN pinning (blocked playback, wrong-language synthesis).
5. **Controller never touches Viewer**: events → ReaderActivity collector (established VM→Activity pattern); user swipes win via onPageSelected arbitration.
6. **Tall webtoon strips tiled at ENGINE level** (Glens), never compensated in TTS/segmenter.
7. **provideContext() re-resolves chapter context on every queue rebuild** — stale context caused silent death.
8. **Settings tab stays LAST page in ReaderSettingsDialog** (ColorFilter dim-hack index `== 2` depends on it).
9. **Reader-first identity**: manga/comic reader; OCR-assisted; TTS; dictionary/language-learning; M3 Expressive. NOT anime/video/novel/gamification.
10. **Navigation**: Voyager; frozen 5-tab IA (Library→Recent→Feed→Browse→More); per-tab reselect semantics intentional; Voyager addresses tabs by CLASS not index.
11. **PreferenceGroupCard = one group one surface**; color = surfaceContainerLow (Lowest rejected: invisible in dark schemes); no shadow; no frost-on-frost.
12. **DB**: two SQLDelight schemas (main 19 migrations + OcrCacheDatabase). Schema change → `.sqm` + `verifySqlDelightMigration`.
13. **DI**: Injekt only. **Layering**: UI → ScreenModel → Interactor (:domain) → Repo (:domain) → Impl (:data/:app).
14. **OCR engine chain**: GLENS primary (parallel tiles ×4, single-flight per (chapter,page)); FAST local (TFLite/LiteRT full-page inference — but dead on the scan path: `UnavailableDetOcrEngine` stub always redirects LEGACY/FAST→GLENS, see 2026-09-25 audit RC-6); OWOCR self-host; LEGACY alias→GLENS. Local-engine reinstatement as PRIMARY engine REJECTED (reverses shipped −133MB removal). **New (audit 2026-09-25)**: hybrid local-first dual-stage (FAST interim text for cold p0 only + GLENS authoritative background upgrade) is CONDITIONALLY FEASIBLE but is a NEW architecture, not the rejected reinstatement — needs explicit user scope decision (roadmap S4, conflicts with §F row).
15. **OCR cache**: delete-if-outdated DB + 5000-page prune; getPage filters by ocr_model (BUG-004 fixed 09-13, no migration).
16. **OCR exclusion matcher**: pure :domain, NFKC fold, 35 tests. WORD = rule-token concat equals consecutive-token-run concat; PHRASE = NFKC+lowercase+strip-all-whitespace substring; ZONE = pure-rect page-anchored any scope; COMBINED = rect AND text, opt-in (blank text → pure ZONE). Dialog matchText starts EMPTY (prefill removed — regression lesson).

Rejected approaches (TTS core): direct TextToSpeech from UI; retry/delay masking of speak() failures (retry once → honest Paused); segmenter-side region re-sorting (ordering belongs to OCR engine).

## Known issues register (current status)

| # | Status | Summary |
|---|---|---|
| 1 | OPEN MEDIUM | Local Legacy/Fast scan ordering = raw detection index (vertical manga misorder). Mitigated: detection stub throws → Glens redirect. Upgrade: port Glens ordering (Q7-class). |
| 2 | RESOLVED 09-12 | Dual setComposeContent claim was stale — ONE composition block. |
| 3 | RESOLVED locally | ML models downloaded via CI step; re-run if wiped (fresh clones affected). |
| 5 | RESOLVED 08-29 | ~100MB TTS leak (onFocusEvent chain) — fixed 5c7d2cc2c, LeakCanary 0 leaks. |
| 6 | OPEN MEDIUM | Glens seam fragments (IoU ≥0.45 dedup ceiling); watch 429/5xx under TILE_CONCURRENCY=3. |
| 7 | RESOLVED 09-26 | Debug-keystore stability — contradiction reconciled. Root cause: debug signing reads container `/home/vscode/.android/debug.keystore`; a build WITHOUT `yomihon-android-home` mounted at `/home/vscode/.android` silently signs with a stale baked key (`1a6fbe75...`), while installed/known-good = volume keystore `e486ea51...8968` (alias `androiddebugkey`, created 2026-08-22). FIX/PROCEDURE: always mount `yomihon-android-home:/home/vscode/.android` for debug builds; verify APK cert (`apksigner verify --print-certs`) vs installed `app.yomihon.dev` before `install -r`. 2026-09-26 install -r Success with correct key, data preserved. Reconciles the 08-24 (stable volume key) vs 09-15 (rotated) records. Never uninstall without .tachibk restore. |
| 12 | OPEN LOW | GlanceAppWidget "LocalContext not present" (non-fatal, pre-existing, unrelated). |
| — | TODO | Voyager settings double-push race: SaveableStateHolder 'transition used multiple times' crash on blind-tap chaos (2026-09-15); guard push if screen==stack top. |
| — | WATCH | Glens HTTP 502 has no client retry on scan path (recognizeText falls back fast engine; scan does not). NetworkOnMainThread in remote page-list resolve was found in verify2 analysis — prefetch spam fixed; re-check if scan failures recur. |

Resolved/fixed families (detail in session-logs.md): advance-confirm timeouts (findFirstVisibleItemPosition), duplicate scans + recycle crash (single-flight + task-owned bitmap), prefetch failure escalation (reportFailure gate), exclusion toggle param-swap, matcher NFKC/token-concat, ZONE pure-rect semantics, WebtoonTransitionHolder 184.7MB leak (detach()), nav-translucency OFF→0f, ManageFeeds key identity, frosted missing color slots.

## Technical debt

- UnavailableDetOcrEngine stub (TODO upstream) — see issue #1; scanLocally/cropBitmap ~60 lines unreachable, deletion deferred with det-engine ceiling.
- No unit tests for repos/download/network/UI (house-wide, pre-existing); androidTest OcrRepositoryImplTest @Ignore (device+models).
- Screen-level 16dp paddings bypass token API (~91 sites) — cosmetic debt only.
- Phrase split across two regions un-excluded (per-region matcher by design v1); mid-page rule adds apply next page.

## Dependencies

Key versions in architecture.md §1/§14: Kotlin 2.4.0, AGP 9.2.1, Compose BOM 2026.06.01,
SQLDelight 2.3.2, OkHttp 5.4.0, Injekt, JUnit5/Kotest/MockK, LiteRT 2.1.6. New lib → rules.md §5
checklist + record justification here.

**2026-09-26 — ADDED `com.microsoft.onnxruntime:onnxruntime-android:1.30.0`** (rules §5 record,
PP-OCRv5 phases 1-3). Apache-2.0, actively maintained, minSdk 21 so minSdk 26 is fine, on Maven
Central, ships consumer proguard rules.
1. *Does the repo already provide it?* No. LiteRT 2.1.6 **cannot** load the models: `libLiteRt.so`
   is a TensorFlow Lite build with no ONNX parser, and no TFLite PP-OCRv5 exists at all. There is
   no third option short of converting the ONNX offline (no ML toolchain on this machine, and no
   host to serve the converted file from).
2. *Stdlib / platform?* No — there is no platform ONNX runtime on Android.
3. *Already in the catalog?* No. Added as `onnxruntime` in `gradle/libs.versions.toml` +
   `implementation(libs.onnxruntime)` in `data/build.gradle.kts` (the only module that needs it).
4. Compatible with the toolchain: verified by `javap` against the AAR before any code was written.
5. Maintained: current release line, 1.30.0.
6. License: Apache-2.0, compatible.
**Cost to accept: `libonnxruntime.so` is 32.3 MB on arm64-v8a (23.4 MB armeabi-v7a, 39.5 MB
x86/x86_64) — about +32 MB per arm64 APK.** The PP-OCRv5 weights themselves are still 0 bytes of
APK (on-demand download to `filesDir`). Telemetry needed a second fix — see the next entry.
`com.microsoft.onnxruntime:onnxruntime` (the desktop JVM artifact) was considered for a JVM
benchmark and rejected: it is a different artifact from the shipped one, needs the 12.7MB models
present in CI, and measures x86 rather than the phone.

**2026-09-27 — ORT TELEMETRY: `setTelemetry(false)` IS NOT ENOUGH, REMOVE THE PROVIDER.**
`ai.onnxruntime.TelemetryInitializer` is a **ContentProvider merged from the ORT manifest**
(`android:authorities="${applicationId}.onnxruntime_telemetry_initializer"`, `initOrder=100`), so it
runs at every app start from `ActivityThread.installContentProviders` — i.e. **before
`Application.onCreate`**, which is why calling `OrtEnvironment.setTelemetry(false)` from the engine
constructor cannot prevent it (that code only runs if a local scan is ever attempted, possibly
never). Proven three ways: `javap` on the AAR (`System.loadLibrary("onnxruntime")` then
`HttpClient(applicationContext)`, which reads `android_id` + `Build.MANUFACTURER` + `Build.MODEL`,
calls `ConnectivityManager.registerDefaultNetworkCallback` and registers a `battery_low` receiver,
and exposes `executeTask` for HTTP), the merged manifest, and a live device log line showing
`HttpClient.<init>` <- `TelemetryInitializer.onCreate` <- `installContentProviders` <-
`handleBindApplication`. The `.so` also carries the 1DS stack and the string *"Android telemetry is
unavailable because the 1DS Java HttpClient was not initialized"*. **Fix: a `tools:node="remove"`
block for that provider in `app/src/main/AndroidManifest.xml`** — this removes the initializer
rather than disabling it, so there is no device fingerprint, no network callback, no battery
receiver and no HTTP client at all. `setTelemetry(false)` is deliberately kept as a second layer.
**Durable rule: a library that ships a startup ContentProvider cannot be neutralized by a runtime
API call, only by manifest removal.** This is the mirror image of why the LeakCanary installers in
this app's provider list are kept deliberately.

## Testing status

- Unit: full `testDebugUnitTest` green per batch (latest 2026-09-15: UpdatesGroupingTest 7/7, NavTabTest 9/9, OcrExclusionMatcherTest 35, AppBackgroundGradientTest 4).
- Device: v0.5.4.1 smoke PASS 2026-09-13; 2026-09-15 morning batch DEVICE VERIFIED (SM_M066B wireless); YOMUCHU batch 2 device pass PENDING (keystore signature blocker — issue #7).
- Device env: SM_M066B Android 16 arm64; wireless adb `./scripts/adb-wireless connect`; use on-device logcat capture (streaming adb logcat dies on blip); full unfiltered capture to disk, no `--pid` pinning, no `logcat -c`.
- Release builds: `release.yml` is fork-gated (`github.repository == 'yomihon/yomihon'`) → releases MUST be built + published LOCALLY (docker, both volumes, -Xmx4g). If root-owned build dirs fail release packaging: one-off root container `chown -R 1000:1000 /workspace`.

---

## Important verification conventions

1. **Full gates order** (CI source of truth):
   ```bash
   ./gradlew spotlessCheck              # ktlint gate
   ./gradlew testDebugUnitTest          # unit tests
   ./gradlew verifySqlDelightMigration  # required after any DB schema change
   ./gradlew assembleRelease -Pinclude-telemetry -Penable-updater
   ```

2. **Single test**: `./gradlew :app:testDebugUnitTest --tests "SomeClass.method"` (swap module as needed). Use `testDebugUnitTest` (build types: debug, release, foss, preview, benchmark — NOT flavors).

3. **Local dev container**:
   ```bash
   docker run --rm -u vscode -v "$PWD":/workspace -w /workspace vsc-yomihon-image \
     bash -c 'GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx4g" ./gradlew spotlessCheck :app:compileDebugKotlin'
   ```
   Pass `-Xmx4g` explicitly (default `-Xmx2560m` OOMs during packaging in 7.4 GiB container). Mount BOTH volumes: `yomihon-gradle-home`, `yomihon-android-home` at `/home/vscode/.android`.

4. **JDK**: CI pins 21 (`.github/.java-version`); Gradle toolchain compiles with 17. Both fine for local gates.

5. **Device verification**: SM_M066B (Android 16, arm64, wireless 192.168.29.98:5555). Use `app-arm64-v8a-debug.apk` from app/build/outputs/apk/debug/. Restore `.tachibk` backup after signature mismatch.

6. **Artifacts**: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`. Build outputs: APKs, .tachibk, screenshots in `.device-pass/` (per-session logs, .png, device.py/measure.py probes).

---

## Release/version conventions

- **Version naming**: v0.5.x (major.minor.patch). Debug suffix = commit count from v0.5.x base (0.5.4-8288 = 288 commits from v0.5.4).
- **Version code**: increment integer (v0.5.4 → 30, v0.5.4.1 → 31).
- **Tag format**: `v0.5.4`, `v0.5.4.1` etc.
- **Build types**: `assembleDebug` for local builds; `assembleRelease` with `-Pinclude-telemetry -Penable-updater` for CI parity.
- **APKs**: 5 ABI builds (arm64-v8a, armeabi-v7a, x86_64, x86, maybe riscv64).

---

## Current implementation context

- **Phase 10B backlog**: Per-voice rate/pitch tuning, JP opt-in preflight, libLiteRt GPU-lib exclusion, cross-tile seam merge, Glens-ordering port to local scan (per-phase.md).
- **Deferred**: True backdrop blur, Liquid background, tap-zone editor, dictionary history/favorites, Anki screenshot capture (see implementation-roadmap.md §G, §F).
- **Rejected**: Navigation/tab customization, 6th tab, standardized reselect, Browse Search tab, Library Continue, screen-OCR lookup, local OCR reinstatement (§F).

---

## Known unresolved decisions

- **Create-tab referent** (U-1): user referenced a nonexistent tab. Build nothing until clarified.
- **U-5** recursive dictionary lookup design approval (Q3 — HARD HALTED, informational only).
- **U-6** DEBUG/INFO TTS+OCR timing logs in release: keep (3 INFO lines, rules §7 compliant) or downgrade. OPEN.
- **U-7/U-8/U-9**: RESOLVED 2026-09-13 via BUG-008/009/010 fixes (roadmap §H table text stale — noted, not rewritten).
- **Device pass** for the committed 2026-09-15 batches: PENDING user (blocked by keystore issue #7 contradiction).

## 2026-09-25 — TTS resiliency + dynamic prefetch batch (3 fixes)

**Authorization:** user master prompt (09-25, post-verification). Three tasks in one batch.

### Fix 1: `GlensOcrEngine` per-tile retry + reduced read timeout
- `READ_TIMEOUT_MS` 60s → 12s (matches GLENS p95=10.9s; timeout now fires before server stall).
- New `executeRequestWithRetry(payload, scanId, tileTop)`: up to 2 retries on `IOException`
  (covers socket timeout + HTTP 5xx/429 thrown by `executeRequest`). Logs
  `[GlensOcrEngine] Tile X retry Y/2 due to Z` at WARN.
- `recognizeTile` now calls `executeRequestWithRetry` instead of `executeRequest` directly.
- `recognizeSingle` (non-tiled) unchanged — single-request path has no partial-failure risk.
- CancellationException rethrown (no retry on user cancel).
- Closes WATCH item 1 from S1-S5 device verification (500 no-retry absorbed by `awaitAll`).

### Fix 2: `TtsPlaybackController` hard OCR timeout guard
- `acquireSentences`: `scanOnDemand` now wrapped in `withTimeoutOrNull(OCR_ACQUIRE_TIMEOUT_MS = 8000)`.
  On timeout (or scan failure), `fail(TtsError.OcrError)` is called and null returned;
  `runPlayback` exits the loop gracefully instead of wedging in `LoadingPage`.
- `pause()`/`stop()` already cancel `playbackJob`/`prefetchJob` via `resetSession`;
  no phase-guard blocking identified — timeout guard is the only missing piece.
- `OCR_ACQUIRE_TIMEOUT_MS` = 8s chosen: GLENS p95 per-tile = 10.9s but a full page
  (4 tiles in parallel) typically completes in 5–8s; 8s is generous.

### Fix 3: Dynamic sentence-budget prefetch
- `schedulePrefetch` refactored: now `suspend` (calls `getCachedPageOcr.await` to estimate
  sentence count before launching the job). Call sites: `start()` wraps in `scope.launch`,
  `runPlayback` already in a coroutine.
- New constants: `TARGET_SENTENCE_BUFFER = 40`, `MIN_SENTENCES_PER_PAGE = 5`,
  `MAX_PREFETCH_DEPTH_EXTENDED = 6`.
- Budget loop: iterate from `startPageIndex` up to `min(startPageIndex + 6, totalPages)`,
  accumulating `cached?.regions?.size ?: MIN_SENTENCES_PER_PAGE` per page; break when
  `accumulated >= TARGET_SENTENCE_BUFFER`. This naturally extends lookahead for
  text-light pages (few sentences) and stops early for text-heavy pages.
- `prefetchPages` type changed `IntRange?` → `ClosedRange<Int>?` (buildList + first/last).
- `prefetchDepth()` and `MAX_PREFETCH_DEPTH` removed (replaced by sentence budget).
- Prefetch still dispatches at `OcrScanPriority.NORMAL` (via `scanOnDemand` `reportFailure=false`
  path); does not block active page speech (HIGH priority for current page is unchanged).

### Gate results (docker -Xmx4g, both volumes)
- `spotlessCheck` BUILD SUCCESSFUL (after `spotlessApply` for import order + formatting)
- `testDebugUnitTest` BUILD SUCCESSFUL (all tests pass)
- `:app:assembleDebug` BUILD SUCCESSFUL

### Design decisions
- `TARGET_SENTENCE_BUFFER = 40`: midpoint of user-specified 30–50 range. At 1x speech rate
  one page of dialogue (~15 sentences) takes ~10–15s; 40 sentences ≈ 2–3 pages of runway,
  matching the old 2–3 page depth for typical pages while extending for text-light pages.
- `MIN_SENTENCES_PER_PAGE = 5`: conservative lower bound so uncached pages don't inflate
  the budget estimate; if a page is truly empty the scan returns 0 regions and the next
  acquireSentences handles it.
- `MAX_PREFETCH_DEPTH_EXTENDED = 6`: hard cap prevents unbounded lookahead even if all
  pages are text-light (e.g. action manga with few bubbles).
