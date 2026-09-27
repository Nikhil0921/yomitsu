package mihon.domain.tts.speech

/**
 * Speech-only cleanup of OCR region text. The original OCR data is never
 * modified; this layer only decides what/how text is spoken by Read-Aloud.
 * Other consumers (dictionary, search, copy) continue to see raw OCR output.
 */
data class SpeechCleanupOptions(
    val skipPunctuationOnly: Boolean = true,
    val skipOcrGarbage: Boolean = true,
    val normalizeExcessivePunctuation: Boolean = true,
    val ellipsisToPause: Boolean = true,
)

object SpeechCleaner {

    /**
     * Runs of 3+ exclamation/question marks (incl. CJK variants) are spoken as
     * their 2-char form; shorter runs ("?!", "!!") keep their emphasis.
     */
    private val EXCESSIVE_PUNCTUATION = Regex("[!?！？‼⁉⁇⁈]{3,}")

    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * Dot runs and the ellipsis char become a comma pause so voices stop
     * reading "dot dot dot". Standalone runs are still dropped by the
     * punctuation-only skip that runs after this step.
     */
    private val ELLIPSIS_RUN = Regex("\\.{2,}|…")

    /**
     * Returns the text to speak for a region, or null when the region should be
     * skipped entirely. Null-safe ordering: punctuation-only skip runs AFTER
     * normalization so "WHAT?!?!?!" is first reduced to "WHAT?!" and kept.
     */
    fun cleanRegionForSpeech(text: String, options: SpeechCleanupOptions): String? {
        var cleaned = WHITESPACE_RUN.replace(text.trim(), " ")
        if (options.ellipsisToPause) {
            cleaned = ELLIPSIS_RUN.replace(cleaned, ", ")
        }
        if (options.normalizeExcessivePunctuation) {
            cleaned = EXCESSIVE_PUNCTUATION.replace(cleaned) { it.value.take(2) }
        }
        if (options.skipPunctuationOnly && cleaned.none { it.isLetterOrDigit() }) return null
        if (options.skipOcrGarbage && isOcrGarbage(cleaned)) return null
        return cleaned.ifBlank { null }
    }

    /**
     * Conservative garbage detector: requires several characters AND a very low
     * share of letters/digits so legitimate dialogue (even emphatic, e.g.
     * "WHAT?!?!?!" → 40% letters) is never dropped. Symbol soup with stray
     * letters ("W@#R%!") is dropped.
     *
     * Shared with the OCR adaptive router ([mihon.domain.ocr.model.OcrQualitySignals]) so both
     * layers agree on what "garbage" means instead of keeping two copies of the rule.
     */
    internal fun isOcrGarbage(text: String): Boolean {
        if (text.length < 4) return false
        val nonWhitespace = text.count { !it.isWhitespace() }
        val meaningful = text.count { it.isLetterOrDigit() }
        return meaningful < nonWhitespace * 0.4
    }
}
