package mihon.data.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlensTileMathTest {

    private val expRule: (Int) -> Int = { width -> minOf((width * 2.4f).toInt(), 2000) }

    @Test
    fun productionRuleMatchesStage4BWorkload() {
        val tiling = tileTopsFor(width = 690, height = 20128)
        assertEquals(1242, tiling.tileHeight)
        assertEquals(20, tiling.tops.size)
        assertEquals(0, tiling.tops.first())
        assertEquals(994, tiling.tops[1] - tiling.tops[0])
    }

    @Test
    fun experimentalRuleHalvesTileCount() {
        val tiling = tileTopsFor(width = 690, height = 20128, tileHeightFor = expRule)
        assertEquals(1656, tiling.tileHeight)
        assertEquals(15, tiling.tops.size)
        assertEquals(1325, tiling.tops[1] - tiling.tops[0])
    }

    @Test
    fun experimentalRuleOnStage4ERepresentative() {
        val tiling = tileTopsFor(width = 820, height = 14467, tileHeightFor = expRule)
        assertEquals(1968, tiling.tileHeight)
        assertEquals(9, tiling.tops.size)
    }

    @Test
    fun coverageHoldsNoGaps() {
        val cases = listOf(
            Triple(690, 20128, null),
            Triple(690, 20128, expRule),
            Triple(820, 14467, expRule),
            Triple(690, 14900, expRule),
            Triple(100, 3000, null),
        )
        for ((width, height, rule) in cases) {
            val tiling = tileTopsFor(width, height, rule)
            val tops = tiling.tops
            assertEquals(0, tops.first(), "w=$width h=$height")
            assertTrue(tops.last() + tiling.tileHeight >= height, "w=$width h=$height")
            for (i in 1 until tops.size) {
                assertTrue(tops[i] > tops[i - 1], "w=$width h=$height")
                assertTrue(tops[i] < tops[i - 1] + tiling.tileHeight, "w=$width h=$height overlap")
            }
        }
    }

    @Test
    fun minHeightClampApplies() {
        val tiling = tileTopsFor(width = 100, height = 3000)
        assertEquals(1000, tiling.tileHeight)
    }
}
