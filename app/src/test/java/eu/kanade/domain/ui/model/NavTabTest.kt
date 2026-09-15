package eu.kanade.domain.ui.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NavTabTest {

    @Test
    fun `default order is library recent feed browse more`() {
        assertEquals(
            listOf(NavTab.LIBRARY, NavTab.RECENT, NavTab.FEED, NavTab.BROWSE, NavTab.MORE),
            NavTab.DEFAULT_ORDER,
        )
    }

    @Test
    fun `parse null or blank falls back to default`() {
        assertEquals(NavTab.DEFAULT_ORDER, NavTab.parseOrder(null))
        assertEquals(NavTab.DEFAULT_ORDER, NavTab.parseOrder(""))
        assertEquals(NavTab.DEFAULT_ORDER, NavTab.parseOrder("   "))
    }

    @Test
    fun `parse roundtrips serialized order`() {
        val order = listOf(NavTab.MORE, NavTab.LIBRARY, NavTab.FEED, NavTab.BROWSE, NavTab.RECENT)
        assertEquals(order, NavTab.parseOrder(NavTab.serializeOrder(order)))
    }

    @Test
    fun `parse drops unknown names and appends missing tabs`() {
        assertEquals(
            listOf(NavTab.LIBRARY, NavTab.RECENT, NavTab.FEED, NavTab.BROWSE, NavTab.MORE),
            NavTab.parseOrder("LIBRARY,ANIME,RECENT"),
        )
    }

    @Test
    fun `parse appends missing tabs in default order`() {
        // Stored from an older version that lacked FEED
        assertEquals(
            listOf(NavTab.MORE, NavTab.LIBRARY, NavTab.RECENT, NavTab.FEED, NavTab.BROWSE),
            NavTab.parseOrder("MORE,LIBRARY"),
        )
    }

    @Test
    fun `parse removes duplicates`() {
        assertEquals(
            listOf(NavTab.LIBRARY, NavTab.RECENT, NavTab.FEED, NavTab.BROWSE, NavTab.MORE),
            NavTab.parseOrder("LIBRARY,LIBRARY,RECENT,FEED,BROWSE,MORE"),
        )
    }

    @Test
    fun `parse trims whitespace but stays case-sensitive`() {
        // Names are written by serializeOrder, so matching is exact-case;
        // lowercase input is unknown and safely appended back in default order.
        assertEquals(
            listOf(NavTab.LIBRARY, NavTab.FEED, NavTab.BROWSE, NavTab.MORE, NavTab.RECENT),
            NavTab.parseOrder(" LIBRARY , recent ,FEED,BROWSE,MORE"),
        )
    }

    @Test
    fun `parse garbage yields default`() {
        assertEquals(NavTab.DEFAULT_ORDER, NavTab.parseOrder("!!!,42,{}"))
    }

    @Test
    fun `malformed full list never loses a tab`() {
        // Even a fully-corrupt stored order still produces all five tabs
        val parsed = NavTab.parseOrder("garbage")
        assertEquals(5, parsed.size)
        assertEquals(NavTab.entries.toSet(), parsed.toSet())
    }
}
