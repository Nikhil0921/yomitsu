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

            // A pixel at index x covers [x, x + 1] of the scaled page.
            val boxWidth = (maxX - minX + 1).toFloat()
            val boxHeight = (maxY - minY + 1).toFloat()
            val area = boxWidth * boxHeight
            val perimeter = 2f * (boxWidth + boxHeight)
            val distance = if (perimeter > 0f) area * config.unclipRatio / perimeter else 0f

            val scaleX = imageWidth / mapWidth
            val scaleY = imageHeight / mapHeight
            val left = ((minX - distance) * scaleX).coerceAtLeast(0f)
            val top = ((minY - distance) * scaleY).coerceAtLeast(0f)
            val right = (minOf(maxX + 1f + distance, mapWidth.toFloat()) * scaleX)
                .coerceAtMost(imageWidth)
            val bottom = (minOf(maxY + 1f + distance, mapHeight.toFloat()) * scaleY)
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
