package eu.kanade.tachiyomi.ui.recent.continuereading

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.ocr.OcrScanManager
import eu.kanade.tachiyomi.data.ocr.OcrScanQueueEntry
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

enum class ContinueSort(val labelRes: dev.icerock.moko.resources.StringResource) {
    LAST_READ(tachiyomi.i18n.MR.strings.action_sort_last_read),
    ALPHA(tachiyomi.i18n.MR.strings.action_sort_alpha),
}

class ContinueScreenModel(
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val ocrScanManager: OcrScanManager = Injekt.get(),
) : StateScreenModel<ContinueScreenModel.State>(State()) {

    @Immutable
    data class ContinueItem(
        val manga: LibraryManga,
        val nextChapter: Chapter?,
    )

    /**
     * Raw (unfiltered) items are retained; the displayed list is derived from
     * the current sort/filter values so toggles are always reversible and
     * never consume the source list.
     */
    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val rawItems: List<ContinueItem> = emptyList(),
        val sort: ContinueSort = ContinueSort.LAST_READ,
        val downloadedOnly: Boolean = false,
        val ocrScanningIds: Set<Long> = emptySet(),
        val isItemDownloaded: (ContinueItem) -> Boolean = { false },
    ) {
        val items: List<ContinueItem>
            get() = applyContinueFilters(rawItems, sort, downloadedOnly, isItemDownloaded)
    }

    init {
        // Wire the download check once the instance exists; stays stable
        // across state updates.
        mutableState.update { it.copy(isItemDownloaded = ::itemDownloaded) }
        screenModelScope.launch {
            getLibraryManga.subscribe()
                .collectLatest { mangas ->
                    val resumable = mangas
                        // Started reading (real reading history) and still has
                        // something left to read.
                        .filter { it.unreadCount > 0 && it.hasStarted }
                    val items = resumable.map { m ->
                        ContinueItem(m, getNextUnreadChapter(m.manga))
                    }
                    mutableState.update { it.copy(isLoading = false, rawItems = items) }
                }
        }
        screenModelScope.launch {
            ocrScanManager.queueState
                .collectLatest { queueState ->
                    val scanningIds = queueState.entries
                        .filter { it.state != OcrScanQueueEntry.State.ERROR }
                        .map(OcrScanQueueEntry::chapterId)
                        .toSet()
                    mutableState.update { it.copy(ocrScanningIds = scanningIds) }
                }
        }
    }

    fun setSort(sort: ContinueSort) {
        mutableState.update { it.copy(sort = sort) }
    }

    fun setDownloadedOnly(enabled: Boolean) {
        mutableState.update { it.copy(downloadedOnly = enabled) }
    }

    fun scanNextOcr(mangaId: Long) {
        val item = mutableState.value.rawItems.firstOrNull { it.manga.id == mangaId }
        val chapter = item?.nextChapter ?: return
        screenModelScope.launch {
            ocrScanManager.enqueue(listOf(chapter.id))
        }
    }

    private fun itemDownloaded(item: ContinueItem): Boolean {
        return item.nextChapter?.let { isDownloaded(it, item.manga.manga) } == true
    }

    private fun isDownloaded(chapter: Chapter, manga: Manga): Boolean {
        return downloadManager.isChapterDownloaded(
            chapterName = chapter.name,
            chapterScanlator = chapter.scanlator,
            chapterUrl = chapter.url,
            mangaTitle = manga.title,
            sourceId = manga.source,
        )
    }

    private suspend fun getNextUnreadChapter(manga: Manga): Chapter? {
        val chapters = getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
        return chapters.getNextUnread(manga, downloadManager)
    }
}

internal fun applyContinueFilters(
    items: List<ContinueScreenModel.ContinueItem>,
    sort: ContinueSort,
    downloadedOnly: Boolean,
    isDownloaded: (ContinueScreenModel.ContinueItem) -> Boolean,
): List<ContinueScreenModel.ContinueItem> {
    var result = items
    if (downloadedOnly) {
        result = result.filter(isDownloaded)
    }
    return when (sort) {
        ContinueSort.LAST_READ -> result.sortedByDescending { it.manga.lastRead }
        ContinueSort.ALPHA -> result.sortedBy { it.manga.manga.title.lowercase() }
    }
}
