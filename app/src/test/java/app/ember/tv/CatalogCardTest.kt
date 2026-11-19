package app.ember.tv

import app.ember.tv.ui.CatalogBrowse
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogCardTest {
    @Test fun theCardLineNamesTheTypeAndTheAddonsNameOnlyWhenRenamed() {
        assertEquals("Movies", CatalogBrowse.cardLine("movie", "Popular", "Popular"))
        assertEquals("Series · was Top Series", CatalogBrowse.cardLine("series", "Weekend shows", "Top Series"))
        assertEquals("Live TV", CatalogBrowse.cardLine("tv", "News", ""))
    }
}
