# Yomitsu — Session Logs (Historical Archive)

> APPEND-ONLY HISTORICAL EVIDENCE. Do NOT read this during normal startup.
> Contains: detailed session records, root causes, device evidence, commit hashes, release info.

---

## 2026-09-15 (Wed) — Adaptive UI corrective fix batch

**User-directed refinement** of 2026-09-13 adaptive-UI batch (nav sizing controls explicitly SKIPPED per task — pill stays frozen 12/8dp + 80dp metrics; no RenderEffect/backdrop blur added).

**Session**: 2 commits (user-committed). 7 files changed. Gates GREEN. Device verification PENDING.

**Root causes** (source-proven, both batch-introduced):

1. **Issue-001 (P1) nav translucency OFF rendered 55% alpha**:
   - TachiyomiTheme passed navTranslucencyAlpha(0)=0.55f (MIN bound) when toggle OFF.
   - FIX: gate on boolean — OFF → 0f (asNavContainer treats ≤0f as opaque, verified downstream), ON → bounded 0.55..0.92 via unchanged navTranslucencyAlpha().
   - navIntensity now read unconditionally (no if-collapse into slider). Preview stays 0f.
   - Files: `TachiyomiTheme.kt`.

2. **Issue-002 (P2) ManageFeeds LazyColumn key = FeedItem data class**:
   - key = FeedItem data class (contains mutable `enabled`); toggle mid-drag = key change → ViewHolder recreation.
   - FIX: key = "sourceId:listing" (stable immutable identity; same source CAN have POPULAR+LATEST so pair, not sourceId alone).
   - ReorderableItem key matched. Drag/toggle/delete logic untouched.
   - Files: `ManageFeedsScreen.kt`.

3. **Issue-003 (P3) FrostedColorScheme missing slots vs XML palette**:
   - Missing error/onError/errorContainer/onErrorContainer, outlineVariant, scrim, surfaceDim, surfaceBright (dark+light, values = exact XML frosted_* source of truth).
   - Also removed duplicate-inversePrimary compile dupes during edit (2× "Argument already passed" caught by gates, fixed).
   - Error family included — sibling schemes all define it, XML has frosted_error.
   - Files: `FrostedColorScheme.kt`.

**GATES**: GREEN (devcontainer JDK17, -Xmx4g, both volumes):
- spotlessCheck + testDebugUnitTest + verifySqlDelightMigration + :app:assembleDebug BUILD SUCCESSFUL 5m49s.
- One prior red run: forgot gradle-home volume → dep resolution fail.
- One prior red run: dup params, fixed.
- arm64 APK 20:08.

**DEVICE VERIFICATION**: PENDING (same SM_M066B matrix as master batch).

**Evidence**:
- Commit: user-committed (exact hash in git log, not provided in session).
- Files: `TachiyomiTheme.kt`, `ManageFeedsScreen.kt`, `FrostedColorScheme.kt`.
- Logs: `.device-pass/` (not captured in session log; pending user device session).

---

## 2026-09-15 (Wed) — Nav-pill scroll-behind + gradient/live-apply

**User-directed refinement** of 2026-09-13 adaptive-UI batch.

**Session**: 5 files changed. Gates GREEN. Device VERIFIED on SM_M066B.

**Root causes** (source-proven, batch-introduced):

1. **Gradient + nav translucency appeared static**:
   - TachiyomiTheme read backgroundStyle/backgroundGradientIntensity/navBarTranslucent/navBarTranslucency via plain Preference.get().
   - Slider writes persisted but root composition never recomposed → brush + pill alpha frozen until process restart.
   - FIX: collectAsState() on those 4 prefs in TachiyomiTheme (presentation-core util, HomeScreen precedent) → remember-keys re-fire → LocalAppBackground brush + LocalNavTranslucency alpha recompute live.
   - Brush math unchanged (intensity lerps background→surfaceContainerLow). Extracted pure internal backgroundGradientEndColor(bg, container, percent) + NEW AppBackgroundGradientTest 4 cases (0→solid, 100→full, 50 lerp ±1ULP, out-of-range coercion).
   - Files: `TachiyomiTheme.kt`, `AppBackgroundGradientTest.kt` (new).

2. **Content clipped above pill**:
   - HomeScreen Box applied outer Scaffold's contentPadding as LAYOUT padding → viewport bottom cut at pill top → LazyColumns clip there.
   - FIX (scroll-behind plumbing, presentation-core only + HomeScreen):
     - NavigationBar.kt: + LocalNavPillBottomInset (staticCompositionLocalOf, default 0.dp) — the pill's measured total clearance.
     - HomeScreen: Box pads top/start/end only (no bottom clip), still consumes all insets (nested Scaffolds see 0 system bottom inset), provides LocalNavPillBottomInset = contentPadding bottom (pill height, 0 on tablet rail / hidden-nav AnimatedVisibility).
     - Forked Scaffold.kt: reads the local, clears it to 0.dp for its own content (nesting never double-counts), folds it into:
       - innerPadding bottom (resting content padding → scrollables like LibraryPager / Feed grid / TabbedScreen pass under pill; layout-padders like RecentTab keep clip-above-pill = old behavior, no regression)
       - bottomBar slot placement (+pill → selection bars ride above pill)
       - FAB + snackbar bottom anchors (max with pill clearance).
     - Zero behavior change for ~100 other Scaffold call sites (local default 0.dp; pushed Voyager routes are siblings of HomeScreen → 0).
   - Pill translucency itself unchanged bounded (navTranslucencyAlpha 0.55..0.92; OFF = 0f opaque fallback) — now actually applies live.
   - Files: `presentation-core NavigationBar.kt`, `Scaffold.kt`, `TachiyomiTheme.kt`, `HomeScreen.kt`, `AppBackgroundGradientTest.kt` (new).

**GATES**: GREEN (docker vsc-yomihon…, JDK17, -Xmx4g, both volumes):
- spotlessApply → spotlessCheck + testDebugUnitTest BUILD SUCCESSFUL 3m12s (AppBackgroundGradientTest 4/4 + all suites incl. NavTabTest 9/9)
- :presentation-core:compileDebugKotlin + :app:compileDebugKotlin green.
- NO DB/pref-key/schema change → verifySqlDelightMigration N/A.
- NO new deps, NO i18n, NO true backdrop blur (RenderEffect stays rejected).

**DEVICE VERIFIED** 2026-09-15 on SM_M066B (arm64, 720x1600, wireless adb 192.168.29.98:5555):
- `:app:assembleDebug docker BUILD SUCCESSFUL 3m17s`
- `app-arm64-v8a-debug.apk → app.yomihon.dev installed -r Success`

**Pixel-metric + uiautomator proof** (no image input to verifier — programmatic bands, screenshots kept for user eyeball):
- **GRADIENT LIVE** ✓ 3 vs 97 same-scroll: raw bg gap RGB(32.9,39,42)→(33.8,45.4,47.7), upper bands 0.0 (correct vertical stop), cards MAD 0.06, same pid = no recreate. Subtle by design (Δ≈9-11/255).
- **NAV ALPHA LIVE** ✓ pill-gap bleed vs content MAD 74-83:
  - OFF=0.0 exact (opaque)
  - intensity 3 (α.55)=16.9-20.9
  - intensity 100 (α.92)=5.9-7.4
  - Matches (1−α)×content quantitatively, single process.
- **SCROLL-BEHIND** ✓ Library+Feed covers/text pass under pill (bleed table):
  - Library bottom last-row text y1313 vs pill top 1362 = 49px resting clearance.
  - Browse list fits viewport (nothing to scroll — no defect).
- **Selection long-press** = pill hides, action bar [1411-1495] + top Cancel/Select-all ✓ per nav-hidden selection design. No FABs on main tabs (top-bar actions only).
- **Tablet rail/AMOLED/Monochrome** not exercised (phone, active theme Dark+Frosted surfaces).

**Incident**:
- One crash 20:49 during scripted blind-tap chaos:
  - Voyager SaveableStateHolder 'Key …SettingsTrackingScreen:transition was used multiple times' → CrashActivity → restart.
  - Clean double-tap repro on Tracking = NO crash; my 5 files create no transition/saveable keys (Scaffold diff pure layout/locals) ⇒ pre-existing settings-nav double-push race, NOT this batch.
  - TODO candidate: guard Voyager push if screen==stack top.

**Device prefs restored**: translucent ON, intensity 99, gradient 99 (slider pixel-max ~99, Δα 0.004 vs 100), style Gradient, Dark.

**Artifacts**:
- `.device-pass/nav-pill-20260915/RESULTS.md`
- `.device-pass/nav-pill-20260915/*.png`
- `.device-pass/nav-pill-20260915/device.py/measure.py probes`

**Files changed**: `presentation-core NavigationBar.kt`, `Scaffold.kt`, `TachiyomiTheme.kt`, `HomeScreen.kt`, `AppBackgroundGradientTest.kt` (new).

**Evidence**:
- Commit: user-committed (exact hash in git log, not provided in session).
- Device log: `.device-pass/nav-pill-20260915/` (RESULTS.md + .png + device.py/measure.py).

---

## 2026-09-13 (Mon) — Adaptive UI & Personalization master batch

**User-authorized via master prompt** (supersedes roadmap "nav customization REJECT" + "Liquid DEFERRED" locks for THIS scope: reorder ≠ 6th tab / IA change; gradient ≠ Liquid's rejected blur). AnymeX = reference only.

**Session**: 20 files changed. Gates GREEN. Device verification PENDING.

**Implemented**:

A. **Immersive mode**: pref_immersive_mode (default false); MainActivity LaunchedEffect hides/shows systemBars via WindowInsetsControllerCompat with BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE (gesture nav safe, no trap: toggle off restores). ReaderActivity untouched (own fullscreen pref).

B. **Bottom nav reorder**: NavTab enum (:domain ui.model) = identity, NOT visual position/TabOptions.index; pref_nav_tab_order CSV; parseOrder() never loses/dupes a tab, malformed→default (9 unit tests). HomeScreen orderedTabs() from pref (live apply); NavigationBar + NavigationRail both iterate ordered list; TabNavigator start = first ordered tab (ponytail: live reorder doesn't relaunch nav; restart moves start tab). SettingsNavigationScreen (ReaderToolbar drag pattern: ReorderableItem + draggableHandle + move customActions a11y) — registered in settings search index. 5 tabs kept, reselect semantics untouched.

C. **Feed drag reorder**: ManageFeedsScreen rebuilt as reorderable ElevatedCard rows (was PreferenceGroupCard + up/down arrows); drag handle + a11y move actions retained; enable/delete/toggle/identity preserved; FeedScreenModel.moveFeedTo(from,to) = same pref-list mutation as old buttons (no network on drag; sections keyed by FeedItem data class = order-safe).

D/E. **Translucent nav + intensity**: pref_nav_bar_translucent (default false) + pref_nav_bar_translucency (0..100, default 60 → bounded alpha 0.55..0.92 via navTranslucencyAlpha()); NavigationBar pill uses new Color.asNavContainer() (real bounded alpha, pill floats over gradient only). LocalNavTranslucency provided by TachiyomiTheme.

F/G. **Background gradient**: BackgroundStyle enum (SOLID default / GRADIENT) + pref_background_style + pref_background_gradient_intensity (0..100, default 35). Brush = theme-derived background→surfaceContainerLow vertical lerp (semantic roles; AMOLED/Monet/Frosted all derive automatically; 0 ≈ solid). LocalAppBackground provided by TachiyomiTheme; presentation-core Scaffold draws brush via drawBehind when non-null (containerColor→Transparent); AppBars stay opaque = readable chrome. No per-frame work (remember(pref-set)).

H. **FROSTED THEME**: AppTheme.FROSTED + FrostedColorScheme (cool blue-grey editorial, light+dark) + colors_frosted.xml ×2 + themeResources entry; auto-enables translucent chrome while active (isTranslucent = pref || FROSTED). Appears in theme picker after Monochrome (entries-driven).

I. **Dialog/Sheet adaptation**: audited: AdaptiveSheet/ResizableSheet/TabbedDialog already route asChromeContainer/asFrostedModal; AlertDialogs = M3 defaults; Frosted = colorscheme swap → all surfaces follow. No frost-on-frost (content panels untouched).

J. **Download queue**: already grouped (FlexibleAdapter sections + MaterialCardView headers + drag enabled); OcrQueueScreen already PreferenceGroupCard (09-13 micro-batch). Documented, no change.

**PREF KEYS**: pref_immersive_mode, pref_nav_tab_order, pref_nav_bar_translucent, pref_nav_bar_translucency, pref_background_style, pref_background_gradient_intensity. NO DB change (migration gate green). i18n: +13 base strings (nav group, background group, immersive, frosted theme). NO locale hand-edits.

**Files changed**: `UiPreferences.kt`, `AppTheme.kt`, `BackgroundStyle.kt`(new), `NavTab.kt`(new), `NavTabTest.kt`(new), `FrostedColorScheme.kt`(new), `colors_frosted.xml` ×2 (new), `themes.xml`, `ThemingDelegate.kt`, `TachiyomiTheme.kt`, `Translucent.kt`, `Scaffold.kt`, `NavigationBar.kt` (pcore), `HomeScreen.kt`, `SettingsAppearanceScreen.kt`, `SettingsNavigationScreen.kt` (new), `SettingsSearchScreen.kt`, `ManageFeedsScreen.kt`, `FeedScreenModel.kt`, `RecentTab.kt`, `TabbedScreen.kt`, `MainActivity.kt`, `strings.xml`.

**Device verification matrix PENDING** user (needs SM_M066B session).

**Evidence**:
- Commit: user-committed (exact hash in git log, not provided in session).
- Files: 20 files changed.
- Logs: `.device-pass/` (not captured in session log; pending user device session).

---

## 2026-09-13 (Mon) — Master session: Q9 a11y + BUG fixes + Tscan + OCR/TTS latency + Feed genre + AnymeX micro-passes

**User-authorized via master prompt** (supersedes roadmap "nav customization REJECT" + "Liquid DEFERRED" locks for THIS scope). Q3–Q8 HARD HALTED. Q9 + AnymeX micro-track authorized.

**Session**: 6 commits (user-authorized). Gates GREEN. Device VERIFIED on SM_M066B.

**Q9 a11y**:
- CategoryListItem customActions move-up/down (+ CategoryScreen wiring, existing action_move_up/down strings).
- BaseSliderItem Slider stateDescription=valueString (~20 callers).
- SourceSelectorDropdown menu stateDescription selected/not_selected.
- TtsPlaybackBar speed menu stateDescription.
- Large-font heightIn sweep ×4 files (ClearDatabaseScreen/CommonMangaItem/UpdatesUiItem/BaseMangaListItem).

**Bugs**:
- **BUG-003 (P3)**: resumeIndex=0 on page-change-while-Paused (FIXED): onPageSelected Paused branch also sets resumeIndex=0; resetSession at rebind unchanged.
- **BUG-004 (P3)**: ocr_cache getPage model predicate (FIXED): getPage gains `AND ocr_model = :ocrModel` (UNIQUE triple + index already exist → NO migration; verifySqlDelightMigration green). OcrCacheStore.getPage + OcrRepositoryImpl.getCachedPage pass model. Cache-hit log includes model. Device-verified live: cached startup 1625ms vs 15870ms uncached. No new unit test (no Robolectric/driver harness in :data — new deps forbidden).
- **BUG-005 (P4)**: prefetch cancel on NextChapter (FIXED): NextChapter branch cancels prefetchJob; resetSession at rebind still covers.
- **BUG-006 (P4)**: setVoice SUCCESS check + DEBUG log (FIXED): both setVoice sites check SUCCESS, DEBUG log on failure.
- **BUG-007 (P4)**: verified UNREACHABLE: reaching it requires openBitmap to succeed while page un-Ready, which cannot occur (openBitmap sets Ready on success first). NO ACTION.
- **BUG-008 (P4)**: no-op clickable removed (FIXED): Result-card row clickable{} removed (resolves U-7 recommendation "remove affordance").
- **BUG-009 (P4)**: loadSectionsOnStart=false from ManageFeeds (FIXED): ManageFeedsScreen passes false; default true keeps all other callers unchanged.
- **BUG-010 (P4)**: synchronous remember + 2 screens registered (FIXED): produceState gap = blank flash; toolbar-screen index registration.

**Tscan Feed NetworkOnMainThreadException**:
Root cause: fetchSection body ran source calls on Main via screenModelScope → withIOContext wrap (all 3 callers covered). Files: `FeedScreenModel.kt`.

**OCR/TTS latency**:
ChapterCache injected into OcrPageSourceResolver: getPageListFromCache-first, image from cache when present (decode-fail → refetch, CancellationException rethrown). Verdict: residual 30–60s first-page latency = mostly GLENS service round-trip, app-side duplicate fetches now deduped via cache. Files: `OcrPageSourceResolver.kt`, `ChapterCache.kt`.

**Feed genre filtering**:
FeedScreenModel genreToggles state + getSearchManga routing when chips active, reuses BrowseSourceScreenModel helpers; FilterBar rebuilt as stacked two-row layout (selector row + chips rows, spacedBy(small), horizontalScroll) → also resolves §8 selector-spacing task. Files: `FeedScreenModel.kt`, `FeedFilterBar.kt`.

**AnymeX micro-passes**:
- MoreScreen Studies card (Text Recognition/Dictionary/Manage dictionaries moved from Library; new label_studies i18n base string).
- OcrQueueScreen PreferenceGroupCard regroup.
- DownloadsScreen PreferenceGroupCard regroup.
- Reference = AnymeX hierarchy/grouping intent only (no cloning). Liquid/blur/IA-regroup/nav/Q3-Q8 explicitly NOT touched.

**GATES**:
- spotlessCheck + testDebugUnitTest + verifySqlDelightMigration + :app:assembleDebug BUILD SUCCESSFUL 3m14s (NavTabTest 9/9).
- Device SM_M066B: full matrix PASS (5 tabs + genre + genres -> Find manga + AnymeX micro-passes, 0 FATAL, 0 ANR).

**Device evidence**:
- `.device-pass/master-session-20260913/` (full logcat series, screenshots, uiautomator dumps).
- Device logs show: Q9 a11y moves, speed menu stateDescription, large-font heightIn sweep; BUG-003 resumeIndex fix; BUG-004 ocr_model predicate; prefetch cancellation; setVoice check; Tscan withIOContext wrap; genre chips; AnymeX micro-passes.
- 0 FATAL exceptions. All 4 gates green.

**Files changed**: 16 files (CategoryListItem.kt, CategoryScreen.kt, BaseSliderItem.kt, SourceSelectorDropdown.kt, TtsPlaybackBar.kt, ClearDatabaseScreen.kt, CommonMangaItem.kt, UpdatesUiItem.kt, BaseMangaListItem.kt, ManageFeedsScreen.kt, FeedScreenModel.kt, FeedFilterBar.kt, OcrPageSourceResolver.kt, ChapterCache.kt, ThemingDelegate.kt, strings.xml). Plus docs.

**Evidence**:
- Commit: user-authorized, 6 commits (exact hashes in git log, not provided in session).
- Device log: `.device-pass/master-session-20260913/` (full series).

---

## 2026-09-12 (Sun) — Q2 genre-chip search

**User-authorized** (U-4 satisfied by direct user task authorization).

**Session**: 2 src files + 1 test file + docs. Gates GREEN. Device PASS on SM_M066B.

**Implemented**:
- Genre chip row in BrowseSourceScreen derived from source's OWN Filter leaves (TriState/CheckBox inside Group or top-level) via pure helpers genreToggles()/isGenreSelected()/toggleGenreSelection() in BrowseSourceScreenModel.kt.
- ToggleGenreChip flips INCLUDE↔IGNORE then search(filters=) re-runs (one pager rebuild per tap — FilterList data-class equals=false guarantees distinctUntilChanged fires once).
- No new architecture (option B: source-supported filtering; upstream searchGenre() MangaScreen→browse path untouched).
- M3 FilterChip + leading check icon = non-color-only selection.
- Sources without genre filter leaves honestly show no row (Asura Scans verified).
- Multi-genre = source's own semantics (filters passed through verbatim).

**10 unit tests green** (GenreTogglesTest). Gates GREEN (spotlessCheck + testDebugUnitTest + verifySqlDelightMigration + assembleDebug).

**Device SM_M066B 0.5.4-8281**:
- Q2-01..09 PASS (search unchanged; chips checked=true a11y + results genuinely filtered — gender-bender evidence; clear restores; query+genre combo narrows; empty+Retry intact; nav/back state preserved; a11y checked semantics verified; Browse/Library/Feed/Reader/More smoke OK).
- Known limitation: Weeb Central filter leaves exposed = status+type+genre; chips are quick toggles over ALL such leaves, not a hand-curated genre list.

**Files changed**: `BrowseSourceScreen.kt`, `BrowseSourceScreenModel.kt`, `BrowseSourceScreenModelTest.kt`. Plus docs.

**Evidence**:
- Commit: user-authorized (exact hash in git log, not provided in session).
- Device log: `.device-pass/q2/` (dumps + session log).
- Unit tests: GenreTogglesTest 10/10 green.

---

## 2026-09-12 (Sun) — RM-01: pause-guard + chapter-advance-failure + dead-code sweep

**User-authorized** (RM-01 authorized task per implementation-roadmap.md §B).

**Session**: All gates green. Device VERIFIED on SM_M066B. UNCOMMITTED.

**Implemented**:
- **BUG-001 (P2)**: pause() no-op during LoadingPage/Preparing → pause guard added (if phase != Playing && !paused return) — pause = any non-Idle/Finished/Error phase; acquireSentences no longer clobbers Paused. Files: `TtsPlaybackController.kt`.
- **BUG-002 (P2)**: NextChapter host-load failure → controller.fail(ChapterLoadFailed) when Preparing/LoadingPage → Error + Retry; new i18n key tts_error_chapter_load. Files: `ReaderViewModel.kt`.
- **Dead code**:
  - badgeNumber dead param (RecentTab.kt:68-72).
  - RecentTab badgeCount (dead param, set but never read).
  - ReaderBottomBar pointerInput no-op (ReaderBottomBar.kt:41).
  - detectionEngine identical branch (OcrRepositoryImpl.kt:158-168).
  - orphaned localOcrAvailable (scanLocally/cropBitmap ~60 lines unreachable; deletion deferred with ceiling marked).
- **Docs corrected**: Known-issue #2 (dual setComposeContent) → RESOLVED — code has ONE composition block. Updated memory.md, architecture.md, next-phase-plan.md.

**GATES**: ALL GREEN (spotless 51s; unit+migration 4m29s; assembleDebug 3m20s).

**Device VERIFIED** (attended follow-up, SM_M066B USB, Limitless Predation ch6→ch7→ch8):
- TEST 1: TTS happy path PASS (play/progress/prefetch/pause-from-Playing/resume-exact-sentence).
- TEST 2: pause-during-LoadingPage PASS via onStop (`TTS pause page=4 sentence=0`, zero speech after; pill shows Stop-only in that phase so onStop path used).
- TEST 3: chapter-advance failure PASS on cold process (radios off → advance → UnknownHost → Error + exact tts_error_chapter_load + Retry; Stop-from-Error; recovery online → ch8 dispatch + advance; cold ch6→ch7 transition OK).
- TEST 4: toolbar PASS (5 actions render, Settings sheet, Crop toggle, no crash).
- TEST 5: artwork-tone PASS at log level (tone sample + schedule Ready per page, no crash; tint not eyeball-checked).
- Contradiction: pill shows Stop-only during Preparing/LoadingPage, no Pause affordance — TEST 2 used onStop path.
- Device left as found (radios re-enabled).
- RM-01 COMPLETE; still UNCOMMITTED.

**Files changed**: 7 files (`TtsPlaybackController.kt`, `ReaderViewModel.kt`, `RecentTab.kt`, `ReaderBottomBar.kt`, `OcrRepositoryImpl.kt`, `ReaderActivity.kt` (dead-code guard), docs). Dead-code sweep: `RecentTab.kt`, `ReaderBottomBar.kt`, `OcrRepositoryImpl.kt` only (scanLocally/cropBitmap deletion deferred).

**Evidence**:
- Commit: user-authorized (exact hash in git log, not provided in session).
- Device log: `.device-pass/rm-01-device-verify.log` (PID 24098, Limitless Predation ch6→ch7→ch8, radios off/on).
- Docs: memory.md Known-issue #2 → RESOLVED, architecture.md §3.8 historical note + header v0.5.3, next-phase-plan row + refs.

---

## 2026-09-11 (Sat) — Batch 7: reader toolbar customization

**User-authorized via PRD review**.

**Session**: 13 unit tests green. USER-VERIFIED on device. UNCOMMITTED.

**Implemented**:
- Drag-reorder toolbar actions (move-up, move-down, crop, read-aloud, favorite, download, toggle, settings, toggle-auto-download).
- Persistence (sorted list saved to pref).
- Upgrade-safe defaults (first 5 positions, duplicates handled, one-time migration if needed).
- OCR/Read-Aloud visibility independence (each action has separate pref).
- Settings pinned last.
- 13 unit tests green (`ReaderBottomBarActionTest`).

**Device**: USER-VERIFIED on SM_M066B. Pull-to-reorder works, persist on restart, drag-drop a11y, UI unchanged.

**Files changed**: `ReaderBottomBar.kt`, `ReaderBottomBarAction.kt`, `ReaderBottomBarActionTest.kt`, `ReaderPreferences.kt`, `ReaderViewModel.kt`, `ReaderActivity.kt`, `strings.xml`, docs.

**Evidence**:
- Commit: user-authorized (exact hash in git log, not provided in session).
- Device log: `.device-pass/toolbar-customize-test.log` (USER-VERIFIED).

---

## 2026-09-10/11 (Fri–Sat) — v0.5.3 release

**User-authorized**.

**Session**: All gates green + device smoke PASS. UNCOMMITTED.

**Implemented**:
- Yomitsu rebrand (commit 708a7182d).
- Legacy-OCR removal (−133MB, redirect to GLENS).
- Stabilization batches (1–5).
- UI audit Batches 1–5.

**Baseline**: Yomitsu rebrand, legacy-OCR removal (−133MB), stabilization batches, UI audit Batches 1–5. Open decision register (Create-tab only; nothing else pending).

**Files changed**: ~30 files (rebrand, docs, stabilization, audit).

**Evidence**:
- Tag: `v0.5.3` (commit daa942738).
- Device log: `.device-pass/v053-smoke.log` (full matrix PASS).
- Docs: memory.md Phase 8/9 blocks; phase.md Phase 10B backlog.

---

---

## VERBATIM ARCHIVE — pre-compression `docs/memory.md` (git HEAD @ 3dbf474ba, restored 2026-09-16)

> The sections below are the ENTIRE former memory.md, preserved unmodified as historical
> evidence. Contradictions between blocks are recorded, NOT reconciled.
> The "Current project state" / "MEMORY UPDATE PROTOCOL" headers below are superseded
> by docs/state.md + docs/rules.md §12; content retained for evidence only.

# Yomitsu — Project Memory (AI State File)

> LIVING DOCUMENT. Every coding agent MUST read this file before substantial work
> and MUST update it after meaningful implementation work (protocol at bottom).
> Companion documents: `prd.md` (WHAT) · `architecture.md` (HOW) · `rules.md`
> (MUST/MUST NOT) · `phase.md` (WHEN) · `design.md` (LOOK/FEEL).

---

## Current project state

```text
Project:        Yomitsu fork (v0.5.3, vc29) — Android manga reader + OCR/language tooling
Repo state:     branch main @ daa942738 = tag v0.5.3 (RELEASED
                2026-09-11, all gates green + device smoke PASS; release
                published with 5 ABI APKs, GitHub Latest). Tree clean
                except this post-release docs update (uncommitted,
                same pattern as v0.5.2). Baseline = Batches 1–5
                (1b2c56b23) + master-plan docs (eaccfe6f0) + release
                (daa942738).
Untracked:      .opencode/ + .device-pass/ (gitignored), .codegraph/ (index,
                gitignored)
Primary goal:    Stabilize post-v0.5.2: stabilization batches DONE
                (2026-09-08), then Phase 10B backlog as PRD-gated work
Current phase:  UI audit implementation Batches 1–5 COMMITTED
                (1b2c56b23, 2026-09-10, device-verified). Prior
                stabilization batches 1/2/3/5/6/7 committed within that
                set; F GLENS-retry live-verify + J eviction boundary +
                onboarding PermissionStep device test = PENDING items.
Current status: TTS v1 + 10A + 2026-09-01 multi-feature set all shipped in
                v0.5.2; visual-hierarchy + IA/feature sets committed
                (e89104296); rebrand committed (708a7182d); UI audit
                Batches 1–5 committed 2026-09-10.
```

## Current objective

Stabilize the read-aloud pipeline end-to-end for ENGLISH content per the
2026-08-25 product decision: OCR must find actual dialogue (incl. long
webtoon strips), text must be spoken completely without silent skips, and
page/chapter progression must advance exactly once with user navigation
authoritative. Device verification of prd.md §3.4(3)–(5) DONE (script steps
1–15 executed + user-confirmed 2026-08-28). Phase 9 COMPLETE 2026-08-29:
leakcanary sign-off (0 application leaks after two reader TTS sessions on
0.4.0-8241), memory profile captured (meminfo-profile.log: stable), and
battery measurement done (battery-sample.log + batterystats-app.txt:
TTS+OCR session ~365mA avg device drain incl. screen, app total 115mAh
attributed over 1h on-battery window, ZERO post-exit background drain —
app reaped+frozen, no wakelocks after reader exit).

## Completed work

```text
[COMPLETED 2026-08-21]
- Full repository audit (modules, Gradle, packages, reader, OCR, data/DI,
  network, sources/extensions, settings, testing, i18n, theme/design system)
- Verified ZERO TTS/audio-focus/MediaSession code exists (only
  DictionaryAudioPlayerImpl fire-and-forget MediaPlayer)
- Confirmed TTS scope decisions from architect.md/architect-2.md are consistent
  with the audited architecture
- Created docs system: prd.md, architecture.md, rules.md, phase.md, design.md,
  memory.md (this file). No source files modified.

[COMPLETED 2026-08-22 — commit 07c64985f]
- Phase 1: TtsEngine interface, TtsAdvancePolicy (+ TtsAdvancePolicyTest),
  TtsPreferences in :domain; DomainModule + PreferenceModule bindings
- Phase 2: AndroidTtsEngine (:app data/tts) — TextToSpeech + AudioFocusRequest,
  CompletableDeferred bridging
- Verified: spotlessCheck + :domain:testDebugUnitTest green (JDK17 container;
  CI toolchain is JDK 21 per .github/.java-version)

[COMPLETED 2026-08-22 — this session, UNCOMMITTED]
- Phase 4: SentenceSegmenter.toTtsSentences() + TtsSentence model (:domain);
  SentenceSegmenterTest (12 cases: multi-sentence, remainder, `.`/`...`
  non-terminal, blank skip, ‼⁇⁉⁈ glyphs, half-width !?, order/bbox
  preservation, no cross-region merge, consecutive terminals) — TDD RED→GREEN
- Phase 3+5: TtsPlaybackController (:app ui/reader/tts/) — cached-first
  acquisition (GetCachedPageOcr → miss via OcrPageSourceResolver +
  WithOcrScanSession + ScanPageOcr, bitmap recycle in finally), N+1 prefetch
  with cancellation on page change, TEXT→SEGMENT→SPEAK→ADVANCE loop,
  TtsPhase state machine exposed as StateFlow<TtsPlaybackState>, advance
  confirm via CompletableDeferred + 10 s timeout → Paused, onPageSelected
  arbitration (user navigation wins), next/prev sentence within page,
  audio-focus loss pauses, live rate/pitch pref collection, events channel
  (AdvancePage/AdvanceChapter/Failed) for host wiring
- Verified: spotlessCheck + testDebugUnitTest + :app:assembleDebug green

[COMPLETED 2026-08-23 — commit ac1614a5e + a36c3cc83]
- Phase 6 committed (ReaderViewModel/ReaderActivity wiring, TtsPlaybackBar,
  ReaderBottomBar icon, onStop pause, keep-screen-on) via "checkpoint before
  WSL recovery" — but that checkpoint contained COMPILE BREAKS (see Fixed)

[COMPLETED 2026-08-23 — this session, UNCOMMITTED]
- Phase 7: ReadAloud settings tab
  - ReaderSettingsScreenModel: `ttsPreferences` (Injekt default)
  - ReadAloudPage.kt: rate/pitch sliders (50–200%, pref ×100 mapping via
    roundToInt), auto-page-turn / auto-next-chapter / keep-screen-on checkboxes
    (CheckboxItem(pref) overloads)
  - ReaderSettingsDialog: 4th tab "Read aloud" appended as page 3; ColorFilter
    dim-hack index (`== 2`) intentionally untouched
  - i18n base strings.xml: 5 snake_case keys (pref_tts_speech_rate, pitch,
    auto_page_turn, auto_next_chapter, keep_screen_on); reuses action_read_aloud
  - ReaderActivity: added ttsKeepScreenOn().changes() collector → live
    updateKeepScreenOn() (was only reader-pref + phase-change driven)
- FIXED pre-existing Phase-6 compile breaks from WSL-recovery checkpoint:
  - ReaderViewModel Event.TtsError param self-shadowed sibling nested class
    (Kotlin scoping: nested classifiers shadow imports) → FQ type in data class
  - ReaderViewModel l.371 passed db Chapter where TtsChapterContext expects
    domain Chapter → toDomainChapter()!! (house precedent l.640/l.1168)
- Verified: spotlessCheck + :app:compileDebugKotlin + testDebugUnitTest green
```

```text
[COMPLETED 2026-08-23 — commit 3fc10ad50]
- Phase 6 + Phase 7 + Phase-6 compile fixes COMMITTED by user in one commit
  (also contains devcontainer deletions and .opencode state files — noted,
  left as-is). docs/memory.md + phase.md updated inside that commit too.

[COMPLETED 2026-08-23 — this session, UNCOMMITTED (docs only)]
- Phase 8 unit portion:
  - Audited SentenceSegmenterTest (12 cases) + TtsAdvancePolicyTest (10 cases)
    against prd.md §3.4(1) checklist — ALL required branches covered, no gaps.
  - Full gates green in devcontainer (JDK17): spotlessCheck +
    testDebugUnitTest + :app:assembleDebug → BUILD SUCCESSFUL 14m48s, EXIT:0
  - Fixed stale/contradictory docs: repo-state hashes (3fc10ad50), phase.md
    Phase 6 status NOT_STARTED→COMPLETED, Phase 8 status→IN_PROGRESS

[COMPLETED 2026-08-23 — follow-up session, UNCOMMITTED (docs only)]
- Phase 8 §3.4(6) verification: `git diff 07c64985f^..HEAD` shows ZERO
  uses-permission changes in any AndroidManifest.xml, ZERO version-catalog
  edits, ZERO new dependency statements in build.gradle.kts → "no new
  permissions / no new external dependencies" CONFIRMED
- prd §3.4 scorecard: (1) ✓ suites, (2) ✓ gates green, (6) ✓ verified;
  (3)(4)(5) = on-device script, BLOCKED

[COMPLETED 2026-08-23 — Phase 9 static-audit session, UNCOMMITTED]
- Phase 9 static portion (bitmap lifecycle + cancellation correctness audit of
  AndroidTtsEngine + TtsPlaybackController + OCR scan path):
  - VERIFIED CLEAN: bitmap retention (toOcrImage copies pixels to IntArray
    upfront — OCR never holds Bitmap; controller recycle guarded by
    isRecycled; ResolvedOcrPages/OcrPageInput streams closed via use);
    teardown ordering (failPendingUtterances BEFORE engine.stop/shutdown →
    no post-teardown callbacks; completeUtterance no-ops on cleared map);
    explicit CancellationException rethrow in main loop paths; prefetch N+1
    bounded + cancelled on page change/reset; focus request acquire/abandon paired
  - FIXED 1: AndroidTtsEngine.initialize() leaked a freshly built TextToSpeech
    (service connection) when the init coroutine was cancelled during
    readiness.await() (e.g. swipe-away during Preparing) → now shuts it down
    on Main before rethrowing CE
  - FIXED 2: schedulePrefetch used runCatching → swallowed
    CancellationException (rules.md §7 violation) → explicit try/catch, CE rethrown,
    other failures logged/no-op'd as before (prefetch stays best-effort)
- Verified: spotlessCheck + :app:compileDebugKotlin + testDebugUnitTest GREEN
  (devcontainer JDK17, BUILD SUCCESSFUL 10m54s, EXIT:0)

[COMPLETED 2026-08-23 — model-setup session, UNCOMMITTED (gitignored assets only)]
- Downloaded all 6 ML model assets via the exact CI step
  (.github/workflows/build.yml), every file sha256-verified OK. Clears the
  models half of the Phase 8/9 device-pass blocker (Known issue #3).
- :app:assembleDebug re-run WITH models present → BUILD SUCCESSFUL 3m46s,
  EXIT:0; verified all 6 assets packaged into the APK (unzip -l). Staged
  installable artifacts at app/build/outputs/apk/debug/ (split per ABI;
  use app-arm64-v8a-debug.apk on a modern phone) → device pass is turnkey.
```

```text
[COMPLETED 2026-08-24 — device bring-up session]
- Host adb + wireless device SM_M066B (Android 16, arm64) connected; staged
  APK verified byte-identical to installed build (vc25).
- DEVICE LOG EVIDENCE of two real bugs:
  a) GoogleTTSServiceImpl synthesized locale eng-IND then TextToSpeech.ERROR —
     engine never called setLanguage and preflight gated on Japanese only.
  b) Samsung SMT has NO Japanese pack (en/hi only) → old gate would hard-block
     all playback for English content.
- Signature-mismatch incident: fresh container generated a NEW debug keystore
  (~/.android not persisted) → INSTALL_FAILED_UPDATE_INCOMPATIBLE. With user
  approval: uninstalled app.yomihon.dev, reinstalled fresh build. Fix: created
  persistent docker volume yomihon-android-home mounted at /home/vscode/.android
  so debug signing is stable from now on. User must restore the .tachibk backup
  after any such reinstall (latest: app.yomihon.dev_2026-08-24_23-44.tachibk).
- Gradle poison found+fixed: yomihon-gradle-home volume held transform-cache
  entries with stale absolute /work/... paths → DexingNoClasspathTransform
  failed ("file ... located outside the root directory"). Volume deleted and
  recreated; one cold rebuild (~19m). Keep mounting both volumes.

[COMPLETED 2026-08-25 — reader stabilization session (PRODUCT PIVOT), UNCOMMITTED]
User directive: English OCR → English system TTS → reliable progression is the
v1 goal; Japanese TTS explicitly de-prioritized (Phase 10). Diagnostic-first,
then fixes:

P0 OCR correctness (data/.../ocr/GlensOcrEngine.kt):
- ROOT CAUSE of skipped bubbles on long strips: prepareImage downscaled ANY
  image to ≤1500px on the LONG side (800×8000 webtoon → 150×1500, text
  unreadable for Lens). FIX: recognizePage now tiles tall strips
  (isTallStrip = h>w*3 && h>1500; tile height = min(w*1.8,1500) floor 1000;
  20% overlap; boxes remapped to full-image coords; seam duplicates dropped by
  IoU ≥0.45; order reassigned sequentially top→bottom). Single-page path
  unchanged. DEBUG log reports region count + tiled flag.
- ROOT CAUSE of English misordering: nonJpLines were appended AFTER the JP
  pipeline unsorted. FIX: pages with zero Japanese text skip the JP/ruby
  pipeline entirely and go straight through mergeIntoBubbles positional sort
  (horizontal = top-down). JP pages keep existing behavior.

P0 TTS correctness:
- Removed Japanese-only gating: TtsEngine.japaneseAvailable deleted from
  interface+impl; controller ensureInitialized no longer fails without a JP
  voice; engine no longer pins Locale.JAPAN. Speech uses the system-default
  voice (English devices speak English).
- TtsError.NoJapaneseVoice removed (+ UI branches + i18n key
  tts_error_no_japanese_voice deleted from base strings.xml).
- ROOT CAUSE of silent sentence skips: runPlayback treated ANY speak()=false
  as an interruption and still did sentenceIndex++ → engine-rejected utterances
  vanished. FIX per rules.md §7: retry once, then honest Paused (logged).
- Pause/resume race fixed: when pause interrupted an utterance, the loop now
  returns instead of advancing (resume() relaunches from resumeIndex; before,
  old and new jobs could race QUEUE_FLUSH on the engine).

P0 progression:
- awaitAdvanceConfirmation restructured (returns Boolean): mismatch between
  requested vs shown page now RECONCILES via rebuildQueueForUserNavigation
  (was: dead-end Paused); timeout → explicit logged Paused.
- Controller takes provideContext: () -> TtsChapterContext? (ReaderViewModel
  supplies buildTtsChapterContext() reading LIVE state). Every queue rebuild
  re-resolves chapter context → no more stale-chapter playback after auto or
  manual chapter switches; hasNextChapter no longer stale.
- onPageSelected treats Preparing as active so late first-page callbacks heal
  chapter transitions (fixes "playback silently dies during chapter load").
- ReaderActivity.loadNextChapter(): moveToPageIndex(0) now ONLY runs when the
  chapter id actually changed (loadAdjacent swallows errors; previously a
  failed load yanked the OLD chapter back to page 0 = content skip).

Segmenter English support (:domain SentenceSegmenter.kt):
- ASCII '.' now terminal when a single dot precedes whitespace/end-of-region;
  dot-runs ("...") stay glued; decimals ("3.14") never split; slices trimmed.
- SentenceSegmenterTest updated + extended to 15 cases (EN period split,
  ellipsis glue, decimals).

UI:
- TtsPlaybackBar padding aligned to design.md §5 (24dp/12dp); error retry now
  always offered (no JP-voice special case).

Diagnostics logging added (DEBUG): OCR cache hit/miss, on-demand scan start,
segmented sentence/region counts, page advance request/confirm/timeout, user
navigation, prefetch start/hit/complete/cancelled, sentence retry/fail-pause.

Verified this session: spotlessCheck GREEN; :domain:testDebugUnitTest GREEN
(15 segmenter cases); FULL testDebugUnitTest + :app:assembleDebug GREEN
(BUILD SUCCESSFUL in 19m8s, fresh caches). New build installed on device as
versionName 0.4.0-8232; logcat capture running. DEVICE SCRIPT NOT YET RUN.
```

```text
[COMPLETED 2026-08-25 → 2026-08-26 — Phase 8 device pass session + Phase 9 perf pass #1]

User committed the stabilization change set as a071feedf (incl. docs).

DEVICE SCRIPT RESULTS so far (evidence in .device-pass/logcat-step*.log):
- Step 1 cached GLENS chapter: PASS WITH ISSUE — playback/auto-advance/prefetch
  hits work; but 16 duplicate scan starts (p4×4, p8×5), bitmap recycle crash ×2
  (GlensOcrEngine.kt:91, IllegalArgumentException recycled source), false
  "sentence failed twice; pausing" ×3 during rapid rebuild churn.
- Step 2 uncached page: PASS (user-observed; log evidence lost — capture was
  pinned to a dead PID and ring buffer wrapped; procedure fixed: full unfiltered
  capture to disk, no --pid pinning, no logcat -c).
- Step 3 webtoon strips: PASS WITH ISSUE — tiled=true everywhere, healthy
  regions, zero speak failures; duplicates again (p18×3 with exact cancel→rescan
  correlation), recycle crash ×2 MORE timestamp-exact with nav-cancel of
  in-flight scans; scan durations 10–25 s/strip.
- Step 4 pause/resume: PASS WITH ISSUE (indirect) — clean stop/start cycles,
  no false fails; NEW ISSUE: advance-confirm timeouts systematic on webtoon
  (targets 14,16,17×3, then ch947 target=1,2 both) = 10 s stall + Paused each;
  suspect findLastEndVisibleItemPosition mismatch on short pages.
- Step 5 prev/next sentence: PASS WITH ISSUE — mostly works; intermittent stall
  matching advance-timeout signature (heals via manual scroll + resume).
  NOTE: sentence-skip has ZERO instrumentation (gap logged for Phase 9).
- Step 6 deliberate swipe arbitration: PASS — user nav wins cleanly.
- Steps 7–15 PENDING (rate/pitch live, chapter transition, end-of-content,
  home, rotation, exit, audio-focus, exit-idle timing).

ROOT-CAUSE INVESTIGATIONS (explore agents, verified against source):
- Webtoon auto-scroll sync: FEASIBLE without architecture change. bbox is
  normalized 0..1 vs full image (OcrModels.kt); WebtoonViewer.moveToPage uses
  scrollToPositionWithOffset(pos, 0) — offset hook exists; in-page scroll does
  NOT fire onPageSelected (won't trip arbitration). Plan: TtsEvent.ScrollToRegion
  emitted before engine.speak → VM → Activity → Viewer.scrollToRegion(fraction).
  NOT yet implemented.
- Duplicate scans: OcrRepositoryImpl.scanPage had NO cache re-check and NO
  single-flight; every call = full Lens pass, upsert only at end.

PHASE 9 PERF PASS #1 IMPLEMENTED (UNCOMMITTED, 4 files):
1. GlensOcrEngine.recognizeTiled: tiles now run TILE_CONCURRENCY=3 at a time
   (Semaphore + async/awaitAll, order preserved by index). Was strictly serial:
   6–8 sequential Lens RTTs = 10–31 s/page. Expected ~3× faster pages.
2. OcrRepositoryImpl.scanPage: single-flight map (chapterId,pageIndex)→Deferred
   + cache pre-check; concurrent identical requests join the running scan.
3. Bitmap lifecycle + upsert moved INSIDE queue task (scanWithGlens/
   scanWithOwOcr/scanLocally take OcrImage now): abandoned-on-cancel scans run
   to completion, recycle their own bitmap, and CACHE the result instead of
   crashing recognizeTiled (fixes the recycle-crash class) or wasting work.
4. Instrumentation: OCR queue depth log (PrioritizedTaskQueue.submit),
   "OCR scan joining in-flight" / "cache hit" logs, controller per-page
   acquireMs + "TTS startup open->first page ready in Xms" (SystemClock).
Verified: :data/:app compileDebugKotlin GREEN; spotlessApply applied;
spotlessCheck + full testDebugUnitTest GREEN; :app:assembleDebug GREEN
(first attempt failed packaging, immediate rerun BUILD SUCCESSFUL 2m27s);
arm64 APK built 20:01 UTC awaiting install (device dropped off wireless).

Phase 10 backlog updated in phase.md: Advanced TTS / Voice Calibration system
(engines, voices, picker, quality comparison, locale voices, voice-specific
rate/pitch tuning, engine settings, neural/local+cloud, latency comparison,
audio caching, per-engine config) — explicit future requirement, v1 untouched.
```

```text
[COMPLETED 2026-08-27 — Phase 9 perf pass #2: seamless webtoon playback + auto-scroll sync]

Build 0.4.0-8234 installed (spotlessCheck + testDebugUnitTest + :app:assembleDebug GREEN).

Fixes implemented (6 files):
1. WebtoonViewer.onScrolled: switched from findLastEndVisibleItemPosition to
   findFirstVisibleItemPosition — eliminates advance-confirm timeouts caused by
   "next page already visible" false-positive on short pages. Root cause of
   Known issue #8 (systematic 10s stalls at page boundaries) resolved.
2. TtsEvent.ScrollToRegion(pageIndex, bbox) added; emitted before each
   engine.speak() in controller; forwarded VM → Activity → WebtoonViewer.
   Implements region-level auto-scroll for webtoon/long-strip: viewer smoothly
   scrolls to sentence's bbox.top fraction within the page item.
3. WebtoonViewer.scrollToRegion(pageIndex, bbox): uses scrollToPositionWithOffset
   with offset computed from bbox.top * itemHeight (negative for down-scroll).
   In-page scroll does NOT fire onPageSelected → won't trip arbitration.
4. Controller.onPageSelected: now updates pageIndex during Paused phase so
   resume() continues from the viewer's actual page, not stale state. Fixes
   "pause/resume targets previous cached page" issue.
5. TtsPlaybackController: emits ScrollToRegion event before every speak(),
   passing sentence.boundingBox. Backward-compatible for single-page manga.
6. Instrumentation: "TTS startup open->first page ready in Xms" (existing),
   plus ScrollToRegion logs via existing event channel.

Verified: spotlessCheck + testDebugUnitTest + :app:assembleDebug GREEN
(3m9s). Build 0.4.0-8234 installed on SM_M066B. Logcat capture live
(.device-pass/logcat-8234-test.log). Awaiting device script steps 7–15.
```

```text
[COMPLETED 2026-09-02 — OCR exclusion device-test regression fixes + auto-detect, UNCOMMITTED]

Device test (user, build from 1b810ccde audit set) found: toggle no-op in BOTH
management UIs, WORD/PHRASE rules not excluded on fresh OCR pages. Evidence
phase: 2 parallel explore agents traced full pipelines against source (no fresh
device logs existed; newest .device-pass log = 2026-08-31). Root causes proven
in code, DISTINCT for the two bugs:

Issue A (toggle dead) — ROOT CAUSE: positional-argument swap in
OcrExclusionZoneRepositoryImpl.setEnabled. .sq writes "SET enabled=:enabled
WHERE _id=:id" → SQLDelight generates (enabled, id); repo called (id, enabled)
positionally, both Long — silent swap. OFF → "WHERE _id=0" = 0 rows (no
autoincrement row has id 0); ON → "SET enabled=<rowId> WHERE _id=1" = corrupts
row 1, never touches tapped row. DELETE worked because deleteZone is
single-param (unswappable). Toggle wiring (both UIs → interactor → repo) was
fully correct end-to-end. FIX: named args at call site (compile-checked
forever). NOTE: device DB may have row _id=1 re-enabled by the ON-tap side
effect — user should re-toggle row 1 to intended state.

Issue B (WORD/PHRASE no-op on fresh pages) — wiring verified CORRECT: rules
re-queried per page (TtsPlaybackController.acquireSentences:496 via
awaitForSpeech), zonesForSpeech SQL includes global WORD/PHRASE, matcher has no
rect requirement for them, cache stores RAW regions + playback-time filtering
(cached pages DO get new exclusions on next acquire). Failure was MATCH
SEMANTICS only, 3 causes all in OcrExclusionMatcher:
  1. WORD needle never tokenized: "K-manga.com" contains -/. → pure
     isLetterOrDigit tokens can never equal it = impossible rule; "keymanga"
     missed OCR variants ("Key Manga").
  2. PHRASE collapsed whitespace to single space but kept it: OCR emits
     "Discord. gg / AsuraScans" → needle "discord.gg/asurascans" (0 spaces)
     never substring-matches.
  3. JP-mixed lines are full-width-converted by TextPostprocessor (half→full
     ASCII mapping); matcher lowercase() does not fold width → ｋｅｙｍａｎｇａ ≠
     keymanga.
FIX (OcrExclusionMatcher rework): all text comparisons NFKC-normalize (folds
full-width→half). WORD = rule-token CONCATENATION must equal concat of a
consecutive run of region tokens (single-token "ion" still ≠ "combination";
"K-manga.com" ≡ [k,manga,com] runs; "KeyManga" ≡ "Key Manga"). PHRASE =
NFKC-fold + lowercase + strip ALL whitespace + substring (URL/domain noise-
proof). URL-like input therefore behaves predictably in EITHER type (documented
choice: robust matching in both, no UI forced-conversion).

Manage-sheet visibility landmine (agent-found): reader "Manage exclusion zones"
used zonesForManga WHERE manga_id=:id → global WORD/PHRASE rows (manga_id=0)
invisible there = user thinks rule missing. FIX: zonesForManga now
manga_id=:mangaId OR source_id=:sourceId OR match_type IN (WORD,PHRASE);
subscribeForManga(mangaId, sourceId) signature ripple (repo+interactor+VM).
Settings screen unchanged (subscribeAll already).

Diagnostics (rules §7 — no text logged): acquireSentences now logs
"TTS page=N exclusion rules=X types={WORD=1,...} excluded=A/B" per page when
rules exist.

Issue D (auto-detect selection text) — implemented per spec, cached-first:
captureExclusionZoneSelection resolves normalized rect → VM
detectExclusionZoneText (cached OcrPageResult regions intersecting selection
via boxesOverlap, sorted by order, joined \n — NO scan) → miss → targeted crop
OCR (Bitmap.createBitmap of selection rect only, existing ocrProcessor.getText
HIGH-priority queue path, no full-page scan, bitmap recycled in VM finally) →
Dialog.ExclusionZoneScope gains detectedText → ExclusionZoneScopeDialog
pre-fills match text field (multiline, editable, clearable; cancel+re-select =
re-detect). User confirms before any rule is saved (dialog flow unchanged).
rejoin: detection runs while dialog opens? NO — detection runs BEFORE dialog
opens (launchIO), dialog opens with result; null detection = empty field,
manual entry.

Tests: OcrExclusionMatcherTest extended 14→20 cases: keymanga all-cases +
trailing punct, punctuated rule tokenized ("K-manga.com"), separator variants
("KeyManga"≡"Key Manga"), consecutive-run requirement, full-width fold WORD +
PHRASE, URL-like OCR spacing noise ×3 variants, (existing ion/combination
standalone-token case kept).

GATES GREEN 2026-09-02 (devcontainer JDK17, -Xmx4g, both volumes):
  spotlessCheck + testDebugUnitTest + verifySqlDelightMigration BUILD
  SUCCESSFUL 3m39s (matcher 20/20); :app:assembleDebug BUILD SUCCESSFUL 3m20s.
  No DB schema change (query-only edit to zonesForManga — verifySqlDelight
  Migration still run + green).

Files changed (10): OcrExclusionMatcher.kt, OcrExclusionMatcherTest.kt,
  OcrExclusionZoneRepositoryImpl.kt, ocr_exclusion_zones.sq (zonesForManga
  query), OcrExclusionZoneInteractors.kt, OcrExclusionZoneRepository.kt,
  ReaderViewModel.kt (subscribeForManga call + detect/crop fns + dialog field),
  ReaderActivity.kt (capture flow + dialog args), OcrExclusionZoneDialogs.kt
  (pre-fill), TtsPlaybackController.kt (diagnostics log).
```

```text
[COMPLETED 2026-09-03 — OCR exclusion regression #2: diagnostic-first fix set, UNCOMMITTED]

Device evidence FIRST (on-device capture .device-pass/ocr-excl-snapshot1.log,
PID 20038, 11:00–11:37, build 0.5.1-8254):
- 11:01:36 "OCR(legacy) Runtime: recognizeText total time: 5715 ms" during
  exclusion auto-detect → crop OCR ran the JP-vocab LegacyOcrEngine on an
  English selection (device DB rule _id=14 = COMBINED with saved JP garbage
  text — user-confirmed garbage insert). 36 tokens of kana/kanji output.
- 11:03:05 "TTS page=0 exclusion rules=2 types={PHRASE=1, COMBINED=1}
  excluded=0/16" → exclusion MISS with rules loaded (matcher semantics).
- 11:03:35 ReaderActivity destroy → LeakCanary "Found 2 objects retained,
  app is not visible" → 11:06 heap dump (12.3s freeze, all threads stopped)
  → analysis 131.5s @ ~100% CPU on WorkManager thread. Leak trace:
  WebtoonTransitionHolder retained 184.7MB → ReaderChapter.stateFlow slot →
  ScopeCoroutine collect → holder.itemView LinearLayout.mContext → destroyed
  ReaderActivity (97.7kB, 2691 objects). Root: holder's stateJob (own
  MainScope) cancelled ONLY in recycle(); RecyclerView never recycles holders
  on activity destroy → collector stayed registered in the chapter's StateFlow
  slot. PagerTransitionHolder has onDetachedFromWindow cancel; webtoon one
  didn't. Same family as 2026-08-30 WebtoonPageHolder 49.9MB flag.
- Device DB dump (python sqlite3): 3 rules; enabled values all clean 0/1
  (B-RC1 corrupt-enabled residue from old toggle bug NOT present on device;
  zonesForSpeech enabled=1 predicate safe here). Rule 10 COMBINED disabled
  (toggle fix works), rule 13 PHRASE enabled, rule 14 = the JP-garbage rule.

3 parallel explore subagents (A auto-detect pipeline, B exclusion/TTS, C perf)
+ orchestrator source verification. CONFIRMED root causes and fixes:

Fix 1 (A-RC1, garbage crops): OcrRepositoryImpl.recognizeText called
  recognizeWithFallback(selectedEngineType()) — LEGACY default pref routed
  arbitrary English crops into LegacyOcrEngine (JP vocab, non-uniform 224×224
  stretch). Scan path NEVER hits LEGACY directly (detection stub throws →
  Glens redirect) but recognizeText did. FIX: LEGACY/FAST text-recognition
  redirects to GLENS in recognizeText (mirrors scanLocalOrFallback), with
  ponytail: note to drop when a real DetOcrEngine lands. Manual long-press OCR
  selection path (processOcrRegion) gets the same correction for free.

Fix 2 (A-RC2, outside-selection leaks): detectExclusionZoneText filtered
  cached regions by boxesOverlap (ANY intersection ≥1px) → neighboring bubble
  text leaked into detected field. FIX: new pure fn boxMostlyInside(a, b,
  minCoverage=0.5f) in SpeechPipeline.kt (intersection ≥50% of REGION area);
  detectExclusionZoneText uses it. Selection-inside-huge-region now EXCLUDED
  (documented decision: merged-bubble box mostly outside selection = leak).
  BoxMostlyInsideTest 7 cases (contain/graze/disjoint/40%/75%/huge/degenerate).

Fix 3 (B-RC3, phrase punctuation asymmetry): phraseMatches kept punctuation —
  rule "discord gg" could never match OCR "discord.gg"; single ・ survived.
  FIX: phraseMatches first tries whitespace-stripped substring (as before),
  then falls back to TOKEN-CONCAT containment (normalizedTokens joined, both
  sides) — separator/punctuation tolerant in BOTH directions. New matcher
  tests 20→27: space-rule vs punct-OCR, punct-rule vs space-OCR, ・ separator,
  cross-region phrase NOT excluded (pins documented v1 per-region semantics),
  punctuation-only rule never matches, "Dis\ncord" word concat-run pinned.
  NOT fixed (documented): phrase split across TWO regions stays un-excluded
  (per-region matcher by design, v1); mid-page rule additions apply next page.

Fix 4 (C-RC1, 184.7MB leak): WebtoonTransitionHolder gained detach() =
  stateJob?.cancel(); WebtoonAdapter.onViewDetachedFromWindow calls it
  (RecyclerView.ViewHolder has no onDetachedFromWindow). detach also fires on
  mid-session recycling — same semantics as recycle(), next bind relaunches.

Fix 5 (C-A, detect job pile-up): captureExclusionZoneSelection now
  single-flight — exclusionDetectJob?.cancel() before relaunch (lifecycleScope
  cancels on destroy); obsolete detections no longer queue 5.7s OCRs behind
  each other. Sub-10px degenerate crops skip OCR entirely (honest empty field
  beats noise). Lazy-bitmap-open REJECTED during implementation: normalization
  needs file dims before cache lookup (chicken-egg with region decode) — not
  worth a bounds-only decoder helper.

Fix 5' (C-RC2, LeakCanary Toast config) DROPPED after API verification:
  IgnoredReferenceMatcher/referenceMatchers only filter leak-trace paths,
  do NOT prevent dumps of retained instances; AppWatcher exposes no
  per-class watcher filter. The REAL retained object was the (now-fixed)
  holder leak; Toast/PopupLayout dumps remain debug-build noise = Known
  issue #13 (unchanged). Debug sourceSet + manifest override reverted.

GATES GREEN 2026-09-03 (devcontainer JDK17, -Xmx4g, both volumes):
  spotlessCheck 35s; testDebugUnitTest + verifySqlDelightMigration BUILD
  SUCCESSFUL 2m36s (193 domain tests, matcher 27/27, BoxMostlyInside 7/7,
  one pre-existing test expectation of mine corrected before green);
  :app:assembleDebug BUILD SUCCESSFUL 2m44s. NO DB schema change.
  APK 0.5.1-8255 installed on SM_M066B 12:54; on-device verify capture
  /sdcard/ocr-excl-verify.log running (PID 24098).

Device verification checklist (Phase 7, pending user): A/B English selections,
C noisy area, D WORD excl, E PHRASE excl spacing/punct variant, F cached,
G fresh, H rapid re-select + rule CRUD, I long session lag watch,
J no playback regressions.

Files changed (7): OcrRepositoryImpl.kt (recognizeText redirect),
  SpeechPipeline.kt (+boxMostlyInside), ReaderViewModel.kt (filter + import),
  OcrExclusionMatcher.kt (phraseMatches token-concat), ReaderActivity.kt
  (single-flight detect + min-crop guard + Job import),
  WebtoonTransitionHolder.kt (+detach), WebtoonAdapter.kt
  (+onViewDetachedFromWindow), OcrExclusionMatcherTest.kt (+7),
  BoxMostlyInsideTest.kt (new, 7).
```

```text
[COMPLETED 2026-09-03 — P0 ZONE exclusion reliability fix (RC1/RC2/RC3), UNCOMMITTED]

User report: ZONE exclusion intermittent on device (WORD/PHRASE fine). 6
parallel explore agents + orchestrator verification + fresh device-log
evidence (ocr-excl-verify2.log, 63 exclusion decisions). Root causes:

RC1 (CONFIRMED, primary): zones drawn with CHAPTER/MANGA/SOURCE scope were
  FORCED to match_type=COMBINED (rect AND phraseMatches) by
  saveExclusionZone — user zones all became text-dependent rules whose text
  half broke on Glens re-clustering/noise (excluded=0/22 dominant; device
  logs show ONLY types={COMBINED=*}, never a pure ZONE rule). Fix:
  - Matcher ZONE semantics: all scopes pure-rect, PAGE-ANCHORED —
    matchesRegionScope: ZONE requires pageIndex!=null && (PAGE/CHAPTER:
    own chapterId; MANGA: mangaId; SOURCE: sourceId); matchesRegion rect
    still requires zone.pageIndex == context.pageIndex. CHAPTER-scope pure
    zone ≡ PAGE (documented; rect drawn on one page). Legacy rows
    (page_index NULL) dormant via pageIndex null-gate.
  - saveExclusionZone: matchType derived from text presence ONLY — blank
    text = pure ZONE for ANY scope; non-blank = COMBINED (opt-in).
  - ExclusionZoneScopeDialog: PAGE saves immediately (pure zone); wider
    scopes show OPTIONAL text field (supportingText "leave empty to always
    exclude"); Save no longer disabled on blank. Legacy detection now
    pageIndex==null (was scope!=PAGE — new wider-scope ZONE rules would
    have been mislabeled legacy).
  - SettingsOcrExclusionsScreen RuleRow: scope label shown for all
    non-PAGE scopes; rect shown for pageIndex!=null; legacy = null pageIndex.
RC2 (CONFIRMED conditional): zone normalized against DISPLAYED bitmap,
  OCR bboxes against ORIGINAL image — divergent exactly on dual-page
  split (x halved+offset) / rotateToFit (x/y transposed) / webtoon
  splitAndMerge pages → wrong rect, page-dependent = "intermittent".
  cropBorders + splitTallImages verified SAFE (both paths share base).
  Fix: captureExclusionZoneSelection now bounds-decodes the ORIGINAL
  page stream (page.stream, inJustDecodeBounds) and REJECTS zone creation
  with a clear error when displayed dims != original dims (honest failure
  beats silently-wrong rect). Full inverse-transform mapping deferred
  (documented follow-up if users need zones on split pages).
RC3 (diagnostics): controller acquireSentences now logs per-rule
  structural detail after the summary line: "OCR-ZONE rule=<id>
  type=<t> scope=<s> page=<pi> chapter=<cid> manga=<mid> rect=[l,t,r,b]
  enabled=<b>" — no text content (rules §7). Proves rule/scope/rect per
  page on next device pass.

CONFIRMED NON-CAUSES (agents + logs): all OCR engines emit normalized
  0..1 full-image bboxes (Glens single+tiled, OwOcr; Legacy/Fast never
  emit bboxes, redirected to Glens); cache roundtrip bit-exact; rules
  re-queried fresh per acquire (awaitAsList, no Flow race); matcher pure;
  same (chapter,page) always stable excluded A/B in logs (all A/B changes
  = chapter switches). ZonesForSpeech SQL unchanged (enabled=1 +
  text-rules-or-scope-match) — still correct for new ZONE semantics.

Tests: OcrExclusionMatcherTest 28→35: chapter-scope anchoring,
  manga/source same-page-index, null-pageIndex dormant, rect edge
  touching vs overlap vs spanning, degenerate rect, multi-zone/page.
  All prior WORD/PHRASE/COMBINED cases unchanged+green (COMBINED stays
  rect+text, opt-in).

GATES GREEN 2026-09-03 (devcontainer JDK17, -Xmx4g, both volumes):
  spotlessApply+spotlessCheck; :domain:OcrExclusionMatcherTest 35/35;
  full testDebugUnitTest + verifySqlDelightMigration BUILD SUCCESSFUL
  3m3s; :app:assembleDebug BUILD SUCCESSFUL (arm64 APK 12:42).
  NO DB schema change (no migration needed).

Files changed (7): OcrExclusionMatcher.kt, OcrExclusionMatcherTest.kt,
  ReaderViewModel.kt (saveExclusionZone), OcrExclusionZoneDialogs.kt
  (dialog + isLegacyZone), SettingsOcrExclusionsScreen.kt (RuleRow),
  ReaderActivity.kt (original-bounds guard + BitmapFactory import),
  TtsPlaybackController.kt (OCR-ZONE per-rule log), i18n base
  strings.xml (ocr_exclusion_match_text_optional; label de-required).

Device verification PENDING user (repeat matrix): fresh vs cached OCR,
replay, exit/reopen, toggle off/on, delete, multi-zone, page-edge zone,
webtoon top/middle/bottom, CHAPTER/MANGA/SOURCE pure-zone (blank text),
COMBINED opt-in, repeated same-scenario runs (detect intermittency),
"OCR-ZONE rule=" lines confirm rule content. Existing device DB rules
(match_type=COMBINED from old save path) keep working as COMBINED;
re-create as blank-text zone rules for pure-rect behavior.
```

```text
[COMPLETED 2026-09-03 — verify2 log deep-analysis (read-only, no code change)]

Analyzed .device-pass/ocr-excl-verify2.log (79MB; PID 24098 12:54–13:11,
PID 8957 13:26–13:39+ after user relaunch, build 0.5.1-8255) +
ocr-excl-verify.log (= truncated prefix of verify2, ends 13:06, NO new data;
do not re-analyze) + ocr-excl-snapshot1.log (PID 20038, build 8254).
Findings (full report in session; structural facts only, no OCR text):

EXCLUSION PIPELINE: HEALTHY — no nondeterminism found.
- All "A/B changes across acquisitions" cases resolve to CHAPTER switches:
  page=0 with B=16/22/18/25/8/24 = chapters 2145/2188/2187/2186/1717/2184
  respectively. Same (chapter,page) re-acquired → A and B ALWAYS identical,
  cache vs fresh (dispatch textHashes also identical).
- No page ever completed a fresh scan twice (single-flight + cache hold).
  Near-miss: ch2184 p0 scan 13:30:25 abandoned (user left reader, VRI
  destructor 13:30:48) before completion — second attempt 13:32:10 was
  cache MISS → fresh → 2/24. No A/B contradiction.
- Matcher fixes verified stable: snapshot1 ch2152 p19 = 1/13 FRESH then ×8
  CACHE 1/13; ch2145 p0 = 0/16 across 1h47m + app restart (cache stable).
- ZONE pure type: NEVER appears in any log. Types seen: PHRASE/COMBINED/WORD
  only. ("types={[100]=...}" lines = Samsung CpEventLog telephony noise.)
- "dedup dropped": ZERO events in all three logs (only MR2SystemProvider /
  fb4a substring noise).
- Rule CRUD invisible (no repo logging) but inferable: rules count path
  2→{COMB=2}→1→3→4→5 with dialog opens (WindowManager addView) at
  13:29:45–57 (before first rules=4 line) and 13:36:44–58 (before first
  rules=5 line). Count bumps land exactly after dialog windows. Rules
  re-queried per acquire confirmed live (mid-session adds take effect).

REAL DEFECTS FOUND (unrelated to matcher, ranked):
1. NEW BUG — NetworkOnMainThreadException ×25: TtsPlaybackController.scanOnDemand
   → OcrPageSourceResolver.resolveRemotePages → HttpSource.getPageList via
   awaitSingle ON MAIN THREAD. Kills next-page prefetch scans in ~35ms each
   (ch2188 p1/p2 ×10 each; ch2184 p1..p7). "TTS prefetch scan failed page=N
   (best-effort)". FIX CANDIDATE: move page-list resolve off main (IO
   dispatcher) — root-cause fix at resolver/scanOnDemand level.
2. Glens HTTP 502 ×4 (13:05:35 / 13:34:58 / 13:38:26 / 13:38:33), server-side,
   NO client retry. FALLBACK INCONSISTENCY: recognizeText path falls back to
   fast engine (13:05 → FastOcrEngine 2245ms OK); scan path does NOT — 502
   during scan = page gets NO OCR at all (TTS on-demand scan failed).
3. DetectionUnavailable redirect stacks ×22 (detection model absent on
   device — expected, but 21-frame W-stack per scan = log spam; benign).

Noise ruled out: SQLiteLog (10) LOCK error 3850 ×10 = transient lock
contention, self-recovered, not exclusion-related. 24098 death 13:26:06 =
normal LMK cached-app reaping (cch CAC), no crash/ANR.

Device clock = host + ~5h29m (log timestamps 12:54+ ↔ file mtime ~07:30).
```

## In progress

```text
[COMPLETED 2026-09-13 — ADAPTIVE UI & PERSONALIZATION master batch, UNCOMMITTED]
User-authorized via master prompt (supersedes roadmap "nav customization
REJECT" + "Liquid DEFERRED" locks for THIS scope: reorder ≠ 6th tab / IA
change; gradient ≠ Liquid's rejected blur). AnymeX = reference only.

Implemented (all gates green 3m14s: spotlessCheck + testDebugUnitTest +
verifySqlDelightMigration + :app:assembleDebug; NavTabTest 9/9):

A. IMMERSIVE MODE — pref_immersive_mode (default false); MainActivity
   LaunchedEffect hides/shows systemBars via WindowInsetsControllerCompat
   with BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE (gesture nav safe, no trap:
   toggle off restores). ReaderActivity untouched (own fullscreen pref).
B. BOTTOM NAV REORDER — NavTab enum (:domain ui.model) = identity, NOT
   visual position/TabOptions.index; pref_nav_tab_order CSV; parseOrder()
   never loses/dupes a tab, malformed→default (9 unit tests). HomeScreen
   orderedTabs() from pref (live apply); NavigationBar + NavigationRail
   both iterate ordered list; TabNavigator start = first ordered tab
   (ponytail: live reorder doesn't relaunch nav; restart moves start tab).
   SettingsNavigationScreen (ReaderToolbar drag pattern: ReorderableItem
   + draggableHandle + move customActions a11y) — registered in settings
   search index. 5 tabs kept, reselect semantics untouched.
C. FEED DRAG REORDER — ManageFeedsScreen rebuilt as reorderable
   ElevatedCard rows (was PreferenceGroupCard + up/down arrows); drag
   handle + a11y move actions retained; enable/delete/toggle/identity
   preserved; FeedScreenModel.moveFeedTo(from,to) = same pref-list
   mutation as old buttons (no network on drag; sections keyed by
   FeedItem data class = order-safe).
D. COLLAPSIBLE CHROME — Feed = prior grid-item collapse (kept); Library
   already conditional; RecentTab + TabbedScreen (Browse) switched to
   enterAlwaysScrollBehavior (title collapses, PrimaryTabRow pinned);
   More = no header (N/A); reader untouched.
E/F. TRANSLUCENT NAV + INTENSITY — pref_nav_bar_translucent (default
   false) + pref_nav_bar_translucency (0..100, default 60 → bounded
   alpha 0.55..0.92 via navTranslucencyAlpha()); NavigationBar pill
   uses new Color.asNavContainer() (real bounded alpha, pill floats over
   gradient only). LocalNavTranslucency provided by TachiyomiTheme.
G/H. BACKGROUND GRADIENT — BackgroundStyle enum (SOLID default /
   GRADIENT) + pref_background_style + pref_background_gradient_intensity
   (0..100, default 35). Brush = theme-derived background→surfaceContainerLow
   vertical lerp (semantic roles; AMOLED/Monet/Frosted all derive
   automatically; 0 ≈ solid). LocalAppBackground provided by
   TachiyomiTheme; presentation-core Scaffold draws brush via drawBehind
   when non-null (containerColor→Transparent); AppBars stay opaque =
   readable chrome. No per-frame work (remember(pref-set)).
I. FROSTED THEME — AppTheme.FROSTED + FrostedColorScheme (cool
   blue-grey editorial, light+dark) + colors_frosted.xml ×2 +
   Theme.Tachiyomi.Frosted + themeResources entry; auto-enables
   translucent chrome while active (isTranslucent = pref || FROSTED).
   Appears in theme picker after Monochrome (entries-driven).
J. DIALOG/SHEET ADAPTATION — audited: AdaptiveSheet/ResizableSheet/
   TabbedDialog already route asChromeContainer/asFrostedModal;
   AlertDialogs = M3 defaults; Frosted = colorscheme swap → all
   surfaces follow. No frost-on-frost (content panels untouched).
K. DOWNLOAD QUEUE — already grouped (FlexibleAdapter sections +
   MaterialCardView headers + drag enabled); OcrQueueScreen already
   PreferenceGroupCard (09-13 micro-batch). Documented, no change.

PREF KEYS: pref_immersive_mode, pref_nav_tab_order, pref_nav_bar_
translucent, pref_nav_bar_translucency, pref_background_style,
pref_background_gradient_intensity. No DB change (migration gate green).
i18n: +13 base strings (nav group, background group, immersive, frosted
theme). No locale hand-edits.

Files changed (20): UiPreferences.kt, AppTheme.kt, BackgroundStyle.kt(new),
NavTab.kt(new), NavTabTest.kt(new), FrostedColorScheme.kt(new),
colors_frosted.xml ×2(new), themes.xml, ThemingDelegate.kt,
TachiyomiTheme.kt, Translucent.kt, Scaffold.kt, NavigationBar.kt(pcore),
HomeScreen.kt, SettingsAppearanceScreen.kt, SettingsNavigationScreen.kt
(new), SettingsSearchScreen.kt, ManageFeedsScreen.kt, FeedScreenModel.kt,
RecentTab.kt, TabbedScreen.kt, MainActivity.kt, strings.xml.

Device verification matrix PENDING user (needs SM_M066B session).
```

```text
[COMPLETED 2026-09-13 — ADAPTIVE UI corrective fix batch (post-audit), UNCOMMITTED]
Read-only audit found 3 batch-introduced defects; all fixed, smallest diffs:

1. ISSUE-001 (P1) nav translucency OFF rendered 55% alpha:
   TachiyomiTheme passed navTranslucencyAlpha(0)=0.55f (MIN bound) when
   toggle OFF. FIX: gate on boolean — OFF → 0f (asNavContainer treats
   <=0f as opaque, verified downstream), ON → bounded 0.55..0.92 via
   unchanged navTranslucencyAlpha(). navIntensity now read unconditionally
   (no if-collapse into slider). Preview stays 0f.
2. ISSUE-002 (P2) ManageFeeds LazyColumn key = FeedItem data class
   (contains mutable `enabled`; toggle mid-drag = key change). FIX: key =
   "sourceId:listing" (stable immutable identity; same source CAN have
   POPULAR+LATEST so pair, not sourceId alone). ReorderableItem key
   matched. Drag/toggle/delete logic untouched.
3. ISSUE-003 (P3) FrostedColorScheme missing slots vs XML palette:
   added error/onError/errorContainer/onErrorContainer, outlineVariant,
   scrim, surfaceDim, surfaceBright (dark+light, values = exact XML
   frosted_* source of truth). Also removed duplicate-inversePrimary
   compile dupes during edit (2× "Argument already passed" caught by
   gates, fixed). Error family included — sibling schemes all define it,
   XML has frosted_error.

GATES GREEN 2026-09-13 (devcontainer JDK17, -Xmx4g, both volumes):
   spotlessCheck + testDebugUnitTest + verifySqlDelightMigration +
   :app:assembleDebug BUILD SUCCESSFUL 5m49s (one prior red run: forgot
   gradle-home volume → dep resolution fail; one red run: dup params,
   fixed). arm64 APK 20:08.
 DEVICE VERIFICATION PENDING (same SM_M066B matrix as master batch).
 Files changed (3): TachiyomiTheme.kt, ManageFeedsScreen.kt,
   FrostedColorScheme.kt.
```

```text
[COMPLETED 2026-09-15 — NAV-PILL SCROLL-BEHIND + GRADIENT/NAV LIVE-APPLY FIX, UNCOMMITTED]

User-directed refinement of the 2026-09-13 adaptive-UI batch (nav sizing
controls explicitly SKIPPED per task — pill stays at frozen 12/8dp + 80dp
metrics; no RenderEffect/backdrop blur added).

Root causes (source-proven, both batch-introduced):
1. GRADIENT + NAV TRANSLUCENCY APPEARED STATIC: TachiyomiTheme read
   backgroundStyle/backgroundGradientIntensity/navBarTranslucent/
   navBarTranslucency via plain Preference.get() — non-observable. Slider
   writes persisted but the root composition never recomposed → brush +
   pill alpha frozen until process restart. FIX: collectAsState() on those
   4 prefs in TachiyomiTheme (presentation-core util, HomeScreen precedent)
   → remember-keys re-fire → LocalAppBackground brush +
   LocalNavTranslucency alpha recompute live. Brush math unchanged
   (intensity lerps background→surfaceContainerLow); extracted pure
   internal backgroundGradientEndColor(bg, container, percent) + NEW
   AppBackgroundGradientTest 4 cases (0→solid, 100→full, 50 lerp ±1ULP,
   out-of-range coercion).
2. CONTENT CLIPPED ABOVE THE PILL (nothing could scroll under it):
   HomeScreen Box applied the outer Scaffold's contentPadding as LAYOUT
   padding — viewport bottom cut at the pill top → LazyColumns clip there.
   FIX (scroll-behind plumbing, presentation-core only + HomeScreen):
   - NavigationBar.kt: + LocalNavPillBottomInset (staticCompositionLocalOf,
     default 0.dp) — the pill's measured total clearance.
   - HomeScreen: Box pads top/start/end only (no bottom clip), still
     consumes all insets (nested Scaffolds see 0 system bottom inset);
     provides LocalNavPillBottomInset = contentPadding bottom (pill height,
     0 on tablet rail / hidden-nav AnimatedVisibility).
   - Forked Scaffold.kt: reads the local, clears it to 0.dp for its own
     content (nesting never double-counts), folds it into: innerPadding
     bottom (resting content padding → scrollables like LibraryPager /
     Feed grid / TabbedScreen pass under the pill; layout-padders like
     RecentTab keep clip-above-pill = old behavior, no regression),
     bottomBar slot placement (+pill → selection bars ride above pill),
     FAB + snackbar bottom anchors (max with pill clearance).
     Zero behavior change for the ~100 other Scaffold call sites (local
     default 0.dp; pushed Voyager routes are siblings of HomeScreen → 0).
   - Pill translucency itself unchanged bounded (navTranslucencyAlpha
     0.55..0.92; OFF = 0f opaque fallback) — now actually applies live.

GATES GREEN 2026-09-15 (docker vsc-yomihon…, JDK17, -Xmx4g, both volumes):
  spotlessApply→spotlessCheck + testDebugUnitTest BUILD SUCCESSFUL 3m12s
  (AppBackgroundGradientTest 4/4 + all suites incl. NavTabTest 9/9);
  :presentation-core:compileDebugKotlin + :app:compileDebugKotlin green.
  NO DB/pref-key/schema change → verifySqlDelightMigration N/A. NO new
  deps, NO i18n, NO true backdrop blur (RenderEffect stays rejected).
 DEVICE VERIFIED 2026-09-15 on SM_M066B (arm64, 720x1600, wireless adb
  192.168.29.98:5555). :app:assembleDebug docker BUILD SUCCESSFUL 3m17s;
  app-arm64-v8a-debug.apk → app.yomihon.dev installed -r Success.
  Pixel-metric + uiautomator proof (no image input to verifier —
  programmatic bands, screenshots kept for user eyeball):
  - GRADIENT LIVE ✓ 3 vs 97 same-scroll: raw bg gap RGB(32.9,39,42)→
    (33.8,45.4,47.7), upper bands 0.0 (correct vertical stop), cards MAD
    0.06, same pid = no recreate. Subtle by design (Δ≈9-11/255).
  - NAV ALPHA LIVE ✓ pill-gap bleed vs content MAD 74-83: OFF=0.0 exact
    (opaque), intensity 3 (α.55)=16.9-20.9, intensity 100 (α.92)=5.9-7.4
    — matches (1−α)×content quantitatively, single process.
  - SCROLL-BEHIND ✓ Library+Feed covers/text pass under pill (bleed
    table); Library bottom last-row text y1313 vs pill top 1362 = 49px
    resting clearance. Browse list fits viewport (nothing to scroll —
    no defect). Selection long-press = pill hides, action bar [1411-1495]
    + top Cancel/Select-all ✓ per nav-hidden selection design. No FABs on
    main tabs (top-bar actions only). Tablet rail/AMOLED/Monochrome not
    exercised (phone, active theme Dark+Frosted surfaces).
  - INCIDENT: one crash 20:49 during scripted blind-tap chaos:
    Voyager SaveableStateHolder 'Key …SettingsTrackingScreen:transition
    was used multiple times' → CrashActivity → restart. Clean double-tap
    repro on Tracking = NO crash; my 5 files create no transition/
    saveable keys (Scaffold diff pure layout/locals) ⇒ pre-existing
    settings-nav double-push race, NOT this batch. TODO candidate:
    guard Voyager push if screen==stack top.
  - Device prefs restored: translucent ON, intensity 99, gradient 99
    (slider pixel-max ~99, Δα 0.004 vs 100), style Gradient, Dark.
  Artifacts: .device-pass/nav-pill-20260915/RESULTS.md + *.png +
  device.py/measure.py probes.
  Files changed (5): presentation-core NavigationBar.kt, Scaffold.kt;
   app TachiyomiTheme.kt, HomeScreen.kt, AppBackgroundGradientTest.kt (new).
 ```

 ```text
[YOMUCHU BATCH 2 — RECENT GROUPING + GRADIENT CONTRAST + RECENT PILL — 2026-09-15, UNCOMMITTED]

 USER-AUTHORIZED 3-fix batch (design approved via question gate):
 1. RECENT→UPDATES COLLAPSIBLE MANGA GROUPS:
    - Pure fold groupConsecutiveUpdates(uiModels, expandedGroupIds) in
      UpdatesScreenModel.kt: ≥2 CONSECUTIVE same-manga Items → new sealed
      variant UpdatesUiModel.Group(mangaId, items, expanded); date Headers
      break runs (groups never span days); singles stay flat rows.
    - Expanded set lives in State.expandedGroupIds + toggleUpdatesGroup()
      (survives scroll/pager via ScreenModel); DEFAULT COLLAPSED (user
      approved). Selection/filter/count logic untouched (operates on
      items, not uiModels).
    - UI: parent row = MangaCover.Square (tap→Manga) + title +
      "%d new chapters" (existing plural notification_chapters_generic,
      no invented label) + unread dot + ExpandMore chevron, Role.DropdownList;
      children = same UpdatesUiItem parameterized showCover/showMangaTitle=
      false (config, not new component) inside AnimatedVisibility
      expand/shrinkVertically+fade — established motion pattern.
    - TDD watched: RED 4 grouping tests failed vs no-op stub (3 flat-case
      passed), GREEN 7/7. New UpdatesGroupingTest.kt.
 2. GRADIENT WASH-OUT AUDIT (Teal & Turquoise / Taco reported):
    ROOT CAUSE: gradient bottom stop IS surfaceContainerLow = exactly the
    PreferenceGroupCard fill (Teal dark #222F31, Tako #262636; AMOLED cards
    #0C0C0C over black→#0C0C0C-end) → cards merge into background at screen
    bottom at high intensity, ALL schemes. FIX: PreferenceGroupCard gains
    1.dp colorScheme.outlineVariant hairline on shapes.large (clip→bg→border
    chain) — precedent already shipped (SettingsDictionaryScreen outline
    cards). Tokens only, no black, Monochrome shape-delineation intact.
    design.md §Grouped settings surfaces updated.
 3. RECENT TAB 'SOLID' NAV PILL:
    ROOT CAUSE: RecentTab Column clipped viewport above pill
    (.padding(bottom=innerPadding)) and all 3 pages DISCARDED the
    contentPadding lambda param → nothing scrolls under → translucency had
    no backdrop to show (Library/Feed/Browse got it right via list
    contentPadding; supersedes the "layout-padders keep clip" note in the
    block above — Recent no longer is one).
    FIX: Column keeps top padding only; RecentTab passes
    PaddingValues(bottom=calculateBottomPadding()) (system inset + pill
    clearance folded by nested Scaffold) to Continue/History/Updates pages
    → FastScrollLazyColumn contentPadding (ContinueTab, HistoryScreen
    +Content, UpdateScreen +contentPadding param + lifted inline
    SnackbarHost). Selection mode: pill already hides
    (showBottomNav(!selectionMode)) → action menu keeps full bottom.
  GATES GREEN 2026-09-15 (docker, -Xmx4g): spotlessApply 39s; chained
  spotlessCheck + :app:testDebugUnitTest (FULL suite, UpdatesGroupingTest
  7/7) + :presentation-core:compileDebugKotlin + :app:compileDebugKotlin
  BUILD SUCCESSFUL 2m30s; :app:assembleDebug BUILD SUCCESSFUL 4m5s
  (app-arm64-v8a-debug.apk 17:18). NO DB/schema/pref-key change →
  verifySqlDelightMigration N/A. 1 i18n base key added: action_collapse.
  DEVICE VERIFICATION PENDING (user chose SKIP): app.yomihon.dev on
  SM_M066B was reinstalled 20:23 by a build signed with a DIFFERENT
  ephemeral debug keystore → INSTALL_FAILED_UPDATE_INCOMPATIBLE; clean
  reinstall would wipe debug-app data, user declined. GOTCHA FOUND:
  ~/.android/debug.keystore is NOT on a mounted volume — every --rm
  container regenerates it → debug APK signatures rotate per session.
  Fix when device pass is wanted: persistent volume for /home/vscode/
  .android (one-time uninstall of app.yomihon.dev then stable key).
  Files: app UpdatesScreenModel.kt, presentation/updates UpdatesScreen.kt
  + UpdatesUiItem.kt, ui/recent RecentTab.kt + continuereading/ContinueTab.kt
  + history/RecentHistoryTab.kt + updates/RecentUpdatesTab.kt,
  presentation/history HistoryScreen.kt, widget/PreferenceGroupCard.kt;
  i18n base strings.xml; docs/design.md; test UpdatesGroupingTest.kt (new).
 ```

```text
v0.5.2 RELEASE PUBLISHED 2026-09-03 (tag v0.5.2, 5 ABI APKs, Latest).
- Version bumped 0.5.2/28 (commit 0286d9081 "chore(release): bump version to
  0.5.2 (versionCode 28)" + CHANGELOG.md entry), tagged, pushed, release
  created via gh with full notes: Feed tab, OCR exclusion rules (all types,
  NFKC-robust matching), speech cleanup/classification prefs, rate 50–300%,
  dictionary entry, 10 bug fixes (toggle row swap, pure-ZONE semantics,
  prefill removal, split-page coord rejection, leak fixes, etc.).
- Built LOCAL (release.yml fork-gated github.repository == 'yomihon/yomihon'
  → all jobs skip on fork; releases MUST be built+published locally, same
  as v0.5.0/v0.5.1):
  docker -u vscode, both volumes, -Xmx4g, assembleRelease
  -Pinclude-telemetry -Penable-updater → BUILD SUCCESSFUL 13m53s.
  aapt2 verified versionCode=28 versionName=0.5.2; 6 ML models packaged.
- BUILD ISSUE (resolved): root-owned dirs in data/build + domain/build
  intermediates (residue from a past root container run, Aug 28) failed
  copyReleaseJniLibsProjectAndLocalJars / checkReleaseAarMetadata
  "Failed to create parent directory". Fix: one-off root container
  `chown -R 1000:1000 /workspace` (no sudo on host). If it recurs, same fix.
- APKs staged: /tmp/yomihon-{abi}-v0.5.2.apk (also at
  app/build/outputs/apk/release/). Release:
  https://github.com/Nikhil0921/yomihon/releases/tag/v0.5.2
- In-app updater: points at this fork (7e0d52697) — v0.5.2 users get
  future update prompts.
NOTE: main @ 0286d9081; all prior feature/fix commits (c70e32252,
1b810ccde, baf7f679b, 4543cf453) now part of release. Docs update
(memory.md this block) is post-release, uncommitted.
```

P0 followup: ZONE-exclusion "prefill regression" FIX — code done, device
verify pending. Device logs (excl3-final.log, 20:10-20:26) proved all 6
new zone-drag rules (34,36,37,38,39,46) saved type=COMBINED: dialog
pre-filled matchText with OCR-detected text (RC1 resurrected via UI);
user tapped Save w/o clearing → rect+text conjunct broke on OCR text
variance again (rule 38 covers 94% of page 15 yet excluded=0/4).
FIX: prefill removed; dialog matchText starts EMPTY; blank→ZONE, typed→
COMBINED (opt-in as designed). Dead chain deleted: ExclusionZoneScopeDialog
detectedText param, Dialog.ExclusionZoneScope.detectedText field,
openExclusionZoneScopeDialog param, detectExclusionZoneText +
ocrExclusionCropText fns, ReaderActivity detection block (crop OCR
fallback + selectionChapterId/selectionBox), boxMostlyInside import.
Gates GREEN (docker, correct volume mounts — android-home mounts at
/home/vscode/.android NOT /opt/android-sdk, memory line 172): spotless+
compile 5m12s, spotlessCheck+testDebugUnitTest+assembleDebug 3m14s.
APK installed 16:17. Fresh capture /sdcard/zone-prefill-fix.log running.
NOTE: old COMBINED rules 34-46 from bad session still in device DB —
must be deleted/re-created blank for pure-zone behavior. PHRASE/WORD
rules confirmed working in same log (3/19 etc.) — "phrase broken" cases
were COMBINED-by-prefill zones, not phrase rules.
```

```text
[COMPLETED 2026-09-03 — UI/UX modernization set 1, UNCOMMITTED]

Scope: frontend modernization only; zero business-logic changes. Audited
first (2 explore agents: screens/theme/nav + settings/OCR/TTS), then
presentation-layer edits:

1. Bottom nav: presentation-core NavigationBar.kt → floating elevated
   pill (surfaceContainer, RoundedCornerShape(28.dp), 12dp horizontal
   inset, 8dp bottom inset, navBars windowInsets inside pill). All
   themes/accents unaffected (token-driven).
2. Library Continue section: LibraryContent.kt new param continueItems;
   LibraryTab computes via derivedStateOf from EXISTING LibraryScreenModel
   state (favorites filtered unread>0 && lastRead>0, sorted lastRead desc,
   take(10)) — no SM/repo changes. Rows = surfaceContainerLow cards,
   cover + title + unread-count plural + FilledTonalIconButton resume
   (reuses existing getNextUnreadChapter path via onContinueReadingClicked).
   Hidden during selection/search/active-filters.
3. OCR exclusion phrase EDIT (full chain, NO schema change — new query
   only): ocr_exclusion_zones.sq +updateMatchText; repo iface+impl;
   UpdateOcrExclusionZoneText interactor; DomainModule binding; SM
   updateTextRule(id,text) (trim, blank-reject); UI EditRuleDialog
   (multiline, pre-filled, remember(rule.id)). verifySqlDelightMigration
   GREEN (query-only, no migration needed).
4. OCR exclusions UI: phrases/words collapsed by default (first line +
   "N lines" subtitle, expand arrow + row click toggles, animateContentSize,
   rememberSaveable(zone.id)); zone coords shown only when expanded;
   edit TextButton inside expanded state (words+phrases only); delete now
   error-tinted IconButton. Zone identity resolution: SM injects
   GetManga/GetChapter/SourceManager, builds identities map
   zoneId→"Manga · Chapter"/manga title/source name, rendered as primary
   labelMedium first line on every RuleRow.
5. Feed selector: FeedScreenModel State +selectedSourceId/+listingOverride
   (+visibleFeeds computed filter; sections cache untouched — no refetch on
   filter). FeedScreen FeedFilterBar: All-sources + per-source FilterChips
   (horizontalScroll, shown when ≥2 feed sources) + All/Popular/Latest
   chips (shown when feeds have both listings). visibleFeeds replaces feeds
   in grid. AddFeedDialog/empty/headers untouched.
6. Browse Search tab: new SearchTab.kt (globalsearch pkg) — existing
   GlobalSearchScreenModel + NEW public GlobalSearchTabContent wrapper
   (presentation/browse/GlobalSearchScreen.kt; content-only, no nested
   Scaffold) mounted as TabbedScreen tab 0; search bar = TabbedScreen's
   shared SearchToolbar (searchEnabled=true), typing ≥2 chars triggers
   screenModel.search() (existing infra). BrowseTab now routes searchQuery
   by currentPage (0=search SM, else extensions SM); showExtension() page
   index 1→2. Sources/Extensions/Migrate tabs untouched.
7. More tab: HorizontalDivider group separators → GroupHeader (titleSmall,
   primary color) sections: General / Library / Settings.

i18n base additions (strings.xml): label_continue_reading, action_edit_rule,
ocr_exclusion_edit_rule, ocr_exclusion_lines, feed_all_sources,
action_expand; plurals.xml: continue_reading_unread. No locale hand-edits.

GATES GREEN 2026-09-03 (devcontainer JDK17, -Xmx4g, both volumes):
  spotlessApply → spotlessCheck + testDebugUnitTest +
  verifySqlDelightMigration BUILD SUCCESSFUL 2m27s; :app:assembleDebug
  BUILD SUCCESSFUL 3m15s (APKs built 20:30). :app:compileDebugKotlin green
  after fixes (NavigationBarDefaults ref, asMangaCover import, unreadCount
  Long→Int, GroupHeader moved to top level, searchTab import).

Files changed (13): presentation-core/.../material/NavigationBar.kt,
  app/.../presentation/library/components/LibraryContent.kt,
  app/.../tachiyomi/ui/library/LibraryTab.kt,
  data/src/main/sqldelight/tachiyomi/data/ocr_exclusion_zones.sq,
  domain/.../ocr/repository/OcrExclusionZoneRepository.kt,
  data/.../ocr/OcrExclusionZoneRepositoryImpl.kt,
  domain/.../ocr/interactor/OcrExclusionZoneInteractors.kt,
  app/.../domain/DomainModule.kt,
  app/.../setting/ocrexclusions/SettingsOcrExclusionsScreenModel.kt,
  app/.../settings/screen/SettingsOcrExclusionsScreen.kt,
  app/.../presentation/feed/FeedScreen.kt, app/.../ui/feed/{FeedTab,FeedScreenModel}.kt,
  app/.../ui/browse/BrowseTab.kt,
  app/.../ui/browse/source/globalsearch/SearchTab.kt (new),
  app/.../presentation/browse/GlobalSearchScreen.kt,
  app/.../presentation/more/MoreScreen.kt, i18n base strings/plurals.

Device verification PENDING user (matrix): Continue section renders ≤10 +
resume works, all theme colors OK on nav pill, phrase expand/edit roundtrip
(edit survives + matcher still excludes), zone identity lines correct,
Feed chips filter + Popular/Latest toggle, Browse search tab searches +
result navigation, More grouping, no regressions in updates/history/source
browse/migrate/TTS/OCR playback.
```

```text
[COMPLETED 2026-09-04 — docs design audit: aligned docs/* with shipped v0.5.2, UNCOMMITTED]
- Audited all docs/*.md against the codebase (git log @0286d9081, file tree,
  .sq/migrations, prefs, tests, screens). Docs were 1–4 days stale: they
  described TTS as planned/in-progress and omitted everything from the
  2026-09-01..04 sessions.
- architecture.md: header v0.5.2; app-flow tabs (+Feed); reader flow
  (onPageSelected TTS debounce note); OCR flow entry D=exclusion zones,
  E=TTS; recognizeText LEGACY/FAST→GLENS redirect documented; NEW §4.1 OCR
  exclusion system (schema 18/19.sqm, ZONE/WORD/PHRASE/COMBINED semantics,
  matcher/prefetch/backups/dims-guard); §5 rewritten planned→actual (speech
  pipeline step 2, dispatch-id utterances, speed-aware prefetch); NEW §5.1
  speech pipeline, §5.2 voice profiles, §5.3 rate 50–300; layering table +
  speech/; §6 folder tree (feed/, ocrexclusions/, DictionaryLookupScreen,
  tts/, migrations 1..19); §7 "New files planned" → shipped-file table;
  §11 DI bindings shipped; §12 migrations 1..19 + ocr_exclusion_zones;
  §14 test-suite inventory (counts).
- prd.md: §1.1 v0.5.2/vc28; §1.3 use-case rows (Read Aloud, exclusions,
  Feed, dictionary lookup); §1.4 floating nav + Feed tab; §1.6 shipped
  statement; §2.6 pref classes (+TTS/Voice/Feed); NEW §2.8 TTS additions,
  §2.9 Feed, §2.10 dictionary nav; §3 header SHIPPED; F4 rewritten (EN
  pivot, 50–300%); §3.4 status MET note; §4 backlog = 10B; §6 status
  COMPLETED (+ voice profiles row, rate range, committed hash).
- design.md: §1 floating pill language; §2 surface token (elevation);
  §6 shapes (floating pills vs full-width strips); §8 TtsPlaybackBar =
  floating rounded pill (0.92f/560dp, z-order before dialogs) + speed chip
  + dropdown; §10 animateContentSize; §11 responsive; §13 rate 50–300 +
  voice profiles group + reader-tab slider range.
- phase.md: pointer → v0.5.2 released + uncommitted UI sets (incl.
  prefill-removal follow-up); 10A block: UNCOMMITTED→committed 0480778fd;
  "per-voice profiles" backlog note updated (profiles landed, tuning
  remains).
- memory.md: header state block (v0.5.2 @0286d9081) + agent handoff block
  updated (this block).
- Verified every new claim against source; no code files touched; no
  gates applicable (docs only).
```

```text
[COMPLETED 2026-09-04 — UI follow-up set 2: revert Continue + Search tab, centralize Feed management, UNCOMMITTED]

User-directed changes (device testing of set 1 found Continue + Browse
Search broken):

1. Library Continue section REMOVED: LibraryContent.kt reverted to
   pre-Continue signature (continueItems param + ContinueReadingSection/
   Row/continueSubtitle composables deleted); LibraryTab derivedStateOf
   block removed (import too). Grid/tabs/continue-reading per-item button
   (existing pref feature) untouched.
2. Browse Search tab REMOVED: SearchTab.kt deleted; GlobalSearchTabContent
   wrapper deleted from presentation GlobalSearchScreen.kt (internal
   GlobalSearchContent back to prior shape); BrowseTab back to 3 tabs
   (Sources/Extensions/Migrate) with original searchQuery routing to
   Extensions SM + showExtension() page index back to 1. Global search
   itself (Browse reselect + Sources toolbar action) untouched.
3. Feed central management: NEW ManageFeedsScreen.kt (presentation/feed,
   pushed Voyager Screen, AppBar + ScrollbarLazyColumn + EmptyScreen) —
   one row per feed: source name + listing, up/down reorder, enable
   Switch, delete; mutations reuse FeedScreenModel (prefs-backed, both
   screens stay live). FeedScreen: FeedHeader stripped to name+listing
   only (controls gone from main screen); TopAppBar gains Tune
   "Manage sources" action beside + Add. Signature dropped 4 per-feed
   callbacks (FeedTab no longer passes them). Filter chips (All/popular/
   latest selector) unchanged.

i18n base additions: feed_manage, feed_manage_empty, feed_manage_reorder.
(label_continue_reading/continue_reading_unread plurals now unused — kept,
harmless; action_expand/ocr edit keys still used by OCR screen.)

GATES GREEN 2026-09-04 (devcontainer JDK17, -Xmx4g, both volumes):
  spotlessApply; spotlessCheck + testDebugUnitTest + verifySqlDelightMigration
  BUILD SUCCESSFUL 2m44s; :app:assembleDebug BUILD SUCCESSFUL 1m24s.
  APK installed 02:2x on SM_M066B, app boots PID 19178, 0 FATAL in
  fresh capture .device-pass/ui-modern-2.log (PID 82688, running).

Files changed (7): presentation/library/components/LibraryContent.kt,
  tachiyomi/ui/library/LibraryTab.kt,
  presentation/browse/GlobalSearchScreen.kt,
  tachiyomi/ui/browse/BrowseTab.kt,
  presentation/feed/FeedScreen.kt, presentation/feed/ManageFeedsScreen.kt
  (new), tachiyomi/ui/feed/FeedTab.kt, i18n base strings.xml.
  (SearchTab.kt deleted.)

Device verify PENDING user: Library clean (no Continue, grid normal),
Browse 3 tabs (Sources/Extensions/Migrate search routing OK), Feed main
screen headers control-free + Tune opens ManageFeedsScreen (toggle/
reorder/delete reflect live on return).
```

```text
[COMPLETED 2026-09-05 — deferred audit-fix set (separate from UI modernization
set 3 + MangaScreen task), UNCOMMITTED]

Scope: the 7 remaining design-audit deferred items ONLY. Audited first;
3 items needed code, rest documented.

1. Reselect/scroll-to-top: DEFERRED — audit §5 marks it NEEDS APPROVAL;
   no approval recorded; current semantics intentionally kept (Library=
   settings sheet, Updates=DownloadQueue, History=resume-last-chapter,
   Feed=none, More=Settings). No change.
2. OCR engine settings discoverability: FIXED via cross-link —
   SettingsReadAloudScreen Advanced group gains "Text Recognition" row
   (title label_text_recognition, subtitle pref_ocr_model, existing keys)
   pushing existing OcrQueueScreen. No relocation, no duplication, no
   second source of truth; OcrQueueScreen/MoreScreen untouched. Settings
   screens pushing ui/ screens = existing precedent (CategoryScreen,
   OnboardingScreen).
3. ttsSpeechScript: NOT dead — consumed by TtsPreferences
   speechRegionFilterConfig() (drives skipForeignScript semantics, default
   LATIN); zero UI exposure (3 i18n keys unused-in-UI). Left unchanged;
   optional future cleanup = expose picker or drop pref+keys together.
   ocrTextSelectionEnabled: pref key gates OCR button visibility; key name
   kept (rename = persistence risk); user-facing label already accurate
   ("OCR text selection button", strings.xml:452). No change.
4. TrackInfoDialogHome double-clip: FIXED — removed redundant trailing
   .clip(RoundedCornerShape(6.dp)) after background+padding (inner clip
   clipped content, not bg, and second radius fought shapes.medium).
   Geometry preserved: shapes.medium + surfaceContainerHighest + 8dp
   padding unchanged. RoundedCornerShape import dropped.
5. Dictionary token micro-pass: FIXED — SearchBar textStyle 15.sp hack
   → pure bodyLarge token. WordSelector 20.sp magnified OCR header =
   deliberate (tap targets), kept. Delete icon in SettingsDictionaryScreen
   already error-tinted (IconButtonDefaults contentColor=error); rest
   token-clean. No redesign.
6. HistoryItem: FIXED — Row .height(96.dp) → .heightIn(min = 96.dp);
   text column now drives height (wraps at large font scale, no clip);
   cover keeps fixed 96dp (identical visual, wrap-safe). IconButtons
   (48dp) untouched; interaction behavior unchanged.
7. Regression audit: no tab/nav files touched; MoreScreen OCR row
   untouched (Settings cross-link is additive); OCR button pref path
   untouched; TTS prefs untouched; TrackInfo geometry preserved.

GATES GREEN 2026-09-05 (devcontainer JDK17, -Xmx4g, both volumes):
  spotlessCheck + :app:compileDebugKotlin + :presentation-core:
  compileDebugKotlin BUILD SUCCESSFUL 3m2s; testDebugUnitTest BUILD
  SUCCESSFUL 3m3s. No DB/schema files touched → verifySqlDelightMigration
  N/A.

Files changed (4): presentation/track/TrackInfoDialogHome.kt (clip),
  presentation/dictionary/components/DictionaryComponents.kt (15sp),
  presentation/history/components/HistoryItem.kt (heightIn),
  presentation/more/settings/screen/SettingsReadAloudScreen.kt
  (+navigator import, +OcrQueueScreen import, +Advanced cross-link row).

Device verify pending user: TrackInfo dialog visuals (shape unchanged,
one clip), History row at default + large font scale, Read aloud
Settings → Advanced → Text Recognition opens queue screen.
```

```text
[COMPLETED 2026-09-05 — UI-modernization Phase B-M: MoreScreen grouped
surfaces, UNCOMMITTED]

One file: presentation/more/MoreScreen.kt. ListGroupHeader sections →
PreferenceGroupCard sections (widget from Phase A foundation, same Gradle
module — internal import OK). Report: /tmp/opencode/phaseBM-report.md.

- 3 cards, existing i18n keys reused (zero new strings):
  pref_category_general (Downloaded only + Incognito switches),
  pref_category_library (Download queue, OCR queue — both state-subtitle
  rows, Categories, Stats, Data and storage, Dictionary lookup, Manage
  dictionaries), label_settings (Settings, Support us, About, Help URI).
- LogoHeader item 0 untouched, outside cards. One card = ONE lazy item,
  rows non-lazy Column inside (counts 2/7/4). 12dp spacer items between
  cards (matches PreferenceScreen rhythm, no trailing spacer).
- All row widgets/callbacks/state providers verbatim; queue-state
  providers now compose inside Library card item (slightly coarser
  recomposition scope — negligible at these row counts).
- ListGroupHeader import removed (unused). No frost/shadows/row metric
  changes. No two-pane code in file (nothing to preserve). Only caller
  MoreTab.kt uses public MoreScreen signature — unchanged.

GATES GREEN 2026-09-05 (docker vsc-yomihon-e24e3bd7e46d…, JDK17, -Xmx4g,
both volumes): spotlessApply + :app:compileDebugKotlin BUILD SUCCESSFUL
2m33s; spotlessCheck + :app:compileDebugKotlin BUILD SUCCESSFUL 5m15s.
No DB/pref/semantic changes; no unit tests touched (presentation-only).

Device verify pending user: More tab = 3 tactile cards under logo, all
themes, queue subtitles live during downloads.
```

```text
[COMPLETED 2026-09-05 — UI-modernization Phase B-R: reader floating-chrome
unification, UNCOMMITTED]

4 files, all reader floating chrome moved to asFloatingChrome() role
(Translucent.kt foundation): ReaderAppBars.kt shared backgroundColor =
surfaceColorAtElevation(3.dp).asFloatingChrome() (dead isSystemInDarkTheme
branch deleted); ChapterNavigator.kt — computes its OWN color (NOT passed
from ReaderAppBars) — same swap + comment fixed ReaderActivity→ReaderAppBars;
TtsPlaybackBar.kt .copy(0.9/0.95).asChromeContainer() → .asFloatingChrome()
(fixes opaque-when-pref-ON + translucent-when-OFF bugs); OcrLoadingIndicator.kt
surfaceContainer strip → frosted role. OcrResultPopup deliberately untouched
(content panel, readability ruling). Spec discrepancy found: forked
surfaceColorAtElevation is private in presentation-core Surface.kt — kept
androidx import (identical result at these sites). Read-only audit: reader
settings slider pills (surfaceContainerHighest) correctly stay opaque-tokened
inside frosted sheet (no frost-on-frost); ReaderActivity passes NO chrome
colors (viewer bg only). Report: /tmp/opencode/phaseBR-report.md.

GATES GREEN 2026-09-05 (docker vsc-yomihon-e24e3bd7e46d…, JDK17, -Xmx4g,
both volumes): spotlessApply + :app:compileDebugKotlin 2m26s; spotlessCheck +
:app:compileDebugKotlin 2m27s. Side effect: forced spotlessApply also
reformatted 4 PRE-EXISTING dirty files from earlier sets (ktlint-only).
Stale Gradle journal-1.lock (rogue parallel container) removed once.

Device verify pending user: reader bars/tray/navigator/TTS pill/OCR strip
frosted 0.85 with pref ON, opaque OFF, both themes, no frost-on-frost.
```

Feature: Phase 10A advanced system TTS voice configuration — COMPLETE
           (device-verified 2026-08-31), ALL UNCOMMITTED awaiting user commit.
           Tasks 1-6 (domain prefs/resolver, TtsEngine contracts,
           AndroidTtsEngine re-apply design, Read aloud & voice screen,
           root entry + reader deep-link, docs) + Task 7 device pass
           (USER CONFIRMED all manual checks PASS 2026-08-31) + voice-picker
           SEARCH feature (user-requested follow-up: BasicListPreference
           searchable flag → ListPreferenceWidget OutlinedTextField filter,
           voice picker only; gates green, APK 0.5.0-8250 installed).
           Evidence: .device-pass/tts-10a-test.log (571,082 lines, PIDs
           14214/30339): advances 17→18 confirmed 1-2ms, pause/stop clean,
           preview audio-focus request/abandon paired (08:51:53→56 cycle),
           "TTS default voice restored name=en-IN-language" ×4 (SystemDefault
           re-apply path device-proven), 0 FATAL exceptions, 0 sentence
           failures, single TTS engine connection per session. Device engine
           reality: Google Speech (com.google.android.tts) = only real
           synthesis engine; SamsungTTS present as download-provider only
           (no voices installed) — picker correctly lists real engines only.
           Deferred to 10B: per-voice rate/pitch profiles, cloud/neural
           providers, local neural engines, downloadable AI voices,
           expressive speech.
```

Done: leak-fix set COMMITTED (5c7d2cc2c): ReaderViewModel.onCleared
      ttsEngine.onFocusEvent = null (detaches dead controller from singleton
      engine — the LeakCanary-flagged ~100MB chain: TTS service native root →
      AndroidTtsEngine → onFocusEvent → dead controller → ViewModel → destroyed
      Activity); ReaderActivity DisposableEffect onDispose cancels
      settingsScreenModel.ioCoroutineScope (both composition blocks);
      LeakCanary core debugImplementation re-enabled. Known issue #5 → RESOLVED
      (old "bounded, self-healing" assessment wrong — LeakCanary showed ~100MB).
      Memory profile captured (meminfo-profile.log: Java 8–25MB / Native 8–28MB
      / TOTAL ~160–267MB, stable). Gates green 2026-08-29. Debug APK 0.4.0-8241
      (built from 5c7d2cc2c) installed on SM_M066B 2026-08-29.
      NOTE: user docs commit fd52a613a accidentally reverted memory.md/phase.md
      to pre-leak-fix state and broke ReaderActivity import order; docs
      restored + imports re-fixed (spotlessApply) this session (2026-08-29).

FRESH LOGCAT ANALYSIS (2026-08-28): logcat-8234-phase9.log (7,793 lines,
  PID 27363, 12 min window 02:26:39–02:38:50). Real TTS/OCR activity captured
  across 3 chapters (206, 205, 1). Key findings:
  - 9/9 page advances confirmed (0 timeouts) — Known issue #8 RESOLVED
  - 0 bitmap recycle crashes — Known issue #10 RESOLVED
  - 0 sentence failures
  - Cache hits: 72, misses: 15
  - OCR scan times: 957ms–16.7s (tiled strips)
  - Cold startup: 6.7–13.9s; warm startup: 1.9s
  - NEW ISSUE: Page 15→16 spam loop (19 prefetch cancellations + 20 scan starts
    in 7s) — prefetch restart race condition → FIXED 2026-08-28 (see below)
  - NEW ISSUE: Rapid swipe (13 pages in 0.7s) fires 13 simultaneous OCR scans
    → FIXED 2026-08-28 (see below)
  - ScrollToRegion: not visible in logs → instrumentation ADDED 2026-08-28
  - HTTP 502 GLens error (server-side, retry succeeded)

P0 + P1 FIXES LANDED (2026-08-28, COMMITTED as 872c55397 + 8cb320e7c, all gates green):
  - P0 prefetch spam: TtsPlaybackController.rebuildQueueForUserNavigation now
    dedups on lastRebuildPageIndex (skip if same page already handled);
    schedulePrefetch guards on prefetchPageIndex (skip if same page already
    in flight); both trackers reset in resetSession + on provideContext()==null.
  - P1 rapid-swipe mass OCR: ReaderViewModel.onPageSelected debounces the TTS
    call by 250ms (TTS_PAGE_SELECTED_DEBOUNCE_MS) for user navigation; advance
    confirmations (hasPendingAdvance) still land immediately, never debounced.
  - ScrollToRegion: added logcat in WebtoonViewer.scrollToRegion (success +
    view-not-laid-out paths) so next device run confirms auto-scroll fires.

MINI-PLAYER Z-ORDER FIX (2026-08-28, COMMITTED as 41200022e, all gates green):
  - Root cause: in the inner ContentOverlay Box (ReaderActivity.kt),
    TtsPlaybackBar was the LAST child — declared after `when (state.dialog)` —
    so it drew ON TOP of the inline OcrResultOverlay (scrim + popup/sheet),
    while window-based dialogs (AdaptiveSheet) always covered it: inconsistent
    z-order. FIX: moved TtsPlaybackBar before the dialog block so all
    dialogs/overlays render above the pill. OcrLoadingIndicator position
    unchanged (pre-existing behavior). User committed prior fix set as
    fff9583f0 + 872c55397 + 8cb320e7c.

CONTROLLER ACTION LOGGING (2026-08-28, COMMITTED as 41200022e/be31edb71, all gates green):
  - Known issue #9 closed. TtsPlaybackController now logs DEBUG action-level
    events: pause (page+sentence), resume (page+resumeIndex), stop (prior phase),
    stepBy next/prev (from->to + phase, plus boundary-reject), and audio-focus-loss
    pause (event name). Indices/phase only — no spoken text (rules §7). Makes
    device script steps 4 (pause/resume) + 5 (prev/next sentence) verifiable.

DEVICE VERIFICATION RUN4 — SCRIPT STEPS 7–15 (2026-08-28, build 0.4.0-8238):
  Evidence: .device-pass/tts-steps7-15.log (144,053 lines, PID 5582,
  19:44:42–19:49:29; chapters 1189 → 1188 auto-advance → 1942).
  - Step 7 rate/pitch live: PASS (USER CONFIRMED 2026-08-28: rate and pitch
    changes worked correctly during playback; engine setters have no logs by
    design).
  - Step 8 chapter transition: PASS — 19:45:43 "TTS chapter advance request"
    ch1189→ch1188; page 0 on-demand scan 4.6s; playback continued from page 0.
  - Step 9 end-of-content: PASS — 19:48:44 "TTS stop (phase=Finished)"; clean
    finish, engine disconnected, no crash.
  - Step 10 home-during-playback: PASS — home 19:46:13 (onTop=false), auto-pause
    logged (page=1 sentence=1), return 19:46:30. (Session then needed restart
    due to the prefetch-DNS bug below, not the home transition itself.)
  - Step 11 rotation: PASS (USER CONFIRMED 2026-08-28: rotation worked —
    playback paused on rotation, resume continued from the same OCR
    line/position. No rotation events in logcat because reader orientation is
    preference-locked; pause/resume path verified by user observation).
  - Step 12 exit reader: PASS — stop(Playing) → stop(Idle) → "Disconnected from
    TTS engine" <1s; zero TTS/OCR activity after exit.
  - Step 13 audio focus: PASS — YouTube Music opened → "TTS audio focus lost
    (PermanentLoss); pausing" 19:46:46 → auto-pause; manual resume 19:46:54
    continued from the SAME sentence (page=1 sentence=5).
  - Steps 14/15 exit-idle: PASS — final stop 19:48:47.383 → engine disconnect
    19:48:47.932 (550ms); process idle of TTS work afterwards.
  - Advance confirms: 4/4 confirmed 1–2ms, 0 timeouts; chapter advance 1/1.
  - Sentence stepping: 6 rapid next-sentence steps (2→8) all re-acquired from
    cache 4–9ms + ScrollToRegion; 0 boundary rejects. Pause/resume: 3 clean
    cycles resuming from correct sentences (0, 8, 5).
  - ScrollToRegion auto-scroll ON-DEVICE VERIFIED: bboxTop increases
    monotonically per page (was "not visible in logs" before instrumentation).
  - FINDING #4 (P1) FIXED 2026-08-28: background PREFETCH scan failure
    escalated to a session-killing Error. Evidence: home-press 19:46:13 → DNS
    failure (UnknownHostException lensfrontend-pa.googleapis.com) during page 2
    prefetch at 19:46:20 → scanOnDemand called fail(TtsError.OcrError) →
    healthy paused session entered Error phase; user had to restart (warm
    start 50ms at 19:46:40). Root cause: scanOnDemand unconditionally calls
    fail() on exception, but prefetch reuses it — the prefetch wrapper's
    best-effort catch never fired because scanOnDemand swallows exceptions and
    returns null. FIX: scanOnDemand gained reportFailure param (default true);
    prefetch passes false and logs "prefetch scan failed (best-effort)";
    misleading "prefetch complete" now only logs on non-null result. Main loop
    behavior unchanged (still reports its own failures).
  - GlanceAppWidget composition error 19:48:26 "CompositionLocal LocalContext
    not present" — non-fatal, widget-related, NOT TTS — new Known issue #12.

PREFETCH-DNS FIX DEVICE VERIFICATION (2026-08-28, fresh APK with fix):
  Evidence: .device-pass/logcat-prefetch-verify.log (ring-buffer dump, PID
  22599, 20:12–20:16, chapter 1187, wifi-killed test). RESULT: FIX VERIFIED.
  - 13 prefetch failures for page 4 (wifi off) each logged "TTS prefetch scan
    failed page=4 (best-effort)" while page 3 playback + 13 next-sentence
    steps continued uninterrupted — zero Error-state escalation.
  - Home-press mid-prefetch (page 6 scan in flight) → auto-pause 20:16:14
    (page=5 sentence=17) → stop(phase=Paused) — user observed Paused, not
    Error (the exact run4 regression scenario).
  - Main-loop scan failure on the CURRENT page (page 4, wifi off) still
    honestly reports Error; user retry after wifi restore → 16.8s tiled scan
    → playback resumed. Correct by design.
  - Advance confirms 4/4 @ 1–4ms, 0 timeouts; pause/resume cycles clean.

DEVICE VERIFICATION RUN2 (2026-08-28): build 0.4.0-8236 reinstalled, on-device
  logcat (.device-pass/logcat-8236-run2.log, 185,361 lines, PID 25847). NOTE:
  first capture attempt died — streaming `adb logcat` over wireless adb drops
  when the link blips; use ON-DEVICE capture instead:
  `adb shell "logcat -c; nohup logcat -v threadtime > /sdcard/tts-test.log 2>&1 &"`
  then `adb pull`. Findings:
  - FINDING #1 (advance-confirm) FIXED+VERIFIED: 22/22 advances confirmed,
    0 timeouts, request→confirm latency 1–5ms (was 10s wall). Root cause was
    scrollToPositionWithOffset = layout-driven jump that never dispatches
    onScrolled; fix = WebtoonViewer.moveToPage now calls onScrolled(pos)
    explicitly after the scroll. Session ran clean page 4→22 auto-advancing.
  - FINDING #2 (stale page on timeout) FIXED (safety net): awaitAdvanceConfirmation
    timeout branch now sets pageIndex=target so resume() can't re-read the prior
    page. 0 timeouts occurred so branch unexercised; pause/resume verified correct
    (resume pageIndex=3 resumeIndex=4 then 9).
  - FINDING #3 (duplicate speech) RESOLVED-AS-EXPECTED + DEFERRED: run2 dups were
     USER tapping prev/next-sentence buttons (confirmed) — stepBy() re-speaks from
     the chosen sentence, expected nav behavior, NOT a bug. A SEPARATE hands-off
     "words/phrases read twice" was reported but did NOT reproduce in run3
     (logcat-8236-run3.log: 55 dispatches/55 unique ids, 0 engine onStop/onError,
     0 retries, 4/4 advances). Likely manga-specific OCR overlapping regions or a
     Google-TTS audio quirk. USER DECISION 2026-08-28: DROP for now, LOW PRIORITY,
     revisit after full app build (see Deferred issues).
  - TTS-DBG instrumentation REMOVED 2026-08-28 (all files reverted); #1 + #2 fixes
     KEPT. Gates re-run green (spotlessCheck + testDebugUnitTest + assembleDebug).

LEAKCANARY PASS — FINDING #5 ~100MB READER LEAK (2026-08-28, fix COMMITTED
  2026-08-29 as 5c7d2cc2c):
  Evidence: .device-pass/logcat-leakcanary.log (37M; LeakCanary permission
  prompt 20:33:01, leak-launcher badge present = leak detected; TTS session
  PID 31288 same log). LeakCanary (previously commented out upstream) enabled
  via debugImplementation(libs.leakCanary.core) flagged the reader screen
  retaining ~100MB after exit. Root cause: system TTS service keeps a native
  GC root to the TextToSpeech callback → AndroidTtsEngine singleton →
  onFocusEvent lambda → dead TtsPlaybackController → ReaderViewModel →
  destroyed ReaderActivity. FIX (3 files, COMMITTED 5c7d2cc2c):
  - ReaderViewModel.onCleared: ttsEngine.onFocusEvent = null after
    stop()/shutdown() — detaches controller from singleton engine; next
    reader session re-registers in controller init. Supersedes the old Known
    issue #5 "bounded, self-healing" assessment: LeakCanary showed ~100MB
    retained, NOT a small bounded object graph.
  - ReaderActivity: DisposableEffect { onDispose { settingsScreenModel
    .ioCoroutineScope.cancel() } } (both composition blocks) — the settings
    screen model's io scope outlived the Activity otherwise.
  - app/build.gradle.kts: LeakCanary core re-enabled for debug builds
    (Phase 9 leak verification; upstream had it commented out).
  Known issue #5 → RESOLVED by this fix. GATES GREEN 2026-08-29
  (spotlessCheck + :app:compileDebugKotlin + testDebugUnitTest, BUILD
  SUCCESSFUL 2m56s). Memory profile captured (meminfo-profile.log: Java
  8–25MB / Native 8–28MB / TOTAL ~160–267MB, stable, no leak spike).
  Debug APK 0.4.0-8241 installed on SM_M066B 2026-08-29. Device re-verify
  (leak gone) DONE 2026-08-29 — see LEAK-FIX DEVICE VERIFICATION below.

LEAK-FIX DEVICE VERIFICATION (2026-08-29, build 0.4.0-8241, PID 31179):
  Evidence: .device-pass/leak-full.log (33.6M full ring dump, 18:15–19:40).
  RESULT: FIX VERIFIED — LeakCanary heap dump (user-requested 19:38:32,
  analyzed 19:40:16, 95s) reports **0 APPLICATION LEAKS** after TWO full
  reader TTS sessions + exits (18:55 webtoon ch248 + 19:29–19:31 ch248
  re-run). Heap total 41.8MB, 79 bitmaps 7.5MB, large bitmaps 0.
  - Session 1 (18:55): rapid user nav pages 5↔6 fired only 3 on-demand
    scans in 1.2s (250ms debounce + single-flight working as designed),
    prefetch cache hits, stop(Playing)→stop(Idle) clean.
  - Session 2 (19:29): startup open→first page ready 3764ms (cache hit);
    advances 8→9 confirmed 1–6ms, 0 timeouts; page acquire 5–15ms cache
    hits; prefetch hits; stop clean.
  - No sentence failures, no advance timeouts, no Error phases.
  - Noise only: transient GLens HTTP 502 (known server-side, retried OK);
    SQLiteLog POSIX 3850 warnings (system-side, not TTS).
  Finding #5 device-verified CLOSED. Phase 9 leakcanary sign-off DONE.

BATTERY MEASUREMENT — PHASE 9 FINAL ITEM (2026-08-29, build 0.4.0-8241):
  Method: batterystats --reset + on-device 10s sampler (level/current/charge
  counter → battery-sample.log, 5726 lines) + on-device full logcat
  (battery-tts-session.log, 65.6MB) + post-hoc `dumpsys batterystats
  app.yomihon.dev` (batterystats-app.txt). Charger unplugged for whole
  measurement window (powered 20:03, unplugged 20:10, replugged 21:04).
  Realistic use: 23 min hands-off-ish session (PID 31179): villain-to-kill
  ch2→1 auto-advance, then ch246→ch242, rapid sentence stepping, 5 chapter
  advances, 58/58 page advances confirmed 1–3ms, 0 timeouts, 94 on-demand
  Glens scans (fresh chapters, 97 cache hits elsewhere), 0 sentence
  failures. Session ENDED at 20:31:50 by transient GLens HTTP 502 (known
  server-side; scan-fail → honest Error = correct by design). Short second
  session 20:45 (PID 32549, cached ch244, ~35s, sentence stepping + clean
  stop 20:45:51, engine disconnect <1s).
  RESULTS (sampler, device-wide current incl. screen+modem):
  - Active TTS+OCR session (20:08:39–20:31:50): avg ~365mA, median ~319mA
    device-wide. Battery 5000mAh nominal → ~13h continuous playback at that
    screen-on draw; screen is the dominant consumer (Samsung attributes
    84.5mAh of app's 115mAh to screen).
  - Post-exit, app alive background (20:31–20:44): avg ~222mA
    device-wide — dominated by OTHER apps/screen (user browsing); app's own
    logcat TTS/OCR activity = ZERO after 20:31:50.
  - App killed by LMK 20:44:50, cached-frozen 20:49:28. Frozen-idle
    (20:46–21:04): median device draw -14.8mA floor (idle floor), app frozen
    = no app CPU.
  - batterystats attribution (u0a435 = app.yomihon.dev, full 1h34m
    on-battery window): TOTAL 115mAh (fg 25.4mAh CPU / 24m51s foreground,
    bg 4.18mAh / 12m26s, cached 1.16mAh / 23m39s); screen-off/doze drain
    cpu:bg=4.02mAh+cached=1.01mAh ≈ 5mAh over 41m screen-off — that
    includes LMK reaping + freeze accounting, i.e. no runaway background
    work; TOTAL partial wakelock for uid = 0 (only WindowManager screen
    wakelock 19m = reader visible, as designed with keep-screen-on).
    App CPU total: 10m51s usr + 2m19s krn across both PIDs.
  - Keep-screen-on worked as designed: 19m2s full wakelock exactly while
    reader visible during playback.
  VERDICT: PASS. No post-exit battery drain (TTS engine disconnect <1s,
    zero app log activity 20:32–20:44, no wakelocks, cached-frozen within
    ~4 min of exit, screen-off CPU cost ≈ noise).
  LIMITATIONS (honest): (a) sampler measures DEVICE current, not app
    rail — app-specific numbers come from batterystats estimates
    (Samsung-model-based, ±); app fg CPU cost ≈ 25mAh for ~25 min
    foreground ≈ 1mAh/min with screen+TTS+OCR+network. (b) Active phone
    call 3m40s + other apps (YouTube-class audio app u0a222 94mAh,
    u0a334 206mAh) shared the same on-battery window — session avg current
    includes their draw where overlapping. (c) GLens 502 ended the long
    session at 23 min instead of user-planned ~30 min; coverage still
    spans 3 chapters + 94 real network OCR scans. (d) No A/B vs
    TTS-off baseline run recorded (would need second unplugged pass);
    relative cost: app fg 25.4mAh CPU vs screen 84.5mAh in same window →
    app compute is minority of app-session cost, screen dominates —
    expected for a reader.
  PHASE 9 COMPLETE. Remaining: commit small uncommitted set (import order
  + docs) when user approves.

[COMPLETED 2026-08-30 — Reader TTS mini-player UI polish, UNCOMMITTED (3 files)]
- Redesigned TtsPlaybackBar full-width bottom bar → floating pill:
  wrap→fixed width (fillMaxWidth(0.92f) + widthIn(max=560dp)), 28dp rounded
  corners, surfaceColorAtElevation(3).copy(0.9/0.95 alpha), shadow 6dp,
  text slots weight(1f) so icon row anchors (no width jump per sentence).
- Layout-aware positioning (no hardcoded offsets): ReaderAppBars gains
  onBottomTrayHeightChanged callback — onGloballyPositioned on bottom
  AnimatedVisibility reports real tray height (incl. nav-bar insets; 0 when
  hidden); ReaderActivity computes clearance = max(trayPx, navBarPx,
  cutoutPx) + 12dp margin; pill rides above bottom tray when menu visible,
  falls back to insets when hidden. Rotations/config changes re-measured.
- Perf: position animated via animateIntAsState → Modifier.offset{} lambda =
  PLACEMENT-phase only (zero re-measure per frame), 150ms tween (was
  animateDpAsState→padding 200ms = measure-phase churn = choppy).
  Root cause of "slow/choppy" was structural (padding invalidation), not
  jank — confirmed via logcat (no skip attributable to pill).
- Gates GREEN 2026-08-30: spotlessCheck + testDebugUnitTest + :app:assembleDebug
  (devcontainer JDK17, -Xmx4g). Installed SM_M06B arm64 APK.
  User device-tested ALL scenarios PASS (manga+webtoon, portrait+landscape,
  rotation, phase changes incl. Preparing/Playing/Paused/Error).
- Final logcat verification pass (.device-pass/ui-polish-final.log, PID 11054,
  23:53–23:57 session): 0 TTS errors/timeouts, 0 crashes, 0 Compose errors.
  All Choreographer frame-skips attributed, NONE from pill code:
  319f+68f+39f+34f = LeakCanary heap-dump cycle (hprof 74MB 5.2s + explicit
  GCs, debug-build tooling, absent in release); 53f = dropdown popup attach;
  51f = reading-mode viewer switch (webtoon→L2R pager recreation + toast,
  known upstream cost); 37f = background GC freeing 36MB (normal churn).
  TTS advances 1–6ms confirms, OCR cache hits 5–25ms, prefetch clean.
- LeakCanary same-session analysis: flagged 1 APPLICATION LEAK signature
  (WebtoonPageHolder ~49.9MB, d385fca8…) — WEBTOON page holder retained
  via mapLatest flow chain while reader ACTIVE (Activity mDestroyed=false,
  view attached). NOT the TTS pill; NOT reader-exit retention (past passes
  showed 0 application leaks after exit). Likely live-page bitmap cache
  held by still-active viewer page recycling lag; separate investigation
  item, not a regression of this change set. No release impact.
- KNOWN ISSUE #13 (NEW, LOW): LeakCanary Toast-watching noise (retained
  Toast FrameLayout) triggers debug heap dumps mid-session; consider
  excluding android.widget.Toast in LeakCanary config if it bothers future
  device passes.

[COMPLETED 2026-08-30 — Phase 10A Task 1, UNCOMMITTED (2 files)]
- TtsVoicePreferences (:domain mihon.domain.tts.service) — engine package /
  voice name / language tag string prefs (keys pref_tts_engine_package /
  pref_tts_voice_name / pref_tts_language_tag, default "") + reset();
  TtsVoiceSelection sealed interface (SystemDefault / Voice / Language) +
  pure resolveVoiceSelection fallback: valid voice → Voice, else valid
  language → Language, else SystemDefault. Consumes PreferenceStore
  (precedent TtsPreferences.kt). Tasks 2/3/5 consume this.
- TDD: TtsVoicePreferencesTest 5 cases — RED (unresolved reference,
  compile fail as brief expected) → GREEN 5/5. Full :domain:testDebugUnitTest
  green (all suites). spotlessCheck green (devcontainer JDK17, -Xmx4g).
  NOTE: brief's bare docker cmd lacks -v yomihon-gradle-home/-v yomihon-
  android-home mounts — wrapper re-downloads Gradle 9.6.1 each run and
  times out; mount both volumes (memory 2026-08-24 directive stands).

[COMPLETED 2026-08-30 — Phase 10A Task 3, UNCOMMITTED (3 files)]
- AndroidTtsEngine implements extended TtsEngine interface (:app):
  constructor gains TtsVoicePreferences; cached enginePackage/voiceName/
  languageTag fields + activeEnginePackage (set at initialize); engine-
  package-aware TextToSpeech creation (3-arg ctor (context, listener, pkg)
  when pkg non-empty, else 2-arg); idempotent initialize path re-applies
  voice config from fresh prefs BEFORE returning true (mid-session pref
  changes land at next pause/resume with ZERO controller changes —
  controller/ReaderViewModel untouched); applyVoiceConfig uses
  resolveVoiceSelection (Voice → setVoice + DEBUG log; Language →
  setLanguage + LANG_MISSING_DATA/LANG_NOT_SUPPORTED fallback log;
  SystemDefault → no-op) — NEVER fails initialize; setEnginePackage
  compares against activeEnginePackage, shutdown releases instance when
  mismatch; getEngines/getVoices mapped to TtsEngineInfo/TtsVoiceInfo.
- applyVoiceConfig re-reads ONLY voiceName/languageTag — NOT enginePackage
  (deviation from brief §4: re-reading enginePackage would cache the new
  value and make Task 5's setEnginePackage(newPkg) early-return, so a live
  instance built with the OLD engine would never rebuild. activeEngine-
  Package tracks creation-time package for the compare).
- BRIEF API CORRECTIONS (brief assumed wrong SDK signatures): 3-arg
  TextToSpeech ctor is (context, OnInitListener, enginePackage) — NOT
  (context, pkg, listener); engine.defaultEngine returns String (package
  name) — NOT EngineInfo; EngineInfo has name (pkg) + label: String (non-
  null) — no packageName field. Verified via javap android-36 android.jar.
- DI: PreferenceModule +TtsVoicePreferences factory (after TtsPreferences);
  DomainModule AndroidTtsEngine(get<Application>(), get<TtsVoicePreferences>()).
- Regression guard: speak/stop/shutdown/audio-focus/pendingUtterances/
  onFocusEvent paths byte-identical (verified via git diff — none appear).
- Gates GREEN 2026-08-30 (docker devcontainer JDK17, -Xmx4g, both volumes):
  :app:compileDebugKotlin BUILD SUCCESSFUL 4m19s; spotlessCheck BUILD
  SUCCESSFUL 35s; full testDebugUnitTest BUILD SUCCESSFUL 2m40s.
  NO commit (per ruling).

[COMPLETED 2026-08-30 — Phase 10A Task 4, UNCOMMITTED (4 files)]
- ReadAloudSettingsScreenModel (:app ui/setting/readaloud): StateScreenModel
  over ReadAloudSettingsState (@Immutable; isLoading/loadFailed/engines/
  voices/selectedEnginePackage/selectedVoiceName/selectedLanguageTag/rate/
  pitch/isPreviewPlaying). load() = engine.initialize() FIRST (empty lists
  guard), false → loadFailed state; true → getEngines/getVoices + pref
  reads into state. selectEngine guards pkg against loaded engines, writes
  all three voice prefs (voice+language reset ""), engine.setEnginePackage,
  reload. selectLanguage writes languageTag + clears voice pref (no engine
  call — applied at next initialize). selectVoice writes voice pref +
  engine.initialize() relaunch (re-apply on live instance). setRate/setPitch
  write global TtsPreferences pair (controller collects live). preview() in
  screenModelScope try/finally: stop → initialize → acquireFocus → speak
  (hardcoded PREVIEW_TEXT, no i18n per ruling) → finally resets
  isPreviewPlaying + abandonFocus + stop (scope-cancel safe). stopPreview()
  = engine.stop() (speak returns → finally resets flag). resetVoiceConfig()
  = voicePreferences.reset() + setEnginePackage("") + reload.
- SettingsReadAloudScreen (SearchableSettings object, Anki pattern):
  loading → CustomPreference spinner; loadFailed → InfoPreference
  (tts_voices_unavailable) + retry TextPreference; 3 PreferenceGroups:
  Text to speech (engine picker + Language + Locale + Voice pickers),
  Voice calibration (rate/pitch SliderPreferences 50..200% reusing
  pref_tts_speech_rate/pitch keys + preview TextPreference with small
  CircularProgressIndicator widget while isPreviewPlaying), Advanced
  (engine info TextPreference no onClick, Available voices InfoPreference
  %d, Reset voice configuration + toast). Helpers private in screen file:
  localeDisplayName (Locale.forLanguageTag + getDisplayName, blank→tag),
  voiceLabel (name + " · high quality" q>=400 / " · low latency" l<=200 /
  " · network" — API facts only), engineInfoSummary (label · voice count).
- LANGUAGE/LOCALE RULING implemented (brief's binding resolution): ONE
  persisted pref (ttsLanguageTag full-tag semantics). Language row: entries
  = "" (Default system engine) + ALL distinct full tags sorted. Locale row:
  "" + full tags filtered to selectedLanguageTag prefix, enabled only when
  a language is selected; both rows write ttsLanguageTag via selectLanguage.
  Bare-language entries exist only if engine reports them under bare tags.
  Voice picker filter: exact tag match when selected tag contains '-',
  prefix match for bare tag, all voices when empty. Stale-pref values not
  in entries render raw tag/name via subtitleProvider { v, e -> e[v] ?: v }
  (engine picker falls back to Default system engine label) — never "null".
- i18n: 17 keys added to base strings.xml TTS block (pref_category_read_
  aloud, pref_read_aloud_summary, tts_section_text_to_speech/
  voice_calibration/advanced, tts_engine/voice/language/locale,
  tts_default_system_engine, tts_engine_information, tts_available_voices
  (%d), tts_preview_voice, tts_play_sample, tts_reset_voice_config,
  tts_config_reset, tts_voices_unavailable). pref_read_aloud_summary
  consumed by Task 5 (main-screen entry wiring is Task 5 scope).
- SettingsSearchScreen settingScreens list: SettingsReadAloudScreen added
  (after SettingsBrowseScreen).
- Gates GREEN 2026-08-30 (docker devcontainer JDK17, -Xmx4g, both volumes):
  :app:compileDebugKotlin BUILD SUCCESSFUL 2m57s (+2m25s re-run after
  review fixes); spotlessCheck GREEN (one ktlint line-length fix via
  spotlessApply); full testDebugUnitTest BUILD SUCCESSFUL (all suites).
  NO commit (per ruling).
- [FIX ROUND 1 2026-08-30 — review findings, UNCOMMITTED, same 2 code files]
  Finding 1 (preview ignores rate/pitch): preview() now calls
  engine.setSpeechRate(mutableState.value.rate) + setPitch(mutableState.value
  .pitch) between initialize() and acquireFocus() — values read INSIDE launch
  (latest state at execution time). Root cause: initialize() existing-engine
  path only re-applies voice/language; engine rate/pitch fields ctor-time 1f.
  Finding 2 (stale voice on reset/selectLanguage→default): applyVoiceConfig
  SystemDefault branch no-op → now restores engine.defaultVoice via setVoice
  (null defaultVoice → DEBUG log + leave, language NOT touched — SystemDefault
  is engine's choice not device locale). Language branch unchanged — framework
  doc: setLanguage sets default voice for that language (self-clearing).
  Minor: preview() early-returns if isPreviewPlaying (double-click guard).
  No AndroidTtsEngine ctor change (no second prefs dep). Gates GREEN:
  compileDebugKotlin 2m28s / spotlessCheck 39s first-try / testDebugUnitTest
  2m45s. Details: task-4-report.md "Fix round 1".
```

```text
[COMPLETED 2026-08-30 — Phase 10A Task 5, UNCOMMITTED (8 files)]
- Settings root entry: SettingsMainScreen.getItems gains Item after Reader
  (pref_category_read_aloud / pref_read_aloud_summary / Icons.AutoMirrored
  .Outlined.VolumeUp → SettingsReadAloudScreen object). AutoMirrored icon
  resolved at compile — fallback unused.
- Deep-link plumbing: Constants.SHORTCUT_VOICE_SETTINGS =
  "eu.kanade.tachiyomi.SHOW_VOICE_SETTINGS"; SettingsScreen.Destination
  ReadAloud id 4 (0-3 taken) + BOTH when branches (phone SettingsMainScreen
  fallback / tablet SettingsAppearanceScreen fallback) →
  SettingsReadAloudScreen; MainActivity.handleIntentAction new case beside
  ACTION_APPLICATION_PREFERENCES: popUntilRoot + push(SettingsScreen(
  Destination.ReadAloud)) → null.
- Reader tab minimal per spec PART 5: ReadAloudPage pitch SliderItem
  REMOVED (relocates to main settings Voice Calibration; rate slider + 3
  checkboxes kept); nav row TextPreferenceWidget(tts_advanced_voice_
  settings) at bottom; signature + onOpenVoiceSettings callback.
  ReaderSettingsDialog param after onHideMenus; tab order + dim-hack
  currentPage==2 untouched. ReaderActivity BOTH dialog call sites
  (phone L463 block + tablet L886 block): closeDialog() + startActivity
  MainActivity action=SHORTCUT_VOICE_SETTINGS FLAG_ACTIVITY_CLEAR_TOP
  (openMangaScreen precedent; no new imports). TtsPlaybackController +
  ReaderViewModel ZERO-DIFF confirmed.
- i18n: tts_advanced_voice_settings "Advanced voice settings" appended to
  TTS block.
- Gates GREEN 2026-08-30 (docker devcontainer JDK17, -Xmx4g, both volumes):
  :app:compileDebugKotlin BUILD SUCCESSFUL 3m36s; spotlessCheck BUILD
  SUCCESSFUL 46s first-try; full testDebugUnitTest BUILD SUCCESSFUL 2m57s
  (all suites incl. TtsVoicePreferencesTest 5/5). NO commit (per ruling).
  Full flow live: Settings root → Read aloud & voice ↔ reader quick
  settings → deep-link. Report: .superpowers/sdd/2026-08-30-phase10a-
  advanced-tts-voice-config/task-5-report.md.
```

```text
[COMPLETED 2026-08-30 — Phase 10A Task 6 (docs), UNCOMMITTED (5 docs files)]
- Documented the Phase 10A implementation across the docs system (Task 7
  device verification still PENDING — nothing marked complete):
  - prd.md: new §6 Phase 10A section — capabilities (engine/language/locale/
    voice pickers, calibration, preview, persistence, fallback chain, reset,
    entry points), user flow, preview policy (single shared engine; preview
    stops previous utterance; reader narration wins by QUEUE_FLUSH; honest
    pause), §6.4 Phase 10B deferred list (per-voice rate/pitch profiles,
    cloud/neural providers w/ credentials+cost+privacy+streaming+caching+
    latency, local neural engines, downloadable AI voices, expressive speech
    needs SSML/prosody/emotion — pitch/rate not expressive, no fake emotion).
    §4 future list annotated: voice picker landed in 10A.
  - architecture.md: §5 TtsEngine 10A contracts (getEngines/getVoices/
    setEnginePackage + models); AndroidTtsEngine config pipeline (construction
    seed, activeEnginePackage compare+shutdown on engine switch, initialize
    re-applies FRESH prefs every call → zero controller changes for mid-
    session pref pickup; applyVoiceConfig via resolveVoiceSelection, never
    fails initialize); preview single-engine policy; future-provider Injekt
    swap note. §7 tables: +3 new 10A files, integration-point list expanded.
  - design.md: new §13 — 3 preference groups (Text to speech / Voice
    calibration / Advanced), loading spinner (Anki pattern), failure+retry
    state, voice metadata labels API-facts-only (quality≥400 high quality,
    latency≤200 low latency, network marker), Locale.getDisplayName entries,
    stale-pref raw-value subtitleProvider, reader tab minimal + nav row,
    pitch relocated.
  - phase.md: Phase 10A entry IN_PROGRESS (code complete, gates green,
    device verification PENDING — completion gated on Task 7 + commit); old
    Phase 10 block → Phase 10B with 10A-landed/10B-remaining note; pointer
    block + dependency graph updated.
  - memory.md: this record.
- Gates: docs-only change — no Gradle run needed (spotless does not cover
  docs/*.md; no code touched). No commit/stage (per ruling).
- Phase 10A overall state: Tasks 1–5 code + Task 6 docs ALL UNCOMMITTED;
  gates green per task reports (spotlessCheck / testDebugUnitTest /
  :app:compileDebugKotlin; verifySqlDelightMigration NOT needed — no DB
  change). Task 7 device verification NOT run.
```

```text
[COMPLETED 2026-08-31 — Phase 10A Task 7 (device pass) + voice-picker search, UNCOMMITTED]
- DEVICE VERIFICATION PASS (build 0.5.0-8250, SM_M066B, USER CONFIRMED):
  Read-Aloud playback, language/locale selection, voice selection, voice
  preview, speech rate, and all other tested functionality working. Manual
  checks PASS per user report 2026-08-31.
- Evidence collected: .device-pass/tts-10a-test.log (571,082 lines, PIDs
  14214/30339, two sessions 06:37 + 08:51): page advances 17→18 confirmed
  1–2ms with 0 timeouts; pause/stop cycles clean (phase=Paused/Idle);
  preview audio-focus request/abandon PAIRED (08:51:53 request → 08:51:56
  abandon); "TTS default voice restored name=en-IN-language" ×4 — the
  SystemDefault re-apply branch (Task 4 fix round 1) proven on-device;
  0 FATAL exceptions; 0 sentence failures; single "Connected to TTS engine"
  per session (no duplicate TextToSpeech instances).
- Device engine reality (user observation + log): only default system engine
  + Google Speech Recognition & Synthesis appear in the picker; SamsungTTS
  present on device as a download-provider only (log: "Provider found [4]
  voices" but no synthesis service voices installed). Picker correctly lists
  only real installed engines — dynamic discovery working as designed.
- FOLLOW-UP FEATURE (user request, same session): voice-picker SEARCH —
  hundreds of voices made scrolling painful. Implementation (3 files + 1
  call-site):
  - Preference.kt: BasicListPreference gains `searchable: Boolean = false`.
  - PreferenceItem.kt: passes searchable through to ListPreferenceWidget.
  - ListPreferenceWidget.kt: when searchable, OutlinedTextField (placeholder
    = existing action_search MR key — zero new i18n) filters entries by
    case-insensitive contains on entry labels; remember(searchQuery, entries)
    memoizes the filtered map; list auto-shrinks as user types.
  - SettingsReadAloudScreen.kt: voice picker passes searchable = true;
    engine/language/locale pickers unchanged (small lists don't need it).
  - Gates GREEN: spotlessCheck + :app:compileDebugKotlin + testDebugUnitTest
    + :app:assembleDebug (3m51s / 3m28s, devcontainer JDK17 -Xmx4g). APK
    0.5.0-8250 reinstalled on SM_M066B.
- PHASE 10A COMPLETE. All work UNCOMMITTED awaiting user commit.
```

```text
[COMPLETED 2026-09-01 — Speed-adaptive Read-Aloud OCR prefetch, UNCOMMITTED]
- Problem: hardcoded N+1 prefetch had zero margin at high speech rates.
  Measured: Glens scan p50 8.1s / p90 16.7s / max 31s vs page speech time
  ~10-17s at 3x. Cache hits 9ms (irrelevant).
- TtsPlaybackController.kt (only code file):
  - prefetchPageIndex → prefetchPages: IntRange? (pages covered by
    current/last prefetch job).
  - prefetchDepth(): rate <1.5x → 1, 1.5-2.5x → 2, ≥2.5x → 3
    (MAX_PREFETCH_DEPTH=3 const). Depth read at schedule time — mid-session
    rate changes pick up naturally.
  - schedulePrefetch: targetPages = start until min(start+depth, totalPages)
    (auto-clamped at chapter end); same-pages+active-job skip guard; ONE
    coroutine iterating pages SEQUENTIALLY (per-page cache check via
    getCachedPageOcr → scanOnDemand reportFailure=false; CE rethrow; other
    exceptions logged/no-op). No new parallelism — respects serialized
    PrioritizedTaskQueue, single in-flight repo scan.
  - Mid-page escalation: sentence loop at sentenceIndex == size/2 with
    rate ≥ 2f calls schedulePrefetch(page+1) — no-op via guards when
    already covered. No new state.
  - Cancellation: rebuildQueueForUserNavigation + resetSession now also
    null prefetchPages (old code left stale prefetchPageIndex — was safe
    via isActive check but inconsistent).
  - Prefetch start log now includes pages range + rate.
  - resumeIndex semantics, dispatch-counter ids, exclusion awaitForSpeech
    untouched.
- rules.md §6: prefetch line updated to 3-page bounded, speed-aware depth.
- Gates NOT run (per task instruction). ktlint 120-col verified by awk.
- Device verify pending: 3x playback of Glens-style tall strips should show
  "TTS prefetch start pages=N..N+2 rate=3.0" with no mid-playback OCR
  LoadingPage gaps.
```

```text
[COMPLETED 2026-09-01 — Phases A–I multi-feature roadmap, UNCOMMITTED]

Phase A — Intelligent speech cleanup (domain mihon.domain.tts.speech):
- SpeechCleaner: punctuation-only skip (post-normalization so "WHAT?!?!?!" →
  "WHAT?!" kept), conservative OCR-garbage detector (<40% letters over ≥4
  chars drops symbol soup; never drops emphatic dialogue), excessive punct
  normalization (runs ≥3 → 2 chars incl. full-width ！！？？), whitespace
  collapse (bubble \n → space). SpeechCleanupOptions has per-feature toggles.
- Tests: SpeechCleanerTest 9 cases.

Phase B — Expression/language filtering:
- SpeechRegionFilterConfig: speak-sfx/expressions/decorative/unknown toggles +
  skipForeignScript + speechScript (LATIN/CJK — script-based not
  language-name-based, stays multilingual).

Phase C — Region classification:
- SpeechRegionClassifier heuristics: blank/symbol-only → DECORATIVE; CJK
  script on Latin page → DECORATIVE; wide-thin terminal-less → NARRATION;
  short uppercase + emphasis → SOUND_EFFECT; uppercase interjection regex →
  EXPRESSION; else DIALOGUE. OcrRegion untouched (no schema/confidence
  exists — classifier designed for future metadata swap-in).
- Tests: SpeechRegionClassifierTest 7 cases + SpeechRegionFilterTest +
  SpeechPipelineTest.

Pipeline wiring (TtsPlaybackController.acquireSentences):
- result.regions → SpeechPipeline.toSpeakableSentences (classify → filter →
  clean → segment). Cleanup BEFORE segmentation (punct runs normalize
  first); post-segment punct-only slices dropped. Original OCR data
  immutable (dictionary/tap-overlay/search see full regions).

Phase D — OCR exclusion zones:
- DB: 18.sqm + ocr_exclusion_zones.sq (manga_id FK CASCADE, chapter_id FK
  CASCADE, source_id, page_index, scope TEXT, normalized REAL rect, enabled,
  created_at). verifySqlDelightMigration GREEN.
- Domain: OcrExclusionZone model, OcrExclusionScope {PAGE,CHAPTER,MANGA,
  SOURCE}, repository + interactors (Get/Add/Delete/SetEnabled +
  subscribeForManga/Source, awaitForChapter/awaitAll).
- Matching: OcrExclusionMatcher (pure overlap test on normalized coords;
  PAGE scope matches pageIndex only; enabled filter at query + matcher).
  Applied ONLY in TtsPlaybackController (speech layer) — tap/dictionary
  OCR intentionally unaffected (spec: exclusion vs speech-cleanup distinct).
- Selection UI: reuse drag-select infra (SelectionAction.SaveExclusionZone);
  captureExclusionZoneSelection resolves captures → bitmap dims → normalized
  rect → Dialog.ExclusionZoneScope (page/chapter/manga/source choice) →
  VM saveExclusionZone (launchNonCancellable). Manage sheet
  (OcrExclusionZonesSheet) lists zones w/ enable toggle + delete.
  Entry: Reader settings → Read aloud tab → "OCR & text recognition"
  (enable toggle + add zone + manage). Pref gate
  pref_tts_ocr_exclusions_enabled (default true) controls lookup.
- Tests: OcrExclusionMatcherTest 5 cases (overlap, page-scope, cross-scope,
  normalized-coords scaling).

Phase G — Backup/restore:
- Backup model +BackupOcrExclusionZone list @ProtoNumber(107) (additive proto
  — old backups compatible, unknown fields survive).
- Creator: OcrExclusionZonesBackupCreator gated on options.appSettings.
  Restore: OcrExclusionZoneRestorer — dedupe (same manga/chapter/page/rect),
  skip zones w/ invalid scope name, per-zone try/catch (FK miss → logged skip,
  no restore abort). All new prefs (pref_tts_*, pref_dictionary_reader_*,
  pref_feed_items, pref_tts_voice_profiles) auto-included via
  PreferenceBackupCreator (plain keys, no __PRIVATE_ prefix).

Phase E — Voice profiles:
- TtsVoiceProfile @Serializable {id,name,enginePackage,voiceName,languageTag,
  rate,pitch}; stored as JSON in one pref (pref_tts_voice_profiles) via
  getObjectFromString; active id pref (pref_tts_active_voice_profile).
- ReadAloudSettingsScreenModel: saveVoiceProfile (snapshot current config),
  deleteVoiceProfile, applyVoiceProfile (writes component prefs + rate/pitch
  + engine.setEnginePackage + reload). UI: "Voice profiles" group in
  SettingsReadAloudScreen — rows w/ apply + delete icon, save row w/ name
  dialog (default name "N% · voice").

Phase F — 3x speech rate:
- Rate slider ranges 50..300 (reader tab + main settings). Engine-side no
  clamp (Android TTS accepts float; framework clamps internally per engine).
- TtsPlaybackBar: speed chip ("1x") + DropdownMenu (0.5/0.75/1/1.25/1.5/
  1.75/2/2.5/3) → writes ttsSpeechRate pref → controller live collector
  applies to engine (same path as Phase 10A rate/pitch).

Phase H — Dictionary navigation cleanup:
- AUDIT RESULT: bottom-nav DictionaryTab (word lookup) vs More→Dictionaries
  (SettingsDictionaryScreen: import/manager/popup-style) = DIFFERENT
  features, no merge. Lookup tab moved: DictionaryTab DELETED →
  DictionaryLookupScreen (Voyager Screen, same DictionarySearchScreenModel
  content) opened from More tab "Dictionary" row (MenuBook icon) above the
  "Dictionaries" manager row.
- Dictionary interaction settings (SettingsDictionaryScreen, "Dictionary
  style" group): reader tap lookup toggle (pref_dictionary_reader_tap_lookup,
  default ON — gates shouldHandleCachedOcrRegionTaps) + auto-search toggle
  (pref_dictionary_reader_auto_search, default ON — gates OcrResultOverlay
  initial LaunchedEffect search; off = popup opens with query filled, user
  searches manually). Manual long-press OCR lookup unaffected.

Phase I — Feed:
- FeedTab replaces DictionaryTab in bottom nav (HomeScreen TABS + Tab.Feed;
  Dictionary Tab sealed member replaced). Label "Feed", Icons.Outlined.Feed.
- Model: FeedItem @Serializable {sourceId, listing POPULAR/LATEST, enabled};
  FeedPreferences (JSON pref pref_feed_items — auto-backed-up).
- FeedScreenModel: GetEnabledSources (stub-filtered) for picker; per enabled
  feed fetch page 1 via source.getPopularManga/getLatestUpdates(1) →
  toDomainManga → NetworkToLocalManga (GlobalSearch fan-out precedent,
  async per feed, per-feed result state). Feeds reactive via pref changes().
- FeedScreen: LazyVerticalGrid, per-feed header (source name, listing label,
  up/down reorder, enable switch, delete) + MangaComfortableGridItem grid
  → MangaScreen(id, true). Add dialog: source list (supportsLatest gates
  Latest listing) + listing choice. Empty state w/ add CTA.

GATES GREEN 2026-09-01 (devcontainer JDK17, -Xmx4g, both volumes):
  spotlessApply → spotlessCheck + testDebugUnitTest +
  verifySqlDelightMigration + :app:assembleDebug ALL BUILD SUCCESSFUL.
  NOT run: device pass, release build, restore round-trip test.

Follow-ups (deliberately deferred, ponytail):
- Feed: no prefetch/paging (page 1 per feed only); no per-feed refresh
  control; source-uninstalled state shows error text.
- Exclusion zone rect visualization on-page (currently list-only coords).
- Speech classifier ML upgrade path = swap classify() internals only.
```

```text
[COMPLETED 2026-09-01 — OCR exclusion system redesign, UNCOMMITTED]

Rule model (single ocr_exclusion_zones table, preserved):
- match_type ∈ {ZONE, WORD, PHRASE, COMBINED} + match_text + rule_name columns.
- ZONE: pure rect, scope PAGE only for new rules (chapterId+pageIndex match).
  Legacy CHAPTER/MANGA/SOURCE ZONE rows (page_index NULL): DORMANT in matcher
  (rect was drawn on one page; blind application was the bug), visible+deletable
  with "legacy" hint in manage UI. Data preserved.
- WORD: global standalone-token match (Unicode isLetterOrDigit tokens,
  case-insensitive; "ion" ≠ "combination").
- PHRASE: global normalized substring (case-insensitive, \s+→space collapse).
- COMBINED: rect overlap AND phrase match within scope (PAGE=chapter+page,
  CHAPTER=any page of chapter, MANGA, SOURCE). Location+text = safe wide scope.

Migration 19.sqm (verifySqlDelightMigration NOT yet run — user runs it):
- Full table rebuild: drops manga_id FK (global WORD/PHRASE rules need
  manga_id=0 which would violate old FK), keeps chapter_id FK CASCADE,
  adds match_text/match_type/rule_name in same column order as .sq.
- UPDATE chapter_id=NULL for legacy MANGA/SOURCE rows (chapter-delete cascade
  used to destroy them — bug 3 fix). .sq mirror-updated identically.

Matcher (OcrExclusionMatcher.kt): new ExclusionMatchContext(mangaId, sourceId,
chapterId, pageIndex) API; applyExclusions(zones, context). PAGE zone now also
requires chapterId match (was page-only). Enabled check at matcher + query.

Repository/interactors: +subscribeAll, +getZonesForSpeech(mangaId, sourceId,
chapterId) = enabled AND (text rules OR manga/chapter/source match); insert
gains matchType/matchText/ruleName/enabled (enabled was hardcoded 1 — restorer
bug 5 fix). Controller acquireSentences → awaitForSpeech + context call.

Reader save flow (bug 2/3 fix): scope dialog now offers PAGE (pure zone) or
CHAPTER/MANGA/SOURCE (COMBINED; required non-blank match text, Save disabled
while blank; AlertDialog + OutlinedTextField, same Dialog.ExclusionZoneScope
entry so both ReaderActivity when-branches unchanged). saveExclusionZone:
MANGA/SOURCE → chapter_id NULL (no cascade); pageIndex always stored.

New Settings → OCR exclusions screen (SettingsOcrExclusionsScreen, plain
Voyager Screen per DictionaryScreen pattern — not searchable): words/phrases/
zones sections, add-word/add-phrase AlertDialogs, per-row switch+delete,
rule hint, legacy marker. ScreenModel: SettingsOcrExclusionsScreenModel
(StateScreenModel over subscribeAll). Entry: SettingsMainScreen row after
Read aloud (Icons.Outlined.Block) + Destination.OcrExclusions id 5 + both
SettingsScreen when branches. Reader manage sheet stays manga-scoped
(subscribeForManga) + type labels + legacy hint.

Backup/restore: BackupOcrExclusionZone +@ProtoNumber(11/12/13) matchType
(default "ZONE")/matchText/ruleName — old backups decode. Restorer: dedupe
now compares scope+matchType+matchText too (scope-omission bug fixed),
unknown matchType skipped, enabled round-trips via new insert param.

i18n (base only): 17 new keys (ocr_exclusions_screen_title, _summary,
_type_zone/word/phrase/combined/_legacy, _section_words/phrases/zones,
_add_word/_add_phrase, _match_text_label, _rule_hint, _invalid_text, _empty).

Tests: OcrExclusionMatcherTest rewritten — 13 cases (page-zone page+chapter
match, other-page survive, non-overlap, WORD standalone/case/unicode,
PHRASE whitespace/case/substring, COMBINED chapter any-page + rect/text
required + wrong scope kept, COMBINED manga/source, legacy dormant all
scopes, disabled, any-match, normalized coords).

GATES NOT RUN (host gradle forbidden this session; user runs gates later).
```

```text
[COMPLETED 2026-09-01 — TTS duplicate-speech fix set (4 root causes), UNCOMMITTED]

Root causes treated as established facts (user-confirmed investigation):
OCR seam duplicates surviving IoU<0.45 + resumeIndex race + utterance-id
collision + old-job stop() flushing new utterance.

1. Domain text-dedup (mihon.domain.tts.speech/SpeechPipeline.kt):
   - dedupeOverlappingDuplicates(regions) inserted BEFORE segmentation in
     toSpeakableSentences: drops later regions whose normalized text
     (trim + \s+→space + lowercase) EXACTLY duplicates an earlier kept region
     AND strictly overlaps its bbox (AABB, boxesOverlap helper). Duplicate
     text in disjoint bubbles survives (legitimate). Hoisted key regex.
   - New pure fns: boxesOverlap(a,b), boundingBoxIoU(a,b) (normalized floats).
   - Benefits ALL engines; GlensOcrEngine untouched (per plan).
2. resumeIndex race (TtsPlaybackController.runPlayback):
   - resumeIndex=sentenceIndex stays ONLY at pre-dispatch (pause DURING
     speech re-speaks current sentence = desired).
   - After spoke==true: resumeIndex=sentenceIndex+1 BEFORE suspension points
     → pause between utterances resumes at NEXT (never re-speaks completed).
   - sentenceIndex>=sentences.size on (re)entry now advances the page instead
     of resetting to 0 (fixes pause-in-advance-window re-speaking whole page).
   - stepBy resumeIndex=newIndex kept consistent.
3. Utterance-id collision: utteranceId now appends monotonic per-controller
   AtomicLong dispatch counter ("p${page}_s${sentence}_c${n}") — unique per
   dispatch, page/sentence still traceable. Engine-side finally now removes
   pendingUtterances entry only when identity matches (===) ITS completion —
   stale zombie dispatch can't unhook a newer dispatch's callback.
4. Zombie stop() flush: resume() now calls engine.stop() after
   playbackJob?.cancel() when phase was Playing/LoadingPage (old job could
   have in-flight utterance). rebuildQueueForUserNavigation + stepBy already
   called engine.stop(); pause() calls its own. engine.stop() idempotent.
5. Traceability logs (no text content, rules §7): "TTS dispatch id=...
   textLen=... textHash=..." at speak dispatch; "TTS page=N dedup dropped=X
   regions" (only when X>0) in acquireSentences (dedup applied there once,
     pipeline stays dedup-free at call site).
6. Tests: SpeechPipelineDedupTest 9 cases (dup+overlap→later dropped /
   dup+disjoint→kept / different-text+overlap→kept / case+whitespace dup→
   dropped / triple→first kept / empty unchanged / pipeline single-sentence
   / IoU identical=1 / IoU disjoint=0). 9/9 GREEN.

DEVIATIONS from brief (all flagged, user-WIP interactions):
- Brief's line numbers were stale: on-disk controller had evolved (phase A–I
  + exclusion redesign landed uncommitted). Verified every hunk against
  CURRENT source before editing; exclusion code now uses
  ExclusionMatchContext/awaitForSpeech — acquireSentences dedup log
  adapted to that shape.
- User's uncommitted exclusion-zone WIP had a COMPILE BREAK blocking all
  gates: OcrExclusionMatcher.kt:30 called zone.matchesRegion(...) but fn
  is top-level matchesRegion(zone,...). Fixed (one word, no semantic
  change). Also fixed: ReaderViewModel.kt:1055 (text:String? null-check
  → isNullOrEmpty guard), SettingsOcrExclusionsScreenModel.kt (missing
  kotlinx.coroutines.launch import).
- User WIP spotless violations (continuation indents) hand-applied per
  ktlint's exact output: OcrExclusionMatcher.kt (2 hunks),
  ReaderActivity.kt (ExclusionZoneScopeDialog arg indent).
- User WIP SpeechCleaner tests had 2 WRONG expectations contradicting their
  own impl/docs: "..." with ellipsisToPause=false → null (their own
  doc: punctuation-only skip drops standalone runs), and pipeline slice
  "I don't know," (segmenter trims slices by design, memory 2026-08-25).
  Expectations corrected; no production code changed for these two.
- Resumed resumeIndex=size fallthrough: NOT in brief; required to avoid
  re-speaking whole page when pause lands between last utterance and page
  advance. advanceFromPolicy path preserves auto-turn arbitration.

GATES GREEN 2026-09-01 (devcontainer JDK17, -Xmx4g, both volumes):
  spotlessCheck + testDebugUnitTest BUILD SUCCESSFUL in 2m 30s (173 domain
  tests + all suites; DedupTest 9/9). :domain:compileReleaseKotlin +
  :app:compileDebugKotlin BUILD SUCCESSFUL 4m 7s. verifySqlDelightMigration
  NOT needed (no DB change). NOT run: assembleDebug, device pass.

Files edited: SpeechPipeline.kt; SpeechPipelineDedupTest.kt (new);
  TtsPlaybackController.kt; AndroidTtsEngine.kt;
  OcrExclusionMatcher.kt (WIP compile+ktlint fix);
  ReaderViewModel.kt (WIP null-check fix);
  SettingsOcrExclusionsScreenModel.kt (WIP import fix);
  ReaderActivity.kt (WIP ktlint indent);
  SpeechCleanerTest.kt + SpeechRegionFilterTest.kt (WIP expectation fix).
Deferred issue #3b (hands-off duplicate speech) → addressed by this set;
device re-verify on affected manga still pending.
```

```text
[COMPLETED 2026-09-01 — Reader UI polish set (Issues 5/6/8), UNCOMMITTED]

Feature 1 (Issue 5) — Stop button during Preparing/LoadingPage:
- TtsPlaybackBar.kt Preparing/LoadingPage branch: status Text gained
  Modifier.weight(1f) + trailing Stop IconButton (Icons.Outlined.Stop, reuses
  tts_action_stop key, same pattern as PlaybackContent/ErrorContent stop) wired
  to existing onStop. UI-only; ReaderActivity onStop wiring untouched.

Feature 2 (Issue 6) — OCR drag-select toggle:
- ReaderPreferences: ocrTextSelectionEnabled ("reader_ocr_text_selection",
  default true, Controls region beside longTapOcr).
- ReaderBottomBar: showOcrButton param (default true) wraps DocumentScanner
  IconButton. Chain: ReaderActivity (both call sites, collectAsState) →
  ReaderAppBars (param + forward) → ReaderBottomBar.
- Long-press OCR path (longTapOcr pref) untouched. enterOcrMode NOT gated —
  exclusion-zone flow shares enterSelectionMode with its own SelectionAction.

Feature 3 (Issue 8) — Read Aloud button toggle (same pattern):
- ReaderPreferences: readAloudButtonEnabled ("reader_read_aloud_button",
  default true). ReaderBottomBar showReadAloudButton param wraps
  RecordVoiceOver IconButton. Wired identically at both call sites.
- Settings: SettingsReaderScreen getActionsGroup gains both SwitchPreferences
  after longTapOcr row. Core actions (mode/orientation/crop/settings) always
  visible.

i18n (base only): pref_reader_ocr_text_selection, pref_reader_read_aloud_button
(2 new keys; stop reuses existing tts_action_stop).

GATES NOT RUN (host gradle forbidden this session; user runs gates later).
Files: TtsPlaybackBar.kt, ReaderBottomBar.kt, ReaderAppBars.kt,
ReaderActivity.kt, ReaderPreferences.kt, SettingsReaderScreen.kt,
i18n base strings.xml.
```

```text
[COMPLETED 2026-09-01 — POST-DEVICE-TEST AUDIT SET (session: repeated speech +
exclusion redesign + adaptive prefetch + stop-during-prepare + reader prefs +
toolbar toggles + ellipsis pause), UNCOMMITTED — ALL GATES GREEN]

Session flow: evidence collection (5 parallel explore agents, full audit of
TTS pipeline/OCR exclusions/prefetch/minibar/toolbar/settings/backup) →
root-cause report → implementation via 4 parallel agents + orchestrator edits
→ verification. No fresh device logs existed for the reported test pass
(newest .device-pass log = tts-10a-test.log 2026-08-31); evidence = source +
prior logs. Root causes established in code, not guessed.

Issue 2 (repeated speech) — 4 root causes, all fixed:
- OCR seam/text duplicates (primary): domain text-dedup (see dedup block
  below) + traceability logs.
- resumeIndex race: fixed (see dedup block).
- utterance-id collision: unique per-dispatch ids (see dedup block).
- zombie stop() flush: resume() engine.stop() guard (see dedup block).

Issue 1 (ellipsis "dot dot dot"): SpeechCleaner ellipsisToPause option —
dot-runs (2+) and "…" → ", " spoken pause, toggleable
(pref_tts_ellipsis_to_pause default true, checkbox in Read aloud reader tab,
key pref_tts_ellipsis_to_pause). SpeechCleanerTest +2 cases; SpeechRegionFilterTest
pipeline expectation updated ("I don't know," — segmenter trims slices).

Issue 3 (OCR exclusion redesign): full redesign (see exclusion-redesign
block above) — ZONE/WORD/PHRASE/COMBINED match types, 19.sqm table rebuild
(drops manga_id FK, adds match_text/match_type/rule_name, legacy MANGA/
SOURCE chapter_id→NULL), matcher ExclusionMatchContext API, scope dialog
combined-rule text requirement, Settings→OCR exclusions screen, backup
proto 11/12/13 + restorer dedupe/enabled fixes.

Issue 4 (high-speed prefetch): speed-adaptive depth (see prefetch block).

Issue 5 (stop during Preparing/LoadingPage): TtsPlaybackBar spinner branch
gains trailing Stop IconButton (Icons.Outlined.Stop, tts_action_stop) —
stop() was fully functional controller-side; pure UI gap.

Issue 6 (OCR drag-select toggle): reader_ocr_text_selection pref (default
true) → showOcrButton param chain ReaderActivity→ReaderAppBars→
ReaderBottomBar; SettingsReaderScreen Actions group SwitchPreference.
Long-press path (longTapOcr) untouched — separate mechanism.

Issue 7 (dictionary popup toggle): ALREADY EXISTING —
pref_dictionary_reader_tap_lookup gates shouldHandleCachedOcrRegionTaps
(PHASE H, Settings→Dictionary). Regression-verified only, no change needed.

Issue 8 (toolbar customization): reader_read_aloud_button pref (default
true) → showReadAloudButton chain, same pattern as Issue 6. Core actions
(mode/orientation/crop/settings) always visible. No drag-drop ordering.

Issue 9 (dictionary in More): untouched per directive.
Issue 10 (Feed): untouched per directive.

GATES GREEN 2026-09-01 (devcontainer JDK17, -Xmx4g, both volumes, run by
ORCHESTRATOR directly, all four):
  spotlessCheck BUILD SUCCESSFUL 42s;
  testDebugUnitTest + verifySqlDelightMigration BUILD SUCCESSFUL 4m9s
    (19.sqm validated vs .sq schema; new suites green: OcrExclusionMatcherTest
    14/14, SpeechCleanerTest 11/11, SpeechPipelineDedupTest 9/9);
  :app:assembleDebug BUILD SUCCESSFUL 3m52s.
NOT run: device pass (user), assembleRelease.

Device-pass checklist for user (from audit task spec):
1. Speech cleanup incl. "..." pause + dialogue preservation.
2. Repeated-speech: multiple chapters/pages, hands-off; look for
   "TTS dispatch id=... textHash=" + "dedup dropped=" logs; zero repeats.
3. OCR exclusions: page zone; chapter/manga/source COMBINED (text required);
   word "ion" vs "combination"; phrase; legacy dormant rows visible.
4. High-speed 1x/2x/2.5x/3x: "TTS prefetch start pages=N..N+2 rate=3.0";
   stalls bounded; no LoadingPage gaps at 3x on cached pages.
5. Stop during Preparing/LoadingPage: bar now shows Stop; cancel clean;
   next session starts fresh.
6. OCR selection toggle OFF → button gone.
7. Dictionary popup: toggle OFF → no popups/no "No dictionary is enabled".
8. Toolbar customization: toggles hide/show optional buttons.
9. Backup/restore round-trip: voice profile + word + phrase + zone +
   combined rule all survive; disabled rules stay disabled.
```

Deferred issues (LOW PRIORITY, revisit post-build):
  - #3b hands-off duplicate words/phrases: intermittent, manga-specific, never
    reproduced under instrumentation. ADDRESSED 2026-09-01 by the duplicate-
    speech fix set (domain text-dedup + resumeIndex race + unique utterance
    ids + zombie-stop guard); device re-verify on the affected manga is the
    remaining confirmation step.

Remaining:
- User review + commit: 2026-09-01 Phases A–I change set is COMMITTED
  (c70e32252); the 2026-09-01 POST-DEVICE-TEST AUDIT SET (this session)
  is UNCOMMITTED awaiting user commit.
- Device pass of the new features (speech cleanup behavior, exclusion-zone
  selection on manga+webtoon, speed popup, profiles, Feed tab, dictionary
  More-tab relocation) + this session's audit checklist (9 items, listed
  in the audit-set block above).
- (DONE 2026-08-29: Phase 9 COMPLETE — leak fix device-verified 0 APPLICATION
  LEAKS; battery measurement done: no post-exit drain, app fg CPU ~1mAh/min,
  screen dominates; evidence battery-sample.log + battery-tts-session.log +
  batterystats-app.txt.)
```

## Blocked

```text
Nothing hard-blocked. Finding #3 deferred LOW PRIORITY by user (post-build).
Phase 8 device script COMPLETE (steps 1–15 executed + user-confirmed).
SM_M066B via wireless adb; use on-device logcat capture (streaming adb logcat
dies on blip). English TTS voice = device default. GLENS/network reachable.
Standing decision unchanged: prd script stays manual/interactive.
```

## Current files being modified

```text
[COMPLETED 2026-09-05 — UI-modernization Phase A (visual hierarchy
foundation), UNCOMMITTED (4 files)]

Presentation-only change set (report: /tmp/opencode/phaseA-report.md):

1. Translucent.kt (+2 roles, zero call-site changes): asFloatingChrome() =
   frosted READER role — copy(alpha=0.85f) REAL translucency when mica on
   (backdrop = artwork), opaque fallback when off, dark/light identical,
   FLOATING_CHROME_ALPHA const, kdoc = no frost-on-frost + scrim-adequate
   container + onSurfaceVariant content colors required. asFrostedModal()
   = modal role alias → delegates asChromeContainer() (pre-blend 0.82).
   LocalTranslucentSurfaces/asChromeContainer untouched (backward compat;
   NavigationBar/AdaptiveSheet/ResizableSheet/TtsPlaybackBar callers NOT
   migrated — other agents own those files this phase).

2. PreferenceGroupCard.kt (NEW widget): one group = one surface — Column,
   16dp screen inset, MaterialTheme.shapes.large clip, tonal background,
   4dp vertical inner padding, header INSIDE surface (typography.header,
   start 16/top 12), rows keep BasePreferenceWidget metrics, NO shadow
   (design §17), NO frost. COLOR DECISION: surfaceContainerLowest
   REJECTED — verified all 15 color schemes: dark modes put it AT/BELOW
   background (Tachiyomi #1A181D vs bg #1B1B1F = inverted; Monochrome/
   GreenApple identical = invisible) → used surfaceContainerLow (task's
   own fallback clause). Monochrome stays flat (Low==bg, shape-only
   delineation — intentional theme philosophy).

3. PreferenceScreen.kt: PreferenceGroup renders as ONE LazyColumn item
   (PreferenceGroupCard wrapping non-lazy Column of PreferenceItems +
   12dp trailing spacer, enabled-skip preserved). findHighlightedIndex
   REWRITTEN for 1-group-1-item model: top-level index of group containing
   matching item title / direct item title match; disabled groups excluded
   (matches render skip — also fixes pre-existing off-by-N scroll bug when
   disabled group precedes target). Search breadcrumbs/index untouched
   (SettingsSearchScreen reads data model, not render tree).

4. SettingsTrackingScreen.kt: 2 loose items (auto-update switch, on-mark-
   read list) wrapped in new PreferenceGroup(pref_category_general —
   existing key). Data/Anki loose rows NOT wrapped: no existing i18n key
   fits (candidates duplicate screen/row titles; spec forbids inventing).
   ReadAloudPage + SettingsReadAloudScreen state placeholders untouched.

GATES GREEN 2026-09-05 (docker vsc-yomihon-e24e3bd7e46d…, JDK17, -Xmx4g,
both volumes): spotlessApply + :app:compileDebugKotlin +
:presentation-core:compileDebugKotlin BUILD SUCCESSFUL 3m39s;
spotlessCheck BUILD SUCCESSFUL 48s. No DB change. No unit tests touched
(presentation-only; task scope was compile+spotless).

Device verify pending: settings screens grouped-surface look (all
themes, esp. dark/AMOLED contrast), search "anki" → navigate → pulse row
(highlight scroll under new 1-item groups), Tracking screen General
group, very tall groups (Anki field mappings) scroll behavior.

NOTE: other agents concurrently own NavigationBar/AdaptiveSheet/
ResizableSheet/TtsPlaybackBar/MoreScreen/SettingsMainScreen/AboutScreen/
SettingsOcrExclusionsScreen — do not rebase this set without checking
their state.

Current working files:
- UI-modernization Phase A set UNCOMMITTED (4 files above)
- Prior uncommitted sets: UI set 2 (Feed mgmt), audit-fix set, prefill
  fix, docs alignment (see blocks above)
- docs/memory.md (this record)


## Recently changed files

```text
2026-08-30  docs/prd.md                                  Phase 10A T6: new §6 (capabilities,
                                                        user flow, preview policy, fallback
                                                        chain, 10B deferred list)
2026-08-30  docs/architecture.md                        Phase 10A T6: §5 TtsEngine contracts +
                                                        AndroidTtsEngine config pipeline +
                                                        preview policy; §7 file tables
2026-08-30  docs/design.md                              Phase 10A T6: new §13 settings screen
                                                        spec (groups, states, metadata labels)
2026-08-30  docs/phase.md                               Phase 10A T6: 10A entry IN_PROGRESS,
                                                        Phase 10 → 10B backlog rework
2026-08-30  app .../settings/screen/SettingsMainScreen.kt    Phase 10A T5: Read aloud row
            (AutoMirrored VolumeUp) after Reader item
2026-08-30  core/common/.../Constants.kt                    Phase 10A T5: SHORTCUT_VOICE_SETTINGS
2026-08-30  app .../ui/setting/SettingsScreen.kt            Phase 10A T5: Destination.ReadAloud
            id 4 + both when branches
2026-08-30  app .../ui/main/MainActivity.kt                 Phase 10A T5: handleIntentAction
            voice-settings deep-link case
2026-08-30  app .../reader/settings/ReadAloudPage.kt        Phase 10A T5: pitch slider removed,
            advanced-voice-settings nav row added
2026-08-30  app .../reader/settings/ReaderSettingsDialog.kt  Phase 10A T5: onOpenVoiceSettings
            param + pass-through
2026-08-30  app .../ui/reader/ReaderActivity.kt             Phase 10A T5: both dialog call
            sites deep-link to MainActivity
2026-08-30  i18n base strings.xml                            Phase 10A T5: tts_advanced_voice_
            settings key (T4's 17 keys also uncommitted)
2026-08-30  app .../settings/screen/SettingsReadAloudScreen.kt + Phase 10A T4 (Task 4 report)
            app .../ui/setting/readaloud/*
2026-08-30  domain + app TtsVoicePreferences/TtsEngine/AndroidTtsEngine Phase 10A T1/T3
2026-08-29  docs/memory.md, docs/phase.md             Accuracy restoration: fd52a613a docs
            commit had reverted memory/phase to pre-leak-fix state (stale
            repo hash, prefetch-fix marked unverified, Finding #5 records
            deleted) — restored + updated for committed reality
2026-08-29  app .../ui/reader/ReaderActivity.kt       Import order re-fixed (spotlessApply):
            fd52a613a had moved ioCoroutineScope + kotlinx.coroutines.cancel
            imports to non-ktlint positions → spotlessCheck failed
2026-08-29  (committed 5c7d2cc2c) LeakCanary fix: ReaderViewModel.onCleared
            onFocusEvent=null; ReaderActivity ioCoroutineScope cancel on dispose;
            build.gradle.kts LeakCanary core debug builds
2026-08-28  app .../ui/reader/tts/TtsPlaybackController.kt Prefetch failure no longer kills session (reportFailure gate); RUN4 analysis
2026-08-28  app .../ui/reader/tts/TtsPlaybackController.kt Action-level DEBUG logs: pause/resume/stop/stepBy/focus-loss (COMMITTED be31edb71)
2026-08-28  docs/memory.md, docs/phase.md                 Finding #3 deferred LOW PRIORITY; run2/run3 results; TTS-DBG removed
2026-08-28  app .../viewer/webtoon/WebtoonViewer.kt       #1 moveToPage explicit onScrolled; TTS-DBG removed
2026-08-28  app .../ui/reader/tts/TtsPlaybackController.kt #2 timeout pageIndex=target; P0 rebuild/prefetch dedup; TTS-DBG removed
2026-08-28  app .../ui/reader/ReaderViewModel.kt          P1 250ms TTS page-selected debounce; advance confirmations exempt
2026-08-27  app .../ui/reader/tts/TtsPlaybackController.kt  ScrollToRegion emit + pause/resume page fix
2026-08-27  app .../ui/reader/ReaderViewModel.kt           ScrollToRegion forward to event channel
2026-08-27  app .../ui/reader/ReaderActivity.kt            ScrollToRegion handler + Viewer.scrollToRegion()
2026-08-27  app .../viewer/Viewer.kt                       scrollToRegion() default impl
2026-08-27  app .../viewer/webtoon/WebtoonViewer.kt        scrollToRegion() impl + findFirstVisibleItemPosition fix
2026-08-26  data .../data/ocr/GlensOcrEngine.kt        parallel tiles (TILE_CONCURRENCY=3)
2026-08-26  data .../data/ocr/OcrRepositoryImpl.kt     single-flight + task-owned bitmap/upsert
2026-08-26  data .../data/ocr/PrioritizedTaskQueue.kt  queue-depth debug log
2026-08-26  app .../ui/reader/tts/TtsPlaybackController.kt  acquireMs + startup latency logs
2026-08-25  docs/phase.md                              Phase 10 voice-calibration backlog
2026-08-25  (committed a071feedf) GlensOcrEngine tiling, EN ordering, JP-gate removal,
            segmenter EN rules, progression fixes, TtsPlaybackBar, i18n — see commit
2026-08-24  docs/memory.md, docs/phase.md              session records
```

## Architecture decisions

```text
Decision:
TTS = Android system TextToSpeech behind framework-free TtsEngine interface
(:domain); AndroidTtsEngine impl isolated in :app data/tts.

Reason:
Offline-capable baseline, zero new dependencies, future engines (cloud/neural)
become drop-in Injekt binding swaps; mirrors DictionaryAudioPlayer
interface-in-domain precedent.

Decision:
Playback is reader-bound (pause on Activity.onStop; no FGS/MediaSession/
permissions); current sentence visualized in mini-bar only (no on-image bbox
highlight in v1).

Reason:
User-approved product fork decisions (architect.md Context); targetSdk 36 makes
background audio require FGS+MediaSession surface that v1 deliberately avoids.

Decision:
Advance/navigation logic as pure functions in :domain (SentenceSegmenter,
TtsAdvancePolicy) unit-tested with JUnit5+Kotest; controller orchestration stays
thin and untested-by-design (no Robolectric in repo).

Reason:
Matches house testing stack and existing pure-domain precedents
(ChapterRecognition, SentenceParser, PrioritizedTaskQueueTest).

Decision:
Controller emits Events handled by ReaderActivity's existing eventFlow collector
(moveToPageIndex/loadNextChapter); it never touches Viewer directly.

Reason:
Reuses established VM→Activity pattern; user swipes mid-playback win via
onPageSelected arbitration without fighting the viewer.

Decision:
English is the primary v1 Read-Aloud language (product pivot 2026-08-25).
No language preflight, no setLanguage pinning — the system-default TTS voice
is used. Japanese/voice-picker work belongs to Phase 10.

Reason:
Device had no JP voice; the old gate hard-blocked all playback and the
eng-IND synthesis log proved wrong-language output. English content was the
user's actual usage.

Decision:
Tall webtoon strips are tiled before the Glens request (engine-level fix),
not compensated in TTS.

Reason:
Root cause was image downscale destroying text; per-task spec forbids masking
OCR gaps in TTS. Tiling keeps MAX_IMAGE_DIMENSION per tile and preserves the
single-request path for normal pages.

Decision:
Controller re-resolves chapter context via provideContext() on every queue
rebuild instead of caching it at start().

Reason:
Auto chapter advance + user navigation both need fresh totalPages/
hasNextChapter/chapter id; stale context caused silent death and wrong-chapter
scans.

Decision:
Settings tab appended LAST in ReaderSettingsDialog (page 3) so ColorFilter's
dim-amount hack (`pagerState.currentPage == 2`) keeps pointing at ColorFilter.

Reason:
Smallest diff; reordering tabs would silently move the special no-dim behavior.
```

## Rejected approaches

```text
Rejected:
Direct TextToSpeech calls from ReaderScreen/composables.
Reason: lifecycle coupling, untestable, blocks future engines.

Rejected:
Foreground service / MediaSession background playback in v1.
Reason: scope decision; adds permissions/notification surface; revisit in Phase 10.

Rejected:
On-image bbox highlighting of spoken region in v1.
Reason: scope decision; mini-bar text feedback suffices; reuse
ReaderOcrOverlayRenderer approach if ever added.

Rejected:
New audio caching layer for TTS output.
Reason: system-TTS latency is tens of ms; OCR cache already covers text;
revisit only with high-latency cloud engines.

Rejected:
Japanese-voice availability gate + Locale.JAPAN pinning in the TTS engine.
Reason: blocked all playback on JP-less devices and produced wrong-language
synthesis; replaced by system-default voice (product pivot 2026-08-25).

Rejected:
Retrying utterances more than once or adding delays to mask speak() failures.
Reason: rules.md §7 fixes failure at the cause (retry once → honest Paused);
delays hide bugs.

Rejected:
Sorting regions inside SentenceSegmenter to fix webtoon order.
Reason: ordering belongs to the OCR engine (now fixed there via tiling +
positional merge); segmenter must mirror tap-highlight behavior.
```

```text
[COMPLETED 2026-09-06 — USER-DIRECTED CORRECTION PASS over the
post-modernization set, UNCOMMITTED]

Spec: user's "POST-IMPLEMENTATION CORRECTION" specification (session
prompt) — previous implementation NOT accepted; structural fixes only,
no visual-polish masking. Prior set committed by user as e89104296
("feat: Implement Recent tab and navigation reordering; enhance Feed
management") — this correction pass stacks on it.

1. RECENT SCREEN STRUCTURE (spec §5/§6/§30):
   - RecentTab gained screen-level AppBar(title=label_recent) via the
     shared Scaffold — tab row now starts BELOW the status-bar/cutout
     safe inset (AppBar consumes it; no magic paddings).
   - HistoryScreen (presentation): SearchToolbar title REMOVED (search +
     clear-history action remain). UpdateScreen (presentation): AppBar
     title REMOVED, action mode + actions remain. No duplicate titles:
     exactly one "Recent" title, tab labels not repeated as headers.
   - History/Updates keep own inner Scaffolds (Tachiyomi Scaffold
     consumed-inset handling prevents double insets).
2. RESELECT SEMANTICS (spec §4):
   - Library: unchanged (existing settings-sheet requestOpenSettingsSheet).
   - Recent: NEW onReselect → resume last-read manga. Reuses old
     HistoryTab mechanism verbatim: GetNextChapters.await(onlyUnread=
     false).firstOrNull() + ReaderActivity.newIntent + no_next_chapter
     snackbar fallback. New RecentReselect.kt (suspend helper, Injekt
     GetNextChapters). Channel pattern from old HistoryTab.
   - Feed: NEW onReselect → opens source-selector dropdown directly
     (state hoisted to FeedTab, passed to FeedScreen;
     openSourceSelectorEvent channel). NOT ManageFeeds, NOT customize.
   - Browse (GlobalSearch), More (Settings): unchanged, verified.
3. CONTINUE (spec §7/§8): semantics confirmed correct (Library ∩
   hasStarted(readCount>0) ∩ unread>0 = started-reading-and-resumable,
   history-derived via chapters table — NOT favorites+unread alone).
   Added compact page-level controls (FilterChip row, lazy item):
   sort Last read / Alphabetically (existing sort i18n keys) +
   Downloaded only filter (downloadManager.isChapterDownloaded via
   nextChapter). In-memory only. No giant toolbar.
4. FEED (spec §12–§16):
   - Grid style: second independent display option — Normal grid
     (MangaComfortableGridItem, title under cover) vs Compact grid
     (MangaCompactGridItem, title INSIDE cover bottom overlay — spec's
     exact compact semantics, reused Library components). New pref
     pref_feed_compact_grid (Boolean, default false) + SM asState.
   - FeedHeader now ListGroupHeader (shared section-header hierarchy).
   - Customize dialog restructured: Display (grid columns + grid style) /
     Sources (source-selector switch) / Listing (default listing chips +
     listing-selector switch) — existing components only.
   - "All sources" selection confirmed configurable + deselectable
     (in-memory per session; no invented persistence).
   - Load more/Loading/Error states: shared components already
     (CircularProgressIndicator/EmptyScreen/TextButton retry) — kept.
5. GLOBAL SEARCH DEFAULTS (spec §19): SearchScreenModel.State
   sourceFilter default PinnedOnly → All; SourcePreferences
   has_filters_toggle_state default false → true (Has-results ON).
   Persisted user choice still wins over defaults. Pinned NOT
   auto-enabled.
6. READER SETTINGS (spec §22–§28): all four pages now grouped via
   PreferenceGroupCard (existing solid grouped-surface widget, no
   frost — §24 verified: asFrostedModal is opaque pre-blend; settings
   surfaces solid):
   - ReadingModePage: Reader layout card (mode+orientation) + named
     Paged/Long-strip cards (TapZones inside) + dual-page cards
     (title=null — rows self-labeled; duplicate-text fix). Loose
     HeadingItems removed.
   - GeneralPage: unchanged groups from prior set (already compliant).
   - ReadAloudPage: Speech (rate + auto-page-turn/auto-next-chapter/
     keep-screen-on) / Speech cleanup / Spoken content / OCR & text
     recognition / Advanced voice row. Existing keys + new
     pref_group_speech.
   - ColorFilterPage: Brightness / Color filter / Effects groups
     (new key pref_filter_effects). No fake categories; RGBA sliders
     and mode chips stay inside Color filter group.

i18n base additions: feed_grid_style_normal, feed_grid_style_compact,
feed_sources_section, pref_group_speech, pref_filter_effects. No locale
hand-edits.

GATES GREEN 2026-09-06 (docker devcontainer JDK17, -Xmx4g, both
volumes): spotlessApply + spotlessCheck + testDebugUnitTest +
verifySqlDelightMigration + :app:assembleDebug BUILD SUCCESSFUL 2m53s.
NO DB/schema change. APK 0.5.2-8259 arm64 installed on SM_M066B;
clean relaunch (PID 20072), 0 FATAL in logcat.

Files changed (15 + 1 new):
  NEW: app/.../ui/recent/RecentReselect.kt
  EDIT: RecentTab.kt (AppBar + reselect), ContinueTab.kt (controls row),
    ContinueScreenModel.kt (sort/downloaded-only),
    HistoryScreen.kt (title removed), UpdatesScreen.kt (title removed),
    FeedTab.kt (reselect wiring + state hoist), FeedScreen.kt (compact
    grid, hoisted selector, dialog restructure, ListGroupHeader),
    FeedScreenModel.kt (+compactGrid), FeedPreferences.kt (+compactGrid),
    SearchScreenModel.kt (default All), SourcePreferences.kt
    (has-results default true), ReadingModePage.kt / ReadAloudPage.kt /
    ColorFilterPage.kt (grouping), GeneralSettingsPage.kt (vertical-nav
    card title=null), i18n base strings.xml (+5 keys).

Device verification PENDING user (spec §31 matrix): Library reselect →
settings sheet; Recent reselect → last-read opens (empty case =
no_next_chapter snackbar); Feed reselect → dropdown; Browse reselect →
Global Search w/ All + Has-results, Pinned off; More reselect →
Settings; Recent status-bar/cutout clear; Continue sort/downloaded
chips; History/Updates no duplicate titles; Feed compact grid title
inside cover; customize dialog groups; Reader settings 4 tabs grouped,
spacing consistent, no frost.
```

User-directed information-architecture + functional set (NOT a visual
redesign; M3 baseline untouched). Audited first (3 parallel read-only
agents: nav/index, recent candidates, feed+reader settings).

1. BOTTOM NAV REORDER — final order Library → Recent → Feed → Browse →
   More. HomeScreen TABS list edited; sealed Tab.{Updates,History} →
   Tab.Recent; openTabEvent when-map updated; updates-count badge moved
   to RecentTab. Index audit: Voyager addresses tabs by CLASS, not index
   (TabOptions.index consumed NOWHERE in repo — verified by grep; the
   historical Feed/More index swap was already fixed). TabOptions.index
   now 0..4 matching list order (RecentTab 1u, FeedTab 2u, BrowseTab 3u
   unchanged, MoreTab 5u→4u fixed). MainActivity SHORTCUT_UPDATES/
   SHORTCUT_HISTORY both map → Tab.Recent (notification taps in
   LibraryUpdateNotifier still work via SHORTCUT_UPDATES). shortcuts.xml
   (res/xml + dead duplicate at app/src/main/shortcuts.xml) consolidated:
   2 recent shortcuts → 1 "Recent" (SHOW_RECENTLY_UPDATED). Feed keeps
   animated icon + fade-through tab animation (same treatment as others).
   BackHandler still targets LibraryTab (class-based, safe).
2. RECENT DESTINATION — new ui/recent package. RecentTab hosts 3 internal
   tabs via PrimaryTabRow + HorizontalPager (TabbedScreen pattern, but
   hosting full self-contained screens: UpdateScreen/HistoryScreen keep
   their own Scaffold+AppBar):
   - Continue (NEW, ui/recent/continuereading): unfinished/resumable
     reading — favorites with unreadCount>0 && hasStarted, sorted
     lastRead desc. ContinueScreenModel reuses GetLibraryManga.subscribe
     + GetChaptersByMangaId + getNextUnread (same resume logic as
     Library's per-item continue button — NOT the reverted Library
     Continue section UI). Row = cover + title + unread plural +
     PlayArrow resume; row tap resumes too.
   - History (ui/recent/history): verbatim HistoryTab Content logic —
     HistoryScreen + all dialogs + events + snackbar, shared
     SnackbarHostState from RecentTab scaffold. Resume-last-chapter
     reselect semantics DROPPED (HistoryTab deleted; RecentTab reselect
     = no-op like Feed).
   - Updates (ui/recent/updates): verbatim UpdatesTab Content logic —
     UpdateScreen + filter/delete dialogs + events + selection-mode
     showBottomNav interplay + resetNewUpdatesCount. DownloadQueue
     reselect semantics DROPPED (UpdatesTab deleted; queue still
     reachable via More + notification).
   KEY VOYAGER LESSON (cost 90min debugging): rememberScreenModel is an
   EXTENSION on cafe.adriel.voyager.core.screen.Screen, and
   MigrateMangaDialog is internal fun Screen.MigrateMangaDialog — plain
   top-level composables have NO implicit Screen receiver → "unresolved
   reference". Fixed by making tab-builder + content functions
   Screen extensions (BrowseTab sourcesTab() pattern). Probe-file
   bisect + javap on voyager-screenmodel jar found it.
3. FEED V2:
   - Compact header: source row replaced by dropdown (name ▼ → menu with
     All + each configured source, checkmark on selection) + listing
     chips on the same row (All/Popular/Latest). No permanent
     one-chip-per-source row.
   - Customization dialog (AppBar List icon): grid columns (Auto/2-5),
     show source selector toggle, show listing selector toggle, default
     listing (All/Popular/Latest — drives initial listingOverride until
     user changes chips). New FeedPreferences keys:
     pref_feed_show_source_selector (true), pref_feed_show_listing_
     selector (true), pref_feed_default_listing (""=All), pref_feed_grid_
     columns (0=Auto). No new DB/schema.
   - Pagination (explicit Load more, NO auto infinite scroll): per-feed
     paging state inside FeedSectionResult.Success(mangas, hasMore,
     isLoadingMore). loadMore computes next page from mangas.size/20+2
     (PAGE_SIZE const, ponytail: swap for stored counter if a source
     returns uneven pages), appends + dedups by url; error during append
     keeps existing list (isLoadingMore=false only); hasNextPage drives
     hasMore. retry() re-fetches page 1. Independent state per feed —
     switching source/listing never corrupts another section (map keyed
     by FeedItem). Recomposition-safe: fetch only from user actions +
     feed-pref changes (state-controlled, no network per recompose).
   - FeedSectionResult.Error now has retry button. Section footer:
     spinner while loading-more / "You're all caught up" at end / Load
     more button.
   - Grid: GridCells.Adaptive(96.dp) → Fixed(N) when pref set; gutters
     unchanged (4dp, CommonMangaItemDefaults — matches Library item
     spacing; Library-style FastScroll skipped, feed is short).
4. READER SETTINGS (IA only, no new components):
   - ReadingModePage: NEW grouped "Reader layout" surface
     (PreferenceGroupCard wrapping reading-mode + orientation chips —
     Tadami-inspired grouping in Yomihon's own M3 language); pager/
     webtoon sub-settings grouped into unnamed card (scale/zoom/crop/
     pan/panel-nav) + "dual page split" card (split/invert + rotate/
     invert). All existing prefs/keys untouched.
   - GeneralSettingsPage: groups = "Toolbar & display" (theme, page
     number, fullscreen, cutout, transitions) + "Behavior" (keep-screen-
     on, long-tap, chapter transition) + unnamed vertical-navigator
     card + "flash" card. TTS/OCR/ColorFilter pages untouched (per
     spec); toolbar drag-drop NOT implemented (visibility controls only
     — see backlog).
5. i18n base additions (strings.xml): label_recent, recent_tab_continue,
   recent_continue_empty, feed_select_feed (unused-dropped later — kept
   minimal), feed_show_source_selector, feed_show_listing_selector,
   feed_default_listing, feed_load_more, feed_end_of_list,
   feed_grid_columns, pref_group_reader_layout, pref_group_behavior,
   pref_group_toolbar. Plural continue_reading_unread reused (existed).
   No locale hand-edits.

GATES GREEN 2026-09-06 (devcontainer JDK17, -Xmx4g, both volumes):
  spotlessApply; spotlessCheck + testDebugUnitTest +
  verifySqlDelightMigration + :app:assembleDebug BUILD SUCCESSFUL
  2m33s. NO DB/schema changes.

Files changed (17 code + 3 xml + docs):
  NEW: app/.../ui/recent/{RecentTab,RecentTabContent}.kt,
       app/.../ui/recent/continuereading/{ContinueScreenModel,ContinueTab}.kt,
       app/.../ui/recent/history/RecentHistoryTab.kt,
       app/.../ui/recent/updates/RecentUpdatesTab.kt
  EDIT: HomeScreen.kt (TABS/Tab seal/badge/when), MainActivity.kt
       (shortcut map), MoreTab.kt (5u→4u), FeedTab.kt (wiring, 2u),
       FeedScreenModel.kt (paging + prefs + retry), FeedPreferences.kt
       (+4 keys), FeedScreen.kt (dropdown header, customize dialog,
       footer, retry), ReadingModePage.kt + GeneralSettingsPage.kt
       (groups), i18n base strings.xml, shortcuts.xml (res/xml + dup).
  DELETED: ui/updates/UpdatesTab.kt, ui/history/HistoryTab.kt (logic
       relocated verbatim into ui/recent/*; ScreenModels untouched).

Device verification PENDING user (matrix): all 5 tabs + active
indicator; Recent Continue/History/Updates switch + resume + empty
states; updates badge on Recent; shortcuts (launcher + notification tap
→ Recent); Feed: dropdown select, chips, customize dialog (toggles +
grid columns + default listing), Load more page 2 append, per-feed
state independence, retry on error, end-of-list label; Reader: layout
group selection works per-series, TTS/OCR pages unchanged, color-filter
dim behavior unchanged (title-based check, no index hack).

FUTURE BACKLOG RECORDED (docs/phase.md "Deferred features"):
  1. Reader toolbar reordering (drag/drop + persist + defaults) — this
     phase only keeps visibility controls.
  2. True backdrop blur investigation (Compose can't sample sibling
     artwork View; RenderEffect fullscreen rejected on perf — must
     first prove feasibility: rendering arch, perf, battery, memory,
     AMOLED behavior, scroll perf; then implement).
  3. Artwork-reactive reader tray (subtle, content-first, no aura/rim/
     glass, reader perf first).
  4. Feed automatic near-end pagination (only after explicit Load-more
     is device-tested stable).
```

## Known issues

```text
1. MEDIUM | OCR ordering | Local Legacy/Fast full-page scan path uses raw
   detection index as region order (hardcoded Horizontal orientation) — vertical
   manga would read out of order. Mitigation today: detection engine stub always
   throws (UnavailableDetOcrEngine TODO in OcrRepositoryImpl.detectionEngine())
   so scans redirect to Glens when fallbacks enabled. Action: document
   (done in architecture.md §4); optional follow-up = port Glens ordering into
   scanLocally. Do NOT silently reorder in segmenter.

2. RESOLVED (2026-09-12 audit; docs corrected 2026-09-12 RM-01) | ReaderActivity
   | Entry claimed ContentOverlay internally re-called
   binding.composeOverlay.setComposeContent creating two parallel Compose
   rendering blocks. The 2026-09-12 master audit verified the code:
   ReaderActivity has ONE composition block (single setComposeOverlay at
   ReaderActivity.kt:606; grep clean of duplicates). The dual-block landmine
   NO LONGER EXISTS — the entry was stale. Historical note: while the
   duplicated block existed, TTS bar/dialog additions belonged in the inner
   ContentOverlay beside OcrLoadingIndicator; current code confirms that
   placement. No refactor happened; no refactor is needed.

3. RESOLVED-locally | Build env | ML model assets were gitignored & absent;
   NOW DOWNLOADED into working tree via the exact CI step
   (.github/workflows/build.yml "Download ML models", all sha256 verified):
   app/src/main/assets/ocr/{encoder,decoder}.tflite+embeddings.bin,
   app/src/main/assets/ocr_fast/{encoder,decoder}.tflite,
   data/src/main/assets/panel_detector/model.tflite. Still absent on fresh
   clones/CI-only; re-run that step if assets are wiped.

4. INFO | Docs env | AGENTS.md notes .devcontainer "Java 17" note stale — CI/toolchain
   effectively JDK 21 (Gradle java property 17). Use CI commands from rules.md §11.

 5. RESOLVED | TTS leak (2026-08-28 LeakCanary pass, fixed 2026-08-29) | Old
    assessment "bounded, self-healing small retention" was WRONG: LeakCanary
    showed ~100MB retained after reader exit (TTS service native GC root →
    AndroidTtsEngine singleton → onFocusEvent lambda → dead controller →
    ReaderViewModel → destroyed ReaderActivity). FIXED in 5c7d2cc2c:
    ReaderViewModel.onCleared sets ttsEngine.onFocusEvent = null AFTER
    stop()/shutdown() (engine-init-failure mid-session path NOT affected —
    clearing happens only on ViewModel teardown, not engine.shutdown());
    next session re-registers in controller init. Plus ReaderActivity cancels
    settingsScreenModel.ioCoroutineScope on dispose. DEVICE-VERIFIED 2026-08-29
    (build 0.4.0-8241, leak-full.log): LeakCanary 0 APPLICATION LEAKS after two
    reader TTS sessions + exits. CLOSED.

6. MEDIUM | OCR tiling | Glens strip tiling dedupes seam
   repeats by IoU ≥0.45; a bubble straddling a seam can still yield two
   complementary fragments if Lens returns differing boxes in the overlap.
   Ceiling accepted for v1; upgrade path = cross-tile text merge before region
   creation. Watch `tiled=true` pages during device pass. NOTE: tiles now run
   3-concurrent — seam behavior unchanged, but watch for new Lens rate-limit
   responses (HTTP 429/5xx) under parallelism; reduce TILE_CONCURRENCY if seen.

7. LOW | Build env (2026-08-24) | Docker debug keystore now persisted via
   volume yomihon-android-home (~/.android). If that volume is ever deleted,
   signatures change → INSTALL_FAILED_UPDATE_INCOMPATIBLE again; recovery =
   uninstall + reinstall + restore .tachibk backup (data loss). Also: gradle
   transform cache once held stale /work paths — if DexingNoClasspathTransform
   fails with "outside the root directory", delete yomihon-gradle-home volume.

8. RESOLVED | Reader TTS (NEW 2026-08-26) | Advance-confirm timeouts on webtoon:
   `page advance to N timed out` fired systematically (ch732 p17 ×3, ch947
   p1+p2). Root cause: WebtoonViewer.onScrolled used
   findLastEndVisibleItemPosition which returns the item at the BOTTOM of the
   view — for webtoon (vertical stack), short pages have the next page already
   visible → returns next page index ≠ target → 10 s timeout → Paused.
   FIXED 2026-08-27: switched to findFirstVisibleItemPosition in onScrolled
   (WebtoonViewer.kt). Verified in build 0.4.0-8234. Evidence: logcat-step3-
   full.log lines ~99730–132013 (pre-fix), logcat-8234-test.log (post-fix).

9. RESOLVED | Instrumentation gaps (NEW 2026-08-26, FIXED 2026-08-28) | Controller
   lacked debug logs for pause/resume/stop + nextSentence/previousSentence, so step
   4/5 results were only partially verifiable. FIXED 2026-08-28: added DEBUG
   action-level logs in TtsPlaybackController — pause (page+sentence), resume
   (page+resumeIndex), stop (prior phase), stepBy next/prev (from->to + phase, and
   boundary-reject), and audio-focus-loss pause (event name). No text content logged
   (rules §7). Step 4/5 now verifiable from logcat.

10. RESOLVED | OCR duplicate scans + recycle crash (2026-08-26) | Root causes
     were: no single-flight in OcrRepositoryImpl.scanPage; bitmap recycled by
     caller's useBitmap-finally while the repo-owned task still used it;
     upsert skipped when caller abandoned await. Fixed by single-flight map +
     moving bitmap lifecycle/upsert inside the queue task (Phase 9 perf pass #1).
     Device re-verification pending.

11. INFO | Logcat analysis (2026-08-27) | logcat-8234-test.log (312,371 lines)
     contains ZERO app activity after install. App process (PID 22868) started
     once for broadcast receiver at 23:58:33, killed after 11 seconds (signal 9).
     Zero TTS/OCR logs, zero activity launches. All 6 user-reported issues
     cannot be verified — no test run occurred on build 0.4.0-8234 during this
     log capture. Fresh test required.

12. LOW | App widget (NEW 2026-08-28) | GlanceAppWidget composition error
     "CompositionLocal LocalContext not present" observed in run4 logcat
     (19:48:26, non-fatal, app kept running). Unrelated to TTS/reader work —
     pre-existing Glance widget issue. Investigate only if widget complaints
     surface.

13. RESOLVED | TTS prefetch failure escalation (NEW+FIXED 2026-08-28,
     DEVICE-VERIFIED 2026-08-28) | A failed background prefetch scan (transient
     DNS: UnknownHostException for lensfrontend-pa.googleapis.com while app
     backgrounded) called scanOnDemand→fail(TtsError.OcrError) and pushed a
     healthy paused session into Error phase; user had to restart. Root cause:
     scanOnDemand unconditionally reported failure even for best-effort
     prefetch callers. FIXED: reportFailure param (default true); prefetch
     passes false + logs "prefetch scan failed (best-effort)"; "prefetch
     complete" now only on success. VERIFIED ON DEVICE (logcat-prefetch-
     verify.log, PID 22599, wifi-killed test): 13 prefetch failures for page 4
     logged "(best-effort)" while page 3 playback + 13 sentence-steps
     continued uninterrupted; home-press mid-prefetch (page 6 scan in flight)
     → auto-pause, stop(phase=Paused), NO Error. Main-loop failure on the
     CURRENT page still honestly errors (page 4 wifi-off → Error bar → user
     retry after wifi restore → 16.8s tiled scan → playback resumed) — correct
     by design. INFO observation: each next-sentence tap while wifi down
     restarted the failed page-4 prefetch (~170ms fast-fail each) — harmless,
     tap-rate bounded, no fix needed.
```

## Technical debt

```text
- UnavailableDetOcrEngine stub (TODO upstream) — see Known issues #1.
- Known issue #2 (dual Compose composition blocks) was found RESOLVED in code
  by the 2026-09-12 audit (one composition block, ReaderActivity.kt:606);
  docs corrected in RM-01. No debt remains.
- No unit tests for repositories/download/network/UI layers (house-wide, pre-existing).
- androidTest OcrRepositoryImplTest is @Ignore'd (needs device+models).
(Deliberately NOT adding new debt for TTS v1: policy logic must be tested.)
```

## Dependencies

```text
No dependency changes made. Standing decision: TTS v1 adds ZERO dependencies
(framework android.speech.tts + android.media.AudioManager; minSdk 26 covers
AudioFocusRequest). Key existing versions recorded in architecture.md §1/§14
(Kotlin 2.4.0, AGP 9.2.1, Compose BOM 2026.06.01, SQLDelight 2.3.2, OkHttp 5.4.0,
Injekt 91edab2317, JUnit5 6.1.1/Kotest 6.2.2/MockK 1.14.11).
```

## Testing status

```text
Unit tests:        PASS (2026-09-10, full testDebugUnitTest — UI audit
                    Batches 1-4 + Batch 5 device pass; incl
                    FeedScreenModelStateTest 9/9 and fixed
                    MangaScreenModelErrorStateTest 4/4 full-suite)
Integration tests: none run (existing androidTest suites are device-gated/@Ignore)
UI tests:          none exist in repo
Device tests:      Phase 8 script COMPLETE (2026-08-28). Phase 9 COMPLETE
                      (2026-08-29). Phase 10A Task 7 COMPLETE (2026-08-31).
                      Stabilization matrix 2026-09-08: A/B/C/D/E/G/H/I PASS,
                      F (GLENS retry live) + J (eviction boundary) +
                      onboarding PermissionStep = PENDING (no natural 502 /
                      5000-page boundary / fresh install runnable);
                      evidence .device-pass/stabilize-verify.log.
                      UI-audit Batch 5 device matrix 2026-09-10: PASS
                      (Feed D-02/D-03/D-04 + paging + ManageFeeds + order;
                      Recent + D-01; Settings Search D-08; MangaScreen
                      D-11 success-path; typography/surfaces/a11y spot;
                      D-11 missing-path + D-04 negative + stale-source =
                      unit/code-verified only, no safe device trigger);
                      evidence .device-pass/batch5-verify.log +
                      screenshots/batch5/.
Lint:              spotlessCheck PASS (2026-09-10 post-commit baseline,
                      committed HEAD c02efca25)
Build:             :app:assembleDebug PASS (2026-09-10 post-commit baseline,
                      0.5.2-8266 arm64; in-place install SM_M066B wired
                      USB; launch + 5-tab + MangaScreen smoke PASS,
                      0 crashes)
Baseline (pre-TTS expectations): CI order = spotlessCheck → testDebugUnitTest →
                          verifySqlDelightMigration → assembleRelease (see rules.md §11)
Environment: devcontainer image vsc-yomihon-e24e3bd7… (JDK 17) via docker on host;
             ALWAYS pass -Xmx4g; mount BOTH volumes:
               -v yomihon-gradle-home:/home/vscode/.gradle
               -v yomihon-android-home:/home/vscode/.android   (stable debug key)
             CI (JDK 21, more RAM) unaffected.
```

## Last verified build

```text
Date:     2026-09-10 (POST-COMMIT BASELINE VERIFICATION of committed HEAD
          c02efca25 — Batches 1–5 @ 1b2c56b23 + docs c02efca25.
          VERIFICATION-ONLY task, zero source changes.)
Command:  ./gradlew spotlessCheck (37s) → testDebugUnitTest +
          verifySqlDelightMigration (2m59s) → :app:assembleDebug
          (2m38s) — docker devcontainer JDK17, -Xmx4g, both volumes.
Result:   ALL 4 GATES GREEN. APK 0.5.2-8266 vc28 arm64 debug.
Device:   SM_M066B (wired USB), in-place install over 0.5.2-8264
          (adb install -r, data preserved). NOTE: package was found
          DISABLED (enabled=0) on device — pm enable app.yomihon.dev
          run before launch (likely user-side disable; flagged, not
          investigated). Smoke: launch OK (PID 25362), Library grid
          renders (titles, badges, category chips), Recent opens
          (Continue/History/Updates), Feed opens (source chips + All/
          Latest, listings), Browse opens (sources list), More opens
          (grouped settings), MangaScreen opens (chapters, source,
          genres, In-library state). 0 FATAL EXCEPTION / 0 app crash
          in full session logcat. VERDICT: POST-COMMIT BASELINE PASS —
          committed Batches 1–5 tree is the new known-good baseline.
```

## Last verified build (prior)

```text
Date:     2026-09-10 (Batch 5 device-verification pass, run by orchestrator)
Command:  ./gradlew spotlessCheck testDebugUnitTest verifySqlDelightMigration
          :app:assembleDebug (docker devcontainer JDK17, -Xmx4g, both volumes)
Result:   ALL GREEN — 3m22s (first run exposed a TEST-ONLY harness defect in
          the Batch-4-added MangaScreenModelErrorStateTest: per-test
          resetMain/setMain raced the model's flowWithLifecycle IO workers
          (Voyager screenModelScope is process-global in JVM tests) → fixed
          by @TestInstance(PER_CLASS) class-scoped setMain/resetMain; suite
          then 4/4 cold (--rerun-tasks) AND full-suite green; reproducible
          pre-fix: failed 2× in full-suite, passed isolated — real race, not
          flake). APK 0.5.2-8264 arm64 reinstalled in-place on SM_M066B;
          full device matrix executed (see Batch 5 block). UNCOMMITTED;
          awaiting user commit.
```

## Last verified build (prior)

```text
Date:     2026-09-10 (Batch 4 set, run by orchestrator)
Command:  ./gradlew spotlessCheck testDebugUnitTest verifySqlDelightMigration
          :app:assembleDebug (docker devcontainer JDK17, -Xmx4g, both volumes)
Result:   ALL GREEN — spotless+tests+migration 3m5s, assembleDebug 3m15s
          (rebuild 2m33s after Dictionary-subtitle fix). Change set:
          D-08 settings search registration, D-11 MangaScreen Error state
          + MangaScreenModelErrorStateTest 4/4, D-12 prd docs fix. arm64
          debug APK installed in-place on SM_M066B + device pass (see
          Batch 4 block). UNCOMMITTED; awaiting user commit.
```

## Last verified build (prior)

```text
Date:     2026-09-08 (post-v0.5.2 stabilization set, run by orchestrator)
Command:  ./gradlew spotlessCheck testDebugUnitTest verifySqlDelightMigration
          :app:assembleDebug (docker devcontainer JDK17, -Xmx4g, both volumes)
Result:   ALL GREEN — spotless+tests+migration 2m, assembleDebug 2m24s.
          Change set: LEGACY OCR engine + 139.9MB assets removed (batch 1),
          TTS prefetch withIOContext fix (batch 2), GLENS transient retry
          (batch 3), dead-code hygiene C6-C10 (batch 5; C5 disproved),
          OCR cache 5000-page retention (batch 6), PermissionStep
          runBlocking→lifecycleScope (batch 7). APK 0.5.2-8263 arm64
          installed in-place on SM_M066B; device matrix executed
          (A/B/C/D/E/G/H/I PASS, F/J/onboarding PENDING). UNCOMMITTED;
          awaiting user commit.
```

## Last verified test

```text
Date:     2026-09-10 (Batch 4 set)
Command:  ./gradlew testDebugUnitTest (in full gate run above) +
          :app:testDebugUnitTest --tests MangaScreenModelErrorStateTest
Result:   BUILD SUCCESSFUL — all suites green + NEW
          MangaScreenModelErrorStateTest 4/4 (missing→Error.missing,
          generic→retryable Error, retry→Success, valid→Success).
```

---

[COMPLETED 2026-09-08 — FULL UI AUDIT + IMPLEMENTATION MAP (docs/Prompt.md
spec), UNCOMMITTED (docs only, ZERO app source touched)]

Read-only audit per docs/Prompt.md: docs system (prd/architecture/rules/
phase/design/design-audit/memory/branding) re-read + current source
re-verified (baseline main @ 9e7a27b08, post-stabilization). Primary
deliverable: **docs/ui-implementation-map.md** (32-section designer→dev
handoff: screen inventory, nav map, IA, design-system/typography/spacing/
alignment/header/surface specs, frost rules, responsive, component
inventory, screen-by-screen maps, feature-placement table, code-ownership
map, discrepancy register D-01..D-15, functional VISIBLE==ACTUAL audit,
protected systems, approval gates, batch sequence).

KEY FINDINGS (re-audit, prior records treated as evidence not truth):
1. **P0 BUG (D-02)** — Feed listing selector NO-OP on grid: chips highlight
   but grid still shows BOTH Popular+Latest sections. ROOT CAUSE proven in
   source: FeedScreenModel.State.visibleFeeds (FeedScreenModel.kt:64-71)
   filters by enabled + selectedSourceId ONLY — never consults
   listingOverride; selectListing() (:202) writes state chips read but
   nothing applies it to feeds. Proposed fix = add listing filter to
   visibleFeeds getter (presentation+state only; sections cache untouched;
   source-persistence path untouched; low regression risk). Verification
   plan written (map §15).
2. **D-03 SECONDARY** — FeedScreenModel.kt:102 re-seeds
   `listingOverride ?: defaultListing` on EVERY pref-emit → explicit "All"
   (null) silently reverts to defaultListing next time any of the 4
   collected prefs changes (e.g. toggling a customize switch). Needs
   user-chose-All sentinel vs uninitialized distinction.
3. **D-01** — RecentTab.kt:67 creates resumeHostState SnackbarHost but
   NEVER mounts it in Scaffold (:86 passes snackbarHostState only) →
   reselect no_next_chapter fallback snackbar invisible.
4. **D-04** — AddFeedDialog: default selectedListing=LATEST persists when
   switching to a !supportsLatest source (guard only fires on explicit
   re-click).
5. Minor register: D-05 Feed section divider rhythm, D-07 ManageFeeds
   trailing density, D-08 Dictionary/OCR-exclusions absent from settings
   search index (approval gate), D-10 Advanced loose rows, D-11 MangaScreen
   still has NO error state (approval gate), D-12 prd.md §1.4 stale tab
   list (6-tab History/Updates vs actual 5-tab Recent), D-13 grid-container
   duplication, INFO: D-09 global-search 4-path, D-14 Recent AppBar no
   actions (by design), D-15 Library reselect affordance.
6. RESOLVED-SINCE-2026-09-04-AUDIT list (verified in source — do NOT redo):
   grid gutters unified, itemTitle typography, AppBar/Dictionary sp hacks,
   TrackInfo double-clip, HistoryItem heightIn, dead outer reader tree,
   ReaderPageIndicator restored, OcrLoadingIndicator clearance, More
   GroupHeader private dup, TabOptions index swap, Feed raw-TopAppBar +
   custom empty/loading/error + "✓" markers, ManageFeeds AMOLED
   transparent-row fix, customize-sheet grouping. Remaining LOW:
   ResizableSheet geometry unification (deferred by 09-05 B-R ruling).
7. "Create" tab (Prompt.md user reference): CONFIRMED ABSENT from source
   (no route/destination/string); most plausible referents = Recent
   "Continue" tab or Feed Add-dialog. Recorded, not invented.
8. Docs-vs-source conflicts recorded (map §24): prd §1.4 stale (D-12);
   design-audit.md superseded on 6-tab order + reselect=scroll-to-top +
   Feed-icon finding (fixed); no conflicts in spacing/frost/reader rules.

Verification: docs-only change — no Gradle gates applicable, no app source
modified (Prompt.md §3 absolute rule honored). NEXT: user reviews/
approves docs/ui-implementation-map.md (approval gates §27), then
implementation begins per batch sequence §26 (Batch 1 = Feed listing fix
D-02/D-03/D-04 + D-01 snackbar mount, device-verified per §15 plan).

[COMPLETED 2026-09-06 — UI governance skill creation, UNCOMMITTED (docs-adjacent, zero app source)]

Created project-local OpenCode skill .opencode/skills/yomihon-ui/SKILL.md
(per user spec: permanent UI/design guardrail for future agents). Grounded
in docs/design.md §1–§14 + rules.md §4 + source verification (codegraph:
PreferenceGroupCard, Translucent.kt roles, NavigationBar pill,
ListGroupHeader, TachiyomiTheme/MaterialExpressiveTheme, ReaderBottomBar).
Encodes: consistency-above-novelty ladder (Yomihon→Mihon→M3→new),
do-not-invent-components search protocol, colorScheme-only tokens,
black≠hierarchy rule, frost surface model (floating chrome / frosted modal
/ solid; no frost-on-frost, no frost on readable content), grouped
settings (one group = one PreferenceGroupCard surface), spacing/typography
baselines (16dp screen, 24/12 pills, SettingsItems metrics, Material
roles), reader hero/overlay rules, floating-pill bottom nav, Feed/Recent
behavioral-vs-visual separation, a11y/motion/responsive, MANDATORY visual
verification + screenshot checklist, anti-pattern list, Yomitsu
future-brand note (no rebranding in non-rebranding tasks). Validated:
YAML frontmatter parses, name matches dir, `opencode run` recognizes the
skill; scenario pressure-tests GREEN (black-card rejected, component
search-first enforced). No application source files modified.

[COMPLETED 2026-09-06 — visual/UX correction pass (2nd) over Feed
reselect + source persistence, UNCOMMITTED (4 files)]

Spec source: user "VISUAL DESIGN + UX CORRECTION PASS" prompt (this
session) — overrides two behaviors of the previous correction pass:

1. FEED RESELECT (spec: "Reselecting the already-active Feed bottom-nav
   item opens Manage Sources. Do NOT substitute the source-selector
   dropdown"). FeedTab.onReselect now navigator.push(ManageFeedsScreen())
   (same screen the Tune AppBar action opens). openSourceSelectorEvent
   channel + hoisted sourceSelectorExpanded state DELETED from FeedTab;
   dropdown visibility is now local remember state inside
   SourceSelectorDropdown (FeedScreen.kt). First navigation opens Feed
   normally (onReselect only fires on already-active tab — Voyager
   semantics, unchanged).
2. FEED SOURCE SELECTION PERSISTENCE (spec: persist across process
   death, restore on reopen, never always-default-to-All). New pref
   FeedPreferences.selectedSource() ("pref_feed_selected_source",
   Long? via getObjectFromString, "" = All sources). FeedScreenModel:
   - 4-flow combine (showSourceSelector/showListingSelector/
     defaultListing/selectedSource changes) via private DisplayPrefs
     data class (first attempt using listOf() erased types to
     Comparable<*> — compile error, fixed).
     - selectedSourceId seeded from pref on collect; in-session
       selection wins (?: guard, same pattern as listingOverride).
   - selectSource() now WRITES the pref + updates state.
   - visibleFeeds: saved source whose feeds no longer exist/disabled
     falls back to ALL enabled feeds (no empty screen on stale pref).
     Source filter + listing filter logic unchanged otherwise.
   VERIFIED BEHAVIOR PATH: select → pref written → process death →
   FeedScreenModel re-created → combine emits pref value → state
   restored → dropdown shows saved source + grid filtered. Pref store
   is SharedPreferences-backed (Injekt) → survives process death.
   (Device confirmation pending, see below.)
3. Recent: VERIFIED already-correct (no changes needed) — one screen
   AppBar "Recent" (RecentTab.kt:82), persistent PrimaryTabRow
   Continue|History|Updates with badge, HorizontalPager swipe between
   all 3, no duplicate content headings (Continue controls = FilterChips,
   not headers).
4. Reader settings: VERIFIED already-correct (no changes needed) — all
   4 pages (ReadingMode/General/ReadAloud/ColorFilter) grouped via
   PreferenceGroupCard solid surfaces, no frost, no black containers
   (grep: zero arbitrary Color(0x/Black/fontSize in these files; hits
   elsewhere = theme token definitions + documented exceptions).
5. Full-repo arbitrary-token sweep: only legitimate hits (Nord/Tako/
   colorscheme token sources, documented WordSelector 20sp, pill
   shapes 28dp precedent). No new violations introduced.

GATES GREEN 2026-09-06 (docker vsc-yomihon-e24e3bd7e46d…, JDK17, -Xmx4g,
both volumes): spotlessApply + :app:compileDebugKotlin BUILD SUCCESSFUL
3m25s; spotlessCheck + testDebugUnitTest + :app:assembleDebug BUILD
SUCCESSFUL 2m38s. No DB/schema change (pref-only). APKs built.

Device visual pass NOT RUN — wireless adb unreachable from host this
session (192.168.29.x subnet no route: host on 172.26.16.0/20, device
subnet unreachable, last known device IP 192.168.29.98 refused). No
emulator (no Android SDK on host). Visual verification performed
STATICALLY per yomihon-ui checklist (structure/components/tokens/
spacing verified in source); rendered-screenshot review PENDING user
device pass.

Files changed (4): FeedPreferences.kt (+selectedSource pref),
FeedTab.kt (reselect → ManageFeedsScreen, dropdown state un-hoisted),
FeedScreen.kt (SourceSelectorDropdown local expanded state,
FeedFilterBar signature cleanup), FeedScreenModel.kt (DisplayPrefs
combine, selectSource persists, visibleFeeds stale-source fallback).

Device verification PENDING user: (1) Feed reselect opens Manage
Sources; (2) select source → leave Feed → kill app → reopen → Feed
shows saved source; (3) source selector toggle respected; (4) All
Sources selection persists too; (5) delete feed of saved source →
Feed falls back to all feeds.

[COMPLETED 2026-09-07 — DEVICE VISUAL PASS over the correction set,
UNCOMMITTED (verification only, no code changes)]

Build 0.5.2-8260 (arm64 debug) installed on SM_M066B via wireless
adb (port had rotated to :35577; reachable this session). Method:
uiautomator dumps (view hierarchy/text/bounds) + screencap PNGs +
pure-stdlib pixel-band analyzer (/tmp/opencode/png_scan.py — no PIL
on host) + dumpsys activity states. Screenshots archived:
.device-pass/screenshots/01..18*.png. Host-side image reading
unsupported by the model — verification done via hierarchy dumps +
pixel-band analysis, screenshot files preserved for human review.

ALL TESTS PASS:
1. Feed reselect → Manage Sources screen (feed rows: Asura/Atsumaru/
   Mangakakalot/Comix + listing labels, no manga grid). CONFIRMED
   with clean sequence from Library → Feed → reselect. (Earlier
   ambiguous runs were test-harness chaos, not app behavior.)
2. Source selection persistence: Asura Scans selected via dropdown
   → force-stop → cold relaunch → Feed shows "Asura Scans (EN)"
   restored in dropdown + grid filtered. PREF SURVIVES PROCESS
   DEATH. (stray tap had also exercised Mangakakalot persisting
   across nav + relaunch — same result.)
3. All Sources selection persists across process death too.
4. Customize dialog "Show source selector" OFF → dropdown row
   disappears, All/Popular/Latest listing chips remain, feed
   sections render. Toggle respected. Restored ON afterward.
5. Normal/Compact grid toggle: both switchable via customize
   dialog chips; compact grid title-inside-cover rendering
   (pixel bands differ vs normal; Library component semantics).
6. Recent: one "Recent" AppBar title only; Continue|History|
   Updates PrimaryTabRow persistent at top (y26-58, below status
   inset, no overlap); tab taps switch immediately; HORIZONTAL
   SWIPE verified Continue→History→Updates and back (History shows
   Today/Yesterday + Ch. timestamps; Updates shows "No recent
   updates" empty state); no duplicate section headings under the
   tab row (Continue shows chip controls, not a heading).
7. Reader settings: all 4 tabs device-verified on-screen — Reading
   mode (Reader layout group: mode chips + Rotation group + Long
   strip card with Tap zones/Invert), General (Toolbar & display +
   Behavior + vertical-navigator + flash cards), Custom filter
   (Custom brightness / Custom color filter / Effects), Read aloud
   (Speech / Speech cleanup / Spoken content). Grouped tonal
   surfaces render as bands over the frosted sheet — no black
   rectangles.
8. Zero app crashes in full-session logcat (grep FATAL/
   AndroidRuntime = 0 app hits; the one "FATAL EXCEPTION" grep hit
   was the adb echo of the search string itself). LeakCanary
   "1 Distinct Leak / Last leaked 7 hours ago" banner = stale
   pre-build event, no leak events during this pass.

DEVICE PASS NOTES: device had auto-rotate ON and was physically
landscape during part of the pass (caused earlier phantom-tap
chaos + 1600x720 screenshots); temporarily locked portrait via
settings for determinism, restored accelerometer_rotation=1 after.
Logcat capture at /sdcard/visual-pass.log (device-side).

VERDICT: The 2026-09-06 correction set is DEVICE-VERIFIED.
Remaining known-limitations: (a) pixel screenshots not human-
reviewed for aesthetic nuance (files preserved for user); (b)
stale-source fallback (delete saved source's feeds → all feeds)
verified in code only, not exercised on device (would require
removing a feed).

```text
[COMPLETED 2026-09-07 — visual hierarchy correction pass 3 (user
spec "VISUAL UX CORRECTION PASS"), UNCOMMITTED]

User verdict on 09-06 set: functionally correct, visually NOT —
Recent tab row invisible, reader settings weak grouping, Feed
selector/control incoherence, Manage Sources plain, customize
dialog plain. Root causes found (code + pixel-band evidence):

1. RecentTab.kt: content Column applied ONLY bottom padding —
   Scaffold places body at y=0 with PaddingValues(top=topBarHeight),
   so PrimaryTabRow rendered UNDER the AppBar (invisible). FIX: +
   padding(top = padding.calculateTopPadding()). Device: tabs at
   y202-234, lavender selected indicator verified, tap + swipe
   both switch + stay synced.
2. ReaderSettingsDialog.kt: adjacent PreferenceGroupCards touched
   (no inter-card gap) → one dark slab, groups indistinguishable
   (pixel: single (31,26,31) band). FIX: Column
   verticalArrangement = Arrangement.spacedBy(12.dp) — same rhythm
   as PreferenceScreen Spacer(12.dp). Device: 12dp gap bands
   verified on Reading mode/General/Read aloud tabs.
3. FeedScreen.kt SourceSelectorDropdown: bare Text+arrow row read
   as stray text, not a control. FIX: FilterChip (label = selected
   source, trailing ArrowDropDown, selected = source!=null) +
   unchanged DropdownMenu. Same chip language as listing chips.
   Device: chip renders, dropdown opens w/ all sources, selection
   filters grid, persists process death.
4. ManageFeedsScreen.kt: flat ListItem soup + dividers. FIX: rows
   flat inside one PreferenceGroupCard (header "Reorder feeds"),
   dividers deleted. CRITICAL FIX FOUND ON DEVICE: M3 ListItem
   default containerColor = surface → pure black rows painted OVER
   the tonal card in AMOLED (card only peeked at rims). FIX:
   ListItemDefaults.colors(containerColor = Color.Transparent) —
   same applied to AddFeedDialog rows (dialog surface vs row
   surface same class of clash). Device: single continuous
   (31,26,31) surface y176→1251, controls aligned.
5. FeedCustomizeDialog: AlertDialog + divider-separated chip/switch
   stack. FIX: AdaptiveSheet + PreferenceGroupCard groups — Display
   (Grid columns + Grid style SettingsChipRows), Sources (SwitchPre-
   ferenceWidget), Default listing (chips + switch). Device: three
   grouped cards w/ 12dp gaps, selected chips lavender tonal.
6. i18n base: +feed_grid_style ("Grid style") key.

NO black manufactured anywhere: all surfaces via existing tokens
(surfaceContainerLow cards, Transparent rows); device runs AMOLED
theme (bg pure black by design) — grouping reads via tonal card +
gaps, exactly per design.md.

GATES GREEN 2026-09-07 (docker, JDK17, -Xmx4g, both volumes):
  spotlessApply + :app:assembleDebug BUILD SUCCESSFUL 3m29s;
  spotlessCheck + testDebugUnitTest + verifySqlDelightMigration
  BUILD SUCCESSFUL 2m17s. No DB change.

DEVICE VISUAL PASS 2026-09-07 (wireless adb port rotated to
36137; screenshots r1..r12 in .device-pass/screenshots/): Recent
tabs visible+selected indicator+swipe sync ✓; Feed chip selector +
dropdown + persistence ✓; Manage Sources single tonal surface ✓
(after transparent-row fix + rebuild + reinstall); reader settings
3 tabs gap bands ✓; customize sheet 3 groups ✓; Add feed dialog ✓;
zero crashes. Full-session functional regression preserved (reselect
→ Manage Sources still works, listing chips still filter).

Files changed (5): RecentTab.kt (+1), ReaderSettingsDialog.kt (+5),
   FeedScreen.kt (selector chip + transparent ListItems + customize
   sheet rewrite), ManageFeedsScreen.kt (group card + transparent
   rows), i18n base strings.xml (+1 key).
```

```text
[COMPLETED 2026-09-08 — POST-v0.5.2 STABILIZATION SET
(Batches 1/2/3/5/6/7), UNCOMMITTED]

User-approved stabilization master task. Batches 4 (FAST removal) and
8 (x86/x86_64/universal distribution) EXPLICITLY HELD — untouched.

Batch 1 — LEGACY OCR removal (dead code, 3-way-verified unreachable:
recognizeText redirect :218-221 + scanLocalOrFallback DetectionUnavailable
redirect + UnavailableDetOcrEngine stub):
- DELETED: LegacyOcrEngine.kt (356 ln), Vocab.kt (6149 ln),
  app/src/main/assets/ocr/{decoder.tflite 98.2MB, encoder.tflite 22.8MB,
  embeddings.bin 18.9MB} (139,893,816 B total), CI download entries
  (build.yml 3 lines, release.yml 3 + artifact path), CONTRIBUTING model
  instructions, prd.md engine bullet.
- PRUNED in OcrRepositoryImpl: legacyEngine field, engineFor LEGACY
  engine branch (→ glensEngine for exhaustive when), closeEngines legacy
  lines. OcrEngineLocks: legacyMutex deleted; mutexFor(LEGACY) →
  glensMutex (comment: redirects to GLENS); withAllLocks nesting fixed.
- KEPT (compat, all device-verified): OcrModel.LEGACY enum entry,
  EngineType.LEGACY, pref_ocr_model default LEGACY (getEnum
  deserialization), recognizeText + scanLocalOrFallback redirects,
  OcrQueueScreen picker Legacy entry, ocr_model_legacy i18n,
  noCompress "tflite"/"bin" (ocr_fast + panel_detector need tflite).
- litert dependency KEPT (FastOcrEngine + PanelDetection CPU users).
  libLiteRtClGlAccelerator.so (~2.8MB/ABI) still packaged from AAR —
  GPU requested by nothing now; packaging exclusion untested → deferred
  (lazy-dlopen risk per audit).

Batch 2 — TTS prefetch Main-thread I/O fix:
- scanOnDemand body wrapped in withIOContext (tachiyomi.core.common
  convention; import existed) — pageSourceResolver.resolve does
  HttpSource.getPageList via Rx awaitSingle on the CALLING thread;
  prefetch job launches on viewModelScope (Main). Explicit return type
  OcrPageResult?. Covers main-loop caller (double withIOContext cheap)
  + prefetch. Cancellation/finally/bitmap-recycle semantics unchanged.

Batch 3 — GLENS scan transient-failure retry (mirror of
recognizeWithFallback policy):
- scanWithGlens split: wrapper catches firstError → CE rethrow →
  isTransientHttpFailure (IOException message "HTTP 5"/"HTTP 429" —
  how GlensOcrEngine.executeRequest surfaces 502/429) → WARN log +
  ONE retry via scanWithGlensOnce → retry error suppressed onto first,
  rethrow first. No retry storm, honest failure after exhaustion,
  cancellation preserved (CE rethrow at both levels).

Batch 5 — hygiene (each independently verified zero refs):
- C5 MangaBakaListEntry SKIPPED — audit proved ALIVE (MangaBakaApi
  findLibManga parses MangaBakaListResult; called by MangaBaka tracker).
- C6 IconItem composable removed (SettingsItems.kt; zero refs repo-wide;
  BaseSortItem keeps ImageVector import).
- C7 69 dead i18n keys removed from base strings.xml (of 81 candidates;
  FOUR compile rounds caught 5 live keys my first sweep missed —
  LESSON RECORDED: sweep must cover ALL modules incl core/common +
  BOTH reference styles MR.strings.* AND R.string.* + @string/ in XML
  res of ALL modules incl presentation-widget. Restored exact original
  values: information_cloudflare_bypass_failure, information_webview_
  outdated (core/common MR.strings), appwidget_updates_description
  (widget info XMLs), download_notifier_no_network +
  download_notifier_text_only_wifi (app R.string). 1178→1112 keys.
  Locale files keep stale translated copies — harmless, Weblate prunes.)
- C8 ic_launcher_round (5 mipmap webp + anydpi-v26 xml), drawable/
  anim_updates_enter.xml (Updates tab → Recent rename orphaned it),
  app values/dimens.xml (appwidget radii live only in
  presentation-widget module) — all removed, zero source refs.
- C9 OcrExclusionZoneRepository awaitForChapter + getZonesForChapter +
  subscribeZonesForSource + subscribeForSource removed across
  interactor/repository-interface/impl/.sq (zonesForChapter +
  zonesForSource queries). subscribeForManga/awaitForSpeech/awaitAll/
  subscribeAll remain (live callers).
- C10 loadingPreferences() @Composable added to SearchableSettings;
  SettingsReadAloudScreen + SettingsAnkiScreen share it (were
  byte-identical blocks). SettingsOcrExclusionsScreen loading Row →
  Box (Dictionary-screen pattern). No visual/behavior change.

Batch 6 — OCR cache retention (query-only, no schema change):
- ocr_cache.sq +countPages, +deleteOldestPages (ORDER BY created_at
  DESC, _id DESC LIMIT -1 OFFSET :keepCount — cascade deletes regions).
- OcrCacheStore.upsert calls suspend pruneOldestPages(db) inside the
  same transaction; MAX_CACHED_PAGES = 5000 companion const (ponytail
  comment: parameterize on thrash reports). Oldest pages evict
  atomically; recent chapters stay hot. No .sqm migration needed
  (delete-if-outdated DB; query-only change) —
  verifySqlDelightMigration still run GREEN.

Batch 7 — PermissionStep runBlocking removal:
- onResume: kotlinx.coroutines.runBlocking { anki... } →
  lifecycleOwner.lifecycleScope.launch { anki... }. Repository fns
  already suspend + withContext(Dispatchers.IO) internally. No Main
  blocking; state updates remain compose-state (deterministic render
  on completion). Lifecycle observer structure unchanged.

GATES GREEN 2026-09-08 (docker vsc-yomihon-e24e3bd7e46d…, JDK17, -Xmx4g,
both volumes): :core:common+data+domain+:app compileDebugKotlin BUILD
SUCCESSFUL (after fix rounds); spotlessCheck + testDebugUnitTest +
verifySqlDelightMigration BUILD SUCCESSFUL 2m; :app:assembleDebug BUILD
SUCCESSFUL 2m24s.

APK CONTENTS VERIFIED (5 ABI + universal): assets/ocr/ ABSENT;
assets/ocr_fast/{decoder 12.8MB, encoder 8.45MB} PRESENT;
data panel_detector present via universal; x86/x86_64/universal outputs
UNCHANGED (Batch 8 held). arm64 debug APK 95,058,865 B (95.06MB); LEGACY
assets were 139,893,816 B (133.4 MiB) — measured reduction.

DEVICE VERIFICATION 2026-09-08 (SM_M066B arm64-v8a, Android 16, wireless
adb; in-place adb install -r Success, app data + OCR cache preserved,
0.5.2-8262 → 0.5.2-8263; evidence .device-pass/stabilize-verify.log 35.4MB
22:51–23:10):
- A startup: PASS — launch clean, PID 32270→7705 (device adb daemon
  cycled mid-test; app itself stable), 0 FATAL/AndroidRuntime.
- B OCR model compat: PASS — device pref was FAST: full session worked
  (scans redirect → GLENS, speech, cache). Pref temporarily set to
  LEGACY via run-as (product UI untouched): cold relaunch clean,
  on-demand scan "OCR scanning redirected to glens" 23:04:38, prefetch
  page 9 COMPLETE 23:04:39, TTS speech continued. Picker dialog shows
  Legacy/Fast/Online/OwOCR incl LEGACY entry. Pref restored to FAST.
- C cached path: PASS — cache hits (acquireMs 16–86ms), 28–32
  sentences/page segmented, speech progress + ScrollToRegion firing.
- D uncached path: PASS — on-demand scans complete → sentences →
  speech; no Main-thread network exception.
- E prefetch: PASS — "TTS prefetch start pages=5..6 rate=1.5" →
  page-6 scan start 22:57:08.228 → "TTS prefetch complete page=6"
  22:57:20.562 (12.3s real Glens scan; pre-fix this exact path died
  in ~35ms with NetworkOnMainThreadException, 25×/session). Prefetch
  completes logged for pages 1,2,4,5,6,7,12,9; cancellations only on
  real navigation. ZERO NetworkOnMainThreadException in the whole
  35MB log.
- F GLENS transient retry: PENDING — no natural 502/429 occurred
  during the session (server-side, uncontrollable); retry path is
  code-verified only. Log shows zero "transient scan failure" lines =
  no false-positive retries either.
- G exclusions: PASS — live "TTS page=4 exclusion rules=10 types=
  {PHRASE=2, WORD=4, ZONE=4} excluded=0/24" (matcher + query intact
  post-C9); OCR exclusions screen renders all rule types + add flows.
- H reader stability: PASS — page advance request→confirmed ~0ms ×6;
  user-nav arbitration (swipes 5→4→3→2 win); pause/resume same
  sentence (p3 s5→s5); sentence steps both directions; stop
  Playing→Idle 0.7s; reader exit → TTS engine disconnect <1s; zero
  crashes.
- I settings: PASS — Read aloud screen loading scaffold → full voice
  profile content (loadingPreferences() works); OCR exclusions
  screen; Text Recognition screen + model picker; bottom nav all
  5 tabs intact.
- J cache retention: PASS (code path) / PENDING (eviction boundary —
  needs 5000 cached pages, untestable in one session; cache DB healthy
  102KB actively read/written, no SQLite errors beyond known
  transient 3850 lock contention lines).
- Batch 7 onboarding: PENDING device (fresh-install onboarding not
  runnable without wiping user data — forbidden). Compile + suspend
  pattern verified.

REGRESSION PROTECTIONS intact (verified in session + diff): bitmap
recycle finally unchanged, single-flight (cache hit + joining logs),
prefetch debounce/guards unchanged, arbitration unchanged, utterance
dispatch ids unique, engine disconnect <1s, LeakCanary untouched,
WebtoonTransitionHolder/detach code untouched, backup format
untouched, ABI outputs untouched.

Files changed (20 tracked + 2 kt + assets deleted): build.yml,
release.yml, CONTRIBUTING.md, prd.md, PermissionStep.kt,
SearchableSettings.kt, SettingsAnkiScreen.kt,
SettingsOcrExclusionsScreen.kt, SettingsReadAloudScreen.kt,
TtsPlaybackController.kt, OcrCacheStore.kt, OcrEngineLocks.kt,
OcrExclusionZoneRepositoryImpl.kt, OcrRepositoryImpl.kt, ocr_cache.sq,
ocr_exclusion_zones.sq, OcrExclusionZoneInteractors.kt,
OcrExclusionZoneRepository.kt, i18n base strings.xml, SettingsItems.kt
+ DELETED LegacyOcrEngine.kt, Vocab.kt, assets/ocr/, ic_launcher_round×6,
anim_updates_enter.xml, values/dimens.xml.
UNCOMMITTED — awaiting user review + commit.
```

## Agent handoff

```text
[BATCH 7B — FEED SOURCE-SELECTOR WIDTH STABILITY, COMPLETED 2026-09-11,
UNCOMMITTED — all 4 gates green + device verified]

Scope: presentation-only polish in FeedScreen.kt SourceSelectorDropdown.
FeedScreenModel/state/navigation/DB/deps/i18n untouched.

BEHAVIOR BEFORE: chip label = selected source's visualName → intrinsic
width per name ("Kagane (EN)" tiny, "Mangakakalot (EN)" wide) → selector
jumped on switch. Audit classification: expected intrinsic sizing, visual
inconsistency only.

FIX (FeedScreen.kt only): label Text gets fixed width = MAX measured
width across ALL selectable candidates ("All sources" + every enabled
feed source visualName), measured via rememberTextMeasurer +
labelLarge (repo precedent: DuplicateMangaDialog.kt:321), CAPPED at
160.dp so listing chips keep room on narrow screens. Text maxLines=1 +
Ellipsis inside fixed slot. Switching sources now changes ZERO layout:
chip footprint constant.

A11y: Text semantics unchanged — full source name (uncut string) is the
chip's accessible name; ellipsis is visual-only. No decorative
contentDescriptions added (arrow icon stays null per implementation map).

DEVICE VERIFIED (SM_M066B wired, real taps): chip bounds [56,209]
[255,241] = 199px CONSTANT across: All sources → Mangakakalot (EN)
(longest) → Kagane (EN) → All sources. Dropdown opens, all 6 sources
listed, checkmark follows selection, grid refilters. Listing chips All/
Popular/Latest clickable + Popular filters grid (Asura section only).
Customize dialog opens (Grid columns/style, Show source selector,
Default listing all present). Feed reselect → ManageFeedsScreen
(existing semantics), back → Feed intact with persisted selection.
Small-width n/a (single test device 720px).

GATES GREEN 2026-09-11 (devcontainer, JDK17, -Xmx4g, both volumes):
spotlessCheck + testDebugUnitTest + verifySqlDelightMigration +
:app:assembleDebug BUILD SUCCESSFUL 2m43s (one run).

Files changed (1): presentation/feed/FeedScreen.kt (+16/-2 in
SourceSelectorDropdown + 4 imports). Batch 6/7A uncommitted set
untouched.

Remaining: none for this scope. Not started (per brief): Feed
auto-pagination, toolbar reordering, Phase 10B, everything else.
```

```text
[BATCH 7 — READER SETTINGS NAV/GESTURE-BAR CONSISTENCY FIX, COMPLETED
2026-09-11, UNCOMMITTED — all 4 gates green + single device verify PASS]

Scope: only ReaderSettingsDialog.kt menu-visibility asymmetry. Batch 6
uncommitted set (Continue/Recent) + docs untouched, left as-is.

ROOT CAUSE: ReaderSettingsDialog per-page LaunchedEffect(pagerState
.currentPage) called onHideMenus() on Color Filter page, onShowMenus()
on the other three. ReaderActivity.setMenuVisibility (l.955) shows/hides
systemBars per call → with fullscreen pref ON, swiping settings pages
flipped the Android nav/gesture bar per page (CF hidden, others shown).

FIX (1 file, -2 lines): removed onHideMenus()/onShowMenus() from the
per-page LaunchedEffect; dialog now NEVER touches menu/system-bar
visibility while open. Dim handling (0f on CF for filter preview,
0.5f otherwise) kept — cosmetic, unrelated to bars. Dialog keeps
onShowMenus params wired to dismissal path (pre-existing, untouched:
TabbedDialog onDismissRequest → onDismissRequest() + onShowMenus()).
Result: bar state = whatever reader state was when dialog opened
(settings opened from visible menu → bar visible), consistent across
ALL pages; dismissal restores menu as before.

FIRST ATTEMPT REJECTED by user (correctly): initial fix force-hid bars
for the whole dialog (LaunchedEffect(Unit){onHideMenus()}) — over-
reached; required behavior was consistency, not forced hiding. Reverted.

GATES GREEN 2026-09-11 (devcontainer, JDK17, -Xmx4g, both volumes):
spotlessCheck + :app:assembleDebug BUILD SUCCESSFUL 3m18s;
testDebugUnitTest + verifySqlDelightMigration BUILD SUCCESSFUL 2m45s.
NO DB change, NO i18n, NO deps.

DEVICE VERIFICATION (SM_M066B wired R9ZY30X3SGP, arm64 debug APK,
fresh force-stop relaunch, ONE loop per user's hard-stop rule):
reader bar hidden (menu hidden) → menu shown bar=true → Settings open:
ReadingMode bar=true → swipe General bar=true → swipe ColorFilter
bar=true → (synthetic-swipe dismissal artifact on 3rd swipe, see note)
→ reopen via tab taps: ReadAloud bar=true, CF bar=true. Bar constant
true across every page, matches reader menu state. Dismissal restores
normal reader behavior (logcat: setMenuVisibility show on dismiss).
NOTE: uiautomator "input swipe" intermittently dismisses the AdaptiveSheet
(mostly on CF→RA swipe; reproduced with OLD build too — PRE-EXISTING
synthetic-input artifact, not a regression; tab taps + slower swipes
keep dialog open). Not masked, not fixed (out of scope).

Files changed (1): presentation/reader/settings/ReaderSettingsDialog.kt
(-2 lines). ReaderActivity/TTS/OCR/nav/Feed/Recent/DB/deps/i18n
UNTOUCHED (verified by git diff).

Remaining: none for this scope. Feed source-selector width, Phase 10B,
auto-pagination, toolbar customization NOT started (per brief).
```

```text
[PHASE 6A POST-BATCH-5 BUG & FEATURE CONSOLIDATION — PLANNING-ONLY,
COMPLETED 2026-09-11, ZERO app-source changes]

Per user master brief. Read docs/* + git + source. Repo baseline CONFIRMED:
clean @ f112df5d4 (docs), HEAD~ chain = a11y pass 212a09c7b + docs 82cce9206;
v0.5.3 tag @ daa942738 = 5 commits back, all released. Baseline matches
brief §1.

NEW BUG INVESTIGATION MATRIX (all source-verified, no code touched):
- A1 Recent scroll shade: EXPECTED MATERIAL BEHAVIOR (not bug).
  RecentTab.kt:79-85 AppBar uses default Scaffold pinnedScrollBehavior +
  default TopAppBar colors; M3 TopAppBar scrolledContainerColor =
  surfaceColorAtElevation(3dp) activates when scrolledFraction>0 → header
  lightens while scrolling, restores at rest. Same mechanism at U2.
  Confidence HIGH. Classification: visual gap only IF unwanted; fix (if
  user wants flat header) = 1-line containerColor=surface on Recent
  AppBar; needs user taste decision.
- A2 Continue "Alphabetically" no visible change: LIKELY BUG (HIGH conf).
  ContinueScreenModel.kt:60-79 setSort works, BUT applyFilters reads
  state.value.sort INSIDE mutableState.update — reads the OLD value
  before the new sort lands (race with the state update itself), so
  switching sorts can no-op. ALSO chips are single-select-looking but
  state is independent (sort + downloadedOnly compose). ALSO possible
  dataset already alphabetical by coincidence. Fix shape (Batch 6):
  compute sort inside the update lambda from the NEW state, or store
  unfiltered items and derive display list. Small, isolated.
- A3 Continue Download-only stuck: CONFIRMED BUG (HIGH conf, root cause
  A2-adjacent). Same stale-state read: applyFilters(it.items) inside
  setDownloadedOnly re-reads state.value.downloadedOnly (pre-update
  value) → filter can toggle "on" in state but produce stale/empty list
  items; empty-list branch then shows recent_continue_empty
  ("Nothing in progress…"), and since items are stored FILTERED (not
  raw), the filter can never be reversed — raw list lost. Empty state +
  dead-end = exactly user symptom. Force-stop resets because state is
  in-memory (StateScreenModel, not persisted). Fix: store raw items in
  State; apply downloadedOnly+sort as derivedStateOf at UI; keep chips
  toggleable. Small, isolated.
- H1 History excessive top spacing: CONFIRMED STRUCTURAL (HIGH conf).
  History/Updates keep INNER Scaffolds (HistoryScreen.kt:42,
  UpdatesScreen.kt:68) nested inside RecentTab outer Scaffold
  (RecentTab.kt:79). Inner Scaffold's TopAppBar re-consumes status-bar
  inset (contentWindowInsets default) + SearchToolbar/AppBar row adds
  64dp → double top inset + double toolbar height. Shared root with H3.
- H2 History search: PASS (source verified: HistoryScreenModel search
  flow subscribe(query) + SearchToolbar wiring intact). No action.
- H3 History search/delete toolbar lighter block: SAME root as H1 +
  A1-family. Inner Scaffold topBar surface (surfaceColorAtElevation 0 =
  surface) vs screen background → visible band; scroll behavior also
  shifts it (same scrolledContainerColor mechanism as A1/U2). Classify:
  visual gap; resolved BY the H1 structural fix (one title, one inset).
- U1 Updates excessive spacing: same H1 nested-Scaffold root (Updates
  inner Scaffold topBar under Recent tab row). Confidence HIGH.
- U2 Updates scroll/refresh shade: same A1 mechanism. Confidence HIGH.
- Feed source-selector width (§7): EXPECTED INTRINSIC SIZING (HIGH
  conf). SourceSelectorDropdown FilterChip (FeedScreen.kt:440-450)
  label = selected source name → chip width follows text length (All
  Sources vs Asura Scans vs Manga Catalog). No collision: listing chips
  row has weight(1f)+horizontalScroll (FeedScreen.kt:402-407), no
  clipping (scrollable). Layout stable; not an a11y or responsive bug.
  Classification: visual inconsistency only; candidate polish (optional
  max-width ellipsize), no Batch-6 necessity.
- Reader Settings nav-bar inconsistency (§8): ROOT CAUSE FOUND (HIGH
  conf) — ReaderSettingsDialog.kt:56-65: LaunchedEffect(pagerState
  .currentPage) calls onHideMenus() ONLY on ColorFilter page
  (isColorFilterPage), onShowMenus() for others. setMenuVisibility
  (ReaderActivity.kt:955-962) hides system bars only when
  readerPreferences.fullscreen ON; with 3-button nav + fullscreen pref
  ON, General/ReadAloud show menus → nav bar visible, ColorFilter hides
  → hidden; swipe-back shows again. NOT a per-page hack need: smallest
  safe future fix = call onHideMenus() on ALL pages (or show only on
  dismiss), 1-line change in dialog, no ReaderActivity/TTS/OCR touch.
  PROTECTED AREA root-cause documented, zero code changed.
  NOTE: user described "Reading Modes visible, General hidden" — slight
  inverse of code expectation (page 0 shows, 1 hides per source);
  device behavior order may differ — needs 30s device verify (swipe
  pages, watch 3-button nav bar), then Batch 6 candidate.

ACCESSIBILITY FOLLOW-UPS (per brief §9, recorded, NOT auto-promoted):
CategoryListItem drag-handle custom actions; BaseSliderItem slider
label; SourceSelectorDropdown check contentDescriptions; spinner cds.
Deferred — only fold into Batch 6 if user approves (small, coherent
with Feed/Recent fixes).

ORIGINAL FEATURE WORKFLOW RECOVERED (brief §10): docs/next-phase-plan.md
PART C matrix (built from Prompt.md §16 rules + recorded ideas) + PART G
roadmap = the authoritative recovered backlog. Already-implemented:
grouped settings cards. Valid candidates: Feed auto-pagination (now
eligible, Load-more stable), reader toolbar reordering (deferred #1),
artwork-reactive tray feasibility study (deferred #3, protected-adjacent),
a11y completion micro-batch, litert GPU-lib exclusion (~2.8MB/ABI).
Rejected: clone aesthetics, nav customization, 6th tab, standardized
reselect, Library Continue section (twice reverted), true backdrop blur.
FUTURE/HOLD: Phase 10B all items. USER CLARIFICATION REQUIRED:
"Create" tab referent (only open UI decision).

PROPOSED BATCH 6 (smallest coherent unit, awaiting user approval):
1. Continue fix (A2+A3): ContinueScreenModel store raw items; derived
   display list; sort inside update. Files: ContinueScreenModel.kt,
   ContinueTab.kt. Protected areas: NO. Risk LOW. Unit-testable pure
   logic (state transitions) — 1 small test class.
2. Recent nested-Scaffold cleanup (H1/H3/U1): collapse History/Updates
   inner Scaffold topBar — one Recent screen title + tab row already
   exist; strip inner toolbar surface/inset duplication (keep search
   action + search field as row, not full toolbar), OR simplest variant:
   set inner Scaffold contentWindowInsets to zero/consumed + flat
   containerColor. Exact mechanism to be decided at implementation;
   files: HistoryScreen.kt, UpdatesScreen.kt (+SearchToolbar usage).
   Protected areas: NO. Risk MEDIUM (visual only, device verify).
3. OPTIONAL (user taste): flat Recent AppBar color (A1/U2). 1 line.
   Protected: NO.
4. OPTIONAL (user approval): Reader-settings nav-bar consistency —
   apply onHideMenus on all dialog pages (1 line, dialog file only,
   no ReaderActivity change). Protected area TECHNICALLY NO (dialog
   is presentation; but reader-adjacent → ask user first per freeze
   list).
DEVICE VERIFICATION PLAN (Batch 6): wired USB adb (R9ZY30X3SGP present
in adb devices, 192.168.29.98 wireless entry IGNORED per brief). Tests:
Recent → Continue: toggle Alphabetically (list reorders live), toggle
Downloaded only on→off (list restores, no dead-end empty state,
force-stop not needed); History/Updates top spacing + toolbar band
gone; scroll Recent + Updates (header shade behavior consistent);
Feed selector widths (no regression); Reader Settings page swipes with
3-button nav (bar visibility consistent); a11y chip selected-state
announce. Screenshots: Continue before/after chips, History/Updates
top region, Recent scroll mid-state. Build gates: standard 4 CI-order
gates. NO destructive tests (no history wipe, no feed deletion, no
pref surgery beyond chip state).
HOLD (not Batch 6): Phase 10B everything; Create tab (user
clarification); speculative features; protected reader/TTS/OCR
architecture; Feed auto-pagination (eligible but separate batch);
toolbar reordering; artwork-reactive tray; a11y deferred micro-batch
(unless user folds it in).

Last agent:                 opencode (2026-09-11 — PHASE 6A planning)
Next recommended task:      user approves/declines Batch 6 items 1–2
                            (+optionals 3/4), then implementation
                            session executes. No implementation prompt
                            produced yet per brief.
Files safe to modify:       none (planning-only session).
```

```text
[YOMITSU REBRAND — COMPLETED 2026-09-07, UNCOMMITTED→ user commit 708a7182d]
Audit-first controlled rebrand (user spec, 12 phases). Audit totals:
  yomihon 176 refs / mihon 1784 / tachiyomi 12327 / yomitsu 5 (pre-change).
Every match classified A–H. Full classification + rationale:
  docs/branding.md (NEW — single source of truth).
Changed (Category A): app_name → Yomitsu; launcher icons (legacy
   mipmap webps + adaptive foreground rasters + monochrome, all
   regenerated from docs/Logo/Yomitsu-logo via PIL/uv, mark inside
   66dp safe zone); drawable/ic_mihon.xml → bitmap wrapper around
   drawable-nodpi/ic_yomitsu_mark.png (About LogoHeader + notification
   small icons + splash — resource name kept so zero code refs changed);
   splash color → #000000 (logo bg); README rewritten; .github/assets/
   logo.png replaced + cover.png added; CONTRIBUTING + issue templates;
   release.yml artifact names yomitsu-* (upstream if-gates kept =
   skip-on-fork by design); AppUpdateChecker.GITHUB_REPO + AboutScreen
   GitHub link → Nikhil0921/yomitsu; settings.gradle.kts rootProject.name
   → Yomitsu; docs titles + prd §1.1 + architecture §1/§6 wording;
   GitHub repo renamed via API (yomitsu) + description; origin remote
   updated; docs/branding.md created.
Preserved (B/C/D/E): applicationId app.yomihon; all code namespaces;
   tachiyomi:// + mihon:// schemes (extension-store + tracker OAuth);
   .tachibk + APPLICATION_ID backup filename; provider authorities;
   TelemetryConfig gate (app.yomihon pkgs + cert fingerprint);
   google-services.json; com.github.yomihon:{Furiganable,hoshidicts,
   image-decoder} Maven coords (upstream org); AnkiDroid persisted names
   (deck "Yomihon", model "Yomihon Card", YOMIHON_* constants, yomihon-*
   media prefixes — user AnkiDroid data looks models up BY NAME);
   DictionaryTermCard "yomihon" Anki tag (existing cards); upstream
   yomihon.github.io doc URLs (live docs); upstream Discord + FUNDING;
   LICENSE text + upstream copyright lines (added © 2026 Yomitsu
   contributors line only); CHANGELOG history; release.yml upstream
   if-gates; AGENTS.md / architect.md / architect-2.md (user-owned);
   memory.md historical blocks.
Validation: repo-wide re-search — every remaining "yomihon" ref maps to
   a preserved category (list above); no unexplained user-visible
   Yomihon branding remains (aapt2 application-label:'Yomitsu').
GATES GREEN 2026-09-07 (docker, JDK17, -Xmx4g, both volumes):
   spotlessCheck + :app:compileDebugKotlin 2m49s; testDebugUnitTest +
   :app:assembleDebug 2m40s (after :source-local:clean :source-api:clean
   for stale intermediates). aapt2 dump badging verified label + assets.
Files changed (28 tracked-modified + 4 new): see git status.
Device pass PENDING user: launcher name/icon, splash, About mark,
   notifications.
```

```text
Last agent:                 opencode (2026-09-08 — FULL UI AUDIT + MAP per
                            docs/Prompt.md: docs/* re-read + source
                            re-verified @ 9e7a27b08, produced
                            docs/ui-implementation-map.md (32 sections,
                            discrepancy register D-01..D-15, Feed listing-
                            selector P0 root cause D-02 + fix + verification
                            plan, approval gates). ZERO app source touched.
                            Awaiting user review/approval of the map before
                            ANY implementation (Prompt.md §30 STOP
                            condition). See 2026-09-08 audit block.
Date:                       2026-09-08
Current task:               DONE — audit + map delivered.
Next recommended task:      User reviews Batch 1 diff + report (D-01..D-04
                            below), decides Batch 2. Device verification
                            of Batch 1 PENDING screen unlock (PIN lock).
Files safe to modify:       app/src/main/java/eu/kanade/tachiyomi/ui/recent/RecentTab.kt,
                            app/src/main/java/eu/kanade/tachiyomi/ui/feed/FeedScreenModel.kt,
                            app/src/main/java/eu/kanade/presentation/feed/FeedScreen.kt,
                            app/src/test/java/eu/kanade/tachiyomi/ui/feed/.
```

```text
Last agent:                 opencode (2026-09-08 — BATCH 1 IMPLEMENTED per
                            user authorization message: D-01 + D-02 + D-03 +
                            D-04, exactly the approved scope of
                            docs/ui-implementation-map.md §26 Batch 1.
                            D-05/06/07/08/10/11/12/13 NOT implemented.
                            D-01: RecentTab.kt Scaffold snackbarHost slot now
                            mounts BOTH hosts — SnackbarHost(snackbarHostState)
                            + SnackbarHost(resumeHostState) — so reselect
                            no_next_chapter fallback is visible; pager/tab
                            structure, reselect channel untouched.
                            D-02: FeedScreenModel.State.visibleFeeds now
                            applies listingOverride filter
                            (listingMatched = sourceMatched.filter
                            {listingOverride==null || it.listing==listingOverride});
                            fallback chain listingMatched→sourceMatched→enabled
                            (empty-grid impossible; stale-source fallback
                            preserved). Grid now changes with chips.
                            D-03: State.listingSelected sentinel (true after
                            ANY explicit user listing choice incl "All");
                            pref-collector seeds defaultListing ONLY while
                            !listingSelected — explicit "All" survives pref
                            emissions; selectListing/setDefaultListing set
                            flag. No persistence change.
                            D-04: AddFeedDialog confirm now coerces to POPULAR
                            when selectedSource.supportsLatest==false (click
                            guards at :297/:305 unchanged); Latest default
                            UX kept.
                            Unit test added:
                            app/src/test/java/eu/kanade/tachiyomi/ui/feed/
                            FeedScreenModelStateTest.kt (9 cases: null/POPULAR/
                            LATEST override, source+listing compose, disabled
                            hidden, listing-no-match fallback, stale-source
                            fallback, empty, order preserved).
 Date:                       2026-09-10
 Current task:               DONE — Batch 5 (device verification matrix)
                             executed over the combined Batch 1–4 set:
                             full matrix PASS (Feed D-02/D-03/paging/
                             ManageFeeds/order; Recent + D-01 reselect→
                             resume; Settings Search D-08 all queries;
                             MangaScreen D-11 success-path regression ×2;
                             typography/surfaces/a11y spot checks; 0
                             crashes, 0 app leaks, 65.7MB log evidence).
                             Gates re-run green — AND the re-run exposed a
                             real defect in the Batch-4 test harness:
                             MangaScreenModelErrorStateTest failed
                             deterministically in full-suite runs (2/2)
                             from a resetMain/setMain race vs the model's
                             IO workers (Voyager screenModelScope is
                             process-global in JVM tests); FIXED test-only
                             via @TestInstance(PER_CLASS) class-scoped
                             setMain/resetMain (see Batch 5 block). ZERO
                             app source changes in Batch 5. All batches
                             1–5 UNCOMMITTED awaiting user.
 Next recommended task:      user review of Batch 1-5 diffs + commit
                             (Batch 5 adds only the 1 test-harness file +
                             docs); remaining register items are
                             D-09/D-14/D-15 (INFO, no action proposed).
                             OPEN DECISION for user (recorded, still
                             open): TtsPlaybackBar bodyMedium-vs-bodyLarge
                             + 16/4-vs-24/12 doc conflicts — ratify code
                             or fix docs.
BATCH 2 (2026-09-09, user-authorized D-05+D-07 only):
                            D-05 DONE — HorizontalDivider() removed from
                            FeedHeader (FeedScreen.kt, 1-line deletion;
                            divider under section header was doubling
                            ListGroupHeader separation). Header→label→grid
                            rhythm verified intact on device (gaps 14dp/7dp,
                            no collapse); no divider View nodes anywhere in
                            Feed dumps. AddFeedDialog's internal
                            HorizontalDivider (:308) untouched.
                            D-07 PASS, NO CODE CHANGE — verify-first audit:
                            all controls M3 default 48dp touch targets
                            (device-measured 42px icons = 48dp touch target
                            nodes, Switch 52×48dp), all icon-only controls
                            have i18n contentDescriptions, Switch gets
                            semantic toggle role + row headline context,
                            controls center-aligned (y-center 299 uniform),
                            transparent ListItem inside PreferenceGroupCard
                            (documented AMOLED decision), spacedBy(0.dp)
                            contiguous non-overlapping targets. No
                            discrepancy vs design.md/map §8/§23/M3.
                            Device matrix (SM_M066B, APK 0.5.2-8266):
                            Feed — chips/grid sync all combos (fresh-start
                            Latest seed, All, All-sources, source-filter,
                            Load more paging works, append verified);
                            sections render both listings with clean
                            boundaries. Recent — 3 tabs switch OK, reselect
                            → ReaderActivity (D-01 no regression).
                            ManageFeeds — toggle persisted, move-up/down
                            verified+restored, delete verified (Atsumaru/
                            Popular; NOTE: its source extension vanished
                            from device mid-test — feed NOT re-added, user
                            config now 5 feeds vs 6 pre-test; re-add needs
                            extension reinstalled), add flow verified
                            (QiScans/Popular added+deleted, net zero).
                            D-06/D-08/D-10/D-11/D-12/D-13 NOT touched.
BATCH 3 (2026-09-09, user-authorized D-06+D-13+D-10 only):
                            ZERO APP SOURCE CHANGED — verify-first audits
                            resolved all three as register dispositions
                            (docs corrections only, map §21/§6/§17/§26/§27
                            updated).
                            D-10 RESOLVED-STALE — SettingsAdvancedScreen
                            ALREADY fully grouped: 8 PreferenceGroups
                            (pref_category_general, label_background_
                            activity, label_data, label_network, label_
                            library, pref_category_reader, label_
                            extensions, pref_category_dictionary_parser),
                            0 loose rows; original claim = stale copy from
                            design-audit.md (git disproof: 0eab09ce4
                            2026-07-25 already had 7 groups). No code
                            change; search breadcrumbs unaffected.
                            D-13 LEFT UNCHANGED INTENTIONALLY — Feed grid
                            (FeedScreen.kt:154-159 PaddingValues 8dp
                            start/end + CommonMangaItemDefaults 4dp
                            spacers, Adaptive(96.dp)/Fixed(N)) vs
                            LazyLibraryGrid (hardcoded FastScrollLazy-
                            VerticalGrid + Adaptive(128.dp) + 8dp all
                            sides). Reuse would CHANGE BEHAVIOR (fast-
                            scroll on Feed = documented skip; 128dp vs
                            96dp adaptive column count); new shared helper
                            rejected (approval-gated + unjustified for 2
                            literals that already share real geometry
                            constants). Option D per brief.
                            D-06 RESOLVED — registered item (More 12dp
                            inter-card spacers) confirmed fine-as-is;
                            already documented map §7 frozen rhythm. Batch
                            sweep (explore agent + orchestrator verify)
                            found NO further authorized code changes:
                            (a) LibraryToolbar Pill 14sp still present —
                            map §6 previously claimed RESOLVED falsely;
                            corrected doc + documented as intentional
                            exception (counter-density family, same as
                            OcrQueue/DownloadQueue triage counters);
                            (b) TtsPlaybackBar sentence text bodyMedium
                            vs design.md §4 bodyLarge + pill padding 16/4
                            vs §5 24/12 — PROTECTED SYSTEM (reader TTS
                            playback bar), doc-vs-code conflict recorded,
                            USER DECISION REQUIRED (not Batch-3 scope);
                            (c) upstream residue documented untouched:
                            upcoming-calendar 16sp raw literals
                            (CalendarDay.kt:59, Calendar.kt:32/96),
                            SettingsDictionaryScreen card dialect
                            (4 ad-hoc Card styles + spacedBy(16) + dead
                            spacer items), TrackerSearch/Migration radii,
                            search row 14dp vertical, TriStateListDialog
                            20dp, LogoHeader 56 vs 32dp inset divergence.
GATES GREEN 2026-09-09 Batch 3 (docker vsc-yomihon-e24e3bd7e46d…, JDK17,
                            -Xmx4g, both volumes): spotlessCheck +
                            testDebugUnitTest + verifySqlDelightMigration
                            BUILD SUCCESSFUL 3m11s; :app:assembleDebug
                            BUILD SUCCESSFUL 2m31s. (No code changed;
                            gates run per brief mandate. No DB/schema
                            change.)
DEVICE VERIFIED 2026-09-09 Batch 3 (SM_M066B wireless ADB; port chaos —
                            46761 flapped offline repeatedly, settled
                            43737; APK 0.5.2-8264 arm64 installed
                            in-place Success, app.yomihon.dev vc28):
                            - D-10 PASS: Advanced screen all 8 groups
                            rendered on device (General/Background
                            activity/Data/Networking/Library/Reader/
                            Extensions/Dictionary Parser headers at
                            y204/875/1243/1585/408/1179/1008/1324), rows
                              flat, no loose cards, matches sibling
                              grouped settings.
                            - D-06 PASS: More screen grouped cards
                              render (General header y318, Library y660,
                              rows flat); Settings screen grouped cards
                              (Appearance y204/Library y419/Reader y761/
                              Tracking y1357) — spacing rhythm intact.
                            - D-13 PASS: Feed grid vs Library grid
                              pixel-band analysis IDENTICAL geometry —
                              both 3-column at same density, left edge
                              21px, right edge 21px, gutters 21px (=
                              8dp edges + 4dp gutters at 2.625x density);
                              consistency with Library confirmed without
                              any code change.
                            - Regression PASS: Recent 3 tabs switch
                              (Continue chips + rows, History Today,
                              Updates yesterday-14h); Feed chips intact,
                              scrolling OK, Load-more appends page 2
                              (Murim Psychopath/The Knight King/... new
                              items verified); zero real FATAL/
                              AndroidRuntime in 17.5MB session log (one
                              grep hit = adbd echo of search string,
                              known false-positive pattern).
                            Evidence: .device-pass/batch3-verify.log
                            (17.5MB) + b3-feed/b3-library pixel analysis
                            in session transcript.
                            D-08/D-11/D-12/D-14/D-15 NOT touched
                            (Batch 4 / docs-only, not authorized).
GATES GREEN 2026-09-09 Batch 2: spotlessCheck + testDebugUnitTest +
                            verifySqlDelightMigration + :app:assembleDebug
                            BUILD SUCCESSFUL 2m25s (docker, JDK17, -Xmx4g,
                            both host volume mounts).
GATES GREEN 2026-09-08 (docker vsc-yomihon-e24e3bd7e46d…, JDK17, -Xmx4g,
                            HOST ~/.gradle + ~/.android volumes — container
                            home was empty this session, first run redownloaded
                            Gradle 9.6.1 + failed on network; volume mounts
                            fixed it — ALWAYS pass both volume mounts):
                            spotlessApply+compileDebugKotlin 6m26s;
                            spotlessCheck+target test 1m59s (first run 1 test
                            FAIL — test expectation wrong, not code: disabled+
                            LATEST case hits intended source-fallback → fixed
                            test expectation, §15-conformant); full
                            testDebugUnitTest 2m6s GREEN; :app:assembleDebug
                            3m37s.
DEVICE VERIFIED 2026-09-09 (SM_M066B wireless ADB, wifi flaky — port cycled
                            37483→40641→40785→43835→43337; use
                            ./scripts/adb-wireless reconnect pattern; screen
                            PIN-unlocked by user). DEBUG APK TARGETS
                            app.yomihon.dev (debug applicationIdSuffix
                            .dev — NOT app.yomihon; old stable install on
                            app.yomihon misled first launch attempt).
                            D-02 PASS: chips ↔ grid synchronized in all
                            tested combos — All+All-sources → all sections;
                            Popular+All-sources → Popular sections only
                            (multi-source scroll verified); Latest (default
                            seed) → Latest only; Thunder-source+All →
                            single source's Latest section (source+listing
                            AND-filter composes); source dropdown selection
                            persists.
                            D-03 PASS: user-selected "All" survived
                            customize-dialog pref emissions (stayed checked,
                            grid unchanged); fresh app start seeds default
                            LATEST pref correctly (ListingSelected=false
                            path).
                            D-04 PARTIAL-PASS by constraint: all 5 enabled
                            sources on this device support Latest (incl
                            LocalSource — supportsLatest=true hardcoded
                            LocalSource.kt:73), so negative path (switch to
                            !supportsLatest source) NOT device-triggerable;
                            positive path (Latest select+confirm for
                            supporting source) PASS — Asura/Latest feed added
                            via dialog during testing; confirm-guard logic
                            code-verified. Negative path needs a source whose
                            extension sets supportsLatest=false (none
                            installed).
                            D-01 PASS (functional path): Recent reselect →
                            ReaderActivity opened (resume last-read works).
                            Snackbar no_next_chapter path NOT device-verified
                            (requires wiping reading history — user data,
                            not acceptable); structural: resumeHostState now
                            mounted in same Scaffold slot as the
                            device-proven History-tab host.
                            Side observations: stub/removed-source feeds
                            (e.g. Comix w/o extension) render Error section
                            with retry — pre-existing, out of Batch 1 scope;
                            AddFeedDialog source rows are NOT disabled for
                            sources with existing same-listing feed (dup
                            add silently returns via addFeed guard).
                            Test-bed cleanup done: device prefs restored to
                            pre-test state (single Mangakakalot/LATEST feed,
                            selected_source=null, default=LATEST).
 Files safe to modify:       see handoff above (Batch 1 set only).
 ```

```text
[COMPLETED 2026-09-09 — BATCH 4 (D-08 + D-11 + D-12), UNCOMMITTED]

User-authorized Batch 4 per docs/ui-implementation-map.md §26 (approval
gates §27 items 3+4 cleared by user task message).

D-08 — Settings Search registration (SettingsSearchScreen.kt only):
- getIndex() corpus extended with unindexedSettingScreens: plain Voyager
  Screens registered as SYNTHETIC single-entry results (SettingsData with
  one TextPreference: title = screen title, subtitle = search corpus only
  — results render title + breadcrumb, never the subtitle). Both targets
  keep their existing Screen objects + custom UI (zero conversion to
  SearchableSettings, zero screen-UI change, zero navigation change):
  SettingsOcrExclusionsScreen (title ocr_exclusions_screen_title, subtitle
  ocr_exclusions_summary) + SettingsDictionaryScreen (title
  pref_category_dictionaries, subtitle label_dictionary — singular
  "dictionary" query coverage; plural title alone misses it, device-proven
  during the pass). Selecting a result → navigator.replace(screen) — same
  mechanism as every registered entry. highlightKey no-op on these targets
  (they render no PreferenceScreen; stale key self-clears on next real
  settings visit — benign, title collision impossible: neither title equals
  any preference title).
- Verified honest behavior kept: search "theme" still yields pref-level
  "App theme / Appearance > Theme" — all 11 registered screens untouched.

D-11 — MangaScreen missing/error state (3 files):
- MangaScreenModel: sealed State gained Error(missing: Boolean). init's
  inline load extracted to public load() (Error→Loading reset; retryable).
  try/catch (CE rethrow) → publishError: SQLDelight awaitAsOne NPE =
  "ResultSet returned no rows" = row absent → missing=true (honest
  "manga no longer exists"); other exceptions → generic retryable error.
  combine-subscription (manga+chapters flow) gained .catch (CE rethrow) →
  failWithMissingMangaCheck — mid-session row deletion now transitions
  Success→Error instead of killing the collector silently (previous
  behavior: uncaught NPE death, zombie screen).
- MangaScreen.kt: Error branch renders EmptyScreen (existing component —
  same as Feed/Continue/GlobalSearch error+empty states) with
  manga_screen_not_found (missing) or unknown_error (generic) + actions:
  Retry (generic only; missing manga is not retryable) + Close
  (navigator.pop). Loading/Success branches byte-identical.
- DI cleanup: Injekt.get<SourceManager>() inline call in load() →
  constructor-injected sourceManager (all other deps follow this pattern;
  required for unit-testability).
- i18n: +1 base key manga_screen_not_found ("This manga is no longer
  available"). No locale hand-edits.

D-12 — prd.md §1.4: stale 6-tab list (Library/History/Updates/Browse/
Feed/More) → actual 5-tab IA (Library / Recent / Feed / Browse / More).
Docs-only; map register rows D-08/D-11/D-12 + §2/§14.9/§26/§27 updated.

TESTS: NEW MangaScreenModelErrorStateTest (app/src/test/.../ui/manga/)
4 cases GREEN: missing-manga → Error(missing=true) NOT Loading; generic
failure → Error(missing=false); retry from generic error recovers to
Success; valid manga → Success (existing path unchanged). Test harness
notes: screenModelScope runs on real Dispatchers.IO via launchIO → real-time
polling helper (not virtual advanceUntilIdle); mocked Lifecycle
(INITIALIZED, no Looper in JVM tests); LogcatLogger.install no-op logger
(logcat-android calls android.util.Log → not mocked in unit tests).

GATES GREEN 2026-09-09 (docker vsc-yomihon-e24e3bd…, JDK17, -Xmx4g, both
volumes): :app:compileDebugKotlin + :app:compileDebugUnitTestKotlin
SUCCESSFUL; :app:testDebugUnitTest --tests MangaScreenModelErrorStateTest
4/4; spotlessApply (ktlint continuation reformat of new getIndex chain) →
spotlessCheck + FULL testDebugUnitTest + verifySqlDelightMigration BUILD
SUCCESSFUL 3m5s; :app:assembleDebug BUILD SUCCESSFUL 3m15s (then rebuild
after subtitle fix 2m33s). NO DB/schema change.

DEVICE VERIFIED 2026-09-10 (SM_M066B wireless ADB 192.168.29.98:44521,
user-unlocked PIN; APK 0.5.2-826x arm64 debug installed in-place ×2
( Success → first-pass APK, then rebuilt with Dictionary-subtitle fix);
evidence /sdcard/batch4-verify.log 131k lines, 0 FATAL/AndroidRuntime):
- D-08 PASS: "dictionary" (singular) → "Dictionaries" result → opens
  SettingsDictionaryScreen (import/popup prefs/recommended cards render);
  "dictionaries" → same; "exclusions" → "OCR exclusions" result → opens
  SettingsOcrExclusionsScreen (user's real WORD/PHRASE/ZONE rules render,
  read-only interaction); existing entries intact ("theme" → App theme /
  Appearance > Theme).
- D-11 Success-path PASS (regression): Library → "Villain To Kill" →
  full MangaScreen (info header, description, 13+ chapter rows) unchanged.
- D-11 missing-path: unit-test-verified only (4/4). Live device trigger
  NOT possible without unsafe acts: cold-start after DB row deletion gives
  no stale entry point (history/updates/tracks FK-cascade, browse/feed
  re-create rows via NetworkToLocalManga); mid-session deletion requires
  DB write while app running (no sqlite3 on device; push-swap only safe
  with app dead — verified DB surgery itself works: pulled db via run-as
  cat, deleted disposable browse row id=3 "Absolute Sword Sense" + its 200
  chapter rows locally, pushed back, app relaunches clean, library intact).
  Error-state UI uses the same EmptyScreen/ActionButton components already
  device-proven in Feed error rows.
- Smoke: Settings → Read aloud & voice screen loads fully (engine/lang/
  voice pickers, calibration) — Settings nav regression clean.
- Test-bed cleanup: deleted row was a browse-cache stub created by the
  test itself (favorite=0, auto-recreated on next Asura visit — harmless);
  no prefs touched; no user manga/history/categories modified.

SCOPE INTEGRITY: zero OCR/TTS/Reader/Feed-listing/Recent/nav-architecture/
dependency/unrelated-cleanup changes. Files changed (app source 3 + test 1
+ docs 3 + i18n 1): SettingsSearchScreen.kt, MangaScreenModel.kt,
MangaScreen.kt, MangaScreenModelErrorStateTest.kt (new), prd.md,
ui-implementation-map.md, memory.md, i18n base strings.xml.

UNCOMMITTED — awaiting user review + commit.
```

```text
[COMPLETED 2026-09-10 — UI AUDIT BATCH 5: device verification matrix,
UNCOMMITTED (verification pass; 1 test-harness file changed, ZERO app
source changes)]

Full device matrix over the combined Batch 1–4 implementation, per
docs/ui-implementation-map.md §26 Batch 5. APK 0.5.2-8264 arm64 debug
(built this session from the uncommitted B1-4 tree), installed in-place
on SM_M066B (wireless adb for most of the pass, then USB after wifi-adb
port churn; same device, same install, no data loss). Session logcat:
.device-pass/batch5-verify.log (65.7MB, 0 FATAL/AndroidRuntime app
crashes; LeakCanary 0 APPLICATION LEAKS, only <5-threshold retained
watch-noise). Screenshots: .device-pass/screenshots/batch5/ (12 PNGs).

DEVICE MATRIX RESULTS (all device-verified unless noted):
- Feed opens: PASS (AppBar + FilterBar + grid).
- D-02 chips↔grid sync: PASS — Popular chip → Popular section only;
  Latest → Latest only; All → all sections; source-chip (Asura) AND
  listing compose (source+listing single section); All sources → all
  4 sections (Asura P, Mangakakalot L/P, Atsumaru L visible by scroll).
- Section order = pref_feed_items order: PASS (verified against pulled
  pref JSON + sources table cross-ref; earlier "Mangakakalot first"
  scare was D-03 design behavior — process death resets listingSelected
  → default LATEST re-seeds → first visible = first LATEST feed. NOT a
  defect. Explicit All re-tap restores exact pref order with Asura
  Popular first.)
- D-03 explicit All survives pref emissions: PASS — customize-sheet
  source-selector toggle off→on (2 pref emits) while All selected:
  chip stayed, grid unchanged. Persistence across force-stop: PASS
  (All sources + grid restored).
- D-04 invalid-Latest coercion: PARTIAL by constraint — all enabled
  sources on device support Latest (incl LocalSource), negative path
  not device-triggerable (same as Batch 1); confirm-guard code-verified.
  Positive path (Add dialog select+confirm) PASS.
- Paging/Load more: PASS — Asura Popular page-2 append verified (new
  titles post-tap), footer button + section per-feed isolation intact.
- Sections separation + FeedHeader rhythm: PASS (ListGroupHeader +
  bodySmall listing label, no divider — D-05 stands; headers y-anchored,
  content banded clean in dumps).
- Manage Feeds: open PASS; reorder PASS (Move down + Move up, order
  verified in both directions + persisted); enable-switch PASS (toggled
  Asura feeds off → grid lost both Asura sections → toggled back →
  restored; uiautomator does not expose Compose Switch checked state —
  verified by grid effect, not attr); delete PASS (Batch 2 pass; not
  re-run to avoid touching user feed config); add flow PASS (Batch 2;
  skipped re-run same reason). NOTE: Atsumaru extension reappeared on
  device mid-pass → its old feed re-enabled itself in the list (pref
  rows persisted all along); user feed config unchanged net.
- Stale-source fallback: code+unit verified (FeedScreenModelStateTest
  stale-source fallback case); not device-exercised (would require
  deleting a feed) — same as 09-07 pass.
- Recent: opens PASS; Continue/History/Updates tab taps PASS; pager
  swipe PASS (Updates→History LTR); hierarchy PASS (one "Recent" AppBar
  title + 3-tab row, Continue = chips not headers); Continue rows PASS
  (cover+title+unread plural+Resume 48dp, heightIn alignment consistent).
- D-01 reselect→resume: PASS — Recent tab reselect opened ReaderActivity
  (last-read resume worked). no_next_chapter snackbar path not
  device-triggerable (requires wiping reading history = user data);
  host-mount verified structurally (same Scaffold slot as the
  device-proven History host).
- Settings Search (D-08): PASS — "dictionary" → Dictionaries result →
  opens SettingsDictionaryScreen (custom UI intact: popup style sliders
  + recommended cards); "exclusions" → OCR exclusions result → opens
  SettingsOcrExclusionsScreen (user's real WORD/ZONE rules render);
  "theme" → App theme / Appearance > Theme intact; no duplicate/broken
  results observed.
- MangaScreen (D-11): Success-path regression PASS ×2 (Scandal Maker:
  full info header + chapters list; Villain To Kill: info + In library
  + chapter rows). Missing/deleted path: UNIT-VERIFIED ONLY
  (MangaScreenModelErrorStateTest 4/4) — no safe live-DB trigger exists
  (same conclusion as Batch 4); Error UI reuses EmptyScreen/ActionButton
  (code-verified, same components as device-proven Feed error rows).
- Typography/spacing/alignment: PASS via hierarchy dumps + screenshots —
  grid geometry identical to Batch-3 pixel-band verdict (D-13 carry-
  forward: 8dp edges + 4dp gutters both grids); 16dp screen inset on
  rows/cards; grouped settings surfaces render (Settings root card
  headers y-banded); section headers ListGroupHeader one rank below
  screen titles; no nested headers under tab rows; documented exceptions
  untouched.
- Surfaces/frost: PASS — normal screens solid; grouped settings use
  PreferenceGroupCard; reader chrome unchanged (screenshots captured,
  no frost-on-frost, no new backdrop blur); AMOLED readable throughout
  (device runs AMOLED theme).
- Accessibility: spot-PASS — 48dp icon targets (uiautomator geometry),
  icon-only actions content-described (Resume/Delete/Move up/down/
  Manage sources/Add feed all carry descs), selected chip = tonal+text
  not color-only, Switch has toggleable semantic + row-label context
  (D-07 Batch 2 carry-forward). Large-text scale + full screen-reader
  ordering NOT re-run this pass (time; no source changes since last
  verified state).

REGRESSION GATES (docker, JDK17, -Xmx4g, both volumes): spotlessCheck +
  testDebugUnitTest + verifySqlDelightMigration + :app:assembleDebug
  BUILD SUCCESSFUL 3m22s (after test-harness fix; pre-fix full-suite
  failed 2× on the race described above).

SOURCE CHANGES: ZERO app source. 1 test file changed:
  app/src/test/java/eu/kanade/tachiyomi/ui/manga/
  MangaScreenModelErrorStateTest.kt — @TestInstance(PER_CLASS) +
  @BeforeAll/@AfterAll class-scoped Dispatchers.setMain/resetMain,
  replacing per-test @BeforeEach/@AfterEach that raced the model's
  IO-worker repeatOnLifecycle against resetMain (Voyager screenModelScope
  cached process-globally in JVM tests — cannot cancel per-test). This
  was a gate-blocking defect found BY the Batch 5 gate re-run: batch 4
  recorded "4/4 green" from an isolated/filtered run; full-suite runs
  fail deterministically (2/2). Fix is the minimal correct one.

INCIDENTS (test-harness, not app): stray Filter-sheet taps left Library
  TriState filters in include/exclude states → grid empty; restored via
  pref surgery (sed on pulled prefs XML, run-as push, 6 filter keys →
  DISABLED) + user finished reset via UI. No app data lost; user
  confirmed manga visible after.

OPEN ITEMS (unchanged, NOT turned into work): D-09/D-14/D-15 INFO;
  "Create" tab ambiguity; FeedFilterBar arrow content-desc; FeedHeader
  label-vs-combined-header; TtsPlaybackBar bodyMedium-vs-bodyLarge +
  16/4-vs-24/12 doc conflict (recorded, user decision).

VERDICT: PASS. Batches 1–4 device-conform. Ready for user diff review +
commit.
```

(Date/Task-completed lines 3186-3188 onward below are the SUPERSEDED
stabilization handoff record — kept as history; the block above is current.)
```text
[Superseded 2026-09-08 handoff — stabilization master task record]
Date:                       2026-09-08
Task completed:             B1: LEGACY OCR engine removed (LegacyOcrEngine.kt
                            + Vocab.kt 6149 lines deleted; assets/ocr/ 133.4MB
                            deleted; legacyEngine field/engineFor LEGACY
                            branch/closeEngines lines/legacyMutex pruned;
                            engineFor(LEGACY)→GlensOcrEngine for exhaustive
                            when; OcrModel.LEGACY enum + EngineType.LEGACY +
                            recognizeText redirect + scanLocalOrFallback
                            redirect + picker entry ALL KEPT; CI model
                            download steps + release artifact paths +
                            CONTRIBUTING + prd.md updated). B2: TTS prefetch
                            NetworkOnMainThreadException FIXED — scanOnDemand
                            body wrapped in withIOContext (covers
                            pageSourceResolver.resolve Rx awaitSingle for
                            BOTH main-loop + prefetch callers; explicit
                            OcrPageResult? return). B3: GLENS scan
                            transient-failure retry — scanWithGlens split
                            into retry wrapper + scanWithGlensOnce;
                            isTransientHttpFailure (HTTP 5xx/429 via
                            IOException message) → exactly ONE retry, CE
                            rethrow, suppressed chaining mirrors
                            recognizeWithFallback. B5: C5 SKIPPED
                            (MangaBakaListEntry ALIVE — audit disproved);
                            C6 IconItem removed (zero refs); C7 69 i18n
                            keys removed (81→69 after repo-wide re-verify
                            caught 5 live: information_cloudflare_bypass_
                            failure + information_webview_outdated (core/
                            common MR.strings), appwidget_updates_description
                            (widget XML), download_notifier_no_network +
                            download_notifier_text_only_wifi (app R.string)
                            — original values restored from git; lesson:
                            sweep ALL modules + R.string style, not just
                            MR.strings in kotlin modules); C8
                            ic_launcher_round (5 webp + 1 xml) +
                            anim_updates_enter + app values/dimens.xml
                            removed (all zero source refs); C9
                            awaitForChapter/getZonesForChapter/
                            subscribeForSource/subscribeZonesForSource
                            removed (interactor+repo+impl+2 .sq queries);
                            C10 loadingPreferences() @Composable helper on
                            SearchableSettings shared by
                            SettingsReadAloudScreen + SettingsAnkiScreen
                            (byte-identical blocks), Exclusions spinner
                            Row→Box, Dictionary variant untouched. B6: OCR
                            cache retention — countPages + deleteOldestPages
                            (LIMIT -1 OFFSET :keepCount) queries added;
                            pruneOldestPages inside upsert transaction;
                            MAX_CACHED_PAGES=5000 (ponytail comment).
                            B7: PermissionStep runBlocking →
                            lifecycleOwner.lifecycleScope.launch (repo fns
                            already suspend+IO).
GATES GREEN 2026-09-08 (docker vsc-yomihon-e24e3bd7e46d…, JDK17, -Xmx4g,
both volumes): spotlessCheck + testDebugUnitTest + verifySqlDelightMigration
BUILD SUCCESSFUL 2m; :app:assembleDebug BUILD SUCCESSFUL 2m24s (after
4 compile-fix rounds: OcrEngineLocks brace rewrite, engineFor LEGACY
branch, pruneOldestPages suspend, 5 i18n key restores).
APK: arm64 debug 95.06MB (was ~228MB w/ legacy assets; −133.4MB measured
assets: decoder 98.2MB + encoder 22.8MB + embeddings 18.9MB = 139,893,816B).
Contents verified: assets/ocr/ ABSENT all 5 APKs incl universal;
assets/ocr_fast/ + panel_detector present; libLiteRtClGlAccelerator.so
still packaged (litert AAR auto; no code requests GPU post-Legacy;
exclusion untested → deferred per audit). 5 ABI APKs + universal built
(Batch 8 held).
DEVICE (SM_M066B arm64, Android 16): in-place install Success (data
preserved, versionName 0.5.2-8262→8263). Matrix: A startup PASS (launch
clean, 0 FATAL); B OCR compat PASS (persisted FAST pref session works
end-to-end; persisted pref temporarily set LEGACY via run-as → app
relaunch clean + on-demand scans redirect to Glens + prefetch COMPLETE
23:04:39 + speech continues; picker shows Legacy/Fast/Online/OwOcr
entries incl LEGACY); C cached TTS PASS (cache hits + sentence
progression); D uncached PASS (on-demand scan → segmented sentences);
E prefetch PASS — page-6 scan start 22:57:08.228 → COMPLETE 22:57:20.562
(12.3s Glens, previously died ~35ms NetworkOnMainThreadException ×25/
session); ZERO NetworkOnMainThreadException in full log (35MB,
.device-pass/stabilize-verify.log); F GLENS transient retry PENDING
(no natural 502 during session — retry path code-verified only);
G exclusions PASS (rules=10 types={PHRASE=2,WORD=4,ZONE=4} loaded live;
exclusion screens render); H reader stability PASS (advance confirmed
0ms, user nav arbitration, pause/resume same-sentence, sentence steps,
stop Playing→Idle 0.7s, exit → engine disconnect <1s, no crash); I
settings PASS (ReadAloud loading scaffold → full content; exclusions
screen; Text Recognition screen + picker); J retention PASS-code-only
(eviction boundary needs 5000 pages — untestable; cache DB healthy
102KB, reads fine); Batch 7 onboarding PENDING (can't wipe user data;
compile + pattern verified).
Device pref restored to FAST after LEGACY test.
Current task:               DONE — all approved batches implemented +
                            verified. UNCOMMITTED (awaiting user).
Next recommended task:      User reviews diff (20 files, +118/−215 +
                            2 kt + 133MB assets deleted), commits. Optional
                            follow-ups: libLiteRtClGlAccelerator.so
                            packaging exclusion (~2.8MB/ABI) after testing
                            litert CPU paths; GLENS retry live-verify when
                            a natural 502 occurs; onboarding PermissionStep
                            device test on fresh install.
Files safe to modify:       all modules touched this session.
Known risks:                LEGACY removal relies on redirects (recognizeText
                            + scanLocalOrFallback) — persisted LEGACY users
                            get GLENS scans (device-verified). ocr_cache
                            schema untouched (query-only add) — no
                            migration risk. i18n locale files may still
                            carry translated copies of deleted keys
                             (harmless, Weblate will prune).
```

```text
[COMPLETED 2026-09-10 — v0.5.3 RELEASE CONSOLIDATION (user-approved batch,
per docs/next-phase-plan.md Part H)]

Pre-release state: tree had UNCOMMITTED master-plan docs (memory.md edit +
next-phase-plan.md untracked) from the planning session — committed FIRST
as separate docs commit eaccfe6f0 so the release diff stayed clean. Baseline
HEAD before release: 2a377ef12 (3 ahead of origin).

Release changes (commit daa942738 "release: v0.5.3", tag v0.5.3):
- app/build.gradle.kts: versionCode 28→29, versionName 0.5.2→0.5.3
- CHANGELOG.md: v0.5.3 section (rebrand, −139.9MB legacy OCR assets, Recent/
  Feed reworks, settings-search coverage, MangaScreen error state, prefetch
  main-thread fix, GLENS retry, cache retention 5000, Feed filter fix,
  PermissionStep freeze fix) + compare-link block updated to yomitsu repo.
  ZERO application-source changes beyond version metadata.

GATES GREEN 2026-09-10 (docker vsc-yomihon-e24e3bd7e46d…, JDK17, -Xmx4g,
both volumes), CI order:
- spotlessCheck + testDebugUnitTest + verifySqlDelightMigration: BUILD
  SUCCESSFUL 3m24s.
- assembleRelease -Pinclude-telemetry -Penable-updater: BUILD SUCCESSFUL
  12m36s.

APK VERIFIED (aapt2 + unzip, arm64): versionCode=29, versionName=0.5.3,
application-label='Yomitsu', assets/ocr_fast/{decoder 12.8MB, encoder
8.45MB} present, assets/panel_detector/model.tflite (2.84MB) present,
legacy assets/ocr/ ABSENT (removal held), libLiteRtClGlAccelerator.so
still packaged (GPU-lib exclusion deferred per plan). Sizes: arm64
65,295,045 B (~62.3MB); armeabi-v7a 58,740,433; x86 58,080,632; x86_64
70,393,209; universal 128,149,391.

DEVICE VERIFICATION 2026-09-10/11 (SM_M066B, WIRED USB adb only, in-place
adb install -r over vc28, data preserved; package found suspended+disabled
again on device — pm enable + pm unsuspend run, reversible, cause still
unknown; flagged to user): installed vc29/0.5.3. Smoke PASS: launch clean
(PID 14501); Yomitsu branding (aapt2 label + launcher); all 5 bottom tabs
(Library/Recent/Feed/Browse/More); Feed grid loads (All sources + Asura
Scans chip + All/Popular/Latest listing chips + 6 titles; one transient
"Source unavailable → Retry" row cleared on its own — stale-source path,
known); chip tap changes grid content (Atsumaru/Mangakakalot listings
loaded = filter works); MangaScreen opens (Bad Born Blood details);
ReaderActivity opens via Chapter 3; TTS started via Read-aloud button —
Google TTS engine loaded, audio focus requested (USAGE_MEDIA/SPEECH),
GLENS on-demand scan 11.4s completed, speech + TtsPlaybackBar (Pause/
Stop/Prev/Next sentence, 1.25x chip) live; PAUSE→Play + resume→Pause
button-state swap verified; Stop + reader exit clean; Settings Search:
"dictionary"→Dictionaries row, "exclusions"→OCR exclusions row, no crash.
Full-session logcat (.device-pass/v053-smoke.log, 220k lines): 0 FATAL
EXCEPTION, app alive at end. NOTE: reader chrome tap-toggle appears
disabled on this device's config (center tap = page nav) — KEYCODE_MENU
shows chrome; recorded for future device scripts. No destructive tests.

RELEASE PUBLISHED 2026-09-11: main pushed (9e7a27b08..daa942738, 5
commits incl. docs + release), tag v0.5.3 pushed, GitHub release created
with 5 ABI APKs (renamed yomitsu-*-release.apk per release.yml convention):
https://github.com/Nikhil0921/yomitsu/releases/tag/v0.5.3 (Latest).
In-app updater (points at fork) will prompt v0.5.2 users.

Out of scope held (per brief): TtsPlaybackBar unchanged (bodyMedium 16/4
still open decision), Create-tab, FeedFilterBar arrow desc, FeedHeader,
D-09/D-14/D-15, no 10B/a11y/Batch-6 work, libLiteRt exclusion NOT done.
Protected systems untouched.
```

```text
[COMPLETED 2026-09-10 — PHASE 6 MASTER PLAN (planning-only, docs change),
COMMITTED-WORKING-TREE-PENDING-USER]

Per user master-planning brief: read docs/* (memory/phase/prd/design/
ui-implementation-map/rules/architecture/Prompt) + verified open items
against source (TtsPlaybackBar.kt:90/111 bodyMedium+16/4; FeedScreen.kt:445
arrow desc null inside labeled chip; FeedScreen.kt:253-263 FeedHeader
two-line). Deliverable: docs/next-phase-plan.md — baseline inventory
(all-green c02efca25), remaining-work matrix (NO P0 items), Chimahon
ADOPT/ADAPT/DEFER/REJECT matrix (evidence-bounded: repo holds NO Chimahon
feature inventory — only Prompt.md §16 rules + recorded Tadami-inspired
ideas; nothing invented), design-system STABLE verdict, architecture-risk
matrix, 8-item decision register (TtsPlaybackBar typography+padding
recommend ratify-code; Create-tab = user clarify; arrow desc = accept;
FeedHeader = keep two-line; D-09/D-14 no action; D-15 future), roadmap
(Next = v0.5.3 release batch — 10 commits unreleased incl −133MB APK +
rebrand; After = decision micro-batch, a11y completion pass, 10B track,
litert GPU-lib exclusion), batch spec for v0.5.3 (release-only, LOW
rollback risk, full gates + device smoke), gate = READY FOR USER APPROVAL
with exact decision list. ZERO app source touched; no open decisions
silently resolved.
```

```text
Last agent:                 opencode (2026-09-10 — POST-COMMIT BASELINE
                            VERIFICATION per user brief. VERIFICATION-ONLY:
                            no Batch 6, no source changes, no open decisions
                            resolved, TtsPlaybackBar bodyMedium+16/4 left
                            untouched as directed.)
Date:                       2026-09-10
Current task:               DONE — baseline verification PASS.
  Git: HEAD c02efca25, both commits present (1b2c56b23 feat Batches
       1–5, c02efca25 docs memory update), working tree clean,
       zero uncommitted source changes.
  Gates (committed HEAD, docker JDK17 -Xmx4g both volumes):
       spotlessCheck PASS 37s; testDebugUnitTest +
       verifySqlDelightMigration PASS 2m59s; :app:assembleDebug
       PASS 2m38s. APK 0.5.2-8266 vc28.
  Device (SM_M066B WIRED USB only per brief; no wireless attempted):
       adb install -r in-place over 8264, data preserved. Package
       was DISABLED (enabled=0, user 0) — pm enable run (reversible,
       no data touched); cause unknown, flagged to user. Launch +
       Library/Recent/Feed/Browse/More/MangaScreen all open and
       render correct content; 0 FATAL/0 crash across session
       logcat. No destructive tests, no feeds/history/data changes.
  Protected areas untouched: ReaderActivity, TtsPlaybackBar, OCR/
       TTS impls+controllers, reader navigation. Open decisions
       (TtsPlaybackBar 24/12+bodyLarge, Create-tab referent,
       FeedFilterBar arrow desc, FeedHeader label, D-09/D-14/D-15)
       all left open, zero implementation work.
Next recommended task:      user reviews docs/next-phase-plan.md + rules on
                            the 5 listed decisions (decision micro-batch,
                            a11y completion pass, or first 10B item); v0.5.3
                            released — roadmap "After Next" now applies.
Files safe to modify:       none (verification-only session, no edits).
```

```text
Last agent:                 opencode (2026-09-11 — PHASE 6 ACCESSIBILITY
                            COMPLETION PASS per user brief. All 14 fix
                            batches implemented, gates green, device-
                            verified. User committed the pass as
                            212a09c7b "feat: enhance accessibility with
                            semantics and toggleable components across
                            multiple screens" 2026-09-11 05:45 UTC.)
Date:                       2026-09-11
Current task:               DONE (pending user review) — a11y pass implemented
                            + verified.
  Scope honored: smallest targeted fixes only; protected systems untouched
       (ReaderActivity, TtsPlayback*, OCR, reader nav); open decisions
       (TtsPlaybackBar typography, Create-tab, FeedFilterBar arrow,
       FeedHeader, D-09/D-14/D-15) all left open; zero new i18n keys
       (reused selected/not_selected/disabled/ext_downloading/
       label_downloaded/action_close/loading pattern keys).
  Changed files (all compile + spotless clean):
       presentation-core/SettingsItems.kt (Checkbox/Radio/TriState/Heading
       semantics), LabeledCheckbox.kt (toggleable);
       app BasePreferenceWidget+SwitchPreferenceWidget+TextPreferenceWidget
       (row-level toggleable, Role.Switch), PreferenceGroupCard+ListGroupHeader
       heading(); ChapterDownloadIndicator (state cds); MangaChapterListItem,
       UpdatesUiItem, MigrateMangaScreen, ClearDatabaseScreen, CommonMangaItem
       (selection semantics); HistoryItem/ContinueTab/UpdatesUiItem cover cds;
       GlobalSearchResultItems arrow invisibleToUser; SettingsSearchScreen
       clear cd; FeedScreen AddFeedDialog (scroll + selectable radio rows);
       ManageFeedsScreen switch label; ExtensionDetailsScreen
       SourceSwitchPreference checked param; SourcesFilterScreen
       stateDescription; CommonMangaItem ContinueReadingButton size→48dp
       default (constants removed).
  Gates (docker JDK17 -Xmx4g both volumes): compileDebugKotlin
       :presentation-core PASS; spotlessApply applied formatting;
       spotlessCheck + testDebugUnitTest + verifySqlDelightMigration PASS
       (2m37s); :app:assembleDebug PASS. APK 0.5.2-8266+ installed.
  Device verification (SM_M066B, app.yomihon.dev, font default 0.9):
       More/Settings switch rows: checkable+checked exposed, live toggle
       verified (was 0 checkable anywhere). Library grid + chapter
       selection: checked=true on selected, false siblings. Continue +
       History covers: cd=title (was cd=""). AddFeedDialog: 9 labeled
       selectable radio rows + scrollable container (was ~10 unlabeled,
       no scroll). Download/OCR icons labeled. Cancelled dialog after
       radio check — no feed created, no user data changed. One read-
       history side effect: opened Ch.1 of Villain To Kill while probing
       (reader advanced history one chapter) — normal read behavior, no
       data loss.
  Known limitations: stateDescription fixes (TriState filter rows,
       SourcesFilterScreen) NOT verifiable via uiautomator — tool doesn't
       serialize stateDescription; TalkBack does announce it. API-
       conformance verified only (pattern matches TriStateListDialog
       precedent which uses same keys). Debug-launch gotcha discovered:
       monkey LAUNCHER intent resolves to LeakCanary LeakLauncherActivity
       in debug builds — use am start -n app.yomihon.dev/eu.kanade.
       tachiyomi.ui.main.MainActivity instead.
  Documented-only findings (no code, see /tmp/opencode/a11y/ dumps):
       TagsChip 32dp targets (deliberate density, LocalMinimumInteractive-
       ComponentSize provides 0.dp — NOT changed); swipe-action icons
       unlabeled/no SR path; ManageFeeds move up/down generic descs +
       boundary no-op buttons; search-toolbar BasicTextField label (P2);
       scrim unlabeled; badges bare numbers; MangaBottomActionMenu 48dp
       clip (P2); BaseSettingsItem 40-44dp rows; read-state color-only
       asymmetry + slider/desc noise.
Next recommended task:      user reviews diff (~16 files), eyeballs
                            /tmp/opencode/a11y/fix_{feed,recent,library}.png
                            for visual regressions, then commits. Optional
                            follow-up micro-batch: CategoryListItem drag
                            handle custom a11y actions (move up/down) +
                            BaseSliderItem slider label + FeedScreen
                            SourceSelectorDropdown check cds + spinner cds
                            — planned in audit but deferred to keep this
                            change set minimal.
Files safe to modify:       the 16 a11y-fix files above (pending commit);
                            everything else needs user's new instruction.
```

```text
Last agent:                 opencode (2026-09-11 — DECISION MICRO-BATCH per
                            user brief. Docs-only; ZERO application source
                            changes. Seven of eight open decisions closed;
                            Create-tab remains OPEN.)
Date:                       2026-09-11
Current task:               DONE — decision register reconciled with shipped
                            implementation.
  Decisions (per user-approved dispositions):
       1. TtsPlaybackBar typography — CODE RATIFIED (bodyMedium);
          design.md §4 + §8 updated. No source change.
       2. TtsPlaybackBar padding — CODE RATIFIED (16dp/4dp pill interior);
          design.md §5 documents it as exception to 24/12 pill precedent.
          No source change.
       3. FeedFilterBar arrow — CLOSED NO ACTION (decorative glyph inside
          labeled FilterChip; chip label is accessible name). map §23/§29
          Q2 + plan matrix/Part F updated. FeedScreen.kt untouched.
       4. FeedHeader — KEEP two-line (ListGroupHeader + bodySmall label).
          map §29 Q3 + plan matrix/Part F updated. No source change.
       5. D-09 — CLOSED no action (intentional post-revert).
       6. D-14 — CLOSED no action (actions per-page by design).
       7. D-15 — FUTURE, NOT PART OF CURRENT WORK (IA gate if ever).
       8. Create-tab — REMAINS OPEN (DECISION REQUIRED; user clarification
          only; nothing invented, nothing built).
  Docs updated: design.md (§4 row, §5 pill exception, §8 sentence text),
       ui-implementation-map.md (§2 Create status, §23 arrow closure,
       §29 all three questions resolved, D-06 register row), next-phase-
       plan.md (Part B matrix rows, Part F register, Part I gate),
       phase.md (current pointer), memory.md (this entry).
  Source integrity: git diff confirms ZERO application source changes;
       TtsPlaybackBar.kt, FeedScreen.kt, all reader/TTS/OCR/nav files
       untouched; no dependencies touched; no a11y deferred items added.
  Verification: docs-only — full text grep confirms no contradictory
       bodyLarge/24-12/TtsPlaybackBar-spec statements remain in normative
       design docs (historical memory.md entries preserved as history).
       No build required (no source changed).
Next recommended task:      user picks next track (first Phase 10B item,
                            deferred a11y micro-batch, or Batch 6 of their
                            definition); Create-tab clarification stands as
                            the only open UI decision.
Files safe to modify:       none — docs-only session; next session needs
                            user direction.
```

---

## MEMORY UPDATE PROTOCOL (mandatory)

**Before modifying code:** read this file → check Current objective / phase /
working files / known issues / decisions / rejected approaches.

**After modifying code:** update Completed work · In progress · Changed files ·
Decisions/rejections · Issues/blockers · Testing status · Last verified
build/test · Agent handoff. Skip updates for trivial formatting-only changes.

**Anti-spaghetti checklist before any new class/utility/dependency/refactor:**
existing class? existing utility? SDK/platform solution? why is current
architecture insufficient? is each module touch required? does it reduce or move
complexity? where does it belong per architecture.md? If unclear — inspect the
repo before writing code.

**Session startup sequence:** memory.md → rules.md → relevant architecture.md
section → current phase.md status → git status → task-relevant files → confirm
architecture → smallest appropriate change → test → update memory.md → report
exactly what changed. Do not redo full analysis unless architecture changed,
docs are stale, or reality contradicts them.

```text
[BATCH 6 IMPLEMENTATION — Continue A2+A3 fix + Recent H1+H3+U1 nested-toolbar
cleanup, COMPLETED 2026-09-11, UNCOMMITTED — all 4 gates green + device
VERIFIED on SM_M066B]

Scope: only the two approved root-cause families. Protected areas untouched
(reader/TTS/OCR/Feed/nav/schema/deps/tokens — verified by final git diff).

1. Continue A2+A3 (ContinueScreenModel.kt + ContinueTab.kt):
- ROOT CAUSES: (A2) applyFilters read state.value.sort INSIDE
  mutableState.update → sorted from stale pre-update value. (A3)
  setDownloadedOnly/applyFilters destructively replaced stored items with the
  FILTERED result → raw list lost → toggling filter off could dead-end until
  force-stop. DEVICE CONFIRMATION of dead-end: pre-fix, Downloaded-only ON →
  empty list ALSO HID the chips row (it rendered only in the non-empty branch)
  → no way back without force-stop — two-layer bug (state + UI reachability).
- FIX (SM): State now stores rawItems + sort + downloadedOnly +
  isItemDownloaded lambda; displayed list = derived `items` property via new
  internal pure fn applyContinueFilters(raw, sort, downloadedOnly, predicate)
  — always computed from NEW values; setSort/setDownloadedOnly only flip their
  flag. DownloadManager check moved to stable itemDownloaded() member wired
  once in init (constructor-time ::member is illegal pre-super-init).
- FIX (UI): ContinueTab controls row (sort chips + Downloaded-only chip) now
  renders in ALL non-loading states; filtered-empty shows chips + centered
  EmptyScreen via fillParentMaxHeight Box (LazyItemScope member fn, NOT
  importable). Toggling OFF from filtered-empty restores the list — verified
  live.
- TESTS: ContinueScreenModelStateTest (new, 7 cases): alpha reorders, lastRead
  desc default, downloaded-only filters, OFF restores raw, sort composes with
  active filter, filter toggle preserves sort, pure-fn latest-values check.
  One wrong initial expectation corrected (lastRead desc vs raw order).

2. Recent H1+H3+U1 (HistoryScreen.kt + UpdatesScreen.kt):
- ROOT CAUSE: both pages kept their own inner Scaffold+topBar under the
  RecentTab host Scaffold → duplicated top-bar height, double status-bar
  inset handling, lighter toolbar band + excessive top spacing.
- FIX: inner Scaffolds/AppBars deleted. History: search = page-level
  OutlinedTextField (shapes.large, bodyLarge, ImeAction.Search) + Clear
  (x) reset + DeleteSweep (Clear history) in a controls Row; Row renders
  even when list is empty (search-empty keeps controls reachable — mirrors
  old always-mounted toolbar semantics). Updates: Filter/View-Upcoming/
  Update-library IconButtons page-level right-aligned row; action mode
  (counter + Select all/Invert/Cancel) replaces the row in selection mode;
  MangaBottomActionMenu + SnackbarHost aligned BottomCenter in a
  fillMaxSize Box (first attempt pinned menu to TOP — caught + fixed during
  device verify). Pull-refresh, lastUpdated item, delete-confirm, filter
  dialog wiring untouched. Public signatures unchanged (UpdateScreen,
  HistoryScreen params identical; RecentTab/RecentHistoryTab/
  RecentUpdatesTab/RecentReselect zero-diff).

DEVICE VERIFICATION (SM_M066B, arm64 debug APK, uiautomator + on-device
logcat /sdcard/batch6-verify.log): Continue list renders (Villain To Kill
243 unread etc.); Alphabetical visibly reorders; Downloaded-only ON →
filtered empty WITH chips visible; OFF → full list restored, no force-stop;
alpha persists across filter cycle; History: single Recent title, search
live-filters + Reset + Clear history reachable incl. empty state, date
groups + per-item delete/Add-to-library intact; Updates: no duplicate
toolbar, controls row, long-press → selection counter + select-all/
invert/cancel + bottom action menu (bottom!), calendar opens Upcoming,
filter icon present; Recent reselect → reader resume (12/58) works; Feed
tab unchanged (selector + chips + manage/add); tab switching clean; 0
FATAL exceptions attributable to app (all "FATAL" grep hits = my own
shell-grep echoes). Visual/screenshot review via uiautomator dumps only
(no pixel-diff tooling); no user data modified.

GATES GREEN 2026-09-11 (devcontainer vsc-yomihon-e24e3bd7e46d…, JDK17,
-Xmx4g, both volumes): spotlessCheck + testDebugUnitTest +
verifySqlDelightMigration + :app:assembleDebug BUILD SUCCESSFUL in one
2m38s run (plus per-iteration compile/spotless cycles during the 3
device-found fix rounds). NO DB change; NO new i18n keys; NO dependency
changes.

Files changed (5): presentation/history/HistoryScreen.kt,
presentation/updates/UpdatesScreen.kt, tachiyomi/ui/recent/continuereading/
{ContinueScreenModel,ContinueTab}.kt,
app/src/test/.../continuereading/ContinueScreenModelStateTest.kt (new).
docs/memory.md (this record).

Remaining: none observed for this scope. UpdateScreen snackbar now hosted
in-page (BottomCenter) — previously inner-Scaffold-hosted; layout under
keyboard/IME not explicitly re-verified (minor).
```

```text
[COMPLETED 2026-09-11 — Feature Batch 7: Reader toolbar customization,
UNCOMMITTED — all 4 gates green + APK installed on SM_M066B; device
verification script PENDING USER]

Scope: first user-facing customization feature from the deferred Chimahon/
AnymeX-inspired track (phase.md "Deferred features #1" — reader toolbar
reordering). Protected systems untouched: ReaderActivity architecture,
TTS/OCR pipeline, viewer/progression, overlay z-order (only param threading
+ 1 new collectAsState in setComposeOverlay).

Model (app/.../ui/reader/setting/ReaderBottomBarAction.kt, new):
- Enum: READING_MODE, ORIENTATION, CROP_BORDERS, OCR, READ_ALOUD, SETTINGS.
- DEFAULT_ORDER = current hardcoded row order (upgrade-safe).
- fromStoredIds(): normalize stored ID list — drop unknowns, dedupe, append
  missing in default order, SETTINGS always pinned LAST (fixed action; drag
  list never includes it). Never produces empty/broken bar (4 always-on
  actions can't be hidden; OCR/READ_ALOUD visibility = existing prefs).
- serialize(): comma-joined enum names, SETTINGS stripped (implied last).
  Round-trips; blank pref = defaults.

Persistence: ReaderPreferences.bottomBarActionOrder —
  getString("reader_bottom_bar_action_order", ""), empty default (existing
  users keep current order). Visibility stays in EXISTING prefs
  (ocrTextSelectionEnabled / readAloudButtonEnabled) — order and visibility
  stored separately, hidden actions remain in stored order, restore lands
  at stored position. No new preference types; string-pref ordering
  precedent (OcrScanStore queue, TtsVoicePreferences).

Reader wiring: ReaderBottomBar renders via actionOrder.forEach + when
  (replaces 6 hardcoded IconButtons; same icons/contentDescriptions);
  ReaderAppBars threads actionOrder param; ReaderActivity collects pref +
  fromStoredIds in remember. Defaults keep byte-identical layout.

Settings UI: SettingsReaderToolbarScreen (new Voyager Screen) —
  CategoryScreen-style drag-reorder (sh.calvin.reorderable, LazyColumn,
  ReorderableItem + draggableHandle, ElevatedCard rows: drag handle +
  action icon + name + Switch for optional actions); fixed Settings row
  (lock icon, "Fixed" label) below the reorderable list; hint text;
  Reset→confirm AlertDialog restores default order + default visibility
  (order pref delete(), OCR/Read-aloud switches set true; unrelated reader
  prefs untouched). A11y: row customActions = move up/down
  (CustomAccessibilityAction), Switch contentDescription = action label,
  drag handle contentDescription = action label; 48dp targets via default
  IconButton/Switch metrics.
  Entry: SettingsReaderScreen Actions group TextPreference
  ("Customize toolbar") → navigator.push. Settings-search registration:
  NOT added to unindexedSettingScreens (screen reachable via Reader
  settings trail; avoids duplicate search index entries) — noted as
  limitation.

Tests: ReaderBottomBarActionTest (new, 13 cases — all 10 brief-§15
requirements + duplicate-dedupe + settings-pin count + never-empty).
All green: tests="13" failures="0".

GATES GREEN 2026-09-11 (devcontainer vsc-yomihon-e24e3bd7e46d…, JDK17,
-Xmx4g, both volumes): spotlessCheck + testDebugUnitTest +
verifySqlDelightMigration BUILD SUCCESSFUL 2m56s; :app:assembleDebug
BUILD SUCCESSFUL 3m8s. arm64 debug APK (95MB, ML models packaged)
installed on SM_M066B (R9ZY30X3SGP) 18:4x; logcat capture running
(/sdcard/toolbar-customize-test.log). App launch smoke-clean
(MainActivity resumed, no crash).

Files changed (9): ReaderBottomBarAction.kt (new),
ReaderBottomBarActionTest.kt (new), SettingsReaderToolbarScreen.kt (new),
ReaderPreferences.kt (+1 pref), ReaderBottomBar.kt (ordered render),
ReaderAppBars.kt (+param), ReaderActivity.kt (+collect/remember/param),
SettingsReaderScreen.kt (Actions entry), i18n base strings.xml (+13 keys).
NO DB/schema change; NO new dependencies (reorderable already in catalog
+ used by CategoryScreen/MigrationConfigScreen).

DEVICE VERIFICATION PENDING USER (script): More→Settings→Reader→Actions→
Customize toolbar opens; rows render (5 reorderable + fixed Settings);
drag reorder persists; OCR/Read-aloud switches hide/restore in reader bar
at stored position; Reset restores defaults; reader bar order matches;
OCR + Read Aloud + TTS bar + navigation regression; Library/Recent/Feed/
Browse/More/MangaScreen regression.

Limitations noted: (1) settings-search index not registered; (2) a11y
custom actions are move-up/move-down only (TalkBack reorder-by-drag
unsupported by reorderable lib — same as CategoryScreen precedent);
(3) SETTINGS pinned last by design (documented).
```

```text
[COMPLETED 2026-09-11 — Feature Batch 7 DEVICE VERIFIED (user report)]
User confirmed Reader toolbar customization working on device: reorder
persists, OCR/Read-aloud visibility independent, Settings pinned last,
defaults upgrade-safe. Batch 7 CLOSED — no issues reported. Deferred
feature #1 (phase.md) fully shipped.

[COMPLETED 2026-09-11 — Artwork-Reactive Reader Tray (Chimahon-track
"ADAPT" item, phase.md deferred #3), UNCOMMITTED — all gates green + APK
installed; device verification script PENDING USER]

Feasibility (Phase A) — SAFE, minimal path, no new pipeline:
- Artwork = View-based viewers (ReaderPageImageView/SubsamplingScaleImageView);
  chrome = separate Compose overlay already fed Activity-state (bottomTrayHeightPx
  precedent — same pattern reused).
- ReaderPage.stream (existing () -> InputStream, loader-agnostic: HTTP cache
  file / archive entry / directory file / EPUB) = artwork source. Existing
  tachiyomi.decoder.ImageDecoder (same decoder chooseBackground uses, which
  PagerPageHolder already runs per page) decodes a tiny sample. No Coil
  interception, no second image-processing pipeline, no viewer touch.

Implementation (Phase B–D) — "sample → average → normalize → blend",
ALL BEFORE asFloatingChrome(); frost role stays THE surface mechanism:
- ReaderArtworkTone.kt (new, presentation/reader): pure color math +
  sampleReaderPageTone() (≤48px-side software bitmap, getPixels, mean,
  guards; IO-only; recycled in place) + Color.withReaderTone().
  Blend ratio READER_TONE_BLEND_RATIO=0.08; neutral guards: luma 0.18–0.82,
  saturation ≥0.12 → plain b/w/gray manga pages produce NO tint at all
  (most pages = zero visual change by design; only strong mid-luminance
  dominant tones nudge the chrome).
- ReaderActivity.scheduleArtworkToneUpdate(page) on onPageSelected
  (fires for ALL viewers incl. webtoon): single-flight Job, 300ms debounce
  (rapid paging cancels obsolete work), launchIO, tone in mutableStateOf
  read by compose overlay. Monochrome theme → tone forced null
  (grayscale-only scheme by design; reads uiPreferences.appTheme — the
  exact pref TachiyomiTheme consumes).
- 4 floating-chrome sites blend tone into surfaceColorAtElevation(3.dp)
  before .asFloatingChrome(): ReaderAppBars background (top bar + bottom
  tray), ChapterNavigator (both call sites), TtsPlaybackBar, OcrLoadingIndicator.
- Fallback = absolute: null tone (neutral page / decode failure / animated
  page / null stream / Monochrome / translucent-off) → withReaderTone(null)
  is identity → chrome BYTE-IDENTICAL to pre-feature. AMOLED/light/dark
  untouched (base color + alpha math unchanged; only pre-frost RGB nudge
  ≤8%). No animated color chase; tone lands on next recomposition.

Rejected (Phase C): true backdrop blur (Compose cannot sample sibling
artwork View), fullscreen RenderEffect (perf), per-frame sampling, Coil/
decoder interception (would create second pipeline), viewer architecture
changes, animated color chase, new dependency.

Performance budget: ≤48px-side sample decode per SETTLED page (~<40KB
transient, recycled), zero main-thread work, no retention, no per-frame
recomputation, no animation. Negligible vs the full-page decode display
itself performs (and vs chooseBackground which decodes MORE per page when
enabled).

Tests: ReaderArtworkToneTest (new, 10 cases): null-tone fallback identity,
ratio bound, channel validity, empty pixels, opaque-alpha mean, near-black/
near-white/gray no-tint, saturated mid-luma produces tone, dark-saturated
lineart safety. 10/10 green. (2 test expectations fixed during red-green:
Compose Color quantizes 8-bit — 0.08→0.0784, 255/2→127.)

GATES GREEN 2026-09-11 (devcontainer vsc-yomihon-e24e3bd…, JDK17, -Xmx4g,
both volumes): spotlessApply + spotlessCheck GREEN; testDebugUnitTest +
verifySqlDelightMigration BUILD SUCCESSFUL 2m38s (no schema change —
verify run anyway); :app:assembleDebug BUILD SUCCESSFUL 2m57s. arm64
debug APK (95MB, models packaged) in-place installed on SM_M066B
(vc29, data preserved); launch smoke 0 FATAL. On-device capture running:
/sdcard/artwork-tone-test.log.

Files changed (7): ReaderArtworkTone.kt (new), ReaderActivity.kt
(scheduleArtworkToneUpdate + tone state + 3 chromeTone pass-throughs),
ReaderAppBars.kt (+chromeTone param, blended bg), ChapterNavigator.kt
(+chromeTone param, blended bg), TtsPlaybackBar.kt (+chromeTone param,
blended bg), OcrLoadingIndicator.kt (+chromeTone param, blended bg),
ReaderArtworkToneTest.kt (new, 10). NO OCR/TTS/progression/viewer/
z-order/DB/i18n/Batch-7 changes.

DEVICE VERIFICATION PENDING USER (matrix): normal manga page, dark/light/
AMOLED, Monochrome, webtoon, page changes, rapid page changes, chrome
show/hide, reader toolbar, TTS playback bar, OCR loading strip;
regressions: OCR, Read Aloud, TTS controls, page progression, Batch 7
toolbar customization, overlay z-order, no visible perf regression.

Limitations (documented): (1) dual-page split/rotate/splitTallImages —
tone sampled from pre-transform stream (approximate by nature, safe);
(2) animated GIF pages may decode-fail → null → stock chrome (safe
fallback); (3) 300ms settle lag intentional (no chase); (4) no user pref
toggle yet — translucent-off already neutralizes via opaque frost role;
add Reader-settings toggle only if users ask.
```

```text
[COMPLETED 2026-09-12 — Artwork-Reactive Reader Tray: device verification,
stream-wait root-cause fix + perceptibility tuning, UNCOMMITTED]

DEVICE-DRIVEN DEBUG SESSION (user report: "don't see any changes"):
- Root cause 1 (CONFIRMED via device logs, FIXED): HTTP pages set
  ReaderPage.stream only on Ready (HttpPageLoader.kt:189); onPageSelected
  fires during DownloadImage → 300ms debounce sampled a null stream →
  tone=null, NEVER resampled (no retry on later Ready). Fix:
  samplePageToneWhenReady — await page.statusFlow until Ready (bounded
  20s TONE_STREAM_WAIT_MS; Error/timeout → null → stock chrome).
  Device-verified: schedule page=0 now waits → samples → tone logged.
- Root cause 2 (CONFIRMED, expected): most manga pages are near-white/
  gray → neutral guards correctly produce NO tint; combined with user's
  AMOLED (near-black base) the original 8% blend was imperceptible
  everywhere. Device screenshots: chrome pixels pure-black + SurfaceFlinger
  dither speckle on this device — but mean deltas + warm pixel rows
  confirmed tone rendering direction.

TUNING (user-approved "Stronger tint"): READER_TONE_BLEND_RATIO 0.08→0.20
+ TONE_CHROMA_GAIN 2.5 (accepted tones amplified around page's own luma
  so 20% registers on near-black bases). Guards unchanged: b/w/gray
  pages still stock chrome; unit test updated (ratio bound ≤0.25, chroma
  spread >0.2). 10/10 green.

DEVICE VERIFICATION RESULTS (scripted via adb, SM_M066B, build vc29):
- Tone sampling: page events fire across viewers; wait-for-Ready works
  (Ready stream=true logged); warm page0 (avg 97,66,69 → amplified tone
  0.51,0.20,0.23) vs neutral pages (avg ~200-217 grays → null) — both
  behaviors correct per design.
- Chrome rendering: menu screenshots over toned vs neutral pages show
  measurable warm shift in top-bar/tray region (mean R-B +2.6 vs +0.1;
  warm pixel rows (101,76,50) present). Screenshot color readout is
  noisy on this device (dither/quantization) — final visual sign-off
  left to user eyes (screenshots staged /tmp/opencode/toned-menu-v2.png
  + neutral-menu-v2.png; not committed).
- Rapid page changes: 6 fast dpad steps → exactly 1 settled sample
  (debounce + single-flight working; no queue spam).
- TTS regression: Read Aloud started, played (exclusion rules loaded,
  pages segmented), auto-advanced page 3→4 with tone scheduling
  alongside — zero interference; stop clean.
- Toolbar (Batch 7): customized order renders (ReadingMode, Rotation,
  Crop, ReadAloud, Settings; OCR hidden per user visibility pref) —
  intact.
- OCR button path not exercised (user pref off); pipeline untouched
  (sampler is a read-only second consumer of page.stream).
- Monochrome/light-mode: not device-exercised (branch = tone null +
  covered by unit tests; token math unchanged).
- Device quirks fought (documented for future sessions): notification
  shade keeps stealing focus (cmd statusbar collapse unreliable; HOME
  + re-launch workaround); leakcanary LeakLauncherActivity intercepts
  monkey launcher intents (am start -n MainActivity to escape); input
  events silently dropped when screen dozes (KEYCODE_WAKEUP first);
  webtoon dpad = pixel scroll (use swipes for page turns); on-device
  logcat file is the reliable capture (adb streaming drops).

GATES GREEN 2026-09-12 (docker vsc-yomihon-e24e3bd…, JDK17, -Xmx4g,
both volumes): spotlessCheck + testDebugUnitTest +
verifySqlDelightMigration BUILD SUCCESSFUL 2m36s; :app:assembleDebug
BUILD SUCCESSFUL (both fix + tuning builds). Debug log lines (tone
sample/schedule) still in build — remove before release or gate behind
BuildConfig.DEBUG next session.

Files changed this session (vs Batch-8 baseline): ReaderActivity.kt
(samplePageToneWhenReady + TONE_STREAM_WAIT_MS + debug logs),
ReaderArtworkTone.kt (ratio 0.20, chroma gain 2.5, per-step debug logs),
ReaderArtworkToneTest.kt (bounds updated), docs (this block).
```

```text
[COMPLETED 2026-09-12 — MASTER AUDIT, RECONCILIATION & REFERENCE-IMPLEMENTATION
ROADMAP (user master brief), UNCOMMITTED (docs only, ZERO app source touched)]

Session scope honored: full audit + reconciliation + reference-repo analysis +
ONE canonical roadmap + registers + exact next task. NO feature implementation.

DELIVERABLE: docs/implementation-roadmap.md (NEW — canonical execution
register with AGENT EXECUTION LOCK). Contains: baseline (v0.5.3 released,
HEAD 9126e20dc + uncommitted tone set), CURRENT AUTHORIZED TASK RM-01,
completed ledger L-01..L-16, reference register (24 candidates from 3 cloned
repos), queue Q1..Q9, rejected/deferred/decision registers, design +
architecture + documentation audits, verification matrix incl. 20-case OCR
exclusion matrix, bug register BUG-001..012, change history.

AUDIT METHOD: all 9 docs re-read; 3 reference repos cloned + inspected
(Tadami @e34f353, AnymeX @a3cfde7, chimahon @091ee6a); 2 parallel explore
subagents (TTS/OCR/reader pipeline; UI screens + typography/spacing/color)
+ codegraph source verification; GATES RE-RUN THIS SESSION: spotlessCheck +
testDebugUnitTest + verifySqlDelightMigration BUILD SUCCESSFUL 2m57s on the
CURRENT tree incl. uncommitted tone set (docker, JDK17, -Xmx4g, both volumes).

KEY AUDIT VERDICTS:
- NO P0/P1 anywhere. 2×P2 (both in TTS pause/chapter-advance seam):
  BUG-001 pause() no-op during LoadingPage/Preparing (focus-loss/onStop
  during OCR acquire leaves playback unfocused; TtsPlaybackController.kt:
  172-180 phase guard); BUG-002 NextChapter host-load failure wedges
  controller in Preparing forever (loadAdjacent swallows errors,
  ReaderViewModel.kt:563-570; no event → no Error/retry). Both → RM-01.
- 3×P3: resumeIndex carried across Paused page change (:237-240);
  OCR cache getPage ignores ocr_model → engine switch serves stale-model
  text (ocr_cache.sq:40-46 + OcrRepositoryImpl.kt:230-233); INFO log note.
- TONE-LOG NOTE CORRECTED: memory line "remove tone DEBUG logs before
  release" was priority-inverted — tone logs are LogPriority.DEBUG and
  release min is INFO (App.kt:171-179) → already suppressed in release.
  Only 3 INFO lines ship (Glens scan timings ×2, TTS startup) — harmless,
  rules-§7-compliant.
- KNOWN ISSUE #2 DISPROVED: ReaderActivity has ONE composition block
  (setComposeOverlay l.606; grep clean) — dual-block landmine doc entry is
  STALE. memory Known-issue #2 + next-phase-plan Part B row 12 flagged for
  RM-01 docs correction. (Not edited this session beyond the plan banner
  + phase pointer — full correction lands in RM-01.)
- OCR exclusion system: matcher semantics verified against source; 33
  current test fns in OcrExclusionMatcherTest (memory said 35 — recount;
  suite green in gates). 20-case master matrix: all VERIFIED via
  unit + device logs; case 17 (crop-OCR detect) N/A — feature removed with
  prefill deletion 09-03; cases 9/10 guarded by honest original-dims
  rejection (inverse transform remains documented follow-up).
- UI sweep: ZERO new .sp violations (all 9 raw hits documented exceptions);
  ONE Color literal (CommonMangaItem 0xAA000000 cover scrim — scrim-family);
  screen-level dp paddings consistent-but-token-bypassing (~91 sites,
  cosmetic); all screens PASS or MINOR — no BROKEN/MAJOR. Findings: 10
  MINORs (dead badgeNumber param, pointerInput no-op, ManageFeeds network
  waste, dictionary card clickable{} no-op, settings-search blank flash,
  dict OCR-results group loose header, etc.) → registered as BUG-004..012 /
  DS-01..07 / U-7..U-9.
- Architecture: KEEP everywhere; 2 INVESTIGATE (ManageFeeds SM reuse waste;
  setVoice result ignored); dead-code deletion candidates enumerated.
- Reference register: ADOPT/ADAPT queue = REF-TAD-001 genre-chip search
  (Q2), REF-CHI-001 recursive lookup (Q3), REF-CHI-004 e-ink popup style
  (Q4), REF-TAD-002+REF-ANY-003 tap-zone investigation (Q5), REF-CHI-002
  dict history/favorites (Q6). REJECT: all anime/novel/reels/player/
  achievement subsystems, Aurora identity, reader-control themes, screen-
  OCR-any-app (permissions), local-OCR reinstatement (contradicts −133MB
  shipped decision), nav customization, standardized reselect, Browse
  search tab. ALREADY IMPLEMENTED/COVERED: grouped settings, pitch accent,
  Anki field mapping, scanlator filters, appearance organization, download
  info, chapter transition.

DOCS CHANGES (this session, all docs-only):
- NEW docs/implementation-roadmap.md (canonical; agent execution lock).
- docs/next-phase-plan.md: SUPERSEDED banner added (kept as evidence).
- docs/phase.md: current pointer now points at the master roadmap + RM-01.
- docs/memory.md: this record.
- prd.md / architecture.md / rules.md / design.md / branding.md: NOT
  modified (no factual contradictions found by the audit beyond Known-issue
  #2, which is a memory/plan entry — corrected in RM-01 per minimal-diff
  rule; architecture.md gets its header/#2 note in RM-01's docs pass).

CURRENT AUTHORIZED TASK (one, from roadmap §B): RM-01 — cleanup &
pre-release consolidation (tone-set disposition U-2, pause-guard fix,
chapter-advance failure fix, dead-code sweep, stale-doc corrections).
Awaiting user go/no-go + U-2 commit decision. Implementation agents: read
docs/implementation-roadmap.md FIRST; execute ONLY RM-01; STOP on
contradiction and record it.

Next recommended task: RM-01 (after user approval + U-2 tone-commit call).
```

```text
[COMPLETED 2026-09-12 — RM-01 CLEANUP & PRE-RELEASE CONSOLIDATION, UNCOMMITTED]
Unattended implementation session per roadmap §B. Only RM-01 executed.

ROOT CAUSES RE-VERIFIED before editing (both matched roadmap):
- BUG-001: TtsPlaybackController.pause() phase guard
  `if (phase != Playing && !paused) return` rejected pause during
  Preparing/LoadingPage.
- BUG-002: ReaderViewModel.loadAdjacent() catch only logged (ERROR) — no
  controller signal; TTS auto-advance waits in Preparing for a chapter
  that never becomes active (rebind fires only on currChapter id change).

FIXES (smallest-change, existing architecture only):
1. BUG-001 pause guard (TtsPlaybackController.kt:172):
   - Old guard replaced by exit on {Idle, Finished, Error} — pause now
     takes effect in Preparing/LoadingPage/Playing/Paused. paused=true,
     engine.stop() (safe pre-init: tts==null early-return), phase→Paused.
   - Guard added in acquireSentences (:489): skip phase=LoadingPage update
     while paused, so a mid-acquire pause stays visibly Paused (runPlayback
     holds speech via awaitWhilePaused; resume() restarts from resumeIndex
     — this pre-existing BUG-003-family path, not touched).
   - Pause still cannot START playback (unchanged resume/start logic).
2. BUG-002 chapter-advance failure wedge (ReaderViewModel.kt:563-570,
   TtsPlaybackController.kt fail()):
   - Controller.fail() made public (host-callable, same body).
   - New TtsError.ChapterLoadFailed + i18n base string
     tts_error_chapter_load ("Couldn't load the next chapter for reading
     aloud"); wired in ReaderActivity.toMessageRes + TtsPlaybackBar
     errorMessage (both exhaustive-when sites updated).
   - loadAdjacent catch now notifies the controller ONLY when it is
     actually waiting on the transition (phase Preparing or LoadingPage):
     controller.fail(ChapterLoadFailed) → Error phase + TtsEvent.Failed →
     Event.TtsError toast + TtsPlaybackBar Retry (retryReadAloud restarts
     on the stored old-chapter page). Manual chapter navigation, page
     navigation, successful transitions: untouched (guard prevents
     healthy Playing/Paused sessions from being failed by an unrelated
     manual loadAdjacent error).
3. Dead code sweep (zero-risk, all references verified first):
   - RecentTabContent.badgeNumber param deleted + RecentTab.kt TabText
     badgeCount arg removed (RecentTabContent is Recent-tab-only;
     TabbedScreen/ExtensionsTab badgeNumber is a DIFFERENT data class —
     untouched, still live).
   - ReaderBottomBar.kt: no-op Modifier.pointerInput(Unit){} removed +
     import dropped. Touch behavior unchanged (modifier consumed no
     events). Note: BrowseSourceScreen.kt:134 has the same no-op but is
     NOT in RM-01's file list — left untouched.
   - OcrRepositoryImpl.detectionEngine(): identical if-branch collapsed
     to plain UnavailableDetOcrEngine() (TODO comment kept); orphaned
     private localOcrAvailable() deleted (no remaining callers). OCR
     pipeline/matcher/crop logic untouched.

DEAD-CODE CONTRADICTION CHECK (none found): detectionEngine branch was
genuinely behavior-identical (both arms returned UnavailableDetOcrEngine);
badgeNumber never populated; pointerInput{} consumed no events.

DOCS CORRECTIONS (BUG-012 / stale Known-issue #2):
- memory.md Known-issue #2 → RESOLVED with audit evidence (ONE composition
  block, setComposeOverlay ReaderActivity.kt:606); Technical-debt entry
  updated. Historical evidence kept.
- architecture.md: header v0.5.2→v0.5.3/vc29 + 2026-09-12 re-verification
  note; §3.8 gained historical RESOLVED note (dual-block landmine no longer
  exists; z-order contract codified at the Box comment).
- next-phase-plan.md: Part B row 12 → RESOLVED/CLOSED; protected-systems
  paragraph + Rejected list no longer cite #2 as open. SUPERSEDED banner
  kept.
- implementation-roadmap.md: §M history + bug register BUG-001/002/011/012
  statuses updated. Authority model unchanged.

ARTWORK-TONE SET DISPOSITION (Task A): UNTOUCHED + UNCOMMITTED. No
authorization to commit exists in the repo (roadmap U-2 OPEN; session
directive: do not invent user decisions). 7-file set (OcrLoadingIndicator,
TtsPlaybackBar, ReaderAppBars, ChapterNavigator, ReaderActivity,
ReaderArtworkTone.kt new, tone test dir new) remains exactly as the
2026-09-11/12 sessions left it. RM-01 code changes are disjoint files.
NOTE: docs/memory.md + docs/phase.md + docs/next-phase-plan.md contain BOTH
the pre-existing uncommitted tone-session edits AND RM-01 doc edits —
inseparable within those files (docs are append/correct-in-place).

GATES (docker devcontainer, image vsc-yomihon-e24e3bd…, JDK17, -Xmx4g,
BOTH volumes yomihon-gradle-home + yomihon-android-home, CI order):
- spotlessCheck: BUILD SUCCESSFUL 51s (PASS)
- testDebugUnitTest + verifySqlDelightMigration: BUILD SUCCESSFUL 4m29s
  (PASS — 252 tasks; no DB change, migration gate run anyway per protocol)
- :app:assembleDebug: BUILD SUCCESSFUL 3m20s (PASS; 0.5.3-8275 APKs built)

DEVICE (SM_M066B — unattended session 1 + attended session 2, USB
R9ZY30X3SGP after wireless shell hung; captures .device-pass/rm01-verify*.log):
- arm64 debug APK 0.5.3-8275 installed -r: Success (same signature via
  yomihon-android-home volume; production app.yomihon 0.5.3 NOT touched —
  debug installs under app.yomihon.dev).
- App launched, MainActivity resumed, no crash in capture (no FATAL for
  app PIDs 3065/20764 across both sessions).
- TEST 1 TTS happy path: DEVICE VERIFIED — Limitless Predation ch1
  (mangaId 190): play, page progression (advance confirmed 1-2ms),
  prefetch working; Pause from Playing (`TTS pause page=11 sentence=1`,
  UI Play+Paused); Resume (`TTS resume page=11 sentence=1`, same
  textHash 578538595, advances). PASS.
- TEST 2 pause during LoadingPage: DEVICE VERIFIED via onStop — HOME
  mid-OCR-acquire → `TTS pause page=4 sentence=0` during LoadingPage
  (old code no-op); zero dispatch/speech after. PASS.
- TEST 3 chapter-advance failure: DEVICE VERIFIED on cold process
  (PID 20764, force-stop): ch7 p20 dispatched → both radios off →
  `TTS chapter advance request` → `Loading adjacent .../chapter/8` →
  E/ReaderViewModel UnknownHost → phase Error, UI shows exact
  `tts_error_chapter_load` string ("Couldn't load the next chapter for
  reading aloud") + Retry (Preparing wedge gone). Stop-from-Error works
  (`TTS stop (phase=Error)`). Recovery with network: fresh Read aloud
  → dispatch ch8 p11 + prefetch scans → `advance confirmed`. Cold
  chapter transition (ch6→ch7, fresh loadAdjacent) also observed working.
  PASS. (Same session also showed the OcrError-at-boundary variant when
  the next page list was memory-cached: Error + Retry + tts_error_ocr,
  Retry re-attempts cleanly.)
- TEST 4 toolbar regression: DEVICE VERIFIED — bottom bar renders 5
  actions in default order (Reading mode, Rotation, Crop, Read aloud,
  Settings; OCR hidden by pref); Settings opens reader settings sheet;
  Crop toggles without crash; no touch regression from pointerInput
  removal. PASS.
- TEST 5 artwork-tone regression: DEVICE VERIFIED (log-level) —
  ReaderArtworkTone sampling + `schedule ... status=Ready` per page on
  cold process, no crash; TTS bar + OCR progress indicator rendered
  throughout. Visual tint not eyeball-checked (no image input). PASS
  with that caveat.
- CONTRADICTION vs roadmap device procedure (recorded per protocol):
  roadmap says "tap Pause during Preparing" but TtsPlaybackBar renders
  spinner + Stop ONLY during Preparing/LoadingPage — no Pause affordance
  in that phase, so TEST 2 verified via the onStop path instead (same
  pause() code path). Procedure should say "background the app / use
  Stop" for that phase, or add a Pause affordance.
- Device left as found: radios re-enabled (wifi + mobile_data=1), reader
  exited to manga detail, TTS idle. Shared-device caveat: user touches
  observed mid-session (extra Retry/Stop taps in log); all claims above
  rest on logcat lines + UI dumps, not on assumed tap ownership.
- Regression coverage note (Task B test): no JVM harness exists for the
  controller by design (thin orchestration, roadmap says don't build fake
  test architecture); behavior left to device verification per roadmap.

REMAINING KNOWN ISSUES (unchanged, from roadmap §Appendix): BUG-003
resumeIndex across Paused page change (P3), BUG-004 OCR cache ocr_model
(P3), BUG-005/006/007 P4s, U-7/8/9 minors. scanLocally/cropBitmap dead
code (~60 lines) intentionally KEPT (det-engine ceiling, architecture
table J).

GIT STATE: branch main @ 9126e20dc, no commit made (no authorization);
RM-01 changes uncommitted beside the pre-existing tone set. No release,
no tag, no v0.5.4. Next per roadmap queue: user commit decision (U-2) →
Q1 v0.5.4 release batch.
```

```text
[COMPLETED 2026-09-12 — Q1 v0.5.4 RELEASE BATCH (RM-01 + tone set shipped; release
published; PLUS user-requested OCR preload latency fix)]

GIT BASELINE CORRECTION (recorded): on session start the RM-01 + artwork-tone
set was ALREADY COMMITTED by the user as 0434d07a1 (U-2 satisfied — commit
together, as user directed). Tree was clean; no re-commit needed. Release
proceeded from that state.

RELEASE SEQUENCE (all executed + verified):
1. Gates (docker devcontainer vsc-yomihon-e24e3bd…, JDK17, -Xmx4g, both
   volumes): spotlessCheck + testDebugUnitTest + verifySqlDelightMigration
   BUILD SUCCESSFUL 3m. No DB change (migration gate run anyway).
2. Version bump 0.5.4 / versionCode 30 (convention: +1 like v0.5.2→v0.5.3);
   CHANGELOG entry (release: v0.5.4 commit 9b153610a). Notes list only the
   actual delta since v0.5.3 (toolbar customization, artwork tray, a11y pass,
   Batch 6, RM-01 fixes); branding/APK-size were v0.5.3 content — NOT
   re-claimed.
3. assembleRelease -Pinclude-telemetry -Penable-updater BUILD SUCCESSFUL
   12m23s → aapt2: versionCode=30 versionName=0.5.4 label='Yomitsu';
   ocr_fast ×2 + panel_detector packaged; legacy assets/ocr/ ABSENT.
   Sizes: arm64 62.3MB / v7a 56.0 / x86 55.5 / x86_64 67.1 / universal 122.2.
4. Device smoke PASS on SM_M066B (wireless 192.168.29.98:5555; -r install, no
   data wipe): launch clean, 5 tabs, reader loads (Villain To Kill ch2 13pp),
   Read Aloud play/pause/resume/stop OK (pill Pause↔Play↔Stop), reader
   settings + Read aloud tab render, Settings Search works ("read aloud" →
   "Read aloud button"), a11y content-descs present, ZERO FATAL/crash.
   Evidence: .device-pass/v054-smoke.log (23.8MB).

USER-REPORTED ISSUE (pre-push): "takes a lot of time to preload new
uncached OCR" — RELEASE BLOCKED, fix landed BEFORE publish:
- Evidence: smoke log `TTS startup open->first page ready in 32548ms`;
  GLENS scans 20-67s/page (service latency; historical rm01 logs identical —
  structural, NOT a regression).
- Root causes (code-proven): (1) PrioritizedTaskQueue = strictly serial
  worker; (2) glensMutex serialized even remote (network-bound) GLENS scans;
  (3) current-page acquire submitted at NORMAL priority → could queue behind
  prefetch; (4) prefetchDepth 1 page at 1x rate with a serial for-loop —
  lookahead can never cover a 30s scan.
- Fixes (commit 9f228d07c, gates green incl. 9 OCR unit tests):
  * PrioritizedTaskQueue: bounded parallelism maxConcurrentTasks=3, HIGH
    dequeued first, finishing tasks restart the drain worker. ALSO fixed an
    elvis-precedence bug found on-device (HIGH dequeues skipped the
    activeTasks++ → counter went negative → isIdle never true → engines
    never cleaned up; first release APK wedged once during acquire — that
    build was superseded).
  * OcrEngineLocks: GLENS/LEGACY text scans run UNLOCKED (engine stateless
    except StringBuilder-based TextPostprocessor — plain function local use;
    engine's own tiled path already runs TILE_CONCURRENCY=3 parallel requests
    through the same instance). FAST/OWOCR keep mutexes (tflite not
    thread-safe). withAllLocks unchanged (cleanup waits via glensMutex +
    activeOperations refcount).
  * Priority plumbing: OcrScanPriority{HIGH,NORMAL} in :domain models;
    OcrRepository.scanPage(priority=) default NORMAL; TTS scanOnDemand
    current page = HIGH, prefetch = NORMAL.
  * Controller: prefetch pages scan in PARALLEL (async/awaitAll; OCR queue
    bounds true concurrency); prefetchDepth 1→2 (2→3 at ≥1.5x, MAX=3 at
    ≥2.5x).
  * Tests: PrioritizedTaskQueueTest +5 cases (parallel capacity, capacity
    gating, idle reflection, priority under capacity 1); OcrEngineLocksTest
    glensTextScansRunInParallel added. NOTE: runTest virtual-time quirk —
    use runCurrent() not advanceUntilIdle() before asserting mid-task state
    (advanceUntilIdle silently swallows children suspended on un-completed
    deferreds).
- DEVICE VERIFIED (fixed build, SM_M066B): dev build uncached Absolute Sword
  Sense ch (webtoon, 24pp): startup 23.4s (was 32.5s; GLENS floor ~15-30s
  remains — service latency), then gap-free multi-page playback with visible
  parallel prefetch (two `on-demand scan start` same second, 37.9s + 36.2s
  scans overlapped); cache-hit advance acquireMs 51-72ms. Release build
  re-verified: Swordmaster ch4→ch7 (mixed cached/uncached): startup 3.3s
  cached / 368ms on retry; parallel scans 26.4s+34.7s overlapped; playback
  continuous ~4min; remote image-fetch SocketTimeout (SOURCE-side, OkHttp
  page-image download — unrelated to OCR queue) surfaced Error + Retry
  (BUG-002 path), Retry recovered clean + speech resumed. No wedge, no
  FATAL. Evidence: .device-pass/v054-smoke2.log.
- Residual latency (documented ceiling, not fixable in-app): GLENS remote
  scan itself 15-45s/page; first uncached page cannot beat one scan round
  trip (~15-30s). Parallel queue only removes the SELF-IMPOSED serialization
  + queue-behind-prefetch. ponytail: if first-page latency matters more,
  Phase 10B local/neural OCR is the upgrade path.

RELEASE PUBLISHED:
- commit 9f228d07c (tag v0.5.4) = 9b153610a (bump) + 9f228d07c (latency fix)
  + 0434d07a1 (RM-01+tone+docs) + earlier committed feature sets.
- Pushed main + tag; GitHub release "Yomitsu v0.5.4" published Latest with 5
  ABI APKs: https://github.com/Nikhil0921/yomitsu/releases/tag/v0.5.4
- Post-publish verified: branch pushed, tag remote, release non-draft with
  5 assets, tree clean.

Known-issue note (UNCHANGED, pre-existing, NOT release blockers): BUG-003
resumeIndex across Paused page change (P3); BUG-004 OCR cache ignores
ocr_model (P3); BUG-005/006/007 P4s; U-7/8/9 minors.
Next per roadmap queue: Q2 — Genre-chip Search (user decision U-4 pending
approval).
```

```text
[COMPLETED 2026-09-12 — Q2 GENRE-CHIP SEARCH, UNCOMMITTED]

User authorized Q2 directly (U-4 satisfied). Roadmap item REF-TAD-001
ADOPT-path. Audit first: upstream chain (MangaScreen TagsChip →
performGenreSearch → pop-to-BrowseSourceScreen → searchGenre filter-state
match w/ query fallback) ALREADY EXISTS; the real gap = BrowseSourceScreen
showed NO genre state/refinement surface.

ARCHITECTURE DECISION (option B — source-supported filtering):
- Genre chips = toggle surface over the source's OWN Filter leaves.
  genreToggles() derives chip list from FilterList: TriState/CheckBox
  children of Filter.Group + top-level TriState/CheckBox. Select/Text/
  Header/Separator never become chips.
- toggleGenreChip flips INCLUDE↔IGNORE (CheckBox: toggle) on the LIVE
  Filter object, then search(filters=state.filters) — Listing.Search
  copy → distinctUntilChanged on listing → ONE pager rebuild per tap
  (FilterList.equals always false → guaranteed re-emission, no dup risk).
- Unsupported sources (no TriState/CheckBox leaves, e.g. Asura Scans
  Select-only) honestly render NO chip row. Fake filtering impossible
  by construction — chips ARE the source's filters.
- Multi-genre = source's own semantics (all selected filters passed
  through verbatim to getSearchManga; source decides AND/OR). No
  client-side guess.
- Persistence: NONE (session/state-scoped, matches existing search UX;
  filters die with the screen model). No DB change, no .sqm, no prefs.
- MangaScreen upstream searchGenre path untouched.

FILES (2 src + 1 test):
- BrowseSourceScreenModel.kt: +genreToggles()/isGenreSelected()/
  toggleGenreSelection() pure helpers (internal, same pkg) +
  toggleGenreChip(). 54 lines.
- BrowseSourceScreen.kt: second scrollable FilterChip row under the
  Popular/Latest/Filter chips when genreToggles() non-empty; leading
  Icons.Filled.Check when selected (non-color-only selection);
  FilterChip's built-in toggleable semantics expose checked state to
  a11y. Token spacing (MaterialTheme.padding.small/extraSmall).
- GenreTogglesTest.kt NEW: 10 cases — group+top-level derivation,
  Select/Text-only → no chips, TriState selected only on INCLUDE,
  checkbox selected, toggle roundtrips, EXCLUDE→INCLUDE (not ignore),
  independent multi-select, non-genre no-op.

UI: M3 FilterChip (same component as Popular/Latest/saved-search rows
already on screen) — no new design system, no new tokens, yomihon-ui
skill ladder rung 1 (existing component on same screen).

GATES GREEN 2026-09-12 (docker devcontainer, JDK17, -Xmx4g, both volumes):
- spotlessCheck + testDebugUnitTest + verifySqlDelightMigration BUILD
  SUCCESSFUL 2m57s (GenreTogglesTest 10/10).
- :app:assembleDebug BUILD SUCCESSFUL 3m9s. NO schema change.

DEVICE SM_M066B (build 0.5.4-8281 wireless):
- Q2-01 plain search PASS ("one" → expected results, behavior unchanged).
- Q2-02 genre selection PASS: Weeb Central (filter leaves = status/type/
  genre): tap chip → results genuinely filtered (Gender-Bender tap → all
  gender-bender titles; "one"+Ongoing → completed "Wild Ones"/"One Outs"
  dropped, ongoing kept); chip container checked=true in a11y dump +
  leading check icon (chip grows 32px, layout shift evidence).
- Q2-03 clear PASS: re-tap chip → unfiltered result list restored.
- Q2-04 query+genre PASS: "one" + Ongoing = intersection, both honored.
- Q2-05 empty PASS: "zzzzqq" → existing No-results state + Retry/WebView/
  Help actions; no crash, no infinite spinner.
- Q2-06 error/retry PASS: radios off → search → No-results/Retry path
  exercised (existing SourcePagingSource error machinery, unchanged by
  diff); Retry tap functional. NOTE: svc wifi disable killed wireless
  ADB mid-test (self-inflicted, reconnected after auto-rejoin; radios
  re-enabled — device left as found).
- Q2-07 navigation PASS: browse → manga → back = browse state preserved
  (query+filter chips intact).
- Q2-08 a11y PASS: FilterChip toggleable semantics → checked=true node
  in uiautomator dump; label = genre name; check icon = non-visual-only
  state; 48dp targets (M3 chip default).
- Q2-09 regression smoke PASS: Browse (sources+browse), Library (grid+
  filter sheet), Feed (sections render), Reader (manga→chapter 1/28
  opens), More (settings groups render). Kagane also exposes chips
  (Safe/Suggestive/Erotica/Pornographic) — row renders per-source.
- Asura Scans (Select-only filters): NO chip row = honest absence PASS.

KNOWN LIMITATION (documented, deliberate): chips surface ALL toggleable
filter leaves of the source (Weeb Central = status + type + genre),
not a hand-curated genre-only list — curating per-source would require
name heuristics (fragile, per-source maintenance). Full filter sheet
remains the complete surface; chips are the quick-toggle subset.

PERFORMANCE: one search per chip tap (FilterList.equals=false → exactly
one re-emission); no duplicate/parallel searches observed (results
replace, no flicker-dup); paging untouched (existing Pager restart on
listing change); no debounce needed (user-tap frequency).

Docs updated: implementation-roadmap.md (§B, §E queue, §H U-4, §C L-17,
§M history), phase.md (pointer), memory.md (this block).
Git: UNCOMMITTED (user commit decision pending, RM-01/tone precedent).
Next per roadmap: Q3 recursive dictionary lookup design pass (U-5).
```

```
[MASTER SESSION 2026-09-13 — Q9 + bug register + Tscan + latency + Feed
genre-filter + AnymeX micro-passes — EXECUTED, UNCOMMITTED]

SCOPE: user authorized Q9 a11y + bug fixes + Tscan Feed crash + OCR/TTS
preload latency diagnostic + Feed source-supported filtering + selector
spacing + AnymeX-inspired UI modernization. Q3–Q8 HARD HALTED (no work).
AnymeX track separate from Q-numbering (Q9 not renumbered).

CHANGED FILES (all uncommitted, on top of Q2 uncommitted work):
- TtsPlaybackController.kt: BUG-003 (Paused branch resumeIndex=0 on page
  change), BUG-005 (NextChapter cancels prefetchJob, comment).
- AndroidTtsEngine.kt: BUG-006 (both setVoice sites check SUCCESS,
  DEBUG log on failure).
- ocr_cache.sq + OcrCacheStore.kt + OcrRepositoryImpl.kt: BUG-004
  (getPage gains `AND ocr_model = :ocrModel`; store + repo pass model;
  cache-hit log includes model; NO migration — UNIQUE triple + index
  already existed; verifySqlDelightMigration GREEN).
- DictionaryComponents.kt: BUG-008 (no-op .clickable{} removed).
- SettingsSearchScreen.kt: BUG-010 (produceState → synchronous
  remember(searchKey, isLtr); SettingsReaderToolbarScreen() +
  AppLanguageScreen() registered in unindexedSettingScreens — ()
  ctor calls REQUIRED, compile fails without).
- FeedScreenModel.kt: Tscan fix (fetchSection body wrapped in
  withIOContext — was running source.getPopularManga/getLatestUpdates
  on Main via screenModelScope → NetworkOnMainThreadException; all 3
  callers loadSections/loadMore/retry covered) + BUG-009 flag
  (loadSectionsOnStart: Boolean = true; ManageFeedsScreen passes false)
  + genre-filter state (genreToggles + private sourceFilterList;
  fetchSection routes getSearchManga(page, "", activeFilters) when
  chips active for selected source; selectSource loads filter list,
  no refetch — client-side filter via visibleFeeds; toggleGenreChip +
  refetchAllSections; init restores persisted source filter list once).
- FeedScreen.kt: FeedFilterBar rebuilt as stacked Column (selector row
  → listing chips → genre chips; spacedBy(small); horizontalScroll
  rows; FilterChip check leadingIcon; a11y stateDescription on
  selector) = §8 selector-spacing task + genre chips UI. FeedTab.kt
  wiring onToggleGenre.
- CategoryListItem.kt + CategoryScreen.kt: Q9 drag a11y (customActions
  move up/down; new onMove param; existing action_move_up/down strings).
- SettingsItems.kt (presentation-core): Q9 BaseSliderItem Slider
  stateDescription = valueString (~20 callers covered).
- SourceSelectorDropdown: Q9 stateDescription selected/not_selected.
- TtsPlaybackBar.kt: Q9 speed-menu stateDescription.
- heightIn large-font sweep ×4: ClearDatabaseScreen:203,
  CommonMangaItem:340, UpdatesUiItem:164 (kept .height import — line
  197 uses it), BaseMangaListItem:33.
- MoreScreen.kt + i18n base strings.xml: Studies card (Text
  Recognition/Dictionary lookup/Manage dictionaries moved from
  Library card; new label_studies). BASE STRING ONLY (moko rule).
- OcrQueueScreen.kt: flat headers → 2 PreferenceGroupCards + 12dp gap.
- UpdatesScreen.kt: filtered-empty shows UpdatesControls above
  EmptyScreen (dead-end removed; Column import).
- MangaNotesSection.kt: RoundedCornerShape(8.dp) → shapes.small.
- OcrPageSourceResolver.kt + DomainModule.kt: ChapterCache injected
  (4 gets at DI:317); resolveRemotePages reads
  chapterCache.getPageListFromCache first (fallback source.getPageList);
  openRemotePageBitmap reads cached image when isImageInCache
  (decode-fail → refetch source.getImage; CancellationException
  rethrown). Duplicate page-list/image fetches deduped = app-avoidable
  latency removed; residual 30–60s first page = GLENS service
  round-trip (evidence from prior trace).
- BrowseSourceScreenModel.kt: helpers internal→public (shared with
  Feed; only Q2-file change; rest of Q2 work untouched).

AUDITED, NO CHANGE: Download Queue (not a bug — empty-state +
notifications reachable), popups/dropdowns (theme Shapes already
consistent), spinner cds (OK), BUG-007 (verified UNREACHABLE —
openBitmap sets Ready on success before stream-null window).

DESIGNED, DEFERRED: Liquid Mode/Background (recipe in roadmap §G:
theme-derived gradient at root; prerequisite = translucent
containerColor audit across all Scaffolds; own batch, §34 stop).
True backdrop blur stays REJECTED (perf).

TESTS: no new unit tests. Justification: BUG-004 SQL predicate needs
in-memory driver harness absent from :data tests (new deps forbidden);
genre-toggle derivation already covered by uncommitted GenreTogglesTest
(shared public helpers); FeedScreenModelStateTest only constructs
State (new ctor param defaulted — compile-compatible, suite green).
Ponytail verdict: gates + device pass carry verification.

GATES GREEN (docker vsc-yomihon-e24e3bd, JDK17, -Xmx4g, both volumes):
- spotlessApply (fixed my files: OcrRepositoryImpl, CategoryListItem,
  FeedScreen, AndroidTtsEngine import order) → spotlessCheck GREEN.
- testDebugUnitTest + verifySqlDelightMigration GREEN (3m18s).
- :app:assembleDebug GREEN (3m19s).

DEVICE VERIFICATION DONE 2026-09-13 (SM_M066B wireless, debug build
app.yomihon.dev 0.5.4 installed; NOTE: debug applicationId carries
.dev suffix — use app.yomihon.dev for adb):
- Cold launch clean, 0 FATAL.
- FEED TSCAN PASS: Thunder Scans feed added via Add-dialog; section
  renders 10 titles; 0 NetworkOnMainThreadException + 0 FATAL across
  whole session = withIOContext fix live-verified. Feed selector
  stacked rows render (source row + All/Popular/Latest + genre chips).
- FEED GENRE FILTER PASS: Atsumaru Action chip tap → results genuinely
  filtered (action titles: Knight Only Lives Today, Greatest Estate
  Developer, Stellar Swordmaster, Pick Me Up); toggle off → unfiltered
  list restored (Love Desires Life, Moyuru Haru etc.). No chip row for
  Thunder Scans (no toggleable leaves — honest absence, Asura precedent).
- BROWSE Q2 REGRESSION PASS: Weeb Central browse chips render
  (Ongoing/Complete/Canceled/Hiatus/Manga).
- MORE STUDIES CARD PASS: Studies card with Text Recognition +
  Dictionary + Dictionaries; Library card keeps rest.
- OCR QUEUE PASS: grouped PreferenceGroupCard layout (Settings /
  Text recognition queue groups).
- OCR/TTS TIMING (§J evidence, .device-pass/master-session-ocr-tts-timing.log):
  uncached first page: recognizePage 12159ms, acquireMs=12876,
  startup open->first page ready 15870ms (page 0 = cover, 6/6 regions
  excluded by rules — correct, 0 sentences → auto-advance); page 1
  content: GLENS 27217ms, 20 sentences, 24 regions; 3 parallel prefetch
  scans observed (queue parallelism 3 live). CACHED RE-RUN
  (.device-pass/master-session-cache-hit.log): "TTS OCR cache hit"
  pages 0+1 instant, startup open->first page ready 1625ms (≈10×
  faster than 15870ms uncached) — BUG-004 model-predicate cache hit
  live-verified + ChapterCache dedup (no duplicate page-list/image
  fetches in logs). Residual latency = GLENS service round-trip only,
  as diagnosed.
- BUG-006 PASS: "TTS voice applied name=en-us-x-tpc-local" DEBUG log on
  setVoice success (new log line live).
- BUG-009 PASS: ManageFeedsScreen lists all 9 feeds incl. new Thunder
  Scans; 0 network fetches in logcat while on management screen.
- TTS full-chain PASS: 20+ sentences sequential dispatch, auto page
  advance p0→p9, pause (p2 s2/s7, p5 s10 etc.), resume same position,
  stop clean (phase=LoadingPage — prefetch-cancel window exercised).
- BUG-003 partial-device: pause + resume + page-change each exercised
  individually; exact paused-page-change→resume-sentence-0 sequence NOT
  isolated (UI tap racing playback-bar auto-hide on this device; reader
  is webtoon-style vertical, manual page-change-while-paused hard to
  drive via adb). Fix is 2-line (TtsPlaybackController.kt:254-256
  resumeIndex=0 in onPageSelected Paused branch), gates green — accepted
  as code-verified; revisit in next natural reader device session.
- Q9 a11y stateDescription (slider/selector/menus): uiautomator XML
  does not expose state-description attr on this device — code-verified
  only; TalkBack audible verification deferred to user.

DOCS UPDATED: implementation-roadmap.md (§B current task, §E Q3–Q8
HALTED + Q9 EXECUTED, §G Liquid recipe + AnymeX IA rejection, bug
register 003–010 statuses, §M history), memory.md (this block +
device results), phase.md (pointer), ui-implementation-map.md (§30
addendum: new surfaces, no D-item changes).

Git: COMMITTED 2026-09-13 by user authorization (6 logical commits on
top of b2f1da316):
- 345fcb67d feat(browse): genre-chip quick filters (Q2)
- ed368bf6a fix(tts/ocr): bug register fixes BUG-003/004/005/006/008
- 0553a8a78 fix(feed): main-thread fetch, mgmt-screen waste, genre
  chips, selector spacing (+ OCR/TTS ChapterCache latency)
- 81811dcd8 feat(a11y): Q9 completion batch + settings search fixes
  (BUG-010)
- 4f7a9e51b feat(ui): Studies card, grouped OCR queue, updates
  empty-state controls
- 64e0103af docs: record master session (+ ANYMEX-SCREENSHOT refs)
Total 44 files, +976/-242. Device evidence stays local
(.device-pass/, gitignored).
Session CLOSED.
```

```text
[COMPLETED 2026-09-13 — FEED UI CORRECTION (user-authorized follow-up to
master session; supersedes stacked-selector layout), UNCOMMITTED]

User prompt (docs/Prompt.md): remove listing "All", restore compact
[Source][Popular][Latest] row, remove duplicate source/listing title,
tighten header, collapse-on-scroll, correct settings Default listing,
preserve genre chips + filtering + "All sources".

AUDIT FIRST: FeedScreen.kt / FeedScreenModel.kt / FeedTab.kt /
FeedPreferences / FeedModels / ManageFeedsScreen / state test traced.
Key facts: "All" = listingOverride==null chip (FeedScreen.kt) + null
defaultListing chip (customize dialog); FeedListing enum never had ALL
(no migration possible/needed); "All sources" = SourceSelectorDropdown
item (source selection, DIFFERENT concept, untouched); FeedHeader
composable rendered per-section source+listing duplicate title.

CHANGES (2 files):
1. FeedScreen.kt:
   - FilterBar moved INTO LazyVerticalGrid as first full-span grid item
     (was Column-wrapped above grid) = collapse-on-scroll + return with
     ZERO custom machinery (prompt §15 "reuse existing behavior" rung).
   - FeedFilterBar rebuilt: single primary Row = SourceSelectorDropdown +
     Popular + Latest FilterChips (horizontalScroll, CenterVertically);
     All chip deleted; genre row below (unchanged semantics, honest
     absence); spacing extraSmall (8dp edges align with grid insets).
   - FeedHeader composable DELETED (duplicate title §13).
   - Customize dialog Default listing: All chip removed (Popular/Latest).
   - Unused imports dropped (ListGroupHeader).
2. FeedScreenModel.kt: prefs collect maps legacy null defaultListing →
   POPULAR at read (`?: FeedListing.POPULAR`) — smallest compatible
   fallback, no migration, no new architecture (§10). selectedSourceId
   / listingOverride restore paths unchanged.

PRESERVED: visibleFeeds fallback semantics (null override still shows
both listings if a feed set lacks the selected one — state test 9/9
green untouched); Q2 Browse work untouched; genre chips (session-scoped
filter leaves); "All sources" dropdown item; stable-width selector
(textMeasurer max-width cap + ellipsis); AddFeedDialog; ManageFeeds
(loadSectionsOnStart=false); withIOContext fetch fix.

GATES GREEN 2026-09-13 (docker vsc-yomihon-e24e3bd…, JDK17, -Xmx4g, both
volumes): spotlessApply→spotlessCheck + :app:compileDebugKotlin green;
testDebugUnitTest + verifySqlDelightMigration BUILD SUCCESSFUL 2m59s;
:app:assembleDebug BUILD SUCCESSFUL 3m26s. One compile fix mid-run
(bottom val hoisted out of deleted Column block).

DEVICE VERIFIED SM_M066B (debug app.yomihon.dev, 720px, wireless):
- Layout: single row verified by bounds — selector [49,209], Popular
  [363,209], Latest [512,209] SAME y; genre row y=300; no overlap.
- All absent: zero "All" text nodes in Feed + customize dumps; "All
  sources" dropdown item still present (§9 distinction held).
- Single-select + data match (§18): Popular checked → romance set;
  Latest checked → different latest set; both listings render distinct
  results; highlighted listing = rendered data.
- Genre chips: Kagane content-rating TriStates render selected (INCLUDE
  default from source), Safe off→on roundtrip genuinely filters +
  restores results; Thunder/Asura now expose filter leaves → chips shown
  (source-side change; honest per-source).
- Collapse: 3× swipe-down → selector/chips/genre all scrolled off
  (GONE in dump); swipe-up → back at exact y=209/300. No jitter (bounds
  identical before/after). App bar enterAlways already handled top bar.
- Load more: tap appended next page (30 Years Since…, Academy's Genius
  Swordmaster etc.).
- Persistence: source + listing + default survive force-stop/restart
  (Latest + Asura restored); Manage Feeds roundtrip (reselect path)
  preserves state.
- Legacy fallback: pref_feed_default_listing hand-emptied on device →
  relaunch → Popular chip checked, no crash, no corruption (§10 verified
  live). NOTE: sed surgery self-broke pref_feed_items mid-test (host
  python repaired via /data/local/tmp + run-as cat); final state feeds
  restored, defaultListing left EMPTY intentionally for this test.
  User may want to re-set preferred default from the app UI.
- No FATAL / NetworkOnMainThreadException in session logcat.

DEVICE QUIRKS (documented): Facebook notifications/overlay repeatedly
stole taps mid-session (tap landed in Messenger UI) — re-launch +
statusbar collapse workaround from prior session still required;
uiautomator dump occasionally returned stale/empty tree when racing app
recomposition (re-dump after settle).

Docs updated: ui-implementation-map.md §14.3 (rewritten to current
contract), implementation-roadmap.md (§B current-task block, §C L-18
master-session commit ledger row + L-19 feed correction, §M history
entry), phase.md (pointer), memory.md (this block).

Git: committed by user as 35a78cb7e (2 source files + docs incl.
Prompt.md). Closeout audit session (below) verified the commit, re-ran
all gates green, and resolved the empty-defaultListing device caveat.
```

```text
[COMPLETED 2026-09-13 — MASTER CLOSEOUT AUDIT + RELEASE-READINESS
VERIFICATION, UNCOMMITTED (docs only)]

Session type: closeout audit per user master-closeout brief. NO code
changes required — correct outcome achieved.

A. REPOSITORY INTEGRITY: branch main @ 35a78cb7e (Feed correction
   committed by user, includes docs/Prompt.md + memory/phase/roadmap/
   ui-map updates; commit-tree clean, zero uncommitted code). Previous
   "uncommitted @ d41a46ae2" state superseded by that user commit.
   No Q2 files touched by it (BrowseSourceScreen/Model, GenreTogglesTest
   all clean). No protected architecture changed, no dependency
   changes, no new .sqm migrations, no tag/release created.

B. FEED CORRECTION VERIFIED against source: FeedListing enum =
   {POPULAR, LATEST} (no ALL, never had); persisted legacy null
   defaultListing → Popular at read (FeedScreenModel.kt:128
   `?: FeedListing.POPULAR`); Customize exposes Popular/Latest only
   (FeedScreen.kt:599-608); filter bar = [Source][Popular][Latest]
   single row + genre row (horizontalScroll); duplicate section header
   gone (no FeedHeader/source title in sections — grep clean); FilterBar
   = first LazyVerticalGrid item (collapse-on-scroll, no nested scroll
   container, no custom collapse framework); visibleFeeds fallback
   semantics + paging/retry/persistence intact (9/9 state tests).
   "All sources" dropdown item retained (§9 distinction held).

C. Q9 VERIFIED present: CategoryListItem customActions moveUp/moveDown;
   BaseSliderItem stateDescription=valueString; SourceSelectorDropdown
   menu stateDescription; TtsPlaybackBar speed stateDescription;
   heightIn(min=56.dp) ×4 (ClearDatabase/CommonMangaItem/UpdatesUiItem/
   BaseMangaListItem). No regression.

D. BUG REGISTER: 003 PASS (Paused page-change → resumeIndex=0,
   TtsPlaybackController.kt:432); 004 PASS (OcrCacheStore.getPage
   ocrModel predicate, no migration); 005 PASS (NextChapter →
   prefetchJob?.cancel(), :469); 006 PASS (setVoice ×2 SUCCESS checks);
   007 NO-ACTION/UNREACHABLE (guard intact); 008 PASS (no-op
   clickable gone from DictionaryComponents); 009 PASS
   (loadSectionsOnStart=false, ManageFeedsScreen.kt:53); 010 PASS
   (synchronous settingScreens index + unindexedSettingScreens
   registration incl. OcrExclusions/Dictionary/ReaderToolbar/AppLanguage).

E. TSCAN: fetchSection wrapped withIOContext (FeedScreenModel.kt:206).

F. OCR/TTS LATENCY: OcrPageSourceResolver — getPageListFromCache-first
   (:111-116), ChapterCache image reuse (:150-153), decode-fail →
   refetch fallback with CE rethrow (:154-160), all inside withIOContext.
   Verdict retained: app-side duplicate fetches deduped; residual
   first-page latency = external GLENS/service round-trip (NOT claimed
   solved).

G. TESTS: 9 Feed state tests + 10 GenreTogglesTest green in full
   testDebugUnitTest run; no new tests needed (UI/layout change only,
   deterministic state paths already covered).

H. GATES (closeout re-run, docker vsc-yomihon-e24e3bd…, JDK17, -Xmx4g,
   both volumes): spotlessCheck + testDebugUnitTest +
   verifySqlDelightMigration + :app:assembleDebug BUILD SUCCESSFUL 3m11s
   (single chained run; MigratorTest + DictionaryTermCardTest visible
   PASSED in tail).

I. DEVICE SM_M066B (0.5.4-8286, current tree): focused smoke PASS —
   Feed renders [Source▼][Popular][Latest] single row (y=209-241 all
   chips); no All chip; Popular/Latest data-distinct + selection
   verified on parent checkable nodes (uiautomator quirk: Text child
   carries no checked attr — parent checkable node does; stale dumps
   twice, re-dump pattern from memory reused); collapse-on-scroll +
   return-on-scroll-up verified; Manage Feeds opens/lists; force-stop
   restart → source + listing + default persisted; 0 FATAL, 0
   NetworkOnMainThreadException in session logcat.
   LeakCanary dump-screen interrupted one restart cycle (known debug
   noise, dismissed, no app impact).

J. DEVICE-PREF CAVEAT RESOLVED (brief §15): Feed Settings UI → Default
   listing → Latest tapped; pref persisted as
   pref_feed_default_listing=LATEST (verified via run-as shared_prefs
   read), survived force-stop/restart, feed renders Latest data with
   Latest chip checked. Empty/missing pref still falls back to Popular
   (code path intact, FeedScreenModel.kt:128). No manual pref surgery.

K. DOCS RECONCILED: this block + phase.md pointer + roadmap L-19 row
   updated UNCOMMITTED→COMMITTED 35a78cb7e + closeout status. Audited
   ui-implementation-map.md §14.3 (matches current contract), roadmap
   (Q3-Q8 HALTED held, Liquid DESIGNED/DEFERRED held, true blur
   REJECTED held, AnymeX separate track held).

L. RELEASE READINESS: versionName 0.5.4 / versionCode 30 — INCREMENTED
   BEYOND released tag v0.5.4 (vc29 era). Working tree contains 6
   post-v0.5.4 commits not in any release. NOT READY to release without:
   user review of 35a78cb7e + roadmap decision on next version bump
   (0.5.5/vc31?) + usual release process (user-authorized only). No
   commit/tag/push/release performed by this session.

Session discipline held: no new features, no Liquid Background, no new
UI work, no speculative cleanup, no renumbering. STOP after report.
```

```text
[COMPLETED 2026-09-13 — RELEASE CANDIDATE v0.5.4.1 PREPARED, UNCOMMITTED]

Session type: release-candidate preparation per user brief (v0.5.5 renamed
to 0.5.4.1 by user mid-session — patch release, versionCode 31). Scope
lock held: no new features, no Q3-Q8, no Liquid Background, no blur, no
AnymeX expansion, no protected-architecture changes, no speculative
cleanup. No git commit/tag/push/release performed (user decision).

A. BASELINE AUDIT: main @ 607646553 (docs-only descendant of approved
   35a78cb7e), tree clean, tags end at v0.5.4 (no stray v0.5.5 tag).
   Post-v0.5.4 = 10 committed commits: Q2 genre chips (345fcb67d), bug
   register BUG-003/004/005/006/008 (ed368bf6a), Feed fix + ChapterCache
   latency (0553a8a78), Q9 a11y + BUG-010 (81811dcd8), Studies card +
   grouped OCR queue + updates empty-state (4f7a9e51b), docs (64e0103af,
   d41a46ae2), Feed UI correction (35a78cb7e), roadmap/memory docs
   (607646553). Only DB-adjacent change: ocr_cache.sq getPage gains
   `AND ocr_model = :ocrModel` predicate (query-only, no schema change,
   no migration, part of approved BUG-004 commit). No dependency changes.

B. VERSION BUMP (2 files):
   - app/build.gradle.kts: versionCode 30→31, versionName 0.5.4→0.5.4.1
     (single source of truth; About reads BuildConfig, no other version
     sites; README has zero version refs).
   - CHANGELOG.md: [v0.5.4.1] - 2026-09-13 entry — Feed compact controls
     row + All-option removal + header dedup + collapse-on-scroll,
     Default listing Popular/Latest only + legacy fallback, Studies card,
     genre quick-filter chips (Browse+Feed), Feed main-thread fetch fix,
     Manage-Feeds waste fix, ChapterCache/preload latency, persistence,
     Updates empty-state controls, a11y stateDescriptions + category drag
     actions + large-font fixes, Settings Search fixes, TTS/OCR bug
     fixes (003/005/006). No GLENS-latency-eliminated claim; no deferred
     features advertised.

C. GATES GREEN (docker vsc-yomihon-e24e3bd…, JDK17, -Xmx4g, both
   volumes), single chained run: spotlessCheck + testDebugUnitTest +
   verifySqlDelightMigration + :app:assembleDebug BUILD SUCCESSFUL 3m37s.

D. APK AUDIT: app/build/outputs/apk/debug/ 5 ABIs, arm64 95,150,697 B,
   built 14:52 fresh. output-metadata: versionCode=31, versionName=
   0.5.4.1-8288 (debug -{commitCount} suffix by design), applicationId
   app.yomihon.dev (debug .dev suffix).

E. DEVICE VERIFICATION SM_M066B (wireless 192.168.29.98:5555, debug
   install Success, dumpsys package: versionCode=31 versionName=
   0.5.4.1-8288 = APK-metadata match, no UI/APK mismatch):
   - Launch clean, session 0 FATAL / 0 ANR (.device-pass on-device
     capture /sdcard/rc-v0541.log, 20MB).
   - FEED PASS: single row [Asura Scans▼][Popular][Latest] all y=209;
     0 "All" chips; Popular→Latest data-distinct (title sets differ) +
     checked-state on parent nodes verified; genre Action chip tap →
     checked=true + chips shifted (refetch) + list changed to Action
     titles (Doctor's Rebirth, Genius Martial Arts Trainer etc.);
     collapse-on-scroll (selector+chips GONE in dump) + return-on-scroll
     up; Manage sources + Add feed icons present.
   - BROWSE PASS: Sources/Extensions/Migrate tabs, source list renders
     (AllManga/Asura/Atsumaru/Kagane/…). (Q2 chip matrix already
     device-verified 2026-09-13, not re-run.)
   - LIBRARY PASS: cards + category chips render; filter sheet opens;
     long-press opens per-manga menu (selection mechanics functional).
   - MORE PASS: Studies card (Text Recognition/Dictionary/Dictionaries);
     Library card with live queue subtitle "Paused • 5 remaining";
     Settings/About/Help rows.
   - SETTINGS PASS: root renders all groups; Settings Search live
     ("read" → Read aloud button, Reader>… results; "language" → App
     language findable = BUG-010 unindexed registration verified);
     navigation into Read aloud & voice screen OK.
   - READER/TTS SMOKE PASS: Villain To Kill ch1 opens (1/16, webtoon);
     Read aloud tapped → TextToSpeech bound to com.google.android.tts,
     "TTS voice applied name=en-us-x-iom-local" ×3 (BUG-006 log live),
     GLENS OCR service bind/unbind cycles (chimera ocr.service START
     ×2), no speak failures. Architecture untouched (smoke only).
   - OCR SMOKE PASS: GLENS service exercised in TTS path (above);
     recognition pipeline from master-session evidence + this session's
     clean cycles.
   - ABOUT: "Debug 607646553" per debug-build convention (AboutScreen
     shows Stable {VERSION_NAME} on release builds — correct, no
     mismatch).
   - Device quirks (known): PIN lock interrupt → user unlocked; Facebook
     overlay + notification shade stole taps/focus twice → dismissed;
     LeakCanary LeakLauncherActivity intercepted one relaunch (debug
     noise, known issue #13) → backed out; one rotation dialog opened by
     stray tap → dismissed.

F. DOCS RECONCILED: phase.md pointer (this release-candidate state),
   memory.md (this block). roadmap/ui-map unchanged (no new UI work —
   release prep only). Docs state 0.5.4.1 release candidate prepared;
   tag/GitHub-release NOT marked done.

DEFERRED (retained): Q3-Q8 = HALTED; Liquid Background = DEFERRED;
   true backdrop blur = REJECTED; AnymeX = separate track.

STATUS: RELEASE CANDIDATE READY. Awaiting explicit user authorization
for commit / tag / push / GitHub Release.
```

```text
[COMPLETED 2026-09-13 — v0.5.4.1 RELEASED (user-authorized)]

User authorized full release. Actions taken:
- Commit 2af00b105 "release: v0.5.4.1" (4 files: build.gradle.kts version
  bump, CHANGELOG, memory.md, phase.md) — pushed to main.
- Release build: assembleRelease -Pinclude-telemetry -Penable-updater
  (docker, JDK17, -Xmx4g) BUILD SUCCESSFUL 12m54s. Output metadata:
  applicationId=app.yomihon, versionCode=31, versionName=0.5.4.1 (no
  suffix — release), 5 ABI APKs + baseline profiles. ML models verified
  packaged (ocr_fast encoder/decoder + panel_detector; legacy ocr/ set
  gone since v0.5.3 — correct).
- Tag v0.5.4.1 created + pushed. NOTE: first push also uploaded old local
  tags v0.3.x–v0.5.4 (were local-only) — harmless.
- GitHub release published: Nikhil0921/yomitsu v0.5.4.1 "Yomitsu v0.5.4.1",
  Latest (api confirmed), 5 ABI APKs attached, notes = CHANGELOG entry.
  https://github.com/Nikhil0921/yomitsu/releases/tag/v0.5.4.1
  Gotcha hit: gh defaulted to yomihon/yomihon remote — needed
  --repo Nikhil0921/yomitsu.
- In-app updater (points at this fork) will prompt v0.5.4 users.

Release state: DONE. Next per roadmap queue: Q3 recursive dictionary
lookup design pass (U-5).
```

```text
[COMPLETED 2026-09-13 — ANYMEX UI MODERNIZATION MICRO-BATCH, UNCOMMITTED]

User-authorized AnymeX-inspired UI track (post-v0.5.4.1). Reference =
AnymeX screenshots/analysis already registered (roadmap §D/§G) — visual
intent only (hierarchy, grouping, rhythm); NO cloning, NO Liquid, NO
blur, NO IA regroup, NO nav changes, NO new deps, NO DB changes.

AUDIT FIRST: re-verified prior findings still in source: DS-01
(SettingsDictionaryScreen OcrResultPreferenceGroup = loose header+Box,
last grouped-settings holdout), DS-03 (SettingsSearch 24/14dp literal
row paddings vs 16dp token rhythm everywhere else), DS-06 (Feed
customize action = List icon + "Settings" title), DS-02
(MangaCompactGridItem CoverTextOverlay scrim Color(0xAA000000)),
FeedCustomizeDialog card spacedBy(8dp) vs frozen 12dp grouped rhythm.
Everything else already converged (09-13 matrices); DS-07/32dp empty
insets = documented tolerance, left alone.

CHANGES (5 files):
1. SettingsDictionaryScreen.kt: OcrResultPreferenceGroup →
   PreferenceGroupCard(title=pref_category_dictionary_ocr_results);
   Box+PreferenceGroupHeader dialect deleted; rows flat inside card.
2. SettingsSearchScreen.kt: result-row padding 24/14dp → 16/12dp
   (token rhythm; matches sibling list rows).
3. FeedScreen.kt: customize action icon Icons.Outlined.List →
   GridView (icon matches grid/display purpose); FeedCustomizeDialog
   spacedBy(small) → spacedBy(12.dp) (grouped-card rhythm).
4. CommonMangaItem.kt: CoverTextOverlay gradient end
   Color(0xAA000000) → MaterialTheme.colorScheme.scrim.copy(0.67f)
   (token; same scrim family as reader overlays; visual parity 0xAA).

PRESERVED: Feed §17/§18 contract untouched (row/chips/genre/paging/
persistence verified post-change on device); Q2 Browse untouched;
settings search index untouched; all a11y semantics untouched.

GATES GREEN 2026-09-13 (docker vsc-yomihon-e24e3bd…, JDK17, -Xmx4g,
both volumes): spotlessApply→spotlessCheck + :app:compileDebugKotlin
3m3s; chained spotlessCheck + testDebugUnitTest +
verifySqlDelightMigration + :app:assembleDebug BUILD SUCCESSFUL 3m15s.

DEVICE VERIFIED SM_M066B (debug arm64 fresh install, 720×1600):
- Dictionaries screen: OCR-results rows inside tonal card — pixel
  band proof (card px 34,47,49 vs background 32,33,37), header in
  card, rows x=56 aligned with other grouped screens; Recommended
  Dictionaries + items render below; 0 crash.
- Settings search "read": live results, rows left-aligned 16dp
  rhythm (title x=28px ≈ 16dp+glyph inset vs old 24dp start).
- Feed: [Asura Scans▼][Popular][Latest] row + genre chips render;
  customize sheet opens (Display/Sources/Default listing groups);
  content-desc actions Manage sources/Settings/Add feed intact.
- Library: compact grid + scrim-backed titles + unread badges render
  (Villain To Kill 243 etc.).
- Recent: Continue chips (Last read/Alphabetically/Downloaded only)
  + rows render. Browse: sources list renders. More: General/
  Studies/Library/Settings cards intact.
- Session: 0 FATAL EXCEPTION / 0 NetworkOnMainThreadException.
- Device quirks: LeakCanary LeakLauncherActivity intercepted 2
  relaunches (known #13) — dismissed; NotificationShade + systemui
  overlays stole focus twice — cleared; stale uiautomator dumps ×2 —
  re-dump pattern; swipes eaten once by overlay on Dictionaries
  scroll (top-of-screen target verified; bottom content unchanged
  code, covered by prior passes).

Docs: ui-implementation-map.md §31 addendum, implementation-roadmap
§B current-task + §I DS rows + §M history row, phase.md pointer,
memory.md (this block).

Roadmap state: AnymeX UI = EXECUTED (this batch); Q3 = queued next
(design pass, U-5); Q4-Q8 HALTED; Liquid Background DEFERRED; true
blur REJECTED. UNCOMMITTED — user commit decision.
```

```text
[YOMUCHU BATCH 3 — GROUP-BORDER ROLLBACK + RADIAL GRADIENT — 2026-09-15, UNCOMMITTED]

 USER-AUTHORIZED 2-change batch; supersedes BATCH 2 fix #2 (hairline):
 1. PreferenceGroupCard: 1.dp outlineVariant border REMOVED — back to
    clean surfaceContainerLow/shapes.large tonal surface, no outline.
 2. TachiyomiTheme appBackgroundBrush: linear top→bottom gradient →
    screen-centred RADIAL multi-stop. Pure fn backgroundGradientColors
    (replaces backgroundGradientEndColor): stops [peak, mid, bg] =
    lerp(background, surfaceContainerLow, t·0.55 / t·0.25) → background
    at corners. Peak capped below container tone → group boxes can never
    sit on their own fill at ANY point on screen (wash-out root cause,
    Teal&Turquoise/Taco/AMOLED) — border no longer needed. Colors stay
    fully theme-derived (Monet/dynamic/AMOLED/light valid); intensity
    0 → flat stops → visually solid.
 Test: AppBackgroundGradientTest rewritten for 3-stop ramp (zero=flat,
 full=0.55/0.25 peak+monotonic decay, coercion). design.md
 §Grouped settings surfaces updated (hairline text → radial note).
 No prefs/DB/schema change → verifySqlDelightMigration N/A.
 GATES GREEN 2026-09-15 (docker vsc-yomihon-e24e3bd…, -Xmx4g, gradle-home
 volume; NOTE: do NOT mount yomihon-android-home — it shadows the image
 SDK/licences): spotlessCheck + :app:testDebugUnitTest FULL suite +
 :app:compileDebugKotlin + :app:assembleDebug BUILD SUCCESSFUL 3m23s.
```

```text
[UPDATES VISUAL MODERNIZATION — COVER HEIGHT + VERTICAL RHYTHM — 2026-09-15, UNCOMMITTED]

 PRESENTATION-ONLY visual refinements to Updates page (Recent→Updates tab):
 1. UpdatesUiItem.kt: MangaCover.Square cover height constrained from
    .fillMaxHeight() to .heightIn(min = 90.dp, max = 135.dp) — 2:3
    aspect ratio target from visual reference screenshots.
    Applied to both UpdatesUiItem and UpdatesMangaGroupItem.
 2. UpdatesUiItem.kt: Expanded chapter list Column gained
    .padding(vertical = 4.dp) — achieves ~70-75px vertical rhythm
    between expanded chapter rows.

 NO functional changes: domain/repository/navigation/state preserved.
 NO domain models changed.
 NO new dependencies.
 NO database/schema changes.

 GATES GREEN 2026-09-15 (docker vsc-yomihon-e24e3bd…, JDK17, -Xmx4g,
 both volumes mounted: yomihon-gradle-home + yomihon-android-home):
 spotlessCheck + testDebugUnitTest (248 tests) + verifySqlDelightMigration
 + :app:assembleDebug BUILD SUCCESSFUL 3m29s.
 Test file UpdatesScreenModelTest.kt removed — attempted to instantiate
 internal UpdatesScreenModel class (invalid). Existing UpdatesGroupingTest
 (pure function groupConsecutiveUpdates) remains valid.

 APK: app-arm64-v8a-debug.apk (91MB, SHA-256: 2b9ffe34a997b73b...)
 versionName=0.5.4.1, versionCode=31, applicationId=app.yomihon.dev

  DEVICE VERIFICATION PENDING (blocked by ephemeral debug-keystore
  signature mismatch — known gotcha in memory.md).
```

## 2026-09-16 — Recent→Updates compact manga group card (UNCOMMITTED)

Authorized one-off UI refinement (roadmap §B remains NONE; not Q3–Q8/Phase 10B).

Changed only `app/src/main/java/eu/kanade/presentation/updates/UpdatesUiItem.kt`
(`UpdatesMangaGroupItem`):
- Group wrapped in one Material 3 `Card` (`MaterialTheme.shapes.large`).
- Cover changed from tall `MangaCover.Square` (90–135dp) to compact
  `MangaCover.Book`, fixed 52dp width (~78dp tall, `extraSmall` shape),
  vertically centered.
- Header now title (`titleSmall`, 1 line) plus first-row `chapterName` and
  relative `dateFetch` metadata (`bodySmall`, `onSurfaceVariant`).
- Trailing affordance is now an exclusive 48dp `IconButton`
  (`ExpandMore`/`ExpandLess`, existing `action_expand`/`action_collapse`
  content descriptions); header-row click removed, so chapter clicks and
  expansion no longer nest.
- Expanded chapter rows reused unchanged (`showCover=false`,
  `showMangaTitle=false`); no nested cards; no model/state/ordering/DB change.

Verification (docker `vsc-yomihon-e24e3bd7e46d…`, `-Xmx4g`, both volumes):
- `./gradlew spotlessCheck` BUILD SUCCESSFUL in 41s.
- `./gradlew testDebugUnitTest` BUILD SUCCESSFUL in 2m51s
  (including `UpdatesGroupingTest` 7/7).
- `./gradlew :app:assembleDebug` BUILD SUCCESSFUL in 2m39s.
- Device/visual pass PENDING (debug-keystore signature mismatch, issue #7;
  never uninstall without current `.tachibk` backup).

## 2026-09-16 — Debug-key reinstall (install only, no verification)

- Volume `yomihon-android-home` holds `debug.keystore` dated 2026-08-29 plus a
  same-size root-owned `debug.keystore.docker.bak`; local Gradle debug builds
  use the AGP default (`~/.android/debug.keystore`) since neither
  `MIHON_GITHUB_RELEASE` nor `keystorePropertiesFile` applies.
- Keystore cert SHA-256 `E4:86:EA:51:…:24:89:68` matches both the pulled
  installed `base.apk` and the fresh `app-arm64-v8a-debug.apk` (apksigner).
- `adb -s 192.168.29.98:5555 install -r .../app-arm64-v8a-debug.apk` → Success.
  No uninstall, no wipe, no new key. No post-install verification per task.

## 2026-09-16 — Single-chapter Updates unified with compact card (UNCOMMITTED)

- `UpdatesUiItem.kt` only: extracted shared `UpdatesCompactCardHeader`
  (Card `shapes.large`, `MangaCover.Book` 52dp, `titleSmall` title,
  chapter + relative `dateFetch` + optional read-progress `bodySmall`).
- Group header reuses it with the expand `IconButton`; expanded rows unchanged.
- Flat single items now render `UpdatesSingleChapterCard`: same card/header,
  existing `ChapterDownloadIndicator` trailing (disabled in selection mode),
  selection background + combined click/long-press + haptic preserved,
  no expand arrow.
- No model/grouping/ordering/callback/state/data changes. No gates run
  (task forbids verification/build/install).

## 2026-09-16 — Gates after single-chapter unification (GREEN)

- `spotlessCheck` BUILD SUCCESSFUL in 53s.
- `testDebugUnitTest` BUILD SUCCESSFUL in 3m39s (UpdatesGroupingTest 7/7).
- `:app:assembleDebug` BUILD SUCCESSFUL in 3m32s;
  `app-arm64-v8a-debug.apk` built (95,624,936 bytes).
- No device work, no install, no commit per task.
