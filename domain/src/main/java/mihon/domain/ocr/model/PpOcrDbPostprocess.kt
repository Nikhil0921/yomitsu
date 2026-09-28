package mihon.domain.ocr.model

/**
 * DBNet post-processing: probability map to text boxes.
 *
 * Pure Kotlin, no OpenCV — the connected-component pass is a plain 8-neighbour flood fill, which
 * also means it is unit-testable with a synthetic map instead of needing a device.
 *
 * `ponytail:` boxes are the component's bounding box grown isotropically by
 * `area * unclipRatio / perimeter`, which is what upstream PaddleOCR does, instead of a rotated
 * minimum-area rectangle from `cv2.minAreaRect`. Fine for axis-aligned manga text; add the
 * rotated rect if vertical or slanted bubble text ever needs tighter crops.
 */
object PpOcrDbPostprocess {

    /**
     * Share of the shorter box that must overlap vertically for two boxes to be one text line. A
     * glyph and its neighbour overlap almost completely; two separate lines of a bubble do not.
     */
    const val SAME_LINE_VERTICAL_OVERLAP = 0.6f

    /**
     * Horizontal gap between two boxes, as a multiple of the TALLER box's height, still treated as
     * one line.
     *
     * 1.5, up from 0.8. 0.8 is about the inter-word space of display type — 0.25-0.35 em against a
     * 0.7 em glyph — so at 0.8 a gap that is still ordinary word spacing refused to join, leaving
     * one region per word, and a region boundary is a pause in Android TTS.
     *
     * The taller box, not the shorter, because a descender ("g") or an ascender ("h") is a taller
     * box than its x-height neighbour ("a"). Sizing the bound from the shorter box let a
     * descender/short pair reject a gap it should accept: measured on hardware 2026-09-28, 84 of
     * 514 recognition calls were still a square `in=48x48` crop, and 8 of 19 spoken pages still
     * carried single-glyph regions.
     *
     * ponytail: the guard against swallowing a second speech bubble is the ratio itself. Measured on
     * 2026-09-28, two side-by-side bubbles leave a gap of 3.3x the taller box's height, so 1.5 has
     * about 2.2x of headroom. If real content ever reads two bubbles as one sentence, lower this
     * before touching anything else; if letter-spaced words still come apart, raise it.
     */
    const val SAME_LINE_MAX_GAP_RATIO = 1.5f

    fun boxes(
        probability: FloatArray,
        mapWidth: Int,
        mapHeight: Int,
        imageWidth: Float,
        imageHeight: Float,
        config: PpOcrDbConfig = PpOcrDbConfig(),
    ): List<OcrBoundingBox> {
        if (mapWidth <= 0 || mapHeight <= 0) return emptyList()
        if (probability.size < mapWidth * mapHeight) return emptyList()
        if (imageWidth <= 0f || imageHeight <= 0f) return emptyList()

        val components = components(probability, mapWidth, mapHeight, config)
        val found = ArrayList<OcrBoundingBox>(components.size)
        val scaleX = imageWidth / mapWidth
        val scaleY = imageHeight / mapHeight

        // Group into text lines FIRST, then unclip once per line. Unclipping each component on its
        // own would grow neighbouring glyphs until they overlap, and by then the gap that said
        // "same line" is gone — which is why merging on the already-unclipped boxes merged
        // everything on a row.
        for (component in mergeSameLineBoxes(components)) {
            val boxWidth = component.width
            val boxHeight = component.height
            val area = boxWidth * boxHeight
            val perimeter = 2f * (boxWidth + boxHeight)
            val distance = if (perimeter > 0f) area * config.unclipRatio / perimeter else 0f

            val left = ((component.left - distance) * scaleX).coerceAtLeast(0f)
            val top = ((component.top - distance) * scaleY).coerceAtLeast(0f)
            val right = (minOf(component.right + distance, mapWidth.toFloat()) * scaleX)
                .coerceAtMost(imageWidth)
            val bottom = (minOf(component.bottom + distance, mapHeight.toFloat()) * scaleY)
                .coerceAtMost(imageHeight)

            if (right - left < imageWidth * config.minSideFraction) continue
            if (bottom - top < imageHeight * config.minSideFraction) continue

            val box = OcrBoundingBox(
                left = left / imageWidth,
                top = top / imageHeight,
                right = right / imageWidth,
                bottom = bottom / imageHeight,
            )
            if (box.isValid()) found += box
        }

        return found
            .sortedWith(compareBy({ it.top }, { it.left }))
            .take(config.maxBoxes)
    }

    /**
     * The raw connected components, in map coordinates, before any line grouping or unclipping.
     *
     * Exposed so the flood fill's own contract stays testable on its own: a white gap between two
     * blobs really does yield two components, whatever the line grouping does with them afterwards.
     */
    fun components(
        probability: FloatArray,
        mapWidth: Int,
        mapHeight: Int,
        config: PpOcrDbConfig = PpOcrDbConfig(),
    ): List<OcrBoundingBox> {
        val visited = BooleanArray(mapWidth * mapHeight)
        val queue = IntArray(mapWidth * mapHeight)
        val found = ArrayList<OcrBoundingBox>()

        for (start in probability.indices) {
            if (visited[start] || probability[start] < config.binaryThreshold) continue
            val count = floodFill(probability, visited, queue, start, mapWidth, mapHeight, config.binaryThreshold)
            if (count < MIN_BLOB_PIXELS) continue

            var minX = mapWidth
            var minY = mapHeight
            var maxX = -1
            var maxY = -1
            var scoreSum = 0f
            for (index in queue.indices) {
                if (index >= count) break
                val pixel = queue[index]
                val x = pixel % mapWidth
                val y = pixel / mapWidth
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                scoreSum += probability[pixel]
            }
            if (maxX < 0 || scoreSum / count < config.boxThreshold) continue

            // A pixel at index x covers [x, x + 1] of the map.
            val box = OcrBoundingBox(
                left = minX.toFloat(),
                top = minY.toFloat(),
                right = (maxX + 1).toFloat(),
                bottom = (maxY + 1).toFloat(),
            )
            if (box.isValid()) found += box
        }

        return found
    }

    /**
     * Joins boxes that are one text line which DB happened to split into pieces.
     *
     * The flood fill above returns one box per connected component, so tracked display type — and a
     * low [PpOcrDbConfig.binaryThreshold] — can split a single word into one box PER GLYPH. Each
     * glyph then becomes its own `OcrRegion`, and `SentenceSegmenter` may not merge across regions
     * (prd F2), so Android TTS spells the word out letter by letter. Measured on hardware: 270 of
     * 1098 recognition calls were a square `in=48x48` crop, which is exactly what
     * [PpOcrPreprocess.recognitionInputSize] produces for a box narrower than it is tall. Fixing it
     * here repairs the OCR rather than papering over bad OCR in the speech layer.
     *
     * Two boxes join only when they share a baseline and are close enough to be one line:
     * a vertical overlap of at least [SAME_LINE_VERTICAL_OVERLAP] of the shorter box,
     * and a horizontal gap no larger than [SAME_LINE_MAX_GAP_RATIO] of the taller one. The gap
     * bound is what keeps two bubbles sitting side by side on one row apart.
     *
     * The two bounds are sized from different boxes on purpose. The overlap asks "does the shorter
     * box sit substantially inside the taller one", which is what a shared baseline means, so it
     * is measured against the shorter. The gap asks "is the tracking between them still word
     * spacing", and a descender must not shrink that answer, so it is measured against the taller.
     * Sizing the overlap from the taller box instead would make the rule stricter and merge fewer
     * boxes, which is the opposite of what this function exists to do.
     *
     * Merging is transitive and left-to-right, so a whole word of glyphs collapses to one box.
     */
    fun mergeSameLineBoxes(boxes: List<OcrBoundingBox>): List<OcrBoundingBox> {
        if (boxes.size < 2) return boxes
        val lines = mutableListOf<MutableList<OcrBoundingBox>>()
        // Top-to-bottom so a row is contiguous; a box joins the first row it shares a baseline with.
        for (box in boxes.sortedBy { it.top }) {
            val row = lines.firstOrNull { candidate -> candidate.any { isSameLine(it, box) } }
            if (row != null) {
                row += box
            } else {
                lines += mutableListOf(box)
            }
        }
        return lines.map { row ->
            row.reduce { a, b ->
                OcrBoundingBox(
                    left = minOf(a.left, b.left),
                    top = minOf(a.top, b.top),
                    right = maxOf(a.right, b.right),
                    bottom = maxOf(a.bottom, b.bottom),
                )
            }
        }
    }

    private fun isSameLine(a: OcrBoundingBox, b: OcrBoundingBox): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (overlap < minOf(a.height, b.height) * SAME_LINE_VERTICAL_OVERLAP) return false
        val gap = b.left - a.right
        return gap <= maxOf(a.height, b.height) * SAME_LINE_MAX_GAP_RATIO
    }

    /** 8-connected flood fill; returns how many pixels are in the blob and leaves them in [queue]. */
    private fun floodFill(
        probability: FloatArray,
        visited: BooleanArray,
        queue: IntArray,
        start: Int,
        width: Int,
        height: Int,
        threshold: Float,
    ): Int {
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true
        while (head < tail) {
            val pixel = queue[head++]
            val x = pixel % width
            val y = pixel / width
            for (dy in -1..1) {
                val ny = y + dy
                if (ny < 0 || ny >= height) continue
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    if (nx < 0 || nx >= width) continue
                    val next = ny * width + nx
                    if (visited[next] || probability[next] < threshold) continue
                    visited[next] = true
                    queue[tail++] = next
                }
            }
        }
        return tail
    }

    private const val MIN_BLOB_PIXELS = 4
}

/** Upstream DBNet post-processing defaults (`PaddleOCR` `DBPostProcess`). */
data class PpOcrDbConfig(
    val binaryThreshold: Float = 0.3f,
    val boxThreshold: Float = 0.6f,
    val unclipRatio: Float = 1.6f,
    val maxBoxes: Int = 1000,
    /** Smallest box side, as a fraction of the page; below this it is noise, not text. */
    val minSideFraction: Float = 0.003f,
) {
    init {
        require(maxBoxes > 0) { "maxBoxes must be positive" }
    }
}
