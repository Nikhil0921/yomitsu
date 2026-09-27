package mihon.domain.ocr.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class OcrTextSanitizerTest {

    @Test
    fun `tracked uppercase run merges into one word`() {
        mergeSpacedSingleLetters("N E T W O R K") shouldBe "NETWORK"
        mergeSpacedSingleLetters("K I M") shouldBe "KIM"
        mergeSpacedSingleLetters("K I M  J O N G  U N") shouldBe "KIMJONGUN"
    }

    @Test
    fun `two and three letter runs merge`() {
        mergeSpacedSingleLetters("C A T") shouldBe "CAT"
        mergeSpacedSingleLetters("U S A") shouldBe "USA"
    }

    @Test
    fun `normal prose is untouched`() {
        mergeSpacedSingleLetters("This is a test") shouldBe "This is a test"
        mergeSpacedSingleLetters("I am here") shouldBe "I am here"
        mergeSpacedSingleLetters("He said it was fine.") shouldBe "He said it was fine."
        mergeSpacedSingleLetters("a test of one thing") shouldBe "a test of one thing"
    }

    @Test
    fun `single letters are never merged`() {
        mergeSpacedSingleLetters("A") shouldBe "A"
        mergeSpacedSingleLetters("I") shouldBe "I"
        mergeSpacedSingleLetters("I and a") shouldBe "I and a"
        mergeSpacedSingleLetters("") shouldBe ""
    }

    @Test
    fun `run inside a sentence merges without touching its neighbours`() {
        mergeSpacedSingleLetters("Join N E T W O R K now") shouldBe "Join NETWORK now"
        mergeSpacedSingleLetters("Name: K I M. Age: 30") shouldBe "Name: KIM. Age: 30"
    }

    @Test
    fun `digits units and dotted abbreviations are preserved`() {
        mergeSpacedSingleLetters("1 2 3") shouldBe "1 2 3"
        mergeSpacedSingleLetters("5 x 3") shouldBe "5 x 3"
        mergeSpacedSingleLetters("U.S.A") shouldBe "U.S.A"
    }

    @Test
    fun `trailing and leading punctuation is preserved`() {
        mergeSpacedSingleLetters("N E T W O R K!") shouldBe "NETWORK!"
        mergeSpacedSingleLetters("(C A T)") shouldBe "(CAT)"
        mergeSpacedSingleLetters("A B,") shouldBe "AB,"
    }

    @Test
    fun `newlines and tabs are not treated as separators`() {
        // Only plain spaces are merged; a line break may separate genuinely distinct text.
        mergeSpacedSingleLetters("N\nE\nT") shouldBe "N\nE\nT"
        mergeSpacedSingleLetters("N\tE") shouldBe "N\tE"
    }

    @Test
    fun `empty and too-short input short circuits`() {
        mergeSpacedSingleLetters("a") shouldBe "a"
        mergeSpacedSingleLetters("ab") shouldBe "ab"
    }
}
