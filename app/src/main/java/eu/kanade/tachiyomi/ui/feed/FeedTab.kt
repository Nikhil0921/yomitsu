package eu.kanade.tachiyomi.ui.feed

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.feed.FeedScreen
import eu.kanade.presentation.feed.ManageFeedsScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object FeedTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_feed_enter)
            return TabOptions(
                index = 2u,
                title = stringResource(MR.strings.label_feed),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    // Reselecting the already-active Feed tab is a deliberate manage action.
    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(ManageFeedsScreen())
    }

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { FeedScreenModel() }
        val state by screenModel.state.collectAsState()
        val gridColumns by screenModel.gridColumns
        val compactGrid by screenModel.compactGrid
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        FeedScreen(
            state = state,
            onMangaClick = { mangaId -> navigator.push(MangaScreen(mangaId, true)) },
            onAddFeedClick = { screenModel.showAddDialog() },
            onManageFeedsClick = { navigator.push(ManageFeedsScreen()) },
            onAddFeedConfirm = { sourceId, listings -> listings.forEach { screenModel.addFeed(sourceId, it) } },
            onSelectSource = { screenModel.selectSource(it) },
            onSelectListing = { screenModel.selectListing(it) },
            onToggleGenre = { screenModel.toggleGenreChip(it) },
            onDismissAddDialog = { screenModel.dismissAddDialog() },
            onLoadMore = { screenModel.loadMore(it) },
            onRetry = { screenModel.retry(it) },
            gridColumns = gridColumns,
            compactGrid = compactGrid,
            onChangeGridColumns = { screenModel.setGridColumns(it) },
            onToggleCompactGrid = { screenModel.setCompactGrid(it) },
            onToggleSourceSelector = { screenModel.toggleSourceSelector(it) },
            onToggleListingSelector = { screenModel.toggleListingSelector(it) },
            onSelectDefaultListing = { screenModel.setDefaultListing(it) },
        )

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }
    }
}
