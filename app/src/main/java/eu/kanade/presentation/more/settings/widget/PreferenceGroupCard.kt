package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tachiyomi.presentation.core.theme.header

/**
 * One conceptual group = one visual surface. Settings groups render as
 * tonal grouped-surface containers with the group header inside the
 * surface, rows flat within. Solid (never frosted): settings are
 * long-form readable content. Group boxes stay distinct from the
 * background because the radial gradient peak is capped below the
 * surfaceContainerLow tone (see appBackgroundBrush) — no outline needed.
 */
@Composable
internal fun PreferenceGroupCard(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = 4.dp),
    ) {
        if (title != null) {
            Text(
                text = title,
                modifier = Modifier
                    .semantics { heading() }
                    .padding(start = 16.dp, top = 12.dp, end = 16.dp),
                style = MaterialTheme.typography.header,
            )
        }
        content()
    }
}
