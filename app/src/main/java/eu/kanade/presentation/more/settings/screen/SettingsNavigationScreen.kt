package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.NavTab
import eu.kanade.presentation.components.AppBar
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.plus
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Bottom navigation customization: drag-reorder the five primary tabs.
 * Identity is the [NavTab] constant, never visual position, so the order
 * can never lose or duplicate a tab; malformed data falls back to the
 * default order.
 */
class SettingsNavigationScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val storedOrder by uiPreferences.navTabOrder.collectAsState()

        val tabs = remember {
            NavTab.parseOrder(storedOrder).toMutableStateList()
        }

        val lazyListState = rememberLazyListState()
        val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
            val item = tabs.removeAt(from.index)
            tabs.add(to.index.coerceIn(0, tabs.size), item)
            uiPreferences.navTabOrder.set(NavTab.serializeOrder(tabs))
        }

        LaunchedEffect(storedOrder) {
            if (!reorderableState.isAnyItemDragging) {
                val fresh = NavTab.parseOrder(storedOrder)
                if (fresh != tabs.toList()) {
                    tabs.clear()
                    tabs.addAll(fresh)
                }
            }
        }

        val moveTab: (Int, Int) -> Unit = { from, to ->
            val validTo = to.coerceIn(0, tabs.lastIndex)
            if (from != validTo && from in tabs.indices) {
                val item = tabs.removeAt(from)
                tabs.add(validTo, item)
                uiPreferences.navTabOrder.set(NavTab.serializeOrder(tabs))
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.pref_navigation_tabs),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = lazyListState,
                contentPadding = topSmallPaddingValues +
                    PaddingValues(horizontal = MaterialTheme.padding.medium) + contentPadding,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                items(
                    items = tabs,
                    key = { it.name },
                ) { tab ->
                    val index = tabs.indexOf(tab)
                    ReorderableItem(reorderableState, key = tab.name) {
                        NavTabRow(
                            tab = tab,
                            onMove = { offset -> moveTab(index, index + offset) },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ReorderableCollectionItemScope.NavTabRow(
        tab: NavTab,
        onMove: (Int) -> Unit,
    ) {
        val label = stringResource(tab.titleRes)
        val upLabel = stringResource(MR.strings.action_move_up)
        val downLabel = stringResource(MR.strings.action_move_down)
        ElevatedCard(
            modifier = Modifier.semantics {
                customActions = listOf(
                    CustomAccessibilityAction(upLabel) {
                        onMove(-1)
                        true
                    },
                    CustomAccessibilityAction(downLabel) {
                        onMove(1)
                        true
                    },
                )
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = MaterialTheme.padding.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.DragHandle,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(MaterialTheme.padding.medium)
                        .draggableHandle(),
                )
                Icon(
                    imageVector = tab.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = MaterialTheme.padding.small),
                )
            }
        }
    }
}

private val NavTab.titleRes
    get() = when (this) {
        NavTab.LIBRARY -> MR.strings.label_library
        NavTab.RECENT -> MR.strings.label_recent
        NavTab.FEED -> MR.strings.label_feed
        NavTab.BROWSE -> MR.strings.browse
        NavTab.MORE -> MR.strings.label_more
    }

private val NavTab.icon: ImageVector
    get() = when (this) {
        NavTab.LIBRARY -> Icons.Outlined.GridView
        NavTab.RECENT -> Icons.Outlined.History
        NavTab.FEED -> Icons.Outlined.MenuBook
        NavTab.BROWSE -> Icons.Outlined.Explore
        NavTab.MORE -> Icons.Outlined.MoreHoriz
    }
