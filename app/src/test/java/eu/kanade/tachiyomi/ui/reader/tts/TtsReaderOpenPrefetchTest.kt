package eu.kanade.tachiyomi.ui.reader.tts

import android.graphics.Bitmap
import android.util.Log
import eu.kanade.tachiyomi.data.ocr.OcrPageInput
import eu.kanade.tachiyomi.data.ocr.OcrPageSourceResolver
import eu.kanade.tachiyomi.data.ocr.ResolvedOcrPages
import eu.kanade.tachiyomi.util.ocr.toOcrImage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mihon.domain.ocr.interactor.GetCachedPageOcr
import mihon.domain.ocr.interactor.GetOcrExclusionZones
import mihon.domain.ocr.interactor.ScanPageOcr
import mihon.domain.ocr.interactor.WithOcrScanSession
import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.model.OcrScanPriority
import mihon.domain.tts.engine.TtsEngine
import mihon.domain.tts.service.TtsPreferences
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.IOException

class TtsReaderOpenPrefetchTest {

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

        engine = mockk(relaxed = true)
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
        unmockkStatic(Log::class)
    }

    @Test
    fun prefetchSkipsScanWhenNoPageInput() = runTest {
        coEvery { pageSourceResolver.resolve(any(), any()) } returns ResolvedOcrPages(emptyList())
        val controller = createController(this)

        controller.prefetchReaderOpenPage(ctx(), 0).join()
        advanceUntilIdle()

        coVerify(exactly = 0) { scanPageOcr.await(any(), any(), any(), any()) }
        assertEquals(TtsPhase.Idle, controller.state.value.phase)
    }

    @Test
    fun prefetchScansWithNormalPriority() = runTest {
        mockkStatic("eu.kanade.tachiyomi.util.ocr.OcrImageMapperKt")
        every {
            any<Bitmap>().toOcrImage()
        } returns OcrImage(width = 2, height = 2, pixels = IntArray(4))
        try {
            val bitmap = mockk<Bitmap>()
            coEvery { pageSourceResolver.resolve(any(), any()) } returns ResolvedOcrPages(
                listOf(OcrPageInput(pageIndex = 0, openBitmap = { bitmap }, openBitmapRegion = { null })),
            )
            coEvery { scanPageOcr.await(any(), any(), any(), any()) } returns
                OcrPageResult(7L, 0, OcrModel.GLENS, 2, 2, emptyList())
            val controller = createController(this)

            controller.prefetchReaderOpenPage(ctx(), 0).join()
            advanceUntilIdle()

            coVerify(exactly = 1) {
                scanPageOcr.await(7L, 0, any(), OcrScanPriority.NORMAL)
            }
            assertEquals(TtsPhase.Idle, controller.state.value.phase)
        } finally {
            unmockkStatic("eu.kanade.tachiyomi.util.ocr.OcrImageMapperKt")
        }
    }

    @Test
    fun prefetchFailureStaysSilent() = runTest {
        coEvery { pageSourceResolver.resolve(any(), any()) } throws IOException("boom")
        val controller = createController(this)

        // Must not throw; best-effort path swallows the failure.
        controller.prefetchReaderOpenPage(ctx(), 0).join()
        advanceUntilIdle()

        coVerify(exactly = 0) { scanPageOcr.await(any(), any(), any(), any()) }
        assertEquals(TtsPhase.Idle, controller.state.value.phase)
    }

    private fun createController(scope: CoroutineScope): TtsPlaybackController {
        return TtsPlaybackController(
            scope = scope,
            engine = engine,
            preferences = preferences,
            getCachedPageOcr = getCachedPageOcr,
            scanPageOcr = scanPageOcr,
            withOcrScanSession = withOcrScanSession,
            pageSourceResolver = pageSourceResolver,
            getExclusionZones = getExclusionZones,
            provideContext = { null },
        )
    }

    private fun ctx(): TtsChapterContext {
        val manga = mockk<Manga>()
        val chapter = mockk<Chapter> {
            every { id } returns 7L
        }
        return TtsChapterContext(
            manga = manga,
            chapter = chapter,
            totalPages = 10,
            hasNextChapter = false,
        )
    }
}
