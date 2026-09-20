package eu.kanade.presentation.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.feed.model.FeedItem
import eu.kanade.domain.feed.model.FeedListing
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.more.settings.widget.PreferenceGroupCard
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.tachiyomi.ui.browse.source.browse.isGenreSelected
import eu.kanade.tachiyomi.ui.feed.FeedScreenModel
import eu.kanade.tachiyomi.ui.feed.FeedSectionResult
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.model.Source
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.header
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

private const val AUTO_LOAD_THRESHOLD = 5

@Composable
fun FeedScreen(
    state: FeedScreenModel.State,
    onMangaClick: (Long) -> Unit,
    onAddFeedClick: () -> Unit,
    onManageFeedsClick: () -> Unit,
    onAddFeedConfirm: (Long, List<FeedListing>) -> Unit,
    onSelectSource: (Long?) -> Unit,
    onSelectListing: (FeedListing?) -> Unit,
    onToggleGenre: (SourceModelFilter<*>) -> Unit,
    onDismissAddDialog: () -> Unit,
    onLoadMore: (FeedItem) -> Unit,
    onRetry: (FeedItem) -> Unit,
    gridColumns: Int,
    compactGrid: Boolean,
    onChangeGridColumns: (Int) -> Unit,
    onToggleCompactGrid: (Boolean) -> Unit,
    onToggleSourceSelector: (Boolean) -> Unit,
    onToggleListingSelector: (Boolean) -> Unit,
    onSelectDefaultListing: (FeedListing?) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    var showCustomizeDialog by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState, state) {
        snapshotFlow {
            val layout = gridState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = layout.totalItemsCount
            lastVisible >= totalItems - AUTO_LOAD_THRESHOLD
        }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (nearEnd && state.selectedSourceId != null) {
                    val lastFeed = state.visibleFeeds.lastOrNull() ?: return@collect
                    val section = state.sections[lastFeed] as? FeedSectionResult.Success
                        ?: return@collect
                    if (!section.isLoadingMore && section.hasMore) {
                        onLoadMore(lastFeed)
                    }
                }
            }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppBar(
                title = stringResource(MR.strings.label_feed),
                actions = {
                    AppBarActions(
                        listOf(
                            AppBar.Action(
                                title = stringResource(MR.strings.feed_manage),
                                icon = Icons.Outlined.Tune,
                                onClick = onManageFeedsClick,
                            ),
                            AppBar.Action(
                                title = stringResource(MR.strings.action_settings),
                                icon = Icons.Outlined.GridView,
                                onClick = { showCustomizeDialog = true },
                            ),
                            AppBar.Action(
                                title = stringResource(MR.strings.feed_add),
                                icon = Icons.Outlined.Add,
                                onClick = onAddFeedClick,
                            ),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (state.isLoading) {
            LoadingScreen(Modifier.padding(padding))
            return@Scaffold
        }
        if (state.feeds.isEmpty()) {
            EmptyScreen(
                stringRes = MR.strings.feed_empty,
                modifier = Modifier.padding(padding),
                actions = listOf(
                    EmptyScreenAction(
                        stringRes = MR.strings.feed_add,
                        icon = Icons.Outlined.Add,
                        onClick = onAddFeedClick,
                    ),
                ),
            )
            return@Scaffold
        }

        // Filter bar is the first grid item: scrolling content collapses it
        // out of view naturally and it returns on scroll-up (no nested scroll
        // containers, no custom collapse framework).
        val bottom = padding.calculateBottomPadding()
        LazyVerticalGrid(
            state = gridState,
            columns = if (gridColumns > 0) GridCells.Fixed(gridColumns) else GridCells.Adaptive(96.dp),
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
            horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, contentType = { "feed_filter_bar" }) {
                FeedFilterBar(
                    state = state,
                    onSelectSource = onSelectSource,
                    onSelectListing = onSelectListing,
                    onToggleGenre = onToggleGenre,
                )
            }
            state.visibleFeeds.forEach { feed ->
                if (state.selectedSourceId == null) {
                    val sourceName = state.sources.firstOrNull { it.id == feed.sourceId }?.visualName
                    if (sourceName != null) {
                        item(
                            span = { GridItemSpan(maxLineSpan) },
                            contentType = { "feed_source_header" },
                        ) {
                            Text(
                                text = sourceName,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                when (val section = state.sections[feed]) {
                    is FeedSectionResult.Success -> {
                        items(section.mangas, key = { "${feed.sourceId}-${feed.listing}-${it.url}" }) { manga ->
                            if (compactGrid) {
                                MangaCompactGridItem(
                                    coverData = manga.asMangaCover(),
                                    onClick = { onMangaClick(manga.id) },
                                    onLongClick = { },
                                    title = manga.title,
                                )
                            } else {
                                MangaComfortableGridItem(
                                    coverData = manga.asMangaCover(),
                                    title = manga.title,
                                    onClick = { onMangaClick(manga.id) },
                                    onLongClick = { },
                                )
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }, contentType = { "feed_section_footer" }) {
                            FeedSectionFooter(
                                section = section,
                                onRetry = { onRetry(feed) },
                                onLoadMore = { onLoadMore(feed) },
                            )
                        }
                    }
                    is FeedSectionResult.Loading, null -> item(
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = { "feed_section_loading" },
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.padding.medium),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    is FeedSectionResult.Error -> item(
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = { "feed_section_error" },
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.padding.medium),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = section.message ?: stringResource(MR.strings.unknown_error),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = { onRetry(feed) }) {
                                Text(stringResource(MR.strings.action_retry))
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showAddDialog) {
        AddFeedDialog(
            sources = state.sources,
            onConfirm = onAddFeedConfirm,
            onDismiss = onDismissAddDialog,
        )
    }

    if (showCustomizeDialog) {
        FeedCustomizeDialog(
            gridColumns = gridColumns,
            compactGrid = compactGrid,
            onChangeGridColumns = onChangeGridColumns,
            onToggleCompactGrid = onToggleCompactGrid,
            onToggleSourceSelector = onToggleSourceSelector,
            onToggleListingSelector = onToggleListingSelector,
            onSelectDefaultListing = onSelectDefaultListing,
            showSourceSelector = state.showSourceSelector,
            showListingSelector = state.showListingSelector,
            defaultListing = state.defaultListing,
            onDismiss = { showCustomizeDialog = false },
        )
    }
}

@Composable
private fun AddFeedDialog(
    sources: List<Source>,
    onConfirm: (Long, List<FeedListing>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedSource by remember { mutableStateOf<Source?>(null) }
    var selectedListings by remember { mutableStateOf(setOf(FeedListing.LATEST)) }

    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(MaterialTheme.padding.medium),
        ) {
            Text(
                stringResource(MR.strings.feed_add),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(MaterialTheme.padding.small))
            Text(stringResource(MR.strings.feed_select_source), style = MaterialTheme.typography.header)
            sources.forEach { source ->
                val selected = selectedSource?.id == source.id
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(source.visualName) },
                    trailingContent = {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = {
                                selectedSource = source
                                if (!source.supportsLatest) {
                                    selectedListings = setOf(FeedListing.POPULAR)
                                } else {
                                    selectedListings = setOf(FeedListing.LATEST)
                                }
                            },
                        ),
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.padding.small))
            Text(stringResource(MR.strings.feed_select_listing), style = MaterialTheme.typography.header)
            val latestSupported = selectedSource?.supportsLatest ?: true
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                FilterChip(
                    selected = FeedListing.POPULAR in selectedListings,
                    onClick = {
                        selectedListings = if (FeedListing.POPULAR in selectedListings) {
                            selectedListings - FeedListing.POPULAR
                        } else {
                            selectedListings + FeedListing.POPULAR
                        }
                    },
                    label = { Text(stringResource(MR.strings.popular)) },
                )
                FilterChip(
                    selected = FeedListing.LATEST in selectedListings,
                    onClick = {
                        if (latestSupported) {
                            selectedListings = if (FeedListing.LATEST in selectedListings) {
                                selectedListings - FeedListing.LATEST
                            } else {
                                selectedListings + FeedListing.LATEST
                            }
                        }
                    },
                    enabled = latestSupported,
                    label = { Text(stringResource(MR.strings.latest)) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(MR.strings.action_cancel))
                }
                TextButton(
                    onClick = {
                        selectedSource?.let { source ->
                            onConfirm(source.id, selectedListings.toList())
                        }
                    },
                    enabled = selectedSource != null && selectedListings.isNotEmpty(),
                ) {
                    Text(stringResource(MR.strings.action_ok))
                }
            }
        }
    }
}

@Composable
private fun FeedSectionFooter(
    section: FeedSectionResult.Success,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.padding.small),
        contentAlignment = Alignment.Center,
    ) {
        when {
            section.isLoadingMore -> CircularProgressIndicator(strokeWidth = 3.dp)
            !section.hasMore -> Text(
                text = stringResource(MR.strings.feed_end_of_list),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> TextButton(onClick = onLoadMore) {
                Text(stringResource(MR.strings.feed_load_more))
            }
        }
    }
}

@Composable
private fun FeedFilterBar(
    state: FeedScreenModel.State,
    onSelectSource: (Long?) -> Unit,
    onSelectListing: (FeedListing?) -> Unit,
    onToggleGenre: (SourceModelFilter<*>) -> Unit,
) {
    val enabledFeeds = state.feeds.filter { it.enabled }
    val feedSources = enabledFeeds.map { it.sourceId }.distinct()
        .mapNotNull { id -> state.sources.firstOrNull { it.id == id } }
    val hasListings = enabledFeeds.map { it.listing }.distinct().size > 1
    val hasSourceSelector = state.showSourceSelector && feedSources.size >= 2
    val showSelectorRow = hasSourceSelector ||
        (state.showListingSelector && hasListings) ||
        state.genreToggles.isNotEmpty()
    val genreToggles = state.genreToggles

    if (!showSelectorRow) return

    // Compact rows: primary controls inline in one row (source selector left,
    // listing chips right where width allows), source-supported filter chips
    // on a second scrollable row.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.extraSmall)
            .padding(vertical = MaterialTheme.padding.extraSmall),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasSourceSelector) {
                SourceSelectorDropdown(
                    state = state,
                    onSelectSource = onSelectSource,
                )
            }
            if (state.showListingSelector && hasListings) {
                FilterChip(
                    selected = state.listingOverride == FeedListing.POPULAR,
                    onClick = { onSelectListing(FeedListing.POPULAR) },
                    label = { Text(stringResource(MR.strings.popular)) },
                )
                FilterChip(
                    selected = state.listingOverride == FeedListing.LATEST,
                    onClick = { onSelectListing(FeedListing.LATEST) },
                    label = { Text(stringResource(MR.strings.latest)) },
                )
            }
        }
        // Source-supported filter chips: shown only when the selected source
        // actually exposes toggleable Filter leaves (honest absence).
        if (genreToggles.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                genreToggles.forEach { filter ->
                    val selected = filter.isGenreSelected()
                    FilterChip(
                        selected = selected,
                        onClick = { onToggleGenre(filter) },
                        leadingIcon = if (selected) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        } else {
                            null
                        },
                        label = { Text(filter.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceSelectorDropdown(
    state: FeedScreenModel.State,
    onSelectSource: (Long?) -> Unit,
) {
    val enabledFeeds = state.feeds.filter { it.enabled }
    val feedSources = enabledFeeds.map { it.sourceId }.distinct()
        .mapNotNull { id -> state.sources.firstOrNull { it.id == id } }
    val selected = feedSources.firstOrNull { it.id == state.selectedSourceId }
    var expanded by remember { mutableStateOf(false) }

    // Size the label to the widest candidate so switching sources doesn't
    // change the chip's footprint; cap it so it can't squeeze the listing
    // chips on narrow screens.
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val allSourcesLabel = stringResource(MR.strings.feed_all_sources)
    val maxWidth = with(density) { 160.dp.roundToPx() }
    val labelWidth = remember(textMeasurer, labelStyle, maxWidth, feedSources, allSourcesLabel) {
        val labels = listOf(allSourcesLabel) + feedSources.map { it.visualName }
        labels.maxOf { textMeasurer.measure(it, labelStyle, maxLines = 1).size.width }
            .coerceAtMost(maxWidth)
    }
    val labelModifier = with(density) { Modifier.width(labelWidth.toDp()) }

    Box {
        FilterChip(
            selected = selected != null,
            onClick = { expanded = true },
            label = {
                Text(
                    text = selected?.visualName ?: stringResource(MR.strings.feed_all_sources),
                    modifier = labelModifier,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val selectedLabel = stringResource(MR.strings.selected)
            val notSelectedLabel = stringResource(MR.strings.not_selected)
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.feed_all_sources)) },
                onClick = {
                    onSelectSource(null)
                    expanded = false
                },
                modifier = Modifier.semantics {
                    stateDescription = if (selected == null) selectedLabel else notSelectedLabel
                },
                trailingIcon = {
                    if (selected == null) Icon(Icons.Outlined.Check, contentDescription = null)
                },
            )
            feedSources.forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.visualName) },
                    onClick = {
                        onSelectSource(source.id)
                        expanded = false
                    },
                    modifier = Modifier.semantics {
                        stateDescription = if (selected?.id == source.id) selectedLabel else notSelectedLabel
                    },
                    trailingIcon = {
                        if (selected?.id == source.id) Icon(Icons.Outlined.Check, contentDescription = null)
                    },
                )
            }
        }
    }
}

@Composable
private fun FeedCustomizeDialog(
    gridColumns: Int,
    compactGrid: Boolean,
    onChangeGridColumns: (Int) -> Unit,
    onToggleCompactGrid: (Boolean) -> Unit,
    onToggleSourceSelector: (Boolean) -> Unit,
    onToggleListingSelector: (Boolean) -> Unit,
    onSelectDefaultListing: (FeedListing?) -> Unit,
    showSourceSelector: Boolean,
    showListingSelector: Boolean,
    defaultListing: FeedListing?,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Display: grid columns + grid style
            PreferenceGroupCard(title = stringResource(MR.strings.action_display)) {
                SettingsChipRow(MR.strings.feed_grid_columns) {
                    FilterChip(
                        selected = gridColumns == 0,
                        onClick = { onChangeGridColumns(0) },
                        label = { Text(stringResource(MR.strings.label_auto)) },
                    )
                    listOf(2, 3, 4, 5).forEach { columns ->
                        FilterChip(
                            selected = gridColumns == columns,
                            onClick = { onChangeGridColumns(columns) },
                            label = { Text(columns.toString()) },
                        )
                    }
                }
                SettingsChipRow(MR.strings.feed_grid_style) {
                    FilterChip(
                        selected = !compactGrid,
                        onClick = { onToggleCompactGrid(false) },
                        label = { Text(stringResource(MR.strings.feed_grid_style_normal)) },
                    )
                    FilterChip(
                        selected = compactGrid,
                        onClick = { onToggleCompactGrid(true) },
                        label = { Text(stringResource(MR.strings.feed_grid_style_compact)) },
                    )
                }
            }

            // Sources
            PreferenceGroupCard(title = stringResource(MR.strings.feed_sources_section)) {
                SwitchPreferenceWidget(
                    title = stringResource(MR.strings.feed_show_source_selector),
                    checked = showSourceSelector,
                    onCheckedChanged = onToggleSourceSelector,
                )
            }

            // Listing
            PreferenceGroupCard(title = stringResource(MR.strings.feed_default_listing)) {
                SettingsChipRow(MR.strings.feed_default_listing) {
                    FilterChip(
                        selected = defaultListing == FeedListing.POPULAR,
                        onClick = { onSelectDefaultListing(FeedListing.POPULAR) },
                        label = { Text(stringResource(MR.strings.popular)) },
                    )
                    FilterChip(
                        selected = defaultListing == FeedListing.LATEST,
                        onClick = { onSelectDefaultListing(FeedListing.LATEST) },
                        label = { Text(stringResource(MR.strings.latest)) },
                    )
                }
                SwitchPreferenceWidget(
                    title = stringResource(MR.strings.feed_show_listing_selector),
                    checked = showListingSelector,
                    onCheckedChanged = onToggleListingSelector,
                )
            }
        }
    }
}
