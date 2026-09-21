package eu.kanade.presentation.updates

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import eu.kanade.tachiyomi.ui.updates.UpdatesScreenModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds

@Composable
fun UpdateScreen(
    state: UpdatesScreenModel.State,
    snackbarHostState: SnackbarHostState,
    lastUpdated: Long,
    onClickCover: (UpdatesItem) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
    onCalendarClicked: () -> Unit,
    onUpdateLibrary: () -> Boolean,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
    onMultiBookmarkClicked: (List<UpdatesItem>, bookmark: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<UpdatesItem>, read: Boolean) -> Unit,
    onMultiDeleteClicked: (List<UpdatesItem>) -> Unit,
    onUpdateSelected: (UpdatesItem, Boolean, Boolean) -> Unit,
    onOpenChapter: (UpdatesItem) -> Unit,
    onFilterClicked: () -> Unit,
    onToggleGroupExpand: (Long) -> Unit,
    hasActiveFilters: Boolean,
    contentPadding: PaddingValues = PaddingValues(),
) {
    BackHandler(enabled = state.selectionMode) {
        onSelectAll(false)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> LoadingScreen()
            // Filtered-to-empty still shows the controls so the user can
            // reach the filter sheet and unfilter (no dead-end empty state).
            state.items.isEmpty() -> Column {
                UpdatesControls(
                    selectionMode = state.selectionMode,
                    selectedCount = state.selected.size,
                    hasActiveFilters = hasActiveFilters,
                    onFilterClicked = onFilterClicked,
                    onCalendarClicked = onCalendarClicked,
                    onUpdateLibrary = onUpdateLibrary,
                    onSelectAll = { onSelectAll(true) },
                    onInvertSelection = onInvertSelection,
                    onCancelActionMode = { onSelectAll(false) },
                )
                EmptyScreen(
                    stringRes = MR.strings.information_no_recent,
                    modifier = Modifier.weight(1f),
                )
            }
            else -> {
                val scope = rememberCoroutineScope()
                var isRefreshing by remember { mutableStateOf(false) }

                PullRefresh(
                    refreshing = isRefreshing,
                    onRefresh = {
                        val started = onUpdateLibrary()
                        if (!started) return@PullRefresh
                        scope.launch {
                            // Fake refresh status but hide it after a second as it's a long running task
                            isRefreshing = true
                            delay(1.seconds)
                            isRefreshing = false
                        }
                    },
                    enabled = !state.selectionMode,
                ) {
                    FastScrollLazyColumn(contentPadding = contentPadding) {
                        // Page-level controls; the Recent host provides the
                        // screen title.
                        item(key = "updates_controls") {
                            UpdatesControls(
                                selectionMode = state.selectionMode,
                                selectedCount = state.selected.size,
                                hasActiveFilters = hasActiveFilters,
                                onFilterClicked = onFilterClicked,
                                onCalendarClicked = onCalendarClicked,
                                onUpdateLibrary = onUpdateLibrary,
                                onSelectAll = { onSelectAll(true) },
                                onInvertSelection = onInvertSelection,
                                onCancelActionMode = { onSelectAll(false) },
                            )
                        }
                        updatesLastUpdatedItem(lastUpdated)

                        updatesUiItems(
                            uiModels = state.getUiModel(),
                            selectionMode = state.selectionMode,
                            onUpdateSelected = onUpdateSelected,
                            onClickCover = onClickCover,
                            onClickUpdate = onOpenChapter,
                            onDownloadChapter = onDownloadChapter,
                            onToggleGroupExpand = onToggleGroupExpand,
                        )
                    }
                }
            }
        }

        UpdatesBottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            selected = state.selected,
            onDownloadChapter = onDownloadChapter,
            onMultiBookmarkClicked = onMultiBookmarkClicked,
            onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
            onMultiDeleteClicked = onMultiDeleteClicked,
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding()),
        )
    }
}

@Composable
private fun UpdatesControls(
    selectionMode: Boolean,
    selectedCount: Int,
    hasActiveFilters: Boolean,
    onFilterClicked: () -> Unit,
    onCalendarClicked: () -> Unit,
    onUpdateLibrary: () -> Boolean,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onCancelActionMode: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            // Action mode stays as a control row; the counter communicates
            // the selection size.
            Text(
                text = selectedCount.toString(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSelectAll) {
                Icon(
                    imageVector = Icons.Outlined.SelectAll,
                    contentDescription = stringResource(MR.strings.action_select_all),
                )
            }
            IconButton(onClick = onInvertSelection) {
                Icon(
                    imageVector = Icons.Outlined.FlipToBack,
                    contentDescription = stringResource(MR.strings.action_select_inverse),
                )
            }
            IconButton(onClick = onCancelActionMode) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(MR.strings.action_cancel),
                )
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onFilterClicked) {
                Icon(
                    imageVector = Icons.Outlined.FilterList,
                    contentDescription = stringResource(MR.strings.action_filter),
                    tint = if (hasActiveFilters) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        LocalContentColor.current
                    },
                )
            }
            IconButton(onClick = onCalendarClicked) {
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = stringResource(MR.strings.action_view_upcoming),
                )
            }
            IconButton(onClick = { onUpdateLibrary() }) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(MR.strings.action_update_library),
                )
            }
        }
    }
}

@Composable
private fun UpdatesBottomBar(
    modifier: Modifier = Modifier,
    selected: List<UpdatesItem>,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
    onMultiBookmarkClicked: (List<UpdatesItem>, bookmark: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<UpdatesItem>, read: Boolean) -> Unit,
    onMultiDeleteClicked: (List<UpdatesItem>) -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        MangaBottomActionMenu(
            visible = selected.isNotEmpty(),
            modifier = Modifier.align(Alignment.TopCenter),
            onBookmarkClicked = {
                onMultiBookmarkClicked.invoke(selected, true)
            }.takeIf { selected.fastAny { !it.update.bookmark } },
            onRemoveBookmarkClicked = {
                onMultiBookmarkClicked.invoke(selected, false)
            }.takeIf { selected.fastAll { it.update.bookmark } },
            onMarkAsReadClicked = {
                onMultiMarkAsReadClicked(selected, true)
            }.takeIf { selected.fastAny { !it.update.read } },
            onMarkAsUnreadClicked = {
                onMultiMarkAsReadClicked(selected, false)
            }.takeIf { selected.fastAny { it.update.read || it.update.lastPageRead > 0L } },
            onDownloadClicked = {
                onDownloadChapter(selected, ChapterDownloadAction.START)
            }.takeIf {
                selected.fastAny { it.downloadStateProvider() != Download.State.DOWNLOADED }
            },
            onDeleteClicked = {
                onMultiDeleteClicked(selected)
            }.takeIf { selected.fastAny { it.downloadStateProvider() == Download.State.DOWNLOADED } },
        )
    }
}

sealed interface UpdatesUiModel {
    data class Header(val date: LocalDate) : UpdatesUiModel
    data class Item(val item: UpdatesItem) : UpdatesUiModel
    data class Group(
        val mangaId: Long,
        val items: List<UpdatesItem>,
        val expanded: Boolean,
    ) : UpdatesUiModel
}
