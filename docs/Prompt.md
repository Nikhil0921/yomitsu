# YOMITSU MASTER IMPLEMENTATION PROMPT
## Q9 Finalization + Feed UI Correction + Verification

You are continuing development of **Yomitsu**, the user's Android manga-reader fork.

This is an **implementation session**, not a brainstorming session.

Operate with:

- **Audit first**
- **Smallest safe diff**
- **Root-cause fixes**
- **No speculative refactors**
- **No architecture churn**
- **No silent scope expansion**
- **Test everything deterministic**
- **Device-verify before declaring success**
- **Do not commit/tag/release without explicit user authorization**

---

# 1. CURRENT PROJECT STATE

Current branch:

```text
main
```

Current baseline reported:

```text
b2f1da316
```

This is already past the `v0.5.4` tag in documentation.

There are **uncommitted user changes in the tree**.

Treat those changes as valuable existing work.

Do NOT reset, discard, clean, stash, squash, or overwrite them unless explicitly authorized.

---

# 2. ROADMAP / SCOPE LOCK

The roadmap currently has:

```text
Q3 = HARD HALTED
Q4 = HARD HALTED
Q5 = HARD HALTED
Q6 = HARD HALTED
Q7 = HARD HALTED
Q8 = HARD HALTED
Q9 = AUTHORIZED / EXECUTED
AnymeX UI track = separately authorized
```

Therefore:

## HARD RULE

Do NOT implement:

- Q3
- Q4
- Q5
- Q6
- Q7
- Q8

Do not sneak features from those phases into this session.

Do not renumber Q9.

The Feed correction in this prompt is a **user-authorized UI correction** and should be treated as its own focused correction within the current work, not as permission to reopen unrelated roadmap items.

---

# 3. IMPORTANT UNCOMMITTED USER WORK

The user has existing uncommitted Q2 work.

Treat these as **UNTouchable user work**:

```text
BrowseSourceScreen.kt
BrowseSourceScreenModel.kt
docs/roadmap / memory / phase changes
GenreTogglesTest.kt
```

The Q2 Browse genre-filter work is already device-tested and must not regress.

One small shared change already exists:

```text
BrowseSourceScreenModel.kt
genreToggles()
isGenreSelected()
toggleGenreSelection()
```

were made accessible so Feed can reuse the filter model.

Do NOT revert this.

Do NOT redesign Q2.

Do NOT commit Q2 separately.

Do NOT clean up unrelated Q2 code merely because it looks refactorable.

---

# 4. WHAT HAS ALREADY BEEN IMPLEMENTED

The current session already implemented/audited:

## Tscan Feed crash

Root cause:

`FeedScreenModel.fetchSection()` was executing:

```text
source.getPopularManga()
source.getLatestUpdates()
```

on Main through `screenModelScope`.

Fixed by wrapping the network work in `withIOContext`.

All three paths are covered:

```text
loadSections
loadMore
retry
```

Preserve this fix.

---

## BUG-003

`TtsPlaybackController.kt`

Paused `onPageSelected` now resets:

```text
resumeIndex = 0
```

when changing pages.

Preserve.

---

## BUG-004

OCR cache now keys page lookup by:

```text
chapter
page
ocr_model
```

`OcrCacheStore` and repository pass the OCR model.

No migration was required because the required uniqueness/index structure already exists.

Preserve.

---

## BUG-005

NextChapter now cancels `prefetchJob`.

Preserve.

---

## BUG-006

Android TTS voice-setting paths now check:

```text
TextToSpeech.SUCCESS
```

Preserve.

---

## BUG-007

Verified unreachable.

No code change.

Do not invent a fix.

---

## BUG-008

Removed no-op:

```text
.clickable { }
```

from dictionary result card.

Preserve.

---

## BUG-009

`FeedScreenModel` now supports:

```text
loadSectionsOnStart: Boolean = true
```

and Manage Feeds passes:

```text
false
```

to prevent unnecessary loading.

Preserve.

---

## BUG-010

Settings search now uses synchronous `remember()` instead of `produceState`.

Also registered:

```text
SettingsReaderToolbarScreen()
AppLanguageScreen()
```

in the unindexed settings list.

Preserve.

---

# 5. Q9 ACCESSIBILITY WORK ALREADY IMPLEMENTED

The Q9 accessibility batch implemented:

### Category

Move-up/move-down accessibility custom actions.

### BaseSliderItem

Added `stateDescription = valueString`.

This covers approximately 20 slider callers.

### Source selector

Selected/not-selected state semantics.

### TTS playback speed

State description.

### Large-font support

Changed fixed:

```text
.height(56.dp)
```

to:

```text
.heightIn(min = 56.dp)
```

in the four audited locations.

### Spinner

Audited and determined acceptable.

Do not change without evidence.

---

# 6. OCR/TTS LATENCY WORK ALREADY IMPLEMENTED

The current v0.5.4 tree already includes:

- OCR queue parallelism = 3
- HIGH priority OCR work
- GLENS unlocked
- parallel prefetch
- depth 2 prefetch
- ChapterCache reuse

Remote page resolution now:

1. Checks cached page list first.
2. Falls back to source network request.
3. Checks cached image files.
4. Refetches on decode failure.
5. Properly rethrows `CancellationException`.

The remaining reported 30–60 second first-page delay is primarily the GLENS service round-trip.

The app-side duplicate page-list/image requests have already been addressed.

Do not redesign OCR/TTS during this task.

The final device report MUST include timing evidence from logcat.

---

# 7. FEED GENRE FILTER FEATURE

The current Feed implementation includes source-supported filter chips.

State includes:

```text
genreToggles
sourceFilterList
```

Feed can use:

```text
source.getSearchManga(page, "", activeFilters)
```

when filters are active.

Source selection:

- loads the source's supported filter list,
- does not unnecessarily refetch merely to change visible source selection,
- restores the persisted source's filter list.

Genre toggling triggers section refresh.

This implementation is already based on the Q2 precedent.

## DO NOT REMOVE IT.

Genre chips should remain available when the selected source exposes supported filter leaves.

If a source does not expose supported filter leaves, absence of chips is correct.

Do not fabricate filters.

---

# 8. NEW USER-AUTHORIZED FEED UI CORRECTION

## THIS SECTION SUPERSEDES THE PREVIOUS STACKED FEED SELECTOR LAYOUT.

The latest session rebuilt `FeedFilterBar` into stacked rows.

The user explicitly does NOT want that final arrangement.

The desired arrangement is the earlier compact layout.

---

# 9. REMOVE `ALL` FROM FEED LISTING SELECTION

The Feed listing selector currently exposes:

```text
All
Popular
Latest
```

Change the user-facing listing selector to:

```text
Popular
Latest
```

`All` must disappear from:

### Feed screen

and:

### Feed Settings / Customize Feed → Default listing

There must be no visible:

```text
All
```

listing button/chip in either location.

---

## IMPORTANT DISTINCTION

Do NOT remove:

```text
All sources
```

from the source selector if that is an existing valid source-selection option.

These are different concepts:

```text
All sources
```

is source selection.

```text
All
```

was a listing mode.

Only the listing mode `All` is being removed.

---

# 10. AUDIT THE UNDERLYING LISTING STATE BEFORE CHANGING IT

Do not simply delete an enum/state because the UI no longer displays it.

First inspect:

- listing model/state
- Feed preferences
- persisted default listing
- serialization
- state restoration
- tests
- FeedScreenModel
- Feed settings

Determine whether existing installations may contain a legacy `All` preference.

If they do:

- safely migrate/fallback that value to an appropriate valid listing,
- do not expose `All`,
- do not corrupt preferences,
- do not add a database migration.

Do not introduce a new architecture merely to handle this.

Use the smallest compatible solution.

---

# 11. RESTORE POPULAR/LATEST BESIDE SOURCE SELECTOR

The desired Feed primary selector row is:

```text
[ Source selector ▼ ]   [ Popular ] [ Latest ]
```

NOT:

```text
[ Source selector ▼ ]

[ Popular ] [ Latest ]
```

and NOT:

```text
[ Source selector ▼ ]

[ All ] [ Popular ] [ Latest ]
```

The source selector should remain on the left.

Popular and Latest should be positioned immediately to its right where screen width allows.

The source selector's existing stable-width and ellipsis behavior must remain intact.

Do not make the source selector jump in width when source names change.

---

# 12. RESPONSIVE BEHAVIOR

The compact horizontal arrangement is the preferred layout.

However, do not sacrifice usability on narrow screens.

Audit actual available width.

If all controls cannot fit safely:

- use the existing appropriate Compose/M3 horizontal scrolling or responsive behavior,
- preserve touch targets,
- avoid clipping,
- avoid overlap,
- avoid arbitrary hardcoded widths.

Do NOT invent a new responsive system.

Do NOT blindly force everything into a row if that produces broken UI.

The intent is:

> compact horizontal primary controls, with graceful narrow-screen handling.

---

# 13. REMOVE DUPLICATED SOURCE/LISTING TITLE

The Feed currently repeats information after the filter/navigation controls.

It displays another title indicating things such as:

```text
Source Name
Popular Listing
```

Remove this redundant source/listing heading.

The source is already shown in:

```text
Source selector
```

The listing is already shown by:

```text
Popular / Latest selected state
```

Therefore do NOT repeat:

```text
Source Name
Popular Listing
```

below the controls.

Do not replace it with another equivalent duplicate header.

The actual Feed manga content should begin after the filter controls.

---

# 14. FEED HEADER DENSITY

The current vertical spacing is too large.

Tighten the Feed structure.

Desired hierarchy:

```text
Feed header
   ↓ compact spacing
[ Source ] [ Popular ] [ Latest ]
   ↓ compact spacing
[ Genre chips when available ]
   ↓ appropriate content spacing
Manga sections/grid
```

Avoid:

- excessive blank space,
- duplicate header rhythm,
- unnecessary stacked controls,
- arbitrary spacing islands,
- inconsistent indentation.

Use existing Yomitsu Material 3 spacing tokens.

Known design tokens:

```text
4dp
8dp
16dp
24dp
32dp
```

with existing documented special 12dp usages.

Do not invent a new spacing scale.

---

# 15. FEED HEADER COLLAPSE ON SCROLL

The Feed should use vertical space efficiently.

When the user scrolls DOWN:

```text
Feed header/filter chrome minimizes/collapses
```

so more manga content becomes visible.

When the user scrolls UP:

```text
Feed header/filter chrome returns
```

appropriately.

The behavior must be:

- smooth,
- predictable,
- reversible,
- non-jittery.

Do not hide controls permanently.

Do not break touch targets.

Do not introduce nested/competing scrolling containers.

Do not interfere with:

- source selection,
- Popular selection,
- Latest selection,
- genre filters,
- paging,
- load-more,
- manga item scrolling.

Before implementing this, audit the existing Feed scroll container.

If an existing Compose/M3 collapsing behavior can be reused, use it.

Do not create a custom scroll framework unless absolutely necessary.

---

# 16. GENRE FILTER ROW

Genre/source-supported filter chips remain.

Their placement should be:

```text
Primary row:
[ Source ] [ Popular ] [ Latest ]

Secondary row:
[ Filter ] [ Filter ] [ Filter ] ...
```

The second row should:

- align correctly with the Feed content inset,
- use horizontal scrolling where appropriate,
- retain M3 chip styling,
- have appropriate compact spacing,
- avoid excessive vertical gaps.

Do not redesign the filter semantics.

Do not turn all source filters into arbitrary manually curated "genres."

The existing source-supported filter model is intentional.

---

# 17. FEED SETTINGS DEFAULT LISTING

In:

```text
Feed Settings
→ Default listing
```

change:

```text
All
Popular
Latest
```

to:

```text
Popular
Latest
```

Requirements:

- selected state remains obvious,
- persistence remains correct,
- no orphaned space remains,
- spacing remains consistent with the settings design system,
- terminology matches Feed screen,
- no duplicate controls are introduced.

---

# 18. FEED DATA BEHAVIOR MUST REMAIN CORRECT

This is a UI correction, not permission to weaken Feed filtering.

The final data relationship must remain:

```text
Selected source
       +
Selected listing
       +
Selected source-supported filters
       ↓
actual Feed sections
```

Example:

```text
Source = Asura
Listing = Popular
```

must display the relevant Asura Popular Feed.

And:

```text
Source = Asura
Listing = Latest
```

must display the relevant Asura Latest Feed.

The highlighted listing must correspond to the actual data being rendered.

Never allow:

```text
Popular highlighted
+
Popular AND Latest data displayed
```

unless the underlying product specification explicitly requires that behavior.

---

# 19. IMPORTANT: DO NOT BLINDLY REWRITE FEEDFILTERBAR

Before editing:

Inspect:

```text
FeedScreen.kt
FeedScreenModel.kt
FeedTab.kt
Feed settings/customize implementation
Feed preferences
listing model/state
```

Trace:

```text
source selection
listing selection
genre filter state
default listing
persistence
section loading
paging
scroll container
header rendering
```

Identify the smallest set of changes.

The likely UI owner is:

```text
app/src/main/java/eu/kanade/presentation/feed/FeedScreen.kt
```

but confirm this from source before editing.

Do not assume.

---

# 20. PROTECTED AREAS

Do NOT modify:

- Reader navigation
- TTS controller architecture
- OCR engine architecture
- OCR exclusion architecture
- navigation IA
- five-tab navigation
- Browse IA
- backup architecture
- database schema
- dependency graph
- source extension APIs
- Feed paging architecture
- Manage Feeds architecture
- Q2 genre filter architecture

unless a minimal supporting change is objectively required by compilation or correctness.

No new dependency.

No database migration.

No unrelated refactoring.

---

# 21. ANYMEX UI TRACK

AnymeX is a **visual reference only**.

The available evidence is:

```text
11 screenshots
720 × 1452
dark gray / purple family
#3f3846-family
Liquid Mode
Liquid Background
Grain Texture
OLED
poster color
```

Do NOT copy exact dimensions from AnymeX.

Use Yomitsu's existing M3 tokens.

The user-authorized design direction remains:

- structured,
- modern,
- premium,
- spacious,
- minimal,
- manga-reader personality,
- subtle depth,
- no neon,
- no cyberpunk,
- no excessive gradients,
- no glass-everywhere treatment.

---

# 22. LIQUID BACKGROUND IS DEFERRED

Do NOT implement:

- Liquid Mode
- Liquid Background
- Grain Texture
- OLED theme layer
- poster-color background system

in this task.

The investigation concluded that current opaque Scaffold containers hide background layers.

A proper implementation would require:

```text
theme-derived background
+
root gradient
+
translucent container audit
+
multi-screen verification
```

That is intentionally deferred.

Do NOT half-implement it.

Do NOT reintroduce true backdrop blur.

---

# 23. TRUE BACKDROP BLUR IS REJECTED

Do not introduce:

```text
RenderEffect
```

or a sibling-sampling blur architecture.

Reason:

- Compose rendering constraints,
- performance,
- battery,
- AMOLED implications,
- architecture blast radius.

Use existing Yomitsu frost/surface roles only.

---

# 24. DESIGN SYSTEM RULES

Use:

```text
MaterialTheme.colorScheme.*
MaterialTheme.padding.*
MaterialTheme.shapes.*
existing Typography roles
```

Known spacing:

```text
extraSmall = 4dp
small      = 8dp
medium     = 16dp
large      = 24dp
extraLarge = 32dp
```

Screen horizontal inset:

```text
16dp
```

Existing grouped settings rhythm:

```text
PreferenceGroupCard
12dp inter-card gap
```

Do not add arbitrary dimensions when an existing token already expresses the intent.

---

# 25. AUDIT-FIRST EXECUTION ORDER

Perform the work in this order.

## STEP 1 — Repository state

Inspect:

```text
git status
git diff --stat
git diff
```

Understand every existing user change.

Do not overwrite anything.

---

## STEP 2 — Feed audit

Inspect:

```text
FeedScreen.kt
FeedScreenModel.kt
FeedTab.kt
ManageFeedsScreen.kt
Feed preference/state definitions
Feed tests
```

Map:

```text
source selector
listing selector
genre filters
source/listing title
scroll container
header
default listing
persistence
```

---

## STEP 3 — Existing UI map comparison

Compare the current implementation against:

```text
docs/ui-implementation-map.md
docs/design.md
docs/design-audit.md where still historically relevant
docs/implementation-roadmap.md
docs/memory.md
docs/phase.md
```

Remember:

The implementation map is the current UI blueprint, but newer explicit user decisions supersede older layout assumptions.

The user's latest Feed instruction supersedes the previously implemented stacked selector layout.

---

## STEP 4 — State/persistence audit

Determine:

- where `All` is defined,
- where Popular/Latest are defined,
- whether `All` exists in persisted preferences,
- how default listing is restored,
- whether tests depend on `All`.

Design the smallest compatibility-safe change.

---

## STEP 5 — Implement Feed correction

Only after the audit:

1. Remove `All` from user-facing listing controls.
2. Safely handle legacy `All` state if required.
3. Restore Popular/Latest beside source selector.
4. Remove duplicate source/listing title.
5. Tighten header spacing.
6. Preserve genre filter row.
7. Implement/reuse header collapse-on-scroll.
8. Correct settings Default listing UI.
9. Preserve actual Feed filtering.

---

# 26. TEST REQUIREMENTS

Add or update only the tests required for deterministic changed behavior.

At minimum test:

### Listing

```text
Popular selection
Latest selection
All absent from UI/state where appropriate
```

### Persistence

```text
Popular default persists
Latest default persists
legacy All handled safely if encountered
```

### Composition

```text
source + listing
source + listing + genre filter
```

### Genre

```text
chip toggle
chip deselection
source-specific filters
```

### Feed loading

Verify:

```text
loadSections
loadMore
retry
```

still work after the UI correction.

### Existing tests

Reuse existing patterns.

Do not create a giant new test framework.

---

# 27. FORMATTING / BUILD GATES

The host has no Android SDK.

Use the known Docker environment:

```text
image:
vsc-yomihon-e24e3bd7e46d5060e88796634a865cb501faf4766a48662dbc474a380427c674
```

Run as:

```text
-u vscode
```

with:

```text
-v "$PWD":/workspace
-v yomihon-gradle-home:/home/vscode/.gradle
-v yomihon-android-home:/home/vscode/.android
-w /workspace
```

and:

```text
GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx4g"
```

Do NOT use 2560m heap.

The reported environment needs approximately 4 GB Gradle heap for reliability.

---

# 28. REQUIRED GATES

Run in this order:

```text
./gradlew spotlessApply
```

then:

```text
./gradlew spotlessCheck
```

then:

```text
./gradlew testDebugUnitTest
```

then:

```text
./gradlew verifySqlDelightMigration
```

then:

```text
./gradlew :app:assembleDebug
```

Do not skip failed gates.

If one fails:

1. diagnose root cause,
2. make smallest fix,
3. rerun affected gate,
4. continue only after it passes.

---

# 29. DEVICE

Primary device:

```text
SM_M066B
```

Wireless ADB:

```text
192.168.29.98:5555
```

Device verification is REQUIRED.

Do not claim device verification from compilation alone.

---

# 30. FEED DEVICE TEST MATRIX

On the device verify:

### Basic

- Open Feed.
- Feed loads without crash.
- Source selector opens.
- Source selection works.
- Popular selection works.
- Latest selection works.

### Listing UI

Verify:

```text
All = absent
Popular = present
Latest = present
```

### Layout

Verify:

```text
[Source] [Popular] [Latest]
```

are in the intended compact arrangement.

Check:

- indentation,
- spacing,
- chip alignment,
- source selector width,
- ellipsis,
- narrow-width behavior,
- no overlap,
- no clipping.

### Duplicate information

Confirm that the old:

```text
Source Name
Popular Listing
```

duplicate title is gone.

### Genre

For a source that supports filters:

- genre/filter chips appear,
- toggle works,
- selected state is visible,
- results actually change,
- deselection restores expected results.

For a source with no supported filter leaves:

- no fake filter chips appear.

### Scroll

Scroll DOWN:

```text
header minimizes
```

Scroll UP:

```text
header returns
```

Verify:

- no jitter,
- no jump,
- no broken grid,
- no nested-scroll issue.

### Paging

Verify load-more still works.

### Persistence

Change:

```text
source
listing
filters
```

navigate away/back and verify expected persistence.

### Manage Feeds

Open Manage Feeds and verify it still works.

---

# 31. FULL REGRESSION MATRIX

Because this session contains more than Feed work, run the relevant existing regression matrix for:

### Accessibility

- category reorder actions
- slider descriptions
- source selected state
- TTS speed state
- large-font layout

### Browse Q2

- sources
- search
- genre/filter chips
- query + filter intersection
- empty result
- retry
- navigation state preservation

Do NOT alter Q2.

### Library

- grid
- categories
- selection
- filters

### Recent

- Continue
- History
- Updates

### More

- Studies card
- Library card
- settings navigation

### Settings

- settings search
- reader settings
- app language
- searchable settings

### Reader/TTS

Only smoke-test.

Do not modify architecture.

### OCR

Smoke-test relevant queue/preload paths.

Capture timing evidence where required.

---

# 32. OCR/TTS LATENCY EVIDENCE

The final report must include actual device evidence for the current preload path.

Capture logcat evidence showing:

- page-list cache reuse where applicable,
- image-cache reuse where applicable,
- OCR startup/preload timing,
- remaining network/service latency where observable.

Do not claim the entire 30–60 second delay was eliminated.

The current verdict is that the remaining delay is largely GLENS service round-trip.

---

# 33. DOCUMENTATION

After implementation and verification, update:

```text
docs/implementation-roadmap.md
docs/ui-implementation-map.md
docs/memory.md
docs/phase.md
```

Record:

### Feed correction

Document:

- removal of listing `All`,
- Popular/Latest compact placement,
- removal of duplicate source/listing header,
- header collapse behavior,
- preserved genre filters,
- tests,
- device evidence.

### Q9

Record actual final status.

### Background

Record:

```text
Liquid/Background = designed but deferred
```

Do not mark it implemented.

### AnymeX

Keep the track separate from Q-numbering.

---

# 34. FINAL REPORT

At the end produce a concise but evidence-based final report.

Include:

```text
SESSION RESULT
```

Then sections:

```text
A. Repository state
B. Scope executed
C. Feed UI correction
D. Q9 accessibility
E. Bugs fixed
F. Tscan fix
G. OCR/TTS latency work
H. Tests
I. Formatting
J. SQLDelight verification
K. Build
L. Device verification
M. Documentation
N. Remaining risks / deferred work
```

For every claim use actual evidence.

Do not say:

```text
PASS
```

without explaining what was tested.

---

# 35. COMMIT RULE

Absolutely NO:

```text
git commit
git tag
git push
GitHub release
APK release
```

unless the user explicitly authorizes it.

Leave the working tree available for user review.

---

# 36. SUB-AGENT RULE

Maximum:

```text
2 parallel sub-agents
```

at any time.

Queue additional work sequentially.

Use agents for:

- targeted audits,
- tests,
- independent verification,

not for uncontrolled broad rewrites.

All agents must respect:

```text
smallest diff
protected areas
Q3-Q8 hard halt
Q2 uncommitted work
no commits
```

---

# 37. IMPORTANT FAILURE-PREVENTION RULES

Never:

- reset the repository,
- discard user changes,
- stash user work,
- rewrite Feed architecture unnecessarily,
- redesign the Feed from scratch,
- reintroduce `All`,
- remove `All sources`,
- remove genre filters,
- break source persistence,
- alter Feed paging,
- modify navigation IA,
- modify reader architecture,
- introduce true blur,
- implement Liquid Background,
- add dependencies,
- create a DB migration,
- silently commit.

If something appears inconsistent:

**audit first, then fix only the proven root cause.**

---

# 38. FINAL FEED VISUAL CONTRACT

The preferred final Feed structure is:

```text
┌────────────────────────────────────────────┐
│ Feed                                  +   │
│                                            │
│ [ Source ▼ ]    [ Popular ] [ Latest ]     │
│                                            │
│ [ Filter ] [ Filter ] [ Filter ] →         │
│                                            │
│ Manga content                              │
│                                            │
│ [cover] [cover] [cover] [cover]            │
│ [cover] [cover] [cover] [cover]            │
└────────────────────────────────────────────┘
```

On scroll down:

```text
┌────────────────────────────────────────────┐
│                                            │
│ [compact/minimized chrome]                 │
│                                            │
│ [cover] [cover] [cover] [cover]            │
│ [cover] [cover] [cover] [cover]            │
│ [cover] [cover] [cover] [cover]            │
└────────────────────────────────────────────┘
```

On scroll up:

```text
Feed header
[ Source ] [ Popular ] [ Latest ]
[ Filter ] [ Filter ] ...
```

---

# 39. EXPLICITLY REJECTED FINAL LAYOUT

Do NOT leave Feed as:

```text
Feed

[ Source ▼ ]

[ All ] [ Popular ] [ Latest ]

[ Filter ] [ Filter ] [ Filter ]

Source Name
Popular Listing

Manga content...
```

This is explicitly rejected because:

1. `All` is unnecessary.
2. Source/listing information is duplicated.
3. Controls consume excessive vertical space.
4. The previous stacked implementation reduced manga visibility.
5. The source and listing are already communicated by the controls.
6. The header should collapse during scrolling.

---

# 40. DEFINITION OF DONE

This task is DONE only when all of the following are true:

## Feed

- [ ] `All` removed from Feed listing UI.
- [ ] `All` removed from Feed Settings → Default listing UI.
- [ ] Popular remains.
- [ ] Latest remains.
- [ ] Popular/Latest are beside the source selector in the preferred compact layout.
- [ ] Source selector retains stable width/ellipsis behavior.
- [ ] Duplicate source/listing title removed.
- [ ] Header spacing tightened.
- [ ] Genre filter row preserved.
- [ ] Header minimizes while scrolling down.
- [ ] Header restores while scrolling up.
- [ ] No nested-scroll regression.
- [ ] No clipping/overlap.
- [ ] Narrow-screen behavior verified.
- [ ] Actual displayed data matches selected listing.

## Feed functionality

- [ ] Source selection works.
- [ ] Popular works.
- [ ] Latest works.
- [ ] Genre filters work.
- [ ] Persistence works.
- [ ] Paging works.
- [ ] Load-more works.
- [ ] Retry works.
- [ ] Manage Feeds works.

## Q9

- [ ] Accessibility changes preserved.
- [ ] Existing a11y tests pass.
- [ ] Large-font behavior remains valid.

## Q2

- [ ] Existing uncommitted Q2 work preserved.
- [ ] Browse genre-filter regression passes.

## Engineering

- [ ] `spotlessApply` passes.
- [ ] `spotlessCheck` passes.
- [ ] `testDebugUnitTest` passes.
- [ ] `verifySqlDelightMigration` passes.
- [ ] `assembleDebug` passes.
- [ ] Device install succeeds.
- [ ] Device matrix passes.
- [ ] OCR/TTS timing evidence captured.
- [ ] Documentation updated.
- [ ] No commits/tags/releases created.

---

# 41. OPERATING PRINCIPLE

The goal is NOT to make the most changes.

The goal is to leave Yomitsu with the **smallest correct implementation** that:

```text
preserves existing architecture
        +
fixes the Feed UI regression
        +
finishes authorized verification
        +
does not disturb protected systems
        +
leaves clear evidence
```

If an existing implementation can be reused, reuse it.

If a behavior already works, do not rewrite it.

If a problem cannot be reproduced or proven, document it rather than inventing a fix.

**Audit → smallest change → test → build → device verify → document → stop.**