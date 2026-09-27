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
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class OcrChapterScanner(
    private val context: Context,
    private val getChapter: GetChapter,
    private val getManga: GetManga,
    /**
     * Deliberately NOT used to clear anything. Injected only so the no-wipe invariant is a real
     * behavioural test rather than a vacuous one — `OcrChapterScannerTest` verifies zero calls.
     * The scanner used to wipe the chapter's whole cache at scan start and on every failure path,
     * which destroyed its own work and left ERROR chapters with an empty cache (audit R1).
     */
    @Suppress("unused")
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
                // The OCR cache is accumulated good work and this scan only ADDS to it. It used to
                // be wiped here at scan start and again on every failure path below, which
                // destroyed the pages the run had already scanned, left a chapter that ended in
                // ERROR with an empty cache, and made Read-Aloud re-OCR every page of it on demand.
                // Device proof: docs/audits/full-ocr-pipeline-audit.md §1 (R1).
                // Bounded: this is the chapter's first network call and it had no limit at all.
                val resolvedPages = withTimeoutOrNull(pageScanTimeout) {
                    pageSourceResolver.resolve(manga, chapter)
                }
                if (resolvedPages == null) {
                    logcat(LogPriority.WARN) {
                        "Resolving the page list of chapter $chapterId timed out after $pageScanTimeout"
                    }
                    onError(
                        OcrChapterScanError(
                            mangaId = manga.id,
                            mangaTitle = manga.title,
                            chapterId = chapterId,
                            chapterName = chapter.name,
                            failure = OcrScanFailure.PageListTimeout,
                        ),
                    )
                    false
                } else {
                    resolvedPages.use { resolved ->
                        scanPages(
                            pageInputs = resolved.pages,
                            chapterId = chapterId,
                            manga = manga,
                            chapter = chapter,
                            onProgress = onProgress,
                            onComplete = onComplete,
                            onError = onError,
                            onCacheStateChanged = onCacheStateChanged,
                        )
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
            )
        }
    }

    /**
     * The page loop of one chapter. Split out of [scanChapter] so the page-list timeout guard can
     * report and bail out without an early return through `WithOcrScanSession.await`, which is not
     * an inline function and therefore has no non-local return.
     */
    private suspend fun scanPages(
        pageInputs: List<OcrPageInput>,
        chapterId: Long,
        manga: Manga,
        chapter: Chapter,
        onProgress: (OcrChapterScanProgress) -> Unit,
        onComplete: (OcrChapterScanProgress) -> Unit,
        onError: (OcrChapterScanError) -> Unit,
        onCacheStateChanged: (chapterId: Long, hasResults: Boolean) -> Unit,
    ): Boolean {
        if (pageInputs.isEmpty()) {
            onError(
                OcrChapterScanError(
                    mangaId = manga.id,
                    mangaTitle = manga.title,
                    chapterId = chapterId,
                    chapterName = chapter.name,
                    failure = OcrScanFailure.NoPages,
                ),
            )
            return false
        }
        val totalPages = pageInputs.size
        var lastProgress = OcrChapterScanProgress(
            mangaId = manga.id,
            mangaTitle = manga.title,
            chapterId = chapterId,
            chapterName = chapter.name,
            processedPages = 0,
            totalPages = totalPages,
        )

        onProgress(lastProgress)

        return try {
            var chapterHasCachedResults = false
            var skippedPages = 0
            var processedPages = 0
            for (page in pageInputs) {
                val networkError = checkNetworkState()
                if (networkError != null) {
                    // No wipe: the pages already scanned stay cached. Wiping here used to throw
                    // away the whole run over a dropped connection.
                    onError(
                        OcrChapterScanError(
                            mangaId = manga.id,
                            mangaTitle = manga.title,
                            chapterId = chapterId,
                            chapterName = chapter.name,
                            failure = OcrScanFailure.Unexpected(networkError),
                        ),
                    )
                    return false
                }

                var bitmap: Bitmap? = null
                var pageScanned = false
                try {
                    // Bounded: openBitmap reaches the source with the SOURCE's own timeouts, so an
                    // unbounded wait here stalled the whole chapter scan (audit F1.5). Measured
                    // 7.1 s on a healthy network.
                    bitmap = withTimeoutOrNull(pageScanTimeout) { page.openBitmap() }
                    if (bitmap == null) {
                        logcat(LogPriority.WARN) {
                            "Page ${page.pageIndex + 1} of chapter $chapterId has no usable image " +
                                "(timed out after $pageScanTimeout, or it would not decode); skipping"
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
                onComplete(lastProgress)
                onError(
                    OcrChapterScanError(
                        mangaId = manga.id,
                        mangaTitle = manga.title,
                        chapterId = chapterId,
                        chapterName = chapter.name,
                        failure = OcrScanFailure.PagesSkipped(
                            skipped = skippedPages,
                            total = totalPages,
                        ),
                    ),
                )
                false
            } else {
                onComplete(lastProgress)
                true
            }
        } catch (e: Throwable) {
            handleUnexpectedFailure(
                chapterId = chapterId,
                chapterName = chapter.name,
                mangaId = manga.id,
                mangaTitle = manga.title,
                throwable = e,
                logMessage = "Failed to scan OCR",
                onError = onError,
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
    ): Boolean {
        if (throwable is CancellationException) {
            throw throwable
        }

        logcat(LogPriority.ERROR, throwable) { "$logMessage for chapterId=$chapterId" }
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

    /** The page list never came back within the per-page budget; the chapter cannot be scanned. */
    data object PageListTimeout : OcrScanFailure

    /**
     * The chapter has holes in its OCR cache. Reported as a failure on purpose: the scan used to
     * return success here, which made the queue drop the entry as done while pages were missing,
     * so Read-Aloud only discovered the gaps later, one cold network round trip at a time
     * (docs/audits/full-ocr-pipeline-audit.md §2, R4). With the automatic retry budget, the retry
     * only re-runs the missing pages — the scanned ones are served from the cache.
     */
    data class PagesSkipped(val skipped: Int, val total: Int) : OcrScanFailure

    data class Unexpected(val message: String?) : OcrScanFailure
}
