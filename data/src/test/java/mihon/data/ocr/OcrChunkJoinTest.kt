package mihon.data.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * A wide line box is recognized in horizontal chunks and the pieces are stitched back together
 * here. The overlap between chunks exists so a character sitting on a boundary is not cut in half,
 * which means the same character can be decoded twice, so the join has to remove it.
 *
 * Measured on hardware 2026-09-28: 76% of detected boxes were wider than the recognizer's
 * `RECOGNITION_MAX_WIDTH` allows, median squeeze 2.8x, and every one of them was being squashed
 * into a single 320x48 tensor, which decoded as blank or as a single character.
 */
@Execution(ExecutionMode.CONCURRENT)
class OcrChunkJoinTest {

    @Test
    fun `chunks with no shared text are joined with a space`() {
        joinOcrChunks(listOf("the quick", "brown fox")) shouldBe "the quick brown fox"
    }

    @Test
    fun `a character repeated by the overlap is not duplicated`() {
        joinOcrChunks(listOf("the qui", "ick brown")) shouldBe "the quick brown"
    }

    @Test
    fun `the longest matching overlap wins`() {
        joinOcrChunks(listOf("abcde", "cdefgh")) shouldBe "abcdefgh"
    }

    @Test
    fun `an overlap longer than the previous chunk keeps the next chunk whole`() {
        joinOcrChunks(listOf("ab", "abcdef")) shouldBe "abcdef"
    }

    @Test
    fun `a single chunk is returned as is`() {
        joinOcrChunks(listOf("alone")) shouldBe "alone"
    }

    @Test
    fun `blank chunks are dropped`() {
        joinOcrChunks(listOf("hello", "", "  ", "world")) shouldBe "hello world"
    }

    @Test
    fun `no chunks at all yields empty text`() {
        joinOcrChunks(emptyList()) shouldBe ""
    }

    /** A chunk that decoded to nothing must not swallow the one before it. */
    @Test
    fun `a blank chunk between two real ones does not glue their text together`() {
        joinOcrChunks(listOf("start", "", "end")) shouldBe "start end"
    }
}
