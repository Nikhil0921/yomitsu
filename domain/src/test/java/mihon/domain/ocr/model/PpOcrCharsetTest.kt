package mihon.domain.ocr.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class PpOcrCharsetTest {

    @Test
    fun `charset size matches the official en model dict`() {
        // Code points, not UTF-16 units: two entries are astral-plane symbols.
        PpOcrCharset.CHARSET.codePointCount(0, PpOcrCharset.CHARSET.length) shouldBe 436
        PpOcrCharset.CHARACTERS.size shouldBe 436
    }

    @Test
    fun `every dictionary entry is a whole code point`() {
        // A lone surrogate would mean the entry can never be appended as a valid character.
        PpOcrCharset.CHARACTERS.none { entry ->
            entry.length == 1 && entry[0].isSurrogate()
        } shouldBe true
        PpOcrCharset.CHARACTERS.all { it.codePointCount(0, it.length) == 1 } shouldBe true
    }

    @Test
    fun `class count matches the model, which is blank plus charset plus a space`() {
        // Ground truth: the real export's output shape is [-1, -1, 438] for a 436 character dict.
        // PaddleOCR's CTCLabelDecode builds ['blank'] + dict + [' '], so 438 is not a typo.
        PpOcrCharset.CLASS_COUNT shouldBe 438
        PpOcrCharset.SPACE_CLASS shouldBe 437
    }

    @Test
    fun `charset has no duplicate characters`() {
        PpOcrCharset.CHARACTERS.toSet().size shouldBe PpOcrCharset.CHARACTERS.size
    }

    @Test
    fun `digits and ascii letters come first in the documented order`() {
        PpOcrCharset.CHARSET.take(10) shouldBe "0123456789"
        PpOcrCharset.CHARSET.substring(10, 36) shouldBe "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        PpOcrCharset.CHARSET.substring(36, 62) shouldBe "abcdefghijklmnopqrstuvwxyz"
    }

    @Test
    fun `charset is the english dict and contains no cjk`() {
        PpOcrCharset.CHARACTERS.none { entry ->
            val code = entry.codePointAt(0)
            code in 0x3000..0x9FFF || code in 0xAC00..0xD7AF
        } shouldBe true
    }

    @Test
    fun `ctc class index maps to dictionary index plus one for the blank`() {
        // Class 0 is the CTC blank, so 'A' (dictionary index 10) is class 11.
        PpOcrCharset.CHARACTERS.indexOf("A") shouldBe 10
        PpOcrCharset.CHARACTERS.first() shouldBe "0"
        PpOcrCharset.CHARACTERS.last() shouldBe "\u263A"
    }
}
