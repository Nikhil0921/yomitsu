package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Downloads the PP-OCRv5 weights in the background. Mirrors [OcrScanJob] deliberately: same worker
 * shape, same unique-work policy, same foreground plumbing, reusing the OCR progress channel rather
 * than adding a second one for a single model.
 *
 * Retry policy: a transport failure is retried, an integrity failure (SHA-256 mismatch) is not —
 * retrying a corrupt mirror forever is the bug this avoids.
 */
internal class OcrModelDownloadJob(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_OCR_PROGRESS) {
            setContentTitle(applicationContext.stringResource(MR.strings.ocr_model_download_title))
            setSmallIcon(android.R.drawable.stat_sys_download)
        }.build()

        return ForegroundInfo(
            Notifications.ID_OCR_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        setForegroundSafely()
        return try {
            val manager = Injekt.get<OcrModelDownloadManager>()
            if (manager.downloader.download()) {
                Result.success()
            } else {
                val failure = manager.state.value
                logcat(LogPriority.WARN) {
                    "OCR model download failed: ${failure.errorMessage} retryable=${failure.errorRetryable}"
                }
                if (failure.errorRetryable) Result.retry() else Result.failure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "OCR model download worker failed; scheduling retry" }
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "OcrModelDownload"

        private fun createRequest() = OneTimeWorkRequestBuilder<OcrModelDownloadJob>()
            .addTag(TAG)
            .build()

        fun start(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, createRequest())
        }
    }
}
