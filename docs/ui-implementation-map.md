# Yomitsu — UI Implementation Map (Designer→Developer Handoff)

> **PRIMARY DELIVERABLE** of the docs/Prompt.md audit (2026-09-08).
> READ-ONLY audit; NO application source was modified while building this map.
> Baseline: commit `9e7a27b08` (post-v0.5.2 stabilization set, main branch).
> Companion: `docs/design.md` (LOOK/FEEL), `docs/design-audit.md` (2026-09-04
> Phase-1 audit — superseded where noted), `docs/rules.md` §4, yomihon-ui skill.
>
> **Status: AWAITING USER REVIEW/APPROVAL. Implementation MUST NOT begin
> before the user approves scope. Per Prompt.md §30 this document STOPS at
> the blueprint.**

---

## 1. Document purpose

Implementation-grade UI/UX map of the CURRENT Yomitsu app plus the APPROVED
FUTURE UI. An implementing agent must never have to guess: where something
belongs, which component to use, what typography role applies, how much
spacing, whether a surface is grouped/solid/frosted, what file owns the UI,
or what business logic is untouchable.

Everything below was re-verified against current source on 2026-09-08 —
the 2026-09-04 design-audit.md is treated as historical evidence, NOT
current truth; where they conflict, this document records the conflict
(§24).

## 2. Current application UI inventory

Bottom navigation (5 tabs, order fixed): **Library → Recent → Feed →
Browse → More** (`HomeScreen.kt:74-80`). Floating pill NavigationBar
(`presentation-core/.../material/NavigationBar.kt`: surfaceContainer,
shapes.extraLarge(28), tonal 3, navBars inset INSIDE pill, 12/8dp outer
inset — frozen pill constant per its ponytail note). Tablet = NavigationRail,
same items. Animated icons all 5 tabs (anim_*_enter.xml incl Feed). Badge:
updates count on Recent tab item (HomeScreen.kt:242-258, gated by
`newShowUpdatesCount` pref).

Screens:

| Screen | Route/Owner | Notes |
|---|---|---|
| Library | `LibraryTab` → `LibraryScreen` (presentation/library) | SearchToolbar, category tabs, 4 display modes, selection mode, settings sheet |
| Recent | `RecentTab` → 3 internal pages | Continue / History / Updates; screen-level AppBar "Recent"; PrimaryTabRow + HorizontalPager |
| Feed | `FeedTab` → `FeedScreen` (presentation/feed) | FilterBar (source dropdown chip + listing chips), sections grid, customize sheet, ManageFeedsScreen, AddFeedDialog |
| Browse | `BrowseTab` → `TabbedScreen` | Sources / Extensions / Migrate tabs; shared SearchToolbar routed to Extensions |
| More | `MoreTab` → `MoreScreen` (presentation/more) | 3 PreferenceGroupCards: General / Library / Settings |
| Manga detail | `MangaScreen` | toolbar + info + chapters; Error state for missing/generic load failure (D-11, Batch 4) |
| Reader | `ReaderActivity` (explicit) | overlay chrome; see §23 protected systems |
| Settings tree | `SettingsScreen(Destination)` → `SettingsMainScreen` + 12 SearchableSettings sub-screens | grouped PreferenceGroupCards since 09-06 |
| Global search | `GlobalSearchScreen` | reached via Browse reselect, Sources toolbar TravelExplore, Library empty-search row, SEARCH intent |
| Feed management | `ManageFeedsScreen` | reorder/enable/delete all feeds |
| Dictionary lookup | `DictionaryLookupScreen` (More tab) | More→Dictionary |
| Dictionary manager | `SettingsDictionaryScreen` (Settings Destination.Dictionary) | import + installed list |
| OCR queue | `OcrQueueScreen` (More→Library card; SettingsReadAloud Advanced cross-link) | queue + engine prefs |
| Downloads | `DownloadQueueScreen` (More→Library card) | |
| OCR exclusions | `SettingsOcrExclusionsScreen` (Settings Destination.OcrExclusions; reader manage sheet) | |
| About / Stats / Categories / Data&Storage / Support / Onboarding | various | |

**"Create" tab/screen**: DOES NOT EXIST in source. No destination, route, or
string contains it (rg for "Create" under `ui/recent/` → zero hits). The
user's reference most plausibly maps to the **Recent "Continue" tab** (newest
recently-introduced tab surface, `recent_tab_continue` i18n) or to the Feed
"Add feed" creation dialog. Recorded, not invented. STATUS 2026-09-11:
remains OPEN — DECISION REQUIRED (user clarification; no implementation
without an explicit referent + spec).

## 3. Navigation map

```
MainActivity
└─ Voyager Navigator → HomeScreen
   ├─ phone: floating pill NavigationBar / tablet: NavigationRail
   ├─ TabNavigator (saveable per-tab state, fade-through 200ms)
   │  ├─ LibraryTab   (reselect → Library settings sheet)
   │  ├─ RecentTab   (reselect → resume last-read chapter; no_next_chapter snackbar)
   │  ├─ FeedTab     (reselect → push ManageFeedsScreen)
   │  ├─ BrowseTab   (reselect → push GlobalSearchScreen)
   │  └─ MoreTab    (reselect → push SettingsScreen)
   └─ pushed Voyager screens per tab (MangaScreen, ManageFeeds, Settings*, …)
ReaderActivity = explicit Activity (ReaderActivity.newIntent)
```

Reselect semantics are INTENTIONAL per-tab (documented decision, memory
2026-09-06 spec §4); do NOT "standardize" them without an explicit IA
decision. TabOptions.index = 0..4 matching list order (verified: Library 0,
Recent 1, Feed 2, Browse 3, More 4). Voyager addresses tabs by CLASS — index
is metadata only.

## 4. Screen hierarchy

More two-level IA (current, verified):

```
More
├─ [LogoHeader]
├─ General (pref_category_general)
│  ├─ Downloaded only (switch)
│  └─ Incognito mode (switch)
├─ Library (pref_category_library)
│  ├─ Download queue (live subtitle: paused/downloading + count)
│  ├─ Text recognition (OCR queue; live subtitle)
│  ├─ Categories
│  ├─ Stats
│  ├─ Data and storage  → SettingsScreen(DataAndStorage)
│  ├─ Dictionary lookup → DictionaryLookupScreen
│  └─ Dictionaries      → SettingsScreen(Dictionary)
└─ Settings (label_settings)
   ├─ Settings → SettingsScreen()
   ├─ Support us
   ├─ About → SettingsScreen(About)
   └─ Help (external URL)
```

Settings tree (SettingsMainScreen sections, all grouped in cards, 12dp
inter-card gaps):

| Section | Screens |
|---|---|
| Appearance | Appearance & Interface |
| Library | Library, Downloads |
| Reader | Reader, Read aloud & voice, OCR exclusions, AnkiDroid |
| Tracking | Tracking, Browse |
| Data & storage | Data and storage, Security, Advanced |
| About | About |

Destinations (SettingsScreen.Destination ids): About=0, DataAndStorage=1,
Tracking=2, Dictionary=3, ReadAloud=4, OcrExclusions=5 — consistent with
when-branches in SettingsScreen.kt:40-48.

## 5. Design system reference

`TachiyomiTheme` → `BaseTachiyomiTheme` → `MaterialExpressiveTheme(colorScheme)`.
No typography/shapes override → M3 defaults + two custom roles in
presentation-core `theme/Typography.kt`:
- `Typography.header` = bodyMedium + onSurfaceVariant + SemiBold (section headers)
- `Typography.itemTitle` = 12sp/18sp (grid/list item titles — killed the old
  per-item fontSize hacks; `GridItemTitle` merges it, CommonMangaItem.kt:282)

Colors: 15 BaseColorScheme impls + Monet + AMOLED. All feature UI must use
`MaterialTheme.colorScheme.*` only. Surfaces: semantic frost roles in
`theme/Translucent.kt` (`asFloatingChrome()` 0.85 real-alpha reader chrome,
`asChromeContainer()`/`asFrostedModal()` opaque pre-blend modals, solid
tokens elsewhere). Grouped settings: `PreferenceGroupCard` =
one group = one surface (surfaceContainerLow, shapes.large, header INSIDE,
16dp horizontal, 4dp vertical padding, NO shadow/frost).

Spacing tokens: `MaterialTheme.padding` — extraSmall 4 / small 8 / medium 16
/ large 24 / extraLarge 32. 12dp exists ONLY as: PreferenceScreen group
spacer, MoreScreen inter-card spacer, ReaderSettingsDialog card gaps,
nav-pill inset — all frozen grouped-settings rhythm (design.md §13
"16/12dp"), NOT a general scale.

Icons: material-icons-extended, outlined dominates. Motion: fade/slide bar
patterns, animateContentSize expand/collapse, standard M3 states only.

## 6. Typography specification

| Role | Token | Used by |
|---|---|---|
| Screen/AppBar title | titleLarge / titleMedium | AppBar defaults |
| Section header (in-list) | `Typography.header` via `ListGroupHeader` | Feed headers, History/Updates date headers, More/settings card titles (inside PreferenceGroupCard) |
| Row/grid item title | `Typography.itemTitle` merged over titleSmall | MangaComfortableGridItem etc. (CommonMangaItem.kt) |
| Body | bodyMedium / bodyLarge | rows, ContinueTab title (bodyMedium), TTS pill sentence (bodyLarge) |
| Supporting/description | bodySmall / labelMedium | FeedHeader listing label (bodySmall onSurfaceVariant), unread count (bodySmall) |
| Caption/meta | labelSmall | timestamps, badges |

Known deliberate exceptions (documented, do not "fix" silently):
- `WordSelector` 20sp header (tap-target decision, DictionaryComponents.kt:660)
- `ChapterTransition` 20sp decorative page-separator glyphs
- `Tabs.kt:26` badge `fontSize = 10.sp` (badge density)
- `BasePreferenceWidget` `TitleFontSize = 16.sp` (settings row-title
  dialect — consistent across all settings rows)
- OcrQueueScreen/DownloadQueueScreen 14.sp triage-row counters
- LibraryToolbar unread-count Pill 14.sp (counter density — same family
  as queue triage counters; verified 2026-09-09 Batch-3 sweep)
- PitchAccentGraph labelSmall 10sp (data-viz caption)
- ChapterTransition/ReaderPageIndicator letterSpacing 1-2sp

Audit result: previous hard-coded-sp cluster (GridItemTitle 12/18sp,
MangaInfoHeader, AppBar search 18sp, Dictionary SearchBar 15sp) has been
RESOLVED since design-audit.md — current source uses tokens/`itemTitle`/
titleMedium. CORRECTION 2026-09-09 (Batch-3 sweep): LibraryToolbar Pill
14sp is STILL PRESENT (LibraryToolbar.kt:86, upstream heritage) and is now
documented as an intentional exception above (counter density family).
Remaining `.sp` hits are the documented exceptions above + furigana math
(`textStyle.fontSize * 0.60f`, correct derived-relative use).

## 7. Spacing specification

- Screen horizontal padding: 16dp (`MaterialTheme.padding.medium`) —
  FeedFilterBar, ContinueTab rows, HistoryItem etc. comply.
- Grids: `CommonMangaItemDefaults` 4dp gutters + 8dp content padding
  (LazyLibraryGrid; Feed grid now matches: 8dp edges + 4dp spacers —
  design-audit.md's 2×-gutter finding is FIXED in current source).
- Grouped cards: 16dp card inset, 12dp between cards (PreferenceScreen
  Spacer, MoreScreen spacers, ReaderSettingsDialog spacedBy(12.dp)), 4dp
  card inner vertical padding, header start/top 16/12dp.
- Pills: 24/12 compact bars (OcrLoadingIndicator precedent), 12dp icon gaps;
  nav pill 12/8 inset (frozen).
- Settings rows: BasePreferenceWidget built-ins — never override.
- ContinueItem row: heightIn(min=96dp), padding medium/small — heightIn fix
  verified (HistoryItem precedent followed).

## 8. Alignment specification

- All list rows align covers at screen 16dp inset; title column start 16dp.
- ContinueTab row, HistoryItem: cover left / text weight(1f) / trailing
  48dp IconButton — equivalent rows share equivalent alignment. PASS.
- Grouped settings: rows flat, BasePreferenceWidget 16dp title alignment
  matches card 16dp inset. PASS.
- Recent PrimaryTabRow full-width under screen AppBar — matches TabbedScreen
  (Browse) pattern. PASS.
- ManageFeedsScreen trailing row: 3 IconButtons + Switch (visual density;
  see D-07).

## 9. Header specification

- One screen = one screen-level title. Recent: AppBar "Recent" ONLY;
  History/Updates AppBars title=null, History toolbar comment documents it
  (HistoryScreen.kt:45). Feed: AppBar "Feed"; sections use ListGroupHeader
  (not repeated titles). Library: dynamic category title in toolbar.
- Internal section headers = ListGroupHeader ONE rank below screen title.
- Manga detail keeps its own collapsing toolbar (MangaScreen) — separate
  destination, not nested under a tab AppBar. Correct per architecture.
- Nested headers inside tab rows: PROHIBITED (recent correction passes
  removed them; do not reintroduce).

## 10. Surface specification

| Surface | Treatment | Examples |
|---|---|---|
| Background | `colorScheme.background` | all screens |
| Grouped settings | `PreferenceGroupCard` surfaceContainerLow + shapes.large, solid | More, SettingsMain, settings sub-screens, Feed customize sheet, ManageFeedsScreen, reader settings pages |
| Floating chrome | `asFloatingChrome()` real 0.85 alpha | Reader bars/tray/navigator, TtsPlaybackBar, OcrLoadingIndicator |
| Frosted modal | `asChromeContainer()` pre-blend | AdaptiveSheet, ResizableSheet, TabbedDialog, NavigationBar pill containerColor |
| List rows in tonal cards | `ListItemDefaults.colors(containerColor = Color.Transparent)` | ManageFeedsScreen, AddFeedDialog (AMOLED fix — MANDATORY pattern when nesting M3 ListItem inside any tonal card) |
| Reader content panels | solid | OcrResultPopup (readability ruling) |

## 11. Glass / frost rules

There is NO true backdrop blur and none may be added (rejected on Compose
rendering architecture + perf; recorded in phase.md deferred list #2). Frost
= semantic roles above, gated by Translucent UI preference with opaque
fallback. NEVER frost: settings cards, manga cards, long-form text, OCR
result content, About, ordinary rows, large content surfaces. No
frost-on-frost (opaque tokens inside frosted sheets are correct — reader
settings slider pills).

## 12. Responsive specification

- Compact/expanded via `isTabletUi()`: pill NavigationBar vs NavigationRail.
  No separate tablet layouts.
- Reader adapts: ChapterNavigator rails, dual-page split, orientation prefs.
- Grids: Adaptive(96dp Feed / 128dp Library) or Fixed(pref columns).
- Sheets: AdaptiveSheet → phone bottom / tablet center automatically.
- All screens must tolerate large font scale (heightIn pattern, not fixed
  height).

## 13. Component inventory (reuse-first)

presentation-core: NavigationBar/Rail, Scaffold, Surface, AdaptiveSheet,
ResizableSheet, Pill, Badge/BadgeGroup, ActionButton, EmptyScreen (+Action),
LoadingScreen, InfoScreen, SectionCard, ListGroupHeader, SettingsItems
(HeadingItem/TextItem/CheckboxItem/SliderItem/ChipRow/SettingsChipRow/
IconGrid), Scrollbar/FastScroll lists, CollapsibleBox, Tabs/TabText wrapper,
DropdownMenu wrapper, Typography.header/itemTitle, Translucent roles.

app settings dialect: PreferenceScreen/PreferenceGroup, Preference variants
(TextPreference, BasicListPreference `searchable`, SliderPreference,
InfoPreference, CustomPreference), BasePreferenceWidget family,
PreferenceGroupCard, PreferenceGroupHeader, TriStateListDialog.

app shared: AppBar/SearchToolbar/TabbedScreen/GlobalSearchToolbar,
MangaCover.Book, MangaCompactGridItem/MangaComfortableGridItem,
CommonMangaItemDefaults, LibraryBottomActionMenu, MangaBottomActionMenu,
ReaderBottomBar, TtsPlaybackBar, OcrLoadingIndicator, ReaderPageIndicator,
OcrSelectionOverlay, OcrResultPopup family, HistoryItem, UpdatesUiItem.

**Rule: an implementing agent greps this inventory before creating ANY new
component. New components require approval (§27).**

## 14. Screen-by-screen implementation map

### 14.1 Recent (P0 — recently changed)

- Identity: `RecentTab` (bottom-nav tab 1). Internal pages Continue /
  History / Updates. Entry: tab tap, SHORTCUT_UPDATES/HISTORY → Tab.Recent.
- Layout: Scaffold + screen AppBar("Recent") + PrimaryTabRow + HorizontalPager
  (RecentTab.kt:79-116). Content padding applied to Column top/bottom
  (fix 2026-09-07 verified: tabs y~202-234 under AppBar, no overlap).
- Typography: AppBar titleMedium; TabText labelLarge (badge 10sp documented);
  Continue row title bodyMedium + unread bodySmall; History/Updates rows
  existing upstream roles.
- Surfaces: plain background; chips FilterChip; no cards over rows.
- Interaction: tab tap animateScrollToPage; swipe pager; reselect → resume
  last-read (RecentReselect.kt helper + resumeLastChapterReadEvent channel).
- States: Continue loading/empty (`recent_continue_empty`); History
  search/no-results empty variants; Updates filter + selection-mode +
  "No recent updates" empty.
- Responsive: same layout tablet/phone (tab row under AppBar).
- A11y: badge counts via TabText badge semantics; 48dp rows targets OK
  (IconButton default).
- Known residual: `resumeHostState` SnackbarHost created but NEVER mounted in
  the Scaffold (RecentTab.kt:67,86) → reselect-failure snackbar
  (`no_next_chapter`) will NOT display. See D-01.

### 14.2 Continue (inside Recent)

- Owner: ContinueTab.kt + ContinueScreenModel (library favorites with
  unreadCount>0 && hasStarted; sort LAST_READ/ALPHA; downloadedOnly filter;
  nextChapter via getNextUnread). All business logic verified — UNTOUCHABLE.
- UI verified: FilterChip controls row (sort chips + downloaded-only) as
  lazy item; rows heightIn(min 96), cover 96dp, PlayArrow resume IconButton
  (48dp, contentDescription action_resume). Row tap = resume too.

### 14.3 Feed (P0 — recently changed + functional bug)

- Identity: `FeedTab` (tab 2). Screen: FeedScreen.kt. Model:
  FeedScreenModel (sections map keyed by FeedItem; paging state per feed;
  PAGE_SIZE=20 ponytail assumption documented in code).
- Layout: Scaffold + AppBar("Feed", enterAlways) + [FilterBar + Lazy grid].
  FilterBar: SourceSelectorDropdown chip (left) + listing FilterChips
  (scrollable, right-aligned weight(1f)); hidden when <2 feed sources and no
  mixed listings.
- Grid: Adaptive(96dp) or Fixed(gridColumns pref); 8dp edges, 4dp gutters
  (CommonMangaItemDefaults — matches Library); section header =
  ListGroupHeader(source name) + bodySmall listing label + divider; footer =
  Load more / spinner / "You're all caught up"; error row = message + retry.
- Customize sheet (AdaptiveSheet + PreferenceGroupCards): Display (grid
  columns chips + grid style chips) / Sources (show-selector switch) /
  Default listing (chips + show-listing-selector switch).
- ManageFeedsScreen: AppBar + single PreferenceGroupCard("Reorder feeds") +
  transparent ListItems (up/down/switch/delete).
- AddFeedDialog: AlertDialog + transparent ListItems + RadioButton + listing
  FilterChips (supportsLatest gates Latest).
- **LISTING SELECTOR BUG — see §15 (P0).**
- Surfaces: solid; chips M3; no frost anywhere in Feed.

### 14.4 Library

Verified current: SearchToolbar + dynamic title + category tabs +
selection + 4 grid modes + settings sheet (reselect) + Continue-reading
per-item button (pref-gated, `onContinueReadingClicked` — the reverted
page-level Continue section is NOT to be reintroduced; per-item button is
the approved form). GlobalSearchItem appears during search.

### 14.5 Browse

TabbedScreen 3 tabs Sources/Extensions/Migrate; search routed to Extensions
SM (set-2 revert stands — do NOT re-add Search tab); reselect →
GlobalSearchScreen. Sources tab TravelExplore action = visible global-search
path.

### 14.6 More

Grouped 3-card layout (§4). Reselect → Settings. All rows verified against
callbacks in MoreTab.kt:75-85.

### 14.7 Reader (protected — visual work only via §23 constraints)

Overlay composition = single live tree (dead outer tree REMOVED; z-order
contract comment at ReaderActivity.kt:569-581). ReaderPageIndicator restored
to live tree (:643). OcrLoadingIndicator topmost with pill clearance
(:943). Do not touch viewer/progression/TTS logic during UI work.

### 14.8 Reader quick settings

ReaderSettingsDialog 4 tabs (Reading mode / General / Custom filter / Read
aloud), all pages grouped via PreferenceGroupCard with 12dp spacedBy gaps
(fix 2026-09-07 verified). ColorFilter dim-hack previously index-based —
verify current title-based check before ANY tab reordering (recorded in
memory 2026-09-06; re-verify on touch).

### 14.9 Settings sub-screens

All SearchableSettings render PreferenceGroups as PreferenceGroupCards.
SettingsReadAloudScreen = reference implementation (loading scaffold,
groups, searchable voice picker, profiles). SettingsOcrExclusionsScreen and
SettingsDictionaryScreen are plain Screens but are registered in the Settings
Search index as synthetic single-entry results (see D-08) — searchable without
converting them to SearchableSettings (their custom UI stays).

## 15. Feed listing behavior contract + BUG (P0)

**Contract (desired)**:
- All selected → show every enabled feed section (both listings).
- Popular selected → show ONLY POPULAR sections of the visible source
  filter. Latest likewise. Single-select.
- Source selector + listing selector compose (AND filter).
- VISIBLE chip state must equal ACTUAL data state.

**CURRENT BEHAVIOR**: tapping Popular/Latest updates `listingOverride`
state and the chip highlights, but the GRID STILL SHOWS BOTH POPULAR AND
LATEST SECTIONS. Selected chip ≠ displayed data.

**ROOT CAUSE** (proven in source, not guessed):
`FeedScreenModel.State.visibleFeeds` (FeedScreenModel.kt:64-71) filters by
`enabled` and `selectedSourceId` only — **it never consults
`listingOverride`**. `selectListing()` (:202-204) writes the state field the
chips read, but nothing applies it to the feed list.

**EXPECTED**: visibleFeeds = enabled ∩ (source match) ∩ (listingOverride ==
null || feed.listing == listingOverride).

**AFFECTED FILES**: `app/src/main/java/eu/kanade/tachiyomi/ui/feed/FeedScreenModel.kt`
(State.visibleFeeds). Optionally FeedScreen.kt FeedFilterBar if chip
visibility should also consider the filter (no).

**PROPOSED FIX** (1 hunk, presentation+state only):
```kotlin
val visibleFeeds: List<FeedItem>
    get() {
        val enabled = feeds.filter { it.enabled }
        val sourceMatched = enabled.filter { selectedSourceId == null || it.sourceId == selectedSourceId }
        val listingMatched = sourceMatched.filter { listingOverride == null || it.listing == listingOverride }
        // A saved source whose feeds no longer exist falls back to all enabled feeds
        return if (listingMatched.isEmpty() && enabled.isNotEmpty()) {
            if (sourceMatched.isEmpty() && selectedSourceId != null) enabled
            else if (sourceMatched.isEmpty() || selectedSourceId == null) enabled
            else enabled.filter { it.listing == listingOverride }.ifEmpty { sourceMatched }
        } else listingMatched
    }
```
Simplified acceptable version (recommended — fallback nuance is edge-case):
filter by both, and if result empty while source-filter matched non-empty,
fall back to the SOURCE-matched set (preserves existing stale-source
fallback, adds stale/absent-listing tolerance). Decide fallback shape at
implementation review; the core fix is the listing filter itself.

**SECONDARY DEFECT (same cluster)**: pref-collector at FeedScreenModel.kt:
102 re-seeds `listingOverride = it.listingOverride ?: prefs.defaultListing`
— after the user explicitly selects "All" (null), the NEXT emit of ANY of
the 4 collected prefs (e.g. toggling a customize switch) silently re-applies
defaultListing ≠ null, un-selecting "All". Distinguish "user chose All" from
"no user choice yet" (sentinel or a separate userSelectedListing flag) OR
stop treating null as uninitialized.

**REGRESSION RISK**: LOW — visibleFeeds is a derived getter consumed only
by FeedScreen grid rendering; sections cache untouched (no refetch); source
persistence path untouched; ManageFeedsScreen uses state.feeds directly
(unfiltered) and is unaffected.

**VERIFICATION PLAN**: device — configure one source with BOTH Popular and
Latest feeds; tap Popular → only Popular section + header; tap Latest → only
Latest; tap All → both; combine with source dropdown (source X + Popular →
only X's Popular section); customize default-listing = Popular + fresh app
start → Popular preselected AND grid filtered; select All then toggle a
customize switch → grid stays All (secondary fix); process-death restore of
each combination.

## 16. Feature placement map

| Feature | Current Location | Proposed Location | Reason | Related Settings | Implementation Owner (file) |
|---|---|---|---|---|---|
| Panorama Cover | reader pref (upstream) | KEEP | working | SettingsReaderScreen | reader presentation |
| Cover-based theming | n/a | NOT PROPOSED (no approval) | — | — | — |
| Dynamic reader controls | ReaderBottomBar visibility prefs (Issue 6/8) | KEEP | shipped + device-verified | SettingsReaderScreen actions group | ReaderBottomBar/ReaderActivity |
| Reader controls | ReaderBottomBar + overlay | KEEP (protected) | hero/overlay contract | reader quick settings | reader presentation only |
| OCR (scan/queue/engine) | More→Library card "Text recognition" + ReadAloud Advanced cross-link | KEEP (cross-links both directions exist) | 3-surface triangle complete | SettingsReadAloud Advanced | OcrQueueScreen |
| Read Aloud | reader pill + ReaderBottomBar entry | KEEP | protected | ReadAloudPage + SettingsReadAloudScreen | TtsPlaybackBar |
| Voice profiles / rate | SettingsReadAloudScreen | KEEP | 10A shipped | same | SettingsReadAloudScreen |
| Dictionary lookup | More→Library card "Dictionary lookup" | KEEP | Phase H decision | SettingsDictionaryScreen (tap lookup/auto search) | DictionaryLookupScreen |
| Dictionary manager | Settings Destination.Dictionary + More "Dictionaries" | KEEP | distinct feature, no merge | itself | SettingsDictionaryScreen |
| Text Recognition naming | = OCR queue row (label_text_recognition) | KEEP | same feature, one name | — | MoreScreen/SettingsReadAloud |
| Feed customization | Feed AppBar List (customize sheet) | KEEP | central since set 2 | FeedPreferences | FeedScreen |
| Feed listing selector | FeedFilterBar chips | FIX (§15) | P0 bug | pref_feed_default_listing | FeedScreenModel |
| Feed source selector | FeedFilterBar dropdown chip | KEEP | persistence verified | pref_feed_selected_source | FeedScreenModel |
| Feed management | ManageFeedsScreen (Tune action + Feed reselect) | KEEP | intentional | — | ManageFeedsScreen |
| Storage Manager | Data and storage screen | KEEP | upstream | SettingsDataScreen | — |
| Backup & Restore | Data and storage | KEEP | protected format | same | — |
| Theme customization | Appearance & Interface | KEEP | 13 schemes + Monet | SettingsAppearanceScreen | — |
| Wallpapers | none | NOT PROPOSED | no requirement | — | — |
| Navigation customization | none (fixed 5 tabs) | NOT PROPOSED (needs approval) | — | — | — |
| OCR exclusion rules | Settings OcrExclusions + reader manage sheet | KEEP (two surfaces intentional: settings=full CRUD, sheet=quick manage) | v0.5.2 shipped | SettingsOcrExclusionsScreen | — |

No AnymeX/Chimahon features are copied. Borrowed ideas recorded in
design-audit.md §3 remain constrained to hierarchy/IA thinking.

## 17. Settings information architecture

SCREEN → GROUP → ROW verified map (all sub-screens use
PreferenceGroup/PreferenceGroupCard):

- **Appearance**: theme, amoled, dynamic color, nav-bar-label visibility… (upstream groups)
- **Library**: Categories / Global update / (Behavior)
- **Downloads**: auto-download groups (grouped 09-06)
- **Reader**: Actions (incl OCR-select + Read-Aloud button toggles) /
  Reading mode / Navigation groups (grouped 09-06)
- **Read aloud & voice**: Text to speech / Voice calibration / Voice
  profiles / Advanced (+Text Recognition cross-link)
- **OCR exclusions**: hint + words/phrases/zones sections, add dialogs,
  per-row switch/edit/delete, identity labels
- **Tracking**: General (auto-update switch, on-mark-read) / per-tracker
- **Browse**: Sources group / extension-store prefs
- **Data & storage**: Storage manager / backup groups; Data/Anki loose rows
  (unwrapped — no natural i18n key, documented decision)
- **Security/Advanced**: fully grouped (8 keyed groups; D-10 resolved
  2026-09-09 — original "loose rows" claim was stale)
- **AnkiDroid**: full field-mapping screens (very tall groups OK)

Naming consistent (pref_category_* keys); no duplicated groups found.

## 18. Code ownership map

| UI Element | Source File | Shared Component | State Owner | Business Logic Owner |
|---|---|---|---|---|
| Bottom nav pill | presentation-core material/NavigationBar.kt | itself | TabNavigator | — |
| Tab registry/reselect | ui/home/HomeScreen.kt + ui/{library,recent,feed,browse,more}/*Tab.kt | Tab interface | Voyager | reselect helpers |
| Recent screen | ui/recent/RecentTab.kt | Scaffold/PrimaryTabRow/TabText | pager state | — |
| Continue page | ui/recent/continuereading/{ContinueTab,ContinueScreenModel}.kt | FastScrollLazyColumn/FilterChip/EmptyScreen | ContinueScreenModel | GetLibraryManga/GetChaptersByMangaId/getNextUnread |
| History page | ui/recent/history/RecentHistoryTab.kt → presentation/history/HistoryScreen.kt | SearchToolbar/HistoryItem | HistoryScreenModel | GetHistory etc. |
| Updates page | ui/recent/updates/RecentUpdatesTab.kt → presentation/updates/UpdatesScreen.kt | AppBar action mode | UpdatesScreenModel | UpdatesInteractor |
| Recent reselect | ui/recent/RecentReselect.kt | — | RecentTab channel | GetNextChapters |
| Feed screen | presentation/feed/FeedScreen.kt | AppBar/EmptyScreen/ListGroupHeader/FilterChip | FeedScreenModel | sources via SourceManager |
| Feed model | ui/feed/FeedScreenModel.kt | — | itself | getPopularManga/getLatestUpdates |
| Feed prefs | eu/kanade/domain/feed/service/FeedPreferences.kt | PreferenceStore | prefs | — |
| Feed tab wiring | ui/feed/FeedTab.kt | — | — | — |
| Manage feeds | presentation/feed/ManageFeedsScreen.kt | PreferenceGroupCard/transparent ListItem | shared FeedScreenModel | same prefs |
| More screen | presentation/more/MoreScreen.kt | PreferenceGroupCard/row widgets | MoreScreenModel (MoreTab.kt) | queue states via managers |
| Settings root | presentation/more/settings/screen/SettingsMainScreen.kt | PreferenceGroupCard | — | — |
| Settings sub-screens | presentation/more/settings/screen/Settings*.kt | SearchableSettings/Preference family | each ScreenModel | interactors |
| Settings search | SettingsSearchScreen.kt (settingScreens list) | — | — | — |
| Grouped card widget | presentation/more/settings/widget/PreferenceGroupCard.kt | itself | — | — |
| Section header | presentation-core components/ListGroupHeader.kt | Typography.header | — | — |
| Reader overlay tree | ui/reader/ReaderActivity.kt (setComposeOverlay) | ContentOverlay pattern | ReaderViewModel | protected (§23) |
| TTS pill | presentation/reader/TtsPlaybackBar.kt | floating pill | ReaderViewModel.ttsState | TtsPlaybackController (protected) |
| Reader chrome | presentation/reader/ReaderAppBars.kt + ChapterNavigator.kt | asFloatingChrome | ReaderViewModel | — |
| OCR loading | presentation/reader/OcrLoadingIndicator.kt | strip family | ReaderViewModel | OcrScanManager |

## 19. Implementation boundaries

Classification for every proposed change (§24 register carries per-item):

- **PRESENTATION ONLY**: typography/spacing/alignment corrections, chip
  alignment, header consolidation, empty-state copy, D-01 snackbar mount.
- **PRESENTATION + STATE**: §15 listing-selector fix (visibleFeeds getter +
  selectListing/defaultListing sentinel), D-04 (AddFeedDialog latest-guard).
- **PREFERENCE CHANGE**: none proposed (all Feed prefs exist already).
- **NAVIGATION CHANGE**: none proposed (reselect semantics frozen).
- **DOMAIN/DATA/DATABASE**: NONE.

Default: UI work stays presentation-only. Anything deeper → STOP and
document (rules.md §10).

## 20. Protected systems (DO NOT TOUCH during UI work)

Reader playback behavior; OCR acquisition pipeline/caching/exclusion
matching; TTS progression/arbitration; TtsPlaybackController; AndroidTtsEngine;
bitmap lifecycle; database/schemas (main + ocr_cache); backup format +
`.tachibk`; preference semantics + existing keys; source/network logic;
extension system; Feed data fetching EXCEPT the §15 visibleFeeds fix;
Mihon/Tachiyomi protocols + schemes; applicationId/namespaces; Voyager
navigation semantics incl. reselect; OCR exclusion matcher; speech pipeline
(SpeechCleaner/classifier/segmenter); AnkiDroid integration; dictionary
audio; download manager; tracking.

## 21. Discrepancy register

| ID | Screen | Category | Current Problem | Expected | Severity | Root Cause | Proposed Resolution |
|---|---|---|---|---|---|---|---|
| D-01 | Recent→reselect | Functional | resumeHostState SnackbarHost never mounted in Scaffold (RecentTab.kt:67,86); no_next_chapter fallback invisible | failure feedback visible | MEDIUM | second host created, snackbarHost param only passes snackbarHostState | pass resumeHostState to Scaffold snackbarHost (or merge hosts) |
| D-02 | Feed | Functional | listing selector no-op on grid (chips highlight, both listings still shown) | single-select filtering per §15 contract | **P0 MAJOR** | State.visibleFeeds ignores listingOverride (FeedScreenModel.kt:64-71) | apply listing filter in visibleFeeds |
| D-03 | Feed | State | "All" selection silently reverted to defaultListing on next pref emit | explicit All persists | MEDIUM | `listingOverride ?: defaultListing` conflates user-All with uninitialized (FeedScreenModel.kt:102) | sentinel/flag distinguishing user choice; or drop re-seed after first emit |
| D-04 | Feed→AddFeedDialog | State | selecting a source WITHOUT supportsLatest after choosing Latest leaves listing chip selected-but-unsupported until confirm | Latest auto-deselects instantly | MINOR | guard exists on click (FeedScreen.kt:297,305) but only fires on NEW selection; default LATEST persists | default selectedListing POPULAR when opening dialog, or guard on confirm | 
| D-05 | Feed | Typography | FeedHeader listing label bodySmall + divider directly under ListGroupHeader — divider under section header doubles the ListGroupHeader separation used in History/Updates (dates) | consistent section rhythm | MINOR | local composition | consider dropping divider (matches History/Updates) — visual review first |
| D-06 | More | Spacing | Spacer(12.dp) between cards in more — duplicated literal instead of shared rhythm constant | fine as-is (matches PreferenceScreen); document | MINOR | literal | RESOLVED 2026-09-09 (Batch 3): documented in §7 (frozen 12dp grouped-card rhythm); const extraction declined — 2 sites, shared const = abstraction for its own sake. Batch-3 typography sweep found NO further authorized residue: remaining `.sp`/dp findings are either documented exceptions (LibraryToolbar Pill 14sp added to §6 exception family — counter density, same as queue triage counters), PROTECTED-system doc conflicts (TtsPlaybackBar bodyMedium vs design.md §4 bodyLarge; pill 16/4dp vs §5 24/12 — RESOLVED 2026-09-11 decision micro-batch: code RATIFIED as shipped, design.md §4/§5/§8 now describe bodyMedium + 16/4; no source change), or upstream residue outside this batch's register (upcoming-calendar 16sp raw, SettingsDictionaryScreen card dialect, TrackerSearch/Migration radii, search row 14dp, TriStateListDialog 20dp, LogoHeader inset divergence — documented, untouched) |
| D-07 | ManageFeedsScreen | Spacing | trailing row = 3×48dp IconButtons + Switch = ~216dp controls in 80dp ListItem trailing — dense on narrow screens, Switch not content-described | verify 48dp + label semantics | MINOR | layout choice | visual review on 360dp device; Switch contentDescription if missing |
| D-08 | Settings search | Discoverability | SettingsDictionaryScreen + SettingsOcrExclusionsScreen not in settingScreens search index (SettingsSearchScreen.kt:288-300) | documented decision (either register or record exclusion) | MINOR | plain Screen objects by design | RESOLVED 2026-09-09 (Batch 4): registered via synthetic single-entry index additions in getIndex() — unindexedSettingScreens list (screen + title + subtitle) appended to the SearchableSettings corpus; results navigate with navigator.replace to the existing Screen objects; zero UI change to either screen; Dictionary subtitle = label_dictionary (search corpus only, covers singular "dictionary" query). Device-verified: queries "dictionary"/"dictionaries"/"exclusions" surface + open both screens; existing entries ("theme" → App theme) intact |
| D-09 | Global search | Navigation | 4 scattered entry paths, none primary | intentional post-revert (Browse reselect + Sources TravelExplore visible) | INFO | set-2 revert decision | none (recorded) |
| D-10 | SettingsAdvanced | Grouping | residual loose pre-group rows | grouped where natural keys exist | MINOR | pre-group drift | RESOLVED 2026-09-09 (Batch 3 audit): source ALREADY fully grouped — 8 PreferenceGroups, all keyed (pref_category_general, label_background_activity, label_data, label_network, label_library, pref_category_reader, label_extensions, pref_category_dictionary_parser), 0 loose rows; original claim was stale copy-forward from design-audit.md (git disproof: commit 0eab09ce4 2026-07-25 already had 7 groups). No code change; screen conforms to design.md §13 |
| D-11 | MangaScreen | State | no error/missing-manga state (Loading forever) | honest failure state | MEDIUM | ScreenModel has Loading/Success only | RESOLVED 2026-09-09 (Batch 4): MangaScreenModel.State gained Error(missing: Boolean) — load() extracted from init, try/catch with CE rethrow, SQLDelight awaitAsOne NPE = row-absent (missing) vs generic retryable failure; combine-subscription .catch transitions Success→Error on mid-session row deletion; MangaScreen renders EmptyScreen (manga_screen_not_found for missing, unknown_error otherwise) with Retry (generic only) + Close (navigator.pop). sourceManager constructor-injected (replaces inline Injekt.get). Unit tests 4/4 (missing→Error.missing, generic→retryable, retry→Success, valid→Success); Success path device-verified; mid-session deletion UI not device-triggerable (no safe live-DB-write path) — code+unit verified |
| D-12 | Docs | Docs | prd.md §1.4 lists stale tab set ("Library / History / Updates / Browse / Feed / More", 6 tabs) | Library / Recent / Feed / Browse / More | MINOR | docs lag 09-06 IA change | RESOLVED 2026-09-09 (Batch 4): prd.md §1.4 corrected to the actual five-tab IA (Library / Recent / Feed / Browse / More); docs-only, no navigation change |
| D-13 | Feed | Spacing | Feed grid contentPadding start/end 8.dp literals — matches LazyLibraryGrid(8dp)+4dp gutters pattern but Library adds it via shared helper | shared helper reuse | MINOR | duplicated pattern | RESOLVED 2026-09-09 (Batch 3 audit): left UNCHANGED, intentionally. LazyLibraryGrid reuse rejected — it hardcodes FastScrollLazyVerticalGrid (Feed skips fast-scroll by documented decision) + Adaptive(128.dp) (Feed is 96.dp) → reuse would change behavior. New shared helper rejected per §9/§27 approval gates + not justified for two literals that already share the REAL geometry (CommonMangaItemDefaults 4dp gutter constants + identical 8dp edge intent). No functional Feed changes permitted in batch; preserved adaptive/fixed, gutters, edges, paging, section ordering |
| D-14 | Recent | Header | AppBar lacks actions (no search/refresh at screen level; History keeps its own toolbar actions) | acceptable — actions live per-page | INFO | design intent | none (recorded) |
| D-15 | Library | Navigation | Library reselect = settings sheet is undiscoverable (no affordance) | documented convention | INFO | hidden long-press-style semantics | none without IA approval (D-09 family) |

Previous-audit items RESOLVED since design-audit.md (verified in source,
do not redo): grid gutters unified; itemTitle typography; AppBar search 18sp;
Dictionary 15sp; TrackInfoDialog double-clip; HistoryItem fixed-height; dead
outer composition tree; ReaderPageIndicator restored; OcrLoadingIndicator
clearance; MoreScreen private GroupHeader; TabOptions index swap; Feed
raw-TopAppBar; Feed custom empty/loading/error rows; AddFeedDialog "✓"
markers; ManageFeedsScreen dividers/AMOLED row clash; FeedCustomizeDialog
structure; ResizableSheet geometry (verify remaining: unification deferred
by 09-05 B-R ruling — only reader chrome was moved to roles; ResizableSheet
still 24dp-radius variant, LOW).

## 22. Functional audit findings (VISIBLE STATE == ACTUAL STATE)

| Control | Verdict |
|---|---|
| Feed All/Popular/Latest chips | **FAIL — D-02/D-03 (P0)** |
| Feed source dropdown | PASS (persistence device-verified 2026-09-07) |
| Feed reselect → Manage Sources | PASS (device-verified) |
| Feed customize dialog (columns/style/toggles/default listing) | PASS visually; default-listing interacts with D-03 |
| Feed Load more / retry / end-of-list | PASS (code + 09-06 device plan; paging math ponytail-documented) |
| Recent tab switch (tap + swipe) | PASS (device-verified 09-07) |
| Recent reselect | functional, feedback broken (D-01) |
| Continue sort/downloaded chips | PASS (in-memory, deterministic applyFilters) |
| More navigation (all rows) | PASS (callbacks verified) |
| Settings grouping navigation | PASS (Destination ids 0-5, when-branches consistent) |
| Settings toggles/dialogs/sheets | PASS (device-verified 09-08 settings matrix) |
| Reader controls + TTS pill | PASS (device-verified stabilization matrix) |
| Library reselect/settings sheet | PASS |
| Browse reselect → Global Search (All + Has-results defaults) | PASS (defaults changed 09-06, persisted choice wins) |

## 23. Accessibility requirements

≥48dp touch targets (IconButton default; ManageFeedsScreen Switch+buttons
verify D-07); contentDescription on every icon-only action (FeedFilterBar
dropdown arrow is decorative inside its labeled FilterChip — CLOSED
2026-09-11, NO ACTION: chip label is the accessible name, a separate
description would be redundant); sp typography only; token contrast under all schemes
incl. AMOLED+Monochrome; no color-only state (chips = selected tonal + text);
state semantics via Switch/selected defaults; screen-reader order follows
visual order; large-font tolerance via heightIn.

## 24. Conflict register (docs vs source — Prompt.md §1)

1. **prd.md §1.4** describes 6-tab nav with History/Updates tabs — source
   has 5 tabs with Recent. Source is current truth; docs stale (D-12).
2. **design-audit.md (09-04)** enumerates Feed/More/typography weaknesses —
   most were FIXED by the 09-05..09-07 sets (verified in source; list in
   §21 "resolved"). It also proposed 6-tab order and reselect=scroll-to-top
   — SUPERSEDED by shipped 5-tab Recent IA + per-tab reselect semantics.
3. **design-audit.md §4 "Feed = only tab without animated icon"** — fixed
   (anim_feed_enter.xml exists).
4. **design.md §4 table** says section headers use ListGroupHeader —
   consistent with source; PreferenceGroupHeader (settings dialect) now
   shares Typography.header → colors converged. No conflict.
5. No unresolved doc-vs-code conflict found in spacing/frost/reader rules —
   docs match source.

## 25. Motion requirements

Reuse only: fadeIn+slideInVertically / fadeOut+slideOutVertically for pills
and bars; animateContentSize for expand/collapse (OCR rule rows);
animateScrollToPage for tab switches; AnimatedVisibility for nav bar +
state changes; fade-through 200ms tab transitions (soup motion lib);
standard M3 ripple/chip states. NO new animation systems, no decorative
motion.

## 26. Implementation sequence (proposed batches)

- **Batch 0 (DONE)**: this audit/map.
- **Batch 1 — Feed functional (P0)**: §15 fix + D-03 sentinel + D-04 guard +
  D-01 snackbar mount. Files: FeedScreenModel.kt (visibleFeeds + seeding),
  RecentTab.kt (host), FeedScreen.kt (D-04). Gate: spotless + unit tests +
  §15 verification plan on device.
- **Batch 2 — Recent/Feed visual polish**: D-05 divider decision, D-07
  verify. Presentation-only.
- **Batch 3 — Typography/spacing residue**: DONE 2026-09-09 — D-06/D-13/D-10
  audited + resolved as register dispositions (docs corrections; ZERO app
  source changed — verify-first audits found D-10 already conformed, D-13
  reuse would alter behavior, D-06 register item already documented).
  Findings beyond register (TtsPlaybackBar doc conflicts, upstream residue)
  recorded in memory.md Batch-3 block.
- **Batch 4 — Discoverability decisions**: DONE 2026-09-09 — D-08 search
  registration + D-11 MangaScreen error state + D-12 prd §1.4 docs fix,
  implemented + device-verified (see D-08/D-11/D-12 register rows).
- **Batch 5 — Device verification matrix** (per-batch, not only at end):
  DONE 2026-09-10 — full device pass over Batches 1–4 (SM_M066B, APK
  0.5.2-8264): Feed chips/grid sync D-02, All-persistence D-03, paging,
  ManageFeeds reorder/toggle, section order vs pref JSON; Recent tabs +
  D-01 reselect→resume; Settings Search D-08 (dictionary/exclusions/
  theme); MangaScreen D-11 success-path regression; typography/surfaces/
  a11y spot checks; 0 crashes. D-04 negative path + D-11 missing-path +
  stale-source = unit/code-verified only (no safe device trigger).
  Gates re-run green; exposed + fixed (test-only) a deterministic
  full-suite race in MangaScreenModelErrorStateTest. See memory.md
  Batch 5 block. VERDICT: Batches 1–4 device-conform, ready for user
  diff review.

Each batch: scope as listed, protected systems per §20, visual acceptance =
screenshot checklist (yomihon-ui skill), functional acceptance = §22
re-run, regression = full gates + device matrix.

## 27. Approval gates (user decisions required)

1. §15 fix shape — visibleFeeds filter (+ fallback semantics) APPROVED? (P0)
2. D-03 solution — sentinel vs drop-reseed.
3. D-08 — register Dictionary/OCR-exclusions screens in settings search?
   (RESOLVED 2026-09-09 — registered; see D-08 register row)
4. D-11 — MangaScreen error state (ScreenModel change)?
   (RESOLVED 2026-09-09 — implemented; see D-11 register row)
5. D-05 — drop Feed section divider? (DONE — Batch 2)
6. D-13 — extract shared grid-container helper? (RESOLVED 2026-09-09 —
   no; see D-13 register row)
7. Any new component, new surface role, new persistent preference, any
   navigation/IA change — NOT proposed here; each needs its own gate.

## 28. Acceptance criteria (map itself)

Per Prompt.md §29 checklist — all mapped: bottom-nav screens, major
destinations, settings screens (all 13 sub-screens listed), More recursive
audit, Recent, Feed, listing bug investigated (root cause in source),
typography/line-spacing/spacing/indentation/alignment/headers/surfaces,
glass placement explicit (none new), responsive, accessibility, feature
placement, code ownership, protected systems, implementation boundaries,
previous device-passes rechecked (09-07/09-08 passes treated as evidence;
D-01/D-02 found by re-audit anyway), no speculative implementation, no app
source modified.

## 29. Open questions

1. "Create" tab (user-referenced) — confirmed absent from source; referent
   never clarified. STATUS: OPEN — DECISION REQUIRED (user clarification
   only; build nothing until a referent is specified and spec'd).
2. FeedFilterBar source-chip trailing ArrowDropDown has no
   contentDescription — RESOLVED 2026-09-11: CLOSED, NO ACTION (decorative
   glyph inside labeled FilterChip; chip label is the accessible name).
3. FeedHeader listing label — RESOLVED 2026-09-11: KEEP two-line
   structure (ListGroupHeader source name + bodySmall listing label);
   collapsing rejected (overloads the section-header role; current rhythm
   device-verified incl. D-05 divider removal).

## 30. Master-session addendum (2026-09-13)

D-01..D-15 register unchanged (no statuses altered — none of the
touched files map to open D-items). New surfaces this session:

- FeedFilterBar rebuilt: stacked Column (selector row → listing chips
  → genre chips), spacedBy(small), horizontalScroll rows, M3 FilterChip
  with check leadingIcon; selector a11y stateDescription. Selector
  two-row spacing = task §8 satisfied. Reuses public
  BrowseSourceScreenModel genre helpers (Q2 precedent).
- MoreScreen: Studies card (Text Recognition / Dictionary lookup /
  Manage dictionaries) — new i18n label_studies (base only).
- OcrQueueScreen: two PreferenceGroupCards (Settings, Queue) + 12dp
  gap — grouped-surface standard now met.
- UpdatesScreen: filtered-empty shows UpdatesControls above
  EmptyScreen (dead-end removed).
- MangaNotesSection: shapes.small token (was 8dp literal).
- Large-font: heightIn(min 56dp) sweep ×4 (ClearDatabaseScreen,
  CommonMangaItem, UpdatesUiItem, BaseMangaListItem).
- Q9 a11y: CategoryListItem customActions move up/down; BaseSliderItem
  stateDescription; SourceSelectorDropdown selected-state cds;
  TtsPlaybackBar speed-menu cds; spinner audit OK.
- Not implemented (deferred, see roadmap §G): Liquid Mode/Background
  (opaque Scaffolds hide background layers; needs translucent
  containerColor audit — own batch).
