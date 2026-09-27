package mihon.domain.ocr.model

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Input geometry for the two PP-OCRv5 graphs. Pure arithmetic on purpose: the tensors themselves
 * are built in the data module from Android `Bitmap`s, but every decision about *how big* they
 * should be is testable here.
 */
object PpOcrPreprocess {

    /** DBNet needs both sides on a 32 pixel grid; its training range is 32..736 per side. */
    const val DETECTION_MULTIPLE = 32
    const val DETECTION_MAX_SIDE = 960
    const val DETECTION_MIN_SIDE = 32

    /** SVTR_LCNet recognition head: fixed 48 pixel height, width clamped, 8x temporal stride. */
    const val RECOGNITION_HEIGHT = 48
    const val RECOGNITION_MIN_WIDTH = 48
    const val RECOGNITION_MAX_WIDTH = 320
    const val RECOGNITION_WIDTH_STRIDE = 8

    fun detectionInputSize(
        width: Int,
        height: Int,
        maxSide: Int = DETECTION_MAX_SIDE,
    ): OcrTensorSize {
        val safeWidth = max(width, 1)
        val safeHeight = max(height, 1)
        val longest = max(safeWidth, safeHeight)
        val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        val scaledWidth = (safeWidth * scale).roundToInt()
        val scaledHeight = (safeHeight * scale).roundToInt()
        return OcrTensorSize(width = roundUpToGrid(scaledWidth), height = roundUpToGrid(scaledHeight))
    }

    fun recognitionInputSize(
        width: Int,
        height: Int,
        targetHeight: Int = RECOGNITION_HEIGHT,
    ): OcrTensorSize {
        val safeHeight = max(height, 1)
        val aspect = width.toFloat() / safeHeight
        val rawWidth = (targetHeight * aspect).roundToInt()
        val clamped = rawWidth.coerceIn(RECOGNITION_MIN_WIDTH, RECOGNITION_MAX_WIDTH)
        // The head downsamples time by 8, so the width must stay on that grid or T is not integral.
        val aligned = ((clamped + RECOGNITION_WIDTH_STRIDE - 1) / RECOGNITION_WIDTH_STRIDE) *
            RECOGNITION_WIDTH_STRIDE
        return OcrTensorSize(
            width = aligned.coerceAtLeast(RECOGNITION_WIDTH_STRIDE),
            height = targetHeight,
        )
    }

    private fun roundUpToGrid(value: Int): Int {
        val grid = ((value + DETECTION_MULTIPLE - 1) / DETECTION_MULTIPLE) * DETECTION_MULTIPLE
        return max(grid, DETECTION_MIN_SIDE)
    }
}

/**
 * A tensor's spatial size, as **named fields**.
 *
 * Both preprocess functions used to return a `Pair<Int, Int>`, and the two used the opposite
 * order: `detectionInputSize` was `(width, height)` while `recognitionInputSize` was
 * `(height, width)`. Destructuring both as `(width, height)` therefore transposed every recognition
 * tensor, and the first device run fed the recognizer `Got: 192 Expected: 48` — the model pins its
 * height at 48. A `Pair` cannot express "which is which", so the ambiguity itself was the defect.
 */
data class OcrTensorSize(
    val width: Int,
    val height: Int,
)
