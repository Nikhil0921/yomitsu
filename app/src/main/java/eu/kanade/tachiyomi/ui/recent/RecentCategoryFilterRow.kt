package eu.kanade.tachiyomi.ui.recent

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Category chip row for the Recent tab pages: an "All" chip plus one chip
 * per user category. The selected category id is persisted so it survives
 * tab switches; the row itself is hidden by the page's display toggle.
 */
@Composable
fun RecentCategoryFilterRow(
    isUpdatesPage: Boolean,
    modifier: Modifier = Modifier,
) {
    val libraryPreferences = Injekt.get<LibraryPreferences>()

    val categories by produceState(initialValue = emptyList()) {
        val getCategories = Injekt.get<GetCategories>()
        value = getCategories.await().filterNot { it.isSystemCategory }
    }
    // ponytail: categories fetched once at composition, no live category
    // changes mid-page (upgrade: observe getCategories.subscribe() if category
    // editing from this surface is ever needed).

    // ponytail: local + pref mirror; no live re-read of the pref across
    // recompositions (upgrade: collectAsState on the pref when a settings
    // screen gains a category-filter toggle that writes the same key).
    var selectedId by mutableStateOf(
        if (isUpdatesPage) {
            libraryPreferences.recentUpdatesCategoryId.get()
        } else {
            libraryPreferences.recentHistoryCategoryId.get()
        },
    )

    fun onSelect(id: Int) {
        selectedId = id
        if (isUpdatesPage) {
            libraryPreferences.recentUpdatesCategoryId.set(id)
        } else {
            libraryPreferences.recentHistoryCategoryId.set(id)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                horizontal = MaterialTheme.padding.extraSmall,
                vertical = MaterialTheme.padding.extraSmall,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = selectedId == 0,
            onClick = { onSelect(0) },
            label = { Text(stringResource(MR.strings.label_all_categories)) },
        )
        categories.forEach { category ->
            FilterChip(
                selected = selectedId == category.id.toInt(),
                onClick = { onSelect(category.id.toInt()) },
                label = { Text(category.name) },
            )
        }
    }
}
