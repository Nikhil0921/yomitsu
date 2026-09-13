package eu.kanade.tachiyomi.data.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import java.io.InputStream
import kotlin.time.Duration.Companion.seconds

internal class OcrPageSourceResolver(
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val pageSourceGateway: OcrPageSourceGateway,
    private val chapterCache: ChapterCache,
) {
    suspend fun resolve(
        manga: Manga,
        chapter: Chapter,
    ): ResolvedOcrPages {
        val source = sourceManager.getOrStub(manga.source)
        val downloadedPagesReady = if (source is HttpSource) {
            awaitDownloadedChapterPages(manga, chapter)
        } else {
            false
        }
        return when {
            downloadedPagesReady -> resolveDownloadedPages(manga, chapter, source)
            source is LocalSource -> resolveLocalPages(source, chapter)
            source is HttpSource -> resolveRemotePages(source, chapter)
            else -> ResolvedOcrPages(emptyList())
        }
    }

    private suspend fun awaitDownloadedChapterPages(
        manga: Manga,
        chapter: Chapter,
    ): Boolean {
        val queuedDownload = downloadManager.getQueuedDownloadOrNull(chapter.id)
        if (queuedDownload == null) {
            return isChapterDownloaded(manga, chapter)
        }

        return withTimeoutOrNull(DOWNLOAD_WAIT_TIMEOUT.inWholeMilliseconds) {
            queuedDownload.statusFlow
                .map { status ->
                    val isStillQueued = downloadManager.getQueuedDownloadOrNull(chapter.id) != null
                    val downloaded = isChapterDownloaded(manga, chapter)
                    when {
                        downloaded && !isStillQueued -> true
                        status == Download.State.ERROR || status == Download.State.NOT_DOWNLOADED -> false
                        else -> null
                    }
                }
                .filterNotNull()
                .first()
        } ?: false
    }

    private fun isChapterDownloaded(
        manga: Manga,
        chapter: Chapter,
    ): Boolean {
        return downloadManager.isChapterDownloaded(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.title,
            manga.source,
            skipCache = true,
        )
    }

    private suspend fun resolveDownloadedPages(
        manga: Manga,
        chapter: Chapter,
        source: Source,
    ): ResolvedOcrPages {
        return pageSourceGateway.resolveDownloadedPages(manga, chapter, source)
    }

    private suspend fun resolveLocalPages(
        source: LocalSource,
        chapter: Chapter,
    ): ResolvedOcrPages {
        return pageSourceGateway.resolveLocalPages(source, chapter)
    }

    private suspend fun resolveRemotePages(
        source: HttpSource,
        chapter: Chapter,
    ): ResolvedOcrPages {
        // The reader's HttpPageLoader already persists the page list in
        // ChapterCache when the chapter is open; reuse it instead of another
        // network round trip (falls back to the source on miss).
        val cachedPages = try {
            chapterCache.getPageListFromCache(chapter)
        } catch (_: Exception) {
            null
        }
        val pages = (cachedPages ?: source.getPageList(chapter.toSChapter()))
            .mapIndexed { index, page -> Page(index, page.url, page.imageUrl, page.uri) }
            .map { page ->
                OcrPageInput(
                    pageIndex = page.index,
                    openBitmap = {
                        decodeOcrBitmapWithFallback { decode ->
                            openRemotePageBitmap(page, source, decode)
                        }
                    },
                    openBitmapRegion = { sourceRect ->
                        decodeOcrRegionWithFallback(sourceRect) { decode ->
                            openRemotePageBitmap(page, source, decode)
                        }
                    },
                )
            }

        return ResolvedOcrPages(pages)
    }

    private suspend fun <T> openRemotePageBitmap(
        page: Page,
        source: HttpSource,
        decode: (InputStream) -> T?,
    ): T? {
        return withIOContext {
            if (page.imageUrl.isNullOrBlank()) {
                page.imageUrl = source.getImageUrl(page)
            }
            // The viewer (Coil/HttpPageLoader) already saved the displayed
            // page's image into ChapterCache; read it from disk instead of a
            // cacheless re-download when present.
            val imageUrl = page.imageUrl
            if (imageUrl != null && chapterCache.isImageInCache(imageUrl)) {
                val file = chapterCache.getImageFile(imageUrl)
                try {
                    file.inputStream().use { decode(it) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    logcat(LogPriority.DEBUG) { "OCR cached image decode failed, refetching page=${page.index}" }
                    source.getImage(page).use { response ->
                        decode(response.body.byteStream())
                    }
                }
            } else {
                source.getImage(page).use { response ->
                    decode(response.body.byteStream())
                }
            }
        }
    }
}

private val DOWNLOAD_WAIT_TIMEOUT = 15.seconds

data class OcrPageInput(
    val pageIndex: Int,
    val openBitmap: suspend () -> Bitmap?,
    val openBitmapRegion: suspend (Rect) -> Bitmap?,
)

internal class ResolvedOcrPages(
    val pages: List<OcrPageInput>,
    private val closeBlock: () -> Unit = {},
) : AutoCloseable {
    fun getPageInput(pageIndex: Int): OcrPageInput? {
        return pages.firstOrNull { it.pageIndex == pageIndex }
    }

    override fun close() {
        closeBlock()
    }
}
