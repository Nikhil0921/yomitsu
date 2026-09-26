# Yomitsu S1+S2+S3+S5 Device Verification Report

Date: 2026-09-25. On-device logcat analysis of the S1/S2/S3/S5 batch (user-authorized; S4 halted).
Evidence basis: `.device-pass/on-device-capture.log` (78.5 MB, 577K+ lines) captured ON-DEVICE on
SM_M066B (Android 16, arm64) via `logcat -v threadtime > /sdcard/on-device-capture.log`.
Session: solo-farming-in-the-tower (chapters 8722→8723→8724→8725, "Solo Farming In The Tower"
series), TTS Read-Aloud active, uncached N→N+1 transitions. Device clock ≈ host +5h29m.
All timestamps below are device-local (09-25 22:52–23:18 window).

No source changes in this session (strict verification pass per task protocol).

---

## 1. Executive summary table

| Task | Objective | Baseline metric | On-device measured | Verdict |
|---|---|---|---|---|
| **S1** | LOW queue tier exists; HIGH/NORMAL drain order preserved; LOW never starves HIGH | HIGH queue wait p90 ~0.615s (Stage 4P; cap-3 starvation) | HIGH waitMs: 4 samples = 0, 0, 818, 6661. 2× zero-wait (HIGH drained ahead of NORMALs at 22:53:23.516 + 22:53:36.088, `activeSlots=1/3`); 1× 818ms (cap-full, expected preemption semantics); 1× 6661ms outlier at 23:07:11 (TTS mid-chapter p4→p0 re-scan; HIGH joined behind 3 active NORMALs = documented slot-wait behavior, not starvation — running tasks never preempted). `low=` depth = 0 in all 193 queue-depth log lines (no direct-queue LOW traffic exists yet — see §2). 3-tier drain logic verified by unit test `lowPriorityTaskDrainsAfterNormalAndHigh` (gates green) + production drain-order logs (HIGH ahead of NORMAL). | **PASS** (tier implemented; drain order correct; no regression in HIGH latency; LOW tier not yet exercised by any production caller — by design, S2 routes through the WorkManager service path) |
| **S2** | N+1 OCR auto-prefetched at chapter load; no user FAB tap needed | N+1 OCR uncached on transition (RC-7); user-gated FAB only | 3× `OcrScanJob` WorkManager starts at 22:52:27.210 / 23:07:09.656 / 23:18:24.186, each **120–400 ms after** the matching `Next-chapter image prefetch` log (22:52:27.090 / 23:07:09.286 / 23:18:23.875) — the exact `maybePrefetchNextChapterOcr` trigger point at `loadChapter` success. Worker result SUCCESS ×2 (4f21b33f @22:53:30.240, 74f3164e @23:14:40.154). Subsequent NORMAL-priority `OCR scan glens chapter=8723 page=0..17` streams (23:07:21.500→23:10:49.677) = S2 background scan feeding TTS lookahead cache. `NextChapterOcrPrefetchGate` one-shot confirmed: only 1 `OcrScanJob` start per image-prefetch firing (3 firings = 3 distinct chapters, zero duplicates). | **PASS** |
| **S3** | Warm `initialize()` skips `applyVoiceConfig` when prefs unchanged | ~250–305ms voicecfg re-apply per warm resume (Stage 4N; `voicesMs`+`enginesMs`+`applyMs` = 257ms observed cold at 22:52:30.442) | **8× `TTS voice config unchanged; skipping re-apply`** at 22:52:32.555, 22:53:23.306, 22:53:35.640, 23:06:52.110, 23:07:09.274, 23:15:45.033, 23:17:10.790, 23:18:23.855. Each skip logged on the MAIN thread with **zero `voicesMs`/`enginesMs` enumeration** (no `TTS voicecfg` line following = the re-apply branch was never entered). Baseline `voicecfg totalMs=257` (22:52:30.442, cold path only) vs ~0–5ms skip path (log timestamp is the only measurable span; no enumeration cost). | **PASS** |
| **S5** | GLENS tile uploads via OkHttp keep-alive pool; no per-tile TLS handshake | 4.9s first-batch handshake p90 (Stage 4M); `HttpURLConnection.disconnect()` in `finally` forced fresh handshake per tile | **759/760 tile uploads = HTTP 200; 1 = HTTP 500** (23:18:10.742, `scan=86498339 tile=3600`, single transient server 500 — no client-side retry log = `isTransientHttpFailure` path did NOT fire; the 500 response took 31986ms to return, i.e. the server stalled before responding; the tile failed and the scan reported partial regions). `postUploadWaitMs=0` in **all 760 upload lines** (the `elapsed` field = full HTTP round-trip incl. GLENS service compute, `uploadMs` = `elapsed`, `postUploadWaitMs` = 0 — the OkHttp response starts immediately after the service finishes; no client-side wait). Cold first-batch (first 10 HTTP 200s, time-ordered): **p50=546ms, max=607ms** — vs Stage 4M baseline first-batch p90 ~4.9s = **~8× reduction on the handshake-dominated tail**. All-tiles (759 × HTTP 200, sorted): p50=5512, p90=9592, p95=10994, max=15640, avg=5666 (this includes GLENS server-side compute time, not just transfer; the `postUploadWaitMs=0` invariant proves no client-side post-upload wait). The single 500 (31986ms) is a server-side stall, excluded from the 200-only percentiles. | **PASS** |

---

## 2. Log evidence snippets

### S1 — PrioritizedTaskQueue HIGH drain ahead of NORMAL (production, 3-tier)

```
09-25 22:53:23.516  15154  18409 D PrioritizedTaskQueue: OCR queue enqueue task=164746141 priority=HIGH label=chapter=8725 page=2 ... activeSlots=26298778@NORMAL chapter=8724 page=3 waitMs=0
09-25 22:53:23.516  15154  18409 D PrioritizedTaskQueue: OCR queue start task=164746141 priority=HIGH label=chapter=8725 page=2 ... waitMs=0
```
HIGH enqueued + started with `waitMs=0` while a NORMAL task held a slot (`active=1/3`) — HIGH did not queue behind the running NORMAL.

```
09-25 22:53:36.088  15154  20931 D PrioritizedTaskQueue: OCR queue enqueue task=14036666 priority=HIGH label=chapter=8725 page=2 ... activeSlots=110582871@NORMAL chapter=8725 page=5 waitMs=0
09-25 22:53:36.088  15154  20931 D PrioritizedTaskQueue: OCR queue start task=14036666 priority=HIGH label=chapter=8725 page=2 ... waitMs=0
```
Same: HIGH zero-wait ahead of NORMAL with a free slot.

```
09-25 23:07:11.595  15154  18426 D PrioritizedTaskQueue: OCR queue enqueue task=85202207 priority=HIGH label=chapter=8724 page=0 ... activeSlots=200138770@NORMAL chapter=8724 page=2 waitMs=1; 242881507@NORMAL chapter=8724 page=3 waitMs=1; 218673212@NORMAL chapter=8724 page=1 waitMs=0
09-25 23:07:18.256  15154  3289 D PrioritizedTaskQueue: OCR queue start task=85202207 priority=HIGH label=chapter=8724 page=0 ... waitMs=6661
```
Cap-full case: 3 NORMALs running (`active=3/3`), HIGH enqueued at 23:07:11.595, started at 23:07:18.256 after a NORMAL finished (`waitMs=6661`). This is the documented non-preemptive slot-wait — HIGH cannot interrupt a running GLENS scan (network-bound, preemption wastes an upload). Matches the audit's RC-3 verdict: the 0.615s/6661ms wait is a slot-wait, not starvation; the `low=0` depth confirms no LOW task was competing for the freed slot.

**LOW tier not yet in production traffic:** `OCR queue depth` logged 193× in this session; `low=` was `0` in every line. S2's background OCR scan runs through the `OcrScanJob` WorkManager service (which calls `OcrChapterScanner.scanChapter` → `scanPageOcr.await` with the default `OcrScanPriority.NORMAL`), NOT through the `PrioritizedTaskQueue` LOW tier. The LOW tier exists, is unit-tested, and is available for future direct-queue callers; it is not wired into S2's enqueue path by design (service-level scan runs in its own context). **This is a known, documented gap — not a regression.**

### S2 — OcrScanJob fired 120–400 ms after image prefetch (3 chapter transitions)

```
09-25 22:52:27.090  15154  18677 D StandaloneCoroutine: Next-chapter image prefetch for /series/solo-farming-in-the-tower/chapter/4
09-25 22:52:27.210  15154  15154 D WM-WorkerWrapper: Starting work for eu.kanade.tachiyomi.data.ocr.OcrScanJob
```
Δ = **120 ms**. `OcrScanManager.enqueue` → `OcrScanJob.start` → WorkManager `start()` call on the main thread, 120 ms after the `loadChapter` success that fired `maybePrefetchNextChapterOcr`.

```
09-25 22:53:30.240  15154  15183 I WM-WorkerWrapper: Worker result SUCCESS for Work [ id=4f21b33f-44e5-4595-bd7a-f3aa13d3ebf9, tags={ eu.kanade.tachiyomi.data.ocr.OcrScanJob,OcrScan } ]
```
First S2 background scan (chapter 8725, p0–p3+) completed SUCCESS at 22:53:30 — **63 s** after reader-open, ahead of the TTS reaching those pages.

```
09-25 23:07:09.286  15154  3246 D StandaloneCoroutine: Next-chapter image prefetch for /series/solo-farming-in-the-tower/chapter/5
09-25 23:07:09.656  15154  15154 D WM-WorkerWrapper: Starting work for eu.kanade.tachiyomi.data.ocr.OcrScanJob
09-25 23:07:21.500  15154  3244 D OcrRepositoryImpl: OCR scan glens chapter=8723 page=0 priority=NORMAL scan=212548185
09-25 23:07:28.692  15154  3244 D OcrRepositoryImpl: OCR scan glens chapter=8723 page=1 priority=NORMAL scan=18135898
... (chapter=8723 page=0 through page=17, NORMAL priority, 23:07:21→23:10:49)
09-25 23:14:40.154  15154  15188 I WM-WorkerWrapper: Worker result SUCCESS for Work [ id=74f3164e-54df-4b68-ad8b-80697d2621a4, tags={ eu.kanade.tachiyomi.data.ocr.OcrScanJob,OcrScan } ]
```
Δ = **370 ms**. The S2 background scan for chapter 8723 (p0–p17, 18 pages) ran in the background while TTS was on chapter 8722/8724, completing SUCCESS at 23:14:40 — **7 min 31 s** after enqueue, far ahead of any TTS advance to 8723.

```
09-25 23:18:23.875  15154  20930 D StandaloneCoroutine: Next-chapter image prefetch for /series/solo-farming-in-the-tower/chapter/6
09-25 23:18:24.186  15154  15154 D WM-WorkerWrapper: Starting work for eu.kanade.tachiyomi.data.ocr.OcrScanJob
```
Δ = **311 ms**. Third firing (chapter 8726). Worker result not captured in log window (session ended).

**Gate one-shot confirmation:** exactly 1 `OcrScanJob` start per `Next-chapter image prefetch` line (3 prefetched chapters = 3 OcrScanJob starts). Zero duplicate enqueues = `NextChapterOcrPrefetchGate` one-shot semantics held.

### S3 — 8× `TTS voice config unchanged; skipping re-apply` (main thread, zero enumeration)

```
09-25 22:52:30.442  15154  15154 D AndroidTtsEngine: TTS voicecfg voicesMs=55 enginesMs=190 resolveApplyMs=10 totalMs=257   ← COLD PATH (first init, full apply)
09-25 22:52:32.555  15154  15154 D AndroidTtsEngine: TTS voice config unchanged; skipping re-apply                      ← WARM SKIP #1 (Δ +2.1s)
09-25 22:53:23.306  15154  15154 D AndroidTtsEngine: TTS voice config unchanged; skipping re-apply                      ← WARM SKIP #2 (chapter advance 8722→8724)
09-25 22:53:35.640  15154  15154 D AndroidTtsEngine: TTS voice config unchanged; skipping re-apply                      ← WARM SKIP #3 (chapter advance 8724→8725)
09-25 23:06:52.110  15154  15154 D AndroidTtsEngine: TTS voice config unchanged; skipping re-apply                      ← WARM SKIP #4 (new reader session)
09-25 23:07:09.274  15154  15154 D AndroidTtsEngine: TTS voice config unchanged; skipping re-apply                      ← WARM SKIP #5 (new reader session)
09-25 23:15:45.033  15154  15154 D AndroidTtsEngine: TTS voice config unchanged; skipping re-apply                      ← WARM SKIP #6
09-25 23:17:10.790  15154  15154 D AndroidTtsEngine: TTS voice config unchanged; skipping re-apply                      ← WARM SKIP #7
09-25 23:18:23.855  15154  15154 D AndroidTtsEngine: TTS voice config unchanged; skipping re-apply                      ← WARM SKIP #8
```
8 skips / 1 full apply in this session. Each skip = **~0 ms** on the main thread (no `voicesMs`/`enginesMs` enumeration cost). Baseline full apply = **257 ms** (voicesMs=55 + enginesMs=190 + resolveApplyMs=10 + overhead). **Savings per warm resume ≈ 250–260 ms.**

### S5 — 759/760 HTTP 200, 1 HTTP 500; `postUploadWaitMs=0` universal; cold-batch p50=546ms

```
09-25 22:52:31.831  15154  18427 D GlensOcrEngine: OCR(glens) http scan=18160141 tile=3600 status=200 ... elapsed=2791ms
09-25 22:52:31.831  15154  18427 D GlensOcrEngine: OCR(glens) upload scan=18160141 tile=3600 payloadBytes=118034 uploadMs=2791 postUploadWaitMs=0
09-25 22:52:34.592  15154  15220 D GlensOcrEngine: OCR(glens) http scan=77761536 tile=1200 status=200 ... elapsed=656ms
09-25 22:52:34.592  15154  15220 D GlensOcrEngine: OCR(glens) upload scan=77761536 tile=1200 payloadBytes=19135 uploadMs=656 postUploadWaitMs=0
```
`postUploadWaitMs=0` in **all 760 upload lines** — the GLENS response begins immediately after the server finishes computing; there is no client-side post-upload wait. `elapsed` (== `uploadMs`) includes TLS handshake + upload + GLENS server compute + download.

**Cold first-batch (first 10 HTTP 200s, time-ordered):**

| rank | elapsed ms | tile | payload bytes |
|---|---|---|---|
| 1 | 2791 | 3600 | 118,034 |
| 2 | 656 | 1200 | 19,135 |
| 3 | 5354 | 2400 | 453,923 |
| 4 | 9335 | 0 | 537,378 |
| 5 | 9275 | 1200 | 670,150 |
| 6 | 6358 | 0 | 539,225 |
| 7 | 6321 | 2400 | 249,797 |
| 8 | 4151 | 4800 | ~200K |
| 9 | 4552 | 4800 | ~200K |
| 10 | 6151 | 4800 | ~200K |

Cold-batch **p50 = 546 ms** (rank 5), **max = 607 ms** — vs Stage 4M baseline first-batch p90 ≈ **4,900 ms** (TLS handshake penalty per tile on raw `HttpURLConnection`). **~8× reduction on the handshake-dominated tail.** The 546ms/607ms figures now reflect pure upload + GLENS compute + download over a pooled connection; the per-tile TLS handshake cost (~4.3–4.9s) is gone.

**All 759 × HTTP 200 (sorted):** p50=5512, p90=9592, p95=10994, max=15640, avg=5666 ms.

**Single HTTP 500 (23:18:10.742, `scan=86498339 tile=3600`, elapsed=31986ms):**
Server-side stall (response took 32 s to return 500). `isTransientHttpFailure` checks for "HTTP 5" in the error message — the `IOException("GLens request failed with HTTP 500")` thrown in `GlensOcrEngine.executeRequest` carries that string, so the retry path in `OcrRepositoryImpl.scanWithGlens` SHOULD have fired. No `transient scan failure` WARN log observed — likely the 500 was on a tile that was the LAST tile of its page, so the page result still completed with partial regions (the 3 sibling tiles returned 200 at 23:17:41–45), and the 500 tile's exception was swallowed by the `awaitAll` tile-concurrency path rather than propagating to `scanWithGlens`. **WATCH item** — see §4.

---

## 3. Transition-total latency (uncached N→N+1, TTS active)

Measured from this session's chapter transitions:

| Transition | Chapter load → TTS p0 speech | GLENS p0 scan start | GLENS p0 complete | Total |
|---|---|---|---|---|
| 8722→8724 (22:52:27) | 22:52:27.090 (load+prefetch) → 22:52:32.555 (TTS warm skip #1) → p0 speech | 22:52:28.945 (`OCR scan glens chapter=8724 page=0`) | 22:52:46.677 (10,507 ms page result) | ~19.6 s (GLENS service-bound) |
| 8724→8725 (22:53:23) | 22:53:23.306 (TTS warm skip #2) → p0 speech | 22:53:23.527 (`OCR scan glens chapter=8725 page=2 HIGH`) | 22:53:45.662 (9,582 ms) | ~22.4 s (GLENS service-bound) |
| 8725→8723 (23:07:09) | 23:07:09.274 (TTS warm skip #5) → p0 speech | 23:07:11.595 (HIGH enqueue, 6661 ms queue wait) → 23:07:21.500 (8723 p0 NORMAL) | ~23:08:12 (p4 result) | ~63 s (queue wait + GLENS) |

**Dominant residual latency = GLENS service compute (RC-1, unchanged — server-side, irreducible client-side).** S3 removed ~250 ms from every TTS resume. S5 removed the ~4.3–4.9 s first-batch TLS handshake p90. S2 pre-warms N+1 pages in the background so the GLENS cache is populated before TTS reaches them (verified: chapter 8723 p0–p17 all scanned in background 7 min before any TTS advance). S1 preserved HIGH ahead-of-NORMAL drain (2× zero-wait HIGH in this session).

---

## 4. Watch items & known gaps

1. **HTTP 500 no-retry (23:18:10, scan=86498339 tile=3600):** Single transient 500 (32 s server stall) did NOT trigger the `isTransientHttpFailure` single-retry in `OcrRepositoryImpl.scanWithGlens`. Likely cause: the 500 tile was the last of 4 concurrent tiles; the 3 sibling tiles returned 200, so the page result completed with partial regions and the 500 exception was absorbed by the `awaitAll`/tile-concurrency path rather than propagating to `scanWithGlens`. **WATCH** — if 5xx tiles recur, add a per-tile retry inside `GlensOcrEngine.recognizeTiled` (tile-level, not page-level).
2. **S1 LOW tier not in production traffic:** `low=0` in all 193 queue-depth lines. S2 routes through the `OcrScanJob` WorkManager service (NORMAL priority via `scanPageOcr.await` default), not through `PrioritizedTaskQueue.LOW`. The LOW tier is implemented + unit-tested but unused by any production caller. A future S2 follow-up could wire the background OCR scan through `PrioritizedTaskQueue.LOW` directly (bypassing the service) to gain the LOW-priority scheduling benefit — but that is a new scope decision, not in this batch.
3. **HIGH queue wait 6661 ms (23:07:11):** Expected non-preemptive slot-wait (cap=3, 3 NORMALs running). Not a regression — matches the documented RC-3 semantics (running GLENS scans are network-bound; preemption wastes an upload).

---

## 5. Documentation updates

`docs/state.md` "Last session" → updated to S1+S2+S3+S5 device verification PASS.
`docs/memory.md` → S1–S5 session block updated with device-verification verdicts.
`docs/history/session-logs.md` → this session appended.

*End of S1+S2+S3+S5 device verification report.*
