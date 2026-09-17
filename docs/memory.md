# Yomitsu — Active Memory (Recent Sessions)

> COMPRESSED ACTIVE MEMORY. Contains recent sessions + durable decisions/gotchas.
> Do NOT read this during normal startup — use `state.md` + `rules.md` first.
> Full historical records in `history/session-logs.md`.

---

## Recent sessions (most recent first)

### 2026-09-16 — Feed auto-pagination + All Sources source headers (uncommitted)

User-authorized Q8: single-source auto-pagination (near-end threshold=5) + compact source headers in All Sources mode. FeedScreen.kt only — FeedScreenModel.kt untouched. `selectedSourceId != null` gates auto-pagination (not visibleFeeds count — correct for single-enabled-source edge case). `lastOrNull()` triggers for the last visible feed section. Source headers: `labelMedium`, 8dp/4dp padding, full-span, no divider. Tests: 2 new in FeedScreenModelStateTest (9/9 total). Gates green: spotlessCheck 35s, testDebugUnitTest 3m45s, :app:assembleDebug 4m4s. Device verification PENDING.

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
