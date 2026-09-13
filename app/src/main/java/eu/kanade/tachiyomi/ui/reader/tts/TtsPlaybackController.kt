package eu.kanade.tachiyomi.ui.reader.tts

import android.os.SystemClock
import eu.kanade.tachiyomi.data.ocr.OcrPageSourceResolver
import eu.kanade.tachiyomi.util.ocr.toOcrImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.domain.ocr.interactor.GetCachedPageOcr
import mihon.domain.ocr.interactor.GetOcrExclusionZones
import mihon.domain.ocr.interactor.ScanPageOcr
import mihon.domain.ocr.interactor.WithOcrScanSession
import mihon.domain.ocr.model.ExclusionMatchContext
import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.model.OcrScanPriority
import mihon.domain.ocr.model.applyExclusions
import mihon.domain.tts.TtsAdvanceAction
import mihon.domain.tts.TtsAdvancePolicy
import mihon.domain.tts.TtsSentence
import mihon.domain.tts.engine.TtsEngine
import mihon.domain.tts.engine.TtsFocusEvent
import mihon.domain.tts.service.TtsPreferences
import mihon.domain.tts.speech.SpeechClassificationConfig
import mihon.domain.tts.speech.SpeechPipeline
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.atomic.AtomicLong

enum class TtsPhase {
    Idle,
    Preparing,
    LoadingPage,
    Playing,
    Paused,
    Finished,
    Error,
}

enum class TtsError {
    EngineError,
    OcrError,
    NoTextFound,
    ChapterLoadFailed,
}

data class TtsPlaybackState(
    val phase: TtsPhase = TtsPhase.Idle,
    val pageIndex: Int = -1,
    val sentenceIndex: Int = -1,
    val sentenceCount: Int = 0,
    val sentenceText: String = "",
    val error: TtsError? = null,
)

sealed interface TtsEvent {
    data class AdvancePage(val pageIndex: Int) : TtsEvent
    data object AdvanceChapter : TtsEvent
    data class Failed(val error: TtsError) : TtsEvent
    data class ScrollToRegion(val pageIndex: Int, val bbox: mihon.domain.ocr.model.OcrBoundingBox) : TtsEvent
}

/** Everything the controller needs to play one chapter; supplied by ReaderViewModel. */
data class TtsChapterContext(
    val manga: Manga,
    val chapter: Chapter,
    val totalPages: Int,
    val hasNextChapter: Boolean,
)

/**
 * Orchestrates read-aloud playback: OCR text acquisition → sentence segmentation →
 * speech → advance policy. Never touches the viewer directly; page/chapter movement
 * is requested via [TtsEvent]s and confirmed back through [onPageSelected], so user
 * navigation always wins over automatic advancement.
 */
internal class TtsPlaybackController(
    private val scope: CoroutineScope,
    private val engine: TtsEngine,
    private val preferences: TtsPreferences,
    private val getCachedPageOcr: GetCachedPageOcr,
    private val scanPageOcr: ScanPageOcr,
    private val withOcrScanSession: WithOcrScanSession,
    private val pageSourceResolver: OcrPageSourceResolver,
    private val getExclusionZones: GetOcrExclusionZones,

    /**
     * Supplies a context for the CURRENTLY ACTIVE chapter. Re-queried on every queue
     * rebuild so chapter switches and user navigation never play against stale state.
     */
    private val provideContext: () -> TtsChapterContext?,
) {

    private val mutableState = MutableStateFlow(TtsPlaybackState())
    val state = mutableState.asStateFlow()

    private val eventChannel = Channel<TtsEvent>()
    val events = eventChannel.receiveAsFlow()

    /** Completed by [onPageSelected] with the index of the page actually shown. */
    private var pendingAdvance: CompletableDeferred<Int>? = null

    /** True while waiting for an advance confirmation; callers must not debounce those. */
    val hasPendingAdvance: Boolean
        get() = pendingAdvance != null

    private var context: TtsChapterContext? = null

    private var queue: List<TtsSentence> = emptyList()
    private var queuePageIndex = -1

    /** Sentence to resume from when playback continues. */
    private var resumeIndex = 0

    /** Page index we last started playback for; used to dedup redundant rebuilds. */
    private var lastRebuildPageIndex = -1

    @Volatile
    private var paused = false

    @Volatile
    private var stopped = false

    private var playbackJob: Job? = null

    private var prefetchJob: Job? = null

    /** Pages covered by the current/last prefetch job; guards redundant cancel/restart. */
    private var prefetchPages: IntRange? = null

    /** ElapsedRealtime marker set in [start]; first successful acquire logs startup latency. */
    private var sessionStartedAtElapsed = 0L

    /** Monotonic dispatch counter making every utterance id unique per relaunch. */
    private val dispatchCounter = AtomicLong()

    init {
        engine.onFocusEvent = { event ->
            when (event) {
                TtsFocusEvent.TransientLoss, TtsFocusEvent.PermanentLoss -> {
                    logcat(LogPriority.DEBUG) { "TTS audio focus lost ($event); pausing" }
                    pause()
                }
                TtsFocusEvent.Regained -> Unit // resume stays manual for transient loss
            }
        }
        preferences.ttsSpeechRate().changes().onEach { engine.setSpeechRate(it) }.launchIn(scope)
        preferences.ttsPitch().changes().onEach { engine.setPitch(it) }.launchIn(scope)
    }

    fun start(context: TtsChapterContext, startPageIndex: Int) {
        resetSession(stoppedFlag = true)
        this.context = context
        stopped = false
        paused = false
        sessionStartedAtElapsed = SystemClock.elapsedRealtime()
        mutableState.value = TtsPlaybackState(phase = TtsPhase.Preparing, pageIndex = startPageIndex)
        playbackJob = scope.launch { runPlayback(startPageIndex) }
    }

    fun pause() {
        // Pause in ANY active phase (Preparing/LoadingPage included): OCR
        // acquisition must not out-speak the user's pause or the focus-loss
        // pause. Idle/Finished/Error have nothing to pause; starting playback
        // is never pause's job.
        when (mutableState.value.phase) {
            TtsPhase.Idle, TtsPhase.Finished, TtsPhase.Error -> return
            else -> {}
        }
        paused = true
        engine.stop() // interrupts the current utterance; speak() returns false
        if (mutableState.value.phase != TtsPhase.Paused) {
            mutableState.update { it.copy(phase = TtsPhase.Paused) }
        }
        logcat(LogPriority.DEBUG) {
            "TTS pause page=${mutableState.value.pageIndex} sentence=${mutableState.value.sentenceIndex}"
        }
    }

    fun resume() {
        if (!paused || stopped) return
        paused = false
        val pageIndex = mutableState.value.pageIndex
        logcat(LogPriority.DEBUG) { "TTS resume page=$pageIndex sentence=$resumeIndex" }
        playbackJob?.cancel()
        if (mutableState.value.phase == TtsPhase.Playing || mutableState.value.phase == TtsPhase.LoadingPage) {
            engine.stop()
        }
        playbackJob = scope.launch {
            if (engine.initialize()) {
                engine.acquireFocus()
                runPlayback(pageIndex, startAtSentence = resumeIndex)
            } else {
                fail(TtsError.EngineError)
            }
        }
    }

    fun togglePlayPause() {
        if (paused || mutableState.value.phase == TtsPhase.Paused) resume() else pause()
    }

    fun stop() {
        logcat(LogPriority.DEBUG) { "TTS stop (phase=${mutableState.value.phase})" }
        context = null
        resetSession(stoppedFlag = true)
        mutableState.value = TtsPlaybackState(phase = TtsPhase.Idle)
    }

    fun nextSentence() = stepBy(1)

    fun previousSentence() = stepBy(-1)

    /**
     * Called by the host on every viewer page change. A mismatch against the page we
     * asked to advance to means the user navigated manually — user navigation wins.
     */
    fun onPageSelected(pageIndex: Int) {
        val pending = pendingAdvance
        if (pending != null) {
            logcat(LogPriority.DEBUG) { "TTS advance confirmed page=$pageIndex" }
            pending.complete(pageIndex)
            return
        }
        val state = mutableState.value
        val isActivePlayback = state.phase == TtsPhase.Playing ||
            state.phase == TtsPhase.LoadingPage ||
            state.phase == TtsPhase.Preparing
        // Also handle navigation during pause so resume picks up the correct page.
        val isPaused = state.phase == TtsPhase.Paused
        if ((isActivePlayback || isPaused) && !stopped && pageIndex != state.pageIndex) {
            logcat(LogPriority.DEBUG) { "TTS user navigation to page=$pageIndex (phase=${state.phase})" }
            if (isActivePlayback) {
                rebuildQueueForUserNavigation(pageIndex)
            } else {
                // Paused: update the page index and reset the sentence index so
                // resume starts from the top of the NEW page instead of carrying
                // the old page's resumeIndex (BUG-003).
                mutableState.update { it.copy(pageIndex = pageIndex) }
                resumeIndex = 0
            }
        }
    }

    /**
     * Asks the host to show [target] and waits for confirmation.
     * Returns true when playback may continue with [target] as the active page.
     */
    private suspend fun awaitAdvanceConfirmation(target: Int): Boolean {
        val deferred = CompletableDeferred<Int>()
        pendingAdvance = deferred
        logcat(LogPriority.DEBUG) { "TTS page advance request target=$target" }
        try {
            eventChannel.send(TtsEvent.AdvancePage(target))
            val shown = withTimeoutOrNull(ADVANCE_CONFIRM_TIMEOUT_MS) { deferred.await() }
            when {
                shown == target -> {
                    mutableState.update { it.copy(pageIndex = shown, phase = TtsPhase.LoadingPage) }
                    return true
                }
                shown != null -> {
                    // Viewer settled somewhere else (user navigated mid-advance);
                    // reconcile playback with the page actually shown.
                    rebuildQueueForUserNavigation(shown)
                    return false
                }
                else -> {
                    logcat(LogPriority.WARN) { "TTS page advance to $target timed out" }
                    paused = true
                    // Reflect the requested advance so resume() continues from the target
                    // page instead of re-reading the page we just finished. User navigation
                    // during the pause still wins via onPageSelected updating pageIndex.
                    mutableState.update {
                        it.copy(phase = TtsPhase.Paused, pageIndex = target, sentenceText = "")
                    }
                    return false
                }
            }
        } finally {
            pendingAdvance = null
        }
    }

    private fun rebuildQueueForUserNavigation(pageIndex: Int) {
        // Dedup: skip if we already rebuilt for this page — prevents prefetch/scan
        // spam when onPageSelected fires repeatedly for the same target during a swipe.
        if (pageIndex == lastRebuildPageIndex) {
            logcat(LogPriority.DEBUG) { "TTS rebuild dedup: already handling page=$pageIndex" }
            return
        }
        lastRebuildPageIndex = pageIndex
        playbackJob?.cancel()
        prefetchJob?.cancel()
        prefetchJob = null
        prefetchPages = null
        engine.stop()
        paused = false
        val ctx = provideContext()
        if (ctx == null) {
            // Chapter not ready yet; surface an honest paused state until the
            // viewer reports a page and triggers another rebuild. Clear the dedup
            // marker so that retry rebuild is not swallowed.
            lastRebuildPageIndex = -1
            paused = true
            mutableState.update { it.copy(pageIndex = pageIndex, phase = TtsPhase.Paused, sentenceText = "") }
            return
        }
        context = ctx
        mutableState.update { it.copy(pageIndex = pageIndex, phase = TtsPhase.LoadingPage) }
        playbackJob = scope.launch {
            if (engine.initialize()) runPlayback(pageIndex) else fail(TtsError.EngineError)
        }
    }

    private suspend fun runPlayback(startPageIndex: Int, startAtSentence: Int = 0) {
        var pageIndex = startPageIndex
        var sentenceIndex = startAtSentence

        if (!ensureInitialized()) return

        while (true) {
            val ctx = context ?: return finishIdle()

            val sentences = acquireSentences(pageIndex) ?: return // acquisition failed & reported
            queuePageIndex = pageIndex

            if (sentences.isEmpty()) {
                if (!advanceFromPolicy(ctx, pageIndex, pageHasText = false)) return
                pageIndex = mutableState.value.pageIndex
                sentenceIndex = 0
                continue
            }

            queue = sentences
            if (sentenceIndex >= sentences.size) {
                // Resume landed after the page's last utterance (resumeIndex already
                // points past it): continue with the page advance instead of
                // re-speaking the page from the start.
                resumeIndex = 0
                if (!advanceFromPolicy(ctx, pageIndex, pageHasText = true)) return
                pageIndex = mutableState.value.pageIndex
                sentenceIndex = 0
                continue
            }
            schedulePrefetch(ctx, pageIndex + 1)

            var utteranceRetries = 0
            while (sentenceIndex < sentences.size) {
                if (stopped) return finishIdle()
                if (!awaitWhilePaused()) return finishIdle()

                val sentence = sentences[sentenceIndex]
                resumeIndex = sentenceIndex
                mutableState.update {
                    it.copy(
                        phase = TtsPhase.Playing,
                        pageIndex = pageIndex,
                        sentenceIndex = sentenceIndex,
                        sentenceCount = sentences.size,
                        sentenceText = sentence.text,
                    )
                }
                // Mid-page escalation: at high rates the initial N-page lookahead may
                // finish before the page ends; re-arm once at the page's midpoint.
                if (sentenceIndex == sentences.size / 2 &&
                    preferences.ttsSpeechRate().get() >= 2f
                ) {
                    schedulePrefetch(ctx, pageIndex + 1)
                }
                val id = utteranceId(pageIndex, sentenceIndex)
                logcat(LogPriority.DEBUG) {
                    "TTS dispatch id=$id page=$pageIndex sentence=$sentenceIndex " +
                        "textLen=${sentence.text.length} textHash=${sentence.text.hashCode()}"
                }
                val spoke = try {
                    // Auto-scroll to the sentence's region before speaking (webtoon sync)
                    eventChannel.send(TtsEvent.ScrollToRegion(pageIndex, sentence.boundingBox))
                    engine.speak(id, sentence.text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "TTS speak failed" }
                    return fail(TtsError.EngineError)
                }
                if (!spoke) {
                    if (stopped) return finishIdle()
                    if (paused) {
                        // resume() relaunches playback from resumeIndex; this job must
                        // not keep advancing or both jobs would race on the engine.
                        if (!awaitWhilePaused()) return finishIdle()
                        return
                    }
                    // Genuine utterance failure (engine rejected/erred): per house rule,
                    // retry once, then pause instead of silently skipping the sentence.
                    if (utteranceRetries == 0) {
                        utteranceRetries++
                        logcat(LogPriority.WARN) {
                            "TTS sentence $id failed; retrying once"
                        }
                        continue
                    }
                    logcat(LogPriority.ERROR) {
                        "TTS sentence $id failed twice; pausing"
                    }
                    paused = true
                    mutableState.update { it.copy(phase = TtsPhase.Paused) }
                    return
                }
                // Point resume at the NEXT unheard sentence before any suspension, so
                // a pause between utterances resumes after (not re-speaking) this one.
                resumeIndex = sentenceIndex + 1
                utteranceRetries = 0
                sentenceIndex++
            }

            resumeIndex = 0
            if (!advanceFromPolicy(ctx, pageIndex, pageHasText = true)) return
            pageIndex = mutableState.value.pageIndex
            sentenceIndex = 0
        }
    }

    /**
     * Runs the advance policy and executes its decision.
     * Returns true when the loop should continue (NextPage), false otherwise.
     */
    private suspend fun advanceFromPolicy(
        ctx: TtsChapterContext,
        pageIndex: Int,
        pageHasText: Boolean,
    ): Boolean {
        when (
            val action = TtsAdvancePolicy.computeAdvance(
                currentPageIndex = pageIndex,
                totalPages = ctx.totalPages,
                pageHasText = pageHasText,
                autoTurn = preferences.ttsAutoPageTurn().get(),
                autoNextChapter = preferences.ttsAutoNextChapter().get(),
                nextChapterExists = ctx.hasNextChapter,
            )
        ) {
            TtsAdvanceAction.NextPage -> {
                mutableState.update { it.copy(phase = TtsPhase.LoadingPage) }
                return awaitAdvanceConfirmation(pageIndex + 1)
            }
            TtsAdvanceAction.NextChapter -> {
                logcat(LogPriority.DEBUG) { "TTS chapter advance request" }
                mutableState.update { it.copy(phase = TtsPhase.Preparing, sentenceText = "") }
                engine.stop()
                engine.abandonFocus()
                // Cancel old-chapter prefetch; resetSession at rebind also covers
                // this, but the load window can keep scans running wastefully.
                prefetchJob?.cancel()
                prefetchJob = null
                eventChannel.send(TtsEvent.AdvanceChapter)
                // Host loads the next chapter and rebinds us via start().
                return false
            }
            TtsAdvanceAction.Finish -> {
                if (!pageHasText) eventChannel.send(TtsEvent.Failed(TtsError.NoTextFound))
                engine.stop()
                engine.abandonFocus()
                mutableState.update { it.copy(phase = TtsPhase.Finished, sentenceText = "") }
                return false
            }
            TtsAdvanceAction.PauseAtPageEnd -> {
                paused = true
                mutableState.update { it.copy(phase = TtsPhase.Paused, sentenceText = "") }
                return false
            }
        }
    }

    private suspend fun ensureInitialized(): Boolean {
        if (!engine.initialize()) {
            fail(TtsError.EngineError)
            return false
        }
        engine.acquireFocus()
        return true
    }

    /** Returns the page's sentences, an empty list when the page has no text, or null on failure. */
    private suspend fun acquireSentences(pageIndex: Int): List<TtsSentence>? = withIOContext {
        // Don't clobber Paused: a pause during acquisition must stay visible
        // (and awaitWhilePaused in runPlayback holds speech until resumed).
        if (!paused) {
            mutableState.update { it.copy(phase = TtsPhase.LoadingPage) }
        }
        val ctx = context ?: return@withIOContext emptyList()
        val acquireStartedAt = SystemClock.elapsedRealtime()
        val cached = try {
            getCachedPageOcr.await(ctx.chapter.id, pageIndex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "TTS cached OCR read failed" }
            null
        }
        logcat(LogPriority.DEBUG) {
            "TTS OCR ${if (cached != null) "cache hit" else "cache miss"} chapter=${ctx.chapter.id} page=$pageIndex"
        }
        val result = cached ?: scanOnDemand(ctx, pageIndex) ?: return@withIOContext null
        val zones = if (preferences.ttsOcrExclusionsEnabled().get()) {
            try {
                getExclusionZones.awaitForSpeech(ctx.manga.id, ctx.manga.source, ctx.chapter.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "TTS exclusion zone lookup failed; speaking all regions" }
                emptyList()
            }
        } else {
            emptyList()
        }
        val regions = result.regions.applyExclusions(
            zones,
            ExclusionMatchContext(
                mangaId = ctx.manga.id,
                sourceId = ctx.manga.source,
                chapterId = ctx.chapter.id,
                pageIndex = pageIndex,
            ),
        )
        if (zones.isNotEmpty()) {
            val typeCounts = zones.groupingBy { it.matchType.name }.eachCount()
            logcat(LogPriority.DEBUG) {
                "TTS page=$pageIndex exclusion rules=${zones.size} types=$typeCounts " +
                    "excluded=${result.regions.size - regions.size}/${result.regions.size}"
            }
            // Structural rule detail (no text content, rules §7): enough to prove
            // which rule/scope/rect fired or did not on a given page.
            zones.forEach { z ->
                logcat(LogPriority.DEBUG) {
                    "OCR-ZONE rule=${z.id} type=${z.matchType.name} scope=${z.scope.name} " +
                        "page=${z.pageIndex} chapter=${z.chapterId} manga=${z.mangaId} " +
                        "rect=[${z.boundingBox.left},${z.boundingBox.top}," +
                        "${z.boundingBox.right},${z.boundingBox.bottom}] " +
                        "enabled=${z.enabled}"
                }
            }
        }
        val dedupedRegions = SpeechPipeline.dedupeOverlappingDuplicates(regions)
        if (dedupedRegions.size < regions.size) {
            logcat(LogPriority.DEBUG) {
                "TTS page=$pageIndex dedup dropped=${regions.size - dedupedRegions.size} regions"
            }
        }
        val sentences = SpeechPipeline.toSpeakableSentences(
            regions = dedupedRegions,
            classificationConfig = SpeechClassificationConfig(),
            filterConfig = preferences.speechRegionFilterConfig(),
            cleanupOptions = preferences.speechCleanupOptions(),
        )
        val acquireMs = SystemClock.elapsedRealtime() - acquireStartedAt
        logcat(LogPriority.DEBUG) {
            "TTS page=$pageIndex segmented sentences=${sentences.size} regions=${result.regions.size} " +
                "acquireMs=$acquireMs"
        }
        if (sessionStartedAtElapsed != 0L) {
            logcat(LogPriority.INFO) {
                "TTS startup open->first page ready in ${SystemClock.elapsedRealtime() - sessionStartedAtElapsed}ms"
            }
            sessionStartedAtElapsed = 0L
        }
        sentences
    }

    /** Cached-miss path: resolve the bitmap through the shared pipeline, scan, recycle.
     *  Runs on IO: page-list resolution does network via Rx awaitSingle on the calling
     *  thread, and the prefetch job launches on the Main viewModelScope.
     *  The current page's scan is HIGH priority so it never queues behind prefetch
     *  or background chapter scans. */
    private suspend fun scanOnDemand(
        ctx: TtsChapterContext,
        pageIndex: Int,
        reportFailure: Boolean = true,
    ): OcrPageResult? = try {
        logcat(LogPriority.DEBUG) { "TTS on-demand scan start chapter=${ctx.chapter.id} page=$pageIndex" }
        withIOContext {
            withOcrScanSession.await {
                val pages = pageSourceResolver.resolve(ctx.manga, ctx.chapter)
                pages.use { resolved ->
                    val input = resolved.getPageInput(pageIndex) ?: return@use null
                    val bitmap: android.graphics.Bitmap = input.openBitmap() ?: return@use null
                    try {
                        scanPageOcr.await(
                            ctx.chapter.id,
                            pageIndex,
                            bitmap.toOcrImage(),
                            priority = if (reportFailure) OcrScanPriority.HIGH else OcrScanPriority.NORMAL,
                        )
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: OutOfMemoryError) {
        logcat(LogPriority.ERROR, e) { "OOM during TTS page scan" }
        if (reportFailure) fail(TtsError.OcrError)
        null
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "TTS on-demand scan failed" }
        if (reportFailure) fail(TtsError.OcrError)
        null
    }

    /** Prefetch lookahead depth by speech rate: faster speech needs more runway.
     *  Remote scans commonly take 20-60s per page while one page of speech at
     *  1x runs ~10-20s, so the default lookahead is 2 pages, overlapping scans. */
    private fun prefetchDepth(): Int {
        val rate = preferences.ttsSpeechRate().get()
        return when {
            rate >= 2.5f -> MAX_PREFETCH_DEPTH
            rate >= 1.5f -> 3
            else -> 2
        }
    }

    private fun schedulePrefetch(ctx: TtsChapterContext, startPageIndex: Int) {
        if (startPageIndex >= ctx.totalPages) return
        val targetPages = startPageIndex until minOf(startPageIndex + prefetchDepth(), ctx.totalPages)
        // Guard: skip if these exact pages are already being prefetched.
        if (targetPages.isEmpty() || (prefetchPages == targetPages && prefetchJob?.isActive == true)) {
            return
        }
        prefetchPages = targetPages
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            logcat(LogPriority.DEBUG) {
                "TTS prefetch start pages=${targetPages.first}..${targetPages.last} " +
                    "rate=${preferences.ttsSpeechRate().get()}"
            }
            // Pages prefetch in parallel: remote scans take ~20-60s each while one
            // page of speech lasts ~10-20s, so serial lookahead cannot keep up.
            // The scan queue bounds actual OCR concurrency.
            coroutineScope {
                targetPages.map { page ->
                    async {
                        val cached = try {
                            getCachedPageOcr.await(ctx.chapter.id, page)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        if (cached != null) {
                            logcat(LogPriority.DEBUG) { "TTS prefetch cache hit page=$page" }
                            return@async
                        }
                        try {
                            // Best-effort: failures must not kill the session; the main loop
                            // re-scans and reports its own errors when it reaches this page.
                            val scanned = scanOnDemand(ctx, page, reportFailure = false)
                            if (scanned != null) {
                                logcat(LogPriority.DEBUG) { "TTS prefetch complete page=$page" }
                            } else {
                                logcat(LogPriority.DEBUG) { "TTS prefetch scan failed page=$page (best-effort)" }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Prefetch is best-effort; the main loop reports its own failures.
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun awaitWhilePaused(): Boolean {
        while (paused && !stopped) delay(RESUME_POLL_MS)
        return !stopped
    }

    private fun stepBy(delta: Int) {
        val current = mutableState.value
        if (current.phase != TtsPhase.Playing && current.phase != TtsPhase.Paused) return
        val newIndex = current.sentenceIndex + delta
        if (newIndex !in queue.indices) {
            logcat(LogPriority.DEBUG) {
                "TTS step ${if (delta > 0) "next" else "prev"} rejected at boundary " +
                    "sentence=${current.sentenceIndex} queueSize=${queue.size}"
            }
            return // page boundary crossing stays manual in v1
        }

        resumeIndex = newIndex
        logcat(LogPriority.DEBUG) {
            "TTS step ${if (delta > 0) "next" else "prev"} sentence ${current.sentenceIndex}->$newIndex " +
                "(phase=${current.phase})"
        }
        if (current.phase == TtsPhase.Playing) {
            playbackJob?.cancel()
            engine.stop()
            paused = false
            playbackJob = scope.launch {
                if (engine.initialize()) {
                    runPlayback(
                        queuePageIndex,
                        startAtSentence = newIndex,
                    )
                } else {
                    fail(TtsError.EngineError)
                }
            }
        } else {
            mutableState.update {
                it.copy(
                    sentenceIndex = newIndex,
                    sentenceCount = queue.size,
                    sentenceText = queue.getOrNull(newIndex)?.text.orEmpty(),
                )
            }
        }
    }

    private fun resetSession(stoppedFlag: Boolean) {
        stopped = stoppedFlag
        playbackJob?.cancel()
        playbackJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        prefetchPages = null
        lastRebuildPageIndex = -1
        pendingAdvance = null
        paused = false
        queue = emptyList()
        queuePageIndex = -1
        resumeIndex = 0
        engine.stop()
    }

    private fun finishIdle() {
        engine.abandonFocus()
        mutableState.update { it.copy(phase = TtsPhase.Idle, sentenceText = "") }
    }

    /**
     * Moves the session to the Error phase with Retry. Also callable by the host
     * (ReaderViewModel) for failures the controller cannot observe itself —
     * e.g. a next-chapter load failure during TTS auto-advance, which would
     * otherwise wedge the controller in Preparing forever.
     */
    fun fail(error: TtsError) {
        engine.stop()
        engine.abandonFocus()
        scope.launch { eventChannel.send(TtsEvent.Failed(error)) }
        mutableState.update { it.copy(phase = TtsPhase.Error, error = error) }
    }

    private fun utteranceId(pageIndex: Int, sentenceIndex: Int): String =
        "p${pageIndex}_s${sentenceIndex}_c${dispatchCounter.getAndIncrement()}"

    private companion object {
        const val ADVANCE_CONFIRM_TIMEOUT_MS = 10_000L
        const val RESUME_POLL_MS = 100L
        const val MAX_PREFETCH_DEPTH = 3
    }
}
