package eu.kanade.tachiyomi.ui.recent.updates

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.updates.UpdateScreen
import eu.kanade.presentation.updates.UpdatesDeleteConfirmationDialog
import eu.kanade.presentation.updates.UpdatesFilterDialog
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.recent.RecentTabContent
import eu.kanade.tachiyomi.ui.updates.UpdatesScreenModel
import eu.kanade.tachiyomi.ui.updates.UpdatesSettingsScreenModel
import kotlinx.coroutines.flow.collectLatest
import mihon.feature.upcoming.UpcomingScreen
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

fun Screen.recentUpdatesTab(snackbarHostState: SnackbarHostState): RecentTabContent = RecentTabContent(
    titleRes = MR.strings.label_recent_updates,
) { contentPadding, _, _ ->
    recentUpdatesContent(snackbarHostState, contentPadding)
}

@Composable
private fun Screen.recentUpdatesContent(
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = rememberScreenModel { UpdatesScreenModel() }
    val settingsScreenModel = rememberScreenModel { UpdatesSettingsScreenModel() }
    val state by screenModel.state.collectAsState()

    UpdateScreen(
        state = state,
        snackbarHostState = screenModel.snackbarHostState,
        lastUpdated = screenModel.lastUpdated,
        onClickCover = { item -> navigator.push(MangaScreen(item.update.mangaId)) },
        onSelectAll = screenModel::toggleAllSelection,
        onInvertSelection = screenModel::invertSelection,
        onUpdateLibrary = screenModel::updateLibrary,
        onDownloadChapter = screenModel::downloadChapters,
        onMultiBookmarkClicked = screenModel::bookmarkUpdates,
        onMultiMarkAsReadClicked = screenModel::markUpdatesRead,
        onMultiDeleteClicked = screenModel::showConfirmDeleteChapters,
        onUpdateSelected = screenModel::toggleSelection,
        onOpenChapter = {
            val intent = ReaderActivity.newIntent(context, it.update.mangaId, it.update.chapterId)
            context.startActivity(intent)
        },
        onCalendarClicked = { navigator.push(UpcomingScreen()) },
        onFilterClicked = screenModel::showFilterDialog,
        onToggleGroupExpand = screenModel::toggleUpdatesGroup,
        hasActiveFilters = state.hasActiveFilters,
        contentPadding = contentPadding,
    )

    val onDismissDialog = { screenModel.setDialog(null) }
    when (val dialog = state.dialog) {
        is UpdatesScreenModel.Dialog.DeleteConfirmation -> {
            UpdatesDeleteConfirmationDialog(
                onDismissRequest = onDismissDialog,
                onConfirm = { screenModel.deleteChapters(dialog.toDelete) },
            )
        }
        is UpdatesScreenModel.Dialog.FilterSheet -> {
            UpdatesFilterDialog(
                onDismissRequest = onDismissDialog,
                screenModel = settingsScreenModel,
            )
        }
        null -> {}
    }

    LaunchedEffect(Unit) {
        screenModel.events.collectLatest { event ->
            when (event) {
                UpdatesScreenModel.Event.InternalError -> screenModel.snackbarHostState.showSnackbar(
                    context.stringResource(MR.strings.internal_error),
                )
                is UpdatesScreenModel.Event.LibraryUpdateTriggered -> {
                    val msg = if (event.started) {
                        MR.strings.updating_library
                    } else {
                        MR.strings.update_already_running
                    }
                    screenModel.snackbarHostState.showSnackbar(context.stringResource(msg))
                }
            }
        }
    }

    LaunchedEffect(state.selectionMode) {
        HomeScreen.showBottomNav(!state.selectionMode)
    }

    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) {
            (context as? MainActivity)?.ready = true
        }
    }
    DisposableEffect(Unit) {
        screenModel.resetNewUpdatesCount()

        onDispose {
            screenModel.resetNewUpdatesCount()
        }
    }
}
