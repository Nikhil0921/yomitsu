package eu.kanade.tachiyomi.ui.library.component

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.library.components.ContinueReadingButton
import eu.kanade.presentation.library.components.DownloadsBadge
import eu.kanade.presentation.library.components.LanguageBadge
import eu.kanade.presentation.library.components.UnreadBadge
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.util.selectedBackground
import eu.kanade.presentation.manga.components.MangaCover as CoverComponent

/**
 * Compact card-style row for Library list display mode, styled after the
 * Updates compact card header: small book-ratio cover, one-line title, and a
 * bodySmall metadata row of badges separated by dots.
 */
@Composable
fun LibraryListItem(
    libraryItem: LibraryItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickContinueReading: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val manga = libraryItem.libraryManga.manga
    Card(
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .padding(
                horizontal = MaterialTheme.padding.medium,
                vertical = MaterialTheme.padding.small,
            )
            .selectedBackground(isSelected)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { selected = isSelected },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverComponent.Book(
                modifier = Modifier.width(52.dp),
                data = MangaCover(
                    mangaId = manga.id,
                    sourceId = manga.source,
                    isMangaFavorite = manga.favorite,
                    url = manga.thumbnailUrl,
                    lastModified = manga.coverLastModified,
                ),
                contentDescription = manga.title,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = MaterialTheme.padding.small),
            ) {
                Text(
                    text = manga.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UnreadBadge(count = libraryItem.badges.unreadCount)
                    if (libraryItem.badges.unreadCount > 0 &&
                        (
                            libraryItem.badges.downloadCount > 0 ||
                                libraryItem.badges.isLocal ||
                                libraryItem.badges.sourceLanguage.isNotEmpty()
                            )
                    ) {
                        DotSeparatorText(
                            modifier = Modifier.padding(
                                horizontal = MaterialTheme.padding.extraSmall,
                            ),
                        )
                    }
                    DownloadsBadge(count = libraryItem.badges.downloadCount)
                    if (libraryItem.badges.downloadCount > 0 &&
                        (
                            libraryItem.badges.isLocal ||
                                libraryItem.badges.sourceLanguage.isNotEmpty()
                            )
                    ) {
                        DotSeparatorText(
                            modifier = Modifier.padding(
                                horizontal = MaterialTheme.padding.extraSmall,
                            ),
                        )
                    }
                    LanguageBadge(
                        isLocal = libraryItem.badges.isLocal,
                        sourceLanguage = libraryItem.badges.sourceLanguage,
                    )
                }
            }
            if (onClickContinueReading != null) {
                ContinueReadingButton(
                    iconSize = 16.dp,
                    onClick = onClickContinueReading,
                    modifier = Modifier
                        .padding(start = MaterialTheme.padding.small),
                )
            }
        }
    }
}
