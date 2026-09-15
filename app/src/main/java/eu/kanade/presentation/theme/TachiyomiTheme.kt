package eu.kanade.presentation.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.BackgroundStyle
import eu.kanade.presentation.theme.colorscheme.BaseColorScheme
import eu.kanade.presentation.theme.colorscheme.CatppuccinColorScheme
import eu.kanade.presentation.theme.colorscheme.FrostedColorScheme
import eu.kanade.presentation.theme.colorscheme.GreenAppleColorScheme
import eu.kanade.presentation.theme.colorscheme.LavenderColorScheme
import eu.kanade.presentation.theme.colorscheme.MidnightDuskColorScheme
import eu.kanade.presentation.theme.colorscheme.MonetColorScheme
import eu.kanade.presentation.theme.colorscheme.MonochromeColorScheme
import eu.kanade.presentation.theme.colorscheme.NordColorScheme
import eu.kanade.presentation.theme.colorscheme.StrawberryColorScheme
import eu.kanade.presentation.theme.colorscheme.TachiyomiColorScheme
import eu.kanade.presentation.theme.colorscheme.TakoColorScheme
import eu.kanade.presentation.theme.colorscheme.TealTurqoiseColorScheme
import eu.kanade.presentation.theme.colorscheme.TidalWaveColorScheme
import eu.kanade.presentation.theme.colorscheme.YinYangColorScheme
import eu.kanade.presentation.theme.colorscheme.YotsubaColorScheme
import tachiyomi.presentation.core.theme.LocalAppBackground
import tachiyomi.presentation.core.theme.LocalNavTranslucency
import tachiyomi.presentation.core.theme.LocalTranslucentSurfaces
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun TachiyomiTheme(
    appTheme: AppTheme? = null,
    amoled: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val uiPreferences = Injekt.get<UiPreferences>()
    val resolvedTheme = appTheme ?: uiPreferences.appTheme.get()
    val navTranslucent = uiPreferences.navBarTranslucent.get()
    val navIntensity = uiPreferences.navBarTranslucency.get()
    BaseTachiyomiTheme(
        appTheme = resolvedTheme,
        isAmoled = amoled ?: uiPreferences.themeDarkAmoled.get(),
        isTranslucent = uiPreferences.translucentTheme.get() || resolvedTheme == AppTheme.FROSTED,
        backgroundStyle = uiPreferences.backgroundStyle.get(),
        gradientIntensity = uiPreferences.backgroundGradientIntensity.get(),
        // OFF = opaque (0f bypasses asNavContainer); ON = bounded 0.55..0.92 alpha.
        navTranslucencyAlpha = if (navTranslucent) {
            tachiyomi.presentation.core.theme.navTranslucencyAlpha(navIntensity)
        } else {
            0f
        },
        content = content,
    )
}

@Composable
fun TachiyomiPreviewTheme(
    appTheme: AppTheme = AppTheme.DEFAULT,
    isAmoled: Boolean = false,
    content: @Composable () -> Unit,
) = BaseTachiyomiTheme(
    appTheme = appTheme,
    isAmoled = isAmoled,
    isTranslucent = false,
    backgroundStyle = BackgroundStyle.SOLID,
    gradientIntensity = 0,
    navTranslucencyAlpha = 0f,
    content = content,
)

@Composable
private fun BaseTachiyomiTheme(
    appTheme: AppTheme,
    isAmoled: Boolean,
    isTranslucent: Boolean = false,
    backgroundStyle: BackgroundStyle = BackgroundStyle.SOLID,
    gradientIntensity: Int = 0,
    navTranslucencyAlpha: Float = 0f,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalTranslucentSurfaces provides isTranslucent,
        LocalNavTranslucency provides navTranslucencyAlpha,
        LocalAppBackground provides remember(appTheme, isDark, isAmoled, backgroundStyle, gradientIntensity) {
            appBackgroundBrush(
                context = context,
                appTheme = appTheme,
                isDark = isDark,
                isAmoled = isAmoled,
                backgroundStyle = backgroundStyle,
                gradientIntensity = gradientIntensity,
            )
        },
    ) {
        MaterialExpressiveTheme(
            colorScheme = remember(appTheme, isDark, isAmoled) {
                getThemeColorScheme(
                    context = context,
                    appTheme = appTheme,
                    isDark = isDark,
                    isAmoled = isAmoled,
                )
            },
            content = content,
        )
    }
}

/**
 * Root background treatment. Solid uses the theme background color directly;
 * gradient derives a subtle vertical tonal separation from the theme's own
 * surface containers, scaled by intensity (0 → background, 100 → full
 * surfaceContainerLow separation). Always theme-derived, never hard-coded.
 */
private fun appBackgroundBrush(
    context: Context,
    appTheme: AppTheme,
    isDark: Boolean,
    isAmoled: Boolean,
    backgroundStyle: BackgroundStyle,
    gradientIntensity: Int,
): Brush {
    val scheme = getThemeColorScheme(context, appTheme, isDark, isAmoled)
    if (backgroundStyle != BackgroundStyle.GRADIENT) {
        return Brush.verticalGradient(listOf(scheme.background, scheme.background))
    }
    val t = (gradientIntensity.coerceIn(0, 100)) / 100f
    val end = lerpColor(scheme.background, scheme.surfaceContainerLow, t)
    return Brush.verticalGradient(listOf(scheme.background, end))
}

private fun lerpColor(start: Color, stop: Color, fraction: Float): Color = Color(
    red = start.red + (stop.red - start.red) * fraction,
    green = start.green + (stop.green - start.green) * fraction,
    blue = start.blue + (stop.blue - start.blue) * fraction,
    alpha = 1f,
)

private fun getThemeColorScheme(
    context: Context,
    appTheme: AppTheme,
    isDark: Boolean,
    isAmoled: Boolean,
): ColorScheme {
    val colorScheme = if (appTheme == AppTheme.MONET) {
        MonetColorScheme(context)
    } else {
        colorSchemes.getOrDefault(appTheme, TachiyomiColorScheme)
    }
    return colorScheme.getColorScheme(
        isDark = isDark,
        isAmoled = isAmoled,
        overrideDarkSurfaceContainers = appTheme != AppTheme.MONET,
    )
}

private val colorSchemes: Map<AppTheme, BaseColorScheme> = mapOf(
    AppTheme.DEFAULT to TachiyomiColorScheme,
    AppTheme.CATPPUCCIN to CatppuccinColorScheme,
    AppTheme.GREEN_APPLE to GreenAppleColorScheme,
    AppTheme.LAVENDER to LavenderColorScheme,
    AppTheme.MIDNIGHT_DUSK to MidnightDuskColorScheme,
    AppTheme.MONOCHROME to MonochromeColorScheme,
    AppTheme.NORD to NordColorScheme,
    AppTheme.STRAWBERRY_DAIQUIRI to StrawberryColorScheme,
    AppTheme.TAKO to TakoColorScheme,
    AppTheme.TEALTURQUOISE to TealTurqoiseColorScheme,
    AppTheme.TIDAL_WAVE to TidalWaveColorScheme,
    AppTheme.YINYANG to YinYangColorScheme,
    AppTheme.YOTSUBA to YotsubaColorScheme,
    AppTheme.FROSTED to FrostedColorScheme,
)
