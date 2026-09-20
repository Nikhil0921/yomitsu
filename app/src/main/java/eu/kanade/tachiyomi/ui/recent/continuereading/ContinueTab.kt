package eu.kanade.tachiyomi.ui.recent.continuereading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.recent.RecentTabContent
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

private val ContinueItemHeight = 96.dp

fun Screen.continueTab(): RecentTabContent = RecentTabContent(
    titleRes = MR.strings.recent_tab_continue,
) { contentPadding, _, _ ->
    ContinueContent(contentPadding)
}

@Composable
private fun Screen.ContinueContent(contentPadding: PaddingValues) {
    val screenModel = rememberScreenModel { ContinueScreenModel() }
    val state by screenModel.state.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current

    when {
        state.isLoading -> LoadingScreen()
        else -> {
            FastScrollLazyColumn(contentPadding = contentPadding) {
                // Compact page-level controls; content stays the hero. Rendered
                // even when the filtered list is empty so the filter/sort can
                // always be toggled back.
                item(key = "continue_controls") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = MaterialTheme.padding.medium,
                                vertical = MaterialTheme.padding.extraSmall,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        ContinueSort.entries.forEach { sort ->
                            FilterChip(
                                selected = state.sort == sort,
                                onClick = { screenModel.setSort(sort) },
                                label = { Text(stringResource(sort.labelRes)) },
                            )
                        }
                        FilterChip(
                            selected = state.downloadedOnly,
                            onClick = { screenModel.setDownloadedOnly(!state.downloadedOnly) },
                            label = { Text(stringResource(MR.strings.label_downloaded_only)) },
                        )
                    }
                }
                if (state.items.isEmpty()) {
                    item(key = "continue_empty") {
                        Box(
                            modifier = Modifier
                                .fillParentMaxHeight()
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            EmptyScreen(MR.strings.recent_continue_empty)
                        }
                    }
                } else {
                    items(state.items, key = { it.manga.manga.id }) { item ->
                        val ocrScanning = item.nextChapter?.id in state.ocrScanningIds
                        ContinueItemRow(
                            item = item.manga,
                            ocrScanning = ocrScanning,
                            onScanOcr = { screenModel.scanNextOcr(item.manga.manga.id) },
                            onClickCover = { navigator.push(MangaScreen(item.manga.manga.id)) },
                            onClickResume = {
                                val chapter = item.nextChapter
                                if (chapter != null) {
                                    context.startActivity(
                                        ReaderActivity.newIntent(context, chapter.mangaId, chapter.id),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) {
            (context as? MainActivity)?.ready = true
        }
    }
}

@Composable
private fun ContinueItemRow(
    item: LibraryManga,
    ocrScanning: Boolean,
    onScanOcr: () -> Unit,
    onClickCover: () -> Unit,
    onClickResume: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClickResume)
            .heightIn(min = ContinueItemHeight)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            modifier = Modifier.height(ContinueItemHeight),
            data = item.manga.asMangaCover(),
            contentDescription = item.manga.title,
            onClick = onClickCover,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium, end = MaterialTheme.padding.small),
        ) {
            Text(
                text = item.manga.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.unreadCount > 0) {
                Text(
                    text = pluralStringResource(
                        MR.plurals.continue_reading_unread,
                        count = item.unreadCount.toInt(),
                        item.unreadCount.toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onScanOcr, enabled = !ocrScanning) {
            if (ocrScanning) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.Outlined.DocumentScanner,
                    contentDescription = stringResource(MR.strings.action_scan_next_chapter_ocr),
                )
            }
        }
        IconButton(onClick = onClickResume) {
            Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = stringResource(MR.strings.action_resume),
            )
        }
    }
}
