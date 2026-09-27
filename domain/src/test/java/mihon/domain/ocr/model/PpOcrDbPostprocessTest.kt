package mihon.domain.ocr.model

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class PpOcrDbPostprocessTest {

    private fun blob(
        probability: FloatArray,
        width: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        value: Float,
    ) {
        for (y in y0..y1) {
            for (x in x0..x1) {
                probability[y * width + x] = value
            }
        }
    }

    /** Blob rectangle in page pixels; a map pixel at index x covers [x, x + 1] of the scaled page. */
    private fun rectPx(x0: Int, y0: Int, x1: Int, y1: Int, scale: Float) =
        floatArrayOf(x0 * scale, y0 * scale, (x1 + 1) * scale, (y1 + 1) * scale)

    @Test
    fun `blank map yields no boxes`() {
        PpOcrDbPostprocess.boxes(FloatArray(64 * 64), 64, 64, 1000f, 1000f) shouldBe emptyList()
    }

    @Test
    fun `a single high confidence blob becomes one valid box that covers the blob`() {
        val probability = FloatArray(32 * 32)
        blob(probability, 32, x0 = 4, y0 = 8, x1 = 15, y1 = 11, value = 0.9f)

        val boxes = PpOcrDbPostprocess.boxes(probability, 32, 32, 320f, 320f)

        boxes.size shouldBe 1
        val box = boxes.single()
        box.isValid() shouldBe true
        val rect = rectPx(4, 8, 15, 11, scale = 10f)
        (box.left * 320f) shouldBeLessThanOrEqual rect[0]
        (box.top * 320f) shouldBeLessThanOrEqual rect[1]
        (box.right * 320f) shouldBeGreaterThanOrEqual rect[2]
        (box.bottom * 320f) shouldBeGreaterThanOrEqual rect[3]
    }

    @Test
    fun `unclip grows the box on every side`() {
        val probability = FloatArray(32 * 32)
        blob(probability, 32, x0 = 10, y0 = 10, x1 = 13, y1 = 13, value = 0.9f)

        val box = PpOcrDbPostprocess.boxes(probability, 32, 32, 320f, 320f).single()
        val rect = rectPx(10, 10, 13, 13, scale = 10f)

        (box.left * 320f) shouldBeLessThan rect[0]
        (box.top * 320f) shouldBeLessThan rect[1]
        (box.right * 320f) shouldBeGreaterThan rect[2]
        (box.bottom * 320f) shouldBeGreaterThan rect[3]
    }

    @Test
    fun `blobs above the binary threshold but below the box threshold are discarded`() {
        val probability = FloatArray(32 * 32)
        blob(probability, 32, x0 = 4, y0 = 8, x1 = 15, y1 = 11, value = 0.4f)

        PpOcrDbPostprocess.boxes(probability, 32, 32, 320f, 320f) shouldBe emptyList()
    }

    @Test
    fun `blobs below the binary threshold never form`() {
        val probability = FloatArray(32 * 32)
        blob(probability, 32, x0 = 4, y0 = 8, x1 = 15, y1 = 11, value = 0.2f)

        PpOcrDbPostprocess.boxes(probability, 32, 32, 320f, 320f) shouldBe emptyList()
    }

    @Test
    fun `two separate blobs become two boxes sorted top to bottom`() {
        val probability = FloatArray(64 * 64)
        // Lower blob added first on purpose: the result must be positional, not discovery order.
        blob(probability, 64, x0 = 8, y0 = 40, x1 = 20, y1 = 44, value = 0.9f)
        blob(probability, 64, x0 = 30, y0 = 6, x1 = 44, y1 = 9, value = 0.9f)

        val boxes = PpOcrDbPostprocess.boxes(probability, 64, 64, 640f, 640f)

        boxes.size shouldBe 2
        boxes[0].top shouldBeLessThan boxes[1].top
        (boxes[0].top * 640f) shouldBeLessThan (boxes[0].bottom * 640f)
    }

    @Test
    fun `boxes too small to be text are dropped`() {
        val probability = FloatArray(64 * 64)
        probability[32 * 64 + 32] = 0.9f

        // One map pixel of 64 across a 640px page is 10px, under the minimum side fraction.
        PpOcrDbPostprocess.boxes(probability, 64, 64, 640f, 640f) shouldBe emptyList()
    }

    @Test
    fun `a blob touching the page edge is clamped into the unit square`() {
        val probability = FloatArray(32 * 32)
        blob(probability, 32, x0 = 0, y0 = 0, x1 = 9, y1 = 3, value = 0.95f)

        val box = PpOcrDbPostprocess.boxes(probability, 32, 32, 320f, 320f).single()

        box.left shouldBe 0f
        box.top shouldBe 0f
        (box.right * 320f) shouldBeGreaterThan 90f
        box.right shouldBeLessThanOrEqual 1f
        box.bottom shouldBeLessThanOrEqual 1f
    }

    @Test
    fun `box count is capped and the cap keeps reading order`() {
        val probability = FloatArray(128 * 128)
        var y = 2
        while (y < 124) {
            blob(probability, 128, x0 = 2, y0 = y, x1 = 122, y1 = y + 3, value = 0.9f)
            y += 6
        }

        val boxes = PpOcrDbPostprocess.boxes(
            probability = probability,
            mapWidth = 128,
            mapHeight = 128,
            imageWidth = 1280f,
            imageHeight = 1280f,
            config = PpOcrDbConfig(maxBoxes = 5),
        )

        boxes.size shouldBe 5
        boxes.map { it.top } shouldContainExactly boxes.map { it.top }.sorted()
    }

    @Test
    fun `touching blobs stay one box`() {
        val probability = FloatArray(32 * 32)
        blob(probability, 32, x0 = 4, y0 = 8, x1 = 19, y1 = 11, value = 0.9f)

        PpOcrDbPostprocess.boxes(probability, 32, 32, 320f, 320f).size shouldBe 1
    }

    @Test
    fun `diagonally adjacent pixels are one blob under 8-connectivity`() {
        val probability = FloatArray(32 * 32)
        probability[10 * 32 + 10] = 0.9f
        probability[10 * 32 + 11] = 0.9f
        probability[11 * 32 + 11] = 0.9f
        probability[11 * 32 + 12] = 0.9f

        PpOcrDbPostprocess.boxes(probability, 32, 32, 320f, 320f).size shouldBe 1
    }

    @Test
    fun `a white gap splits two blobs`() {
        val probability = FloatArray(32 * 32)
        blob(probability, 32, x0 = 4, y0 = 8, x1 = 12, y1 = 11, value = 0.9f)
        blob(probability, 32, x0 = 14, y0 = 8, x1 = 22, y1 = 11, value = 0.9f)

        PpOcrDbPostprocess.boxes(probability, 32, 32, 320f, 320f).size shouldBe 2
    }

    @Test
    fun `config defaults match the upstream dbnet postprocess`() {
        val config = PpOcrDbConfig()
        config.binaryThreshold shouldBe 0.3f
        config.boxThreshold shouldBe 0.6f
        config.unclipRatio shouldBe 1.6f
        config.maxBoxes shouldBe 1000
    }

    @Test
    fun `a map shorter than declared does not read out of bounds`() {
        val probability = FloatArray(9) { 0.9f }

        PpOcrDbPostprocess.boxes(probability, 32, 32, 320f, 320f) shouldBe emptyList()
    }

    @Test
    fun `reading order is top to bottom then left to right within a row`() {
        val probability = FloatArray(64 * 64)
        blob(probability, 64, x0 = 6, y0 = 6, x1 = 20, y1 = 9, value = 0.9f)
        blob(probability, 64, x0 = 6, y0 = 20, x1 = 20, y1 = 23, value = 0.9f)
        blob(probability, 64, x0 = 30, y0 = 6, x1 = 44, y1 = 9, value = 0.9f)

        val boxes = PpOcrDbPostprocess.boxes(probability, 64, 64, 640f, 640f)

        boxes.size shouldBe 3
        // Upper-left, upper-right, then the lower one.
        boxes[0].left shouldBeLessThan boxes[1].left
        boxes[1].top shouldBeLessThan boxes[2].top
    }
}
