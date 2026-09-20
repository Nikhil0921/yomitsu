package eu.kanade.tachiyomi.data.ocr

import android.util.Log
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

class OcrPageSourceResolverTest {

    private lateinit var sourceManager: SourceManager
    private lateinit var downloadManager: DownloadManager
    private lateinit var gateway: OcrPageSourceGateway
    private lateinit var chapterCache: ChapterCache
    private lateinit var httpSource: HttpSource
    private lateinit var resolver: OcrPageSourceResolver

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.println(any(), any(), any()) } returns 0

        sourceManager = mockk()
        downloadManager = mockk()
        gateway = mockk()
        chapterCache = mockk()
        httpSource = mockk()

        every { sourceManager.getOrStub(1L) } returns httpSource
        every { downloadManager.getQueuedDownloadOrNull(any()) } returns null
        every {
            downloadManager.isChapterDownloaded(any(), any(), any(), any(), any(), any())
        } returns false
        every { chapterCache.getPageListFromCache(any()) } throws NoSuchElementException("miss")

        resolver = OcrPageSourceResolver(sourceManager, downloadManager, gateway, chapterCache)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun firstResolutionPopulatesMemoizedPages() = runTest {
        coEvery { httpSource.getPageList(any()) } returns pages("a", "b")

        val resolved = resolver.resolve(manga(), chapter(8084L))

        assertEquals(2, resolved.pages.size)
        assertNotNull(resolved.getPageInput(0))
        coVerify(exactly = 1) { httpSource.getPageList(any()) }
    }

    @Test
    fun repeatedResolutionForSameChapterReusesMemoizedPages() = runTest {
        coEvery { httpSource.getPageList(any()) } returns pages("a", "b")

        val first = resolver.resolve(manga(), chapter(8084L))
        val second = resolver.resolve(manga(), chapter(8084L))

        assertEquals(2, first.pages.size)
        assertEquals(2, second.pages.size)
        coVerify(exactly = 1) { httpSource.getPageList(any()) }
    }

    @Test
    fun differentChapterDoesNotReuseMemoizedPages() = runTest {
        coEvery { httpSource.getPageList(any()) } returns pages("a", "b")

        resolver.resolve(manga(), chapter(8084L))
        val other = resolver.resolve(manga(), chapter(9999L))

        assertEquals(2, other.pages.size)
        coVerify(exactly = 2) { httpSource.getPageList(any()) }
    }

    @Test
    fun failedResolutionDoesNotPoisonMemoizedPages() = runTest {
        coEvery { httpSource.getPageList(any()) } throws
            IllegalStateException("boom") andThen pages("a", "b")

        var failed = false
        try {
            resolver.resolve(manga(), chapter(8084L))
        } catch (e: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
        val recovered = resolver.resolve(manga(), chapter(8084L))
        val reused = resolver.resolve(manga(), chapter(8084L))

        assertEquals(2, recovered.pages.size)
        assertEquals(2, reused.pages.size)
        // One failed fetch + one successful fetch; the third resolve reuses the memo.
        coVerify(exactly = 2) { httpSource.getPageList(any()) }
    }

    @Test
    fun cancelledResolutionDoesNotPoisonMemoizedPages() = runTest {
        coEvery { httpSource.getPageList(any()) } coAnswers {
            delay(60_000)
            pages("a")
        }

        val job = async { resolver.resolve(manga(), chapter(8084L)) }
        delay(10)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)

        coEvery { httpSource.getPageList(any()) } returns pages("a", "b")
        val recovered = resolver.resolve(manga(), chapter(8084L))

        assertEquals(2, recovered.pages.size)
        assertNull(recovered.getPageInput(7))
    }

    private fun pages(vararg urls: String): List<Page> {
        return urls.mapIndexed { index, url -> Page(index, url, "img-$url") }
    }

    private fun manga(): Manga {
        return mockk {
            every { source } returns 1L
            every { title } returns "title"
        }
    }

    private fun chapter(chapterId: Long): Chapter {
        return mockk {
            every { id } returns chapterId
            every { url } returns "chapter-$chapterId"
            every { name } returns "chapter-$chapterId"
            every { dateUpload } returns 0L
            every { chapterNumber } returns 1.0
            every { scanlator } returns null
            every { memo } returns JsonObject(emptyMap())
        }
    }
}
