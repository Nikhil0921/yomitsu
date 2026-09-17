package tachiyomi.presentation.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Translucent ("Mica-like") surface treatment. When enabled, chrome
 * surfaces (nav pill, sheets, bars) use translucent container colors;
 * when disabled, normal opaque Material surfaces.
 */
val LocalTranslucentSurfaces = staticCompositionLocalOf { false }

/**
 * Active root background treatment: solid themes provide null (screens
 * use their default opaque Scaffold color); gradient mode provides a
 * theme-derived brush drawn behind all screen content.
 */
val LocalAppBackground = staticCompositionLocalOf<Brush?> { null }

/**
 * Bottom navigation translucency: 0 = opaque pill, otherwise the
 * pre-blend alpha for the navigation chrome (bounded 0.55..0.92 so
 * icons/text stay readable).
 */
val LocalNavTranslucency = staticCompositionLocalOf { 0f }

/**
 * Popup/sheet modal translucency: 0 = opaque modal, otherwise the
 * pre-blend alpha for content-area popups and sheets (bounded
 * 0.70..0.92). User-controlled via the same intensity slider as nav.
 */
val LocalPopupTranslucency = staticCompositionLocalOf { 0f }

private const val TRANSLUCENT_CONTAINER_ALPHA = 0.82f

private const val FLOATING_CHROME_ALPHA = 0.85f

// Bounded navigation-pill translucency range; beyond this text/icons suffer.
private const val NAV_TRANSLUCENCY_MIN = 0.55f
private const val NAV_TRANSLUCENCY_MAX = 0.92f

// Bounded modal/sheet translucency range; beyond this text legibility suffers.
private const val MODAL_TRANSLUCENCY_MIN = 0.70f
private const val MODAL_TRANSLUCENCY_MAX = 0.92f

/**
 * Maps a 0..100 intensity preference to the bounded nav translucency
 * alpha range. Pure — usable outside composition.
 */
fun navTranslucencyAlpha(intensityPercent: Int): Float {
    val t = (intensityPercent.coerceIn(0, 100)) / 100f
    return NAV_TRANSLUCENCY_MIN + (NAV_TRANSLUCENCY_MAX - NAV_TRANSLUCENCY_MIN) * t
}

/**
 * Maps a 0..100 intensity preference to the bounded modal/sheet
 * pre-blend alpha range. Same semantic direction as [navTranslucencyAlpha]:
 * higher intensity → higher alpha → more opaque → less translucent.
 * Pure — usable outside composition.
 */
fun modalTranslucencyAlpha(intensityPercent: Int): Float {
    val t = (intensityPercent.coerceIn(0, 100)) / 100f
    return MODAL_TRANSLUCENCY_MIN + (MODAL_TRANSLUCENCY_MAX - MODAL_TRANSLUCENCY_MIN) * t
}

/**
 * Chrome container color for the current mode: translucent (pre-blended
 * with background) when [LocalPopupTranslucency] is enabled (user-controlled
 * intensity) or [LocalTranslucentSurfaces] is enabled (legacy/frosted),
 * the passed color untouched otherwise.
 */
@androidx.compose.runtime.Composable
fun Color.asChromeContainer(): Color {
    val popupAlpha = LocalPopupTranslucency.current
    if (popupAlpha > 0f) return asPreBlendedContainer(popupAlpha)
    if (!LocalTranslucentSurfaces.current) return this
    return asPreBlendedContainer(TRANSLUCENT_CONTAINER_ALPHA)
}

/**
 * Navigation-pill container color: user-controlled translucency via
 * [LocalNavTranslucency] (0 = opaque). Real bounded alpha — the pill
 * floats inset from the screen edge, so what shows through is the app
 * background/gradient, not dense content (readability stays safe).
 */
@androidx.compose.runtime.Composable
fun Color.asNavContainer(): Color {
    val translucency = LocalNavTranslucency.current
    if (translucency <= 0f) return this
    return copy(alpha = translucency)
}

@androidx.compose.runtime.Composable
private fun Color.asPreBlendedContainer(alpha: Float): Color {
    val background = MaterialTheme.colorScheme.background
    return Color(
        red = red * alpha + background.red * (1 - alpha),
        green = green * alpha + background.green * (1 - alpha),
        blue = blue * alpha + background.blue * (1 - alpha),
        alpha = 1f,
    )
}

/**
 * Frosted reader chrome role: surfaces floating directly over the manga
 * artwork (reader bars, pill, indicators). Real translucency — the artwork
 * behind is the frost. Callers must pass a scrim-adequate container color
 * and onSurfaceVariant+ content colors. Never nest frosted surfaces.
 * Returns this (opaque) when translucent surfaces are disabled.
 */
@androidx.compose.runtime.Composable
fun Color.asFloatingChrome(): Color {
    if (!LocalTranslucentSurfaces.current) return this
    return copy(alpha = FLOATING_CHROME_ALPHA)
}

/**
 * Frosted modal role: sheets/modals floating above content (not artwork).
 * Alias of [asChromeContainer] semantics — opaque pre-blend with the theme
 * background, no real see-through.
 */
@androidx.compose.runtime.Composable
fun Color.asFrostedModal(): Color = asChromeContainer()
