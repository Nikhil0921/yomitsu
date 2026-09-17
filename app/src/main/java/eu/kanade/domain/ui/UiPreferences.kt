package eu.kanade.domain.ui

import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.BackgroundStyle
import eu.kanade.domain.ui.model.TabletUiMode
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.isDynamicColorAvailable
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class UiPreferences(
    preferenceStore: PreferenceStore,
) {

    val themeMode: Preference<ThemeMode> = preferenceStore.getEnum("pref_theme_mode_key", ThemeMode.SYSTEM)

    val appTheme: Preference<AppTheme> = preferenceStore.getEnum(
        "pref_app_theme",
        if (DeviceUtil.isDynamicColorAvailable) {
            AppTheme.MONET
        } else {
            AppTheme.DEFAULT
        },
    )

    val themeDarkAmoled: Preference<Boolean> = preferenceStore.getBoolean("pref_theme_dark_amoled_key", false)

    val translucentTheme: Preference<Boolean> = preferenceStore.getBoolean("pref_theme_translucent_key", false)

    val immersiveMode: Preference<Boolean> = preferenceStore.getBoolean("pref_immersive_mode", false)

    val navTabOrder: Preference<String> = preferenceStore.getString("pref_nav_tab_order", "")

    val navBarTranslucent: Preference<Boolean> = preferenceStore.getBoolean("pref_nav_bar_translucent", false)

    val navBarTranslucency: Preference<Int> = preferenceStore.getInt("pref_nav_bar_translucency", 60)

    val popupSheetTranslucent: Preference<Boolean> = preferenceStore.getBoolean("pref_popup_sheet_translucent", false)

    val backgroundStyle: Preference<BackgroundStyle> = preferenceStore.getEnum(
        "pref_background_style",
        BackgroundStyle.SOLID,
    )

    val backgroundGradientIntensity: Preference<Int> = preferenceStore.getInt("pref_background_gradient_intensity", 35)

    val relativeTime: Preference<Boolean> = preferenceStore.getBoolean("relative_time_v2", true)

    val dateFormat: Preference<String> = preferenceStore.getString("app_date_format", "")

    val tabletUiMode: Preference<TabletUiMode> = preferenceStore.getEnum("tablet_ui_mode", TabletUiMode.AUTOMATIC)

    val imagesInDescription: Preference<Boolean> = preferenceStore.getBoolean("pref_render_images_description", true)

    companion object {
        fun dateFormat(format: String): DateTimeFormatter = when (format) {
            "" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            else -> DateTimeFormatter.ofPattern(format, Locale.getDefault())
        }
    }
}
