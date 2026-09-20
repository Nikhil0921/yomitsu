# Yomitsu — State File (Startup Memory)

> COMPACT STARTUP STATE. Read FIRST. Answers: "Where is Yomitsu right now?"
> Deep context: rules.md (protocol) · memory.md (recent+durable) ·
> implementation-roadmap.md (authorization) · history/session-logs.md (archive, on demand only).

---

## Current state

```text
Project:        Yomitsu — Android manga/comic reader, OCR + Read-Aloud TTS, dictionary/language tooling
Version:        0.5.4.2 / versionCode 32 (app/build.gradle.kts single source of truth)
Latest release: v0.5.4.2 (tag 20f4eb746, published 2026-09-20, GitHub Latest, 5 ABI APKs)
Branch/HEAD:    main @ 17773e92c — next-chapter image prefetch pipeline COMMITTED locally
                (not pushed); 8 src/doc files + 2 new + Phase-1 audit doc + session logs
Working tree:   clean
Current phase:  no active implementation phase (all stages 0–4P closed, 4P-impl done)
Last release:   2026-09-20 v0.5.4.2 released locally (docker -Xmx4g, both volumes):
                gates green 5m08s (spotless+test+verifySqlDelightMigration),
                assembleRelease 19m36s, APK cert SHA-256 verified = e486ea51...8968,
                5 ABI APKs pushed to GitHub (Nikhil0921/yomitsu)
Last gates:     2026-09-20 all green (see last release line)
Last device:    2026-09-19 STAGE 4L device PASS (SM_M066B wireless: ch8447 p0–15,
                 160×HTTP200, max 4 concurrent tile spans, TTS smoke OK).
                 Q8 Feed auto-pagination device PASS (user-verified 09-19).
```

## Current blockers

1. **Debug-keystore signature mismatch** blocks device pass: `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (09-15). CONFLICTING RECORDS (not reconciled — see memory.md issue #7): known-issue #7 says `yomihon-android-home` volume at `/home/vscode/.android` keeps the key stable; YOMUCHU batch 2 found it NOT mounted/regenerated. VERIFY the mount before any device session; never uninstall `app.yomihon.dev` without a current `.tachibk` backup (data loss). Note: 09-20 v0.5.4.2 release built + published with the stable keystore (SHA-256 e486ea51...8968 verified host + container + APK cert) — release path unaffected; device-session blocker remains.
2. Nothing else hard-blocked. No authorized implementation task. All stages 0–4P closed.

## Active technical gotchas

- Build in docker image `vsc-yomihon-*` (host has no SDK); ALWAYS `-Xmx4g`; mount BOTH volumes (`yomihon-gradle-home`, `yomihon-android-home`) — MUST mount to `/home/vscode/.gradle` + `/home/vscode/.android` (the `vscode` user's home is `/home/vscode`, NOT `/home/user`; mounting to `/home/user/...` breaks dep resolution, e.g. JitPack `com.github.arkon.FlexibleAdapter:flexible-adapter:c8013533` not found → build fails).
- ML models gitignored — download via CI step or CONTRIBUTING.md; OCR silently degrades without them.
- Device SM_M066B (Android 16, arm64) wireless: `./scripts/adb-wireless connect`; capture logcat ON-DEVICE to disk (no `--pid`, no `logcat -c`); device clock ≈ host +5h29m.
- Releases must be built+published LOCALLY (release.yml is fork-gated); root-owned build dirs → one-off `chown -R 1000:1000 /workspace` in root container.
- Strings: edit ONLY `i18n/src/commonMain/moko-resources/base/`; never locales (Weblate).
- Voyager tabs addressed by CLASS, not index; settings tab must stay LAST page (ColorFilter dim-hack index).

## Scope locks

```text
HARD HALTED:   Q3, Q4, Q5, Q6, Q7 (user, 2026-09-13) — do not inspect/implement.
REJECTED:      true backdrop blur (RenderEffect), nav-tab customization as 6th tab/IA
               change, standardized reselect, Browse Search tab, Library page-level
               Continue, screen-OCR from other apps, local OCR engine reinstatement,
               anime/novel/gamification features (identity).
DEFERRED:      Liquid Background (designed 09-13; needs translucent-container audit),
                tap-zone editor, dict history/favorites, Anki context capture,
                .mokuro, all Phase 10B heavy items.
COMPLETED/CLOSED (do not reopen): TTS Phases 1–9, Phase 10A, stabilization batches,
                UI audit Batches 1–5, Batch 6/7, artwork tray, Q1/Q2/Q9, v0.5.3/v0.5.4/
                v0.5.4.1 releases, 2026-09-13 adaptive-UI batches, YOMUCHU batch 2,
                OCR TTS stages 4K–4N (closed via prior sessions), 4O read-only audit,
                Q8 Feed auto-pagination (user-verified 09-19),
                Stage 4P OCR prefetch-timing optimization (integrated 09-19).
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
