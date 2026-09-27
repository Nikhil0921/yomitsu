package mihon.domain.ocr.model

import mihon.domain.tts.speech.SpeechCleaner

/** One recognised region as the router sees it: the text plus the local model's own confidence. */
data class OcrPageText(
    val text: String,
    val confidence: Float,
)

/**
 * Page-level quality signals. Every field is something the **local** model produced: the GLENS
 * protobuf parse yields no score at all, so the cloud can never arbitrate a disagreement — the
 * adaptive decision is one-directional, local proposes and the cloud escalates.
 */
data class OcrPageSignals(
    val regionCount: Int,
    val meanCharConfidence: Float,
    val confidenceAvailable: Boolean,
    val meanCharsPerRegion: Float,
    val meanBoxHeightNorm: Float,
    val nonLatinCharRatio: Float,
    val blankOrGarbageRatio: Float,
)

enum class OcrRoute {
    ACCEPT_LOCAL,
    ESCALATE_CLOUD,
}

/**
 * Local-first routing policy. All thresholds live here, named, so no engine invents its own.
 *
 * The escalation bias is deliberate: anything the local pass cannot vouch for goes to the cloud
 * whole-page, because a wrong local read is silently wrong text in the reader and in Read-Aloud,
 * while an unnecessary cloud call only costs latency we already measured.
 *
 * `ponytail:` escalation is per page and the cloud result replaces the local one. Merging both
 * would break `OcrRegion.order`, which `SentenceSegmenter`, `SpeechPipeline` and tap-highlight all
 * treat as reading-order truth. Merge only if measurement ever shows mixed-quality pages are the
 * common case rather than the exception.
 */
object OcrQualityRouter {

    const val DEFAULT_CONFIDENCE_FLOOR = 0.80f

    /** "Text detected but nothing readable" — the classic bubbles-found-words-missing shape. */
    const val MIN_CHARS_PER_REGION = 1.5f

    /** Boxes below this fraction of page height are noise, not text. */
    const val MIN_BOX_HEIGHT_NORM = 0.008f

    /**
     * The `en` recognizer cannot read CJK at all, so a CJK-heavy local result means the page is
     * not English and the cloud — which is hard-hinted to Japanese — should redo it.
     */
    const val MAX_NON_LATIN_RATIO = 0.15f

    const val MAX_GARBAGE_RATIO = 0.30f

    fun route(signals: OcrPageSignals, floor: Float = DEFAULT_CONFIDENCE_FLOOR): OcrRoute {
        // Fail safe: a page we cannot score is never trusted.
        if (!signals.confidenceAvailable) return OcrRoute.ESCALATE_CLOUD
        if (signals.regionCount <= 0) return OcrRoute.ESCALATE_CLOUD
        if (signals.meanCharConfidence < floor) return OcrRoute.ESCALATE_CLOUD
        if (signals.meanCharsPerRegion < MIN_CHARS_PER_REGION) return OcrRoute.ESCALATE_CLOUD
        if (signals.meanBoxHeightNorm < MIN_BOX_HEIGHT_NORM) return OcrRoute.ESCALATE_CLOUD
        if (signals.nonLatinCharRatio > MAX_NON_LATIN_RATIO) return OcrRoute.ESCALATE_CLOUD
        if (signals.blankOrGarbageRatio > MAX_GARBAGE_RATIO) return OcrRoute.ESCALATE_CLOUD
        return OcrRoute.ACCEPT_LOCAL
    }
}

/** Builds [OcrPageSignals] from what the local pass recognised on one page. */
object OcrQualitySignals {

    fun from(
        regions: List<OcrPageText>,
        confidenceAvailable: Boolean,
        meanBoxHeightNorm: Float,
    ): OcrPageSignals {
        if (regions.isEmpty()) {
            return OcrPageSignals(
                regionCount = 0,
                meanCharConfidence = 0f,
                confidenceAvailable = confidenceAvailable,
                meanCharsPerRegion = 0f,
                meanBoxHeightNorm = meanBoxHeightNorm,
                nonLatinCharRatio = 0f,
                blankOrGarbageRatio = 0f,
            )
        }

        val characters = regions.sumOf { it.text.length }
        val nonLatin = regions.sumOf { region -> region.text.count { it.isNonLatin() } }
        val garbage = regions.count { SpeechCleaner.isOcrGarbage(it.text) }

        return OcrPageSignals(
            regionCount = regions.size,
            meanCharConfidence = regions.map { it.confidence }.average().toFloat(),
            confidenceAvailable = confidenceAvailable,
            meanCharsPerRegion = characters.toFloat() / regions.size,
            meanBoxHeightNorm = meanBoxHeightNorm,
            nonLatinCharRatio = if (characters == 0) 0f else nonLatin.toFloat() / characters,
            blankOrGarbageRatio = garbage.toFloat() / regions.size,
        )
    }
}

/**
 * Anything the `en` recognizer could not have read: CJK, kana, Hangul, full-width forms and
 * Cyrillic. Used only to detect "this page is not English", never to repair text.
 */
internal fun Char.isNonLatin(): Boolean {
    val code = code
    return code in 0x1100..0x11FF || // Hangul Jamo
        code in 0x2E80..0x9FFF || // CJK radicals .. CJK unified
        code in 0xA960..0xA97F || // Hangul Jamo extended-A
        code in 0xAC00..0xD7FF || // Hangul syllables + Jamo extended-B
        code in 0xF900..0xFAFF || // CJK compatibility ideographs
        code in 0xFF00..0xFF60 || // full-width forms
        code in 0xFFE0..0xFFE6
}
