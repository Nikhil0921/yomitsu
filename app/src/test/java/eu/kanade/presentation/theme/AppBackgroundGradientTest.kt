package eu.kanade.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AppBackgroundGradientTest {

    private val background = Color(0xFF1B1B1F)
    private val container = Color(0xFF211F26)

    @Test
    fun `zero intensity is plain background at every stop`() {
        assertEquals(listOf(background, background, background), backgroundGradientColors(background, container, 0))
    }

    @Test
    fun `full intensity peaks capped below container and decays to background`() {
        val stops = backgroundGradientColors(background, container, 100)
        assertClose(background.red + (container.red - background.red) * 0.55f, stops[0].red)
        assertClose(background.red + (container.red - background.red) * 0.25f, stops[1].red)
        assertEquals(background, stops[2])
        // Ramp is monotonic away from the container tone, so the card
        // (container) fill never matches any background stop.
        assertEquals(true, stops[0].luminance() > stops[1].luminance())
        assertEquals(true, stops[1].luminance() > stops[2].luminance())
    }

    @Test
    fun `intensity outside range is coerced`() {
        assertEquals(
            backgroundGradientColors(background, container, 0),
            backgroundGradientColors(background, container, -20),
        )
        assertEquals(
            backgroundGradientColors(background, container, 100),
            backgroundGradientColors(background, container, 250),
        )
    }

    private fun assertClose(expected: Float, actual: Float) {
        // Compose Color quantizes channels to 8 bits; allow one ULP.
        org.junit.jupiter.api.Assertions.assertTrue(abs(expected - actual) <= 1f / 255f)
    }
}
