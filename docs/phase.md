# Yomitsu — Implementation Phases

> Roadmap for the Read-Aloud TTS feature and the documentation system that
> governs it. Statuses use: `NOT_STARTED | IN_PROGRESS | BLOCKED | COMPLETED | NEEDS_REVIEW`.
> A phase is COMPLETED only when its verification steps have actually been run
> and recorded in `docs/memory.md`.

Current phase pointer: **RELEASE CANDIDATE v0.5.4.1 PREPARED 2026-09-13 —
  version bumped 0.5.4→0.5.4.1 / versionCode 30→31 (app/build.gradle.kts,
  single source of truth), CHANGELOG entry added. All 4 gates GREEN in one
  chained run (docker vsc-yomihon-e24e3bd…, JDK17 -Xmx4g: spotlessCheck +
  testDebugUnitTest + verifySqlDelightMigration + :app:assembleDebug,
  3m37s). APK metadata verified: versionName=0.5.4.1-8288 (debug suffix =
  commit count), versionCode=31, applicationId=app.yomihon.dev, arm64
  APK fresh-built 14:52. Device smoke PASS on SM_M066B (wireless
  192.168.29.98:5555): install OK, package metadata matches (0.5.4.1-8288
  / vc31), launch clean 0 FATAL/0 ANR session-wide, Feed full matrix
  (single [Source][Popular][Latest] row y=209, no All chip, Popular/Latest
  data-distinct + checked-state verified, genre Action chip filter
  roundtrip, collapse-on-scroll + return), Browse sources render,
  Library cards + filter sheet + long-press menu, More = Studies card +
  grouped cards, Settings search live ("read"→results, "language"→App
  language findable = BUG-010 registration verified), Reader/TTS smoke
  (engine connected, voice applied ×3, GLENS OCR service cycles), About
  shows Debug {sha} per debug-build convention. AWAITING user commit/tag/
  push/GitHub-release authorization. No git action taken.**
  Prior: MASTER CLOSEOUT AUDIT 2026-09-13 COMPLETE — all
  authorized work VERIFIED, tree COMMITTED as 35a78cb7e (Feed UI correction
  + docs, user-committed), tree clean. Closeout re-ran all 4 gates green
  (docker JDK17 -Xmx4g: spotlessCheck + testDebugUnitTest +
  verifySqlDelightMigration + :app:assembleDebug, 3m11s), device smoke
  SM_M066B 0.5.4-8286 PASS, device-pref caveat RESOLVED via app UI
  (default listing set to Latest, persisted across restart). Ready for
  user review. No release action taken.
  Scope of the committed Feed UI correction (user-authorized, supersedes
  stacked-selector layout): `All`
  listing chip removed from Feed + Customize→Default listing; legacy null
  defaultListing → Popular fallback at read (no migration); compact
  [Source][Popular][Latest] primary row (horizontalScroll narrow fallback,
  stable-width selector); duplicate per-section source/listing header
  removed; FilterBar moved into grid = collapse-on-scroll/return;
  genre chip row preserved. Files: FeedScreen.kt, FeedScreenModel.kt.
  Gates green (docker JDK17 -Xmx4g: spotlessCheck, testDebugUnitTest,
  verifySqlDelightMigration 2m59s, :app:assembleDebug 3m26s). Device
  SM_M066B: full §30 matrix PASS (layout, no-All, single-select data
  match, genre roundtrip, collapse/return, load-more, persistence,
  legacy fallback, Manage Feeds intact).**
  Prior: MASTER SESSION 2026-09-13 COMMITTED (6 commits 345fcb67d..
  64e0103af on b2f1da316, user-authorized): Q9 a11y batch + bug fixes
  BUG-003/004/005/006/008/009/010 (BUG-007 verified unreachable) +
  Tscan Feed fix + OCR/TTS ChapterCache latency + Feed genre filtering +
  AnymeX micro-passes + Liquid DESIGNED+DEFERRED. Q3–Q8 HARD HALTED.
  Prior: Q2 GENRE-CHIP SEARCH COMPLETE 2026-09-12
  (implementation + gates green + device Q2-01..09 PASS on SM_M066B;
  committed in master session set as 345fcb67d). Genre chips = M3
  FilterChip row in BrowseSourceScreen derived from
  the source's own Filter leaves (TriState/CheckBox); toggle re-searches
  via existing search(filters=); sources without such leaves honestly show
  no row. Multi-genre = source semantics.
  Prior: Q1 v0.5.4 RELEASED 2026-09-12 (tag v0.5.4,
  commit 9f228d07c, GitHub Latest with 5 ABI APKs; RM-01 + artwork-tone set
  shipped inside it as commit 0434d07a1, user-committed per U-2). All gates
  green; device smoke PASS on SM_M066B; PLUS user-requested pre-push fix:
  parallel OCR scan queue with HIGH priority for the current Read-Aloud
  page (commit 9f228d07c) — uncached preload much faster; first-page GLENS
  round-trip (~15-30s, service latency) remains a documented ceiling
  (Phase 10B local OCR = upgrade path). Next per roadmap queue: Q2 —
  Genre-chip Search (awaiting user approval U-4); then Q3 recursive
  dictionary lookup design.**
  Prior: MASTER ROADMAP CANONICAL 2026-09-12 —
  docs/implementation-roadmap.md is now THE execution register (agent
  execution lock inside it). Current authorized task: RM-01 cleanup &
  pre-release consolidation (TTS pause-guard P2 + chapter-advance-failure
  P2 + dead-code sweep + stale-doc corrections: Known-issue #2 found
  RESOLVED in code by the audit). Full-audit verdict: no P0/P1; 2×P2,
  3×P3, rest P4. Reference candidates registered (Tadami/AnymeX/Chimahon);
  queue = v0.5.4 release → genre-chip search → recursive dictionary
  lookup → e-ink popup style → tap-zone investigation → dict
  history/favorites → 10B → Feed auto-pagination → a11y completion.**
  Prior: ARTWORK-REACTIVE READER TRAY COMPLETE
  2026-09-12 (Chimahon/AnymeX-track "ADAPT" item, deferred #3): device
  verification done (stream-wait root-cause fix + user-approved 20%/2.5x
  chroma tuning after 8% proved invisible on AMOLED); gates green;
  debug logs pending removal before next release. Batch 7 toolbar
  customization USER-VERIFIED → CLOSED (2026-09-11).** Prior:
  DECISION MICRO-BATCH COMPLETE 2026-09-11 (docs-
  only, commit see memory.md). Ratified TtsPlaybackBar as shipped
  (bodyMedium + 16/4; design.md §4/§5/§8 updated, zero source change);
  FeedFilterBar arrow CLOSED NO ACTION; FeedHeader two-line KEPT; D-09
  and D-14 CLOSED no action; D-15 FUTURE not current work. Create-tab
  remains OPEN (user clarification; nothing built). A11y pass complete +
  committed (212a09c7b + docs 82cce9206). v0.5.3 RELEASED 2026-09-11
  (tag v0.5.3, commit daa942738, all gates green, device smoke PASS,
  GitHub release published with 5 ABI APKs).** Releases the verified
  baseline: Yomitsu
  rebrand, legacy-OCR removal (−133MB), stabilization batches, UI audit
  Batches 1–5. Open decision register (Create-tab only; nothing else
  pending). Next per docs/next-phase-plan.md roadmap: first Phase 10B
  item, a11y deferred micro-batch (CategoryListItem drag actions,
  BaseSliderItem label, SourceSelectorDropdown checks, spinner cds), or
  user-defined Batch 6 — user's pick.
  Prior: UI audit implementation Batches 1–5 COMPLETE
  2026-09-10 (Batch 5 device-verification matrix PASS: Feed D-02/D-03/
  paging/ManageFeeds, Recent + D-01, Settings Search D-08, MangaScreen
  D-11, typography/surfaces/a11y; gates green — 1 Batch-4 test-harness
  race fixed test-only; evidence .device-pass/batch5-verify.log +
  screenshots/batch5/). All committed (1b2c56b23 + docs 2a377ef12 +
  master-plan eaccfe6f0). Remaining register items D-09/D-14/D-15 =
  INFO only.**
  Prior: FULL UI AUDIT + docs/ui-implementation-map.md delivered
  (2026-09-08, docs only).
  Prior: Yomitsu rebrand (2026-09-07, committed 708a7182d) — docs/
  branding.md is the source of truth for name/branding decisions.
  Prior: correction passes + visual hierarchy sets (2026-09-06/07,
  committed e89104296/e166cd16e/af5606a56/333dafe3a) — Recent tab IA,
  Feed v2 + paging, reader-settings groups, grouped surfaces, frosted
  reader chrome, device-verified.
  Prior: UI/UX modernization set 1 (2026-09-03) — floating nav pill, OCR
  exclusion phrase EDIT + collapsed rule rows + identity labels, Feed filter
  chips, More-tab GroupHeader sections.
  Prior: P0 ZONE exclusion reliability fix (2026-09-03 #3) code COMPLETE +
  gates green — pure-rect page-anchored ZONE rules for all scopes (COMBINED
  now opt-in via optional text field; prefill REMOVED — match text starts
  empty), original-dims guard against split/rotate page transforms,
  per-rule OCR-ZONE diagnostics. Device repeat-matrix verification PENDING
  user. UNCOMMITTED.
  Prior: OCR exclusion regression-fix set #2 (2026-09-03) code COMPLETE +
  gates green — crop-OCR engine redirect (Legacy JP model
  caused garbage), boxMostlyInside selection filter (outside-region text
  leak), PHRASE token-concat matching, WebtoonTransitionHolder leak fix
  (184.7MB), single-flight detect. APK 0.5.1-8255 installed; device
  verification (tests A–J) PENDING user run. UNCOMMITTED.
  Prior: OCR exclusion regression-fix set COMPLETED
  (2026-09-02, all 4 gates green) — toggle param-swap fix, WORD/PHRASE
  matcher rework (NFKC fold + token-concat runs + whitespace-strip phrase),
  manage-sheet global-rule visibility, exclusion diagnostics. UNCOMMITTED,
  device pass recorded (see memory.md 2026-09-02/03 blocks).
  Prior: Post-device-test audit set COMPLETED (2026-09-01, all 4 gates green)
  — repeated-speech fixes, OCR exclusion redesign (ZONE/WORD/PHRASE/COMBINED +
  19.sqm), speed-adaptive prefetch, stop-during-prepare, reader interaction
  toggles, ellipsis pause; committed by user as 1b810ccde.
  Phase 10A COMPLETED (2026-08-31, device-verified, build 0.5.0-8250);
  Phases A–I multi-feature set COMMITTED as c70e32252; v0.5.1 released.
  Phase 10B backlog remains (per-voice tuning beyond profiles, cloud/neural
  providers, expressive speech). Phase 9 COMPLETED (2026-08-29). Phase 8
  device pass COMPLETE (2026-08-28). All prior fix sets committed.
PRODUCT PIVOT 2026-08-25: English is the primary v1 Read-Aloud language;
  Japanese TTS moved to Phase 10B.

---

## Phase 0 — Repository analysis & documentation system

- **Status**: COMPLETED (this change set)
- **Objective**: map the actual repository; establish the AI documentation/memory
  system so future agents don't restart analysis.
- **Tasks**: repository audit; architecture mapping; reader/OCR/data/DI/network/
  testing inventory; TTS scope confirmation against root `architect.md` /
  `architect-2.md`; creation of `docs/{prd,architecture,rules,phase,design,memory}.md`.
- **Dependencies**: none.
- **Files affected**: `docs/*` only (new).
- **Completion criteria**: six documents exist, cross-checked, no contradictions,
  all claims verified against source.
- **Tests required**: none (documentation only).
- **Verification performed**: full source inspection recorded in `docs/memory.md`
  §Last verified build/test (no code changed).

---

## Phase 1 — TTS foundation

- **Status**: COMPLETED (commit 07c64985f; spotlessCheck + :domain tests green)
- **Objective**: framework-free contracts + pure decision logic in `:domain`,
  wired via Injekt.
- **Tasks**:
  - `domain/src/main/java/mihon/domain/tts/engine/TtsEngine.kt` (initialize,
    language availability, suspend `speak(utteranceId, text)`, rate/pitch setters,
    focus hooks, stop, shutdown).
  - `domain/src/main/java/mihon/domain/tts/TtsAdvancePolicy.kt` (pure advance fn).
  - `domain/src/main/java/mihon/domain/tts/service/TtsPreferences.kt`
    (rate 0.5–2.0 def 1.0, pitch def 1.0, auto page turn def true,
    auto next chapter def false, keep screen on def true).
  - DI bindings: `DomainModule` engine factory, `PreferenceModule` prefs singleton.
- **Dependencies**: Phase 0.
- **Files/modules affected**: `:domain`, `app/.../di/PreferenceModule.kt`,
  `app/.../eu/kanade/domain/DomainModule.kt`.
- **Completion criteria**: interfaces compile; bindings resolve (`:app:assembleDebug`).
- **Tests required**: none yet beyond compile (policy tests land with Phase 4 files
  or here if written together).

## Phase 2 — Android TTS engine

- **Status**: COMPLETED (commit 07c64985f; 2026-08-25: Japanese preflight
  REMOVED per product pivot — engine speaks system-default voice; device check
  bundled with Phase 8 device pass)
- **Objective**: working system-engine implementation behind the abstraction.
- **Tasks**: `app/.../data/tts/AndroidTtsEngine.kt`; main-thread construction;
  `CompletableDeferred` bridging of `OnInitListener`/`UtteranceProgressListener`;
  `isLanguageAvailable(JAPANESE)` preflight; rate/pitch application;
  `AudioFocusRequest` gain/transient/permanent handling; stop fails pending
  deferreds; shutdown releases resources.
- **Dependencies**: Phase 1.
- **Files affected**: new engine file only.
- **Completion criteria**: engine initializes/shuts down cleanly on device;
  speak() suspends until done/error; focus transitions correct.
- **Tests required**: manual device check (framework-thin by design); no Robolectric.

## Phase 3 — OCR integration

- **Status**: COMPLETED (acquisition + prefetch inside TtsPlaybackController;
  device verification deferred to Phase 8 script)
- **Objective**: feed pages to the pipeline from cache or on-demand scan.
- **Tasks**: cached-first `GetCachedPageOcr`; miss path via
  `OcrPageSourceResolver` + `WithOcrScanSession` + `ScanPageOcr`; bitmap
  acquire/recycle discipline; N+1 prefetch job with cancellation on page change.
- **Dependencies**: Phase 1 (controller skeleton may start in parallel).
- **Files affected**: controller file (`ui/reader/tts/TtsPlaybackController.kt`).
- **Completion criteria**: text acquisition works for cached GLENS chapters and
  uncached pages without leaking bitmaps.
- **Tests required**: covered indirectly by policy tests; manual verification path
  defined in prd.md §3.4(3).

## Phase 4 — Sentence processing

- **Status**: COMPLETED (SentenceSegmenterTest green; 2026-08-25 extended to
  English rules: ASCII '.' terminal before whitespace/EOL, dot-runs glued,
  decimals safe; 15 cases)
- **Objective**: pure segmentation honoring manga reading order.
- **Tasks**: `SentenceSegmenter.toTtsSentences()`; terminal punct
  `。！？!?‼⁇⁉⁈` with punctuation attached; remainder fragment; blank-region skip;
  no cross-region merges; reuse existing normalization (no re-cleanup);
  `TtsSentence(text, regionOrder, boundingBox, textOrientation)`.
- **Dependencies**: Phase 1 (models), independent of Phase 2/3.
- **Files affected**: `:domain` segmenter + tests.
- **Completion criteria**: `SentenceSegmenterTest` green covering all cases listed
  in prd.md §3.4(1).
- **Tests required**: JUnit5+Kotest suite in `domain/src/test/java/mihon/domain/tts/`.

## Phase 5 — TTS queue & playback control

- **Status**: COMPLETED (controller loop, TtsPhase state machine, confirm-timeout
  arbitration, policy reuse; device pass deferred to Phase 8)
- **Objective**: play/pause/resume/stop/next/prev sentence + automatic progression.
- **Tasks**: `TtsAdvancePolicyTest` suite; controller loop
  (TEXT→SEGMENT→SPEAK→ADVANCE); state machine
  `TtsPhase { Idle, Preparing, LoadingPage, Playing, Paused, Finished, Error }`;
  `TtsPlaybackState` exposed as StateFlow; user-navigation arbitration
  (`onPageSelected` mismatch rebuilds queue; ~10 s advance-confirm timeout → Paused);
  end-of-content detection (`viewerChapters.nextChapter == null`).
- **Dependencies**: Phases 2–4.
- **Files affected**: controller + `TtsAdvancePolicy` tests.
- **Completion criteria**: all policy branches tested; controller drives a full
  chapter on device including page turn and chapter transition.
- **Tests required**: `TtsAdvancePolicyTest` (all branches from prd.md §3.4(1)).

## Phase 6 — Reader integration

- **Status**: COMPLETED (code + compile verified 2026-08-23, commit 3fc10ad50;
  device verification deferred to Phase 8 pass)
- **Objective**: surface the feature in the reader UI safely.
- **Tasks**: `ReaderViewModel.State.ttsState` + controller lifecycle wiring
  (lazy start, stop in `onActivityFinish`/`onCleared`); new Events
  (`TtsAdvancePage`, `TtsAdvanceChapter`, `TtsError`, `TtsNoTextFound`) handled in
  ReaderActivity event collector; `TtsPlaybackBar` rendered beside
  `OcrLoadingIndicator`; entry icon in `ReaderBottomBar` threaded like `onClickOcr`;
  `onStop` pause; keep-screen-on combination logic.
- **Dependencies**: Phase 5.
- **Files affected**: `ReaderViewModel.kt`, `ReaderActivity.kt`,
  `presentation/reader/*`, `ReaderBottomBar.kt`.
- **Completion criteria**: lifecycle matrix from root architect.md verified on device.
- **Tests required**: unit tests still green; manual matrix run recorded.

## Phase 7 — Settings

- **Status**: COMPLETED (2026-08-23; device verification deferred to Phase 8 pass)
- **Objective**: user control over speech behavior.
- **Tasks**: "Read aloud" tab in `ReaderSettingsDialog` using existing
  `CheckboxItem`/`SliderItem` specs; rate/pitch sliders, auto page turn,
  auto next chapter, keep-screen-on checkboxes; i18n snake_case keys added to base
  `strings.xml` only.
- **Dependencies**: Phases 2 & 6.
- **Files affected**: settings page file, `ReaderSettingsDialog.kt`, i18n base.
- **Completion criteria**: every pref takes effect live on device.
- **Tests required**: spotless; manual toggling script.

## Phase 8 — Testing

- **Status**: COMPLETED (2026-08-28: device script steps 1–15 all executed +
  user-confirmed; all suites green. History: 2026-08-25: stabilization change
  set landed — Glens
  strip tiling + EN ordering, JP gate removal, segmenter EN rules, progression
  fixes; spotlessCheck + testDebugUnitTest + :app:assembleDebug GREEN.
  2026-08-26: device script steps 1–6 executed (3 PASS / 3 PASS-WITH-ISSUE,
  evidence in .device-pass/); Phase 9 perf pass #1 landed (parallel tiles,
  scan single-flight, task-owned bitmap+upsert) — build 0.4.0-8233 installed.
  2026-08-27: Phase 9 perf pass #2 landed — webtoon advance-confirm fix
  (findFirstVisibleItemPosition), region-level auto-scroll (ScrollToRegion
  event), pause/resume page-awareness — build 0.4.0-8234 installed. All gates
  green. 2026-08-28: FRESH logcat analysis COMPLETE — logcat-8234-phase9.log
  (7,793 lines, PID 27363). Results: 3 chapters tested (206, 205, 1),
  9/9 page advances confirmed, 0 recycle crashes, 0 sentence failures,
  0 timeouts. Known issues #8 + #10 RESOLVED. Two new issues found:
   P0 prefetch spam loop, P1 rapid-swipe mass OCR — both FIXED 2026-08-28.
   Build 0.4.0-8236 run2/run3 verified Finding #1/#2; Finding #3 deferred LOW
   PRIORITY post-build. 2026-08-28 RUN4 (build 0.4.0-8238): script steps 7–15
   EXECUTED — chapter transition, end-of-content, home-during-playback, exit
   reader, audio-focus, exit-idle all PASS; 4/4 advance confirms 1–2ms / 0
   timeouts; ScrollToRegion on-device verified. USER CONFIRMED: step 7
   rate/pitch live PASS + step 11 rotation PASS (pause → resume same OCR
   line/position). FINDING #4 (P1) prefetch-DNS escalation FIXED 2026-08-28
   (reportFailure gate). Steps 1–15 all executed + confirmed — phase complete.
   The "missing-JP-voice" branch of the old script is OBSOLETE after the pivot.)
- **Objective**: complete the test story.
- **Tasks**: ensure segmenter+policy suites comprehensive; run full
  `testDebugUnitTest`; device pass of the prd.md §3.4(3) script (cached path,
  uncached path, webtoon strip incl. tiling, arbitration, end-of-content,
  rate/pitch); record results in memory.md.
- **Dependencies**: Phases 1–7 + 2026-08-25 stabilization fixes.
- **Completion criteria**: all suites green + device checklist recorded.
- **Tests required**: as listed.

## Phase 9 — Performance/stability hardening

- **Status**: COMPLETED (static audit DONE 2026-08-23; reader-stabilization
  pass DONE 2026-08-25; perf pass #1 DONE 2026-08-26 [tile parallelism ×3,
  scan single-flight, task-owned bitmap+upsert — kills duplicate scans and
  recycle crashes, ~3× faster pages expected]; perf pass #2 DONE 2026-08-27
  [webtoon confirm fix: findFirstVisibleItemPosition, region-level auto-scroll
  via TtsEvent.ScrollToRegion, pause/resume page-awareness] — all gates green,
  build 0.4.0-8234 installed. 2026-08-28: FRESH logcat analysis COMPLETE.
  Phase 9 fixes VERIFIED: findFirstVisibleItemPosition ✓, single-flight ✓,
  task-owned bitmap ✓, pause/resume page-awareness ✓, tile parallelism ✓.
  Known issues #8 + #10 RESOLVED. P0 prefetch spam loop + P1 rapid-swipe mass
  OCR FIXED (committed 872c55397/8cb320e7c). Build 0.4.0-8236 run2/run3
  verified advance-confirm/stale-page fixes; hands-off duplicate speech USER
  DROPPED (LOW PRIORITY post-build). TTS-DBG removed. Script steps 7–15
  EXECUTED + USER-CONFIRMED (RUN4 build 0.4.0-8238) — Phase 8 device script
  COMPLETE; exit-to-idle 550ms measured; prefetch-DNS fix (Finding #4)
  DEVICE-VERIFIED; z-order + action logging + prefetch fix committed by user
  (41200022e, be31edb71, 80deac5b8). 2026-08-28: LeakCanary enabled →
  FINDING #5 ~100MB reader leak (engine onFocusEvent retention chain) →
  3-file fix COMMITTED as 5c7d2cc2c (ReaderViewModel onFocusEvent=null,
  ReaderActivity DisposableEffect ioCoroutineScope cancel, build.gradle.kts
  LeakCanary core) — GATES GREEN 2026-08-29 (spotlessCheck +
  compileDebugKotlin + testDebugUnitTest, BUILD SUCCESSFUL 2m56s); memory
  profile captured (meminfo-profile.log, stable); debug APK 0.4.0-8241
  installed 2026-08-29. DEVICE RE-VERIFY 2026-08-29: LeakCanary 0
  APPLICATION LEAKS after two reader TTS sessions + exits (leak-full.log,
  95s analysis, heap 41.8MB) — leakcanary sign-off DONE; session regression
  check clean (advances 1–6ms, 0 timeouts, rapid-nav debounce working,
  startup 3.8s). BATTERY MEASUREMENT 2026-08-29: PASS — 23min realistic
  session (3 chapters, 5 chapter advances, 58/58 page advances confirmed,
  94 on-demand OCR scans, 0 sentence failures; ended by transient GLens 502
  = correct honest-Error) + short cached re-run; batterystats: app fg CPU
  25.4mAh/24m51s (~1mAh/min incl. TTS+OCR+network), screen dominates
  (84.5mAh), keep-screen-on wakelock scoped exactly to reader visibility
  (19m2s), ZERO post-exit app activity or wakelocks, app LMK-reaped
  ~13min post-exit + cached-frozen ~18min, screen-off app CPU ~5mAh/41m
  ≈ noise. Evidence: .device-pass/battery-sample.log,
  battery-tts-session.log, batterystats-app.txt. PHASE 9 COMPLETE — all
  completion criteria met: leakcanary clean, exit-to-idle <1s, documented
  measurements in memory.md. (2026-08-29: fd52a613a docs commit
  accidentally reverted memory/phase docs + broke ReaderActivity import
  order — both repaired.)
- **Objective**: production quality under stress.
- **Tasks**: bitmap lifecycle audit (no retention across suspension points);
  cancellation correctness (swipe-away, chapter switch mid-scan); memory profile
  during long sessions; battery check after exit (no background CPU);
  latency masked by Preparing/LoadingPage states + prefetch.
- **Dependencies**: Phase 8.
- **Completion criteria**: no leaks in leakcanary runs; exit-to-idle < ~1 s audio
  stop; documented measurements in memory.md.
- **Tests required**: repeat device matrix + `:app:assembleDebug` release build.

## Phase 10A — Advanced system TTS voice configuration

- **Status**: COMPLETED (2026-08-31, released in v0.5.x). Code Tasks 1–6 done
  2026-08-30 (spotlessCheck + testDebugUnitTest + :app:compileDebugKotlin GREEN per
  task reports; verifySqlDelightMigration not needed — no DB change).
  Task 7 DEVICE PASS DONE 2026-08-31, build 0.5.0-8250 on SM_M066B:
  USER CONFIRMED all manual checks PASS — Read Aloud playback, language/
  locale selection, voice selection, preview, speech rate, persistence,
  no crashes. Evidence: `.device-pass/tts-10a-test.log` (571k lines:
  advances confirmed 1–2ms, paired audio-focus request/abandon, SystemDefault
  voice-restore path logged ×4, 0 FATAL exceptions, single engine connection
  per session). Follow-up improvement same session: voice-picker SEARCH
  (BasicListPreference `searchable` flag → ListPreferenceWidget filter
  field; gates green, APK installed). Committed by user as 0480778fd.
- **Objective**: user-configurable system TTS engine/voice/language with
  calibration + preview, reader behavior unchanged.
- **Files affected**: `:domain` —
  `TtsVoicePreferences.kt` (+`TtsVoicePreferencesTest`, 5 cases) new,
  `TtsEngine.kt` extended (getEngines/getVoices/setEnginePackage +
  TtsEngineInfo/TtsVoiceInfo); `:app` — `AndroidTtsEngine.kt` (engine-package-
  aware creation, initialize-time config re-apply, voice fallback),
  `ReadAloudSettingsScreenModel.kt` + `SettingsReadAloudScreen.kt` new,
  `SettingsSearchScreen.kt`, `SettingsMainScreen.kt`, `SettingsScreen.kt`,
  `MainActivity.kt`, `Constants.kt`, `ReaderSettingsDialog.kt`,
  `ReadAloudPage.kt`, `ReaderActivity.kt` (both dialog call sites), DI
  (`DomainModule.kt`, `PreferenceModule.kt`); `:i18n` base strings +18 keys.
  (Committed by user as 0480778fd, released in v0.5.0.)
- **Completion criteria**: device verification of the full flow (above)
  executed + recorded in `docs/memory.md`; then user review + commit.
- **Tests required**: `TtsVoicePreferencesTest` (unit, done); full
  `testDebugUnitTest` (done, green); device pass (Task 7, DONE 2026-08-31 —
  user-confirmed PASS, evidence in memory.md).

## Phase 10B — Future engines (backlog)

- **Status**: NOT_STARTED
- **Note**: Phase 10A landed the system-engine voice configuration subset of
  the old Phase 10 block (engines, voices, picker, locale voices, latency/
  quality display, per-engine discovery). What REMAINS from that block and
  from the 2026-08-25 advanced-TTS requirement: per-voice rate/pitch profiles
  (global pair for now — deliberate); cloud TTS provider(s) (credentials,
  billing/cost, network, privacy, streaming/downloaded audio, caching,
  latency management); local neural TTS; downloadable AI voices; expressive
  speech (needs provider SSML/prosody/emotion controls — pitch/rate alone are
  NOT expressive narration, never fake emotion via random pitch); Japanese/
  multi-language preflight as explicit opt-in (not a gate); background
  playback via FGS+MediaSession; on-image bbox highlight; audio caching for
  high-latency engines; porting Glens ordering into local Legacy/Fast scans;
  cross-tile text merge for seam bubbles (Known issue #6); voice-specific
  rate/pitch tuning where technically appropriate.
- **Rule**: each requires a PRD update + architecture review BEFORE coding
  (rules.md §10). None may regress v1/10A behavior.

---

## Deferred features (recorded 2026-09-06 — DO NOT implement without PRD/architecture review)

These were intentionally NOT implemented in the 2026-09-06 post-modernization
feature set and must not be forgotten:

1. **Reader toolbar reordering** — SHIPPED 2026-09-11 as Feature Batch 7;
    reordering + persistence + upgrade-safe defaults + OCR/Read-Aloud
    visibility independence + Settings pinned last; 13 unit tests;
    user-verified on device. CLOSED.
2. **True backdrop blur investigation** — reader floating chrome currently
   uses semantic color roles, NOT true backdrop blur (Compose cannot sample
   the sibling artwork View; fullscreen RenderEffect was rejected on
   performance grounds). Future work must FIRST prove feasibility: rendering
   architecture, performance impact, battery impact, memory impact,
   compatibility, AMOLED/light/dark behavior, reader scrolling performance.
   Do not "just add a blur modifier."
  3. **Artwork-reactive reader tray** — IMPLEMENTED 2026-09-11 (see
    memory.md same-date block): feasibility proven SAFE; minimal blend
    implementation (sample → average → normalize → 8% blend before
    asFloatingChrome, debounced per settled page, IO-only, full fallback
    identity). Gates green; device verification PENDING user. True
    backdrop blur remains REJECTED (unrelated; not the shipped path).
4. **Automatic Feed pagination** — near-end automatic loading, ONLY after
   the explicit Load-more paging (shipped 2026-09-06) is device-tested and
   stable. No auto infinite scroll in the current phase.

---

## Dependency graph

```text
P0 ──> P1 ──> P2 ──┐
        │          ├──> P5 ──> P6 ──> P7 ──> P8 ──> P9 ──> P10A (in progress)
        └──> P3 ───┤
        └──> P4 ───┘
P10B (backlog, gated on 10A completion + PRD/architecture review)
```

Update statuses in this file AND `docs/memory.md` whenever work happens.
