package eu.kanade.tachiyomi.data.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // Stage 3B: the TTS OCR path resolves the same chapter's page list once per
    // scanned page (current + prefetch run concurrently), repeating the
    // download check's page-list/network fetch each time. Memoize the fetched
    // page list per chapter so repeats reuse it. Each resolve() still builds
    // fresh OcrPageInput wrappers + ResolvedOcrPages, so callers' use{} pairing
    // and per-scan imageUrl state are unaffected.
    private val remoteResolveMutex = Mutex()
    private var memoizedRemotePages: MemoizedRemotePages? = null

    suspend fun resolve(
        manga: Manga,
        chapter: Chapter,
    ): ResolvedOcrPages {
        val source = sourceManager.getOrStub(manga.source)
        val downloadCheckStartNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        val downloadedPagesReady = if (source is HttpSource) {
            awaitDownloadedChapterPages(manga, chapter)
        } else {
            false
        }
        if (BuildConfig.DEBUG) {
            logcat {
                "OCR resolve downloadcheck chapter=${chapter.id} " +
                    "elapsedMs=${(System.nanoTime() - downloadCheckStartNs) / 1_000_000} " +
                    "downloadedReady=$downloadedPagesReady"
            }
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
        val remoteStartNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        val pageListCacheStartNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        val cachedPages = try {
            chapterCache.getPageListFromCache(chapter)
        } catch (_: Exception) {
            null
        }
        if (BuildConfig.DEBUG) {
            logcat {
                "OCR pagelist chapter=${chapter.id} " +
                    "hit=${cachedPages != null} " +
                    "elapsedMs=${(System.nanoTime() - pageListCacheStartNs) / 1_000_000}"
            }
        }
        val networkStartNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        // Serialize concurrent same-chapter resolves so only the first performs
        // the fetch; the rest reuse the memoized list. Failures/cancellation
        // propagate without storing, so the next resolve retries.
        val fetched = remoteResolveMutex.withLock {
            val memoized = memoizedRemotePages
            if (memoized != null && memoized.chapterId == chapter.id) {
                if (BuildConfig.DEBUG) {
                    logcat {
                        "OCR resolveRemote memo hit chapter=${chapter.id} " +
                            "pages=${memoized.pages.size}"
                    }
                }
                return@withLock memoized.pages
            }
            val fresh = cachedPages ?: source.getPageList(chapter.toSChapter())
            if (fresh.isNotEmpty()) {
                memoizedRemotePages = MemoizedRemotePages(chapter.id, fresh)
            }
            fresh
        }
        if (BuildConfig.DEBUG) {
            logcat {
                "OCR resolveRemote chapter=${chapter.id} " +
                    "networkFetch=${cachedPages == null} " +
                    "networkMs=${(System.nanoTime() - networkStartNs) / 1_000_000} " +
                    "totalMs=${(System.nanoTime() - remoteStartNs) / 1_000_000}"
            }
        }
        val pages = fetched
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
        val startNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        if (BuildConfig.DEBUG) {
            logcat { "OCR acquisition start page=${page.index} acquisitionNs=$startNs" }
        }
        try {
            return withIOContext {
                if (page.imageUrl.isNullOrBlank()) {
                    val imageUrlStartNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                    if (BuildConfig.DEBUG) {
                        logcat {
                            "OCR getImageUrl start page=${page.index} acquisitionNs=$startNs startNs=$imageUrlStartNs"
                        }
                    }
                    try {
                        page.imageUrl = source.getImageUrl(page)
                    } finally {
                        if (BuildConfig.DEBUG) {
                            val endNs = System.nanoTime()
                            logcat {
                                "OCR getImageUrl end page=${page.index} acquisitionNs=$startNs " +
                                    "startNs=$imageUrlStartNs endNs=$endNs elapsedNs=${endNs - imageUrlStartNs}"
                            }
                        }
                    }
                }
                // The viewer (Coil/HttpPageLoader) already saved the displayed
                // page's image into ChapterCache; read it from disk instead of a
                // cacheless re-download when present.
                val imageUrl = page.imageUrl
                if (imageUrl != null && chapterCache.isImageInCache(imageUrl)) {
                    if (BuildConfig.DEBUG) {
                        logcat { "OCR acquisition branch=cache page=${page.index} acquisitionNs=$startNs" }
                    }
                    val file = chapterCache.getImageFile(imageUrl)
                    try {
                        val decodeStartNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                        if (BuildConfig.DEBUG) {
                            logcat {
                                "OCR cache stream decode start page=${page.index} acquisitionNs=$startNs " +
                                    "startNs=$decodeStartNs"
                            }
                        }
                        try {
                            file.inputStream().use { decode(it) }
                        } finally {
                            if (BuildConfig.DEBUG) {
                                val endNs = System.nanoTime()
                                logcat {
                                    "OCR cache stream decode end page=${page.index} acquisitionNs=$startNs " +
                                        "startNs=$decodeStartNs endNs=$endNs elapsedNs=${endNs - decodeStartNs}"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        logcat(LogPriority.DEBUG) { "OCR cached image decode failed, refetching page=${page.index}" }
                        val imageStartNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                        if (BuildConfig.DEBUG) {
                            logcat {
                                "OCR acquisition branch=fallback getImage start page=${page.index} " +
                                    "acquisitionNs=$startNs startNs=$imageStartNs"
                            }
                        }
                        source.getImage(page).use { response ->
                            if (BuildConfig.DEBUG) {
                                val endNs = System.nanoTime()
                                logcat {
                                    "OCR getImage response headers page=${page.index} acquisitionNs=$startNs " +
                                        "startNs=$imageStartNs endNs=$endNs elapsedNs=${endNs - imageStartNs}"
                                }
                            }
                            val decodeStartNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                            if (BuildConfig.DEBUG) {
                                logcat {
                                    "OCR network stream decode start page=${page.index} acquisitionNs=$startNs " +
                                        "startNs=$decodeStartNs"
                                }
                            }
                            try {
                                decode(response.body.byteStream())
                            } finally {
                                if (BuildConfig.DEBUG) {
                                    val endNs = System.nanoTime()
                                    logcat {
                                        "OCR network stream decode end page=${page.index} acquisitionNs=$startNs " +
                                            "startNs=$decodeStartNs endNs=$endNs elapsedNs=${endNs - decodeStartNs}"
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val imageStartNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                    if (BuildConfig.DEBUG) {
                        logcat {
                            "OCR acquisition branch=network getImage start page=${page.index} acquisitionNs=$startNs"
                        }
                    }
                    source.getImage(page).use { response ->
                        if (BuildConfig.DEBUG) {
                            val endNs = System.nanoTime()
                            logcat {
                                "OCR getImage response headers page=${page.index} acquisitionNs=$startNs " +
                                    "startNs=$imageStartNs endNs=$endNs elapsedNs=${endNs - imageStartNs}"
                            }
                        }
                        val decodeStartNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                        if (BuildConfig.DEBUG) {
                            logcat {
                                "OCR network stream decode start page=${page.index} acquisitionNs=$startNs startNs=$decodeStartNs"
                            }
                        }
                        try {
                            decode(response.body.byteStream())
                        } finally {
                            if (BuildConfig.DEBUG) {
                                val endNs = System.nanoTime()
                                logcat {
                                    "OCR network stream decode end page=${page.index} acquisitionNs=$startNs " +
                                        "startNs=$decodeStartNs endNs=$endNs elapsedNs=${endNs - decodeStartNs}"
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            if (BuildConfig.DEBUG) {
                val endNs = System.nanoTime()
                logcat {
                    "OCR acquisition end page=${page.index} acquisitionNs=$startNs " +
                        "endNs=$endNs elapsedNs=${endNs - startNs}"
                }
            }
        }
    }
}

private val DOWNLOAD_WAIT_TIMEOUT = 15.seconds

private data class MemoizedRemotePages(
    val chapterId: Long,
    val pages: List<Page>,
)

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
