package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colors for Frosted theme: a cool, restrained, editorial surface palette.
 * Low-chroma blue-grey containers with a slate primary; designed to read as
 * calm chrome over the frosted/translucent surface treatment.
 */
internal object FrostedColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFa9c7de),
        onPrimary = Color(0xFF113047),
        primaryContainer = Color(0xFF294b63),
        onPrimaryContainer = Color(0xFFcbe4f7),
        secondary = Color(0xFF93b7cf), // Unread badge
        onSecondary = Color(0xFF0e293f),
        secondaryContainer = Color(0xFF2b4a62), // Navigation bar selector pill & progress indicator (remaining)
        onSecondaryContainer = Color(0xFFbcd7ea),
        tertiary = Color(0xFF8fc3b8), // Downloaded badge
        onTertiary = Color(0xFF04302a),
        tertiaryContainer = Color(0xFF254f48),
        onTertiaryContainer = Color(0xFFb2ded3),
        error = Color(0xFFffb4ab),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000a),
        onErrorContainer = Color(0xFFffdad6),
        background = Color(0xFF11181f),
        onBackground = Color(0xFFdbe4ec),
        surface = Color(0xFF11181f),
        onSurface = Color(0xFFdbe4ec),
        surfaceVariant = Color(0xFF1d2731), // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = Color(0xFFb2bfc9),
        outline = Color(0xFF7c8b97),
        outlineVariant = Color(0xFF414b54),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFdbe4ec),
        inverseOnSurface = Color(0xFF2b3440),
        inversePrimary = Color(0xFF4a5f72),
        surfaceTint = Color(0xFFa9c7de),
        surfaceDim = Color(0xFF11181f),
        surfaceBright = Color(0xFF36413c),
        surfaceContainerLowest = Color(0xFF161d24),
        surfaceContainerLow = Color(0xFF1a222a),
        surfaceContainer = Color(0xFF1d2731), // Navigation bar background
        surfaceContainerHigh = Color(0xFF22303b),
        surfaceContainerHighest = Color(0xFF283642),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFF3d5a72),
        onPrimary = Color(0xFFffffff),
        primaryContainer = Color(0xFFd3e3f0),
        onPrimaryContainer = Color(0xFF16262f),
        secondary = Color(0xFF3d5a72), // Unread badge
        onSecondary = Color(0xFFffffff),
        secondaryContainer = Color(0xFFc8d9e8), // Navigation bar selector pill & progress indicator (remaining)
        onSecondaryContainer = Color(0xFF16262f),
        tertiary = Color(0xFF3e6b60), // Downloaded badge
        onTertiary = Color(0xFFffffff),
        tertiaryContainer = Color(0xFFc4e4db),
        onTertiaryContainer = Color(0xFF0d251f),
        error = Color(0xFFba1a1a),
        onError = Color(0xFFffffff),
        errorContainer = Color(0xFFffdad6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFf7fafc),
        onBackground = Color(0xFF181f25),
        surface = Color(0xFFf7fafc),
        onSurface = Color(0xFF181f25),
        surfaceVariant = Color(0xFFe3e9ee), // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = Color(0xFF414b54),
        outline = Color(0xFF71818f),
        outlineVariant = Color(0xFFc1cdd6),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF2b3440),
        inverseOnSurface = Color(0xFFf0f3f6),
        inversePrimary = Color(0xFF9ab8d2),
        surfaceTint = Color(0xFF3d5a72),
        surfaceDim = Color(0xFFd7dee4),
        surfaceBright = Color(0xFFf7fafc),
        surfaceContainerLowest = Color(0xFFffffff),
        surfaceContainerLow = Color(0xFFeff4f8),
        surfaceContainer = Color(0xFFe3e9ee), // Navigation bar background
        surfaceContainerHigh = Color(0xFFdde5ec),
        surfaceContainerHighest = Color(0xFFd5dee6),
    )
}
