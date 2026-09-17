# Yomitsu — State File (Startup Memory)

> COMPACT STARTUP STATE. Read FIRST. Answers: "Where is Yomitsu right now?"
> Deep context: rules.md (protocol) · memory.md (recent+durable) ·
> implementation-roadmap.md (authorization) · history/session-logs.md (archive, on demand only).

---

## Current state

```text
Project:        Yomitsu — Android manga/comic reader, OCR + Read-Aloud TTS, dictionary/language tooling
Version:        0.5.4.1 / versionCode 31 (app/build.gradle.kts single source of truth)
Latest release: v0.5.4.1 (tag 2af00b105, published 2026-09-13, GitHub Latest, 5 ABI APKs)
Branch/HEAD:    main @ 3dbf474ba — 3 commits AHEAD of origin/main, NOT pushed:
                3c5858e0e AnymeX UI micro-batch · 45e9b7c55 Frosted theme +
                immersive + adaptive-UI batches · 3dbf474ba YOMUCHU batch 2
                (Recent collapsible manga groups + gradient hairline fix + Recent pill scroll)
Working tree:   Feed auto-pagination + All Sources source headers (uncommitted)
                + previous: UpdatesUiItem.kt compact group card + single-chapter
                card unification + docs restructure
Current phase:  no active implementation phase
Authorized task: Feed auto-pagination + All Sources compact source headers
                 (Q8 from roadmap, user-authorized)
Last gates:     2026-09-16 all green (spotlessCheck 35s + testDebugUnitTest
                 3m45s + :app:assembleDebug 4m4s; FeedScreenModelStateTest 9/9).
Last device:    2026-09-15 MORNING batch DEVICE VERIFIED (SM_M066B wireless: gradient
                 live-apply, nav alpha, scroll-behind — pixel-proven).
                 Feed auto-pagination DEVICE PASS PENDING.
```

## Current blockers

1. **Debug-keystore signature mismatch** blocks device pass: `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (09-15). CONFLICTING RECORDS (not reconciled — see memory.md issue #7): known-issue #7 says `yomihon-android-home` volume at `/home/vscode/.android` keeps the key stable; YOMUCHU batch 2 found it NOT mounted/regenerated. VERIFY the mount before any device session; never uninstall `app.yomihon.dev` without a current `.tachibk` backup (data loss).
2. Nothing else hard-blocked. Roadmap has NO current authorized task.

## Active technical gotchas

- Build in docker image `vsc-yomihon-*` (host has no SDK); ALWAYS `-Xmx4g`; mount BOTH volumes (`yomihon-gradle-home`, `yomihon-android-home`).
- ML models gitignored — download via CI step or CONTRIBUTING.md; OCR silently degrades without them.
- Device SM_M066B (Android 16, arm64) wireless: `./scripts/adb-wireless connect`; capture logcat ON-DEVICE to disk (no `--pid`, no `logcat -c`); device clock ≈ host +5h29m.
- Releases must be built+published LOCALLY (release.yml is fork-gated); root-owned build dirs → one-off `chown -R 1000:1000 /workspace` in root container.
- Strings: edit ONLY `i18n/src/commonMain/moko-resources/base/`; never locales (Weblate).
- Voyager tabs addressed by CLASS, not index; settings tab must stay LAST page (ColorFilter dim-hack index).

## Scope locks

```text
HARD HALTED:   Q3, Q4, Q5, Q6, Q7, Q8 (user, 2026-09-13) — do not inspect/implement.
REJECTED:      true backdrop blur (RenderEffect), nav-tab customization as 6th tab/IA
               change, standardized reselect, Browse Search tab, Library page-level
               Continue, screen-OCR from other apps, local OCR engine reinstatement,
               anime/novel/gamification features (identity).
DEFERRED:      Liquid Background (designed 09-13; needs translucent-container audit),
               tap-zone editor, dict history/favorites, Anki context capture,
               .mokuro, Feed auto-pagination (Q8 — halted), all Phase 10B heavy items.
COMPLETED/CLOSED (do not reopen): TTS Phases 1–9, Phase 10A, stabilization batches,
               UI audit Batches 1–5, Batch 6/7, artwork tray, Q1/Q2/Q9, v0.5.3/v0.5.4/
               v0.5.4.1 releases, 2026-09-13 adaptive-UI batches, YOMUCHU batch 2.
Unlocked ONLY for the 09-13/09-15 batches (per user master prompt): nav ORDER +
               background gradient + translucency. Reorder ≠ IA change; gradient ≠ Liquid.
```

## Deeper context — which doc to consult

| Need | Read |
|---|---|
| What may I implement? | `implementation-roadmap.md` (CANONICAL authorization) |
| Engineering rules + doc protocol | `rules.md` |
| Recent sessions, durable decisions, known issues | `memory.md` |
| UI/visual work | `design.md`, then `ui-implementation-map.md` |
| Phase history | `phase.md` |
| Historical evidence / prior session detail | `history/session-logs.md` (ONLY when required) |
| HOW (architecture) | `architecture.md` |
| WHAT (product) | `prd.md` |
| Branding | `branding.md` |

**Never bulk-load the docs tree. Startup order: state.md → rules.md → memory.md → implementation-roadmap.md.**
