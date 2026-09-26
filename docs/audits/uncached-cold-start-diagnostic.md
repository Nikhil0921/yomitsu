# Uncached-Manga TTS Cold-Start Diagnostic

Date: 2026-09-26. Evidence: `.device-pass/on-device-capture.log` (09-25 22:52–23:18, S1–S5
verification session) + `.device-pass/logcat-20260925-200614.log` (09-26 00:40–01:42,
post-Fix-1/2/3 build). SM_M066B wireless.

## 1. Verdict

**The "Couldn't get page text for reading aloud" (`tts_error_ocr`) toast on uncached
manga is NOT caused by the 8s OCR timeout guard and NOT by GLENS tile failures.**
It is caused by **`PrioritizedTaskQueue` non-preemption**: when TTS starts on an
uncached chapter, the `HIGH`-priority current-page scan is enqueued *behind up to
3 in-flight `NORMAL` scans* (background `OcrScanJob` chapter scan + TTS prefetch of
pages N+1..N+3). The HIGH task waits for the slowest running NORMAL to complete.
On a cold chapter the wait + scan routinely exceeds **15–29s** (observed 6661ms queue
wait + 5787ms scan = 17.6s; worst 29.1s end-to-end). The controller's
`withTimeoutOrNull(8000)` in `acquireSentences` therefore **fires at 8s and
permanently fails the TTS session** with `TtsError.OcrError` → toast → error phase.

In the 09-26 capture (all cache-warm chapters) every TTS start reached first speech
in 74–355ms and **zero** OcrError toasts fired — the failure mode only reproduces on
a *freshly opened, never-scanned chapter* while the background scan is still running.

## 2. Exact failure sequence (cold chapter, from 09-25 23:07:09 trace)

```
23:07:09.272  TTS start chapter=8724 page=0 (reader just opened, uncached)
23:07:09.330  TTS on-demand scan start p1   (prefetch, NORMAL)
23:07:09.402  TTS on-demand scan start p2   (prefetch, NORMAL)
23:07:09.420  TTS on-demand scan start p3   (prefetch, NORMAL)
23:07:09.426  TTS on-demand scan start p0   (current page, HIGH)
23:07:09.656  OcrScanJob WorkManager starts background scan of 8724 (NORMAL, 40 pages)
23:07:10.85   page-list network fetch done (pagelist hit=false → resolveRemote, ~1.4s)
23:07:11.0–11.6  3× NORMAL scanPageOcr awaits started (p1,p2,p3) → 3 queue slots occupied
23:07:11.595  HIGH p0 ENQUEUED — activeSlots = 3× NORMAL (cap=3, full)
                → HIGH cannot preempt running NORMALs (documented RC-3 semantics)
23:07:18.256  slowest NORMAL (p1, 6.9s) finishes → HIGH p0 STARTS (waitMs=6661)
23:07:18.275  OcrRepositoryImpl: OCR scan glens chapter=8724 page=0 priority=HIGH
23:07:24.333  GLENS tile upload done (5787ms)
23:07:26.866  TTS acquire usable end acquireMs=17587 → FIRST SPEECH 17.6s after start
```

Worst observed in same log: `TTS scanPageOcr await end` elapsedNs = 29.1s
(`23:xx` window, chapter re-scan during TTS mid-playback).

**What the 8s guard (Fix 2) does in this exact scenario:**
`acquireSentences` wraps `scanOnDemand` in `withTimeoutOrNull(8000)`. The `scanPageOcr.await`
HIGH call takes 15.2s (queue wait 6.7s + scan 8.5s). At t=8s the timeout cancels the
in-flight await → `withTimeoutOrNull` returns null → `fail(TtsError.OcrError)` →
session enters `Error` phase → toast "Couldn't get page text for reading aloud".
The user sees the error *even though the OCR scan would have succeeded 7s later*.

**Root cause: the 8s guard treats "queue slot-wait" (transient, bounded, always
recovers) as "OCR failure" (permanent).** The timeout fires on a *succeeding* scan.

## 3. Latency breakdown of the uncached p0 path (09-25 trace, ms)

| Stage | Duration | Notes |
|---|---|---|
| Page-list fetch (`pagelist hit=false` → `resolveRemote`) | ~1436 | network, chapter page-list HTTP |
| Image download (`openBitmap` network branch) | ~1000–2500 | `cf-cache-status: HIT/MISS` dependent |
| GLENS 4-tile upload + compute (cold, first batch) | 5500–15000 | p50≈5500, observed max 13157ms per tile; 12s read-timeout covers single tile |
| `PrioritizedTaskQueue` HIGH slot-wait (cap-full) | 0–7000 | **dominant uncontrolled variable**; = duration of slowest running NORMAL |
| **Total start→first-speech (cold)** | **12000–29000** | observed: 6130, 12337, 17593 ms |

The 8s guard sits over *all* of these. When queue wait alone can be 6.7s, the guard
is arithmetically guaranteed to fire on any cold chapter whose normal-page scan takes
>1.3s (which is most of them).

## 4. Why 09-26 capture shows no failures

Chapters 8721/8722/8723 had been pre-scanned by `OcrScanJob` (20 Worker SUCCESS)
before TTS started → every `acquireSentences` was a cache hit (acquireMs 49–346ms).
The 8s guard never fired. **This capture does not reproduce the user's error.**
The reproducing condition = TTS start on a chapter whose background scan has NOT
completed p0 yet (reader just opened, scan in flight).

## 5. Recommended fixes (ranked)

### A. (Primary) Guard against "queue wait" ≠ "OCR failure" — fix the 8s guard semantics
The `withTimeoutOrNull(OCR_ACQUIRE_TIMEOUT_MS)` in `acquireSentences` must NOT apply
to the `scanPageOcr.await` high-priority call when the underlying scan is merely
*queued behind NORMAL tasks*. Two minimal options:

1. **Raise the guard to 30s** (`OCR_ACQUIRE_TIMEOUT_MS = 30_000`): worst observed
   cold path is 29.1s. A 30s guard still bounds pathological stalls (GLENS 500
   storm, dead network) while never firing on a succeeding scan. Cost: 30s of
   silent `LoadingPage` instead of an instant false-negative error. UX-preferrable:
   add a visible "Scanning page…" progress state for cold p0 (see C).
2. **Or**: check queue depth before giving up — if the HIGH task is still *queued*
   (not yet running) at t=8s, extend once to 20s; only fail if it exceeded 20s
   total. Requires exposing queue position (small addition to `PrioritizedTaskQueue`).

Option 1 is the smallest diff (one constant) and matches observed ceiling.

### B. (Complementary) Pre-warm p0 at reader-open with HIGH priority, before TTS button
`prefetchReaderOpenPage(ctx, startPage)` already runs `scanOnDemand` at NORMAL.
For the *first* page the user is on, dispatch it at `OcrScanPriority.HIGH` so the
reader-open prefetch itself grabs a queue slot ahead of the background OcrScanJob's
NORMAL flood. This removes the cap-full HIGH wait in the most common cold path
(open chapter → immediately press Read-Aloud).

### C. (UX) Replace the error toast with a bounded loading state for cold p0
While `acquireSentences` is blocked on a slow HIGH scan, the controller is in
`LoadingPage`. Surface "Scanning page N…" (existing `tts_preparing` string) for up to
the guard window; only emit `TtsError.OcrError` after the guard actually expires.
This turns a false-negative "Unable to load read aloud" into a short spinner even
when fix A's 30s window is exceeded.

### D. (Regression guard) Unit test: HIGH queue-wait does not trigger OcrError
Add a `TtsPlaybackController` test that stubs `scanPageOcr.await` to complete at
12s (queue wait + scan) and asserts `fail(TtsError.OcrError)` is NOT called for
acquire times under the guard, and IS called above it.

## 6. No regressions found in 09-26 capture

- 183/183 GLENS tiles HTTP 200; max tile 6.9s (< 12s read timeout) — Fix 1 retry path
  dormant, no 5xx in session
- 20/20 OcrScanJob Worker SUCCESS
- 24× S3 warm voice-skip (0ms)
- 197 TTS dispatches, max inter-sentence gap 23.3s = user tap-pause (not buffering)
- No app crashes; `GetChaptersByMangaId ChildCancelledException` lines at 01:40:57 =
  normal scope cancellation on reader reopen (E-log noise, pre-existing)
- Two toasts in capture (01:35:38, 01:40:57) = reading-mode / chapter-advance
  toasts, **not** TtsError — zero OcrError emissions in the warm-cache session
