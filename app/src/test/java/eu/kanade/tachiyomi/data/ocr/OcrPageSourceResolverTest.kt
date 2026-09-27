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

    /**
     * The memo was a single slot, so a next-chapter prefetch interleaving with the chapter being
     * read evicted the current chapter's page list and every subsequent resolve went back to the
     * network - once per scanned page, inside the acquire path that has a 30 s budget
     * (docs/audits/full-ocr-pipeline-audit.md §3).
     */
    @Test
    fun interleavingAnotherChapterDoesNotEvictMemoizedPages() = runTest {
        coEvery { httpSource.getPageList(any()) } returns pages("a", "b")

        resolver.resolve(manga(), chapter(8084L))
        resolver.resolve(manga(), chapter(9999L))
        val third = resolver.resolve(manga(), chapter(8084L))

        assertEquals(2, third.pages.size)
        // Two chapters, two fetches - the third resolve reuses 8084's memoized list.
        coVerify(exactly = 2) { httpSource.getPageList(any()) }
    }

    @Test
    fun memoizedPagesAreBoundedToTheMostRecentChapters() = runTest {
        coEvery { httpSource.getPageList(any()) } returns pages("a", "b")

        val ids = (1L..MEMOIZED_CHAPTER_CAPACITY + 2L).toList()
        ids.forEach { resolver.resolve(manga(), chapter(it)) }
        // The newest chapter is still memoized; the oldest was evicted by the cap and refetched.
        resolver.resolve(manga(), chapter(ids.last()))
        resolver.resolve(manga(), chapter(ids.first()))

        // ids.size fetches, +0 for the newest, +1 for the evicted oldest.
        coVerify(exactly = ids.size + 1) { httpSource.getPageList(any()) }
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
