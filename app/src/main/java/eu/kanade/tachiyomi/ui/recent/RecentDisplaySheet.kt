package eu.kanade.tachiyomi.ui.recent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.kanade.presentation.more.settings.widget.PreferenceGroupCard
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Display settings sheet for the Recent tab, mirroring the Library tab's
 * display-sheet pattern: a "Display" group with a "Show tabs" switch that
 * toggles the Continue/History/Updates sub-tab row.
 */
@Composable
fun RecentDisplaySheet(
    onDismiss: () -> Unit,
) {
    val libraryPreferences = Injekt.get<LibraryPreferences>()
    val showTabs by libraryPreferences.showRecentTabs.collectAsState()

    eu.kanade.presentation.components.AdaptiveSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.padding.medium,
                    vertical = MaterialTheme.padding.medium,
                ),
        ) {
            PreferenceGroupCard(title = stringResource(MR.strings.action_display)) {
                SwitchPreferenceWidget(
                    title = stringResource(MR.strings.show_recent_tabs),
                    checked = showTabs,
                    onCheckedChanged = { libraryPreferences.showRecentTabs.set(it) },
                )
            }
        }
    }
}
