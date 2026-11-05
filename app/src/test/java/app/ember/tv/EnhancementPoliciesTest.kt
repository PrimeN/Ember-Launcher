package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.iptv.GuideWindow
import org.junit.Assert.*
import org.junit.Test

class EnhancementPoliciesTest {
    @Test fun reorderPreservesExcludedAndHiddenSlots() {
        assertEquals(listOf("c", "restricted", "a", "hidden", "b"),
            AppOrder.merge(listOf("a", "restricted", "b", "hidden", "c"), listOf("c", "a", "b")))
    }

    @Test fun reorderHandlesRemovalAndDuplicateDraftItems() {
        assertEquals(listOf("b", "new", "a"), AppOrder.merge(listOf("a", "new", "b"), listOf("b", "gone", "b", "a")))
        assertEquals(listOf("a", "b"), AppOrder.move(listOf("a", "b"), 0, -1))
        assertEquals(listOf("b", "a"), AppOrder.move(listOf("a", "b"), 0, 1))
    }

    @Test fun failedRefreshRetainsTimestampAndDistinguishesSavedContent() {
        val health = SourceHealth()
        health.start("source", "Source")
        health.finish("source", success = true, cached = false, now = 100)
        health.start("source", "Source")
        health.finish("source", success = false, cached = true, now = 200)
        val status = health.entries.getValue("source")
        assertEquals(100L, status.lastSuccess)
        assertTrue(status.failed)
        assertTrue(status.cached)
        assertFalse(status.loading)
        health.start("source", "Source")
        health.finish("source", success = true, cached = false, now = 300)
        assertEquals(300L, health.entries.getValue("source").lastSuccess)
        assertFalse(health.entries.getValue("source").failed)
    }

    @Test fun cancellingOneKindDoesNotStopAnotherKindsLoadingIndicator() {
        val health = SourceHealth()
        health.start("a:rows", "Rows"); health.start("a:libraries", "Libraries")
        health.cancelLoading(setOf("a:rows"))
        assertFalse(health.entries.getValue("a:rows").loading)
        assertTrue(health.entries.getValue("a:libraries").loading)
        health.retain(emptySet())
        health.finish("a:rows", true, false)
        assertTrue(health.entries.isEmpty())
    }

    @Test fun resumeUsesKnownEpisodeDurationAndSource() {
        val meta = Meta(name = "Example", progress = 0.5f, season = 2, episode = 5,
            runtime = "2h 10m", progressSource = "Trakt")
        assertEquals("Resume S2 E5 · 65 min left · Trakt", meta.resumeLabel())
        assertEquals("Resume S2 E5 · 65 min left · My server", meta.copy(serverName = "My server").resumeLabel())
        assertTrue(meta.spokenLabel(true).contains("On your watchlist"))
    }

    @Test fun resumeDoesNotInventDurationAndRejectsInvalidProgress() {
        val meta = Meta(progress = 0.4f)
        assertEquals("Resume · 40% watched", meta.resumeLabel())
        assertNull(meta.copy(progress = null).resumeLabel())
        assertNull(meta.copy(progress = Float.NaN).resumeLabel())
        assertNull(meta.copy(progress = 1.1f).resumeLabel())
    }

    @Test fun localPlaybackSuppliesMeasuredDurationForResumeCards() {
        LocalLibrary.resetForTest()
        LocalLibrary.started("test", Meta(id = "one", name = "Film"))
        LocalLibrary.progress("test", 0.5f, 90 * 60_000L)
        assertEquals("Resume · 45 min left · Ember", LocalLibrary.inProgress.single().resumeLabel())
        LocalLibrary.resetForTest()
    }

    @Test fun guideWindowAdvancesAfterOvernightSessionAndClockChanges() {
        val clock = 1_800_000L * 100 + 300_000L
        val start = GuideWindow.start(clock)
        assertTrue(clock in start until start + GuideWindow.HALF_HOUR)
        assertFalse(GuideWindow.expired(start, clock))
        assertTrue(GuideWindow.expired(start, start + GuideWindow.DURATION))
        assertTrue(GuideWindow.expired(start, start - 1))
        assertEquals(start, GuideWindow.start(start + GuideWindow.HALF_HOUR - 1))
    }

    @Test fun sparseOverlappingAndEmptyGuidesHaveIdenticalTimelineWidths() {
        val start = 100_000L
        val entries = listOf(
            app.ember.tv.iptv.Programme(start - 10, start + 40_000, "First"),
            app.ember.tv.iptv.Programme(start + 20_000, start + 60_000, "Overlap"),
            app.ember.tv.iptv.Programme(start + 80_000, start + 90_000, "After gap"),
            app.ember.tv.iptv.Programme(start + 50_000, start + 40_000, "Invalid"),
        )
        val slots = GuideWindow.slots(entries, start)
        assertEquals(GuideWindow.DURATION, slots.sumOf { it.to - it.from })
        assertTrue(slots.zipWithNext().all { (a, b) -> a.to == b.from })
        assertEquals(40_000L, slots.first { it.programme?.title == "Overlap" }.from - start)
        assertEquals(GuideWindow.DURATION, GuideWindow.slots(emptyList(), start).sumOf { it.to - it.from })
    }
}
