package app.ember.tv

import app.ember.tv.HomeViewModel.HomeBlock
import app.ember.tv.data.CatalogRow
import app.ember.tv.data.LocalLibrary
import app.ember.tv.data.Meta
import app.ember.tv.data.Updater
import app.ember.tv.state.HomeSectionsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibraryAndHomeTest {
    private fun movie(i: Int) = Meta(id = "tt$i", type = "movie", name = "Movie $i")
    private val show = Meta(id = "tt900", type = "series", name = "A show")

    @Before
    fun reset() = LocalLibrary.resetForTest()

    @Test
    fun partWayIsInProgressAndTheEndIsWatched() {
        LocalLibrary.started("tt1", movie(1))
        LocalLibrary.progress("tt1", 0.4f)
        assertEquals(listOf("tt1"), LocalLibrary.inProgress.map { it.id })
        assertEquals(0.4f, LocalLibrary.inProgress.first().progress!!, 0.001f)
        LocalLibrary.progress("tt1", 0.95f)
        assertTrue(LocalLibrary.inProgress.isEmpty())
        assertEquals(listOf("tt1"), LocalLibrary.recent.map { it.id })
    }

    @Test
    fun watchingAgainStartsFromNothing() {
        LocalLibrary.started("tt1", movie(1))
        LocalLibrary.finished("tt1")
        LocalLibrary.started("tt1", movie(1))
        assertTrue("a finished title must not come back as 100% in progress", LocalLibrary.inProgress.isEmpty())
    }

    @Test
    fun onlyTheLastTenTitlesAreKept() {
        (1..15).forEach { i ->
            LocalLibrary.started("tt$i", movie(i))
            LocalLibrary.progress("tt$i", 0.5f)
        }
        assertEquals(LocalLibrary.MAX_TITLES, LocalLibrary.recent.size)
        assertEquals("tt15", LocalLibrary.recent.first().id)
        assertFalse(LocalLibrary.recent.any { it.id == "tt1" })
    }

    @Test
    fun aShowKeepsOnlyItsLatestEpisode() {
        LocalLibrary.started("tt900:1:1", show.copy(season = 1, episode = 1))
        LocalLibrary.progress("tt900:1:1", 0.97f)
        LocalLibrary.startedEpisode("tt900:1:1", "tt900:1:2", 1, 2, "Second")
        LocalLibrary.progress("tt900:1:2", 0.3f)
        val inProgress = LocalLibrary.inProgress
        assertEquals(1, inProgress.size)
        assertEquals(2, inProgress.first().episode)
        assertEquals(1, LocalLibrary.recent.size)
    }

    @Test
    fun hiddenAndClearedTitlesLeaveRecentlyWatched() {
        LocalLibrary.started("tt1", movie(1)); LocalLibrary.finished("tt1")
        LocalLibrary.started("tt2", movie(2)); LocalLibrary.progress("tt2", 0.5f)
        LocalLibrary.hideRecent(movie(1))
        assertTrue(LocalLibrary.isHidden(movie(1)))
        assertEquals(listOf("tt2"), LocalLibrary.recent.map { it.id })
        LocalLibrary.clearRecent(LocalLibrary.recent)
        // Still part-way through: stays in In progress.
        assertEquals(listOf("tt2"), LocalLibrary.inProgress.map { it.id })
    }

    @Test
    fun watchlistChangesWaitUntilSent() {
        LocalLibrary.queue(add = true, meta = movie(1))
        LocalLibrary.queue(add = false, meta = movie(1)) // the latest change wins
        assertEquals(1, LocalLibrary.pending.size)
        assertFalse(LocalLibrary.pending.first().add)
        LocalLibrary.done(LocalLibrary.pending.first())
        assertTrue(LocalLibrary.pending.isEmpty())
    }

    @Test
    fun buttonsNextToEachOtherShareOneLine() {
        val row = HomeBlock.Row("r", CatalogRow("", "Row", emptyList()))
        val blocks = listOf(
            HomeBlock.Hero,
            HomeBlock.Shortcuts("s1", listOf(HomeViewModel.HOME_RECENT)),
            HomeBlock.Shortcuts("s2", listOf(HomeViewModel.HOME_WATCHLIST)),
            row,
            HomeBlock.Shortcuts("s3", listOf(HomeViewModel.HOME_CONTINUE)),
        )
        val joined = HomeViewModel.joinShortcuts(blocks)
        assertEquals(4, joined.size)
        assertEquals(listOf(HomeViewModel.HOME_RECENT, HomeViewModel.HOME_WATCHLIST), (joined[1] as HomeBlock.Shortcuts).ids)
        assertEquals(listOf(HomeViewModel.HOME_CONTINUE), (joined[3] as HomeBlock.Shortcuts).ids)
    }

    @Test
    fun newRecentlyWatchedSectionGoesAfterInProgress() {
        val known = listOf("hero", "apps", "continue", "recent", "watchlist", "catalogs")
        val saved = listOf("apps" to true, "continue" to true, "catalogs" to true, "hero" to false)
        val merged = HomeSectionsState.merge(known, saved, setOf("recent", "watchlist"))
        assertEquals(listOf("apps", "continue", "recent", "watchlist", "catalogs", "hero"), merged.map { it.first })
        assertTrue(merged.first { it.first == "recent" }.second)
    }

    @Test
    fun buildNumberFromReleaseNotesOrApkName() {
        assertEquals(141, Updater.versionCodeOf("Built from abc.\nversionCode: 141", "x.apk"))
        assertEquals(139, Updater.versionCodeOf("", "ember-tv-0.9.0-139.apk"))
        assertNull(Updater.versionCodeOf("", "ember-tv-0.9.0-139-debug.apk"))
        assertTrue(Updater.newerName("0.10.0", "0.9.9"))
    }
}
