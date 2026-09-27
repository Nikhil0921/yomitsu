package mihon.domain.ocr.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class OcrQualityRouterTest {

    private fun signals(
        regionCount: Int = 12,
        meanCharConfidence: Float = 0.95f,
        confidenceAvailable: Boolean = true,
        meanCharsPerRegion: Float = 18f,
        meanBoxHeightNorm: Float = 0.03f,
        nonLatinCharRatio: Float = 0f,
        blankOrGarbageRatio: Float = 0f,
        singleCharRegionRatio: Float = 0f,
    ) = OcrPageSignals(
        regionCount = regionCount,
        meanCharConfidence = meanCharConfidence,
        confidenceAvailable = confidenceAvailable,
        meanCharsPerRegion = meanCharsPerRegion,
        meanBoxHeightNorm = meanBoxHeightNorm,
        nonLatinCharRatio = nonLatinCharRatio,
        blankOrGarbageRatio = blankOrGarbageRatio,
        singleCharRegionRatio = singleCharRegionRatio,
    )

    @Test
    fun `a clean page is accepted locally`() {
        OcrQualityRouter.route(signals(), OcrQualityRouter.DEFAULT_CONFIDENCE_FLOOR) shouldBe
            OcrRoute.ACCEPT_LOCAL
    }

    @Test
    fun `no regions on a page escalates`() {
        OcrQualityRouter.route(signals(regionCount = 0), 0.8f) shouldBe OcrRoute.ESCALATE_CLOUD
    }

    @Test
    fun `missing confidence always escalates`() {
        // Fail safe: a page we cannot score must not be trusted, even if it looks dense.
        OcrQualityRouter.route(
            signals(confidenceAvailable = false),
            0f,
        ) shouldBe OcrRoute.ESCALATE_CLOUD
    }

    @Test
    fun `confidence below the floor escalates`() {
        OcrQualityRouter.route(signals(meanCharConfidence = 0.5f), 0.8f) shouldBe OcrRoute.ESCALATE_CLOUD
    }

    @Test
    fun `confidence exactly at the floor is accepted`() {
        OcrQualityRouter.route(signals(meanCharConfidence = 0.8f), 0.8f) shouldBe OcrRoute.ACCEPT_LOCAL
    }

    @Test
    fun `bubbles found but nothing readable escalates`() {
        OcrQualityRouter.route(signals(meanCharsPerRegion = 1.2f), 0.8f) shouldBe OcrRoute.ESCALATE_CLOUD
    }

    @Test
    fun `boxes too small to be text escalate`() {
        OcrQualityRouter.route(signals(meanBoxHeightNorm = 0.004f), 0.8f) shouldBe OcrRoute.ESCALATE_CLOUD
    }

    @Test
    fun `a garbage heavy page escalates`() {
        OcrQualityRouter.route(signals(blankOrGarbageRatio = 0.5f), 0.8f) shouldBe OcrRoute.ESCALATE_CLOUD
    }

    @Test
    fun `the default floor is the audited 0 point 80`() {
        OcrQualityRouter.DEFAULT_CONFIDENCE_FLOOR shouldBe 0.80f
    }

    @Test
    fun `escalation is decided per page not per region`() {
        // One weak region among many good ones is a page-level signal, not a silent merge case.
        val page = signals(regionCount = 40, meanCharConfidence = 0.6f, meanCharsPerRegion = 12f)
        OcrQualityRouter.route(page, 0.8f) shouldBe OcrRoute.ESCALATE_CLOUD
    }

    @Test
    fun `page signals are derived from recognised regions`() {
        val regions = listOf(
            OcrPageText("Hello there", 0.98f),
            OcrPageText("General Kenobi", 0.96f),
            OcrPageText("a", 0.99f),
        )

        val signals = OcrQualitySignals.from(
            regions = regions,
            confidenceAvailable = true,
            meanBoxHeightNorm = 0.04f,
        )

        signals.regionCount shouldBe 3
        signals.confidenceAvailable shouldBe true
        (signals.meanCharConfidence - 0.9766f).toDouble().let { kotlin.math.abs(it) < 1e-3 } shouldBe true
        (signals.meanCharsPerRegion - 8.667f).toDouble().let { kotlin.math.abs(it) < 0.01 } shouldBe true
        signals.nonLatinCharRatio shouldBe 0f
    }

    @Test
    fun `garbage regions count towards the garbage ratio`() {
        val regions = listOf(
            OcrPageText("Hello there", 0.98f),
            OcrPageText("| | |", 0.98f),
        )

        val signals = OcrQualitySignals.from(regions, confidenceAvailable = true, meanBoxHeightNorm = 0.04f)

        signals.blankOrGarbageRatio shouldBe 0.5f
    }

    @Test
    fun `no recognised regions yields unusable signals`() {
        val signals = OcrQualitySignals.from(emptyList(), confidenceAvailable = true, meanBoxHeightNorm = 0f)

        signals.regionCount shouldBe 0
        signals.meanCharConfidence shouldBe 0f
        OcrQualityRouter.route(signals, 0.8f) shouldBe OcrRoute.ESCALATE_CLOUD
    }

    @Test
    fun `non latin characters are measured against the whole page`() {
        val regions = listOf(
            OcrPageText("Hello", 0.99f),
            OcrPageText("こんにちは", 0.99f),
        )

        val signals = OcrQualitySignals.from(regions, confidenceAvailable = true, meanBoxHeightNorm = 0.04f)

        // 5 of 10 characters are CJK.
        signals.nonLatinCharRatio shouldBe 0.5f
    }

    @Test
    fun `a dense foreign script page escalates so the cloud can redo it`() {
        val signals = OcrQualitySignals.from(
            listOf(OcrPageText("こんにちはせかい", 0.99f)),
            confidenceAvailable = true,
            meanBoxHeightNorm = 0.04f,
        )

        OcrQualityRouter.route(signals, 0.8f) shouldBe OcrRoute.ESCALATE_CLOUD
    }

    /**
     * A page that is mostly one-character regions is the "c-h-a-n-g-e-s" shape: the recognizer is
     * handing back per-glyph boxes, so TTS spells every word out. `MIN_CHARS_PER_REGION` cannot see
     * it — it is a page MEAN, so a page with 12 full lines and 6 lone glyphs still averages well
     * above 1.5 and passes every gate. Needs a per-region signal.
     */
    @Test
    fun `a page that is mostly single character regions escalates`() {
        OcrQualityRouter.route(
            signals(meanCharsPerRegion = 10f, singleCharRegionRatio = 0.5f),
            0.8f,
        ) shouldBe OcrRoute.ESCALATE_CLOUD
    }

    @Test
    fun `a page with a couple of short regions is still accepted`() {
        OcrQualityRouter.route(
            signals(meanCharsPerRegion = 10f, singleCharRegionRatio = 0.1f),
            0.8f,
        ) shouldBe OcrRoute.ACCEPT_LOCAL
    }

    @Test
    fun `single char region ratio is derived from the regions`() {
        val regions = listOf(
            OcrPageText("Hello there", 0.98f),
            OcrPageText("General Kenobi", 0.96f),
            OcrPageText("a", 0.99f),
            OcrPageText("I", 0.99f),
        )

        OcrQualitySignals.from(
            regions = regions,
            confidenceAvailable = true,
            meanBoxHeightNorm = 0.04f,
        ).singleCharRegionRatio shouldBe 0.5f
    }
}
