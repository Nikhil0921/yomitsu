package mihon.domain.tts.speech

import mihon.domain.ocr.model.OcrRegion

/**
 * Extensible classification of OCR regions for Read-Aloud. Heuristic-based on
 * OCR text characteristics and region geometry; designed so a smarter
 * classifier can replace [SpeechRegionClassifier.classify] later without
 * touching callers.
 */
enum class SpeechRegionType {
    DIALOGUE,
    NARRATION,
    SOUND_EFFECT,
    EXPRESSION,
    DECORATIVE,
    UNKNOWN,
}

data class SpeechClassificationConfig(
    /** Page aspect hint: wide-and-thin regions without terminal punctuation read as narration boxes. */
    val classifyNarration: Boolean = true,
)

object SpeechRegionClassifier {

    private val HAS_TERMINAL = Regex("[.!?…。！？]$")

    /**
     * Environmental onomatopoeia — sounds, not words. Membership is lexical because shape cannot
     * do the job: "BOOM!!" and "STOP!!" are both short, uppercase and emphatic, and only one of
     * them is a sound. Anything absent from these sets stays DIALOGUE, which is the safe
     * direction — a missed sound effect is read aloud, a misfiled word is silently skipped.
     */
    private val SOUND_EFFECT_TOKENS = setOf(
        "BAM", "BANG", "BOOM", "CLANG", "CLANK", "CRACK", "CRASH", "KABOOM", "POW", "SHLASH",
        "SLAM", "SMACK", "THUD", "RUMBLE", "WHAM", "ZAP",
        "BZZT", "CLNK", "CLUNK", "CREAK", "GRR", "SHRR", "VROOM", "ZZZT",
    )

    /** Non-word vocalizations: screams, grunts, hisses. Also lexical, same reason. */
    private val EXPRESSION_TOKENS = setOf(
        "AAAH", "AAARGH", "AARGH", "AHH", "EEE", "EEK", "HMM", "HRM", "MM", "OOF", "SHH", "TSK", "UGH",
    )

    /**
     * Regions are classified in stored order; never re-sorted. Classification is
     * conservative: anything not clearly decorative/SFX/expression stays
     * DIALOGUE/NARRATION/UNKNOWN so legitimate manga dialogue is never dropped.
     */
    fun classify(region: OcrRegion, config: SpeechClassificationConfig): SpeechRegionType {
        val text = region.text.trim()
        if (text.isBlank()) return SpeechRegionType.DECORATIVE

        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return SpeechRegionType.DECORATIVE

        // Decorative foreign script outside the primary dialogue (e.g. CJK on
        // an English page) is classified by script, not by language name, so
        // the system stays multilingual.
        val script = dominantScript(letters)
        if (script == SpeechScript.CJK) return SpeechRegionType.DECORATIVE

        val bbox = region.boundingBox
        val wideThin = bbox.width > 0.55f && bbox.height < 0.12f && !HAS_TERMINAL.containsMatchIn(text)
        if (config.classifyNarration && wideThin) return SpeechRegionType.NARRATION

        // Non-speech is only ever claimed for upper-case text, matching the long-standing gate:
        // a lower-case "boom!!" is a character saying "boom", not a drawn sound.
        val upperLetters = letters.uppercase()
        if (upperLetters == letters) {
            if (upperLetters in SOUND_EFFECT_TOKENS) return SpeechRegionType.SOUND_EFFECT
            if (upperLetters in EXPRESSION_TOKENS) return SpeechRegionType.EXPRESSION
        }

        return SpeechRegionType.DIALOGUE
    }
}

/** Script detection over the letters of a region; pure JDK UnicodeBlock. */
enum class SpeechScript { LATIN, CJK, OTHER }

fun dominantScript(letters: String): SpeechScript {
    var latin = 0
    var cjk = 0
    var other = 0
    for (c in letters) {
        if (c.isFullWidthLatin()) {
            latin++
            continue
        }
        when (Character.UnicodeBlock.of(c)) {
            null, Character.UnicodeBlock.BASIC_LATIN,
            Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
            Character.UnicodeBlock.LATIN_EXTENDED_A, Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL,
            -> latin++

            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
            Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
            Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA,
            Character.UnicodeBlock.HANGUL_SYLLABLES, Character.UnicodeBlock.HANGUL_JAMO,
            Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO,
            -> cjk++

            else -> other++
        }
    }
    return when {
        cjk > latin && cjk > other -> SpeechScript.CJK
        latin >= other -> SpeechScript.LATIN
        else -> SpeechScript.OTHER
    }
}

/**
 * Full-width Ａ-Ｚ / ａ-ｚ. These are still Latin: counting them as "other" made every full-width
 * English bubble look foreign, so `skipForeignScript` deleted the whole bubble.
 */
private fun Char.isFullWidthLatin(): Boolean =
    code in 0xFF21..0xFF3A || code in 0xFF41..0xFF5A
