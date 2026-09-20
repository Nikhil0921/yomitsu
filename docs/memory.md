# Yomitsu — Active Memory (Recent Sessions)

> COMPRESSED ACTIVE MEMORY. Contains recent sessions + durable decisions/gotchas.
> Do NOT read this during normal startup — use `state.md` + `rules.md` first.
> Full historical records in `history/session-logs.md`.

---

## Recent sessions (most recent first)

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
14. **OCR engine chain**: GLENS primary (parallel tiles ×3, single-flight per (chapter,page)), FAST local, OWOCR self-host; LEGACY alias→GLENS. Local-engine reinstatement REJECTED (reverses shipped −133MB removal).
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
| 7 | OPEN LOW + CONTRADICTION | Debug-keystore stability. KNOWN ISSUE #7 (08-24): `yomihon-android-home` volume at `/home/vscode/.android` = stable key. YOMUCHU BATCH 2 (09-15): keystore rotated anyway (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`; clean reinstall would wipe app data — user declined device pass). Records CONTRADICT — unresolved; verify volume mount before any device pass; never uninstall without .tachibk restore. |
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

Zero changes. TTS v1 adds none (framework `android.speech.tts` + `android.media`). Key versions in architecture.md §1/§14: Kotlin 2.4.0, AGP 9.2.1, Compose BOM 2026.06.01, SQLDelight 2.3.2, OkHttp 5.4.0, Injekt, JUnit5/Kotest/MockK. New lib → rules.md §5 checklist + record justification here.

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
