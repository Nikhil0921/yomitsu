package mihon.domain.ocr.model

import java.text.Normalizer

/** Chapter/page identity the matcher needs to decide which zones apply where. */
data class ExclusionMatchContext(
    val mangaId: Long,
    val sourceId: Long,
    val chapterId: Long,
    val pageIndex: Int,
)

/**
 * Pure exclusion-rule matching for OCR regions.
 *
 * - ZONE: pure rectangle. A rectangle is drawn on exactly one page, so the
 *   rule always requires the drawing page (pageIndex) to match; the scope only
 *   widens where in the manga that page is looked up on (CHAPTER = any chapter
 *   page with the same index, MANGA = same page index across the manga,
 *   SOURCE = same page index across the source). Legacy rows with a null
 *   pageIndex (pre-redesign wide ZONE rules) are dormant: their rectangle was
 *   drawn on one page but the page is unknown.
 * - WORD: region excluded when the rule's token concatenation equals the
 *   concatenation of a consecutive run of region word tokens. Tokenization is
 *   identical on both sides, so "K-manga.com" matches tokens k, manga, com and
 *   camelCase/OCR separator variants ("KeyManga" ≡ "Key Manga") match too,
 *   while "ion" never matches "combination" (token boundaries enforced).
 * - PHRASE: region excluded when the rule text appears as a substring after
 *   Unicode-folded, case-insensitive, whitespace-stripped comparison —
 *   tolerant of OCR spacing noise around punctuation ("Discord. gg / x").
 * - COMBINED: rectangle + phrase must both match within the rule's scope.
 *
 * All text comparisons NFKC-normalize (folds full-width ｋｅｙ → key) so
 * JP-mixed OCR lines converted to full-width still match half-width rules.
 */
fun List<OcrRegion>.applyExclusions(
    zones: List<OcrExclusionZone>,
    context: ExclusionMatchContext,
): List<OcrRegion> {
    if (zones.isEmpty()) return this
    val active = zones.filter { it.enabled && it.matchesRegionScope(context) }
    if (active.isEmpty()) return this
    return filter { region ->
        // Normalized once per region instead of once per (zone, region) pair: a page with
        // N zones otherwise re-tokenized the same bubble text N times.
        val regionTokens = region.text.normalizedTokens()
        val regionPhrase = normalizeForPhrase(region.text)
        active.none { zone -> matchesRegion(zone, region, regionTokens, regionPhrase, context) }
    }
}

private fun OcrExclusionZone.matchesRegionScope(context: ExclusionMatchContext): Boolean = when (matchType) {
    OcrExclusionMatchType.ZONE ->
        pageIndex != null &&
            when (scope) {
                OcrExclusionScope.PAGE, OcrExclusionScope.CHAPTER -> chapterId == context.chapterId
                OcrExclusionScope.MANGA -> mangaId == context.mangaId
                OcrExclusionScope.SOURCE -> sourceId == context.sourceId
            }
    OcrExclusionMatchType.WORD, OcrExclusionMatchType.PHRASE -> true
    OcrExclusionMatchType.COMBINED -> when (scope) {
        OcrExclusionScope.PAGE ->
            chapterId == context.chapterId &&
                pageIndex == context.pageIndex
        OcrExclusionScope.CHAPTER -> chapterId == context.chapterId
        OcrExclusionScope.MANGA -> mangaId == context.mangaId
        OcrExclusionScope.SOURCE -> sourceId == context.sourceId
    }
}

private fun matchesRegion(
    zone: OcrExclusionZone,
    region: OcrRegion,
    regionTokens: List<String>?,
    regionPhrase: String?,
    context: ExclusionMatchContext,
): Boolean = when (zone.matchType) {
    // Rect rules are page-anchored: pageIndex gates scope, the rect does the work.
    OcrExclusionMatchType.ZONE ->
        zone.pageIndex == context.pageIndex &&
            overlaps(region.boundingBox, zone.boundingBox)
    OcrExclusionMatchType.WORD -> wordMatches(zone.matchText, regionTokens)
    OcrExclusionMatchType.PHRASE -> phraseMatches(zone.matchText, regionPhrase, regionTokens)
    OcrExclusionMatchType.COMBINED ->
        overlaps(region.boundingBox, zone.boundingBox) &&
            phraseMatches(zone.matchText, regionPhrase, regionTokens)
}

/**
 * WORD: the rule's token concatenation must equal the concatenation of some
 * consecutive run of region tokens. Single-token rules therefore behave as
 * standalone-word match ("ion" never matches "combination"), while separator
 * variants match across rule/region ("KeyManga" ≡ "Key Manga" ≡ [k, manga, com]
 * runs of "K-manga.com").
 */
private fun wordMatches(ruleText: String?, regionTokens: List<String>?): Boolean {
    val needleTokens = ruleText.normalizedTokens() ?: return false
    if (needleTokens.isEmpty()) return false
    if (regionTokens.isNullOrEmpty()) return false
    val needleConcat = needleTokens.joinToString("")
    return containsTokenRun(regionTokens, needleConcat)
}

/**
 * True when [needle] equals the concatenation of some consecutive run of [tokens].
 *
 * A run's character length is pinned by [needle], so every candidate start has exactly one
 * possible end offset, and a run is only valid when that offset lands on a token boundary.
 * Testing one start is therefore O(1) plus a single comparison, instead of windowing every
 * length and re-joining each window's strings.
 *
 * This keeps the original semantics exactly, including the separator-tolerance cases a plain
 * "does any single token equal the rule" check would lose: rule [keymanga] still matches the
 * region `Key Manga` (run `[key, manga]`) and `Dis\ncord` (run `[dis, cord]`).
 */
private fun containsTokenRun(tokens: List<String>, needle: String): Boolean {
    val needleLength = needle.length
    if (needleLength == 0) return false

    // One concatenation of the whole run plus the offsets where each token ends.
    val concat = StringBuilder()
    val tokenEnds = IntArray(tokens.size)
    for (i in tokens.indices) {
        concat.append(tokens[i])
        tokenEnds[i] = concat.length
    }
    val haystack = concat.toString()
    if (needleLength > haystack.length) return false

    val isTokenEnd = BooleanArray(haystack.length + 1)
    for (end in tokenEnds) isTokenEnd[end] = true

    var start = 0
    for (i in tokens.indices) {
        val end = start + needleLength
        if (end > haystack.length) break
        if (isTokenEnd[end] && haystack.regionMatches(start, needle, 0, needleLength)) return true
        start = tokenEnds[i]
    }
    return false
}

/** NFKC-fold, then split on non-letter/digit runs; tokens keep their case-fold. */
private fun String?.normalizedTokens(): List<String>? {
    if (this == null) return null
    val normalized = Normalizer.normalize(this, Normalizer.Form.NFKC).lowercase()
    val tokens = ArrayList<String>()
    val current = StringBuilder()
    for (ch in normalized) {
        if (ch.isLetterOrDigit()) {
            current.append(ch)
        } else if (current.isNotEmpty()) {
            tokens.add(current.toString())
            current.clear()
        }
    }
    if (current.isNotEmpty()) tokens.add(current.toString())
    return tokens
}

/** NFKC-fold, lowercase, strip ALL whitespace — OCR spacing noise-proof. */
private fun normalizeForPhrase(text: String?): String? {
    if (text == null) return null
    return Normalizer.normalize(text, Normalizer.Form.NFKC)
        .filterNot { it.isWhitespace() }
        .lowercase()
}

/**
 * PHRASE: substring match on token concatenations — NFKC-folded, lowercase,
 * letter/digit tokens joined without separators. Tolerates OCR spacing AND
 * punctuation noise in BOTH directions ("discord gg" rule matches
 * "Discord. gg / AsuraScans" region; "discord.gg" rule matches "discord gg").
 */
private fun phraseMatches(
    ruleText: String?,
    regionPhrase: String?,
    regionTokens: List<String>?,
): Boolean {
    val needle = normalizeForPhrase(ruleText) ?: return false
    if (needle.isEmpty()) return false
    val haystack = regionPhrase ?: return false
    if (haystack.contains(needle)) return true
    val needleTokens = ruleText.normalizedTokens() ?: return false
    if (needleTokens.isEmpty()) return false
    if (regionTokens.isNullOrEmpty()) return false
    return regionTokens.joinToString("").contains(needleTokens.joinToString(""))
}

private fun overlaps(a: OcrBoundingBox, b: OcrBoundingBox): Boolean =
    a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
