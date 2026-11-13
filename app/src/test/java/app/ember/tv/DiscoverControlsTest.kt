package app.ember.tv

import app.ember.tv.discover.ExploreKind
import app.ember.tv.discover.ExploreQuery
import app.ember.tv.discover.ExploreRef
import app.ember.tv.discover.ExploreSort
import app.ember.tv.ui.DiscoverLook
import app.ember.tv.ui.FilterRail
import app.ember.tv.ui.FilterSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #244 Discover's filters and sort; #245 the navbar tabs. */
class DiscoverControlsTest {
    @Test fun slidersStepThroughSensibleValues() {
        assertEquals(0, FilterSteps.years.first()); assertTrue(FilterSteps.years.zipWithNext().all { (a, b) -> a < b })
        assertEquals("Any", FilterSteps.yearLabel(0)); assertEquals("1990", FilterSteps.yearLabel(1990))
        assertEquals("7.5+", FilterSteps.minLabel(75)); assertEquals("Any", FilterSteps.minLabel(0))
        assertEquals("Up to 8.0", FilterSteps.maxLabel(80)); assertEquals("Any", FilterSteps.maxLabel(100))
        assertEquals(75, FilterSteps.tenths(7.5)); assertEquals(null, FilterSteps.tenths(null))
        assertEquals(1990 to 2000, FilterSteps.ordered(2000, 1990)); assertEquals(1990 to null, FilterSteps.ordered(1990, null))
        listOf(FilterSteps.minScore, FilterSteps.maxScore, FilterSteps.movieVotes, FilterSteps.showVotes).forEach { steps -> assertTrue(steps.zipWithNext().all { (a, b) -> a < b }) }
        // The default vote minimums are steps, so the sliders start where the query is.
        assertTrue(ExploreQuery().minMovieVotes in FilterSteps.movieVotes && ExploreQuery().minTvVotes in FilterSteps.showVotes)
    }

    @Test fun eachCategorySaysWhatItIsSetTo() {
        assertEquals("Any year", FilterSteps.yearSummary(ExploreQuery()))
        assertEquals("2010–2019", FilterSteps.yearSummary(ExploreQuery(fromYear = 2010, toYear = 2019)))
        assertEquals("2020 onward", FilterSteps.yearSummary(ExploreQuery(fromYear = 2020)))
        assertEquals("7.0+", FilterSteps.scoreSummary(ExploreQuery(minRating = 7.0)))
        assertEquals("Netflix, Crave", FilterSteps.names(listOf(ExploreRef(1, "Netflix"), ExploreRef(2, "Crave")), "Any service"))
        assertTrue("Any year" in FilterRail.unset && "Any score" in FilterRail.unset)
    }

    @Test fun sortAndFiltersAreDescribed() {
        ExploreSort.entries.forEach { assertTrue(DiscoverLook.sortNote(it).isNotBlank()) }
        assertEquals("Filters", DiscoverLook.filtersLabel(0)); assertEquals("Filters · 3", DiscoverLook.filtersLabel(3))
        val ui = java.io.File("src/main/java/app/ember/tv/ui/ExploreUi.kt").readText()
        assertTrue(ui.contains("SortSheet(state.query.sort") && ui.contains("ActiveFilterTags(state.query"))
        assertFalse("#251: the title type is chosen in Filters only", ui.contains("KindSegments("))
        assertFalse("sorting is no longer a plain list", ui.contains("OptionMenu(\"Sort by\""))
        val filters = java.io.File("src/main/java/app/ember/tv/ui/ExploreFilters.kt").readText()
        assertFalse("no typed years or scores", filters.contains("placeholder = \"From year\"") || filters.contains("placeholder = \"Minimum score\""))
        assertTrue(filters.contains("FilterRailItem(") && filters.contains("KindSegments(draft.kind)") && filters.contains("YearPane(draft)"))
        val visuals = java.io.File("src/main/java/app/ember/tv/ui/FilterVisuals.kt").readText()
        assertTrue(visuals.contains("StepSlider(\"Earliest year\"") && visuals.contains("StepSlider(\"Latest year\""))
        assertFalse("#251: no decade chips", filters.contains("\"1980s\""))
        assertEquals(3, ExploreKind.entries.size)
    }

    @Test fun navbarTabsSwitchOnAndOffBesideTheNavbarStyle() {
        val tabs = java.io.File("src/main/java/app/ember/tv/ui/SettingsSystem.kt").readText().substringAfter("internal fun TopMenuSection").substringBefore("\n}\n")
        assertFalse("no moving tabs", tabs.contains("moveTab") || tabs.contains("ui_move_left"))
        assertTrue(tabs.contains("SwitchChip(tabTitle(id), shown"))
        assertTrue("navbar-tabs" in app.ember.tv.ui.lookKeys(app.ember.tv.ui.SettingsTab.APPEARANCE, app.ember.tv.data.InterfaceStyles.MODERN))
        assertEquals(app.ember.tv.ui.SettingsTab.APPEARANCE, app.ember.tv.ui.SettingsTab.TOP_MENU.host)
    }
}
