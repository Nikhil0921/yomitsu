# Yomitsu — State File (Startup Memory)

> COMPACT STARTUP STATE. Read FIRST. Answers: "Where is Yomitsu right now?"
> Deep context: rules.md (protocol) · memory.md (recent+durable) ·
> implementation-roadmap.md (authorization) · history/session-logs.md (archive, on demand only).

---

## Current state

```text
Project:        Yomitsu — Android manga/comic reader, OCR + Read-Aloud TTS, dictionary/language tooling
Version:        0.5.4.2 / versionCode 32 (app/build.gradle.kts single source of truth)
Latest release: v0.5.4.2 (tag 20f4eb746, published 2026-09-20, GitHub Latest, 5 ABI APKs)
Branch/HEAD:    main @ 8732ed49d — Recent-tab display sheet + sub-tab toggle
                  (reverted the Phase 2 M2 category-chip misconception); Phase 2
                  M1/M3/M4 still shipped; prefetch + Phase-2 commits local (not pushed)
Working tree:   dirty (uncommitted) — 2026-09-26 A–D cold-start fixes +
                 2026-09-25 F1–F3 + S1/S2/S3/S5 batches; docs updated
Current phase:  Phase 2 Yomitsu UI refinement — M2 corrected to display-sheet
                  pattern (showRecentTabs pref + RecentDisplaySheet + tab-row gate)
Last gates:     2026-09-26: spotlessCheck + :app:testDebugUnitTest +
                 :app:assembleDebug all BUILD SUCCESSFUL (docker -Xmx4g,
                 ~/.gradle mounted to /home/vscode/.gradle); no DB change
Last gates:     2026-09-21: spotlessCheck + :app:testDebugUnitTest +
                 :app:assembleDebug all BUILD SUCCESSFUL 3m48s (docker -Xmx4g,
                 both volumes); no DB change
Last release:   2026-09-20 v0.5.4.2 released locally (docker -Xmx4g, both volumes):
                 gates green 5m08s (spotless+test+verifySqlDelightMigration),
                 assembleRelease 19m36s, APK cert SHA-256 verified = e486ea51...8968,
                 5 ABI APKs pushed to GitHub (Nikhil0921/yomitsu)
Last gates:     2026-09-20 all green (see last release line)
Last device:    2026-09-26 READ-ALOUD RESILIENCY VERIFICATION PASS (SM_M066B) — two runs on
                  the queue/prefetch + exclusion-matcher build.
                  (A) QUEUE/PREFETCH — .device-pass/logcat-20260926-192424.log, cold uncached
                  ch8807, OcrScanJob scanning ch8806 throughout (real contention):
                  HIGH queue wait p90 = 1 ms (baseline 9233 ms), HIGH worst = 1 ms
                  (baseline max 17169 ms); duplicate enqueues 0/17 = 0% (baseline 56/193 =
                  29%); cold prefetch lookahead 1 page (baseline 6) — `pages=1..1` then
                  `pages=2..2`; prefetch starts/skip 2/1 (baseline 51/1, dedup now fires);
                  cold start→first speech 1.64s; OcrError 0, postUploadWaitMs=0, 0 crashes.
                  (B) EXCLUSION MATCHER — .device-pass/logcat-20260926-202430.log, ch8805,
                  43 pages, 16 exclusion zones/page, 173 dispatches:
                  exclusion match median 7.6 ms, p90 17.8 ms, max 74.0 ms, min 0.5 ms
                  (n=30) — the same heavy page that cost 12 258 ms on the previous build now
                  costs 5.6 ms. Share of acquireSentences 0.1% (was 76.9%). Unit benchmark
                  427 ms → 2.04 ms (209x). Cold start→first speech 2.92s (target <6s);
                  OcrError/acquisition-null 0, 0 crashes.
                  REMAINING BOTTLENECK IS NETWORK, NOT CODE: median acquire is now 91.2%
                  `scanPageOcr await` (7229.6 ms) + 6.0% image download (476 ms); exclusion
                  match is 0.1%. GLENS itself healthy: 510 uploads, median 2670 ms, max
                  6619 ms, postUploadWaitMs=0. Uncached-page acquireMs is still 5-12s
                  because that is genuine remote inference.
                  Prior: 2026-09-26 A–D cold-start PARTIAL PASS (logcat-20260926-133534.log,
                  ch3285 p0, cold start→speech 5.97s, OcrError 0; CAVEAT contended shape not
                  reproduced). 2026-09-25 S1+S2+S3+S5 PASS (on-device-capture.log 78.5 MB;
                  S2 OcrScanJob ×3, S3 8× warm-skip 0ms vs 257ms, S5 759/760 HTTP200
                  cold-batch p50 546ms, S1 HIGH zero-wait ×2; 1× HTTP 500 no-retry = WATCH).
                  2026-09-19 STAGE 4L PASS (ch8447 p0–15, 160×HTTP200).
Last device:    2026-09-28 STAGES 2-5 PARTIAL DEVICE VERIFICATION (0.5.4.2-8316, cert
                  e486ea51…8968, `install -r` Success, firstInstallTime 2026-09-16 intact).
                  Capture /sdcard/Download/logcat-20260928-011944-s25.log (75 MB), boundary
                  `YOMI_S25_CAPTURE_START` at line 303929 (06:49:49). Device clock is now +5h30m
                  from host. Read only from the marker. OCR model = ADAPTIVE, 19 pages read aloud,
                  0 crashes.
                  **PROVEN.** (1) Stage 5 instrumentation works, and immediately earned itself:
                  `shortRegions` is now a per-page measurement, 12 WARN lines fired, and the R7
                  defect is quantified for the first time — 8 of 19 spoken pages carry single-glyph
                  regions (page 1 = 8/9, page 11 = 7/15, page 18 = 6/10). That is the "c-h-a-n-g-e-s"
                  symptom measured instead of inferred. (2) **Stage 2's LRU memo is proven**:
                  `OCR resolveRemote memo hit … cached=2` (7×) — two chapters memoized at once,
                  which the single-slot memo could not do. (3) Stage 1's advance-not-die behaviour
                  held: TTS walked pages 5→9 and paused cleanly.
                  **SHORTFALL, measured, not guessed.** Stage 3's line grouping is INCOMPLETE:
                  square `in=48x48` recognition crops are 84/514 = **16.3%**, down from 24.6% but not
                  eliminated, and the per-page shortRegions above are the residue. The merge is
                  firing (many 56-320px wide boxes) but stops at some glyph boundaries; the most
                  likely cause is that both the gap and the overlap test use
                  `minOf(a.height, b.height)`, so a descender/ascender pair shrinks the threshold
                  below the real gap. One-token change, NOT made without sign-off. Note the measure
                  can only improve the LOCAL engine — escalated pages are spoken from cloud text.
                  **Stage 4's retry policy does not cover the real failure mode.** `cloudPage` now
                  inherits the one-retry policy, but `isTransientHttpFailure` matches only messages
                  containing "HTTP 5" or "HTTP 429", and the 15 network failures observed were 13
                  `SocketTimeoutException: timeout` + 2 `ConnectionError` — so **neither retry line
                  ever fired**. 45 GLENS tile responses completed and 0 pages produced a result: the
                  escalations were network-starved (13 tiles/page at 4 concurrency, 12 s read
                  timeout). Wiring the escalation into a policy is only half the fix.
                  **STILL UNEXERCISED:** Stage 2's bounded waits, Stage 3's seam dedupe (no GLENS
                  page completed), Stage 4's model-scoped probe and engine lock.
Last session:   2026-09-27 OCR PIPELINE STAGES 1-5 COMPLETE AND COMMITTED (user-authorized,
                  "AUTHORIZED" + "PROCEED WITH STAGES 2 THROUGH 5"). 5 commits, tree clean:
                  74b17a463 Stage 1 code, ec68eb613 Stage 1 docs, 134473c80 Stage 2,
                  93b091b39 Stage 3, f041dfa68 Stage 4, e31953d4d Stage 5. **GATES after EVERY
                  stage: `spotlessCheck testDebugUnitTest :app:assembleDebug
                  verifySqlDelightMigration` all BUILD SUCCESSFUL. 546 tests, 0 failures** (was 512
                  at the start of this work: +34). No DB schema change anywhere in Stages 1-5.
                  STAGE 2 (bounded waits): `openBitmap()` and the page-list resolve now run under the
                  per-page budget; the page loop moved into `scanPages()` because
                  `WithOcrScanSession.await` is not inline and has no non-local return;
                  `PrioritizedTaskQueue` parents each task to its submitter's job so cancelling the
                  caller cancels the work (kills the 29% duplicate-scan source);
                  `OcrPageSourceResolver`'s single-slot page-list memo is a 4-chapter LRU.
                  STAGE 3 (speech): `dedupeTileSeamDuplicates` is text-aware, killing both the
                  duplicate-word seams and the false deletions (`DUPLICATE_IOU_THRESHOLD` removed);
                  `PpOcrDbPostprocess` groups same-line components BEFORE unclipping — unclipping
                  first grows neighbouring glyphs until they overlap, so the "same line" gap is
                  gone by then — with the flood-fill contract preserved in a new `components()`;
                  `OcrQualityRouter` gained `singleCharRegionRatio` because `MIN_CHARS_PER_REGION`
                  is a page MEAN and structurally cannot see a glyph-fragmented page.
                  **STEP 14 DELIBERATELY NOT DONE** (GLENS hard-coded `ja`/`Asia/Tokyo`): the plan
                  requires a device capture of Japanese-mixed English pages and neither available
                  capture has one. STAGE 4 (engine hygiene): `getCachedChapterIds` is model-scoped
                  like `getPage`; engine construction is behind a leaf `synchronized` lock; the
                  adaptive escalation goes through `cloudPage` and inherits the one-retry policy;
                  `pref_ocr_model` has one default (GLENS) in both places. **Stage 4 has NO unit
                  tests — `OcrRepositoryImpl` and `OcrCacheStore` need a real Context and SQLite, so
                  that stage is verified by compilation, the full suite and device checks, NOT by a
                  red-green cycle.** STAGE 5 (observability): five log lines, including
                  `shortRegions` in the segmented-speech line (the "c-h-a-n-g-e-s" signal that was
                  invisible until 1098 recognition widths were mined out of a 302 MB logcat), a clean
                  scan's page count, the ERROR transition, the interruption, and scanAdaptive's cache
                  write. One Stage 5 test passed on its FIRST run — a guard, not a red-green cycle.
                  **STILL PENDING: device re-verification of Stages 2-5.** Only Stage 1 step 3 is
                  device-proven. Everything else is compile + unit-test evidence.
                  Q8 is closed and out of scope; do not re-verify or report it.
Last device:    2026-09-27 STAGE 1 STEP 3 DEVICE-VERIFIED on SM_M066B
                  (0.5.4.2-8311, cert e486ea51…8968, `install -r` Success, firstInstallTime
                  2026-09-16 intact = no data loss). Capture
                  /sdcard/Download/logcat-20260927-174019-stage1.log (63 MB), boundary marker
                  `YOMI_STAGE1_CAPTURE_START` at line 253303 (23:10:22.686) — read only from
                  there. **THE TEST WAS HARD: THE DEVICE HAD NO DNS.** 128x
                  `UnknownHostException: Unable to resolve host
                  "lensfrontend-pa.googleapis.com"`, 62x `SocketTimeoutException: timeout`,
                  every GLENS tile failing all 3 attempts. So EVERY OCR in the run failed.
                  **RESULT: 9 consecutive OCR failures inside ONE session, and the session walked
                  pages 1→2→3→4→5→6→7→8→9 instead of dying on page 1.** `TTS OCR unavailable for
                  page=N chapter=4561; treating the page as having no text` ×9, each followed by
                  `TTS page advance request target=N+1` + `advance confirmed`. **0 × OcrError,
                  0 × `OCR timeout page=` (the old line that lied about the code), 0 crashes.**
                  The pre-fix build died 3× on the SAME page in 5 min with zero progress
                  (logcat-20260927-run2.log ch4563 p1); this build lost the whole chapter's speech
                  but kept the session alive and let the user keep going.
                  NOT EXERCISED BY THIS RUN (honest gaps): **no `OcrChapterScanner` ran at all**
                  (0 occurrences) because the network never came up, so **steps 1+2 (the cache
                  wipe) and steps 4+5 (ERROR retry / startIfPending) are still UNVERIFIED on
                  device** — code-verified and unit-tested only. Re-run with the network up.
                  Also measured under total outage: OCR queue wait HIGH p50 62s / max 113s,
                  NORMAL p50 348s — non-preemptive cap-3 with 100s+ doomed uploads, i.e. the queue
                  behaving as designed, not a new stall. 3 pages did speak; cold start→first speech
                  113ms cached / 9815ms cold.

Last session:   2026-09-27 OCR PIPELINE STAGE 1 COMPLETE (steps 1-6, user-authorized,
                  NO COMMIT). Steps 2-5 of the audit's 19-step plan REMAIN UNAUTHORIZED. GATES (docker vsc-yomihon-e24e3bd7, -Xmx4g, BOTH
                  volumes): `spotlessCheck testDebugUnitTest` = **BUILD SUCCESSFUL 3m01s —
                  523 tests, 0 failures** (all modules); `:app:assembleDebug
                  verifySqlDelightMigration` = BUILD SUCCESSFUL 3m07s. No DB change. No commit.
                  TEST-FIRST THROUGHOUT: every step's test was run red first, then green.
                  (1) **R1 CACHE WIPES DELETED** — `OcrChapterScanner` no longer calls
                  `clearCachedChapterOcr` at scan start (`:69`), on a mid-chapter network abort
                  (`:105`), or in `handleUnexpectedFailure` (`:209`), and no longer emits the
                  `onCacheStateChanged(chapterId, false)` lies that accompanied them. The chapter's
                  accumulated OCR is now only ever added to. `handleUnexpectedFailure` lost its
                  unused `onCacheStateChanged` param. **The ctor keeps `clearCachedChapterOcr`
                  injected (@Suppress("unused")) ON PURPOSE** — dropping it made the 3 new
                  `coVerify(exactly = 0)` guards vacuous (a green test that tests nothing; see
                  memory RULE 3). +4 tests: scanDoesNotWipeTheOcrCache,
                  failedPageDoesNotWipeAlreadyScannedPages,
                  networkAbortMidScanDoesNotWipeAlreadyScannedPages,
                  cacheEventIsNotResetToFalseAtScanStart.
                  (2) **R2 SESSION NO LONGER DIES ON ONE BAD PAGE** — `acquireSentences` logged
                  *"advancing gracefully"* and then called `fail(TtsError.OcrError)`, i.e. phase =
                  Error and `runPlayback` returns. Now an unobtainable page returns `emptyList()`,
                  so the existing `pageHasText = false` branch of `TtsAdvancePolicy` moves on;
                  the last page still ends as `Finished` + `Failed(NoTextFound)`. `scanOnDemand`'s
                  `if (reportFailure) fail(OcrError)` on OOM/Exception is gone and with it the
                  now-meaningless `reportFailure` parameter and its 2 call sites. +2 tests:
                  failedPageAdvancesInsteadOfFailingTheSession,
                  failedLastPageFinishesAsNoTextFound.
                  (3) **ERROR HAS A BOUNDED RETRY BUDGET** — new `OcrScanQueueEntry.attempts`
                  (persisted via `PersistedOcrScanQueueEntry.attempts`, defaulted so older JSON
                  still decodes — this is a SharedPreferences JSON blob, NOT a SQLDelight schema,
                  so no `.sqm`/verifySqlDelightMigration applies, and none was needed).
                  `OcrScanManager.requeueFailedEntries()` moves `ERROR` entries back to `QUEUED`
                  while `attempts <= AUTOMATIC_RETRIES (3)`, and `OcrScanJob.doWork` returns
                  `Result.retry()` when it re-queued anything. Retries happen BETWEEN worker runs
                  (WorkManager backoff), never inside `runPendingQueue`'s loop, so one run still
                  attempts each chapter exactly once. `resume()` resets `attempts = 0` — a manual
                  retry is a fresh, unlimited budget. **SPEC CHANGE: the pre-existing test
                  `runPendingQueueDoesNotAutoRetryFailures` asserted the old terminal-ERROR
                  intent BY NAME and was rewritten** to
                  `runPendingQueueScansEachChapterOnceAndNeverLoops` (it now pins "one attempt per
                  run, no spin") plus `failedChapterIsAutomaticallyRetriedUpToTheBudget` and
                  `manualResumeRestoresTheAutomaticRetryBudget`; the audit is the evidence that
                  the old intent was wrong. Flagged to the user. 2 existing
                  `entry(..., lastError=...)` expectations gained `attempts = 1`.
                  (4) **`startIfPending()` NOW HAS CALLERS** — it had ZERO, which is why a
                  restored `QUEUED` entry (SCANNING remapped on process death) or one enqueued
                  while a worker was finishing (`enqueueUniqueWork(KEEP)` drops that request) sat
                  with nothing to run it forever. Called from `OcrScanManager.init` (skipped when
                  paused) and from `OcrScanJob.doWork` on the success path. +2 tests:
                  aPersistedQueuedEntryStartsAWorkerOnConstruction,
                  aPausedQueueStartsNoWorkerOnConstruction.
                  (5) **STAGE 1 STEP 6 LANDED — user sign-off 2026-09-27 under rules §10.**
                  `OcrChapterScanner` no longer returns `true` with `skippedPages > 0`: it now
                  reports the holes and returns false, so `OcrScanManager` keeps the entry (as
                  ERROR) instead of deleting a chapter that is not actually scanned. New
                  `OcrScanFailure.PagesSkipped(skipped, total)` + base string
                  `ocr_preprocess_pages_skipped` ("%1$d of %2$d pages scanned, the rest will be
                  retried"); the notifier's `toMessage()` needed `when (val failure = this)` to
                  bind it. The retry loop is unchanged on purpose — it still attempts EVERY page, so
                  one bad page does not abandon the rest. Combined with the retry budget this is
                  self-healing and cheap: a retry re-runs only the missing pages, because
                  `OcrRepositoryImpl.scanPage` checks the cache first and R1 no longer wipes it.
                  TWO EXISTING TESTS RE-SPECIFIED UNDER EXPLICIT §10 SIGN-OFF, renamed not
                  deleted: `failedPageIsSkippedAndScanContinues` ->
                  `failedPageIsSkippedButScanIsNotReportedComplete`,
                  `timedOutPageIsSkippedAndScanContinues` ->
                  `timedOutPageIsSkippedButScanIsNotReportedComplete`. Both now assert
                  `ok == false` + `PagesSkipped`, and both still assert every page is attempted —
                  the "scan continues" half of the original intent is preserved. +2 new tests:
                  `partialScanReportsFailureInsteadOfClaimingSuccess`,
                  `completeScanStillReportsSuccessAndNoError`. One stale test assertion of my own
                  was corrected: `failedPageDoesNotWipeAlreadyScannedPages` used
                  `onError = { throw AssertionError(...) }`, and now that a partial scan legitimately
                  reports, throwing from `onError` was caught by the scanner's own catch-all and
                  turned into a nested `Unexpected` — it collects the errors instead.
                  **Stage 1 is COMPLETE (all 6 steps).** GATES: `spotlessCheck testDebugUnitTest
                  :app:assembleDebug verifySqlDelightMigration` = **BUILD SUCCESSFUL 4m30s —
                  525 tests, 0 failures** (was 523, +2). No commit.
                  **STAGES 2-5 REMAIN UNAUTHORIZED.**
                  **DEVICE: step 3 VERIFIED 2026-09-27 (see `Last device` above); steps 1/2/4/5
                  still unverified on device** because that run had no network and no background
                  chapter scan ever started.
Last session:   2026-09-27 READ-ONLY FULL OCR PIPELINE AUDIT
                   (docs/audits/full-ocr-pipeline-audit.md). No source changes, no gates, no commit.
                   4 areas: queue/prefetch deadlocks, TTS letter spelling, duplicate words +
                   exclusion matching, engine fallback/recovery. **ONE ROOT CAUSE BEHIND 3 OF THE 4
                   SYMPTOMS: `OcrChapterScanner` wipes the chapter's whole OCR cache three times**
                   (`:69` scan start, `:105` network abort, `:209` handleUnexpectedFailure), and
                   `deleteChapterPages` is model-blind. So an ERROR chapter has ZERO cached pages and
                   `resume()` re-wipes it. DEVICE PROOF ch4563 p1, three consecutive session deaths
                   19:28:04 / 19:30:03 (24.9 s await → `OcrException$ConnectionError` → wipe) /
                   19:32:51 (restart → `TTS OCR cache miss` → 29.3 s → dead). ch8936 p2 same at
                   17:47/17:48/17:49. `ERROR` is terminal: `runPendingQueue`→`Result.success()`,
                   `startIfPending()` (:45) has ZERO call sites, ERROR survives process death and is
                   hidden from `remainingChapterCount`. **"c-h-a-n-g-e-s" = REGION COUNT, not spaces:**
                   `mergeSpacedSingleLetters` is per-region and cannot merge a 1-char region; DB
                   emits per-component boxes, `minSideFraction=0.003` lets a lone glyph through,
                   width clamps to 48 and `normalize()` stretches it square — **270 of 1098 rec calls
                   measured `in=48x48` (24.6%)**; `SentenceSegmenter` may not merge across regions, so
                   7 glyphs = 7 `speak()` calls. `OcrQualityRouter.MIN_CHARS_PER_REGION` is a PAGE
                   MEAN so a glyph-fragmented page passes. **Duplicate words = tile seams:** 13
                   tiles/page at 20% overlap (tiled=true 20/20 pages), dedupe is IoU≥0.45 with NO
                   text comparison (measured in=47→40, in=47→37, in=37→29 = partial). ALSO: the
                   `acquireSentences` log says "advancing gracefully" then calls
                   `fail(TtsError.OcrError)` (hard kill — log is wrong about the code);
                   `openBitmap()`/`resolve()` unbounded while the 90 s guard DETACHES not cancels
                   (`PrioritizedTaskQueue.kt:163`) → 16 pages × 90 s = 24 min; a partial scan
                   returns `true` and is PINNED BY 2 EXISTING TESTS; `getCachedChapterIds` not
                   model-filtered (model switch ⇒ every chapter looks cached ⇒ prefetch skipped);
                   engine construction is an unlocked `?:` ⇒ duplicate ORT sessions that
                   `closeEngines()` never frees; ADAPTIVE escalation bypasses the one-retry policy;
                   `pref_ocr_model` has TWO defaults (`OcrPreferences.kt:15` GLENS vs
                   `OcrRepositoryImpl.kt:41` LEGACY — 09-26 fixed the wrong one). CONFIRMED
                   NOT-BUGS: no lock inversion anywhere, `HybridOcrEngine` holds no mutex across the
                   cloud call, `OcrExclusionMatcher` still 7.6 ms median / 0.1% of acquireSentences,
                   exclusion zones are user-authored only so headers/footers are not its job.
                   11 root causes mapped, 19-step remediation plan in 5 stages, 7 test gaps listed.
                   NOT ESTABLISHED: R8 frequency (JP locale reorder) needs a JP-mixed capture; the
                   89.7% escalation rate may itself be a SYMPTOM of the glyph-box defect — a second
                   reason not to lower the 0.80 floor yet.
Last session:   2026-09-27 ADAPTIVE HYBRID ESCALATION VERIFIED — final numbers, 68 real pages.
                  GATES: `spotlessCheck testDebugUnitTest` = **BUILD SUCCESSFUL 3m16s — 512 tests,
                  0 failures**. No DB change. No commit. **GLENS remains the code default**
                  (`OcrPreferences.ocrModel()` untouched); PPOCR/ADAPTIVE are opt-in.
                  MEASURED (post-fix window only, i.e. from the first `init ok` at 17:45:40 —
                  everything earlier in the capture is the retained ring buffer from the PRE-fix
                  process, pid 19017, and is excluded):
                  **pages 68 · local accepted 7 (10.3%) · escalated to cloud 61 (89.7%) · local
                  engine failures 0. ESCALATION RATE = 61/68 = 89.7%.** The `OcrTensorSize` fix is
                  confirmed on hardware: **1094 recognition calls, all with height exactly 48**
                  (27 distinct widths from 48 to 320); before the fix every call was `in=48x48` or
                  threw. `init ok detIn=x recIn=x recOut=fetch_name_0 classes=438` appears 3x, once
                  per process — single init, no re-initialization storm (was 23 attempts / 649 graph
                  optimization runs).
                  **LATENCY, post-fix, real pages under concurrent load: detection n=68 p50 = 393 ms,
                  p90 = 883 ms, p99 = 1481 ms, max 2727 ms (64x960 p50 381 ms on 57 of 68 pages;
                  960x640 p50 697 ms); recognition n=1094 p50 = 61 ms, p90 = 202 ms, p99 = 659 ms,
                  mean 100 ms.** Isolated hardware benchmark (two runs): det p50 80–110 ms,
                  p90 548–552 ms; rec p50 47–48 ms, p90 57–59 ms. **Detection misses the 250 ms
                  budget at p90 in every configuration; recognition is comfortably inside it.**
                  **WHY 89.7% ESCALATE — one constant, not a bug: `meanCharConfidence < 0.80`
                  tripped on 61 of 61 escalated pages (100%), median confidence 0.557.** Every other
                  router threshold is effectively never reached: chars/region < 1.5 on 0 pages,
                  nonLatin > 0.15 on 0, garbage > 0.30 on 0, boxH < 0.008 on 5 (8%). The 0.80 floor
                  came from the audit's *unmeasured* table, not from a measurement, and the `en`
                  recognizer simply does not reach it on real manga crops. Do NOT "fix" this by
                  lowering the constant to make the number look good: the honest next step is to
                  compare local text against GLENS text on the same pages and set the floor from
                  measured accuracy. The 7 accepted pages scored conf 0.80–0.93.
                  **FINAL ARCHITECTURAL DECISION: Online (GLENS) stays the default engine; PP-OCRv5
                  (Local Fast OCR) and Adaptive Hybrid remain opt-in choices in Settings → Studies →
                  Text recognition.** Two independent measured reasons, both from the audit's own
                  pre-registered criteria: (a) audit R1 — "an escalation rate above ~20% means the
                  local model is not earning its place and the hybrid is a pessimization"; 89.7% is
                  4.5x that threshold, so in Adaptive Hybrid the local pass is pure added latency;
                  (b) audit §5 Phase 1 kill-switch — "if detection alone is > 400 ms/page the
                  local-first thesis dies"; detection p50 is 393 ms isolated-adjacent and 788 ms
                  under the earlier heavier load, with p90 883 ms. The engine is CORRECT and remains
                  defensible as the only OCR path that needs no network; what is rejected is making
                  it the default. Any further work belongs in NNAPI (audit R4), a smaller detector,
                  or an explicitly multi-second page budget.
Last session:   2026-09-27 LATENCY METRICS EXTRACTED + BENCHMARK RUN + ONE MORE CRASH FIXED.
                  GATES (docker vsc-yomihon-e24e3bd7, -Xmx4g, BOTH volumes): `spotlessCheck
                  testDebugUnitTest` = BUILD SUCCESSFUL 3m — **512 tests, 0 failures**; `:app:assembleDebug`
                  BUILD SUCCESSFUL 2m27s, `install -r` Success, models intact in filesDir.
                  **SECOND CRASH FOUND AND FIXED (the one the logcat metric pull exposed).** The
                  user's real-content run produced `ORT_INVALID_ARGUMENT ... index: 2 Got: 192
                  Expected: 48` (also 56/64/72/…/320) on 19 pages. Index 2 is the recognizer's
                  HEIGHT, which the model pins at 48 — and the values fed were my *widths*. Root
                  cause: `PpOcrPreprocess.recognitionInputSize` returned `Pair(height, width)` while
                  `detectionInputSize` returned `Pair(width, height)`, and the engine destructured
                  BOTH as `(width, height)`. Every recognition tensor was transposed; only square
                  crops (width clamped to 48) survived by luck, which is why the earlier rec samples
                  were all `in=48x48`. FIX: both functions now return a named `OcrTensorSize(width,
                  height)` — a `Pair` cannot express which is which, so the ambiguity WAS the defect —
                  and the engine reads `size.width`/`size.height` explicitly. Confirmed on hardware:
                  the benchmark now completes **47 recognition calls** on real text crops.
                  **MEASURED LATENCY, real manga pages, concurrent app session (n=32 det, n=10 rec):
                  detection p50 = 788 ms, p90 = 1926 ms, p99 = 4009 ms, max 4009 ms; recognition
                  p50 = 243 ms, p90 = 380 ms, p99 = 569 ms.** Target is 250 ms. By input size:
                  64x960 (61k px) p50 506 ms; 224x960 (215k) 788 ms; 960x640 (614k) p50 2302 ms —
                  cost scales with input area, and 3-4 scans run concurrently on separate threads,
                  which is why real-world p50 is ~5x the isolated figure.
                  **ISOLATED HARDWARE BENCHMARK (androidTest, one page at a time, pages drawn
                  in-process so no fixture files are needed), two consecutive runs:
                  `pages=20 boxes=47 det p50=110ms p90=548ms | rec n=47 p50=47ms p90=59ms` and
                  `pages=20 boxes=47 det p50=80ms p90=552ms | rec n=47 p50=48ms p90=57ms`.
                  So detection p50 clears 250 ms in isolation but p90 is reproducibly ~2.2x OVER it,
                  while recognition (47-48 ms p50, 57-59 ms p90) is comfortably inside. The gate
                  FAILS on detection p90: `detection p90 552ms exceeds the 250ms budget (p50 80ms)`.
                  **ADAPTIVE HYBRID ESCALATION RATE = 19/19 pages = 100%**, all with
                  "local engine unavailable … index: 2 Got: …" — i.e. every escalation was the
                  transposed-tensor bug, not the quality router. Router-accepted pages: 0.
                  Quality-based escalations: 0. **THE REAL ESCALATION RATE IS UNKNOWN until a re-test
                  with the tensor fix.**
                  **VERDICT AGAINST THE AUDIT'S OWN PRE-REGISTERED KILL-SWITCH** (audit §5 Phase 1:
                  "if detection alone is > 400 ms/page, the local-first thesis dies"): under real app
                  conditions detection p50 is 788 ms = 2x the kill-switch, and even isolated p90 is
                  548 ms. **The local-first thesis does not hold at 250 ms/page on SM_M066B.** Keeping
                  the code is still defensible (it is the only offline path and it is correct now),
                  but ADAPTIVE must not be made the default and PPOCR/ADAPTIVE should stay opt-in.
                  Benchmark note: the harness previously required 20+ fixture PNGs pushed to
                  `externalFilesDir/ocr_benchmark/`, and Android's PNG decoder rejected the
                  hand-rolled fixtures, so the gate silently skipped; it now draws its own pages with
                  `Canvas.drawText`, which also means real glyphs exercise recognition.
                  Log capture note: the on-device capture died at 16:25; restarted as
                  `/sdcard/Download/logcat-20260927-run2.log`.
                  STILL PENDING: a re-test for the true escalation rate and for recognition on real
                  page crops (all rec samples so far are square 48x48 crops from the buggy build).
Last session:   2026-09-27 PP-OCRv5 CRASH DIAGNOSED AND FIXED (user-authorized, NO COMMIT) — the
                  first device run failed every local page with "recognizer exposes 0 classes".
                  GATES (docker vsc-yomihon-e24e3bd7, -Xmx4g, BOTH volumes): `spotlessCheck
                  testDebugUnitTest :app:assembleDebug` = **BUILD SUCCESSFUL 2m47s — 511 tests, 0
                  failures** (was 499, +12). Installed on SM_M066B (`install -r` Success).
                  **ROOT CAUSE = Kotlin silently downcasting an ONNX Runtime Java platform type.**
                  `rec.outputInfo[name] as? TensorInfo` — in ORT 1.30.0 `NodeInfo` is a plain
                  class and `TensorInfo implements ValueInfo`, so the correct chain is
                  `outputInfo[name]?.info as? TensorInfo`. Because Java types are *platform types*
                  to Kotlin the wrong cast COMPILES, and at runtime it is ALWAYS null, so my own
                  loud-failure guard fired with `classes = 0` and threw `InitializationError` on
                  every page. Proved offline before touching the device: javap on the AAR shows
                  `NodeInfo.getInfo(): ValueInfo` and `TensorInfo implements ValueInfo`, and a
                  host-side probe with the SAME wrong cast **fails to compile** ("NodeInfo cannot be
                  converted to TensorInfo") under plain javac.
                  Two more latent faults the same probe exposed, which would have fired the moment
                  RC1 was fixed: ORT returns the det output `[1,1,H,W]` as `float[][][][]` and the
                  rec output `[1,40,438]` as `float[][][]`, but the code assumed 2-D and 1-D. Both
                  `as?` casts erase to `Object[]` and therefore SUCCEED on the wrong shape — the
                  det path would have silently produced an all-zero probability map (zero boxes,
                  no error). And **the dict is 436 characters while the model emits 438 classes**:
                  PaddleOCR's `CTCLabelDecode` builds `['blank'] + dict + [' ']`, so class 437 is a
                  literal space. Unhandled, it was dropped, so words would have been glued together
                  ("Helloworld") even after a successful init.
                  FIXES: new pure `OcrTensorReader` (:domain) walks both nestings positionally with
                  `is Array<*>` checks and returns null on a wrong shape; `PpOcrCharset.CLASS_COUNT`
                  437 -> 438 with `SPACE_CLASS = 437` and decoder support for it; engine reads
                  metadata through `NodeInfo.getInfo()`; init failure is now STICKY (it was
                  re-running full ORT graph optimization on every page — 23 attempts, 649
                  `CleanUnusedInitializersAndNodeArgs` lines, all under the shared local-engine
                  lock, which is a real self-inflicted stall); `infer` failures and a null output
                  shape now raise a domain exception instead of a raw ClassCastException;
                  `HybridOcrEngine` treats a local failure as an ESCALATION TRIGGER (cancellation
                  still propagates); `scanLocalOrFallback` now catches any non-cancellation
                  throwable and falls back to GLENS, mirroring the existing `scanOwOcrOrFallback`.
                  TESTS +12: `OcrTensorReaderTest` 9 (correct 4-D/3-D nestings, wrong-shape null,
                  ragged null, no aliasing), 3 new CTC space-class tests, charset count 438.
                  **GLENS "stall" IS NOT A LOCK LEAK AND NOT MINE**: `PrioritizedTaskQueue` releases
                  its slot in a `finally`, and `OcrEngineLocks`/the engine mutex are exception-safe.
                  Measured GLENS at 8.8–10.1 s per tile upload and **57–68 s per page** against
                  `OCR_ACQUIRE_TIMEOUT_MS = 30 000`, so timeouts are expected; the 09-26 pass had a
                  2.67 s median upload, so the network is currently ~4x slower. No network behaviour
                  changed. The 9 chapters sitting in `ocr_preprocess_queue` state=ERROR are those
                  failed runs and will be retried by the scanner.
                  VERIFIED ON DEVICE (headless): models present at
                  `files/app_ocr_models/pp_ocr_v5/v1/`, and the on-device SHA-256 of both files
                  equals the pinned manifest exactly (a4319856…6e61d / b5f833df…0557f) — the
                  on-demand download and its integrity check are proven working. The installed
                  package no longer contains the ORT telemetry authority (0 occurrences).
                  **STILL PENDING: the 250 ms number and the three-mode end-to-end run, which need
                  a chapter read and therefore the user.** The interactive test is theirs; the
                  container's adb cannot pair with the wireless device, so `connectedDebugAndroidTest`
                  is not available to me.
Last session:   2026-09-26 PHASES 1-3 IMPLEMENTED — PP-OCRv5 local engine, adaptive hybrid,
                  model downloader, settings UI (user-authorized master task, NO COMMIT).
                  SCOPE LOCK LIFTED: "local OCR engine reinstatement" (was REJECTED) — this master
                  task is the explicit authorization the audit §8 asked for.
                  GATES (docker vsc-yomihon-e24e3bd7, -Xmx4g, BOTH volumes):
                  `spotlessCheck testDebugUnitTest :app:assembleDebug verifySqlDelightMigration`
                  = **BUILD SUCCESSFUL 4m18s — 499 tests, 0 failures** (was 417, +82). No DB
                  change (verifySqlDelightMigration still run for CI parity). One full-suite run
                  failed on the PRE-EXISTING 09-26 perf guard `OcrExclusionMatcherTest > word
                  matching stays linear` (7.12ms vs its 5ms wall-clock bound); in isolation the
                  same test prints 2.45ms and passes, so it is container contention, NOT a
                  regression. That guard is a 5ms wall-clock assertion in a shared 7GB container —
                  treat it as a known flaky, do not loosen it without evidence.
                  FOUR PREMISE CORRECTIONS (all verified, not assumed):
                  (1) **LiteRT CANNOT load PP-OCRv5.** `libLiteRt.so` (litert 2.1.6) exposes only
                  `TfLite*` symbols + `ml_drift/tflite/object_reader`; there is no ONNX parser, so
                  `CompiledModel.create(path)` only accepts `.tflite`. PP-OCRv5 has no TFLite
                  distribution at all (Paddle publishes `inference.json`+`inference.pdiparams` and
                  ONNX). => added `com.microsoft.onnxruntime:onnxruntime-android:1.30.0`
                  (Apache-2.0) to `libs.versions.toml` + `data/build.gradle.kts`. APK COST:
                  `libonnxruntime.so` 32 332 128 B on arm64-v8a, 23 392 928 B armeabi-v7a,
                  39 460 224 B x86, 39 448 520 B x86_64 — i.e. +32MB per arm64 APK, 0 bytes for
                  the models (still an on-demand download). ORT telemetry is NOT
                  neutral out of the box: `ai.onnxruntime.TelemetryInitializer` is a ContentProvider
                  merged from the ORT manifest that runs at every app start from
                  `ActivityThread.installContentProviders` — BEFORE `Application.onCreate`, so
                  `setTelemetry(false)` could never prevent it. It loaded the .so, derived a device
                  id from `android_id` + `Build.MANUFACTURER`/`MODEL`, registered a default network
                  callback and a `battery_low` receiver, and handed the native 1DS stack an HTTP
                  client (proved by javap on the AAR + a live device log). FIXED 2026-09-27 with a
                  `tools:node="remove"` provider block in `app/src/main/AndroidManifest.xml` (only
                  file touched); verified absent from all 5 merged manifests AND from the built APK
                   manifest via aapt2. `setTelemetry(false)` is kept as a second layer.
                  (2) MODELS ARE REAL AND PINNED. `PaddlePaddle/PP-OCRv5_mobile_det_onnx` =
                  4 826 518 B sha256 a4319856…6e61d; `PaddlePaddle/en_PP-OCRv5_mobile_rec_onnx` =
                  7 848 423 B sha256 b5f833df…557f. Total 12 674 941 B = 12.1 MiB (~14 MB claim
                  holds). Both hashes independently confirmed against the HF LFS oids AND against
                  a local download. Graph I/O read out of the ONNX directly: input `x`, output
                  `fetch_name_0`; det tail is `ConvTranspose.3`, rec tail is `Softmax.2` — so the
                  rec head already emits probabilities and no softmax is needed on our side.
                  (3) NO DICT DOWNLOAD NEEDED: the `en` recognizer's 436-character dict is embedded
                  in the official `inference.yml`, copied verbatim into `PpOcrCharset.kt`.
                  Two of its entries (U+1D462 `𝑢`, U+1D4D3 `𝜓`) are astral-plane, so the decode
                  vocabulary is a code-point `List<String>` — a Kotlin `String` indexes UTF-16
                  units and would have handed the decoder lone surrogates. `CLASS_COUNT = 437` is
                  asserted against the model's real output width at engine init, so a
                  model/dict mismatch throws `InitializationError` instead of decoding to garbage.
                  (4) `TextRecognitionModelDialog.kt` DOES NOT EXIST and neither does
                  `domain/.../ocr/engine/`. Real locations used: model selector =
                  `OcrQueueScreen.kt` `ListPreferenceWidget` + `OcrModelExtensions.titleRes`;
                  prefs = `domain/.../ocr/service/OcrPreferences.kt`; engines in `:data` (rules §2:
                   framework code cannot live in `:domain`).
                  WHAT LANDED — pure logic in `:domain`, unit-tested (82 new tests):
                  `PpOcrAssets` (manifest+pins), `PpOcrCharset` (dict), `OcrModelDownloader` (pure
                  installer: `.part` staging, whole-file SHA-256, atomic MANIFEST.json rename,
                  NOT_DOWNLOADED/DOWNLOADING/DOWNLOADED/ERROR, `errorRetryable` for the worker
                  policy), `PpOcrDbPostprocess` (8-connected flood fill, mean-score box threshold,
                  PaddleOCR isotropic unclip, min-side + max-box caps, positional reading order —
                  no OpenCV), `PpOcrCtcDecode` (greedy + mean peak probability = the only
                  confidence in the stack), `PpOcrPreprocess` (det /32 grid ≤960 long side, rec
                  48px height with 8x-stride width clamped to [48,320]), `OcrQuality`/`OcrQualityRouter`
                  (the audit §3.3 table, one named threshold set) and `SpeechCleaner.isOcrGarbage`
                  promoted from private to `internal` so the two layers share one garbage rule
                   instead of duplicating it. `OcrModel` gains PPOCR + ADAPTIVE (free: the OCR cache
                   stores the enum as a string, so no `.sqm` migration and no cache wipe).
                  `:data`: `PpOcrV5Engine` (both ORT sessions, `det=`/`rec=` ms logged as
                  `OCR(ppocr) Runtime:`, CPU threads 2..4 like FastOcrEngine, NNAPI left off),
                  `HybridOcrEngine` (local pass -> router -> whole-page GLENS), and
                  `OcrRepositoryImpl` wiring: `EngineType.PPOCR`, `engineFor`, a `detectionEngine()`
                  that returns the real detector only when the weights are installed (otherwise the
                  throwing stub keeps the existing FAST/GLENS redirect intact), `dispatchScan`
                  branches, `scanAdaptive`, and PPOCR removed from the `recognizeText` LEGACY/FAST
                   -> GLENS redirect. `OcrEngineLocks` maps PPOCR onto the existing `fastMutex`
                  rather than adding a mutex tier.
                  PHASE 2 DEVIATION, DELIBERATE: the task asked to "merge local and cloud bounding
                  box results". NOT DONE. `OcrRegion.order` is reading-order truth for
                  `SentenceSegmenter`, `SpeechPipeline` and tap-highlight, and two detectors cannot
                  produce one coherent order; audit §3.7 forbids it and R1 flags the double-inference
                  cost. Escalation is whole-page: the cloud result replaces the local one, cached
                  under `OcrModel.ADAPTIVE`. Both were approved explicitly by the user.
                  THE <=250ms GATE IS **UNMEASURED**. It cannot be a `testDebugUnitTest` assertion:
                  onnxruntime-android ships no JVM artifact, the weights are an on-demand download
                  (absent in CI), and container-x86 is not phone-ARM. Delivered instead:
                  `data/src/androidTest/.../PpOcrV5EngineBenchmarkTest` (downloads, warms up, p90
                  over >=20 pages, asserts 250ms, skips rather than lies) + the in-engine timing
                  log. CI does not run androidTest. **Device pass on SM_M066B is PENDING** — the
                  number does not exist yet, so the local-first thesis is unproven.
                  `OcrPreferences.kt` UNCHANGED, deliberately: the model choice is a UI list, its
                  default is already GLENS (correct), and the four prefs the audit proposed are all
                  avoided on purpose — the manifest is the durable truth for "installed" (a pref
                  would drift from the filesystem), auto-download contradicts "prompts if missing",
                  `adaptiveLocalFirst` is a knob for behaviour the router does not have, and
                  `adaptiveConfidenceFloor` is one constant edit away until measurement says the
                  0.80 default is wrong. Phase 3 CLEANUP NOT DONE, and deliberately: the "FastOcrEngine panel detector
                  stub" in the task is not a stub. `FastOcrEngine` is a working 507-line LiteRT
                  implementation that is merely unreachable, and `UnavailableDetOcrEngine` is
                  LOAD-BEARING — its `DetectionUnavailable` throw is exactly what routes
                  FAST -> GLENS. Deleting either breaks routing and violates rules §10. The real
                  dead weight is `FastOcrEngine` + `FastVocab*` + 21MB of `ocr_fast` assets, which
                  audit §5 Phase 3 says to delete only after PP-OCRv5 is device-verified.
                  DETECTION-ACCURACY CAVEAT: det normalization uses the upstream ImageNet
                  mean/std and rec uses 0.5/0.5; both are single-sourced constants but UNVERIFIED
                  on-device, as is the 960px long-side cap on tall webtoon strips.
Last session:   2026-09-26 PHASE 0 IMPLEMENTED — skipped words / speech region classification
                  (user-authorized, NO COMMIT, 2 source files + 3 test files, no DB change).
                  GATES (docker vsc-yomihon-e24e3bd7, -Xmx4g, BOTH volumes):
                  `spotlessCheck testDebugUnitTest :app:assembleDebug` = BUILD SUCCESSFUL
                  3m23s — **417 tests, 0 failures** (was 405, +12); spotlessCheck green with
                  NO spotlessApply. (One intermediate combined run failed with no captured
                  cause — only the tail was kept; the identical command re-ran green, as do
                  all three tasks individually. Same transient pattern as 09-25/09-26.)
                  (1) `SpeechRegionClassifier`: the shape heuristics are GONE —
                  `INTERJECTION = ^[A-Z' ]{2,6}$` + "uppercase AND <=8 letters AND has
                  !?" could not tell "BOOM!!" from "STOP!!", so dialogue was being filed as
                  SOUND_EFFECT/EXPRESSION and both default `false` => silently skipped.
                  Replaced with two explicit token sets (SOUND_EFFECT_TOKENS 24 onomatopoeia,
                  EXPRESSION_TOKENS 13 vocalizations), consulted ONLY for upper-case text
                  (the pre-existing case gate, so "boom!!" stays dialogue as before).
                  Anything not listed => DIALOGUE => spoken. Also deleted the dead
                  `if (INTERJECTION.matches(text))` branch (upper-only regex, unreachable
                  for lower case, looked like it covered it). "OK"/"NO"/"YES"/"HI"/"MEH"/
                  "RUN"/"HELP"/"STOP!!"/"WHAT??"/"NO!"/"OKAY!"/"WAIT!" now DIALOGUE;
                  "BOOM!!"/"WHAM!"/"BAM"/"CRASH"/"RUMBLE"/"AAAH"/"HMM" unchanged.
                  (2) `TextPostprocessor`: `hasJapaneseText = text.any{ isJapaneseScript }`
                  -> `japaneseDominant = isJapaneseDominant(text)` (counts JP vs other
                  LETTERS, JP must outnumber them). One stray kana no longer full-width-
                  converts a whole English line, which used to set dominantScript=OTHER and
                  get the bubble deleted by skipForeignScript. The space rule now keys off
                  the same flag, so a Latin-dominant line keeps its spaces next to a stray
                  kana ("I'm ready こんにちは" stays intact instead of "I'mready こ").
                  (3) `dominantScript`: full-width Ａ-Ｚ/ａ-ｚ now counts as LATIN
                  (`isFullWidthLatin()`, codepoint ranges 0xFF21-0xFF3A / 0xFF41-0xFF5A —
                  deliberately NOT by UnicodeBlock, so full-width katakana still counts CJK).
                  This heals pages ALREADY cached with full-width text, which the
                  postprocessor fix alone cannot reach. TDD: 5 domain + 2 data tests failed
                  first for the right reasons, one initial data expectation of mine was wrong
                  (JP-dominant lines also full-width the digits: "こんにちは 1" ->
                  "こんにちは１") and the TEST was corrected, not the code. NOT DONE (deliberately
                  out of Phase 0 scope): the GLENS `DEFAULT_CLIENT_LANGUAGE="ja"` /
                  `"Asia/Tokyo"` hard-code and the `parseResponsePage` region REORDER
                  (audit D3, needs a device capture first) and the IoU dedupe text-precondition
                  (audit D4). Device verification of the words fix is still PENDING.
Last session:   2026-09-26 READ-ONLY audit: PP-OCRv5 local engine + Adaptive Hybrid +
                  skipped-words root cause (docs/audits/ppocrv5-hybrid-audit.md). No source
                  changes, no gates run. THREE PREMISE CORRECTIONS: (1) `OcrPostProcessor.kt`
                  does not exist — it is `data/.../TextPostprocessor.kt`; (2) the 09-26 S4 verdict
                  was specifically "no text-DETECTION model + JP-vocab recognizer", and PP-OCRv5
                  Mobile supplies the missing DB detector + an `en` rec model, so the S4 blocker is
                  resolvable in principle (scope lock still REJECTED, needs explicit lift);
                  (3) NO CONFIDENCE EXISTS ANYWHERE — `OcrBoundingBox`/`OcrRegion`/GLENS
                  proto parse/OCR cache all lack a score, so an adaptive gate can only be
                  local-score -> cloud escalation, never cloud-arbitrated agreement.
                  SKIPPED WORDS ROOT CAUSE IS THE SPEECH LAYER, NOT OCR: primary =
                  `SpeechRegionClassifier.kt:54-56` classifies upper-case <=8-letter+emphasis as
                  SOUND_EFFECT and `^[A-Z' ]{2,6}$` as EXPRESSION, and BOTH prefs default
                  `false` (`TtsPreferences.kt:35,37`) => "OK"/"NO"/"WHAT??"/"STOP!!" are never
                  spoken, while multi-word bubbles survive (exact reported shape). Secondary =
                  `TextPostprocessor.kt:205` full-width-converts a whole line if it contains ONE
                  kana, and `dominantScript` then returns OTHER -> `skipForeignScript` deletes the
                  whole bubble. Third = GLENS hard-codes `DEFAULT_CLIENT_LANGUAGE="ja"` /
                  `"Asia/Tokyo"` (`:1044-1045`), so one stray JP line flips an English page into
                  the JP pipeline (`:467-484`) which REORDERS regions and can delete a line via
                  `filterRuby` (:668-715). PROVEN there is NO length/word-count filter in either
                  OCR path. LiteRT verified via javap on the shipped AAR: `CompiledModel.create(
                  String path, Options, Environment)` loads from an absolute path (on-demand
                  download needs zero new deps) and `Accelerator.NPU/GPU` exist. PP-OCRv5 plan is
                  3 phases; Phase 0 (words bug, no new engine) is separable and is the highest
                  value-per-diff item. Phase 1 gates on a MEASURED det-only <=250ms/page, not the
                  "~200ms" assumption (no local OCR timing exists in any capture).
Last session:   2026-09-26 Single-letter OCR sanitizer + OCR model menu cleanup
                  (user-authorized, NO COMMIT; HEAD had advanced to 035989c27).
                  (1) New pure domain util mergeSpacedSingleLetters() in
                  domain/.../mihon/domain/ocr/model/OcrTextSanitizer.kt; regex
                  SPACED_LETTER_RUN = (?<![A-Za-z])[A-Za-z](?:[ ]+[A-Za-z])+(?![A-Za-z]). The TRAILING LOOKAHEAD is
                  load-bearing: it is what stops "This is a test" being corrupted (the engine can
                  match "a t" but the following "e" fails the lookahead, so "test" is never
                  truncated); lookbehind stops a match starting mid-word; a single letter never
                  matches. ASCII letters only (1 2 3 / 5 x 3 / U.S.A untouched), spaces are the
                  only separator (newlines/tabs NOT merged), length<3 short-circuits. Known
                  limitation documented: shape-only, so letter-spaced English ("I a m") also
                  collapses to "Iam". Wired as ONE line in SpeechPipeline.toSpeakableSentences,
                  AFTER SpeechCleaner so its heuristics still see raw text; deliberately not at
                  scan time (would mutate cached OCR text, need cache invalidation, and miss
                  cached pages).
                  (2) Menu: OcrModel.LEGACY removed from OcrQueueScreen entries (hidden —
                  ListPreferenceWidget has no per-entry enable and adding one would touch a
                  shared widget used by many screens); OcrPreferences.ocrModel() default
                  LEGACY -> GLENS (safe: no test depends on it, and engineFor(LEGACY) already
                  returns the GLENS engine so it is behaviour-neutral); base strings only —
                  ocr_model_fast -> "Fast (local model, in development)", ocr_model_legacy ->
                  "Legacy (deprecated)". GLENS + OWOCR untouched. Per the 09-26 audit FAST
                  cannot run standalone (no text-detection model), so labelling is the honest
                  minimum the task allowed.
                  Tests +11 (OcrTextSanitizerTest 9, SpeechPipelineLetterMergeTest 2 pinning the
                  utility is actually wired in). GATES (docker, -Xmx4g, both volumes):
                  spotlessCheck + testDebugUnitTest + :app:assembleDebug BUILD SUCCESSFUL 4m5s —
                  405 tests, 0 failures (was 394); spotlessCheck green with NO spotlessApply
                  needed. 4 files modified + 3 new. No DB change.
                  Prior 2026-09-26 OcrExclusionMatcher O(n^3) -> linear (12.3s stall eliminated,
                  user-authorized, NO COMMIT). Real path is
                  domain/src/main/java/mihon/domain/ocr/model/OcrExclusionMatcher.kt (NOT
                  ui/reader/ocr/). Measured TDD: 16 WORD zones x 16 regions x 60 tokens =
                  427ms JVM / 12 258ms device inside acquireSentences -> now 2.04ms (209x).
                  (1) wordMatches' nested windowing replaced by containsTokenRun(): a run's
                  char length is pinned by the needle so each start has exactly one possible
                  end offset, valid only on a token boundary -> O(L + n*k) with one concat +
                  a BooleanArray of token-end offsets. Exactly equivalent, all 36 matcher tests
                  unchanged. (2) Hoisted per-region normalizedTokens()/normalizeForPhrase() out
                  of the per-zone loop (16x less NFKC on a 16-zone page) — needed for <5ms;
                  the run fix alone left 6.64ms. REJECTED the proposed single-token fast path
                  `regionTokens.any { it == needleConcat }`: NOT behavior-preserving — a
                  1-token rule is still satisfied by a MULTI-token region run (existing tests
                  "Key Manga"/KeyManga and "Dis\ncord"/discord, the latter documented
                  "excluded by design"); pinned by a new test. Tests: perf guard (<5ms,
                  best-of-5, non-matching rules so the full scan runs), the multi-token-run
                  guard, and a multi-token-rule case. GATES (docker, -Xmx4g, both volumes):
                  spotlessCheck + testDebugUnitTest + :app:assembleDebug BUILD SUCCESSFUL
                  3m30s — 394 tests, 0 failures; matcher suite 36/36. One transient failure
                  (container installed SDK Build-Tools 36 mid-run), re-ran clean.
                  2 files changed.
                  Prior 2026-09-26 Read-Aloud architecture recovery (audit F1/F3/F4 implemented,
                  user-authorized, NO COMMIT): (1) schedulePrefetch now throttles a COLD
                  lookahead to 1 page until the active page is speaking (`cached == null &&
                  !speaking` → break) and dedups by COVERAGE (`it !in covered`) instead of an
                  exact-range guard that never matched the sliding window — kills the 29%
                  duplicate-scan enqueues and the 6-parallel-cold-upload oversubscription;
                  prefetchPages=null on chapter advance so stale coverage can't suppress the
                  new chapter. (2) PrioritizedTaskQueue reserves one slot: backgroundSlotCeiling
                  = maxConcurrentTasks-1, HIGH admitted before the ceiling → active page never
                  queues behind a full NORMAL set (CHANGES documented RC-3 non-preemption, user
                  authorized); hard cap unchanged, background 3→2. (3) NO CODE — the LeakCanary
                  retention was ALREADY fixed at ReaderViewModel.onCleared() (controller.stop()
                  + ttsEngine.shutdown() + onFocusEvent=null, comment cites LeakCanary
                  2026-08-28) and the audit proved the verdict CLEAN / no heap dump; there is
                  no controller onDestroy(). (4) NO CODE — TtsPlaybackControllerTest.kt does
                  not exist; durable gotcha: never wrap a real-Dispatchers.IO join in
                  withTimeout inside runTest (virtual clock fast-forwards → spurious
                  TimeoutCancellationException); runTest's own dispatch timeout is the real
                  bound. Tests: replaced tasksRunConcurrentlyUpToCapacity →
                  backgroundTasksLeaveOneSlotReservedForHigh, updated
                  queuedTaskStartsWhenCapacityFrees, NEW
                  highPriorityTaskBypassesSaturatedBackgroundQueue +
                  uncachedChapterQueuesOnlyOneLookaheadPageBeforeSpeech.
                  GATES (docker, -Xmx4g, both volumes): spotlessCheck + testDebugUnitTest +
                  :app:assembleDebug BUILD SUCCESSFUL 3m40s — 391 tests, 0 failures;
                  queue 7/7, TTS 5/5. 4 source files changed.
                  Prior 2026-09-26 READ-ONLY OCR/TTS resiliency audit
                  (docs/audits/full-ocr-system-resiliency-audit.md). No source changes.
                  VERDICT: no memory leak (LeakCanary verdict was CLEAN, no heap dump ever
                  written; ART live set flat ~36-38MB, 0 growth-limit events / 65 GCs).
                  10-30s stalls ROOT CAUSE = queue-slot starvation from DUPLICATE scans:
                  56/193 (29%) queue enqueues were repeat scans of an already-enqueued page
                  (45 pages >1x, worst ch8725 p2 = 4x). Prefetch dedup guard compares exact
                  ranges but the window slides 1/page → never dedups (51 starts / 1 skip);
                  prefetchJob.cancel() does NOT cancel already-queued queue tasks. Queue wait
                  p90 9233ms / p99 15806ms / max 17169ms; 25 tasks >8s. Non-preemptive cap-3
                  then starves the active page. S4 local Fast OCR NOT viable as GLENS
                  fallback: blocked on a text-DETECTION model that exists nowhere (DetOcrEngine
                  is a throwing stub; panel_detector is a YOLO comic-PANEL detector);
                  models already packaged in APK → 0-byte APK cost, but ~25-40MB RSS and
                  JP-vocab garbles English. S4 remains user-HALTED (REJECTED scope lock).
                  Prior 2026-09-26 A–D device deployment + cold-start verification: installed
                  refreshed debug APK on SM_M066B (install -r Success, data preserved),
                  captured logcat, verified Fix A/B/C/D. KEYSTORE GOTCHA: gate build
                  without the android-home volume mounted signed with a wrong key
                  (1a6fbe75...); installed/known-good = e486ea51...; must mount
                  yomihon-android-home:/home/vscode/.android for debug builds.
                  Prior 2026-09-26 A–D implementation: (A) OCR_ACQUIRE_TIMEOUT_MS
                  8000→30000; (B) prefetchReaderOpenPage scans at OcrScanPriority.HIGH
                  (grab slot ahead of background NORMAL scans); (C) LoadingPage
                  UX already present (no change); (D) new TtsOcrTimeoutGuardTest
                  regression guard. Gates: spotlessCheck + testDebugUnitTest +
                  :app:assembleDebug all BUILD SUCCESSFUL. No commit.
                  Prior 2026-09-25 TTS resiliency + dynamic prefetch batch (F1–F3,
                  user-authorized; S4 halted): (F1) GlensOcrEngine per-tile
                  retry ×2 on IOException/5xx/429 + READ_TIMEOUT 60s→12s,
                  closes WATCH#1 (500 no-retry absorbed by awaitAll).
                  (F2) TtsPlaybackController.acquireSentences wrapped in
                  withTimeoutOrNull (8000; now 30000 per Fix A above); on
                  timeout → fail(OcrError), runPlayback exits gracefully
                  instead of wedging in LoadingPage. (F3) schedulePrefetch
                  refactored to sentence-budget: TARGET_SENTENCE_BUFFER=40,
                  MIN_SENTENCES_PER_PAGE=5, MAX_PREFETCH_DEPTH_EXTENDED=6;
                  dynamic lookahead replaces fixed prefetchDepth().
                  Prior S1+S2+S3+S5 batch (09-25) also uncommitted.
```

## Session task log — PP-OCRv5 local OCR, 2026-09-26/27 (all complete, nothing committed)

Single place listing everything finished in this session. Detail lives in `memory.md` (recent +
durable rules) and `history/session-logs.md` (full records); the `Last session` entries above carry
the per-turn evidence.

| # | Completed task | Outcome / evidence |
|---|---|---|
| 1 | **Phase 0 — skipped words** (earlier in session) | `SpeechRegionClassifier` shape heuristics replaced with 2 lexical token sets; `TextPostprocessor` Japanese-dominance; full-width Latin counted as Latin. 417 tests green. |
| 2 | **Phase 1 — on-demand model downloader** | `OcrModelDownloader` (`:domain`, pure): `.part` staging, whole-file SHA-256, atomic `MANIFEST.json`, 4 states + `errorRetryable`. 15 tests. |
| 3 | **Phase 1 — model manifest pinned** | Official PaddlePaddle ONNX exports, hashes confirmed against HF LFS oids *and* a local download: det 4 826 518 B `a4319856…`, rec-en 7 848 423 B `b5f833df…`, 12 674 941 B total. 6 tests. |
| 4 | **Phase 1 — pure inference logic** | `PpOcrDbPostprocess` (8-connected flood fill, no OpenCV), `PpOcrCtcDecode`, `PpOcrPreprocess`, `PpOcrCharset`. 39 tests. |
| 5 | **Phase 1 — local engine** | `PpOcrV5Engine` on ONNX Runtime (LiteRT provably cannot load ONNX). Wired into `OcrRepositoryImpl` (`EngineType.PPOCR`, real `detectionEngine`, locks reuse `fastMutex`). |
| 6 | **Phase 2 — adaptive hybrid** | `OcrQualityRouter` (`:domain`, 15 tests) + `HybridOcrEngine` + `scanAdaptive`. Whole-page escalation, **no local/cloud merge** (order invariant), user-approved. |
| 7 | **Phase 3 — settings UI** | `OcrQueueScreen` 4-entry model list + download/progress/retry/delete row; `OcrModelExtensions` titles; 12 new i18n strings (base only). |
| 8 | **Phase 3 — download worker** | `OcrModelDownloadJob` mirrors `OcrScanJob` (same shape, `KEEP`, reused channel, retry-vs-fail on `errorRetryable`). |
| 9 | **Gates after Phases 1-3** | `spotlessCheck testDebugUnitTest :app:assembleDebug verifySqlDelightMigration` = SUCCESS, 499 tests. |
| 10 | **Device install + cert verify** | `install -r` Success, data preserved, cert `e486ea51…8968` = known-good keystore. |
| 11 | **On-demand download proven on device** | Both weights in `files/app_ocr_models/pp_ocr_v5/v1/`; **on-device SHA-256 equals the pinned manifest exactly**. |
| 12 | **Telemetry ContentProvider removed** | `ai.onnxruntime.TelemetryInitializer` runs before `Application.onCreate`, so the runtime toggle was too late. `tools:node="remove"`; **0 references in all 5 merged manifests and in the shipped APK**, 0 in the installed package. |
| 13 | **Crash #1 diagnosed + fixed** | `NodeInfo → TensorInfo` silent-null cast (Kotlin platform type). Fixed via `NodeInfo.getInfo()`; new pure `OcrTensorReader` for the real 4-D/3-D output nesting (9 tests); dict 436 → **438 classes incl. the PaddleOCR space token**; init failure made **sticky** (was 23 re-inits / 649 graph-optimization runs). 511 tests. |
| 14 | **Robustness: local failure ⇒ cloud** | `HybridOcrEngine` escalates on any non-cancellation throwable; `scanLocalOrFallback` mirrors `scanOwOcrOrFallback`. A bad tensor shape can no longer crash speech acquisition. |
| 15 | **Latency metrics extracted** | Real pages: det p50 788 ms / p90 1926 / p99 4009; rec p50 243 / p90 380 / p99 569. |
| 16 | **Crash #2 diagnosed + fixed** | `OcrTensorSize` — `recognitionInputSize` returned `Pair(height,width)` while `detectionInputSize` returned `Pair(width,height)`; every rec tensor was transposed (`index: 2 Got: 192 Expected: 48`) and square crops masked it. Named type now. 512 tests. |
| 17 | **Hardware benchmark run** | `PpOcrV5EngineBenchmarkTest` made self-contained (draws its own pages; the old fixture-file version silently *skipped*). Two runs: det p50 80–110 ms **p90 548–552 (FAILS 250 ms)**, rec p50 47–48 / p90 57–59. |
| 18 | **Adaptive Hybrid escalation verified** | 68 real pages: **7 accepted (10.3%), 61 escalated (89.7%), 0 local failures**, 1094 rec calls all height 48. |
| 19 | **Escalation cause identified** | 100% of escalations are `meanCharConfidence < 0.80` (median 0.557); all other thresholds ~never hit. The 0.80 floor is the audit's *unmeasured* guess — deliberately NOT lowered. |
| 20 | **Final decision recorded** | **GLENS stays the default engine**; PPOCR + ADAPTIVE stay opt-in. Two pre-registered audit criteria fail: escalation 89.7% vs R1's ~20%, and detection p50 393–788 ms vs §5's 400 ms kill-switch. |
| 21 | **Final gates** | `spotlessCheck testDebugUnitTest` = **BUILD SUCCESSFUL, 512 tests, 0 failures**. `OcrPreferences.ocrModel()` still `GLENS` (verified, not assumed). |
| 22 | **Docs synced** | `state.md`, `memory.md` (3 durable rules), `history/session-logs.md` (5 session records). |

**Not done, deliberately:** merging local+cloud boxes (breaks `OcrRegion.order`); deleting
`FastOcrEngine`/`FastVocab` (~21 MB of dead weight) until PP-OCRv5 is device-verified — and
`UnavailableDetOcrEngine` is *load-bearing*, not an obsolete stub, since its throw is what routes
FAST → GLENS; lowering the confidence floor without a local-vs-GLENS accuracy comparison.
**Open:** the true post-fix escalation *quality* profile needs a local-vs-GLENS text comparison, which
does not exist yet.

## Current blockers

1. **Debug-keystore signature mismatch** blocks device pass: `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (09-15). RECONCILED 2026-09-26: debug signing uses container `/home/vscode/.android/debug.keystore`; a gate build WITHOUT the `yomihon-android-home` volume mounted signs with a stale key (`1a6fbe75...`), while installed/known-good = `e486ea51...8968` (volume keystore, alias `androiddebugkey`, created 2026-08-22). ALWAYS mount `yomihon-android-home:/home/vscode/.android` for debug builds; verify cert before `install -r`; never uninstall `app.yomihon.dev` without a current `.tachibk` backup (data loss). 09-20 v0.5.4.2 release used the stable keystore (verified) — release path unaffected.
2. Nothing else hard-blocked. 2026-09-26 A–D cold-start fixes executed + device-verified (partial — see caveat above): timeout 8s→30s + HIGH-priority reader-open p0 scan + regression guard test. S1+S2+S3+S5 and F1–F3 batches (09-25) also executed; S4 halted/dropped by user. All uncommitted — awaiting user commit decision.

## Active technical gotchas

- Build in docker image `vsc-yomihon-*` (host has no SDK); ALWAYS `-Xmx4g`. TWO mounts required: (1) host `~/.gradle` → `/home/vscode/.gradle` (`GRADLE_USER_HOME=/home/vscode/.gradle` is where the real dependency cache lives; mounting `/root/.gradle` or `/home/user/...` breaks dep resolution, e.g. JitPack `com.github.arkon.FlexibleAdapter:flexible-adapter:c8013533` not found → build fails); (2) `yomihon-android-home` → `/home/vscode/.android` for the known-good debug keystore (`e486ea51...8968`) — OMITTING it silently signs with a stale key (`1a6fbe75...`) and breaks `install -r`.
- ML models gitignored — download via CI step or CONTRIBUTING.md; OCR silently degrades without them.
- Device SM_M066B (Android 16, arm64) wireless: `./scripts/adb-wireless connect`; capture logcat ON-DEVICE to disk (no `--pid`, no `logcat -c`); device clock ≈ host +5h29m.
- Releases must be built+published LOCALLY (release.yml is fork-gated); root-owned build dirs → one-off `chown -R 1000:1000 /workspace` in root container.
- Strings: edit ONLY `i18n/src/commonMain/moko-resources/base/`; never locales (Weblate).
- Voyager tabs addressed by CLASS, not index; settings tab must stay LAST page (ColorFilter dim-hack index).

## Scope locks

```text
HARD HALTED:   Q3, Q4, Q5, Q6, Q7 (user, 2026-09-13) — do not inspect/implement.
LIFTED 09-26:  local OCR engine reinstatement — lifted by the user's PP-OCRv5 master task
               (audit §8 asked for exactly this authorization); PP-OCRv5 supplies the text
               DETECTOR the 09-26 S4 verdict identified as the hard blocker. Not a re-open of
               the S4 stack: ocr_fast/FastVocab still exist and are still dead weight.
REJECTED:      true backdrop blur (RenderEffect), nav-tab customization as 6th tab/IA
               change, standardized reselect, Browse Search tab, Library page-level
               Continue, screen-OCR from other apps, local OCR engine reinstatement,
               anime/novel/gamification features (identity).
DEFERRED:      Liquid Background (designed 09-13; needs translucent-container audit),
                tap-zone editor, dict history/favorites, Anki context capture,
                .mokuro, all Phase 10B heavy items.
COMPLETED/CLOSED (do not reopen): TTS Phases 1–9, Phase 10A, stabilization batches,
                UI audit Batches 1–5, Batch 6/7, artwork tray, Q1/Q2/Q9, v0.5.3/v0.5.4/
                v0.5.4.1 releases, 2026-09-13 adaptive-UI batches, YOMUCHU batch 2,
                OCR TTS stages 4K–4N (closed via prior sessions), 4O read-only audit,
                Q8 Feed auto-pagination (user-verified 09-19),
                Stage 4P OCR prefetch-timing optimization (integrated 09-19).
Unlocked ONLY for the 09-13/09-15 batches (per user master prompt): nav ORDER +
               background gradient + translucency. Reorder ≠ IA change; gradient ≠ Liquid.
```

## Deeper context — which doc to consult

| Need | Read |
|---|---|
| What may I implement? | `implementation-roadmap.md` (CANONICAL authorization) |
| Engineering rules + doc protocol | `rules.md` |
| Recent sessions, durable decisions, known issues | `memory.md` |
| UI/visual work | `design.md`, then `ui-implementation-map.md` |
| Phase history | `phase.md` |
| Historical evidence / prior session detail | `history/session-logs.md` (ONLY when required) |
| HOW (architecture) | `architecture.md` |
| WHAT (product) | `prd.md` |
| Branding | `branding.md` |

**Never bulk-load the docs tree. Startup order: state.md → rules.md → memory.md → implementation-roadmap.md.**
