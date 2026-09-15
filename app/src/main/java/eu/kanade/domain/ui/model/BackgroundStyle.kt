package eu.kanade.domain.ui.model

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * Background treatment below screen content. SOLID is the historical
 * opaque background; GRADIENT derives a subtle vertical tonal separation
 * from the active theme's semantic surface palette.
 */
enum class BackgroundStyle(val titleRes: StringResource) {
    SOLID(MR.strings.background_style_solid),
    GRADIENT(MR.strings.background_style_gradient),
}
