package mihon.data.ocr

import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrRegion
import mihon.domain.ocr.model.OcrTextOrientation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Seam dedupe of the tiled GLENS path.
 *
 * A tall page is split into 13 tiles with 20% vertical overlap, so every bubble inside an overlap
 * band is recognised TWICE, at two tile-local positions. The old rule compared IoU alone, which
 * failed in both directions: a bubble the boundary cut comes back from each tile as a *partial*
 * box whose IoU can sit well under the threshold (so the same words are spoken twice), while a
 * small bubble nested inside a large one crosses it with completely different text and is deleted
 * outright. Device evidence: 7-10 regions dropped per page on a 13-tile page
 * (docs/audits/full-ocr-pipeline-audit.md §5, F3.1/F3.2).
 */
class GlensSeamDedupeTest {

    @Test
    fun identicalTextAtNearlyTheSameSpotIsASeamDuplicate() {
        val regions = listOf(
            region("Changes", top = 0.400f, bottom = 0.420f),
            // Same physical text, seen from the neighbouring tile: shifted a little and shorter,
            // so its IoU against the first is far below the old 0.45 threshold.
            region("Changes", top = 0.406f, bottom = 0.418f),
        )

        assertEquals(1, dedupeTileSeamDuplicates(regions).size)
    }

    @Test
    fun identicalTextWithDifferentSpacingAndCaseIsStillADuplicate() {
        val regions = listOf(
            region("What are you doing", top = 0.100f, bottom = 0.120f),
            region("What  are   YOU  doing", top = 0.104f, bottom = 0.119f),
        )

        assertEquals(1, dedupeTileSeamDuplicates(regions).size)
    }

    @Test
    fun aSmallBubbleNestedInALargerOneSurvives() {
        val regions = listOf(
            // The big bubble's box is the union AABB of its merged lines, so it routinely overlaps
            // a neighbour. The texts differ, so this is NOT a seam duplicate.
            region("I never said she stole the money", top = 0.200f, bottom = 0.260f),
            region("Hey!", top = 0.215f, bottom = 0.230f),
        )

        val kept = dedupeTileSeamDuplicates(regions)
        assertEquals(2, kept.size)
    }

    @Test
    fun theSameWordsInTwoSeparateBubblesBothSurvive() {
        val regions = listOf(
            region("No", top = 0.100f, bottom = 0.120f),
            region("No", top = 0.400f, bottom = 0.420f),
        )

        assertEquals(2, dedupeTileSeamDuplicates(regions).size)
    }

    @Test
    fun regionOrderIsPreserved() {
        val regions = listOf(
            region("Second", order = 1, top = 0.400f, bottom = 0.420f),
            region("First", order = 0, top = 0.100f, bottom = 0.120f),
            region("First", order = 2, top = 0.104f, bottom = 0.119f),
        )

        val kept = dedupeTileSeamDuplicates(regions)
        assertEquals(listOf("Second", "First"), kept.map { it.text })
    }

    private fun region(
        text: String,
        order: Int = 0,
        top: Float,
        bottom: Float,
    ): OcrRegion = OcrRegion(
        order = order,
        text = text,
        boundingBox = OcrBoundingBox(left = 0.1f, top = top, right = 0.6f, bottom = bottom),
        textOrientation = OcrTextOrientation.Horizontal,
    )
}
