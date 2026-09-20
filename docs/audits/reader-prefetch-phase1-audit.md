# Phase 1 — Reader Architecture & Queue Audit (READ-ONLY)

> 2026-09-20 · post-v0.5.4.2 · No source files modified. Evidence = file:line refs +
> Stage 4L/4M/4P device forensics in memory.md.
> Goal: ground truth for a **Next-Chapter Prefetch Pipeline** (Chapter N+1) without
> starving Chapter N (page loads, active OCR, TTS prefetch).
>
> **STATUS: VERIFIED + SHIPPED (2026-09-20).** §4 hooks implemented (Phase 2/3 task)
> and device-verified on SM_M066B: 3× logcat firings across 3 reader opens
> (Asura Scans remote source), N+1 p0..p3 all `status=Ready` in `ChapterCache`
> (disk hits confirmed on subsequent opens), transition into N+1 rendered from
> cache. Logcat evidence: `.device-pass/prefetch-verify.log`.
> Detailed runtime statistics + per-session timing table:
> `docs/audits/next-chapter-prefetch-verification-report.md` (2026-09-21 capture,
> 266,942-line logcat; N+1 p0–p4 ready 1–20 ms disk; 0 image GETs warm;
> worker-isolation threads confirmed; OCR waitMs=0 co-located).

---

## 1. Current System Flow & Hotspots

### 1.1 Opening a chapter

1. `ReaderViewModel.init` (ReaderViewModel.kt:489) → `loadChapter(loader!!, …)`
   (:504) → `ChapterLoader.loadChapter` (loader/ChapterLoader.kt:35).
2. `ChapterLoader` picks `HttpPageLoader` (network) / `DownloadPageLoader` /
   `ArchivePageLoader` / `EpubPageLoader` / `DirectoryPageLoader` (:78-106).
   Each loader instance owns its own queue + worker.
3. `HttpPageLoader.getPages()` (:71): page list from `ChapterCache` first
   (disk JSON, key = md5 of mangaId+url), else `source.getPageList` (network).
4. `ChapterLoader` then sets `chapter.state = State.Loaded(pages)` —
   **page images are NOT fetched at chapter load**. Images load lazily via
   the viewer's per-page `loadPage` (PagerPageHolder.loadPageAndProcessStatus).
5. `HttpPageLoader` ctor (HttpPageLoader.kt:48-63): `PriorityBlockingQueue` +
   **a single worker coroutine** (`scope.launchIO { flow { queue.take() }… }`),
   i.e. strictly one page load at a time per chapter. On each `loadPage(page)`
   (:89) it also enqueues `preloadNextPages(page, 4)` as ADJACENT (0) while
   the page itself is DEFAULT (1); RETRY=2. So "next 4 pages" = queued
   entries processed by that one worker in order.
6. `internalLoadPage` (:177): `getImageUrl` → `source.getImage` →
   `ChapterCache.putImageToCache` (DiskLruCache write + `flush()`) → page Ready.

### 1.2 Adjacent-chapter handling TODAY

- `ViewerChapters(curr, prev, next)` built in `loadChapter` (:556-560) —
  `chapterList.getOrNull(pos±1)`; adjacent chapters hold only a
  `ReaderChapter` stub (state Wait/Error), no loader, no pages.
- Preload trigger: viewer transition holders call
  `ReaderActivity.requestPreloadChapter` (ReaderActivity.kt:1517) →
  `viewModel.preload(chapter)` (ReaderViewModel.kt:640) →
  `loader.loadChapter(chapter)` — **page-list fetch only** (same path as
  active chapter). Callers: PagerViewer.kt:82,318,334 · PagerTransitionHolder.kt:131
  · WebtoonViewer.kt:116,321,338 · WebtoonTransitionHolder.kt:141.
- Transition into N+1: `loadNewChapter` (:582, user swipe across boundary) or
  `loadNext/PreviousChapter` → `loadAdjacent` (:607, toolbar + TTS
  AdvanceChapter). TTS path: `TtsEvent.AdvanceChapter` →
  `Event.TtsAdvanceChapter` (ReaderViewModel.kt:229) →
  ReaderActivity.kt:422 → `loadNextChapter` → `viewModel.loadNextChapter()`
  (suspended) → `moveToPageIndex(0)` only on success.

### 1.3 Where the 10–30 s transition delays come from

Bottleneck is **sequential page-list + first-images network on the transition
itself**, not an in-app queue:

- Transition into N+1 → its `HttpPageLoader` must `getPages` (1 HTTP round
  trip if page list uncached) before `State.Loaded` → viewer can show page 0.
- Then N+1 page 0 image `getImage` (1 round trip) before any bitmap.
- If TTS is active: N+1 p0 OCR = GLENS upload + service round-trip.
  Stage 4M (ch8475): post-upload GLENS service wait **median 9.6 s /
  p90 17.9 s**; ~14 s of ~27 s total scan latency is hidden by existing
  prefetches in settled reading. First-page round trip alone observed
  15–30 s (roadmap §M Q1 entry). Stage 4P already cut the p0 HIGH queue wait
  (~0.615 s) by starting reader-open OCR prefetch at `loadNewChapter`
  (ReaderViewModel.kt:594).
- `preload(N+1)` runs early, but it caches **page lists** (small JSON) —
  image bytes still land on transition.

### 1.4 Hotspot inventory

| Hotspot | Mechanism | Evidence |
|---|---|---|
| H1 | N+1 transition = uncached `getPageList` + `getImage`(p0) serial network | §1.3 |
| H2 | OCR queue: `PrioritizedTaskQueue(maxConcurrentTasks=3)`; TTS prefetch depth 2–3 (rate-aware, TtsPlaybackController.kt:784-791, MAX=3) + OcrChapterScanner background scan → N's p0 HIGH can queue behind NORMAL tasks: 0.615 s wait measured (Stage 4P) | PrioritizedTaskQueue.kt:51-161 |
| H3 | DiskLruCache single global `flush()` per image write (ChapterCache.kt:160) — heavy concurrent N+1 writes = journal I/O under N's reads | §3 |
| H4 | OkHttp shared per-source clients: N's up-to-4 in-flight preloads + N+1 traffic compete for per-host pool (~5 slots) — N+1 image fetch can delay N's ADJACENT preloads on shared connections | HttpPageLoader.kt:221-238 (per-chapter worker, not a global pool) |

---

## 2. Queue & Resource Isolation Strategy

### 2.1 Existing priority tiers (documented, as-is)

| Queue | Tiers | Callers |
|---|---|---|
| `PrioritizedTaskQueue` (data, OCR scans) | `HIGH`, `NORMAL` — **no LOW/background tier exists** | TTS current page HIGH (TtsPlaybackController.kt:751 reportFailure=true), TTS prefetch + reader-open OCR prefetch + OcrChapterScanner = NORMAL; `OcrScanPriority` (OcrModels.kt:39) maps 1:1 via `toQueuePriority()` (OcrRepositoryImpl.kt:627) |
| `HttpPageLoader` queue | `RETRY(2) > DEFAULT(1) > ADJACENT(0)`, **one worker per chapter instance** (HttpPageLoader.kt:44-63) | each chapter loads via its own loader; N and N+1 are isolated by construction |
| `OcrRepositoryImpl` concurrency | `maxConcurrentTasks=3`, HIGH deque drained before NORMAL (PrioritizedTaskQueue.kt:129-131); engine locks (`withTextEngineLock`) serialize local engines; GLENS stateless (parallel tiles ×4) | OcrRepositoryImpl.kt:200,426,486,554 |

**Key finding: chapter N image loading and chapter N+1 image loading never
share a queue** — each `HttpPageLoader` is a separate instance with its own
`PriorityBlockingQueue` + worker coroutine. Contention is at the **network
layer** (shared OkHttp client) and the **disk layer** (shared
DiskLruCache), not in-thread.

### 2.2 Proposed isolation (minimal)

1. **Chapter N+1 image prefetch**: no new queue needed. Reuse the existing
   per-chapter `HttpPageLoader` (already exists after `preload(N+1)` runs).
   Add a bounded image prefetch (p0 + next 3) submitted through that
   loader's own `loadPage` path as ADJACENT-priority entries. N+1 worker
   is a separate coroutine → N's worker/timing is structurally untouched.
2. **Network protection**: cap N+1 image prefetch at 4 pages, launch it
   *after* N's `loadChapter` succeeds and only when N is in steady state
   (not mid-transition). OkHttp's per-host pool then naturally bounds
   N+1 to idle connection slots. Optional guard: check
   `ConnectivityManager` metered state → skip prefetch on cellular
   (see §3.3).
3. **OCR isolation (only if N+1 OCR prefetch is ever added)**: add a
   `LOW` tier to `PrioritizedTaskQueue` (`ArrayDeque` + drain order
   HIGH→NORMAL→LOW; `isIdle` + restart logic unchanged, ~15 lines,
   existing test file `PrioritizedTaskQueueTest` already covers 2 tiers —
   extend). N's TTS current-page stays HIGH; TTS prefetch stays NORMAL;
   N+1 OCR goes LOW, never preempts N.
   **Recommendation: DO NOT add N+1 OCR prefetch in Phase 1.**
   Rationale: TTS only prefetches 2–3 pages ahead *within* the active
   chapter; on chapter advance TTS's own N+1 p0 acquire + prefetch
   re-schedules automatically (BUG-005 fixed reset/cancel semantics).
   Reader-open OCR prefetch (Stage 4P) already fires for N+1 p0 at
   `loadNewChapter`/`loadAdjacent` success (ReaderViewModel.kt:594).
   Pre-OCR'ing N+1 while N is active spends GLENS capacity + OcrImage
   memory (pixels IntArray × N+1 pages) that the user may never read.
   Add LOW tier + N+1 OCR only when device evidence shows N+1 first-page
   TTS latency matters after image prefetch lands.
4. **Bitmap memory**: OCR bitmaps are task-owned + recycled in `finally`
   (TtsPlaybackController.kt:763-765, OcrRepositoryImpl useBitmap). N+1
   image prefetch reuses the standard page `stream = { input() }`
   pattern — decode happens lazily by the viewer only when N+1 becomes
   active, so no concurrent bitmap residency for N+1.
5. **TTS prefetch**: untouched (rules §6 bounded depths already ship).
   N+1 prefetch pipeline must NOT touch `TtsPlaybackController`.

---

## 3. Cache & Data Safety Evaluation

### 3.1 `ChapterCache` / `DiskLruCache`

- Location `cacheDir/chapter_disk_cache`, app-version 1, **100 MiB cap**
  (ChapterCache.kt:33-38, 213: `PARAMETER_CACHE_SIZE = 100L*1024*1024`),
  value count 1. Single `DiskLruCache` instance (thread-safe internally;
  jakewharton lib).
- Layout: per-chapter JSON page-list entry + per-image `md5(url).0` blob.
- Eviction = LRU on 100 MiB. Manga chapter image ≈ 0.5–3 MB (varies by
  source) → cap holds roughly 30–200 image entries. Prefetching N+1
  (≈ 0.5–6 MB) evicts at most ~5–10 oldest N-entry images — re-fetchable,
  no correctness impact. **Safe.**
- Risk H3: `putImageToCache` calls `diskCache.flush()` per image
  (ChapterCache.kt:160). 4 consecutive N+1 writes = 4 journal flushes on
  the same disk I/O path N's reader stream opens use. Measured-acceptable
  (existing behavior already does this for N's own 4-preload); no change
  required.

### 3.2 OCR cache (disk)

`OcrCacheDatabase` (SQLDelight, separate from ChapterCache): delete-if-
outdated + 5000-page prune, `getPage` filters `ocr_model` (BUG-004 fixed,
no migration). N+1 OCR prefetch **not in Phase 1** → zero OCR-disk impact.

### 3.3 Network consumption

- +1 page-list fetch per N+1 (unless `preload` already ran earlier —
  transition holders fire it on approach; idempotent via
  `State.Loaded/Loading` guard, ReaderViewModel.kt:641-643).
- +≤4 image downloads per N+1 prefetch (typical chapter 0.5–6 MB).
- **Bounds recommendation**: max 1 chapter ahead, ≤4 images, skip when
  `connectivityManager.isActiveNetworkMetered` (metered cellular), skip
  entirely for local/downloaded sources (pages already on disk —
  `DownloadPageLoader`/archive make prefetch redundant).

---

## 4. Proposed Code Hooks (insertion points, no code written)

All triggers live in `ReaderViewModel`; loader plumbing in `ChapterLoader`/
`HttpPageLoader`. Cancellation anchored to existing job patterns
(`readerOpenPrefetchJob` precedent, ReaderViewModel.kt:416-422).

### 4.1 `ReaderViewModel.kt`

| Hook | Location | Action |
|---|---|---|
| New property | near `readerOpenPrefetchJob` (:416) | `private var nextChapterImagePrefetchJob: Job? = null` + `private var nextChapterImagePrefetchedId: Long? = null` (one-shot-per-chapter gate, `ReaderOpenPrefetchGate` pattern) |
| **Trigger** | end of `loadChapter` success path (:572, inside `withUIContext` or just after) — only when `newChapters.nextChapter != null` | `viewModelScope.launchIO { prefetchNextChapterImages(newChapters.nextChapter) }`: (a) ensure N+1 loader exists (`preload(next)` if `state` not `Loaded` — reuses §1.2 path), (b) `loadPage(p0)` via N+1 `HttpPageLoader` (ADJACENT-priority enqueue for p1..p3 auto-follows via `preloadNextPages`, HttpPageLoader.kt:106) |
| Metered guard | inside the trigger | `Injekt.get<ConnectivityManager>().isActiveNetworkMetered` → skip (logcat DEBUG only, no user string) |
| Cancel on chapter change | `loadNewChapter` (:585, beside `readerOpenPrefetchJob?.cancel()`) | `nextChapterImagePrefetchJob?.cancel()` — old N+1 prefetch is now N+2 at best; gate re-arms on new chapter id |
| Cancel on destroy | `onCleared` (:354) | `nextChapterImagePrefetchJob?.cancel()` (viewModelScope cancels it anyway; explicit = deterministic, matches `readerOpenPrefetchJob` discipline); `onActivityFinish` (:382) = no-op, TTS stop path unchanged |
| TTS interplay | `loadAdjacent` (:612) — no change needed | TTS AdvanceChapter already flows through `loadNextChapter` → `loadAdjacent`; trigger at `loadChapter` success covers both user-swipe and TTS-driven transitions uniformly |

### 4.2 `ChapterLoader.kt` / `HttpPageLoader.kt`

| Hook | Location | Action |
|---|---|---|
| Prefetch entry point | new `ChapterLoader.prefetchFirstPages(chapter: ReaderChapter, count: Int = 4)` (~5 lines) | if `chapterIsReady` (loader+pages exist), call `chapter.pageLoader.loadPage(pages.first())` — the page list + p0 image land in `ChapterCache`; p1..p3 ADJACENT enqueue happens inside `loadPage` automatically (HttpPageLoader.kt:102-106). No `PageLoader` API change (loadPage already exists; `DownloadPageLoader`/archive impls are no-ops for images → naturally skip) |
| No change | `HttpPageLoader` | queue semantics untouched — N+1 runs in its own worker; N's queue untouched |
| OCR LOW tier (DEFERRED) | `PrioritizedTaskQueue.kt:17-20,93-96,129-131` + `OcrModels.kt:39` + `OcrRepositoryImpl.kt:627` | add `Priority.LOW` + `OcrScanPriority.LOW` — ONLY when N+1 OCR prefetch is authorized (see §2.2.3). Extends `PrioritizedTaskQueueTest` |

### 4.3 Invariants preserved (rules §1-§2)

- N's loader/queue/pages never touched by N+1 work (separate instances).
- TTS controller: zero changes Phase 1.
- `preload`/`requestPreloadChapter` paths unchanged (idempotency guard).
- No new deps, no i18n strings (no user-facing UI), no DB schema.
- Gate: one chapter, ≤4 images, non-metered, remote source only.

---

## 5. Audit verdicts (per scope letter)

- **A (lifecycle)**: §1.1 + §4.1. `State.Loaded` set in `ChapterLoader`
  (:60); adjacent chapters in `ViewerChapters`; preload triggers listed §1.2.
- **B (loading infra)**: §1.1 + §1.3. Page-list resolve = cache-first
  (HttpPageLoader.kt:71-79); images = per-page worker; "next 4" = ADJACENT
  preloads (:155-169).
- **C (queues)**: §2.1. No background/LOW tier today; LOW tier = deferred
  add (§4.2). N+1 image prefetch needs no new tier (loader-per-chapter).
- **D (OCR/TTS intersect)**: §2.2.3. H2 quantified (0.615 s p0 HIGH wait,
  Stage 4P); N+1 image prefetch interacts with OCR bitmap acquisition ONLY
  through disk I/O (ChapterCache) + GLENS capacity — memory safe
  (task-owned bitmaps, §2.2.4).
- **E (cache/memory)**: §3.1-3.3. 100 MiB LRU cap; safe eviction; +0.5–6 MB
  network per prefetch; metered guard recommended.
