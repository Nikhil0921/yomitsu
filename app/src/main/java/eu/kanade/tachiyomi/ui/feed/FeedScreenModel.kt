package eu.kanade.tachiyomi.ui.feed

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.domain.feed.model.FeedItem
import eu.kanade.domain.feed.model.FeedListing
import eu.kanade.domain.feed.service.FeedPreferences
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.browse.source.browse.genreToggles
import eu.kanade.tachiyomi.ui.browse.source.browse.isGenreSelected
import eu.kanade.tachiyomi.ui.browse.source.browse.toggleGenreSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

sealed interface FeedSectionResult {
    data object Loading : FeedSectionResult
    data class Success(
        val mangas: List<Manga>,
        val hasMore: Boolean,
        val isLoadingMore: Boolean = false,
        val error: String? = null,
    ) : FeedSectionResult
    data class Error(val message: String?) : FeedSectionResult
}

private data class DisplayPrefs(
    val showSource: Boolean,
    val showListing: Boolean,
    val defaultListing: FeedListing?,
    val selectedSource: Long?,
)

class FeedScreenModel(
    private val loadSectionsOnStart: Boolean = true,
    private val sourceManager: SourceManager = Injekt.get(),
    private val getEnabledSources: GetEnabledSources = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val feedPreferences: FeedPreferences = Injekt.get(),
) : StateScreenModel<FeedScreenModel.State>(State()) {

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val feeds: List<FeedItem> = emptyList(),
        val sections: Map<FeedItem, FeedSectionResult> = emptyMap(),
        val sources: List<tachiyomi.domain.source.model.Source> = emptyList(),
        val showAddDialog: Boolean = false,
        val selectedSourceId: Long? = null,
        val listingOverride: FeedListing? = null,
        // True once the user has explicitly chosen a listing (including "All");
        // distinguishes user-selected "All" (null override) from uninitialized
        // state so preference emissions don't silently re-apply defaultListing.
        val listingSelected: Boolean = false,
        val showSourceSelector: Boolean = true,
        val showListingSelector: Boolean = true,
        val defaultListing: FeedListing? = null,
        // Session-scoped source filters (genre chips): the selected source's
        // own toggleable Filter leaves. Empty = source has none (or no source
        // selected) → UI honestly shows no chip row.
        val genreToggles: List<SourceModelFilter<*>> = emptyList(),
    ) {
        val visibleFeeds: List<FeedItem>
            get() {
                val enabled = feeds.filter { it.enabled }
                val sourceMatched = enabled.filter { selectedSourceId == null || it.sourceId == selectedSourceId }
                val listingMatched = sourceMatched.filter { listingOverride == null || it.listing == listingOverride }
                // A saved source with no enabled feeds left (removed/disabled)
                // falls back to showing everything instead of an empty screen.
                // A listing selection with no matching feeds (e.g. single-listing
                // source filtered to the other listing) falls back to the
                // source-matched set instead of an empty grid.
                return when {
                    listingMatched.isNotEmpty() -> listingMatched
                    sourceMatched.isNotEmpty() -> sourceMatched
                    else -> enabled
                }
            }
    }

    private var sectionJobs: MutableMap<FeedItem, Job> = mutableMapOf()

    // The selected source's live FilterList (genre chips mutate its leaves'
    // state in place). Session-scoped: not persisted (Feed architecture keeps
    // display prefs in FeedPreferences; filter chips are transient queries).
    private var sourceFilterList: FilterList? = null

    val gridColumns = feedPreferences.gridColumns().asState(screenModelScope)

    val compactGrid = feedPreferences.compactGrid().asState(screenModelScope)

    init {
        screenModelScope.launch {
            launch {
                feedPreferences.feeds().changes().collect { feeds ->
                    mutableState.update { it.copy(feeds = feeds) }
                    // Management screens pass loadSectionsOnStart=false: they only
                    // read feeds/mutations, so skip the per-feed network fetches.
                    if (loadSectionsOnStart) loadSections(feeds)
                }
            }
            launch {
                combine(
                    feedPreferences.showSourceSelector().changes(),
                    feedPreferences.showListingSelector().changes(),
                    feedPreferences.defaultListing().changes(),
                    feedPreferences.selectedSource().changes(),
                    ::DisplayPrefs,
                ).collect { prefs ->
                    // Legacy "All" default (null) falls back to Popular at read
                    // time; the All listing mode is removed from the UI.
                    val defaultListing = prefs.defaultListing ?: FeedListing.POPULAR
                    mutableState.update {
                        it.copy(
                            showSourceSelector = prefs.showSource,
                            showListingSelector = prefs.showListing,
                            defaultListing = defaultListing,
                            selectedSourceId = it.selectedSourceId ?: prefs.selectedSource,
                            listingOverride = if (it.listingSelected) it.listingOverride else defaultListing,
                        )
                    }
                    // Restore the persisted source's filter list once (model
                    // init / process recreation) so the chip row survives.
                    if (sourceFilterList == null) {
                        val restored = state.value.selectedSourceId
                        if (restored != null) {
                            sourceFilterList = (sourceManager.get(restored) as? CatalogueSource)
                                ?.getFilterList()
                            mutableState.update { s ->
                                s.copy(genreToggles = sourceFilterList?.genreToggles().orEmpty())
                            }
                        }
                    }
                }
            }
            launch {
                val sources = getEnabledSources.subscribe()
                sources.collect { list ->
                    mutableState.update {
                        it.copy(isLoading = false, sources = list.filterNot { s -> s.isStub })
                    }
                }
            }
        }
    }

    private fun loadSections(feeds: List<FeedItem>) {
        val enabled = feeds.filter { it.enabled }
        // Keep sections for feeds that still exist; drop the rest.
        mutableState.update { state ->
            state.copy(
                sections = enabled.associateWith { state.sections[it] ?: FeedSectionResult.Loading },
            )
        }
        enabled.forEach { feed ->
            if (state.value.sections[feed] is FeedSectionResult.Success) return@forEach
            sectionJobs.remove(feed)?.cancel()
            sectionJobs[feed] = screenModelScope.launch {
                val result = fetchSection(feed, page = 1)
                mutableState.update { it.copy(sections = it.sections + (feed to result)) }
            }
        }
    }

    /**
     * Explicitly loads the next page for one feed/listing. State-controlled:
     * no prefetch, no auto-infinite scroll.
     */
    fun loadMore(feed: FeedItem) {
        val section = state.value.sections[feed] as? FeedSectionResult.Success ?: return
        if (section.isLoadingMore || !section.hasMore) return
        sectionJobs.remove(feed)?.cancel()
        sectionJobs[feed] = screenModelScope.launch {
            mutableState.update {
                it.copy(sections = it.sections + (feed to section.copy(isLoadingMore = true)))
            }
            val nextPageNumber = section.mangas.size / PAGE_SIZE + 2
            val result = fetchSection(feed, page = nextPageNumber, appendTo = section)
            mutableState.update { it.copy(sections = it.sections + (feed to result)) }
        }
    }

    private suspend fun fetchSection(
        feed: FeedItem,
        page: Int,
        appendTo: FeedSectionResult.Success? = null,
    ): FeedSectionResult {
        val source = sourceManager.get(feed.sourceId) as? CatalogueSource
            ?: return FeedSectionResult.Error(null)
        return withIOContext {
            try {
                // Source-supported filtering: when genre chips are active for
                // this feed's source, route through the source's own search
                // listing with its FilterList (source keeps AND/OR semantics).
                val activeFilters = sourceFilterList.takeIf {
                    feed.sourceId == state.value.selectedSourceId && it != null
                }
                val pageResult = if (activeFilters != null &&
                    activeFilters.genreToggles().any { it.isGenreSelected() }
                ) {
                    source.getSearchManga(page, "", activeFilters)
                } else {
                    when (feed.listing) {
                        FeedListing.POPULAR -> source.getPopularManga(page)
                        FeedListing.LATEST -> source.getLatestUpdates(page)
                    }
                }
                val freshMangas = pageResult.mangas
                    .map { it.toDomainManga(source.id) }
                    .let { networkToLocalManga(it) }
                val mangas = if (appendTo == null) {
                    freshMangas.distinctBy { it.url }
                } else {
                    (appendTo.mangas + freshMangas).distinctBy { it.url }
                }
                FeedSectionResult.Success(
                    mangas = mangas,
                    hasMore = pageResult.hasNextPage,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.DEBUG) { "Feed fetch failed source=${feed.sourceId} page=$page" }
                if (appendTo != null) {
                    appendTo.copy(isLoadingMore = false)
                } else {
                    FeedSectionResult.Error(e.message)
                }
            }
        }
    }

    fun showAddDialog() {
        mutableState.update { it.copy(showAddDialog = true) }
    }

    fun dismissAddDialog() {
        mutableState.update { it.copy(showAddDialog = false) }
    }

    fun selectSource(sourceId: Long?) {
        feedPreferences.selectedSource().set(sourceId)
        // Load the newly selected source's own filter list for the chip row.
        // No refetch: source selection filters already-loaded sections
        // client-side (visibleFeeds); genre chips apply at fetch time.
        sourceFilterList = (sourceId?.let { sourceManager.get(it) } as? CatalogueSource)
            ?.getFilterList()
        mutableState.update {
            it.copy(
                selectedSourceId = sourceId,
                genreToggles = sourceFilterList?.genreToggles().orEmpty(),
            )
        }
    }

    /**
     * Toggle a source-filter genre chip: flips the source's own filter leaf
     * (TriState INCLUDE↔IGNORE, CheckBox on/off) and re-fetches page 1 of the
     * visible sections with the updated filter list.
     */
    fun toggleGenreChip(filter: SourceModelFilter<*>) {
        if (!filter.toggleGenreSelection()) return
        mutableState.update { it.copy(genreToggles = sourceFilterList?.genreToggles().orEmpty()) }
        refetchAllSections()
    }

    private fun refetchAllSections() {
        val feeds = state.value.visibleFeeds
        mutableState.update { state ->
            state.copy(
                sections = feeds.associateWith { FeedSectionResult.Loading },
            )
        }
        feeds.forEach { feed ->
            sectionJobs.remove(feed)?.cancel()
            sectionJobs[feed] = screenModelScope.launch {
                val result = fetchSection(feed, page = 1)
                mutableState.update { it.copy(sections = it.sections + (feed to result)) }
            }
        }
    }

    fun selectListing(listing: FeedListing?) {
        mutableState.update { it.copy(listingOverride = listing, listingSelected = true) }
    }

    fun setDefaultListing(listing: FeedListing?) {
        feedPreferences.defaultListing().set(listing)
        mutableState.update { it.copy(listingOverride = listing, listingSelected = true) }
    }

    fun toggleSourceSelector(show: Boolean) {
        feedPreferences.showSourceSelector().set(show)
    }

    fun toggleListingSelector(show: Boolean) {
        feedPreferences.showListingSelector().set(show)
    }

    fun setGridColumns(columns: Int) {
        feedPreferences.gridColumns().set(columns)
    }

    fun setCompactGrid(compact: Boolean) {
        feedPreferences.compactGrid().set(compact)
    }

    fun retry(feed: FeedItem) {
        sectionJobs.remove(feed)?.cancel()
        mutableState.update { it.copy(sections = it.sections + (feed to FeedSectionResult.Loading)) }
        sectionJobs[feed] = screenModelScope.launch {
            val result = fetchSection(feed, page = 1)
            mutableState.update { it.copy(sections = it.sections + (feed to result)) }
        }
    }

    fun addFeed(sourceId: Long, listing: FeedListing) {
        val feeds = feedPreferences.feeds().get()
        if (feeds.any { it.sourceId == sourceId && it.listing == listing }) return
        feedPreferences.feeds().set(feeds + FeedItem(sourceId, listing))
        mutableState.update { it.copy(showAddDialog = false) }
    }

    fun deleteFeed(feed: FeedItem) {
        feedPreferences.feeds().set(feedPreferences.feeds().get() - feed)
    }

    fun setFeedEnabled(feed: FeedItem, enabled: Boolean) {
        val feeds = feedPreferences.feeds().get()
        val index = feeds.indexOfFirst { it == feed.copy(enabled = true) || it == feed }
        if (index >= 0) {
            feedPreferences.feeds().set(feeds.toMutableList().also { it[index] = feed.copy(enabled = enabled) })
        }
    }

    fun moveFeedUp(feed: FeedItem) = moveFeed(feed, -1)

    fun moveFeedDown(feed: FeedItem) = moveFeed(feed, 1)

    /**
     * Drag-reorder: move the feed at [from] to [to] (indices in the full
     * persisted list). Same persistence path as the move buttons; no
     * network fetch is triggered by a reorder (sections are keyed by
     * FeedItem, order-independent).
     */
    fun moveFeedTo(from: Int, to: Int) {
        val feeds = feedPreferences.feeds().get().toMutableList()
        if (from !in feeds.indices || to !in feeds.indices || from == to) return
        val item = feeds.removeAt(from)
        feeds.add(to, item)
        feedPreferences.feeds().set(feeds)
    }

    private fun moveFeed(feed: FeedItem, delta: Int) {
        val feeds = feedPreferences.feeds().get().toMutableList()
        val index = feeds.indexOfFirst { it.sourceId == feed.sourceId && it.listing == feed.listing }
        val target = index + delta
        if (index < 0 || target !in feeds.indices) return
        val item = feeds.removeAt(index)
        feeds.add(target, item)
        feedPreferences.feeds().set(feeds)
    }

    private companion object {
        // ponytail: page-size assumption for append page math; per-feed server sizes vary,
        // swap for a stored page counter if a source returns uneven pages.
        const val PAGE_SIZE = 20
    }
}
