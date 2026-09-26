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
Last session:   2026-09-26 OcrExclusionMatcher O(n^3) -> linear (12.3s stall eliminated,
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
