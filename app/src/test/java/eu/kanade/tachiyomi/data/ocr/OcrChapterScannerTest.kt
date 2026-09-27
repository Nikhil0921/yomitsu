package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import mihon.domain.ocr.interactor.ClearCachedChapterOcr
import mihon.domain.ocr.interactor.ScanPageOcr
import mihon.domain.ocr.interactor.WithOcrScanSession
import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.model.OcrScanPriority
import mihon.domain.ocr.repository.OcrRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class OcrChapterScannerTest {

    @Test
    fun probeAllPagesSucceed() = runTest {
        val fixture = createFixture(pages = 3) { _, pageIndex -> pageResult(pageIndex) }

        val completed = mutableListOf<OcrChapterScanProgress>()
        val ok = fixture.scanner.scanChapter(
            chapterId = 1L,
            onProgress = {},
            onComplete = { completed += it },
            onError = { throw AssertionError("unexpected error: $it") },
        )

        assertTrue(ok)
        assertEquals(3, completed.single().processedPages)
    }

    /**
     * Renamed and re-specified 2026-09-27 under explicit rules §10 sign-off. This used to assert
     * `ok == true` — i.e. that a chapter with an unreadable page is reported as DONE, the queue
     * entry is removed, and the holes are only discovered later by Read-Aloud re-OCRing them one
     * cold network round trip at a time. The "scan continues" half of the name is still exactly
     * right and is still asserted: every page is attempted.
     */
    @Test
    fun failedPageIsSkippedButScanIsNotReportedComplete() = runTest {
        val fixture = createFixture(pages = 3) { _, pageIndex ->
            if (pageIndex == 1) {
                error("ocr engine down")
            }
            pageResult(pageIndex)
        }

        val completed = mutableListOf<OcrChapterScanProgress>()
        val errors = mutableListOf<OcrChapterScanError>()
        val ok = fixture.scanner.scanChapter(
            chapterId = 1L,
            onProgress = {},
            onComplete = { completed += it },
            onError = { errors += it },
        )

        assertEquals(false, ok)
        assertEquals(2, completed.single().processedPages)
        assertEquals(OcrScanFailure.PagesSkipped(skipped = 1, total = 3), errors.single().failure)
        coVerify(exactly = 1) { fixture.scanPageOcr.await(eq(1L), eq(0), any()) }
        coVerify(exactly = 1) { fixture.scanPageOcr.await(eq(1L), eq(1), any()) }
        coVerify(exactly = 1) { fixture.scanPageOcr.await(eq(1L), eq(2), any()) }
    }

    /**
     * Renamed and re-specified 2026-09-27 under explicit rules §10 sign-off, same as
     * [failedPageIsSkippedButScanIsNotReportedComplete]. A page whose scan exceeds the per-page
     * guard is a hole in the cache, so the chapter is incomplete — the loop still continues.
     */
    @Test
    fun timedOutPageIsSkippedButScanIsNotReportedComplete() = runTest {
        val fixture = createFixture(
            pages = 3,
            pageScanTimeout = 100.milliseconds,
        ) { _, pageIndex ->
            if (pageIndex == 1) {
                delay(5_000)
            }
            pageResult(pageIndex)
        }

        val completed = mutableListOf<OcrChapterScanProgress>()
        val errors = mutableListOf<OcrChapterScanError>()
        val ok = fixture.scanner.scanChapter(
            chapterId = 1L,
            onProgress = {},
            onComplete = { completed += it },
            onError = { errors += it },
        )

        assertEquals(false, ok)
        assertEquals(2, completed.single().processedPages)
        assertEquals(OcrScanFailure.PagesSkipped(skipped = 1, total = 3), errors.single().failure)
        coVerify(exactly = 1) { fixture.scanPageOcr.await(eq(1L), eq(1), any()) }
    }

    @Test
    fun scanDoesNotWipeTheOcrCache() = runTest {
        val fixture = createFixture(pages = 3) { _, pageIndex -> pageResult(pageIndex) }

        fixture.scanner.scanChapter(
            chapterId = 1L,
            onProgress = {},
            onComplete = {},
            onError = { throw AssertionError("unexpected error: $it") },
        )

        coVerify(exactly = 0) { fixture.clearCachedChapterOcr.await(any()) }
    }

    @Test
    fun failedPageDoesNotWipeAlreadyScannedPages() = runTest {
        val fixture = createFixture(pages = 3) { _, pageIndex ->
            if (pageIndex == 1) {
                error("ocr engine down")
            }
            pageResult(pageIndex)
        }

        // A partial scan legitimately reports PagesSkipped now, so the callback collects instead of
        // throwing — throwing from onError used to be caught by the scanner's own catch-all and
        // turned into a nested Unexpected error.
        val errors = mutableListOf<OcrChapterScanError>()
        fixture.scanner.scanChapter(
            chapterId = 1L,
            onProgress = {},
            onComplete = {},
            onError = { errors += it },
        )

        assertEquals(OcrScanFailure.PagesSkipped(skipped = 1, total = 3), errors.single().failure)
        // Pages 0 and 2 were cached by this run; a wipe here is what left the chapter in ERROR
        // with an empty cache and made Read-Aloud re-OCR every page on demand (audit R1).
        coVerify(exactly = 0) { fixture.clearCachedChapterOcr.await(any()) }
    }

    @Test
    fun networkAbortMidScanDoesNotWipeAlreadyScannedPages() = runTest {
        val fixture = createFixture(pages = 5, onlineCalls = 1) { _, pageIndex -> pageResult(pageIndex) }

        val errors = mutableListOf<OcrChapterScanError>()
        val ok = fixture.scanner.scanChapter(
            chapterId = 1L,
            onProgress = {},
            onComplete = {},
            onError = { errors += it },
        )

        assertEquals(false, ok)
        assertEquals(1, errors.size)
        coVerify(exactly = 0) { fixture.clearCachedChapterOcr.await(any()) }
    }

    @Test
    fun cacheEventIsNotResetToFalseAtScanStart() = runTest {
        val fixture = createFixture(pages = 2) { _, pageIndex -> pageResult(pageIndex) }

        val events = mutableListOf<Boolean>()
        fixture.scanner.scanChapter(
            chapterId = 1L,
            onProgress = {},
            onComplete = {},
            onError = { throw AssertionError("unexpected error: $it") },
            onCacheStateChanged = { _, hasResults -> events += hasResults },
        )

        // The scan adds to the cache; it never clears it, so reporting "no results" at the start
        // would be a lie that flips the chapter badge off and (briefly) on again.
        assertEquals(listOf(true), events)
    }

    @Test
    fun partialScanReportsFailureInsteadOfClaimingSuccess() = runTest {
        val fixture = createFixture(pages = 5) { _, pageIndex ->
            if (pageIndex == 1 || pageIndex == 3) {
                error("ocr engine down")
            }
            pageResult(pageIndex)
        }

        val completed = mutableListOf<OcrChapterScanProgress>()
        val errors = mutableListOf<OcrChapterScanError>()
        val ok = fixture.scanner.scanChapter(
            chapterId = 1L,
            onProgress = {},
            onComplete = { completed += it },
            onError = { errors += it },
        )

        // A chapter with holes in its cache must not be reported as done, or the queue entry is
        // removed and Read-Aloud has to re-OCR the missing pages on demand, one by one
        // (docs/audits/full-ocr-pipeline-audit.md §2, R4).
        assertEquals(false, ok)
        assertEquals(3, completed.single().processedPages)
        assertEquals(
            OcrScanFailure.PagesSkipped(skipped = 2, total = 5),
            errors.single().failure,
        )
        // The loop still visits every page: a hole must not abort the remaining ones.
        coVerify(exactly = 1) { fixture.scanPageOcr.await(eq(1L), eq(0), any()) }
        coVerify(exactly = 1) { fixture.scanPageOcr.await(eq(1L), eq(1), any()) }
        coVerify(exactly = 1) { fixture.scanPageOcr.await(eq(1L), eq(2), any()) }
        coVerify(exactly = 1) { fixture.scanPageOcr.await(eq(1L), eq(3), any()) }
        coVerify(exactly = 1) { fixture.scanPageOcr.await(eq(1L), eq(4), any()) }
    }

    @Test
    fun completeScanStillReportsSuccessAndNoError() = runTest {
        val fixture = createFixture(pages = 3) { _, pageIndex -> pageResult(pageIndex) }

        val errors = mutableListOf<OcrChapterScanError>()
        val ok = fixture.scanner.scanChapter(
            chapterId = 1L,
            onProgress = {},
            onComplete = {},
            onError = { errors += it },
        )

        assertTrue(ok)
        assertTrue(errors.isEmpty(), "a clean scan must not report an error, got $errors")
    }

    private fun createFixture(
        pages: Int = 3,
        pageScanTimeout: Duration = 30_000.milliseconds,
        onlineCalls: Int = Int.MAX_VALUE,
        scanBehavior: suspend (chapterId: Long, pageIndex: Int) -> OcrPageResult,
    ): Fixture {
        val context = mockk<Context>(relaxed = true)
        val connectivity = mockk<ConnectivityManager>(relaxed = true)
        val wifi = mockk<WifiManager>(relaxed = true)
        val networkInfo = mockk<NetworkInfo>(relaxed = true)
        val capabilities = mockk<NetworkCapabilities>(relaxed = true)
        val onlineChecks = java.util.concurrent.atomic.AtomicInteger(0)
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivity
        every { context.getSystemService(ConnectivityManager::class.java.name) } returns connectivity
        every { context.getSystemService(WifiManager::class.java) } returns wifi
        every { context.getSystemService(WifiManager::class.java.name) } returns wifi
        every { connectivity.activeNetworkInfo } returns networkInfo
        every { networkInfo.isConnected } returns true
        every { connectivity.getNetworkCapabilities(any()) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } answers {
            onlineChecks.getAndIncrement() < onlineCalls
        }

        val chapter = Chapter.create().copy(mangaId = 7L, name = "Chapter 1")
        val manga = Manga.create().copy(id = 7L, title = "Manga")

        val getChapter = mockk<GetChapter>()
        coEvery { getChapter.await(1L) } returns chapter
        val getManga = mockk<GetManga>()
        coEvery { getManga.await(7L) } returns manga

        val clearCachedChapterOcr = mockk<ClearCachedChapterOcr>(relaxed = true)

        val ocrRepository = object : OcrRepository {
            override suspend fun <T> withScanSession(block: suspend () -> T): T = block()

            override suspend fun recognizeText(image: OcrImage): String = ""

            override suspend fun scanPage(
                chapterId: Long,
                pageIndex: Int,
                image: OcrImage,
                priority: OcrScanPriority,
            ): OcrPageResult = throw UnsupportedOperationException()

            override suspend fun getCachedPage(chapterId: Long, pageIndex: Int): OcrPageResult? = null

            override suspend fun getCachedChapterIds(chapterIds: Collection<Long>): Set<Long> = emptySet()

            override suspend fun clearCachedChapter(chapterId: Long) = Unit

            override suspend fun clearCache() = Unit

            override suspend fun getCacheSizeBytes(): Long = 0L

            override fun cleanup() = Unit
        }
        val withOcrScanSession = WithOcrScanSession(ocrRepository)

        val scanPageOcr = mockk<ScanPageOcr>()
        coEvery { scanPageOcr.await(any(), any(), any(), any()) } coAnswers {
            scanBehavior(args[0] as Long, args[1] as Int)
        }

        val pageSourceResolver = mockk<OcrPageSourceResolver>()
        val pageBitmap = mockk<Bitmap>(relaxed = true)
        every { pageBitmap.width } returns 2
        every { pageBitmap.height } returns 2
        every { pageBitmap.isRecycled } returns false
        val pageInputs = (0 until pages).map { index ->
            OcrPageInput(
                pageIndex = index,
                openBitmap = { pageBitmap },
                openBitmapRegion = { null },
            )
        }
        coEvery { pageSourceResolver.resolve(any(), any()) } returns ResolvedOcrPages(pageInputs)

        val downloadPreferences = mockk<DownloadPreferences>()
        val downloadOnlyOverWifi = mockk<Preference<Boolean>>()
        every { downloadPreferences.downloadOnlyOverWifi } returns downloadOnlyOverWifi
        every { downloadOnlyOverWifi.get() } returns false

        val scanner = OcrChapterScanner(
            context = context,
            getChapter = getChapter,
            getManga = getManga,
            clearCachedChapterOcr = clearCachedChapterOcr,
            withOcrScanSession = withOcrScanSession,
            scanPageOcr = scanPageOcr,
            pageSourceResolver = pageSourceResolver,
            downloadPreferences = downloadPreferences,
            pageScanTimeout = pageScanTimeout,
        )

        return Fixture(scanner, scanPageOcr, clearCachedChapterOcr)
    }

    private fun pageResult(pageIndex: Int): OcrPageResult {
        return OcrPageResult(
            chapterId = 1L,
            pageIndex = pageIndex,
            ocrModel = OcrModel.LEGACY,
            imageWidth = 2,
            imageHeight = 2,
            regions = emptyList(),
        )
    }

    private data class Fixture(
        val scanner: OcrChapterScanner,
        val scanPageOcr: ScanPageOcr,
        val clearCachedChapterOcr: ClearCachedChapterOcr,
    )
}
