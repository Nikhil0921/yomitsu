package mihon.domain.ocr.model

/**
 * Positional reader for the nested arrays ONNX Runtime returns from `OnnxValue.getValue()`.
 *
 * Verified shapes against the real PP-OCRv5 exports on ORT 1.30.0:
 * `det` output `[1, 1, H, W]` arrives as `float[][][][]`, `rec` output `[1, T, C]` as `float[][][]`.
 *
 * Every step is an `is Array<*>` check rather than a cast. That is the whole point: a Kotlin
 * `as? Array<FloatArray>` erases to `Object[]` and therefore *succeeds* against a 3-D value,
 * quietly handing the caller the wrong array — the first version of the engine did exactly that and
 * produced an all-zero probability map instead of boxes. A wrong shape must return null so the
 * caller can fail loudly.
 */
object OcrTensorReader {

    /** Flattens a `[1, 1, H, W]` probability map, or null if [value] is not that shape. */
    fun probabilityMap(value: Any, expectedSize: Int): FloatArray? {
        val batch = value as? Array<*> ?: return null
        if (batch.size != 1) return null
        val channels = batch[0] as? Array<*> ?: return null
        if (channels.size != 1) return null
        val rows = channels[0] as? Array<*> ?: return null

        // Every row of a real [1, 1, H, W] map has the same width; a ragged value is malformed.
        val rowWidth = (rows.firstOrNull() as? FloatArray)?.size ?: return null
        var total = 0
        rows.forEach { row ->
            if (row !is FloatArray || row.size != rowWidth) return null
            total += row.size
        }
        if (total != expectedSize) return null

        val out = FloatArray(expectedSize)
        var offset = 0
        rows.forEach { row ->
            (row as FloatArray).copyInto(out, offset)
            offset += row.size
        }
        return out
    }

    /** Unpacks a `[1, T, C]` recognition output into T rows of C probabilities, or null. */
    fun timeSteps(value: Any): List<FloatArray>? {
        val batch = value as? Array<*> ?: return null
        if (batch.size != 1) return null
        val steps = batch[0] as? Array<*> ?: return null
        val rows = ArrayList<FloatArray>(steps.size)
        steps.forEach { step ->
            if (step !is FloatArray) return null
            rows.add(step)
        }
        return rows
    }
}
