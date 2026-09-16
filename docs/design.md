# Yomitsu — Design & UX Specification

> Describes HOW the product should look/feel. Grounded in the existing design
> language; TTS-specific specs extend it and never override it.
> Compose implementation rules live in `docs/rules.md` §4.

---

## 1. Design philosophy

Yomitsu inherits Mihon's **Material 3** design language, rendered through
`MaterialExpressiveTheme` (Compose BOM 2026.06.01):

- Content-first: chrome recedes; the manga page is the hero.
- System-conformant: dynamic color (Monet) on Android 12+, light/dark/AMOLED,
  13 selectable color schemes.
- Consistent shared components from `presentation-core`
  (`tachiyomi.presentation.core.components.*`) — feature code composes these
  rather than inventing new primitives.
- Reader UI is overlay-based and auto-hiding (menus toggle, bottom pills slide in
  for transient state like `OcrLoadingIndicator`).
- Floating pill language: since the 2026-09 UI modernization set, the bottom
  navigation bar is a floating elevated pill (`RoundedCornerShape(28.dp)`,
  `surfaceContainer`), and the TTS playback bar is a rounded floating pill
  rather than a full-width strip — elevated, rounded surfaces at the bottom
  edge are the current pattern for both chrome and reader overlays.

New TTS UI must feel native to this system: a compact bottom pill in the reader,
settings rows/sliders using existing settings item specs.

## 2. Color system

Colors are NOT hard-coded per feature. All UI uses `MaterialTheme.colorScheme`
tokens supplied by `TachiyomiTheme`
(`app/src/main/java/eu/kanade/presentation/theme/TachiyomiTheme.kt`) via
`BaseColorScheme` implementations:

- Default scheme `TachiyomiColorScheme` (e.g., dark primary `0xFFB0C6FF`,
  dark background/surface `0xFF1B1B1F`, surfaceContainer `0xFF211F26`;
  light primary `0xFF0058CA`) — see
  `app/src/main/java/eu/kanade/presentation/theme/colorscheme/`.
- Alternatives: Monet (dynamic), Catppuccin, Nord, Tako, Yotsuba, YinYang,
  Midnight Dusk, Strawberry, Green Apple, Lavender, TealTurqoise, Tidal Wave,
  Monochrome; AMOLED variant flips surfaces to pure black.

Token mapping for TTS components (no new colors unless a proven need appears):

| Role | Token |
|---|---|
| Primary / accent | `colorScheme.primary` |
| Secondary | `colorScheme.secondary` |
| Background | `colorScheme.background` |
| Surface (bars/pills) | `colorScheme.surfaceContainer` (matches `OcrLoadingIndicator`); floating pills use `surfaceColorAtElevation(3.dp)` with slight alpha |
| Text | `colorScheme.onSurface` |
| Muted text | `colorScheme.onSurfaceVariant` |
| Success | `colorScheme.primary` (checkmark affordance); avoid new greens |
| Warning | `colorScheme.error`-adjacent tertiary only if unavoidable |
| Error | `colorScheme.error` + `onError` |
| Playback state | Communicated by icon + text (non-color), tinted with `primary` when active |

## 3. Theme

- Light/dark follow the system (`isSystemInDarkTheme()`), overridable in
  appearance settings (`AppTheme`, dark theme preference, AMOLED flag).
- Dynamic colors via `MonetColorScheme(context)` when selected.
- All reader overlays must remain legible under every scheme including AMOLED and
  Monochrome — use tokens only.

## 4. Typography

No custom fonts are introduced. The app uses Material 3 defaults provided by
`MaterialExpressiveTheme`; usages set roles explicitly per component:

| Use | Role |
|---|---|
| Screen titles / top bars | `titleLarge` / `titleMedium` |
| Section headers | `titleSmall` (with `ListGroupHeader` component) |
| Body text | `bodyMedium` / `bodyLarge` |
| Labels/settings descriptions | `bodySmall`, `labelMedium` |
| Captions/meta | `labelSmall` |
| Reader playback bar sentence text | `bodyMedium` (ratified 2026-09-11: compact pill chrome — bodyLarge inflates pill height over artwork at large font scales) |
| Reader playback bar position "x/y" | `labelMedium` |

## 5. Spacing

Follow existing component metrics rather than a new scale:

- Screen horizontal padding: 16 dp standard.
- Compact bars/pills: `horizontal = 24.dp, vertical = 12.dp`
  (`OcrLoadingIndicator` precedent). Exception: the TTS playback pill
  interior uses `horizontal = 16.dp, vertical = 4.dp` (ratified 2026-09-11 —
  single-line compact chrome; 24/12 inflates height over artwork).
- Icon spacing inside pills: `Arrangement.spacedBy(12.dp)`.
- Settings rows use `SettingsItems` specs (their built-in paddings).

## 6. Shapes

Material 3 shape tokens from the theme:

- Cards/sheets: `AdaptiveSheet` (presentation-core) handles bottom-sheet radius;
  dialogs use M3 defaults.
- Buttons: M3 defaults (`IconButton` for bar actions).
- Pills/bars: the bottom navigation bar and the TTS playback pill are FLOATING
  rounded pills (`RoundedCornerShape(28.dp)`, elevated shadow, inset from screen
  edges; playback pill `fillMaxWidth(0.92f)` + `widthIn(max = 560.dp)`).
  `OcrLoadingIndicator` remains a full-width `surfaceContainer` strip — the
  two families coexist: transient loading strips are full-width; interactive
  floating controls are rounded pills.
- No custom corner radii in feature composables beyond these shared shapes.

## 7. Icons

- Source: `androidx.compose.material.icons` extended set (dependency
  `androidx-compose-materialIcons`). Outlined variants dominate reader controls
  (OCR button uses `Icons.Outlined.DocumentScanner`).
- Planned TTS icons (existing material icons only):
  - Entry point: `Icons.Outlined.RecordVoiceOver`
  - Play/Pause: standard `PlayArrow` / `Pause`
  - Stop: `Stop`; Previous/Next sentence: `SkipPrevious` / `SkipNext`
  - Error state: `ErrorOutline` + retry via `Refresh`
- Every icon-only action requires a content description string resource.

## 8. Reader TTS controls

Placement mirrors established reader patterns:

- **Entry**: bottom-bar icon button in `ReaderBottomBar`, threaded exactly like
  `onClickOcr`.
- **Playback pill** (`TtsPlaybackBar`): floating rounded pill
  (`RoundedCornerShape(28.dp)`, elevation shadow, `fillMaxWidth(0.92f)`,
  `widthIn(max = 560.dp)`), `Alignment.BottomCenter`, AnimatedVisibility
  slide-up/fade; rendered BEFORE the dialog block in the reader overlay so all
  dialogs/overlays draw above it. Contents:
  - Current sentence text (single line, ellipsize, `bodyMedium` — ratified 2026-09-11, matches shipped TtsPlaybackBar)
  - Position indicator "x/y" (`labelMedium`)
  - Controls row: previous | play/pause | next | stop (`IconButton`s)
  - Speed chip ("1x" label) with `DropdownMenu` (0.5–3x) writing the shared
    speech-rate pref; applied live by the controller
  - Preparing/LoadingPage states show a small `CircularProgressIndicator`
    (20 dp, stroke 2 dp) instead of controls where applicable
  - Error state: message + retry action
- Unobtrusive rule: the pill never covers more vertical space than the OCR
  loading bar family; it hides with menus when appropriate and always yields to
  page interaction (controls are tap targets only).
- v1 has NO on-image highlighting (approved scope decision); current-sentence
  feedback is textual in the pill only (webtoon auto-scroll to the spoken
  region is navigational, not a highlight).

## 9. Accessibility

- Minimum touch target 48 dp for all controls (`IconButton` default size or
  explicit minimum).
- Content descriptions on every icon button from i18n strings.
- Contrast: rely on scheme tokens (`onSurface` on `surfaceContainer` meets WCAG;
  precedent: `ReaderOcrOverlayRenderer` already computes WCAG-safe colors for
  in-page bubbles).
- Scalable text: sp-based typography only; pill layout must tolerate large font
  scales (sentence line ellipsizes; controls wrap gracefully).
- State is never color-only: playing/paused/error differ by icon AND label.
- Screen reader order: sentence text → position → controls.

## 10. Motion

- Reuse existing patterns; no bespoke animation systems:
  - Bars appear/disappear with `fadeIn()+slideInVertically` /
    `fadeOut()+slideOutVertically` (OcrLoadingIndicator precedent; the playback
    pill slides half its height).
  - Standard M3 ripple/pressed states on controls; `animateContentSize` for
    expand/collapse (OCR exclusion rule rows).
- No looping animations while playing (progress is conveyed by text position);
  spinner only during Preparing/LoadingPage.
- Respect system animator duration scale implicitly (Compose defaults).

## 11. Responsive behavior

- Phone portrait: pill spans ~92% width (max 560 dp) above the bottom bar;
  controls centered.
- Phone landscape / tablets: same floating pill, width-capped; reader already
  adapts app bars (`ChapterNavigator` rails); no separate tablet layout for
  the pill.
- Dual-page/split pages: pill reflects the primary visible page's queue; worst
  case a one-tick stall on InsertPage transitions (accepted in root architect.md).

## 12. TTS visual feedback (v1)

- Current sentence: shown as text in the pill ("mini-bar highlight").
- On-image bbox highlight: explicitly deferred (approved decision). If added
  later, it must reuse the cached `OcrRegion.boundingBox` data and the drawing
  approach of `ReaderOcrOverlayRenderer` (normalized coords → view coords,
  WCAG-safe strokes) so it stays consistent with tap-to-lookup visuals.

## 13. Frosted Surface System (2026-09 UI modernization set 4)

Layered on M3 Expressive — material treatment on selected surfaces, never
the whole app. Semantic roles (presentation-core `theme/Translucent.kt`),
all gated by the existing "Translucent UI" appearance preference with an
opaque Material fallback:

| Role | Function | Treatment |
|---|---|---|
| Floating chrome | Reader bars/tray/navigator, TTS pill, OCR loading strip — surfaces floating over the manga artwork | `asFloatingChrome()`: real 0.85 alpha; artwork behind IS the frost |
| Frosted modal | AdaptiveSheet, ResizableSheet, TabbedDialog tabs — sheets above flat content | `asChromeContainer()`/`asFrostedModal()`: opaque pre-blend with background |
| Solid surface | Settings groups, lists, long-form content | plain Material surface tokens — never frosted |

Hard rules: no frost-on-frost nesting; no frost on long-form/readable
panels (settings, OCR popup, About); reader performance > blur strength —
there is NO true backdrop blur (artwork is a sibling View under the Compose
overlay; Compose blur cannot sample it; View RenderEffect on the fullscreen
viewer was rejected on perf). "Frost" = real-alpha over artwork where a
backdrop exists, pre-blend elsewhere, opaque when the preference is off.

### Grouped settings surfaces

ONE conceptual group = ONE visual surface: `PreferenceGroupCard`
(`surfaceContainerLow`, `shapes.large`, header inside the surface,
rows flat within, tonal only — no shadow, no frost, no outline, no
per-row cards). Separation from the background is guaranteed by the
gradient itself: the radial glow peaks at 55% blend toward
`surfaceContainerLow` and decays back to `background` at the corners,
so no card ever sits on its own tone (the old linear gradient ended
exactly at `surfaceContainerLow` at the bottom stop, washing cards
out in Teal & Turquoise, Taco, AMOLED).
Adopted by PreferenceScreen (all SearchableSettings screens),
SettingsMainScreen sections, MoreScreen, AboutScreen, OCR exclusions.
Section semantics over card count; spacing rhythm 16/12dp. Monochrome
scheme = shape-only delineation (all container tones identical, by
design). Screen title > section header > row title > supporting text
hierarchy preserved via existing `Typography.header`/roles.

## 14. Read aloud & voice settings screen (Phase 10A)

Entry: Settings root row "Read aloud & voice" (VolumeUp icon, after Reader),
reader quick-settings "Advanced voice settings" row (deep-link), settings
search. Composed of existing `Preference` components (`PreferenceGroup`,
`BasicListPreference`, `SliderPreference`, `TextPreference`, `InfoPreference`,
`CustomPreference`) — no new primitives.

- **Loading state**: `CustomPreference` with a `CircularProgressIndicator`
  (Anki screen pattern) while the engine initializes.
- **Failure state**: `InfoPreference` (voices unavailable) + retry
  `TextPreference`; loading is user-retryable, never a dead end.
- **Group 1 — Text to speech**: engine picker, language picker, locale picker
  (enabled only when a language is selected), voice picker. Voice list is
  filtered to the selected language (exact tag match when the tag contains a
  `-`, prefix match for bare tags, all voices when empty). Stale persisted
  values no longer offered by the engine render their RAW stored value via
  `subtitleProvider { v, e -> e[v] ?: v }` — never "null"; the engine picker
  falls back to the "Default system engine" label. The VOICE picker dialog is
  `searchable` (added 2026-08-31): an `OutlinedTextField` filter (placeholder
  = existing `action_search` string) sits above the list and case-insensitively
  matches entry labels — engines expose hundreds of voices; other pickers
  unchanged (`searchable` defaults false on `BasicListPreference`).
- **Group 2 — Voice calibration**: rate slider (50–300 %) + pitch slider
  (50–200 %, reusing the global `pref_tts_speech_rate`/`pref_tts_pitch` keys),
  preview row showing a small 2dp-stroke `CircularProgressIndicator` widget
  while the sample plays; tapping again stops it.
- **Voice profiles group**: named snapshot rows (apply + delete icon buttons,
  active-profile highlight) with a save row + name dialog (default name
  "N% · voice").
- **Group 3 — Advanced**: engine info row (label + voice count, no onClick),
  "Available voices: %d" info row, reset action + confirmation toast.
- **Voice metadata labels — API facts only**: quality ≥ 400 → "high quality",
  latency ≤ 200 → "low latency", network-required flag → "network" marker.
  No invented quality tiers; no display of raw numeric values.
- **Locale display names**: `Locale.forLanguageTag(tag).getDisplayName()` in
  picker entries; blank display name falls back to the raw tag.
- **Reader tab stays minimal**: rate slider (50–300 %) + auto page turn /
  auto next chapter / keep-screen-on checkboxes + one "Advanced voice
  settings" row linking to the full screen (pitch slider relocated here from
  v1's reader tab — one obvious home for calibration).
