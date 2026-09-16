package tachiyomi.presentation.core.components.material

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tachiyomi.presentation.core.theme.asNavContainer

/**
 * Total bottom clearance of the floating navigation pill, provided by the
 * host that renders it ([HomeScreen]) and folded into [Scaffold] content
 * padding / bottom-anchored slots by nested screen Scaffolds. This lets
 * scrollable content extend under the translucent pill while resting
 * clearance and bottom bars/FAB/snackbars stay anchored above it.
 * Cleared to 0.dp by [Scaffold] for its own content, so nesting never
 * double-counts it.
 */
val LocalNavPillBottomInset = staticCompositionLocalOf { 0.dp }

/**
 * M3 Navbar with no horizontal spacer, drawn as a floating elevated pill
 * separated from the screen bottom edge.
 *
 * @see [androidx.compose.material3.NavigationBar]
 */
@Composable
fun NavigationBar(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer.asNavContainer(),
    contentColor: Color = MaterialTheme.colorScheme.contentColorFor(containerColor),
    tonalElevation: Dp = 3.dp,
    windowInsets: WindowInsets = WindowInsets.navigationBars,
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.Surface(
        color = Color.Transparent,
        contentColor = contentColor,
        modifier = modifier
            .windowInsetsPadding(windowInsets)
            // ponytail: 12/8 floating-pill inset is the frozen pill language; not a padding token
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column {
            androidx.compose.material3.Surface(
                color = containerColor,
                contentColor = contentColor,
                tonalElevation = tonalElevation,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .selectableGroup(),
                    content = content,
                )
            }
        }
    }
}
