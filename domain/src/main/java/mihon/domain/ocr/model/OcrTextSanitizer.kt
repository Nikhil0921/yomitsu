package mihon.domain.ocr.model

/**
 * A run of two or more single space-separated ASCII letters, e.g. `N E T W O R K`.
 *
 * Both boundaries are guarded, and that is what makes the rewrite safe on ordinary prose.
 * The lookbehind stops a match from starting inside a longer word; the lookahead stops it
 * from *ending* on the first letter of a longer word. The lookahead is the load-bearing one:
 * in `This is a test` the engine can match `a t`, but the following `e` fails the lookahead,
 * so `test` is never truncated. `A B` at the end of a string passes it (next char is a space).
 *
 * A single letter never matches — the `+` requires at least one separator, so `A` alone and
 * `I` alone are left alone.
 */
private val SPACED_LETTER_RUN = Regex("(?<![A-Za-z])[A-Za-z](?:[ ]+[A-Za-z])+(?![A-Za-z])")

/**
 * Collapses letter-spaced OCR output back into single words.
 *
 * GLENS returns tracked display type as separate glyphs (`N E T W O R K`, `K I M`), and
 * Android TTS then spells every letter out. Merging restores the intended word so it is
 * spoken as a word.
 *
 * Only ASCII letters participate, so digits (`1 2 3`), units (`5 x 3`) and dotted
 * abbreviations (`U.S.A`) are untouched, and surrounding punctuation is preserved verbatim
 * (`N E T W O R K!` becomes `NETWORK!`).
 *
 * Known limitation: this is a shape heuristic with no notion of meaning, so genuinely
 * letter-spaced English (`I a m`) also collapses to `Iam`. Scanned manga is dominated by
 * tracked display type, and the two cases cannot be told apart without semantics; keeping the
 * rule shape-only and predictable is preferred over guessing.
 *
 * @param text raw OCR text; returned unchanged when it cannot contain a match.
 */
fun mergeSpacedSingleLetters(text: String): String {
    // Shortest mergeable input is "A B" (3 chars), so anything shorter cannot match.
    if (text.length < 3) return text
    return SPACED_LETTER_RUN.replace(text) { match ->
        match.value.filterNot { it == ' ' }
    }
}
