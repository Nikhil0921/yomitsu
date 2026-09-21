package eu.kanade.tachiyomi.ui.updates

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.util.fastFilter
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.core.util.addOrRemove
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.updates.UpdatesUiModel
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.service.UpdatesPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.ZonedDateTime

class UpdatesScreenModel(
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val getUpdates: GetUpdates = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val updatesPreferences: UpdatesPreferences = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateScreenModel<UpdatesScreenModel.State>(State()) {

    private val _events: Channel<Event> = Channel(Int.MAX_VALUE)
    val events: Flow<Event> = _events.receiveAsFlow()

    val lastUpdated by libraryPreferences.lastUpdatedTimestamp.asState(screenModelScope)

    // First and last selected index in list
    private val selectedPositions: Array<Int> = arrayOf(-1, -1)
    private val selectedChapterIds: HashSet<Long> = HashSet()

    private var categoryMembershipCache: Map<Long, Boolean> = emptyMap()

    private suspend fun mangaInCategory(mangaId: Long, categoryId: Int): Boolean {
        if (categoryId == 0) return true
        categoryMembershipCache[mangaId]?.let { return it }
        val result = getCategories.await(mangaId).any { it.id == categoryId.toLong() }
        categoryMembershipCache = categoryMembershipCache + (mangaId to result)
        return result
    }

    init {
        screenModelScope.launchIO {
            // Set date limit for recent chapters
            val limit = ZonedDateTime.now().minusMonths(3).toInstant()

            combine(
                // needed for SQL filters (unread, started, bookmarked, etc)
                getUpdatesItemPreferenceFlow()
                    .distinctUntilChanged()
                    .flatMapLatest {
                        getUpdates.subscribe(
                            limit,
                            unread = it.filterUnread.toBooleanOrNull(),
                            started = it.filterStarted.toBooleanOrNull(),
                            bookmarked = it.filterBookmarked.toBooleanOrNull(),
                            hideExcludedScanlators = it.filterExcludedScanlators,
                        ).distinctUntilChanged()
                    },
                downloadCache.changes,
                downloadManager.queueState,
                // needed for Kotlin filters (downloaded)
                getUpdatesItemPreferenceFlow().distinctUntilChanged { old, new ->
                    old.filterDownloaded == new.filterDownloaded
                },
            ) { updates, _, _, itemPreferences ->
                updates
                    .toUpdateItems()
                    .applyFilters(itemPreferences)
            }
                .collectLatest { updateItems ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            items = updateItems,
                        )
                    }
                }
        }

        screenModelScope.launchIO {
            combine(
                libraryPreferences.displayRecentUpdatesCategoryFilter.changes(),
                libraryPreferences.recentUpdatesCategoryId.changes(),
            ) { enabled, categoryId ->
                Pair(enabled, categoryId)
            }
                .distinctUntilChanged()
                .collectLatest { (enabled, categoryId) ->
                    mutableState.update { state ->
                        state.copy(
                            categoryFilterEnabled = enabled,
                            categoryFilterId = categoryId,
                            categoryFilteredMangaIds = if (enabled && categoryId != 0) {
                                state.items
                                    .map { it.update.mangaId }
                                    .distinct()
                                    .filter { mangaId -> mangaInCategory(mangaId, categoryId) }
                                    .toSet()
                            } else {
                                emptySet()
                            },
                        )
                    }
                }
        }

        screenModelScope.launchIO {
            merge(downloadManager.statusFlow(), downloadManager.progressFlow())
                .catch { logcat(LogPriority.ERROR, it) }
                .collect(this@UpdatesScreenModel::updateDownloadState)
        }

        getUpdatesItemPreferenceFlow()
            .map { prefs ->
                listOf(
                    prefs.filterUnread,
                    prefs.filterDownloaded,
                    prefs.filterStarted,
                    prefs.filterBookmarked,
                )
                    .any { it != TriState.DISABLED }
            }
            .distinctUntilChanged()
            .onEach {
                mutableState.update { state ->
                    state.copy(hasActiveFilters = it)
                }
            }
            .launchIn(screenModelScope)
    }

    private fun List<UpdatesItem>.applyFilters(
        preferences: ItemPreferences,
    ): List<UpdatesItem> {
        val filterDownloaded = preferences.filterDownloaded

        val filterFnDownloaded: (UpdatesItem) -> Boolean = {
            applyFilter(filterDownloaded) {
                it.downloadStateProvider() == Download.State.DOWNLOADED
            }
        }

        return fastFilter {
            filterFnDownloaded(it)
        }
    }

    private fun List<UpdatesWithRelations>.toUpdateItems(): List<UpdatesItem> {
        return this
            .map { update ->
                val activeDownload = downloadManager.getQueuedDownloadOrNull(update.chapterId)
                val downloaded = downloadManager.isChapterDownloaded(
                    update.chapterName,
                    update.scanlator,
                    update.chapterUrl,
                    update.mangaTitle,
                    update.sourceId,
                )
                val downloadState = when {
                    activeDownload != null -> activeDownload.status
                    downloaded -> Download.State.DOWNLOADED
                    else -> Download.State.NOT_DOWNLOADED
                }
                UpdatesItem(
                    update = update,
                    downloadStateProvider = { downloadState },
                    downloadProgressProvider = { activeDownload?.progress ?: 0 },
                    selected = update.chapterId in selectedChapterIds,
                )
            }
    }

    fun updateLibrary(): Boolean {
        val started = LibraryUpdateJob.startNow(Injekt.get<Application>())
        screenModelScope.launch {
            _events.send(Event.LibraryUpdateTriggered(started))
        }
        return started
    }

    /**
     * Update status of chapters.
     *
     * @param download download object containing progress.
     */
    private fun updateDownloadState(download: Download) {
        mutableState.update { state ->
            val newItems = state.items.toMutableList().also { list ->
                val modifiedIndex = list.indexOfFirst { it.update.chapterId == download.chapter.id }
                if (modifiedIndex < 0) return@also

                val item = list[modifiedIndex]
                list[modifiedIndex] = item.copy(
                    downloadStateProvider = { download.status },
                    downloadProgressProvider = { download.progress },
                )
            }
            state.copy(items = newItems)
        }
    }

    fun downloadChapters(items: List<UpdatesItem>, action: ChapterDownloadAction) {
        if (items.isEmpty()) return
        screenModelScope.launch {
            when (action) {
                ChapterDownloadAction.START -> {
                    downloadChapters(items)
                    if (items.any { it.downloadStateProvider() == Download.State.ERROR }) {
                        downloadManager.startDownloads()
                    }
                }
                ChapterDownloadAction.START_NOW -> {
                    val chapterId = items.singleOrNull()?.update?.chapterId ?: return@launch
                    startDownloadingNow(chapterId)
                }
                ChapterDownloadAction.CANCEL -> {
                    val chapterId = items.singleOrNull()?.update?.chapterId ?: return@launch
                    cancelDownload(chapterId)
                }
                ChapterDownloadAction.DELETE -> {
                    deleteChapters(items)
                }
            }
            toggleAllSelection(false)
        }
    }

    private fun startDownloadingNow(chapterId: Long) {
        downloadManager.startDownloadNow(chapterId)
    }

    private fun cancelDownload(chapterId: Long) {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return
        downloadManager.cancelQueuedDownloads(listOf(activeDownload))
        updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
    }

    /**
     * Mark the selected updates list as read/unread.
     * @param updates the list of selected updates.
     * @param read whether to mark chapters as read or unread.
     */
    fun markUpdatesRead(updates: List<UpdatesItem>, read: Boolean) {
        screenModelScope.launchIO {
            setReadStatus.await(
                read = read,
                chapters = updates
                    .mapNotNull { getChapter.await(it.update.chapterId) }
                    .toTypedArray(),
            )
        }
        toggleAllSelection(false)
    }

    /**
     * Bookmarks the given list of chapters.
     * @param updates the list of chapters to bookmark.
     */
    fun bookmarkUpdates(updates: List<UpdatesItem>, bookmark: Boolean) {
        screenModelScope.launchIO {
            updates
                .filterNot { it.update.bookmark == bookmark }
                .map { ChapterUpdate(id = it.update.chapterId, bookmark = bookmark) }
                .let { updateChapter.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param updatesItem the list of chapters to download.
     */
    private fun downloadChapters(updatesItem: List<UpdatesItem>) {
        screenModelScope.launchNonCancellable {
            val groupedUpdates = updatesItem.groupBy { it.update.mangaId }.values
            for (updates in groupedUpdates) {
                val mangaId = updates.first().update.mangaId
                val manga = getManga.await(mangaId) ?: continue
                // Don't download if source isn't available
                sourceManager.get(manga.source) ?: continue
                val chapters = updates.mapNotNull { getChapter.await(it.update.chapterId) }
                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    /**
     * Delete selected chapters
     *
     * @param updatesItem list of chapters
     */
    fun deleteChapters(updatesItem: List<UpdatesItem>) {
        screenModelScope.launchNonCancellable {
            updatesItem
                .groupBy { it.update.mangaId }
                .entries
                .forEach { (mangaId, updates) ->
                    val manga = getManga.await(mangaId) ?: return@forEach
                    val source = sourceManager.get(manga.source) ?: return@forEach
                    val chapters = updates.mapNotNull { getChapter.await(it.update.chapterId) }
                    downloadManager.deleteChapters(chapters, manga, source)
                }
        }
        toggleAllSelection(false)
    }

    fun showConfirmDeleteChapters(updatesItem: List<UpdatesItem>) {
        setDialog(Dialog.DeleteConfirmation(updatesItem))
    }

    fun toggleSelection(
        item: UpdatesItem,
        selected: Boolean,
        fromLongPress: Boolean = false,
    ) {
        mutableState.update { state ->
            val newItems = state.items.toMutableList().apply {
                val selectedIndex = indexOfFirst { it.update.chapterId == item.update.chapterId }
                if (selectedIndex < 0) return@apply

                val selectedItem = get(selectedIndex)
                if (selectedItem.selected == selected) return@apply

                val firstSelection = none { it.selected }
                set(selectedIndex, selectedItem.copy(selected = selected))
                selectedChapterIds.addOrRemove(item.update.chapterId, selected)

                if (selected && fromLongPress) {
                    if (firstSelection) {
                        selectedPositions[0] = selectedIndex
                        selectedPositions[1] = selectedIndex
                    } else {
                        // Try to select the items in-between when possible
                        val range: IntRange
                        if (selectedIndex < selectedPositions[0]) {
                            range = selectedIndex + 1..<selectedPositions[0]
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            range = (selectedPositions[1] + 1)..<selectedIndex
                            selectedPositions[1] = selectedIndex
                        } else {
                            // Just select itself
                            range = IntRange.EMPTY
                        }

                        range.forEach {
                            val inbetweenItem = get(it)
                            if (!inbetweenItem.selected) {
                                selectedChapterIds.add(inbetweenItem.update.chapterId)
                                set(it, inbetweenItem.copy(selected = true))
                            }
                        }
                    }
                } else if (!fromLongPress) {
                    if (!selected) {
                        if (selectedIndex == selectedPositions[0]) {
                            selectedPositions[0] = indexOfFirst { it.selected }
                        } else if (selectedIndex == selectedPositions[1]) {
                            selectedPositions[1] = indexOfLast { it.selected }
                        }
                    } else {
                        if (selectedIndex < selectedPositions[0]) {
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            selectedPositions[1] = selectedIndex
                        }
                    }
                }
            }
            state.copy(items = newItems)
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        mutableState.update { state ->
            val newItems = state.items.map {
                selectedChapterIds.addOrRemove(it.update.chapterId, selected)
                it.copy(selected = selected)
            }
            state.copy(items = newItems)
        }

        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun invertSelection() {
        mutableState.update { state ->
            val newItems = state.items.map {
                selectedChapterIds.addOrRemove(it.update.chapterId, !it.selected)
                it.copy(selected = !it.selected)
            }
            state.copy(items = newItems)
        }
        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun resetNewUpdatesCount() {
        libraryPreferences.newUpdatesCount.set(0)
    }

    private fun getUpdatesItemPreferenceFlow(): Flow<ItemPreferences> {
        return combine(
            updatesPreferences.filterDownloaded.changes(),
            updatesPreferences.filterUnread.changes(),
            updatesPreferences.filterStarted.changes(),
            updatesPreferences.filterBookmarked.changes(),
            updatesPreferences.filterExcludedScanlators.changes(),
        ) { downloaded, unread, started, bookmarked, excludedScanlators ->
            ItemPreferences(
                filterDownloaded = downloaded,
                filterUnread = unread,
                filterStarted = started,
                filterBookmarked = bookmarked,
                filterExcludedScanlators = excludedScanlators,
            )
        }
    }

    fun showFilterDialog() {
        mutableState.update { it.copy(dialog = Dialog.FilterSheet) }
    }

    @Immutable
    private data class ItemPreferences(
        val filterDownloaded: TriState,
        val filterUnread: TriState,
        val filterStarted: TriState,
        val filterBookmarked: TriState,
        val filterExcludedScanlators: Boolean,
    )

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val hasActiveFilters: Boolean = false,
        val items: List<UpdatesItem> = listOf(),
        val expandedGroupIds: Set<Long> = emptySet(),
        val dialog: Dialog? = null,
        val categoryFilterEnabled: Boolean = false,
        val categoryFilterId: Int = 0,
        val categoryFilteredMangaIds: Set<Long> = emptySet(),
    ) {
        val selected = items.filter { it.selected }
        val selectionMode = selected.isNotEmpty()

        fun getUiModel(): List<UpdatesUiModel> {
            return items
                .filter { item ->
                    !categoryFilterEnabled ||
                        categoryFilterId == 0 ||
                        item.update.mangaId in categoryFilteredMangaIds
                }
                .map { UpdatesUiModel.Item(it) }
                .insertSeparators { before, after ->
                    val beforeDate = before?.item?.update?.dateFetch?.toLocalDate()
                    val afterDate = after?.item?.update?.dateFetch?.toLocalDate()
                    when {
                        beforeDate != afterDate && afterDate != null -> UpdatesUiModel.Header(afterDate)
                        // Return null to avoid adding a separator between two items.
                        else -> null
                    }
                }
                .let { groupConsecutiveUpdates(it, expandedGroupIds) }
        }
    }

    fun toggleUpdatesGroup(mangaId: Long) {
        mutableState.update { state ->
            val expanded = if (mangaId in state.expandedGroupIds) {
                state.expandedGroupIds - mangaId
            } else {
                state.expandedGroupIds + mangaId
            }
            state.copy(expandedGroupIds = expanded)
        }
    }

    sealed interface Dialog {
        data class DeleteConfirmation(val toDelete: List<UpdatesItem>) : Dialog
        data object FilterSheet : Dialog
    }

    sealed interface Event {
        data object InternalError : Event
        data class LibraryUpdateTriggered(val started: Boolean) : Event
    }
}

private fun TriState.toBooleanOrNull(): Boolean? {
    return when (this) {
        TriState.DISABLED -> null
        TriState.ENABLED_IS -> true
        TriState.ENABLED_NOT -> false
    }
}

/**
 * Folds runs of 2+ consecutive same-manga items into a collapsible group;
 * date headers always break a run so a group never spans days. Single
 * updates and different-manga neighbors stay flat rows.
 */
internal fun groupConsecutiveUpdates(
    uiModels: List<UpdatesUiModel>,
    expandedGroupIds: Set<Long>,
): List<UpdatesUiModel> {
    val result = ArrayList<UpdatesUiModel>(uiModels.size)
    var run = mutableListOf<UpdatesItem>()

    fun flushRun() {
        if (run.size >= 2) {
            val mangaId = run.first().update.mangaId
            result.add(
                UpdatesUiModel.Group(
                    mangaId = mangaId,
                    items = run.toList(),
                    expanded = mangaId in expandedGroupIds,
                ),
            )
        } else {
            run.forEach { result.add(UpdatesUiModel.Item(it)) }
        }
        run = mutableListOf()
    }

    uiModels.forEach { model ->
        when (model) {
            is UpdatesUiModel.Item -> {
                if (run.isNotEmpty() && run.last().update.mangaId != model.item.update.mangaId) {
                    flushRun()
                }
                run.add(model.item)
            }
            is UpdatesUiModel.Header -> {
                flushRun()
                result.add(model)
            }
            is UpdatesUiModel.Group -> {
                flushRun()
                result.add(model)
            }
        }
    }
    flushRun()

    return result
}

@Immutable
data class UpdatesItem(
    val update: UpdatesWithRelations,
    val downloadStateProvider: () -> Download.State,
    val downloadProgressProvider: () -> Int,
    val selected: Boolean = false,
)
