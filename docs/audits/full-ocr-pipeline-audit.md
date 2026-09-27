# Full OCR Pipeline Audit — queue, prefetch, speech, exclusion, fallback

Date: 2026-09-27 · **READ-ONLY: no source changed, no gate run, no commit.**
HEAD at audit time: `b9d74b12e`. Pre-existing uncommitted work left untouched.

## 0. Method and evidence base

Code read in full: `OcrScanManager`, `OcrScanJob`, `OcrChapterScanner`, `OcrScanStore`,
`OcrScanQueueModels`, `OcrQueueScreenModel`, `PrioritizedTaskQueue`, `OcrRepositoryImpl`,
`OcrCacheStore`, `OcrEngineLocks`, `PpOcrV5Engine`, `HybridOcrEngine`, `GlensOcrEngine`
(the paths that matter), `PpOcrCtcDecode`, `PpOcrPreprocess`, `PpOcrDbPostprocess`, `OcrQuality`,
`OcrTextSanitizer`, `OcrExclusionMatcher`, `OcrModels`, `TextPostprocessor`, `TtsPlaybackController`,
`SentenceSegmenter`, `SpeechPipeline`, `SpeechCleaner`, `SpeechRegionClassifier`, `SpeechRegionFilter`,
`OcrPageSourceResolver`, `OcrPageSourceGatewayImpl`, `data/.../ocr_cache.sq`.

Device evidence, measured against the two most recent captures:

| Capture | Size | Content |
|---|---|---|
| `.device-pass/logcat-20260927-run2.log` | 302 MB | PP-OCRv5 + ADAPTIVE run, GLENS escalations, TTS failures |
| `.device-pass/logcat-20260927-ppocr-fixed.log` | 306 MB | same build, earlier window |
| `.device-pass/logcat-20260926-202430.log` | 19 MB | GLENS-only run, exclusion-matcher benchmark |
| `.device-pass/logcat-20260926-192424.log` | 30 MB | GLENS-only run, queue/prefetch contention |

Per `rules.md` §7 no page text is quoted. Only lengths, counts, timings and error classes.

## 1. The single root cause behind three of the four reported symptoms

`OcrChapterScanner.scanChapter` **deletes the chapter's entire OCR cache before it starts, and
deletes it again if anything goes wrong.**

```kotlin
// OcrChapterScanner.kt:69 — scan start
clearCachedChapterOcr.await(chapterId)

// OcrChapterScanner.kt:105 — network drops mid-chapter
clearCachedChapterOcr.await(chapterId)

// OcrChapterScanner.kt:209 — handleUnexpectedFailure
clearCachedChapterOcr.await(chapterId)
```

`ClearCachedChapterOcr` → `OcrRepositoryImpl.clearCachedChapter` → `OcrCacheStore.clearChapter` →
`deleteChapterPages`. The SQL has no model filter either, so the wipe removes every OCR model's rows:

```sql
-- data/src/main/sqldelight-ocr/tachiyomi/data/ocr/ocr_cache.sq:59-61
deleteChapterPages:
DELETE FROM ocr_pages
WHERE chapter_id = :chapterId;
```

Consequences, in order of blast radius:

1. **A chapter that ends in `ERROR` has zero cached pages.** Whatever the run had already OCR'd is
   destroyed by `handleUnexpectedFailure`. Read-Aloud on that chapter then pays a cold network OCR
   round trip for *every* page.
2. **A background scan wipes the cache out from under an active Read-Aloud session** on the same
   chapter, because the two paths share one cache and one table.
3. **`resume()` re-queues the ERROR entry, which re-wipes the cache** — the failure loop is
   self-sustaining. See §2 for the state machine.
4. `ClearCachedChapterOcr` is called from exactly three places in the tree, all three in
   `OcrChapterScanner` (verified by grep). There is no other caller, so no caller compensates.

Device proof (`logcat-20260927-run2.log`, pid 3554, chapter 4563):

```
19:28:04.437  TTS OCR acquisition null (timeout after 30000ms or scan failed) chapter=4563 page=1
19:28:04.437  TTS OCR timeout page=1 chapter=4563; advancing gracefully
19:29:38.235  TTS scanPageOcr await start chapter=4563 page=1 priority=HIGH
19:30:03.162  TTS scanPageOcr await end   chapter=4563 page=1 elapsedNs=24934618848  (24.9 s)
19:30:07.850  OcrChapterScanner: mihon.domain.ocr.exception.OcrException$ConnectionError:
              Failed to process: unable to connect            ← cache wiped, entry → ERROR
19:32:21.822  TTS start chapter=4563 page=1
19:32:21.937  TTS OCR cache miss chapter=4563 page=1          ← because of the wipe
19:32:51.943  TTS scanPageOcr await end chapter=4563 page=1 elapsedNs=29265967078   (29.3 s)
19:32:51.943  TTS OCR acquisition null … chapter=4563 page=1
```

Same page, same chapter, three consecutive session deaths: 19:28:04, 19:30:03, 19:32:51. The
`OcrException$ConnectionError` line is the chapter scanner reporting the identical GLENS failure —
the two paths fail together, and the scanner's failure handler is what destroys the cache the
reader needed. Chapter 8936 page 2 repeats the identical pattern at 17:47:03, 17:48:31, 17:49:23.

**`app/src/test/java/eu/kanade/tachiyomi/data/ocr/OcrChapterScannerTest.kt` mocks
`ClearCachedChapterOcr` as `relaxed = true` and never verifies it.** The wipe is completely untested.

---

## 2. Area 1 — queue state machine: four independent ways to get stuck

### F1.1 `ERROR` is terminal. Nothing ever retries it.

```kotlin
// OcrScanManager.kt:208-278 runPendingQueue
while (true) {
    val chapterId = markNextQueuedChapterScanning() ?: break   // no QUEUED entry → exit
    …
}
return processedAny                                            // true if anything ran
```

```kotlin
// OcrScanJob.kt:54-55
ocrScanManager.runPendingQueue()
Result.success()
```

`processedAny` is `true` whenever a chapter was attempted, so the worker returns `success` even
when every remaining entry is `ERROR`. WorkManager will not retry. `startWorkerIfNeeded()` is only
reached from `enqueue`/`resume`/`cancelQueuedChapters`, and `startIfPending()`:

```kotlin
// OcrScanManager.kt:45-49
fun startIfPending() { … }
```

**has zero call sites in the tree** (verified by grep across `app/src/main`). An all-`ERROR` queue is
therefore inert until the user manually hits resume. The notification's remaining-count actively
hides this, because errors are excluded from it:

```kotlin
// OcrScanQueueModels.kt:32-33
val remainingChapterCount: Int get() = entries.count { it.state != OcrScanEntry.State.ERROR }
```

### F1.2 The `ERROR` state survives process death and blocks the queue behind it.

```kotlin
// OcrScanStore.kt:53-64 OcrScanStoreSerializer.restore
entry.copy(
    state = if (entry.state == OcrScanEntry.State.SCANNING) QUEUED else entry.state,
    lastError = null,
)
```

`SCANNING` is remapped to `QUEUED` (correct) but `ERROR` is persisted verbatim by
`serialize` and restored as `ERROR`. Combined with F1.1: a chapter that failed once is
permanently, permanently failed across restarts. And because `markNextQueuedChapterScanning` picks
`entries.firstOrNull { QUEUED }`, a restored `SCANNING`→`QUEUED` entry blocks nothing, but with
no worker ever started (F1.3) it just sits there.

### F1.3 A `QUEUED` entry can exist with no worker, forever.

`startWorkerIfNeeded()` calls `OcrScanJob.start` → `enqueueUniqueWork(TAG, KEEP, …)`. If the worker
is RUNNING at that moment, KEEP **drops the request**. `runPendingQueue` can then break out of its
loop a millisecond later, leaving the newly added entry `QUEUED` with nothing to run it. There is no
`startIfPending()` call to recover, and no periodic reconciliation. Same shape after a process
restart: `OcrScanManager`'s initial state comes from `store.snapshot()` (line 27) and nothing starts.

### F1.4 A "successful" chapter scan can be silently partial.

```kotlin
// OcrChapterScanner.kt:152-164
} else { skippedPages++ }
…
if (skippedPages > 0) { logcat(WARN) { "… finished with $skippedPages/$totalPages pages skipped" } }
onComplete(lastProgress)
true                       // ← returns true regardless of skippedPages
```

A page that times out, throws, or fails to decode is counted and skipped, and the chapter is then
reported complete → `OcrScanManager.removeChapterEntry` → the entry leaves the queue. The user sees
"done"; the cache holds a subset of the pages; Read-Aloud re-OCRs the missing ones on demand. This
behaviour is **pinned by tests**: `OcrChapterScannerTest.failedPageIsSkippedAndScanContinues` and
`timedOutPageIsSkippedAndScanContinues` both assert the scan "continues", i.e. returns true.

### F1.5 Unbounded awaits inside the chapter-scan loop.

```kotlin
// OcrChapterScanner.kt:122
bitmap = page.openBitmap()                    // NO timeout, NO withTimeoutOrNull
// OcrChapterScanner.kt:128
pageScanned = withTimeoutOrNull(pageScanTimeout) { scanPageOcr.await(…) } != null
// OcrChapterScanner.kt:238
internal val PAGE_SCAN_TIMEOUT = 90.seconds
```

`pageSourceResolver.resolve(manga, chapter)` (line 72) is also unbounded. `openBitmap()` reaches
`OcrPageSourceResolver.openRemotePageBitmap` (line 195-335), which calls `source.getImageUrl(page)`
(line 214) and `source.getImage(page)` (lines 263/298) with *the source's own* timeouts, not the
OCR layer's. Measured on device: `openBitmap end … elapsedNs=7145982154` (7.1 s) for page 2 while
page 1 took 0.45 s. One stalled source call stalls the whole 16-page scan forever.

### F1.6 The 90 s page timeout does not stop the work it times out on.

`PrioritizedTaskQueue` detaches each task into `scope.launch` **by design** and runs it to
completion regardless of whether the submitter still awaits:

```kotlin
// PrioritizedTaskQueue.kt:160-187
// Launch instead of running inline … an abandoned await never cancels the running scan.
scope.launch { try { task() } finally { activeTasks--; … } }
```

So when `withTimeoutOrNull(90s)` fires, the GLENS upload keeps running and keeps holding a queue
slot; the scanner counts the page as skipped and moves on. A chapter where every page stalls runs
`16 × 90 s = 24 min` while the orphaned tasks saturate the 2 background slots. This is the
"5+ minutes, and sometimes it never finishes" shape.

### F1.7 Duplicate-slot leak when the in-flight owner is cancelled.

```kotlin
// OcrRepositoryImpl.kt:278-296
try { if (owner) { val r = dispatchScan(…); deferred.complete(r); r } else deferred.await() }
catch (error: Throwable) { deferred.completeExceptionally(error); throw error }
finally {
    if (owner) {
        inFlightMutex.withLock {                 // ← in an already-cancelled coroutine
            if (inFlightScans[key] === deferred) inFlightScans.remove(key)
        }
    }
}
```

`Mutex.lock()` delegates to `tryLock()`, which does **not** check cancellation; when the mutex is
contended it falls to `lockSuspend`, which *does* and throws. So under contention the map entry
survives a cancelled owner. Two effects: the stale `CompletableDeferred` is already
`completeExceptionally(CancellationException)`, so any later joiner is cancelled by *another*
coroutine's cancellation; and the key stays occupied, so a legitimate re-scan is forced onto a
duplicate path. This is a narrow window but it is real and it is a duplicate-scan generator.

### Area-1 summary

Nothing in the chain is a lock inversion. `OcrEngineLocks.withAllLocks` takes
`fast → glens → owocr → detection` while scans take at most one of those, and
`PpOcrV5Engine.mutex` is never held across another lock. The stalls are **state-machine**
failures plus **unbounded network waits**, not deadlocks.

---

## 3. Area 1b — why a 16-page chapter takes 5+ minutes

Three multiplicative costs, all measured on device:

1. **The chapter scan is fully serial.** `OcrChapterScanner.kt:102` is a `for` loop with one
   `scanPageOcr.await` inside. No intra-chapter parallelism, so the queue's 3-slot concurrency buys
   nothing for a single chapter.
2. **Every page is a 13-tile GLENS upload.** `isTallStrip` (`GlensOcrEngine.kt:142-143`,
   `height > width*3 && height > 1500`) is true for essentially every real page in these captures:

   ```
   logcat-20260926-202430.log:  tiled=true × 89, tiled=false × 14
   logcat-20260927-run2.log:    tiled=true × 20 of 20 pages
   tiles per page:              13   (six distinct scans, all 13)
   ```

   13 tiles at `TILE_CONCURRENCY = 4` and a 2.6-7 s median upload ⇒ 8-23 s of GLENS per page.
   Sixteen pages ⇒ **2.6-6 minutes of pure upload**, before image downloads.
3. **Per-page re-resolution.** `TtsPlaybackController.scanOnDemand` calls
   `pageSourceResolver.resolve(ctx.manga, ctx.chapter)` for **every page** (line 720). Each call
   first runs `awaitDownloadedChapterPages` — `downloadManager.isChapterDownloaded(…, skipCache = true)`
   (line 99-106), a filesystem walk — and then takes `remoteResolveMutex`. Measured 200-891 ms per
   resolve. The page list itself is memoized, but **in a single slot keyed by chapter id**
   (`OcrPageSourceResolver.kt:149-165`), so a next-chapter prefetch interleaving with the current
   chapter evicts it and every resolve goes back to the network.

Observed totals: `TTS page=1 … acquireMs=26028` and `acquireMs=7514` in the same run; the 09-26
GLENS-only run recorded `acquireMs` in the 17-42 ms range on cached pages, confirming the cost is
entirely the cold path.

---

## 4. Area 2 — "c-h-a-n-g-e-s": TTS spells letters because the *regions* are single letters

The existing mitigation is real and works, but it operates on the wrong axis.

```kotlin
// OcrTextSanitizer.kt:15
private val SPACED_LETTER_RUN = Regex("(?<![A-Za-z])[A-Za-z](?:[ ]+[A-Za-z])+(?![A-Za-z])")
```

It merges `N E T W O R K` → `NETWORK` **inside one region string**. It cannot help when the letters
are *separate regions*, and on the local engine they are.

The chain, every link verified in code:

1. `PpOcrDbPostprocess.boxes` emits **one box per 8-connected component** of the DB probability map
   (`PpOcrDbPostprocess.kt:32-81`). Tracked display type and `binaryThreshold = 0.3f` split glyphs
   into separate components.
2. `minSideFraction = 0.003f` (`PpOcrDbPostprocess.kt:133`) is ~3 px on a 1000 px page, so a
   **single glyph box is not filtered out** — the guard is meant to drop noise, and a lone letter is
   above it.
3. `HybridOcrEngine.recognizeLocally` (line 126-141) crops and recognises each box independently.
4. `PpOcrPreprocess.recognitionInputSize` clamps width to `[48, 320]` (`PpOcrPreprocess.kt:46`). A
   crop narrower than it is tall — i.e. exactly a single glyph — is clamped **up** to 48 and then
   stretched to a square by `normalize`:

   ```kotlin
   // PpOcrV5Engine.kt:287-291
   val scaled = if (image.width == width && image.height == height) image
                else Bitmap.createScaledBitmap(image, width, height, true)
   ```

   Device measurement of all 1098 recognition calls in the ADAPTIVE run:

   ```
   in=48x48  × 270   (24.6 %)   ← the square / single-glyph bucket
   in=72x48  × 107
   in=80x48  × 105
   in=64x48  × 105
   in=56x48  × 100
   … 27 distinct widths
   ```

   A 48x48 input gives the CTC head `48 / 8 = 6` timesteps for one character.
5. `PpOcrCtcDecode` therefore returns **one character per such region**
   (`PpOcrCtcDecode.kt:51-67`).
6. `SpeechPipeline.toSpeakableSentences` runs `mergeSpacedSingleLetters` **per region**
   (`SpeechPipeline.kt:37`). A one-character region contains no spaced run — the regex needs a
   separator — so the sanitizer is a no-op.
7. `SentenceSegmenter` is explicitly forbidden from merging:

   > `// A region boundary is a hard boundary: sentences never merge across regions.`
   > `// SentenceSegmenter.kt:19-20`

8. Seven glyph regions become seven `TtsSentence`s and seven `engine.speak()` calls
   (`TtsPlaybackController.kt:424-433`) — Android TTS has no other option but to spell them.

**The quality router cannot see this.** `OcrQualityRouter.MIN_CHARS_PER_REGION = 1.5f` is compared
against a **page mean** (`OcrQuality.kt:99` `meanCharsPerRegion = characters / regions.size`). A page
with 12 full lines and 6 lone glyphs averages 10 chars/region and passes every gate. There is no
per-region minimum anywhere. Device confirmation: 5 pages were accepted locally with 9-18 regions
each, and the dispatch-length histogram from that run is dominated by short utterances —
`textLen=2` ×8, `textLen=3` ×5, `textLen=4` ×11 out of 131 dispatches.

Secondary contributor, same family: `PpOcrPreprocess` also **squashes** any crop wider than
aspect 6.67 (width clamped to 320 with height pinned at 48), which is a real quality loss on long
bubbles and a plausible source of spurious `SPACE_CLASS` emissions — but that path *is* already
covered by `mergeSpacedSingleLetters`, which is why it is not the reported symptom.

---

## 5. Area 3 — duplicate words, and exclusion matching

### F3.1 Tile seams duplicate text on every page (this is the duplicate-word source)

```kotlin
// GlensOcrEngine.kt:190  overlapRatio = 0.2f          (TILE_OVERLAP_RATIO, line 1050)
// GlensOcrEngine.kt:196-208  13 overlapping tiles, TILE_CONCURRENCY = 4
```

With 13 tiles there are 12 overlap bands. Any bubble inside a band is recognised **twice**, at two
different tile-local positions. The only defence is position-only:

```kotlin
// GlensOcrEngine.kt:304-316
/** Drops seam duplicates from overlapping tiles (same physical text seen twice). */
val duplicate = kept.any { existing -> intersectionOverUnion(existing.boundingBox, region.boundingBox) >= 0.45f }
```

No text comparison. A bubble that the tile boundary cuts is seen by each tile as a *partial* box, so
the two boxes differ in both extent and IoU, and **both survive**. Measured drop counts:

```
OCR(glens) page postprocess  in=47 out=40      (7 dropped)
                            in=47 out=37     (10 dropped)
                            in=37 out=29      (8 dropped)
                            in=21 out=16      (5 dropped)
                            in=19 out=19      (0 dropped)
```

7-10 drops per page is the seam band being *partially* cleaned. Whatever falls under IoU 0.45 is
spoken twice, or a bubble is split into two half-bubbles that are both spoken.

The downstream `SpeechPipeline.dedupeOverlappingDuplicates` (`SpeechPipeline.kt:47-61`) compares
**exact normalised text plus any box overlap**, so it only rescues cases where the two halves came
back byte-identical. Truncated or differently-punctuated halves are not caught. Note it runs twice —
once in `TtsPlaybackController.kt:661` and again inside `toSpeakableSentences`
(`SpeechPipeline.kt:31`) — so a *first* region that was already dropped is not reconsidered.

### F3.2 `GlensOcrEngine.dedupeOverlapping` can also delete real text

It drops any later region overlapping an earlier one at IoU ≥ 0.45 **regardless of text**. Because
`mergeIntoBubbles` (line 717-766) builds cluster boxes as the union AABB of all member lines, a
cluster's box routinely overlaps a neighbouring bubble's box. A small bubble nested in a large one
is deleted outright. This is the `docs/audits/ppocrv5-hybrid-audit.md` "D4" item, still open, and it
is the mirror image of F3.1: the same threshold both misses duplicates and creates false deletions.

### F3.3 Ordering: the JP branch reorders regions, `SentenceSegmenter` cannot repair it

`DEFAULT_CLIENT_LANGUAGE = "ja"` / `DEFAULT_CLIENT_REGION = "Asia/Tokyo"`
(`GlensOcrEngine.kt:1044-1045`) are hard-coded. One stray kana on an English page flips the whole
page into the JP pipeline (`parseResponsePage:467-484`), which returns
`filteredVertical + filteredHorizontal + nonJpLines` — vertical-JP first, and `mergeIntoBubbles`
(`GlensOcrEngine.kt:728-729`) again returns `verticalGroup + horizontalGroup`. `OcrRegion.order` is
then assigned by `mapIndexed` and is **no longer reading order**. `SentenceSegmenter` consumes
regions in stored order and never re-sorts (`SentenceSegmenter.kt:17-18`), so the words are spoken
in the wrong order. `filterRuby` (line 668-715) can additionally delete a line outright.

This is still a **HYPOTHESIS about the device symptom** — the code path is confirmed, the frequency
is not measured, because no capture contains Japanese-mixed English pages at a known rate.

### F3.4 `OcrExclusionMatcher` — is it filtering correctly?

**Mechanically: yes, and the O(n³) fix held.** 30 dispatches on `logcat-20260926-202430.log`:
exclusion match median 7.6 ms / p90 17.8 ms / max 74.0 ms, 0.1% of `acquireSentences` (was 76.9%).

**Semantically: it only filters what the user authored, so headers/footers/garbage are not its
job.** Confirmed: exclusion zones are user-created only, there are no seeded/built-in rules. A
watermark or scanlation credit is excluded only if the user drew a rule for it.

Two real gaps:

- **`getCachedChapterIds` has no model filter**, unlike `getPage`:

  ```sql
  -- ocr_cache.sq:40-46  (model-filtered)
  getPage: … WHERE chapter_id = :chapterId AND page_index = :pageIndex AND ocr_model = :ocrModel

  -- ocr_cache.sq:48-51  (NOT model-filtered)
  getCachedChapterIds: SELECT DISTINCT chapter_id FROM ocr_pages WHERE chapter_id IN ?;
  ```

  `GetCachedChapterIdsOcr` therefore reports a chapter as cached when its pages are cached under a
  *different* model. It gates `ReaderViewModel.maybePrefetchNextChapterOcr` and the Continue-screen
  badge. Effect: **after switching the OCR model, every chapter looks cached, so next-chapter
  prefetch is skipped entirely** and the reader lands on a fully cold chapter. The mirror image,
  F1, is that the wipe is also model-blind.
- `OcrExclusionMatchType.ZONE` rules with a null `pageIndex` are **dormant by design**
  (`OcrExclusionMatcher.kt:52-59`, documented). Legacy rows do nothing.

---

## 6. Area 4 — engine fallback and recovery

### F4.1 Fallback itself is correct. The state it leaves behind is not.

`scanLocalOrFallback` (`OcrRepositoryImpl.kt:396-434`) catches every non-cancellation throwable and
hands the page to GLENS, gated on `useFallbackModelsPref`. `HybridOcrEngine.runCatchingLocal`
(line 108-116) does the same and escalates whole-page. `HybridOcrEngine` is exception-safe: no
`Mutex.withLock` is held across the cloud call. **No deadlock, no slot leak on this path** — that
conclusion is confirmed, matching `docs/memory.md` 2026-09-27.

Three defects around it:

1. **Unsynchronised engine construction — duplicate ORT sessions, leaked native memory.**

   ```kotlin
   // OcrRepositoryImpl.kt:170-174
   private fun ppOcrEngine(): PpOcrV5Engine {
       return ppOcrEngine ?: PpOcrV5Engine(ppOcrModelDirectory, textPostprocessor).also { ppOcrEngine = it }
   }
   ```

   No lock, no `lazy`. `PrioritizedTaskQueue` runs up to 3 tasks concurrently, so three callers can
   each construct a `PpOcrV5Engine`, each of which calls `createSessions()` and allocates **two ORT
   sessions** (line 203-242). `closeEngines()` (line 791-809) closes only the field's current
   instance; every loser is never closed and its native memory is never freed. The same
   check-then-assign pattern exists for `glensEngine` (lines 148, 158, 487), `fastEngine` (152),
   `owOcrEngine` (162) and `detEngine` (189). The 2026-09-27 build already fixed the *symptom* of
   this class of bug (sticky `initializationFailure`) on a single instance; the creation race itself
   remains.

2. **A GLENS result gets cached under the *local* model's key.**

   ```kotlin
   // OcrRepositoryImpl.kt:335-342 → 426-432
   OcrModel.PPOCR -> scanLocalOrFallback(… modelKey = modelKey, type = EngineType.PPOCR …)
   //   catch → scanWithGlens(… modelKey = modelKey …)   ← still PPOCR
   ```

   After one local failure every subsequent `getPage(chapter, page, ocrModel = PPOCR)` **hits** and
   returns cloud text. The local engine is never retried for that chapter, and the cached rows are
   indistinguishable from genuine local results. Same shape in `scanAdaptive`, except there it is
   deliberate and documented (line 645-650).

3. **The ADAPTIVE escalation bypasses the retry policy.**

   ```kotlin
   // OcrRepositoryImpl.kt:662-666
   val page = engine.recognizePage(bitmap) {
       val cloud = engineFor(EngineType.GLENS) as GlensOcrEngine
       cloud.recognizePage(bitmap).regions
   }
   ```

   `scanWithGlens` gives a non-tiled scan exactly one retry on HTTP 5xx/429
   (`isTransientHttpFailure`, line 437-440). This closure calls `recognizePage` **directly**, so an
   escalated page gets no page-level retry at all. Per-tile retry exists for tiled pages
   (`executeRequestWithRetry`, line 279-302), so the exposure is exactly the non-tiled path — and
   the non-tiled path is the *fast* one, where a transient 500 kills the page. This is very likely
   the 09-25 "HTTP 500 no-retry" WATCH item, still open.

4. **`PpOcrV5Engine`'s single `mutex` (line 58) serialises the whole local engine** — both detection
   and every recognition call. The queue's 3-slot concurrency is therefore worth **1** on the local
   path. This is correct (the scratch buffers are shared) but it means the local path cannot
   overlap at all, and the 89.7% escalation rate means 89.7% of pages pay local-serial time *and
   then* a full GLENS round trip.

5. **`performDeferredCleanupIfIdle` reads four locks non-atomically** (line 747-755):
   `taskQueue.isIdle()` then `hasActiveOperations()` then `hasActiveScanSessions()`, each under a
   different mutex, then `closeEngines()` + `cacheStore.close()` outside all of them. A scan can
   start in the gap. Self-healing in practice (`getDatabase()` recreates a closed handle,
   `OcrCacheStore.kt:182-191`; engines are re-nulled and rebuilt), but it is a real TOCTOU and it
   closes a database under an in-flight reader.

### F4.2 Split default on one preference key

```kotlin
// OcrPreferences.kt:15           — what the UI shows
fun ocrModel() = preferenceStore.getEnum("pref_ocr_model", OcrModel.GLENS)

// OcrRepositoryImpl.kt:41        — what the engine actually dispatches on
private val ocrModelPref = preferenceStore.getEnum("pref_ocr_model", OcrModel.LEGACY)
```

Same key `"pref_ocr_model"`, **two different defaults**. A fresh install displays *GLENS* in Settings
while the repository behaves as *LEGACY* until the user touches the dropdown. Behaviour currently
converges (LEGACY → `scanLocalOrFallback` → `UnavailableDetOcrEngine` throws
`DetectionUnavailable` → falls back to GLENS) but only via an exception + WARN log per page. The
`state.md` note "`OcrPreferences.ocrModel()` default LEGACY → GLENS" fixed the wrong one of the two.

---

## 7. Root-cause map

| # | Root cause | Location | Symptom it produces |
|---|---|---|---|
| R1 | Chapter scan wipes its own cache at start, mid-failure and on error | `OcrChapterScanner.kt:69,105,209` + `ocr_cache.sq:59` | ERROR ⇒ 0 cached pages ⇒ read-aloud permanently stalls on the same page |
| R2 | `ERROR` is terminal, survives process death, hidden from the remaining-count | `OcrScanManager.kt:211,273`; `OcrScanJob.kt:54`; `OcrScanStore.kt:56`; `OcrScanQueueModels.kt:32` | Failed chapters never retried; queue looks idle |
| R3 | `startIfPending()` has no callers; `KEEP` drops a start request for a finishing worker | `OcrScanManager.kt:45`; `OcrScanJob.kt:73` | `QUEUED` entries with no worker, forever |
| R4 | A partial chapter scan reports success | `OcrChapterScanner.kt:157-164` | "Done" chapters with a partial cache |
| R5 | `openBitmap()` / `resolve()` unbounded; the 90 s guard detaches instead of cancelling | `OcrChapterScanner.kt:72,122,128`; `PrioritizedTaskQueue.kt:163` | Indefinite stall on page 1; 16 pages × 90 s = 24 min worst case |
| R6 | 13 overlapping GLENS tiles per page, 20% overlap, position-only IoU 0.45 dedupe | `GlensOcrEngine.kt:190,304-316` | Duplicated and truncated speech on every page |
| R7 | DB emits per-component boxes; single glyphs survive `minSideFraction`; width clamped to 48 → 48x48 crop; regions never merge | `PpOcrDbPostprocess.kt:32-81,133`; `PpOcrPreprocess.kt:46`; `PpOcrV5Engine.kt:287`; `SentenceSegmenter.kt:19` | "c-h-a-n-g-e-s" — TTS spells each letter |
| R8 | Hard-coded `ja` / `Asia/Tokyo` client locale | `GlensOcrEngine.kt:1044-1045` | Region reordering → words spoken out of order |
| R9 | `getCachedChapterIds` not model-filtered | `ocr_cache.sq:48-51` | After a model switch, every chapter looks cached → prefetch skipped |
| R10 | Unlocked `?:` engine construction; ADAPTIVE escalation bypasses retry; cloud cached under local key | `OcrRepositoryImpl.kt:170,662,335` | Duplicate ORT sessions, leaked native memory; 500 kills an escalated page |
| R11 | Split default on `pref_ocr_model` | `OcrPreferences.kt:15` vs `OcrRepositoryImpl.kt:41` | UI and engine disagree on a fresh install |

---

## 8. Remediation plan

Ordered by user-visible damage ÷ diff size. Each step names the test that must fail first.
**Nothing here is authorised yet** — `docs/implementation-roadmap.md` §B is unchanged by this audit.

### Stage 1 — stop the read-aloud stall (highest value, smallest diff)

1. **Delete R1's failure-path wipes.** Keep the scan-start wipe only if a rescan must be clean;
   otherwise drop all three and let `cacheStore.upsert` overwrite. R1 is the whole stall.
   *Test:* `OcrChapterScannerTest` — a chapter whose 3rd page throws must still leave pages 0-2 in
   the cache. Today `clearCachedChapterOcr` is `relaxed` and unverified, so nothing pins this.
2. **Delete R1's scan-start wipe too**, or scope it to the pages actually about to be re-scanned.
   Wiping the whole chapter is what makes a resume destructive. *Test:* scan chapter → cache exists →
   rescan → cache still exists.
3. **Make a failed on-demand page non-fatal (R2/F1.1).** `TtsPlaybackController.kt:582-588` logs
   *"advancing gracefully"* and then calls `fail(TtsError.OcrError)`, which sets `phase = Error` and
   returns null out of `runPlayback` — a hard session kill, not an advance. The log message is
   wrong about the code. Make it `return@withIOContext emptyList()` so `runPlayback` takes the
   existing `sentences.isEmpty()` → `advanceFromPolicy(pageHasText = false)` path.
   *Test:* `TtsOcrTimeoutGuardTest` has **no failure-path case** — add
   `failedPageScanAdvancesInsteadOfFailing`.
4. **Give `ERROR` a retry budget** (R2). Simplest: `runPendingQueue` returns whether work remains,
   and `OcrScanJob.doWork` returns `Result.retry()` while any entry is retryable and under the cap.
   *Test:* `OcrScanManagerTest` — a chapter that fails once is retried without user action.
5. **Call `startIfPending()`** on `OcrScanManager` init and after `runPendingQueue` exits with
   pending entries (R3). One line each; it also covers the process-restart case.
6. **Stop reporting partial scans as complete** (R4). `OcrChapterScanner` should return false when
   `skippedPages > 0`, or return a partial count the manager records. **This contradicts two
   existing tests** — `failedPageIsSkippedAndScanContinues` and
   `timedOutPageIsSkippedAndScanContinues` — so it needs explicit user sign-off per `rules.md` §10
   ("remove or weaken existing tests").

### Stage 2 — bound the unbounded waits (R5)

7. Wrap `page.openBitmap()` and `pageSourceResolver.resolve(...)` in the same
   `withTimeoutOrNull(PAGE_SCAN_TIMEOUT)` already used for the scan. `OcrChapterScanner.kt:72,122`.
8. Decide what the 90 s per-page timeout should mean. Today it *abandons* rather than cancels
   (`PrioritizedTaskQueue.kt:163`). Either make `submit` propagate cancellation into the task, or
   stop pretending the timeout bounds anything and say so in the KDoc. The current comment is
   "the code never cancels the running scan" — true, and the reason the guard does not bound
   anything.
9. Move `OcrPageSourceResolver`'s memo from one slot to a small `Map<Long, List<Page>>` (§3, cost 3)
   so a next-chapter prefetch stops evicting the current chapter's page list.

### Stage 3 — speech correctness (R6, R7)

10. **Fix the seam duplicates** (R6). Cheapest correct fix: make tile dedupe **text-aware** —
    compare the same normalised key `SpeechPipeline.duplicateTextKey` uses, and drop a later region
    when the keys match and the boxes are within the seam band. Fix it **in `GlensOcrEngine`** so
    it is cached correctly, not patched again in the speech layer.
11. **Stop `dedupeOverlapping` from deleting real text** (F3.2). Require text inequality to drop, or
    restrict it to boxes that straddle a tile boundary. Positional-only deletion at IoU 0.45 over
    union-AABB cluster boxes is unsafe.
12. **Fix "c-h-a-n-g-e-s" at the source** (R7). Two options, cheapest first:
    - *(a)* Raise `PpOcrDbConfig.minSideFraction` so a lone glyph box is dropped, and merge
      same-line adjacent boxes before recognition. ~15 lines in `:domain`, unit-testable on a
      synthetic probability map — no device needed.
    - *(b)* Add a region merge step that joins horizontally-adjacent single-character regions on the
      same baseline. More general, but it collides with the `SentenceSegmenter` "never merge across
      regions" rule from `prd.md` F2 and with tap-highlight, so it needs a design decision first.
    Option (a) is strictly better: it fixes the OCR, not the presentation of bad OCR. **Do not**
    lower `RECOGNITION_MIN_WIDTH` — a 48x48 stretch is what makes the single glyph unreadable in the
    first place.
13. Add a per-region minimum to `OcrQualityRouter` (currently page-mean only), so a
    glyph-fragmented page is visible in the router and in the logs.
14. **Decide the GLENS client locale** (R8). A pref with a `ja` default that matches the app's
    language is a one-line change; the region-reorder consequence then needs a device capture to
    confirm. Do not change it blind — the previous audit flagged it as needing evidence.

### Stage 4 — engine hygiene (R9, R10, R11)

15. `getCachedChapterIds` → add `AND ocr_model = :ocrModel` (R9). `:domain` + `:data` signature
    change; no `.sqm` needed (query only). **Then re-check `maybePrefetchNextChapterOcr`**, which
    will start prefetching chapters it previously skipped.
16. Wrap engine construction in the existing `operationMutex`, or make the five engine fields
    `by lazy` (R10). `closeEngines()` must close the instances that exist, not the field's.
17. Route the ADAPTIVE escalation through `scanWithGlens` so it inherits the one-retry policy (R10),
    instead of calling `recognizePage` on a raw engine reference.
18. Cache the escalation result under the key the *engine that produced it* used, or record the
    source in `OcrPageResult` — do not silently re-key cloud text as local (R10).
19. Delete one of the two `pref_ocr_model` defaults; keep `GLENS` in both (R11).

### Stage 5 — observability (do this first, it is one file)

20. The logs already carry every number needed (`acquireMs`, `elapsedNs`, `waitMs`, `textLen`,
    `in=WxH`, `in=N out=M`). Add three lines so a future audit does not need a 302 MB logcat:
    - per-region text length + region count in `TTS page=N segmented …` (would have made R7 obvious
      in one grep),
    - `skippedPages/totalPages` in the chapter-scan completion log (R4),
    - the failing `chapter/page` in the `ERROR` transition (R2).

---

## 9. Test gaps that let all of this through

| Gap | File |
|---|---|
| The cache wipe is mocked `relaxed` and never verified | `OcrChapterScannerTest.kt:130` |
| Partial scans are pinned as *success* | `OcrChapterScannerTest.failedPageIsSkippedAndScanContinues`, `timedOutPageIsSkippedAndScanContinues` |
| Only the success path is guarded; no failure case | `TtsOcrTimeoutGuardTest` (2 tests, both success) |
| No multi-region single-character case (the "c-h-a-n-g-e-s" shape) | `SpeechPipelineLetterMergeTest` (2 tests, single region only) |
| Tile-seam duplicate text is untested | `GlensTileMathTest` covers tile geometry, not seam dedupe |
| No `getCachedChapterIds` model-scoping test | — |
| `OcrScanManagerTest` has no ERROR-retry or stuck-QUEUED case | `OcrScanManagerTest` |

## 10. What this audit did **not** establish

- The **frequency** of R8 (JP-locale region reordering) on real pages. Code path confirmed, rate
  unmeasured — it needs a capture of Japanese-mixed English pages.
- Whether the 89.7% ADAPTIVE escalation rate changes once R7 is fixed. If single-glyph boxes are a
  large share of the 9 locally-accepted pages, the escalation rate is a *symptom* of R7, not
  evidence about the confidence floor. `state.md` already forbids lowering `0.80` without a
  local-vs-GLENS text comparison; this audit adds a second reason not to touch it yet.
- Wall-clock cost of the 302 MB captures' per-page timing under 13 tiles — the tiles-per-page count
  (13) is measured, the per-tile latency distribution for these specific runs is not.
- No gate was run. `spotlessCheck` / `testDebugUnitTest` are irrelevant to a docs-only change and
  the tree was not modified.
