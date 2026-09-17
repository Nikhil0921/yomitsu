# AGENTS.md

## What this is

Yomihon — Android manga reader (Kotlin, Jetpack Compose, Gradle multi-module). Community fork of [Mihon](https://github.com/mihonapp/mihon) (Tachiyomi lineage).

**Package names do not match the branding.** Code lives under `eu.kanade.tachiyomi.*` (upstream code), `mihon.*` (newer features), and `tachiyomi.*` (library modules); only `applicationId` is `app.yomihon`. Follow each module's existing namespace — never invent `app.yomihon.*` packages.

## Session protocol — read this first

Startup sequence (default — never bulk-load the docs tree):

1. `docs/state.md` — compact current state: version/HEAD, tree, authorized task, blockers, gotchas, scope locks.
2. `docs/rules.md` — engineering rulebook + documentation/state-management protocol (§12).
3. `docs/memory.md` — compressed active memory: recent sessions + durable decisions/gotchas.
4. `docs/implementation-roadmap.md` — CANONICAL execution authority: execute only the current authorized task.

Read other docs only when the task requires them: UI → `design.md` / `ui-implementation-map.md`; phase history → `phase.md`; historical evidence → `docs/history/session-logs.md` (append-only archive; NEVER read at normal startup); architecture → `architecture.md`.

After meaningful work: update `state.md` (current state) + `memory.md` (recent delta + durable knowledge) + `history/session-logs.md` (detailed record). Roadmap/phase only on genuine state change. Never duplicate full session history across files.

- Root `AGENTS.md`, `architect.md`, `architect-2.md` belong to the user — don't delete or rewrite them.

## Commands

CI (`.github/workflows/build.yml`) is the source of truth and runs in this order:

```bash
./gradlew spotlessCheck              # ktlint gate; fix with ./gradlew spotlessApply
./gradlew testDebugUnitTest          # unit tests
./gradlew verifySqlDelightMigration  # required after any DB schema change
./gradlew assembleRelease -Pinclude-telemetry -Penable-updater
```

- Single test: `./gradlew :app:testDebugUnitTest --tests "SomeClass.method"` (swap module as needed).
- Use `testDebugUnitTest` (not `test`): these are **build types** (`debug`, `release`, `foss`, `preview`, `benchmark`), not product flavors.
- Root `spotlessApply`/`spotlessCheck`/`clean` also delegate into the included build `gradle/build-logic`.
- Never claim success without running the command — record results in `docs/memory.md`.

## Build environment (this machine)

- Host has no Android SDK (`/opt/android-sdk` absent). Run Gradle inside the local devcontainer image:
  ```bash
  docker run --rm -u vscode -v "$PWD":/workspace -w /workspace <vsc-yomihon-image> \
    bash -c 'GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx4g" ./gradlew spotlessCheck :app:compileDebugKotlin'
  ```
  Get the exact image tag via `docker images | grep yomihon`. The `.devcontainer/` files were removed from the repo but the built image persists locally.
- Repo-default `-Xmx2560m` OOMs during packaging in the 7.4 GiB container — always pass `-Xmx4g`. CI unaffected.
- JDK: CI pins 21 (`.github/.java-version`); Gradle toolchain compiles with 17. Both fine for local gates.

## Setup gotchas

- **ML models are gitignored and absent from a fresh clone**; OCR / panel-detection silently breaks without them:
  - `app/src/main/assets/ocr/{encoder.tflite,decoder.tflite,embeddings.bin}` and `app/src/main/assets/ocr_fast/{encoder,decoder}.tflite`
  - `data/src/main/assets/panel_detector/model.tflite`
  - Pinned URLs + sha256: "Download ML models" step in `.github/workflows/build.yml`; manual steps in CONTRIBUTING.md.

## Architecture

- Convention plugins and the `mihonx` version catalog live in included build `gradle/build-logic`; dependency versions in `gradle/libs.versions.toml`. Typesafe project accessors are enabled (`projects.data`).
- Modules: `:app` (Compose UI/features; entrypoints `App.kt`, `MainActivity` under `app/src/main/java/eu/kanade/tachiyomi/`), `:domain` (repositories/interactors), `:data` (implementations + SQLDelight), `:source-api`/`:source-local` (catalogue sources), `:core:common`, `:core:archive`, `:core-metadata`, `:i18n`, `:presentation-core`, `:presentation-widget`, `:telemetry`, `:baseline-profile`.
- DI is **Injekt**, not Hilt/Koin/Dagger.
- Database: two SQLDelight schemas in `:data` — main `Database` (`.sq` files + numbered `.sqm` migrations under `data/src/main/sqldelight/tachiyomi/`) and `OcrCacheDatabase` (`sqldelight-ocr/`). Schema changes need a new `.sqm` migration, then run `verifySqlDelightMigration`.
- Strings: moko-resources in `:i18n`. Edit only `src/commonMain/moko-resources/base/`; other locales come from Weblate — never hand-edit them.
- `-Pinclude-telemetry` opts into Firebase (google-services/crashlytics); without it telemetry is compiled out. `-Penable-updater` enables the update checker.
- Reader code splits across lookalike packages: UI pages/composables in `eu.kanade.presentation.reader.settings`, screen models/preferences in `eu.kanade.tachiyomi.ui.reader.setting` — cross-references need explicit imports.

## Git

- No force-push or history rewrite without explicit user approval. Uncommitted user work is untouchable.
- Never commit heap dumps / large binaries (`*.hprof` is gitignored for a reason).

## Misc

- Wireless ADB helper: `./scripts/adb-wireless pair|connect|devices`.
