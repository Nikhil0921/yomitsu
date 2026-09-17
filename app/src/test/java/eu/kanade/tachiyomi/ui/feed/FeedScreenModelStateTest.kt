package eu.kanade.tachiyomi.ui.feed

import eu.kanade.domain.feed.model.FeedItem
import eu.kanade.domain.feed.model.FeedListing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Covers the D-02 listing-filter fix: `visibleFeeds` must honor
 * `listingOverride` while keeping the source/enabled filters and fallbacks.
 */
class FeedScreenModelStateTest {

    private fun state(
        feeds: List<FeedItem>,
        selectedSourceId: Long? = null,
        listingOverride: FeedListing? = null,
    ) = FeedScreenModel.State(
        feeds = feeds,
        selectedSourceId = selectedSourceId,
        listingOverride = listingOverride,
    )

    private val srcA = 1L
    private val srcB = 2L

    private fun feeds(vararg items: FeedItem) = items.toList()

    private val aPopular = FeedItem(srcA, FeedListing.POPULAR)
    private val aLatest = FeedItem(srcA, FeedListing.LATEST)
    private val bPopular = FeedItem(srcB, FeedListing.POPULAR)

    @Test
    fun `null override shows all listings`() {
        val s = state(feeds(aPopular, aLatest, bPopular), listingOverride = null)
        assertEquals(listOf(aPopular, aLatest, bPopular), s.visibleFeeds)
    }

    @Test
    fun `POPULAR override shows only popular feeds`() {
        val s = state(feeds(aPopular, aLatest, bPopular), listingOverride = FeedListing.POPULAR)
        assertEquals(listOf(aPopular, bPopular), s.visibleFeeds)
    }

    @Test
    fun `LATEST override shows only latest feeds`() {
        val s = state(feeds(aPopular, aLatest, bPopular), listingOverride = FeedListing.LATEST)
        assertEquals(listOf(aLatest), s.visibleFeeds)
    }

    @Test
    fun `source and listing filters compose`() {
        val s = state(feeds(aPopular, aLatest, bPopular), selectedSourceId = srcA, listingOverride = FeedListing.LATEST)
        assertEquals(listOf(aLatest), s.visibleFeeds)
    }

    @Test
    fun `disabled feeds are hidden`() {
        val s = state(
            feeds(aPopular, aLatest.copy(enabled = false), bPopular),
            listingOverride = FeedListing.LATEST,
        )
        // No enabled LATEST feed exists, so the source-matched fallback applies
        // (per §15: empty filtered result falls back, never an empty grid).
        assertEquals(listOf(aPopular, bPopular), s.visibleFeeds)
    }

    @Test
    fun `listing override with no match falls back to source-matched set`() {
        val s = state(feeds(aPopular, bPopular), listingOverride = FeedListing.LATEST)
        assertEquals(listOf(aPopular, bPopular), s.visibleFeeds)
    }

    @Test
    fun `stale source selection falls back to all enabled feeds`() {
        val s = state(feeds(aPopular, bPopular), selectedSourceId = 99L)
        assertEquals(listOf(aPopular, bPopular), s.visibleFeeds)
    }

    @Test
    fun `no feeds yields empty list`() {
        val s = state(feeds(), listingOverride = FeedListing.POPULAR)
        assertEquals(emptyList<FeedItem>(), s.visibleFeeds)
    }

    @Test
    fun `feed order is preserved`() {
        val s = state(feeds(bPopular, aPopular, aLatest), listingOverride = FeedListing.POPULAR)
        assertEquals(listOf(bPopular, aPopular), s.visibleFeeds)
    }

    @Test
    fun `single source without listing override shows both feeds`() {
        val s = state(feeds(aPopular, aLatest), selectedSourceId = srcA)
        assertEquals(listOf(aPopular, aLatest), s.visibleFeeds)
    }

    @Test
    fun `single source with listing override shows one feed`() {
        val s = state(feeds(aPopular, aLatest), selectedSourceId = srcA, listingOverride = FeedListing.POPULAR)
        assertEquals(listOf(aPopular), s.visibleFeeds)
    }
}
