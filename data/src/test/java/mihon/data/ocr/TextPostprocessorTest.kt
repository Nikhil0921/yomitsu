package mihon.data.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextPostprocessorTest {

    private val postprocessor = TextPostprocessor()

    @Test
    fun `stray kana in an english line keeps ascii and word spacing`() {
        assertEquals("I'm ready こんにちは", postprocessor.postprocess("I'm ready こんにちは"))
    }

    @Test
    fun `japanese-dominant line still converts and drops spacing next to japanese`() {
        assertEquals("こんにちは１", postprocessor.postprocess("こんにちは 1"))
    }

    @Test
    fun `japanese-dominant line still drops spaces next to japanese`() {
        assertEquals("こんにちは", postprocessor.postprocess("こん にちは"))
    }

    @Test
    fun `english line with a stray full-width symbol is untouched`() {
        assertEquals("Wait！ What now?", postprocessor.postprocess("Wait！ What now?"))
    }

    @Test
    fun `pure english line is untouched`() {
        assertEquals("Wait. What now?", postprocessor.postprocess("Wait. What now?"))
    }
}
