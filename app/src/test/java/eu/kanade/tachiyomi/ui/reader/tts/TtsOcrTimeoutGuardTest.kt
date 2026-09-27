package eu.kanade.tachiyomi.ui.reader.tts

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import eu.kanade.tachiyomi.data.ocr.OcrPageInput
import eu.kanade.tachiyomi.data.ocr.OcrPageSourceResolver
import eu.kanade.tachiyomi.data.ocr.ResolvedOcrPages
import eu.kanade.tachiyomi.util.ocr.toOcrImage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.ocr.exception.OcrException
import mihon.domain.ocr.interactor.GetCachedPageOcr
import mihon.domain.ocr.interactor.GetOcrExclusionZones
import mihon.domain.ocr.interactor.ScanPageOcr
import mihon.domain.ocr.interactor.WithOcrScanSession
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.model.OcrRegion
import mihon.domain.ocr.model.OcrScanPriority
import mihon.domain.ocr.model.OcrTextOrientation
import mihon.domain.tts.engine.TtsEngine
import mihon.domain.tts.service.TtsPreferences
import mihon.domain.tts.speech.SpeechCleanupOptions
import mihon.domain.tts.speech.SpeechRegionFilterConfig
import mihon.domain.tts.speech.SpeechScript
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Regression guard: an OCR scan that merely takes a while (queue-wait + GLENS
 * round-trip) must NOT be misclassified as a permanent [TtsError.OcrError].
 * The 8s guard used to cancel a succeeding-but-still-in-flight
 * `scanPageOcr.await(HIGH)` queued behind background scans; a successful
 * acquisition must always reach [TtsPhase.Playing] — never OcrError.
 *
 * The scan completes instantly through the mock; a `Failed(OcrError)` event
 * would only be emitted by the timeout/failure paths in [acquireSentences].
 *
 * Runs on a real [SupervisorJob] scope ([Dispatchers.Default] via
 * [runBlocking]) rather than a virtual test dispatcher: [acquireSentences]
 * hops to the real [Dispatchers.IO] through `withIOContext`, which virtual
 * test dispatchers cannot advance. With the mocked scan returning
 * immediately, the whole start→Playing sequence settles on real threads
 * within a bounded [withTimeout].
 */
class TtsOcrTimeoutGuardTest {

    private lateinit var engine: TtsEngine
    private lateinit var preferences: TtsPreferences
    private lateinit var getCachedPageOcr: GetCachedPageOcr
    private lateinit var scanPageOcr: ScanPageOcr
    private lateinit var withOcrScanSession: WithOcrScanSession
    private lateinit var pageSourceResolver: OcrPageSourceResolver
    private lateinit var getExclusionZones: GetOcrExclusionZones

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.println(any(), any(), any()) } returns 0
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L

        engine = mockk(relaxed = true)
        coEvery { engine.initialize() } returns true
        coEvery { engine.speak(any(), any()) } returns true

        preferences = mockk()
        every { preferences.ttsSpeechRate() } returns mockk {
            every { changes() } returns emptyFlow()
            every { get() } returns 1f
        }
        every { preferences.ttsPitch() } returns mockk {
            every { changes() } returns emptyFlow()
        }
        every { preferences.ttsOcrExclusionsEnabled() } returns mockk {
            every { get() } returns false
        }
        every { preferences.ttsAutoPageTurn() } returns mockk {
            every { get() } returns false
        }
        every { preferences.ttsAutoNextChapter() } returns mockk {
            every { get() } returns false
        }
        every { preferences.speechRegionFilterConfig() } returns SpeechRegionFilterConfig(
            speakSoundEffects = false,
            speakExpressions = false,
            skipForeignScript = true,
            speechScript = SpeechScript.LATIN,
        )
        every { preferences.speechCleanupOptions() } returns SpeechCleanupOptions()

        getCachedPageOcr = mockk()
        scanPageOcr = mockk()
        withOcrScanSession = mockk()
        coEvery { withOcrScanSession.await(any<suspend () -> OcrPageResult?>()) } coAnswers {
            firstArg<suspend () -> OcrPageResult?>()()
        }
        pageSourceResolver = mockk()
        getExclusionZones = mockk()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun successfulOcrScanDoesNotFailWithOcrError() {
        mockkStatic("eu.kanade.tachiyomi.util.ocr.OcrImageMapperKt")
        every { any<Bitmap>().toOcrImage() } returns OcrImage(width = 2, height = 2, pixels = IntArray(4))
        try {
            val bitmap = mockk<Bitmap>()
            every { bitmap.isRecycled } returns false
            every { bitmap.recycle() } just runs
            coEvery { pageSourceResolver.resolve(any(), any()) } returns ResolvedOcrPages(
                listOf(OcrPageInput(pageIndex = 0, openBitmap = { bitmap }, openBitmapRegion = { null })),
            )
            coEvery { getCachedPageOcr.await(any(), any()) } returns null
            coEvery {
                scanPageOcr.await(any(), any(), any(), any())
            } returns OcrPageResult(7L, 0, OcrModel.GLENS, 2, 2, listOf(region("Hello there.")))

            val supervisorJob = SupervisorJob()
            val scope = CoroutineScope(supervisorJob)
            val controller = TtsPlaybackController(
                scope = scope,
                engine = engine,
                preferences = preferences,
                getCachedPageOcr = getCachedPageOcr,
                scanPageOcr = scanPageOcr,
                withOcrScanSession = withOcrScanSession,
                pageSourceResolver = pageSourceResolver,
                getExclusionZones = getExclusionZones,
                provideContext = { ctx() },
            )
            val collected = MutableStateFlow<TtsEvent?>(null)
            runBlocking {
                val collector = scope.launch {
                    controller.events.collect { collected.value = it }
                }
                controller.start(ctx(), 0)
                // Real threads settle the start→acquire→speak chain; the mocked scan
                // completes instantly so the 30s acquire guard never fires. Wait for a
                // terminal phase (with autoTurn off the page ends in Paused).
                withTimeout(10_000) {
                    while (controller.state.value.phase == TtsPhase.Preparing ||
                        controller.state.value.phase == TtsPhase.LoadingPage ||
                        controller.state.value.phase == TtsPhase.Playing
                    ) {
                        delay(50)
                    }
                }
                // A successful scan must speak the sentence, not fail: with autoTurn
                // off the terminal phase is Paused at page end — never Error.
                assertEquals(TtsPhase.Paused, controller.state.value.phase)
                // Scan ran at HIGH priority (cold page must not queue behind background scans).
                coVerify(exactly = 1) {
                    scanPageOcr.await(7L, 0, any(), OcrScanPriority.HIGH)
                }
                // No failure event was emitted for the successful scan.
                delay(200)
                assertTrue(
                    collected.value !is TtsEvent.Failed,
                    "expected no Failed event, got ${collected.value}",
                )
                collector.cancel()
                controller.stop()
                supervisorJob.cancel()
            }
        } finally {
            unmockkStatic("eu.kanade.tachiyomi.util.ocr.OcrImageMapperKt")
        }
    }

    @Test
    fun uncachedChapterQueuesOnlyOneLookaheadPageBeforeSpeech() {
        mockkStatic("eu.kanade.tachiyomi.util.ocr.OcrImageMapperKt")
        every { any<Bitmap>().toOcrImage() } returns OcrImage(width = 2, height = 2, pixels = IntArray(4))
        try {
            val bitmap = mockk<Bitmap>()
            every { bitmap.isRecycled } returns false
            every { bitmap.recycle() } just runs
            // A cold chapter: 10 pages, none cached.
            val pages = (0 until 10).map { index ->
                OcrPageInput(pageIndex = index, openBitmap = { bitmap }, openBitmapRegion = { null })
            }
            coEvery { pageSourceResolver.resolve(any(), any()) } returns ResolvedOcrPages(pages)
            coEvery { getCachedPageOcr.await(any(), any()) } returns null
            // Record every scan request so the test can wait for the prefetch instead of
            // depending on the page-advance policy reaching a terminal phase.
            val scans = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, OcrScanPriority>>()
            coEvery { scanPageOcr.await(any(), any(), any(), any()) } coAnswers {
                val page = secondArg<Int>()
                val priority = arg<OcrScanPriority>(3)
                scans += page to priority
                OcrPageResult(7L, page, OcrModel.GLENS, 2, 2, listOf(region("Hello there.")))
            }

            val supervisorJob = SupervisorJob()
            val scope = CoroutineScope(supervisorJob)
            val controller = TtsPlaybackController(
                scope = scope,
                engine = engine,
                preferences = preferences,
                getCachedPageOcr = getCachedPageOcr,
                scanPageOcr = scanPageOcr,
                withOcrScanSession = withOcrScanSession,
                pageSourceResolver = pageSourceResolver,
                getExclusionZones = getExclusionZones,
                provideContext = { ctx() },
            )
            runBlocking {
                controller.start(ctx(), 0)
                // start() schedules the lookahead while the session is still Preparing, which
                // is exactly the window the throttle governs.
                withTimeout(10_000) {
                    while (scans.none { it.second == OcrScanPriority.NORMAL }) delay(20)
                }
                delay(300) // let any further lookahead scheduling settle

                // The cold lookahead is throttled to a single NORMAL page (p1). Before the fix
                // the uncached budget never bound (6 pages x 5 sentences < 40) and all six
                // pages were queued in parallel, oversubscribing the scan queue 2-3x.
                assertEquals(
                    listOf(1),
                    scans.filter { it.second == OcrScanPriority.NORMAL }.map { it.first },
                )
                // The active page is still scanned at HIGH.
                assertTrue(scans.contains(0 to OcrScanPriority.HIGH))
                controller.stop()
                supervisorJob.cancel()
            }
        } finally {
            unmockkStatic("eu.kanade.tachiyomi.util.ocr.OcrImageMapperKt")
        }
    }

    /**
     * A page whose OCR cannot be obtained (GLENS upload failed, or the 30 s guard fired) must
     * not kill the whole Read-Aloud session. The page is treated as textless and the advance
     * policy moves on. Before the fix `acquireSentences` logged "advancing gracefully" and then
     * called `fail(TtsError.OcrError)`, which sets phase = Error and returns null out of
     * `runPlayback` — device-verified as three consecutive session deaths on ch4563 p1
     * (docs/audits/full-ocr-pipeline-audit.md §1).
     */
    @Test
    fun failedPageAdvancesInsteadOfFailingTheSession() {
        runFailingScanTest(totalPages = 10) { events, controller ->
            // The very first event must be the page advance, not a failure.
            withTimeout(10_000) {
                while (events.filterIsInstance<TtsEvent.AdvancePage>().isEmpty()) delay(50)
            }
            assertEquals(1, events.filterIsInstance<TtsEvent.AdvancePage>().first().pageIndex)
            assertTrue(
                events.none { it is TtsEvent.Failed && it.error == TtsError.OcrError },
                "a failed page scan must not emit Failed(OcrError), got $events",
            )
            assertTrue(
                controller.state.value.phase != TtsPhase.Error,
                "a failed page scan must not put the session in Error, " +
                    "got ${controller.state.value.phase}",
            )
        }
    }

    /**
     * When the failing page is the last one there is nowhere to advance to, so the chapter ends
     * as [TtsPhase.Finished] and the existing "no text found" signal is what the user gets —
     * never [TtsError.OcrError].
     */
    @Test
    fun failedLastPageFinishesAsNoTextFound() {
        runFailingScanTest(totalPages = 1) { events, controller ->
            withTimeout(10_000) {
                while (controller.state.value.phase != TtsPhase.Finished) delay(50)
            }
            assertEquals(TtsPhase.Finished, controller.state.value.phase)
            assertTrue(
                events.any { it is TtsEvent.Failed && it.error == TtsError.NoTextFound },
                "expected Failed(NoTextFound), got $events",
            )
        }
    }

    private fun runFailingScanTest(
        totalPages: Int,
        assertion: suspend (List<TtsEvent>, TtsPlaybackController) -> Unit,
    ) {
        mockkStatic("eu.kanade.tachiyomi.util.ocr.OcrImageMapperKt")
        every { any<Bitmap>().toOcrImage() } returns OcrImage(width = 2, height = 2, pixels = IntArray(4))
        try {
            val bitmap = mockk<Bitmap>()
            every { bitmap.isRecycled } returns false
            every { bitmap.recycle() } just runs
            val pages = (0 until totalPages).map { index ->
                OcrPageInput(pageIndex = index, openBitmap = { bitmap }, openBitmapRegion = { null })
            }
            coEvery { pageSourceResolver.resolve(any(), any()) } returns ResolvedOcrPages(pages)
            coEvery { getCachedPageOcr.await(any(), any()) } returns null
            coEvery { scanPageOcr.await(any(), any(), any(), any()) } throws
                OcrException.ConnectionError(java.io.IOException("glens unreachable"))

            val supervisorJob = SupervisorJob()
            val scope = CoroutineScope(supervisorJob)
            val controller = TtsPlaybackController(
                scope = scope,
                engine = engine,
                preferences = preferences,
                getCachedPageOcr = getCachedPageOcr,
                scanPageOcr = scanPageOcr,
                withOcrScanSession = withOcrScanSession,
                pageSourceResolver = pageSourceResolver,
                getExclusionZones = getExclusionZones,
                provideContext = { ctx(totalPages) },
            )
            val events = java.util.concurrent.CopyOnWriteArrayList<TtsEvent>()
            runBlocking {
                val collector = scope.launch { controller.events.collect { events += it } }
                controller.start(ctx(totalPages), 0)
                try {
                    assertion(events, controller)
                } finally {
                    collector.cancel()
                    controller.stop()
                    supervisorJob.cancel()
                }
            }
        } finally {
            unmockkStatic("eu.kanade.tachiyomi.util.ocr.OcrImageMapperKt")
        }
    }

    private fun ctx(totalPages: Int = 10): TtsChapterContext {
        val manga = mockk<Manga> {
            every { id } returns 1L
            every { source } returns 2L
        }
        val chapter = mockk<Chapter> {
            every { id } returns 7L
        }
        return TtsChapterContext(
            manga = manga,
            chapter = chapter,
            totalPages = totalPages,
            hasNextChapter = false,
        )
    }

    private fun region(text: String): OcrRegion {
        val box = OcrBoundingBox(0.1f, 0.1f, 0.5f, 0.2f)
        return OcrRegion(0, text, box, OcrTextOrientation.Horizontal)
    }
}
