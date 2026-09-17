# Yomitsu — Master Implementation Roadmap (Canonical Execution Register)

> **THIS FILE IS THE SINGLE CANONICAL EXECUTION REGISTER.** Produced by the
> 2026-09-12 master audit session (full repo audit + reference-repo analysis
> + documentation reconciliation). It supersedes every other "what next"
> document (see §K Documentation audit for their authority downgrades).
>
> **AGENT EXECUTION LOCK — read this before ANY implementation work:**
> - Agents MUST read this file before performing implementation work.
> - Agents MUST execute only the CURRENT AUTHORIZED TASK (§B).
> - Agents MUST NOT independently select a task from memory.md, phase.md,
>   next-phase-plan.md, old audit documents, reference repositories, or
>   previous conversations while this roadmap contains a current authorized
>   task.
> - Agents MUST NOT implement REJECTED (§F), DEFERRED (§G), or USER DECISION
>   REQUIRED (§H) items without explicit authorization.
> - If implementation reveals a contradiction: STOP. Record the contradiction
>   in memory.md. Do not silently change scope, rewrite architecture, or
>   replace this roadmap.
>
> Verification vocabulary: VERIFIED / PARTIALLY VERIFIED / DOCUMENTED ONLY /
> CODE EXISTS / DEVICE VERIFIED / NOT VERIFIED / UNKNOWN. Never claim a state
> this file does not evidence.

---

## A. Current baseline

| Item | State |
|---|---|
| App version | v0.5.4, versionCode 30 (tag `v0.5.4` = commit `9f228d07c`, RELEASED 2026-09-12, GitHub Latest, 5 ABI APKs) |
| Branch / HEAD | `main` @ `9f228d07c` (tag v0.5.4) — 3 commits past v0.5.3 tag; tree clean post-release docs updates only |
| Last verified build | 2026-09-12 (Q1 session): `spotlessCheck + testDebugUnitTest + verifySqlDelightMigration` BUILD SUCCESSFUL 3m; `assembleRelease -Pinclude-telemetry -Penable-updater` BUILD SUCCESSFUL 12m23s (docker devcontainer, JDK17, -Xmx4g, both volumes) |
| Last device verification | 2026-09-12: artwork-tone tray (stream-wait fix + 20%/2.5× tuning) verified on SM_M066B via adb script; user tone sign-off pending final visual (screenshots staged). Batch 7 toolbar USER-VERIFIED 2026-09-11. v0.5.3 smoke PASS 2026-09-11 |
| Known release state | Healthy: all 4 CI-order gates green; APK contents verified (ocr_fast ×2 + panel_detector packaged; legacy assets/ocr/ absent; label 'Yomitsu') |
| Unresolved issues | See §Bug register (2×P2, 3×P3, ~15×P4/minor; NO P0/P1) |
| Uncommitted work | Artwork-reactive tray set (7 files incl. 2 new + docs) — COMPLETE + gates green + device-verified, awaiting user commit decision. Known-issue #2 (dual compose blocks) was found RESOLVED in code by this audit — docs were stale |
| Environment | Host has no SDK; use docker image `vsc-yomihon-e24e3bd7e46d…`, `-Xmx4g`, mount BOTH volumes (`yomihon-gradle-home`, `yomihon-android-home` at `/home/vscode/.android`) |

**Product identity (immutable):** manga/comic reader first; reader-first; OCR-assisted
reading; TTS/read-aloud; dictionary/language-learning; library/source/feed
ecosystem; Material 3 Expressive minimal/editorial UI; performance-conscious.
NOT: anime/video/novel/media-center/gamification app. Reference projects are
idea sources only — the final product stays recognizably Yomitsu (docs/branding.md).

---

## B. Current authorized task

There is exactly ONE.

```text
CURRENT AUTHORIZED TASK:
    Q8 FEED AUTO-PAGINATION 2026-09-16 (user-authorized):
    IMPLEMENTED + GATES GREEN — device verification PENDING.
    FeedScreen.kt: auto-pagination (selectedSourceId != null → near-end
    threshold 5 → existing loadMore), compact source headers in All Sources
    mode (labelMedium, 8dp/4dp padding, full-span, no divider).
    FeedScreenModel.kt: UNTOUCHED. FeedScreenModelStateTest: 2 new tests
    (9/9 total). Existing filtering/paging/section-state semantics unchanged.
    Scope: FeedScreen.kt, FeedScreenModelStateTest.kt only.

    ANYMEX UI MODERNIZATION MICRO-BATCH 2026-09-13 (user-authorized,
    post-v0.5.4.1): smallest safe visual-correction set EXECUTED +
    device-verified — DS-01 (SettingsDictionaryScreen OCR-results group
    → PreferenceGroupCard), DS-03 (SettingsSearch 24/14dp → 16/12dp
    token rhythm), DS-06 (Feed customize icon List → GridView), DS-02
    (cover scrim literal → colorScheme.scrim token), FeedCustomizeDialog
    card gaps 8→12dp grouped rhythm. UNCOMMITTED — awaiting user commit
    decision. Scope locks held: no Liquid Background, no blur, no IA
    regroup, no navigation changes, no Q3-Q8, no DB/deps.

    MASTER SESSION 2026-09-13 COMMITTED by user (6 commits on b2f1da316:
    345fcb67d, ed368bf6a, 0553a8a78, 81811dcd8, 4f7a9e51b, 64e0103af).
    Q9 a11y + BUG-003/004/005/006/008/009/010 + Tscan Feed fix +
    OCR/TTS ChapterCache latency + Feed genre filtering + AnymeX
    micro-passes — all executed + device-verified (see §M history).

    FEED UI CORRECTION 2026-09-13 (user-authorized follow-up,
    supersedes stacked-selector layout): (1) `All` listing chip removed
    from FeedFilterBar + Customize→Default listing (Popular/Latest
    only); legacy null defaultListing falls back to Popular at read
    (FeedScreenModel prefs collect, no migration, "All sources" dropdown
    untouched). (2) Compact single primary row: source selector left +
    Popular/Latest chips right (horizontalScroll for narrow screens).
    (3) Per-section duplicate source/listing header removed. (4) FilterBar
    moved INTO Lazy grid as first full-span item = natural collapse-on-
    scroll + return-on-scroll-up, no nested containers. (5) Genre chip
    row preserved below primary row. Gates green (docker JDK17 -Xmx4g:
    spotlessCheck, testDebugUnitTest, verifySqlDelightMigration,
    :app:assembleDebug). Device-verified SM_M066B: compact layout, no
    All, single-select + data match, genre toggle roundtrip, collapse/
    return, load-more, persistence across restart, legacy fallback,
    Manage Feeds intact. UNCOMMITTED — awaiting user commit decision.

    Q3–Q8 HARD HALTED by user (2026-09-13): no work on them. Q9 was the
    only authorized queue item this session.
```

---

## C. Completed implementation ledger (abridged — full history in memory.md)

| ID | Date | Objective | Commit | Build/Device | Evidence |
|---|---|---|---|---|---|
| L-01 | 2026-08-29 | TTS v1 Phases 1–9 | multiple (a071feedf, 5c7d2cc2c, 872c55397, 8cb320e7c, 41200022e, be31edb71) | gates green + device scripts 1–15 user-confirmed | memory.md Phase 8/9 blocks; .device-pass logs |
| L-02 | 2026-08-31 | Phase 10A voice config + picker search | 0480778fd | device PASS user-confirmed | tts-10a-test.log (571k lines) |
| L-03 | 2026-09-01 | Phases A–I (speech cleanup, classification, exclusions v1, voice profiles, 3x rate, Feed, dict nav) | c70e32252 + 1b810ccde | gates green | memory blocks |
| L-04 | 2026-09-02/03 | OCR exclusion regressions (toggle swap, matcher NFKC rework, ZONE semantics, leak fixes, prefill removal) | baf7f679b, 4543cf453 | gates green + device logs | ocr-excl-*.log series; matcher 35/35 |
| L-05 | 2026-09-03 | v0.5.2 release | 0286d9081 (tag v0.5.2) | local build 13m53s + smoke | GitHub release |
| L-06 | 2026-09-06/07 | Recent tab IA, Feed v2 + paging, grouped settings, frost chrome, visual passes | e89104296, e166cd16e, af5606a56, 333dafe3a | device visual passes 09-07 | screenshots r1..r12 |
| L-07 | 2026-09-07 | Yomitsu rebrand | 708a7182d | gates green + aapt2 label verified | branding.md |
| L-08 | 2026-09-08 | Stabilization batches (LEGACY OCR removal −133MB, Main-thread I/O fix, GLENS retry, cache retention 5000, hygiene) | (uncommitted then; released in v0.5.3) | device matrix A–J (F/J/onboarding opportunistic) | stabilize-verify.log 35.4MB |
| L-09 | 2026-09-08 | Full UI audit + ui-implementation-map.md | (docs; in v0.5.3) | docs-only | map D-01..D-15 |
| L-10 | 2026-09-09/10 | UI audit Batches 1–5 (Feed P0 fix D-02/D-03/D-04, D-01, D-05/07, D-08/10/11/12/13, device matrix) | 1b2c56b23 | device matrix PASS 65.7MB log | batch5-verify.log + screenshots |
| L-11 | 2026-09-10/11 | v0.5.3 release | daa942738 (tag) | gates green 3m24s + release 12m36s + device smoke PASS | v053-smoke.log |
| L-12 | 2026-09-11 | Decision micro-batch (7/8 open decisions closed; Create-tab open) | f112df5d4 (docs) | docs-only | map §29 + plan Part F |
| L-13 | 2026-09-11 | Accessibility pass (16 files: semantics, toggleable, cds) | 212a09c7b | device spot-verified | memory a11y block |
| L-14 | 2026-09-11 | Batch 6: Continue stale-state fix + Recent nested-toolbar cleanup | 1bd50510d | device-verified | batch6-verify.log |
| L-15 | 2026-09-11 | Batch 7: reader toolbar customization (drag-reorder, 13 tests) | 9126e20dc | USER-VERIFIED on device | toolbar-customize-test.log |
| L-16 | 2026-09-11/12 | Artwork-reactive reader tray (stream-wait fix + 20%/2.5× tuning) | 0434d07a1 (in v0.5.4) | gates green + device-scripted verify | /tmp/opencode/toned-menu-v2.png etc. |
| L-17 | 2026-09-12 | Q2 genre-chip search (BrowseSourceScreen chip row over source Filter leaves + 10 unit tests) | UNCOMMITTED (2 src files + 1 test file + docs) | gates green; device Q2-01..09 PASS on SM_M066B (build 0.5.4-8281) | .device-pass/q2/ dumps + this §M |
| L-18 | 2026-09-13 | Master session: Q9 a11y + BUG-003..010 + Tscan fix + OCR/TTS ChapterCache + Feed genre chips + AnymeX micro-passes | 345fcb67d..64e0103af (6 commits, user-authorized) | gates green + device-verified | §M history 2026-09-13; .device-pass/master-session-*.log |
| L-19 | 2026-09-13 | Feed UI correction: remove `All` listing, compact [Source][Popular][Latest] row, remove duplicate section header, collapse-on-scroll (filter bar as grid item), legacy null→Popular fallback | COMMITTED as 35a78cb7e (FeedScreen.kt + FeedScreenModel.kt + docs); closeout-audited 2026-09-13 (gates re-green, device smoke PASS, pref caveat resolved) | gates green + device-verified (matrix §M) | uiautomator dumps feed-*.xml session 09-13 |

---

## D. Reference feature register (Tadami / AnymeX / Chimahon)

Evidence basis: reference repos cloned + inspected 2026-09-12 (Tadami-Aniyomi-fork @ e34f353, AnymeX @ a3cfde7, chimahon @ 091ee6a v2.4.1). Yomitsu equivalents verified against Yomitsu source this session.

### Tadami candidates

| ID | Feature | Ref location | Compatibility / impact | Recommendation |
|---|---|---|---|---|
| REF-TAD-001 | Genre/tag chips on title screen → tap searches that genre in the same source (pop-to-existing-browse-stack pattern) | `AnimeScreen.kt:624` performGenreSearch; `AuroraHeroComponents.kt:225` AuroraHeroGenreChips; manga equivalent in MangaScreenAurora | High value, LOW risk: Yomitsu MangaScreen already renders genre rows; missing piece = clickable → BrowseSourceScreen(source, query=genre). No new architecture. Reader untouched | **ADAPT — queue Q2** |
| REF-TAD-002 | 3×3 custom tap-zone editor (tap cell → cycle action; serialized token layout) | `ReaderTapZonesEditor.kt` + navigation token parse/serialize | Yomitsu has 5 fixed navigation kinds only (navigation/ package). Full editor = new pref + viewer-navigation surface; touches reader nav (protected-adjacent) | **INVESTIGATE — needs user + reader-nav review** |
| REF-TAD-003 | Aurora title-card hero (oversized cover, gradient, recent-blocks Home hub) | `MangaSeriesAuroraContent.kt`, `entries/components/aurora/` | Contradicts Yomitsu content-first M3 identity (design.md §1); MangaScreen already reworked along own language | **REJECT (identity)** |
| REF-TAD-004 | Anime/novel/reels/achievements subsystems | `ui/player`, `ui/novel`, `ui/reels`, `ui/achievement` | Not a manga reader | **REJECT (identity)** |
| REF-TAD-005 | Reading-speed tracker (ReadingSpeedTracker) | `ui/reader/ReadingSpeedTracker.kt` | Stats-adjacent novelty; no PRD requirement; gamification-adjacent | **REJECT (scope)** |
| REF-TAD-006 | Download-information display polish (per-chapter size/progress in title screen) | Tadami title cards | Yomitsu already shows download badges + queue; marginal | **ALREADY COVERED** |
| REF-TAD-007 | Reader preload manager tuning (ReaderPreloadManager) | `ui/reader/ReaderPreloadManager.kt` | Yomitsu ChapterLoader already preloads next 4 pages; no evidence of deficiency | **ALREADY COVERED** |
| REF-TAD-008 | Adaptive/display settings for Home interactions (Aurora customization toggles) | Tadami display settings | Tied to REF-TAD-003 hero identity | **REJECT (identity)** |

### AnymeX candidates

| ID | Feature | Ref location | Compatibility / impact | Recommendation |
|---|---|---|---|---|
| REF-ANY-001 | Organized Appearance settings (theme + UI customization sub-settings tree) | `lib/screens/settings/sub_settings/settings_theme.dart`, `settings_ui.dart` | Yomitsu SettingsAppearanceScreen already organized + grouped (device-verified); nothing to import | **ALREADY COVERED** |
| REF-ANY-002 | Theme profiles/presets (save-switch app themes) | `settings_theme.dart` | Yomitsu has 15 schemes + Monet + AMOLED; profile layer = speculative. Voice profiles already exist for TTS | **DEFER — no demand evidence** |
| REF-ANY-003 | Per-mode tap-zone profiles (paged / pagedVertical / webtoon layouts persisted separately) | `settings_tap_zones.dart`, TapZoneRepository | Same family as REF-TAD-002; AnymeX version is Flutter; Yomitsu nav kinds cover main need | **INVESTIGATE (merged with REF-TAD-002)** |
| REF-ANY-004 | Reader control themes (iOS-style vs default bottom-bar variants) | `reader_control_themes/` | Contradicts frozen reader chrome identity; TtsPlaybackBar/nav language already unified | **REJECT (identity)** |
| REF-ANY-005 | Reader chapter-transition overlay polish | `reader_chapter_transition.dart` | Yomitsu has ChapterTransition page; adequate | **ALREADY COVERED** |
| REF-ANY-006 | Multiservice tracking hub (AniList/MAL/Simkl focus) | whole app | Yomitsu has 11 trackers; tracking is source-side + existing sheets | **ALREADY COVERED / REJECT (scope: no Simkl case without demand)** |
| REF-ANY-007 | Chapter-range/scanlator filter dialogs in title screen | `chapter_ranges.dart`, `scanlators_ranges.dart` | Yomitsu already has ScanlatorFilterDialog + ChapterSettingsDialog (verified in source) | **ALREADY COVERED** |
| REF-ANY-008 | AI/chat/news/community/novel subsystems | `lib/screens/{ai,community,news,novel}` | Not a manga reader | **REJECT (identity)** |

### Chimahon candidates

| ID | Feature | Ref location | Compatibility / impact | Recommendation |
|---|---|---|---|---|
| REF-CHI-001 | Recursive dictionary lookup (lookup-within-lookup, tabs/stack) | `OcrLookupPopup.kt:170-187` LookupStackState | HIGH value, fits identity (dictionary core). Yomitsu popup has no recursion; OcrResultOverlay could gain tap-in-definition → new lookup. Medium complexity (WebView/popup reuse) | **ADAPT — queue Q3 (needs design)** |
| REF-CHI-002 | Dictionary lookup history/favorites | Chimahon dict tab persistence | Fits language-learning identity; needs DB migration (main DB) → PRD + .sqm gate | **INVESTIGATE — after Q3 design** |
| REF-CHI-003 | OCR → dictionary flow (already Yomitsu's own lineage — tap-lookup, drag-OCR) | `OcrLookupPopup.kt` | Yomitsu originated/exceeds this (exclusions, profiles, popup styles) | **ALREADY IMPLEMENTED (Yomitsu-led)** |
| REF-CHI-004 | E-Ink / high-contrast dictionary popup mode | `DictionaryPreferences.eInkMode()` (pref_dictionary_eink_mode) → bootstrap HTML attr | LOW cost, fits reader identity; Yomitsu popup is Compose-native (no WebView bootstrap), so adapt = a high-contrast popup style pref, not a port | **ADAPT — small (popup style pref)** |
| REF-CHI-005 | Vocabulary capture workflow (mining → Anki with markers: expression/reading/gloss/pitch/context) | `chimahon/anki/` AnkiProfile, OcrLookupPopup field mapping | Yomitsu already ships AnkiDroid export with field mapping + DictionaryTermCard (verified) | **ALREADY COVERED (verify marker completeness in 10B-class review if user wants)** |
| REF-CHI-006 | Screenshot/context-sentence capture for cards | `OcrLookupPopup` screenshot/onCropTriggered params | Yomitsu has OCR text + region context; image crop capture for Anki = new capability (media files). Medium; user data path | **INVESTIGATE — needs PRD mini-spec** |
| REF-CHI-007 | Screen-OCR lookup from ANY app (ProcessText + quick-tile service) | `ProcessTextLookupActivity.kt`, `ScreenLookupService.kt` | New service + permissions (SYSTEM_ALERT_WINDOW/quick-tile) — rules §8 permission gate; out of reader-first scope unless user demands | **REJECT (permissions/scope) unless USER DECISION** |
| REF-CHI-008 | Anime/MPV/YouTube/Jimaku subsystems | `ui/player`, `ui/youtube`, `AniyomiMPVView` | Not a manga reader | **REJECT (identity)** |
| REF-CHI-009 | Novel/EPUB reader | `chimahon/novel/` | Yomitsu is not a novel reader (PRD identity) | **REJECT (identity)** |
| REF-CHI-010 | .mokuro support (pre-rendered OCR overlay files) | Chimahon local source | Niche local format; existing local source covers folders/CBZ/EPUB; OCR pipeline exists — .mokuro = JSON-box ingestion, medium cost | **DEFER — no demand evidence** |
| REF-CHI-011 | Pitch-accent visualization | Yomitsu already has PitchAccentGraph/PitchAccentFormatter (verified in source) | — | **ALREADY IMPLEMENTED** |
| REF-CHI-012 | Local OCR engine (chimahon-local-ocr module) | `chimahon-local-ocr/` | Yomitsu deliberately removed legacy local OCR (−133MB) and redirects to GLENS; reinstating a local engine = reversing a shipped optimization | **REJECT (contradicts shipped decision)** |

**Previously ruled (from next-phase-plan Part C, still binding):** grouped settings cards ADOPTED (shipped); artwork-reactive tray ADAPTED (shipped L-16); reader toolbar reordering SHIPPED (L-15); true backdrop blur REJECT until feasibility proven; neon/cyberpunk/glass REJECT; nav customization REJECT; standardized reselect REJECT; Browse search tab REJECT (reverted once); Feed auto-infinite-scroll DEFER→eligible; anime-ecosystem trackers REJECT; Library page-level Continue REJECT (reverted twice).

---

## E. Future ordered queue

Strictly ordered. Each item gets its own batch + gates + device verify.

```text
STATUS 2026-09-13: Q3, Q4, Q5, Q6, Q7, Q8 HARD HALTED by user — do
    not work on them without new authorization. Q9 EXECUTED in the
    2026-09-13 master session (see §M history). Queue order below
    preserved for whenever the halt lifts.

NEXT (was): Q3. Recursive dictionary lookup design + implementation
    (REF-CHI-001): design pass first (popup interaction, back-stack,
    term-chaining UI), user approval of design (U-5), then implement in
    OcrResultOverlay family.
  Q3. Recursive dictionary lookup design + implementation (REF-CHI-001):
    design pass first (popup interaction, back-stack, term-chaining UI),
    user approval of design, then implement in OcrResultOverlay family.
 ↓
Q4. E-Ink/high-contrast dictionary popup style (REF-CHI-004, small).
 ↓
Q5. Tap-zone customization investigation (REF-TAD-002 + REF-ANY-003):
    feasibility study ONLY (reader nav is protected) → user gate →
    implement or reject.
 ↓
Q6. Dictionary history/favorites (REF-CHI-002): PRD mini-spec + .sqm
    migration design → user gate → implement.
 ↓
Q7. Phase 10B track (per-item PRD + architecture review, per phase.md):
    smallest-first candidates: per-voice rate/pitch tuning, JP opt-in
    preflight, libLiteRt GPU-lib exclusion (~2.8MB/ABI, test in release
    batch), cross-tile seam merge, Glens-ordering port to local scan.
 ↓
Q8. Feed auto near-end pagination (eligible; Load-more device-stable since 09-06).
 ↓
Q9. A11y completion micro-batch (CategoryListItem drag actions, BaseSliderItem
    label, SourceSelectorDropdown check cds, spinner cds) + large-font/sr
    full sweep — schedule with any device session. **EXECUTED 2026-09-13**
    (see §M history; CategoryListItem move actions, BaseSliderItem
    stateDescription, SourceSelectorDropdown + TtsPlaybackBar cds,
    heightIn large-font sweep ×4 files).
 ↓
LATER: Anki screenshot/context capture (REF-CHI-006 PRD first); ResizableSheet
    24dp variant unification; Glance widget #12 (complaint-driven); D-15
    Library reselect affordance (IA gate).
```

---

## F. Rejected feature register

| Feature | Source | Reason | Permanent? | User-rejected? |
|---|---|---|---|---|
| Anime/video/MPV/YouTube player | Tadami/Chimahon | Identity: not a manga reader | Yes (identity) | — (identity rule) |
| Novel/ranobe/EPUB reading ecosystem | Tadami/Chimahon | Identity: not a novel reader (PRD §1) | Yes (identity) | — |
| Reels/shorts, achievements/gamification | Tadami | Identity + gamification exclusion | Yes (identity) | — |
| Aurora hero/edge-ring visual identity, neon/cyberpunk/glass-everywhere | Tadami | Contradicts design.md §1 + §16 identity filter | Yes (identity) | Implicitly (design ratified) |
| Reader control theme variants (iOS-style) | AnymeX | Frozen reader chrome language | Yes (identity) | — |
| True backdrop blur | Tadami-track | Rendering arch (Compose can't sample sibling) + perf; deferred #2 verdict stands | Until feasibility proven | Previously ruled |
| Navigation/tab customization, 6th tab | AnymeX | Frozen 5-tab IA + reselect semantics | Yes (IA) | Yes (map §27 gate 7) |
| Standardized reselect (scroll-to-top) | — | Destroys intentional per-tab semantics | Yes | Yes (2026-09-06) |
| Browse Search tab | Chimahon-style | Shipped once, broke Extensions routing, reverted 2026-09-04 | Yes | Yes |
| Library page-level Continue section | — | Reverted twice; per-item button is approved form | Yes | Yes (×2) |
| Screen-OCR lookup from other apps | Chimahon | New services + permissions vs reader-first scope | Revisit only on explicit user request | Not yet |
| Local OCR engine reinstatement | Chimahon-local-ocr | Reverses shipped −133MB optimization + redirect design | Yes (supersedes) | — (architecture) |
| Anime-ecosystem tracker expansions (Simkl etc.) | AnymeX | Scope; 11 trackers suffice | No (demand-gated) | Not yet |
| Theme profiles/presets app-wide | AnymeX | 15 schemes + Monet suffice; speculative layer | No (demand-gated) | Not yet |

---

## G. Deferred feature register

| Feature | Source | Why deferred | Unblocks when |
|---|---|---|---|
| Feed automatic near-end pagination | — | Was stability-gated; now eligible (Load-more stable ×3 device passes) | Q8 slot |
| Tap-zone editor (custom zones) | Tadami/AnymeX | Reader nav protected; needs feasibility + user gate | Q5 investigation |
| Dictionary history/favorites | Chimahon | Needs PRD + DB migration design | Q6 |
| Anki screenshot/context capture | Chimahon | Needs PRD mini-spec (media path) | LATER |
| .mokuro local format | Chimahon | No demand evidence; niche | User request |
| Phase 10B heavy items (cloud/neural TTS, AI voices, expressive speech, FGS+MediaSession background playback, on-image bbox highlight, audio caching) | — | PRD-gated backlog (prd §6.4, phase.md 10B) | Per-item PRD + arch review |
| ResizableSheet geometry unification | — | LOW cosmetic | Any UI batch |
| Onboarding PermissionStep device test | — | Needs fresh install (user-data wipe forbidden) | Next fresh-install/emulator window |
| GLENS retry live-verify / OCR eviction boundary (5000 pages) | — | Need natural 502 / 5000 pages | Opportunistic log watch |
| Debug build tone-log removal note | — | **Resolved by audit**: tone logs are DEBUG priority → suppressed in release already; only 3 INFO logs ship (Glens timings, TTS startup). No action needed unless user wants silence | Optional RM-01 tweak |
| Liquid Mode / Liquid Background (AnymeX theme system) | AnymeX | **Designed 2026-09-13, deferred**: opaque Scaffold containers hide any background layer — visible background requires translucent containerColor work across all screens (blast radius = every Scaffold surface) + its own device pass. RECIPE (when unblocked): theme-derived gradient (surfaceContainerLow→surfaceContainerHigh, subtle vertical) at root composition; per-screen containerColor audit is the prerequisite. Grain texture/OLED/poster-color sub-options considered OUT OF SCOPE until base background ships. Same §34 stop-condition logic as backdrop blur (perf-adjacent) | Own future batch after user authorization; prerequisite = translucent-container audit |
| AnymeX settings IA (Accounts&Sync / Preferences&System / Appearance&Interface / Media&Playback / Extensions&Diagnostics regroup) | AnymeX | Rejected for now: Yomitsu settings IA is stable + indexed (search depends on it); regroup = churn without demand evidence | User request |

---

## H. User decision register

| # | Decision | Status | Context |
|---|---|---|---|
| U-1 | "Create" tab referent (user referenced a tab that does not exist) | **OPEN** | Never clarified; plausible referents = Recent "Continue" tab or Feed add-dialog. Build nothing until clarified |
| U-2 | Artwork-tone set: commit now (with RM-01) or hold? | **RESOLVED 2026-09-12** | User committed RM-01 + tone together as 0434d07a1; shipped in v0.5.4 |
| U-3 | Tone visual sign-off (final human eyeball of tinted chrome) | **RESOLVED 2026-09-12** | Tone strength ratified (20%/2.5×), shipped in v0.5.4 unchanged; user authorized release containing it |
| U-4 | Genre-chip search (Q2) — approve as next feature after RM-01/v0.5.4? | **RESOLVED 2026-09-12** | User-authorized Q2 directly; IMPLEMENTED + device-verified, uncommitted awaiting user commit decision |
| U-5 | Recursive dictionary lookup (Q3) — approve design pass? | OPEN (recommend YES) | Highest-value Chimahon idea within identity |
| U-6 | DEBUG/INFO TTS+OCR timing logs in release: keep (harmless diagnostics) or downgrade to DEBUG? | OPEN (recommend keep) | Only 3 INFO lines ship; rules §7-compliant (no text content) |
| U-7 | Dictionary card row `clickable{}` no-op: make row open the term, or remove affordance? | OPEN (recommend: remove clickable or wire to add) | UI-audit MINOR |
| U-8 | ManageFeedsScreen network-fetch waste: lazy sections on management screen? | OPEN (recommend fix in Q2-class batch) | UI-audit MINOR |
| U-9 | SettingsSearch loading-state gap (blank flash) + toolbar-screen index registration | OPEN (recommend fold into next UI micro-batch) | UI-audit MINOR |

Resolved by earlier micro-batches (binding, do not reopen without new evidence):
TtsPlaybackBar bodyMedium + 16/4 RATIFIED; FeedFilterBar arrow NO ACTION; FeedHeader
two-line KEPT; D-09/D-14 NO ACTION; D-15 FUTURE-only.

---

## I. Design audit (discrepancy register, current)

| DESIGN-ID | Screen/Component | Expected | Actual | Severity | Status |
|---|---|---|---|---|---|
| DS-01 | SettingsDictionaryScreen OCR-results group (`:603-623`) | PreferenceGroupCard grouped surface | ~~Loose PreferenceGroupHeader in plain Box~~ | MINOR | **RESOLVED 2026-09-13** (AnymeX UI micro-batch): now PreferenceGroupCard; device-verified |
| DS-02 | CommonMangaItem.kt:132 | Token colors | ~~`Color(0xAA000000)` cover scrim literal~~ → colorScheme.scrim.copy(0.67f) | MINOR | **RESOLVED 2026-09-13** (AnymeX UI micro-batch) |
| DS-03 | SettingsSearchScreen.kt:240 | 16dp token rhythm | ~~24/14dp literal paddings~~ → 16/12dp | MINOR | **RESOLVED 2026-09-13** (AnymeX UI micro-batch) |
| DS-04 | RecentTab badge | Badge renders | `RecentTabContent.badgeNumber` dead param — PrimaryTabRow badge never populated | MINOR (dead code) | RM-01 item 4 |
| DS-05 | ReaderBottomBar.kt:41 | No dead modifiers | `pointerInput(Unit){}` no-op | MINOR (dead code) | RM-01 item 4 |
| DS-06 | Feed customize AppBar action | Icon/label coherence | ~~List icon paired with "Settings" title~~ → GridView icon | MINOR | **RESOLVED 2026-09-13** (AnymeX UI micro-batch) |
| DS-07 | MoreScreen logo / Dictionary empty states | Token spacing | 32dp isolated literals | MINOR | Documented tolerance |

Typography sweep: ZERO new `.sp` violations (all 9 hits documented exceptions).
Screen-level 16dp paddings: consistent in value, bypass token API (~91 sites) —
cosmetic debt, no visual inconsistency. Frost roles, nav pill, grouped cards,
pill language: all CONFORM (device-verified 09-07..09-11 passes).

Previously registered D-01..D-15: all RESOLVED or INFO-closed (see
ui-implementation-map.md §21; do not redo).

---

## J. Architecture audit

| Area | Verdict | Notes |
|---|---|---|
| Module layering (:app/:domain/:data/:source-api/…) | **KEEP** | Clean, verified; no cycles |
| Injekt DI | **KEEP** | Not up for discussion (no Hilt/Koin migration) |
| Voyager navigation + 5-tab IA + per-tab reselect | **KEEP (frozen)** | Semantics documented; standardization rejected |
| TtsEngine interface in :domain + AndroidTtsEngine isolated | **KEEP** | Future engines = Injekt swap |
| TtsPlaybackController event-channel → ReaderActivity pattern | **KEEP** | Two P2 seam bugs found (pause guard, chapter-advance failure) — fix in place, no redesign |
| OCR engine chain (GLENS primary, FAST local, OWOCR self-host; LEGACY alias→GLENS) | **KEEP** | Redirect design verified; local-engine reinstatement rejected |
| OCR cache (delete-if-outdated DB, 5000-page prune) | **KEEP (one gap)** | Read path ignores ocr_model (bug OCR-P3-4) — fix candidate in a 10B-class batch |
| OCR exclusion matcher (pure :domain, NFKC, 35 tests) | **KEEP** | Verified correct this session |
| Speech pipeline (dedup→classify→filter→clean→segment) | **KEEP** | Immutability + placement verified |
| Reader overlay composition (single tree, z-order contract) | **KEEP** | Known-issue #2 found RESOLVED in code — docs were stale |
| SQLDelight schemas (main 19 migrations + ocr_cache) | **KEEP** | verifySqlDelightMigration green |
| Backup format + .tachibk + persisted names | **KEEP (frozen)** | branding.md compatibility category |
| FeedScreenModel (state+sentinel+fallback chain) | **KEEP** | Batches 1–5 device-verified |
| ManageFeedsScreen FeedScreenModel reuse | **INVESTIGATE** | Instantiation triggers network fetches for all feeds — waste; consider lazy/param-gated sections |
| detectionEngine() dead branch + scanLocally/cropBitmap (~60 lines) | **CHANGE (delete)** | Unreachable pending real DetOcrEngine; RM-01-adjacent (optional, keep if a panel-detector engine lands — ponytail note already marks ceiling) |
| AndroidTtsEngine setVoice result ignored | **INVESTIGATE (P4)** | Add status check when voice config next touched |

---

## K. Documentation audit

| Document | Status | Authority | Required update |
|---|---|---|---|
| **implementation-roadmap.md (this file)** | NEW, canonical | **AUTHORITATIVE** for all next-work decisions | Maintain §M on every change |
| state.md | NEW 2026-09-16 (docs-compression restructure) | **CURRENT REPOSITORY STATE** (smallest startup file) | Update on every meaningful change |
| memory.md | Compressed 2026-09-16: recent sessions + durable knowledge; full chronological history moved to history/session-logs.md (verbatim archive) | ACTIVE RECENT MEMORY + durable knowledge | Update with recent delta; archive older detail |
| rules.md | §2 documentation/state-management protocol added 2026-09-16 | AUTHORITATIVE (MUST) + doc protocol | None |
| history/session-logs.md | NEW 2026-09-16 (append-only archive; contains former memory.md verbatim) | HISTORICAL EVIDENCE ONLY — never execution authority | Append detailed session records |
| phase.md | Current (pointer = artwork tray complete) | SECONDARY (history + 10B backlog) | Pointer update after RM-01; no structural change |
| next-phase-plan.md | 2026-09-10 content, partially stale (Baseline says "v0.5.3 release = next" but v0.5.3 shipped; Part B row 12 cites resolved-as-open issue #2) | **SUPERSEDED** by this roadmap for all next-task decisions | Add supersession banner pointing here; correct Part B row 12; keep as historical plan evidence |
| ui-implementation-map.md | Current through Batch 4/5 + 09-11 closures | AUTHORITATIVE for UI implementation specs | No change now; add ReaderArtworkTone + toolbar screen to §13/§14 when RM-01 docs pass runs (optional) |
| prd.md | v0.5.3 header; accurate | AUTHORITATIVE (WHAT) | None (audit found no factual contradiction) |
| architecture.md | v0.5.2-era header, §accurate otherwise | AUTHORITATIVE (HOW) | Header version bump + Known-issue #2 note + tone-feature paragraph when RM-01 docs run |
| design.md | Current (TtsPlaybackBar rows ratified 09-11) | AUTHORITATIVE (LOOK/FEEL) | None; add tone-tray paragraph optional |
| design-audit.md | 2026-09-04 | HISTORICAL (superseded by ui-map §21/§24) | None (keep as evidence) |
| branding.md | Current | AUTHORITATIVE (brand) | None |
| Prompt.md | User's own spec archive | USER-OWNED evidence | Do not touch |

---

## L. Verification matrix

| Feature | Unit | Integration | UI-test | Device | Visual | Current state |
|---|---|---|---|---|---|---|
| Sentence segmentation / advance policy | SentenceSegmenterTest, TtsAdvancePolicyTest | — | none | script steps 1–15 | — | VERIFIED |
| Speech pipeline (clean/classify/dedup) | SpeechCleaner/Classifier/Filter/Dedup suites | — | none | device logs | — | VERIFIED |
| OCR exclusion matcher | OcrExclusionMatcherTest 33 cases (grep count; 35 per memory — recount confirms 33 test fns; treat 33 as current) | — | none | exclusion logs verify2 + stabilize G | — | VERIFIED (manual matrix §M-a below) |
| TTS controller orchestration | none by design (thin, no Robolectric) | — | none | device matrix 08-28..09-12 | — | DEVICE VERIFIED (2 P2 seam gaps → RM-01) |
| AndroidTtsEngine | none (framework-thin) | — | none | device 10A pass | — | DEVICE VERIFIED |
| OCR engines + cache | OcrScanManagerTest, OcrScanStoreSerializerTest; androidTest @Ignore (device-gated) | — | none | stabilize A–J | — | VERIFIED (F/J opportunistic) |
| Feed model | FeedScreenModelStateTest 9 | — | none | Batch 1/2/5 matrices | pixel-band | VERIFIED |
| Continue model | ContinueScreenModelStateTest 7 | — | none | Batch 6 device | — | VERIFIED |
| MangaScreen error | MangaScreenModelErrorStateTest 4 | — | none | success-path only | — | PARTIALLY VERIFIED (missing-path unit-only, untriggerable live) |
| Reader toolbar action model | ReaderBottomBarActionTest 13 | — | none | user-verified | — | VERIFIED |
| Artwork tone | ReaderArtworkToneTest 10 | — | none | scripted adb verify | pixel deltas (+user eyeball pending U-3) | VERIFIED pending U-3 |
| Backup/restore incl. exclusion zones | none automated | — | none | manual round-trip (09-01 checklist item 9) | — | PARTIALLY VERIFIED |
| UI screens (all) | — | — | none exist in repo | per-batch matrices through 09-12 | screenshots per batch | VERIFIED per batch |
| Full gates | — | CI order | — | — | — | GREEN 2026-09-12 (this session) |

### M-a. OCR exclusion manual test matrix (per master-audit spec)

The 20-case matrix below is covered by: OcrExclusionMatcherTest (matching
semantics), device logs (integration), and documented manual procedures
(device). Status per case:

| # | Case | Coverage | Status |
|---|---|---|---|
| 1 | exact WORD match | unit `word rule excludes only standalone token` | VERIFIED |
| 2 | partial WORD mismatch | unit (`ion` ≠ `combination`) | VERIFIED |
| 3 | PHRASE with spaces | unit `phrase rule matches case and whitespace tolerant` | VERIFIED |
| 4 | PHRASE vs OCR spacing noise | unit `url-like text with ocr spacing noise` | VERIFIED |
| 5 | PHRASE with punctuation | unit space↔punct cross cases + nakaguro | VERIFIED |
| 6 | normalized Unicode (NFKC full-width) | unit WORD + PHRASE fold cases | VERIFIED |
| 7 | ZONE inside region | unit overlap cases | VERIFIED |
| 8 | ZONE outside region | unit `non-overlapping regions survive` | VERIFIED |
| 9 | rotated page | original-dims guard REJECTS zone creation (split/rotate/merge) — error path, by design | VERIFIED (guard, ReaderActivity.kt:1265-1278) |
| 10 | split page | same guard — honest rejection; full inverse-transform deferred | VERIFIED (guard); inverse mapping NOT IMPLEMENTED (documented follow-up) |
| 11 | multiple zones | unit `multiple zones on one page` | VERIFIED |
| 12 | disabled rule | unit + device (rule 10 toggle logs) | VERIFIED |
| 13 | global rule (WORD/PHRASE manga_id=0) | zonesForSpeech SQL + device logs | VERIFIED |
| 14 | manga-specific rule | unit scope cases + device | VERIFIED |
| 15 | chapter-specific rule | unit chapter anchoring | VERIFIED |
| 16 | COMBINED behavior | unit (rect AND text, scope table) | VERIFIED |
| 17 | crop OCR | crop path removed with prefill deletion (09-03); detect flow deleted — N/A now | N/A (feature removed) |
| 18 | cached OCR (new rules apply on cached pages) | playback-time filtering design + device logs (ch2145 0/16 stable across 1h47m) | VERIFIED |
| 19 | fresh OCR | device logs (excluded counts per fresh scan) | VERIFIED |
| 20 | fallback OCR | LEGACY/FAST→GLENS redirect; scan fallback chain | VERIFIED (stabilize matrix B) |

Known ceiling (documented, do not "fix" silently): phrase split across two
regions stays un-excluded (per-region matcher by design, v1); cross-tile seam
fragments (IoU 0.45); mid-page rule adds apply next page.

---

## M. Roadmap change history

| Date | Change | Author |
|---|---|---|
| 2026-09-12 | Created from master audit session: baseline L-01..L-16 ledger, reference register (24 candidates: 6 ALREADY-COVERED/IMPL, 4 ADOPT/ADAPT queued, 4 INVESTIGATE, 3 DEFER, 11 REJECT), RM-01 authorized task, queue Q1–Q9, doc authority model (next-phase-plan superseded), bug register (below), verification matrix incl. 20-case OCR matrix | opencode master-audit session |
| 2026-09-12 | RM-01 EXECUTED (unattended): BUG-001 pause guard fixed (pause works in any active non-Idle phase; Paused not clobbered by acquireSentences); BUG-002 fixed (loadAdjacent failure → controller.fail(ChapterLoadFailed) when Preparing/LoadingPage → Error + Retry; new TtsError.ChapterLoadFailed + i18n key); dead code swept (badgeNumber param + RecentTab badgeCount, ReaderBottomBar pointerInput no-op, detectionEngine identical branch + orphaned localOcrAvailable); docs corrected (memory Known-issue #2 → RESOLVED, architecture.md §3.8 historical note + header v0.5.3, next-phase-plan row + refs, this §M). Artwork-tone set UNTOUCHED + UNCOMMITTED (U-2 open). Gates all green (spotless 51s; unit+migration 4m29s; assembleDebug 3m20s). Device: APK 0.5.3-8275 installed on SM_M066B, app boots; full matrix NOT VERIFIED (device PIN-locked, no user present). Roadmap stays canonical; next task per queue = Q1 v0.5.4 release batch (after user commits) | opencode RM-01 session |
| 2026-09-12 | RM-01 DEVICE VERIFICATION (attended follow-up, SM_M066B USB, Limitless Predation ch6→ch7→ch8): TEST 1 TTS happy path PASS (play/progress/prefetch/pause-from-Playing/resume-exact-sentence); TEST 2 pause-during-LoadingPage PASS via onStop (`TTS pause page=4 sentence=0`, zero speech after); TEST 3 chapter-advance failure PASS on cold process (radios off → advance → UnknownHost → Error + exact tts_error_chapter_load + Retry; Stop-from-Error; recovery online → ch8 dispatch + advance; cold ch6→ch7 transition OK); TEST 4 toolbar PASS (5 actions render, Settings sheet, Crop toggle, no crash); TEST 5 artwork-tone PASS at log level (tone sample + schedule Ready per page, no crash; tint not eyeball-checked). Contradiction: pill shows Stop-only during Preparing/LoadingPage, no Pause affordance — TEST 2 used onStop path. Device left as found (radios re-enabled). RM-01 COMPLETE; still uncommitted. | opencode RM-01 session |
| 2026-09-12 | Q1 v0.5.4 RELEASE EXECUTED: user pre-committed RM-01+tone set as 0434d07a1 (U-2 = commit together, satisfied); gates green 3m; bump 0.5.4/vc30 (9b153610a); USER-REPORTED blocker pre-push — uncached OCR preload slow → root-caused and FIXED in 9f228d07c: PrioritizedTaskQueue bounded parallelism (3) + priority plumbing (OcrScanPriority; current page HIGH, prefetch NORMAL) + GLENS text-lock removed (stateless network engine) + parallel prefetch + depth 1→2 (elvis-precedence activeTasks bug also fixed after on-device negative-counter sighting; first superseded APK wedged once); 9 OCR unit tests updated/green; device-verified gap-free uncached playback + Error/Retry recovery; released tag v0.5.4 @ 9f228d07c, GitHub Latest, 5 ABI APKs, smoke PASS (v054-smoke.log + v054-smoke2.log). Residual ceiling documented: first-page GLENS round-trip 15-30s (service latency; 10B local OCR = upgrade path). Next: Q2 genre-chip search (U-4 approval pending). | opencode Q1 session |
| 2026-09-12 | Q2 GENRE-CHIP SEARCH EXECUTED (U-4 satisfied by direct user task authorization): genre chip row in BrowseSourceScreen (2 source files, +73/+33 lines) derived from the source's OWN Filter leaves (TriState/CheckBox inside Group or top-level) via pure helpers genreToggles()/isGenreSelected()/toggleGenreSelection() in BrowseSourceScreenModel.kt; toggleGenreChip flips INCLUDE↔IGNORE then search(filters=) re-runs (one pager rebuild per tap — FilterList data-class equals=false guarantees distinctUntilChanged fires once); no new architecture (option B: source-supported filtering; upstream searchGenre() MangaScreen→browse path untouched); M3 FilterChip + leading check icon = non-color-only selection; sources without genre filter leaves honestly show no row (Asura Scans verified); multi-genre = source's own semantics (filters passed through verbatim). 10 unit tests green (GenreTogglesTest); gates green (spotlessCheck + testDebugUnitTest + verifySqlDelightMigration + assembleDebug); device SM_M066B 0.5.4-8281: Q2-01..09 PASS (search unchanged; chips checked=true a11y + results genuinely filtered — gender-bender evidence; clear restores; query+genre combo narrows; empty+Retry intact; nav/back state preserved; a11y checked semantics verified; Browse/Library/Feed/Reader/More smoke OK). Known limitation: Weeb Central filter leaves exposed = status+type+genre; chips are quick toggles over ALL such leaves, not a hand-curated genre list. Next: Q3 recursive dictionary lookup design. | opencode Q2 session |
| 2026-09-13 | MASTER SESSION EXECUTED (user-authorized; Q3–Q8 HARD HALTED, Q9 + AnymeX-UI micro-track authorized): (1) Q9 a11y — CategoryListItem customActions move-up/down (+ CategoryScreen wiring, existing action_move_up/down strings), BaseSliderItem Slider stateDescription=valueString (~20 callers), SourceSelectorDropdown menu stateDescription selected/not_selected, TtsPlaybackBar speed menu stateDescription, large-font heightIn sweep ×4 (ClearDatabaseScreen/CommonMangaItem/UpdatesUiItem/BaseMangaListItem), spinner audit = OK no change. (2) Bugs: BUG-003 resumeIndex=0 on page-change-while-Paused; BUG-004 ocr_cache getPage model predicate (no migration, verify green); BUG-005 prefetch cancel on NextChapter; BUG-006 setVoice SUCCESS check + DEBUG log; BUG-007 verified UNREACHABLE = no action; BUG-008 no-op clickable removed; BUG-009 loadSectionsOnStart=false from ManageFeeds; BUG-010 synchronous remember + 2 screens registered in search index. (3) Tscan Feed NetworkOnMainThreadException — root cause: fetchSection body ran source calls on Main via screenModelScope → withIOContext wrap, all 3 callers covered. (4) OCR/TTS latency — ChapterCache injected into OcrPageSourceResolver: getPageListFromCache-first, image from cache when present (decode-fail → refetch, CancellationException rethrown); verdict: residual 30–60s first-page latency = mostly GLENS service round-trip, app-side duplicate fetches now deduped via cache. (5) Feed source-supported genre filtering (Q2 precedent): FeedScreenModel genreToggles state + getSearchManga routing when chips active, reuses public BrowseSourceScreenModel helpers; FeedFilterBar rebuilt as stacked two-row layout (selector row + chips rows, spacedBy(small), horizontalScroll) → also resolves §8 selector-spacing task. (6) AnymeX-inspired micro-passes: MoreScreen Studies card (Text Recognition/Dictionary/Manage dictionaries moved from Library; new label_studies i18n base string), OcrQueueScreen PreferenceGroupCard regroup, UpdatesScreen filtered-empty shows controls (no dead-end), MangaNotesSection shapes.small token. (7) Liquid Mode/Background DESIGNED + DEFERRED (recipe in §G). Gates green in docker (spotlessCheck after apply, testDebugUnitTest, verifySqlDelightMigration, :app:assembleDebug). No new unit tests: BUG-004 predicate needs driver harness absent from :data (new deps forbidden), genre derivation covered by existing GenreTogglesTest. Device verification PENDING (§28 matrix + OCR/TTS timing logcat). | opencode master session |

---

## Appendix — Bug register (from 2026-09-12 audit)

| BUG-ID | Sev | Area | Description | Root cause (evidence) | Status |
|---|---|---|---|---|---|
| BUG-001 | P2 | TTS controller | `pause()` no-op during LoadingPage/Preparing → focus loss / onStop during OCR acquire does not pause; next page speaks unfocused/backgrounded; pill Pause tap dead while loading | Phase guard `if (phase != Playing && !paused) return` (TtsPlaybackController.kt:172-180) | **FIXED (RM-01)** — guard dropped; pause = any non-Idle/Finished/Error phase; acquireSentences no longer clobbers Paused. Code VERIFIED (gates); DEVICE VERIFIED (SM_M066B: HOME mid-acquire → `TTS pause page=4 sentence=0` during LoadingPage, zero speech after; pill shows Stop-only in that phase so onStop path used) |
| BUG-002 | P2 | TTS chapter advance | NextChapter host-load failure leaves controller wedged in Preparing forever; no error, no retry path | loadAdjacent swallows errors without event (ReaderViewModel.kt:563-570); rebind only on chapter-id change (:340-344) | **FIXED (RM-01)** — loadAdjacent catch → controller.fail(ChapterLoadFailed) when phase Preparing/LoadingPage → Error + Retry; new i18n key tts_error_chapter_load. Code VERIFIED (gates); DEVICE VERIFIED (SM_M066B cold process, radios off: advance → UnknownHost → Error + exact chapter-load message + Retry; Stop-from-Error; recovery online → ch8 dispatch + advance) |
| BUG-003 | P3 | TTS resume | resumeIndex carried across user page change while Paused → resume speaks arbitrary sentence of new page | onPageSelected Paused branch updates pageIndex but not resumeIndex (TtsPlaybackController.kt:237-240) | **FIXED (2026-09-13 session)** — Paused branch also sets resumeIndex=0; resetSession at rebind unchanged. Gates green; device partial-verified (pause/resume/page-change exercised; paused-page-change sequence not isolated — code-verified, revisit next reader device session) |
| BUG-004 | P3 | OCR cache | getPage ignores ocr_model → engine switch serves stale other-model results, never rescans | ocr_cache.sq:40-46 no model predicate; scanPage cache pre-check returns early (OcrRepositoryImpl.kt:230-233) | **FIXED (2026-09-13 session)** — getPage gains `AND ocr_model = :ocrModel` (UNIQUE triple + index already exist → NO migration; verifySqlDelightMigration green); OcrCacheStore.getPage + OcrRepositoryImpl.getCachedPage pass model; cache-hit log includes model. Device-verified live: cached startup 1625ms vs 15870ms uncached. No new unit test (no Robolectric/driver harness in :data — new deps forbidden) |
| BUG-005 | P4 | TTS prefetch | NextChapter transition does not cancel old-chapter prefetch (bounded waste during chapter load window) | TtsPlaybackController.kt:445-453 | **FIXED (2026-09-13 session)** — NextChapter branch cancels prefetchJob; resetSession at rebind still covers |
| BUG-006 | P4 | TTS engine | setVoice result ignored (contrast setLanguage) | AndroidTtsEngine.kt:239-247 | **FIXED (2026-09-13 session)** — both setVoice sites check SUCCESS, DEBUG log on failure |
| BUG-007 | P4 | Exclusion capture | `stream == null` page bypasses original-dims guard (zone on transformed page possible if openBitmap succeeded un-Ready — narrow window) | ReaderActivity.kt:1265-1278 conditional guard | **NO ACTION (verified unreachable 2026-09-13)** — reaching it requires openBitmap to succeed while page un-Ready, which cannot occur (openBitmap sets Ready on success first) |
| BUG-008 | P4 | Dictionary UI | Result-card row `clickable{}` no-op affordance | DictionaryComponents.kt:298 | **FIXED (2026-09-13 session, resolves U-7)** — no-op clickable removed (recommendation "remove affordance" taken) |
| BUG-009 | P4 | Feed mgmt | ManageFeedsScreen instantiates FeedScreenModel → network fetch of every enabled feed on a management screen | ManageFeedsScreen.kt:52 + model init | **FIXED (2026-09-13 session, resolves U-8)** — `loadSectionsOnStart: Boolean = true` ctor param; ManageFeedsScreen passes false; default true keeps all other callers unchanged |
| BUG-010 | P4 | Settings search | produceState gap = blank flash; toolbar screen unindexed | SettingsSearchScreen.kt:221, 323-335 | **FIXED (2026-09-13 session, resolves U-9)** — synchronous `remember(searchKey, isLtr)`; SettingsReaderToolbarScreen + AppLanguageScreen registered in unindexedSettingScreens |
| BUG-011 | P4 | Dead code | badgeNumber dead param; pointerInput no-op; detectionEngine identical branches; ~60 lines unreachable scanLocally/cropBitmap | RecentTab.kt:68-72; ReaderBottomBar.kt:41; OcrRepositoryImpl.kt:158-168,513-574 | **FIXED (RM-01, first 3 items)**; scanLocally/cropBitmap deletion deferred with det-engine ceiling (architecture table J) |
| BUG-012 | P4 | Docs | Known-issue #2 (dual setComposeContent) stale — code has ONE composition block | ReaderActivity.kt:321-323,606 grep clean; memory.md:2478-2482 | **FIXED (RM-01)** — memory + architecture + next-phase-plan corrected |

**No P0, no P1.** Baseline healthy; all findings are seam polish.

| 2026-09-13 | FEED UI CORRECTION (user-authorized; supersedes stacked-selector layout from master session): removed `All` listing chip from FeedFilterBar + Customize→Default listing; legacy null defaultListing → Popular fallback at prefs-read (no migration; "All sources" source dropdown untouched); compact primary row [Source selector][Popular][Latest] (single Row, horizontalScroll narrow-screen fallback, stable-width selector preserved); removed per-section duplicate source/listing FeedHeader; moved FilterBar into LazyVerticalGrid as first full-span item = natural collapse-on-scroll + return (zero custom scroll machinery); genre chip row preserved below primary row. Files: FeedScreen.kt, FeedScreenModel.kt. Gates green docker (spotlessCheck, testDebugUnitTest + verifySqlDelightMigration 2m59s, :app:assembleDebug 3m26s). Device-verified SM_M066B (debug 720px): row layout bounds (selector y209, Popular y209, Latest y209 — same row), zero `All` nodes, Popular↔Latest single-select with distinct result sets (data matches selection §18), genre Safe toggle off→on changes + restores results, chips honestly follow source filter leaves, collapse on swipe-down + return on swipe-up, Load more appends next page, persistence across force-stop/restart + ManageFeeds roundtrip, legacy empty default_listing → Popular selected, no crash/no NetworkOnMainThread in session logcat. UNCOMMITTED — awaiting user commit decision. | opencode feed-correction session |
| 2026-09-13 | ANYMEX UI MODERNIZATION MICRO-BATCH (user-authorized post-v0.5.4.1; visual corrections only): DS-01 SettingsDictionaryScreen OCR-results group → PreferenceGroupCard (last grouped-settings holdout); DS-03 SettingsSearch rows 24/14dp → 16/12dp token rhythm; DS-06 Feed customize icon List → GridView; DS-02 compact-grid cover scrim Color(0xAA000000) → colorScheme.scrim.copy(0.67f); FeedCustomizeDialog gaps 8→12dp grouped-card rhythm. 4 files + docs. Reference = AnymeX hierarchy/grouping intent only (no cloning). Liquid/blur/IA-regroup/nav/Q3-Q8 explicitly NOT touched. Gates green docker (spotlessCheck + testDebugUnitTest + verifySqlDelightMigration + assembleDebug, chained 3m15s). Device SM_M066B smoke PASS: Dictionary card renders tonal + rows inside (px band verified), Settings search live w/ 16dp rows, Feed row+chips+customize sheet intact, Library grid + badges render, Recent Continue + Browse sources render, More Studies card intact, 0 FATAL. UNCOMMITTED. | opencode anymex-ui session |
| 2026-09-16 | Q8 FEED AUTO-PAGINATION (user-authorized; scope: FeedScreen.kt + FeedScreenModelStateTest.kt only): (1) Auto-pagination: `selectedSourceId != null` gates trigger; `LazyGridState` + `snapshotFlow { nearEnd }.distinctUntilChanged()` → `onLoadMore(lastFeed)` when within `AUTO_LOAD_THRESHOLD = 5` items of grid end; `isLoadingMore`/`hasMore` guards preserved; existing `loadMore()` untouched; FeedScreenModel.kt UNTOUCHED. (2) All Sources source headers: when `selectedSourceId == null`, insert full-span `Text(sourceName, labelMedium, 8dp/4dp padding)` before each source section; no divider, no card, no AppBar; spacing intentionally minimal. (3) Tests: 2 new in FeedScreenModelStateTest (`single source without listing override shows both feeds`, `single source with listing override shows one feed`); 9/9 total. Gates green docker (spotlessCheck 35s, testDebugUnitTest 3m45s, :app:assembleDebug 4m4s). Device verification PENDING. Key design decision: `lastOrNull()` (not `singleOrNull()`) for trigger — correct for 1-feed (listing selected) and 2-feed (no listing) cases in single-source mode. | opencode feed-auto-pagination session |

END OF ROADMAP.
