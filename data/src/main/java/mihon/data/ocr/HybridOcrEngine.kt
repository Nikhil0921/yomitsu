package mihon.data.ocr

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrPageText
import mihon.domain.ocr.model.OcrQualityRouter
import mihon.domain.ocr.model.OcrQualitySignals
import mihon.domain.ocr.model.OcrRegion
import mihon.domain.ocr.model.OcrRoute
import tachiyomi.core.common.util.system.logcat

/** Where a page's text actually came from; recorded in the log line and used by the caller. */
enum class OcrPageSource {
    LOCAL,
    CLOUD,
}

/** One region as the local pass recognised it, with the confidence the router needs. */
internal data class OcrLocalRegion(
    val text: String,
    val confidence: Float,
    val boundingBox: OcrBoundingBox,
)

/** A recognised page plus the route that produced it. */
internal data class OcrPageRegions(
    val regions: List<OcrLocalRegion>,
    val source: OcrPageSource,
)

/**
 * Adaptive hybrid: the local PP-OCRv5 pass first, a whole-page cloud escalation when the local
 * result cannot be trusted.
 *
 * The decision itself is [OcrQualityRouter] — pure, in `:domain`, unit-tested there. This class owns
 * only what needs a `Bitmap`: running the local pass, measuring what came back, and handing the
 * page to the cloud exactly once.
 *
 * `ponytail:` on escalation the local result is **discarded**, not merged. `OcrRegion.order` is
 * reading-order truth for `SentenceSegmenter`, `SpeechPipeline` and tap-highlight, and two
 * independent detectors cannot produce one coherent order. Merge local and cloud boxes only if
 * measurement ever shows mixed-quality pages are the common case rather than the exception.
 */
internal class HybridOcrEngine(
    private val local: PpOcrV5Engine,
    private val confidenceFloor: Float = OcrQualityRouter.DEFAULT_CONFIDENCE_FLOOR,
) {
    /**
     * Runs the local pass over [image], then returns either its regions or the cloud's. [cloud] is
     * invoked only on escalation, so an accepted page costs no network at all.
     *
     * A local failure is an **escalation trigger**, never a page failure: if PP-OCRv5 cannot start
     * (missing or mismatched weights, an ORT error) the whole page goes to the cloud rather than
     * failing the page. Cancellation is rethrown so it still cancels the caller's scope.
     */
    suspend fun recognizePage(image: Bitmap, cloud: suspend () -> List<OcrRegion>): OcrPageRegions {
        val localRegions = runCatchingLocal { recognizeLocally(image) }

        if (localRegions.isFailure) {
            logcat(LogPriority.WARN, localRegions.failureOrNull()) {
                "OCR(hybrid) local engine unavailable, escalating the page to cloud: " +
                    (localRegions.failureOrNull()?.message ?: "unknown")
            }
            return cloudResult(cloud())
        }

        val recognised = localRegions.value
        val signals = OcrQualitySignals.from(
            regions = recognised.map { OcrPageText(it.text, it.confidence) },
            confidenceAvailable = true,
            meanBoxHeightNorm = recognised
                .map { it.boundingBox.bottom - it.boundingBox.top }
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?.toFloat()
                ?: 0f,
        )

        return when (OcrQualityRouter.route(signals, confidenceFloor)) {
            OcrRoute.ACCEPT_LOCAL -> {
                logcat(LogPriority.DEBUG) {
                    "OCR(hybrid) local accepted regions=${recognised.size} " +
                        "conf=${signals.meanCharConfidence} chars=${signals.meanCharsPerRegion}"
                }
                OcrPageRegions(recognised, OcrPageSource.LOCAL)
            }

            OcrRoute.ESCALATE_CLOUD -> {
                logcat(LogPriority.WARN) {
                    "OCR(hybrid) escalating to cloud: regions=${signals.regionCount} " +
                        "conf=${signals.meanCharConfidence} chars=${signals.meanCharsPerRegion} " +
                        "boxH=${signals.meanBoxHeightNorm} nonLatin=${signals.nonLatinCharRatio} " +
                        "garbage=${signals.blankOrGarbageRatio}"
                }
                cloudResult(cloud())
            }
        }
    }

    private suspend fun cloudResult(regions: List<OcrRegion>) = OcrPageRegions(
        regions = regions.map { OcrLocalRegion(text = it.text, confidence = 1f, boundingBox = it.boundingBox) },
        source = OcrPageSource.CLOUD,
    )

    /** Cancellation is control flow and must survive; everything else becomes a local failure. */
    private suspend fun runCatchingLocal(block: suspend () -> List<OcrLocalRegion>): LocalAttempt {
        return try {
            LocalAttempt(value = block())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            LocalAttempt(failure = error)
        }
    }

    private data class LocalAttempt(
        val value: List<OcrLocalRegion> = emptyList(),
        val failure: Throwable? = null,
    ) {
        val isFailure: Boolean get() = failure != null
        fun failureOrNull(): Throwable? = failure
    }

    private suspend fun recognizeLocally(image: Bitmap): List<OcrLocalRegion> {
        val boxes = local.detectTextRegions(image)
        return boxes.mapNotNull { box ->
            val crop = crop(image, box) ?: return@mapNotNull null
            try {
                val recognition = local.recognizeRegion(crop)
                if (recognition.text.isBlank()) {
                    null
                } else {
                    OcrLocalRegion(recognition.text, recognition.confidence, box)
                }
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }
        }
    }

    private fun crop(image: Bitmap, box: OcrBoundingBox): Bitmap? {
        val left = (box.left * image.width).toInt().coerceIn(0, image.width - 1)
        val top = (box.top * image.height).toInt().coerceIn(0, image.height - 1)
        val right = (box.right * image.width).toInt().coerceIn(left + 1, image.width)
        val bottom = (box.bottom * image.height).toInt().coerceIn(top + 1, image.height)
        if (right <= left || bottom <= top) return null
        return Bitmap.createBitmap(image, left, top, right - left, bottom - top)
    }
}
