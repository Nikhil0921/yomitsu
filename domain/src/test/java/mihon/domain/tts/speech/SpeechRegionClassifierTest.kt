package mihon.domain.tts.speech

import io.kotest.matchers.shouldBe
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrRegion
import mihon.domain.ocr.model.OcrTextOrientation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class SpeechRegionClassifierTest {

    private val config = SpeechClassificationConfig()

    private fun region(text: String, width: Float = 0.2f, height: Float = 0.1f) = OcrRegion(
        order = 0,
        text = text,
        boundingBox = OcrBoundingBox(0.1f, 0.1f, 0.1f + width, 0.1f + height),
        textOrientation = OcrTextOrientation.Horizontal,
    )

    @Test
    fun `dialogue with sentence punctuation is dialogue`() {
        SpeechRegionClassifier.classify(region("What do you mean?"), config) shouldBe SpeechRegionType.DIALOGUE
        SpeechRegionClassifier.classify(region("I don't know..."), config) shouldBe SpeechRegionType.DIALOGUE
    }

    @Test
    fun `wide thin terminal-less text classifies as narration`() {
        SpeechRegionClassifier.classify(
            region("meanwhile, at the school", width = 0.7f, height = 0.05f),
            config,
        ) shouldBe
            SpeechRegionType.NARRATION
    }

    @Test
    fun `short uppercase with emphasis is sound effect`() {
        SpeechRegionClassifier.classify(region("BOOM!!"), config) shouldBe SpeechRegionType.SOUND_EFFECT
        SpeechRegionClassifier.classify(region("WHAM!"), config) shouldBe SpeechRegionType.SOUND_EFFECT
    }

    @Test
    fun `shouted short dialogue is dialogue not sfx`() {
        SpeechRegionClassifier.classify(region("GET DOWN NOW!"), config) shouldBe SpeechRegionType.DIALOGUE
    }

    @Test
    fun `uppercase interjection is expression`() {
        SpeechRegionClassifier.classify(region("AAAH"), config) shouldBe SpeechRegionType.EXPRESSION
        SpeechRegionClassifier.classify(region("HMM"), config) shouldBe SpeechRegionType.EXPRESSION
    }

    @Test
    fun `short uppercase words are dialogue not expressions`() {
        listOf("OK", "NO", "YES", "HI", "MEH", "RUN", "HELP").forEach {
            SpeechRegionClassifier.classify(region(it), config) shouldBe SpeechRegionType.DIALOGUE
        }
    }

    @Test
    fun `emphatic uppercase dialogue is dialogue not sfx`() {
        listOf("STOP!!", "WHAT??", "NO!", "OKAY!", "WAIT!").forEach {
            SpeechRegionClassifier.classify(region(it), config) shouldBe SpeechRegionType.DIALOGUE
        }
    }

    @Test
    fun `environmental onomatopoeia is sound effect`() {
        listOf("BAM", "CRASH", "RUMBLE", "THUD", "SLAM", "KABOOM").forEach {
            SpeechRegionClassifier.classify(region(it), config) shouldBe SpeechRegionType.SOUND_EFFECT
        }
    }

    @Test
    fun `lowercase short text is never sfx or expression`() {
        listOf("ok", "no", "stop!", "what?").forEach {
            SpeechRegionClassifier.classify(region(it), config) shouldBe SpeechRegionType.DIALOGUE
        }
    }

    @Test
    fun `symbol-only and blank regions are decorative`() {
        SpeechRegionClassifier.classify(region("!!!"), config) shouldBe SpeechRegionType.DECORATIVE
        SpeechRegionClassifier.classify(region("***"), config) shouldBe SpeechRegionType.DECORATIVE
        SpeechRegionClassifier.classify(region("   "), config) shouldBe SpeechRegionType.DECORATIVE
    }

    @Test
    fun `foreign script decorative text classifies by script`() {
        SpeechRegionClassifier.classify(region("なんで"), config) shouldBe SpeechRegionType.DECORATIVE
        SpeechRegionClassifier.classify(region("대단해"), config) shouldBe SpeechRegionType.DECORATIVE
    }
}
