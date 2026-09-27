package mihon.domain.ocr.model

import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Reader for the nested arrays ONNX Runtime returns from `OnnxValue.getValue()`.
 *
 * Ground truth from ORT 1.30.0 against the real exports (host-side probe):
 * `det` output `[1, 1, H, W]` comes back as `float[][][][]` and `rec` output `[1, T, C]` as
 * `float[][][]`. Both must be walked **positionally with checked `is Array<*>` tests**: a plain
 * `as? Array<...>` erases to `Object[]` and therefore *passes* on the wrong shape, which is how the
 * first version of the engine silently produced an all-zero probability map.
 */
@Execution(ExecutionMode.CONCURRENT)
class OcrTensorReaderTest {

    /** [1, 1, H, W] as ORT hands it back. */
    private fun detOutput(height: Int, width: Int, value: Float) =
        arrayOf(arrayOf(Array(height) { FloatArray(width) { value } }))

    /** [1, T, C] as ORT hands it back. */
    private fun recOutput(timeSteps: Int, classes: Int, value: Float) =
        arrayOf(Array(timeSteps) { FloatArray(classes) { value } })

    @Test
    fun `probability map flattens the four dimensional det output`() {
        val map = OcrTensorReader.probabilityMap(detOutput(2, 3, 0.5f), expectedSize = 6)

        map!!.size shouldBe 6
        map.all { it == 0.5f } shouldBe true
    }

    @Test
    fun `probability map returns null when the shape does not match the expectation`() {
        OcrTensorReader.probabilityMap(detOutput(2, 3, 0.5f), expectedSize = 5) shouldBe null
    }

    @Test
    fun `probability map returns null for a rec shaped value`() {
        // A 3-D rec output is not a det map; the reader must not half-read it.
        OcrTensorReader.probabilityMap(recOutput(4, 7, 0.5f), expectedSize = 28) shouldBe null
    }

    @Test
    fun `probability map returns null for a scalar or an unrelated type`() {
        OcrTensorReader.probabilityMap(0.5f, expectedSize = 1) shouldBe null
        OcrTensorReader.probabilityMap("not a tensor", expectedSize = 1) shouldBe null
    }

    @Test
    fun `probability map tolerates a ragged value instead of throwing`() {
        val ragged = arrayOf(arrayOf(arrayOf(FloatArray(2), FloatArray(1))))

        OcrTensorReader.probabilityMap(ragged, expectedSize = 3) shouldBe null
    }

    @Test
    fun `time steps unpack the three dimensional rec output`() {
        val rows = OcrTensorReader.timeSteps(recOutput(4, 7, 0.25f))

        rows!!.size shouldBe 4
        rows.all { it.size == 7 } shouldBe true
        rows.all { row -> row.all { it == 0.25f } } shouldBe true
    }

    @Test
    fun `time steps return null for a det shaped value`() {
        OcrTensorReader.timeSteps(detOutput(2, 3, 0.5f)) shouldBe null
    }

    @Test
    fun `time steps return null for a non array`() {
        OcrTensorReader.timeSteps(42) shouldBe null
    }

    @Test
    fun `time steps keep distinct rows rather than aliasing one buffer`() {
        val first = FloatArray(3) { 0.1f }
        val second = FloatArray(3) { 0.9f }
        val rows = OcrTensorReader.timeSteps(arrayOf(arrayOf(first, second)))

        rows!! shouldBe listOf(first, second)
        rows[0][0] shouldBe 0.1f
        rows[1][0] shouldBeGreaterThan 0.5f
    }
}
