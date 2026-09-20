# TTS / OCR speech pipeline — read-only forensic audit (2026-09-17)

Scope: `AndroidTtsEngine`, domain `TtsEngine`, `SentenceSegmenter`, `SpeechPipeline` (classifier/cleaner/filter/dedup), exclusion matcher, `GetOcrExclusionZones` repo SQL, tests. Parent owns `TtsPlaybackController` (per user) — controller audited only at the convergence seam.
Method: codegraph explore (3 calls) + narrow reads. **No build, no test run, no device** — read-only session. Every claim below is code-verified (source shown/cited) unless marked RUNTIME.

Full report below.

## 0. Verdict summary

| Area | State | Confidence |
|---|---|---|
| Pipeline order (exclusions → dedup → filter/classify → clean → segment) | Correct, single convergence point | code-verified |
| Exclusion matcher (scope/enabled/combined/multi-zone) | Correct; 26 pinning tests incl. edge cases | code-verified |
| SQL `zonesForSpeech` over-fetch (broad OR, `enabled=1` filter, chapter-id null trick) | Functionally correct; intentional over-fetch, matcher narrows | code-verified |
| AndroidTtsEngine lifecycle (init/voice/rate/focus/cancel/ownership) | Sound core; 4 real gaps (below) | code-verified, no runtime |
| `resolveVoiceSelection` | Correct fallback chain; `availableEnginePackages` check is dead code | code-verified |
| Segmentation | Correct incl. ellipsis/decimal/CJK; `!` double-split pinned | code-verified |
| Test coverage | Pure-Kotlin layers well covered; **engine layer 0 tests**; repo impl 0 tests | code-verified |

## 1. Architecture map (who owns what)

- Engine seam: `domain/src/main/java/mihon/domain/tts/engine/TtsEngine.kt:3-56` — interface only; contracts documented: `initialize()` returns false on failure (L15), `speak()` suspends until done/fail/stop, returns false when not completed (L21), `stop()` makes pending `speak()` return immediately (L32), focus listener must report events only, playback reactions stay with caller (L5-9).
- Impl: `app/src/main/java/eu/kanade/tachiyomi/data/tts/AndroidTtsEngine.kt` (singleton, `DomainModule.kt:293` `addSingletonFactory<TtsEngine>`).
- Convergence: `TtsPlaybackController.acquireSentences` `app/src/main/java/eu/kanade/tachiyomi/ui/reader/tts/TtsPlaybackController.kt:500-583` — cached `getCachedPageOcr.await` (L509) ?: `scanOnDemand` (L519) → exclusion zones `awaitForSpeech(manga, source, chapter)` (L520-531) → `applyExclusions` (L532-540) → `dedupeOverlappingDuplicates` (L559) → `SpeechPipeline.toSpeakableSentences` (L565-570). All paths converge here; not re-audited per user instruction.
- Pipeline body: `domain/src/main/java/mihon/domain/tts/speech/SpeechPipeline.kt:19-34`.

## 2. AndroidTtsEngine — exact costs and gaps

File: `app/src/main/java/eu/kanade/tachiyomi/data/tts/AndroidTtsEngine.kt`.

### 2.1 init/voice

- `initialize()` (L54-114) guarded by `Mutex` (L32). Fast path: existing instance → re-applies voice config every call (L56-58). Note: **`applyVoiceConfig` runs on EVERY `initialize()`** — and `initialize()` is called on every `resume()` (controller L206), every rebuild (L327, L717), and `ensureInitialized()` (L491). Voice re-resolution is cheap (in-memory lists) but `engine.setVoice`/`setLanguage` during active playback can audibly reset prosody on some engines. Cost, not bug.
- Creation (L63-97): `TextToSpeech` on Main; init listener completes `readiness` (L64-66); `UtteranceProgressListener` routes `onDone`→success, both `onError`s + `onStop`→false (L76-91); rate/pitch replayed on new instance (L94-95).
- Failure/cancel paths close: non-success → `engine.shutdown()` + return false (L105-108); cancellation → shutdown + rethrow (L101-103). No leaked half-initialized instance.
- `applyVoiceConfig` (L211-267): reads prefs fresh each time (L212-213), builds availability sets, resolves via `resolveVoiceSelection`, applies with logged fallbacks at all three tiers (L230-265). Solid.
- `resolveVoiceSelection` (`TtsVoicePreferences.kt:57-72`): chain voice→language→system default. **Dead input**: `availableEnginePackages` (L61, L67-68) is never consulted in the `when` — `voiceValid`/`languageValid` don't check engine membership. Callers test-verify it (`TtsVoicePreferencesTest.kt:64-73` "uninstalled engine... falls back") but only because empty voice/language forces SystemDefault anyway — engine mismatch with a *valid* voice name (e.g. profile pinned to engine A voice X, engine B installed and selected, voice X coincidentally also exists in B) selects Voice under engine B. Realistic via voice profiles (`ReadAloudSettingsScreenModel.kt:152-162` applies profile values without validating against live engine lists; `load()` re-validates only after). Minor, but the parameter + test give false confidence.

### 2.2 rate/pitch

- `setSpeechRate`/`setPitch` (L143-151): field + apply-if-live. Controller pipes pref `changes()` → engine (controller L163-164). `AndroidPreference.changes()` (`AndroidPreference.kt:60-66`) emits initial value ("ignition" `onStart`), so **persisted rate reaches a newly created engine even if prefs never change** — but there's a race: controller init collects `changes()` immediately, engine instance may not exist yet (L145 guards null `tts.get()`); the value is stored in fields `speechRate`/`pitch` (L40-42) and replayed at creation (L94-95). Correct.
- Gap R-1: rate change mid-utterance is applied to the *engine* but the current utterance completes at old rate (Android semantics); controller's `prefetchDepth()` reads rate discretely (controller L630-637) — consistent, no bug found.
- Gap R-2 (real): `setSpeechRate`/`setPitch`/`setEnginePackage`/`acquireFocus`/`abandonFocus`/`stop` are called from the controller's non-main scope threads and from preference flow collection — none synchronized with `initialize()`'s mutex. `tts.get()` is atomic so no crash, but `setSpeechRate` between `tts.getAndSet(null)` (shutdown L204) and next creation is benign; **`shutdown()` racing `speak()`** is the interesting one: `speak()` captured `engine` (L117), then `shutdown()` nulls the ref and calls `engine.shutdown()` (L203-208) → the in-flight `engine.speak(...)` on Main (L123-125) runs against a shutdown instance. Android `TextToSpeech.speak` after `shutdown` returns error or is a no-op; `accepted != SUCCESS` → return false (L126) before touching `completion`. Acceptable degradation; worth a comment, not a lock.

### 2.3 speak / cancellation / callback ownership

- `speak()` (L116-141): registers `CompletableDeferred` under `synchronized(pendingUtterances)` (L120), `QUEUE_FLUSH` on Main (L124), awaits completion (L129). `finally` on the await: `if (!completion.isCompleted) stop()` (L130-132) — this is the **cancellation bridge**: controller cancels playbackJob → `completion.await()` throws CancellationException → finally calls `stop()` → `failPendingUtterances()` + `engine.stop()` (L197-201) → system-side utterance killed. Correct and leak-free.
- Entry-removal identity guard (L133-139): removes only its own map entry (`===`), comment documents the relaunch-same-id race. Good; `dispatchCounter` (controller L151) makes ids unique per relaunch anyway, belt-and-braces.
- Ownership: engine never speaks on its own; all dispatch from controller. Focus listener is the ONLY engine→caller channel (L52, L293-300), mapped GAIN→Regained, LOSS→PermanentLoss, else TransientLoss (L293-300).

### 2.4 focus

- `acquireFocus` (L186-190): builds `AudioFocusRequest` once (L285-301), `AUDIOFOCUS_GAIN`, USAGE_MEDIA/CONTENT_TYPE_SPEECH, listener invokes `onFocusEvent` on the **calling (focus-callback) thread**.
- Controller reaction (L154-162): loss → `pause()`; Regained → **no-op, resume stays manual** (comment L160). Deliberate policy.
- Leak fix documented at `ReaderViewModel.kt:347-357`: `onFocusEvent = null` on `onCleared()` — native GC root through TTS service retained the dead controller (LeakCanary 2026-08-28). Ownership chain: engine is singleton, controller is per-ViewModel; detach is controller-init re-register + VM-clear null-out. Sound.
- Gap F-1: `abandonFocus()` (L192-195) early-returns when `audioFocusRequest == null` — but `acquireFocus` also early-returns when `audioManager()` null-casts fails (L187 `?: return`). No state tracking: **repeated `acquireFocus()` calls re-request focus** (Android dedups same request; fine) and `abandonFocus` without acquire is a no-op (fine). No bug; noting asymmetry.
- Gap F-2: focus listener invoked from a system binder thread → `pause()` mutates `@Volatile paused` + StateFlow (thread-safe) but also `engine.stop()` (fine) — however `pause()` is also called from the focus listener while `runPlayback` is mid-`engine.speak()` await; the interlock relies on `speak()` returning false after `stop()` → controller sees `!spoke` + `paused` → returns (controller L401-408). Verified consistent.

### 2.5 engine gaps — consolidated

1. **G-1 voice-selection engine check is dead** (above, §2.1). One-line fix: in `resolveVoiceSelection`, require `selectedEnginePackage in availableEnginePackages` before Voice/Language branches — but callers pass the *current* engine's packages while selection is interpreted against that engine; actual fix is either drop the param or validate profile apply in `ReadAloudSettingsScreenModel.applyVoiceProfile`. Ponytail: drop the unused param from the signature + test; fallback behavior unchanged.
2. **G-2 `getEngines()`/`getVoices()` return emptyList when uninitialized** (L160, L172) — `ReadAloudSettingsScreenModel.load()` calls `initialize()` first (L42), so UI path safe. Documented in interface (TtsEngine.kt L38-47). No action.
3. **G-3 no `getMaxSpeechInputLength` guard**: Android TTS silently truncates/fails utterances > `getMaxSpeechInputLength()` (commonly 4000 chars). Segmentation produces bounded sentences (OCR regions), worst case a giant run-on region without terminals stays whole (SentenceSegmenter L42). Realistic OCR region >4000 chars: essentially never. Not a bug; note only.
4. **G-4 `speak()` while another `speak()` in flight**: controller serializes via its single playbackJob; settings `preview()` (ReadAloudSettingsScreenModel L97-114) stops first. The `QUEUE_FLUSH` + per-id deferred map handles overlap; `stop()`-then-fail path covers losers. No bug.
5. **RUNTIME-unverified**: everything above is code-verified only; no device/engine behavior test was run (read-only session). Items needing device proof: prosody reset on re-`applyVoiceConfig` during playback; shutdown-vs-speak race on real engines.

## 3. SentenceSegmenter

File: `domain/src/main/java/mihon/domain/tts/SentenceSegmenter.kt`.

- `TtsSentence` carries `text`, `regionOrder`, `boundingBox`, `textOrientation` (L7-12); regions consumed in stored order, never re-sorted (L16-18) — matches tap-highlight behavior, pinned by test (`SentenceSegmenterTest.kt:119-122`).
- Region boundary = hard boundary (L19, L28-29 flatMap); no cross-region merge. Correct: bbox/orientation stay per-region.
- Splitting (L31-49): append char; on terminal → emit slice. Trailing remainder emitted if non-blank (L42). Blank slices dropped (L44-47).
- Terminal logic (L51-57): `。！？!?‼⁇⁉⁈` always terminal; `.` only when previous char isn't `.` (so `...` never splits) AND next is whitespace/null (so `3.14` never splits; `so... but` stays glued because next is space after 3rd dot? — no: `...` = dots at i, i-1 is dot → skip; last dot's next is space → would split, but L54 blocks it because `text[index-1] == '.'`. Ellipsis glue relies on this chain. Decimal: next char `1` not whitespace → no split. Verified by tests L53-79).
- Cost/gap S-1: **`!` after `。` double-splits** ("そうか。!" → ["そうか。", "!"]) — pinned as intended (test L138-144), and pipeline's punctuation-only filter drops the orphan `!` (SpeechPipeline L34; classifier+cleaner would otherwise never see it). Cost: one wasted TtsSentence allocation per occurrence. Fine.
- Gap S-2: **vertical Japanese text with ASCII `.` mid-word followed by space** — e.g. OCR noise "えっと ... そうだね" would split at "... " boundary mid-ellipsis? No: first dot blocked by prev-dot rule only for index>0; a space-separated single dot " ." splits (next=whitespace, prev=space). Harmless.
- Gap S-3: **no `…` single-char terminal** — `…` is NOT in TERMINALS (L26) and not `.`; "Wait… what?" stays one sentence. Intentional (cleaner converts `…`→", " before segmentation when ellipsisToPause on; if user disables ellipsisToPause, ellipsis never splits). Consistent with tests (`SpeechCleanerTest.kt:41-45`). Fine.

## 4. SpeechPipeline components

### 4.1 Order and contract

`SpeechPipeline.toSpeakableSentences` (SpeechPipeline.kt:19-34): dedup∘filter → classify-inside-filter → clean (null = drop region) → segment → drop punctuation-only sentences (L34). Cleanup-before-segmentation documented (L12-15): "WHAT?!?!?!" → "WHAT?!" survives; "I don't know..." → "I don't know, " then the trailing sentence still has letters → kept. Verified by `SpeechRegionFilterTest.kt:102-133`.
- Subtle: **dedup runs INSIDE the pipeline again** (L25) after the controller already deduped (controller L559). Double work per page (O(n²) each), but n = regions/page (~tens). Idempotent. Ponytail verdict: harmless; one of the two could go, but not worth the diff risk now.

### 4.2 Classifier (`SpeechRegionClassifier.kt`)

- Types DIALOGUE/NARRATION/SOUND_EFFECT/EXPRESSION/DECORATIVE/UNKNOWN (L11-18). Conservative-by-design doc (L30-33): unclear → DIALOGUE/NARRATION/UNKNOWN, never dropped unless user opts out.
- Blank/no-letters → DECORATIVE (L37, L40). CJK-dominant → DECORATIVE (L45-46) — deliberate: English pivot, CJK regions fall to filter's DECORATIVE gate; `SpeechRegionFilterConfig.speakDecorative` (default false) then drops them. Test-pinned (`SpeechRegionClassifierTest.kt:63-66`).
- Narration heuristic: wide-thin (w>0.55, h<0.12) + no terminal anywhere (L49-50). SFX: uppercase ≤8 letters + emphasis (L52-54). Expression: INTERJECTION regex `^[A-Z' ]{2,6}$` (L27, L55-56).
- Gap C-1: `INTERJECTION` matches digits? No — `[A-Z' ]` only, but `letters.uppercase() == letters` (L52) is checked on `letters` (filtered letters only) while regex matches full `text` — a region "AAAH 3" (letters AAAH, has digit) → regex fails on "3" → falls to DIALOGUE. Inconsistent input domains (letters for isUpper, raw text for regex) but outcome safe (spoken). Cosmetic.
- Gap C-2: `UNKNOWN` type is declared (L17) but **classifier never returns it** — dead enum value + dead `speakUnknown` config branch (filter L42). Deletion candidate; harmless.

### 4.3 Filter (`SpeechRegionFilter.kt`)

- Blank → drop (L33). Type gate per config (L36-44). Foreign-script gate (L45-50): dominant script of letters must equal `speechScript` (default LATIN) unless DECORATIVE. Default config drops SFX/EXPRESSION/DECORATIVE/CJK (test L27-37).
- Gap F-1: script gate runs `dominantScript` on `text.filter{isLetter}` (L46) — allocates per region; fine at page scale.
- Gap F-2: **OTHER script** (`dominantScript` returns OTHER for e.g. Cyrillic/Arabic — `SpeechRegionClassifier.kt:88-92`) is never equal to LATIN/CJK → always dropped when skipForeignScript on. Russian dialogue unspeakable even with speechScript=CJK. Product-pivot choice (LATIN v1, TtsPreferences.kt:41-42 comment); flagged as known ceiling, not a bug.

### 4.4 Cleaner (`SpeechCleaner.kt`)

- Order: whitespace collapse → ellipsis→", " → excessive-punct→2-char run → punctuation-only drop → garbage drop → blank→null (L37-48). Null-safe ordering documented (L33-36).
- `isOcrGarbage` (L56-61): len≥4 AND letters/digits < 40% of non-whitespace. Conservative; "WHAT?!?!?!" survives normalization first (test L48-53).
- Gap Cl-1: `ellipsisToPause` replaces with ", " — produces "I don't know, " with **trailing space before segmentation**; segmenter trims slices (L45) so clean. "Wait… what?" → "Wait,  what?" double space (test L36) — spoken as space, voices ignore. Cosmetic.
- Gap Cl-2: garbage threshold treats CJK poorly? `isLetterOrDigit` true for CJK → CJK text is 100% meaningful → never garbage. Fine.

### 4.5 Dedup (`SpeechPipeline.kt:41-61`)

- Later region dropped iff normalized text key equal AND strict AABB overlap (L41-55, L64-65). Key = trim + collapse-whitespace + lowercase (L57-60). Triple-dup keeps first (test `SpeechPipelineDedupTest.kt:77-87`). Disjoint boxes survive (L44-53). O(n²) with n = filtered regions; page-scale fine.
- Gap D-1: NFKC NOT applied to dedup key (only lowercase/whitespace) — full-width duplicate "ｋｅｙ" vs "key" both kept. Matcher normalizes (§5), dedup doesn't. Inconsistent but harmless (both speak).

## 5. Exclusion matcher (`OcrExclusionMatcher.kt`)

- `applyExclusions` (L36-46): empty zones → identity; active = enabled ∧ scope-match (L41); region kept iff NO active zone matches (L43-45). **Enabled + scope are double-gated here AND in SQL** (§6) — belt-and-braces, correct under both.
- Scope gating (`matchesRegionScope` L48-65):
  - ZONE: requires non-null `pageIndex` (L50) — legacy null-page rules dormant, pinned (test L288-297, L331-336). Scope widens *lookup*: PAGE/CHAPTER → `chapterId == context.chapterId` (L52) — note PAGE and CHAPTER behave IDENTICALLY here; the page-index narrowing for PAGE-scope comes from the zone's own `pageIndex` vs `context.pageIndex` in `matchesRegion` (L73-75). Test `chapter-scope zone stays anchored` (L299-311) proves page-index anchoring.
  - WORD/PHRASE: always in scope (L56) — global text rules, matches SQL fetch (§6).
  - COMBINED: PAGE requires chapterId ∧ pageIndex match (L58-60); CHAPTER/MANGA/SOURCE per ids (L61-64).
- Region matching (`matchesRegion` L67-81): ZONE = same pageIndex ∧ strict-overlap (L74-75; edge-touch survives, pinned L338-351; degenerate rect never matches L353-358). WORD = token-concat run equality (L90-99): "ion"≠"combination" (L81-85 test), separator variants match (L120-126), newline is separator (L228-236), NFKC full-width folds (L139-145). PHRASE = NFKC + strip-all-whitespace + lowercase substring, both directions + token-concat fallback (L133-143; tests L147-226 incl. cross-region non-match pinned L209-219). COMBINED = rect ∧ phrase (L78-80; tests L238-258).
- Multiple zones: any-match excludes (L43-45; tests L360-372 multi-zone-per-page, L381-389 mixed rule types). Disabled never excludes (L374-379).
- Cost: per region × active zones, O(R×Z) with tokenization recomputed per zone×region pair (`normalizedTokens` called inside `wordMatches`/`phraseMatches` every time — region text re-tokenized once per zone). Page-scale fine; Z large (dozens of rules) → still fine.
- Gap M-1: `phraseMatches` L142 falls back to `haystackTokens.joinToString("").contains(needleTokens.joinToString(""))` — this is substring-of-concatenation, so rule "on" + region "i o n" → tokens [i,o,n] concat "ion" contains "on" → MATCH. Token-boundary guarantee claimed in docs (L23-27) holds for WORD but **PHRASE token fallback can match inside a token run** ("discord gg" vs region "is cord gga" → concat "iscordgga" contains "discordgga"? no — but "cord gg" needle → tokens [cord,gg] → "cordgga" contains "cordgg" → TRUE). So a PHRASE rule can match across token boundaries in the region — matches the *documented* whitespace-stripped-substring semantics (L28-30 doc says substring), so this is spec, not bug. WORD is the boundary-safe one. Documented distinction verified.
- Gap M-2: `matchesRegion` for ZONE re-checks `zone.pageIndex == context.pageIndex` (L74) — combined with scope gate this is the only pageIndex check; PAGE and CHAPTER scopes rely on it entirely (L52 doesn't check pageIndex). Correct, subtle; pinned by tests.

## 6. Repo + SQL (`GetOcrExclusionZones` / `OcrExclusionZoneRepositoryImpl` / `.sq`)

- Interactor (`OcrExclusionZoneInteractors.kt:9-21`): thin pass-through. `awaitForSpeech(mangaId, sourceId, chapterId)` → `repository.getZonesForSpeech` (L17-18).
- Impl (`OcrExclusionZoneRepositoryImpl.kt:38-46`): `zonesForSpeech` query, IO dispatcher, awaitAsList. All writes transactional/IO-wrapped; `setEnabled` uses named args with comment about swapped positional bug fixed (L90-95).
- Mapper (L103-137): unknown enum strings fall back to `PAGE` scope / `ZONE` type via `runCatching` (L125, L134) — schema-evolution safe; silent default acceptable.
- SQL (`ocr_exclusion_zones.sq:40-64`): `enabled = 1` AND `(match_type IN ('WORD','PHRASE') OR manga_id = :mangaId OR source_id = :sourceId OR chapter_id = :chapterId)`.
  - **Scope coverage check**: PAGE/CHAPTER zones → `chapter_id = :chapterId` branch ✓; MANGA zones (migration 19 nulls their chapter_id, 19.sqm L32) → `manga_id` branch ✓; SOURCE zones → `source_id` branch ✓; WORD/PHRASE (global) → first branch ✓; COMBINED rows carry their scope's ids → covered by same id-branches ✓.
  - **Over-fetch is intentional**: OR across ids returns zones from OTHER manga/sources (e.g. a SOURCE-scoped zone matches every source? no — `source_id = :sourceId` binds; but `chapter_id = :chapterId` can hit rows of a different manga if chapter ids collided — they can't, chapter ids are PK). Remaining over-fetch: MANGA-scope zone of manga A + chapter_id branch pulls zones stored under other chapters *of other manga* whose chapter_id equals current — impossible (chapter_id PK global). Actual over-fetch: none beyond intent; matcher re-filters enabled+scope anyway (§5). Verified sound.
  - Index only on `manga_id` (.sq L19); query is OR-heavy → full scan likely; table is user-created rules (dozens), fine. `ponytail:` would index only if rule counts grow.
- Migration 19.sqm (L1-32): table rebuild adding match_text/match_type/rule_name; data preserved with NULL/'ZONE' defaults; MANGA/SOURCE rows get chapter_id NULLed (L32). Backfill consistent with matcher's null-pageIndex dormancy + scope-nulling.
- Gap SQL-1: `zonesForManga` (settings screen, .sq L66-86) lacks `enabled` filter — intentional (settings shows disabled rules). Different query for different consumer; verified not a leak into speech (speech path uses zonesForSpeech only).

## 7. Tests — inventory and gaps

Existing (all pure-JVM, `@Execution(CONCURRENT)`, kotest assertions):
- `SentenceSegmenterTest.kt` (16 tests): split/keep-punct, trailing fragment, whole-region, ASCII dot runs, English periods, ellipsis glue, decimals, half/full-width terminals, combined glyphs, blank/empty, no cross-region merge, order preservation, bbox/orientation carry, consecutive-terminal double-split.
- `SpeechPipelineDedupTest.kt` (9): overlap/disjoint/case/triple/empty + pipeline-level dup→one-sentence + IoU.
- `SpeechRegionFilterTest.kt` (5) + embedded `SpeechPipelineTest` (4): defaults, user config, foreign-script on/off, script selection, order; pipeline cleaning, punctuation-only slice drop, multiline split, empty.
- `SpeechRegionClassifierTest.kt` (7): dialogue, narration wide-thin, SFX, shouted-dialogue-not-SFX, interjection, symbol-only decorative, foreign-script-by-script.
- `SpeechCleanerTest.kt` (11): punctuation-only set, meaningful intact, ellipsis pause + disable, excessive normalization, garbage + short-emphatic never-garbage, line-break collapse, per-option disable.
- `TtsVoicePreferencesTest.kt` (5): voice→language→default fallback chain incl. uninstalled engine.
- `OcrExclusionMatcherTest.kt` (26): page/chapter/manga/source zone anchoring, page-index gating, null-pageIndex dormancy, edge-touch + degenerate rects, multi-zone, disabled, WORD token semantics (standalone, case, unicode, K-manga.com, separator variants, consecutive-run, full-width), PHRASE (case/ws, substring, URL noise, full-width, nakaguro, cross-region pin, punctuation-only), COMBINED chapter/manga/source, legacy dormancy, any-match mixing, normalized coords.

**Gaps (code-verified absences, confirmed by targeted grep):**
1. **AndroidTtsEngine: zero tests.** Only non-Robolectric-testable pieces are `resolveVoiceSelection` (covered) and pending-utterance map logic. `completeUtterance`/`failPendingUtterance`/identity-guard (L133-139, L269-280) are pure-enough to test if the TTS object were seamable — currently requires instrumentation. Proposal: extract pending-map logic into a small `TtsUtteranceTracker` (map + complete/fail/identity-remove) and unit-test that; 3 tests (done-callback completes true; stop fails pending; same-id relaunch keeps newer entry). This is the highest-value missing test.
2. **OcrExclusionZoneRepositoryImpl / SQL queries: zero tests.** No `data` module test dir hits for ocr_exclusion. SQLDelight offers in-memory driver tests (codebase pattern: check `data/src/test` — none for OCR). Proposal: one test class with in-memory driver covering `zonesForSpeech` branch matrix (WORD/PHRASE-only rows, manga/source/chapter branches, enabled=0 excluded, MANGA-scope chapter_id NULL rows returned via manga branch) — 4 tests. This is the second-highest-value missing test (migration 19 semantics live only in matcher tests, not SQL).
3. `matchesRegionScope`/`matchesRegion` are `private` in matcher file — fine, tested via `applyExclusions`.
4. No test pins **dedup-after-exclusions double-run idempotence** (§4.1) — one-liner test candidate, low priority.
5. `SpeechRegionClassifier` UNKNOWN dead-value (§4.2 C-2) untested/untestable-as-behavior; delete when touched.

## 8. Minimal proposals (all read-only here; none applied)

- P-1 (1-line): remove unused `availableEnginePackages` param from `resolveVoiceSelection` + its 5 test sites, or add real engine-membership guard in `applyVoiceProfile`. Recommend param removal — behavior identical (TtsVoicePreferencesTest uninstalled-engine case passes via empty selections).
- P-2: extract `TtsUtteranceTracker` (§7.1) + 3 unit tests. No behavior change.
- P-3: in-memory-driver tests for `zonesForSpeech` branch matrix (§7.2), 4 tests.
- P-4 (cosmetic, skip unless touching): dedup NFKC (§4 D-1), UNKNOWN enum deletion (§4 C-2), classifier input-domain unification (§4 C-1).
- No schema change → no migration gate triggered. No CI-affecting proposal.

## 9. Code-verified vs runtime-unverified (explicit ledger)

CODE-VERIFIED (source read this session): all §1-§8 claims with file:line citations above.
RUNTIME-UNVERIFIED (would need device/engine/DB):
- Actual Android TTS engine behavior: prosody reset on `setVoice` re-apply during playback (§2.1); `speak`-after-`shutdown` degradation path (§2.2); focus-callback threading (§2.4).
- SQLDelight `zonesForSpeech` result set against a live DB (branch matrix is reasoned + migration-read, not executed).
- Prefetch/latency numbers quoted in controller comments (L627-629) — parent's domain.
No builds/tests run this session (read-only mandate). Last recorded gates: 2026-09-16 all green (state.md L25-26) — predates zero TTS/OCR changes since (`git log`: last TTS commit ed368bf6a BUG-003..008 fixes, already part of green run).






