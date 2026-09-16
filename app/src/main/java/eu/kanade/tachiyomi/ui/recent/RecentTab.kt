package eu.kanade.tachiyomi.ui.recent

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.recent.continuereading.continueTab
import eu.kanade.tachiyomi.ui.recent.history.recentHistoryTab
import eu.kanade.tachiyomi.ui.recent.updates.recentUpdatesTab
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource

data object RecentTab : Tab {

    // Reselect = resume the last-read manga (inherited from the old History tab).
    private val resumeLastChapterReadEvent = Channel<Unit>()

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_history_enter)
            return TabOptions(
                index = 1u,
                title = stringResource(MR.strings.label_recent),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        resumeLastChapterReadEvent.send(Unit)
    }

    @Composable
    override fun Content() {
        val snackbarHostState = remember { SnackbarHostState() }
        val resumeHostState = remember { SnackbarHostState() }
        val tabs = listOf(
            continueTab(),
            recentHistoryTab(snackbarHostState),
            recentUpdatesTab(snackbarHostState),
        )
        val state = rememberPagerState { tabs.size }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        // One screen-level title; the Scaffold's AppBar handles the status-bar
        // inset so the tab row starts below the safe area. Enter-always keeps
        // the tab row pinned while the title collapses on scroll.
        val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
        Scaffold(
            topBar = { _ ->
                AppBar(
                    title = stringResource(MR.strings.label_recent),
                    scrollBehavior = topBarScrollBehavior,
                )
            },
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState)
                SnackbarHost(hostState = resumeHostState)
            },
        ) { padding ->
            // Floating pill: no bottom clip here — the pages get the pill
            // clearance as list content padding so content scrolls under the
            // translucent pill and rests clear above it, exactly like Library,
            // Feed and Browse.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
            ) {
                PrimaryTabRow(selectedTabIndex = state.currentPage, modifier = Modifier.zIndex(1f)) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = state.currentPage == index,
                            onClick = { scope.launch { state.animateScrollToPage(index) } },
                            text = {
                                TabText(
                                    text = stringResource(tab.titleRes),
                                )
                            },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                HorizontalPager(
                    modifier = Modifier.fillMaxSize(),
                    state = state,
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    tabs[page].content(
                        PaddingValues(bottom = padding.calculateBottomPadding()),
                        snackbarHostState,
                        state,
                    )
                }
            }
        }

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }

        // Reselect: open/resume the last-read manga via the existing
        // HistoryScreenModel resume mechanism.
        LaunchedEffect(Unit) {
            resumeLastChapterReadEvent.receiveAsFlow().collectLatest {
                resumeLastReadChapter(context, resumeHostState)
            }
        }
    }
}
