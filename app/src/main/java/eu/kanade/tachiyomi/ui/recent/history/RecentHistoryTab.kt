package eu.kanade.tachiyomi.ui.recent.history

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.history.HistoryScreen
import eu.kanade.presentation.history.components.HistoryDeleteAllDialog
import eu.kanade.presentation.history.components.HistoryDeleteDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.history.HistoryScreenModel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.recent.RecentTabContent
import kotlinx.coroutines.flow.collectLatest
import mihon.feature.migration.dialog.MigrateMangaDialog
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

fun Screen.recentHistoryTab(snackbarHostState: SnackbarHostState): RecentTabContent = RecentTabContent(
    titleRes = MR.strings.history,
) { contentPadding, _, _ ->
    recentHistoryContent(snackbarHostState, contentPadding)
}

@Composable
private fun Screen.recentHistoryContent(
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
) {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val screenModel = rememberScreenModel { HistoryScreenModel() }
    val state by screenModel.state.collectAsState()

    HistoryScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        contentPadding = contentPadding,
        onSearchQueryChange = screenModel::updateSearchQuery,
        onClickCover = { navigator.push(MangaScreen(it)) },
        onClickResume = screenModel::getNextChapterForManga,
        onDialogChange = screenModel::setDialog,
        onClickFavorite = screenModel::addFavorite,
    )

    val onDismissRequest = { screenModel.setDialog(null) }
    when (val dialog = state.dialog) {
        is HistoryScreenModel.Dialog.Delete -> {
            HistoryDeleteDialog(
                onDismissRequest = onDismissRequest,
                onDelete = { all ->
                    if (all) {
                        screenModel.removeAllFromHistory(dialog.history.mangaId)
                    } else {
                        screenModel.removeFromHistory(dialog.history)
                    }
                },
            )
        }
        is HistoryScreenModel.Dialog.DeleteAll -> {
            HistoryDeleteAllDialog(
                onDismissRequest = onDismissRequest,
                onDelete = screenModel::removeAllHistory,
            )
        }
        is HistoryScreenModel.Dialog.DuplicateManga -> {
            DuplicateMangaDialog(
                duplicates = dialog.duplicates,
                onDismissRequest = onDismissRequest,
                onConfirm = { screenModel.addFavorite(dialog.manga) },
                onOpenManga = { navigator.push(MangaScreen(it.id)) },
                onMigrate = { screenModel.showMigrateDialog(dialog.manga, it) },
            )
        }
        is HistoryScreenModel.Dialog.ChangeCategory -> {
            ChangeCategoryDialog(
                initialSelection = dialog.initialSelection,
                onDismissRequest = onDismissRequest,
                onEditCategories = { navigator.push(CategoryScreen()) },
                onConfirm = { include, _ ->
                    screenModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, include)
                },
            )
        }
        is HistoryScreenModel.Dialog.Migrate -> {
            MigrateMangaDialog(
                current = dialog.current,
                target = dialog.target,
                // Initiated from the context of [dialog.target] so we show [dialog.current].
                onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                onDismissRequest = onDismissRequest,
            )
        }
        null -> {}
    }

    LaunchedEffect(state.list) {
        if (state.list != null) {
            (context as? eu.kanade.tachiyomi.ui.main.MainActivity)?.ready = true
        }
    }

    LaunchedEffect(Unit) {
        screenModel.events.collectLatest { e ->
            when (e) {
                HistoryScreenModel.Event.InternalError ->
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                HistoryScreenModel.Event.HistoryCleared ->
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.clear_history_completed))
                is HistoryScreenModel.Event.OpenChapter -> openChapter(context, e.chapter, snackbarHostState)
            }
        }
    }
}

private suspend fun openChapter(
    context: android.content.Context,
    chapter: Chapter?,
    snackbarHostState: SnackbarHostState,
) {
    if (chapter != null) {
        val intent = ReaderActivity.newIntent(context, chapter.mangaId, chapter.id)
        context.startActivity(intent)
    } else {
        snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
    }
}
