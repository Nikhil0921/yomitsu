package mihon.domain.ocr.model

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class PpOcrPreprocessTest {

    @Test
    fun `detection input is scaled so the long side is at most the limit`() {
        PpOcrPreprocess.detectionInputSize(2000, 1000) shouldBe OcrTensorSize(960, 480)
    }

    @Test
    fun `detection input rounds both sides up to a multiple of 32`() {
        val size = PpOcrPreprocess.detectionInputSize(1000, 700)
        size.width % 32 shouldBe 0
        size.height % 32 shouldBe 0
    }

    @Test
    fun `detection input never goes below one 32 pixel block`() {
        PpOcrPreprocess.detectionInputSize(8, 8) shouldBe OcrTensorSize(32, 32)
    }

    @Test
    fun `detection input keeps a small page at its own size`() {
        val size = PpOcrPreprocess.detectionInputSize(320, 240)
        size.width shouldBe 320
        size.height shouldBe 256
    }

    @Test
    fun `detection input upsamples a tiny page to the block size`() {
        PpOcrPreprocess.detectionInputSize(20, 40) shouldBe OcrTensorSize(32, 64)
    }

    @Test
    fun `a custom limit is honoured`() {
        PpOcrPreprocess.detectionInputSize(4000, 2000, maxSide = 640) shouldBe OcrTensorSize(640, 320)
    }

    @Test
    fun `recognition input is always 48 pixels tall`() {
        PpOcrPreprocess.recognitionInputSize(400, 100).height shouldBe 48
    }

    @Test
    fun `recognition size is named so width and height cannot be transposed`() {
        // The first device run fed the recognizer "Got: 192 Expected: 48" because two functions
        // returned Pairs in opposite orders. Named fields make that mistake impossible to express.
        val size = PpOcrPreprocess.recognitionInputSize(400, 100)
        size.width shouldBe 192
        size.height shouldBe 48
    }

    @Test
    fun `recognition width follows the aspect ratio`() {
        // 400x100 is 4:1, so 48 tall means 192 wide.
        PpOcrPreprocess.recognitionInputSize(400, 100) shouldBe OcrTensorSize(192, 48)
    }

    @Test
    fun `recognition width is clamped to the model maximum`() {
        // 4000x100 is 40:1, which would be 1920 wide; the rec head stops at 320.
        PpOcrPreprocess.recognitionInputSize(4000, 100) shouldBe OcrTensorSize(320, 48)
    }

    @Test
    fun `recognition width is clamped to the model minimum`() {
        // 40x400 is 1:10, which would be 5 wide; the rec head needs at least 48.
        PpOcrPreprocess.recognitionInputSize(40, 400) shouldBe OcrTensorSize(48, 48)
    }

    @Test
    fun `recognition width is a multiple of the 8x temporal stride`() {
        listOf(400 to 100, 333 to 97, 1000 to 40, 57 to 213).forEach { (w, h) ->
            val size = PpOcrPreprocess.recognitionInputSize(w, h)
            size.width % 8 shouldBe 0
        }
    }

    @Test
    fun `a degenerate crop stays inside the model bounds instead of dividing by zero`() {
        val size = PpOcrPreprocess.recognitionInputSize(100, 0)
        size.width shouldBeGreaterThanOrEqual PpOcrPreprocess.RECOGNITION_MIN_WIDTH
        size.width shouldBeLessThanOrEqual PpOcrPreprocess.RECOGNITION_MAX_WIDTH
        size.width % 8 shouldBe 0
        size.height shouldBe PpOcrPreprocess.RECOGNITION_HEIGHT

        val det = PpOcrPreprocess.detectionInputSize(100, 0)
        det.width % 32 shouldBe 0
        det.height shouldBe 32
    }
}
