package mihon.domain.tts.speech

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrRegion
import mihon.domain.ocr.model.OcrTextOrientation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class SpeechRegionFilterTest {

    private val classificationConfig = SpeechClassificationConfig()
    private val defaultConfig = SpeechRegionFilterConfig()
    private val cleanup = SpeechCleanupOptions()

    private fun region(order: Int, text: String, width: Float = 0.2f, height: Float = 0.1f) = OcrRegion(
        order = order,
        text = text,
        boundingBox = OcrBoundingBox(0.1f, 0.1f, 0.1f + width, 0.1f + height),
        textOrientation = OcrTextOrientation.Horizontal,
    )

    @Test
    fun `defaults keep dialogue and narration, drop sfx expression decorative`() {
        val regions = listOf(
            region(0, "Hello there."),
            region(1, "BOOM!!"),
            region(2, "AAAH"),
            region(3, "なんで"),
            region(4, "!!!"),
        )
        SpeechRegionFilter.filterRegions(regions, classificationConfig, defaultConfig)
            .map { it.text } shouldContainExactly listOf("Hello there.")
    }

    @Test
    fun `spoken types are user configurable`() {
        val all = SpeechRegionFilterConfig(
            speakSoundEffects = true,
            speakExpressions = true,
            speakDecorative = true,
        )
        val regions = listOf(
            region(0, "Hello there."),
            region(1, "BOOM!!"),
            region(2, "AAAH"),
        )
        SpeechRegionFilter.filterRegions(regions, classificationConfig, all)
            .map { it.text } shouldContainExactly listOf("Hello there.", "BOOM!!", "AAAH")
    }

    @Test
    fun `foreign script regions skipped by default, kept when disabled`() {
        val regions = listOf(
            region(0, "Hello."),
            region(1, "なんで"),
        )
        SpeechRegionFilter.filterRegions(regions, classificationConfig, defaultConfig)
            .map { it.text } shouldContainExactly listOf("Hello.")
        val off = SpeechRegionFilterConfig(skipForeignScript = false, speakDecorative = true)
        SpeechRegionFilter.filterRegions(regions, classificationConfig, off)
            .map { it.text } shouldContainExactly listOf("Hello.", "なんで")
    }

    @Test
    fun `speech script controls what is foreign`() {
        val regions = listOf(region(0, "こんにちは"), region(1, "Hello."))
        val jp = SpeechRegionFilterConfig(speechScript = SpeechScript.CJK, speakDecorative = true)
        val kept = SpeechRegionFilter.filterRegions(regions, classificationConfig, jp)
        kept.map { it.text } shouldContainExactly listOf("こんにちは")
    }

    @Test
    fun `short emphatic dialogue survives default config`() {
        val regions = listOf(
            region(0, "OK"),
            region(1, "I'm ready"),
            region(2, "STOP!!"),
            region(3, "WHAT??"),
        )
        SpeechRegionFilter.filterRegions(regions, classificationConfig, defaultConfig)
            .map { it.text } shouldContainExactly listOf("OK", "I'm ready", "STOP!!", "WHAT??")
    }

    @Test
    fun `english bubble with stray kana is not dropped as foreign script`() {
        val regions = listOf(
            region(0, "I'm ready こんにちは"),
            region(1, "OK."),
        )
        SpeechRegionFilter.filterRegions(regions, classificationConfig, defaultConfig)
            .map { it.text } shouldContainExactly listOf("I'm ready こんにちは", "OK.")
    }

    @Test
    fun `full-width latin bubble is not dropped as foreign script`() {
        val regions = listOf(region(0, "ＯＫ"), region(1, "Ｐlease"))
        SpeechRegionFilter.filterRegions(regions, classificationConfig, defaultConfig)
            .map { it.text } shouldContainExactly listOf("ＯＫ", "Ｐlease")
    }

    @Test
    fun `order preserved`() {
        val regions = listOf(
            region(0, "First."),
            region(1, "!!!"),
            region(2, "Second."),
        )
        SpeechRegionFilter.filterRegions(regions, classificationConfig, defaultConfig)
            .map { it.order } shouldContainExactly listOf(0, 2)
    }
}

@Execution(ExecutionMode.CONCURRENT)
class SpeechPipelineTest {

    private val classificationConfig = SpeechClassificationConfig()
    private val filterConfig = SpeechRegionFilterConfig()
    private val cleanup = SpeechCleanupOptions()

    private fun region(order: Int, text: String, width: Float = 0.2f, height: Float = 0.1f) = OcrRegion(
        order = order,
        text = text,
        boundingBox = OcrBoundingBox(0.1f, 0.1f, 0.1f + width, 0.1f + height),
        textOrientation = OcrTextOrientation.Horizontal,
    )

    @Test
    fun `spoken sentences are cleaned`() {
        val sentences = SpeechPipeline.toSpeakableSentences(
            listOf(region(0, "WHAT ARE YOU DOING?!?!?!")),
            classificationConfig,
            filterConfig,
            cleanup,
        )
        sentences.map { it.text } shouldContainExactly listOf("WHAT ARE YOU DOING?")
    }

    @Test
    fun `punctuation-only sentence slices are dropped`() {
        val sentences = SpeechPipeline.toSpeakableSentences(
            listOf(region(0, "I don't know...")),
            classificationConfig,
            filterConfig,
            cleanup,
        )
        sentences.map { it.text } shouldContainExactly listOf("I don't know,")
    }

    @Test
    fun `multiline bubble becomes separated sentences`() {
        val sentences = SpeechPipeline.toSpeakableSentences(
            listOf(region(0, "Wait.\nWho's there?")),
            classificationConfig,
            filterConfig,
            cleanup,
        )
        sentences.map { it.text } shouldContainExactly listOf("Wait.", "Who's there?")
    }

    @Test
    fun `empty page yields no sentences`() {
        SpeechPipeline.toSpeakableSentences(emptyList(), classificationConfig, filterConfig, cleanup)
            .size shouldBe 0
    }
}
