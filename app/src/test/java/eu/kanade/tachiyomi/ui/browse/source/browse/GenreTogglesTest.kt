package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Q2 genre-chip search: chip derivation + selection/toggle semantics
 * over the source's own Filter objects (no parallel state).
 */
class GenreTogglesTest {

    private class TestCheckBox(name: String, state: Boolean = false) : Filter.CheckBox(name, state)
    private class TestTriState(name: String, state: Int = STATE_IGNORE) : Filter.TriState(name, state)
    private class TestSelect(name: String, values: Array<String>) : Filter.Select<String>(name, values)
    private class TestGroup(name: String, state: List<Filter<*>>) : Filter.Group<Filter<*>>(name, state)
    private class TestText(name: String) : Filter.Text(name)

    @Test
    fun `group tri-state and checkbox children surface as chips`() {
        val filters = FilterList(
            TestGroup(
                "Genres",
                listOf(
                    TestTriState("Action"),
                    TestCheckBox("Comedy"),
                    TestText("Not a genre"),
                ),
            ),
        )
        val chips = filters.genreToggles()
        assertEquals(listOf("Action", "Comedy"), chips.map { it.name })
    }

    @Test
    fun `top-level tri-state and checkbox surface as chips`() {
        val filters = FilterList(TestTriState("Action"), TestCheckBox("Drama"))
        assertEquals(listOf("Action", "Drama"), filters.genreToggles().map { it.name })
    }

    @Test
    fun `select-text-and-header-only filters expose no chips`() {
        val filters = FilterList(
            TestSelect("Order by", arrayOf("Popular", "Latest")),
            TestText("Query"),
            Filter.Header("Header"),
            Filter.Separator(),
        )
        assertTrue(filters.genreToggles().isEmpty())
    }

    @Test
    fun `tri-state selected only when include`() {
        val include = TestTriState("A", state = Filter.TriState.STATE_INCLUDE)
        val ignore = TestTriState("B")
        val exclude = TestTriState("C", state = Filter.TriState.STATE_EXCLUDE)
        assertTrue(include.isGenreSelected())
        assertFalse(ignore.isGenreSelected())
        assertFalse(exclude.isGenreSelected())
    }

    @Test
    fun `checkbox selected when checked`() {
        assertTrue(TestCheckBox("A", state = true).isGenreSelected())
        assertFalse(TestCheckBox("B").isGenreSelected())
    }

    @Test
    fun `toggle flips include to ignore and back`() {
        val chip = TestTriState("Action")
        assertTrue(chip.toggleGenreSelection())
        assertEquals(Filter.TriState.STATE_INCLUDE, chip.state)
        assertTrue(chip.isGenreSelected())
        assertTrue(chip.toggleGenreSelection())
        assertEquals(Filter.TriState.STATE_IGNORE, chip.state)
        assertFalse(chip.isGenreSelected())
    }

    @Test
    fun `toggle flips checkbox`() {
        val chip = TestCheckBox("Comedy")
        assertTrue(chip.toggleGenreSelection())
        assertTrue(chip.state)
        assertTrue(chip.toggleGenreSelection())
        assertFalse(chip.state)
    }

    @Test
    fun `exclude-state chip toggles to include not back to ignore`() {
        val chip = TestTriState("Drama", state = Filter.TriState.STATE_EXCLUDE)
        assertTrue(chip.toggleGenreSelection())
        assertEquals(Filter.TriState.STATE_INCLUDE, chip.state)
    }

    @Test
    fun `multiple chips can be selected independently`() {
        val action = TestTriState("Action")
        val comedy = TestTriState("Comedy")
        val drama = TestCheckBox("Drama")
        val chips = FilterList(action, comedy, drama).genreToggles()
        action.toggleGenreSelection()
        drama.toggleGenreSelection()
        assertEquals(listOf(true, false, true), chips.map { it.isGenreSelected() })
        comedy.toggleGenreSelection()
        assertEquals(listOf(true, true, true), chips.map { it.isGenreSelected() })
    }

    @Test
    fun `toggle on non-genre filter is a no-op`() {
        val chip = TestSelect("Order by", arrayOf("Popular"))
        assertFalse(chip.toggleGenreSelection())
        assertEquals(0, chip.state)
    }
}
