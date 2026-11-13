package app.ember.tv

import app.ember.tv.data.CollectionSource
import app.ember.tv.data.HomeWidget
import app.ember.tv.data.HomeWidgets
import app.ember.tv.ui.WidgetSteps
import app.ember.tv.ui.WidgetSteps.Check
import org.junit.Assert.*
import org.junit.Test

/** #254: the widget creator is a guided flow that cannot save an empty widget. */
class WidgetWizardTest {
    private val empty = HomeWidget(id = "w")
    private val catalog = empty.copy(title = "Popular", source = CollectionSource(provider = "addon", addonId = "a", type = "movie", catalogId = "top", title = "Popular"))
    private val list = empty.copy(title = "Watchlist", source = CollectionSource(provider = HomeWidgets.LIST, catalogId = "watchlist", title = "Watchlist"))

    @Test fun aNewWidgetCannotSkipAhead() {
        assertNotNull(WidgetSteps.sourceProblem(empty, Check()))
        for (step in listOf(WidgetSteps.STYLE, WidgetSteps.DETAILS, WidgetSteps.REVIEW)) assertFalse(step, WidgetSteps.open(step, empty, Check(), existing = false))
        assertEquals(WidgetSteps.SOURCE, WidgetSteps.blocker(empty, Check())!!.first)
        assertFalse(WidgetSteps.showsSave(WidgetSteps.DETAILS, existing = false)); assertTrue(WidgetSteps.showsSave(WidgetSteps.REVIEW, existing = false))
    }

    @Test fun theSourceMustLoadWithTitles() {
        assertEquals("Checking the source…", WidgetSteps.sourceProblem(catalog, Check()))
        assertNotNull(WidgetSteps.sourceProblem(catalog, Check(titles = 0)))
        assertNotNull(WidgetSteps.sourceProblem(catalog, Check(error = "offline")))
        assertNull(WidgetSteps.sourceProblem(catalog, Check(titles = 12)))
        assertFalse(WidgetSteps.open(WidgetSteps.STYLE, catalog, Check(titles = 0), existing = false))
        assertTrue(WidgetSteps.open(WidgetSteps.REVIEW, catalog, Check(titles = 12), existing = false))
        assertNull(WidgetSteps.blocker(catalog, Check(titles = 12)))
    }

    @Test fun yourOwnListsMayBeEmptyForNow() {
        assertNull(WidgetSteps.sourceProblem(list, Check(titles = 0)))
        assertTrue(WidgetSteps.status(list, Check(titles = 0)).startsWith("Empty for now"))
    }

    @Test fun aNameIsNeededAndEditsReachEveryStep() {
        val unnamed = catalog.copy(title = " ")
        assertEquals(WidgetSteps.DETAILS, WidgetSteps.blocker(unnamed, Check(titles = 3))!!.first)
        assertFalse(WidgetSteps.open(WidgetSteps.REVIEW, unnamed, Check(titles = 3), existing = false))
        assertTrue(WidgetSteps.open(WidgetSteps.REVIEW, empty, Check(), existing = true))
        assertTrue(WidgetSteps.showsSave(WidgetSteps.SOURCE, existing = true))
    }

    @Test fun sourcesBelongToTheirCards() {
        assertNull(WidgetSteps.kindOf(CollectionSource()))
        assertEquals(WidgetSteps.LISTS, WidgetSteps.kindOf(list.source))
        assertEquals(WidgetSteps.CATALOG, WidgetSteps.kindOf(catalog.source))
        assertEquals(WidgetSteps.FILTERS, WidgetSteps.kindOf(CollectionSource(provider = "ember")))
        assertEquals(5, WidgetSteps.kinds.size)
        assertEquals("12 titles found", WidgetSteps.status(catalog, Check(titles = 12)))
    }
}
