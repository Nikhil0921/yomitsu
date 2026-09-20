package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.ocr.toOcrImage
import eu.kanade.tachiyomi.util.system.activeNetworkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.domain.ocr.interactor.ClearCachedChapterOcr
import mihon.domain.ocr.interactor.ScanPageOcr
import mihon.domain.ocr.interactor.WithOcrScanSession
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class OcrChapterScanner(
    private val context: Context,
    private val getChapter: GetChapter,
    private val getManga: GetManga,
    private val clearCachedChapterOcr: ClearCachedChapterOcr,
    private val withOcrScanSession: WithOcrScanSession,
    private val scanPageOcr: ScanPageOcr,
    private val pageSourceResolver: OcrPageSourceResolver,
    private val downloadPreferences: DownloadPreferences,
    private val pageScanTimeout: Duration = PAGE_SCAN_TIMEOUT,
) {
    suspend fun scanChapter(
        chapterId: Long,
        onProgress: (OcrChapterScanProgress) -> Unit,
        onComplete: (OcrChapterScanProgress) -> Unit,
        onError: (OcrChapterScanError) -> Unit,
        onCacheStateChanged: (chapterId: Long, hasResults: Boolean) -> Unit = { _, _ -> },
    ): Boolean {
        val chapter = getChapter.await(chapterId)
        if (chapter == null) {
            onError(
                OcrChapterScanError(
                    mangaId = null,
                    mangaTitle = null,
                    chapterId = chapterId,
                    chapterName = chapterId.toString(),
                    failure = OcrScanFailure.ChapterNotFound,
                ),
            )
            return false
        }

        val manga = getManga.await(chapter.mangaId)
        if (manga == null) {
            onError(
                OcrChapterScanError(
                    mangaId = chapter.mangaId,
                    mangaTitle = null,
                    chapterId = chapterId,
                    chapterName = chapter.name,
                    failure = OcrScanFailure.MangaNotFound,
                ),
            )
            return false
        }

        return try {
            withOcrScanSession.await {
                clearCachedChapterOcr.await(chapterId)
                onCacheStateChanged(chapterId, false)

                val resolvedPages = pageSourceResolver.resolve(manga, chapter)
                resolvedPages.use pageScope@{ pages ->
                    if (pages.pages.isEmpty()) {
                        onError(
                            OcrChapterScanError(
                                mangaId = manga.id,
                                mangaTitle = manga.title,
                                chapterId = chapterId,
                                chapterName = chapter.name,
                                failure = OcrScanFailure.NoPages,
                            ),
                        )
                        false
                    } else {
                        val totalPages = pages.pages.size
                        var lastProgress = OcrChapterScanProgress(
                            mangaId = manga.id,
                            mangaTitle = manga.title,
                            chapterId = chapterId,
                            chapterName = chapter.name,
                            processedPages = 0,
                            totalPages = totalPages,
                        )

                        onProgress(lastProgress)

                        try {
                            var chapterHasCachedResults = false
                            var skippedPages = 0
                            var processedPages = 0
                            for (page in pages.pages) {
                                val networkError = checkNetworkState()
                                if (networkError != null) {
                                    clearCachedChapterOcr.await(chapterId)
                                    onCacheStateChanged(chapterId, false)
                                    onError(
                                        OcrChapterScanError(
                                            mangaId = manga.id,
                                            mangaTitle = manga.title,
                                            chapterId = chapterId,
                                            chapterName = chapter.name,
                                            failure = OcrScanFailure.Unexpected(networkError),
                                        ),
                                    )
                                    return@pageScope false
                                }

                                var bitmap: Bitmap? = null
                                var pageScanned = false
                                try {
                                    bitmap = page.openBitmap()
                                    if (bitmap == null) {
                                        logcat(LogPriority.WARN) {
                                            "Unable to decode page ${page.pageIndex + 1} in chapter $chapterId; skipping"
                                        }
                                    } else {
                                        pageScanned = withTimeoutOrNull(pageScanTimeout) {
                                            scanPageOcr.await(chapterId, page.pageIndex, bitmap.toOcrImage())
                                        } != null
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    logcat(LogPriority.ERROR, e) {
                                        "Failed to scan page ${page.pageIndex + 1} in chapter $chapterId; skipping"
                                    }
                                } finally {
                                    if (bitmap != null && !bitmap.isRecycled) {
                                        bitmap.recycle()
                                    }
                                }

                                if (pageScanned) {
                                    processedPages++
                                    if (!chapterHasCachedResults) {
                                        chapterHasCachedResults = true
                                        onCacheStateChanged(chapterId, true)
                                    }
                                    lastProgress = lastProgress.copy(processedPages = processedPages)
                                    onProgress(lastProgress)
                                } else {
                                    skippedPages++
                                }
                            }

                            if (skippedPages > 0) {
                                logcat(LogPriority.WARN) {
                                    "OCR scan of chapter $chapterId finished with $skippedPages/$totalPages pages skipped"
                                }
                            }

                            onComplete(lastProgress)
                            true
                        } catch (e: Throwable) {
                            handleUnexpectedFailure(
                                chapterId = chapterId,
                                chapterName = chapter.name,
                                mangaId = manga.id,
                                mangaTitle = manga.title,
                                throwable = e,
                                logMessage = "Failed to scan OCR",
                                onError = onError,
                                onCacheStateChanged = onCacheStateChanged,
                            )
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            handleUnexpectedFailure(
                chapterId = chapterId,
                chapterName = chapter.name,
                mangaId = manga.id,
                mangaTitle = manga.title,
                throwable = e,
                logMessage = "Failed to start OCR scan",
                onError = onError,
                onCacheStateChanged = onCacheStateChanged,
            )
        }
    }

    private suspend fun handleUnexpectedFailure(
        chapterId: Long,
        chapterName: String,
        mangaId: Long,
        mangaTitle: String,
        throwable: Throwable,
        logMessage: String,
        onError: (OcrChapterScanError) -> Unit,
        onCacheStateChanged: (chapterId: Long, hasResults: Boolean) -> Unit,
    ): Boolean {
        if (throwable is CancellationException) {
            throw throwable
        }

        logcat(LogPriority.ERROR, throwable) { "$logMessage for chapterId=$chapterId" }
        clearCachedChapterOcr.await(chapterId)
        onCacheStateChanged(chapterId, false)
        onError(
            OcrChapterScanError(
                mangaId = mangaId,
                mangaTitle = mangaTitle,
                chapterId = chapterId,
                chapterName = chapterName,
                failure = OcrScanFailure.Unexpected(throwable.message),
            ),
        )
        return false
    }

    private fun checkNetworkState(): String? {
        val state = context.activeNetworkState()
        return if (state.isOnline) {
            val requireWifi = downloadPreferences.downloadOnlyOverWifi.get()
            if (requireWifi && !state.isWifi) {
                context.getString(R.string.download_notifier_text_only_wifi)
            } else {
                null
            }
        } else {
            context.getString(R.string.download_notifier_no_network)
        }
    }

    companion object {
        internal val PAGE_SCAN_TIMEOUT = 90.seconds
    }
}

internal data class OcrChapterScanProgress(
    val mangaId: Long,
    val mangaTitle: String,
    val chapterId: Long,
    val chapterName: String,
    val processedPages: Int,
    val totalPages: Int,
)

internal data class OcrChapterScanError(
    val mangaId: Long?,
    val mangaTitle: String?,
    val chapterId: Long,
    val chapterName: String,
    val failure: OcrScanFailure,
)

internal sealed interface OcrScanFailure {
    data object ChapterNotFound : OcrScanFailure

    data object MangaNotFound : OcrScanFailure

    data object NoPages : OcrScanFailure

    data class Unexpected(val message: String?) : OcrScanFailure
}
