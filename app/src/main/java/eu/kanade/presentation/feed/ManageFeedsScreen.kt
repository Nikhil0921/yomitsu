package eu.kanade.presentation.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.feed.model.FeedItem
import eu.kanade.domain.feed.model.FeedListing
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.PreferenceGroupCard
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.feed.FeedScreenModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

/**
 * Centralized feed management: enable/disable, reorder, and delete every
 * configured feed in one list. Mutations reuse FeedScreenModel →
 * FeedPreferences (shared source of truth; Feed tab updates live).
 */
class ManageFeedsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        // Management-only instance: skips per-feed network section fetches.
        val screenModel = rememberScreenModel { FeedScreenModel(loadSectionsOnStart = false) }
        val state by screenModel.state.collectAsState()

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
            ScrollbarLazyColumn(
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "feeds_group") {
                    // One management surface: rows flat inside, grouped per the
                    // app settings language.
                    PreferenceGroupCard(title = stringResource(MR.strings.feed_manage_reorder)) {
                        state.feeds.forEach { feed ->
                            ManageFeedRow(
                                feed = feed,
                                state = state,
                                onToggle = { screenModel.setFeedEnabled(feed, it) },
                                onMoveUp = { screenModel.moveFeedUp(feed) },
                                onMoveDown = { screenModel.moveFeedDown(feed) },
                                onDelete = { screenModel.deleteFeed(feed) },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ManageFeedRow(
        feed: FeedItem,
        state: FeedScreenModel.State,
        onToggle: (Boolean) -> Unit,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
        onDelete: () -> Unit,
    ) {
        val source = state.sources.firstOrNull { it.id == feed.sourceId }
        val feedName = source?.visualName ?: stringResource(MR.strings.feed_source_unavailable)
        ListItem(
            // Transparent so the group card's tonal surface reads as one surface
            // (ListItem's surface-colored default flattens the card in AMOLED).
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Text(feedName)
            },
            supportingContent = {
                Text(
                    stringResource(
                        if (feed.listing == FeedListing.LATEST) MR.strings.latest else MR.strings.popular,
                    ),
                )
            },
            trailingContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    IconButton(onClick = onMoveUp) {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(MR.strings.feed_move_up))
                    }
                    IconButton(onClick = onMoveDown) {
                        Icon(
                            Icons.Outlined.ArrowDownward,
                            contentDescription = stringResource(MR.strings.feed_move_down),
                        )
                    }
                    Switch(
                        checked = feed.enabled,
                        onCheckedChange = onToggle,
                        modifier = Modifier.semantics {
                            contentDescription = feedName
                        },
                    )
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(MR.strings.action_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )
    }
}
