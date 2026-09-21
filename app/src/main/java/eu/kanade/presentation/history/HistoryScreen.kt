package eu.kanade.presentation.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.history.components.HistoryItem
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.tachiyomi.ui.history.HistoryScreenModel
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import java.time.LocalDate

@Composable
fun HistoryScreen(
    state: HistoryScreenModel.State,
    snackbarHostState: SnackbarHostState,
    onSearchQueryChange: (String?) -> Unit,
    onClickCover: (mangaId: Long) -> Unit,
    onClickResume: (mangaId: Long, chapterId: Long) -> Unit,
    onClickFavorite: (mangaId: Long) -> Unit,
    onDialogChange: (HistoryScreenModel.Dialog?) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    state.list.let {
        if (it == null) {
            LoadingScreen()
        } else {
            Column {
                // Page-level controls; the Recent host provides the screen
                // title. Stays reachable even when the list is empty.
                HistoryControls(
                    searchQuery = state.searchQuery,
                    onSearchQueryChange = onSearchQueryChange,
                    onDialogChange = onDialogChange,
                )
                if (it.isEmpty()) {
                    val msg = if (!state.searchQuery.isNullOrEmpty()) {
                        MR.strings.no_results_found
                    } else {
                        MR.strings.information_no_recent_manga
                    }
                    EmptyScreen(stringRes = msg)
                } else {
                    HistoryScreenContent(
                        history = it,
                        contentPadding = contentPadding,
                        onClickCover = { history -> onClickCover(history.mangaId) },
                        onClickResume = { history -> onClickResume(history.mangaId, history.chapterId) },
                        onClickDelete = { item -> onDialogChange(HistoryScreenModel.Dialog.Delete(item)) },
                        onClickFavorite = { history -> onClickFavorite(history.mangaId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryControls(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    onDialogChange: (HistoryScreenModel.Dialog?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = searchQuery.orEmpty(),
            onValueChange = onSearchQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(MR.strings.action_search)) },
            trailingIcon = if (searchQuery != null) {
                {
                    IconButton(onClick = { onSearchQueryChange(null) }) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(MR.strings.action_reset),
                        )
                    }
                }
            } else {
                null
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        IconButton(onClick = { onDialogChange(HistoryScreenModel.Dialog.DeleteAll) }) {
            Icon(
                imageVector = Icons.Outlined.DeleteSweep,
                contentDescription = stringResource(MR.strings.pref_clear_history),
            )
        }
    }
}

@Composable
private fun HistoryScreenContent(
    history: List<HistoryUiModel>,
    contentPadding: PaddingValues,
    onClickCover: (HistoryWithRelations) -> Unit,
    onClickResume: (HistoryWithRelations) -> Unit,
    onClickDelete: (HistoryWithRelations) -> Unit,
    onClickFavorite: (HistoryWithRelations) -> Unit,
) {
    FastScrollLazyColumn(contentPadding = contentPadding) {
        items(
            items = history,
            key = { "history-${it.hashCode()}" },
            contentType = {
                when (it) {
                    is HistoryUiModel.Header -> "header"
                    is HistoryUiModel.Item -> "item"
                }
            },
        ) { item ->
            when (item) {
                is HistoryUiModel.Header -> {
                    ListGroupHeader(
                        modifier = Modifier.animateItemFastScroll(),
                        text = relativeDateText(item.date),
                    )
                }
                is HistoryUiModel.Item -> {
                    val value = item.item
                    HistoryItem(
                        modifier = Modifier.animateItemFastScroll(),
                        history = value,
                        onClickCover = { onClickCover(value) },
                        onClickResume = { onClickResume(value) },
                        onClickDelete = { onClickDelete(value) },
                        onClickFavorite = { onClickFavorite(value) },
                    )
                }
            }
        }
    }
}

sealed interface HistoryUiModel {
    data class Header(val date: LocalDate) : HistoryUiModel
    data class Item(val item: HistoryWithRelations) : HistoryUiModel
}

@PreviewLightDark
@Composable
internal fun HistoryScreenPreviews(
    @PreviewParameter(HistoryScreenModelStateProvider::class)
    historyState: HistoryScreenModel.State,
) {
    TachiyomiPreviewTheme {
        HistoryScreen(
            state = historyState,
            snackbarHostState = SnackbarHostState(),
            onSearchQueryChange = {},
            onClickCover = {},
            onClickResume = { _, _ -> run {} },
            onDialogChange = {},
            onClickFavorite = {},
        )
    }
}
