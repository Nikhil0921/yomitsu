package mihon.domain.tts.speech

import io.kotest.matchers.shouldBe
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrRegion
import mihon.domain.ocr.model.OcrTextOrientation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * The speech pipeline is the only consumer of [mihon.domain.ocr.model.mergeSpacedSingleLetters];
 * these tests pin that it is actually wired in, not merely defined.
 */
@Execution(ExecutionMode.CONCURRENT)
class SpeechPipelineLetterMergeTest {

    private val classificationConfig = SpeechClassificationConfig()
    private val filterConfig = SpeechRegionFilterConfig()
    private val cleanup = SpeechCleanupOptions()

    private fun region(text: String) = OcrRegion(
        order = 0,
        text = text,
        boundingBox = OcrBoundingBox(0.1f, 0.1f, 0.3f, 0.2f),
        textOrientation = OcrTextOrientation.Horizontal,
    )

    private fun spoken(text: String): String =
        SpeechPipeline.toSpeakableSentences(
            regions = listOf(region(text)),
            classificationConfig = classificationConfig,
            filterConfig = filterConfig,
            cleanupOptions = cleanup,
        ).joinToString(" ") { it.text }

    @Test
    fun `tracked text is spoken as a word not as letters`() {
        spoken("N E T W O R K.") shouldBe "NETWORK."
        spoken("Join C A T now.") shouldBe "Join CAT now."
    }

    @Test
    fun `ordinary sentences pass through unchanged`() {
        spoken("This is a test.") shouldBe "This is a test."
        spoken("I am here.") shouldBe "I am here."
    }
}
