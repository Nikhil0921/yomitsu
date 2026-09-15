package eu.kanade.presentation.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.feed.model.FeedItem
import eu.kanade.domain.feed.model.FeedListing
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.feed.FeedScreenModel
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus

/**
 * Centralized feed management: enable/disable, drag-reorder, and delete
 * every configured feed in one list. Mutations reuse FeedScreenModel →
 * FeedPreferences (shared source of truth; Feed tab updates live).
 * Rows follow the reader-toolbar customization pattern (drag handle +
 * move a11y actions); the grouped card surface lives in the Feed screen.
 */
class ManageFeedsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        // Management-only instance: skips per-feed network section fetches.
        val screenModel = rememberScreenModel { FeedScreenModel(loadSectionsOnStart = false) }
        val state by screenModel.state.collectAsState()

        // Ordering mirror for smooth drag; persisted order is the pref list
        // itself (same mechanism the old up/down buttons used).
        val feeds = remember { state.feeds.toMutableStateList() }

        val lazyListState = rememberLazyListState()
        val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
            val item = feeds.removeAt(from.index)
            feeds.add(to.index.coerceIn(0, feeds.size), item)
            screenModel.moveFeedTo(from.index, to.index.coerceIn(0, feeds.lastIndex))
        }

        LaunchedEffect(state.feeds) {
            if (!reorderableState.isAnyItemDragging && state.feeds != feeds.toList()) {
                feeds.clear()
                feeds.addAll(state.feeds)
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.feed_manage),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            if (state.feeds.isEmpty()) {
                EmptyScreen(
                    stringRes = MR.strings.feed_manage_empty,
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }
            LazyColumn(
                state = lazyListState,
                contentPadding = contentPadding + topSmallPaddingValues +
                    PaddingValues(horizontal = MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    items = feeds,
                    // Stable identity: sourceId+listing. FeedItem itself contains
                    // `enabled`, so toggling would change the key mid-drag.
                    key = { "${it.sourceId}:${it.listing}" },
                ) { feed ->
                    val index = feeds.indexOf(feed)
                    ReorderableItem(reorderableState, key = "${feed.sourceId}:${feed.listing}") {
                        ManageFeedRow(
                            feed = feed,
                            state = state,
                            onToggle = { screenModel.setFeedEnabled(feed, it) },
                            onMove = { from, to -> screenModel.moveFeedTo(from, to) },
                            index = index,
                            onDelete = { screenModel.deleteFeed(feed) },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ReorderableCollectionItemScope.ManageFeedRow(
        feed: FeedItem,
        state: FeedScreenModel.State,
        onToggle: (Boolean) -> Unit,
        onMove: (Int, Int) -> Unit,
        index: Int,
        onDelete: () -> Unit,
    ) {
        val source = state.sources.firstOrNull { it.id == feed.sourceId }
        val feedName = source?.visualName ?: stringResource(MR.strings.feed_source_unavailable)
        val upLabel = stringResource(MR.strings.action_move_up)
        val downLabel = stringResource(MR.strings.action_move_down)
        val listingLabel = stringResource(
            if (feed.listing == FeedListing.LATEST) MR.strings.latest else MR.strings.popular,
        )
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = feedName
                    customActions = listOf(
                        CustomAccessibilityAction(upLabel) {
                            onMove(index, index - 1)
                            true
                        },
                        CustomAccessibilityAction(downLabel) {
                            onMove(index, index + 1)
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(MaterialTheme.padding.medium)
                        .draggableHandle(),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(feedName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listingLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = feed.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = feedName },
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(MR.strings.action_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
