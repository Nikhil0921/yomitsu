# Yomitsu UI Refinement — Phase 1 Audit

> READ-ONLY audit. No source files modified. Companion deliverable for the
> "Yomitsu UI Refinement & Feature Audit" task. Generated 2026-09-21 against
> HEAD `main @ 17773e92c` (v0.5.4.2 / versionCode 32).
>
> Scope: four modules — (1) Library list-item redesign, (2) Recent-tab
> category toggle, (3) More & About intent handling, (4) in-app changelog /
> update-checker architecture. All paths verified against source this session.

---

## 0. Conventions used throughout

- Package namespaces: `eu.kanade.*` (upstream), `mihon.*` (newer features),
  `tachiyomi.*` (library modules). `app.yomihon.*` does not exist and must
  never be introduced.
- Strings: moko-resources only — `i18n/src/commonMain/moko-resources/base/strings.xml`
  (snake_case keys); other locales are Weblate-owned, never hand-edited.
- Shared components live in `:presentation-core`
  (`tachiyomi.presentation.core.components.*`) and
  `:app` `eu.kanade.presentation.*`. Check these before writing new components.
- Theme: `MaterialTheme` + `MaterialExpressiveTheme` tokens; `MaterialTheme.padding.*`
  spacing tokens; `MaterialTheme.shapes.*` for corner radii. No literal colors.
- DI: Injekt only (`Injekt.get<...>()`, `injectLazy()`).
- Verification gates (CI order): `spotlessCheck` → `testDebugUnitTest` →
  `verifySqlDelightMigration` (only after DB schema change) →
  `assembleRelease -Pinclude-telemetry -Penable-updater`.

---

## Module 1 — Library List Item Redesign

### 1.1 Exact file inventory (verified)

| Role | Path |
|---|---|
| Display-mode enum | `domain/src/main/java/tachiyomi/domain/library/model/LibraryDisplayMode.kt` |
| Pref storing the mode | `domain/src/main/java/tachiyomi/domain/library/service/LibraryPreferences.kt` → `displayMode` (key `"pref_display_mode_library"`, default `CompactGrid`) |
| Pref-exposing screen model | `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryScreenModel.kt` → `getDisplayMode()` / `getColumnsForOrientation()` |
| Tab that hosts the library UI | `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt` |
| Layout dispatcher (grid vs list) | `app/src/main/java/eu/kanade/presentation/library/components/LibraryPager.kt` |
| **Current list implementation** | `app/src/main/java/eu/kanade/presentation/library/components/LibraryList.kt` (renders `MangaListItem`) |
| **Item composables (all modes)** | `app/src/main/java/eu/kanade/presentation/library/components/CommonMangaItem.kt` → `MangaListItem`, `MangaCompactGridItem`, `MangaComfortableGridItem` |
| Settings dialog w/ mode chips | `app/src/main/java/eu/kanade/presentation/library/LibrarySettingsDialog.kt` → `displayModes` list (line 220) + `DisplayPage` |
| Model feeding items | `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryItem.kt` (data class `LibraryItem` + `Badges`) |
| Cover component | `app/src/main/java/eu/kanade/presentation/manga/components/MangaCover.kt` (`Square`, `Book` ratios) |
| Reference card style to borrow | `app/src/main/java/eu/kanade/presentation/history/components/HistoryItem.kt` and `app/src/main/java/eu/kanade/presentation/updates/UpdatesUiItem.kt` (`UpdatesCompactCardHeader`) |

### 1.2 Current `LibraryDisplayMode.List` path (ground truth)

`LibraryPager.kt:81-93` — when `displayMode == LibraryDisplayMode.List`,
`LibraryList` is composed. `LibraryList.kt` is a `FastScrollLazyColumn` whose
rows are `MangaListItem` (`CommonMangaItem.kt:327-372`):

```
Row(heightIn min 56.dp, padding horizontal 16.dp / vertical 8.dp,
    selectedBackground + combinedClickable)
├── MangaCover.Square (fillMaxHeight, i.e. ~40dp square at that row height)
├── Text(title, bodyMedium, 2-line ellipsis, horizontal 16.dp gutter)
├── BadgeGroup(DownloadsBadge, UnreadBadge, LanguageBadge)
└── ContinueReadingButton (16dp icon, trailing) — only when enabled + unreadCount>0
```

Grid modes use `MangaGridCover` (`Box` + `aspectRatio(Book.ratio = 2/3)`) +
`GridItemSelectable` (clip to `shapes.small`, selected outline via `drawBehind`,
4dp inner padding).

### 1.3 Reference card metrics (to reuse for the new compact list item)

`HistoryItem.kt` (flat row, no Card wrapper):
- `HistoryItemHeight = 96.dp`; `MangaCover.Book(height 96dp)` → width = 96 × (2/3) = **64dp**
- row padding `horizontal = MaterialTheme.padding.medium (16dp), vertical = small (8dp)`
- title `bodyMedium` SemiBold 2-line ellipsis; metadata `bodyMedium` + 4dp top
- trailing `IconButton`s (48dp touch target) for favorite/delete

`UpdatesCompactCardHeader` (`UpdatesUiItem.kt:293-368`) + `UpdatesMangaGroupItem`
(`UpdatesUiItem.kt:158-228`):
- `Card(shape = shapes.large)`, outer padding `horizontal medium / vertical small`
- inner `Row(padding = small, verticalAlignment = CenterVertically)`
- `MangaCover.Book(width = 52.dp)` → height ≈ 78dp
- title `titleSmall` 1-line; metadata row `bodySmall` `onSurfaceVariant`,
  `DotSeparatorText` between `chapterName` and `relativeTimeSpanString(dateFetch)`
- trailing slot: expand arrow (group) or `ChapterDownloadIndicator` (single)

### 1.4 Layout modification plan — new compact `LibraryListItem`

Design decision (recorded here, implementation is a later authorized task):
adopt the **Updates card header language** over the flat HistoryItem row for
the new compact list item — consistent with the shipped "Recent → Updates
compact group card" batch (memory.md 2026-09-16) and the M3 Card + `shapes.large`
surface rule (durable decision #11: one group = one surface, `surfaceContainerLow`-family
fill, no shadow, no frost-on-frost).

Proposed structure (new composable `LibraryListItem` in
`app/src/main/java/eu/kanade/presentation/library/components/`, either in
`CommonMangaItem.kt` alongside `MangaListItem` or a new file
`LibraryListItem.kt` — prefer the new file, `CommonMangaItem.kt` is already
396 lines):

```kotlin
@Composable
internal fun LibraryListItem(
    libraryItem: LibraryItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickContinueReading: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val manga = libraryItem.libraryManga.manga
    Card(
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .padding(horizontal = MaterialTheme.padding.medium,
                    vertical = MaterialTheme.padding.small)
            .selectedBackground(isSelected)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { selected = isSelected },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover.Book(
                modifier = Modifier.width(52.dp),
                data = /* MangaCover(...) built from manga as LibraryList does today */,
                contentDescription = manga.title,
            )
            Column(modifier = Modifier.weight(1f).padding(start = MaterialTheme.padding.small)) {
                Text(
                    text = manga.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // reuse existing badge composables from LibraryBadges.kt
                    DownloadsBadge(count = libraryItem.badges.downloadCount)
                    UnreadBadge(count = libraryItem.badges.unreadCount)
                    LanguageBadge(isLocal = libraryItem.badges.isLocal,
                                  sourceLanguage = libraryItem.badges.sourceLanguage)
                }
            }
            if (onClickContinueReading != null) {
                ContinueReadingButton(
                    iconSize = 16.dp,
                    onClick = onClickContinueReading,
                    modifier = Modifier.padding(start = MaterialTheme.padding.small),
                )
            }
        }
    }
}
```

Wiring change (minimal-diff path, reuses existing preference):
1. Add `LibraryListItem` + keep `MangaListItem` untouched (it is also the
   browse-screen list item — do not change it in place).
2. In `LibraryList.kt:46`, replace `MangaListItem(...)` with
   `LibraryListItem(libraryItem = libraryItem, ...)`, passing the existing
   `badge` lambda content through `DownloadsBadge`/`UnreadBadge`/`LanguageBadge`
   directly (they're already separate composables in `LibraryBadges.kt`).
   `GlobalSearchItem` row at the top stays as-is.
3. No new pref needed for the item style itself — it is the visual body of the
   **existing** `LibraryDisplayMode.List` choice; the mode chip
   (`LibrarySettingsDialog.kt:220-240`, `action_display_list`) already toggles
   between it and the three grid modes.

Rejected alternative: a 5th `LibraryDisplayMode` enum value
(`CompactList`). Skipped: YAGNI — the existing `List` mode IS the compact list
mode today; only its row body needs restyling. Adding an enum value ripples
into `LibraryDisplayMode.kt` serializer, `LibraryPreferences`, chip list, and
`LibraryPager.kt` `when` — 4 extra touch points for zero functional gain.
Add a 5th mode only if a user demand for *both* flat-row and card-row list
styles surfaces.

---

## Module 2 — Recent Tab Category Toggle

### 2.1 Exact file inventory (verified)

| Role | Path |
|---|---|
| Host tab (pager + tab row) | `app/src/main/java/eu/kanade/tachiyomi/ui/recent/RecentTab.kt` |
| Page-data carrier | `app/src/main/java/eu/kanade/tachiyomi/ui/recent/RecentTabContent.kt` |
| Continue-reading page | `app/src/main/java/eu/kanade/tachiyomi/ui/recent/continuereading/continueTab.kt` |
| History page | `app/src/main/java/eu/kanade/tachiyomi/ui/recent/history/RecentHistoryTab.kt` |
| Updates page | `app/src/main/java/eu/kanade/tachiyomi/ui/recent/updates/RecentUpdatesTab.kt` |
| Updates screen (card/list rendering) | `app/src/main/java/eu/kanade/presentation/updates/UpdatesScreen.kt` (+ `UpdatesUiItem.kt`) |
| History screen (row rendering) | `app/src/main/java/eu/kanade/presentation/history/HistoryScreen.kt` (+ `components/HistoryItem.kt`) |
| Screen models | `app/src/main/java/eu/kanade/tachiyomi/ui/updates/UpdatesScreenModel.kt`, `app/src/main/java/eu/kanade/tachiyomi/ui/history/HistoryScreenModel.kt` |
| Library prefs (relevant: category tabs, show counts) | `domain/src/main/java/tachiyomi/domain/library/service/LibraryPreferences.kt` (`categoryTabs`, `categoryNumberOfItems`) |
| UI prefs | `app/src/main/java/eu/kanade/domain/ui/UiPreferences.kt` |
| Tab-row rendering precedent | `app/src/main/java/eu/kanade/presentation/library/components/LibraryTabs.kt` (`PrimaryScrollableTabRow`) |

### 2.2 Current Recent-tab structure (ground truth)

`RecentTab.kt:67-144` — a single `Screen` with **hard-coded** 3 pages:

```kotlin
val tabs = listOf(
    continueTab(),                                   // "Continue"
    recentHistoryTab(snackbarHostState),             // "History"
    recentUpdatesTab(snackbarHostState),             // "Updates"
)
val state = rememberPagerState { tabs.size }
...
PrimaryTabRow(selectedTabIndex = state.currentPage) { tabs.forEachIndexed { ... } }
HorizontalPager(state = state) { page -> tabs[page].content(...) }
```

`RecentTabContent` (verified, `RecentTabContent.kt`) is just:
```kotlin
data class RecentTabContent(
    val titleRes: StringResource,
    val content: @Composable (contentPadding: PaddingValues, snackbarHostState: SnackbarHostState, pagerState: PagerState) -> Unit,
)
```
No preferences, no category model, no screen model — pure static list.

The **Library tab** already has the category-toggle pattern to copy:
`LibraryContent.kt:61` — `showPageTabs` (from `LibraryPreferences.categoryTabs`)
gates a `LibraryTabs` row (`PrimaryScrollableTabRow` over `categories`,
`PagerState`-driven, `HorizontalDivider` underneath) above a per-category
`HorizontalPager`.

### 2.3 What "category toggle" means here + proposed preference strategy

Interpretation (assumed; flag to user if wrong): add a toggle on the
**Updates** page — "group/filter by category" — i.e. let the user show only
updates for a chosen library category, and/or collapse per-category sections.
The 3 top-level tabs (Continue/History/Updates) themselves are **frozen IA**
(durable decision #10: 5-tab IA frozen, per-tab reselect semantics
intentional) — do **not** restructure `RecentTab`'s own tab list; this task
is scoped to *within-page* display toggles, matching the Library-tab
precedent of `categoryTabs`/`categoryNumberOfItems`.

Proposed preference keys (extend `LibraryPreferences`, consistent with its
existing `pref_*`/`display_*` naming + `TriState` filter style):

| Pref | Key | Type / default | Controls |
|---|---|---|---|
| `recentUpdatesShowCategoryFilter` | `display_recent_updates_category_filter` | `Boolean`, default `false` | show a category chip/filter row atop the Updates page (mimics `FeedFilterBar` single-row pattern, `FeedScreen.kt`) |
| `recentUpdatesCategorySelection` | `recent_updates_category_id` | `Int`, default `0` (= all) | which category (or all) the Updates page filters to; written by the chip row |
| `recentHistoryShowCategoryFilter` | `display_recent_history_category_filter` | `Boolean`, default `false` | same toggle for the History page |
| `recentHistoryCategorySelection` | `recent_history_category_id` | `Int`, default `0` | per-category history filter |

Storage: `Int`/`Boolean` prefs via `PreferenceStore` in `LibraryPreferences.kt`
(matches `categoryNumberOfItems`/`categoryTabs` precedent — no DB change, no
`.sqm` migration needed, `verifySqlDelightMigration` stays green).
The category **list** itself comes from `GetCategories` (already injected in
`LibraryScreenModel`, reuse via `Injekt.get<GetCategories>()` in the new
updates/history screen models) — no new source of truth.

Wiring sketch (implementation phase, not this audit):
1. `UpdatesScreenModel`/`HistoryScreenModel`: add `categoryFilter` flow
   (combine `recentUpdatesCategorySelection.changes()` +
   `GetCategories.subscribe()`, mirror `LibraryScreenModel.getLibraryItemPreferencesFlow()`
   shape, `LibraryScreenModel.kt:347-378`).
2. `UpdatesScreen.kt`/`HistoryScreen.kt`: optional top row
   (`SettingsChipRow`/`FilterChip` row over categories, `FilterBar.kt`
   pattern, `FeedScreen.kt` precedent from memory.md 2026-09-13/09-16)
   gated on the `Boolean` pref.
3. Tab-row rendering: **no change** to `RecentTab.kt`'s
   `PrimaryTabRow` + `HorizontalPager` — the toggle is per-page content, not
   a new tab. This respects the frozen IA rule.

If the user instead means "let Recent-tab's own 3 tabs be toggleable in
Settings" (show/hide Continue/History/Updates): that touches
`RecentTabContent`'s static `listOf` + requires a new
`UiPreferences`/`LibraryPreferences` boolean per tab + a settings-screen
group; flag as follow-up, do not conflate with the within-page filter case
above.

---

## Module 3 — More & About Tab Intent Handling

### 3.1 Exact file inventory (verified)

| Role | Path |
|---|---|
| More-tab host + wiring | `app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt` |
| More screen (cards + rows) | `app/src/main/java/eu/kanade/presentation/more/MoreScreen.kt` |
| About screen | `app/src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt` |
| Support screen (Patreon/OC/Discord) | `app/src/main/java/mihon/feature/support/SupportUsScreen.kt` |
| URL constants | `core/common/src/main/kotlin/tachiyomi/core/common/Constants.kt` |
| Social-link icons | `presentation-core/src/main/java/tachiyomi/presentation/core/icons/*` (`CustomIcons`, `Discord`, `Facebook`, `Github`, `Reddit`, `X`) + `LinkIcon` |
| About host (Voyager screen, pushed by MoreScreen "About" row) | `app/src/main/java/eu/kanade/presentation/more/settings/screen/about/AboutScreen.kt` (also `SettingsScreen` `Destination.About` — see `MoreTab.kt:84`) |
| Snackbar/Toast dispatch | `MoreScreen.kt`: no snackbar host of its own; parent `MoreTab` uses `MainActivity` toast util for errors (search shows `context.toast(...)` in `AboutScreen.checkVersion`). `AboutScreen.kt` dispatches toasts for update-check errors, and pushes `NewUpdateScreen` (see Module 4) on success. |

### 3.2 Click-action inventory (verified against source)

**`MoreScreen.kt` rows (via `MoreTab` wiring):**
- Downloaded-only / Incognito toggles → local `screenModel` state (`MoreScreenModel`, `MoreTab.kt:89-141`), no nav.
- Text Recognition → `navigator.push(OcrQueueScreen)`
- Dictionary / Manage dictionaries → `navigator.push(DictionaryLookupScreen)` / `navigator.push(SettingsScreen(Destination.Dictionary))`
- Download queue / Categories / Stats / Data & storage → `navigator.push(...)`
- Settings → `navigator.push(SettingsScreen())`
- **Support us** → `navigator.push(SupportUsScreen())`
- **About** → `navigator.push(SettingsScreen(SettingsScreen.Destination.About))`
- Help → `uriHandler.openUri(Constants.URL_HELP)`

**`SupportUsScreen.kt` rows (Patreon / Open Collective / Discord):**
- `SupportItem` = `Card { TextPreferenceWidget(title, icon, trailing OpenInNew, onPreferenceClick = { uriHandler.openUri(url) }) }`
- Patreon → `Constants.URL_DONATE_PATREON` = `https://patreon.com/mihon/membership`
- Open Collective → `Constants.URL_DONATE_OPENCOLLECTIVE` = `https://opencollective.com/mihon/contribute`
- Discord → `Constants.URL_DISCORD` = `https://discord.com/invite/TXvTZdBuQa`

**`AboutScreen.kt` (verified, `AboutScreen.kt:69-212`):**
- Version row → copies debug info to clipboard (`CrashLogUtil`)
- Check for updates (gated on `updaterEnabled` build flag) → `checkVersion()` (see Module 4)
- "What's new" row (release/preview builds only, `!BuildConfig.DEBUG`) → `uriHandler.openUri(RELEASE_URL)` — a **plain browser link, no in-app content today**
- Licenses → `navigator.push(OpenSourceLicensesScreen())`
- Privacy policy → `uriHandler.openUri("https://yomihon.github.io/privacy/")`
- Footer `Row` of `LinkIcon`s: Website (`https://yomihon.github.io/`), Discord (`Constants.URL_DISCORD`), GitHub (`https://github.com/Nikhil0921/yomitsu`); X/Facebook/Reddit icons imported but commented out as unused.

### 3.3 Snackbar/Toast dispatch (verified)

- `MoreScreen.kt` itself declares **no** `SnackbarHostState` — it's a
  `ScrollbarLazyColumn` inside a plain `Scaffold`; all its rows are pure
  `navigator.push(...)` or `uriHandler.openUri(...)` with no local feedback
  surface. Parent `MoreTab` doesn't add one either (verified
  `MoreTab.kt:62-87`).
- `AboutScreen.checkVersion` (line 217-243) is the only toast-dispatching
  site in this module: `context.toast(...)` for `NoNewUpdate`/`OsTooOld`/
  exception cases, and `navigator.push(NewUpdateScreen(...))` on success —
  no `SnackbarHostState` anywhere in `AboutScreen.kt` (verified by full read;
  the file imports no snackbar API).

Implication for a future bottom-sheet "What's New" surface (Module 4): there
is **no existing `SnackbarHostState` to fold into** — a `WhatsNewSheet`
should be a new `AdaptiveSheet`-based composable (see `presentation-core/.../AdaptiveSheet.kt`,
read in full this session) hosted from `AboutScreen` directly, not a
snackbar replacement.

---

## Module 4 — In-App Changelog & App Update Checker

### 4.1 Exact file inventory (verified)

| Role | Path |
|---|---|
| GitHub-API fetch + parse | `data/src/main/java/tachiyomi/data/release/ReleaseServiceImpl.kt` (+ `GithubRelease.kt`, `data/src/main/java/tachiyomi/data/release/GithubRelease.kt`) |
| Domain interface | `domain/src/main/java/tachiyomi/domain/release/service/ReleaseService.kt` |
| Domain model | `domain/src/main/java/tachiyomi/domain/release/model/Release.kt` |
| Interactor (semver/tag compare + 3-day throttle) | `domain/src/main/java/tachiyomi/domain/release/interactor/GetApplicationRelease.kt` |
| App-level checker | `app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt` (`GITHUB_REPO`, `RELEASE_TAG`, `RELEASE_URL`) |
| Notification prompt | `app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateNotifier.kt` |
| Download job (APK) | `app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateDownloadJob.kt` |
| Full-screen "new update" UI | `app/src/main/java/eu/kanade/presentation/more/NewUpdateScreen.kt` + `app/src/main/java/eu/kanade/tachiyomi/ui/more/NewUpdateScreen.kt` (Voyager wrapper) |
| Trigger in About | `AboutScreen.kt:121-144` (`checkVersion`) + "What's new" row (`AboutScreen.kt:148-152`) |

### 4.2 Current parsing logic (ground truth)

`GithubRelease.kt` — kotlinx-serialization DTO mirroring GitHub's REST
`/releases/latest`:

```kotlin
data class GithubRelease(
    @SerialName("tag_name") val version: String,        // e.g. "v0.5.4.2"
    @SerialName("body") val info: String,                // full markdown release notes
    @SerialName("html_url") val releaseLink: String,
    @SerialName("assets") val assets: List<GitHubAsset>,
)
data class GitHubAsset(val name: String, @SerialName("browser_download_url") val downloadLink: String)
```

`ReleaseServiceImpl.latest()` (lines 18-36):
- GET `https://api.github.com/repos/${arguments.repository}/releases/latest`
  via the shared `NetworkHelper` client (no bare OkHttp — matches
  `rules.md §8`).
- Picks `downloadLink` from `assets` matching `BUILD_TYPES = [foss, arm64-v8a, x86_64]`
  (`getDownloadLink`, line 38-48) — note: **5 ABI APKs** ship per
  `state.md` (arm64-v8a, armeabi-v7a, x86_64, x86, universal) but the
  download-link matcher only keys off 3 suffixes; `armeabi-v7a`/`x86`/universal
  fall back to `map[null]` (universal) — flag as a latent gap, not in scope
  to fix here.
- `Release.info` = `release.info.substringBeforeLast("<!-->")` — a hard-coded
  sentinel: the **release-notes author is expected to end the body with a
  `<!-- ... -->` HTML comment block** (the checksums section); anything after
  the last `<!-->` is dropped client-side. Verified against
  `NewUpdateScreen` (Voyager, `ui/more/NewUpdateScreen.kt:24-26`), which
  *additionally* strips a `---\n...Checksums...\n---` markdown fence via
  regex, as a belt-and-suspenders second filter.
- `release.info` is then rendered client-side as markdown via
  `MarkdownRender` (`app/src/main/java/eu/kanade/presentation/manga/components/MarkdownRender.kt`,
  `GFMFlavourDescriptor`), in the full-screen `NewUpdateScreen`
  (`presentation/more/NewUpdateScreen.kt`).

`GetApplicationRelease` (interactor):
- 3-day throttle via `preferenceStore.getLong("last_app_check", 0)` (bypassed
  by `forceCheck = true`, which `AboutScreen`'s manual "Check for updates"
  click uses).
- `isNewVersion` (line 45-73): strips `[^0-9.]` from the tag; preview builds
  compare commit-count integer, release builds do a positional semver
  compare on dot-split ints.

`AppUpdateChecker.kt`:
- `GITHUB_REPO = "Nikhil0921/yomitsu"` (both preview + release — the `if/else`
  currently returns the same value in both branches; the intent per comment is
  fork-only, never upstream).
- `RELEASE_TAG`: preview → `r{COMMIT_COUNT}`, release → `v{VERSION_NAME}`.
- `RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"`
  — this exact string is what `AboutScreen`'s "What's new" row opens
  **in a browser today** (`AboutScreen.kt:148-152`).

`AppUpdateNotifier.promptUpdate` (line 38-74): builds a notification with two
actions — "Download" (`AppUpdateDownloadJob`) and "What's new"
(`Intent.ACTION_VIEW` on `release.releaseLink`, i.e. **also just a browser
link today**). No in-app sheet anywhere in the current update flow.

### 4.3 Proposed architecture — `WhatsNewSheet` (in-app structured release notes)

Goal: replace the two "open in browser" dead-ends (AboutScreen "What's new"
row, AppUpdateNotifier "What's new" action) with an in-app bottom sheet that
renders the same `Release.info` markdown already fetched by
`ReleaseServiceImpl` — no new network call, no new parsing pipeline, no new
dependencies.

**Reused, not reinvented:**
- `Release.info` (already fetched + `<!-->`-stripped) is the single source of
  release notes; `MarkdownRender` (`GFMFlavourDescriptor`) already renders
  it in `NewUpdateScreen` — extract that rendering block into the new
  composable verbatim.
- `AdaptiveSheet` (`presentation-core`, read in full) is the established
  bottom-sheet primitive (tablet = centered `Surface`, phone =
  `AnchoredDraggable` bottom sheet). Use it as the container — do not
  hand-roll a `ModalBottomSheet`/custom draggable.

**New composable** (app module, `eu.kanade.presentation.more` package,
consistent with `NewUpdateScreen.kt`'s package):

```kotlin
@Composable
fun WhatsNewSheet(
    versionName: String,
    markdown: String,
    onOpenReleasePage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(
        isTabletUi = isTabletUi(),
        enableImplicitDismiss = true,
        onDismissRequest = onDismiss,
    ) {
        // drag handle + version label
        // MarkdownRender(markdown, GFMFlavourDescriptor()) in a scrollable Column
        // trailing TextButton "Open on GitHub" → onOpenReleasePage (uriHandler.openUri(releaseLink))
    }
}
```

**Wiring changes (2 touch points, minimal diff):**
1. `AboutScreen.kt:148-152` — "What's new" row: replace
   `uriHandler.openUri(RELEASE_URL)` with a local
   `var showWhatsNew by remember { mutableStateOf(false) }` +
   `remember`-cached `Release` fetch (reuse `AppUpdateChecker().checkForUpdate(context, forceCheck = true)`,
   or add a lightweight `GetApplicationRelease`-only fetch path that skips the
   version-compare/notification side effects — `checkForUpdate`'s
   `NewUpdate` branch also fires `AppUpdateNotifier.promptUpdate` (a
   notification), which is wrong for a passive "view notes" tap; prefer a
   small dedicated `fetchLatestRelease()` helper wrapping
   `ReleaseService.latest(...)` directly, exposed on `AppUpdateChecker`).
   On success → `WhatsNewSheet(version, info, ...)`.
2. `AppUpdateNotifier.promptUpdate` "What's new" action — can't easily host
   Compose from a notification action; keep it as a browser link for now
   (documented limitation), or (larger, out of scope) route the notification
   action through an `Activity` that reuses the same `WhatsNewSheet`. Not
   recommended for this batch.

**No changes needed** to `Release.kt`, `GithubRelease.kt`,
`GetApplicationRelease.kt`, `ReleaseServiceImpl.kt` — the data path already
delivers `info` verbatim; `WhatsNewSheet` is purely a presentation swap of
an existing browser-link action.

i18n: reuse existing `MR.strings.whats_new`, `MR.strings.update_check_open`
(both verified in `i18n/.../base/strings.xml`) — zero new strings needed for
the sheet itself; add only if a distinct "View release notes" label is
desired.

---

## 5. Risk / gotcha register (this audit)

1. **`LibraryListItem` new file vs. `CommonMangaItem.kt`** — `CommonMangaItem.kt`
   already holds 4 item variants + helpers (396 lines); a new
   `LibraryListItem.kt` keeps the diff surgical and avoids re-triggering
   ktlint import-sorting churn across 4 unrelated composables.
2. **`MangaListItem` is shared** with browse screens (verify before reuse as a
   "compact library row") — do not restyle `MangaListItem` in place; add a
   sibling composable (Module 1 §1.4) so browse-screen list rows are
   unaffected.
3. **`RecentTabContent` has no `PreferenceStore`/screen-model wiring yet** —
   adding category filters means extending `UpdatesScreenModel`/
   `HistoryScreenModel` (both already `Injekt.get()`-injected), not
   `RecentTab.kt` itself.
4. **`RecentTab`'s 3-tab structure is frozen IA** (durable decision #10) —
   the toggle must be within-page, never a 4th/5th top-level tab or a
   nav-reorder of the 5 main tabs.
5. **`ReleaseServiceImpl.getDownloadLink`** only matches 3 of 5 shipped ABIs
   (arm64-v8a, x86_64, foss); v7a/x86/universal all resolve to the
   `null`-keyed universal entry via the `?: map[null]` fallback — works
   today, but if a future release drops the universal APK, `armeabi-v7a`/`x86`
   devices silently get no download link. Flag for a follow-up task, not this
   audit.
6. **`<!-->` sentinel in release notes** is a hand-maintained convention in
   the release body, not enforced by any CI gate today — a release authored
   without it will render the checksums block into `WhatsNewSheet`.
   `NewUpdateScreen`'s regex-stripping of the `---Checksums---` fence is the
   only second line of defense; `WhatsNewSheet` should reuse that exact
   regex (from `ui/more/NewUpdateScreen.kt:24-26`) rather than trust the
   `<!-->` sentinel alone.
7. **i18n**: all Module-1/Module-2 proposed pref labels + sheet strings must
   land in `i18n/src/commonMain/moko-resources/base/strings.xml` only;
   Weblate owns every other locale.
8. **Read-only constraint honored**: no file in this repo has been written or
   modified by this audit; this document is the sole deliverable.
