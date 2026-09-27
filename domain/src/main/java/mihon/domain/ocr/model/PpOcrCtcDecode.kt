package mihon.domain.ocr.model

/**
 * Greedy CTC decode for the PP-OCRv5 recognizer.
 *
 * The exported model already ends in a Softmax, so [probabilities] holds probabilities and the mean
 * of the kept peaks is a usable per-region confidence — which is the only confidence signal in the
 * whole OCR stack (the cloud proto is never asked for one), and therefore the only thing the
 * adaptive router may escalate on.
 */
object PpOcrCtcDecode {

    private const val BLANK = 0

    /**
     * @param classCount the model's real CTC class count, [PpOcrCharset.CLASS_COUNT] for the
     *   official `en` export (blank + 436 characters + space). The engine asserts that at init, and
     *   a class past the end of [vocabulary] is skipped rather than crashing so a mismatched model
     *   still yields partial text.
     */
    fun decode(
        probabilities: FloatArray,
        timeSteps: Int,
        vocabulary: List<String> = PpOcrCharset.CHARACTERS,
        classCount: Int = PpOcrCharset.CLASS_COUNT,
    ): PpOcrRecognition {
        if (timeSteps <= 0 || probabilities.isEmpty() || classCount <= 0) {
            return PpOcrRecognition("", 0f)
        }
        // A model/dict pairing mistake must not read past the buffer.
        val steps = minOf(timeSteps, probabilities.size / classCount)
        if (steps <= 0) return PpOcrRecognition("", 0f)

        val text = StringBuilder()
        var previous = -1
        var confidenceSum = 0f
        var confidenceCount = 0

        for (step in 0 until steps) {
            val row = step * classCount
            var best = 0
            var bestProbability = -1f
            for (cls in 0 until classCount) {
                val probability = probabilities[row + cls]
                if (probability > bestProbability) {
                    bestProbability = probability
                    best = cls
                }
            }

            if (best != previous) {
                previous = best
                if (best != BLANK) {
                    // Class 1..n decodes to vocabulary[0..n), the trailing class is a space, and
                    // anything past the end of a mismatched model is skipped rather than fatal.
                    val character = when {
                        best == PpOcrCharset.SPACE_CLASS -> " "
                        best in 1..vocabulary.size -> vocabulary[best - 1]
                        else -> null
                    }
                    if (character != null) {
                        text.append(character)
                        confidenceSum += bestProbability
                        confidenceCount++
                    }
                }
            }
        }

        val confidence = if (confidenceCount == 0) 0f else confidenceSum / confidenceCount
        return PpOcrRecognition(text.toString(), confidence)
    }
}

/** Decoded text plus the mean CTC probability of the peaks that produced it. */
data class PpOcrRecognition(
    val text: String,
    val confidence: Float,
)
