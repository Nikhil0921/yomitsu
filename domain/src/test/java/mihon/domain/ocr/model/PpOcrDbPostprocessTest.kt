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

        // The flood fill's own contract, pinned on its own: two components. boxes() then groups
        // same-line components into one line box, which is the fix for the per-glyph regions
        // behind "c-h-a-n-g-e-s" (audit R7).
        PpOcrDbPostprocess.components(probability, 32, 32).size shouldBe 2
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
    // ---- same-line box merging (audit R7: "c-h-a-n-g-e-s") ----

    private fun box(left: Float, top: Float, right: Float, bottom: Float) =
        OcrBoundingBox(left = left, top = top, right = right, bottom = bottom)

    /**
     * DB emits one box per connected component, so tracked display type and a low binary threshold
     * split a single word into one box PER GLYPH. Each glyph then becomes its own OcrRegion, and
     * SentenceSegmenter is forbidden from merging regions (prd F2), so Android TTS spells the word
     * out. Measured on hardware: 270 of 1098 recognition calls were a square `in=48x48` crop, which
     * is what recognitionInputSize produces for a box narrower than it is tall.
     */
    @Test
    fun `glyph boxes on one baseline merge into a single line box`() {
        val glyphs = listOf(
            box(0.10f, 0.20f, 0.13f, 0.24f),
            box(0.145f, 0.20f, 0.175f, 0.24f),
            box(0.19f, 0.205f, 0.225f, 0.245f),
        )

        val merged = PpOcrDbPostprocess.mergeSameLineBoxes(glyphs)

        merged.size shouldBe 1
        merged.single().left shouldBe 0.10f
        merged.single().right shouldBe 0.225f
    }

    /**
     * Measured on hardware 2026-09-28: 84 of 514 recognition calls were still a square
     * `in=48x48` crop after the first line-grouping fix, and 8 of 19 spoken pages still carried
     * single-glyph regions (page 1 = 8 of 9). A descender box ("g") is TALLER than its
     * x-height neighbour ("a"), so a gap bound taken from the shorter box shrinks below the real
     * inter-glyph gap and the two stop joining.
     *
     * Geometry: the tall box is 0.10 high and the short one 0.03, with a 0.05 gap between them.
     * 0.05 exceeds 0.8 x 0.03 (shorter box -> refuse) and clears 0.8 x 0.10 (taller box -> join).
     */
    @Test
    fun `a descender next to a short glyph still joins the line`() {
        val descender = box(0.10f, 0.20f, 0.14f, 0.30f)
        val short = box(0.19f, 0.205f, 0.23f, 0.235f)

        val merged = PpOcrDbPostprocess.mergeSameLineBoxes(listOf(descender, short))

        merged.size shouldBe 1
    }

    /**
     * The other half of the gap bound: it must not swallow a second speech bubble. Two 0.06-tall
     * boxes 0.20 apart leave a gap of 3.3x the taller box's height, so they stay apart at 0.8.
     *
     * The companion case — a wide inter-word gap that DOES join — was removed when the bound went
     * back from 1.5 to 0.8: it asserted the 1.5 behaviour, and the measurement that retired 1.5
     * (byte-identical region counts at both values) showed the bound was never the constraint.
     */
    @Test
    fun `a gap far beyond the ratio does not join`() {
        val left = box(0.05f, 0.20f, 0.11f, 0.26f)
        val right = box(0.31f, 0.20f, 0.37f, 0.26f)

        PpOcrDbPostprocess.mergeSameLineBoxes(listOf(left, right)).size shouldBe 2
    }

    @Test
    fun `a whole line box is left alone`() {
        val line = listOf(box(0.10f, 0.20f, 0.40f, 0.24f))

        PpOcrDbPostprocess.mergeSameLineBoxes(line) shouldBe line
    }

    @Test
    fun `two bubbles on one row stay separate`() {
        val bubbles = listOf(
            box(0.05f, 0.20f, 0.20f, 0.26f),
            box(0.60f, 0.20f, 0.75f, 0.26f),
        )

        PpOcrDbPostprocess.mergeSameLineBoxes(bubbles).size shouldBe 2
    }

    @Test
    fun `boxes stacked on different lines stay separate`() {
        val lines = listOf(
            box(0.10f, 0.20f, 0.40f, 0.24f),
            box(0.10f, 0.30f, 0.40f, 0.34f),
        )

        PpOcrDbPostprocess.mergeSameLineBoxes(lines).size shouldBe 2
    }

    @Test
    fun `merging keeps reading order after the sort`() {
        val boxes = listOf(
            box(0.10f, 0.30f, 0.13f, 0.34f),
            box(0.145f, 0.30f, 0.18f, 0.34f),
            box(0.10f, 0.10f, 0.30f, 0.14f),
        )

        val merged = PpOcrDbPostprocess.mergeSameLineBoxes(boxes)

        merged.size shouldBe 2
        merged.first().top shouldBe 0.10f
        merged.last().top shouldBeLessThanOrEqual 0.30f
    }

    @Test
    fun `boxes are merged by the end-to-end path`() {
        val width = 200
        val height = 100
        val scale = width / 1000f
        val probability = FloatArray(width * height)
        // Two glyph-sized blobs on one baseline, far enough apart that neither alone would be
        // recognised as a word.
        blob(probability, width, x0 = 10, y0 = 60, x1 = 22, y1 = 76, value = 0.9f)
        blob(probability, width, x0 = 28, y0 = 60, x1 = 40, y1 = 76, value = 0.9f)

        val boxes = PpOcrDbPostprocess.boxes(
            probability = probability,
            mapWidth = width,
            mapHeight = height,
            imageWidth = 1000f,
            imageHeight = 500f,
        )

        boxes.size shouldBe 1
    }
}
