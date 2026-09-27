package mihon.domain.ocr.model

import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class PpOcrCtcDecodeTest {

    private val blank = 0

    private fun classOf(char: Char) = PpOcrCharset.CHARACTERS.indexOf(char.toString()) + 1

    /** prob[time][class]; every unlisted class stays at 0 so argmax picks the listed one. */
    private fun probs(vararg steps: Pair<Int, Float>): FloatArray {
        val time = steps.size
        val out = FloatArray(time * PpOcrCharset.CLASS_COUNT)
        steps.forEachIndexed { t, (cls, p) -> out[t * PpOcrCharset.CLASS_COUNT + cls] = p }
        return out
    }

    private fun decode(steps: FloatArray, timeSteps: Int = steps.size / PpOcrCharset.CLASS_COUNT) =
        PpOcrCtcDecode.decode(steps, timeSteps)

    @Test
    fun `a single peak decodes to its character`() {
        decode(probs(classOf('A') to 0.9f, blank to 0.95f)).text shouldBe "A"
    }

    @Test
    fun `repeated peaks collapse to one character`() {
        decode(probs(classOf('A') to 0.9f, classOf('A') to 0.9f, classOf('A') to 0.9f)).text shouldBe "A"
    }

    @Test
    fun `a blank between repeats keeps both`() {
        decode(
            probs(
                classOf('A') to 0.9f,
                blank to 0.9f,
                classOf('A') to 0.9f,
            ),
        ).text shouldBe "AA"
    }

    @Test
    fun `a word decodes in time order`() {
        decode(
            probs(
                classOf('H') to 0.9f,
                classOf('i') to 0.9f,
                blank to 0.9f,
            ),
        ).text shouldBe "Hi"
    }

    @Test
    fun `an all blank sequence decodes to empty text with zero confidence`() {
        val result = decode(probs(blank to 0.9f, blank to 0.9f))
        result.text shouldBe ""
        result.confidence shouldBe 0f
    }

    @Test
    fun `confidence is the mean probability of the kept peaks`() {
        val result = decode(
            probs(
                classOf('A') to 0.8f,
                classOf('B') to 0.6f,
            ),
        )
        // (0.8 + 0.6) / 2
        (result.confidence - 0.7f).toDouble().let { kotlin.math.abs(it) < 1e-5 } shouldBe true
    }

    @Test
    fun `low confidence peaks are reported as such`() {
        val result = decode(probs(classOf('A') to 0.2f, classOf('B') to 0.3f))
        result.confidence shouldBeLessThan 0.5f
    }

    @Test
    fun `high confidence peaks are reported as such`() {
        decode(probs(classOf('A') to 0.99f, classOf('B') to 0.98f)).confidence shouldBeGreaterThan 0.9f
    }

    @Test
    fun `the trailing space class decodes to a space`() {
        decode(
            probs(
                classOf('H') to 0.9f,
                PpOcrCharset.SPACE_CLASS to 0.9f,
                classOf('i') to 0.9f,
            ),
        ).text shouldBe "H i"
    }

    @Test
    fun `words are not glued together when the model emits the space class`() {
        // Without the space class the decode returned "Helloworld", which is what makes Read-Aloud
        // read one run-on word instead of two.
        val result = decode(
            probs(
                classOf('O') to 0.9f,
                classOf('K') to 0.9f,
                PpOcrCharset.SPACE_CLASS to 0.9f,
                classOf('N') to 0.9f,
                classOf('O') to 0.9f,
            ),
        )
        result.text shouldBe "OK NO"
    }

    @Test
    fun `the space class alone still counts toward confidence`() {
        val result = decode(
            probs(
                classOf('A') to 0.8f,
                PpOcrCharset.SPACE_CLASS to 0.8f,
            ),
        )
        (result.confidence - 0.8f).toDouble().let { kotlin.math.abs(it) < 1e-5 } shouldBe true
    }

    @Test
    fun `class indices past the vocabulary are skipped instead of crashing`() {
        // A model/dict mismatch: the buffer claims two more classes than the charset has.
        val classCount = PpOcrCharset.CLASS_COUNT + 2
        val out = FloatArray(2 * classCount)
        out[0 * classCount + classCount - 1] = 0.9f
        out[1 * classCount + classOf('Z')] = 0.9f

        val result = PpOcrCtcDecode.decode(out, 2, classCount = classCount)

        result.text shouldBe "Z"
    }

    @Test
    fun `explicit time steps bound the decode`() {
        val out = FloatArray(3 * PpOcrCharset.CLASS_COUNT)
        out[0 * PpOcrCharset.CLASS_COUNT + classOf('A')] = 0.9f
        out[1 * PpOcrCharset.CLASS_COUNT + classOf('B')] = 0.9f
        out[2 * PpOcrCharset.CLASS_COUNT + classOf('C')] = 0.9f

        decode(out, timeSteps = 2).text shouldBe "AB"
    }

    @Test
    fun `more time steps than the buffer holds is clamped`() {
        val out = FloatArray(1 * PpOcrCharset.CLASS_COUNT)
        out[classOf('A')] = 0.9f

        decode(out, timeSteps = 99).text shouldBe "A"
    }

    @Test
    fun `an empty buffer decodes to empty text`() {
        decode(FloatArray(0), 0).text shouldBe ""
    }
}
