package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.iptv.*
import app.ember.tv.ui.RenderOptions
import org.junit.Assert.*
import org.junit.Test

class VisualRefinementsTest {
    @Test fun guideComposesVisibleTimeAndRetainsRemoteFocusOutsideIt() {
        val start = 1_800_000L * 100
        val shows = List(48) { i -> Programme(start + i * 900_000L, start + (i + 1) * 900_000L, "Show $i") }
        val slots = GuideWindow.slots(shows, start)
        val visible = GuideWindow.visible(slots, start + 3_600_000L, start + 5_400_000L)
        assertEquals(2, visible.size)
        val focused = GuideWindow.visible(slots, start + 3_600_000L, start + 5_400_000L, shows.last().start)
        assertEquals(3, focused.size)
        assertEquals(shows.last(), focused.last().programme)
    }
    @Test fun textScalingAndComfortableDensityReduceGridCrowding() {
        val comfortable = RenderOptions(density = "comfortable")
        val dense = RenderOptions(density = "dense")
        assertTrue(comfortable.columns(1000f, 1f) < dense.columns(1000f, 1f))
        assertTrue(dense.columns(1000f, 2f) < dense.columns(1000f, 1f))
        assertTrue(comfortable.count(true) < dense.count(true))
        assertEquals(0, RenderOptions(quality = "economy").prefetchCount)
        assertEquals(1, RenderOptions().prefetchCount)
        assertEquals(2, RenderOptions(quality = "high").prefetchCount)
    }
    @Test fun homeHidingIsScopedToProfileAndMediaTypeAndUsesNoRawIds() {
        val movie = Meta(id = "sensitive-provider-id", type = "movie", imdbId = "tt123")
        assertEquals(HomeTitleVisibility.key("a", movie), HomeTitleVisibility.key("a", movie.copy(id = "other")))
        assertNotEquals(HomeTitleVisibility.key("a", movie), HomeTitleVisibility.key("b", movie))
        assertNotEquals(HomeTitleVisibility.key("a", movie), HomeTitleVisibility.key("a", movie.copy(type = "series")))
        assertFalse(HomeTitleVisibility.key("a", movie).contains("tt123"))
        assertTrue(HomeTitleVisibility.key("a", movie).startsWith(HomeTitleVisibility.prefix("a")))
    }
    @Test fun successfulFreshnessSurvivesNewHealthInstanceAndFailureDoesNotAdvanceIt() {
        val saved = mutableMapOf<String, Long>()
        fun health() = SourceHealth(readSaved = { saved[it] }, saveSuccess = { id, time -> saved[id] = time })
        health().apply { start("source", "Source"); finish("source", true, false, 100) }
        val restarted = health()
        restarted.start("source", "Source")
        assertEquals(100L, restarted.entries.getValue("source").lastSuccess)
        restarted.finish("source", false, true, 200)
        assertEquals(100L, saved["source"])
    }
}
