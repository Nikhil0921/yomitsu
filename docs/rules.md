# Yomitsu — Engineering Rules (AI Rulebook)

> AI agents MUST read this file (and `docs/state.md`) before modifying code.
> Documentation/state-management protocol: **§12** (startup order, precedence, updates, archival).
> These rules are derived from the actual repository conventions, not generic style.
> Where a rule conflicts with observed code, the repository wins — flag it in
> `docs/memory.md` instead of "fixing" it silently.

---

## 1. General rules

1. **Inspect before modifying.** Read the target file and its callers before editing.
2. **Reuse before creating.** If an existing class/interactor/utility provides the
   behavior, use it (`docs/architecture.md` §7 lists TTS-relevant reuse).
3. **Extend before replacing.** Add overloads/fields/new sealed variants; do not
   rewrite working systems.
4. **Preserve existing behavior.** Reader, OCR, dictionary, download behavior is
   production behavior; regressions are unacceptable.
5. **Minimize changes.** Smallest change that satisfies the current phase
   (`docs/phase.md`). No drive-by refactors, no reformatting of untouched lines.
6. **Follow the phase plan.** Do not jump ahead or mix phases in one change set.
7. **Update `docs/state.md` after meaningful work** (immediate current state). Trivial formatting-only changes don't require an update.

## 2. Architecture rules

- Respect the layering: UI (:app presentation) → ViewModel/ScreenModel →
  Interactor (:domain) → Repository interface (:domain) → Impl (:data/:app) →
  sources/network. Never let UI call repositories directly when an interactor exists.
- New decision logic that can be pure belongs in **`:domain`** as pure functions
  (precedents: `ChapterRecognition`, `SentenceParser`, planned
  `SentenceSegmenter`/`TtsAdvancePolicy`). Android/framework code stays in `:app`
  or `:data`.
- No circular module dependencies. Direction is fixed:
  `:app → :data → :domain → :source-api`; presentation modules depend on
  `:i18n`/`:core:common` only.
- Interfaces at boundaries where multiple implementations exist or are planned
  (e.g., `TtsEngine`, `OcrRepository`, `DictionaryAudioPlayer` precedent).
- Keep platform-specific code isolated (expect/actual in KMP modules;
  framework classes only in `:app`/`:data` androidMain/source sets).
- Avoid duplicate implementations of OCR, reader navigation, preference access,
  or engine serialization — they exist and are battle-tested.

## 3. Kotlin rules (project-specific)

- **Language**: Kotlin 2.4, JVM toolchain 17 (CI runs JDK 21). Desugaring enabled.
- **Null safety**: no `!!` outside tests; prefer early returns and `?.let`;
  repository APIs return nullables for "not found" rather than exceptions.
- **Coroutines**: suspend functions for async work; never `GlobalScope`;
  use `viewModelScope`/screen-model scope; bridge callback APIs with
  `CompletableDeferred`/`suspendCancellableCoroutine` (existing precedents:
  `PrioritizedTaskQueue.submit`, OkHttp `await` extensions).
- **Flow/StateFlow**: single `MutableStateFlow<State>` per screen model updated
  via `.update { copy(...) }`; expose immutable state; one-shot events via
  `Channel<Event>.receiveAsFlow()`. Collect with lifecycle-aware helpers.
- **Sealed classes/interfaces** for finite states/events (`ReaderViewModel.Event`,
  `Dialog`, `OcrException` hierarchy). Prefer `sealed interface` + `data class`
  /`data object` members.
- **Data classes** for models/state; `@Immutable` Compose annotations on UI state.
- **Extension functions** live next to their subject or in a `util` package;
  don't create god-file util dumps.
- **Naming**: classes PascalCase; composables PascalCase verbs/nouns
  (`OcrLoadingIndicator`); functions/properties camelCase; constants SCREAMING_SNAKE.
  Package names follow existing namespaces — NEVER invent `app.yomihon.*` packages
  (code lives under `eu.kanade.tachiyomi.*`, `mihon.*`, `tachiyomi.*`).
- **Visibility**: default to `internal`/`private` where possible; public API only
  across module boundaries.
- **Immutable state**: UI state data classes must be immutable; mutable holders
  stay inside ViewModels/screen models.
- **No comments unless required** by repo style; code should be self-explanatory.
  ktlint (spotless) is the gate — run `./gradlew spotlessApply` if it fails.

## 4. Compose/UI rules

- Composables are stateless where practical; hoist state to screen models.
- Shared components come from `presentation-core`
  (`tachiyomi.presentation.core.components.*`: `SettingsItems`, `AdaptiveSheet`,
  `Pill`, `ActionButton`, …) — check there before writing a new component.
- Strings ALWAYS via moko-resources (`tachiyomi.i18n.MR.strings.*` +
  `stringResource` helper from presentation-core); base `strings.xml` only,
  snake_case keys. Never hard-code user-visible strings.
- Icons: `androidx.compose.material.icons` extended set (e.g.,
  `Icons.Outlined.DocumentScanner` used by the OCR button). Match outlined/filled
  usage of neighboring code.
- Side effects through the established event channels, not ad-hoc callbacks into
  activities.
- Navigation: Voyager screens for tabs/screens; explicit Activities only where
  upstream uses them (ReaderActivity). Don't introduce a new navigation library.
- Accessibility: ≥48 dp touch targets, `contentDescription` on icon buttons,
  non-color indicators for state (see design.md §Accessibility).
- Theming: always via `MaterialTheme.colorScheme`/`typography` tokens
  (`MaterialExpressiveTheme`); never literal colors in feature composables.
- Reader overlays follow the `ContentOverlay` composition pattern in
  ReaderActivity (bottom pills like `OcrLoadingIndicator`: AnimatedVisibility
  slide-up, `Alignment.BottomCenter`, `surfaceContainer` background).

## 5. Dependency rules

Before adding ANY library:

1. Does the repository already provide equivalent functionality?
2. Does the Android SDK / Compose provide it?
3. Is it already in `gradle/libs.versions.toml` or `gradle/mihon.versions.toml`?
4. Compatible with minSdk 26 / targetSdk 36 / Kotlin 2.4?
5. Actively maintained?
6. License compatible with Apache-2.0?
7. Only then add — to the correct catalog, with justification recorded in
   `docs/memory.md` §Dependencies.

Current standing decisions: **TTS v1 adds zero dependencies** (framework
`android.speech.tts` + `android.media`). No Robolectric, no DI framework changes,
no new image/audio libraries.

## 6. TTS rules

- All speech goes through the `TtsEngine` interface (`:domain`);
  `AndroidTtsEngine` (`:app`) is the ONLY place that touches `TextToSpeech`.
- Never couple reader UI/composables directly to `TextToSpeech` or audio focus.
- Never duplicate OCR or reader logic — call interactors/activity APIs.
- Lifecycle-safe: pause on Activity onStop; shutdown in finish/onCleared paths;
  no callbacks after teardown; failures must never crash the reader.
- Queues bounded: one page's sentence list at a time; prefetch bounded to at
  most 3 pages ahead, depth speed-aware (1 at <1.5x, 2 at 1.5-2.5x, 3 at
  ≥2.5x), one OCR scan in flight at a time.
- No audio caching in v1 (system TTS latency is tens of ms).
- Future engines must be addable without modifying reader logic
  (interface + Injekt binding swap).
- Segmentation/ordering rules are fixed (see prd.md F2): region order is truth;
  never merge across regions; CJK terminal punct `。！？!?‼⁇⁉⁈` always; ASCII
  `.` terminal ONLY as a single dot before whitespace/EOL (dot-runs `...` and
  decimals are never terminal — English rules added 2026-08-25).

## 7. Error handling

- User-facing errors: localized string + toast/snackbar via existing patterns
  (reader: `Event.*` → toast in activity).
- Recoverable errors: map to state (e.g., TTS `Error` phase with retry), never
  crash; utterance errors retry once then pause.
- Logging: use `logcat` library (`io.github.microutils`-style `Logcat` already a
  dependency); log at DEBUG for diagnostics; NEVER log page text content,
  credentials, or personal data.
- Exceptions: domain exceptions hierarchies exist (`OcrException`,
  `DictionaryImportException`) — extend them rather than throwing raw
  `IllegalStateException` from domain code.
- Network failures: rely on existing source/network error handling; OCR network
  errors map to `OcrException.ConnectionError`.
- OCR failures: existing `Event.Ocr*` mapping — reuse for TTS scan-on-demand.
- TTS failures: defined phases/errors only (init failed, no voice, utterance
  error) — see prd.md F7.
- Invalid state: fail fast in debug, degrade gracefully in release.
- Lifecycle cancellation: treat `CancellationException` as control flow — never
  swallow it; clean up resources in `finally`.
- Never silently swallow important failures (empty catch blocks are forbidden).

## 8. Security & privacy

- Never hard-code API keys/secrets; none exist today — keep it that way.
- Local data stays local: OCR/dictionary/TTS content must not be transmitted
  anywhere except engines the user explicitly configured (GLENS/OWOCR are
  user-selected OCR models; system TTS is on-device).
- Network security: use the shared `NetworkHelper` client (interceptors, DoH);
  don't create bare `OkHttpClient`s.
- Permissions: add NONE without explicit approval (TTS v1 needs none).
- Third-party services: any new outbound service requires PRD-level justification.
- Logs must not contain sensitive user content (searched terms, spoken text).

## 9. Git rules

- Inspect `git status` first; NEVER destroy uncommitted user work.
- No force push/reset, no `git checkout .`/`git clean` without explicit approval.
- Focused diffs: one logical change per commit if asked to commit; never mix
  feature + refactor + formatting.
- Untracked planning files at root (`AGENTS.md`, `architect.md`,
  `architect-2.md`) belong to the user — do not delete or rewrite them.
- Do not commit unless explicitly asked.

## 10. AI boundaries (hard limits)

AI agents MUST NOT, without explicit human authorization recorded in the session:

- Rewrite the application or a subsystem wholesale.
- Delete working systems (extensions, OCR, reader features, dictionary, trackers).
- Replace architecture merely because another looks cleaner.
- Upgrade Kotlin/AGP/major dependencies without written justification.
- Change database schemas casually (main DB requires `.sqm` migration +
  `verifySqlDelightMigration`; OCR cache DB requires understanding its
  delete-if-outdated scheme).
- Remove or weaken existing tests to make suites pass.
- Expose secrets; install arbitrary dependencies; modify unrelated files.
- Create duplicate implementations of existing behavior.
- Claim tests/builds passed without actually running them.
- Fabricate repository facts — verify everything against source.
- Hand-edit locale files other than `i18n/src/commonMain/moko-resources/base/`
  (Weblate owns translations).
- Touch gitignored ML model assets beyond documented CI download steps.

**Before large architectural changes**: STOP, explain why the existing
architecture is insufficient, propose the minimal alternative, and wait for
approval. Record the outcome in `docs/memory.md` (Architecture decisions /
Rejected approaches).

## 11. Build & verification commands

```bash
./gradlew spotlessCheck              # ktlint gate (fix: spotlessApply)
./gradlew testDebugUnitTest          # unit tests (build types, NOT flavors)
./gradlew :domain:testDebugUnitTest --tests "SomeClass"   # single test
./gradlew verifySqlDelightMigration  # REQUIRED after main-DB schema changes
./gradlew :app:assembleDebug         # fast compile check
./gradlew assembleRelease -Pinclude-telemetry -Penable-updater  # CI parity
```

Never report success without running the relevant command and pasting/recording
the result in `docs/memory.md`.

## 12. Documentation & state management protocol

### File precedence

When information overlaps:

```text
implementation-roadmap.md
    = execution authorization

state.md
    = current repository state

rules.md
    = engineering/documentation rules

memory.md
    = active recent memory + durable knowledge

design.md
    = visual/design authority

ui-implementation-map.md
    = detailed UI implementation reference

phase.md
    = phase history/status

history/session-logs.md
    = historical evidence/archive
```

Do NOT describe historical files as execution authority.

### Startup protocol

Agents MUST read in this order:

1. `docs/state.md`
2. `docs/rules.md`
3. `docs/memory.md`
4. `docs/implementation-roadmap.md`

Only read additional documents when the current task requires them:

- **UI task** → `design.md` / `ui-implementation-map.md` as triggered
- **Historical investigation** → `history/session-logs.md`
- **Phase-history question** → `phase.md`
- **Architecture question** → `architecture.md` / relevant technical docs

Never automatically load the entire documentation tree.

### Update protocol

After meaningful implementation work:

1. Update `state.md` with new current state.
2. Update `memory.md` with important recent-session delta (decisions/gotchas only).
3. Append detailed session information to `history/session-logs.md`.
4. Update `implementation-roadmap.md` only when roadmap state/authorization genuinely changes.
5. Update `phase.md` only when phase status genuinely changes.
6. Do NOT duplicate the complete session history across all files.

### Archival rule

When `memory.md` becomes large:

- Preserve durable decisions/gotchas.
- Keep the most recent 1–2 meaningful sessions.
- Move older detailed session records to `history/session-logs.md`.
- Never destroy useful historical evidence merely for token reduction.

### Scope rule

Documentation compression must NEVER become an excuse to:

- Reopen completed phases
- Inspect/implement Q3–Q8 while they are HARD HALTED
- Select work independently
- Change product architecture
- Modify source code
- Alter design decisions
- Rewrite the canonical roadmap
