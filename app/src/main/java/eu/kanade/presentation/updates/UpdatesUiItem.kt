package eu.kanade.presentation.updates

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadIndicator
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground

internal fun LazyListScope.updatesLastUpdatedItem(
    lastUpdated: Long,
) {
    item(key = "updates-lastUpdated") {
        Box(
            modifier = Modifier
                .animateItem(fadeInSpec = null, fadeOutSpec = null)
                .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        ) {
            Text(
                text = stringResource(MR.strings.updates_last_update_info, relativeTimeSpanString(lastUpdated)),
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

internal fun LazyListScope.updatesUiItems(
    uiModels: List<UpdatesUiModel>,
    selectionMode: Boolean,
    onUpdateSelected: (UpdatesItem, Boolean, Boolean) -> Unit,
    onClickCover: (UpdatesItem) -> Unit,
    onClickUpdate: (UpdatesItem) -> Unit,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
    onToggleGroupExpand: (Long) -> Unit,
) {
    items(
        items = uiModels,
        contentType = {
            when (it) {
                is UpdatesUiModel.Header -> "header"
                is UpdatesUiModel.Item -> "item"
                is UpdatesUiModel.Group -> "group"
            }
        },
        key = {
            when (it) {
                is UpdatesUiModel.Header -> "updatesHeader-${it.hashCode()}"
                is UpdatesUiModel.Item -> "updates-${it.item.update.mangaId}-${it.item.update.chapterId}"
                is UpdatesUiModel.Group ->
                    "updatesGroup-${it.mangaId}-${it.items.first().update.chapterId}"
            }
        },
    ) { item ->
        when (item) {
            is UpdatesUiModel.Header -> {
                ListGroupHeader(
                    modifier = Modifier.animateItemFastScroll(),
                    text = relativeDateText(item.date),
                )
            }
            is UpdatesUiModel.Item -> {
                val updatesItem = item.item
                UpdatesSingleChapterCard(
                    modifier = Modifier.animateItemFastScroll(),
                    updatesItem = updatesItem,
                    selectionMode = selectionMode,
                    onUpdateSelected = onUpdateSelected,
                    onClickCover = onClickCover,
                    onClickUpdate = onClickUpdate,
                    onDownloadChapter = onDownloadChapter,
                )
            }
            is UpdatesUiModel.Group -> {
                UpdatesMangaGroupItem(
                    modifier = Modifier.animateItemFastScroll(),
                    group = item,
                    selectionMode = selectionMode,
                    onUpdateSelected = onUpdateSelected,
                    onClickCover = onClickCover,
                    onClickUpdate = onClickUpdate,
                    onDownloadChapter = onDownloadChapter,
                    onToggleExpand = onToggleGroupExpand,
                )
            }
        }
    }
}

@Composable
private fun UpdatesItem.readProgressLabel(): String? {
    val update = this.update
    return update.lastPageRead
        .takeIf { !update.read && it > 0L }
        ?.let { stringResource(MR.strings.chapter_progress, it + 1) }
}

/**
 * Compact per-manga group card: one small cover, title, latest update, and a
 * trailing expand control. Chapter rows stay cover-less/title-less inside the
 * same card.
 */
@Composable
private fun UpdatesMangaGroupItem(
    group: UpdatesUiModel.Group,
    selectionMode: Boolean,
    onUpdateSelected: (UpdatesItem, Boolean, Boolean) -> Unit,
    onClickCover: (UpdatesItem) -> Unit,
    onClickUpdate: (UpdatesItem) -> Unit,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
    onToggleExpand: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = group.items.first()
    Card(
        modifier = modifier.padding(
            horizontal = MaterialTheme.padding.medium,
            vertical = MaterialTheme.padding.small,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        UpdatesCompactCardHeader(
            update = first.update,
            hasUnread = group.items.fastAny { !it.update.read },
            readProgress = null,
            onClickCover = { onClickCover(first) }.takeIf { !selectionMode },
            trailing = {
                IconButton(onClick = { onToggleExpand(group.mangaId) }) {
                    Icon(
                        imageVector = if (group.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = stringResource(
                            if (group.expanded) MR.strings.action_collapse else MR.strings.action_expand,
                        ),
                    )
                }
            },
        )
        AnimatedVisibility(
            visible = group.expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier.padding(bottom = MaterialTheme.padding.small),
            ) {
                group.items.forEach { updatesItem ->
                    UpdatesUiItem(
                        update = updatesItem.update,
                        selected = updatesItem.selected,
                        readProgress = updatesItem.readProgressLabel(),
                        showCover = false,
                        showMangaTitle = false,
                        onLongClick = {
                            onUpdateSelected(updatesItem, !updatesItem.selected, true)
                        },
                        onClick = {
                            when {
                                selectionMode -> onUpdateSelected(updatesItem, !updatesItem.selected, false)
                                else -> onClickUpdate(updatesItem)
                            }
                        },
                        onClickCover = null,
                        onDownloadChapter = { action: ChapterDownloadAction ->
                            onDownloadChapter(listOf(updatesItem), action)
                        }.takeIf { !selectionMode },
                        downloadStateProvider = updatesItem.downloadStateProvider,
                        downloadProgressProvider = updatesItem.downloadProgressProvider,
                    )
                }
            }
        }
    }
}

/**
 * Single-chapter update rendered in the same compact card as a group header:
 * small cover, title, chapter/time metadata, existing download action
 * trailing. No expand arrow — one chapter has nothing to expand.
 */
@Composable
private fun UpdatesSingleChapterCard(
    updatesItem: UpdatesItem,
    selectionMode: Boolean,
    onUpdateSelected: (UpdatesItem, Boolean, Boolean) -> Unit,
    onClickCover: (UpdatesItem) -> Unit,
    onClickUpdate: (UpdatesItem) -> Unit,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val update = updatesItem.update
    Card(
        modifier = modifier
            .padding(
                horizontal = MaterialTheme.padding.medium,
                vertical = MaterialTheme.padding.small,
            )
            .selectedBackground(updatesItem.selected)
            .combinedClickable(
                onClick = {
                    when {
                        selectionMode -> onUpdateSelected(updatesItem, !updatesItem.selected, false)
                        else -> onClickUpdate(updatesItem)
                    }
                },
                onLongClick = {
                    onUpdateSelected(updatesItem, !updatesItem.selected, true)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
            .semantics { selected = updatesItem.selected },
        shape = MaterialTheme.shapes.large,
    ) {
        UpdatesCompactCardHeader(
            update = update,
            hasUnread = !update.read,
            readProgress = updatesItem.readProgressLabel(),
            onClickCover = { onClickCover(updatesItem) }.takeIf { !selectionMode },
            trailing = {
                ChapterDownloadIndicator(
                    enabled = !selectionMode,
                    modifier = Modifier.padding(start = 4.dp),
                    downloadStateProvider = updatesItem.downloadStateProvider,
                    downloadProgressProvider = updatesItem.downloadProgressProvider,
                    onClick = { onDownloadChapter(listOf(updatesItem), it) },
                )
            },
        )
    }
}

/**
 * Shared compact card header: small cover, title, chapter/time metadata, and
 * a trailing action. Groups trail the expand control; single chapters trail
 * the download action.
 */
@Composable
private fun UpdatesCompactCardHeader(
    update: UpdatesWithRelations,
    hasUnread: Boolean,
    readProgress: String?,
    onClickCover: (() -> Unit)?,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = MaterialTheme.padding.small,
                vertical = MaterialTheme.padding.small,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            modifier = Modifier.width(52.dp),
            data = update.coverData,
            contentDescription = update.mangaTitle,
            onClick = onClickCover,
        )
        Column(
            modifier = Modifier
                .padding(start = MaterialTheme.padding.small)
                .weight(1f),
        ) {
            Text(
                text = update.mangaTitle,
                maxLines = 1,
                style = MaterialTheme.typography.titleSmall,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasUnread) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = stringResource(MR.strings.unread),
                        modifier = Modifier
                            .height(8.dp)
                            .padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = update.chapterName,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(weight = 1f, fill = false),
                )
                DotSeparatorText()
                Text(
                    text = relativeTimeSpanString(update.dateFetch),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis,
                )
                if (readProgress != null) {
                    DotSeparatorText()
                    Text(
                        text = readProgress,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = DISABLED_ALPHA),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        trailing()
    }
}

@Composable
private fun UpdatesUiItem(
    update: UpdatesWithRelations,
    selected: Boolean,
    readProgress: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickCover: (() -> Unit)?,
    onDownloadChapter: ((ChapterDownloadAction) -> Unit)?,
    // Download Indicator
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    modifier: Modifier = Modifier,
    showCover: Boolean = true,
    showMangaTitle: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val textAlpha = if (update.read) DISABLED_ALPHA else 1f

    Row(
        modifier = modifier
            .selectedBackground(selected)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    onLongClick()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
            .semantics { this.selected = selected }
            .heightIn(min = 56.dp)
            .padding(horizontal = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCover) {
            MangaCover.Square(
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .heightIn(min = 90.dp, max = 135.dp),
                data = update.coverData,
                contentDescription = update.mangaTitle,
                onClick = onClickCover,
            )
        }

        Column(
            modifier = Modifier
                .padding(horizontal = MaterialTheme.padding.medium)
                .weight(1f),
        ) {
            if (showMangaTitle) {
                Text(
                    text = update.mangaTitle,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = textAlpha),
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                var textHeight by remember { mutableIntStateOf(0) }
                if (!update.read) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = stringResource(MR.strings.unread),
                        modifier = Modifier
                            .height(8.dp)
                            .padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (update.bookmark) {
                    Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = stringResource(MR.strings.action_filter_bookmarked),
                        modifier = Modifier
                            .sizeIn(maxHeight = with(LocalDensity.current) { textHeight.toDp() - 2.dp }),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                }
                Text(
                    text = update.chapterName,
                    maxLines = 1,
                    style = if (showMangaTitle) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = LocalContentColor.current.copy(alpha = textAlpha),
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { textHeight = it.size.height },
                    modifier = Modifier
                        .weight(weight = 1f, fill = false),
                )
                if (readProgress != null) {
                    DotSeparatorText()
                    Text(
                        text = readProgress,
                        maxLines = 1,
                        color = LocalContentColor.current.copy(alpha = DISABLED_ALPHA),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        ChapterDownloadIndicator(
            enabled = onDownloadChapter != null,
            modifier = Modifier.padding(start = 4.dp),
            downloadStateProvider = downloadStateProvider,
            downloadProgressProvider = downloadProgressProvider,
            onClick = { onDownloadChapter?.invoke(it) },
        )
    }
}
